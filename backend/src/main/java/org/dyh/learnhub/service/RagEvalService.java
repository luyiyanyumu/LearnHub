package org.dyh.learnhub.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.dyh.learnhub.entity.RagEval;
import org.dyh.learnhub.entity.RagEvalRun;
import org.dyh.learnhub.mapper.RagEvalMapper;
import org.dyh.learnhub.mapper.RagEvalRunMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 检索评测：把"检索变好了没有"从感觉变成数字。
 *
 * <h3>为什么必须有它</h3>
 * 检索的每一步改动（切块带上下文、RRF 融合、重排）都有"看起来更合理"的直觉，
 * 但直觉会骗人 —— 尤其是这类"改了 A 可能把 B 弄坏"的链路。
 * 所以先有一组固定问题 + 已知答案来源，跑出 recall@k 与 MRR，每次改动前后各跑一次。
 *
 * <h3>指标口径</h3>
 * <ul>
 *   <li>{@code recall@k}：top-k 里出现了该问题的**任一**期望来源，就算这条召回。</li>
 *   <li>{@code MRR}：第一条期望来源排在第几位，取倒数（第 1 位=1.0，第 2 位=0.5，没命中=0），再求均值。
 *       recall 看不出"排在第 1 还是第 5"，MRR 补这一刀。</li>
 *   <li>同时分别记录**只用词面**与**只用语义**的 recall@k 作为对照组 ——
 *       没有对照组，就无法判断融合到底有没有贡献。</li>
 * </ul>
 *
 * <p>评测跑的是与线上**同一条**检索链路（同一个 {@code autoRetrieve} 接口），
 * 不另写一套"评测版检索" —— 两套实现必然发散，测出来的分数就没有意义。
 */
@Service
public class RagEvalService {

    private static final Logger log = LoggerFactory.getLogger(RagEvalService.class);

    private final RagEvalMapper evalMapper;
    private final RagEvalRunMapper runMapper;
    /** 检索器：由 AgentService 实现（评测与线上共用同一条链路） */
    private final Retriever retriever;

    public RagEvalService(RagEvalMapper evalMapper, RagEvalRunMapper runMapper, Retriever retriever) {
        this.evalMapper = evalMapper;
        this.runMapper = runMapper;
        this.retriever = retriever;
    }

    /**
     * 检索器接口。实现方是 {@code AgentService}（线上那条链路）。
     * <p>用接口而不是直接依赖 AgentService：避免"评测服务 ↔ 对话服务"互相引用成环。
     */
    /**
     * 一次检索的完整产物。
     *
     * <p>为什么不拆成两个方法各取一次：检索链路里有**模型重排**，跑两遍就是两倍 token 与两倍耗时，
     * 而且两遍结果可能不一致（重排是模型判断，不是纯函数）。命中列表与证据文本必须来自同一次检索。
     *
     * @param refs 按相关度降序的 {@code type:id}（判"必要来源是否全部召回"用）
     * @param text 拼好、可直接注入模型的证据文本（为空表示什么都没检索到）
     */
    public record Evidence(List<String> refs, String text) {
        public static Evidence empty() {
            return new Evidence(List.of(), "");
        }
    }

    public interface Retriever {
        /**
         * @param mode {@code fused} 融合（线上行为） / {@code keyword} 只用词面 / {@code vector} 只用语义
         * @return 按相关度降序的 {@code type:id} 列表
         */
        List<String> retrieveRefs(String question, int limit, String mode);

        /**
         * 检索并拼成**注入给模型的证据文本**（与线上对话注入同一套拼法）。
         *
         * <p>答案级评测必须知道"模型当时到底能看到什么"：只拿 {@code type:id} 列表，
         * 既生成不了答案，也判定不了"这句话有没有证据支撑"。所以证据文本要单独取一次，
         * 且必须复用线上拼装逻辑 —— 另写一套拼法，评出来的就不是线上行为。
         *
         * <p>一次调用同时给出 refs 与 text，避免重复付出重排的模型成本。
         *
         * @param wiki 是否把主题 wiki 块一并注入（报告要求的四臂对照需要它）
         * @param kg   是否把概念图谱块一并注入
         */
        Evidence evidenceFor(String question, int limit, String mode, boolean wiki, boolean kg);
    }

