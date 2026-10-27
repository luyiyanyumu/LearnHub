package org.dyh.learnhub.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** One typed schema for the settings panel and immutable operation-local retrieval settings. */
@Slf4j
@Service
public class KnowledgeBaseSettings {
    private final SettingsService settings;
    public KnowledgeBaseSettings(SettingsService settings) { this.settings = settings; }

    public record Field(String key, String settingKey, String label, String group, String type,
                        Object defaultValue, Number min, Number max, Number step,
                        boolean requiresRebuild, String help, List<Map<String, String>> options) {
        public Map<String, Object> metadata() {
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("key", key); out.put("label", label); out.put("group", group); out.put("type", type);
            out.put("default", defaultValue); out.put("min", min); out.put("max", max); out.put("step", step);
            out.put("options", options); out.put("requiresRebuild", requiresRebuild); out.put("help", help);
            return out;
        }
    }

    private static final List<Field> FIELDS = List.of(
            new Field("chunkStrategy", "kb.chunk_strategy", "切割方式", "index", "select", "structure", null, null, null, true, "结构切割保护代码、公式与表格；长资料语义切割只对长篇资料库文件生效，笔记与速查卡仍按结构切割。使用现有嵌入模型预计算边界，保存后需重建索引。", List.of(Map.of("label", "结构切割", "value", "structure"), Map.of("label", "长资料语义切割", "value", "semantic_long"))),
            new Field("chunkTarget", "kb.chunk_target", "目标片段长度", "index", "number", 800, 100, 8000, 10, true, "按标题和段落聚合到目标字符数。", List.of()),
            new Field("chunkOverlap", "kb.chunk_overlap", "片段重叠长度", "index", "number", 120, 0, 4000, 10, true, "须小于目标长度，保留相邻片段的上下文。", List.of()),
            new Field("chunkMax", "kb.chunk_max", "片段最大长度", "index", "number", 1200, 100, 16000, 10, true, "普通文字的最大长度，不得小于目标长度；代码、公式与表格可完整保留并超出此限制。", List.of()),
            new Field("semanticMinChars", "kb.semantic_min_chars", "语义切割起始长度", "index", "number", 4000, 2000, 100000, 100, true, "长资料语义切割模式下，达到此字符数的资料库文件才预计算语义边界；笔记与速查卡仍按结构切割。保存后需重建索引。", List.of()),
            new Field("semanticBreakpointPercentile", "kb.semantic_breakpoint_percentile", "语义边界百分位", "index", "number", 85, 50, 99, 1, true, "长资料语义切割模式下，根据相邻片段语义差异的百分位选择边界；越高越少切分，仍保护代码、公式与表格。保存后需重建索引。", List.of()),
            new Field("embedBatchSize", "kb.embed_batch_size", "嵌入请求批量", "index", "number", 16, 1, 128, 1, false, "每次嵌入请求提交的片段数。", List.of()),
            new Field("embeddingInputChars", "kb.embedding_input_chars", "嵌入文本上限", "index", "number", 6000, 128, 32000, 100, true, "含标题和小节，超出上限的输入截断。", List.of()),
            new Field("vectorEnabled", "ai.vector_enabled", "启用向量检索", "retrieval", "boolean", true, null, null, null, false, "关闭后继续使用词面检索。", List.of()),
            new Field("contextualEmbed", "kb.contextual_embed", "嵌入包含标题与小节", "index", "boolean", true, null, null, null, true, "为片段前置来源标题和所属小节。", List.of()),
            new Field("autoIndex", "kb.auto_index", "自动维护索引", "index", "boolean", true, null, null, null, false, "内容变化后增量更新；自动维护开启时，嵌入空间改变也会排队重建。", List.of()),
            new Field("vectorBackend", "kb.vector_backend", "向量检索后端", "index", "select", "mysql", null, null, null, false, "Milvus 不可用时回退 MySQL；切换后需完整同步。", List.of(Map.of("label", "MySQL 扫描", "value", "mysql"), Map.of("label", "Milvus ANN", "value", "milvus"))),
            new Field("milvusUri", "kb.milvus_uri", "Milvus 服务地址", "index", "text", "http://127.0.0.1:19530", null, null, null, false, "使用已保存的认证配置；修改地址后需完整同步。", List.of()),
            new Field("rerankEnabled", "kb.rerank", "启用候选重排", "rerank", "boolean", false, null, null, null, false, "按照候选对回答问题的用处重新排序。", List.of()),
            new Field("rerankBackend", "kb.rerank_backend", "重排后端", "rerank", "select", "llm", null, null, null, false, "Cross-Encoder 不可用时回退对话模型。", List.of(Map.of("label", "对话模型", "value", "llm"), Map.of("label", "Cross-Encoder", "value", "cross"))),
            new Field("rerankUrl", "kb.rerank_url", "Cross-Encoder 地址", "rerank", "text", "http://127.0.0.1:8091/rerank", null, null, null, false, "配置兼容的 HTTP 重排接口。", List.of()),
            new Field("rerankSnippet", "kb.rerank_snippet", "重排摘录长度", "rerank", "number", 120, 16, 2000, 10, false, "保留小节与问题相关正文；增加长度会增加调用成本。", List.of()),
            new Field("queryRewrite", "ai.query_rewrite", "启用补检索规划", "retrieval", "boolean", true, null, null, null, false, "证据不足时最多进行一次查询规划。", List.of()),
            new Field("wikiInject", "kb.wiki_inject", "注入 Wiki 导览", "context", "boolean", true, null, null, null, false, "Wiki 仅作定位导览，事实应回查原文。", List.of()),
            new Field("kgInject", "kg.inject", "注入图谱证据", "context", "boolean", true, null, null, null, false, "引用经过原文核验的关联证据。", List.of()),
            new Field("candidates", "kb.candidates", "每路候选上限", "retrieval", "number", 24, 4, 120, 1, false, "限制各通道候选和上下文候选池。", List.of()),
            new Field("rrfK", "kb.rrf_k", "RRF 平滑常数", "retrieval", "number", 60, 1, 200, 1, false, "融合按名次投票，常数越大名次差异越平缓。", List.of()),
            new Field("fusedWindow", "kb.fused_window", "融合重排窗口", "retrieval", "number", 20, 2, 20, 1, false, "不超过候选上限；最多20项以保持一次列表重排。", List.of()),
            new Field("wikiWindow", "kb.wiki_window", "Wiki 召回小节数", "retrieval", "number", 4, 0, 12, 1, false, "须小于融合窗口，Wiki 与原文共用重排窗口。", List.of()),
            new Field("supplementQueries", "kb.supplement_queries", "补检索问题数", "retrieval", "number", 2, 0, 4, 1, false, "一次规划最多扩展的问题数。", List.of()),
            new Field("sourcePriority", "kb.source_priority", "每来源优先片段数", "retrieval", "number", 2, 1, 12, 1, false, "优先给不同来源机会，其余相关片段仍可填补空位。", List.of()),
            new Field("vectorSourcePriority", "kb.vector_source_priority", "向量每来源优先片段数", "retrieval", "number", 1, 1, 12, 1, false, "向量召回先给每来源最佳片段机会，再按相关度补入其余片段。", List.of()),
            new Field("vectorMinScore", "kb.vector_min_score", "向量最低相似度", "retrieval", "number", 0.45, 0, 1, 0.01, false, "低于该余弦相似度的候选被排除。", List.of()),
            new Field("vectorScoreGap", "kb.vector_score_gap", "向量相对分差", "retrieval", "number", 0.08, 0, 1, 0.01, false, "只保留与最高分相差不超过此值的候选。", List.of()),
            new Field("vectorPoolMultiplier", "kb.vector_pool_multiplier", "ANN 候选倍数", "retrieval", "number", 4, 1, 16, 1, false, "ANN 请求池为候选上限乘倍数或最小池的较大值。", List.of()),
            new Field("vectorPoolMin", "kb.vector_pool_min", "ANN 最小候选池", "retrieval", "number", 64, 1, 2048, 1, false, "保证后续阈值筛选前有足够候选。", List.of()),
            new Field("maxPassages", "kb.max_passages", "最多注入原文数", "context", "number", 8, 1, 24, 1, false, "不得超过候选上限；同小节扩展不增加原文条目。", List.of()),
            new Field("totalChars", "kb.total_chars", "总上下文预算", "context", "number", 7000, 1000, 64000, 100, false, "包含说明、标签、分隔符、原文、Wiki 和图谱。", List.of()),
            new Field("passageChars", "kb.passage_chars", "单段原文上限", "context", "number", 1200, 128, 16000, 100, false, "超出时取问题相关原文摘录，不得超过总预算。", List.of()),
            new Field("neighborCount", "kb.neighbor_count", "同小节相邻片段数", "context", "number", 2, 0, 2, 1, false, "比较前后相邻片段与问题的关联，优先补充缺失概念；仅扩展同来源同小节。", List.of()),
            new Field("wikiChars", "kb.wiki_chars", "Wiki 导览预算", "context", "number", 1000, 0, 10000, 100, false, "完整小节超预算时归还空间给原文。", List.of()),
            new Field("wikiSections", "kb.wiki_sections", "最多注入 Wiki 小节数", "context", "number", 2, 0, 12, 1, false, "限制实际加入上下文的完整小节。", List.of()),
            new Field("globalGraphChars", "kb.global_graph_chars", "全局图谱预算", "context", "number", 2500, 0, 12000, 100, false, "Wiki 与图谱预算之和须给总预算留下至少256字符。", List.of()),
            new Field("groundingEnabled", "kb.grounding", "启用答案依据核对", "grounding", "boolean", false, null, null, null, false, "回答后核对事实是否受到实际注入证据支撑。", List.of()),
            new Field("groundingAnswerChars", "kb.grounding_answer_chars", "核对答案长度上限", "grounding", "number", 3000, 128, 32000, 100, false, "超预算时跳过完整核对，不截断后作出结论。", List.of())
    );
    private static final Map<String, Field> BY_NAME;
    private static final Map<String, Object> DEFAULT_VALUES;
    static {
        Map<String, Field> names = new LinkedHashMap<>();
        Map<String, Object> defaults = new LinkedHashMap<>();
        for (Field field : FIELDS) { names.put(field.key(), field); defaults.put(field.key(), field.defaultValue()); }
        BY_NAME = Collections.unmodifiableMap(names);
        DEFAULT_VALUES = Collections.unmodifiableMap(defaults);
    }

