package org.dyh.learnhub.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.BiFunction;
import java.util.function.BiConsumer;

/**
 * A bounded JSON workflow composing approved tools, deterministic transforms and
 * optional isolated Python steps. Generated code is sent to a separately configured
 * runner; it is never executed in this JVM. The caller remains responsible for each
 * built-in tool's authorization and write confirmation.
 *
 * <p>中文说明：一个「有界」的 JSON 工作流执行器。模型只负责产出流程定义（JSON），
 * 本类负责按定义顺序执行：调用已授权的内置工具（{@code call}）、做确定性数据变换
 * （pick/filter/sort/unique/join/replace/template/split/length），以及可选的隔离 Python 步骤。
 *
 * <p>两条关键安全边界：
 * <ol>
 *   <li>生成的代码<b>绝不在本 JVM 内执行</b>，而是交给独立配置的 runner 进程；</li>
 *   <li>本类不做权限判定，<b>调用方</b>仍需为每个内置工具的授权与写操作确认负责。</li>
 * </ol>
 */
public final class AdaptiveToolWorkflow {
    /** 单个工作流最多允许的步骤数，防止定义被写成超长流水线。 */
    public static final int MAX_STEPS = 8;
    /** 工作流定义 JSON 的字符上限，先于解析做体量校验。 */
    public static final int MAX_DEFINITION_CHARS = 64_000;
    /** 单个值（含工具返回）的字符上限，避免单步把内存撑爆。 */
    public static final int MAX_VALUE_CHARS = 1_000_000;
    /** 所有步骤累计结果的字符上限，防止「每步都不大、加起来巨大」。 */
    public static final int MAX_TOTAL_RESULT_CHARS = 2_000_000;
    /** JSON 递归深度上限，配合已有的「病理递归」防护，阻断深嵌套攻击。 */
    public static final int MAX_DEPTH = 32;
    /** 单次数组处理的元素上限，避免对超大数组做 O(n) 变换时长时间占用。 */
    public static final int MAX_ARRAY_ITEMS = 10_000;
    private static final Set<String> KINDS = Set.of("pick", "filter", "sort", "unique", "join",
            "replace", "template", "split", "length");
    private static final Set<String> OPERATORS = Set.of("eq", "ne", "contains", "gt", "lt", "gte", "lte");
    private static final Set<String> TYPES = Set.of("object", "array", "string", "number", "integer", "boolean", "null");
    private static final Set<String> SCHEMA_KEYS = Set.of("type", "properties", "required", "additionalProperties",
            "enum", "items", "minLength", "maxLength", "minItems", "maxItems", "minimum", "maximum",
            "exclusiveMinimum", "exclusiveMaximum", "description", "title", "default", "examples", "$schema");
    private final ObjectMapper json;
    private final BiFunction<String, JsonNode, JsonNode> codeExecutor;

    public AdaptiveToolWorkflow(ObjectMapper json) {
        this(json, null);
    }

    public AdaptiveToolWorkflow(ObjectMapper json, BiFunction<String, JsonNode, JsonNode> codeExecutor) {
        this.json = Objects.requireNonNull(json, "json");
        this.codeExecutor = codeExecutor;
    }

    public void validate(JsonNode definition, Set<String> builtins) {
        validate(definition, builtins, Set.of());
    }

