package org.dyh.learnhub.service;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Model suggestions are UI data, never executable commands or tool arguments. */
public final class ReplyActions {
    private ReplyActions() {}
    private static final Set<String> KINDS = Set.of("follow_up", "save_note", "merge_note", "insert_at_cursor", "copy");
    private static final Set<String> WRITES = Set.of("save_note", "merge_note", "insert_at_cursor");

    public static List<Map<String, Object>> sanitize(JsonNode input, Long noteId, boolean truncated) {
        List<Map<String, Object>> out = new ArrayList<>();
        if (input == null || !input.isArray()) return out;
        Set<String> seen = new java.util.HashSet<>();
        for (JsonNode item : input) {
            if (out.size() >= 4) break;
            String kind = item.path("kind").asText("");
            String label = item.path("label").asText("").strip();
            String prompt = item.path("prompt").asText("").strip();
            if (!KINDS.contains(kind) || (truncated && WRITES.contains(kind))) continue;
            if (Set.of("merge_note", "insert_at_cursor").contains(kind) && (noteId == null || noteId <= 0)) continue;
            if (label.isBlank() || label.length() > 32 || prompt.length() > 600
                    || label.chars().anyMatch(Character::isISOControl)
                    || ("follow_up".equals(kind) && prompt.isBlank())) continue;
            if (!seen.add(kind + "\n" + label)) continue;
            Map<String, Object> action = new LinkedHashMap<>();
            action.put("id", "reply-action-" + (out.size() + 1));
            action.put("label", label);
            action.put("kind", kind);
            action.put("prompt", "follow_up".equals(kind) ? prompt : "");
            if (noteId != null && noteId > 0) action.put("noteId", noteId);
            out.add(action);
        }
        return out;
    }
}
