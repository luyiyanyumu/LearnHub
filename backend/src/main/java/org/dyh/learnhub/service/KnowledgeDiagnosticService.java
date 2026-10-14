package org.dyh.learnhub.service;

import lombok.RequiredArgsConstructor;
import org.dyh.learnhub.ai.EmbeddingClient;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Explicit, read-only inspection of the same production retrieval pipeline. Builtin data never enters storage. */
@Service
@RequiredArgsConstructor
public class KnowledgeDiagnosticService {
    private final KnowledgeRetrievalService retrieval;
    private final KnowledgeBaseSettings settings;
    private final VectorIndexService vectors;
    private final EmbeddingClient embedder;
    private final RetrievalContextService contexts;

    public record Request(String caseId, String question, String dataset, String mode, Integer topK,
                          Boolean includeWiki, Boolean includeGraph, Boolean includeContext) {}
    public record DiagnosticCase(String id, String name, String kind, String question, String description,
                                 List<String> expectedSourceRefs) {}

    private static final List<Map<String, Object>> CORPUS = List.of(
            document("note", 900001, "合成示例 · Java 线程池", "## 线程池参数\nThreadPoolExecutor 使用 corePoolSize 设置核心线程数，maximumPoolSize 设置最大线程数，workQueue 暂存等待任务。\n\n## 拒绝策略\n当工作队列已满且线程数达到最大值，新任务触发 RejectedExecutionHandler。CallerRunsPolicy 让提交任务的线程执行任务，AbortPolicy 抛出异常。"),
            document("note", 900002, "合成示例 · 事务与回滚", "## 事务边界\n事务保证一组数据库修改一起提交或一起回滚。Spring 的 @Transactional 默认通过代理生效，同一个对象内部直接调用另一方法可能绕过代理。\n\n## 回滚规则\n默认遇到 RuntimeException 或 Error 回滚，检查异常需通过 rollbackFor 配置。提交前抛出异常时，已执行的更新可以随事务撤销。"),
            document("file", 900003, "合成示例 · RAG 分块与检索", "## 分块\nRAG 将资料按标题和段落拆成片段。相邻片段保留 overlap 重叠，避免答案在切分边界丢失。过短片段缺少上下文，过长片段会稀释检索主题。\n\n## 向量检索\n语义向量可召回用词不同但意思接近的片段。余弦相似度用于比较查询与片段的向量方向，相似度不是答案正确率。"),
            document("quick_ref", 900004, "合成示例 · 融合与重排", "## RRF 融合\nReciprocal Rank Fusion（RRF）按各通道的排名投票，合并关键词与向量候选。重复的同一片段只计一次通道投票。\n\n## Rerank 重排\n重排判断候选片段对当前问题的相关性，再截取 topK。Rerank 排序分数不能当作事实正确的概率。"),
            document("note", 900005, "合成示例 · 页面主题", "## 界面设置\n青绿色按钮表示主要操作。页面可以在浅色主题和深色主题之间切换，窄屏时工具栏按可用宽度换行。")
    );
    private static final List<DiagnosticCase> CASES = List.of(
            new DiagnosticCase("exact-threadpool", "明确术语", "exact", "ThreadPoolExecutor corePoolSize maximumPoolSize workQueue", "检查明确 Java 线程池术语的召回。", List.of("note:900001")),
            new DiagnosticCase("semantic-backpressure", "换一种说法", "semantic", "并发任务太多，排队位置用完后怎样让提交者自己干活？", "同义描述对应 CallerRunsPolicy；未配置嵌入时可观察关键词能力边界。", List.of("note:900001")),
            new DiagnosticCase("transaction-rollback", "事务条件", "exact", "@Transactional rollbackFor 回滚检查异常", "检查事务回滚条件是否来自对应资料。", List.of("note:900002")),
            new DiagnosticCase("rag-pipeline", "跨来源问题", "multi-source", "RAG 分块 overlap 与 RRF 融合、Rerank 重排分别起什么作用？", "答案材料分布在两份合成来源；衡量来源覆盖。", List.of("file:900003", "quick_ref:900004")),
            new DiagnosticCase("uncovered-topic", "未覆盖主题", "negative", "火星火山岩矿物同位素的采样结果", "合成资料没有这一主题，检查是否仍返回无关候选；没有相关来源时不计算召回率。", List.of())
    );
    private static final Set<String> SAFE_INDEX_FIELDS = Set.of("chunks", "indexedChars", "indexedSources", "currentSources",
            "enabled", "configured", "compatibleChunks", "legacyChunks", "embeddingChanged", "annReady", "stale",
            "vectorBackend", "indexedAt", "sourceLatestChange");

    public Map<String, Object> cases() {
        return Map.of("cases", CASES, "corpusSummary", Map.of("sources", CORPUS.size(), "chars",
                CORPUS.stream().mapToInt(row -> row.get("content").toString().length()).sum(),
                "description", "5 份内置合成资料，只在本次诊断内存使用，不写入笔记、资料、Wiki、图谱或向量索引"));
    }