    /** Every possible write must be the last step; read/transform steps cannot follow it. */
    public void validate(JsonNode definition, Set<String> builtins, Set<String> writeTools) {
        Objects.requireNonNull(builtins, "builtins");
        Objects.requireNonNull(writeTools, "writeTools");
        budget(definition, MAX_DEFINITION_CHARS, "definition");
        requireObject(definition, "definition");
        allowedFields(definition, Set.of("name", "description", "parameters", "steps", "output"), "definition");
        String name = text(definition, "name");
        if (!name.matches("[a-z][a-z0-9_]{2,63}")) fail("name must be a lowercase tool identifier (3–64 characters)");
        if (definition.has("description") && !definition.get("description").isTextual()) fail("description must be text");
        JsonNode schema = definition.get("parameters");
        validateSchema(schema, "parameters", 0);
        if (!schema.path("type").isTextual() || !"object".equals(schema.path("type").asText())) {
            fail("parameters must declare type object");
        }
        JsonNode steps = definition.get("steps");
        if (steps == null || !steps.isArray() || steps.isEmpty() || steps.size() > MAX_STEPS) {
            fail("steps must contain 1–" + MAX_STEPS + " steps");
        }
        Set<String> previous = new LinkedHashSet<>();
        for (int index = 0; index < steps.size(); index++) {
            JsonNode step = steps.get(index);
            requireObject(step, "step " + index);
            String id = text(step, "id");
            if (!id.matches("[a-zA-Z][a-zA-Z0-9_]{0,39}") || previous.contains(id)) fail("invalid or duplicate step id: " + id);
            String op = text(step, "op");
            if ("call".equals(op)) {
                allowedFields(step, Set.of("id", "op", "tool", "arguments"), "step " + id);
                String tool = text(step, "tool");
                if (!builtins.contains(tool) || isEvolutionTool(tool)) fail("tool is not an approved built-in: " + tool);
                requireObject(step.get("arguments"), "arguments for " + id);
                validateExpressions(step.get("arguments"), previous);
                if (writeTools.contains(tool) && index != steps.size() - 1) fail("write tool must be the final step: " + tool);
            } else if ("transform".equals(op)) {
                validateTransform(step, previous);
            } else if ("code".equals(op)) {
                allowedFields(step, Set.of("id", "op", "language", "source", "input"), "step " + id);
                if (!"python".equals(text(step, "language"))) fail("code.language must be python");
                String source = text(step, "source");
                if (source.isBlank() || source.length() > CodeExecutionService.MAX_SOURCE_CHARS) {
                    fail("code.source must contain 1–" + CodeExecutionService.MAX_SOURCE_CHARS + " characters");
                }
                if (!step.has("input")) fail("code.input is required");
                validateExpressions(step.get("input"), previous);
            } else {
                fail("unsupported operation: " + op);
            }
            previous.add(id);
        }
        if (!definition.has("output")) fail("output is required");
        validateExpressions(definition.get("output"), previous);
    }

    /**
     * Execute against a fresh input snapshot. Validate against a built-in whitelist before
     * calling this method; the caller must also reject unknown/adaptive tools at dispatch.
     * A failed or staged call returns immediately, preserving its original status.
     */
    public JsonNode execute(JsonNode definition, JsonNode input, BiFunction<String, JsonNode, JsonNode> caller) {
        return execute(definition, input, caller, null);
    }

