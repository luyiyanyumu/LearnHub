package org.dyh.learnhub.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class AgentLoopStateTest {
    private final ObjectMapper json = new ObjectMapper();
    private final AgentLoopState state = new AgentLoopState(json);

    @Test void recursivelyCanonicalizesObjectKeysButRetainsArrayOrderAndNumberTypes() {
        assertTrue(state.beginRound());
        String first = "{\"filter\":{\"kind\":\"synthetic\",\"limit\":2},\"ids\":[1,2]}";
        String reordered = "{ \"ids\":[1,2], \"filter\":{\"limit\":2,\"kind\":\"synthetic\"}}";
        assertNull(state.intercept("search_notes", first, false));
        state.record("search_notes", first, ok());
        assertNull(state.intercept("search_notes", reordered, false));
        state.record("search_notes", reordered, ok());
        assertEquals("duplicate_tool_call", state.intercept("search_notes", first, false).path("code").asText());
        assertNull(state.intercept("search_notes", "{\"filter\":{\"kind\":\"synthetic\",\"limit\":2},\"ids\":[2,1]}", false));
        assertNull(state.intercept("search_notes", "{\"filter\":{\"kind\":\"synthetic\",\"limit\":2.0},\"ids\":[1,2]}", false));
        assertNull(state.intercept("Search_notes", first, false));
        assertEquals(5, state.summary().get("executedCalls"));
    }

    @Test void rejectsMalformedParametersRatherThanExecutingCorrectedOrTruncatedInput() {
        state.beginRound();
        for (String args : List.of("{bad}", "{} {}", "{} trailing", "[]", "null", "{\"x\":1,\"x\":2}", "")) {
            JsonNode result = state.intercept("unknown_tool", args, false);
            assertFalse(result.path("ok").asBoolean()); assertEquals("invalid_tool_arguments", result.path("code").asText());
        }
        assertEquals("invalid_tool_arguments", state.intercept(" get_note", "{}", false).path("code").asText());
        assertEquals(0, state.summary().get("executedCalls")); assertEquals(8, state.summary().get("toolCalls"));
        state.endRound(); state.beginRound();
        assertEquals("invalid_tool_arguments", state.intercept("get_note", null, false).path("code").asText());
        // Unknown but well-formed names are sent to the normal dispatcher, never rewritten to a known tool.
        assertNull(state.intercept("unknown_tool", "{}", false));
    }

    @Test void stagedResultIsAlwaysReplayedAndCopiesCannotMutateStoredApproval() {
        state.beginRound(); String args = "{\"note_id\":1,\"text\":\"synthetic\"}";
        assertNull(state.intercept("edit_note", args, false));
        ObjectNode staged = ok().put("staged", true).put("pendingActionId", "synthetic-approval");
        state.record("edit_note", args, staged); staged.put("pendingActionId", "changed-after-record");
        ObjectNode replay = (ObjectNode) state.intercept("edit_note", "{\"text\":\"synthetic\",\"note_id\":1}", false);
        assertEquals("synthetic-approval", replay.path("pendingActionId").asText());
        replay.put("pendingActionId", "changed-by-consumer");
        assertEquals("synthetic-approval", state.intercept("edit_note", args, true).path("pendingActionId").asText());
        assertEquals(1, state.summary().get("executedCalls")); assertEquals(2, state.summary().get("replayedCalls"));
    }

    @Test void successfulMutationReplaysButFailedMutationMayActuallyRetryOnce() {
        state.beginRound(); String args = "{\"title\":\"synthetic note\"}";
        assertNull(state.intercept("create_note", args, true));
        state.record("create_note", args, failure());
        assertNull(state.intercept("create_note", args, true));
        state.record("create_note", args, ok().put("id", 12));
        assertEquals(12, state.intercept("create_note", args, true).path("id").asInt());
        assertEquals(2, state.summary().get("executedCalls"));
    }

    @Test void successfulReadMayRunTwiceButIdenticalSuccessIsNotNewProgress() {
        state.beginRound(); invoke("get_note", "{\"id\":1}", false, ok()); assertTrue(state.endRound());
        state.beginRound(); invoke("get_note", "{\"id\":1}", false, ok()); assertTrue(state.endRound());
        assertEquals(1, state.summary().get("noProgressRounds"));
        for (int round = 0; round < 2; round++) {
            state.beginRound(); JsonNode blocked = state.intercept("get_note", "{\"id\":1}", false);
            assertEquals("duplicate_tool_call", blocked.path("code").asText());
            state.record("get_note", "{\"id\":1}", blocked);
            assertEquals(round == 0, state.endRound());
        }
        assertEquals("stalled", state.stopReason()); assertFalse(state.beginRound());
    }

    @Test void newSuccessfulSignatureResetsTheNoProgressStreak() {
        for (int round = 0; round < 2; round++) {
            state.beginRound(); invoke("get_note", "{\"id\":1}", false, failure()); assertTrue(state.endRound());
        }
        state.beginRound(); invoke("get_note", "{\"id\":2}", false, ok()); assertTrue(state.endRound());
        assertEquals(0, state.summary().get("noProgressRounds"));
        state.beginRound(); state.record("unexecuted_tool", "{}", ok()); assertTrue(state.endRound());
        assertEquals(1, state.summary().get("noProgressRounds"));
    }

    @Test void changingPlansWithoutDoingWorkCannotResetStallDetection() {
        for (int round = 0; round < 3; round++) {
            assertTrue(state.beginRound());
            String args = "{\"plan\":[{\"step\":\"synthetic step " + round + "\",\"status\":\"in_progress\"}]}";
            assertNull(state.intercept("update_plan", args, false));
            state.record("update_plan", args, state.updatePlan(read(args)));
            assertEquals(round < 2, state.endRound());
        }
        assertEquals("stalled", state.stopReason());
    }

    @Test void perRoundLimitResetsButTotalBudgetStopsAfterFortyCalls() {
        state.beginRound();
        for (int i = 0; i < 8; i++) invoke("search_notes", "{\"page\":" + i + "}", false, ok());
        assertEquals("round_tool_limit", state.intercept("search_notes", "{\"page\":8}", false).path("code").asText());
        assertEquals("", state.stopReason()); assertTrue(state.endRound()); assertTrue(state.beginRound());
        assertNull(state.intercept("search_notes", "{\"page\":8}", false));

        AgentLoopState budget = new AgentLoopState(json);
        for (int round = 0; round < 5; round++) {
            assertTrue(budget.beginRound());
            for (int call = 0; call < 8; call++) {
                String args = "{\"id\":" + (round * 8 + call) + "}";
                assertNull(budget.intercept("get_note", args, false)); budget.record("get_note", args, ok());
            }
            assertEquals(round < 4, budget.endRound());
        }
        assertEquals(40, budget.summary().get("executedCalls")); assertEquals("tool_limit", budget.stopReason());
        assertFalse(budget.beginRound());
        assertEquals("tool_limit", budget.intercept("get_note", "{\"id\":99}", false).path("code").asText());
        assertEquals(40, budget.summary().get("executedCalls"));
    }

    @Test void roundLimitStopsEvenWhenEveryRoundMakesNewProgress() {
        for (int round = 0; round < AgentLoopState.MAX_ROUNDS; round++) {
            assertTrue(state.beginRound()); invoke("search_notes", "{\"page\":" + round + "}", false, ok());
            assertEquals(round < AgentLoopState.MAX_ROUNDS - 1, state.endRound());
        }
        assertEquals("round_limit", state.stopReason()); assertFalse(state.beginRound());
        assertEquals(16, state.summary().get("rounds"));
    }

    @Test void planUpdatesAreAtomicAndSnapshotsCannotAlterState() {
        assertTrue(state.updatePlan(read("{\"plan\":[{\"step\":\"检索合成资料\",\"status\":\"in_progress\"},{\"step\":\"核对结果\",\"status\":\"pending\"}]}")).path("ok").asBoolean());
        List<Map<String, Object>> accepted = state.planSnapshot();
        for (String invalid : List.of(
                "{\"plan\":[{\"step\":\"替换\",\"status\":\"completed\"},{\"step\":\"错误\",\"status\":\"unknown\"}]}",
                "{\"plan\":[{\"step\":\"A\",\"status\":\"in_progress\"},{\"step\":\"B\",\"status\":\"in_progress\"}]}",
                "{\"plan\":[{\"step\":\"\",\"status\":\"pending\"}]}",
                "{\"plan\":[{\"step\":\"A\",\"status\":\"pending\",\"unexpected\":true}]}",
                "{\"explanation\":false,\"plan\":[]}", "{\"plan\":null}", "{\"plan\":[],\"unexpected\":true}")) {
            assertEquals("invalid_plan", state.updatePlan(read(invalid)).path("code").asText()); assertEquals(accepted, state.planSnapshot());
        }
        assertThrows(UnsupportedOperationException.class, () -> accepted.add(Map.of()));
        assertThrows(UnsupportedOperationException.class, () -> accepted.get(0).put("status", "completed"));
        assertTrue(state.hasUnfinishedPlan()); assertTrue(state.updatePlan(read("{\"plan\":[]}")).path("ok").asBoolean());
        assertFalse(state.hasUnfinishedPlan()); assertNull(state.completionReminder(false));
    }

    @Test void validatesPlanLengthAndUnicodeCharacterBudget() {
        ObjectNode args = json.createObjectNode(); var plan = args.putArray("plan");
        for (int i = 0; i < 6; i++) plan.addObject().put("step", "合成步骤" + i).put("status", "pending");
        assertTrue(state.updatePlan(args).path("ok").asBoolean());
        plan.addObject().put("step", "第七步").put("status", "pending"); assertFalse(state.updatePlan(args).path("ok").asBoolean());
        assertEquals(6, state.planSnapshot().size());
        args = json.createObjectNode(); args.putArray("plan").addObject().put("step", "学".repeat(160)).put("status", "pending");
        assertTrue(state.updatePlan(args).path("ok").asBoolean());
        ((ObjectNode) args.path("plan").get(0)).put("step", "学".repeat(161)); assertFalse(state.updatePlan(args).path("ok").asBoolean());
    }

    @Test void completionReminderIsBoundedAndPendingApprovalDoesNotConsumeIt() {
        assertNull(state.completionReminder(false));
        state.updatePlan(read("{\"plan\":[{\"step\":\"合成检查\",\"status\":\"pending\"}]}"));
        assertNull(state.completionReminder(true)); assertEquals(0, state.summary().get("completionReminders"));
        assertNotNull(state.completionReminder(false)); assertNotNull(state.completionReminder(false));
        assertNull(state.completionReminder(false)); assertEquals(2, state.summary().get("completionReminders"));
    }

    @Test void successfulNewToolMustBeTriedBeforeRegistrationCountsAsTaskCompletion() {
        state.beginRound();
        invoke("evolve_tool", "{\"definition\":{\"name\":\"adaptive_synthetic\"}}", true,
                ok().put("registered", true).put("name", "adaptive_synthetic"));
        assertTrue(state.hasUnfinishedWork()); assertFalse(state.hasUnfinishedPlan());
        assertTrue(state.completionReminder(false).contains("adaptive_synthetic"));
        invoke("adaptive_synthetic", "{\"text\":\"synthetic\"}", false, failure());
        assertFalse(state.hasUnfinishedWork());
        assertNull(state.completionReminder(false)); assertEquals(0, state.summary().get("awaitingToolCount"));
    }

    @Test void reusedToolIsAlsoTrackedButFailedRegistrationIsNot() {
        state.beginRound();
        invoke("evolve_tool", "{\"definition\":{\"name\":\"adaptive_failed\"}}", false,
                failure().put("name", "adaptive_failed"));
        assertNull(state.completionReminder(false));
        invoke("evolve_tool", "{\"definition\":{\"name\":\"adaptive_alias\"}}", false,
                ok().put("registered", false).put("decision", "reuse_existing").put("existing_tool", "adaptive_existing"));
        assertTrue(state.completionReminder(false).contains("adaptive_existing"));
        invoke("adaptive_existing", "{}", false, ok()); assertNull(state.completionReminder(false));
    }

    @Test void objectErrorsAreNotProgressAndExplicitStopKeepsFirstReason() {
        for (int round = 0; round < 3; round++) {
            state.beginRound(); ObjectNode result = json.createObjectNode(); result.putObject("error").put("message", "synthetic failure");
            invoke("get_note", "{\"id\":" + round + "}", false, result); state.endRound();
        }
        assertEquals("stalled", state.stopReason()); state.stop("other_reason"); assertEquals("stalled", state.stopReason());
        assertNull(state.completionReminder(false));
    }

    @Test void summaryContainsNoToolParametersSourceCodeOrPlanText() {
        String sensitive = "synthetic-marker-that-must-not-appear-in-summary";
        state.beginRound(); invoke("tool_synthetic", "{\"source\":\"" + sensitive + "\"}", false, ok());
        ObjectNode args = json.createObjectNode(); args.putArray("plan").addObject().put("step", sensitive).put("status", "pending");
        state.updatePlan(args); String summary = json.valueToTree(state.summary()).toString();
        assertFalse(summary.contains(sensitive)); assertFalse(summary.contains("tool_synthetic"));
        assertEquals("", state.stopReason()); assertTrue(state.summary().containsKey("rounds"));
    }

    @Test void generatedToolInputsAndDefinitionsKeepPythonObservableObjectOrder() {
        for (String name : List.of("adaptive_synthetic_keys", "evolve_tool", "test_adaptive_tool")) {
            AgentLoopState loop = new AgentLoopState(json); loop.beginRound();
            String forward = "{\"input\":{\"first\":1,\"second\":2}}";
            String reverse = "{\"input\":{\"second\":2,\"first\":1}}";
            assertNull(loop.intercept(name, forward, true)); loop.record(name, forward, ok().put("output", "first,second"));
            assertNull(loop.intercept(name, reverse, true)); loop.record(name, reverse, ok().put("output", "second,first"));
            assertEquals("first,second", loop.intercept(name, "{ \"input\" : {\"first\":1, \"second\":2} }", true).path("output").asText());
            assertEquals("second,first", loop.intercept(name, reverse, true).path("output").asText());
            assertEquals(2, loop.summary().get("executedCalls"));
        }
    }

    private void invoke(String name, String args, boolean mutation, JsonNode result) {
        assertNull(state.intercept(name, args, mutation)); state.record(name, args, result);
    }
    private ObjectNode ok() { return json.createObjectNode().put("ok", true); }
    private ObjectNode failure() { return json.createObjectNode().put("ok", false).put("error", "synthetic failure"); }
    private JsonNode read(String source) {
        try { return json.readTree(source); } catch (Exception e) { throw new AssertionError(e); }
    }
}
