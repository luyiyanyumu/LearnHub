package org.dyh.learnhub.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Leiden 的判据。重点不是"跑得通"，而是三件会直接决定上层行为的事：
 * 划分**质量**（该分开的簇要分开）、**连通性**（社区摘要不能把不相干的簇混在一起）、
 * **确定性**（同图同种子必须同划分，否则摘要缓存每次失效、白花钱）。
 */
class LeidenCommunityTest {

    /** 两个 4 点团 + 它们之间唯一一条桥边（经典的哑铃图） */
    private static double[][] twoCliques() {
        List<double[]> edges = new ArrayList<>();
        for (int[] clique : new int[][] { { 0, 1, 2, 3 }, { 4, 5, 6, 7 } }) {
            for (int i : clique) {
                for (int j : clique) {
                    if (i < j) edges.add(new double[] { i, j, 1 });
                }
            }
        }
        edges.add(new double[] { 3, 4, 1 });
        return edges.toArray(new double[0][]);
    }

    @Test
    @DisplayName("哑铃图：两个团各成一个社区，桥不算同一簇")
    void splitsTwoCliques() {
        LeidenCommunity.Result r = LeidenCommunity.detect(8, twoCliques(), 1.0, 42);
        assertEquals(2, r.communityCount(), "应恰好切成两个社区");
        assertEquals(r.partition()[0], r.partition()[3], "团内 0..3 必须同社区");
        assertEquals(r.partition()[4], r.partition()[7], "团内 4..7 必须同社区");
        assertNotEquals(r.partition()[0], r.partition()[4], "两个团必须分开");
        // 这种图的正确划分 Q ≈ 0.42（12 条内部边 / m=13，减去两社区的度数罚项）。
        assertTrue(r.modularity() > 0.3, "这种图模块度应明显为正，实际 " + r.modularity());
    }

    @Test
    @DisplayName("模块度的快照判据：整图合成一个社区时 Q 必须为 0")
    void modularityBaselineIsZero() {
        double[][] graph = twoCliques();
        LeidenCommunity.WorkGraph built = LeidenCommunity.build(8, graph, null);
        int[] single = new int[8];                              // 全部同一个社区
        assertEquals(0.0, LeidenCommunity.modularity(built, single, 1.0), 1e-9,
                "单社区划分的模块度定义上就是 0，罚项分母少一个 2 会得到 −0.5");
    }

    @Test
    @DisplayName("每个社区必须连通 —— Leiden 相对 Louvain 的关键保证")
    void everyCommunityIsConnected() {
        // 三团两桥：Louvain 常把中间团拆散或与邻团黏在一起，这里逐社区做 BFS
        List<double[]> edges = new ArrayList<>();
        for (int[] clique : new int[][] { { 0, 1, 2 }, { 3, 4, 5 }, { 6, 7, 8 } }) {
            for (int i : clique) {
                for (int j : clique) {
                    if (i < j) edges.add(new double[] { i, j, 1 });
                }
            }
        }
        edges.add(new double[] { 2, 3, 1 });
        edges.add(new double[] { 5, 6, 1 });
        double[][] graph = edges.toArray(new double[0][]);
        LeidenCommunity.Result r = LeidenCommunity.detect(9, graph, 1.0, 7);

        Map<Integer, List<Integer>> members = new HashMap<>();
        for (int v = 0; v < 9; v++) members.computeIfAbsent(r.partition()[v], k -> new ArrayList<>()).add(v);
        for (Map.Entry<Integer, List<Integer>> e : members.entrySet()) {
            Deque<Integer> queue = new ArrayDeque<>();
            List<Integer> seen = new ArrayList<>();
            queue.add(e.getValue().get(0));
            seen.add(e.getValue().get(0));
            while (!queue.isEmpty()) {
                int v = queue.poll();
                for (double[] edge : graph) {
                    int u = -1;
                    if ((int) edge[0] == v) u = (int) edge[1];
                    else if ((int) edge[1] == v) u = (int) edge[0];
                    if (u >= 0 && members.get(e.getKey()).contains(u) && !seen.contains(u)) {
                        seen.add(u);
                        queue.add(u);
                    }
                }
            }
            assertEquals(e.getValue().size(), seen.size(),
                    "社区 " + e.getKey() + " 内部不连通：" + e.getValue() + "，只走通了 " + seen);
        }
    }

    @Test
    @DisplayName("同一张图 + 同一个种子：两次结果完全一致（摘要缓存的前提）")
    void deterministic() {
        double[][] graph = twoCliques();
        assertArrayEquals(LeidenCommunity.detect(8, graph, 1.0, 2024).partition(),
                LeidenCommunity.detect(8, graph, 1.0, 2024).partition());
    }

    @Test
    @DisplayName("分辨率越大社区越细（γ 的上界行为：每个点自成社区）")
    void resolutionSplitsFiner() {
        double[][] graph = twoCliques();
        int coarse = LeidenCommunity.detect(8, graph, 0.2, 5).communityCount();
        int fine = LeidenCommunity.detect(8, graph, 50.0, 5).communityCount();
        assertTrue(fine >= coarse, "γ 变大不该让社区变少：coarse=" + coarse + " fine=" + fine);
    }

    @Test
    @DisplayName("孤立点、空图、无优雅退出都不抛异常")
    void degenerateInputs() {
        LeidenCommunity.Result empty = LeidenCommunity.detect(0, new double[0][], 1.0, 1);
        assertEquals(0, empty.communityCount());

        LeidenCommunity.Result isolated = LeidenCommunity.detect(3, new double[][] { { 0, 1, 1 } }, 1.0, 1);
        assertTrue(isolated.communityCount() <= 3);

        LeidenCommunity.Result none = LeidenCommunity.detect(2, null, 1.0, 1);
        assertEquals(2, none.communityCount(), "没有边时每点自成社区");
    }

    @Test
    @DisplayName("划分的模块度不差于「每点一个社区」的基线")
    void modularityBeatsTrivialBaseline() {
        double[][] graph = twoCliques();
        LeidenCommunity.Result r = LeidenCommunity.detect(8, graph, 1.0, 3);
        LeidenCommunity.WorkGraph built = LeidenCommunity.build(8, graph, null);
        int[] trivial = new int[8];
        for (int i = 0; i < 8; i++) trivial[i] = i;                      // 每点一个社区
        double baseline = LeidenCommunity.modularity(built, trivial, 1.0);
        assertTrue(r.modularity() >= baseline - 1e-9,
                "划分" + Arrays.toString(r.partition()) + " 的 Q=" + r.modularity() + " 低于基线 " + baseline);
    }

    @Test
    @DisplayName("并行边按权重累加，不重复计入度数")
    void parallelEdgesMerge() {
        // 0—1 有两条权重 1 的边，等价于一条权重 2；0 的度数应为 2
        LeidenCommunity.WorkGraph g = LeidenCommunity.build(2, new double[][] { { 0, 1, 1 }, { 0, 1, 1 } }, null);
        assertEquals(2.0, g.degree(0), 1e-9);
        assertEquals(2.0, g.totalWeight(), 1e-9);
    }
}