    /** The optional observer receives only actual code-step starts and completions. */
    public JsonNode execute(JsonNode definition, JsonNode input, BiFunction<String, JsonNode, JsonNode> caller,
                            BiConsumer<String, JsonNode> codeObserver) {
        Objects.requireNonNull(caller, "caller");
        budget(definition, MAX_DEFINITION_CHARS, "definition");
        JsonNode plan = definition.deepCopy();
        // Repeat structural validation in case a persisted or mutable definition changed.
        Set<String> calls = new HashSet<>();
        if (plan.path("steps").isArray()) {
            plan.path("steps").forEach(step -> {
                if ("call".equals(step.path("op").asText())) calls.add(step.path("tool").asText());
            });
        }
        validate(plan, calls);
        validateInput(plan, input);
        JsonNode snapshot = input.deepCopy();
        ObjectNode results = json.createObjectNode();
        int total = 0;
        for (JsonNode step : plan.get("steps")) {
            String id = step.get("id").asText();
            JsonNode result;
            if ("call".equals(step.get("op").asText())) {
                JsonNode arguments = resolve(step.get("arguments"), snapshot, results);
                budget(arguments, MAX_VALUE_CHARS, "arguments for " + id);
                requireObject(arguments, "resolved arguments for " + id);
                result = caller.apply(step.get("tool").asText(), arguments);
                if (result == null) fail("built-in returned no JSON result: " + step.get("tool").asText());
            } else if ("code".equals(step.get("op").asText())) {
                JsonNode codeInput = resolve(step.get("input"), snapshot, results);
                budget(codeInput, CodeExecutionService.MAX_JSON_CHARS, "code input for " + id);
                observeCode(codeObserver, "start", step);
                JsonNode envelope;
                try {
                    envelope = codeExecutor == null ? codeFailure("code_runner_unavailable", "代码执行环境未配置或不可用")
                            : codeExecutor.apply(step.get("source").asText(), codeInput);
                    if (envelope == null || !envelope.isObject() || !envelope.path("ok").isBoolean()
                            || (envelope.path("ok").asBoolean() && !envelope.has("output"))) {
                        envelope = codeFailure("invalid_runner_response", "代码执行环境返回了无效结果");
                    }
                    budget(envelope, MAX_VALUE_CHARS, "code response for " + id);
                    if (envelope.path("ok").asBoolean()) {
                        budget(envelope.get("output"), CodeExecutionService.MAX_JSON_CHARS, "code output for " + id);
                    }
                } catch (RuntimeException e) {
                    envelope = codeFailure("code_execution_failed", "代码步骤执行失败：" + e.getClass().getSimpleName());
                }
                ObjectNode report = json.createObjectNode().put("id", id);
                for (String field : List.of("ok", "elapsedMs", "error", "code", "stdout", "stderr", "sourceFingerprint")) {
                    if (envelope.has(field)) report.set(field, envelope.get(field).deepCopy());
                }
                observeCode(codeObserver, "finish", report);
                if (!envelope.path("ok").asBoolean()) {
                    ObjectNode failure = (ObjectNode) envelope.deepCopy();
                    failure.put("stepId", id);
                    return failure;
                }
                result = envelope.get("output");
            } else {
                result = transform(step, snapshot, results);
            }
            int chars = budget(result, MAX_VALUE_CHARS, "result for " + id);
            total += chars;
            if (total > MAX_TOTAL_RESULT_CHARS) fail("workflow results exceed the character budget");
            JsonNode stable = result.deepCopy();
            results.set(id, stable);
            if ("call".equals(step.get("op").asText()) && stable.isObject()
                    && ((stable.path("ok").isBoolean() && !stable.path("ok").asBoolean())
                    || (stable.path("staged").isBoolean() && stable.path("staged").asBoolean()))) {
                // Do not replace staged:true with a workflow-level success or continue execution.
                return stable;
            }
        }
        JsonNode output = resolve(plan.get("output"), snapshot, results);
        budget(output, MAX_VALUE_CHARS, "output");
        ObjectNode response = json.createObjectNode();
        response.put("ok", true);
        response.set("output", output);
        response.set("steps", results);
        budget(response, MAX_TOTAL_RESULT_CHARS, "response");
        return response;
    }

    private ObjectNode codeFailure(String code, String message) {
        return json.createObjectNode().put("ok", false).put("code", code).put("error", message);
    }

    private static void observeCode(BiConsumer<String, JsonNode> observer, String event, JsonNode detail) {
        if (observer == null) return;
        // The start callback is also a caller-owned run-budget gate and must fail closed.
        if ("start".equals(event)) { observer.accept(event, detail.deepCopy()); return; }
        // A disconnected completion listener must never change execution semantics.
        try { observer.accept(event, detail.deepCopy()); } catch (RuntimeException ignored) { }
    }

    /** Validate caller-supplied parameters independently of an implementation's execution. */
    public void validateInput(JsonNode definition, JsonNode input) {
        budget(input, MAX_VALUE_CHARS, "input");
        validateValue(definition.get("parameters"), input, "input");
    }

    public static boolean containsWrites(JsonNode definition, Set<String> writeTools) {
        if (definition == null || writeTools == null) return false;
        for (JsonNode step : definition.path("steps")) {
            if ("call".equals(step.path("op").asText()) && writeTools.contains(step.path("tool").asText())) return true;
        }
        return false;
    }

    private static boolean isEvolutionTool(String tool) {
        return tool.startsWith("adaptive_") || tool.startsWith("evolve_") || tool.contains("evolution")
                || tool.contains("adaptive_tool");
    }

