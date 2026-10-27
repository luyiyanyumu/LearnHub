package org.dyh.learnhub.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.commonmark.node.AbstractVisitor;
import org.commonmark.node.Heading;
import org.commonmark.parser.Parser;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Pure validation for a complete, frozen section plan; it never generates or writes note content. */
final class NoteSectionScope {
    static final int MAX_UNITS = 50;
    private static final int MAX_INSTRUCTION_CHARS = 12_000;
    private static final Set<String> SECTION_ACTIONS = Set.of("insert_before", "insert_after", "replace");
    private static final List<String> FROZEN_FIELDS = List.of(
            "unit_id", "heading", "instructions", "action", "text", "occurrence", "prefix", "suffix");

    private NoteSectionScope() {}

    /** The model cannot turn a section-sized write into several independent approval requests. */
    static boolean mayNeedPlanning(String function, JsonNode args) {
        if ("write_note_sections".equals(function)) return true;
        if (args == null || !args.isObject()) return false;
        if ("append_to_note".equals(function)) {
            String body = text(args.path("markdown"));
            if (body.isEmpty()) body = text(args.path("content"));
            return body.length() >= 1_200 || headings(body) >= 2;
        }
        if (!"edit_note".equals(function) || !args.path("operations").isArray()) return false;
        long chars = 0;
        int insertions = 0;
        int headings = 0;
        for (JsonNode operation : args.path("operations")) {
            String action = text(operation.path("action"));
            if (!SECTION_ACTIONS.contains(action)) continue;
            if (action.startsWith("insert_")) insertions++;
            String value = text(operation.path("value"));
            chars += value.length();
            headings += headings(value);
            if (chars >= 1_200 || headings >= 2 || insertions >= 2 && chars >= 400) return true;
        }
        return false;
    }

    /** Exact mode only preserves the caller's original operation; model output cannot replace it. */
    record Plan(String mode, String goal, ObjectNode arguments) {
        Plan { arguments = arguments.deepCopy(); }
        @Override public ObjectNode arguments() { return arguments.deepCopy(); }
    }

    static Plan parsePlan(JsonNode modelOutput, long noteId, String hash) {
        if (modelOutput == null || !modelOutput.isObject()) throw invalid("范围计划必须是JSON对象");
        if (noteId <= 0 || hash == null || hash.isBlank()) throw invalid("范围计划缺少笔记或正文版本");
        String mode = text(modelOutput.path("mode"));
        ObjectNode arguments = JsonNodeFactory.instance.objectNode();
        arguments.put("note_id", noteId).put("expected_hash", hash);
        if ("exact".equals(mode)) return new Plan(mode, text(modelOutput.path("goal")), arguments);
        if (!"sections".equals(mode)) throw invalid("范围计划mode仅支持exact或sections");
        String goal = requiredText(modelOutput, "goal");
        JsonNode units = modelOutput.path("units");
        if (!units.isArray() || units.isEmpty() || units.size() > MAX_UNITS)
            throw invalid("完整范围计划须包含1～50项units");
        JsonNode count = modelOutput.path("unitCount");
        if (!count.isIntegralNumber() || !count.canConvertToInt() || count.intValue() != units.size()
                || !modelOutput.path("complete").isBoolean() || !modelOutput.path("complete").booleanValue())
            throw invalid("范围计划未声明完整清单，complete须为true且unitCount须与units数量相同");
        ArrayNode tasks = arguments.putArray("tasks");
        Set<String> ids = new HashSet<>();
        for (JsonNode unit : units) {
            if (!unit.isObject()) throw invalid("范围计划的每项unit必须是对象");
            String id = requiredText(unit, "id");
            if (!ids.add(id)) throw invalid("范围计划unit id重复：" + id);
            String action = requiredText(unit, "action");
            if (!SECTION_ACTIONS.contains(action)) throw invalid("范围计划action仅支持insert_before/insert_after/replace");
            String heading = boundedText(unit, "heading");
            String instructions = boundedText(unit, "instructions");
            String anchor = requiredText(unit, "text");
            ObjectNode task = tasks.addObject();
            task.put("unit_id", id).put("heading", heading).put("instructions", instructions)
                    .put("action", action).put("text", anchor);
            if (unit.has("occurrence")) {
                JsonNode occurrence = unit.path("occurrence");
                if (!occurrence.isIntegralNumber() || !occurrence.canConvertToInt() || occurrence.intValue() < 1)
                    throw invalid("范围计划occurrence须为正整数");
                task.put("occurrence", occurrence.intValue());
            }
            for (String key : List.of("prefix", "suffix")) if (unit.has(key)) {
                if (!unit.path(key).isTextual()) throw invalid("范围计划" + key + "须为字符串");
                task.put(key, unit.path(key).textValue());
            }
        }
        return new Plan(mode, goal, arguments);
    }

