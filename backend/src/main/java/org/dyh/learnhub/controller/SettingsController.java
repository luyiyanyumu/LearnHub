package org.dyh.learnhub.controller;

import lombok.RequiredArgsConstructor;
import org.dyh.learnhub.common.Result;
import org.dyh.learnhub.service.SettingsService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
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
@RequiredArgsConstructor
public class SettingsController {

    private final SettingsService settingsService;

    @GetMapping
    public Result<Map<String, Object>> get() {
        return Result.ok(settingsService.snapshot());
    }

    /** API 字段名 → 内部设置键 */
    private static final Map<String, String> FIELD_MAPPING = Map.ofEntries(
            Map.entry("model", SettingsService.KEY_MODEL),
            Map.entry("baseUrl", SettingsService.KEY_BASE_URL),
            Map.entry("apiKey", SettingsService.KEY_API_KEY),
            Map.entry("maxTokens", SettingsService.KEY_MAX_TOKENS),
            Map.entry("temperature", SettingsService.KEY_TEMPERATURE),
            Map.entry("thinking", SettingsService.KEY_THINKING),
            Map.entry("reasoningEffort", SettingsService.KEY_REASONING_EFFORT),
            // 注意：polishPrompt / formatPrompt 已移除 —— 润色与格式的提示词改为技能文件
            // （skills/<id>/SKILL.md），见 SkillService 与 skills/README.md。
            // 前端若还在送这两个字段会得到 400，这是故意的：避免"改了半天没生效"。
            Map.entry("chatPrompt", SettingsService.KEY_CHAT_PROMPT),
            // 联网总开关（搜索端点/模型是部署级设置，走 ai.search_base_url / ai.search_model，不进面板）
            Map.entry("webEnabled", SettingsService.KEY_WEB_ENABLED),
            // 长文生成（主题 wiki）用的模型：留空跟随主模型；配本机 Ollama 可让这块零成本
            Map.entry("wikiBaseUrl", SettingsService.KEY_WIKI_BASE_URL),
            Map.entry("wikiModel", SettingsService.KEY_WIKI_MODEL),
            Map.entry("wikiApiKey", SettingsService.KEY_WIKI_API_KEY),
            // 检索词自动扩展（仅第一遍无命中时启用）
            Map.entry("queryRewrite", SettingsService.KEY_QUERY_REWRITE),
            // 第三/四路证据源（主题 wiki / 概念图谱）的注入开关：跑"开 vs 关"对照用
            Map.entry("wikiInject", SettingsService.KEY_WIKI_INJECT),
            Map.entry("kgInject", SettingsService.KEY_KG_INJECT),
            // 向量后端：mysql（全扫，默认）/ milvus（ANN）+ 连接参数
            Map.entry("vectorBackend", "kb.vector_backend"),
            Map.entry("milvusUri", "kb.milvus_uri"),
            Map.entry("milvusToken", "kb.milvus_token"),
            // 精排后端：llm（列表重排，默认）/ cross（Cross-Encoder sidecar）+ 服务地址
            Map.entry("rerankBackend", "kb.rerank_backend"),
            Map.entry("rerankUrl", "kb.rerank_url"),
            Map.entry("rerankSnippet", "kb.rerank_snippet"),
            Map.entry("autoRecompile", SettingsService.KEY_AUTO_RECOMPILE),
            Map.entry("modelForChat", SettingsService.modelForTaskKey("chat")),
            Map.entry("modelForWiki", SettingsService.modelForTaskKey("wiki")),
            Map.entry("modelForEntity", SettingsService.modelForTaskKey("entity")),
            Map.entry("modelForImpact", SettingsService.modelForTaskKey("impact")),
            Map.entry("modelForLint", SettingsService.modelForTaskKey("lint")),
            Map.entry("modelForGraph", SettingsService.modelForTaskKey("graph")),
            // 这三个是后加的任务。漏登记的表现是"改这一行没反应"：
            // 后端返回 400「不支持的设置项」，而前端当时把异常吞掉了，界面上什么都不显示。
            // 约定：凡 ModelRouting 里登记过的任务，这里必须有对应字段名。
            Map.entry("modelForTriple", SettingsService.modelForTaskKey("triple")),
            Map.entry("modelForRerank", SettingsService.modelForTaskKey("rerank")),
            Map.entry("modelForGrounding", SettingsService.modelForTaskKey("grounding")),
            Map.entry("modelForRewrite", SettingsService.modelForTaskKey("rewrite")));

    @PutMapping
    public Result<Map<String, Object>> update(@RequestBody Map<String, String> body) {
        Map<String, String> translated = new LinkedHashMap<>();
        for (Map.Entry<String, String> e : body.entrySet()) {
            String key = FIELD_MAPPING.get(e.getKey());
            if (key == null) {
                return Result.error(400, "不支持的设置项: " + e.getKey());
            }
            translated.put(key, e.getValue());
        }
        settingsService.updateAll(translated);
        return Result.ok(settingsService.snapshot());
    }
}
