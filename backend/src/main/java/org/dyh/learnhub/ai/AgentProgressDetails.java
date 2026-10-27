package org.dyh.learnhub.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.net.URI;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/** Bounded, user-visible execution facts. Never attach model prompts or private reasoning. */
public final class AgentProgressDetails {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Set<String> SOURCE_TYPES = Set.of("note", "file", "quick_ref", "wiki", "code");
    private static final Set<String> SOURCE_TEXT = Set.of("title", "snippet", "passageKey", "topicKey", "sectionKey", "heading", "category");
    private static final Set<String> SOURCE_NUMBERS = Set.of("id", "seq", "offset", "chars");
    private static final Pattern SECRET_FIELD = Pattern.compile("(?i).*(?:api[_-]?key|secret|password|passwd|authorization|cookie|credential|private[_-]?key|access[_-]?token|refresh[_-]?token|^token$).*");
    private static final Pattern INLINE_SECRET = Pattern.compile("(?i)((?:api[_-]?key|access[_-]?token|refresh[_-]?token|password|passwd|authorization|secret|token)\\s*[=:]\\s*[\\\"']?)[^\\s\\\"'&,;}]+|(?i)(Bearer\\s+)[A-Za-z0-9._~+/=-]+|\\bsk-[A-Za-z0-9_-]{12,}");
    private static final Pattern URL_IN_TEXT = Pattern.compile("(?i)https?://[^\\s<>\\\"']+");
    private static final String HIDDEN = "[已隐藏]";

    private AgentProgressDetails() { }

    /** Shared history/SSE contract: only these fields are accepted from stored execution rows. */
    public static Map<String, Object> displayDetails(JsonNode entry) {
        if (entry == null || !entry.isObject()) return Map.of();
        Budget budget = new Budget(12_000);
        Map<String, Object> out = new LinkedHashMap<>();
        String error = text(entry.path("error"), 1000, budget);
        if (!error.isBlank()) out.put("error", error);
        List<Map<String, Object>> tools = new ArrayList<>();
        for (JsonNode tool : entry.path("tools")) {
            if (tools.size() >= 12 || !tool.isObject()) continue;
            String name = text(tool.path("name"), 80, budget);
            if (name.isBlank()) continue;
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("name", name);
            String title = text(tool.path("title"), 100, budget);
            if (!title.isBlank()) item.put("title", title);
            if (tool.path("arguments").isObject()) item.put("arguments", value(tool.path("arguments"), 0, new Budget(Math.min(2400, budget.remaining))));
            // Account for nested parameters as part of the row's shared storage budget.
            if (item.containsKey("arguments")) budget.remaining = Math.max(0, budget.remaining - item.get("arguments").toString().length());
            tools.add(Map.copyOf(item));
        }
        if (!tools.isEmpty()) out.put("tools", List.copyOf(tools));
        List<Map<String, Object>> sources = sourceList(entry.path("sources"), budget);
        if (!sources.isEmpty()) out.put("sources", sources);
        List<Map<String, Object>> websites = new ArrayList<>();
        for (JsonNode site : entry.path("websites")) {
            if (websites.size() >= 24 || !site.isObject()) continue;
            String url = safeUrl(site.path("url").asText(""));
            if (url.isBlank() || budget.remaining < url.length()) continue;
            budget.remaining -= url.length();
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("url", url);
            String title = text(site.path("title"), 180, budget);
            item.put("title", title.isBlank() ? url : title);
            String snippet = text(site.path("snippet"), 400, budget);
            if (!snippet.isBlank()) item.put("snippet", snippet);
            websites.add(Map.copyOf(item));
        }
        if (!websites.isEmpty()) out.put("websites", List.copyOf(websites));
        String result = text(entry.path("result"), 1800, budget);
        if (!result.isBlank()) out.put("result", result);
        return Map.copyOf(out);
    }

    static Map<String, Object> tools(JsonNode calls) {
        List<Map<String, Object>> out = new ArrayList<>();
        if (calls != null) for (JsonNode call : calls) {
            JsonNode function = call.path("function");
            String name = function.path("name").asText("");
            if (name.isBlank()) continue;
            out.add(toolRecord(name, function.path("arguments").asText("{}")));
            if (out.size() == 12) break;
        }
        return displayDetails(JSON.valueToTree(Map.of("tools", out)));
    }

