package org.dyh.learnhub.service;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * Leiden 社区检测（Traag–Waltman–van Eck 2019），自实现、零新依赖、结果确定。
 *
 * <h3>为什么自己写而不是引库</h3>
 * <ol>
 *   <li>本工程到今天为止没有引入任何图算法库，图谱遍历（{@link KgGraphService}、{@link KgReasoner}）
 *       都是手写的，为一个算法拉一条依赖不划算；</li>
 *   <li>更关键的是**确定性**：社区划分要当缓存键用（社区成员哈希 → 社区摘要缓存），
 *       同一张图两次跑出不同划分会让摘要反复失效、白花钱。第三方实现的并行度/随机种子不好控，
 *       自己写就能保证「同一张图 + 同一个种子 = 同一个划分」。</li>
 * </ol>
 *
 * <h3>它做了什么</h3>
 * 标准 Leiden 的三步，逐层（level）迭代到模块度不再提升：
 * <ol>
 *   <li><b>局部移动</b>（local moving，与 Louvain 同）：每个点试着搬进邻居社区，取增益最大且为正的那次；</li>
 *   <li><b>细化</b>（refinement）：把每个社区**拆回单点**再在社区内部合并 —— 只有存在边的集合才会合并，
 *       于是每个社区**一定连通**。这正是 Leiden 比 Louvain 强的地方：Louvain 会产出"内部断成几块"的社区，
 *       摘要时把不相干的簇混在一起；</li>
 *   <li><b>聚合</b>（aggregation）：把细化后的社区各自缩成一个点，重边累加，回到第 1 步。</li>
 * </ol>
 *
 * <h3>输入输出</h3>
 * 入参是边表（无向、带权、允许自环）与分辨率 γ；出参给每个节点的社区编号与整图模块度。
 * 节点编号必须从 0 连续到 nodeCount-1（调用方负责把数据库里的实体 id 映射过来）。
 */
public final class LeidenCommunity {

    /** 社区划分结果：{@code partition[v]} 是节点 v 的社区编号（从 0 连续），外加社区数与模块度 */
    public record Result(int[] partition, int communityCount, double modularity) {}

    /** 迭代上限：Leiden 通常 3~5 层就收敛，这里给足余量又不至于卡死 */
    private static final int MAX_LEVELS = 24;

    /** 增益比较的容差：浮点误差下"看起来一样好"的搬迁不做，保证同一张图的路径稳定 */
    private static final double EPS = 1e-9;

    private LeidenCommunity() {}

    /**
     * 检测社区。
     *
     * @param nodeCount  节点数（编号 0..nodeCount-1）
     * @param edges      边表，每行 {u, v, weight}；weight ≤ 0 会被忽略
     * @param resolution 分辨率 γ：越大社区越小越碎（默认 1.0）
     * @param seed       随机种子（只影响同增益时的访问顺序，不影响结果质量）
     */
    public static Result detect(int nodeCount, double[][] edges, double resolution, long seed) {
        if (nodeCount <= 0) return new Result(new int[0], 0, 0);
        WorkGraph graph = build(nodeCount, edges, null);
        Random random = new Random(seed);
        int[] best = identity(nodeCount);
        double bestScore = modularity(graph, best, resolution);
        // nodeToLevel：原始节点 → 当前层图的节点编号（每聚合一次就往上复合一层）
        int[] nodeToLevel = identity(nodeCount);
        int[] composed = best.clone();

        for (int level = 0; level < MAX_LEVELS; level++) {
            int[] moved = localMove(graph, resolution, random, null);
            int[] refined = refine(graph, moved, resolution, random);
            double score = modularity(graph, refined, resolution);
            if (score <= bestScore + EPS) break;              // 不再变好就收工（避免来回震荡）
            bestScore = score;
            for (int v = 0; v < nodeCount; v++) {
                nodeToLevel[v] = refined[nodeToLevel[v]];
                composed[v] = nodeToLevel[v];
            }
            if (graph.n() == countCommunities(refined)) break; // 每个社区只剩一个点，再聚合也没意义
            graph = aggregate(graph, refined);
        }
        int[] norm = compact(composed);
        return new Result(norm, countCommunities(norm), modularity(build(nodeCount, edges, null), norm, resolution));
    }

