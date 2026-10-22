package org.dyh.learnhub.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.dyh.learnhub.ai.DeepSeekClient;
import org.dyh.learnhub.ai.EmbeddingClient;
import org.dyh.learnhub.ai.ModelRouting;
import org.dyh.learnhub.entity.AppSetting;
import org.dyh.learnhub.mapper.AppSettingMapper;
import org.dyh.learnhub.mapper.KnowledgeMaintenanceSourceMapper;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

import static org.dyh.learnhub.service.KnowledgeMaintenanceService.StepResult;

/** Real operations behind the serial scheduler. Every job is observed until its actual result. */
@Service
public class KnowledgeMaintenanceOperations implements KnowledgeMaintenanceService.Operations {
    static final String ENTITY_REVISION = "kb.maintenance.entity_revision";
    static final int WIKI_BATCH = 3;
    private final KnowledgeMaintenanceSourceMapper sources;
    private final AppSettingMapper settingsStore;
    private final KnowledgeBaseSettings configuration;
    private final EmbeddingClient embedding;
    private final ModelRouting routing;
    private final DeepSeekClient client;
    private final ObjectMapper json;
    private final VectorIndexService index;
    private final WikiService wiki;
    private final EntityCompileService entities;
    private final KgMaintenanceService concepts;
    private final KgPipelineService pipeline;
    private final KgService documents;
    private final KgGraphService graph;
    private final KgCommunityService communities;
    private final KgCommunitySummaryService summaries;
    private final LintService lint;
    private volatile Input input;
    private record Input(String fingerprint, int readableSources) { }
    // These are queue decisions, never page freshness. A changed original-source version invalidates them.
    private String wikiMaterialRevision = "";
    private final Set<String> checkedWikiMaterials = new LinkedHashSet<>();
    private final Map<String, String> unavailableWikiMaterials = new LinkedHashMap<>();
    private String lastWikiAttempt = "";

    public KnowledgeMaintenanceOperations(KnowledgeMaintenanceSourceMapper sources, AppSettingMapper settingsStore,
            KnowledgeBaseSettings configuration, EmbeddingClient embedding, ModelRouting routing,
            DeepSeekClient client, ObjectMapper json, VectorIndexService index, WikiService wiki,
            EntityCompileService entities, KgMaintenanceService concepts, KgPipelineService pipeline,
            KgService documents, KgGraphService graph, KgCommunityService communities,
            KgCommunitySummaryService summaries, LintService lint) {
        this.sources = sources; this.settingsStore = settingsStore; this.configuration = configuration;
        this.embedding = embedding; this.routing = routing; this.client = client; this.json = json;
        this.index = index; this.wiki = wiki; this.entities = entities; this.concepts = concepts;
        this.pipeline = pipeline; this.documents = documents; this.graph = graph;
        this.communities = communities; this.summaries = summaries; this.lint = lint;
    }

    @Override public String fingerprint() {
        List<Map<String, Object>> rows = sources.sources();
        Map<String, Object> identity = new LinkedHashMap<>();
        identity.put("version", "maintenance-v1");
        identity.put("sources", canonical(rows));
        identity.put("categories", canonical(sources.categories()));
        identity.put("tags", canonical(sources.tags()));
        identity.put("noteTags", canonical(sources.noteTags()));
        var config = configuration.snapshot();
        Map<String, Object> parameters = new LinkedHashMap<>();
        for (var field : KnowledgeBaseSettings.fields()) if (field.requiresRebuild()
                || List.of("autoIndex", "vectorEnabled", "vectorBackend", "milvusUri").contains(field.key()))
            parameters.put(field.key(), config.values().get(field.key()));
        identity.put("index", parameters);
        var target = embedding.snapshot();
        identity.put("embedding", target == null ? "unconfigured" : List.of(target.configured(), target.spaceFingerprint()));
        identity.put("wikiEnabled", wiki.autoRefreshEnabled());
        List<Object> models = new ArrayList<>();
        for (String task : List.of(ModelRouting.TASK_WIKI, ModelRouting.TASK_ENTITY, ModelRouting.TASK_TRIPLE,
                ModelRouting.TASK_GRAPH, ModelRouting.TASK_COMMUNITY, ModelRouting.TASK_LINT)) {
            var model = routing.forTask(task);
            // Never persist a credential or use credential text as a model identity.
            models.add(List.of(task, text(model.id()), text(model.baseUrl()), text(model.model()),
                    model.separate(), !text(model.apiKey()).isBlank(), client.maxTokensOf(model.id())));
        }
        identity.put("models", models);
        String revision = sha(serialize(identity));
        int readable = (int) rows.stream().filter(row -> number(row.get("contentChars")) > 0
                && (!"file".equals(text(row.get("sourceType"))) || "ok".equals(text(row.get("textStatus"))))).count();
        input = new Input(revision, readable);
        return revision;
    }