    static Map<String, Object> tool(String name, String argsRaw) {
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("tools", List.of(toolRecord(name, argsRaw)));
        JsonNode args = parse(argsRaw);
        String url = safeUrl(args.path("url").asText(""));
        if (!url.isBlank()) details.put("websites", List.of(Map.of("title", "请求读取的网址", "url", url)));
        return displayDetails(JSON.valueToTree(details));
    }

    static Map<String, Object> sources(List<Map<String, Object>> sources) {
        return displayDetails(JSON.valueToTree(Map.of("sources", sources)));
    }

    static Map<String, Object> result(String name, JsonNode result) {
        if (result == null || result.isNull()) return Map.of();
        Map<String, Object> details = new LinkedHashMap<>();
        if (result.isObject()) {
            if (!result.path("error").asText("").isBlank()) details.put("error", result.path("error").asText());
            List<JsonNode> websites = new ArrayList<>();
            if ("web_search".equals(name)) result.path("sources").forEach(websites::add);
            if (Set.of("web_fetch", "fetch_url").contains(name) && result.hasNonNull("url")) websites.add(result);
            if (!websites.isEmpty()) details.put("websites", websites);
            List<Map<String, Object>> sources = new ArrayList<>();
            switch (name) {
                case "search_knowledge" -> result.path("items").forEach(item -> sources.add(source(item, item.path("type").asText(), "id", "title", "snippet")));
                case "query_notes" -> result.path("notes").forEach(item -> sources.add(source(item, "note", "note_id", "title", "summary")));
                case "list_files" -> result.path("files").forEach(item -> sources.add(source(item, "file", "file_id", "name", "summary")));
                case "get_note" -> sources.add(source(result, "note", "note_id", "title", "content"));
                case "get_quick_ref" -> sources.add(source(result, "quick_ref", "quick_ref_id", "title", "content"));
                case "get_file" -> sources.add(source(result, "file", "file_id", "name", result.path("content").asText("").isBlank() ? "summary" : "content"));
                case "search_wiki", "read_wiki" -> {
                    for (JsonNode section : result.path("search_wiki".equals(name) ? "items" : "sections")) {
                        Map<String, Object> ref = source(section, "wiki", "pageId", "pageTitle", "text");
                        if (!ref.containsKey("id") && result.path("pageId").asLong() > 0) ref.put("id", result.path("pageId").asLong());
                        if (!ref.containsKey("title")) ref.put("title", result.path("pageTitle").asText(""));
                        if (!ref.containsKey("topicKey")) ref.put("topicKey", result.path("topicKey").asText(""));
                        ref.put("generatedGuide", true); ref.put("factVerified", false);
                        sources.add(ref);
                    }
                }
                default -> { }
            }
            if (!sources.isEmpty()) details.put("sources", sources);
            // A bounded preview preserves actual notices, partial errors and read ranges as well as content.
            details.put("result", JSON.valueToTree(value(result, 0, new Budget(3000))).toPrettyString());
        } else details.put("result", result.asText(result.toString()));
        return displayDetails(JSON.valueToTree(details));
    }

    private static Map<String, Object> source(JsonNode item, String type, String idField, String titleField, String snippetField) {
        Map<String, Object> ref = new LinkedHashMap<>();
        if (!SOURCE_TYPES.contains(type)) return ref;
        ref.put("type", type);
        if (item.path(idField).asLong() > 0) ref.put("id", item.path(idField).asLong());
        for (String field : SOURCE_TEXT) if (item.path(field).isTextual()) ref.put(field, item.path(field).asText());
        for (String field : SOURCE_NUMBERS) if (item.path(field).isIntegralNumber()) ref.put(field, item.path(field).longValue());
        for (String field : List.of("channels", "graphRelations", "sourceRefs", "links")) if (item.path(field).isArray()) ref.put(field, item.path(field));
        if (item.path(titleField).isTextual()) ref.put("title", item.path(titleField).asText());
        if (item.path(snippetField).isTextual()) ref.put("snippet", item.path(snippetField).asText());
        return ref;
    }

