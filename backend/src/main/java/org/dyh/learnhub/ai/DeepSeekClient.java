package org.dyh.learnhub.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.dyh.learnhub.config.AiProperties;
import org.dyh.learnhub.service.SettingsService;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;

/**
 * 手写的 OpenAI 兼容 Chat Completions 客户端（面向 DeepSeek）。
 * <p>
 * 不引入 SDK，直接用 JDK HttpClient + Jackson 拼 JSON：
 * <ul>
 *   <li>POST {baseUrl}/chat/completions</li>
 *   <li>支持 messages + tools（function calling）</li>
 *   <li>返回 choices[0].message 节点，由上层 AgentService 决定继续调工具还是收尾</li>
 * </ul>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DeepSeekClient {

    private final AiProperties props;
    private final SettingsService settingsService;
    private final ObjectMapper objectMapper;

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(15))
            .version(HttpClient.Version.HTTP_1_1)
            .build();

    /** API Key 是否已配置（设置面板 > .env；未配置时前端给出引导提示） */
    public boolean isConfigured() {
        String k = apiKey();
        return k != null && !k.isBlank();
    }

    /** 当前生效模型（设置面板可改） */
    public String model() {
        return settingsService.effective(SettingsService.KEY_MODEL);
    }

    /** 当前生效 API 地址（设置面板可改，支持任意 OpenAI 兼容服务/中转） */
    public String baseUrl() {
        String v = settingsService.effective(SettingsService.KEY_BASE_URL);
        return normalizeBase(v);
    }

    /** 当前生效 API Key：设置面板覆盖优先，其次 .env / application.yml */
    public String apiKey() {
        String v = settingsService.effective(SettingsService.KEY_API_KEY);
        return v != null && !v.isBlank() ? v.trim() : props.getApiKey();
    }

    /** 当前生效最大输出 token（默认 8192，避免长文被截断） */
    public int maxTokens() {
        String v = settingsService.effective(SettingsService.KEY_MAX_TOKENS);
        try {
            int n = Integer.parseInt(v == null ? "" : v.trim());
            return n > 0 ? n : 8192;
        } catch (NumberFormatException e) {
            return 8192;
        }
    }

    /** 当前生效温度（设置面板可改；思考模式下服务端会忽略它） */
    public double temperature() {
        String v = settingsService.effective(SettingsService.KEY_TEMPERATURE);
        try {
            double d = Double.parseDouble(v == null ? "" : v.trim());
            return d >= 0 && d <= 2 ? d : props.getTemperature();
        } catch (NumberFormatException e) {
            return props.getTemperature();
        }
    }

    /** 思考模式：null/空 = 自动（由模型决定），"enabled"/"disabled" = 强制开关 */
    public String thinking() {
        return settingsService.effective(SettingsService.KEY_THINKING);
    }

    /** 思考强度：null/空 = 服务端默认，可选 low / high / max */
    public String reasoningEffort() {
        return settingsService.effective(SettingsService.KEY_REASONING_EFFORT);
    }

    // ------------------------------------------------------------------
    // 思考模式判定
    // ------------------------------------------------------------------

    /**
     * 「思考默认开启」的模型前缀。
     * <p>DeepSeek V4 起，「思考」由独立模型（deepseek-reasoner）改成了请求参数，
     * 所以既要认 v4 / flash，也要保留对旧 reasoner 名字的兼容。
     * 注意 {@code deepseek-chat} 不在其中——它是历史遗留的「非思考」通道，默认不开思考。
     */
    private static final List<String> THINKING_ON_BY_DEFAULT = List.of(
            "deepseek-reasoner", "deepseek-flash", "deepseek-v4",
            "kimi-k3", "kimi-k2.7", "kimi-k2.6", "kimi-latest",
            "glm-5", "qwen3.8", "qwen3.7", "qwen3.6", "qwq",
            "gpt-5", "gpt-6", "o1", "o3", "o4",
            "claude-", "gemini-3", "gemini-2.5", "grok-4");

    /**
     * 「完全不能带 temperature」的模型前缀：这些模型的思考无法通过参数关闭，
     * 且官方文档明确要求不要下发 temperature / top_p，多传会报错。
     * <p>DeepSeek 官方端点不在此列——它支持用 {@code thinking:disabled} 关掉思考后再用 temperature。
     */
    private static final List<String> TEMPERATURE_FORBIDDEN = List.of(
            "kimi-k3", "kimi-k2.7", "kimi-k2.6", "kimi-latest",
            "glm-5", "qwen3.8", "qwen3.7", "qwen3.6", "qwq",
            "gpt-5", "gpt-6", "o1", "o3", "o4",
            "claude-", "gemini-3", "gemini-2.5", "grok-4");

    private static boolean startsWithAny(String model, List<String> prefixes) {
        if (model == null) {
            return false;
        }
        String m = model.trim().toLowerCase();
        return prefixes.stream().anyMatch(m::startsWith);
    }

    /** 该模型是否「默认开启思考」 */
    static boolean isThinkingFamily(String model) {
        return startsWithAny(model, THINKING_ON_BY_DEFAULT);
    }

    /**
     * 本次请求思考模式是否开启。
     *
     * @param thinking 用户设置："enabled" / "disabled" / 空（自动）
     */
    static boolean isThinkingOn(String model, String thinking) {
        if ("enabled".equalsIgnoreCase(thinking)) {
            return true;
        }
        if ("disabled".equalsIgnoreCase(thinking)) {
            return false;
        }
        return isThinkingFamily(model);
    }

    /**
     * 是否可以在请求体里显式下发 {@code thinking} 字段。
     * <p>
     * 这是 DeepSeek 官方 API 的扩展，只对 api.deepseek.com 生效：
     * 实测 deepseek-chat / deepseek-reasoner / deepseek-flash / deepseek-v4-* 都支持
     * {@code thinking:{type:adaptive|enabled|disabled}}，而第三方中转（如硅基流动）上的
     * DeepSeek 模型只是同名，多传未知字段会直接 400，所以必须按「接口地址」而不是模型名判断。
     */
    static boolean supportsThinkingParam(String baseUrl) {
        return baseUrl != null && baseUrl.toLowerCase().contains("deepseek.com");
    }

    /** {@code reasoning_effort} 的合法取值（实测 DeepSeek 官方端点的枚举，非法值会直接 400） */
    private static final List<String> VALID_EFFORTS = List.of(
            "none", "minimal", "low", "medium", "high", "xhigh", "max");

    /** 校验思考强度；非法值返回 null —— 宁可不传，也别让整个请求因为一个拼写错误而 400 */
    static String normalizeEffort(String effort) {
        if (effort == null) {
            return null;
        }
        String e = effort.trim().toLowerCase();
        return VALID_EFFORTS.contains(e) ? e : null;
    }

    /** 去掉结尾多余的 /，避免拼出 //chat/completions */
    public static String normalizeBase(String v) {
        if (v == null || v.isBlank()) {
            return "https://api.deepseek.com";
        }
        String s = v.trim();
        while (s.endsWith("/")) {
            s = s.substring(0, s.length() - 1);
        }
        return s;
    }

    /**
     * 单次 HTTP 请求的默认超时。
     * <p>
     * 实测「模型 + 思考」在 4863 字长文上单次要跑到 160~200s，所以给到 300s。
     * 注意前端 axios 的 AI 超时也是 300s（frontend/src/api/index.js），两边必须对齐。
     * 但**一轮可能有多次请求**（分块润色 / 多轮工具调用），所以上层还会按剩余预算
     * 传入更短的 timeout，避免「单次不超时、整轮超时」。
     */
    public static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(300);

    /**
     * 发送一次补全请求。
     *
     * @param messages 消息列表（system/user/assistant/tool 已由调用方拼好），元素可为 Map 或 Jackson 节点
     * @param tools    工具定义，null 表示纯文本对话（不开启函数调用）
     * @return choices[0].message 节点：可能含 content，也可能含 tool_calls
     */
    public JsonNode chat(List<?> messages, List<?> tools) throws Exception {
        return chat(messages, tools, baseUrl(), apiKey(), model(), maxTokens(), temperature(),
                thinking(), reasoningEffort());
    }

    /**
     * 用「指定配置」发送一次补全请求（不读数据库）。
     * 供设置面板的「测试连接」使用：可以先试未保存的地址/密钥/模型是否可用。
     */
    public JsonNode chat(List<?> messages, List<?> tools,
                         String baseUrl, String apiKey, String model,
                         int maxTokens, double temperature) throws Exception {
        return chat(messages, tools, baseUrl, apiKey, model, maxTokens, temperature, null, null);
    }

    /**
     * 最完整的一次补全请求。
     *
     * @param thinking        思考模式："enabled"/"disabled"/null（自动）
     * @param reasoningEffort 思考强度："low"/"high"/"max"/null（服务端默认）
     */
    public JsonNode chat(List<?> messages, List<?> tools,
                         String baseUrl, String apiKey, String model,
                         int maxTokens, double temperature,
                         String thinking, String reasoningEffort) throws Exception {
        return chat(messages, tools, baseUrl, apiKey, model, maxTokens, temperature,
                thinking, reasoningEffort, DEFAULT_TIMEOUT);
    }

    /**
     * 同上，外加显式超时。
     *
     * @param timeout 本次 HTTP 请求的超时。上层按「整轮还剩多少预算」传进来，
     *                目的是保证「一轮内多次请求的总耗时」也不超过前端超时。
     */
    public JsonNode chat(List<?> messages, List<?> tools,
                         String baseUrl, String apiKey, String model,
                         int maxTokens, double temperature,
                         String thinking, String reasoningEffort,
                         Duration timeout) throws Exception {
        ObjectNode body = objectMapper.createObjectNode();
        body.put("model", model);
        // 显式给出 max_tokens：不传时服务端默认偏小，长文润色会被截断。
        // 注意：思考模式下「思考内容」也要占输出预算，所以默认值给得比旧版更宽。
        body.put("max_tokens", maxTokens);

        boolean thinkingOn = isThinkingOn(model, thinking);
        if (supportsThinkingParam(baseUrl)) {
            // DeepSeek 官方端点：思考是请求参数而非独立模型
            body.putObject("thinking").put("type", thinkingOn ? "enabled" : "disabled");
            String effort = normalizeEffort(reasoningEffort);
            if (thinkingOn && effort != null) {
                body.put("reasoning_effort", effort);
            }
        }
        // temperature 的两条禁忌：
        // 1) 思考已开启 → DeepSeek 会忽略它（传了不报错但无效果），干脆不带，避免误会；
        // 2) 非 DeepSeek 的思考型模型（如 kimi-k3 / glm-5.3）思考无法关闭，
        //    且其文档明确要求不要下发 temperature/top_p，也一律不带。
        if (!thinkingOn && !startsWithAny(model, TEMPERATURE_FORBIDDEN)) {
            body.put("temperature", temperature);
        }

        ArrayNode msgs = body.putArray("messages");
        for (Object m : messages) {
            msgs.add(objectMapper.valueToTree(m));
        }
        if (tools != null && !tools.isEmpty()) {
            ArrayNode toolsNode = body.putArray("tools");
            for (Object t : tools) {
                toolsNode.add(objectMapper.valueToTree(t));
            }
            body.put("tool_choice", "auto");
        }

        String payload = objectMapper.writeValueAsString(body);
        // 便于学习/排查：把实际发出的请求体打出来（不含密钥，密钥在 header 里）
        if (log.isDebugEnabled()) {
            log.debug("AI 请求体: {}", truncate(payload, 600));
        }
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(normalizeBase(baseUrl) + "/chat/completions"))
                .timeout(timeout)
                .header("Content-Type", "application/json; charset=utf-8")
                .header("Authorization", "Bearer " + apiKey)
                .POST(HttpRequest.BodyPublishers.ofString(payload, StandardCharsets.UTF_8))
                .build();

        long start = System.currentTimeMillis();
        HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        long cost = System.currentTimeMillis() - start;
        if (response.statusCode() != 200) {
            log.error("AI 服务调用失败 status={} body={} cost={}ms", response.statusCode(), response.body(), cost);
            throw new RuntimeException("AI 服务调用失败(" + response.statusCode() + "): " + truncate(response.body(), 300));
        }
        JsonNode root = objectMapper.readTree(response.body());
        JsonNode choice = root.path("choices").path(0);
        JsonNode message = choice.path("message");
        if (message.isMissingNode()) {
            log.error("AI 响应缺少 choices[0].message: {}", response.body());
            throw new RuntimeException("AI 服务返回格式异常");
        }
        String finish = choice.path("finish_reason").asText("");
        if ("length".equals(finish)) {
            log.warn("AI 输出被 max_tokens={} 截断，建议调大设置里的最大输出 token", maxTokens);
        }
        log.info("AI 请求完成 cost={}ms 模型={} 消息数={} 思考={} finish={}",
                cost, model, messages.size(), thinkingOn ? "on" : "off", finish);
        return message;
    }

    private String truncate(String s, int max) {
        if (s == null) {
            return "";
        }
        return s.length() > max ? s.substring(0, max) + "…" : s;
    }
}
