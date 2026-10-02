package org.dyh.learnhub.controller;

import lombok.RequiredArgsConstructor;
import org.dyh.learnhub.common.Result;
import org.dyh.learnhub.service.GroundingService;
import org.dyh.learnhub.service.KnowledgeService;
import org.dyh.learnhub.service.RagAnswerEvalService;
import org.dyh.learnhub.service.RagEvalService;
import org.dyh.learnhub.service.RerankService;
import org.dyh.learnhub.service.SettingsService;
import org.dyh.learnhub.service.VectorIndexService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 语义检索（向量索引）的运维接口：状态、重建（带进度）、检索体检。
 * <p>"我把资料做成知识储备了没有"这件事必须是**可验证**的，而不是感觉——
 * 所以状态里直接给出块数/字数/模型/是否过期，体检里直接对比"词面 vs 语义"的命中差异。
 */
@RestController
@RequestMapping("/api/kb")
@RequiredArgsConstructor
public class VectorController {

    private final VectorIndexService vectorIndexService;
    private final KnowledgeService knowledgeService;
    private final RagEvalService ragEvalService;
    private final RagAnswerEvalService ragAnswerEvalService;
    private final RerankService rerankService;
    private final SettingsService settingsService;
    private final GroundingService groundingService;

    /** 索引状态（块数、字数、模型、是否与当前内容一致） */
    @GetMapping("/status")
    public Result<Map<String, Object>> status() {
        Map<String, Object> o = new java.util.LinkedHashMap<>(vectorIndexService.status());
        o.put("rerank", rerankService.status());
        o.put("contextualEmbed", !"0".equals(settingsService.effective(VectorIndexService.SETTING_CONTEXTUAL_EMBED)));
        return Result.ok(o);
    }

    /** 重建索引（异步，带进度） */
    @PostMapping("/rebuild")
    public Result<Map<String, Object>> rebuild() {
        return Result.ok(vectorIndexService.startRebuild());
    }

    /**
     * **增量索引**：只重编内容变了的来源（指纹对比），并清掉已删除的来源。
     * <p>比全量重建快得多 —— 改一条笔记只重编那一条。
     */
    @PostMapping("/reindex")
    public Result<Map<String, Object>> reindex() {
        return Result.ok(vectorIndexService.reindexChanged());
    }

    // ---------------- 检索评测（把"变好了没有"变成数字） ----------------

    /** 评测用例清单 */
    @GetMapping("/eval/cases")
    public Result<Object> cases() {
        return Result.ok(ragEvalService.cases());
    }

    /**
     * 跑一轮评测。
     *
     * @param label 标签（例如"改前基线"），历史里按它对比
     * @param topK  只看前几名，默认 5
     * @param mode  fused（默认，线上行为）/ keyword / vector
     */
    @PostMapping("/eval/run")
    public Result<Map<String, Object>> evalRun(@RequestParam(required = false) String label,
                                              @RequestParam(required = false) Integer topK,
                                              @RequestParam(required = false) String mode) {
        return Result.ok(ragEvalService.run(label, topK == null ? 5 : topK, mode));
    }

    /** 评测历史（改动前后对比用） */
    @GetMapping("/eval/history")
    public Result<Object> evalHistory() {
        return Result.ok(ragEvalService.history());
    }

    // ---------------- 答案级评测（2026-10-02 新增：从"召回了没有"推进到"答得对不对"） ----------------

    /**
     * 跑一轮**答案级**评测。
     *
     * <p>与检索评测（{@code /eval/run}）的区别：那一轮只跑检索、几秒到几分钟；
     * 这一轮**每条用例都要真调一次模型生成答案**（还要跑答案核对），所以慢得多也贵得多。
     * 因此默认只跑 {@code limit} 条（{@link RagAnswerEvalService#DEFAULT_LIMIT}），
     * 想全量就显式传大 limit，或用 {@code ids} 指定几条。
     *
     * @param label 标签（历史里按它对比）
     * @param topK  检索取前几名，默认 5
     * @param limit 最多评几条，默认 10
     * @param mode  fused（默认，线上行为）/ keyword / vector
     * @param ids   只评这几条（逗号分隔的 id；传了就忽略 enabled，可用来试草稿题）
     */
    @PostMapping("/eval/answer/run")
    public Result<Map<String, Object>> answerEvalRun(@RequestParam(required = false) String label,
                                                     @RequestParam(required = false) Integer topK,
                                                     @RequestParam(required = false) Integer limit,
                                                     @RequestParam(required = false) String mode,
                                                     @RequestParam(required = false) String ids) {
        return Result.ok(ragAnswerEvalService.run(label, topK == null ? 5 : topK,
                limit == null ? RagAnswerEvalService.DEFAULT_LIMIT : limit, mode, parseIds(ids)));
    }