    private static List<Map<String, Object>> sourceList(JsonNode input, Budget budget) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (JsonNode source : input) {
            String type = source.path("type").asText("");
            if (out.size() >= 32 || !SOURCE_TYPES.contains(type) || source.path("id").asLong() <= 0) continue;
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("type", type);
            for (String key : SOURCE_NUMBERS) if (source.path(key).isIntegralNumber()) item.put(key, source.path(key).longValue());
            for (String key : SOURCE_TEXT) {
                String part = text(source.path(key), "snippet".equals(key) ? 500 : 220, budget);
                if (!part.isBlank()) item.put(key, part);
            }
            for (String key : List.of("generatedGuide", "factVerified", "textOmitted")) if (source.path(key).isBoolean()) item.put(key, source.path(key).asBoolean());
            for (String key : List.of("channels", "graphRelations", "sourceRefs", "links")) {
                List<String> values = new ArrayList<>();
                for (JsonNode part : source.path(key)) {
                    if (values.size() >= 16) break;
                    String text = text(part, 200, budget);
                    if (!text.isBlank()) values.add(text);
                }
                if (!values.isEmpty()) item.put(key, List.copyOf(values));
            }
            // Wiki links/citations remain untrusted navigation, never upgraded to verified evidence.
            if ("wiki".equals(type)) { item.put("generatedGuide", true); item.put("factVerified", false); }
            out.add(Map.copyOf(item));
        }
        return List.copyOf(out);
    }

    private static Map<String, Object> toolRecord(String name, String argsRaw) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("name", name); item.put("title", AgentChatProgress.toolTitle(name));
        JsonNode args = parse(argsRaw);
        if (args.isObject()) item.put("arguments", args);
        return item;
    }

    private static JsonNode parse(String raw) {
        if (raw == null || raw.length() > 1_000_000) return JSON.createObjectNode();
        try { JsonNode node = JSON.readTree(raw); return node == null ? JSON.createObjectNode() : node; }
        catch (Exception ignored) { return JSON.createObjectNode(); }
    }

    private static Object value(JsonNode node, int depth, Budget budget) {
        if (node == null || node.isNull()) return "null";
        if (depth > 4 || budget.remaining <= 0) return "…";
        if (node.isObject()) {
            Map<String, Object> out = new LinkedHashMap<>();
            var fields = node.fields();
            while (fields.hasNext() && out.size() < 24 && budget.remaining > 0) {
                var field = fields.next();
                String key = bounded(field.getKey(), 80, budget);
                out.put(key, SECRET_FIELD.matcher(field.getKey()).matches() ? HIDDEN : value(field.getValue(), depth + 1, budget));
            }
            return Map.copyOf(out);
        }
        if (node.isArray()) {
            List<Object> out = new ArrayList<>();
            for (JsonNode item : node) { if (out.size() >= 8 || budget.remaining <= 0) break; out.add(value(item, depth + 1, budget)); }
            return List.copyOf(out);
        }
        if (node.isBoolean()) return node.asBoolean();
        if (node.isIntegralNumber()) return node.longValue();
        if (node.isNumber()) return node.doubleValue();
        return text(node, 800, budget);
    }

    private static String text(JsonNode node, int max, Budget budget) {
        return node.isTextual() ? bounded(node.asText(), max, budget) : "";
    }

    private static String bounded(String raw, int max, Budget budget) {
        String withoutCredentials = URL_IN_TEXT.matcher(raw).replaceAll(match -> {
            String safe = safeUrl(match.group());
            return java.util.regex.Matcher.quoteReplacement(safe.isBlank() ? "[无效网址]" : safe);
        });
        String clean = INLINE_SECRET.matcher(withoutCredentials.replaceAll("[\\p{Cntrl}&&[^\\n\\t]]", " ")).replaceAll(match -> {
            String prefix = match.group(1) != null ? match.group(1) : match.group(2) != null ? match.group(2) : "";
            return prefix + HIDDEN;
        }).trim();
        int size = Math.max(0, Math.min(max, budget.remaining));
        if (clean.length() > size) clean = clean.substring(0, size) + (size > 0 ? "…" : "");
        budget.remaining = Math.max(0, budget.remaining - clean.length());
        return clean;
    }

    private static String safeUrl(String raw) {
        try {
            URI uri = URI.create(raw.trim());
            if (!Set.of("http", "https").contains(String.valueOf(uri.getScheme()).toLowerCase()) || uri.getHost() == null || raw.length() > 2048) return "";
            String url = new URI(uri.getScheme(), null, uri.getHost(), uri.getPort(), uri.getPath(), uri.getQuery(), uri.getFragment()).toASCIIString();
            return url.replaceAll("(?i)([?&](?:api[_-]?key|access[_-]?token|refresh[_-]?token|password|secret|token)=)[^&#]*", "$1REDACTED");
        } catch (Exception ignored) { return ""; }
    }

    private static final class Budget {
        int remaining;
        Budget(int remaining) { this.remaining = remaining; }
    }
}
