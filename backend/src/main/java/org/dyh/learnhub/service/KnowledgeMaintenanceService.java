package org.dyh.learnhub.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PreDestroy;
import org.dyh.learnhub.common.KnowledgeChangedEvent;
import org.dyh.learnhub.entity.AppSetting;
import org.dyh.learnhub.mapper.AppSettingMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;

/** One server-owned maintenance queue; UI reads never execute maintenance or refresh a fingerprint. */
@Service
public class KnowledgeMaintenanceService {
    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(KnowledgeMaintenanceService.class);
    public interface Operations {
        String fingerprint();
        StepResult execute(String step) throws Exception;
    }
    public record StepResult(boolean complete, String message) {}
    public interface Store {
        String load();
        void save(String state);
    }

    static final String SETTING_KEY = "internal.knowledge_maintenance";
    static final long SCAN_MS = 10_000;
    static final long DEBOUNCE_MS = 2_000;
    private static final List<String> ORDER = List.of("index", "wiki", "graph", "documents", "communities", "summaries", "check");
    private static final List<String> LABELS = List.of("更新检索索引", "整理知识 Wiki", "构建知识图谱", "更新文档关联",
            "整理图谱社区", "生成社区摘要", "检查知识库状态");
    private static final Map<String, List<String>> DEPENDENCIES = Map.of(
            "communities", List.of("wiki", "graph"),
            "summaries", List.of("communities"),
            "check", List.of("wiki"));
    private final Operations operations;
    private final Store store;
    private final ObjectMapper json;
    private final LongSupplier clock;
    private final Map<String, String> completed = new LinkedHashMap<>();
    private final Map<String, Step> steps = new LinkedHashMap<>();
    private boolean enabled = true, running, pending = true, restored, manualQueued, signalQueued, stateDirty;
    private long dirtyVersion, pauseVersion, debounceUntil, nextEligibleAt;
    private String phase = "queued", message = "等待后台首次扫描", lastStartedAt = "", lastCompletedAt = "",
            lastError = "", completedFingerprint = "", observedFingerprint = "", manualGraphRevision = "",
            pendingGraphRevision = "";
    private ScheduledExecutorService scheduler;

    private static final class Step {
        final String key, label;
        String status = "pending", message = "等待维护", error = "";
        int failures;
        long retryAt;
        Step(String key, String label) { this.key = key; this.label = label; }
    }

    @Autowired
    public KnowledgeMaintenanceService(Operations operations, AppSettingMapper mapper, ObjectMapper json) {
        this(operations, new MapperStore(mapper), json, System::currentTimeMillis, false);
    }

    /** Deterministic embedding/testing constructor; scheduling starts only on ApplicationReadyEvent. */
    public KnowledgeMaintenanceService(Operations operations, Store store, LongSupplier clock) {
        this(operations, store, new ObjectMapper(), clock, true);
    }

    private KnowledgeMaintenanceService(Operations operations, Store store, ObjectMapper json, LongSupplier clock,
                                        boolean restoreNow) {
        this.operations = operations;
        this.store = store;
        this.json = json;
        this.clock = clock;
        for (int i = 0; i < ORDER.size(); i++) steps.put(ORDER.get(i), new Step(ORDER.get(i), LABELS.get(i)));
        if (restoreNow) restore();
    }

    @EventListener(ApplicationReadyEvent.class)
    public synchronized void start() {
        if (scheduler != null) return;
        scheduler = Executors.newSingleThreadScheduledExecutor(task -> {
            Thread worker = new Thread(task, "knowledge-maintenance");
            worker.setDaemon(true);
            return worker;
        });
        scheduler.scheduleWithFixedDelay(this::scan, 0, SCAN_MS, TimeUnit.MILLISECONDS);
    }

    @PreDestroy
    public synchronized void close() {
        if (scheduler != null) scheduler.shutdownNow();
    }

    public synchronized boolean enabled() { return enabled; }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public synchronized void changed(KnowledgeChangedEvent event) {
        dirtyVersion++;
        pending = true;
        debounceUntil = clock.getAsLong() + DEBOUNCE_MS;
        nextEligibleAt = debounceUntil;
        if (!running && enabled) { phase = "queued"; message = "来源已变更，等待合并连续修改后维护"; }
    }

