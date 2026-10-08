package org.dyh.learnhub.controller;

import lombok.RequiredArgsConstructor;
import org.dyh.learnhub.common.Result;
import org.dyh.learnhub.service.GraphRagService;
import org.dyh.learnhub.service.KgCommunityService;
import org.dyh.learnhub.service.KgCommunitySummaryService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * GraphRAG 社区层接口（概念图上的社区划分）。
 *
 * <p>为什么单独一个 controller 而不是塞进 {@link KgController}：那个类已经有 10 个概念层端点、
 * 是别处并行在改的文件；社区是新的一条线（后面还要挂社区摘要），独立成类既不冲突也更好找。
 *
 * <ul>
 *   <li>{@code POST /api/kg/communities/recompute} 全量重算（纯本地计算、不花 token）</li>
 *   <li>{@code GET  /api/kg/communities}           每个节点的社区归属（页面着色）</li>
 *   <li>{@code GET  /api/kg/communities/grouped}   按社区聚合（规模/成员，社区摘要的输入）</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/kg/communities")
@RequiredArgsConstructor
public class KgCommunityController {

    private final KgCommunityService communityService;
    private final KgCommunitySummaryService summaryService;
    private final GraphRagService graphRagService;

    /**
     * 全量重算社区（纯本地计算，不花 token）。
     * {@code ifStale=true}（默认）时先看脏标记：图谱没变就直接返回 skipped，避免入库流水线里白跑。
     */
    @PostMapping("/recompute")
    public Result<Map<String, Object>> recompute(@RequestParam(defaultValue = "true") boolean ifStale) {
        return Result.ok(communityService.recompute(!ifStale));
    }

    /**
     * 给规模达标的社区写摘要（花 token，需用户主动触发）。
     * {@code limit} 是**本次最多调几次模型**：一次跑完全部大社区可能要十几二十次调用，
     * 已写的会按成员指纹跳过，所以分几次点与一次点完等价。
     */
    @PostMapping("/summarize")
    public Result<Map<String, Object>> summarize(@RequestParam(defaultValue = "5") int limit) {
        return Result.ok(summaryService.summarize(Math.max(1, Math.min(limit, 50))));
    }

    /** 已存的社区摘要（规模大的在前） */
    @GetMapping("/summaries")
    public Result<List<Map<String, Object>>> summaries() {
        return Result.ok(summaryService.summaries());
    }

    /** 社区及摘要的就绪状态。只查库，不重算社区、不触发模型。 */
    @GetMapping("/status")
    public Result<Map<String, Object>> status() {
        return Result.ok(summaryService.status());
    }

    /**
     * GraphRAG 检索探针：给一句话，看它会走哪条路、召回什么。
     * local 零模型调用；global 默认只回摘要原文（{@code synthesize=true} 才做一次 reduce 综合）。
     * 存在的意义：调试图谱问答 —— 回答不对时，先看是"没召回"还是"召回了但模型说错"。
     */
    @GetMapping("/search")
    public Result<Map<String, Object>> search(@RequestParam String q,
                                             @RequestParam(defaultValue = "6") int limit,
                                             @RequestParam(defaultValue = "false") boolean synthesize,
                                             @RequestParam(defaultValue = "auto") String mode) {
        return Result.ok(graphRagService.search(q, limit, synthesize, mode));
    }

    @GetMapping
    public Result<List<Map<String, Object>>> list() {
        return Result.ok(communityService.communities());
    }

    @GetMapping("/grouped")
    public Result<List<Map<String, Object>>> grouped() {
        return Result.ok(communityService.grouped());
    }
}
