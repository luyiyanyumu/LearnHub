package org.dyh.learnhub.ai;

import com.fasterxml.jackson.core.JsonPointer;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/** Composes catalogued commands by passing JSON values between their inputs and results. */
final class AgentAppPipeline {
    private static final int MAX_STEPS = 8;
    private static final int MAX_READ_CALLS = 64;
    private static final int MAX_INPUT_BYTES = 256 * 1024;
    private static final int MAX_RESULTS_BYTES = 512 * 1024;
    private static final int MAX_TEMPLATE_DEPTH = 32;
    private static final int MAX_MOVE_IDS = 500;
    private static final int MAX_PAGE_ROWS = 500;
    private static final int MAX_PAGE_SIZE = 100;
    private static final String RESULT_LIMIT_MESSAGE = "管道累计结果超过 512 KiB，请缩小查询范围。";
    private static final Pattern STEP_ID = Pattern.compile("[A-Za-z][A-Za-z0-9_-]{0,31}");
    private static final Pattern COMMAND_ID = Pattern.compile("[A-Za-z][A-Za-z0-9_.-]{0,79}");
    private static final Pattern INVALID_POINTER_ESCAPE = Pattern.compile("~(?![01])");
    private static final Pattern DECIMAL_ID = Pattern.compile("[0-9]{1,19}");
    private static final Set<String> STEP_FIELDS = Set.of("id", "command", "input", "paginate", "forEach");
    private static final Map<String, Integer> PAGED_COMMANDS = Map.of("note.page", 10, "file.page", 20);

    private final ObjectMapper json;
    private final AgentAppCommands commands;

    AgentAppPipeline(ObjectMapper json, AgentAppCommands commands) {
        this.json = json;
        this.commands = commands;
    }

    Outcome execute(JsonNode arguments, long deadline) {
        ArrayNode trace = json.createArrayNode();
        List<Step> plan;
        try {
            checkDeadline(deadline);
            plan = plan(arguments);
        } catch (PipelineError error) {
            return failure(trace, null, error.code, error.getMessage());
        }

        Map<String, JsonNode> results = new HashMap<>();
        ReadBudget budget = new ReadBudget();
        JsonNode finalResult = null;
        for (Step step : plan) {
            ObjectNode summary = trace.addObject().put("id", step.id()).put("command", step.command());
            try {
                checkDeadline(deadline);
                JsonNode result;
                if (step.paginate()) {
                    result = paginate(step, resolveInput(step, results, null), budget, deadline, summary);
                } else if (step.forEach() != null) {
                    result = forEach(step, results, budget, deadline, summary);
                } else {
                    var prepared = prepare(step, resolveInput(step, results, null));
                    checkDeadline(deadline);
                    if (prepared.requiresApproval()) {
                        summary.put("status", "pending");
                        return new Outcome(null, prepared, trace);
                    }
                    result = read(prepared, budget, deadline);
                }

                budget.bytes -= resultSize(result, budget.bytes);
                results.put(step.id(), result);
                summarize(summary.put("status", "ok"), result);
                finalResult = json.createObjectNode().put("ok", true).put("command", step.command()).set("result", result);
            } catch (PipelineError error) {
                summary.put("status", "failed");
                return failure(trace, step.command(), error.code, error.getMessage());
            } catch (IllegalArgumentException error) {
                summary.put("status", "failed");
                return failure(trace, step.command(), "invalid_input", error.getMessage());
            }
        }
        return new Outcome(finalResult, null, trace);
    }

    private ObjectNode resolveInput(Step step, Map<String, JsonNode> results, JsonNode item) {
        JsonNode input = resolve(step.input(), results, item, new SizeBudget(MAX_INPUT_BYTES));
        require(input.isObject(), "命令 input 必须解析为 JSON 对象。");
        return (ObjectNode) input;
    }

    private AgentAppCommands.Prepared prepare(Step step, ObjectNode input) {
        measure(input, MAX_INPUT_BYTES, "input_limit", "解析后的命令参数超过 256 KiB，请缩小管道范围。");
        var prepared = commands.prepare(step.command(),
                step.command().equals("folder.move-items") ? normalizeMove(input) : input);
        require(prepared.requiresApproval() == step.requiresApproval(), "命令权限已变更，请重新规划管道。");
        return prepared;
    }