    private void validateTransform(JsonNode step, Set<String> previous) {
        String kind = text(step, "kind");
        if (!KINDS.contains(kind)) fail("unsupported transform: " + kind);
        Set<String> fields = new HashSet<>(Set.of("id", "op", "kind", "value"));
        switch (kind) {
            case "pick" -> {
                fields.add("fields");
                JsonNode values = step.get("fields");
                if (values == null || !values.isArray() || values.isEmpty() || values.size() > 100) fail("pick.fields must be a nonempty string array (max 100)");
                Set<String> seen = new HashSet<>();
                for (JsonNode field : values) {
                    if (!field.isTextual() || !validPath(field.asText()) || !seen.add(field.asText())) fail("invalid or duplicate pick field");
                }
            }
            case "filter" -> {
                fields.addAll(Set.of("field", "operator", "compare"));
                optionalPath(step, "field");
                if (!OPERATORS.contains(text(step, "operator"))) fail("unsupported filter operator");
                if (!step.has("compare")) fail("filter.compare is required");
            }
            case "sort" -> {
                fields.addAll(Set.of("field", "descending"));
                optionalPath(step, "field");
                optionalBoolean(step, "descending");
            }
            case "unique" -> {
                fields.add("field");
                optionalPath(step, "field");
            }
            case "join", "split" -> fields.add("separator");
            case "replace" -> {
                fields.addAll(Set.of("search", "replacement", "all"));
                optionalBoolean(step, "all");
                if (!step.has("search") || !step.has("replacement")) fail("replace.search and replacement are required");
            }
            case "template" -> {
                fields.remove("value");
                fields.add("text");
                String template = text(step, "text");
                scanTemplate(template, ref -> validateReference(ref, previous));
            }
            default -> { }
        }
        if (!"template".equals(kind) && !step.has("value")) fail("transform.value is required");
        if (Set.of("join", "split").contains(kind) && !step.has("separator")) fail(kind + ".separator is required");
        allowedFields(step, fields, "transform " + step.path("id").asText());
        Iterator<Map.Entry<String, JsonNode>> iterator = step.fields();
        while (iterator.hasNext()) {
            var entry = iterator.next();
            if (!Set.of("id", "op", "kind", "text", "fields", "field", "operator", "all", "descending").contains(entry.getKey())) {
                validateExpressions(entry.getValue(), previous);
            }
        }
    }

    private JsonNode transform(JsonNode step, JsonNode input, ObjectNode results) {
        String kind = step.get("kind").asText();
        if ("template".equals(kind)) {
            StringBuilder out = new StringBuilder();
            String template = step.get("text").asText();
            int cursor = 0;
            while (cursor < template.length()) {
                int start = template.indexOf("{{", cursor);
                if (start < 0) { append(out, template.substring(cursor)); break; }
                append(out, template.substring(cursor, start));
                int end = template.indexOf("}}", start + 2);
                String ref = template.substring(start + 2, end).trim();
                append(out, display(reference(ref, input, results)));
                cursor = end + 2;
            }
            return json.getNodeFactory().textNode(out.toString());
        }
        JsonNode value = resolve(step.get("value"), input, results);
        return switch (kind) {
            case "pick" -> {
                if (value.isArray()) {
                    ArrayNode out = json.createArrayNode();
                    for (JsonNode item : value) out.add(pick(item, step.get("fields")));
                    yield out;
                }
                yield pick(value, step.get("fields"));
            }
            case "filter" -> {
                requireArray(value, "filter.value");
                JsonNode compare = resolve(step.get("compare"), input, results);
                String compareKey = canonical(compare);
                ArrayNode out = json.createArrayNode();
                for (JsonNode item : value) {
                    JsonNode selected = selected(item, step);
                    if (matches(selected, step.get("operator").asText(), compare, compareKey)) out.add(item.deepCopy());
                }
                yield out;
            }
            case "sort" -> {
                requireArray(value, "sort.value");
                List<JsonNode> sorted = new ArrayList<>();
                value.forEach(item -> {
                    JsonNode key = selected(item, step);
                    if (!(key.isValueNode() && !key.isMissingNode())) fail("sort keys must be scalar JSON values");
                    sorted.add(item);
                });
                Comparator<JsonNode> comparator = (a, b) -> compareScalars(selected(a, step), selected(b, step));
                if (step.path("descending").asBoolean(false)) comparator = comparator.reversed();
                sorted.sort(comparator);
                ArrayNode out = json.createArrayNode();
                sorted.forEach(item -> out.add(item.deepCopy()));
                yield out;
            }
            case "unique" -> {
                requireArray(value, "unique.value");
                Set<String> seen = new LinkedHashSet<>();
                ArrayNode out = json.createArrayNode();
                for (JsonNode item : value) if (seen.add(canonical(selected(item, step)))) out.add(item.deepCopy());
                yield out;
            }
            case "join" -> {
                requireArray(value, "join.value");
                String separator = resolvedText(step, "separator", input, results);
                StringBuilder out = new StringBuilder();
                for (int index = 0; index < value.size(); index++) {
                    if (index > 0) append(out, separator);
                    append(out, display(value.get(index)));
                }
                yield json.getNodeFactory().textNode(out.toString());
            }
            case "replace" -> {
                requireText(value, "replace.value");
                String search = resolvedText(step, "search", input, results);
                String replacement = resolvedText(step, "replacement", input, results);
                if (search.isEmpty()) fail("replace.search cannot be empty");
                String source = value.asText();
                StringBuilder out = new StringBuilder();
                int cursor = 0;
                while (cursor < source.length()) {
                    int found = source.indexOf(search, cursor);
                    if (found < 0) { append(out, source.substring(cursor)); break; }
                    append(out, source.substring(cursor, found));
                    append(out, replacement);
                    cursor = found + search.length();
                    if (!step.path("all").asBoolean(true)) { append(out, source.substring(cursor)); break; }
                }
                yield json.getNodeFactory().textNode(out.toString());
            }
            case "split" -> {
                requireText(value, "split.value");
                String separator = resolvedText(step, "separator", input, results);
                if (separator.isEmpty()) fail("split.separator cannot be empty");
                String source = value.asText();
                ArrayNode out = json.createArrayNode();
                int cursor = 0;
                int found;
                while ((found = source.indexOf(separator, cursor)) >= 0) {
                    if (out.size() >= MAX_ARRAY_ITEMS - 1) fail("split exceeds the array item budget");
                    out.add(source.substring(cursor, found));
                    cursor = found + separator.length();
                }
                out.add(source.substring(cursor));
                yield out;
            }
            case "length" -> {
                if (!(value.isArray() || value.isObject() || value.isTextual())) fail("length.value must be text, an array or an object");
                yield json.getNodeFactory().numberNode(value.isTextual() ? value.asText().codePointCount(0, value.asText().length()) : value.size());
            }
            default -> throw new IllegalArgumentException("unsupported transform: " + kind);
        };
    }