    public static Config defaults() { return new Config(DEFAULT_VALUES); }
    public static List<Field> fields() { return FIELDS; }
    public static Map<String, String> fieldMapping() {
        Map<String, String> out = new LinkedHashMap<>();
        for (Field f : FIELDS) out.put(f.key(), f.settingKey());
        return Collections.unmodifiableMap(out);
    }
    public static List<String> settingKeys() { return FIELDS.stream().map(Field::settingKey).toList(); }
    public static String defaultSetting(String key) {
        for (Field f : FIELDS) if (f.settingKey().equals(key)) return encode(f.defaultValue());
        return null;
    }

    public record Config(Map<String, Object> values) {
        public Config { values = Collections.unmodifiableMap(new LinkedHashMap<>(values)); }
        public String chunkStrategy() { return (String) values.getOrDefault("chunkStrategy", "structure"); }
        public int chunkTarget() { return ((Number) values.get("chunkTarget")).intValue(); }
        public int chunkOverlap() { return ((Number) values.get("chunkOverlap")).intValue(); }
        public int chunkMax() { return ((Number) values.get("chunkMax")).intValue(); }
        public int semanticMinChars() { return ((Number) values.getOrDefault("semanticMinChars", 4000)).intValue(); }
        public int semanticBreakpointPercentile() { return ((Number) values.getOrDefault("semanticBreakpointPercentile", 85)).intValue(); }
        public int embedBatchSize() { return ((Number) values.get("embedBatchSize")).intValue(); }
        public int embeddingInputChars() { return ((Number) values.get("embeddingInputChars")).intValue(); }
        public boolean vectorEnabled() { return (Boolean) values.get("vectorEnabled"); }
        public boolean contextualEmbed() { return (Boolean) values.get("contextualEmbed"); }
        public boolean autoIndex() { return (Boolean) values.get("autoIndex"); }
        public String vectorBackend() { return (String) values.get("vectorBackend"); }
        public String milvusUri() { return (String) values.get("milvusUri"); }
        public boolean rerankEnabled() { return (Boolean) values.get("rerankEnabled"); }
        public String rerankBackend() { return (String) values.get("rerankBackend"); }
        public String rerankUrl() { return (String) values.get("rerankUrl"); }
        public int rerankSnippet() { return ((Number) values.get("rerankSnippet")).intValue(); }
        public boolean queryRewrite() { return (Boolean) values.get("queryRewrite"); }
        public boolean wikiInject() { return (Boolean) values.get("wikiInject"); }
        public boolean kgInject() { return (Boolean) values.get("kgInject"); }
        public int candidates() { return ((Number) values.get("candidates")).intValue(); }
        public int rrfK() { return ((Number) values.get("rrfK")).intValue(); }
        public int fusedWindow() { return ((Number) values.get("fusedWindow")).intValue(); }
        public int wikiWindow() { return ((Number) values.get("wikiWindow")).intValue(); }
        public int supplementQueries() { return ((Number) values.get("supplementQueries")).intValue(); }
        public int sourcePriority() { return ((Number) values.get("sourcePriority")).intValue(); }
        public int vectorSourcePriority() { return ((Number) values.get("vectorSourcePriority")).intValue(); }
        public double vectorMinScore() { return ((Number) values.get("vectorMinScore")).doubleValue(); }
        public double vectorScoreGap() { return ((Number) values.get("vectorScoreGap")).doubleValue(); }
        public int vectorPoolMultiplier() { return ((Number) values.get("vectorPoolMultiplier")).intValue(); }
        public int vectorPoolMin() { return ((Number) values.get("vectorPoolMin")).intValue(); }
        public int maxPassages() { return ((Number) values.get("maxPassages")).intValue(); }
        public int totalChars() { return ((Number) values.get("totalChars")).intValue(); }
        public int passageChars() { return ((Number) values.get("passageChars")).intValue(); }
        public int neighborCount() { return ((Number) values.get("neighborCount")).intValue(); }
        public int wikiChars() { return ((Number) values.get("wikiChars")).intValue(); }
        public int wikiSections() { return ((Number) values.get("wikiSections")).intValue(); }
        public int globalGraphChars() { return ((Number) values.get("globalGraphChars")).intValue(); }
        public boolean groundingEnabled() { return (Boolean) values.get("groundingEnabled"); }
        public int groundingAnswerChars() { return ((Number) values.get("groundingAnswerChars")).intValue(); }
        public TextChunker.Options chunkOptions() { return new TextChunker.Options(chunkTarget(), chunkOverlap(), chunkMax()); }
        public String indexingFingerprint() {
            return chunkTarget() + ":" + chunkOverlap() + ":" + chunkMax() + ":" + contextualEmbed() + ":" + embeddingInputChars()
                    + ":" + chunkStrategy() + ":" + semanticMinChars() + ":" + semanticBreakpointPercentile();
        }
    }