    /**
     * 模块度 Q（无向带权）：{@code Q = Σ_in/m − γ·Σ(tot_c)²/(4m²)}，其中 Σ_in 是内部边权重之和
     * （每条边算一次）、tot_c 是社区 c 内各点度数之和。
     *
     * <p>分母是 **4m²** 不是 2m²：经典写法 {@code Q = Σ_c[Σin_c/(2m) − (Σtot_c/(2m))²]} 里 Σin_c 是内部边的
     * **两倍**，换成"每条边算一次"的形式后自然变成 4m²。快照判据：整张图合成一个社区时 Q 必须等于 0，
     * 漏掉这个 2 会得到 −0.5（实测踩到）。
     *
     * <p>公式写错会**静默**毁掉整个检测：它不是给用户看的数字，而是"细化后的划分是否更好"的判据 ——
     * 罚项一旦算错，第 0 层就判定没有改善、直接返回初始的"每点一社区"（哑铃图该出 2 个社区，结果出来 8 个）。
     */
    public static double modularity(WorkGraph graph, int[] partition, double resolution) {
        int n = graph.n();
        double m = graph.totalWeight();
        if (n <= 0 || m <= 0 || partition.length < n) return 0;
        double[] total = new double[n];
        for (int v = 0; v < n; v++) total[partition[v]] += graph.degree(v);
        double inside = 0;
        for (int v = 0; v < n; v++) {
            int[] nbr = graph.neighbors()[v];
            double[] weight = graph.weights()[v];
            for (int i = 0; i < nbr.length; i++) {
                if (partition[nbr[i]] == partition[v]) inside += weight[i];
            }
        }
        inside /= 2.0;                                        // 每条内部边被数了两次
        double penalty = 0;
        for (double t : total) penalty += t * t;
        return inside / m - resolution * penalty / (4 * m * m);
    }

    // ---------------------------------------------------------------- 三步

    /** 局部移动：逐点尝试搬进"增益为正且最大"的邻居社区（同一层内反复扫到不动为止） */
    private static int[] localMove(WorkGraph graph, double resolution, Random random, boolean[] frozen) {
        int n = graph.n();
        int[] community = identity(n);
        double m2 = graph.totalWeight() * 2.0;
        double[] total = new double[n];
        for (int v = 0; v < n; v++) total[v] = graph.degree(v);
        if (m2 <= 0) return community;

        boolean moved = true;
        int rounds = 0;
        while (moved && rounds++ < 64) {
            moved = false;
            for (int v : shuffled(n, random)) {
                if (frozen != null && frozen[v]) continue;
                Map<Integer, Double> toCommunity = new HashMap<>();
                int[] nbr = graph.neighbors()[v];
                double[] weight = graph.weights()[v];
                for (int i = 0; i < nbr.length; i++) {
                    if (nbr[i] == v) continue;
                    toCommunity.merge(community[nbr[i]], weight[i], Double::sum);
                }
                double degree = graph.degree(v);
                int from = community[v];
                total[from] -= degree;
                int best = from;
                double bestGain = 0;
                for (Map.Entry<Integer, Double> e : toCommunity.entrySet()) {
                    // ΔQ 的常用形式：w(v→C) − γ·tot(C)·k_v/(2m)
                    double gain = e.getValue() - resolution * total[e.getKey()] * degree / m2;
                    if (gain > bestGain + EPS || (Math.abs(gain - bestGain) <= EPS && gain > 0 && e.getKey() < best)) {
                        bestGain = gain;
                        best = e.getKey();
                    }
                }
                total[best] += degree;
                if (best != from) {
                    community[v] = best;
                    moved = true;
                }
            }
        }
        return community;
    }

    /**
     * 细化：把每个社区拆回单点，只允许**社区内部、且相邻**的集合合并。
     * 因为每次合并都跨一条真实存在的边，合并出来的每个社区必然连通 —— 这是 Leiden 的关键保证。
     */
    private static int[] refine(WorkGraph graph, int[] community, double resolution, Random random) {
        int n = graph.n();
        int[] out = new int[n];
        Map<Integer, List<Integer>> members = new LinkedHashMap<>();
        for (int v = 0; v < n; v++) members.computeIfAbsent(community[v], k -> new ArrayList<>()).add(v);
        int next = 0;
        for (List<Integer> group : members.values()) {
            if (group.size() == 1) {
                out[group.get(0)] = next++;
                continue;
            }
            // 社区内部重新起一张小图，用同样的局部移动（起点是"每点一个社区"）
            Map<Integer, Integer> index = new HashMap<>();
            for (int v : group) index.put(v, index.size());
            double[][] local = new double[group.size() * 4][];
            int k = 0;
            for (int v : group) {
                int[] nbr = graph.neighbors()[v];
                double[] weight = graph.weights()[v];
                for (int i = 0; i < nbr.length; i++) {
                    Integer j = index.get(nbr[i]);
                    if (j == null || nbr[i] == v) continue;   // 只保留社区内部边
                    if (k == local.length) local = Arrays.copyOf(local, k * 2);
                    local[k++] = new double[] { index.get(v), j, weight[i] };
                }
            }
            int[] sub = localMove(build(group.size(), Arrays.copyOf(local, k), null), resolution, random, null);
            for (int v : group) out[v] = next + sub[index.get(v)];
            next += countCommunities(sub) == 0 ? 1 : Arrays.stream(sub).max().orElse(0) + 1;
        }
        return out;
    }