    private ObjectNode pick(JsonNode value, JsonNode fields) {
        requireObject(value, "pick.value item");
        ObjectNode out = json.createObjectNode();
        for (JsonNode field : fields) out.set(field.asText(), path(value, field.asText()).deepCopy());
        return out;
    }

    private static JsonNode selected(JsonNode item, JsonNode step) {
        return step.has("field") ? path(item, step.get("field").asText()) : item;
    }

    private static boolean matches(JsonNode value, String operator, JsonNode compare, String compareKey) {
        return switch (operator) {
            case "eq" -> canonical(value).equals(compareKey);
            case "ne" -> !canonical(value).equals(compareKey);
            case "contains" -> {
                if (value.isTextual() && compare.isTextual()) yield value.asText().contains(compare.asText());
                if (value.isArray()) {
                    boolean found = false;
                    for (JsonNode item : value) if (canonical(item).equals(compareKey)) { found = true; break; }
                    yield found;
                }
                throw new IllegalArgumentException("contains requires text/text or an array/value");
            }
            case "gt" -> compareScalars(value, compare) > 0;
            case "lt" -> compareScalars(value, compare) < 0;
            case "gte" -> compareScalars(value, compare) >= 0;
            case "lte" -> compareScalars(value, compare) <= 0;
            default -> throw new IllegalArgumentException("unsupported filter operator: " + operator);
        };
    }

    private static int compareScalars(JsonNode a, JsonNode b) {
        if (a.isNumber() && b.isNumber()) return a.decimalValue().compareTo(b.decimalValue());
        if (a.isTextual() && b.isTextual()) return a.asText().compareTo(b.asText());
        if (a.isBoolean() && b.isBoolean()) return Boolean.compare(a.asBoolean(), b.asBoolean());
        if (a.isNull() && b.isNull()) return 0;
        throw new IllegalArgumentException("comparison requires matching number, string or boolean values");
    }