    /** All units, their order, writing instructions and exact anchors must match the frozen plan. */
    static void validateCoverage(JsonNode plannedArgs, JsonNode actualArgs) {
        if (plannedArgs == null || actualArgs == null || !plannedArgs.isObject() || !actualArgs.isObject())
            throw invalid("缺少完整范围计划参数");
        JsonNode expectedId = plannedArgs.path("note_id"), actualId = actualArgs.path("note_id");
        if (!expectedId.isIntegralNumber() || !actualId.isIntegralNumber()
                || !expectedId.canConvertToLong() || !actualId.canConvertToLong()
                || expectedId.longValue() <= 0 || expectedId.longValue() != actualId.longValue()
                || !plannedArgs.path("expected_hash").isTextual() || plannedArgs.path("expected_hash").textValue().isBlank()
                || !plannedArgs.path("expected_hash").equals(actualArgs.path("expected_hash")))
            throw invalid("范围计划的笔记或正文版本不一致");
        JsonNode planned = plannedArgs.path("tasks"), actual = actualArgs.path("tasks");
        if (!planned.isArray() || planned.isEmpty() || planned.size() > MAX_UNITS
                || !actual.isArray() || planned.size() != actual.size())
            throw invalid("实际任务遗漏或增加了范围计划中的分段");
        Set<String> plannedIds = new HashSet<>(), actualIds = new HashSet<>();
        for (int index = 0; index < planned.size(); index++) {
            JsonNode frozen = planned.get(index), current = actual.get(index);
            String plannedId = requiredText(frozen, "unit_id"), currentId = requiredText(current, "unit_id");
            if (!plannedIds.add(plannedId) || !actualIds.add(currentId)) throw invalid("范围计划unit_id重复");
            for (String field : FROZEN_FIELDS) if (!frozen.path(field).equals(current.path(field)))
                throw invalid("分段范围、顺序或定位已变化：" + field);
        }
        if (!plannedIds.equals(actualIds)) throw invalid("实际任务未覆盖全部范围计划");
    }

    private static String text(JsonNode node) { return node.isTextual() ? node.textValue() : ""; }

    private static String requiredText(JsonNode object, String key) {
        String value = text(object.path(key));
        if (value.isBlank()) throw invalid("范围计划缺少非空字符串：" + key);
        return value;
    }

    private static String boundedText(JsonNode object, String key) {
        String value = requiredText(object, key);
        if (value.length() > MAX_INSTRUCTION_CHARS) throw invalid("范围计划" + key + "不能超过12000字");
        return value;
    }

    private static int headings(String markdown) {
        if (markdown.isEmpty()) return 0;
        int[] count = {0};
        Parser.builder().build().parse(markdown).accept(new AbstractVisitor() {
            @Override public void visit(Heading heading) { count[0]++; }
        });
        return count[0];
    }

    private static IllegalArgumentException invalid(String message) { return new IllegalArgumentException(message); }
}
