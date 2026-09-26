package org.dyh.learnhub.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.dyh.learnhub.entity.ModelProfile;
import org.dyh.learnhub.mapper.ModelProfileMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 模型配置档案的读写与解析。
 *
 * <h3>它解决什么问题</h3>
 * 原来模型配置是两组写死的键（{@code ai.model/base_url/api_key} 与 {@code ai.wiki_*}），
 * 于是"用哪个模型"只有**两个位置**：主模型、本地/自建。想再接一个 Kimi 或 LM Studio 就没地方放，
 * 想让"语义自检"用 A、"主题 wiki"用 B 也只能在代码里写死。
 * 现在档案是列表，任务通过 {@link ModelRouting} 各指一个。
 *
 * <h3>三个必须守住的约定</h3>
 * <ol>
 *   <li><b>密钥只进不出</b>：{@link #list()} / {@link #detail} 只返回是否已配置与尾号，
 *       明文只在 {@link #resolve} 内部使用。界面上永远看不到完整 key。</li>
 *   <li><b>老配置不丢</b>：{@link #migrateLegacyIfEmpty()} 在首次启动时把原有的
 *       {@code ai.*} 配置转成一个「默认档案」，并给本地目标也建一个档案 ——
 *       否则升级之后用户的密钥"消失"了，那是最糟的体验。</li>
 *   <li><b>总有一个能用的档案</b>：{@link #resolve} 在 id 为空/失效时回退到当前激活档案，
 *       再回退到第一个档案；一个都没有时回退到老设置。任何任务都不该因为"档案被删了"而无法调用。</li>
 * </ol>
 */
@Service
public class ModelProfileService {

    private static final Logger log = LoggerFactory.getLogger(ModelProfileService.class);

    /** 当前激活档案（对话默认用它）写在这个设置键里 */
    public static final String SETTING_ACTIVE = "ai.active_profile";

    private final ModelProfileMapper mapper;
    private final SettingsService settings;
    /** 探测用（只在这里发一次最小请求）；用 ObjectProvider 延迟取，避免与调用方成环 */
    private final org.springframework.beans.factory.ObjectProvider<org.dyh.learnhub.ai.DeepSeekClient> callerProvider;
    /** 老配置里可能没有 key（key 写在 application.yml / .env）——迁移时要带上，否则界面显示"未配置" */
    private final org.dyh.learnhub.config.AiProperties aiProps;

    public ModelProfileService(ModelProfileMapper mapper, SettingsService settings,
                               org.dyh.learnhub.config.AiProperties aiProps,
                               org.springframework.beans.factory.ObjectProvider<org.dyh.learnhub.ai.DeepSeekClient> callerProvider) {
        this.mapper = mapper;
        this.settings = settings;
        this.aiProps = aiProps;
        this.callerProvider = callerProvider;
    }

    /** 设置表里的 key，没有就退回 application.yml / .env 里那份（与 DeepSeekClient.apiKey 同一条回退链） */
    private String effectiveApiKey() {
        String v = settings.effective(SettingsService.KEY_API_KEY);
        if (StringUtils.hasText(v)) {
            return v.trim();
        }
        return aiProps == null ? null : aiProps.getApiKey();
    }

    private org.dyh.learnhub.ai.DeepSeekClient caller() {
        org.dyh.learnhub.ai.DeepSeekClient c = callerProvider.getIfAvailable();
        if (c == null) {
            throw new IllegalStateException("模型客户端尚未就绪");
        }
        return c;
    }

    /**
     * 一个可用的模型目标（已解析出明文密钥，仅供内部调用外部 API 用）。
     * <p>生成参数一并带出：参数的归属是**档案**，调用方拿到 target 后应直接用它的参数，
     * 而不是再去查全局设置 —— 否则"参数跟着档案走"就只是界面上的假象。
     */
    public record Target(String id, String label, String baseUrl, String apiKey, String model,
                         Integer maxTokens, java.math.BigDecimal temperature,
                         String thinking, String reasoningEffort) {
    }

    /** 内置的提供方预设：基址与常用模型名。前端"＋DeepSeek"这类按钮直接用 */
    public static List<Map<String, Object>> presets() {
        List<Map<String, Object>> out = new ArrayList<>();
        out.add(preset("deepseek", "DeepSeek", "https://api.deepseek.com", "deepseek-flash",
                "官方 API。便宜、快，适合大批量摘要"));
        out.add(preset("kimi", "Kimi（月之暗面）", "https://api.moonshot.cn/v1", "moonshot-v1-8k",
                "长上下文见长"));
        out.add(preset("ark", "火山方舟", "https://ark.cn-beijing.volces.com/api/v3", "",
                "字节的模型服务，模型名填接入点 id"));
        out.add(preset("openai", "OpenAI", "https://api.openai.com/v1", "gpt-4o-mini",
                "官方或中转都填这里"));
        out.add(preset("ollama", "本地 Ollama", "http://localhost:11434/v1", "qwen3:8b",
                "本地跑，0 成本；密钥随便填一个非空值即可"));
        out.add(preset("lmstudio", "本地 LM Studio", "http://localhost:1234/v1", "",
                "LM Studio 的 OpenAI 兼容端口"));
        out.add(preset("vllm", "本地 vLLM", "http://localhost:8000/v1", "",
                "vLLM 起服务后的 OpenAI 兼容端口"));
        out.add(preset("custom", "自定义提供方", "", "", "任何 OpenAI 兼容的服务都行"));
        return out;
    }

    private static Map<String, Object> preset(String provider, String name, String baseUrl,
                                              String model, String note) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("provider", provider);
        m.put("name", name);
        m.put("baseUrl", baseUrl);
        m.put("model", model);
        m.put("note", note);
        return m;
    }

    /** 档案清单（**脱敏**）：按 sort_order 排 */
    public List<ModelProfile> all() {
        return mapper.selectList(Wrappers.<ModelProfile>lambdaQuery()
                .orderByAsc(ModelProfile::getSortOrder)
                .orderByAsc(ModelProfile::getCreatedAt));
    }

    /** 给界面用的清单：脱敏 + 标注哪个是当前激活 */
    public List<Map<String, Object>> list() {
        String active = activeId();
        List<Map<String, Object>> out = new ArrayList<>();
        for (ModelProfile p : all()) {
            out.add(view(p, active));
        }
        return out;
    }

    /** 单条视图（脱敏）。密钥只回传"是否已配置 + 尾号" */
    public Map<String, Object> view(ModelProfile p, String activeId) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", p.getId());
        m.put("name", p.getName());
        m.put("provider", p.getProvider());
        m.put("baseUrl", p.getBaseUrl());
        m.put("model", p.getModel());
        m.put("note", p.getNote());
        m.put("sortOrder", p.getSortOrder());
        m.put("active", p.getId().equals(activeId));
        m.put("hasKey", StringUtils.hasText(p.getApiKey()));
        m.put("keyHint", mask(p.getApiKey()));
        // 生成参数：null = 跟随全局默认（界面显示为"未设置/跟随默认"）
        m.put("maxTokens", p.getMaxTokens());
        m.put("temperature", p.getTemperature());
        m.put("thinking", p.getThinking());
        m.put("reasoningEffort", p.getReasoningEffort());
        return m;
    }

    /** 掩码：{@code sk-xxxxxxxx4810} → {@code sk-****4810}；太短就整体打码 */
    public static String mask(String key) {
        if (!StringUtils.hasText(key)) {
            return "";
        }
        String k = key.trim();
        if (k.length() <= 6) {
            return "****";
        }
        String head = k.length() > 8 ? k.substring(0, 3) : "";
        String tail = k.substring(k.length() - 4);
        return head + "****" + tail;
    }

    public String activeId() {
        String v = settings.effective(SETTING_ACTIVE);
        return StringUtils.hasText(v) ? v.trim() : null;
    }

    /** 激活一个档案（对话默认用它） */
    public Map<String, Object> activate(String id) {
        ModelProfile p = mapper.selectById(id);
        if (p == null) {
            throw new IllegalArgumentException("档案不存在：" + id);
        }
        settings.update(SETTING_ACTIVE, id);
        return view(p, id);
    }

    /**
     * 新建档案。
     *
     * @param apiKey 传空 = 不设置；传 {@code "__KEEP__"} = 保留原值（改别的字段时用）
     */
    public Map<String, Object> create(Map<String, Object> body) {
        ModelProfile p = new ModelProfile();
        p.setId(UUID.randomUUID().toString().substring(0, 8));
        p.setName(str(body.get("name"), "未命名档案"));
        p.setProvider(str(body.get("provider"), "custom"));
        p.setBaseUrl(str(body.get("baseUrl"), ""));
        p.setModel(str(body.get("model"), ""));
        p.setNote(str(body.get("note"), null));
        p.setApiKey(str(body.get("apiKey"), null));
        // 生成参数（可省略；省略 = 跟随全局默认）
        p.setMaxTokens(parseIntOrNull(body.get("maxTokens")));
        p.setTemperature(parseDecimalOrNull(body.get("temperature")));
        p.setThinking(str(body.get("thinking"), null));
        p.setReasoningEffort(str(body.get("reasoningEffort"), null));
        p.setSortOrder(all().size());
        validate(p);
        mapper.insert(p);
        // 第一个档案自动激活：否则新装完没有任何档案是"当前"的
        if (activeId() == null || mapper.selectById(activeId()) == null) {
            activate(p.getId());
        }
        log.info("新增模型档案：{}（{} / {}）", p.getName(), p.getBaseUrl(), p.getModel());
        return view(p, activeId());
    }

    /** 更新档案；{@code apiKey} 传 {@code __KEEP__} 表示不改密钥 */
    public Map<String, Object> update(String id, Map<String, Object> body) {
        ModelProfile p = mapper.selectById(id);
        if (p == null) {
            throw new IllegalArgumentException("档案不存在：" + id);
        }
        if (body.containsKey("name")) {
            p.setName(str(body.get("name"), p.getName()));
        }
        if (body.containsKey("provider")) {
            p.setProvider(str(body.get("provider"), p.getProvider()));
        }
        if (body.containsKey("baseUrl")) {
            p.setBaseUrl(str(body.get("baseUrl"), p.getBaseUrl()));
        }
        if (body.containsKey("model")) {
            p.setModel(str(body.get("model"), p.getModel()));
        }
        if (body.containsKey("note")) {
            p.setNote(str(body.get("note"), null));
        }
        // 生成参数：传了就写；传空串/null 视为**清除**（回到跟随全局默认）
        if (body.containsKey("maxTokens")) {
            p.setMaxTokens(parseIntOrNull(body.get("maxTokens")));
        }
        if (body.containsKey("temperature")) {
            p.setTemperature(parseDecimalOrNull(body.get("temperature")));
        }
        if (body.containsKey("thinking")) {
            p.setThinking(str(body.get("thinking"), null));
        }
        if (body.containsKey("reasoningEffort")) {
            p.setReasoningEffort(str(body.get("reasoningEffort"), null));
        }
        Object key = body.get("apiKey");
        if (key != null) {
            String s = String.valueOf(key);
            if ("__KEEP__".equals(s)) {
                // 界面回传的是掩码，不能拿它覆盖真 key；显式保留
            } else if (s.isBlank()) {
                p.setApiKey(null);   // 显式清空
            } else {
                p.setApiKey(s.trim());
            }
        }
        p.setUpdatedAt(LocalDateTime.now());
        validate(p);
        // **必须显式 set 每一列，不能用 updateById**：
        // MyBatis-Plus 的 updateById 默认跳过 null 字段（FieldStrategy.NOT_NULL），
        // 于是"清空密钥"和"清空生成参数（= 回到跟随全局默认）"这两个操作
        // 在 SQL 里根本不会带上那几列 —— 接口响应看起来改好了，库里还是旧值，
        // 刷新界面又变回去（实测踩到：清空 thinking 后 DB 仍是 disabled）。
        mapper.update(null, Wrappers.<ModelProfile>lambdaUpdate()
                .eq(ModelProfile::getId, p.getId())
                .set(ModelProfile::getName, p.getName())
                .set(ModelProfile::getProvider, p.getProvider())
                .set(ModelProfile::getBaseUrl, p.getBaseUrl())
                .set(ModelProfile::getModel, p.getModel())
                .set(ModelProfile::getNote, p.getNote())
                .set(ModelProfile::getApiKey, p.getApiKey())
                .set(ModelProfile::getMaxTokens, p.getMaxTokens())
                .set(ModelProfile::getTemperature, p.getTemperature())
                .set(ModelProfile::getThinking, p.getThinking())
                .set(ModelProfile::getReasoningEffort, p.getReasoningEffort())
                .set(ModelProfile::getUpdatedAt, p.getUpdatedAt()));
        return view(p, activeId());
    }

    /**
     * 删除档案。删掉的若是当前激活档案，自动把激活位置让给剩下的第一个。
     * <p>故意**不**清理各任务上指向它的设置：任务解析时会发现 id 失效并回退，
     * 这样"误删了再建一个同 id"是不存在的，但"删了之后任务还能跑"是成立的。
     */
    public Map<String, Object> delete(String id) {
        ModelProfile p = mapper.selectById(id);
        if (p == null) {
            throw new IllegalArgumentException("档案不存在：" + id);
        }
        mapper.deleteById(id);
        boolean wasActive = id.equals(activeId());
        if (wasActive) {
            List<ModelProfile> rest = all();
            if (rest.isEmpty()) {
                settings.update(SETTING_ACTIVE, "");
            } else {
                activate(rest.get(0).getId());
            }
        }
        Map<String, Object> o = new LinkedHashMap<>();
        o.put("deleted", p.getName());
        o.put("active", activeId());
        return o;
    }

    /**
     * 解析出一个可用的目标（含明文密钥）。
     *
     * @param profileId 想要的档案 id；为空或已失效时回退
     */
    public Target resolve(String profileId) {
        ModelProfile p = null;
        if (StringUtils.hasText(profileId)) {
            p = mapper.selectById(profileId.trim());
        }
        if (p == null) {
            String active = activeId();
            if (StringUtils.hasText(active)) {
                p = mapper.selectById(active);
            }
        }
        if (p == null) {
            List<ModelProfile> all = all();
            p = all.isEmpty() ? null : all.get(0);
        }
        if (p == null) {
            // 一个档案都没有（升级途中/被全删了）：回退老设置，保证任何任务都还能调。
            // 生成参数传 null = 用全局默认（与升级前行为一致）
            return new Target(null, "旧配置回退", settings.effective(SettingsService.KEY_BASE_URL),
                    settings.effective(SettingsService.KEY_API_KEY),
                    settings.effective(SettingsService.KEY_MODEL), null, null, null, null);
        }
        return new Target(p.getId(), p.getName(), p.getBaseUrl(), p.getApiKey(), p.getModel(),
                p.getMaxTokens(), p.getTemperature(), p.getThinking(), p.getReasoningEffort());
    }

    /** 本地档案（基址指向本机）—— 给"便宜/免费"类任务选默认值时用 */
    public Target firstLocal() {
        for (ModelProfile p : all()) {
            String u = p.getBaseUrl() == null ? "" : p.getBaseUrl().toLowerCase();
            if (u.contains("localhost") || u.contains("127.0.0.1") || u.contains("host.docker.internal")
                    || "ollama".equals(p.getProvider()) || "lmstudio".equals(p.getProvider())
                    || "vllm".equals(p.getProvider())) {
                return new Target(p.getId(), p.getName(), p.getBaseUrl(), p.getApiKey(), p.getModel(),
                        p.getMaxTokens(), p.getTemperature(), p.getThinking(), p.getReasoningEffort());
            }
        }
        return null;
    }

    /**
     * 连通性探测：拿这个档案实际发一次最小请求。
     *
     * <p>为什么必须有这一步：配置类功能最烦的就是"填完了不知道对不对" ——
     * 密钥错、基址少一段 {@code /v1}、模型名写错，都要等到真正用的时候才炸。
     * 这里主动发一次，把成功/失败与原因当场告诉界面。
     */
    public Map<String, Object> probe(String id) {
        ModelProfile p = mapper.selectById(id);
        if (p == null) {
            throw new IllegalArgumentException("档案不存在：" + id);
        }
        Map<String, Object> o = new LinkedHashMap<>();
        o.put("name", p.getName());
        o.put("baseUrl", p.getBaseUrl());
        o.put("model", p.getModel());
        long t0 = System.currentTimeMillis();
        try {
            String reply = caller().chat(List.of(
                            Map.of("role", "user", "content", "只回复两个字：可用")),
                    null, p.getBaseUrl(), p.getApiKey(), p.getModel(),
                    16, 0, "disabled", null, Duration.ofSeconds(20))
                    .path("content").asText("");
            o.put("ok", true);
            o.put("ms", System.currentTimeMillis() - t0);
            o.put("reply", reply.length() > 40 ? reply.substring(0, 40) : reply);
            o.put("message", StringUtils.hasText(reply) ? "连接正常" : "连上了，但模型返回空内容（可能模型名不对）");
        } catch (Exception e) {
            o.put("ok", false);
            o.put("ms", System.currentTimeMillis() - t0);
            o.put("message", e.getMessage() == null ? e.toString() : e.getMessage());
            // 常见毛病的提示：少 /v1、模型名不对、密钥错
            String msg = String.valueOf(e.getMessage());
            if (msg.contains("404")) {
                o.put("hint", "404 多为基址缺 /v1 或模型名不存在（本地 Ollama 的 OpenAI 兼容端口是 /v1）");
            } else if (msg.contains("401") || msg.contains("403")) {
                o.put("hint", "鉴权失败：检查密钥；本地服务通常随便填一个非空值即可");
            } else if (msg.contains("Connection") || msg.contains("timed out") || msg.contains("refused")) {
                o.put("hint", "连不上：确认服务在跑、端口对；容器里访问本机要用 host.docker.internal");
            }
        }
        return o;
    }

    /**
     * 首次启动迁移：一个档案都没有时，把老设置（{@code ai.*} + {@code ai.wiki_*}）
     * 转成档案。**必须在任何任务被调用之前跑**（见 {@code ApplicationReadyEvent} 的调用点）。
     *
     * @return 迁移出来的档案数
     */
    public synchronized int migrateLegacyIfEmpty() {
        if (!all().isEmpty()) {
            return 0;
        }
        int created = 0;
        String baseUrl = settings.effective(SettingsService.KEY_BASE_URL);
        String model = settings.effective(SettingsService.KEY_MODEL);
        // key 走与运行时同一条回退链：设置表 → application.yml / .env。
        // 少了这一步，迁移出来的档案会显示"未配置密钥"，而对话其实是能用的 —— 那种自相矛盾最让人困惑。
        String key = effectiveApiKey();
        if (StringUtils.hasText(baseUrl) && StringUtils.hasText(model)) {
            Map<String, Object> b = new LinkedHashMap<>();
            b.put("name", guessName(baseUrl));
            b.put("provider", guessProvider(baseUrl));
            b.put("baseUrl", baseUrl);
            b.put("model", model);
            b.put("apiKey", key);
            b.put("note", "由原有配置自动迁移");
            create(b);
            created++;
        }
        // 老的"本地/自建目标"（wiki 那套）也迁一个档案
        String wUrl = settings.effective(SettingsService.KEY_WIKI_BASE_URL);
        String wModel = settings.effective(SettingsService.KEY_WIKI_MODEL);
        if (StringUtils.hasText(wUrl) && StringUtils.hasText(wModel)) {
            Map<String, Object> b = new LinkedHashMap<>();
            b.put("name", "本地（原 wiki 目标）");
            b.put("provider", guessProvider(wUrl));
            b.put("baseUrl", wUrl);
            b.put("model", wModel);
            b.put("apiKey", settings.effective(SettingsService.KEY_WIKI_API_KEY));
            b.put("note", "由原有本地/自建目标迁移");
            create(b);
            created++;
        }
        if (created > 0) {
            log.info("模型档案首次迁移完成：{} 个", created);
        }
        return created;
    }

    private static String guessProvider(String baseUrl) {
        String u = baseUrl == null ? "" : baseUrl.toLowerCase();
        if (u.contains("deepseek")) {
            return "deepseek";
        }
        if (u.contains("moonshot")) {
            return "kimi";
        }
        if (u.contains("volces")) {
            return "ark";
        }
        if (u.contains("openai")) {
            return "openai";
        }
        if (u.contains("11434")) {
            return "ollama";
        }
        if (u.contains("1234")) {
            return "lmstudio";
        }
        if (u.contains("8000")) {
            return "vllm";
        }
        return "custom";
    }

    private static String guessName(String baseUrl) {
        String p = guessProvider(baseUrl);
        return switch (p) {
            case "deepseek" -> "DeepSeek 云端";
            case "kimi" -> "Kimi";
            case "ark" -> "火山方舟";
            case "openai" -> "OpenAI";
            case "ollama" -> "本地 Ollama";
            case "lmstudio" -> "本地 LM Studio";
            case "vllm" -> "本地 vLLM";
            default -> "自定义档案";
        };
    }

    private static void validate(ModelProfile p) {
        if (!StringUtils.hasText(p.getName())) {
            throw new IllegalArgumentException("档案名不能为空");
        }
        if (!StringUtils.hasText(p.getBaseUrl())) {
            throw new IllegalArgumentException("Base URL 不能为空");
        }
        if (!StringUtils.hasText(p.getModel())) {
            throw new IllegalArgumentException("模型名不能为空");
        }
    }

    private static String str(Object o, String def) {
        if (o == null) {
            return def;
        }
        String s = String.valueOf(o).trim();
        return s.isEmpty() ? def : s;
    }

    /**
     * 解析输出上限。**空字符串/非法值一律当"未设置"（null）** ——
     * 界面上清空输入框的语义就是"回到跟随全局默认"，而不是"设成 0"。
     */
    private static Integer parseIntOrNull(Object o) {
        if (o == null) {
            return null;
        }
        String s = String.valueOf(o).trim();
        if (s.isEmpty()) {
            return null;
        }
        try {
            int n = Integer.parseInt(s);
            return n > 0 ? n : null;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** 解析温度；空/非法/越界当"未设置"（null），合法范围 0~2 */
    private static java.math.BigDecimal parseDecimalOrNull(Object o) {
        if (o == null) {
            return null;
        }
        String s = String.valueOf(o).trim();
        if (s.isEmpty()) {
            return null;
        }
        try {
            java.math.BigDecimal d = new java.math.BigDecimal(s);
            return d.compareTo(java.math.BigDecimal.ZERO) >= 0
                    && d.compareTo(java.math.BigDecimal.valueOf(2)) <= 0 ? d : null;
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