    private JsonNode read(AgentAppCommands.Prepared prepared, ReadBudget budget, long deadline) {
        checkDeadline(deadline);
        requireReadCalls(budget, 1);
        budget.calls--;
        JsonNode response = commands.run(prepared, deadline);
        if (!response.path("ok").asBoolean(false)) {
            throw new PipelineError(response.path("code").asText("read_failed"),
                    response.path("error").asText("读取命令未完成。"));
        }
        return response.path("result");
    }

    private ArrayNode forEach(Step step, Map<String, JsonNode> results, ReadBudget budget, long deadline,
                             ObjectNode summary) {
        JsonNode source = resolveReference(step.forEach(), results);
        require(source.isArray(), "forEach 必须引用前序步骤返回的数组。");
        requireReadCalls(budget, source.size());
        int remainingBytes = budget.bytes;
        ArrayNode outputs = json.createArrayNode();
        summary.put("iterationCount", 0);
        for (JsonNode item : source) {
            checkDeadline(deadline);
            JsonNode result = read(prepare(step, resolveInput(step, results, item)), budget, deadline);
            remainingBytes -= resultSize(result, remainingBytes);
            outputs.add(result);
            summary.put("iterationCount", outputs.size());
        }
        return outputs;
    }

    private ObjectNode paginate(Step step, ObjectNode resolvedInput, ReadBudget budget, long deadline,
                                ObjectNode summary) {
        ObjectNode input = resolvedInput.deepCopy();
        require(!input.has("query") || input.path("query").isObject(), "自动翻页的 input.query 必须是 JSON 对象。");
        ObjectNode query = input.withObject("/query");
        int start = pageParameter(query, "page", 1, Integer.MAX_VALUE);
        int size = pageParameter(query, "size", PAGED_COMMANDS.get(step.command()), MAX_PAGE_SIZE);
        query.put("size", size);
        summary.put("startPage", start).put("pageCount", 0);

        long total = -1;
        long last = start;
        int remainingBytes = budget.bytes;
        Set<Long> seen = new HashSet<>();
        ObjectNode aggregate = json.createObjectNode();
        ArrayNode rows = aggregate.putArray("list");
        for (long page = start; page <= last; page++) {
            checkDeadline(deadline);
            query.put("page", page);
            JsonNode pageResult = read(prepare(step, input), budget, deadline);
            JsonNode pageRows = pageResult.path("list");
            JsonNode reportedTotal = pageResult.path("total");
            requirePage(nonNegativeInteger(reportedTotal) && pageRows.isArray());
            if (step.command().equals("file.page")) {
                requirePage(nonNegativeInteger(pageResult.path("page")) && pageResult.path("page").longValue() == page
                        && nonNegativeInteger(pageResult.path("size")) && pageResult.path("size").longValue() == size);
            }
            if (page == start) {
                total = reportedTotal.longValue();
                long requestedRows = Math.max(0, total - (page - 1) * size);
                if (requestedRows > MAX_PAGE_ROWS) {
                    throw new PipelineError("pagination_limit", "自动翻页最多汇总 500 个条目，请缩小查询范围。");
                }
                long more = requestedRows == 0 ? 0 : (requestedRows - 1) / size;
                requireReadCalls(budget, more);
                last = page + more;
                require(last <= Integer.MAX_VALUE, "自动翻页超出最大页码。");
                aggregate.setAll((ObjectNode) pageResult);
                aggregate.set("list", rows);
            }
            long expected = Math.min(size, Math.max(0, total - (page - 1) * size));
            requirePage(reportedTotal.longValue() == total && pageRows.size() == expected);
            remainingBytes -= resultSize(pageRows, remainingBytes) - 1;
            for (JsonNode row : pageRows) {
                long id = parseId(row.path("id"));
                requirePage(id > 0 && seen.add(id));
                rows.add(row);
            }
            summary.put("pageCount", page - start + 1);
        }
        if (start > 1) summary.put("partial", true);
        return aggregate;
    }