    /** Called only after an explicit graph edit succeeds; generated graph output never increments this token. */
    public synchronized void graphChanged() {
        String revision = UUID.randomUUID().toString();
        try {
            if (!restored) restore();
            manualGraphRevision = revision;
            changed(null);
            stateDirty = true;
            persist();
        } catch (Exception unavailable) {
            if (!restored) {
                pendingGraphRevision = revision;
                changed(null);
            }
            stateDirty = true;
            lastError = "手工图谱变更已标记，维护状态暂未保存，将在后台重试";
        }
    }

    public synchronized Map<String, Object> setEnabled(boolean value) {
        if (!restored) restore();
        enabled = value;
        if (!value) {
            pauseVersion++;
            if (!running) { phase = "paused"; message = "自动维护已暂停"; }
            else message = "将在当前步骤结束后暂停";
        } else {
            pending = true;
            nextEligibleAt = clock.getAsLong();
            if (!running) { phase = "queued"; message = "自动维护已开启，等待后台扫描"; }
        }
        persist();
        if (value) signal();
        return status();
    }

    public synchronized Map<String, Object> requestRun() {
        if (!restored) restore();
        manualQueued = true;
        pending = true;
        // An explicit retry may bypass the automatic backoff, while retaining successful watermarks.
        for (Step step : steps.values()) step.retryAt = 0;
        nextEligibleAt = clock.getAsLong();
        if (!running) { phase = "queued"; message = "维护任务已排队"; }
        signal();
        return status();
    }