    private static String canonical(JsonNode value) {
        if (value.isNumber()) return "n:" + value.decimalValue().stripTrailingZeros().toString();
        if (value.isObject()) {
            List<String> names = new ArrayList<>();
            value.fieldNames().forEachRemaining(names::add);
            names.sort(String::compareTo);
            StringBuilder out = new StringBuilder("{");
            for (String name : names) out.append(name.length()).append(':').append(name).append(canonical(value.get(name))).append(';');
            return out.append('}').toString();
        }
        if (value.isArray()) {
            StringBuilder out = new StringBuilder("[");
            value.forEach(item -> out.append(canonical(item)).append(';'));
            return out.append(']').toString();
        }
        return value.toString();
    }

    private JsonNode resolve(JsonNode expression, JsonNode input, ObjectNode results) {
        if (expression.isObject()) {
            if (expression.has("$ref")) return reference(expression.get("$ref").asText(), input, results).deepCopy();
            ObjectNode out = json.createObjectNode();
            expression.fields().forEachRemaining(entry -> out.set(entry.getKey(), resolve(entry.getValue(), input, results)));
            return out;
        }
        if (expression.isArray()) {
            ArrayNode out = json.createArrayNode();
            expression.forEach(item -> out.add(resolve(item, input, results)));
            return out;
        }
        return expression.deepCopy();
    }

    private static JsonNode reference(String ref, JsonNode input, ObjectNode results) {
        if ("input".equals(ref)) return input;
        if (ref.startsWith("input.")) return path(input, ref.substring(6));
        if (ref.startsWith("steps.")) return path(results, ref.substring(6));
        throw new IllegalArgumentException("invalid reference: " + ref);
    }

    private static JsonNode path(JsonNode root, String path) {
        JsonNode current = root;
        for (String segment : path.split("\\.", -1)) {
            if (current.isArray() && segment.matches("0|[1-9][0-9]{0,8}")) {
                int index = Integer.parseInt(segment);
                current = current.get(index);
            } else if (current.isObject()) {
                current = current.get(segment);
            } else {
                current = null;
            }
            if (current == null) fail("reference field does not exist: " + path);
        }
        return current;
    }

    private static void validateExpressions(JsonNode node, Set<String> previous) {
        if (node.isObject() && node.has("$ref")) {
            if (node.size() != 1 || !node.get("$ref").isTextual()) fail("reference must contain only a textual $ref");
            validateReference(node.get("$ref").asText(), previous);
        } else if (node.isContainerNode()) {
            node.forEach(item -> validateExpressions(item, previous));
        }
    }

    private static void validateReference(String ref, Set<String> previous) {
        if ("input".equals(ref)) return;
        if (ref.startsWith("input.") && validPath(ref.substring(6))) return;
        if (ref.startsWith("steps.") && validPath(ref.substring(6))) {
            String id = ref.substring(6).split("\\.", 2)[0];
            if (previous.contains(id)) return;
            fail("reference requires a previous step: " + ref);
        }
        fail("invalid reference: " + ref);
    }

    private static boolean validPath(String value) {
        if (value.isEmpty() || value.length() > 300) return false;
        for (String segment : value.split("\\.", -1)) if (segment.isEmpty() || !segment.matches("[\\p{L}\\p{N}_-]+")) return false;
        return true;
    }

    private static void scanTemplate(String template, java.util.function.Consumer<String> consume) {
        int cursor = 0;
        while (cursor < template.length()) {
            int start = template.indexOf("{{", cursor);
            int orphan = template.indexOf("}}", cursor);
            if (orphan >= 0 && (start < 0 || orphan < start)) fail("unmatched template closing delimiter");
            if (start < 0) return;
            int end = template.indexOf("}}", start + 2);
            if (end < 0) fail("unmatched template opening delimiter");
            consume.accept(template.substring(start + 2, end).trim());
            cursor = end + 2;
        }
    }