    private static int pageParameter(ObjectNode query, String name, int defaultValue, int maximum) {
        JsonNode value = query.path(name);
        if (value.isMissingNode()) return defaultValue;
        require(nonNegativeInteger(value) && value.longValue() > 0 && value.longValue() <= maximum,
                "自动翻页的 " + name + " 必须是 1 至 " + maximum + " 的整数。");
        return value.intValue();
    }

    private static boolean nonNegativeInteger(JsonNode value) {
        return value.isIntegralNumber() && value.canConvertToLong() && value.longValue() >= 0;
    }

    private static void requirePage(boolean condition) {
        if (!condition) {
            throw new PipelineError("pagination_inconsistent", "分页结果不一致或条目重复，请重新查询或缩小范围。");
        }
    }

    private static void requireReadCalls(ReadBudget budget, long calls) {
        if (calls > budget.calls) {
            throw new PipelineError("call_limit", "一条管道最多执行 64 次只读命令，请缩小查询或循环范围。");
        }
    }

    private int resultSize(JsonNode value, int limit) {
        return measure(value, limit, "output_limit", RESULT_LIMIT_MESSAGE);
    }

    private List<Step> plan(JsonNode arguments) {
        require(arguments != null && arguments.isObject() && arguments.size() == 1, "管道参数必须是只含 steps 的对象。");
        JsonNode steps = arguments.path("steps");
        require(steps.isArray() && !steps.isEmpty() && steps.size() <= MAX_STEPS, "管道须包含 1 至 8 个步骤。");
        measure(arguments, MAX_INPUT_BYTES, "input_limit", "管道参数超过 256 KiB，请缩小管道范围。");

        Set<String> earlier = new HashSet<>();
        List<Step> plan = new ArrayList<>();
        for (JsonNode step : steps) {
            require(step.isObject() && step.properties().stream().allMatch(field -> STEP_FIELDS.contains(field.getKey())),
                    "每个步骤只能包含 id、command、input、paginate 和 forEach。");
            String id = matchingText(step.path("id"), STEP_ID, "步骤 id 须以字母开头，最多 32 个字符，只接受字母、数字、下划线和连字符。");
            require(!earlier.contains(id), "管道步骤 id 必须唯一。");
            String command = matchingText(step.path("command"), COMMAND_ID, "步骤 command 须为应用命令名。");
            JsonNode input = step.has("input") ? step.get("input") : json.createObjectNode();
            require(input.isObject(), "步骤 input 必须是 JSON 对象。");
            require(!step.has("paginate") || step.path("paginate").isBoolean(), "paginate 须为布尔值。");
            boolean paginate = step.path("paginate").asBoolean(false);
            Reference forEach = step.has("forEach") ? earlierReference(step.path("forEach"), earlier) : null;
            require(!paginate || forEach == null, "同一步骤只能选择自动翻页或逐项循环。");
            validateReferences(input, earlier, forEach != null, 0);

            JsonNode definition;
            try {
                definition = commands.describe(command);
            } catch (IllegalArgumentException error) {
                throw new PipelineError("unknown_command", error.getMessage());
            }
            require(definition.path("ok").asBoolean() && definition.path("available").asBoolean(), "管道包含不可用的应用命令。");
            require(definition.path("requiresApproval").isBoolean(), "应用命令未声明审批要求。");
            boolean requiresApproval = definition.path("requiresApproval").booleanValue();
            require(!paginate || (PAGED_COMMANDS.containsKey(command) && definition.path("method").asText().equals("GET")),
                    "自动翻页只支持 note.page 和 file.page。");
            require(!requiresApproval || (!paginate && forEach == null), "自动翻页和逐项循环只支持只读命令。");
            require(!requiresApproval || plan.size() == steps.size() - 1, "需要确认的写入命令只能放在管道最后一步。");
            plan.add(new Step(id, command, input, requiresApproval, paginate, forEach));
            earlier.add(id);
        }
        return plan;
    }

    private static void validateReferences(JsonNode value, Set<String> earlier, boolean allowItem, int depth) {
        require(depth <= MAX_TEMPLATE_DEPTH, "步骤参数的嵌套层级超过 32。");
        if (isItem(value)) {
            require(allowItem, "$item 只能用于 forEach 步骤的 input。");
            itemPointer(value);
        } else if (isReference(value)) {
            earlierReference(value, earlier);
        } else {
            for (JsonNode child : value) validateReferences(child, earlier, allowItem, depth + 1);
        }
    }