    public Config snapshot() {
        Map<String, Object> values = new LinkedHashMap<>();
        Map<String, String> stored = settings.effectiveAll(settingKeys());
        for (Field field : FIELDS) {
            String raw = stored != null && stored.containsKey(field.settingKey())
                    ? stored.get(field.settingKey()) : settings.effective(field.settingKey());
            try { values.put(field.key(), parse(field, raw)); }
            catch (IllegalArgumentException invalid) { values.put(field.key(), field.defaultValue()); }
        }
        try { return validate(values); }
        catch (IllegalArgumentException invalid) {
            log.warn("已保存的知识库参数组合无效，本轮使用默认参数");
            return defaults();
        }
    }

    /** Validates the entire merged batch before any setting is persisted. */
    public Config validatePatch(Map<String, ?> patch) {
        return validatePatch(snapshot(), patch);
    }
    public static Config validatePatch(Config current, Map<String, ?> patch) {
        if (patch == null) throw new IllegalArgumentException("请提供知识库参数");
        Map<String, Object> merged = new LinkedHashMap<>(current.values());
        for (var entry : patch.entrySet()) {
            Field field = BY_NAME.get(entry.getKey());
            if (field == null) throw new IllegalArgumentException("不支持的知识库参数: " + entry.getKey());
            merged.put(field.key(), parse(field, entry.getValue()));
        }
        return validate(merged);
    }

