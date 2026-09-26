package org.dyh.learnhub.controller;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.dyh.learnhub.ai.AgentService;
import org.dyh.learnhub.common.Result;
import org.dyh.learnhub.dto.AiChatRequest;
import org.dyh.learnhub.dto.AiPolishRequest;
import org.dyh.learnhub.dto.AiTestRequest;
import org.dyh.learnhub.entity.AgentSession;
import org.dyh.learnhub.service.AgentSessionService;
import org.dyh.learnhub.service.SkillService;
import org.dyh.learnhub.vo.AiChatVO;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import org.springframework.util.StringUtils;

import jakarta.annotation.PreDestroy;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 智能体接口：
 * <ul>
 *   <li>GET  /api/ai/status        查询配置状态（是否已配 Key / 当前模型）</li>
 *   <li>POST /api/ai/polish        语言润色 / 整理格式（编辑器内按钮）</li>
 *   <li>POST /api/ai/polish-stream 同上，但以 SSE 持续回报分段进度</li>
 *   <li>POST /api/ai/chat          对话（支持函数调用自动操作工作台数据）</li>
 *   <li>GET  /api/ai/skills        技能清单（润色/整理格式的提示词来自 skills/&lt;id&gt;/SKILL.md）</li>
 * </ul>
 *
 * <p>关于 {@code /polish-stream}：<b>它不套统一返回体</b>（{@code Result}）。
 * SSE 是逐事件推送的，每个事件的 data 就是要用的载荷本身：
 * <pre>
 *   event: progress  data: {"stage":"start","total":3}
 *   event: progress  data: {"stage":"chunk","index":2,"total":3}
 *   event: done      data: {"content":"处理后的 Markdown…"}
 *   event: failed    data: {"message":"可读的失败原因"}
 * </pre>
 */
@Slf4j
@RestController
@RequestMapping("/api/ai")
@RequiredArgsConstructor
public class AiController {

    private final AgentService agentService;
    private final AgentSessionService sessionService;
    private final SkillService skillService;