    private JsonNode resolve(JsonNode value, Map<String, JsonNode> results, JsonNode item, SizeBudget referenceBudget) {
        if (isReference(value) || isItem(value)) {
            JsonNode selected = isItem(value) ? select(item, itemPointer(value)) : resolveReference(reference(value), results);
            measure(selected, referenceBudget, "input_limit", "解析后的命令参数超过 256 KiB，请缩小管道范围。");
            return selected;
        }
        if (value.isObject()) {
            ObjectNode resolved = json.createObjectNode();
            value.fields().forEachRemaining(entry -> resolved.set(entry.getKey(), resolve(entry.getValue(), results, item, referenceBudget)));
            return resolved;
        }
        if (value.isArray()) {
            ArrayNode resolved = json.createArrayNode();
            for (JsonNode child : value) resolved.add(resolve(child, results, item, referenceBudget));
            return resolved;
        }
        return value;
    }

    private JsonNode resolveReference(Reference ref, Map<String, JsonNode> results) {
        JsonNode selected = select(results.get(ref.step()), ref.path());
        if (ref.pluck() == null) return selected;
        require(selected.isArray(), "$pluck 只能用于数组。");
        ArrayNode projected = json.createArrayNode();
        for (JsonNode row : selected) projected.add(select(row, ref.pluck()));
        return projected;
    }

    private static JsonNode select(JsonNode source, JsonPointer pointer) {
        JsonNode value = source == null ? null : source.at(pointer);
        if (value == null || value.isMissingNode()) throw new PipelineError("missing_reference", "管道引用的结果字段不存在。");
        return value;
    }

    private static boolean isReference(JsonNode value) {
        return value.isObject() && (value.has("$ref") || value.has("$pluck"));
    }

    private static boolean isItem(JsonNode value) {
        return value.isObject() && value.has("$item");
    }

    private static JsonPointer itemPointer(JsonNode value) {
        require(value.size() == 1 && value.path("$item").isTextual(), "$item 对象只能包含一个 JSON Pointer 文本字段。");
        return pointer(value.path("$item").textValue());
    }

    private static Reference earlierReference(JsonNode value, Set<String> earlier) {
        Reference reference = reference(value);
        require(earlier.contains(reference.step()), "管道引用只能指向已声明的前序步骤。");
        return reference;
    }

    private static Reference reference(JsonNode value) {
        require(value.path("$ref").isTextual() && value.size() == (value.has("$pluck") ? 2 : 1),
                "引用对象只能包含 $ref 和可选的 $pluck。");
        String ref = value.path("$ref").textValue();
        int separator = ref.indexOf('#');
        require(separator > 0 && separator <= 32 && STEP_ID.matcher(ref.substring(0, separator)).matches(),
                "$ref 格式须为步骤 ID 加 #，再接 JSON Pointer。");
        JsonPointer path = pointer(ref.substring(separator + 1));
        JsonPointer pluck = null;
        if (value.has("$pluck")) {
            require(value.path("$pluck").isTextual(), "$pluck 须为 JSON Pointer 文本。");
            pluck = pointer(value.path("$pluck").textValue());
        }
        return new Reference(ref.substring(0, separator), path, pluck);
    }

    private static JsonPointer pointer(String value) {
        require(value.length() <= 512 && (value.isEmpty() || value.startsWith("/"))
                && !INVALID_POINTER_ESCAPE.matcher(value).find(), "JSON Pointer 无效或超过 512 个字符。");
        return JsonPointer.compile(value);
    }

    private static String matchingText(JsonNode value, Pattern pattern, String error) {
        require(value.isTextual() && pattern.matcher(value.textValue()).matches(), error);
        return value.textValue();
    }