    /** A read-only in-memory view. No database, fingerprint, or model calls. */
    public synchronized Map<String, Object> status() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("enabled", enabled); out.put("running", running); out.put("pending", pending);
        out.put("phase", phase); out.put("phaseLabel", phaseLabel());
        out.put("message", message); out.put("lastStartedAt", lastStartedAt); out.put("lastCompletedAt", lastCompletedAt);
        out.put("nextRunAt", (!enabled && !manualQueued && !running) ? "" : iso(Math.max(clock.getAsLong(), nextEligibleAt)));
        out.put("lastError", lastError);
        List<Map<String, Object>> views = new ArrayList<>();
        for (Step step : steps.values()) views.add(Map.of("key", step.key, "label", step.label,
                "status", step.status, "message", step.message, "error", step.error,
                "nextRetryAt", step.retryAt > 0 ? iso(step.retryAt) : ""));
        out.put("steps", List.copyOf(views));
        return Map.copyOf(out);
    }

    private String phaseLabel() {
        Step step = steps.get(phase);
        if (step != null) return step.label;
        return switch (phase) {
            case "paused" -> "已暂停";
            case "idle" -> "本轮维护完成";
            case "error" -> "等待重试";
            case "scan" -> "检查来源变化";
            default -> "等待维护";
        };
    }

    private synchronized void signal() {
        if (scheduler == null || scheduler.isShutdown() || signalQueued) return;
        signalQueued = true;
        scheduler.execute(() -> {
            synchronized (this) { signalQueued = false; }
            scan();
        });
    }

    /** One bounded pass, also used directly with a fake clock in tests. */
    void scan() {
        long version, pause;
        boolean manual;
        synchronized (this) {
            if (running) return;
            if (!restored) {
                try { restore(); }
                catch (Exception unavailable) { failScan("维护状态暂时无法读取"); return; }
            }
            if (stateDirty) {
                try { persist(); }
                catch (Exception unavailable) { failScan("维护状态暂时无法保存，将自动重试"); return; }
            }
            if (!enabled && !manualQueued) { phase = "paused"; message = "自动维护已暂停"; return; }
            long now = clock.getAsLong();
            if (!manualQueued && now < Math.max(nextEligibleAt, debounceUntil)) return;
            manual = manualQueued;
            manualQueued = false;
            version = dirtyVersion;
            pause = pauseVersion;
            running = true;
            phase = "scan";
            message = "正在核对原始来源与配置";
        }
        String fingerprint = null;
        boolean changed = false;
        try {
            fingerprint = fingerprint();
            synchronized (this) {
                if (!fingerprint.equals(observedFingerprint)) {
                    for (Step step : steps.values()) {
                        step.retryAt = 0;
                        step.failures = 0;
                        step.error = "";
                        if (!fingerprint.equals(completed.get(step.key))) {
                            step.status = "pending";
                            step.message = "当前来源等待维护";
                        }
                    }
                    observedFingerprint = fingerprint;
                    refreshErrors();
                }
            }
            for (Step step : steps.values()) {
                synchronized (this) {
                    if (mustPause(manual, pause)) break;
                    List<String> unmet = new ArrayList<>();
                    for (String prerequisite : DEPENDENCIES.getOrDefault(step.key, List.of()))
                        if (!fingerprint.equals(completed.get(prerequisite))) unmet.add(steps.get(prerequisite).label);
                    if (!unmet.isEmpty()) {
                        step.status = "waiting";
                        step.message = "等待前置阶段完成：" + String.join("、", unmet);
                        continue;
                    }
                    if (fingerprint.equals(completed.get(step.key))) {
                        step.status = "completed";
                        if (step.message.isBlank()) step.message = "当前来源已完成此阶段";
                        step.error = "";
                        step.failures = 0;
                        refreshErrors();
                        continue;
                    }
                    if (clock.getAsLong() < step.retryAt) continue;
                    step.status = "running";
                    step.message = "正在维护";
                    phase = step.key;
                    message = step.label;
                    lastStartedAt = iso(clock.getAsLong());
                }
                try {
                    StepResult result = operations.execute(step.key);
                    if (result == null) throw new IllegalStateException("missing result");
                    String current = fingerprint();
                    synchronized (this) {
                        if (!fingerprint.equals(current)) {
                            step.status = "pending";
                            step.message = "来源在维护期间发生变化，将重新核对";
                            dirtyVersion++;
                            debounceUntil = clock.getAsLong() + DEBOUNCE_MS;
                            changed = true;
                            break;
                        }
                        step.message = bounded(result.message());
                        step.failures = 0;
                        step.error = "";
                        refreshErrors();
                        step.retryAt = result.complete() ? 0 : clock.getAsLong() + SCAN_MS;
                        if (result.complete()) {
                            step.status = "completed";
                            String previous = completed.put(step.key, fingerprint);
                            try { persist(); }
                            catch (Exception saveFailure) {
                                if (previous == null) completed.remove(step.key); else completed.put(step.key, previous);
                                throw saveFailure;
                            }
                        } else {
                            step.status = "pending";
                            persist();
                        }
                    }
                } catch (Exception failure) {
                    synchronized (this) {
                        step.status = "error";
                        step.failures++;
                        long delay = Math.min(600_000L, 30_000L << Math.min(step.failures - 1, 5));
                        step.retryAt = clock.getAsLong() + delay;
                        step.error = failureReason(failure);
                        step.message = bounded(step.error + "；" + delay / 1000 + " 秒后自动重试");
                        refreshErrors();
                        log.warn("知识库自动维护失败 [{}] {}", step.key, step.error);
                        try { persist(); }
                        catch (Exception saveFailure) { lastError += "；重试状态暂未保存"; }
                    }
                    if (failure instanceof InterruptedException) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                }
            }
            String latest = fingerprint();
            synchronized (this) {
                boolean all = fingerprint.equals(latest) && !changed && dirtyVersion == version;
                for (String key : ORDER) all &= fingerprint.equals(completed.get(key));
                pending = !all || manualQueued;
                if (all) {
                    boolean different = !fingerprint.equals(completedFingerprint);
                    boolean clearError = !lastError.isBlank();
                    lastError = "";
                    if (different || clearError) {
                        completedFingerprint = fingerprint;
                        if (different) lastCompletedAt = iso(clock.getAsLong());
                        persist();
                    }
                }
                if (mustPause(manual, pause)) {
                    phase = "paused"; message = "自动维护已暂停";
                } else {
                    boolean failed = steps.values().stream().anyMatch(step -> "error".equals(step.status));
                    phase = pending ? failed ? "error" : "queued" : "idle";
                    message = pending ? failed ? "部分阶段暂未完成，将按各阶段计划自动重试" : "仍有维护阶段等待续作"
                            : "已完成当前启用的维护，无需重复生成";
                }
                long earliest = clock.getAsLong() + SCAN_MS;
                if (pending) {
                    earliest = Long.MAX_VALUE;
                    for (Step step : steps.values()) if (!fingerprint.equals(completed.get(step.key)))
                        earliest = Math.min(earliest, Math.max(clock.getAsLong() + SCAN_MS, step.retryAt));
                    if (earliest == Long.MAX_VALUE) earliest = clock.getAsLong() + SCAN_MS;
                }
                nextEligibleAt = Math.max(earliest, debounceUntil);
            }
        } catch (Exception failure) {
            synchronized (this) { failScan("维护扫描暂时失败（" + failure.getClass().getSimpleName() + "），将自动重试"); }
        } finally {
            synchronized (this) { running = false; }
        }
    }

    private boolean mustPause(boolean manual, long pause) {
        return pauseVersion != pause || (!enabled && !manual);
    }

    private String fingerprint() {
        String value = operations.fingerprint();
        if (value == null || value.isBlank()) throw new IllegalStateException("missing fingerprint");
        synchronized (this) {
            return manualGraphRevision.isBlank() ? value : value + "|manualGraphRevision=" + manualGraphRevision;
        }
    }

    private void failScan(String safeMessage) {
        pending = true;
        phase = "error";
        message = safeMessage;
        lastError = safeMessage;
        nextEligibleAt = clock.getAsLong() + 30_000;
        if (restored) {
            try { persist(); }
            catch (Exception saveFailure) { lastError += "；重试状态暂未保存"; }
        }
    }

    private synchronized void restore() {
        String raw = store.load();
        if (raw != null && !raw.isBlank()) {
            try {
                JsonNode state = json.readTree(raw);
                if (!state.isObject()) throw new IllegalArgumentException();
                enabled = state.path("enabled").isBoolean() ? state.path("enabled").asBoolean() : true;
                JsonNode marks = state.path("completed");
                for (String key : ORDER) if (marks.path(key).isTextual()) completed.put(key, marks.path(key).asText());
                completedFingerprint = state.path("completedFingerprint").asText("");
                observedFingerprint = state.path("observedFingerprint").asText(completedFingerprint);
                manualGraphRevision = state.path("manualGraphRevision").asText("");
                lastStartedAt = state.path("lastStartedAt").asText("");
                lastCompletedAt = state.path("lastCompletedAt").asText("");
                lastError = state.path("lastError").asText("");
                nextEligibleAt = Math.max(0, state.path("nextEligibleAt").asLong(0));
                for (Step step : steps.values()) {
                    JsonNode saved = state.path("steps").path(step.key);
                    step.failures = Math.max(0, Math.min(32, saved.path("failures").asInt(0)));
                    step.retryAt = Math.max(0, saved.path("retryAt").asLong(0));
                    String status = saved.path("status").asText("pending");
                    step.status = List.of("pending", "completed", "error", "waiting").contains(status) ? status : "pending";
                    step.message = bounded(saved.path("message").asText("等待后台核对"));
                    step.error = bounded(saved.path("error").asText("error".equals(step.status) ? step.message : ""));
                }
            } catch (Exception invalid) {
                completed.clear();
                completedFingerprint = "";
                lastError = "已保存的维护状态无效，将重新检查";
            }
        }
        restored = true;
        if (!pendingGraphRevision.isBlank()) {
            manualGraphRevision = pendingGraphRevision;
            pendingGraphRevision = "";
            stateDirty = true;
        }
        if (!enabled) { phase = "paused"; message = "自动维护已暂停"; }
    }

    private void persist() {
        try {
            Map<String, Object> state = new LinkedHashMap<>();
            state.put("version", 1); state.put("enabled", enabled); state.put("completed", new LinkedHashMap<>(completed));
            state.put("completedFingerprint", completedFingerprint);
            state.put("observedFingerprint", observedFingerprint);
            state.put("manualGraphRevision", manualGraphRevision);
            state.put("lastStartedAt", lastStartedAt); state.put("lastCompletedAt", lastCompletedAt);
            state.put("lastError", lastError); state.put("nextEligibleAt", nextEligibleAt);
            Map<String, Object> retryStates = new LinkedHashMap<>();
            for (Step step : steps.values()) retryStates.put(step.key, Map.of("failures", step.failures,
                    "retryAt", step.retryAt, "status", step.status, "message", step.message, "error", step.error));
            state.put("steps", retryStates);
            store.save(json.writeValueAsString(state));
            stateDirty = false;
        } catch (Exception failure) {
            stateDirty = true;
            throw new IllegalStateException("维护状态暂时无法保存");
        }
    }

    private void refreshErrors() {
        lastError = bounded(steps.values().stream().filter(step -> !step.error.isBlank())
                .map(step -> step.label + "：" + step.error).collect(java.util.stream.Collectors.joining("；")));
    }

    /** Expose domain explanations, never arbitrary provider/SQL response bodies or credentials. */
    static String failureReason(Throwable failure) {
        List<String> reasons = new ArrayList<>();
        Set<Throwable> visited = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
        for (Throwable cause = failure; cause != null && visited.add(cause); cause = cause.getCause()) {
            if (cause instanceof java.sql.SQLException sql) {
                String reason = switch (sql.getErrorCode()) {
                    case 1062 -> "数据库唯一键冲突，已有内容已保留";
                    case 1406 -> "数据库字段长度不足，已有内容已保留";
                    case 1205, 1213 -> "数据库写入锁冲突，将自动重试";
                    default -> "数据库操作失败，已有内容已保留";
                };
                reasons.add(reason + "（错误码 " + sql.getErrorCode() + "）");
                continue;
            }
            if (cause instanceof com.fasterxml.jackson.core.JsonProcessingException) {
                reasons.add("模型返回的数据格式不正确，已有内容已保留");
                break;
            }
            if (cause instanceof java.net.http.HttpTimeoutException || cause instanceof java.net.SocketTimeoutException) {
                reasons.add("模型请求超时");
                continue;
            }
            if (cause instanceof javax.net.ssl.SSLHandshakeException) {
                reasons.add("模型服务 TLS 握手失败，连接恢复后将自动重试");
                continue;
            }
            if (cause instanceof javax.net.ssl.SSLException) {
                reasons.add("模型服务的安全连接中断，连接恢复后将自动重试");
                continue;
            }
            if (cause instanceof java.net.UnknownHostException
                    || cause instanceof java.nio.channels.UnresolvedAddressException) {
                reasons.add("无法解析模型服务地址，网络恢复后将自动重试");
                continue;
            }
            if (cause instanceof java.net.ConnectException) {
                reasons.add("无法连接模型服务，网络恢复后将自动重试");
                continue;
            }
            String detail = cause.getMessage();
            if (detail == null || !detail.matches("(?s)^(?:图谱|概念|单个公式|实体|知识|维护|部分|本轮|Wiki|模型|原文|来源|没有与|暂无|自动|文档|社区|嵌入|向量|AI 未配置|未取得|未配置|读取|保存|无法|处理|(?:笔记|速查卡|资料)#[0-9]+).*")) continue;
            detail = detail.split("\\R", 2)[0]
                    .replaceAll("(?i)sk-[a-z0-9_-]+", "[已隐藏]")
                    .replaceAll("(?i)(?:bearer\\s+|(?:api[_ -]?key|password|token|secret)\\s*[:=]\\s*)[^\\s,;，；]+", "[已隐藏]")
                    .replaceAll("https?://[^\\s，；]+", "[服务地址]");
            detail = detail.substring(0, Math.min(220, detail.length()));
            if (!reasons.contains(detail)) reasons.add(detail);
        }
        return reasons.isEmpty() ? "处理失败，请稍后重试（" + failure.getClass().getSimpleName() + "）"
                : bounded(String.join("；原因：", reasons));
    }

    private static String bounded(String text) {
        if (text == null) return "";
        return text.substring(0, Math.min(500, text.length()));
    }
    private static String iso(long value) { return Instant.ofEpochMilli(value).toString(); }

    private static final class MapperStore implements Store {
        private final AppSettingMapper mapper;
        MapperStore(AppSettingMapper mapper) { this.mapper = mapper; }
        public String load() {
            AppSetting row = mapper.selectById(SETTING_KEY);
            return row == null ? null : row.getSettingValue();
        }
        public void save(String state) {
            AppSetting row = new AppSetting();
            row.setSettingKey(SETTING_KEY); row.setSettingValue(state);
            if (mapper.selectById(SETTING_KEY) == null) mapper.insert(row); else mapper.updateById(row);
        }
    }
}