    private static void validateSchema(JsonNode schema, String location, int depth) {
        if (depth > MAX_DEPTH) fail("schema exceeds the depth budget");
        requireObject(schema, location);
        allowedFields(schema, SCHEMA_KEYS, location);
        JsonNode type = schema.get("type");
        if (type == null) fail(location + ".type is required");
        if (type.isTextual()) {
            if (!TYPES.contains(type.asText())) fail("unsupported schema type at " + location);
        } else if (type.isArray() && !type.isEmpty()) {
            Set<String> seen = new HashSet<>();
            for (JsonNode item : type) if (!item.isTextual() || !TYPES.contains(item.asText()) || !seen.add(item.asText())) fail("invalid schema type union at " + location);
        } else fail("invalid schema type at " + location);
        if (schema.has("properties")) {
            requireObject(schema.get("properties"), location + ".properties");
            schema.get("properties").fields().forEachRemaining(entry -> validateSchema(entry.getValue(), location + "." + entry.getKey(), depth + 1));
        }
        if (schema.has("required")) {
            requireArray(schema.get("required"), location + ".required");
            Set<String> seen = new HashSet<>();
            for (JsonNode field : schema.get("required")) {
                if (!field.isTextual() || !seen.add(field.asText()) || !schema.path("properties").has(field.asText())) fail("required must list distinct declared properties at " + location);
            }
        }
        optionalBoolean(schema, "additionalProperties");
        if (schema.has("items")) validateSchema(schema.get("items"), location + ".items", depth + 1);
        if (schema.has("enum") && (!schema.get("enum").isArray() || schema.get("enum").isEmpty() || schema.get("enum").size() > 100)) fail("enum must contain 1–100 values at " + location);
        for (String key : Set.of("minLength", "maxLength", "minItems", "maxItems")) {
            if (schema.has(key) && (!schema.get(key).isIntegralNumber() || !schema.get(key).canConvertToInt() || schema.get(key).asInt() < 0)) fail(key + " must be a nonnegative integer at " + location);
        }
        for (String key : Set.of("minimum", "maximum", "exclusiveMinimum", "exclusiveMaximum")) {
            if (schema.has(key) && !schema.get(key).isNumber()) fail(key + " must be a number at " + location);
        }
        for (String prefix : Set.of("Length", "Items")) {
            if (schema.has("min" + prefix) && schema.has("max" + prefix) && schema.get("min" + prefix).asInt() > schema.get("max" + prefix).asInt()) fail("contradictory bounds at " + location);
        }
    }

    private static void validateValue(JsonNode schema, JsonNode value, String location) {
        JsonNode types = schema.get("type");
        boolean matchesType = types.isTextual() ? typeMatches(types.asText(), value) : false;
        if (types.isArray()) for (JsonNode type : types) matchesType |= typeMatches(type.asText(), value);
        if (!matchesType) fail(location + " has an invalid type");
        if (schema.has("enum")) {
            boolean found = false;
            for (JsonNode allowed : schema.get("enum")) if (canonical(allowed).equals(canonical(value))) { found = true; break; }
            if (!found) fail(location + " is not an allowed enum value");
        }
        if (value.isObject()) {
            for (JsonNode required : schema.path("required")) if (!value.has(required.asText())) fail(location + "." + required.asText() + " is required");
            var fields = value.fields();
            while (fields.hasNext()) {
                var entry = fields.next();
                JsonNode childSchema = schema.path("properties").get(entry.getKey());
                if (childSchema != null) validateValue(childSchema, entry.getValue(), location + "." + entry.getKey());
                else if (schema.has("additionalProperties") && !schema.get("additionalProperties").asBoolean()) fail(location + " contains undeclared property: " + entry.getKey());
            }
        }
        if (value.isArray()) {
            checkSize(schema, value.size(), "Items", location);
            if (schema.has("items")) for (int index = 0; index < value.size(); index++) validateValue(schema.get("items"), value.get(index), location + "[" + index + "]");
        }
        if (value.isTextual()) checkSize(schema, value.asText().codePointCount(0, value.asText().length()), "Length", location);
        if (value.isNumber()) {
            BigDecimal number = value.decimalValue();
            for (String key : Set.of("minimum", "maximum", "exclusiveMinimum", "exclusiveMaximum")) {
                if (!schema.has(key)) continue;
                int comparison = number.compareTo(schema.get(key).decimalValue());
                if (("minimum".equals(key) && comparison < 0) || ("maximum".equals(key) && comparison > 0)
                        || ("exclusiveMinimum".equals(key) && comparison <= 0) || ("exclusiveMaximum".equals(key) && comparison >= 0)) fail(location + " violates " + key);
            }
        }
    }

