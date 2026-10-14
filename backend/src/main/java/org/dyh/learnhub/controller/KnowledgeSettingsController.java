package org.dyh.learnhub.controller;

import org.dyh.learnhub.common.Result;
import org.dyh.learnhub.service.KnowledgeBaseSettings;
import org.dyh.learnhub.service.VectorIndexService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.Map;

/** Server-driven knowledge settings; connection credentials remain in the existing settings store. */
@RestController
@RequestMapping("/api/kb/settings")
public class KnowledgeSettingsController {
    private final KnowledgeBaseSettings knowledge;
    private VectorIndexService index;
    public KnowledgeSettingsController(KnowledgeBaseSettings knowledge) { this.knowledge = knowledge; }
    @Autowired(required = false)
    public void setIndex(VectorIndexService index) { this.index = index; }

    @GetMapping
    public Result<Map<String, Object>> get() { return Result.ok(withIndexState(knowledge.describe())); }

    @PutMapping
    public Result<Map<String, Object>> update(@RequestBody Map<String, Object> body) {
        try {
            Map<String, ?> values;
            if (body.containsKey("values")) {
                if (body.size() != 1 || !(body.get("values") instanceof Map<?, ?> raw))
                    throw new IllegalArgumentException("请提供 values 参数对象");
                Map<String, Object> typed = new LinkedHashMap<>();
                raw.forEach((key, value) -> typed.put(String.valueOf(key), value));
                values = typed;
            } else values = body;
            return Result.ok(withIndexState(knowledge.update(values)));
        } catch (IllegalArgumentException invalid) { return Result.error(400, invalid.getMessage()); }
    }

    private Map<String, Object> withIndexState(Map<String, Object> result) {
        if (index == null) return result;
        try {
            Map<String, Object> status = index.status();
            result.put("needsRebuild", Boolean.TRUE.equals(result.get("needsRebuild")) || Boolean.TRUE.equals(status.get("stale")));
            result.put("indexState", Map.of("stale", Boolean.TRUE.equals(status.get("stale")),
                    "configured", Boolean.TRUE.equals(status.get("configured")), "chunks", status.getOrDefault("chunks", 0),
                    "embeddingChanged", Boolean.TRUE.equals(status.get("embeddingChanged"))));
        } catch (Exception unavailable) { result.put("indexState", Map.of("available", false)); }
        return result;
    }
}
