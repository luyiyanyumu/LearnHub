package org.dyh.learnhub.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.dyh.learnhub.ai.DeepSeekClient;
import org.dyh.learnhub.ai.ModelRouting;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 阅读器里的**分段翻译**。
 *
 * <h3>为什么按段翻、而不是"一键翻全文"</h3>
 * 一份论文正文十几万字，一次翻译既超出上下文也超出输出上限（实测踩过：模型一次写两万字
 * 就被输出上限截断，参数 JSON 断在半路）。所以这里**只接受一段文本**（上限见 {@link #MAX_CHARS}），
 * 由界面按段落触发；读到哪里翻到哪里，既快又能立刻判断质量。
 *
 * <h3>为什么默认走本地档案</h3>
 * 翻译是机械任务、不依赖推理：本地 qwen3:8b 逐段翻译质量够读，且零成本。
 * 嫌质量差可以在「模型参数」里把"阅读器翻译"这一项换成云端档案 —— 这个开关是现成的，
 * 因为分工表是从 {@link ModelRouting} 派生的。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TranslationService {

    /** 单次翻译的输入上限（约一段或几段）；超了让界面拆开，别硬塞 */
    public static final int MAX_CHARS = 4000;

    private static final String PROMPT = """
            你是技术文档翻译。把用户给的英文（或其它语言）技术文本翻译成**简体中文**。
            要求：
            1. 只输出译文本身，不要任何解释、不要前后缀、不要 markdown 代码块包裹；
            2. 术语保留通行译法，专有名词/模型名/库名/命令**保留原文**（如 Transformer、Qwen、pip install）；
            3. 保持原有的分段与列表结构；
            4. 原文已是中文则原样返回。
            """;

    private final DeepSeekClient client;
    private final ModelRouting routing;

    /** 单段翻译；返回译文与所用模型（界面要显示"谁翻的"，否则不知道质量该怪谁） */
    public Map<String, Object> translate(String text, String targetLang) {
        if (!StringUtils.hasText(text)) {
            throw new IllegalArgumentException("没有要翻译的内容");
        }
        String src = text.trim();
        if (src.length() > MAX_CHARS) {
            // 明确报错而不是悄悄截断：截断翻译会让人以为"原文只有这么多"
            throw new IllegalArgumentException("这一段太长（" + src.length() + " 字，上限 " + MAX_CHARS
                    + "）—— 请选中更小的一段再翻");
        }
        ModelRouting.ModelTarget t = routing.forTask(ModelRouting.TASK_TRANSLATE);
        String lang = StringUtils.hasText(targetLang) ? targetLang.trim() : "简体中文";
        String ask = PROMPT + "\n目标语言：" + lang + "\n\n待翻译文本：\n" + src;
        long started = System.currentTimeMillis();
        String out;
        try {
            out = client.chat(List.of(Map.of("role", "user", "content", ask)), null,
                    t.baseUrl(), t.apiKey(), t.model(),
                    // 温度 0：翻译要稳，不要每次不一样；思考关掉（机械任务，开思考只会变慢）
                    2048, 0.0, "disabled", null, Duration.ofSeconds(120))
                    .path("content").asText("").trim();
        } catch (Exception e) {
            // 把"哪个档案失败"带出来：换档是用户最可能的下一步动作
            throw new IllegalStateException("翻译调用失败（档案：" + t.label() + " / " + t.model() + "）："
                    + e.getMessage(), e);
        }
        long cost = System.currentTimeMillis() - started;
        if (!StringUtils.hasText(out)) {
            throw new IllegalStateException("模型没有返回译文（可能是输出上限或服务异常），请重试或换一个档案");
        }
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("translation", out);
        m.put("model", t.model());
        m.put("profile", t.label());
        m.put("local", t.separate());
        m.put("ms", cost);
        m.put("sourceChars", src.length());
        log.info("翻译完成 {} 字 -> {}（{}，{}ms）", src.length(), out.length(), t.model(), cost);
        return m;
    }

    /** 供界面一次性判断"这段能不能翻"（超限时先提示，别等点了才报错） */
    public Map<String, Object> capabilities() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("maxChars", MAX_CHARS);
        ModelRouting.ModelTarget t = routing.forTask(ModelRouting.TASK_TRANSLATE);
        m.put("profile", t.label());
        m.put("model", t.model());
        m.put("local", t.separate());
        List<String> langs = new ArrayList<>(List.of("简体中文", "English", "日本語", "한국어"));
        m.put("langs", langs);
        return m;
    }
}