    /** 答案级评测历史 */
    @GetMapping("/eval/answer/history")
    public Result<Object> answerEvalHistory() {
        return Result.ok(ragAnswerEvalService.history());
    }

    /** {@code ids=1,2,3} → {@code [1,2,3]}；空/非法项直接忽略 */
    private static List<Long> parseIds(String ids) {
        if (ids == null || ids.isBlank()) {
            return List.of();
        }
        List<Long> out = new java.util.ArrayList<>();
        for (String p : ids.split("[,，\\s]+")) {
            String t = p.trim();
            if (t.isEmpty()) {
                continue;
            }
            try {
                out.add(Long.parseLong(t));
            } catch (NumberFormatException ignored) {
                // 单个 id 写错不该让整轮评测失败
            }
        }
        return out;
    }

    // ---------------- 重排开关（默认关，用评测决定开不开） ----------------

    @GetMapping("/rerank")
    public Result<Object> rerankStatus() {
        return Result.ok(rerankService.status());
    }

    @PostMapping("/rerank")
    public Result<Object> setRerank(@RequestParam boolean on) {
        settingsService.update(RerankService.SETTING_RERANK, on ? "1" : "0");
        return Result.ok(rerankService.status());
    }

    /**
     * 切换精排后端：{@code backend=llm|cross}。
     * <p>{@code cross} 需要先起 sidecar（{@code python tools/rerank-server.py}，默认 8091）。
     * 服务没起时不会报错，只是自动用 llm —— 状态里的 {@code active} 字段看得出来实际用了哪个。
     */
    @PostMapping("/rerank/backend")
    public Result<Object> setRerankBackend(@RequestParam String backend) {
        String b = backend == null ? "" : backend.trim().toLowerCase();
        if (!b.isEmpty() && !"llm".equals(b) && !"cross".equals(b)) {
            throw new IllegalArgumentException("backend 只能是 llm 或 cross");
        }
        settingsService.update(RerankService.SETTING_BACKEND, b);
        return Result.ok(rerankService.status());
    }

    // ---------------- 上下文嵌入开关（A/B 实测用） ----------------

    @GetMapping("/contextual")
    public Result<Object> contextualStatus() {
        return Result.ok(java.util.Map.of(
                "enabled", !"0".equals(settingsService.effective(VectorIndexService.SETTING_CONTEXTUAL_EMBED))));
    }

    /**
     * 开关"嵌入时是否前置标题+小节"。
     * <p>改完**必须重建索引**才生效（嵌入内容变了）。留着它就是为了能做 A/B：
     * 同一组评测用例、两种配置各跑一次，用 recall@k 说话。
     */
    @PostMapping("/contextual")
    public Result<Object> setContextual(@RequestParam boolean on) {
        settingsService.update(VectorIndexService.SETTING_CONTEXTUAL_EMBED, on ? "1" : "0");
        return Result.ok(java.util.Map.of("enabled", on, "needRebuild", true));
    }

    // ---------------- 答案级校验 ----------------

    @GetMapping("/grounding")
    public Result<Object> groundingStatus() {
        return Result.ok(groundingService.status());
    }

    @PostMapping("/grounding")
    public Result<Object> setGrounding(@RequestParam boolean on) {
        settingsService.update(GroundingService.SETTING_GROUNDING, on ? "1" : "0");
        return Result.ok(groundingService.status());
    }

    /**
     * 校验探针：给定（问题 / 证据 / 答案），看核对器怎么判。
     * <p>留着它是为了**验证核对器本身准不准** —— 一个"总是说没问题"的核对器毫无价值，
     * 而它的准确率只能靠这样喂已知的有/无依据的样本来测。
     */
    @PostMapping("/grounding/check")
    public Result<Object> groundingCheck(@RequestBody Map<String, String> body) {
        GroundingService.Result r = groundingService.check(
                body.getOrDefault("question", ""),
                body.getOrDefault("evidence", ""),
                body.getOrDefault("answer", ""));
        return Result.ok(java.util.Map.of(
                "checked", r.checked(),
                "grounded", r.grounded(),
                "unsupported", r.unsupported(),
                "note", r.note() == null ? "" : r.note()));
    }