    public List<RagEval> cases() {
        return evalMapper.selectList(Wrappers.<RagEval>lambdaQuery()
                .eq(RagEval::getEnabled, 1)
                .orderByAsc(RagEval::getId));
    }

    /**
     * 跑一轮评测并落库。
     *
     * @param label 本次标签（例如"改前基线"/"加 RRF"），用来在历史里对比
     * @param topK  只看前几名
     */
    public Map<String, Object> run(String label, int topK, String mode) {
        List<RagEval> cases = cases();
        if (cases.isEmpty()) {
            throw new IllegalStateException("评测集是空的：先往 rag_eval 里写用例");
        }
        int k = topK <= 0 ? 5 : topK;
        String m = StringUtils.hasText(mode) ? mode : "fused";

        int hit = 0;
        double rrSum = 0;
        int kwHit = 0;
        int vecHit = 0;
        // 缺口/无答案题（expect_refs = none）不进召回率的分母：它本来就没有"该被召回的来源"，
        // 算进去等于每加一条缺口题就白扣一分召回率。"该说不知道时有没有说"由答案级评测判（见
        // RagAnswerEvalService 的无答案处理维度），不在这一层判。
        int scored = 0;
        int gapCases = 0;
        int gapWithHits = 0;
        List<Map<String, Object>> detail = new ArrayList<>();
        for (RagEval c : cases) {
            Set<String> expect = parseRefs(c.getExpectRefs());
            List<String> refs = safeRetrieve(c.getQuestion(), k, m);
            boolean gap = isGapCase(expect);
            int rank = -1;
            if (gap) {
                gapCases++;
                if (!refs.isEmpty()) {
                    gapWithHits++;
                }
            } else {
                scored++;
                for (int i = 0; i < refs.size(); i++) {
                    if (expect.contains(refs.get(i))) {
                        rank = i + 1;
                        break;
                    }
                }
                if (rank > 0) {
                    hit++;
                    rrSum += 1.0 / rank;
                }
                // 对照组：两路分别单独跑（只在融合模式下统计，避免重复计价）
                if ("fused".equals(m)) {
                    if (containsAny(safeRetrieve(c.getQuestion(), k, "keyword"), expect)) {
                        kwHit++;
                    }
                    if (containsAny(safeRetrieve(c.getQuestion(), k, "vector"), expect)) {
                        vecHit++;
                    }
                }
            }
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("question", c.getQuestion());
            row.put("expect", expect);
            row.put("rank", rank);
            row.put("gap", gap);
            row.put("top", refs.subList(0, Math.min(refs.size(), k)));
            row.put("note", c.getNote());
            row.put("ok", !gap && rank > 0);
            detail.add(row);
        }
        int n = cases.size();
        double recall = scored == 0 ? 0 : round((double) hit / scored);
        double mrr = scored == 0 ? 0 : round(rrSum / scored);
        double kwRecall = "fused".equals(m) && scored > 0 ? round((double) kwHit / scored) : 0;
        double vecRecall = "fused".equals(m) && scored > 0 ? round((double) vecHit / scored) : 0;

        RagEvalRun run = new RagEvalRun();
        run.setLabel(label == null || label.isBlank() ? "未命名" : label.trim());
        run.setCases(n);
        run.setTopK(k);
        run.setRecallAtK(recall);
        run.setMrr(mrr);
        run.setKeywordRecall(kwRecall);
        run.setVectorRecall(vecRecall);
        run.setDetail(toJson(detail));
        runMapper.insert(run);

        log.info("检索评测[{}]：{} 条用例（召回统计 {} 条，缺口题 {} 条已排除分母），recall@{}={}, MRR={}（对照：词面 {} / 语义 {}）",
                run.getLabel(), n, scored, gapCases, k, recall, mrr, kwRecall, vecRecall);
        Map<String, Object> o = new LinkedHashMap<>();
        o.put("runId", run.getId());
        o.put("label", run.getLabel());
        o.put("cases", n);
        o.put("scoredCases", scored);
        o.put("gapCases", gapCases);
        o.put("gapWithHits", gapWithHits);
        o.put("topK", k);
        o.put("mode", m);
        o.put("recallAtK", recall);
        o.put("mrr", mrr);
        o.put("keywordRecall", kwRecall);
        o.put("vectorRecall", vecRecall);
        o.put("missed", detail.stream()
                .filter(r -> !Boolean.TRUE.equals(r.get("gap")) && !Boolean.TRUE.equals(r.get("ok")))
                .toList());
        return o;
    }

