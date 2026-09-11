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
    private static final Map<String, String> FIELD_MAPPING = Map.of(
            "model", SettingsService.KEY_MODEL,
            "baseUrl", SettingsService.KEY_BASE_URL,
            "apiKey", SettingsService.KEY_API_KEY,
            "maxTokens", SettingsService.KEY_MAX_TOKENS,
            "temperature", SettingsService.KEY_TEMPERATURE,
            "thinking", SettingsService.KEY_THINKING,
            "reasoningEffort", SettingsService.KEY_REASONING_EFFORT,
            "polishPrompt", SettingsService.KEY_POLISH_PROMPT,
            "formatPrompt", SettingsService.KEY_FORMAT_PROMPT);

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