    public Map<String, Object> inspect(Request request) {
        if (request == null) throw new IllegalArgumentException("诊断请求不能为空");
        String dataset = value(request.dataset()).isBlank() ? "builtin" : request.dataset().trim();
        if (!Set.of("builtin", "current").contains(dataset)) throw new IllegalArgumentException("资料集必须是 builtin 或 current");
        String mode = value(request.mode()).isBlank() ? "fused" : request.mode().trim();
        if (!Set.of("fused", "keyword", "vector").contains(mode)) throw new IllegalArgumentException("检索模式必须是 fused、keyword 或 vector");
        DiagnosticCase selected = null;
        if (!value(request.caseId()).isBlank()) {
            selected = CASES.stream().filter(c -> c.id().equals(request.caseId().trim())).findFirst()
                    .orElseThrow(() -> new IllegalArgumentException("内置诊断用例不存在"));
        }
        String question = value(request.question()).strip();
        if (question.isBlank() && selected != null) question = selected.question();
        if (question.isBlank()) throw new IllegalArgumentException("请输入检索问题或选择内置用例");
        if (question.length() > 2000) throw new IllegalArgumentException("诊断问题最多 2000 字符");
        int topK = request.topK() == null ? 8 : request.topK();
        if (topK < 1 || topK > 100) throw new IllegalArgumentException("topK 必须在 1 到 100 之间");
        KnowledgeBaseSettings.Config cfg = settings.snapshot();
        boolean builtin = "builtin".equals(dataset);
        boolean wiki = !builtin && (request.includeWiki() == null ? cfg.wikiInject() : request.includeWiki());
        boolean graph = !builtin && (request.includeGraph() == null ? cfg.kgInject() : request.includeGraph());
        boolean includeContext = request.includeContext() == null || request.includeContext();
        long started = System.nanoTime();
        List<String> warnings = new ArrayList<>();
        Map<String, Object> indexStatus = indexStatus(builtin, cfg, warnings);
        KnowledgeRetrievalService.Trace trace = new KnowledgeRetrievalService.Trace();
        int searchLimit = includeContext ? cfg.candidates() : topK;
        KnowledgeRetrievalService.SearchResult search = builtin
                ? retrieval.searchSynthetic(question, searchLimit, mode, CORPUS, cfg, trace)
                : retrieval.searchWithTrace(question, searchLimit, mode, graph, wiki, cfg, trace);
        long visibleStarted = System.nanoTime();
        List<RetrievalHit> visibleHits = search.passages().stream().limit(topK).toList();
        List<Map<String, Object>> stages = new ArrayList<>();
        for (Map<String, Object> original : trace.stages()) {
            Map<String, Object> stage = new LinkedHashMap<>(original);
            if ("vector".equals(stage.get("id")) && "completed".equals(stage.get("status"))
                    && Boolean.FALSE.equals(indexStatus.get("configured"))) {
                stage.put("status", "skipped"); stage.put("reason", "未配置嵌入模型；没有生成或模拟向量");
            }
            if ("rerank".equals(stage.get("id")) && "completed".equals(stage.get("status"))) {
                if (!cfg.rerankEnabled()) {
                    stage.put("status", "skipped"); stage.put("reason", "重排开关已关闭，保持融合顺序");
                }
            }
            if (includeContext && "topK".equals(stage.get("id"))) {
                stage.put("id", "candidatePool"); stage.put("label", "上下文候选池");
                stage.put("reason", "保留全部已排序候选供上下文预算筛选，展示结果另按 topK 截取");
            }
            stages.add(stage);
            if ("error".equals(stage.get("status"))) warnings.add(stage.get("label") + "暂时不可用，结果保留其他通道");
            if ("degraded".equals(stage.get("status"))) warnings.add(stage.get("label") + "使用了回退或修复结果，请结合排序变化检查");
        }
        if (includeContext) stages.add(stage("topK", "诊断展示 Top K", visibleStarted, visibleHits.size(), "completed", "效果指标仅计算这些实际展示的片段"));
        Map<String, Object> context = null;
        if (includeContext) {
            long contextStarted = System.nanoTime();
            try {
                RetrievalContextService.Context packed = contexts.buildFromSearch(question, topK, search, wiki, graph, !builtin, cfg);
                context = Map.of("text", packed.text(), "chars", packed.text().length(), "budget", cfg.totalChars(), "retrieved", packed.retrieved());
                stages.add(stage("context", "上下文组装", contextStarted, packed.injectedHits().size(), "completed", "复用本次召回结果，没有第二次检索或生成答案"));
            } catch (Exception e) {
                warnings.add("上下文组装暂时不可用；已返回检索结果");
                stages.add(stage("context", "上下文组装", contextStarted, 0, "error", "上下文组装不可用，未输出异常正文"));
            }
        } else stages.add(stage("context", "上下文组装", 0, 0, "skipped", "本轮未请求上下文"));
        boolean hasGold = builtin && selected != null && selected.question().equals(question);
        Object metrics = hasGold ? metrics(selected.expectedSourceRefs(), visibleHits) : null;
        warnings.add(builtin ? "指标仅针对这组小型合成资料，不能代表当前资料库或一般检索效果" : "当前资料库没有标注正确来源，未计算命中率、召回率或 MRR");
        if (builtin && !hasGold) warnings.add("自定义问题没有标注正确来源，未计算效果指标");
        if (hasGold && selected.expectedSourceRefs().isEmpty()) warnings.add("未覆盖主题用例没有期望来源；召回率和 MRR 为 null，请检查 unexpectedHits");
        Map<String, Object> channels = new LinkedHashMap<>();
        trace.channels().forEach((id, hits) -> channels.put(id, views(hits)));
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("dataset", dataset); out.put("case", selected); out.put("question", question);
        out.put("elapsedMs", (System.nanoTime() - started) / 1_000_000); out.put("config", cfg.values());
        out.put("indexStatus", indexStatus); out.put("stages", stages); out.put("channels", channels);
        out.put("beforeRerank", views(trace.beforeRerank())); out.put("afterRerank", views(trace.afterRerank()));
        out.put("hits", views(visibleHits)); out.put("context", context); out.put("metrics", metrics);
        out.put("warnings", List.copyOf(warnings));
        return out;
    }