    private static void checkSize(JsonNode schema, int length, String suffix, String location) {
        if (schema.has("min" + suffix) && length < schema.get("min" + suffix).asInt()) fail(location + " is shorter than min" + suffix);
        if (schema.has("max" + suffix) && length > schema.get("max" + suffix).asInt()) fail(location + " exceeds max" + suffix);
    }

    private static boolean typeMatches(String type, JsonNode value) {
        return switch (type) {
            case "object" -> value.isObject(); case "array" -> value.isArray(); case "string" -> value.isTextual();
            case "number" -> value.isNumber(); case "integer" -> value.isNumber() && value.decimalValue().stripTrailingZeros().scale() <= 0;
            case "boolean" -> value.isBoolean(); case "null" -> value.isNull(); default -> false;
        };
    }

    private static int budget(JsonNode value, int maxChars, String location) {
        if (value == null || value.isMissingNode()) fail(location + " is required");
        inspect(value, 0, new int[]{0, 0}, maxChars);
        // The structural walk bounds JSON tree size before serialization allocates a copy.
        int length = value.toString().length();
        if (length > maxChars) fail(location + " exceeds the character budget");
        return length;
    }

    private static void inspect(JsonNode value, int depth, int[] counters, int maxChars) {
        if (depth > MAX_DEPTH || ++counters[0] > 50_000) fail("JSON exceeds the structure budget");
        if (value.isArray() && value.size() > MAX_ARRAY_ITEMS) fail("JSON exceeds the array item budget");
        if (!(value.isContainerNode() || value.isTextual() || value.isNumber() || value.isBoolean() || value.isNull())) fail("unsupported JSON value");
        if (value.isNumber() && (value.isFloatingPointNumber() && !Double.isFinite(value.doubleValue()))) fail("nonfinite numbers are not supported");
        if (value.isTextual() && value.asText().length() > MAX_VALUE_CHARS) fail("text exceeds the character budget");
        counters[1] += value.isTextual() ? value.asText().length() + 2 : 1;
        if (counters[1] > maxChars) fail("JSON exceeds the character budget");
        if (value.isObject()) {
            var fields = value.fields();
            while (fields.hasNext()) {
                var entry = fields.next();
                if (entry.getKey().length() > maxChars - counters[1]) fail("JSON fields exceed the character budget");
                counters[1] += entry.getKey().length();
                inspect(entry.getValue(), depth + 1, counters, maxChars);
            }
        } else if (value.isArray()) {
            value.forEach(item -> inspect(item, depth + 1, counters, maxChars));
        }
    }

    private String resolvedText(JsonNode step, String field, JsonNode input, ObjectNode results) {
        JsonNode value = resolve(step.get(field), input, results);
        requireText(value, field);
        return value.asText();
    }

    private static String display(JsonNode value) { return value.isTextual() ? value.asText() : value.toString(); }
    private static void append(StringBuilder builder, String value) {
        if (value.length() > MAX_VALUE_CHARS - builder.length()) fail("transform exceeds the character budget");
        builder.append(value);
    }
    private static String text(JsonNode node, String field) {
        JsonNode value = node.get(field);
        requireText(value, field);
        return value.asText();
    }
    private static void requireText(JsonNode node, String location) { if (node == null || !node.isTextual()) fail(location + " must be text"); }
    private static void requireObject(JsonNode node, String location) { if (node == null || !node.isObject()) fail(location + " must be an object"); }
    private static void requireArray(JsonNode node, String location) { if (node == null || !node.isArray()) fail(location + " must be an array"); }
    private static void optionalBoolean(JsonNode node, String field) { if (node.has(field) && !node.get(field).isBoolean()) fail(field + " must be boolean"); }
    private static void optionalPath(JsonNode node, String field) { if (node.has(field) && (!node.get(field).isTextual() || !validPath(node.get(field).asText()))) fail(field + " must be a field path"); }
    private static void allowedFields(JsonNode node, Set<String> allowed, String location) {
        node.fieldNames().forEachRemaining(field -> { if (!allowed.contains(field)) fail("unsupported field at " + location + ": " + field); });
    }
    private static void fail(String message) { throw new IllegalArgumentException(message); }
}
