package org.dyh.learnhub.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.dyh.learnhub.dto.AiChatMessage;
import org.dyh.learnhub.dto.AiChatRequest;
import org.dyh.learnhub.dto.NoteDTO;
import org.dyh.learnhub.dto.QuickRefDTO;
import org.dyh.learnhub.entity.Category;
import org.dyh.learnhub.service.CategoryService;
import org.dyh.learnhub.service.KnowledgeService;
import org.dyh.learnhub.service.NoteService;
import org.dyh.learnhub.service.QuickRefService;
import org.dyh.learnhub.service.SettingsService;
import org.dyh.learnhub.vo.AiChatVO;
import org.dyh.learnhub.vo.NoteVO;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 智能体编排服务。
 * <ul>
 *   <li>polish：对一段 Markdown 做「语言润色」或「整理格式」，纯文本对话，无工具</li>
 *   <li>chat：带 function-calling 的对话循环。模型要操作数据时由本地工具执行
 *       （create_note / update_note / query_notes / search_knowledge / get_note / list_categories / create_quick_ref），
 *       全部为读 + 新增/更新，不提供删除，避免破坏用户数据</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AgentService {

    /** 单次对话最多允许的工具往返轮数（防止模型陷入循环） */
    private static final int MAX_TOOL_ROUNDS = 8;
    /** 最多带入的历史消息条数 */
    private static final int MAX_HISTORY = 12;
    /**
     * 润色/整理格式的单块字符数上限：超过则按块边界切开分别处理，避免输出被截断。
     * <p>
     * 取值依据：默认 max_tokens=8192，中文约 1.5 字/token，4000 字输入对应的输出
     * 大约 3000 token，留有充足余量。阈值放宽是为了让绝大多数笔记「一次过」，
     * 避免多块拼接带来的风格不一致与接缝空行。
     */
    static final int POLISH_CHUNK_CHARS = 4000;
    /** 润色结果长度下限比例：低于该比例视为内容被压缩/截断，保留原文 */
    private static final double POLISH_MIN_RATIO = 0.75;

    /**
     * 单轮请求（润色/整理格式、或一次智能体对话）的端到端时间预算。
     * <p>
     * 前端 axios 的超时是 300s（{@code frontend/src/api/index.js} 的 AI_TIMEOUT），
     * 后端单次 HTTP 也是 300s —— 但**一轮里可能有多次请求**：
     * 分块润色是串行的，2 块 × 各 150s 就已经撞线；工具循环最多还能跑 8 轮。
     * 真发生这种情况时用户看到的是没有任何信息量的 “timeout of 300000ms exceeded”，
     * 而后端还在傻跑。所以这里主动卡一道 270s（留 30s 给网络与序列化），
     * 到点就带着「已完成几段 / 做了哪些操作」明确收尾或报错。
     */
    private static final long ROUND_BUDGET_MS = 270_000;

    /** 单次请求至少要有这么多剩余预算才值得发出（不然注定跑不完，白等一场） */
    private static final long MIN_STEP_BUDGET_MS = 30_000;

    private final DeepSeekClient client;
    private final ObjectMapper objectMapper;
    private final NoteService noteService;
    private final CategoryService categoryService;
    private final QuickRefService quickRefService;
    private final SettingsService settingsService;
    private final KnowledgeService knowledgeService;

    // ------------------------------------------------------------------
    // 1. 语言润色 / 整理格式（编辑器内调用，无工具）
    // ------------------------------------------------------------------

    public String polish(String text, String mode) {
        ensureConfigured();
        boolean isFormat = "format".equalsIgnoreCase(mode);
        // 提示词支持在设置面板里自定义：数据库有覆盖用覆盖，否则用内置默认
        String system = isFormat
                ? settingsService.effective(SettingsService.KEY_FORMAT_PROMPT)
                : settingsService.effective(SettingsService.KEY_POLISH_PROMPT);

        List<String> chunks = splitForPolish(text);
        long deadline = System.currentTimeMillis() + ROUND_BUDGET_MS;
        try {
            StringBuilder out = new StringBuilder();
            for (int i = 0; i < chunks.size(); i++) {
                String part = chunks.get(i);
                if (!StringUtils.hasText(part.trim())) {
                    out.append(part);
                    continue;
                }
                long remain = deadline - System.currentTimeMillis();
                if (remain < MIN_STEP_BUDGET_MS) {
                    // 剩下的时间不够再跑一段了。这里必须提前失败：
                    // 硬跑下去前端会先超时，用户既拿不到结果也看不到原因。
                    throw new IllegalStateException(String.format(
                            "全文过长（共 %d 段），%s 已用满 %d 秒预算（完成 %d 段）。"
                            + "请分段选中后再处理，或在设置里把「思考模式」切为「关闭」后重试。",
                            chunks.size(), isFormat ? "整理格式" : "润色",
                            ROUND_BUDGET_MS / 1000, i));
                }
                // 多段时明确告知这是全文第几段，避免模型自作主张概括整篇
                String userText = chunks.size() == 1
                        ? part
                        : "【这是全文的第 " + (i + 1) + "/" + chunks.size()
                          + " 段，请只处理本段，保持与原文相同的详略程度，不要写任何说明文字】\n\n" + part;
                // 只给这一块「剩余预算」，避免单次调用把整轮时间吃光
                JsonNode reply = chatOnce(List.of(msg("system", system), msg("user", userText)), null, true,
                        Duration.ofMillis(remain));
                String content = reply.path("content").asText("");
                if (!StringUtils.hasText(content)) {
                    throw new IllegalStateException("AI 未返回有效内容，请稍后重试");
                }
                out.append(content.trim());
                if (i < chunks.size() - 1) {
                    out.append("\n\n");
                }
            }
            String result = out.toString().trim();
            // 长度守卫：明显短于原文说明被压缩或截断，宁可返回原文也不丢内容
            if (result.length() < text.length() * POLISH_MIN_RATIO) {
                log.warn("AI {} 输出疑似缩水：原文 {} 字 → 结果 {} 字，已回退原文",
                        isFormat ? "整理格式" : "润色", text.length(), result.length());
                return text;
            }
            return result;
        } catch (IllegalStateException e) {
            throw e;
        } catch (HttpTimeoutException e) {
            throw new IllegalStateException("AI 请求超时（本轮剩余时间不足）。"
                    + "可以在设置里把「思考模式」切为「关闭」，或分段选中后再处理。");
        } catch (Exception e) {
            log.error("AI 润色失败", e);
            throw new IllegalStateException("AI 服务异常: " + e.getMessage());
        }
    }

    /**
     * 把长文切成若干块（每块尽量不超过 POLISH_CHUNK_CHARS 字符）。
     * <p>
     * 切块而不是整篇丢给模型，是为了避免输出 token 超限被截断导致字数缩水。
     * <b>关键点：只在「块边界」（空行）切，绝不切进表格或围栏代码块内部</b>——
     * 否则拼回去时补的空行会把一张表劈成两张、把代码块劈成两段，反而破坏格式。
     */
    static List<String> splitForPolish(String text) {
        if (text.length() <= POLISH_CHUNK_CHARS) {
            return List.of(text);
        }
        // 1) 先按空行切成顶层块；围栏代码块内部的空行不算边界
        List<String> blocks = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        // 存「当前开栏标记」而不是一个 boolean：``` 和 ```` 必须分开配对，
        // 否则 4 反引号围栏里嵌的 ``` 会被误判成闭合（跟 htmlQuotes.js 同一个坑）
        String fence = null;
        for (String line : text.split("\n", -1)) {
            fence = toggleFence(fence, fenceMarker(line));
            if (line.isBlank() && fence == null) {
                if (cur.length() > 0) {
                    blocks.add(cur.toString());
                    cur.setLength(0);
                }
                continue;
            }
            if (cur.length() > 0) {
                cur.append('\n');
            }
            cur.append(line);
        }
        if (cur.length() > 0) {
            blocks.add(cur.toString());
        }

        // 2) 把块合并成不超过上限的分片；单个块本身就超限时只能按行硬切
        List<String> chunks = new ArrayList<>();
        StringBuilder acc = new StringBuilder();
        for (String b : blocks) {
            if (b.length() > POLISH_CHUNK_CHARS) {
                if (acc.length() > 0) {
                    chunks.add(acc.toString());
                    acc.setLength(0);
                }
                chunks.addAll(hardSplit(b));
                continue;
            }
            if (acc.length() > 0 && acc.length() + b.length() + 2 > POLISH_CHUNK_CHARS) {
                chunks.add(acc.toString());
                acc.setLength(0);
            }
            if (acc.length() > 0) {
                acc.append("\n\n");
            }
            acc.append(b);
        }
        if (acc.length() > 0) {
            chunks.add(acc.toString());
        }
        return chunks.isEmpty() ? List.of(text) : chunks;
    }

    /**
     * 兜底：单个块（超长表格 / 超长代码块）超过上限时，按行硬切。
     * <p>
     * 光按行数切会切出「半截结构」。实测一篇笔记里有个 7611 字的代码块，被切成
     * 3997 / 3613 两片，两片的围栏都不闭合 —— 后果有两个：
     * <ol>
     *   <li>模型拿到的分片本身就不是合法 Markdown，容易自作主张「补齐」格式；</li>
     *   <li>拼回去渲染时，第二片会被吞进第一片未闭合的代码块里，整段变成代码。</li>
     * </ol>
     * 所以这里在断点处做「续接」：
     * <ul>
     *   <li>切在围栏内部 → 本片补上闭合围栏，下一片补上同样的开栏围栏；</li>
     *   <li>切在表格内部 → 下一片补上表头 + 分隔行（否则后半张表会退化成一行普通文本）。</li>
     * </ul>
     * 代价是一段长代码块会渲染成两段、一张长表会渲染成两张 —— 结构正确、内容不丢，
     * 这比「内容全在但渲染错乱」好得多。
     */
    static List<String> hardSplit(String block) {
        String[] lines = block.split("\n", -1);
        List<String> out = new ArrayList<>();
        List<String> cur = new ArrayList<>();
        int curLen = 0;

        String fenceMark = null;    // 文档当前所处的围栏标记（如 ```），null = 不在围栏内
        String fenceOpen = null;    // 开栏那一行的原文（含语言标识，如 ```java），下一片照抄它续上
        String[] tableHead = null;  // 当前表格的「表头行 + 分隔行」
        boolean prevTableRow = false;
        int resumeLen = 0;          // 本片开头「续接前缀」的长度，用来避免切出只有前缀的空片

        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];
            boolean tableRow = line.stripLeading().startsWith("|");

            // 只有「本片已经有真实内容」时才允许切，否则会切出一片只有围栏/表头的空壳
            if (curLen > resumeLen && curLen + line.length() + 1 > POLISH_CHUNK_CHARS) {
                if (fenceMark != null) {
                    cur.add(fenceMark);          // 本片先闭合，保证这一片自己是合法 Markdown
                }
                out.add(String.join("\n", cur));
                cur = new ArrayList<>();
                curLen = 0;
                resumeLen = 0;
                // 下一片把上下文续上，让模型看到的仍是完整结构
                if (fenceMark != null) {
                    cur.add(fenceOpen);          // 连语言标识一起带上，代码高亮不丢
                } else if (tableRow && tableHead != null) {
                    cur.add(tableHead[0]);
                    cur.add(tableHead[1]);
                }
                for (String s : cur) {
                    curLen += s.length() + 1;
                }
                resumeLen = curLen;
            }

            cur.add(line);
            curLen += line.length() + 1;

            // 状态跟着「整篇文档」走，而不是跟着分片走
            String mark = fenceMarker(line);
            if (mark != null) {
                if (fenceMark == null) {
                    fenceMark = mark;            // 遇到开栏
                    fenceOpen = line;
                } else if (fenceMark.charAt(0) == mark.charAt(0) && mark.length() >= fenceMark.length()) {
                    fenceMark = null;            // CommonMark：同种字符且不短于开栏才算闭合
                    fenceOpen = null;
                }
            }
            if (tableRow && !prevTableRow && i + 1 < lines.length
                    && !isTableSeparator(line) && isTableSeparator(lines[i + 1])) {
                tableHead = new String[]{line, lines[i + 1]};
            }
            prevTableRow = tableRow;
        }
        if (!cur.isEmpty()) {
            out.add(String.join("\n", cur));
        }
        return out.isEmpty() ? List.of(block) : out;
    }

    /**
     * 若该行是围栏行则返回围栏标记（连续的反引号或波浪号），否则返回 null。
     * <p>
     * 缩进 4 空格以上的是「缩进代码块」而不是围栏，必须排除；
     * 少于 3 个反引号也不是围栏（`` 是行内代码的语法）。
     */
    private static String fenceMarker(String line) {
        int indent = 0;
        while (indent < line.length() && line.charAt(indent) == ' ') {
            indent++;
        }
        if (indent > 3 || indent >= line.length()) {
            return null;
        }
        char c = line.charAt(indent);
        if (c != '`' && c != '~') {
            return null;
        }
        int end = indent;
        while (end < line.length() && line.charAt(end) == c) {
            end++;
        }
        return end - indent >= 3 ? line.substring(indent, end) : null;
    }

    /**
     * 更新「当前开栏标记」。
     *
     * @param fence 当前状态（null = 不在围栏内）
     * @param mark  本行的围栏标记（见 {@link #fenceMarker}）
     * @return 更新后的状态
     */
    private static String toggleFence(String fence, String mark) {
        if (mark == null) {
            return fence;
        }
        if (fence == null) {
            return mark;
        }
        // CommonMark：只有「同种字符且长度不短于开栏」才算闭合
        boolean closing = fence.charAt(0) == mark.charAt(0) && mark.length() >= fence.length();
        return closing ? null : fence;
    }

    /** 表格分隔行：{@code | --- | :--: |} 这种，只含 | - : 和空格，且至少有 3 个连续 - */
    private static boolean isTableSeparator(String line) {
        String s = line.strip();
        if (s.isEmpty() || s.indexOf("---") < 0) {
            return false;
        }
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c != '-' && c != '|' && c != ':' && c != ' ') {
                return false;
            }
        }
        return true;
    }

    /**
     * 发一次补全请求（读当前生效配置）。
     *
     * @param mechanical true = 机械性转换任务（润色/整理格式）：这类任务思考收益很小，
     *                   却要多花 3-4 倍时间，所以只有用户「显式开启」思考时才开启；
     *                   「自动/关闭」一律走非思考快速通道（更快，且温度参数重新生效）。
     */
    private JsonNode chatOnce(List<?> messages, List<?> tools, boolean mechanical) throws Exception {
        return chatOnce(messages, tools, mechanical, DeepSeekClient.DEFAULT_TIMEOUT);
    }

    /**
     * 同上，外加显式超时（按整轮剩余预算传）。
     *
     * @param timeout 本次 HTTP 请求的超时上限
     */
    private JsonNode chatOnce(List<?> messages, List<?> tools, boolean mechanical, Duration timeout) throws Exception {
        String thinking = client.thinking();
        if (mechanical && !"enabled".equalsIgnoreCase(thinking)) {
            thinking = "disabled";
        }
        return client.chat(messages, tools, client.baseUrl(), client.apiKey(), client.model(),
                client.maxTokens(), client.temperature(), thinking, client.reasoningEffort(), timeout);
    }

    /** 连接自检：用当前配置发一次极短请求，验证地址/密钥/模型是否可用 */
    public String testConnection() {
        return testConnection(null, null, null, null, null, null, null);
    }

    /**
     * 连通性自检。传入的字段若非空则临时覆盖（用于「测试未保存的配置」），
     * 为空则回落到当前生效配置。
     */
    public String testConnection(String baseUrl, String apiKey, String model,
                                 Integer maxTokens, Double temperature,
                                 String thinking, String reasoningEffort) {
        String b = StringUtils.hasText(baseUrl) ? baseUrl.trim() : client.baseUrl();
        String k = StringUtils.hasText(apiKey) ? apiKey.trim() : client.apiKey();
        String m = StringUtils.hasText(model) ? model.trim() : client.model();
        int mt = maxTokens != null && maxTokens > 0 ? maxTokens : client.maxTokens();
        double t = temperature != null && temperature >= 0 && temperature <= 2 ? temperature : client.temperature();
        String th = StringUtils.hasText(thinking) ? thinking.trim() : client.thinking();
        String effort = StringUtils.hasText(reasoningEffort) ? reasoningEffort.trim() : client.reasoningEffort();
        try {
            JsonNode reply = client.chat(List.of(msg("user", "ping，请只回复 pong")), null,
                    b, k, m, mt, t, th, effort);
            String content = reply.path("content").asText("");
            String mode = DeepSeekClient.isThinkingOn(m, th) ? "思考开启" : "思考关闭";
            return "连接成功：模型 " + m + "（" + mode + "）返回「"
                   + (content.length() > 30 ? content.substring(0, 30) + "…" : content) + "」";
        } catch (Exception e) {
            throw new IllegalStateException("连接失败: " + e.getMessage());
        }
    }

    // ------------------------------------------------------------------
    // 2. 智能体对话（function calling 循环）
    // ------------------------------------------------------------------

    public AiChatVO chat(AiChatRequest req) {
        ensureConfigured();
        List<Object> messages = new ArrayList<>();
        messages.add(msg("system", CHAT_SYSTEM));

        // 历史裁剪：只保留 user/assistant，最后 MAX_HISTORY 条
        List<AiChatMessage> history = req.getHistory() == null ? List.of() : req.getHistory();
        int from = Math.max(0, history.size() - MAX_HISTORY);
        for (int i = from; i < history.size(); i++) {
            AiChatMessage m = history.get(i);
            if (m.getRole() == null || m.getContent() == null) {
                continue;
            }
            if ("user".equals(m.getRole()) || "assistant".equals(m.getRole())) {
                messages.add(msg(m.getRole(), m.getContent()));
            }
        }

        // 当前笔记上下文（编辑页发起时）：单独一条 user 消息前置，防止污染角色时序
        if (StringUtils.hasText(req.getNoteContext()) || StringUtils.hasText(req.getNoteTitle())) {
            StringBuilder ctx = new StringBuilder("【当前笔记上下文】");
            if (StringUtils.hasText(req.getNoteTitle())) {
                ctx.append("标题：").append(req.getNoteTitle());
            }
            if (StringUtils.hasText(req.getNoteContext())) {
                String body = req.getNoteContext();
                ctx.append("\n正文节选：\n").append(body.length() > 1500 ? body.substring(0, 1500) + "…" : body);
            }
            ctx.append("\n\n（以上内容仅供你理解背景；若用户要求基于它修改，请调用 update_note 工具持久化。）");
            messages.add(msg("user", ctx.toString()));
        }

        messages.add(msg("user", req.getMessage()));

        List<Object> tools = toolDefinitions();
        AiChatVO vo = new AiChatVO();
        List<String> events = vo.getEvents();

        try {
            boolean outOfTime = false;
            long deadline = System.currentTimeMillis() + ROUND_BUDGET_MS;
            for (int round = 0; round < MAX_TOOL_ROUNDS; round++) {
                long remain = deadline - System.currentTimeMillis();
                if (remain < MIN_STEP_BUDGET_MS) {
                    // 预算用尽：不再发请求，带着「已经执行过的工具结果」体面收尾。
                    // 直接抛错的话用户连已完成的操作提示都看不到，更不划算。
                    outOfTime = true;
                    log.warn("对话超出 {}s 预算，在第 {} 轮提前收尾", ROUND_BUDGET_MS / 1000, round);
                    break;
                }
                JsonNode message = chatOnce(messages, tools, false, Duration.ofMillis(remain));
                JsonNode toolCalls = message.path("tool_calls");
                if (toolCalls.isArray() && !toolCalls.isEmpty()) {
                    // 1) 把模型这条含 tool_calls 的消息原样放回历史
                    messages.add(message);
                    // 2) 逐个执行并追加 tool 结果
                    for (JsonNode call : toolCalls) {
                        String id = call.path("id").asText("");
                        String fn = call.path("function").path("name").asText("");
                        String argsRaw = call.path("function").path("arguments").asText("{}");
                        String result;
                        try {
                            result = dispatch(fn, argsRaw, events);
                        } catch (Exception e) {
                            log.warn("工具 {} 执行失败: {}", fn, e.getMessage());
                            result = "{\"ok\":false,\"error\":\"" + esc(e.getMessage()) + "\"}";
                        }
                        vo.setToolUsed(true);
                        ObjectNode toolMsg = objectMapper.createObjectNode();
                        toolMsg.put("role", "tool");
                        toolMsg.put("tool_call_id", id);
                        toolMsg.put("content", result);
                        messages.add(toolMsg);
                    }
                } else {
                    // 没有 tool_calls → 收尾
                    vo.setReply(message.path("content").asText("").trim());
                    break;
                }
            }
            String reply = vo.getReply();
            if (!StringUtils.hasText(reply)) {
                reply = events.isEmpty() ? "（没有获取到有效回复，请重试）" : "已完成以上操作。" + String.join("；", events);
            }
            if (outOfTime) {
                reply = reply + "\n\n（本轮已达到时间上限，AI 提前收尾；如需继续，请再发一条消息。）";
            }
            vo.setReply(reply);
            return vo;
        } catch (IllegalStateException e) {
            throw e;
        } catch (HttpTimeoutException e) {
            throw new IllegalStateException("AI 请求超时（本轮剩余时间不足）。"
                    + "可以在设置里把「思考模式」切为「关闭」，或把问题拆小一点再问。");
        } catch (Exception e) {
            log.error("智能体对话失败", e);
            throw new IllegalStateException("AI 服务异常: " + e.getMessage());
        }
    }

    public boolean isConfigured() {
        return client.isConfigured();
    }

    public String model() {
        return client.model();
    }

    // ------------------------------------------------------------------
    // 3. 工具分发
    // ------------------------------------------------------------------

    private String dispatch(String fn, String argsRaw, List<String> events) throws Exception {
        JsonNode args = StringUtils.hasText(argsRaw) ? objectMapper.readTree(argsRaw) : objectMapper.createObjectNode();
        switch (fn) {
            case "create_note":
                return createNote(args, events);
            case "update_note":
                return updateNote(args, events);
            case "query_notes":
                return queryNotes(args);
            case "search_knowledge":
                return searchKnowledge(args);
            case "get_note":
                return getNote(args);
            case "list_categories":
                return listCategories();
            case "create_quick_ref":
                return createQuickRef(args, events);
            default:
                return "{\"ok\":false,\"error\":\"未知工具: " + esc(fn) + "\"}";
        }
    }

    /** 创建笔记；支持 category_name 自动建分类 */
    private String createNote(JsonNode args, List<String> events) {
        String title = args.path("title").asText("").trim();
        String content = args.path("content").asText("");
        if (!StringUtils.hasText(title)) {
            return "{\"ok\":false,\"error\":\"标题不能为空\"}";
        }
        NoteDTO dto = new NoteDTO();
        dto.setTitle(title);
        dto.setContent(content);
        Long categoryId = resolveCategoryId(args, events);
        dto.setCategoryId(categoryId);
        NoteVO vo = noteService.save(dto);
        events.add("📝 已创建笔记「" + vo.getTitle() + "」(#" + vo.getId() + ")");
        return okNote(vo);
    }

    /**
     * 更新已有笔记：只覆盖「模型明确传了」的字段，其余原样保留（含标签）。
     * <p>
     * 踩坑记录（两个都是真出现过的问题）：
     * <ol>
     *   <li>分类不能写成 `cond ? args.asLong() : exist.getCategoryId()`——三元表达式一边是
     *       基本类型 long、一边是包装类型 Long，按 JLS 15.25 会被提升为 long，
     *       于是 false 分支对可能为 null 的 Long 自动拆箱 → 笔记本来就没分类时直接 NPE。</li>
     *   <li>必须把原标签回填进 DTO：NoteService.update 会把「null」当成「不修改」，
     *       若这里不填，模型只是改个标题也会把标签清空。</li>
     * </ol>
     */
    private String updateNote(JsonNode args, List<String> events) {
        long id = args.path("note_id").asLong(0);
        if (id <= 0) {
            return "{\"ok\":false,\"error\":\"缺少 note_id\"}";
        }
        NoteVO exist;
        try {
            exist = noteService.detail(id);
        } catch (IllegalArgumentException e) {
            return "{\"ok\":false,\"error\":\"笔记不存在: " + id + "\"}";
        }
        NoteDTO dto = new NoteDTO();
        dto.setTitle(args.has("title") && StringUtils.hasText(args.path("title").asText())
                ? args.path("title").asText().trim() : exist.getTitle());
        dto.setContent(args.has("content") ? args.path("content").asText() : exist.getContent());
        if (args.has("category_id") && args.path("category_id").canConvertToLong()) {
            dto.setCategoryId(args.path("category_id").asLong());
        } else {
            dto.setCategoryId(exist.getCategoryId());
        }
        dto.setTagIds(exist.getTags() == null
                ? List.of()
                : exist.getTags().stream().map(t -> t.getId()).toList());
        NoteVO vo = noteService.update(id, dto);
        events.add("✏️ 已更新笔记「" + vo.getTitle() + "」(#" + vo.getId() + ")");
        return okNote(vo);
    }

    /**
     * 跨「笔记 + 速查卡」全库检索（知识库工具）。
     * 与 query_notes 的区别：覆盖速查卡，返回统一片段，回答里引用用户已有知识时优先用它；
     * 需要某篇笔记全文时再用 get_note 跟进。
     */
    private String searchKnowledge(JsonNode args) {
        String kw = args.path("keyword").asText("").trim();
        Map<String, Object> res = knowledgeService.search(kw);
        ObjectNode out = objectMapper.createObjectNode();
        out.put("ok", true);
        out.put("keyword", kw);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> items = (List<Map<String, Object>>) res.get("items");
        ArrayNode arr = out.putArray("items");
        for (Map<String, Object> it : items) {
            ObjectNode o = arr.addObject();
            o.put("type", String.valueOf(it.get("type")));
            o.put("id", ((Number) it.get("id")).longValue());
            o.put("title", nullTo((String) it.get("title")));
            o.put("category", nullTo((String) it.get("categoryName")));
            o.put("snippet", nullTo((String) it.get("snippet")));
        }
        return out.toString();
    }

    /** 检索笔记：标题+摘要，控制 token */
    private String queryNotes(JsonNode args) {        String kw = args.path("keyword").asText("");
        ObjectNode out = objectMapper.createObjectNode();
        out.put("ok", true);
        ArrayNode arr = out.putArray("notes");
        noteService.page(null, null, kw, 1, 6).getList().forEach(n -> {
            ObjectNode o = arr.addObject();
            o.put("note_id", n.getId());
            o.put("title", n.getTitle());
            o.put("category", nullTo(n.getCategoryName()));
            o.put("summary", nullTo(n.getSummary()));
        });
        return out.toString();
    }

    /** 读取笔记全文 */
    private String getNote(JsonNode args) {
        long id = args.path("note_id").asLong(0);
        if (id <= 0) {
            return "{\"ok\":false,\"error\":\"缺少 note_id\"}";
        }
        try {
            NoteVO vo = noteService.detail(id);
            ObjectNode out = objectMapper.createObjectNode();
            out.put("ok", true);
            out.put("note_id", vo.getId());
            out.put("title", nullTo(vo.getTitle()));
            out.put("category", nullTo(vo.getCategoryName()));
            out.put("content", nullTo(vo.getContent()));
            return out.toString();
        } catch (IllegalArgumentException e) {
            return "{\"ok\":false,\"error\":\"笔记不存在: " + id + "\"}";
        }
    }

    /** 分类扁平化列表（含层级路径），方便模型选分类 */
    private String listCategories() {
        ObjectNode out = objectMapper.createObjectNode();
        out.put("ok", true);
        ArrayNode arr = out.putArray("categories");
        walk(categoryService.tree(), "", arr);
        return out.toString();
    }

    private void walk(List<Category> nodes, String parentPath, ArrayNode arr) {
        if (nodes == null) {
            return;
        }
        for (Category c : nodes) {
            String path = parentPath.isEmpty() ? c.getName() : parentPath + " / " + c.getName();
            ObjectNode o = arr.addObject();
            o.put("category_id", c.getId());
            o.put("name", nullTo(c.getName()));
            o.put("path", path);
            walk(c.getChildren(), path, arr);
        }
    }

    /** 创建速查卡 */
    private String createQuickRef(JsonNode args, List<String> events) {
        String title = args.path("title").asText("").trim();
        String content = args.path("content").asText("");
        if (!StringUtils.hasText(title)) {
            return "{\"ok\":false,\"error\":\"标题不能为空\"}";
        }
        QuickRefDTO dto = new QuickRefDTO();
        dto.setTitle(title);
        dto.setContent(content);
        dto.setCategoryId(resolveCategoryId(args, events));
        var vo = quickRefService.save(dto);
        events.add("⚡ 已创建速查卡「" + vo.getTitle() + "」(#" + vo.getId() + ")");
        ObjectNode out = objectMapper.createObjectNode();
        out.put("ok", true);
        out.put("quick_ref_id", vo.getId());
        out.put("title", nullTo(vo.getTitle()));
        return out.toString();
    }

    /** 解析 category_name → id；不存在则自动创建 */
    private Long resolveCategoryId(JsonNode args, List<String> events) {
        if (args.has("category_id") && args.path("category_id").canConvertToLong()) {
            long cid = args.path("category_id").asLong();
            if (cid > 0 && categoryService.getById(cid) != null) {
                return cid;
            }
        }
        String name = args.path("category_name").asText("").trim();
        if (!StringUtils.hasText(name)) {
            return null;
        }
        for (Category c : flatten(categoryService.tree())) {
            if (name.equals(c.getName())) {
                return c.getId();
            }
        }
        Category created = new Category();
        created.setName(name);
        categoryService.save(created);
        events.add("🗂 已自动新建分类「" + name + "」");
        return created.getId();
    }

    private List<Category> flatten(List<Category> nodes) {
        List<Category> out = new ArrayList<>();
        if (nodes == null) {
            return out;
        }
        for (Category c : nodes) {
            out.add(c);
            out.addAll(flatten(c.getChildren()));
        }
        return out;
    }

    private String okNote(NoteVO vo) {
        ObjectNode out = objectMapper.createObjectNode();
        out.put("ok", true);
        out.put("note_id", vo.getId());
        out.put("title", nullTo(vo.getTitle()));
        out.put("category", nullTo(vo.getCategoryName()));
        return out.toString();
    }

    private String nullTo(String s) {
        return s == null ? "" : s;
    }

    private String esc(String s) {
        return s == null ? "" : s.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private ObjectNode msg(String role, String content) {
        ObjectNode n = objectMapper.createObjectNode();
        n.put("role", role);
        n.put("content", content == null ? "" : content);
        return n;
    }

    private void ensureConfigured() {
        if (!client.isConfigured()) {
            throw new IllegalStateException(
                    "智能体尚未配置 API Key：请在「设置 → 模型与接口」里填写 API 地址、密钥与模型名称（也可在 backend/.env 配置 DEEPSEEK_API_KEY）");
        }
    }

    // ------------------------------------------------------------------
    // 4. 系统提示词与工具定义
    // ------------------------------------------------------------------

    private static final String CHAT_SYSTEM = """
            你是「学习工作台 · 智能体」，一位专注编程与 IT 学习的中文辅导助手，运行在用户自己的知识工作台上。

            用户正在从工程造价/预算岗位转型学 IT，请用这套方式讲：
            1. 先用一句话 + 直觉类比（可用造价、建筑、工地场景打比方）建立直觉，再讲严谨定义，最后给可运行的示例。
            2. 用简体中文，语气务实、直接、有耐心；涉及代码必须用 Markdown 代码块并标注语言。
            3. 回答较长时用标题 / 列表 / 表格分节，不要堆一大段；能给出命令/代码就尽量给全。
            4. 你拥有操作本工作台数据的能力：把知识点沉淀成「笔记」，把短平快的命令与易错点沉淀成「速查卡」，也可以检索或读取用户已有的笔记、分类。
            8. 用户的工作台就是他的知识库：回答技术问题前，先用 search_knowledge 检索用户自己记过的相关内容；如果用户笔记里的说法与通用答案有出入，指出差异并尊重用户自己的记录（用词可以是“你之前记的是…”）。
            5. 只有用户明确要求“存成笔记 / 做个速查卡 / 记下来”，或者你认为该知识点非常值得沉淀时才主动调用创建类工具；普通答疑不要擅自写入。
            6. update_note 只在用户明确让你补充/修改某篇笔记时使用。
            7. 工具执行结果以 JSON 返回，把它们自然地总结给用户听，不要复述原始 JSON。
            """;

    /** 工具定义列表（OpenAI function calling schema，会随请求带给模型） */
    private List<Object> toolDefinitions() {
        List<Object> defs = new ArrayList<>();
        defs.add(tool("create_note",
                "创建一篇新的 Markdown 笔记（沉淀知识点）。当用户说“记成笔记/存为笔记/做成笔记”，或明确要沉淀当前内容时调用。",
                List.of(
                        param("title", "string", "笔记标题", true),
                        param("content", "string", "Markdown 正文", true),
                        param("category_name", "string", "目标分类名（不存在会自动创建；可留空）", false))));
        defs.add(tool("update_note",
                "更新已有笔记的标题/正文/分类。当用户要求“把刚才的回答补充进笔记/替换正文/修改这篇笔记”时调用。",
                List.of(
                        param("note_id", "integer", "笔记 id", true),
                        param("title", "string", "新标题（不传则保留原标题）", false),
                        param("content", "string", "新 Markdown 正文（不传则保留原正文）", false))));
        defs.add(tool("query_notes",
                "按关键词检索用户已有笔记，返回标题+摘要列表（不含全文）。回答前若想参考用户以前学过什么可以调用。",
                List.of(param("keyword", "string", "检索关键词（可空，空则取最近笔记）", false))));
        defs.add(tool("search_knowledge",
                "跨「笔记 + 速查卡」全库检索，返回带上下文片段的统一列表。回答用户提问前，若问题可能与用户已记录的知识相关（报错排查、命令用法、概念解释等），应优先调用本工具参考用户已有知识；需要某篇笔记全文时再用 get_note 跟进。",
                List.of(param("keyword", "string", "检索关键词（可空，空则返回最近知识）", false))));
        defs.add(tool("get_note",
                "读取某篇笔记的完整 Markdown 正文。",
                List.of(param("note_id", "integer", "笔记 id", true))));
        defs.add(tool("list_categories",
                "列出工作台全部分类（含层级路径与 id），用于确定新建内容应放哪个分类。",
                List.of()));
        defs.add(tool("create_quick_ref",
                "创建一条「速查卡」（短平快的命令/API 签名/易错点）。当用户明确要求或内容是短小速查型知识时调用。",
                List.of(
                        param("title", "string", "速查项标题，如：Docker 常用命令", true),
                        param("content", "string", "速查内容，支持 Markdown", true),
                        param("category_name", "string", "目标分类名（不存在会自动创建；可留空）", false))));
        return defs;
    }

    // ---------- 工具 schema 构建工具 ----------

    /** 单个属性描述 */
    private record Param(String name, String type, String desc, boolean required) {
    }

    private Param param(String name, String type, String desc, boolean required) {
        return new Param(name, type, desc, required);
    }

    private ObjectNode tool(String name, String desc, List<Param> params) {
        ObjectNode fn = objectMapper.createObjectNode();
        fn.put("name", name);
        fn.put("description", desc);
        fn.set("parameters", paramsNode(params));
        ObjectNode toolNode = objectMapper.createObjectNode();
        toolNode.put("type", "function");
        toolNode.set("function", fn);
        return toolNode;
    }

    /** parameters 根节点：{type:object, properties:{...}, required:[...]} */
    private ObjectNode paramsNode(List<Param> params) {
        ObjectNode root = objectMapper.createObjectNode();
        root.put("type", "object");
        ObjectNode properties = root.putObject("properties");
        List<String> required = new ArrayList<>();
        for (Param p : params) {
            ObjectNode prop = properties.putObject(p.name());
            prop.put("type", p.type());
            prop.put("description", p.desc());
            if (p.required()) {
                required.add(p.name());
            }
        }
        if (!required.isEmpty()) {
            ArrayNode req = root.putArray("required");
            required.forEach(req::add);
        }
        return root;
    }
}
