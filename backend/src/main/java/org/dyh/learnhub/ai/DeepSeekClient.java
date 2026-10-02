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
import org.springframework.util.StringUtils;

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
    /** 模型配置档案：当前的模型/基址/密钥都来自**激活档案**（可无限新增多个） */
    private final org.dyh.learnhub.service.ModelProfileService profiles;
    /** 任务分工表：wiki / 重排等任务可能指向另一个档案，所以这里要按任务解析而不是只认激活档案 */
    private final ModelRouting routing;

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(15))
            .version(HttpClient.Version.HTTP_1_1)
            .build();

    /** API Key 是否已配置（设置面板 > .env；未配置时前端给出引导提示） */
    public boolean isConfigured() {
        String k = apiKey();
        return k != null && !k.isBlank();
    }

    /**
     * 当前生效模型 —— **来自当前激活的模型档案**。
     * <p>2026-09 改造：原来读的是单个设置键 {@code ai.model}，只能配一个模型；
     * 现在可以是任意多个档案（DeepSeek / Kimi / 本地 Ollama…），这里返回激活档案的那个。
     */
    public String model() {
        org.dyh.learnhub.service.ModelProfileService.Target t = profiles.resolve(null);
        return t.model() != null ? t.model() : settingsService.effective(SettingsService.KEY_MODEL);
    }

    /** 当前生效 API 地址（激活档案的基址） */
    public String baseUrl() {
        org.dyh.learnhub.service.ModelProfileService.Target t = profiles.resolve(null);
        String v = t.baseUrl() != null ? t.baseUrl() : settingsService.effective(SettingsService.KEY_BASE_URL);
        return normalizeBase(v);
    }

    /** 当前生效 API Key：激活档案优先，其次老设置，最后 .env / application.yml */
    public String apiKey() {
        org.dyh.learnhub.service.ModelProfileService.Target t = profiles.resolve(null);
        if (StringUtils.hasText(t.apiKey())) {
            return t.apiKey().trim();
        }
        String v = settingsService.effective(SettingsService.KEY_API_KEY);
        return v != null && !v.isBlank() ? v.trim() : props.getApiKey();
    }

    // ------------------------------------------------------------------
    // 生成参数：**优先取模型档案上的值，没配才用全局默认**
    //
    // 为什么参数要跟着档案走：本地 qwen3:8b 与云端 deepseek-flash 想要的并不一样 ——
    // 小模型要更小的输出上限、思考型模型下发温度会被忽略、机械任务要关掉思考才快。
    // 全局单值等于逼所有模型共用一套折中值。
    //
    // 无参版本委托给**当前激活档案**，于是所有既有调用点（line 14 处）自动跟随，
    // 不必逐个改；明确用了别的档案的地方（wiki/重排/核对…）传档案 id 即可。
    // ------------------------------------------------------------------

    /** 输出上限（默认 8192，避免长文被截断）。优先档案上的值 */
    public int maxTokens() {
        return maxTokensOf(profiles.activeId());
    }

    public int maxTokensOf(String profileId) {
        Integer v = profiles.resolve(profileId).maxTokens();
        if (v != null && v > 0) {
            return v;
        }
        try {
            int n = Integer.parseInt(orEmpty(settingsService.effective(SettingsService.KEY_MAX_TOKENS)));
            return n > 0 ? n : 8192;
        } catch (NumberFormatException e) {
            return 8192;
        }
    }

    /** 温度（思考模式下服务端会忽略它）。优先档案上的值 */
    public double temperature() {
        return temperatureOf(profiles.activeId());
    }

    public double temperatureOf(String profileId) {
        java.math.BigDecimal v = profiles.resolve(profileId).temperature();
        if (v != null) {
            double d = v.doubleValue();
            if (d >= 0 && d <= 2) {
                return d;
            }
        }
        try {
            double d = Double.parseDouble(orEmpty(settingsService.effective(SettingsService.KEY_TEMPERATURE)));
            return d >= 0 && d <= 2 ? d : props.getTemperature();
        } catch (NumberFormatException e) {
            return props.getTemperature();
        }
    }

    /** 思考模式：null/空 = 自动（由模型决定），"enabled"/"disabled" = 强制开关。优先档案上的值 */
    public String thinking() {
        return thinkingOf(profiles.activeId());
    }

    public String thinkingOf(String profileId) {
        String v = profiles.resolve(profileId).thinking();
        return StringUtils.hasText(v) ? v : settingsService.effective(SettingsService.KEY_THINKING);
    }

    /** 思考强度：null/空 = 服务端默认。优先档案上的值 */
    public String reasoningEffort() {
        return reasoningEffortOf(profiles.activeId());
    }

    public String reasoningEffortOf(String profileId) {
        String v = profiles.resolve(profileId).reasoningEffort();
        return StringUtils.hasText(v) ? v : settingsService.effective(SettingsService.KEY_REASONING_EFFORT);
    }

    private static String orEmpty(String s) {
        return s == null ? "" : s.trim();
    }

    // ------------------------------------------------------------------
    // 长文生成（主题 wiki）用的模型目标：可选覆写，留空跟随主模型
    // ------------------------------------------------------------------

    /**
     * wiki 生成用的接口地址 —— **由"主题 wiki"这个任务指向的档案**决定。
     * <p>旧语义是"一个独立的本地目标，留空跟随主模型"；现在等价于
     * "wiki 任务的档案 = 主档案 → 跟随；指向本地档案 → 零成本"。语义一致，表达更自由。
     */
    public String wikiBaseUrl() {
        org.dyh.learnhub.service.ModelProfileService.Target t = profiles.resolve(routing.targetIdOf(ModelRouting.TASK_WIKI));
        String v = t.baseUrl();
        return v == null || v.isBlank() ? baseUrl() : normalizeBase(v);
    }

    public String wikiModel() {
        org.dyh.learnhub.service.ModelProfileService.Target t = profiles.resolve(routing.targetIdOf(ModelRouting.TASK_WIKI));
        return t.model() == null || t.model().isBlank() ? model() : t.model();
    }

    /** 独立目标可以不配密钥（Ollama 不需要）；档案没配则复用主 Key */
    public String wikiApiKey() {
        org.dyh.learnhub.service.ModelProfileService.Target t = profiles.resolve(routing.targetIdOf(ModelRouting.TASK_WIKI));
        if (StringUtils.hasText(t.apiKey())) {
            return t.apiKey().trim();
        }
        // 换了服务端却留空 Key：不要拿 DeepSeek 的密钥去请求别人的服务
        boolean sameTarget = java.util.Objects.equals(t.baseUrl(), baseUrl());
        return sameTarget ? apiKey() : "";
    }

    /** wiki 是否用了**另一个**目标：决定要不要关掉 DeepSeek 专有的 thinking 参数 */
    public boolean wikiUsesSeparateTarget() {
        org.dyh.learnhub.service.ModelProfileService.Target t = profiles.resolve(routing.targetIdOf(ModelRouting.TASK_WIKI));
        return t.id() != null && !java.util.Objects.equals(t.id(), profiles.activeId());
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
     * 请求体构造（chat 与 chatStream 共用）。
     *
     * @param stream 是否流式：流式时不要 thinking 输出混进正文（由服务端处理），仅加 stream 字段
     */
    private ObjectNode buildBody(List<?> messages, List<?> tools, String baseUrl, String model,
                                 int maxTokens, double temperature,
                                 String thinking, String reasoningEffort, boolean stream) {
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
        if (stream) {
            body.put("stream", true);
        }
        return body;
    }

    /**
     * 流式补全：每收到一段增量文本就回调一次已累计的字符数。
     * <p>
     * 为什么 wiki 生成要走流式：本地小模型出一页要 20~35 秒，非流式在这段时间里界面上只能干等。
     * 流式能给出**真实的**"已生成 N 字"，也顺带避免长输出撞上整体超时。
     * 返回的是拼接后的完整正文；解析不了的行直接忽略（SSE 里可能有 keep-alive 注释）。
     *
     * @param onChars 累计字符数回调（用于进度）；可为 null
     */
    public String chatStream(List<?> messages, String baseUrl, String apiKey, String model,
                             int maxTokens, double temperature, String thinking, String reasoningEffort,
                             Duration timeout, java.util.function.IntConsumer onChars) throws Exception {
        String payload = objectMapper.writeValueAsString(
                buildBody(messages, null, baseUrl, model, maxTokens, temperature, thinking, reasoningEffort, true));
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(normalizeBase(baseUrl) + "/chat/completions"))
                .timeout(timeout)
                .header("Content-Type", "application/json; charset=utf-8")
                .header("Authorization", "Bearer " + apiKey)
                .POST(HttpRequest.BodyPublishers.ofString(payload, StandardCharsets.UTF_8))
                .build();

        HttpResponse<java.util.stream.Stream<String>> response =
                http.send(request, HttpResponse.BodyHandlers.ofLines());
        if (response.statusCode() != 200) {
            String err = response.body().limit(4).reduce("", (a, b) -> a + b);
            log.error("AI 流式调用失败 status={} body={}", response.statusCode(), truncate(err, 200));
            throw new RuntimeException("AI 服务调用失败(" + response.statusCode() + "): " + truncate(err, 200));
        }
        StringBuilder acc = new StringBuilder();
        response.body().forEach(line -> {
            if (line == null || line.isEmpty() || !line.startsWith("data:")) {
                return;
            }
            String data = line.substring(5).trim();
            if (data.isEmpty() || "[DONE]".equals(data)) {
                return;
            }
            try {
                JsonNode delta = objectMapper.readTree(data).path("choices").path(0).path("delta");
                String piece = delta.path("content").asText("");
                if (!piece.isEmpty()) {
                    acc.append(piece);
                    if (onChars != null) {
                        onChars.accept(acc.length());
                    }
                }
            } catch (Exception ignored) {
                // 单行解析失败不影响整体：SSE 里可能夹着非 data 行或半截 JSON
            }
        });
        return acc.toString();
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
        return chatFull(messages, tools, baseUrl, apiKey, model, maxTokens, temperature,
                thinking, reasoningEffort, timeout).message();
    }

    /**
     * 一次调用的完整结果。
     *
     * <p>为什么需要它：{@code finish_reason} 与 token 用量以前只用来打一行日志 ——
     * 于是"回答看起来少了半句"这种问题**在库里查不到任何证据**（只能靠事后猜，
     * 实测就被问过一次"是不是达到最大字数了"）。把它们带回调用方，
     * 才能给用户一句明确提示、并落库备查。
     *
     * @param finishReason      {@code stop} / {@code length}（截断）/ {@code tool_calls}
     * @param promptTokens      本次输入 token 数（答案级评测要算总成本，必须带上输入侧）
     * @param completionTokens  本次输出 token 总数（**含思考**）
     * @param reasoningTokens   其中被思考用掉的 token —— 它和正文共享 max_tokens，
     *                          所以"正文没写多少却撞上限"通常就是它在吃预算（实测：max_tokens=400 时
     *                          399 个 token 全归思考，正文 0 字）
     */
    public record ChatResult(JsonNode message, String finishReason, int promptTokens,
                             int completionTokens, int reasoningTokens) {
        /** 是否因为达到 max_tokens 被截断 */
        public boolean truncated() {
            return "length".equals(finishReason);
        }
    }

    /** 与 {@link #chat} 同参数，但把 finish_reason 与 token 用量一起返回 */
    public ChatResult chatFull(List<?> messages, List<?> tools,
                               String baseUrl, String apiKey, String model,
                               int maxTokens, double temperature,
                               String thinking, String reasoningEffort,
                               Duration timeout) throws Exception {
        String payload = objectMapper.writeValueAsString(
                buildBody(messages, tools, baseUrl, model, maxTokens, temperature, thinking, reasoningEffort, false));
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
            throw new RuntimeException(authHint(response.statusCode())
                    + "AI 服务调用失败(" + response.statusCode() + "): " + truncate(response.body(), 300)
                    + "　【目标：" + normalizeBase(baseUrl) + " / " + model + "】");
        }
        JsonNode root = objectMapper.readTree(response.body());
        JsonNode choice = root.path("choices").path(0);
        JsonNode message = choice.path("message");
        if (message.isMissingNode()) {
            log.error("AI 响应缺少 choices[0].message: {}", response.body());
            throw new RuntimeException("AI 服务返回格式异常");
        }
        String finish = choice.path("finish_reason").asText("");
        JsonNode usage = root.path("usage");
        int prompt = usage.path("prompt_tokens").asInt(0);
        int completion = usage.path("completion_tokens").asInt(0);
        int reasoning = usage.path("completion_tokens_details").path("reasoning_tokens").asInt(0);
        if ("length".equals(finish)) {
            log.warn("AI 输出被 max_tokens={} 截断（输出 {} token，其中思考 {} token），"
                            + "建议调大设置里的最大输出 token",
                    maxTokens, completion, reasoning);
        }
        log.info("AI 请求完成 cost={}ms 模型={} 消息数={} 思考={} finish={} tokens={}(思考 {})",
                cost, model, messages.size(), isThinkingOn(model, thinking) ? "on" : "off",
                finish, completion, reasoning);
        return new ChatResult(message, finish, prompt, completion, reasoning);
    }

    private String truncate(String s, int max) {
        if (s == null) {
            return "";
        }
        return s.length() > max ? s.substring(0, max) + "…" : s;
    }

    /**
     * 鉴权类失败的**人话提示**。
     *
     * <p>不加这一段，用户看到的是"AI 服务调用失败(401): {"error":{"message":"Authentication Fails,
     * Your api key: null is invalid"…}}" —— 要自己从 JSON 里读出"密钥没配"。
     * 实测就踩过：清空某个档案的密钥后，用它对话只报 500，得翻日志才知道是 401。
     * 这里把最常见的三类直接说清，并点明是**哪个档案**（基址+模型名由调用方拼在后面）。
     */
    private static String authHint(int status) {
        return switch (status) {
            case 401, 403 -> "【模型鉴权失败】该档案的 API Key 无效或未配置 —— 去「设置 → 模型档案与分工」填好密钥，"
                    + "或用「设置 → 模型参数」把这条档案的「测试连接」跑通。原始错误：";
            case 404 -> "【接口地址或模型名不对】404 多为基址缺 /v1，或模型名该账号没有。原始错误：";
            case 413 -> "【请求体过大】服务端拒收：本轮发给模型的内容超过了它的上限"
                    + "（长会话、注入的长文档、以及思考模式回传的历史思考都会把它撑大）。"
                    + "处理：开一个新对话，或把「思考模式」切成 disabled 减少回传，"
                    + "或缩短投喂的长文；若是自建网关，注意 nginx 的 client_max_body_size 默认只有 1MB。原始错误：";
            case 429 -> "【被限流或额度用尽】稍后重试，或换一条档案。原始错误：";
            default -> "";
        };
    }
}
