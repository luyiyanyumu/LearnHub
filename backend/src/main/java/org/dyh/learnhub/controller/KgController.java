package org.dyh.learnhub.controller;

import lombok.RequiredArgsConstructor;
import org.dyh.learnhub.common.Result;
import org.dyh.learnhub.service.KgGraphService;
import org.dyh.learnhub.service.KgOntology;
import org.dyh.learnhub.service.KgPipelineService;
import org.dyh.learnhub.service.KgReasoner;
import org.dyh.learnhub.service.KgService;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 知识图谱接口。
 *
 * <h3>文档层（原来的相似度图）</h3>
 * <ul>
 *   <li>{@code GET  /api/kg/graph}   节点 + 边（结构边现算，语义边来自 kg_edge）</li>
 *   <li>{@code GET  /api/kg/version} 版本指纹：前端每几秒问一次，变了才重画图（"实时"的实现方式）</li>
 *   <li>{@code POST /api/kg/rebuild} 调模型重建语义关联（花 token，需用户主动触发）</li>
 * </ul>
 *
 * <h3>概念层（真正的知识图谱）</h3>
 * <ul>
 *   <li>{@code GET  /api/kg/concept}           实体 + 三元组 + 本体（界面画概念图用）</li>
 *   <li>{@code POST /api/kg/concept/build}     跑构建流水线：素材 → 抽三元组 → 链接入库 → 推理 → 向量化</li>
 *   <li>{@code GET  /api/kg/concept/jobs/{id}} 构建进度</li>
 *   <li>{@code GET  /api/kg/concept/neighbors} 邻居展开（多跳子图）</li>
 *   <li>{@code GET  /api/kg/concept/path}      两个概念之间的最短路径</li>
 *   <li>{@code POST /api/kg/concept/reason}    只跑规则推理（不花 token）</li>
 *   <li>{@code GET  /api/kg/concept/duplicates} 疑似重复实体（只提示，不自动合并）</li>
 *   <li>{@code POST /api/kg/concept/merge}     人工合并两个实体（不可逆）</li>
 *   <li>{@code DELETE /api/kg/concept/nodes/{id}}        删实体（连同它的边）</li>
 *   <li>{@code DELETE /api/kg/concept/relations/{id}}    删一条三元组</li>
 *   <li>{@code GET  /api/kg/concept/recognize} 实体识别探针：给一句话，看认出哪些概念（调试图谱问答用）</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/kg")
@RequiredArgsConstructor
public class KgController {

    private final KgService kgService;
    private final KgGraphService conceptGraph;
    private final KgPipelineService pipeline;

    // ---------------- 文档层（原有） ----------------

    @GetMapping("/graph")
    public Result<Map<String, Object>> graph() {
        return Result.ok(kgService.graph());
    }

