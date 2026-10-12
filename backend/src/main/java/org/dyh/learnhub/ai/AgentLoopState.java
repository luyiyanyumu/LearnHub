package org.dyh.learnhub.ai;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Per-request loop bookkeeping. It retains no state beyond one agent request. */
final class AgentLoopState {
    static final int MAX_ROUNDS = 16;
    static final int MAX_TOOL_CALLS = 40;
    static final int MAX_CALLS_PER_ROUND = 8;
    private static final int MAX_ARGUMENT_CHARS = 512_000;

    private final ObjectMapper json;
    private final Map<String, Integer> executions = new HashMap<>();
    private final Map<String, Integer> awaitingResults = new HashMap<>();
    private final Set<String> mutationSignatures = new HashSet<>();
    private final Set<String> successfulSignatures = new HashSet<>();
    private final Map<String, JsonNode> replayable = new HashMap<>();
    private final Set<String> toolsAwaitingUse = new LinkedHashSet<>();
    private List<Map<String, Object>> plan = List.of();
    private int rounds;
    private int toolCalls;
    private int roundCalls;
    private int executedCalls;
    private int replayedCalls;
    private int duplicateCalls;
    private int noProgressRounds;
    private int completionReminders;
    private boolean roundProgress;
    private boolean roundClosed = true;
    private String stopReason = "";

    AgentLoopState(ObjectMapper json) { this.json = java.util.Objects.requireNonNull(json); }

    boolean beginRound() {
        if (!stopReason.isEmpty()) return false;
        if (rounds >= MAX_ROUNDS) { stop("round_limit"); return false; }
        rounds++;
        roundCalls = 0;
        roundProgress = false;
        roundClosed = false;
        return true;
    }

    boolean endRound() {
        if (!roundClosed) {
            noProgressRounds = roundProgress ? 0 : noProgressRounds + 1;
            roundClosed = true;
            if (toolCalls >= MAX_TOOL_CALLS) stop("tool_limit");
            else if (noProgressRounds >= 3) stop("stalled");
            else if (rounds >= MAX_ROUNDS) stop("round_limit");
        }
        return stopReason.isEmpty();
    }

    /** Null means execute exactly this call; other values are a rejection or a replayed result. */
    JsonNode intercept(String name, String argsRaw, boolean mutation) {
        toolCalls++;
        roundCalls++;
        if (toolCalls > MAX_TOOL_CALLS) stop("tool_limit");
        if (!stopReason.isEmpty()) return error(stopReason, "本轮智能体执行已停止：" + stopReason);
        if (roundCalls > MAX_CALLS_PER_ROUND) return error("round_tool_limit", "单轮最多执行 8 次工具调用，请在下一轮根据已有结果继续");
        final String signature;
        try { signature = signature(name, argsRaw); }
        catch (IllegalArgumentException invalid) { return error("invalid_tool_arguments", invalid.getMessage()); }

        JsonNode replay = replayable.get(signature);
        if (replay != null) { replayedCalls++; return replay.deepCopy(); }
        int count = executions.getOrDefault(signature, 0);
        if (count >= 2) {
            duplicateCalls++;
            return error("duplicate_tool_call", "相同工具与参数已实际调用两次；请利用已有结果、调整参数或说明无法继续的原因");
        }
        executions.put(signature, count + 1);
        awaitingResults.merge(signature, 1, Integer::sum);
        if (mutation) mutationSignatures.add(signature);
        executedCalls++;
        return null;
    }

    void record(String name, String argsRaw, JsonNode result) {
        final String signature;
        try { signature = signature(name, argsRaw); }
        catch (IllegalArgumentException invalid) { return; }
        int awaiting = awaitingResults.getOrDefault(signature, 0);
        if (awaiting == 0) return;
        if (awaiting == 1) awaitingResults.remove(signature); else awaitingResults.put(signature, awaiting - 1);
        // An actual attempt fulfills the reminder even if the new tool reports an error.
        toolsAwaitingUse.remove(name);
        if (result == null || result.isNull()) return;
        boolean success = successful(result);
        if (result.path("staged").asBoolean() || (mutationSignatures.contains(signature) && success))
            replayable.putIfAbsent(signature, result.deepCopy());
        if (success && successfulSignatures.add(signature) && !"update_plan".equals(name)) roundProgress = true;
        if (success && "evolve_tool".equals(name)) {
            String pendingName = result.path("registered").asBoolean() ? result.path("name").asText()
                    : "reuse_existing".equals(result.path("decision").asText()) ? result.path("existing_tool").asText() : "";
            if (!pendingName.isBlank()) toolsAwaitingUse.add(pendingName);
        }
    }

