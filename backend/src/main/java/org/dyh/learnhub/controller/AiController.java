package org.dyh.learnhub.controller;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.dyh.learnhub.ai.AgentService;
import org.dyh.learnhub.common.Result;
import org.dyh.learnhub.dto.AiChatRequest;
import org.dyh.learnhub.dto.AiPolishRequest;
import org.dyh.learnhub.dto.AiTestRequest;
import org.dyh.learnhub.vo.AiChatVO;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 智能体接口：
 * <ul>
 *   <li>GET  /api/ai/status   查询配置状态（是否已配 Key / 当前模型）</li>
 *   <li>POST /api/ai/polish   语言润色 / 整理格式（编辑器内按钮）</li>
 *   <li>POST /api/ai/chat     对话（支持函数调用自动操作工作台数据）</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/ai")
@RequiredArgsConstructor
public class AiController {

    private final AgentService agentService;

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

    @PostMapping("/chat")
    public Result<AiChatVO> chat(@Valid @RequestBody AiChatRequest req) {
        return Result.ok(agentService.chat(req));
    }
}
