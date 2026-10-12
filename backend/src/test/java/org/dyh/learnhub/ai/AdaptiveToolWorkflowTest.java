package org.dyh.learnhub.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class AdaptiveToolWorkflowTest {
    private final ObjectMapper json = new ObjectMapper();
    private final AdaptiveToolWorkflow engine = new AdaptiveToolWorkflow(json);
    private final Set<String> reads = Set.of("get_note", "query_notes");
    private final Set<String> all = Set.of("get_note", "query_notes", "edit_note");

    @Test void readsFreshNoteAndBuildsAReusableContentsWithoutCapturingPriorContent() throws Exception {
        JsonNode definition = definition("""
                [{"id":"read","op":"call","tool":"get_note","arguments":{"note_id":{"$ref":"input.note_id"}}},
                 {"id":"lines","op":"transform","kind":"split","value":{"$ref":"steps.read.content"},"separator":"\\n"},
                 {"id":"heads","op":"transform","kind":"filter","value":{"$ref":"steps.lines"},"operator":"contains","compare":"# "},
                 {"id":"toc","op":"transform","kind":"join","value":{"$ref":"steps.heads"},"separator":"\\n"},
                 {"id":"format","op":"transform","kind":"template","text":"目录\\n{{steps.toc}}"}]
                """, "{\"$ref\":\"steps.format\"}");
        ((ObjectNode) definition).set("parameters", read("""
                {"type":"object","properties":{"note_id":{"type":"integer","minimum":1}},"required":["note_id"],"additionalProperties":false}
                """));
        engine.validate(definition, reads);
        AtomicInteger calls = new AtomicInteger();
        JsonNode first = engine.execute(definition, read("{\"note_id\":3}"), (tool, args) -> {
            calls.incrementAndGet();
            assertEquals("get_note", tool);
            assertEquals(3, args.path("note_id").asInt());
            return value("content", "# 原标题\n正文\n## 次标题");
        });
        JsonNode updated = engine.execute(definition, read("{\"note_id\":4}"), (tool, args) -> {
            calls.incrementAndGet();
            assertEquals(4, args.path("note_id").asInt());
            return value("content", "# 新标题\n新正文");
        });
        assertEquals("目录\n# 原标题\n## 次标题", first.path("output").asText());
        assertEquals("目录\n# 新标题", updated.path("output").asText());
        assertEquals(2, calls.get());
        assertTrue(updated.path("ok").asBoolean());
        assertEquals(5, updated.path("steps").size());
    }

    @Test void composesStructuredSelectionFilteringOrderingAndStableUniqueness() throws Exception {
        JsonNode definition = definition("""
                [{"id":"eligible","op":"transform","kind":"filter","value":{"$ref":"input.items"},"field":"score","operator":"gt","compare":2},
                 {"id":"sorted","op":"transform","kind":"sort","value":{"$ref":"steps.eligible"},"field":"score","descending":true},
                 {"id":"unique","op":"transform","kind":"unique","value":{"$ref":"steps.sorted"},"field":"name"},
                 {"id":"picked","op":"transform","kind":"pick","value":{"$ref":"steps.unique"},"fields":["name","score"]},
                 {"id":"count","op":"transform","kind":"length","value":{"$ref":"steps.picked"}}]
                """, "{\"items\":{\"$ref\":\"steps.picked\"},\"count\":{\"$ref\":\"steps.count\"}}");
        JsonNode result = engine.execute(definition, read("""
                {"items":[{"name":"A","score":3,"ignore":"x"},{"name":"B","score":6},{"name":"A","score":7},{"name":"C","score":2}]}
                """), this::unexpectedCall);
        assertEquals(read("[{\"name\":\"A\",\"score\":7},{\"name\":\"B\",\"score\":6}]"), result.path("output").path("items"));
        assertEquals(2, result.path("output").path("count").asInt());
    }

    @Test void referencesResolveRecursivelyWithoutEvaluatingSourceTextOrChangingInputs() throws Exception {
        JsonNode definition = definition("""
                [{"id":"read","op":"call","tool":"get_note","arguments":{"a":{"$ref":"input.payload"},"nested":[{"$ref":"input.payload.n"},{"literal":"$ref input.secret"}]}}]
                """, "{\"$ref\":\"steps.read\"}");
        engine.validate(definition, reads);
        JsonNode input = read("{\"payload\":{\"n\":null},\"secret\":\"private\"}");
        JsonNode result = engine.execute(definition, input, (tool, args) -> {
            assertTrue(args.path("nested").get(0).isNull());
            assertEquals("$ref input.secret", args.path("nested").get(1).path("literal").asText());
            ((ObjectNode) args.path("a")).put("n", "callback mutation");
            return value("ok", true);
        });
        assertTrue(input.path("payload").path("n").isNull());
        assertTrue(result.path("ok").asBoolean());
    }

    @Test void builtInWhitelistAndAdaptiveRecursionAreRejectedBeforeAnyCall() throws Exception {
        JsonNode unknown = callDefinition("run_shell");
        assertThrows(IllegalArgumentException.class, () -> engine.validate(unknown, reads));
        for (String tool : Set.of("adaptive_extract", "evolve_tool", "review_adaptive_tool", "tool_evolution")) {
            assertThrows(IllegalArgumentException.class, () -> engine.validate(callDefinition(tool), Set.of(tool)));
        }
    }

    @Test void duplicateIdsForwardRefsSelfRefsAndMalformedReferenceObjectsAreRejected() throws Exception {
        for (String steps : new String[]{
                "[{\"id\":\"one\",\"op\":\"transform\",\"kind\":\"length\",\"value\":[]},{\"id\":\"one\",\"op\":\"transform\",\"kind\":\"length\",\"value\":[]}]",
                "[{\"id\":\"one\",\"op\":\"transform\",\"kind\":\"length\",\"value\":{\"$ref\":\"steps.two\"}},{\"id\":\"two\",\"op\":\"transform\",\"kind\":\"length\",\"value\":[]}]",
                "[{\"id\":\"one\",\"op\":\"transform\",\"kind\":\"length\",\"value\":{\"$ref\":\"steps.one\"}}]",
                "[{\"id\":\"one\",\"op\":\"transform\",\"kind\":\"length\",\"value\":{\"$ref\":\"input.list\",\"fallback\":[]}}]",
                "[{\"id\":\"one\",\"op\":\"transform\",\"kind\":\"length\",\"value\":{\"$ref\":\"env.KEY\"}}]",
                "[{\"id\":\"one\",\"op\":\"transform\",\"kind\":\"length\",\"value\":{\"$ref\":\"input..list\"}}]"}) {
            JsonNode definition = definition(steps, "null");
            assertThrows(IllegalArgumentException.class, () -> engine.validate(definition, reads));
        }
    }

    @Test void missingFieldsFailAndPresentNullCanBeReturned() throws Exception {
        JsonNode definition = definition("[{\"id\":\"one\",\"op\":\"transform\",\"kind\":\"pick\",\"value\":{\"$ref\":\"input\"},\"fields\":[\"nullable\"]}]", "{\"$ref\":\"steps.one.nullable\"}");
        assertThrows(IllegalArgumentException.class, () -> engine.execute(definition, read("{}"), this::unexpectedCall));
        assertTrue(engine.execute(definition, read("{\"nullable\":null}"), this::unexpectedCall).path("output").isNull());
        JsonNode array = definition("[{\"id\":\"one\",\"op\":\"transform\",\"kind\":\"length\",\"value\":{\"$ref\":\"input.items.0.text\"}}]", "{\"$ref\":\"steps.one\"}");
        assertEquals(2, engine.execute(array, read("{\"items\":[{\"text\":\"字😀\"}]}"), this::unexpectedCall).path("output").asInt());
    }

    @Test void failedCallStopsBeforeAnotherReadOrWriteAndKeepsFailureDetails() throws Exception {
        JsonNode definition = twoCalls();
        engine.validate(definition, all, Set.of("edit_note"));
        AtomicInteger calls = new AtomicInteger();
        JsonNode failure = read("{\"ok\":false,\"error\":\"note no longer exists\",\"error_code\":\"not_found\"}");
        JsonNode result = engine.execute(definition, read("{}"), (tool, args) -> { calls.incrementAndGet(); return failure; });
        assertEquals(failure, result);
        assertEquals(1, calls.get());
        assertFalse(result.has("output"));
    }

    @Test void stagedCallStopsImmediatelyAndCannotBeReportedAsCompletedByLaterTemplate() throws Exception {
        JsonNode definition = twoCalls();
        AtomicInteger calls = new AtomicInteger();
        JsonNode staged = read("{\"ok\":true,\"staged\":true,\"action_id\":4,\"message\":\"等待确认\"}");
        assertEquals(staged, engine.execute(definition, read("{}"), (tool, args) -> { calls.incrementAndGet(); return staged; }));
        assertEquals(1, calls.get());
    }

    @Test void callbackExceptionsPropagateAndNoRemainingStepRuns() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        IllegalStateException error = new IllegalStateException("service unavailable");
        assertSame(error, assertThrows(IllegalStateException.class, () -> engine.execute(twoCalls(), read("{}"), (tool, args) -> {
            calls.incrementAndGet(); throw error;
        })));
        assertEquals(1, calls.get());
        assertThrows(IllegalArgumentException.class, () -> engine.execute(callDefinition("get_note"), read("{}"), (tool, args) -> null));
    }

    @Test void writesAreOnlyAllowedLastAndDetectionUsesOperationsNotMentionedText() throws Exception {
        JsonNode valid = twoCalls();
        assertTrue(AdaptiveToolWorkflow.containsWrites(valid, Set.of("edit_note")));
        engine.validate(valid, all, Set.of("edit_note"));
        JsonNode invalid = definition("""
                [{"id":"write","op":"call","tool":"edit_note","arguments":{}},
                 {"id":"after","op":"transform","kind":"template","text":"done"}]
                """, "{\"$ref\":\"steps.after\"}");
        assertThrows(IllegalArgumentException.class, () -> engine.validate(invalid, all, Set.of("edit_note")));
        assertFalse(AdaptiveToolWorkflow.containsWrites(definition("[{\"id\":\"text\",\"op\":\"transform\",\"kind\":\"template\",\"text\":\"edit_note\"}]", "null"), Set.of("edit_note")));
    }

    @Test void inputSchemaIsEnforcedBeforeServicesAreInvoked() throws Exception {
        JsonNode definition = callDefinition("get_note");
        ((ObjectNode) definition).set("parameters", read("""
                {"type":"object","properties":{
                  "id":{"type":"integer","minimum":1,"maximum":20},
                  "mode":{"type":"string","enum":["brief","full"]},
                  "title":{"type":"string","minLength":2,"maxLength":5},
                  "items":{"type":"array","minItems":1,"maxItems":2,"items":{"type":"boolean"}},
                  "optional":{"type":["string","null"]}},
                 "required":["id","mode","title","items"],"additionalProperties":false}
                """));
        engine.validate(definition, reads);
        JsonNode input = read("{\"id\":2,\"mode\":\"full\",\"title\":\"字😀\",\"items\":[true],\"optional\":null}");
        assertTrue(engine.execute(definition, input, (tool, args) -> value("ok", true)).path("ok").asBoolean());
        for (String bad : new String[]{
                "{\"mode\":\"full\",\"title\":\"ok\",\"items\":[true]}",
                "{\"id\":2.5,\"mode\":\"full\",\"title\":\"ok\",\"items\":[true]}",
                "{\"id\":0,\"mode\":\"full\",\"title\":\"ok\",\"items\":[true]}",
                "{\"id\":21,\"mode\":\"full\",\"title\":\"ok\",\"items\":[true]}",
                "{\"id\":2,\"mode\":\"bad\",\"title\":\"ok\",\"items\":[true]}",
                "{\"id\":2,\"mode\":\"full\",\"title\":\"x\",\"items\":[true]}",
                "{\"id\":2,\"mode\":\"full\",\"title\":\"123456\",\"items\":[true]}",
                "{\"id\":2,\"mode\":\"full\",\"title\":\"ok\",\"items\":[]}",
                "{\"id\":2,\"mode\":\"full\",\"title\":\"ok\",\"items\":[true,false,true]}",
                "{\"id\":2,\"mode\":\"full\",\"title\":\"ok\",\"items\":[1]}",
                "{\"id\":2,\"mode\":\"full\",\"title\":\"ok\",\"items\":[true],\"extra\":1}"}) {
            assertThrows(IllegalArgumentException.class, () -> engine.execute(definition, read(bad), this::unexpectedCall));
        }
    }

    @Test void unsupportedSchemaConstraintsFailInsteadOfBeingSilentlyIgnored() throws Exception {
        for (String schema : new String[]{
                "{\"type\":\"object\",\"pattern\":\"(a+)+$\"}",
                "{\"type\":\"object\",\"properties\":{\"n\":{\"type\":\"integer\",\"minimum\":\"1\"}}}",
                "{\"type\":\"object\",\"required\":[\"undeclared\"]}",
                "{\"type\":\"object\",\"additionalProperties\":{\"type\":\"string\"}}",
                "{\"type\":\"object\",\"properties\":{\"s\":{\"type\":\"string\",\"minLength\":8,\"maxLength\":2}}}"}) {
            ObjectNode definition = (ObjectNode) callDefinition("get_note");
            definition.set("parameters", read(schema));
            assertThrows(IllegalArgumentException.class, () -> engine.validate(definition, reads));
        }
    }

    @Test void standaloneInputValidationAndExecutionRejectTheSameBadParametersWithoutDispatch() throws Exception {
        ObjectNode definition = (ObjectNode) callDefinition("get_note");
        definition.set("parameters", read("""
                {"type":"object","properties":{"note_id":{"type":"integer","minimum":1}},
                 "required":["note_id"],"additionalProperties":false}
                """));
        engine.validate(definition, reads);
        assertDoesNotThrow(() -> engine.validateInput(definition, read("{\"note_id\":4}")));
        AtomicInteger calls = new AtomicInteger();
        for (JsonNode input : new JsonNode[]{read("{}"), read("{\"note_id\":0}"),
                read("{\"note_id\":\"4\"}"), read("{\"note_id\":4,\"extra\":1}"),
                value("text", "x".repeat(AdaptiveToolWorkflow.MAX_VALUE_CHARS))}) {
            assertThrows(IllegalArgumentException.class, () -> engine.validateInput(definition, input));
            assertThrows(IllegalArgumentException.class, () -> engine.execute(definition, input, (tool, args) -> {
                calls.incrementAndGet(); return value("ok", true);
            }));
        }
        assertThrows(IllegalArgumentException.class, () -> engine.validateInput(definition, null));
        assertEquals(0, calls.get());
    }

    @Test void literalReplacementDoesNotInterpretRegexBackreferencesOrShellText() throws Exception {
        JsonNode definition = definition("""
                [{"id":"first","op":"transform","kind":"replace","value":"a.*a.*","search":".*","replacement":"$1\\\\cmd","all":false},
                 {"id":"all","op":"transform","kind":"replace","value":{"$ref":"steps.first"},"search":"a","replacement":"$(echo untouched)"}]
                """, "{\"$ref\":\"steps.all\"}");
        assertEquals("$(echo untouched)$1\\cmd$(echo untouched).*", engine.execute(definition, read("{}"), this::unexpectedCall).path("output").asText());
    }

    @Test void templateOnlyExpandsDeclaredReferencesAndPreservesLatexBackslashes() throws Exception {
        JsonNode definition = definition("[{\"id\":\"text\",\"op\":\"transform\",\"kind\":\"template\",\"text\":\"$$s(q,d)=\\\\langle E_q(q),E_d(d)\\\\rangle$$\\n{{ input.name }}\"}]", "{\"$ref\":\"steps.text\"}");
        assertEquals("$$s(q,d)=\\langle E_q(q),E_d(d)\\rangle$$\n合成笔记", engine.execute(definition, read("{\"name\":\"合成笔记\"}"), this::unexpectedCall).path("output").asText());
        for (String text : new String[]{"{{ java.lang.Runtime.exec }}", "{{input.name}", "input.name}}", "{{steps.later}}"}) {
            ObjectNode invalid = (ObjectNode) definition.deepCopy();
            ((ObjectNode) invalid.path("steps").get(0)).put("text", text);
            assertThrows(IllegalArgumentException.class, () -> engine.validate(invalid, reads));
        }
    }

    @Test void splitKeepsEmptyLinesAndTrailingNewlineAndUniqueTreatsJsonNumbersConsistently() throws Exception {
        JsonNode split = definition("[{\"id\":\"lines\",\"op\":\"transform\",\"kind\":\"split\",\"value\":\"a\\n\\nb\\n\",\"separator\":\"\\n\"}]", "{\"$ref\":\"steps.lines\"}");
        assertEquals(read("[\"a\",\"\",\"b\",\"\"]"), engine.execute(split, read("{}"), this::unexpectedCall).path("output"));
        JsonNode unique = definition("[{\"id\":\"items\",\"op\":\"transform\",\"kind\":\"unique\",\"value\":[1,1.0,2,{\"a\":1,\"b\":2},{\"b\":2.0,\"a\":1.0}]}]", "{\"$ref\":\"steps.items\"}");
        assertEquals(read("[1,2,{\"a\":1,\"b\":2}]"), engine.execute(unique, read("{}"), this::unexpectedCall).path("output"));
    }

    @Test void invalidTransformShapesOrRuntimeValueTypesFail() throws Exception {
        for (String steps : new String[]{
                "[{\"id\":\"one\",\"op\":\"transform\",\"kind\":\"eval\",\"value\":\"return 1\"}]",
                "[{\"id\":\"one\",\"op\":\"transform\",\"kind\":\"pick\",\"value\":{},\"fields\":[\"x\",\"x\"]}]",
                "[{\"id\":\"one\",\"op\":\"transform\",\"kind\":\"sort\",\"value\":[],\"descending\":\"false\"}]",
                "[{\"id\":\"one\",\"op\":\"transform\",\"kind\":\"replace\",\"value\":\"a\",\"search\":\"a\",\"replacement\":\"b\",\"regex\":true}]"}) {
            JsonNode definition = definition(steps, "null");
            assertThrows(IllegalArgumentException.class, () -> engine.validate(definition, reads));
        }
        for (String steps : new String[]{
                "[{\"id\":\"one\",\"op\":\"transform\",\"kind\":\"length\",\"value\":null}]",
                "[{\"id\":\"one\",\"op\":\"transform\",\"kind\":\"split\",\"value\":\"abc\",\"separator\":\"\"}]",
                "[{\"id\":\"one\",\"op\":\"transform\",\"kind\":\"replace\",\"value\":\"abc\",\"search\":\"\",\"replacement\":\"x\"}]",
                "[{\"id\":\"one\",\"op\":\"transform\",\"kind\":\"sort\",\"value\":[1,\"x\"]}]",
                "[{\"id\":\"one\",\"op\":\"transform\",\"kind\":\"filter\",\"value\":[{}],\"field\":\"missing\",\"operator\":\"eq\",\"compare\":1}]"}) {
            JsonNode definition = definition(steps, "null");
            assertThrows(IllegalArgumentException.class, () -> engine.execute(definition, read("{}"), this::unexpectedCall));
        }
    }

    @Test void boundsDefinitionsInputsResultsArrayExpansionDepthAndTotalResults() throws Exception {
        ObjectNode tooMany = (ObjectNode) callDefinition("get_note");
        ArrayNode steps = json.createArrayNode();
        for (int index = 0; index < AdaptiveToolWorkflow.MAX_STEPS + 1; index++) {
            steps.add(value("id", "step" + index).put("op", "call").put("tool", "get_note").set("arguments", json.createObjectNode()));
        }
        tooMany.set("steps", steps);
        assertThrows(IllegalArgumentException.class, () -> engine.validate(tooMany, reads));
        ObjectNode longDefinition = (ObjectNode) callDefinition("get_note");
        longDefinition.put("description", "x".repeat(AdaptiveToolWorkflow.MAX_DEFINITION_CHARS));
        assertThrows(IllegalArgumentException.class, () -> engine.validate(longDefinition, reads));
        JsonNode simple = callDefinition("get_note");
        assertThrows(IllegalArgumentException.class, () -> engine.execute(simple, value("text", "x".repeat(AdaptiveToolWorkflow.MAX_VALUE_CHARS)), this::unexpectedCall));
        assertThrows(IllegalArgumentException.class, () -> engine.execute(simple, read("{}"), (tool, args) -> value("text", "x".repeat(AdaptiveToolWorkflow.MAX_VALUE_CHARS))));
        ObjectNode deep = json.createObjectNode();
        ObjectNode current = deep;
        for (int index = 0; index < AdaptiveToolWorkflow.MAX_DEPTH + 1; index++) current = current.putObject("n");
        assertThrows(IllegalArgumentException.class, () -> engine.execute(simple, deep, this::unexpectedCall));
        JsonNode splitting = definition("[{\"id\":\"items\",\"op\":\"transform\",\"kind\":\"split\",\"value\":{\"$ref\":\"input.text\"},\"separator\":\",\"}]", "null");
        assertThrows(IllegalArgumentException.class, () -> engine.execute(splitting, value("text", ",".repeat(AdaptiveToolWorkflow.MAX_ARRAY_ITEMS)), this::unexpectedCall));
        JsonNode repeated = definition("""
                [{"id":"one","op":"call","tool":"get_note","arguments":{}},
                 {"id":"two","op":"call","tool":"get_note","arguments":{}},
                 {"id":"three","op":"call","tool":"get_note","arguments":{}}]
                """, "null");
        AtomicInteger calls = new AtomicInteger();
        assertThrows(IllegalArgumentException.class, () -> engine.execute(repeated, read("{}"), (tool, args) -> {
            calls.incrementAndGet(); return value("content", "x".repeat(750_000));
        }));
        assertEquals(3, calls.get());
    }

    @Test void expansionIsBoundedBeforeAnOversizedTemplateOrReplacementIsAllocated() throws Exception {
        JsonNode template = definition("[{\"id\":\"text\",\"op\":\"transform\",\"kind\":\"template\",\"text\":\"{{input.text}}{{input.text}}\"}]", "null");
        assertThrows(IllegalArgumentException.class, () -> engine.execute(template, value("text", "x".repeat(600_000)), this::unexpectedCall));
        JsonNode replacing = definition("[{\"id\":\"text\",\"op\":\"transform\",\"kind\":\"replace\",\"value\":{\"$ref\":\"input.text\"},\"search\":\"x\",\"replacement\":\"1234567890\"}]", "null");
        assertThrows(IllegalArgumentException.class, () -> engine.execute(replacing, value("text", "x".repeat(150_000)), this::unexpectedCall));
    }

    @Test void callerResultMutationCannotCorruptAnEarlierStepSnapshot() throws Exception {
        JsonNode definition = definition("""
                [{"id":"first","op":"call","tool":"get_note","arguments":{}},
                 {"id":"second","op":"call","tool":"get_note","arguments":{"content":{"$ref":"steps.first.content"}}}]
                """, "{\"$ref\":\"steps.first.content\"}");
        ObjectNode reused = value("content", "first version");
        AtomicInteger calls = new AtomicInteger();
        JsonNode result = engine.execute(definition, read("{}"), (tool, args) -> {
            if (calls.getAndIncrement() == 1) {
                assertEquals("first version", args.path("content").asText());
                reused.put("content", "mutated later");
            }
            return reused;
        });
        assertEquals("first version", result.path("output").asText());
        assertEquals("mutated later", result.path("steps").path("second").path("content").asText());
    }

    @Test void inFlightDefinitionMutationCannotAddAnUncheckedOperation() throws Exception {
        JsonNode definition = definition("""
                [{"id":"first","op":"call","tool":"get_note","arguments":{}},
                 {"id":"second","op":"call","tool":"get_note","arguments":{}}]
                """, "{\"$ref\":\"steps.second.content\"}");
        engine.validate(definition, reads);
        AtomicInteger calls = new AtomicInteger();
        JsonNode result = engine.execute(definition, read("{}"), (tool, args) -> {
            assertEquals("get_note", tool);
            if (calls.getAndIncrement() == 0) ((ObjectNode) definition.path("steps").get(1)).put("tool", "run_shell");
            return value("content", "synthetic read");
        });
        assertEquals(2, calls.get());
        assertEquals("synthetic read", result.path("output").asText());
        assertThrows(IllegalArgumentException.class, () -> engine.validate(definition, reads));
    }

    @Test void aReferenceResolvingCallArgumentsToAScalarFailsBeforeDispatch() throws Exception {
        JsonNode definition = definition("[{\"id\":\"read\",\"op\":\"call\",\"tool\":\"get_note\",\"arguments\":{\"$ref\":\"input.id\"}}]", "null");
        engine.validate(definition, reads);
        assertThrows(IllegalArgumentException.class, () -> engine.execute(definition, read("{\"id\":1}"), this::unexpectedCall));
    }

    @Test void ordinaryTransformedDataStatusFieldsCannotPretendToBeAToolCallStatus() throws Exception {
        JsonNode definition = definition("""
                [{"id":"data","op":"transform","kind":"pick","value":{"ok":false,"staged":true},"fields":["ok","staged"]},
                 {"id":"count","op":"transform","kind":"length","value":{"$ref":"steps.data"}}]
                """, "{\"$ref\":\"steps.count\"}");
        JsonNode result = engine.execute(definition, read("{}"), this::unexpectedCall);
        assertTrue(result.path("ok").asBoolean());
        assertEquals(2, result.path("output").asInt());
        assertEquals(2, result.path("steps").size());
    }

    private JsonNode twoCalls() throws Exception {
        return definition("""
                [{"id":"read","op":"call","tool":"get_note","arguments":{}},
                 {"id":"write","op":"call","tool":"edit_note","arguments":{"content":{"$ref":"steps.read.content"}}}]
                """, "{\"$ref\":\"steps.write\"}");
    }

    private JsonNode callDefinition(String tool) throws Exception {
        return definition("[{\"id\":\"read\",\"op\":\"call\",\"tool\":\"" + tool + "\",\"arguments\":{}}]", "{\"$ref\":\"steps.read\"}");
    }

    private JsonNode definition(String steps, String output) throws Exception {
        ObjectNode definition = json.createObjectNode();
        definition.put("name", "adaptive_test");
        definition.set("parameters", read("{\"type\":\"object\",\"properties\":{}}"));
        definition.set("steps", read(steps));
        definition.set("output", read(output));
        return definition;
    }

    private JsonNode read(String source) throws Exception { return json.readTree(source); }
    private ObjectNode value(String field, String value) { return json.createObjectNode().put(field, value); }
    private ObjectNode value(String field, boolean value) { return json.createObjectNode().put(field, value); }
    private JsonNode unexpectedCall(String tool, JsonNode args) { fail("No service should be invoked: " + tool); return null; }
}