    @Override public StepResult execute(String step) throws Exception {
        if (input == null) fingerprint();
        return switch (step) {
            case "index" -> maintainIndex();
            case "wiki" -> maintainWiki();
            case "graph" -> maintainGraph();
            case "documents" -> maintainDocuments();
            case "communities" -> maintainCommunities();
            case "summaries" -> maintainSummaries();
            case "check" -> maintainCheck();
            default -> throw new IllegalArgumentException("未知维护步骤: " + step);
        };
    }

    private StepResult maintainIndex() throws Exception {
        var cfg = configuration.snapshot();
        if (!cfg.vectorEnabled() || !cfg.autoIndex()) return done("自动向量索引已关闭，关键词检索仍可用");
        Map<String, Object> state = index.status();
        if (!Boolean.TRUE.equals(state.get("configured"))) return input.readableSources() == 0
                ? done("暂无待索引原文") : waiting(text(state.get("reason")));
        if (Boolean.TRUE.equals(state.get("embeddingChanged"))) {
            await(index.startRebuild(), index::jobView);
        } else {
            Map<String, Object> result = index.reindexAutomatically();
            if (Boolean.TRUE.equals(result.get("skipped"))) return waiting(text(result.get("reason")));
        }
        if (Boolean.TRUE.equals(index.status().get("stale")) && input.readableSources() > 0)
            return waiting("索引尚有变化，下一轮继续同步");
        if ("milvus".equalsIgnoreCase(cfg.vectorBackend())) {
            Map<String, Object> backend = index.vectorBackendStatus();
            if (!Boolean.TRUE.equals(backend.get("annReady")) || !Boolean.TRUE.equals(backend.get("consistent")))
                index.syncToMilvus();
        }
        return done("索引已同步");
    }

    private StepResult maintainWiki() throws Exception {
        if (input.readableSources() == 0) return done("暂无可生成知识页的原文，已有页面保留供核对");
        String revision = input.fingerprint();
        AppSetting compiled = settingsStore.selectById(ENTITY_REVISION);
        if (compiled == null || !revision.equals(compiled.getSettingValue())) {
            Map<String, Object> result = await(entities.startAutomatically(), entities::view);
            if (Boolean.FALSE.equals(result.get("automatic"))) return waiting("已有手动编译任务，完成后继续自动补齐");
            if (!revision.equals(fingerprint())) return waiting("编译期间原文已更新，将继续处理新版本");
            saveEntityRevision(revision);
        }
        List<Map<String, Object>> candidates = wiki.topics().stream().filter(this::needsWiki)
                .sorted(Comparator.comparing(row -> text(row.get("topicKey")))).toList();
        if (!revision.equals(wikiMaterialRevision)) {
            checkedWikiMaterials.clear();
            unavailableWikiMaterials.clear();
            wikiMaterialRevision = revision;
        }
        List<String> unchecked = candidates.stream().filter(row -> "entity".equals(text(row.get("topicType"))))
                .map(row -> text(row.get("topicKey"))).filter(key -> !checkedWikiMaterials.contains(key)).toList();
        if (!unchecked.isEmpty()) {
            unavailableWikiMaterials.putAll(entities.unavailableRegenerationMaterials(unchecked));
            checkedWikiMaterials.addAll(unchecked);
        }
        // Wrap after the last attempted key, so one failed page cannot monopolise every retry batch.
        List<Map<String, Object>> eligible = candidates.stream()
                .filter(row -> !unavailableWikiMaterials.containsKey(text(row.get("topicKey")))).toList();
        List<Map<String, Object>> ordered = new ArrayList<>();
        for (Map<String, Object> topic : eligible)
            if (text(topic.get("topicKey")).compareTo(lastWikiAttempt) > 0) ordered.add(topic);
        for (Map<String, Object> topic : eligible)
            if (text(topic.get("topicKey")).compareTo(lastWikiAttempt) <= 0) ordered.add(topic);
        int processed = 0;
        int attempted = 0;
        List<String> failures = new ArrayList<>();
        for (Map<String, Object> topic : ordered) {
            if (attempted >= WIKI_BATCH) break;
            String key = text(topic.get("topicKey"));
            attempted++;
            lastWikiAttempt = key;
            try {
                await(wiki.startGenerate(key, null), wiki::jobView);
                processed++;
            } catch (InterruptedException interrupted) {
                throw interrupted;
            } catch (Exception failure) {
                failures.add(key + "：" + KnowledgeMaintenanceService.failureReason(failure));
            }
        }
        List<Map<String, Object>> after = wiki.topics().stream().filter(this::needsWiki).toList();
        long preserved = after.stream().filter(row -> unavailableWikiMaterials.containsKey(text(row.get("topicKey")))).count();
        long remaining = after.size() - preserved;
        String message = "本轮更新 " + processed + " 页" + (remaining > 0 ? "，还有 " + remaining + " 页待更新" : "，可更新知识页已同步")
                + (preserved == 0 ? "" : "；保留 " + preserved + " 页：当前缺少相关的有效正文片段，来源变化后重新检查");
        if (!failures.isEmpty()) throw new IllegalStateException(message + "；" + failures.size() + " 页暂时失败，将继续轮换重试（"
                + String.join("；", failures) + "）");
        return new StepResult(remaining == 0, message);
    }

