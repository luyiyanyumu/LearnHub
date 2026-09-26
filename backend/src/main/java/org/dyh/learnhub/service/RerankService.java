package org.dyh.learnhub.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.dyh.learnhub.ai.DeepSeekClient;
import org.dyh.learnhub.ai.ModelRouting;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 检索重排（rerank）：召回之后、注入之前，让模型对候选**按对回答这个问题的用处**重新排序。
 *
 * <h3>为什么需要第二阶段</h3>
 * 第一阶段的召回（词面 + 向量）本质是"粗筛"：
 * 词面看的是用词是否一致，向量看的是整段语义是否接近 —— 两者都**不知道这个问题的意图**。
 * 典型失败：问"怎么撤销暂存区的改动"，召回了一堆都提到 git 的片段，
 * 真正讲 {@code git reset} 的那条却排在后面，被 top-k 切掉。
 *
 * <h3>为什么用模型而不是向量再算一次</h3>
 * 向量重排要再训一个交叉编码器（cross-encoder），这个规模不值得。
 * 直接用已有的对话模型做一次"列表排序"是最省的：候选只给标题 + 摘要片段，
 * 一次调用排 20 条，几百 token。
 *
 * <h3>代价与默认值</h3>
 * 它**每次提问都多花一次模型调用**（约 1~3 秒）。所以：默认**关**，
 * 由 {@code kb.rerank} 打开；而且默认值是按评测结果定的 —— 只有 recall@k / MRR 真的提升才值得开。
 * 失败时**静默退回原顺序**（重排是锦上添花，绝不能因为它挂了就没有检索结果）。
 */
@Service
public class RerankService {

    private static final Logger log = LoggerFactory.getLogger(RerankService.class);

    /** 每批给模型多少条候选（太多会挤提示词、也让排序变糊） */
    public static final int BATCH = 20;
    /** 最多重排多少条：再往后性价比已经很低，且要按 {@link #BATCH} 分批 */
    public static final int MAX_WINDOW = 60;
    /** 摘要片段长度：给太长会挤爆提示词，判断"相不相关"120 字足够 */
    private static final int SNIPPET = 120;

    public static final String SETTING_RERANK = "kb.rerank";

    private final DeepSeekClient client;
    private final ModelRouting routing;
    private final ObjectMapper objectMapper;
    private final SettingsService settingsService;

    public RerankService(DeepSeekClient client, ModelRouting routing, ObjectMapper objectMapper,
                         SettingsService settingsService) {
        this.client = client;
        this.routing = routing;
        this.objectMapper = objectMapper;
        this.settingsService = settingsService;
    }

    public boolean enabled() {
        return "1".equals(settingsService.effective(SETTING_RERANK));
    }

    /** 一条待重排候选 */
    public record Item(String key, String title, String snippet) {
    }

    /**
     * 按"对回答这个问题的用处"重排。
     *
     * <p>候选超过 {@link #BATCH} 时**分批**：每批内部排好序，然后按"批间轮转"交错合并
     * （第 1 批第 1 名 → 第 2 批第 1 名 → 第 1 批第 2 名 → …）。
     * <p>为什么不直接把 window 调大一次排完：一次给 60 条候选，提示词变长、注意力被摊薄，
     * 排序质量反而下降；而且失败就整批退回原顺序，影响面更大。
     * 分批 + 轮转能覆盖更多候选，同时每批都保持小规模。
     *
     * @return 重排后的 key 顺序；未启用 / 候选太少 / 调用失败时返回**原顺序**（调用方无需分支处理）
     */
    public List<String> rerank(String question, List<Item> items) {
        if (!enabled() || items == null || items.size() <= 2) {
            return items == null ? List.of() : items.stream().map(Item::key).toList();
        }
        List<Item> window = items.size() > MAX_WINDOW ? items.subList(0, MAX_WINDOW) : items;
        List<List<String>> batches = new ArrayList<>();
        for (int from = 0; from < window.size(); from += BATCH) {
            List<Item> part = window.subList(from, Math.min(window.size(), from + BATCH));
            List<String> ordered = rerankBatch(question, part);
            if (ordered != null) {
                batches.add(ordered);
            } else {
                // 这一批失败：按原顺序保留，绝不丢候选
                batches.add(part.stream().map(Item::key).toList());
            }
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
            if (snip.length() > SNIPPET) {
                snip = snip.substring(0, SNIPPET);
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

    /** 给界面/日志用的开关状态 */
    public Map<String, Object> status() {
        Map<String, Object> o = new LinkedHashMap<>();
        o.put("enabled", enabled());
        o.put("batch", BATCH);
        o.put("maxWindow", MAX_WINDOW);
        return o;
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
