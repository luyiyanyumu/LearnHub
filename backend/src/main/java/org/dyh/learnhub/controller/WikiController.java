package org.dyh.learnhub.controller;

import lombok.RequiredArgsConstructor;
import org.dyh.learnhub.common.Result;
import org.dyh.learnhub.service.EntityCompileService;
import org.dyh.learnhub.ai.ModelRouting;
import org.dyh.learnhub.service.LintService;
import java.util.ArrayList;
import org.dyh.learnhub.service.WikiService;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * LLM wiki 接口（按主题：分类 / 标签）。
 * <ul>
 *   <li>{@code GET  /api/wiki/topics}                主题清单 + 生成状态（有没有生成过、是否过期）</li>
 *   <li>{@code GET  /api/wiki/pages/{topicKey}}      读一页（topicKey 形如 cat-3 / tag-2）</li>
 *   <li>{@code POST /api/wiki/pages/{topicKey}/generate} 生成 / 重新生成（同步等待模型）</li>
 *   <li>{@code PUT  /api/wiki/auto-refresh}          自动增量更新开关（默认开；改了笔记会自动重生成已存在的页）</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/wiki")
@RequiredArgsConstructor
public class WikiController {

    private final WikiService wikiService;
    /** 实体/概念页编译（LLM Wiki 的"跨页编译"那一半） */
    private final EntityCompileService entityCompileService;
    /** ④ 语义自检 */
    private final LintService lintService;
    /** 模型分工表（谁走云端、谁走本地，一处可查） */
    private final ModelRouting routing;

    @GetMapping("/topics")
    public Result<List<Map<String, Object>>> topics() {
        return Result.ok(wikiService.topics());
    }

    @GetMapping("/pages/{topicKey}")
    public Result<Map<String, Object>> page(@PathVariable String topicKey) {
        return Result.ok(wikiService.page(topicKey));
    }

    /** 生成要等模型返回（几秒到几十秒），前端显示"生成中…"并设长超时 */
    /**
     * 生成 / 重新生成一页（**异步**）。
     * <p>立刻返回任务（含 jobId），前端轮询 {@code GET /api/wiki/jobs/{jobId}} 看进度：
     * 阶段、百分比、已生成字数、质量结论。本地小模型出一页要 20~35 秒，同步等待期间界面无事可做。
     *
     * @param target 生成目标：main（主模型）/ local（本地或自建）；留空用上次选择
     */
    @PostMapping("/pages/{topicKey}/generate")
    public Result<Map<String, Object>> generate(@PathVariable String topicKey,
                                                @RequestParam(required = false) String target) {
        return Result.ok(wikiService.startGenerate(topicKey, target));
    }

    /**
     * 编译实体/概念页 + 索引页（后台任务 + 进度）。
     * <p>{@code useMain} 不传时**跟随「模型分工」表**（该项默认云端）。
     * 以前这里默认 {@code false}（= 本地 qwen3:8b），而前端从不传这个参数 ——
     * 结果是「编译知识页」按钮偷偷用了本地模型、46 秒/批还会被 max_tokens 截断，
     * 与设置面板上写的"实体/概念页编译走云端"完全对不上。两条路径必须由同一张表决定。
     */
    @PostMapping("/entities/compile")
    public Result<Map<String, Object>> compileEntities(
            @RequestParam(required = false) String profile) {
        // 不传档案 = 按分工表里"实体编译"这一项走（它自己可能指向本地档案或激活档案）
        return Result.ok(entityCompileService.start(profile));
    }

    /** 实体编译进度 */
    @GetMapping("/entities/jobs/{jobId}")
    public Result<Map<String, Object>> entityJob(@PathVariable String jobId) {
        return Result.ok(entityCompileService.view(jobId));
    }

    /**
     * 影响分析：给定新素材，模型认为该更新哪些已有页面。
     * <p>这是"摄入时局部重编译"的核心判断，也是本地小模型最容易翻车的一步，
     * 所以先做成探针：把模型的选择和候选清单一起返回，人一眼看出准不准。
     */
    @PostMapping("/impact")
    public Result<Map<String, Object>> impact(@RequestBody Map<String, String> body) {
        // 传 null = 按分工表里"影响分析"这一项指向的模型档案
        return Result.ok(entityCompileService.impact(body.getOrDefault("text", ""), 6, null));
    }

    /**
     * ③ 局部重编译：只更新"真正受影响"的页面（最近变更 → 影响分析 → 重生主题页 → 知识页整批重编）。
     * 进度复用 {@code GET /api/wiki/jobs/{jobId}}。
     */
    @PostMapping("/recompile")
    public Result<Map<String, Object>> recompile() {
        return Result.ok(wikiService.startRecompile());
    }

    /** ④ 语义自检：代码检查（红链/质量/过短）+ 模型检查（矛盾/过时/缺口）→ 写入 lint 页 */
    @PostMapping("/lint")
    public Result<Map<String, Object>> lint() {
        return Result.ok(lintService.run());
    }

    /** 模型分工表：每个分析任务当前走云端还是本地，以及为什么 */
    @GetMapping("/model-routing")
    public Result<Map<String, Object>> modelRouting() {
        Map<String, Object> o = new LinkedHashMap<>();
        o.put("table", routing.table());
        List<Map<String, Object>> targets = new ArrayList<>();
        for (ModelRouting.ModelTarget t : routing.targets()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", t.id());
            m.put("label", t.label());
            targets.add(m);
        }
        o.put("targets", targets);
        return Result.ok(o);
    }

    /** 删除一页编译产物（原始素材不动；主题页/实体页/自检页都会在下次生成时回来） */
    @DeleteMapping("/pages/{topicKey}")
    public Result<Map<String, Object>> deletePage(@PathVariable String topicKey) {
        return Result.ok(wikiService.deletePage(topicKey));
    }

    /** 生成任务进度 */
    @GetMapping("/jobs/{jobId}")
    public Result<Map<String, Object>> job(@PathVariable String jobId) {
        return Result.ok(wikiService.jobView(jobId));
    }

    /** 可选生成目标（主模型 / 本地或自建）+ 当前选择 */
    @GetMapping("/models")
    public Result<Map<String, Object>> models() {
        return Result.ok(wikiService.modelOptions());
    }

    @GetMapping("/auto-refresh")
    public Result<Map<String, Object>> autoRefresh() {
        return Result.ok(Map.of("enabled", wikiService.autoRefreshEnabled()));
    }

    @PutMapping("/auto-refresh")
    public Result<Map<String, Object>> setAutoRefresh(@RequestParam boolean on) {
        wikiService.setAutoRefresh(on);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("enabled", wikiService.autoRefreshEnabled());
        return Result.ok(m);
    }
}