    /**
     * 版本指纹 + 边数。刻意做得很轻：这个接口会被前端按秒轮询，
     * 它只查 COUNT/MAX，不拉任何正文（见 KgMapper）。
     */
    @GetMapping("/version")
    public Result<Map<String, Object>> version() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("version", kgService.version());
        m.put("semanticEdges", kgService.semanticEdgeCount());
        m.put("stale", kgService.semanticStale());
        return Result.ok(m);
    }

    /** 用模型重建语义关联：整批替换旧边，返回接受/丢弃统计 */
    @PostMapping("/rebuild")
    public Result<Map<String, Object>> rebuild() {
        return Result.ok(kgService.rebuildSemantic());
    }

    // ---------------- 概念层 ----------------

    /** 概念图载荷：实体 + 三元组 + 本体定义（关系词表与代数属性） */
    @GetMapping("/concept")
    public Result<Map<String, Object>> concept() {
        Map<String, Object> o = new LinkedHashMap<>(conceptGraph.graph());
        o.put("rule", KgReasoner.describe());
        return Result.ok(o);
    }

    /** 跑构建流水线（花 token：抽取三元组走云端，可在设置里改成本地） */
    @PostMapping("/concept/build")
    public Result<Map<String, Object>> build() {
        return Result.ok(pipeline.start());
    }

    @GetMapping("/concept/jobs/{jobId}")
    public Result<Map<String, Object>> job(@PathVariable String jobId) {
        return Result.ok(pipeline.view(jobId));
    }

    /** 邻居展开：{@code id} 可以是实体名（会自动按别名解析） */
    @GetMapping("/concept/neighbors")
    public Result<Map<String, Object>> neighbors(@RequestParam String id,
                                                @RequestParam(required = false, defaultValue = "2") int hops) {
        return Result.ok(conceptGraph.neighbors(id, hops));
    }

    /** 两个概念之间的路径 */
    @GetMapping("/concept/path")
    public Result<Map<String, Object>> path(@RequestParam String from, @RequestParam String to) {
        return Result.ok(conceptGraph.path(from, to));
    }

    /** 只跑规则推理，不调模型（免费，可反复点） */
    @PostMapping("/concept/reason")
    public Result<Map<String, Object>> reason() {
        return Result.ok(conceptGraph.reason());
    }

    /**
     * 重新把概念节点关联到 wiki 实体页（不调模型、免费，可反复点）。
     *
     * <p>关联原先只随图谱重建一起跑，编译出新实体页后若不重建图谱，
     * 页面上"点概念跳 wiki"就一直连不上（评估报告 P1-3）。
     *
     * @return 本次新关联上的节点数
     */
    @PostMapping("/concept/link-wiki")
    public Result<Map<String, Object>> linkWiki() {
        int linked = pipeline.relinkWikiPages();
        return Result.ok(Map.of(
                "linked", linked,
                "total", conceptGraph.nodes().size(),
                "note", "按归一化标题 + 实体页别名（<!-- entity-aliases: -->）匹配；改名的概念请用「疑似重复/合并」处理"));
    }

    /** 疑似重复实体（消歧助手：只列出来，合并要人点） */
    @GetMapping("/concept/duplicates")
    public Result<Object> duplicates(@RequestParam(required = false, defaultValue = "0.6") double minScore) {
        return Result.ok(conceptGraph.duplicates(minScore));
    }

    /** 人工合并两个实体：A 的边全部转到 B，A 的名字进 B 的别名，然后删 A */
    @PostMapping("/concept/merge")
    public Result<Map<String, Object>> merge(@RequestParam String from, @RequestParam String to) {
        return Result.ok(conceptGraph.mergeNodes(from, to));
    }

    /** 实体识别探针：给一句话，看认出哪些概念（用来调试"为什么它没答上来"） */
    @GetMapping("/concept/recognize")
    public Result<Map<String, Object>> recognize(@RequestParam String q) {
        Map<String, Object> o = new LinkedHashMap<>();
        var exact = conceptGraph.recognize(q, 8);
        var vec = exact.isEmpty() ? conceptGraph.recognizeByVector(q, 5) : java.util.List.<org.dyh.learnhub.entity.KgNode>of();
        o.put("question", q);
        o.put("exact", exact.stream().map(n -> Map.of("id", n.getId(), "name", n.getName(),
                "type", n.getType() == null ? "" : n.getType())).toList());
        o.put("byVector", vec.stream().map(n -> Map.of("id", n.getId(), "name", n.getName(),
                "type", n.getType() == null ? "" : n.getType())).toList());
        o.put("block", conceptGraph.retrievalBlock(q));
        return Result.ok(o);
    }

    /** 本体：关系词表与代数属性（界面上的图例/过滤器直接读它） */
    @GetMapping("/concept/ontology")
    public Result<Object> ontology() {
        Map<String, Object> o = new LinkedHashMap<>();
        o.put("relations", KgOntology.describe());
        o.put("rule", KgReasoner.describe());
        return Result.ok(o);
    }

    @DeleteMapping("/concept/nodes/{id}")
    public Result<Map<String, Object>> deleteNode(@PathVariable String id) {
        return Result.ok(conceptGraph.deleteNode(id));
    }

    @DeleteMapping("/concept/relations/{id}")
    public Result<Map<String, Object>> deleteRelation(@PathVariable Long id) {
        return Result.ok(conceptGraph.deleteRelation(id));
    }
}