    private boolean needsWiki(Map<String, Object> topic) {
        String type = text(topic.get("topicType"));
        if (!List.of("category", "tag", "entity").contains(type)) return false;
        if (!"entity".equals(type) && !wiki.autoRefreshEnabled()) return false;
        if (!"entity".equals(type) && number(topic.get("itemCount")) <= 0) return false;
        return !Boolean.TRUE.equals(topic.get("generated")) || Boolean.TRUE.equals(topic.get("stale"));
    }

    private StepResult maintainGraph() {
        if (pipeline.hasRunningJobs()) return waiting("已有概念图谱任务，完成后继续维护");
        Map<String, Object> result = concepts.maintain(3);
        return new StepResult(Boolean.TRUE.equals(result.get("complete")), text(result.get("message")));
    }

    private StepResult maintainDocuments() {
        Map<String, Object> result = documents.maintainSemantic();
        return done("文档关联已更新，维护 " + number(result.get("edges")) + " 条关系");
    }

    private StepResult maintainCommunities() {
        if (pipeline.hasRunningJobs()) return waiting("等待概念任务完成后更新社区");
        pipeline.relinkWikiPages();
        graph.reason();
        communities.recompute(false);
        var cfg = configuration.snapshot();
        var target = embedding.snapshot();
        if (cfg.vectorEnabled() && cfg.autoIndex() && target != null && target.configured()) {
            Map<String, Object> vectors = pipeline.refreshEntityEmbeddings();
            if (Boolean.FALSE.equals(vectors.get("configured")) || number(vectors.get("failed")) > 0)
                throw new IllegalStateException("图谱实体向量尚未同步，将自动重试");
        }
        return done("关联、规则推理和社区划分已更新");
    }

    private StepResult maintainSummaries() {
        var status = summaries.status();
        if (number(status.get("missing")) + number(status.get("stale")) == 0) return done("社区摘要已同步");
        Map<String, Object> result = summaries.summarize(3);
        if (number(result.get("failed")) > 0) throw new IllegalStateException("部分社区摘要生成失败，稍后自动重试");
        var after = summaries.status();
        int remaining = number(after.get("missing")) + number(after.get("stale"));
        return new StepResult(remaining == 0, remaining == 0 ? "社区摘要已同步" : "还有 " + remaining + " 个社区摘要待生成");
    }

    private StepResult maintainCheck() {
        if (input.readableSources() == 0) return done("暂无原文，跳过模型自检");
        Map<String, Object> result = lint.run();
        if (Boolean.FALSE.equals(result.get("modelCheckSucceeded")))
            throw new IllegalStateException("代码自检已完成，语义自检失败，稍后自动重试");
        return done("知识页自检已完成，请在 Wiki 查看检查报告");
    }

    private void saveEntityRevision(String revision) {
        AppSetting row = new AppSetting(); row.setSettingKey(ENTITY_REVISION); row.setSettingValue(revision);
        if (settingsStore.selectById(ENTITY_REVISION) == null) settingsStore.insert(row);
        else settingsStore.updateById(row);
    }

    static Map<String, Object> await(Map<String, Object> initial, Function<String, Map<String, Object>> status)
            throws InterruptedException {
        String id = text(initial.get("jobId"));
        if (id.isBlank()) throw new IllegalStateException("维护任务没有返回任务编号");
        long deadline = System.nanoTime() + Duration.ofMinutes(20).toNanos();
        Map<String, Object> current = initial;
        while (true) {
            if ("done".equals(current.get("status"))) return current;
            if (List.of("failed", "cancelled", "error").contains(text(current.get("status"))))
                throw new IllegalStateException("维护任务未完成：" + text(current.get("error")));
            if (Thread.currentThread().isInterrupted()) throw new InterruptedException("后台维护已停止");
            if (System.nanoTime() >= deadline) throw new IllegalStateException("维护任务仍在运行，稍后核对后继续");
            Thread.sleep(250);
            current = status.apply(id);
        }
    }

    private String serialize(Object value) {
        try { return json.writeValueAsString(value); }
        catch (Exception invalid) { throw new IllegalStateException("无法识别知识库版本", invalid); }
    }
    private List<String> canonical(List<Map<String, Object>> rows) {
        return rows.stream().map(row -> serialize(new java.util.TreeMap<>(row))).sorted().toList();
    }
    private static String sha(String value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); }
        catch (Exception impossible) { throw new IllegalStateException(impossible); }
    }
    private static StepResult done(String message) { return new StepResult(true, message); }
    private static StepResult waiting(String message) { return new StepResult(false, message.isBlank() ? "等待模型配置" : message); }
    private static String text(Object value) { return value == null ? "" : String.valueOf(value); }
    private static int number(Object value) { return value instanceof Number n ? n.intValue() : 0; }
}
