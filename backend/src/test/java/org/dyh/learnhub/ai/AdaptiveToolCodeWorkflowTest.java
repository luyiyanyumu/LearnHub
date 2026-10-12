package org.dyh.learnhub.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class AdaptiveToolCodeWorkflowTest {
    private final ObjectMapper json = new ObjectMapper();
    private static final String SOURCE = "def run(input):\n    return {'total': sum(input['values'])}\n";

    @Test void codeReceivesResolvedFreshInputAndItsOutputCanFeedAPendingWrite() throws Exception {
        ObjectNode definition = definition();
        definition.withArray("steps").insert(0, json.readTree("""
                {"id":"read","op":"call","tool":"get_note","arguments":{"note_id":{"$ref":"input.note_id"}}}
                """));
        ((ObjectNode) definition.path("steps").get(1)).set("input", json.readTree("{\"$ref\":\"steps.read\"}"));
        definition.withArray("steps").add(json.readTree("""
                {"id":"write","op":"call","tool":"edit_note","arguments":{"value":{"$ref":"steps.calculate.total"}}}
                """));
        definition.set("output", json.readTree("{\"$ref\":\"steps.write\"}"));
        AdaptiveToolWorkflow engine = new AdaptiveToolWorkflow(json, (source, input) -> {
            assertEquals(SOURCE, source);
            assertEquals(9, input.path("values").get(0).asInt());
            return json.createObjectNode().put("ok", true).set("output", json.createObjectNode().put("total", 13));
        });
        engine.validate(definition, Set.of("get_note", "edit_note"), Set.of("edit_note"));
        List<String> calls = new ArrayList<>();
        JsonNode result = engine.execute(definition, json.createObjectNode().put("note_id", 7), (name, arguments) -> {
            calls.add(name);
            if (name.equals("get_note")) return json.createObjectNode().set("values", json.createArrayNode().add(9).add(4));
            assertEquals(13, arguments.path("value").asInt());
            return json.createObjectNode().put("ok", true).put("staged", true).put("action_id", 4);
        });
        assertEquals(List.of("get_note", "edit_note"), calls);
        assertTrue(result.path("staged").asBoolean());
        assertFalse(result.has("output"));
    }

    @Test void failedCodeStopsBeforeWritesAndReportsItsStepWithoutInventingSuccess() {
        ObjectNode definition = definition();
        definition.withArray("steps").add(json.createObjectNode().put("id", "write").put("op", "call")
                .put("tool", "edit_note").set("arguments", json.createObjectNode()));
        AtomicInteger calls = new AtomicInteger();
        AdaptiveToolWorkflow engine = new AdaptiveToolWorkflow(json, (source, input) -> json.createObjectNode()
                .put("ok", false).put("code", "code_timeout").put("error", "execution timed out"));
        JsonNode result = engine.execute(definition, json.createObjectNode(), (name, args) -> {
            calls.incrementAndGet(); return json.createObjectNode();
        });
        assertFalse(result.path("ok").asBoolean());
        assertEquals("calculate", result.path("stepId").asText());
        assertEquals("code_timeout", result.path("code").asText());
        assertEquals(0, calls.get());
    }

    @Test void unconfiguredExecutorFailsClosedButDefinitionsRemainInspectable() {
        AdaptiveToolWorkflow engine = new AdaptiveToolWorkflow(json);
        assertDoesNotThrow(() -> engine.validate(definition(), Set.of()));
        JsonNode result = engine.execute(definition(), json.createObjectNode(), this::unexpectedCall);
        assertFalse(result.path("ok").asBoolean());
        assertEquals("code_runner_unavailable", result.path("code").asText());
    }

    @Test void codeSyntaxAllowsOnlyPythonAndLiteralBoundedSourceWithBackwardReferences() {
        AdaptiveToolWorkflow engine = new AdaptiveToolWorkflow(json);
        for (String language : List.of("bash", "powershell", "javascript", "")) {
            ObjectNode invalid = definition();
            ((ObjectNode) invalid.path("steps").get(0)).put("language", language);
            assertThrows(IllegalArgumentException.class, () -> engine.validate(invalid, Set.of()));
        }
        for (String source : List.of(" ", "x".repeat(CodeExecutionService.MAX_SOURCE_CHARS + 1))) {
            ObjectNode invalid = definition();
            ((ObjectNode) invalid.path("steps").get(0)).put("source", source);
            assertThrows(IllegalArgumentException.class, () -> engine.validate(invalid, Set.of()));
        }
        ObjectNode missingInput = definition();
        ((ObjectNode) missingInput.path("steps").get(0)).remove("input");
        assertThrows(IllegalArgumentException.class, () -> engine.validate(missingInput, Set.of()));
        ObjectNode unknownField = definition();
        ((ObjectNode) unknownField.path("steps").get(0)).put("cwd", "private-directory");
        assertThrows(IllegalArgumentException.class, () -> engine.validate(unknownField, Set.of()));
        ObjectNode forward = definition();
        ((ObjectNode) forward.path("steps").get(0)).set("input", json.createObjectNode().put("$ref", "steps.calculate"));
        assertThrows(IllegalArgumentException.class, () -> engine.validate(forward, Set.of()));
    }

    @Test void codeCannotFollowABuiltinWrite() {
        ObjectNode definition = definition();
        definition.withArray("steps").insert(0, json.createObjectNode().put("id", "write").put("op", "call")
                .put("tool", "edit_note").set("arguments", json.createObjectNode()));
        assertThrows(IllegalArgumentException.class, () -> new AdaptiveToolWorkflow(json).validate(
                definition, Set.of("edit_note"), Set.of("edit_note")));
    }

    @Test void reportsActualCodeLifecycleAndPreservesBoundedDiagnosticsForTests() {
        List<String> events = new ArrayList<>();
        AdaptiveToolWorkflow engine = new AdaptiveToolWorkflow(json, (source, input) -> json.createObjectNode()
                .put("ok", true).put("elapsedMs", 12).put("stdout", "synthetic log")
                .put("sourceFingerprint", "canonical-hash").set("output", json.createObjectNode().put("total", 3)));
        JsonNode result = engine.execute(definition(), json.createObjectNode(), this::unexpectedCall, (event, data) -> {
            events.add(event);
            assertEquals("calculate", data.path("id").asText());
            if (event.equals("finish")) {
                assertEquals(12, data.path("elapsedMs").asInt());
                assertEquals("canonical-hash", data.path("sourceFingerprint").asText());
                assertFalse(data.has("source"));
            }
        });
        assertEquals(List.of("start", "finish"), events);
        assertEquals(3, result.path("steps").path("calculate").path("total").asInt());
        assertFalse(result.path("steps").path("calculate").has("stdout"));
    }

    @Test void exhaustedCallerBudgetBlocksCodeBeforeExecutionButDisconnectedFinishDoesNot() {
        AtomicInteger calls = new AtomicInteger();
        AdaptiveToolWorkflow engine = new AdaptiveToolWorkflow(json, (source, input) -> {
            calls.incrementAndGet(); return json.createObjectNode().put("ok", true).putNull("output");
        });
        assertThrows(IllegalStateException.class, () -> engine.execute(definition(), json.createObjectNode(),
                this::unexpectedCall, (event, data) -> { throw new IllegalStateException("round budget exceeded"); }));
        assertEquals(0, calls.get());
        JsonNode result = engine.execute(definition(), json.createObjectNode(), this::unexpectedCall,
                (event, data) -> { if (event.equals("finish")) throw new IllegalStateException("listener disconnected"); });
        assertTrue(result.path("ok").asBoolean());
        assertTrue(result.path("output").isNull());
        assertEquals(1, calls.get());
    }

    @Test void invalidOrOversizedRunnerOutputCannotReachLaterSteps() {
        for (JsonNode response : List.of(json.createObjectNode().put("ok", true),
                json.createObjectNode().put("ok", "true").put("output", 2),
                json.createObjectNode().put("ok", true).put("output", "x".repeat(CodeExecutionService.MAX_JSON_CHARS)))) {
            JsonNode result = new AdaptiveToolWorkflow(json, (source, input) -> response)
                    .execute(definition(), json.createObjectNode(), this::unexpectedCall);
            assertFalse(result.path("ok").asBoolean());
        }
    }

    private ObjectNode definition() {
        ObjectNode definition = json.createObjectNode().put("name", "adaptive_sum").put("description", "合成求和工具");
        definition.set("parameters", json.createObjectNode().put("type", "object"));
        definition.putArray("steps").add(json.createObjectNode().put("id", "calculate").put("op", "code")
                .put("language", "python").put("source", SOURCE).set("input", json.createObjectNode().put("$ref", "input")));
        definition.set("output", json.createObjectNode().put("$ref", "steps.calculate"));
        return definition;
    }

    private JsonNode unexpectedCall(String name, JsonNode input) { throw new AssertionError("unexpected built-in " + name); }
}