    private JsonNode normalizeMove(JsonNode input) {
        JsonNode body = input.path("body");
        require(body.isObject() && body.size() == 3 && body.has("kind") && body.has("ids") && body.has("folderId"),
                "批量移动的 body 必须明确提供 kind、ids 和 folderId。");
        String kind = body.path("kind").asText();
        require(Set.of("note", "file").contains(kind), "批量移动的 kind 必须是 note 或 file。");
        JsonNode ids = body.path("ids");
        require(ids.isArray(), "批量移动的 ids 必须是数组。");
        if (ids.isEmpty()) throw new PipelineError("empty_selection", "没有查到可移动的条目，未发起审批。");
        require(ids.size() <= MAX_MOVE_IDS, "单次最多移动 500 个条目。");
        Set<Long> distinct = new LinkedHashSet<>();
        for (JsonNode id : ids) distinct.add(itemId(id, false));

        ObjectNode normalized = ((ObjectNode) input).deepCopy();
        ObjectNode normalizedBody = normalized.putObject("body").put("kind", kind);
        ArrayNode normalizedIds = normalizedBody.putArray("ids");
        distinct.forEach(normalizedIds::add);
        JsonNode destination = body.path("folderId");
        if (destination.isNull()) normalizedBody.putNull("folderId");
        else normalizedBody.put("folderId", itemId(destination, true));
        return normalized;
    }

    private static long itemId(JsonNode value, boolean folder) {
        long id = parseId(value);
        if (id > 0 || (folder && id == 0)) return id;
        throw new PipelineError("invalid_input", folder ? "目标 folderId 须为正整数、0 或 null。" : "条目 ID 须为正整数。");
    }

    private static long parseId(JsonNode value) {
        if (nonNegativeInteger(value)) return value.longValue();
        if (value.isTextual() && DECIMAL_ID.matcher(value.textValue()).matches()) {
            try {
                return Long.parseLong(value.textValue());
            } catch (NumberFormatException ignored) {
                // Out-of-range decimal IDs are handled by the same validation below.
            }
        }
        return -1;
    }

    private static void summarize(ObjectNode summary, JsonNode result) {
        JsonNode list = result.isArray() ? result : result.path("list");
        if (!list.isArray()) return;
        summary.put("resultCount", list.size());
        JsonNode total = result.path("total");
        if (nonNegativeInteger(total)) {
            summary.put("total", total.longValue());
            if (total.longValue() > list.size()) summary.put("partial", true);
        }
    }

    private int measure(JsonNode value, int maximum, String code, String message) {
        SizeBudget budget = new SizeBudget(maximum);
        measure(value, budget, code, message);
        return maximum - budget.remaining;
    }

    private void measure(JsonNode value, SizeBudget budget, String code, String message) {
        try {
            json.writeValue(budget, value);
        } catch (IOException error) {
            throw new PipelineError(code, message);
        }
    }

    private static void checkDeadline(long deadline) {
        if (Thread.currentThread().isInterrupted()) throw new PipelineError("interrupted", "管道执行已中断。");
        if (System.currentTimeMillis() >= deadline) throw new PipelineError("deadline", "本轮时间预算不足，管道已停止。");
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new PipelineError("invalid_input", message);
    }

    private Outcome failure(ArrayNode trace, String command, String code, String message) {
        ObjectNode result = json.createObjectNode().put("ok", false).put("code", code).put("error", message);
        if (command != null) result.put("command", command);
        return new Outcome(result, null, trace);
    }

    record Outcome(JsonNode result, AgentAppCommands.Prepared pending, ArrayNode steps) { }

    private record Step(String id, String command, JsonNode input, boolean requiresApproval,
                        boolean paginate, Reference forEach) { }

    private record Reference(String step, JsonPointer path, JsonPointer pluck) { }

    private static final class ReadBudget {
        private int calls = MAX_READ_CALLS;
        private int bytes = MAX_RESULTS_BYTES;
    }

    private static final class PipelineError extends RuntimeException {
        private final String code;

        private PipelineError(String code, String message) {
            super(message);
            this.code = code;
        }
    }

    /** Counts serialized bytes without allocating an expanded copy of repeated references. */
    private static final class SizeBudget extends OutputStream {
        private int remaining;

        private SizeBudget(int remaining) {
            this.remaining = remaining;
        }

        @Override
        public void write(int value) throws IOException {
            consume(1);
        }

        @Override
        public void write(byte[] buffer, int offset, int length) throws IOException {
            consume(length);
        }

        private void consume(int length) throws IOException {
            if (length > remaining) throw new IOException("JSON size limit exceeded");
            remaining -= length;
        }
    }
}