    /** 索引进度 */
    @GetMapping("/jobs/{jobId}")
    public Result<Map<String, Object>> job(@PathVariable String jobId) {
        return Result.ok(vectorIndexService.jobView(jobId));
    }

    /**
     * 检索体检：同一个问题，词面检索与语义检索各命中什么。
     * <p>用法：拿一个"换一种说法"的问句来试 —— 词面可能 0 条，语义应该照样命中。
     */
    @GetMapping("/probe")
    public Result<Map<String, Object>> probe(@RequestParam String q,
                                             @RequestParam(required = false) Integer topK) {
        Map<String, Object> out = vectorIndexService.probe(q, topK == null ? VectorIndexService.DEFAULT_TOP_K : topK);
        List<Map<String, Object>> kw = knowledgeService.keywordOnly(q);
        out.put("keyword", kw);
        out.put("keywordCount", kw.size());
        out.put("vectorCount", ((List<?>) out.get("vector")).size());
        return Result.ok(out);
    }

    /**
     * 知识库主搜索（融合检索）。
     *
     * <p>评估报告 P1：知识库页面此前把整段输入交给 SQL LIKE，口语化的问句
     * （如"大量字符串拼接用哪个类性能更好"）会 0 命中，而语义检索其实能找回
     * 《String / StringBuilder / StringBuffer 区别》速查卡。语义能力此前只服务
     * 智能体与"检索体检"，这里把它开放给知识库主搜索。
     *
     * @param q    查询（整句口语化问句也可以）
     * @param topK 返回条数，默认 10
     */
    @GetMapping("/search")
    public Result<Object> search(@RequestParam(required = false) String q,
                                 @RequestParam(required = false) Integer topK) {
        // 空查询直接返回空列表：检索链路对空串会抛异常，前端不该看到一个 500
        // （前端在融合模式下留空时会回落到词面"最近知识"，这里是后端侧的兜底）。
        String query = q == null ? "" : q.trim();
        if (query.isEmpty()) {
            return Result.ok(List.of());
        }
        return Result.ok(vectorIndexService.search(query, topK == null ? 10 : topK));
    }

    // ---------------- 向量后端（2026-09-29：可切 Milvus，默认仍是 MySQL 全扫） ----------------

    /**
     * 向量后端体检：配置的是哪个、当前生效的是哪个、两个后端各有多少向量、是否一致。
     * <p>为什么必须能看：写入路径可能"MySQL 成功、Milvus 失败"，两边数量会悄悄漂开 ——
     * 表现是"检索结果莫名变少"而没有任何报错。
     */
    @GetMapping("/vector/status")
    public Result<Map<String, Object>> vectorStatus() {
        return Result.ok(vectorIndexService.vectorBackendStatus());
    }

    /**
     * 把 MySQL 里已有的向量**直接灌进 Milvus**（不重新嵌入、不调模型）。
     * <p>换后端后的第一次同步、以及 Milvus 挂过之后的补数都用它 ——
     * 一次 1.3k 块约几秒；全量重建要重新嵌入（分钟级），没必要。
     */
    @PostMapping("/vector/sync")
    public Result<Map<String, Object>> vectorSync() {
        return Result.ok(vectorIndexService.syncToMilvus());
    }

    /** 切换向量后端：{@code backend=mysql|milvus}（切换本身不动数据，Milvus 缺向量用 sync/rebuild 补） */
    @PostMapping("/vector/backend")
    public Result<Map<String, Object>> setVectorBackend(@RequestParam String backend) {
        String b = backend == null ? "" : backend.trim().toLowerCase();
        if (!b.isEmpty() && !"mysql".equals(b) && !"milvus".equals(b)) {
            throw new IllegalArgumentException("backend 只能是 mysql 或 milvus");
        }
        settingsService.update("kb.vector_backend", b);
        return Result.ok(vectorIndexService.vectorBackendStatus());
    }
}
