package org.dyh.learnhub.controller;

import org.dyh.learnhub.ai.ModelRouting;
import org.dyh.learnhub.common.Result;
import org.dyh.learnhub.service.SettingsService;
import org.dyh.learnhub.service.ModelProfileService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 应用设置接口（设置面板用）：
 * <ul>
 *   <li>GET /api/settings  当前生效值 + 内置默认值</li>
 *   <li>PUT /api/settings  批量更新；value 空白 = 恢复默认；修改即时生效，无需重启</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/settings")
public class SettingsController {

    private final SettingsService settingsService;
    private final ModelProfileService profiles;

    public SettingsController(SettingsService settingsService) {
        this(settingsService, null);
    }

    @Autowired
    public SettingsController(SettingsService settingsService, ModelProfileService profiles) {
        this.settingsService = settingsService;
        this.profiles = profiles;
    }

    @GetMapping
    public Result<Map<String, Object>> get() {
        return Result.ok(settingsService.snapshot());
    }

    /**
     * 生效值**从哪来**：数据库 / 外部配置（环境变量、命令行）/ 代码默认值。
     *
     * <p>为什么需要它：容器部署时"嵌入服务地址"只能靠启动参数给，而设置面板里没有这个入口 ——
     * 一旦没生效，从界面上完全看不出是"没传进来"还是"传了但被数据库盖住"。
     * 这个只读接口把判断依据一次说清：数据库里有没有该键、Spring 能不能解析到外部值、
     * 以及最终生效值。排错时先看它，不用再猜。
     */
    @GetMapping("/effective")
    public Result<List<Map<String, Object>>> effective(@RequestParam(required = false) String keys) {
        List<String> wanted = (keys == null || keys.isBlank())
                ? List.of("ai.embed_base_url", "ai.embed_model", "kb.vector_enabled",
                          "ai.model", "ai.base_url", "kb.vector_backend")
                : List.of(keys.split(","));
        List<Map<String, Object>> out = new ArrayList<>();
        for (String rawKey : wanted) {
            String key = rawKey.trim();
            if (key.isEmpty()) {
                continue;
            }
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("key", key);
            m.put("envName", key.replace('.', '_').replace('_', '_').toUpperCase(Locale.ROOT));
            m.put("canonical", key.replace('_', '-'));
            m.put("fromDb", settingsService.raw(key));
            m.put("fromExternal", settingsService.externalValue(key));
            m.put("effective", settingsService.effective(key));
            out.add(m);
        }
        return Result.ok(out);
    }

    /**
     * API 字段名 → 内部设置键。
     *
     * <h3>模型分工字段**一律从任务清单派生**，不要再手写</h3>
     * 这里原来是把 {@code modelForChat / modelForTriple / …} 一行行写死的，于是每加一个任务都要记得回来补一行，
     * 实测漏过三次：{@code triple / rerank / grounding}，以及 2026-10 的 {@code translate / formula}
     *（症状：任务分工表里「阅读器翻译」「公式原图识别」那两行选了档案 → 后端 400
     * 「不支持的设置项: modelForTranslate」→ 前端弹出「切换失败」，值其实没写进去）。
     * 任务清单的唯一源头是 {@link ModelRouting#allTasks()}（即 ModelRouting.META），
     * 字段名统一由 {@link SettingsService#modelForTaskField} 换算 —— 加任务只改 META 一处。
     */
    private static final Map<String, String> FIELD_MAPPING = buildFieldMapping();

    /** 包内可见：给「接口字段 ↔ 白名单」一致性测试用（见 SettingsControllerRoutingFieldTest） */
    static Map<String, String> fieldMapping() {
        return FIELD_MAPPING;
    }

