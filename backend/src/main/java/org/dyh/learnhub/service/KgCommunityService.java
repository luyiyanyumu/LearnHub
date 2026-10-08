package org.dyh.learnhub.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.dyh.learnhub.mapper.KgCommunityMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * GraphRAG 的社区层：把概念图划成社区并落库。
 *
 * <p>社区是 GraphRAG 里"全局视角"的载体：实体页回答"这个是什么"，社区摘要回答"这一簇概念整体在讲什么"。
 * 划分用 {@link LeidenCommunity}（自实现、确定），结果整批写进 {@code kg_community}。
 *
 * <p>两个常量固定下来，因为划分会参与摘要缓存；摘要还校验关系、证据、来源正文与模型身份：
 * 种子固定 → 同一张图两次跑出同一划分；分辨率固定 → 社区尺度不随调用漂移。要调尺度就改这里，
 * 并且明白改了之后所有社区摘要都会失效重算（这是预期行为，不是 bug）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class KgCommunityService {

    /** 固定随机种子：只影响同增益时的访问顺序，固定它是为了"同图同划分"可复现 */
    private static final long SEED = 20261006L;

    /** 分辨率 γ：1.0 是标准值，越大社区越碎 */
    private static final double RESOLUTION = 1.0;

    private final KgCommunityMapper mapper;

    /**
     * 全量重算社区并落库。返回摘要（社区数、模块度、规模分布、耗时），界面/日志都用它判断划分是否合理：
     * 只有一两个社区说明图太稠或 γ 太小；全是单点社区说明图太散或 γ 太大。
     */
    @Transactional
    public Map<String, Object> recompute() {
        return recompute(true);
    }

    /**
     * 重算社区并落库。
     *
     * <p>{@code force=false} 时比较持久化的图内容指纹；节点或加权边没有变化时跳过。
     * 摘要缓存独立校验概念、证据、来源正文与模型身份，社区编号变化时可复用并重新关联。
     */
    @Transactional
    public synchronized Map<String, Object> recompute(boolean force) {
        Map<String, Object> out = new LinkedHashMap<>();
        List<String> ids = mapper.nodeIds();
        List<Map<String, Object>> edgeRows = mapper.edges();
        String fingerprint = graphFingerprint(ids, edgeRows);
        // 不用 MAX(timestamp)：它看不见删除，也会漏掉 DATETIME 同一秒内的变更。
        // 首次升级缺少指纹时保守重算，指纹与划分在同一个事务提交。
        if (!force && fingerprint.equals(mapper.partitionFingerprint()) && membershipMatches(ids)) {
            out.put("skipped", true);
            out.put("reason", "节点与加权关系均未变化");
            out.put("ms", 0);
            log.info("社区划分跳过：图谱未变化");
            return out;
        }
        out.put("skipped", false);
        out.putAll(doRecompute(ids, edgeRows, fingerprint));
        return out;
    }

    private Map<String, Object> doRecompute(List<String> ids, List<Map<String, Object>> edgeRows,
                                          String fingerprint) {
        long started = System.currentTimeMillis();
        Map<String, Integer> index = new HashMap<>();
        for (int i = 0; i < ids.size(); i++) index.put(ids.get(i), i);

        List<double[]> edges = new ArrayList<>();
        int dangling = 0;
        for (Map<String, Object> row : edgeRows) {
            Integer u = index.get(String.valueOf(row.get("head")));
            Integer v = index.get(String.valueOf(row.get("tail")));
            if (u == null || v == null) {
                dangling++;                                   // 实体被删但边还在：跳过，别让整次划分失败
                continue;
            }
            double w = row.get("w") instanceof Number n ? n.doubleValue() : 1.0;
            if (!Double.isFinite(w) || w <= 0) continue;
            edges.add(new double[] { u, v, w });
        }

        LeidenCommunity.Result result = LeidenCommunity.detect(
                ids.size(), edges.toArray(new double[0][]), RESOLUTION, SEED);

        int[] sizes = new int[Math.max(1, result.communityCount())];
        for (int c : result.partition()) sizes[c]++;
        int singletons = 0;
        int largest = 0;
        for (int c = 0; c < result.communityCount(); c++) {
            if (sizes[c] == 1) singletons++;
            largest = Math.max(largest, sizes[c]);
        }

        mapper.clear();
        for (int i = 0; i < ids.size(); i++) {
            int c = result.partition()[i];
            mapper.insert(ids.get(i), c, 0, sizes[c], result.modularity());
        }
        mapper.savePartitionFingerprint(fingerprint);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("nodes", ids.size());
        out.put("edges", edges.size());
        out.put("danglingEdges", dangling);
        out.put("communities", result.communityCount());
        out.put("singletons", singletons);
        out.put("largest", largest);
        out.put("modularity", result.modularity());
        out.put("resolution", RESOLUTION);
        out.put("ms", System.currentTimeMillis() - started);
        log.info("社区划分完成：{} 节点 / {} 边 → {} 个社区（单点 {}，最大 {}），Q={}，{}ms",
                ids.size(), edges.size(), result.communityCount(), singletons, largest,
                String.format("%.4f", result.modularity()), out.get("ms"));
        return out;
    }

    /** 每个节点的社区归属（页面着色用） */
    public List<Map<String, Object>> communities() {
        return mapper.all();
    }

    /** 只读检查划分是否需要刷新；不隐式重算或修改持久状态。 */
    @Transactional(readOnly = true)
    public boolean stale() {
        List<String> ids = mapper.nodeIds();
        return !graphFingerprint(ids, mapper.edges()).equals(mapper.partitionFingerprint()) || !membershipMatches(ids);
    }

    /** 按社区聚合的视图：{@code [{communityId, size, modularity, nodeIds}]}，按规模从大到小 */
    public List<Map<String, Object>> grouped() {
        Map<Integer, Map<String, Object>> byCommunity = new LinkedHashMap<>();
        for (Map<String, Object> row : mapper.all()) {
            int id = ((Number) row.get("communityId")).intValue();
            Map<String, Object> group = byCommunity.computeIfAbsent(id, k -> {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("communityId", k);
                m.put("size", row.get("size"));
                m.put("modularity", row.get("modularity"));
                m.put("computedAt", row.get("computedAt"));
                m.put("nodeIds", new ArrayList<String>());
                return m;
            });
            @SuppressWarnings("unchecked")
            List<String> nodeIds = (List<String>) group.get("nodeIds");
            nodeIds.add(String.valueOf(row.get("nodeId")));
        }
        List<Map<String, Object>> out = new ArrayList<>(byCommunity.values());
        // 用实际成员数，避免历史行的 size 不一致导致转型异常或错误显示。
        for (Map<String, Object> group : out) {
            @SuppressWarnings("unchecked")
            List<String> members = (List<String>) group.get("nodeIds");
            members.sort(String::compareTo);
            group.put("size", members.size());
        }
        out.sort((a, b) -> Integer.compare(((Number) b.get("size")).intValue(), ((Number) a.get("size")).intValue()));
        return out;
    }

    private boolean membershipMatches(List<String> ids) {
        List<String> assigned = mapper.all().stream().map(row -> String.valueOf(row.get("nodeId"))).sorted().toList();
        return ids.stream().sorted().toList().equals(assigned);
    }

    static String graphFingerprint(List<String> ids, List<Map<String, Object>> edges) {
        List<String> rows = new ArrayList<>();
        for (String id : ids) rows.add("node:" + id);
        for (Map<String, Object> edge : edges) {
            rows.add("edge:" + edge.get("head") + ":" + edge.get("tail") + ":" + edge.get("w"));
        }
        rows.sort(String::compareTo);
        return KgCommunitySummaryService.digest("partition-v2|" + SEED + "|" + RESOLUTION + "|" + rows);
    }
}