    /**
     * 流式润色的工作线程池。
     * <p>
     * 必须另起线程：SseEmitter 只有在控制器方法**返回之后**才能开始推送事件，
     * 若在方法内同步跑完再用 emitter，客户端要等全部处理完才收到第一批数据，进度就失去意义了。
     * 用单线程是刻意的——同时只跑一个润色任务，避免多份长文请求把 AI 配额和本机资源吃满；
     * 排在后面的请求会等前一个结束（前端也只有一个按钮，正常不会并发）。
     * 守护线程：进程退出时不阻塞关闭。
     */
    private final ExecutorService polishStreamPool = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "ai-polish-stream");
        t.setDaemon(true);
        return t;
    });

    @PreDestroy
    void shutdownStreamPool() {
        polishStreamPool.shutdownNow();
    }

    @GetMapping("/status")
    public Result<Map<String, Object>> status() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("configured", agentService.isConfigured());
        m.put("model", agentService.model());
        return Result.ok(m);
    }

    /** 连接自检：可用未保存的表单值直接试（字段为空则用当前生效配置） */
    @PostMapping("/test")
    public Result<String> test(@RequestBody(required = false) AiTestRequest req) {
        if (req == null) {
            return Result.ok(agentService.testConnection());
        }
        return Result.ok(agentService.testConnection(
                req.getBaseUrl(), req.getApiKey(), req.getModel(),
                parseInt(req.getMaxTokens()), parseDouble(req.getTemperature()),
                req.getThinking(), req.getReasoningEffort()));
    }

    private static Integer parseInt(String s) {
        try {
            return s == null || s.isBlank() ? null : Integer.valueOf(s.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static Double parseDouble(String s) {
        try {
            return s == null || s.isBlank() ? null : Double.valueOf(s.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    @PostMapping("/polish")
    public Result<String> polish(@Valid @RequestBody AiPolishRequest req) {
        return Result.ok(agentService.polish(req.getText(), req.getMode()));
    }

    /**
     * 流式版本：分段进度 + 最终结果。
     * <p>
     * 超时必须显式放大：SseEmitter 默认 30s 就会断开，而本工程一轮润色预算到 270s、
     * 单次 HTTP 超时 300s，这里取 320s 留出收尾余量。
     */
    @PostMapping(value = "/polish-stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter polishStream(@Valid @RequestBody AiPolishRequest req) {
        SseEmitter emitter = new SseEmitter(320_000L);

        polishStreamPool.execute(() -> {
            try {
                String out = agentService.polish(req.getText(), req.getMode(), new AgentService.ChunkListener() {
                    @Override
                    public void onStart(int total) {
                        send(emitter, "progress", Map.of("stage", "start", "total", total));
                    }

                    @Override
                    public void onChunk(int index, int total) {
                        send(emitter, "progress", Map.of("stage", "chunk", "index", index, "total", total));
                    }
                });
                send(emitter, "done", Map.of("content", out == null ? "" : out));
            } catch (Exception e) {
                // 失败也要作为事件推给前端：流已经 200 建立了，没法再用 HTTP 状态表达错误
                String msg = e.getMessage() == null ? "AI 服务异常" : e.getMessage();
                log.warn("流式润色失败: {}", msg);
                send(emitter, "failed", Map.of("message", msg));
            } finally {
                emitter.complete();
            }
        });

        return emitter;
    }

    /**
     * 推一个 SSE 事件。发送失败通常意味着客户端已断开（切页 / 取消），
     * 此时静默丢弃即可——本次任务会在预算耗尽或跑完后自然结束。
     */
    private static void send(SseEmitter emitter, String event, Object data) {
        try {
            emitter.send(SseEmitter.event().name(event).data(data, MediaType.APPLICATION_JSON));
        } catch (Exception ignored) {
            // 客户端断开，忽略
        }
    }

    @PostMapping("/chat")
    public Result<AiChatVO> chat(@Valid @RequestBody AiChatRequest req) {
        return Result.ok(agentService.chat(req));
    }

    /**
     * 会话回看：把事件日志投影成「气泡列表」返回。
     * <p>
     * 界面刷新后靠它把对话恢复出来 —— 否则会出现「模型记得上一轮、面板却是空的」这种不一致。
     */
    @GetMapping("/sessions/{id}")
    public Result<Map<String, Object>> session(@PathVariable String id) {
        AgentSession s = sessionService.get(id);
        if (s == null) {
            return Result.error(404, "会话不存在");
        }
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("sessionId", s.getId());
        m.put("title", s.getTitle());
        m.put("noteId", s.getNoteId());
        m.put("messages", sessionService.messagesForDisplay(id));
        // 未处理的待确认操作也要带回去：否则刷新页面后那些"待确认"就永远没法点了
        m.put("pendingActions", sessionService.pendingActions(id).stream()
                .map(AgentSessionService::actionBrief).toList());
        return Result.ok(m);
    }

    /**
     * 技能清单：润色 / 整理格式的提示词现在来自 {@code skills/<id>/SKILL.md}，
     * 设置面板据此显示"当前生效的是哪个文件、多少字"。
     * <p>
     * 刻意做成只读：技能是磁盘上的文件，改它就该用编辑器改文件（能进版本库、能 diff），
     * 而不是在设置面板里再留一个可编辑的副本 —— 那正是之前两个框被填进同一份文档的成因。
     */
    @GetMapping("/skills")
    public Result<Map<String, Object>> skills() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("skills", skillService.list());
        m.put("polishSkill", SkillService.SKILL_POLISH);
        m.put("beautifySkill", SkillService.SKILL_BEAUTIFY);
        return Result.ok(m);
    }

    /**
     * 上传 / 替换一个技能：把上传的文本写成 {@code skills/<id>/SKILL.md}。
     * <p>
     * 元数据（frontmatter）默认沿用原值，只有上传内容自带 frontmatter 或显式传了字段时才覆盖 ——
     * 这样"只换提示词"不会把 {@code applies_to} / {@code min_ratio} 一起丢掉。
     * 覆盖前旧版本会备份到 {@code skills/.history/<id>/}。
     *
     * @param id   技能目录名（新名即新建技能）
     * @param body content 必填；name / description / appliesTo / minRatio 可选
     */
    @PutMapping("/skills/{id}")
    public Result<SkillService.SkillInfo> saveSkill(@PathVariable String id,
                                                    @RequestBody Map<String, Object> body) {
        String content = body.get("content") == null ? "" : String.valueOf(body.get("content"));
        Map<String, String> over = new LinkedHashMap<>();
        putIfPresent(over, "name", body.get("name"));
        putIfPresent(over, "description", body.get("description"));
        putIfPresent(over, "applies_to", body.get("appliesTo"));
        putIfPresent(over, "min_ratio", body.get("minRatio"));
        return Result.ok(skillService.save(id, content, over));
    }

    private void putIfPresent(Map<String, String> target, String key, Object v) {
        if (v != null && StringUtils.hasText(String.valueOf(v))) {
            target.put(key, String.valueOf(v));
        }
    }

    /** 确认执行一个待确认的写操作（在此之前数据库零改动） */
    @PostMapping("/actions/{id}/approve")
    public Result<Map<String, Object>> approveAction(@PathVariable Long id) {
        return Result.ok(agentService.executePendingAction(id));
    }

    /** 取消一个待确认的写操作：什么都不执行 */
    @PostMapping("/actions/{id}/reject")
    public Result<Void> rejectAction(@PathVariable Long id) {
        agentService.rejectPendingAction(id);
        return Result.ok();
    }

    /** 清空一个会话（含事件） */
    @DeleteMapping("/sessions/{id}")
    public Result<Void> deleteSession(@PathVariable String id) {
        sessionService.delete(id);
        return Result.ok();
    }
}
