package org.dyh.learnhub.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.dyh.learnhub.common.FileTextExtractedEvent;
import org.dyh.learnhub.entity.FileInfo;
import org.dyh.learnhub.entity.KnowledgeModelOutbox;
import org.dyh.learnhub.mapper.FileInfoMapper;
import org.dyh.learnhub.mapper.KnowledgeModelOutboxMapper;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.util.StringUtils;

import java.net.Proxy;
import java.net.ProxySelector;
import java.net.SocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Durable local-only bridge. Uploads only write a transactional outbox; a serial daemon
 * submits committed revisions to the trainer. Query requests never read document text.
 */
@Slf4j
@Service
public class KnowledgeModelService {
    private static final String DEFAULT_URL = "http://127.0.0.1:8766";
    private static final TypeReference<Map<String, Object>> MAP = new TypeReference<>() { };
    private final SettingsService settings;
    private final FileInfoMapper files;
    private final KnowledgeModelOutboxMapper outbox;
    private final ObjectMapper json;
    private final HttpClient client;
    private final AtomicBoolean draining = new AtomicBoolean();
    private final ScheduledExecutorService worker = Executors.newSingleThreadScheduledExecutor(task -> {
        Thread thread = new Thread(task, "knowledge-model-outbox");
        thread.setDaemon(true);
        return thread;
    });
    private volatile String enqueueError = "";

    public KnowledgeModelService(SettingsService settings, FileInfoMapper files,
                                 KnowledgeModelOutboxMapper outbox, ObjectMapper json) {
        this.settings = settings;
        this.files = files;
        this.outbox = outbox;
        this.json = json;
        this.client = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(Duration.ofSeconds(2))
                .followRedirects(HttpClient.Redirect.NEVER)
                .proxy(new ProxySelector() {
                    @Override public List<Proxy> select(URI uri) { return List.of(Proxy.NO_PROXY); }
                    @Override public void connectFailed(URI uri, SocketAddress address, java.io.IOException error) { }
                }).build();
    }

    @PostConstruct
    public void start() {
        // Polling the outbox also resumes committed-but-not-yet-sent uploads after restart.
        worker.scheduleWithFixedDelay(this::drainSafely, 2, 5, TimeUnit.SECONDS);
    }

    @PreDestroy
    public void stop() { worker.shutdownNow(); }

    public boolean enabled() {
        String value = settings.effective(SettingsService.KEY_KNOWLEDGE_MODEL_ENABLED);
        return "1".equals(value) || "true".equalsIgnoreCase(value);
    }

    /**
     * Synchronous database-only listener shares the uploading transaction, so rollback
     * removes the outbox revision too. Never make an HTTP/training call on this path.
     * A trainer/outbox failure must not make an otherwise successful upload fail.
     */
    @EventListener
    public void captureExtractedText(FileTextExtractedEvent event) {
        try {
            if (!enabled()) return;
            persist(event.fileId(), event.title(), event.text());
            enqueueError = "";
        } catch (Exception unavailable) {
            enqueueError = "资料已保存，训练入队失败，请手动重试该资料";
            log.warn("专用模型训练入队失败，资料 #{} 上传继续: {}", event.fileId(), unavailable.toString());
        }
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void afterExtractCommitted(FileTextExtractedEvent event) { wake(); }

    private KnowledgeModelOutbox persist(Long fileId, String title, String text) {
        if (fileId == null || fileId <= 0 || !StringUtils.hasText(text))
            throw new IllegalArgumentException("资料尚无可训练的正文，请先完成正文抽取");
        KnowledgeModelOutbox row = new KnowledgeModelOutbox();
        row.setFileId(fileId);
        row.setTitle(StringUtils.hasText(title) ? title : "资料 " + fileId);
        row.setTextContent(text);
        row.setRevision(revision(text));
        outbox.insertRevision(row);
        return outbox.findRevision(fileId, row.getRevision());
    }

    /** Explicit enrollment is permitted while automatic enrollment is disabled. */
    @Transactional
    public Map<String, Object> enqueueFile(Long fileId) {
        FileInfo file = files.selectById(fileId);
        if (file == null) throw new IllegalArgumentException("资料不存在");
        if (!"ok".equals(file.getTextStatus()))
            throw new IllegalArgumentException("资料尚未成功抽取正文，不能训练");
        KnowledgeModelOutbox row = persist(file.getId(), file.getOriginName(), file.getTextContent());
        // Polling observes this transaction only after commit, even if this wake runs early.
        wake();
        return describe(row);
    }

    public Map<String, Object> setEnabled(boolean enabled) {
        settings.update(SettingsService.KEY_KNOWLEDGE_MODEL_ENABLED, enabled ? "1" : "0");
        return status();
    }

    public Map<String, Object> status() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("enabled", enabled());
        result.put("baseUrl", baseUri().toString());
        result.put("outbox", summary());
        result.put("privacy", Map.of("trainingUsesUploadedText", true, "queryUsesDocuments", false));
        try {
            result.put("service", request("GET", "/v1/status", null, 5));
            result.put("online", true);
            result.put("error", "");
        } catch (ServiceUnavailableException unavailable) {
            result.put("service", Map.of());
            result.put("online", false);
            result.put("error", unavailable.getMessage());
        }
        return result;
    }