    private static Map<String, String> buildFieldMapping() {
        Map<String, String> m = new LinkedHashMap<>();
        // ---- 非「模型分工」字段：逐个显式登记 ----
        m.put("model", SettingsService.KEY_MODEL);
        m.put("baseUrl", SettingsService.KEY_BASE_URL);
        m.put("apiKey", SettingsService.KEY_API_KEY);
        m.put("maxTokens", SettingsService.KEY_MAX_TOKENS);
        m.put("temperature", SettingsService.KEY_TEMPERATURE);
        m.put("thinking", SettingsService.KEY_THINKING);
        m.put("reasoningEffort", SettingsService.KEY_REASONING_EFFORT);
        // 注意：polishPrompt / formatPrompt 已移除 —— 润色与格式的提示词改为技能文件
        // （skills/<id>/SKILL.md），见 SkillService 与 skills/README.md。
        // 前端若还在送这两个字段会得到 400，这是故意的：避免"改了半天没生效"。
        m.put("chatPrompt", SettingsService.KEY_CHAT_PROMPT);
        // 联网总开关（搜索端点/模型是部署级设置，走 ai.search_base_url / ai.search_model，不进面板）
        m.put("webEnabled", SettingsService.KEY_WEB_ENABLED);
        // 长文生成（主题 wiki）用的模型：留空跟随主模型；配本机 Ollama 可让这块零成本
        m.put("wikiBaseUrl", SettingsService.KEY_WIKI_BASE_URL);
        m.put("wikiModel", SettingsService.KEY_WIKI_MODEL);
        m.put("wikiApiKey", SettingsService.KEY_WIKI_API_KEY);
        // 检索词自动扩展（仅第一遍无命中时启用）
        m.put("queryRewrite", SettingsService.KEY_QUERY_REWRITE);
        // 第三/四路证据源（主题 wiki / 概念图谱）的注入开关：跑"开 vs 关"对照用
        m.put("wikiInject", SettingsService.KEY_WIKI_INJECT);
        m.put("kgInject", SettingsService.KEY_KG_INJECT);
        // 向量后端：mysql（全扫，默认）/ milvus（ANN）+ 连接参数
        m.put("vectorBackend", "kb.vector_backend");
        m.put("milvusUri", "kb.milvus_uri");
        m.put("milvusToken", "kb.milvus_token");
        // 精排后端：llm（列表重排，默认）/ cross（Cross-Encoder sidecar）+ 服务地址
        m.put("rerankBackend", "kb.rerank_backend");
        m.put("rerankUrl", "kb.rerank_url");
        m.put("rerankSnippet", "kb.rerank_snippet");
        m.put("autoRecompile", SettingsService.KEY_AUTO_RECOMPILE);
        // 当前激活的模型档案（模型配置已改为"档案列表"，各任务指向档案 id）
        m.put("activeProfile", "ai.active_profile");

        // ---- 模型分工字段：从任务清单派生（见类注释）----
        for (String task : ModelRouting.allTasks()) {
            String field = SettingsService.modelForTaskField(task);
            String key = SettingsService.modelForTaskKey(task);
            String prev = m.put(field, key);
            if (prev != null && !prev.equals(key)) {
                // 任务名撞车会让两个任务共用一个字段：宁可启动即报，也不要静默串味
                throw new IllegalStateException("模型分工字段名冲突: " + field + " → " + prev + " / " + key);
            }
        }
        return Map.copyOf(m);
    }

    @PutMapping
    public Result<Map<String, Object>> update(@RequestBody Map<String, String> body) {
        Map<String, String> translated = new LinkedHashMap<>();
        for (Map.Entry<String, String> e : body.entrySet()) {
            String key = FIELD_MAPPING.get(e.getKey());
            if (key == null) {
                return Result.error(400, "不支持的设置项: " + e.getKey());
            }
            if (profiles != null && e.getValue() != null && !e.getValue().isBlank()) {
                String target = e.getValue().trim();
                try {
                    if (SettingsService.modelForTaskKey(ModelRouting.TASK_EMBED).equals(key)) {
                        if (!ModelRouting.EMBED_DISABLED.equals(target) && !ModelRouting.EMBED_LEGACY.equals(target)) {
                            profiles.embeddingProfile(target);
                        }
                    } else if ("ai.active_profile".equals(key) || (key.startsWith("ai.model_for_")
                            && !List.of("main", "local", "legacy").contains(target))) {
                        profiles.requireChatProfile(target);
                    }
                } catch (IllegalArgumentException ex) {
                    return Result.error(400, ex.getMessage());
                }
            }
            translated.put(key, e.getValue());
        }
        settingsService.updateAll(translated);
        return Result.ok(settingsService.snapshot());
    }
}
