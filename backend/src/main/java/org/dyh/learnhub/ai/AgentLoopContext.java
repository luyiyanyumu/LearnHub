package org.dyh.learnhub.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.List;

/** Compact completed old tool results without breaking the call/result protocol or fresh edit inputs. */
final class AgentLoopContext {
    static final int SOFT_CHARS = 100_000;
    static final int HARD_CHARS = 220_000;
    private static final List<String> KEYS = List.of("ok", "staged", "action_id", "code", "error", "id", "note_id",
            "file_id", "content_hash", "expected_hash", "adaptive_tool", "adaptive_version", "registered", "name", "version");
    private AgentLoopContext() {}

    static int compact(List<Object> messages, ObjectMapper json) {
        if (size(messages, json) <= SOFT_CHARS) return 0;
        int protectedAt = messages.size(), groups = 0;
        for (int i = messages.size() - 1; i >= 0; i--) {
            JsonNode node = json.valueToTree(messages.get(i));
            if (node.path("tool_calls").isArray()) {
                protectedAt = i;
                if (++groups >= 2) break;
            }
        }
        // Preserve the newest two complete exchanges, including exact source/hash needed by edits.
        if (groups < 2) return 0;
        int changed = 0;
        for (int i = 0; i < protectedAt; i++) {
            JsonNode node = json.valueToTree(messages.get(i));
            String content = node.path("content").asText("");
            if (!"tool".equals(node.path("role").asText()) || content.length() <= 4000) continue;
            ObjectNode digest = json.createObjectNode().put("context_compacted", true)
                    .put("notice", "较早工具结果已节选；完整结果曾返回但当前正文不完整，需要精确引用或编辑时重新读取原文。");
            try {
                JsonNode original = json.readTree(content);
                for (String key : KEYS) if (original != null && original.has(key) && original.get(key).toString().length() <= 600)
                    digest.set(key, original.get(key));
            } catch (Exception ignored) { /* Text results remain untrusted quoted excerpts. */ }
            digest.put("excerpt", content.substring(0, 1800));
            ObjectNode replacement = node.deepCopy();
            replacement.put("content", digest.toString());
            messages.set(i, replacement);
            changed++;
            if (size(messages, json) <= SOFT_CHARS) break;
        }
        return changed;
    }

    static int size(List<Object> messages, ObjectMapper json) {
        long count = 0;
        for (Object message : messages) count += json.valueToTree(message).toString().length();
        return (int) Math.min(count, Integer.MAX_VALUE);
    }
}
