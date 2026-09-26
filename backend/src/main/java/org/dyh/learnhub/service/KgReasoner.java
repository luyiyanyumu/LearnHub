package org.dyh.learnhub.service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 符号推理（Symbolic Reasoning）—— 知识图谱真正区别于"一堆连线"的地方。
 *
 * <p>图上直接存的三元组是有限的，但**隐含事实**可以按本体公理推出来。本类实现两条最常用、
 * 且不会引入错误的规则（规则来自 {@link KgOntology.Rel} 上声明的属性，不是硬编码关系名）：
 *
 * <ul>
 *   <li><b>传递</b>（is_a / part_of / prerequisite）：
 *       {@code Maven → part_of → 构建工具}、{@code 构建工具 → part_of → 工程化}
 *       ⇒ 推出 {@code Maven → part_of → 工程化}。链长不超过 {@link #MAX_DEPTH} 跳，
 *       权重按每跳衰减（{@link #DECAY}），否则长的推导链会跟直接事实一样可信。</li>
 *   <li><b>对称</b>（contrast_with / related_to）：{@code A contrast_with B} ⇒ {@code B contrast_with A}。</li>
 * </ul>
 *
 * <p>推导边一律带 {@code origin=derived} 与 {@code derived_from}（依据的两条边 id），
 * 这样界面上能和模型直接抽取的事实区分开，也能整批撤回重推 ——
 * **推导结果必须可溯源**，否则图上会分不清"看到的"和"以为的"。
 *
 * <p>刻意不做的：{@code used_for} 之类不满足上述代数性质的关系不做闭包；
 * 也不做"否定的传递"或默认推理（那需要一致性约束，规模不到位时只会制造假事实）。
 */
public final class KgReasoner {

    private KgReasoner() {
    }

    /** 推导链的最大跳数：3 跳以内基本不会错，再长就该重新抽取而不是硬推 */
    public static final int MAX_DEPTH = 3;

    /** 每多一跳权重乘这个系数，推导出的事实可信度必须低于直接抽取的 */
    public static final double DECAY = 0.7;

    /** 低于这个权重就不再往外推（避免一路推成 0.1 的废话） */
    public static final double MIN_WEIGHT = 0.25;

    /**
     * 一条待推理的边（与存储解耦，便于单测）。
     *
     * @param key      边的唯一键，用于写回 derived_from
     * @param head     头实体 id
     * @param relation 规范关系 id
     * @param tail     尾实体 id
     * @param weight   置信度
     */
    public record Edge(String key, String head, String relation, String tail, double weight) {
    }

    /**
     * 跑一轮闭包。**不修改入参**，只返回新增的推导边（已存在的直接事实不会重复出现）。
     */
    public static List<Edge> derive(List<Edge> existing) {
        Set<String> known = new LinkedHashSet<>();
        List<Edge> all = new ArrayList<>();
        for (Edge e : existing) {
            known.add(tripleKey(e.head(), e.relation(), e.tail()));
            all.add(e);
        }
        List<Edge> fresh = new ArrayList<>();

        // ① 对称：把反向补上
        for (Edge e : existing) {
            KgOntology.Rel r = KgOntology.byId(e.relation());
            if (r == null || !r.symmetric()) {
                continue;
            }
            String k = tripleKey(e.tail(), e.relation(), e.head());
            if (known.add(k)) {
                Edge d = new Edge("sym:" + e.key(), e.tail(), e.relation(), e.head(), e.weight());
                all.add(d);
                fresh.add(d);
            }
        }

        // ② 传递闭包：逐跳展开，每跳只从上一跳新增的边出发（等价于 BFS，避免重复计算）
        List<Edge> frontier = new ArrayList<>(all);
        for (int depth = 2; depth <= MAX_DEPTH; depth++) {
            List<Edge> next = new ArrayList<>();
            for (Edge a : frontier) {
                KgOntology.Rel ra = KgOntology.byId(a.relation());
                if (ra == null || !ra.transitive()) {
                    continue;
                }
                for (Edge b : all) {
                    // 只接"同一关系、首尾相接"的链：a.head →rel→ a.tail == b.head →rel→ b.tail
                    if (!a.relation().equals(b.relation()) || !a.tail().equals(b.head())) {
                        continue;
                    }
                    if (a.head().equals(b.tail())) {
                        continue; // 自环无意义
                    }
                    double w = Math.min(a.weight(), b.weight()) * DECAY;
                    if (w < MIN_WEIGHT) {
                        continue;
                    }
                    String k = tripleKey(a.head(), a.relation(), b.tail());
                    if (known.add(k)) {
                        Edge d = new Edge("tr:" + a.key() + "|" + b.key(), a.head(), a.relation(), b.tail(), w);
                        next.add(d);
                        fresh.add(d);
                    }
                }
            }
            if (next.isEmpty()) {
                break;
            }
            all.addAll(next);
            frontier = next;
        }
        return fresh;
    }

    /** 三元组唯一键：与库里的 uk_kg_triple 保持一致 */
    public static String tripleKey(String head, String relation, String tail) {
        return head + "|" + relation + "|" + tail;
    }

    /**
     * 反向可达性说明：给定一条边，它能不能用来回答"A 和 B 有什么关系"。
     * <p>对称关系两个方向都算；非对称关系只认正向 —— 否则"栈属于 JVM"会变成"JVM 属于栈"。
     */
    public static boolean traversable(String relation, boolean forward) {
        KgOntology.Rel r = KgOntology.byId(relation);
        if (r == null) {
            return forward;
        }
        return forward || r.symmetric();
    }

    /** 给界面用的关系代数摘要（哪些能推、往哪个方向走） */
    public static Map<String, Object> describe() {
        Map<String, Object> o = new LinkedHashMap<>();
        o.put("maxDepth", MAX_DEPTH);
        o.put("decay", DECAY);
        o.put("minWeight", MIN_WEIGHT);
        return o;
    }
}