    /** 历史运行（用来对比改动前后） */
    public List<RagEvalRun> history() {
        return runMapper.selectList(Wrappers.<RagEvalRun>lambdaQuery()
                .orderByDesc(RagEvalRun::getId).last("LIMIT 20"));
    }

    private List<String> safeRetrieve(String q, int k, String mode) {
        try {
            List<String> refs = retriever.retrieveRefs(q, k, mode);
            return refs == null ? List.of() : refs;
        } catch (Exception e) {
            log.warn("评测检索失败（{}）：{}", q, e.toString());
            return List.of();
        }
    }

    private static boolean containsAny(List<String> refs, Set<String> expect) {
        for (String r : refs) {
            if (expect.contains(r)) {
                return true;
            }
        }
        return false;
    }

    /** {@code note:5|file:2} → 集合 */
    static Set<String> parseRefs(String raw) {
        Set<String> out = new LinkedHashSet<>();
        if (raw == null) {
            return out;
        }
        for (String p : raw.split("[|,，]")) {
            String s = p.trim();
            if (!s.isEmpty()) {
                out.add(s);
            }
        }
        return out;
    }

    /**
     * 是否是**库内缺口 / 无答案**题：期望来源为空，或明确写成 {@code none}。
     *
     * <p>这类题的正确答案就是"库里没记这个"，所以它们**不能**进召回率的分母 ——
     * 否则每加一条缺口题都白扣一分 recall，指标会随着评测集的扩充而"假性退步"。
     * 它们考的是"该说不知道时有没有说"，由答案级评测的 noAnswerScore 判定。
     */
    static boolean isGapCase(Set<String> expect) {
        if (expect.isEmpty()) {
            return true;
        }
        for (String s : expect) {
            if (!isNoneRef(s)) {
                return false;
            }
        }
        return true;
    }

    /** {@code none} 的各种写法都认（人工标注时很难统一） */
    private static boolean isNoneRef(String s) {
        String t = s == null ? "" : s.trim().toLowerCase();
        return t.isEmpty() || "none".equals(t) || "null".equals(t) || "-".equals(t)
                || "n/a".equals(t) || "na".equals(t) || "无".equals(t) || "无答案".equals(t)
                || "缺口".equals(t);
    }

    private static double round(double v) {
        return Math.round(v * 1000) / 1000.0;
    }

    private String toJson(List<Map<String, Object>> detail) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < detail.size(); i++) {
            Map<String, Object> r = detail.get(i);
            if (i > 0) {
                sb.append(',');
            }
            sb.append("{\"q\":\"").append(esc(String.valueOf(r.get("question"))))
              .append("\",\"expect\":\"").append(esc(String.join("|", ((Set<?>) r.get("expect")).stream().map(String::valueOf).toList())))
              .append("\",\"rank\":").append(r.get("rank"))
              .append(",\"gap\":").append(Boolean.TRUE.equals(r.get("gap")))
              .append(",\"top\":\"").append(esc(String.join("|", ((List<?>) r.get("top")).stream().map(String::valueOf).toList())))
              .append("\"}");
        }
        return sb.append(']').toString();
    }

    private static String esc(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