    private Map<String, Object> indexStatus(boolean builtin, KnowledgeBaseSettings.Config cfg, List<String> warnings) {
        Map<String, Object> out = new LinkedHashMap<>();
        try {
            EmbeddingClient.Snapshot target = embedder.snapshot();
            out.put("configured", target != null && target.configured());
            out.put("enabled", cfg.vectorEnabled());
            if (builtin) {
                out.put("ephemeral", true); out.put("currentSources", CORPUS.size());
                out.put("scopeReason", "本次临时嵌入只在内存使用，不创建或修改当前向量索引");
            } else {
                Map<String, Object> raw = vectors.status();
                for (String key : SAFE_INDEX_FIELDS) if (raw.containsKey(key)) out.put(key, raw.get(key));
                out.put("ephemeral", false);
            }
            out.put("reason", !cfg.vectorEnabled() ? "向量检索开关已关闭，未调用嵌入模型"
                    : target == null || !target.configured() ? "未配置嵌入模型；没有生成或模拟向量" : "已配置嵌入模型；阶段状态及实际结果反映本轮是否取得命中");
        } catch (Exception e) {
            out.put("status", "unavailable"); warnings.add("索引状态暂时无法读取；诊断仍继续尝试可用通道");
        }
        return out;
    }

    static Map<String, Object> metrics(List<String> expected, List<RetrievalHit> hits) {
        Set<String> gold = new LinkedHashSet<>(expected), returned = new LinkedHashSet<>();
        int first = 0;
        for (int i = 0; i < hits.size(); i++) {
            String ref = hits.get(i).sourceRef(); returned.add(ref);
            if (first == 0 && gold.contains(ref)) first = i + 1;
        }
        List<String> matched = returned.stream().filter(gold::contains).toList();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("expected", List.copyOf(gold)); out.put("expectedSourceRefs", List.copyOf(gold));
        out.put("totalExpected", gold.size()); out.put("matchedExpected", matched.size()); out.put("matchedSourceRefs", matched);
        out.put("returnedSources", returned.size()); out.put("returnedPassages", hits.size());
        out.put("hitRate", gold.isEmpty() ? null : matched.isEmpty() ? 0.0 : 1.0);
        out.put("recallAtK", gold.isEmpty() ? null : (double) matched.size() / gold.size());
        out.put("mrr", gold.isEmpty() ? null : first == 0 ? 0.0 : 1.0 / first);
        out.put("unexpectedHits", hits.stream().filter(hit -> !gold.contains(hit.sourceRef())).count());
        out.put("scope", "single_query_builtin_source_gold");
        out.put("definition", "hitRate 表示本问题是否命中任一期望来源；recallAtK 是期望来源覆盖率；MRR 使用首个期望来源片段的排名。不是答案正确率。 ");
        return out;
    }

    private static List<Map<String, Object>> views(List<RetrievalHit> hits) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (RetrievalHit hit : hits) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("type", hit.sourceType()); row.put("sourceType", hit.sourceType());
            row.put("sourceId", hit.sourceId()); row.put("id", hit.sourceId()); row.put("sourceRef", hit.sourceRef());
            row.put("title", hit.title()); row.put("category", hit.category()); row.put("text", hit.text()); row.put("score", hit.score());
            row.put("key", hit.key()); row.put("passageKey", hit.key()); row.put("seq", hit.seq()); row.put("rank", out.size() + 1);
            row.put("channels", hit.channels()); row.put("graphRelations", hit.graphRelations());
            out.add(row);
        }
        return List.copyOf(out);
    }

    private static Map<String, Object> stage(String id, String label, long started, int count, String status, String reason) {
        return Map.of("id", id, "label", label, "elapsedMs", started == 0 ? 0 : (System.nanoTime() - started) / 1_000_000,
                "count", count, "status", status, "reason", reason);
    }
    private static Map<String, Object> document(String type, long id, String title, String content) {
        return Map.of("source_type", type, "source_id", id, "title", title, "category", "合成诊断", "content", content);
    }
    private static String value(String text) { return text == null ? "" : text; }
}
