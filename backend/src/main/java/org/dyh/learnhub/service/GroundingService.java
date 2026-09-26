package org.dyh.learnhub.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.dyh.learnhub.ai.DeepSeekClient;
import org.dyh.learnhub.ai.ModelRouting;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 答案级校验（grounding check）：回答之后，核对它有没有**超出注入的证据**。
 *
 * <h3>为什么检索做得再好也要这一步</h3>
 * 检索只能保证"材料在上下文里"，不能保证"模型按材料说话"。实测会出现的两种情况：
 * <ol>
 *   <li>材料里没有的事实被模型用**常识补全**（听起来对、但你的库里没写过 ——
 *       对"我的知识库到底记了什么"这个问题来说，这就是错的）；</li>
 *   <li>材料里有 A 和 B，模型把两者**串成一个错的因果**。</li>
 * </ol>
 *
 * <h3>做法</h3>
 * 把（问题 / 注入的证据 / 模型答案）一起给模型，要求它只做一件事：
 * 列出答案里**无法由证据支撑**的断言。返回 JSON：
 * {@code {"grounded":true|false,"unsupported":["…"],"note":"…"}}。
 *
 * <p>刻意的设计取舍：
 * <ul>
 *   <li><b>只标记、不改写</b>：发现不支撑就把结论亮出来，由人判断。
 *       自动改写会掩盖"知识库确实没记这个"这一事实。</li>
 *   <li><b>没有证据时不校验</b>：本轮没检索到任何东西（模型纯凭自身知识回答）时，
 *       标"无证据"更诚实 —— 而不是把常识回答判成"幻觉"。</li>
 *   <li><b>校验失败不阻塞回答</b>：它只是事后核对，挂了就跳过。</li>
 * </ul>
 */
@Service
public class GroundingService {

    private static final Logger log = LoggerFactory.getLogger(GroundingService.class);

    /** 证据文本上限：只在"判断答案有没有超出材料"这件事上够用即可 */
    private static final int EVIDENCE_CHARS = 5000;
    /** 答案文本上限 */
    private static final int ANSWER_CHARS = 3000;

    public static final String SETTING_GROUNDING = "kb.grounding";

    private final DeepSeekClient client;
    private final ModelRouting routing;
    private final ObjectMapper objectMapper;
    private final SettingsService settingsService;

    public GroundingService(DeepSeekClient client, ModelRouting routing, ObjectMapper objectMapper,
                            SettingsService settingsService) {
        this.client = client;
        this.routing = routing;
        this.objectMapper = objectMapper;
        this.settingsService = settingsService;
    }

    public boolean enabled() {
        return "1".equals(settingsService.effective(SETTING_GROUNDING));
    }

    /**
     * 校验结果。
     *
     * @param checked  是否真的校验了（没证据 / 开关关 / 调用失败都是 false）
     * @param grounded 答案是否全部由证据支撑
     * @param unsupported 不被支撑的断言（原句摘录）
     * @param note     一句话说明
     * @param evidenceChars 本轮注入的证据字数（让人知道"基于多少材料"）
     */
    public record Result(boolean checked, boolean grounded, List<String> unsupported, String note,
                         int evidenceChars) {
        public static Result skipped(String why) {
            return new Result(false, true, List.of(), why, 0);
        }
    }

    /**
     * 核对答案。
     *
     * @param question 用户问题
     * @param evidence 本轮注入给模型的**全部**证据文本（检索块 + wiki 块 + 图谱块）
     * @param answer   模型最终答案
     */
    public Result check(String question, String evidence, String answer) {
        if (!enabled()) {
            return Result.skipped("未开启校验");
        }
        if (!StringUtils.hasText(evidence)) {
            return Result.skipped("本轮没有检索到材料，答案基于模型自身知识");
        }
        if (!StringUtils.hasText(answer)) {
            return Result.skipped("答案为空");
        }
        int evLen = Math.min(evidence.length(), EVIDENCE_CHARS);
        String system = """
                你是答案的**依据核对器**。给定用户问题、系统检索到的材料、以及助手的回答，
                请判断：回答里的关键断言，能不能由材料支撑？
                规则：
                1. 只判"材料里有没有"，**不要**判你自己认为对不对；
                2. 材料的常识性表述（例如"Java 是编程语言"）如果回答里也提到，但材料没写，算**不支撑**；
                3. 建议、步骤、代码示例若材料没提，也算不支撑；
                4. 只列**关键的**不支撑断言，最多 5 条，每条用回答里的原话（可截短）；
                5. 全部有支撑就返回空数组。
                只输出严格 JSON：{"grounded":true|false,"unsupported":["…"],"note":"一句话说明（≤40字）"}
                不要输出 JSON 之外的内容。
                """;
        String user = "【用户问题】\n" + clip(question, 500)
                + "\n\n【系统检索到的材料】\n" + clip(evidence, EVIDENCE_CHARS)
                + "\n\n【助手回答】\n" + clip(answer, ANSWER_CHARS);
        ModelRouting.ModelTarget t = routing.forTask(ModelRouting.TASK_GROUNDING);
        try {
            JsonNode node = client.chat(List.of(
                            Map.of("role", "system", "content", system),
                            Map.of("role", "user", "content", user)),
                    null, t.baseUrl(), t.apiKey(), t.model(), 600, 0.1,
                    "disabled", null, Duration.ofSeconds(25));
            String content = node.path("content").asText("");
            if (!StringUtils.hasText(content)) {
                return Result.skipped("核对返回空内容");
            }
            JsonNode root = objectMapper.readTree(stripFence(content));
            boolean grounded = root.path("grounded").asBoolean(true);
            List<String> unsupported = new ArrayList<>();
            for (JsonNode n : root.path("unsupported")) {
                if (n.isTextual() && !n.asText().isBlank()) {
                    unsupported.add(n.asText().trim());
                }
            }
            String note = root.path("note").asText("");
            boolean ok = grounded && unsupported.isEmpty();
            log.info("答案核对：grounded={} 不支撑 {} 条（证据 {} 字）", ok, unsupported.size(), evLen);
            return new Result(true, ok, unsupported, note, evLen);
        } catch (Exception e) {
            log.warn("答案核对失败（跳过，不影响回答）：{}", e.toString());
            return Result.skipped("核对失败：" + e.getMessage());
        }
    }

    /** 给界面/日志用的状态 */
    public Map<String, Object> status() {
        Map<String, Object> o = new LinkedHashMap<>();
        o.put("enabled", enabled());
        o.put("evidenceChars", EVIDENCE_CHARS);
        return o;
    }

    private static String clip(String s, int max) {
        if (s == null) {
            return "";
        }
        return s.length() > max ? s.substring(0, max) + "…" : s;
    }

    private static String stripFence(String s) {
        String t = s == null ? "" : s.trim();
        if (t.startsWith("```")) {
            int nl = t.indexOf('\n');
            if (nl > 0) {
                t = t.substring(nl + 1);
            }
            int end = t.lastIndexOf("```");
            if (end >= 0) {
                t = t.substring(0, end);
            }
        }
        return t.trim();
    }
}