    public Map<String, Object> jobs() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("outbox", outbox.recent().stream().map(KnowledgeModelService::describe).toList());
        try {
            result.put("service", request("GET", "/v1/jobs", null, 5));
            result.put("online", true);
            result.put("error", "");
        } catch (ServiceUnavailableException unavailable) {
            result.put("service", Map.of());
            result.put("online", false);
            result.put("error", unavailable.getMessage());
        }
        return result;
    }

    public Map<String, Object> retry(Long id) {
        KnowledgeModelOutbox row = outbox.findById(id);
        if (row == null) throw new IllegalArgumentException("训练任务不存在");
        if (StringUtils.hasText(row.getRemoteJobId())) {
            if (!row.getRemoteJobId().matches("[A-Za-z0-9_-]{1,128}"))
                throw new IllegalArgumentException("训练服务返回了不合法的任务编号");
            request("POST", "/v1/jobs/" + row.getRemoteJobId() + "/retry", Map.of(), 15);
        } else {
            outbox.retry(id);
            wake();
        }
        return describe(outbox.findById(id));
    }

    public Map<String, Object> query(String question, Integer maxTokens) {
        if (!StringUtils.hasText(question) || question.length() > 5000)
            throw new IllegalArgumentException("问题不能为空，且不能超过 5000 字符");
        if (maxTokens != null && (maxTokens < 1 || maxTokens > 512))
            throw new IllegalArgumentException("max_tokens 须在 1～512 之间");
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("question", question.trim());
        if (maxTokens != null) body.put("max_tokens", maxTokens);
        return request("POST", "/v1/query", body, 120);
    }

    private Map<String, Object> summary() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("pending", 0L);
        result.put("retry", 0L);
        result.put("submitted", 0L);
        long total = 0;
        for (Map<String, Object> count : outbox.counts()) {
            long value = ((Number) count.get("total")).longValue();
            result.put(String.valueOf(count.get("state")), value);
            total += value;
        }
        result.put("total", total);
        result.put("enqueueError", enqueueError);
        return result;
    }

    private void wake() {
        if (!worker.isShutdown()) worker.execute(this::drainSafely);
    }

    private void drainSafely() {
        try { drainOutbox(); }
        catch (Exception unavailable) { log.warn("专用模型训练队列暂不可用，将自动重试: {}", unavailable.toString()); }
    }

    /** Package-visible for a synthetic offline/restart test; production uses one serial daemon. */
    void drainOutbox() {
        if (!draining.compareAndSet(false, true)) return;
        try {
            // A bounded pass prevents uploads from starving periodic polling during a burst.
            for (int remaining = 20; remaining > 0; remaining--) {
                KnowledgeModelOutbox row = outbox.nextDue();
                if (row == null) return;
                try {
                    Map<String, Object> response = request("POST", "/v1/documents", Map.of(
                            "source_id", "file:" + row.getFileId(), "title", row.getTitle(),
                            "text", row.getTextContent(), "revision", row.getRevision()), 15);
                    Object id = response.get("job_id");
                    if (id == null) id = response.get("id");
                    if (id == null && response.get("job") instanceof Map<?, ?> job) id = job.get("id");
                    if (id == null || !id.toString().matches("[A-Za-z0-9_-]{1,128}"))
                        throw new ServiceUnavailableException("训练服务未返回有效任务编号");
                    outbox.submitted(row.getId(), id.toString());
                } catch (Exception unavailable) {
                    int attempts = row.getAttempts() == null ? 0 : row.getAttempts();
                    int delay = Math.min(300, 5 * (1 << Math.min(attempts, 6)));
                    outbox.failed(row.getId(), truncate(unavailable.getMessage(), 1000), delay);
                    // Offline means every pending revision would fail; preserve order and back off.
                    return;
                }
            }
        } finally { draining.set(false); }
    }

    /** No DNS names except localhost; normalize localhost before connecting and never follow redirects. */
    static URI validateBaseUrl(String raw) {
        try {
            URI uri = URI.create(StringUtils.hasText(raw) ? raw.trim() : DEFAULT_URL);
            String host = uri.getHost();
            boolean ipv6 = "::1".equals(host) || "[::1]".equals(host);
            if (!("http".equals(uri.getScheme()) || "https".equals(uri.getScheme()))
                    || !("localhost".equalsIgnoreCase(host) || "127.0.0.1".equals(host) || ipv6)
                    || uri.getUserInfo() != null || uri.getQuery() != null || uri.getFragment() != null
                    || !(uri.getPath().isEmpty() || "/".equals(uri.getPath()))
                    || uri.getPort() == 0 || uri.getPort() > 65535)
                throw new IllegalArgumentException("专用模型服务地址必须是本机回环 HTTP(S) 地址，且不能带路径或凭据");
            return new URI(uri.getScheme(), null, ipv6 ? "::1" : "127.0.0.1", uri.getPort(), "", null, null);
        } catch (IllegalArgumentException invalid) {
            throw invalid;
        } catch (Exception invalid) {
            throw new IllegalArgumentException("专用模型服务地址不合法");
        }
    }

    private URI baseUri() { return validateBaseUrl(settings.effective(SettingsService.KEY_KNOWLEDGE_MODEL_BASE_URL)); }

    private Map<String, Object> request(String method, String path, Object body, int timeoutSeconds) {
        // Keep validation outside the transport catch: an unsafe configuration is a client error.
        URI target = baseUri().resolve(path);
        try {
            HttpRequest.Builder request = HttpRequest.newBuilder(target).timeout(Duration.ofSeconds(timeoutSeconds))
                    .header("Accept", "application/json");
            if ("POST".equals(method)) request.header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body), StandardCharsets.UTF_8));
            else request.GET();
            HttpResponse<String> response = client.send(request.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                // Do not echo arbitrary trainer response bodies or document text into UI/logs.
                throw new ServiceUnavailableException("本地训练服务返回 HTTP " + response.statusCode());
            }
            return json.readValue(response.body(), MAP);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new ServiceUnavailableException("本地训练服务请求已中断");
        } catch (ServiceUnavailableException unavailable) {
            throw unavailable;
        } catch (Exception unavailable) {
            throw new ServiceUnavailableException("无法连接本地训练服务，已入队的资料将自动重试");
        }
    }

    static String revision(String text) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8))); }
        catch (Exception impossible) { throw new IllegalStateException(impossible); }
    }

    private static String truncate(String value, int max) {
        if (value == null) return "本地训练服务暂不可用";
        return value.length() > max ? value.substring(0, max) : value;
    }

    private static Map<String, Object> describe(KnowledgeModelOutbox row) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("id", row.getId()); result.put("fileId", row.getFileId()); result.put("title", row.getTitle());
        result.put("revision", row.getRevision()); result.put("state", row.getState()); result.put("attempts", row.getAttempts());
        result.put("remoteJobId", row.getRemoteJobId()); result.put("lastError", row.getLastError());
        result.put("createdAt", row.getCreatedAt()); result.put("updatedAt", row.getUpdatedAt());
        result.put("nextAttemptAt", row.getNextAttemptAt());
        return result;
    }

    public static class ServiceUnavailableException extends RuntimeException {
        public ServiceUnavailableException(String message) { super(message); }
    }
}
