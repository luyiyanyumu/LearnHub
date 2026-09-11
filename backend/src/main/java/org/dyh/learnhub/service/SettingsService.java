package org.dyh.learnhub.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.dyh.learnhub.entity.AppSetting;
import org.dyh.learnhub.mapper.AppSettingMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 应用设置：键值对存储于 app_setting 表。
 * <p>
 * 读取规则：数据库有值 → 用数据库；空/缺行 → 用内置默认。
 * 这样设置面板里“清空保存”即可恢复默认，且修改无需重启后端。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SettingsService {

    public static final String KEY_MODEL = "ai.model";
    public static final String KEY_BASE_URL = "ai.base_url";
    public static final String KEY_API_KEY = "ai.api_key";
    public static final String KEY_MAX_TOKENS = "ai.max_tokens";
    public static final String KEY_TEMPERATURE = "ai.temperature";
    public static final String KEY_THINKING = "ai.thinking";
    public static final String KEY_REASONING_EFFORT = "ai.reasoning_effort";
    public static final String KEY_POLISH_PROMPT = "ai.polish_prompt";
    public static final String KEY_FORMAT_PROMPT = "ai.format_prompt";

    private static final List<String> KNOWN_KEYS = List.of(
            KEY_MODEL, KEY_BASE_URL, KEY_API_KEY, KEY_MAX_TOKENS, KEY_TEMPERATURE,
            KEY_THINKING, KEY_REASONING_EFFORT, KEY_POLISH_PROMPT, KEY_FORMAT_PROMPT);

    /** 默认模型：deepseek-flash（DeepSeek 官方当前主推的快速版，性价比高） */
    public static final String DEFAULT_MODEL = "deepseek-flash";

    /** 默认 API 地址（OpenAI 兼容，可换成任意中转/自建服务） */
    public static final String DEFAULT_BASE_URL = "https://api.deepseek.com";

    /** 默认最大输出 token：V4 系列单次输出上限 384K，这里留足长文润色空间 */
    public static final String DEFAULT_MAX_TOKENS = "16384";

    /** 默认采样温度（仅在「非思考模式」下生效） */
    public static final String DEFAULT_TEMPERATURE = "0.4";

    /** 默认思考模式：空 = 自动（由模型决定，DeepSeek V4 默认开启思考） */
    public static final String DEFAULT_THINKING = "";

    /** 默认思考强度：空 = 用服务端默认 */
    public static final String DEFAULT_REASONING_EFFORT = "";

    public static final String DEFAULT_POLISH_PROMPT = """
            你是资深中文技术文档编辑。请对用户提供的内容做一次真正有提升的语言润色，必须逐项检查：
            1) 修正错别字、语病、搭配不当，中文统一使用全角标点；
            2) 统一术语书写与大小写（如 maven→Maven、api→API、url→URL、javascript→JavaScript）；
            3) 把口语化、含糊、冗长的表述改写为专业、通顺的句子，可适度调整语序和句式；
            4) 保持原文的所有信息点、例子、注释与 Markdown 结构（标题层级、列表、表格、代码块、内联 HTML 标签一律保留）。
            严禁压缩与删减：不得删除任何段落、列表项、代码块、示例、链接或说明文字；
            润色后的字数不得少于原文（允许因表达更完整而略有增加）。
            只输出润色后的完整 Markdown 正文，不要任何解释或前后缀。
            仅当原文语言确实已无任何可改进之处时，才允许原样返回。
            """;

    public static final String DEFAULT_FORMAT_PROMPT = """
            你是 Markdown 排版专家。请把用户提供的内容整理成规范、清爽的 Markdown，必须逐条检查并执行：
            1) 标题层级规范递进（一级内容用 ##，子级用 ###，不要跳级）；
            2) 并列信息改为无序列表，操作步骤改为有序列表，对比信息改为表格；
            3) 所有命令、代码、文件路径必须放进代码块并标注语言（如 ```java、```bash）；
            4) 关键术语和重要结论用**粗体**标出；
            5) 段落之间留一个空行，清除多余空行、行尾空格和无效符号；
            6) 保留原文的全部内容与原有格式（含内联 HTML 标签、::: 提示块），不做删减。
            严禁压缩与删减：不得删除任何段落、列表项、代码块或示例，整理后的字数不得少于原文。
            只输出整理后的完整 Markdown 正文，不要任何解释或前后缀。
            仅当原文排版确实已完全符合上述每一条时，才允许原样返回。
            """;

    private final AppSettingMapper mapper;

    /** 读原始值（可能为 null） */
    public String raw(String key) {
        AppSetting s = mapper.selectById(key);
        return s == null ? null : s.getSettingValue();
    }

    /** 生效值：数据库覆盖优先，默认兜底 */
    public String effective(String key) {
        String v = raw(key);
        if (StringUtils.hasText(v)) {
            return v;
        }
        return switch (key) {
            case KEY_MODEL -> DEFAULT_MODEL;
            case KEY_BASE_URL -> DEFAULT_BASE_URL;
            case KEY_MAX_TOKENS -> DEFAULT_MAX_TOKENS;
            case KEY_TEMPERATURE -> DEFAULT_TEMPERATURE;
            case KEY_THINKING -> null;          // 空 = 自动
            case KEY_REASONING_EFFORT -> null;  // 空 = 服务端默认
            case KEY_POLISH_PROMPT -> DEFAULT_POLISH_PROMPT;
            case KEY_FORMAT_PROMPT -> DEFAULT_FORMAT_PROMPT;
            default -> null; // API Key 无内置默认，走 .env 兜底
        };
    }

    /** 给前端/调用方的完整快照：当前生效值 + 内置默认值 */
    public Map<String, Object> snapshot() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("model", effective(KEY_MODEL));
        m.put("baseUrl", effective(KEY_BASE_URL));
        // 安全：绝不把已保存的明文密钥回传给前端。
        // 前端约定——输入框留空 = 「不修改」（保存时不带这个字段）；
        // 想清除就点「清除」，前端才会显式发空串 → update() 删行 → 回落 .env。
        m.put("apiKey", "");
        m.put("hasApiKey", StringUtils.hasText(raw(KEY_API_KEY)));
        m.put("maxTokens", effective(KEY_MAX_TOKENS));
        m.put("temperature", effective(KEY_TEMPERATURE));
        m.put("thinking", effective(KEY_THINKING));
        m.put("reasoningEffort", effective(KEY_REASONING_EFFORT));
        m.put("polishPrompt", effective(KEY_POLISH_PROMPT));
        m.put("formatPrompt", effective(KEY_FORMAT_PROMPT));
        m.put("modelOverridden", StringUtils.hasText(raw(KEY_MODEL)));
        m.put("baseUrlOverridden", StringUtils.hasText(raw(KEY_BASE_URL)));
        m.put("apiKeyOverridden", StringUtils.hasText(raw(KEY_API_KEY)));
        m.put("maxTokensOverridden", StringUtils.hasText(raw(KEY_MAX_TOKENS)));
        m.put("temperatureOverridden", StringUtils.hasText(raw(KEY_TEMPERATURE)));
        m.put("thinkingOverridden", StringUtils.hasText(raw(KEY_THINKING)));
        m.put("reasoningEffortOverridden", StringUtils.hasText(raw(KEY_REASONING_EFFORT)));
        m.put("polishOverridden", StringUtils.hasText(raw(KEY_POLISH_PROMPT)));
        m.put("formatOverridden", StringUtils.hasText(raw(KEY_FORMAT_PROMPT)));
        m.put("defaults", Map.of(
                "model", DEFAULT_MODEL,
                "baseUrl", DEFAULT_BASE_URL,
                "maxTokens", DEFAULT_MAX_TOKENS,
                "temperature", DEFAULT_TEMPERATURE,
                "thinking", DEFAULT_THINKING,
                "reasoningEffort", DEFAULT_REASONING_EFFORT,
                "polishPrompt", DEFAULT_POLISH_PROMPT,
                "formatPrompt", DEFAULT_FORMAT_PROMPT));
        return m;
    }

    /**
     * 更新一个设置项；value 空白 = 清除覆盖（恢复默认）。
     */
    @Transactional
    public void update(String key, String value) {
        if (!KNOWN_KEYS.contains(key)) {
            throw new IllegalArgumentException("不支持的设置项: " + key);
        }
        if (!StringUtils.hasText(value)) {
            mapper.deleteById(key);
            log.info("设置 {} 已恢复默认", key);
            return;
        }
        AppSetting s = new AppSetting();
        s.setSettingKey(key);
        s.setSettingValue(value);
        if (mapper.selectById(key) == null) {
            mapper.insert(s);
        } else {
            mapper.updateById(s);
        }
        log.info("设置 {} 已更新（长度 {}）", key, value.length());
    }

    /** 批量更新（只处理请求里出现的键） */
    @Transactional
    public void updateAll(Map<String, String> kv) {
        if (kv == null) {
            return;
        }
        kv.forEach(this::update);
    }

    /** 供启动自检：列出已覆盖的键 */
    public List<String> overriddenKeys() {
        return mapper.selectList(Wrappers.<AppSetting>lambdaQuery())
                .stream().map(AppSetting::getSettingKey).toList();
    }
}
