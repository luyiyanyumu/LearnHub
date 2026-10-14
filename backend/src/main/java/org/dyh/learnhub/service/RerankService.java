package org.dyh.learnhub.service;

import lombok.extern.slf4j.Slf4j;
import org.dyh.learnhub.service.rerank.CrossEncoderReranker;
import org.dyh.learnhub.service.rerank.LlmListwiseReranker;
import org.dyh.learnhub.service.rerank.Reranker;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 检索重排（rerank）的门面：召回之后、注入之前，让候选**按对回答这个问题的用处**重新排序。
 *
 * <h3>为什么需要第二阶段</h3>
 * 第一阶段的召回（词面 + 向量）本质是"粗筛"：词面看用词是否一致，向量看整段语义是否接近
 * —— 两者都**不知道这个问题的意图**。典型失败：问"怎么撤销暂存区的改动"，召回了一堆都提到 git
 * 的片段，真正讲 {@code git reset} 的那条却排在后面，被 top-k 切掉。
 *
 * <h3>两种后端（2026-09-29 起可切换）</h3>
 * <table>
 *   <tr><th>{@code kb.rerank_backend}</th><th>实现</th><th>特点</th></tr>
 *   <tr><td>{@code llm}（默认）</td><td>{@link LlmListwiseReranker}</td>
 *       <td>对话模型输出 order 数组；无外部服务；但有位置偏差、20 条一批、本地 8B 上秒级</td></tr>
 *   <tr><td>{@code cross}</td><td>{@link CrossEncoderReranker}</td>
 *       <td>Cross-Encoder 逐对打分；快、无窗口、无格式风险；需要 sidecar（tools/rerank-server.py）</td></tr>
 * </table>
 *
 * <h3>回退链（这是门面存在的主要理由）</h3>
 * 选中的后端失败 → 换另一个后端 → 都失败 → **原顺序**。
 * 重排是锦上添花，绝不能因为它挂了就让检索没有结果。
 *
 * <h3>代价与默认值</h3>
 * 它每次提问都多一次调用（LLM 约 1~3 秒；cross 约几百毫秒）。所以默认**关**，
 * 由 {@code kb.rerank} 打开，并且默认值要按评测结果定：只有 recall@k / MRR 真的提升才值得开
 * （实测：重排关 MRR 0.748 → 开 0.926，是这条链路里单项收益最大的一环）。
 */
@Slf4j
@Service
public class RerankService {

    /** 开关（默认关）：只有评测证明 MRR 提升才值得开 */
    public static final String SETTING_RERANK = "kb.rerank";
    /** 后端选择：{@code llm}（默认）/ {@code cross} */
    public static final String SETTING_BACKEND = "kb.rerank_backend";

    private final LlmListwiseReranker llm;
    private final CrossEncoderReranker cross;
    private final SettingsService settingsService;
    private KnowledgeBaseSettings knowledgeSettings;
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    public void setKnowledgeSettings(KnowledgeBaseSettings settings) { this.knowledgeSettings = settings; }
    private KnowledgeBaseSettings.Config config() {
        return knowledgeSettings == null ? new KnowledgeBaseSettings(settingsService).snapshot() : knowledgeSettings.snapshot();
    }

    public RerankService(LlmListwiseReranker llm, CrossEncoderReranker cross, SettingsService settingsService) {
        this.llm = llm;
        this.cross = cross;
        this.settingsService = settingsService;
    }

    /** 一条待重排候选（保持原有类型名，调用方无需改动） */
    public record Item(String key, String title, String snippet) {
    }

    public record Outcome(List<String> order, String backend, String status, String reason) {
        public Outcome { order = List.copyOf(order); }
    }

    public boolean enabled() {
        return "1".equals(settingsService.effective(SETTING_RERANK));
    }

    /** 配置的后端名（不探活） */
    public String backend() {
        String v = settingsService.effective(SETTING_BACKEND);
        return v == null || v.isBlank() ? "llm" : v.trim().toLowerCase();
    }

    /**
     * 真正生效的后端。
     * <p>配了 {@code cross} 但 sidecar 没起（探活失败）时**自动用 llm** ——
     * 否则每次提问都要先白等一次连接超时，再走回退。
     */
    public Reranker active() {
        if ("cross".equals(backend()) && cross.available()) {
            return cross;
        }
        return llm;
    }

    /**
     * 重排。
     *
     * @return 重排后的 key 顺序；未启用 / 候选太少 / 全部失败时返回**原顺序**（调用方无需分支）
     */
    public List<String> rerank(String question, List<Item> items) {
        return rerankWithOutcome(question, items).order();
    }

    public Outcome rerankWithOutcome(String question, List<Item> items) {
        return rerankWithOutcome(question, items, config());
    }

    public Outcome rerankWithOutcome(String question, List<Item> items, KnowledgeBaseSettings.Config cfg) {
        if (items == null) {
            return new Outcome(List.of(), "", "skipped", "没有待重排候选");
        }
        List<String> original = items.stream().map(Item::key).toList();
        if (!cfg.rerankEnabled() || items.size() <= 2) {
            return new Outcome(original, cfg.rerankBackend(), "skipped",
                    !cfg.rerankEnabled() ? "重排已关闭" : "候选不超过两项，保留原序");
        }
        List<Reranker.Item> converted = items.stream()
                .map(i -> new Reranker.Item(i.key(), i.title(), i.snippet()))
                .toList();
        Reranker first = "cross".equals(cfg.rerankBackend()) && cross.available() ? cross : llm;
        Reranker second = first == cross ? llm : cross;
        try {
            Reranker.Result result = first.rerankDetailed(question, converted, cfg);
            // Constructor-based mocks and legacy external implementations may not provide a detailed result.
            if (result == null) result = new Reranker.Result(first.rerank(question, converted), "completed", "");
            boolean backendFallback = "cross".equals(cfg.rerankBackend()) && first == llm;
            return new Outcome(result.order(), first.name(), backendFallback && "completed".equals(result.status())
                    ? "degraded" : result.status(), backendFallback
                    ? "Cross-Encoder 不可用，已改用对话模型；" + result.reason() : result.reason());
        } catch (Exception e) {
            log.warn("重排后端 {} 失败，改用 {}：{}", first.name(), second.name(), e.toString());
            if (!second.available()) {
                return new Outcome(original, first.name(), "fallback", "重排后端不可用，保留召回原序");
            }
            try {
                Reranker.Result result = second.rerankDetailed(question, converted, cfg);
                if (result == null) result = new Reranker.Result(second.rerank(question, converted), "completed", "");
                return new Outcome(result.order(), second.name(), "fallback".equals(result.status()) ? "fallback" : "degraded",
                        "首选重排后端失败，已尝试备用后端；" + result.reason());
            } catch (Exception e2) {
                log.warn("重排后端 {} 也失败，本轮保持原顺序：{}", second.name(), e2.toString());
                return new Outcome(original, second.name(), "fallback", "两个重排后端均不可用，保留召回原序");
            }
        }
    }

    /** 给界面/日志用的开关与后端状态（两个后端都报，便于排查"为什么这次没重排"） */
    public Map<String, Object> status() {
        Map<String, Object> o = new LinkedHashMap<>();
        o.put("enabled", enabled());
        o.put("backend", backend());
        o.put("active", active().name());
        o.put("llm", llm.status());
        o.put("cross", cross.status());
        return o;
    }
}