    JsonNode updatePlan(JsonNode args) {
        if (args == null || !args.isObject()) return error("invalid_plan", "计划参数必须为对象");
        for (var fields = args.fieldNames(); fields.hasNext();) {
            if (!Set.of("explanation", "plan").contains(fields.next())) return error("invalid_plan", "计划参数包含未知字段");
        }
        if (args.has("explanation") && !args.path("explanation").isTextual()) return error("invalid_plan", "explanation 必须为文本");
        JsonNode proposed = args.path("plan");
        if (!proposed.isArray() || proposed.size() > 6) return error("invalid_plan", "计划必须为最多 6 个步骤的数组");
        List<Map<String, Object>> replacement = new ArrayList<>();
        Set<String> unique = new HashSet<>();
        int inProgress = 0;
        for (JsonNode item : proposed) {
            if (!item.isObject() || item.size() != 2 || !item.path("step").isTextual() || !item.path("status").isTextual())
                return error("invalid_plan", "每个计划步骤仅包含文本 step 与 status");
            String step = item.path("step").asText(), status = item.path("status").asText();
            if (step.isBlank() || step.codePointCount(0, step.length()) > 160) return error("invalid_plan", "步骤说明需要 1 至 160 个字符");
            if (!unique.add(step)) return error("invalid_plan", "计划步骤不能重复");
            if (!Set.of("pending", "in_progress", "completed").contains(status)) return error("invalid_plan", "未知计划状态：" + status);
            if ("in_progress".equals(status) && ++inProgress > 1) return error("invalid_plan", "计划最多有一个正在执行的步骤");
            replacement.add(Map.of("step", step, "status", status));
        }
        // Only replace the current plan after validating every proposed entry.
        plan = List.copyOf(replacement);
        ObjectNode result = json.createObjectNode().put("ok", true);
        result.set("plan", json.valueToTree(plan));
        return result;
    }

    List<Map<String, Object>> planSnapshot() { return List.copyOf(plan); }

    boolean hasUnfinishedPlan() {
        return plan.stream().anyMatch(item -> !"completed".equals(item.get("status")));
    }

    boolean hasUnfinishedWork() { return hasUnfinishedPlan() || !toolsAwaitingUse.isEmpty(); }

    String completionReminder(boolean pendingActions) {
        if (pendingActions || !stopReason.isEmpty() || completionReminders >= 2
                || (!hasUnfinishedPlan() && toolsAwaitingUse.isEmpty())) return null;
        completionReminders++;
        StringBuilder message = new StringBuilder("结束前请核对本轮任务：");
        if (hasUnfinishedPlan()) message.append("计划仍有未完成步骤，请继续执行并据实更新状态；若已无法继续，明确说明原因和未完成部分。");
        if (!toolsAwaitingUse.isEmpty()) message.append("刚注册或复用的工具尚未实际调用：")
                .append(String.join("、", toolsAwaitingUse)).append("。请用它继续用户原任务，注册成功不等于原任务完成。");
        return message.toString();
    }

    Map<String, Object> summary() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("rounds", rounds); result.put("toolCalls", toolCalls); result.put("executedCalls", executedCalls);
        result.put("replayedCalls", replayedCalls); result.put("duplicateCalls", duplicateCalls);
        result.put("noProgressRounds", noProgressRounds); result.put("planSteps", plan.size());
        result.put("completedSteps", plan.stream().filter(item -> "completed".equals(item.get("status"))).count());
        result.put("awaitingToolCount", toolsAwaitingUse.size()); result.put("completionReminders", completionReminders);
        result.put("stopReason", stopReason);
        return Map.copyOf(result);
    }

    String stopReason() { return stopReason; }
    void stop(String reason) { if (stopReason.isEmpty() && reason != null && !reason.isBlank()) stopReason = reason; }

    private boolean successful(JsonNode result) {
        if (result.has("ok") && (!result.path("ok").isBoolean() || !result.path("ok").asBoolean())) return false;
        return !result.hasNonNull("error") || (result.path("error").isTextual() && result.path("error").asText().isBlank());
    }

    private ObjectNode error(String code, String message) { return json.createObjectNode().put("ok", false).put("code", code).put("error", message); }

    private String signature(String name, String argsRaw) {
        if (name == null || name.isBlank() || name.length() > 128 || name.chars().anyMatch(Character::isWhitespace))
            throw new IllegalArgumentException("工具名称无效");
        if (argsRaw == null || argsRaw.isBlank() || argsRaw.length() > MAX_ARGUMENT_CHARS)
            throw new IllegalArgumentException("工具参数必须为有效且有界的 JSON 对象");
        try {
            JsonNode args = json.reader().with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                    .with(JsonParser.Feature.STRICT_DUPLICATE_DETECTION).readTree(argsRaw);
            if (args == null || !args.isObject()) throw new IllegalArgumentException("工具参数必须为 JSON 对象");
            // Python dictionaries preserve insertion order. Do not collapse distinct inputs or
            // definitions for generated tools merely because their JSON objects share the same keys.
            boolean ordered = name.startsWith("adaptive_") || Set.of("evolve_tool", "test_adaptive_tool").contains(name);
            return name + "\n" + canonical(args, 0, ordered);
        } catch (IllegalArgumentException invalid) { throw invalid; }
        catch (Exception invalid) { throw new IllegalArgumentException("工具参数不是有效 JSON 对象，请修正后重试"); }
    }

    private JsonNode canonical(JsonNode node, int depth, boolean ordered) {
        if (depth > 64) throw new IllegalArgumentException("工具参数嵌套过深");
        if (node.isObject()) {
            ObjectNode result = json.createObjectNode(); List<String> fields = new ArrayList<>();
            node.fieldNames().forEachRemaining(fields::add); if (!ordered) fields.sort(String::compareTo);
            for (String field : fields) result.set(field, canonical(node.get(field), depth + 1, ordered));
            return result;
        }
        if (node.isArray()) {
            ArrayNode result = json.createArrayNode();
            for (JsonNode item : node) result.add(canonical(item, depth + 1, ordered));
            return result;
        }
        return node.deepCopy();
    }
}