    private static Config validate(Map<String, Object> values) {
        Config c = new Config(values);
        if (c.chunkOverlap() >= c.chunkTarget() || c.chunkTarget() > c.chunkMax())
            throw new IllegalArgumentException("片段参数须满足：重叠长度 < 目标长度 ≤ 最大片段长度");
        if (c.maxPassages() > c.candidates()) throw new IllegalArgumentException("原文注入数不得超过候选上限");
        if (c.fusedWindow() > c.candidates()) throw new IllegalArgumentException("融合窗口不得超过候选上限");
        if (c.wikiWindow() >= c.fusedWindow()) throw new IllegalArgumentException("Wiki 召回数须小于融合窗口");
        if (c.passageChars() > c.totalChars()) throw new IllegalArgumentException("单段原文预算不得超过总预算");
        if ((long) c.wikiChars() + c.globalGraphChars() > c.totalChars() - 256)
            throw new IllegalArgumentException("Wiki 与图谱预算之和须给总预算留下至少256字符");
        return c;
    }

    private static Object parse(Field f, Object value) {
        if (value == null || value instanceof String s && s.isBlank()) return f.defaultValue();
        String raw = String.valueOf(value).trim();
        if ("boolean".equals(f.type())) {
            if (value instanceof Boolean) return value;
            if ("1".equals(raw) || "true".equalsIgnoreCase(raw)) return true;
            if ("0".equals(raw) || "false".equalsIgnoreCase(raw)) return false;
            throw new IllegalArgumentException(f.label() + "须为开关值");
        }
        if ("number".equals(f.type())) {
            double number;
            try { number = Double.parseDouble(raw); }
            catch (NumberFormatException ex) { throw new IllegalArgumentException(f.label() + "须为数字"); }
            if (!Double.isFinite(number) || number < f.min().doubleValue() || number > f.max().doubleValue())
                throw new IllegalArgumentException(f.label() + "超出范围 " + f.min() + "–" + f.max());
            if (f.defaultValue() instanceof Integer) {
                if (number != Math.rint(number)) throw new IllegalArgumentException(f.label() + "须为整数");
                return (int) number;
            }
            return number;
        }
        if ("select".equals(f.type())) {
            if (f.options().stream().noneMatch(option -> option.get("value").equals(raw)))
                throw new IllegalArgumentException(f.label() + "选项无效");
            return raw;
        }
        if (!(value instanceof String) || raw.length() > 2048)
            throw new IllegalArgumentException(f.label() + "须为有效地址");
        try {
            URI uri = URI.create(raw);
            if (!List.of("http", "https").contains(uri.getScheme()) || uri.getHost() == null
                    || uri.getUserInfo() != null || uri.getFragment() != null)
                throw new IllegalArgumentException();
        } catch (IllegalArgumentException ex) { throw new IllegalArgumentException(f.label() + "须为不含认证信息的 HTTP(S) 地址"); }
        return raw;
    }