    /** 聚合：把每个社区缩成一个点，重边与自环权重累加 */
    private static WorkGraph aggregate(WorkGraph graph, int[] community) {
        int groups = countCommunities(community);
        Map<Long, Double> merged = new HashMap<>();
        for (int v = 0; v < graph.n(); v++) {
            int[] nbr = graph.neighbors()[v];
            double[] weight = graph.weights()[v];
            for (int i = 0; i < nbr.length; i++) {
                long a = community[v], b = community[nbr[i]];
                long key = a <= b ? a * 1_000_003L + b : b * 1_000_003L + a;
                if (a == b) key = a * 1_000_003L + a;
                merged.merge(key, weight[i], Double::sum);
            }
        }
        double[][] edges = new double[merged.size()][];
        int k = 0;
        for (Map.Entry<Long, Double> e : merged.entrySet()) {
            long key = e.getKey();
            long a = key / 1_000_003L, b = key % 1_000_003L;
            edges[k++] = new double[] { a, b, a == b ? e.getValue() / 2.0 : e.getValue() };
        }
        double[][] sorted = Arrays.copyOf(edges, k);
        Arrays.sort(sorted, (x, y) -> x[0] != y[0] ? Double.compare(x[0], y[0]) : Double.compare(x[1], y[1]));
        return build(groups, sorted, null);
    }

    // ---------------------------------------------------------------- 图与工具

    /** 无向带权图的紧凑表示：邻接表 + 权重，另有总权重与各点度数（增量维护用） */
    public static final class WorkGraph {
        private final int n;
        private final int[][] neighbors;
        private final double[][] weights;
        private final double totalWeight;
        private final double[] degree;
        /** 原始节点 → 本层节点（多层聚合后回填用） */
        private final int[] origin;

        WorkGraph(int n, int[][] neighbors, double[][] weights, double totalWeight, double[] degree, int[] origin) {
            this.n = n;
            this.neighbors = neighbors;
            this.weights = weights;
            this.totalWeight = totalWeight;
            this.degree = degree;
            this.origin = origin;
        }

        public int n() { return n; }
        public int[][] neighbors() { return neighbors; }
        public double[][] weights() { return weights; }
        public double totalWeight() { return totalWeight; }
        public double degree(int v) { return degree[v]; }
    }

    /** 由边表构图：先按 (u,v) 累加（并行边合并、去重），再按邻居编号排序 —— 顺序稳定结果才确定 */
    static WorkGraph build(int n, double[][] edges, int[] origin) {
        Map<Integer, Map<Integer, Double>> adj = new HashMap<>();
        double total = 0;
        for (double[] e : edges == null ? new double[0][] : edges) {
            if (e == null || e.length < 3) continue;
            int u = (int) e[0], v = (int) e[1];
            double w = e[2];
            if (w <= 0 || u < 0 || v < 0 || u >= n || v >= n) continue;
            adj.computeIfAbsent(u, k -> new HashMap<>()).merge(v, w, Double::sum);
            if (u != v) adj.computeIfAbsent(v, k -> new HashMap<>()).merge(u, w, Double::sum);
            total += w;
        }
        int[][] neighbors = new int[n][];
        double[][] weights = new double[n][];
        double[] degree = new double[n];
        for (int u = 0; u < n; u++) {
            Map<Integer, Double> row = adj.get(u);
            if (row == null) {
                neighbors[u] = new int[0];
                weights[u] = new double[0];
                continue;
            }
            int[] vs = row.keySet().stream().mapToInt(Integer::intValue).sorted().toArray();
            neighbors[u] = vs;
            weights[u] = new double[vs.length];
            for (int i = 0; i < vs.length; i++) {
                weights[u][i] = row.get(vs[i]);
                degree[u] += weights[u][i];
            }
        }
        return new WorkGraph(n, neighbors, weights, total, degree, origin);
    }

    private static int mapOf(WorkGraph graph, int v) { return graph.origin == null ? v : graph.origin[v]; }

    private static int[] identity(int n) {
        int[] p = new int[n];
        for (int i = 0; i < n; i++) p[i] = i;
        return p;
    }

    private static int[] shuffled(int n, Random random) {
        int[] order = identity(n);
        for (int i = n - 1; i > 0; i--) {                     // Fisher–Yates，种子固定 → 顺序固定
            int j = random.nextInt(i + 1);
            int t = order[i];
            order[i] = order[j];
            order[j] = t;
        }
        return order;
    }

    private static int countCommunities(int[] partition) {
        int max = -1;
        for (int c : partition) max = Math.max(max, c);
        return max + 1;
    }

    /** 把社区编号压成 0..k-1 连续（聚合与多层复合后编号会有空洞） */
    private static int[] compact(int[] partition) {
        Map<Integer, Integer> seen = new LinkedHashMap<>();
        int[] out = new int[partition.length];
        for (int i = 0; i < partition.length; i++) {
            out[i] = seen.computeIfAbsent(partition[i], k -> seen.size());
        }
        return out;
    }
}
