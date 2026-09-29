package org.dyh.learnhub.service.rerank;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.dyh.learnhub.ai.DeepSeekClient;
import org.dyh.learnhub.ai.ModelRouting;
import org.dyh.learnhub.service.SettingsService;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * LLM 列表重排（listwise）：把候选丢给对话模型，让它输出一个 order 数组。
 *
 * <p>逻辑自 2026-09-29 从 {@code RerankService} 整体搬过来，行为一字未改 ——
 * 当时它是唯一实现，现在它是"没有本地 cross-encoder 时的可用后端"。
 *
 * <h3>分批 + 批间轮转</h3>
 * 候选超过 {@link #BATCH} 时**分批**：每批内部排好序，然后按"批间轮转"交错合并
 * （第 1 批第 1 名 → 第 2 批第 1 名 → 第 1 批第 2 名 → …）。
 * <p>为什么不直接把窗口调大一次排完：一次给 60 条，提示词变长、注意力被摊薄，排序质量反而下降；
 * 而且失败就整批退回原顺序，影响面更大。分批 + 轮转能覆盖更多候选，同时每批都保持小规模。
 *
 * <h3>已知缺陷（这就是要有 cross-encoder 的原因）</h3>
 * 位置偏差、20 条一批的窗口切分、依赖模型输出合法 JSON、以及本地 8B 上的秒级延迟。
 */
@Slf4j
@Service
public class LlmListwiseReranker implements Reranker {

    /** 每批给模型多少条候选（太多会挤提示词、也让排序变糊） */
    public static final int BATCH = 20;
    /** 最多重排多少条：再往后性价比已经很低，且要按 {@link #BATCH} 分批 */
    public static final int MAX_WINDOW = 60;
    /** 摘要片段长度的默认值；可用 {@code kb.rerank_snippet} 覆盖，但**调大对 listwise 通常有害** */
    private static final int DEFAULT_SNIPPET = 120;

    private final DeepSeekClient client;
    private final ModelRouting routing;
    private final ObjectMapper objectMapper;
    private final SettingsService settingsService;

    public LlmListwiseReranker(DeepSeekClient client, ModelRouting routing, ObjectMapper objectMapper,
                               SettingsService settingsService) {
        this.client = client;
        this.routing = routing;
        this.objectMapper = objectMapper;
        this.settingsService = settingsService;
    }

    @Override
    public String name() {
        return "llm";
    }

    @Override
    public boolean available() {
        return true;   // 只要配了模型就能用；真正的可用性由门面的 kb.rerank 开关控制
    }

    @Override
    public List<String> rerank(String question, List<Item> items) {
        if (items == null || items.isEmpty()) {
            return List.of();
        }
        List<Item> window = items.size() > MAX_WINDOW ? items.subList(0, MAX_WINDOW) : items;
        List<List<String>> batches = new ArrayList<>();
        for (int from = 0; from < window.size(); from += BATCH) {
            List<Item> part = window.subList(from, Math.min(window.size(), from + BATCH));
            List<String> ordered = rerankBatch(question, part);
            // 这一批失败：按原顺序保留，绝不丢候选
            batches.add(ordered != null ? ordered : part.stream().map(Item::key).toList());
        }
        List<String> out = interleave(batches);
        // 窗口之外的候选按原顺序接在后面
        for (Item it : items) {
            if (!out.contains(it.key())) {
                out.add(it.key());
            }
        }
        return out;
    }

    /** 批间轮转交错：各批的第 i 名依次排下去 */
    static List<String> interleave(List<List<String>> batches) {
        List<String> out = new ArrayList<>();
        int max = 0;
        for (List<String> b : batches) {
            max = Math.max(max, b.size());
        }
        for (int i = 0; i < max; i++) {
            for (List<String> b : batches) {
                if (i < b.size() && !out.contains(b.get(i))) {
                    out.add(b.get(i));
                }
            }
        }
        return out;
    }

    /**
     * 单批重排。
     *
     * @return 排好序的 key；失败返回 {@code null}（调用方按原顺序保留这一批）
     */
    private List<String> rerankBatch(String question, List<Item> part) {
        if (part.size() <= 2) {
            return part.stream().map(Item::key).toList();
        }
        StringBuilder list = new StringBuilder();
        for (int i = 0; i < part.size(); i++) {
            Item it = part.get(i);
            String snip = it.snippet() == null ? "" : it.snippet().replaceAll("\\s+", " ").trim();
            if (snip.length() > snippetLimit()) {
                snip = snip.substring(0, snippetLimit());
            }
            list.append(i + 1).append(". ").append(it.title())
                    .append(snip.isEmpty() ? "" : "｜" + snip).append('\n');
        }
        String system = """
                你是检索结果的重排器。下面是用户的问题与一批候选片段（编号 + 标题 + 摘要）。
                请只按「对回答这个问题有多大用处」排序：能直接回答问题或提供关键事实的排前面，
                只是碰巧提到同一个词的排后面。
                只输出严格 JSON：{"order":[按用处从高到低的编号数组]}
                必须包含全部编号、不重复、不新增。不要输出 JSON 之外的内容。
                """;
        String user = "问题：" + question + "\n候选：\n" + list;
        ModelRouting.ModelTarget t = routing.forTask(ModelRouting.TASK_RERANK);
        try {
            JsonNode node = client.chat(List.of(
                            Map.of("role", "system", "content", system),
                            Map.of("role", "user", "content", user)),
                    null, t.baseUrl(), t.apiKey(), t.model(), 300, 0.1,
                    "disabled", null, Duration.ofSeconds(20));
            String content = node.path("content").asText("");
            if (!StringUtils.hasText(content)) {
                return null;
            }
            JsonNode arr = objectMapper.readTree(stripFence(content)).path("order");
            List<String> out = new ArrayList<>();
            for (JsonNode n : arr) {
                int idx = n.asInt(-1) - 1;
                if (idx >= 0 && idx < part.size()) {
                    String k = part.get(idx).key();
                    if (!out.contains(k)) {
                        out.add(k);
                    }
                }
            }
            if (out.isEmpty()) {
                return null;
            }
            // 模型漏写的编号按原顺序补在后面
            for (Item it : part) {
                if (!out.contains(it.key())) {
                    out.add(it.key());
                }
            }
            return out;
        } catch (Exception e) {
            log.warn("重排失败（这一批保持原顺序）：{}", e.toString());
            return null;
        }
    }

    @Override
    public Map<String, Object> status() {
        Map<String, Object> o = new LinkedHashMap<>();
        o.put("backend", name());
        o.put("batch", BATCH);
        o.put("maxWindow", MAX_WINDOW);
        o.put("snippet", snippetLimit());
        o.put("model", routing.forTask(ModelRouting.TASK_RERANK).model());
        return o;
    }

    /** 候选文本长度（设置里没配就用 120） */
    private int snippetLimit() {
        String v = settingsService.effective(Reranker.KEY_SNIPPET);
        if (v == null || v.isBlank()) {
            return DEFAULT_SNIPPET;
        }
        try {
            int n = Integer.parseInt(v.trim());
            return n <= 0 ? DEFAULT_SNIPPET : n;
        } catch (NumberFormatException e) {
            return DEFAULT_SNIPPET;
        }
    }

    private static String stripFence(String s) {
        String t = s == null ? "" : s.trim();
        if (t.startsWith("```")) {
            int nl = t.indexOf('\n');
            if (nl > 0) {
                t = t.substring(nl + 1);
            }
            int end = t.lastIndexOf("```");
            if (end >= 0) {
                t = t.substring(0, end);
            }
        }
        return t.trim();
    }
}