    private static String encode(Object value) { return value instanceof Boolean b ? b ? "1" : "0" : String.valueOf(value); }

    public Map<String, Object> describe() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("values", snapshot().values()); out.put("defaults", defaults().values());
        out.put("fields", FIELDS.stream().map(Field::metadata).toList());
        out.put("needsRebuild", false); out.put("rebuildFields", List.of());
        out.put("indexImpact", Map.of("requiresRebuild", false, "fields", List.of(), "requiresSync", false));
        return out;
    }

    public synchronized Map<String, Object> update(Map<String, ?> patch) {
        Config current = snapshot();
        Config next = validatePatch(current, patch);
        Map<String, String> writes = new LinkedHashMap<>();
        List<String> rebuild = new ArrayList<>();
        boolean sync = false;
        for (String key : patch.keySet()) {
            Field f = BY_NAME.get(key);
            Object raw = patch.get(key);
            boolean reset = raw == null || raw instanceof String s && s.isBlank();
            String external = reset ? settings.externalValue(f.settingKey()) : null;
            // An environment override would otherwise reappear immediately after a blank reset.
            writes.put(f.settingKey(), reset && (external == null || external.isBlank())
                    ? null : encode(next.values().get(key)));
            if (!Objects.equals(current.values().get(key), next.values().get(key))) {
                if (f.requiresRebuild()) rebuild.add(key);
                if (List.of("vectorBackend", "milvusUri").contains(key)) sync = true;
            }
        }
        settings.updateAll(writes);
        Map<String, Object> out = describe();
        out.put("values", next.values()); out.put("needsRebuild", !rebuild.isEmpty());
        out.put("rebuildFields", List.copyOf(rebuild));
        out.put("indexImpact", Map.of("requiresRebuild", !rebuild.isEmpty(), "fields", List.copyOf(rebuild), "requiresSync", sync));
        return out;
    }
}
