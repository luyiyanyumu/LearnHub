package org.dyh.learnhub.ai;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.dyh.learnhub.entity.AppSetting;
import org.dyh.learnhub.mapper.AppSettingMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiFunction;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Exercises the persisted registry using synthetic source, fixtures and a compare-and-set memory store. */
class ToolEvolutionServiceTest {
    private static final String SOURCE_PATH = "backend/src/main/java/synthetic/NoteCapabilities.java";
    private static final String SOURCE_HASH = "a".repeat(64);
    private static final Set<String> WRITES = Set.of("edit_note");
    private final ObjectMapper json = new ObjectMapper();
    private final AtomicReference<String> persisted = new AtomicReference<>();
    private final AtomicBoolean failNextCas = new AtomicBoolean();
    private AppSettingMapper mapper;
    private ToolSourceCatalog source;
    private ToolEvolutionService service;
    private List<Object> builtins;

    @BeforeEach @SuppressWarnings("unchecked") void setup() {
        mapper = mock(AppSettingMapper.class);
        source = mock(ToolSourceCatalog.class);
        builtins = List.of(builtin("get_note"), builtin("edit_note"), builtin("inspect_capabilities"), builtin("evolve_tool"));
        when(mapper.selectById(ToolEvolutionService.REGISTRY_KEY)).thenAnswer(ignored -> {
            String raw = persisted.get();
            if (raw == null) return null;
            AppSetting row = new AppSetting();
            row.setSettingKey(ToolEvolutionService.REGISTRY_KEY);
            row.setSettingValue(raw);
            return row;
        });
        when(mapper.insert(any(AppSetting.class))).thenAnswer(invocation -> {
            AppSetting row = invocation.getArgument(0);
            assertEquals(ToolEvolutionService.REGISTRY_KEY, row.getSettingKey());
            if (!persisted.compareAndSet(null, row.getSettingValue())) throw new IllegalStateException("synthetic duplicate registry key");
            return 1;
        });
        when(mapper.update(any(AppSetting.class), any(Wrapper.class))).thenAnswer(invocation -> {
            AppSetting row = invocation.getArgument(0);
            QueryWrapper<AppSetting> condition = invocation.getArgument(1);
            // MyBatis expands the parameter bindings lazily when the SQL segment is requested.
            String sql = condition.getSqlSegment();
            assertTrue(sql.contains("setting_key"));
            assertTrue(sql.contains("setting_value"));
            Map<String, Object> values = condition.getParamNameValuePairs();
            assertTrue(values.containsValue(ToolEvolutionService.REGISTRY_KEY));
            String previous = persisted.get();
            if (failNextCas.getAndSet(false) || !values.containsValue(previous)) return 0;
            return persisted.compareAndSet(previous, row.getSettingValue()) ? 1 : 0;
        });
        when(source.inspect(anyString(), anyList())).thenReturn(Map.of("ok", true, "available", true,
                "sourceMode", "bundled", "sourceRevision", "b".repeat(64),
                "files", List.of(Map.of("path", SOURCE_PATH, "sha256", SOURCE_HASH, "excerpt", "class NoteCapabilities {}"))));
        service = new ToolEvolutionService(json, mapper, source);
    }

    @Test void registersExecutesAndPersistsPureTransformAcrossServiceInstances() throws Exception {
        ObjectNode definition = normalize("adaptive_normalize_titles");
        var review = evolve(definition);
        assertEquals(true, review.get("registered"));
        assertEquals("new_tool", review.get("decision"));
        assertEquals(2, review.get("tested"));
        assertEquals(1, review.get("version"));
        assertEquals(false, review.get("writes"));
        assertEquals(1, registry().path("tools").size());
        assertEquals("new_tool", registry().path("audit").get(0).path("decision").asText());
        assertEquals(1, service.definitions(builtins, WRITES).size());

        BiFunction<String, JsonNode, JsonNode> caller = unexpectedCaller();
        JsonNode result = service.execute(definition.path("name").asText(), read("{\"text\":\"a,a,b\"}"), builtins, WRITES, caller);
        assertTrue(result.path("ok").asBoolean());
        assertEquals("a|b", result.path("output").asText());
        assertEquals("adaptive_normalize_titles", result.path("adaptive_tool").asText());
        assertEquals(1, entry("adaptive_normalize_titles").path("successes").asInt());

        var restarted = new ToolEvolutionService(json, mapper, source);
        assertEquals(1, restarted.definitions(builtins, WRITES).size());
        JsonNode afterRestart = restarted.execute("adaptive_normalize_titles", read("{\"text\":\"\"}"), builtins, WRITES, caller);
        assertTrue(afterRestart.path("ok").asBoolean());
        assertEquals("", afterRestart.path("output").asText());
        assertEquals(2, entry("adaptive_normalize_titles").path("successes").asInt());
        verifyNoInteractions(caller);
        verify(mapper).insert(any(AppSetting.class));
        verify(mapper, times(2)).update(any(AppSetting.class), any(Wrapper.class));
    }

    @Test void unchangedBuiltinAliasIsReusedWithoutRegisteringTestingOrCallingAService() throws Exception {
        ObjectNode alias = proposal("adaptive_note_reader");
        alias.set("steps", read("[{\"id\":\"read\",\"op\":\"call\",\"tool\":\"get_note\",\"arguments\":{\"note_id\":{\"$ref\":\"input.note_id\"}}}]"));
        alias.set("parameters", noteIdSchema());
        alias.set("output", ref("steps.read"));
        // Reuse returns before source/test registration review; it must never execute this primitive.
        alias.remove(List.of("source_refs", "tests"));
        var review = evolve(alias);
        assertEquals(true, review.get("ok"));
        assertEquals(false, review.get("registered"));
        assertEquals("reuse_existing", review.get("decision"));
        assertEquals("get_note", review.get("existing_tool"));
        assertTrue(registry().path("tools").isEmpty());
        assertEquals("reused", registry().path("audit").get(0).path("decision").asText());
        verifyNoInteractions(source);
        BiFunction<String, JsonNode, JsonNode> caller = unexpectedCaller();
        assertEquals("adaptive_unavailable", service.execute("adaptive_note_reader", read("{\"note_id\":1}"), builtins, WRITES, caller).path("code").asText());
        verifyNoInteractions(caller);
    }

    @Test void renamedStepIdsAndSchemaDescriptionsCannotCreateDuplicateCapabilities() throws Exception {
        ObjectNode first = normalize("adaptive_title_compact");
        assertEquals(true, evolve(first).get("registered"));
        ObjectNode renamed = normalize("adaptive_title_alias");
        String[] old = {"lines", "distinct", "joined"}, fresh = {"extract", "dedupe", "render"};
        for (int index = 0; index < old.length; index++) {
            ObjectNode step = (ObjectNode) renamed.path("steps").get(index);
            step.put("id", fresh[index]);
            if (index > 0) step.set("value", ref("steps." + fresh[index - 1]));
        }
        renamed.set("output", ref("steps.render"));
        ((ObjectNode) renamed.path("parameters").path("properties").path("text")).put("description", "Different editorial wording");
        assertEquals(ToolEvolutionService.fingerprint(first), ToolEvolutionService.fingerprint(renamed));
        var review = evolve(renamed);
        assertEquals(false, review.get("registered"));
        assertEquals("adaptive_title_compact", review.get("existing_tool"));
        assertEquals(1, registry().path("tools").size());
    }

    @Test void literalObjectIdsAndOrdinaryStepLikeTextRemainMeaningfulInDuplicateReview() throws Exception {
        ObjectNode firstId = constantValue("adaptive_literal_id_first", "foo", read("{\"id\":\"foo\"}"));
        ObjectNode secondId = constantValue("adaptive_literal_id_second", "bar", read("{\"id\":\"bar\"}"));
        assertNotEquals(ToolEvolutionService.fingerprint(firstId), ToolEvolutionService.fingerprint(secondId));
        assertEquals(true, evolve(firstId).get("registered"));
        assertEquals(true, evolve(secondId).get("registered"));
        assertEquals("foo", executePure("adaptive_literal_id_first", "unused").path("output").path("id").asText());
        assertEquals("bar", executePure("adaptive_literal_id_second", "unused").path("output").path("id").asText());

        ObjectNode firstText = constantValue("adaptive_literal_text_first", "foo", json.getNodeFactory().textNode("steps.foo"));
        ObjectNode secondText = constantValue("adaptive_literal_text_second", "bar", json.getNodeFactory().textNode("steps.bar"));
        assertNotEquals(ToolEvolutionService.fingerprint(firstText), ToolEvolutionService.fingerprint(secondText));
        assertEquals(true, evolve(firstText).get("registered"));
        assertEquals(true, evolve(secondText).get("registered"));
        assertEquals("steps.foo", executePure("adaptive_literal_text_first", "unused").path("output").asText());
        assertEquals("steps.bar", executePure("adaptive_literal_text_second", "unused").path("output").asText());
    }

    @Test void renamedRootParametersTemplateWhitespaceAndSchemaOrderingAreEquivalent() throws Exception {
        ObjectNode first = twoParameterTemplate("adaptive_ordered_template", "first", "second", false);
        ObjectNode renamed = twoParameterTemplate("adaptive_renamed_template", "alpha", "beta", true);
        ((ObjectNode) renamed.path("steps").get(0)).put("id", "formatted").put("text", "{{input.alpha}} / {{ input.beta }}");
        renamed.set("output", ref("steps.formatted"));
        assertEquals(ToolEvolutionService.fingerprint(first), ToolEvolutionService.fingerprint(renamed));
        assertEquals(true, evolve(first).get("registered"));
        var reviewed = evolve(renamed);
        assertEquals("reuse_existing", reviewed.get("decision"));
        assertEquals("adaptive_ordered_template", reviewed.get("existing_tool"));
        assertEquals(1, registry().path("tools").size());
    }

    @Test void callArgumentPropertyOrderDoesNotChangeCanonicalInputRoles() throws Exception {
        ObjectNode first = twoParameterCall("adaptive_ordered_call", "id", "prefix", false);
        ObjectNode reordered = twoParameterCall("adaptive_reordered_call", "note", "heading", true);
        assertEquals(ToolEvolutionService.fingerprint(first), ToolEvolutionService.fingerprint(reordered));
        assertEquals(true, evolve(first).get("registered"));
        var reviewed = evolve(reordered);
        assertEquals("reuse_existing", reviewed.get("decision"));
        assertEquals("adaptive_ordered_call", reviewed.get("existing_tool"));
        assertEquals(1, registry().path("tools").size());
    }

    @Test void wholeInputReferenceAndTemplateKeepObservableRootFieldNames() throws Exception {
        for (boolean template : List.of(false, true)) {
            String suffix = template ? "template" : "object";
            ObjectNode first = wholeInput("adaptive_whole_first_" + suffix, "first", template);
            ObjectNode renamed = wholeInput("adaptive_whole_renamed_" + suffix, "alpha", template);
            assertNotEquals(ToolEvolutionService.fingerprint(first), ToolEvolutionService.fingerprint(renamed));
            assertEquals(true, evolve(first).get("registered"));
            assertEquals(true, evolve(renamed).get("registered"));
            JsonNode firstOutput = service.execute(first.path("name").asText(), json.createObjectNode().put("first", "A"), builtins, WRITES, unexpectedCaller()).path("output");
            JsonNode renamedOutput = service.execute(renamed.path("name").asText(), json.createObjectNode().put("alpha", "A"), builtins, WRITES, unexpectedCaller()).path("output");
            if (template) {
                assertEquals("{\"first\":\"A\"}", firstOutput.asText());
                assertEquals("{\"alpha\":\"A\"}", renamedOutput.asText());
            } else {
                assertTrue(firstOutput.has("first"));
                assertFalse(firstOutput.has("alpha"));
                assertTrue(renamedOutput.has("alpha"));
                assertFalse(renamedOutput.has("first"));
            }
        }
    }

    @Test void duplicateOfDisabledToolReturnsItsStateAndCurrentVersionForRepair() throws Exception {
        assertEquals(true, evolve(normalize("adaptive_disabled_original")).get("registered"));
        assertEquals(true, service.manage("adaptive_disabled_original", "disable", 1, builtins, WRITES).get("ok"));
        var reviewed = evolve(normalize("adaptive_disabled_alias"));
        assertEquals(false, reviewed.get("registered"));
        assertEquals("reuse_existing", reviewed.get("decision"));
        assertEquals("adaptive_disabled_original", reviewed.get("existing_tool"));
        assertEquals(false, reviewed.get("enabled"));
        assertEquals(1, reviewed.get("expected_version"));
        assertTrue(String.valueOf(reviewed.get("message")).contains("已停用"));
        assertFalse(String.valueOf(reviewed.get("message")).contains("请直接调用"));
        assertEquals(1, registry().path("tools").size());
        assertTrue(service.definitions(builtins, WRITES).isEmpty());
    }

    @Test void displayedNumericRepresentationAndLiteralObjectOrderAreDistinctCapabilities() throws Exception {
        for (boolean template : List.of(false, true)) {
            String mode = template ? "template" : "join";
            ObjectNode integer = literalDisplay("adaptive_integer_" + mode, read("[1]"), template, template ? "[1]" : "1");
            ObjectNode decimal = literalDisplay("adaptive_decimal_" + mode, read("[1.0]"), template, template ? "[1.0]" : "1.0");
            assertNotEquals(ToolEvolutionService.fingerprint(integer), ToolEvolutionService.fingerprint(decimal));
            assertEquals(true, evolve(integer).get("registered"));
            assertEquals(true, evolve(decimal).get("registered"));
            assertNotEquals(executePure(integer.path("name").asText(), "unused").path("output"),
                    executePure(decimal.path("name").asText(), "unused").path("output"));

            ObjectNode forward = literalDisplay("adaptive_forward_" + mode, read("[{\"a\":1,\"b\":2}]"), template,
                    template ? "[{\"a\":1,\"b\":2}]" : "{\"a\":1,\"b\":2}");
            ObjectNode reverse = literalDisplay("adaptive_reverse_" + mode, read("[{\"b\":2,\"a\":1}]"), template,
                    template ? "[{\"b\":2,\"a\":1}]" : "{\"b\":2,\"a\":1}");
            assertNotEquals(ToolEvolutionService.fingerprint(forward), ToolEvolutionService.fingerprint(reverse));
            assertEquals(true, evolve(forward).get("registered"));
            assertEquals(true, evolve(reverse).get("registered"));
            assertNotEquals(executePure(forward.path("name").asText(), "unused").path("output"),
                    executePure(reverse.path("name").asText(), "unused").path("output"));
        }
        assertEquals(8, registry().path("tools").size());
    }

    @Test void displaySensitiveWorkflowsStillCanonicalizeStructuralFieldsAndActualReferences() throws Exception {
        ObjectNode first = normalize("adaptive_original_join_refs");
        ObjectNode reordered = normalize("adaptive_reordered_join_refs");
        // Change object insertion order and step names while keeping the expressions themselves equivalent.
        ObjectNode split = json.createObjectNode();
        split.set("value", ref("input.source"));
        split.put("separator", ",").put("kind", "split").put("op", "transform").put("id", "parts");
        ObjectNode unique = json.createObjectNode();
        unique.set("value", ref("steps.parts"));
        unique.put("kind", "unique").put("id", "deduped").put("op", "transform");
        ObjectNode join = json.createObjectNode().put("separator", "|");
        join.set("value", ref("steps.deduped"));
        join.put("id", "display").put("op", "transform").put("kind", "join");
        reordered.set("steps", json.createArrayNode().add(split).add(unique).add(join));
        reordered.set("output", ref("steps.display"));
        reordered.set("parameters", read("{\"additionalProperties\":false,\"required\":[\"source\"],\"properties\":{\"source\":{\"type\":\"string\"}},\"type\":\"object\"}"));
        for (JsonNode test : reordered.path("tests")) {
            ObjectNode input = (ObjectNode) test.path("input");
            input.set("source", input.remove("text"));
        }
        assertEquals(ToolEvolutionService.fingerprint(first), ToolEvolutionService.fingerprint(reordered));
        assertEquals(true, evolve(first).get("registered"));
        var reviewed = evolve(reordered);
        assertEquals("reuse_existing", reviewed.get("decision"));
        assertEquals("adaptive_original_join_refs", reviewed.get("existing_tool"));
    }

    @Test void updatingSameNameRequiresCurrentVersionAndRollbackKeepsVersionMonotonic() throws Exception {
        assertEquals(true, evolve(replacer("adaptive_replace_chars", "x")).get("registered"));
        ObjectNode update = replacer("adaptive_replace_chars", "y");
        var missingVersion = evolve(update);
        assertEquals(false, missingVersion.get("ok"));
        assertTrue(String.valueOf(missingVersion.get("error")).contains("expected_version"));
        assertEquals(1, entry("adaptive_replace_chars").path("version").asInt());
        update.put("expected_version", 1);
        var accepted = evolve(update);
        assertEquals("extend_existing", accepted.get("decision"));
        assertEquals(2, accepted.get("version"));
        assertEquals(1, entry("adaptive_replace_chars").path("versions").size());
        assertEquals("yyby", executePure("adaptive_replace_chars", "aaba").path("output").asText());
        assertEquals(false, service.manage("adaptive_replace_chars", "rollback", 1, builtins, WRITES).get("ok"));
        var rolledBack = service.manage("adaptive_replace_chars", "rollback", 2, builtins, WRITES);
        assertEquals(true, rolledBack.get("ok"));
        assertEquals(3, rolledBack.get("version"));
        assertEquals("xxbx", executePure("adaptive_replace_chars", "aaba").path("output").asText());
        assertEquals(false, service.manage("adaptive_replace_chars", "disable", 2, builtins, WRITES).get("ok"));
        assertTrue(entry("adaptive_replace_chars").path("enabled").asBoolean());
    }

    @Test void invalidSourceHashCannotRegisterEvenWhenSyntheticTestsWouldPass() throws Exception {
        ObjectNode definition = normalize("adaptive_stale_source");
        ((ObjectNode) definition.path("source_refs").get(0)).put("sha256", "c".repeat(64));
        var rejected = evolve(definition);
        assertEquals(false, rejected.get("ok"));
        assertTrue(String.valueOf(rejected.get("error")).contains("源码引用"));
        assertTrue(registry().path("tools").isEmpty());
        assertEquals("rejected", registry().path("audit").get(0).path("decision").asText());
    }

    @Test void unavailableSourceCannotBeInventedFromAToolDefinition() throws Exception {
        when(source.inspect(anyString(), anyList())).thenReturn(Map.of("available", false, "files", List.of()));
        var rejected = evolve(normalize("adaptive_without_source"));
        assertEquals(false, rejected.get("ok"));
        assertTrue(String.valueOf(rejected.get("error")).contains("源码不可用"));
        assertTrue(registry().path("tools").isEmpty());
    }

    @Test void wrongExpectedOutputRejectsRegistrationAndKeepsExistingDefinition() throws Exception {
        assertEquals(true, evolve(replacer("adaptive_verified_tool", "x")).get("registered"));
        JsonNode before = entry("adaptive_verified_tool").path("definition").deepCopy();
        ObjectNode broken = replacer("adaptive_verified_tool", "y");
        broken.put("expected_version", 1);
        ((ObjectNode) broken.path("tests").get(0)).put("expected", "incorrect synthetic expectation");
        var rejected = evolve(broken);
        assertEquals(false, rejected.get("ok"));
        assertTrue(String.valueOf(rejected.get("error")).contains("不匹配"));
        assertEquals(before, entry("adaptive_verified_tool").path("definition"));
        assertEquals(1, entry("adaptive_verified_tool").path("version").asInt());
    }

    @Test void missingOrMismatchedCallFixturesNeverInvokeRealTools() throws Exception {
        for (int scenario = 0; scenario < 3; scenario++) {
            ObjectNode definition = noteTitle("adaptive_fixture_case_" + scenario);
            ObjectNode test = (ObjectNode) definition.path("tests").get(0);
            if (scenario == 0) test.remove("tool_results");
            else if (scenario == 1) ((ObjectNode) test.path("tool_results").get(0)).set("arguments", read("{\"note_id\":99}"));
            else ((ObjectNode) test.path("tool_results").get(0)).put("tool", "edit_note");
            var rejected = evolve(definition);
            assertEquals(false, rejected.get("ok"));
            assertTrue(String.valueOf(rejected.get("error")).contains("模拟"));
            assertTrue(registry().path("tools").isEmpty());
            BiFunction<String, JsonNode, JsonNode> caller = unexpectedCaller();
            assertEquals("adaptive_unavailable", service.execute(definition.path("name").asText(), read("{\"note_id\":1}"), builtins, WRITES, caller).path("code").asText());
            verifyNoInteractions(caller);
        }
    }

    @Test void matchingFixturesCannotOverrideBuiltinRequiredFieldsOrArgumentTypes() throws Exception {
        builtins = List.of(builtinWithSchema("get_note", noteIdSchema()));
        for (boolean wrongType : List.of(false, true)) {
            ObjectNode definition = noteTitle(wrongType ? "adaptive_wrong_builtin_type" : "adaptive_missing_builtin_field");
            ObjectNode arguments = (ObjectNode) definition.path("steps").get(0).path("arguments");
            if (wrongType) {
                ((ObjectNode) definition.path("parameters").path("properties").path("note_id")).put("type", "string").remove("minimum");
                for (JsonNode test : definition.path("tests")) {
                    ((ObjectNode) test.path("input")).put("note_id", "synthetic-" + test.path("input").path("note_id").asInt());
                    ((ObjectNode) test.path("tool_results").get(0)).set("arguments", test.path("input").deepCopy());
                }
            } else {
                arguments.remove("note_id");
                for (JsonNode test : definition.path("tests")) ((ObjectNode) test.path("tool_results").get(0)).set("arguments", json.createObjectNode());
            }
            var rejected = evolve(definition);
            assertEquals(false, rejected.get("ok"));
            assertTrue(String.valueOf(rejected.get("error")).contains("note_id"));
            assertTrue(registry().path("tools").isEmpty());
        }
    }

    @Test void invalidGeneratedBuiltinArgumentsAreBlockedBeforeRuntimeDispatch() throws Exception {
        builtins = List.of(builtinWithSchema("get_note", noteIdSchema()));
        assertEquals(true, evolve(noteTitle("adaptive_checked_primitive")).get("registered"));
        ObjectNode registry = (ObjectNode) registry();
        ObjectNode definition = (ObjectNode) registry.path("tools").get(0).path("definition");
        // A stale or damaged persisted expression is rechecked even though registration previously passed.
        ((ObjectNode) definition.path("parameters").path("properties")).putObject("label").put("type", "string");
        ((ObjectNode) definition.path("steps").get(0).path("arguments")).set("note_id", ref("input.label"));
        persisted.set(registry.toString());
        BiFunction<String, JsonNode, JsonNode> caller = unexpectedCaller();
        JsonNode result = service.execute("adaptive_checked_primitive", read("{\"note_id\":1,\"label\":\"synthetic noninteger\"}"), builtins, WRITES, caller);
        assertEquals("adaptive_execution_failed", result.path("code").asText());
        assertTrue(result.path("error").asText().contains("note_id"));
        verifyNoInteractions(caller);
        assertEquals(1, entry("adaptive_checked_primitive").path("failures").asInt());
    }

    @Test void capabilityInspectionIncludesRecentReviewFailuresForSelfCorrection() throws Exception {
        ObjectNode definition = normalize("adaptive_review_feedback");
        ((ObjectNode) definition.path("source_refs").get(0)).put("sha256", "c".repeat(64));
        assertEquals(false, evolve(definition).get("ok"));
        JsonNode inspection = json.valueToTree(service.inspect("synthetic capability", builtins));
        assertTrue(inspection.path("recentReviews").isArray());
        assertEquals("rejected", inspection.path("recentReviews").get(0).path("decision").asText());
        assertEquals("adaptive_review_feedback", inspection.path("recentReviews").get(0).path("name").asText());
        assertTrue(inspection.path("recentReviews").get(0).path("reason").asText().contains("源码引用"));
    }

    @Test void duplicateTestInputsDoNotPretendToProveBoundaryBehavior() throws Exception {
        ObjectNode definition = normalize("adaptive_repeated_tests");
        ((ObjectNode) definition.path("tests").get(1)).set("input", definition.path("tests").get(0).path("input").deepCopy());
        ((ObjectNode) definition.path("tests").get(1)).set("expected", definition.path("tests").get(0).path("expected").deepCopy());
        var rejected = evolve(definition);
        assertEquals(false, rejected.get("ok"));
        assertTrue(String.valueOf(rejected.get("error")).contains("重复"));
        assertTrue(registry().path("tools").isEmpty());
    }

    @Test void writeInsideWorkflowCannotBeFollowedByAnotherReadOrTransform() throws Exception {
        ObjectNode definition = noteTitle("adaptive_write_then_transform");
        ArrayNode steps = (ArrayNode) definition.path("steps");
        steps.insert(1, read("{\"id\":\"write\",\"op\":\"call\",\"tool\":\"edit_note\",\"arguments\":{\"note_id\":{\"$ref\":\"input.note_id\"}}}"));
        var rejected = evolve(definition);
        assertEquals(false, rejected.get("ok"));
        assertTrue(String.valueOf(rejected.get("error")).contains("final step"));
        assertTrue(registry().path("tools").isEmpty());
        verifyNoInteractions(source);
    }

    @Test void wordDocumentCreationCannotBeHiddenBeforeAnotherWorkflowStep() throws Exception {
        ObjectNode definition = noteTitle("adaptive_document_then_transform");
        ((ObjectNode) definition.path("steps").get(0)).put("tool", "create_word_document");
        var reviewed = service.evolve(definition, List.of(builtin("create_word_document")), Set.of("create_word_document"), "synthetic-session");
        assertEquals(false, reviewed.get("ok"));
        assertTrue(String.valueOf(reviewed.get("error")).contains("final step"));
        assertTrue(registry().path("tools").isEmpty());
        verifyNoInteractions(source);
    }

    @Test void unknownAdaptiveAndEvolutionControlCallsAreRejectedBeforeSourceOrTests() throws Exception {
        var augmented = new ArrayList<>(builtins);
        augmented.add(builtin("adaptive_other_workflow"));
        for (String unsupported : List.of("run_shell", "adaptive_other_workflow", "inspect_capabilities", "evolve_tool")) {
            ObjectNode definition = noteTitle("adaptive_invalid_call");
            ((ObjectNode) definition.path("steps").get(0)).put("tool", unsupported);
            var rejected = service.evolve(definition, augmented, WRITES, "synthetic-session");
            assertEquals(false, rejected.get("ok"));
            assertTrue(String.valueOf(rejected.get("error")).contains("approved built-in"));
            assertTrue(registry().path("tools").isEmpty());
        }
        verifyNoInteractions(source);
    }

    @Test void corruptRegistryIsPreservedInsteadOfBeingReplacedWithEmptyDefinitions() throws Exception {
        for (String corrupted : List.of("{broken", "{\"format\":2,\"tools\":[],\"audit\":[]}",
                "{\"format\":1,\"tools\":{},\"audit\":[]}")) {
            persisted.set(corrupted);
            assertThrows(IllegalStateException.class, () -> evolve(normalize("adaptive_repair_test")));
            assertTrue(service.definitions(builtins, WRITES).isEmpty());
            BiFunction<String, JsonNode, JsonNode> caller = unexpectedCaller();
            assertThrows(IllegalStateException.class, () -> service.execute("adaptive_repair_test", json.createObjectNode(), builtins, WRITES, caller));
            assertThrows(IllegalStateException.class, () -> service.manage("adaptive_repair_test", "disable", 1, builtins, WRITES));
            verifyNoInteractions(caller);
            assertEquals(corrupted, persisted.get());
        }
        verify(mapper, never()).insert(any(AppSetting.class));
        verify(mapper, never()).update(any(AppSetting.class), any(Wrapper.class));
    }

    @Test void staleCompareAndSetFailsWithoutOverwritingThePersistedRegistry() throws Exception {
        assertEquals(true, evolve(replacer("adaptive_concurrent_update", "x")).get("registered"));
        String before = persisted.get();
        ObjectNode update = replacer("adaptive_concurrent_update", "y");
        update.put("expected_version", 1);
        failNextCas.set(true);
        IllegalStateException error = assertThrows(IllegalStateException.class, () -> evolve(update));
        assertTrue(error.getMessage().contains("并发"));
        assertEquals(before, persisted.get());
    }

    @Test void threeImplementationFailuresDisableToolAndStopFurtherDispatch() throws Exception {
        assertEquals(true, evolve(noteTitle("adaptive_faulty_reader")).get("registered"));
        AtomicInteger calls = new AtomicInteger();
        BiFunction<String, JsonNode, JsonNode> caller = (tool, args) -> { calls.incrementAndGet(); return null; };
        for (int failure = 1; failure <= 3; failure++) {
            JsonNode result = service.execute("adaptive_faulty_reader", read("{\"note_id\":1}"), builtins, WRITES, caller);
            assertEquals("adaptive_execution_failed", result.path("code").asText());
            assertEquals(failure, entry("adaptive_faulty_reader").path("failures").asInt());
            assertEquals(failure < 3, entry("adaptive_faulty_reader").path("enabled").asBoolean());
        }
        assertTrue(service.definitions(builtins, WRITES).isEmpty());
        assertEquals("adaptive_unavailable", service.execute("adaptive_faulty_reader", read("{\"note_id\":1}"), builtins, WRITES, caller).path("code").asText());
        assertEquals(3, calls.get());
        assertTrue(registry().path("audit").findValuesAsText("decision").contains("auto_disabled"));
    }

    @Test void businessPermissionAndMissingDataFailuresDoNotDisableNormalImplementation() throws Exception {
        assertEquals(true, evolve(noteTitle("adaptive_business_reader")).get("registered"));
        AtomicInteger calls = new AtomicInteger();
        for (String code : List.of("permission_denied", "not_found", "forbidden", "conflict")) {
            JsonNode failure = json.createObjectNode().put("ok", false).put("code", code).put("error", "synthetic business failure");
            JsonNode result = service.execute("adaptive_business_reader", read("{\"note_id\":1}"), builtins, WRITES, (tool, args) -> { calls.incrementAndGet(); return failure; });
            assertFalse(result.path("ok").asBoolean());
            assertEquals(code, result.path("code").asText());
            assertEquals(0, entry("adaptive_business_reader").path("failures").asInt());
            assertTrue(entry("adaptive_business_reader").path("enabled").asBoolean());
        }
        assertEquals(4, calls.get());
        assertEquals(1, service.definitions(builtins, WRITES).size());
        assertFalse(registry().path("audit").findValuesAsText("decision").contains("auto_disabled"));
    }

    @Test void successfulExecutionResetsImplementationFailureStreak() throws Exception {
        assertEquals(true, evolve(noteTitle("adaptive_recovering_reader")).get("registered"));
        for (int failure = 0; failure < 2; failure++) service.execute("adaptive_recovering_reader", read("{\"note_id\":1}"), builtins, WRITES, (tool, args) -> null);
        assertEquals(2, entry("adaptive_recovering_reader").path("failures").asInt());
        JsonNode success = service.execute("adaptive_recovering_reader", read("{\"note_id\":1}"), builtins, WRITES,
                (tool, args) -> json.createObjectNode().put("ok", true).put("title", "Synthetic success"));
        assertEquals("Title:Synthetic success", success.path("output").asText());
        assertEquals(0, entry("adaptive_recovering_reader").path("failures").asInt());
        service.execute("adaptive_recovering_reader", read("{\"note_id\":1}"), builtins, WRITES, (tool, args) -> null);
        assertEquals(1, entry("adaptive_recovering_reader").path("failures").asInt());
        assertTrue(entry("adaptive_recovering_reader").path("enabled").asBoolean());
    }

    @Test void invalidUserArgumentsDoNotCountAsImplementationFailuresOrDisableTool() throws Exception {
        assertEquals(true, evolve(noteTitle("adaptive_validated_reader")).get("registered"));
        BiFunction<String, JsonNode, JsonNode> caller = unexpectedCaller();
        for (String invalid : List.of("{}", "{\"note_id\":\"not an integer\"}", "{\"note_id\":0}", "{\"note_id\":1,\"unexpected\":true}")) {
            JsonNode result = service.execute("adaptive_validated_reader", read(invalid), builtins, WRITES, caller);
            assertEquals("adaptive_invalid_input", result.path("code").asText());
            assertEquals(0, entry("adaptive_validated_reader").path("failures").asInt());
            assertTrue(entry("adaptive_validated_reader").path("enabled").asBoolean());
        }
        verifyNoInteractions(caller);
        assertEquals(1, service.definitions(builtins, WRITES).size());
    }

    @Test void oversizedSchemaAndExecutablePlanAreRejectedBeforeRegistrationOrExposure() throws Exception {
        ObjectNode oversizedSchema = replacer("adaptive_oversized_schema", "x");
        ((ObjectNode) oversizedSchema.path("parameters").path("properties").path("text")).put("description", "s".repeat(6_500));
        var schemaReview = evolve(oversizedSchema);
        assertEquals(false, schemaReview.get("ok"));
        assertTrue(String.valueOf(schemaReview.get("error")).contains("6000"));
        assertTrue(registry().path("tools").isEmpty());
        assertTrue(service.definitions(builtins, WRITES).isEmpty());

        ObjectNode oversizedPlan = constantValue("adaptive_oversized_plan", "render", json.getNodeFactory().textNode("p".repeat(32_500)));
        var planReview = evolve(oversizedPlan);
        assertEquals(false, planReview.get("ok"));
        assertTrue(String.valueOf(planReview.get("error")).contains("32000"));
        assertTrue(registry().path("tools").isEmpty());
        assertTrue(service.definitions(builtins, WRITES).isEmpty());
        verifyNoInteractions(source);
    }

    @Test void cumulativeSchemaBudgetIncludesDisabledToolsAndPreservesAcceptedDefinitions() throws Exception {
        int schemaChars = 0;
        for (int tool = 0; tool < 4; tool++) {
            ObjectNode definition = replacer("adaptive_schema_budget_" + tool, "r" + tool);
            ((ObjectNode) definition.path("parameters").path("properties").path("text")).put("description", "s".repeat(5_000));
            schemaChars += definition.path("parameters").toString().length();
            assertEquals(true, evolve(definition).get("registered"));
        }
        assertTrue(schemaChars < 24_000);
        assertEquals(true, service.manage("adaptive_schema_budget_0", "disable", 1, builtins, WRITES).get("ok"));
        JsonNode accepted = registry().path("tools").deepCopy();
        JsonNode exposed = json.valueToTree(service.definitions(builtins, WRITES));
        assertEquals(3, exposed.size());

        ObjectNode overflow = replacer("adaptive_schema_budget_overflow", "different");
        ((ObjectNode) overflow.path("parameters").path("properties").path("text")).put("description", "s".repeat(5_000));
        assertTrue(schemaChars + overflow.path("parameters").toString().length() > 24_000);
        var rejected = evolve(overflow);
        assertEquals(false, rejected.get("ok"));
        assertTrue(String.valueOf(rejected.get("error")).contains("24000"));
        assertEquals(accepted, registry().path("tools"));
        assertEquals(exposed, json.valueToTree(service.definitions(builtins, WRITES)));
    }

    @Test void exactToolNameInspectionPrioritizesFullDefinitionWithinSharedBriefBudget() throws Exception {
        for (String part : List.of("first", "second", "target")) {
            ObjectNode definition = constantValue("adaptive_large_definition_" + part, "render", json.getNodeFactory().textNode(part.substring(0, 1).repeat(18_000)));
            assertEquals(true, evolve(definition).get("registered"));
        }
        JsonNode generic = json.valueToTree(service.inspect("synthetic catalog overview", builtins)).path("adaptiveTools");
        assertEquals(3, generic.size());
        assertEquals("adaptive_large_definition_target", generic.get(2).path("name").asText());
        assertTrue(generic.get(2).path("definitionOmitted").asBoolean());
        assertFalse(generic.get(2).has("definition"));
        assertTrue(generic.get(2).hasNonNull("description"));
        assertTrue(generic.get(2).hasNonNull("gap"));

        JsonNode targeted = json.valueToTree(service.inspect("adaptive_large_definition_target", builtins)).path("adaptiveTools");
        assertEquals("adaptive_large_definition_target", targeted.get(0).path("name").asText());
        assertTrue(targeted.get(0).path("definition").isObject());
        assertFalse(targeted.get(0).path("definition").has("tests"));
        assertEquals("t".repeat(18_000), targeted.get(0).path("definition").path("steps").get(0).path("text").asText());
        int fullDefinitionChars = 0, omitted = 0;
        for (JsonNode brief : targeted) {
            if (brief.has("definition")) fullDefinitionChars += brief.path("definition").toString().length();
            else if (brief.path("definitionOmitted").asBoolean()) omitted++;
        }
        assertTrue(fullDefinitionChars <= 48_000);
        assertTrue(omitted > 0);
        assertEquals(3, service.definitions(builtins, WRITES).size());
    }

    @Test void codeDryRunExecutesSyntheticCasesWithoutMutatingRegistryAndRegistrationRetests() throws Exception {
        CodeExecutionService runtime = codeRuntime();
        ObjectNode definition = codeProposal("adaptive_case_format", "upper");
        List<String> observed = new ArrayList<>();
        var result = service.test(definition, builtins, WRITES, (event, step) -> observed.add(event + ":" + step.path("id").asText()));
        assertEquals(true, result.get("ok")); assertEquals(false, result.get("registered"));
        assertEquals(2, result.get("passed")); assertNull(persisted.get());
        assertTrue(observed.contains("start:test_1_format")); assertTrue(observed.contains("finish:test_2_format"));
        JsonNode diagnostics = json.valueToTree(result).path("tests");
        assertEquals("AAB", diagnostics.get(0).path("actual").asText());
        assertEquals("synthetic log", diagnostics.get(0).path("codeSteps").get(0).path("stdout").asText());
        verify(runtime, times(2)).execute(anyString(), any());
        assertEquals(true, evolve(definition).get("registered"));
        verify(runtime, times(4)).execute(anyString(), any());
        assertEquals(1, service.definitions(builtins, WRITES).size());
    }

    @Test void codeTestMismatchReportsActualExpectedAndDoesNotRegister() throws Exception {
        codeRuntime(); ObjectNode definition = codeProposal("adaptive_bad_case", "upper");
        ((ObjectNode) definition.path("tests").get(0)).put("expected", "wrong");
        JsonNode result = json.valueToTree(service.test(definition, builtins, WRITES));
        assertFalse(result.path("ok").asBoolean()); assertEquals(1, result.path("passed").asInt());
        assertEquals("AAB", result.path("tests").get(0).path("actual").asText());
        assertEquals("wrong", result.path("tests").get(0).path("expected").asText()); assertNull(persisted.get());
        assertEquals(false, evolve(definition).get("ok")); assertTrue(registry().path("tools").isEmpty());
    }

    @Test void codeToolsRequireRuntimeAndNormalBoundaryTestsWithoutAffectingPureWorkflows() throws Exception {
        ObjectNode definition = codeProposal("adaptive_unconfigured_code", "upper");
        assertEquals(false, service.test(definition, builtins, WRITES).get("ok")); assertNull(persisted.get());
        assertEquals(true, evolve(normalize("adaptive_pure_available")).get("registered"));
        CodeExecutionService runtime = codeRuntime();
        ((ObjectNode) definition.path("tests").get(1)).remove("kind");
        assertEquals(false, service.test(definition, builtins, WRITES).get("ok"));
        verify(runtime, never()).execute(anyString(), any());
        assertEquals(1, service.definitions(builtins, WRITES).size());
    }

    @Test void canonicalCodeFingerprintReusesCommentAndWhitespaceAliasWithoutExecutingIt() throws Exception {
        CodeExecutionService runtime = codeRuntime();
        ObjectNode original = codeProposal("adaptive_original_code", "upper");
        assertEquals(true, evolve(original).get("registered"));
        ObjectNode alias = original.deepCopy(); alias.put("name", "adaptive_alias_code");
        ((ObjectNode) alias.path("steps").get(0)).put("source", "# synthetic formatting comment\ndef run(input):\n    return input[\"text\"].upper()\n");
        var duplicate = evolve(alias);
        assertEquals("reuse_existing", duplicate.get("decision")); assertEquals("adaptive_original_code", duplicate.get("existing_tool"));
        verify(runtime, times(2)).execute(anyString(), any()); assertEquals(1, registry().path("tools").size());
    }

    @Test void matchingCapabilityKeyRequiresUpdatingExistingToolAndDifferentConstantsStayDistinct() throws Exception {
        codeRuntime(); assertEquals(true, evolve(codeProposal("adaptive_original_case", "upper")).get("registered"));
        ObjectNode proposal = codeProposal("adaptive_parallel_case", "lower");
        var rejected = evolve(proposal);
        assertEquals(false, rejected.get("ok")); assertTrue(rejected.get("error").toString().contains("adaptive_original_case"));
        proposal.put("capability_key", "text_lower_case");
        assertEquals(false, evolve(proposal).get("ok"));
        proposal.put("distinct_reason", "该工具为区分大小写的结构化标识生成小写键，而已有工具用于展示大写文本，输出契约不同");
        assertEquals(true, evolve(proposal).get("registered"));
        assertEquals(2, registry().path("tools").size());
        ObjectNode first = codeProposal("adaptive_constant_first", "upper"), second = first.deepCopy();
        ((ObjectNode) first.path("steps").get(0)).put("source", "def run(input):\n return 1\n");
        ((ObjectNode) second.path("steps").get(0)).put("source", "def run(input):\n return 2\n");
        assertNotEquals(ToolEvolutionService.fingerprint(first), ToolEvolutionService.fingerprint(second));
    }

    @Test void rollbackAndEnableRerunCodeTestsAndFailurePreservesCurrentVersionAndHistory() throws Exception {
        CodeExecutionService runtime = codeRuntime();
        assertEquals(true, evolve(codeProposal("adaptive_versioned_code", "upper")).get("registered"));
        ObjectNode update = codeProposal("adaptive_versioned_code", "lower"); update.put("expected_version", 1).put("extends_tool", "adaptive_versioned_code");
        assertEquals(2, evolve(update).get("version"));
        String before = persisted.get();
        doReturn(json.createObjectNode().put("ok", false).put("code", "EXECUTION_FAILED").put("error", "synthetic interpreter failure")).when(runtime).execute(anyString(), any());
        assertEquals(false, service.manage("adaptive_versioned_code", "rollback", 2, builtins, WRITES).get("ok"));
        assertEquals(before, persisted.get()); assertEquals(1, entry("adaptive_versioned_code").path("versions").size());
        assertEquals(true, service.manage("adaptive_versioned_code", "disable", 2, builtins, WRITES).get("ok"));
        String disabled = persisted.get();
        assertEquals(false, service.manage("adaptive_versioned_code", "enable", 2, builtins, WRITES).get("ok")); assertEquals(disabled, persisted.get());
        stubCodeExecution(runtime);
        assertEquals(3, service.manage("adaptive_versioned_code", "rollback", 2, builtins, WRITES).get("version"));
        assertEquals("AAB", service.execute("adaptive_versioned_code", read("{\"text\":\"AaB\"}"), builtins, WRITES, unexpectedCaller()).path("output").asText());
        assertFalse(service.manage("adaptive_versioned_code", "rollback", 2, builtins, WRITES).get("ok").equals(true));
    }

    @Test void infrastructureAndRoundBudgetFailuresDoNotDisableCorrectCodeTool() throws Exception {
        CodeExecutionService runtime = codeRuntime(); assertEquals(true, evolve(codeProposal("adaptive_reliable_code", "upper")).get("registered"));
        doReturn(json.createObjectNode().put("ok", false).put("code", "RUNNER_BUSY").put("error", "synthetic busy runner")).when(runtime).execute(anyString(), any());
        for (int i = 0; i < 3; i++) assertFalse(service.execute("adaptive_reliable_code", read("{\"text\":\"x\"}"), builtins, WRITES, unexpectedCaller()).path("ok").asBoolean());
        for (int i = 0; i < 3; i++) {
            JsonNode result = service.execute("adaptive_reliable_code", read("{\"text\":\"x\"}"), builtins, WRITES, unexpectedCaller(), (event, step) -> { throw new java.util.concurrent.CancellationException("synthetic exhausted round"); });
            assertEquals("adaptive_budget_exhausted", result.path("code").asText());
        }
        assertEquals(0, entry("adaptive_reliable_code").path("failures").asInt()); assertTrue(entry("adaptive_reliable_code").path("enabled").asBoolean());
        when(runtime.available()).thenReturn(false);
        assertTrue(service.definitions(builtins, WRITES).isEmpty());
        assertEquals("code_runner_unavailable", service.execute("adaptive_reliable_code", read("{\"text\":\"x\"}"), builtins, WRITES, unexpectedCaller()).path("code").asText());
    }

    @Test void generatedImplementationFailuresDisableOnlyAfterThreeActualFailures() throws Exception {
        CodeExecutionService runtime = codeRuntime(); assertEquals(true, evolve(codeProposal("adaptive_faulty_code", "upper")).get("registered"));
        doReturn(json.createObjectNode().put("ok", false).put("code", "EXECUTION_FAILED").put("error", "synthetic TypeError")).when(runtime).execute(anyString(), any());
        for (int i = 0; i < 3; i++) service.execute("adaptive_faulty_code", read("{\"text\":\"x\"}"), builtins, WRITES, unexpectedCaller());
        assertEquals(3, entry("adaptive_faulty_code").path("failures").asInt()); assertFalse(entry("adaptive_faulty_code").path("enabled").asBoolean());
    }

    @Test void builtinOverlapRequiresExplicitExtensionAndCodeDiagnosticsAreBounded() throws Exception {
        CodeExecutionService runtime = codeRuntime(); ObjectNode definition = codeProposal("adaptive_builtin_extension", "upper");
        List<Object> catalog = new ArrayList<>(builtins);
        catalog.add(Map.of("type", "function", "function", Map.of("name", "format_text", "description", definition.path("description").asText(), "parameters", Map.of("type", "object"))));
        assertEquals(false, service.evolve(definition, catalog, WRITES, "synthetic-session").get("ok"));
        definition.put("extends_tool", "format_text"); assertEquals(true, service.evolve(definition, catalog, WRITES, "synthetic-session").get("registered"));
        doReturn(json.createObjectNode().put("ok", false).put("code", "EXECUTION_FAILED").put("error", "e".repeat(9000)).put("stderr", "s".repeat(9000)).put("stdout", "o".repeat(9000))).when(runtime).execute(anyString(), any());
        JsonNode diagnostics = json.valueToTree(service.test(definition, catalog, WRITES)).path("tests").get(0);
        assertFalse(diagnostics.path("passed").asBoolean());
        assertTrue(diagnostics.path("codeSteps").get(0).path("stderr").asText().length() <= 2001);
        assertTrue(diagnostics.path("codeSteps").get(0).path("stdout").asText().length() <= 2001);
        assertTrue(diagnostics.path("error").asText().length() <= 4001);
    }

    @Test void mockedEarlyBusinessFailureCannotPretendGeneratedCodeWasTested() throws Exception {
        CodeExecutionService runtime = codeRuntime(); ObjectNode definition = codeProposal("adaptive_untested_code", "upper");
        ArrayNode steps = (ArrayNode) definition.path("steps");
        ObjectNode readStep = json.createObjectNode().put("id", "read").put("op", "call").put("tool", "get_note");
        readStep.putObject("arguments").set("title", ref("input.text")); steps.insert(0, readStep);
        ObjectNode missing = json.createObjectNode().put("ok", false).put("error", "synthetic missing note");
        for (JsonNode item : definition.path("tests")) {
            ObjectNode test = (ObjectNode) item; test.set("expected", missing.deepCopy());
            ObjectNode fixture = test.putArray("tool_results").addObject().put("tool", "get_note");
            fixture.putObject("arguments").put("title", test.path("input").path("text").asText()); fixture.set("result", missing.deepCopy());
        }
        JsonNode tested = json.valueToTree(service.test(definition, builtins, WRITES));
        assertFalse(tested.path("ok").asBoolean()); assertEquals(0, tested.path("passed").asInt());
        assertTrue(tested.path("tests").get(0).path("error").asText().contains("未实际执行"));
        assertEquals(false, evolve(definition).get("ok")); assertTrue(registry().path("tools").isEmpty());
        verify(runtime, never()).execute(anyString(), any());

        // The same explicitly expected native failure remains valid for a workflow with no generated code.
        ObjectNode transform = json.createObjectNode().put("id", "format").put("op", "transform").put("kind", "length");
        transform.set("value", ref("input.text")); steps.set(1, transform);
        assertEquals(true, service.test(definition, builtins, WRITES).get("ok"));
    }

    @Test void codeFingerprintPreservesObservableNumericTypesAndInputDictionaryOrder() throws Exception {
        ObjectNode first = codeProposal("adaptive_observable_json", "upper"); ObjectNode second = first.deepCopy();
        ((ObjectNode) first.path("steps").get(0)).set("input", read("{\"value\":1}"));
        ((ObjectNode) second.path("steps").get(0)).set("input", read("{\"value\":1.0}"));
        assertNotEquals(ToolEvolutionService.fingerprint(first), ToolEvolutionService.fingerprint(second));
        for (String value : List.of("1", "{\"$ref\":\"input.text\"}")) {
            ((ObjectNode) first.path("steps").get(0)).set("input", read("{\"first\":" + value + ",\"second\":2}"));
            ((ObjectNode) second.path("steps").get(0)).set("input", read("{\"second\":2,\"first\":" + value + "}"));
            assertNotEquals(ToolEvolutionService.fingerprint(first), ToolEvolutionService.fingerprint(second));
        }
    }

    private CodeExecutionService codeRuntime() {
        CodeExecutionService runtime = mock(CodeExecutionService.class);
        when(runtime.available()).thenReturn(true); when(runtime.status()).thenReturn(json.createObjectNode().put("available", true));
        when(runtime.analyze(anyString())).thenAnswer(invocation -> {
            // The real runner's AST normalization is covered by its integration tests; this isolates registry decisions.
            String sourceText = invocation.getArgument(0);
            String canonical = sourceText.replaceAll("(?m)#.*$", "").replaceAll("\\s+", "");
            String hash = java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(canonical.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
            ObjectNode response = json.createObjectNode().put("ok", true); response.putObject("output").put("fingerprint", hash); return response;
        });
        stubCodeExecution(runtime); service = new ToolEvolutionService(json, mapper, source, runtime); return runtime;
    }
    private void stubCodeExecution(CodeExecutionService runtime) {
        doAnswer(invocation -> {
            String sourceText = invocation.getArgument(0); JsonNode input = invocation.getArgument(1);
            String output = sourceText.contains(".upper()") ? input.path("text").asText().toUpperCase(java.util.Locale.ROOT) : input.path("text").asText().toLowerCase(java.util.Locale.ROOT);
            return json.createObjectNode().put("ok", true).put("output", output).put("stdout", "synthetic log").put("stderr", "").put("elapsedMs", 3);
        }).when(runtime).execute(anyString(), any());
    }
    private ObjectNode codeProposal(String name, String method) throws Exception {
        ObjectNode definition = proposal(name); definition.put("capability_key", "text_case_format"); definition.set("parameters", textSchema());
        ObjectNode step = definition.putArray("steps").addObject().put("id", "format").put("op", "code").put("language", "python").put("source", "def run(input):\n return input[\"text\"]." + method + "()\n");
        step.set("input", ref("input")); definition.set("output", ref("steps.format"));
        ArrayNode tests = definition.putArray("tests");
        tests.addObject().put("name", "normal case").put("kind", "normal").put("expected", method.equals("upper") ? "AAB" : "aab").set("input", json.createObjectNode().put("text", "AaB"));
        tests.addObject().put("name", "empty boundary").put("kind", "boundary").put("expected", "").set("input", json.createObjectNode().put("text", ""));
        return definition;
    }

    private ObjectNode normalize(String name) throws Exception {
        ObjectNode definition = proposal(name);
        definition.set("parameters", textSchema());
        definition.set("steps", read("""
                [{"id":"lines","op":"transform","kind":"split","value":{"$ref":"input.text"},"separator":","},
                 {"id":"distinct","op":"transform","kind":"unique","value":{"$ref":"steps.lines"}},
                 {"id":"joined","op":"transform","kind":"join","value":{"$ref":"steps.distinct"},"separator":"|"}]
                """));
        definition.set("output", ref("steps.joined"));
        definition.set("tests", read("""
                [{"name":"normal","input":{"text":"a,a,b"},"expected":"a|b"},
                 {"name":"empty boundary","input":{"text":""},"expected":""}]
                """));
        return definition;
    }

    private ObjectNode constantValue(String name, String stepId, JsonNode value) throws Exception {
        ObjectNode definition = proposal(name);
        definition.set("parameters", textSchema());
        ObjectNode step = json.createObjectNode().put("id", stepId).put("op", "transform");
        if (value.isObject()) {
            step.put("kind", "pick");
            step.set("value", value);
            step.set("fields", read("[\"id\"]"));
        } else {
            step.put("kind", "template").put("text", value.asText());
        }
        definition.set("steps", json.createArrayNode().add(step));
        definition.set("output", ref("steps." + stepId));
        ArrayNode tests = definition.putArray("tests");
        for (String input : List.of("A", "")) {
            ObjectNode test = tests.addObject();
            test.set("input", json.createObjectNode().put("text", input));
            test.set("expected", value.deepCopy());
        }
        return definition;
    }

    private ObjectNode literalDisplay(String name, JsonNode value, boolean template, String expected) throws Exception {
        ObjectNode definition = proposal(name);
        definition.set("parameters", textSchema());
        ArrayNode steps = definition.putArray("steps");
        ObjectNode first = steps.addObject().put("id", "items").put("op", "transform");
        first.set("value", value);
        if (template) {
            first.put("kind", "unique");
            steps.addObject().put("id", "display").put("op", "transform").put("kind", "template").put("text", "{{steps.items}}");
            definition.set("output", ref("steps.display"));
        } else {
            first.put("kind", "join").put("separator", "|");
            definition.set("output", ref("steps.items"));
        }
        ArrayNode tests = definition.putArray("tests");
        for (String input : List.of("A", "")) {
            ObjectNode test = tests.addObject().put("expected", expected);
            test.set("input", json.createObjectNode().put("text", input));
        }
        return definition;
    }

    private ObjectNode twoParameterTemplate(String name, String first, String second, boolean reverse) {
        ObjectNode definition = proposal(name);
        ObjectNode schema = json.createObjectNode().put("type", "object").put("additionalProperties", false);
        ObjectNode properties = schema.putObject("properties");
        for (String field : reverse ? List.of(second, first) : List.of(first, second)) properties.putObject(field).put("type", "string");
        ArrayNode required = schema.putArray("required");
        for (String field : reverse ? List.of(second, first) : List.of(first, second)) required.add(field);
        definition.set("parameters", schema);
        ObjectNode step = json.createObjectNode().put("id", "render").put("op", "transform").put("kind", "template")
                .put("text", "{{ input." + first + " }} / {{input." + second + "}}");
        definition.set("steps", json.createArrayNode().add(step));
        definition.set("output", ref("steps.render"));
        ArrayNode tests = definition.putArray("tests");
        for (String value : List.of("A", "")) {
            ObjectNode test = tests.addObject();
            test.set("input", json.createObjectNode().put(first, value).put(second, value.isEmpty() ? "" : "B"));
            test.put("expected", value.isEmpty() ? " / " : "A / B");
        }
        return definition;
    }

    private ObjectNode twoParameterCall(String name, String id, String prefix, boolean reverse) {
        ObjectNode definition = proposal(name);
        ObjectNode schema = json.createObjectNode().put("type", "object").put("additionalProperties", false);
        ObjectNode properties = schema.putObject("properties");
        for (String field : reverse ? List.of(prefix, id) : List.of(id, prefix)) properties.putObject(field).put("type", field.equals(id) ? "integer" : "string");
        schema.putArray("required").add(id).add(prefix);
        definition.set("parameters", schema);
        ObjectNode arguments = json.createObjectNode();
        if (reverse) { arguments.set("prefix", ref("input." + prefix)); arguments.set("note_id", ref("input." + id)); }
        else { arguments.set("note_id", ref("input." + id)); arguments.set("prefix", ref("input." + prefix)); }
        ObjectNode call = json.createObjectNode().put("id", "read").put("op", "call").put("tool", "get_note");
        call.set("arguments", arguments);
        ObjectNode transform = json.createObjectNode().put("id", "render").put("op", "transform").put("kind", "template").put("text", "{{steps.read.title}}");
        definition.set("steps", json.createArrayNode().add(call).add(transform));
        definition.set("output", ref("steps.render"));
        ArrayNode tests = definition.putArray("tests");
        for (int value : List.of(1, 2)) {
            String text = value == 1 ? "Synthetic" : "";
            ObjectNode test = tests.addObject();
            test.set("input", json.createObjectNode().put(id, value).put(prefix, text));
            ObjectNode fixture = test.putArray("tool_results").addObject().put("tool", "get_note");
            fixture.set("arguments", json.createObjectNode().put("note_id", value).put("prefix", text));
            fixture.set("result", json.createObjectNode().put("ok", true).put("title", text));
            test.put("expected", text);
        }
        return definition;
    }

    private ObjectNode wholeInput(String name, String property, boolean template) {
        ObjectNode definition = proposal(name);
        ObjectNode schema = json.createObjectNode().put("type", "object").put("additionalProperties", false);
        schema.putObject("properties").putObject(property).put("type", "string");
        schema.putArray("required").add(property);
        definition.set("parameters", schema);
        ObjectNode step = json.createObjectNode().put("id", "render").put("op", "transform");
        if (template) step.put("kind", "template").put("text", "{{input}}");
        else { step.put("kind", "length"); step.set("value", ref("input." + property)); }
        definition.set("steps", json.createArrayNode().add(step));
        definition.set("output", ref(template ? "steps.render" : "input"));
        ArrayNode tests = definition.putArray("tests");
        for (String value : List.of("A", "")) {
            ObjectNode input = json.createObjectNode().put(property, value);
            ObjectNode test = tests.addObject();
            test.set("input", input);
            if (template) test.put("expected", input.toString());
            else test.set("expected", input.deepCopy());
        }
        return definition;
    }

    private ObjectNode replacer(String name, String replacement) throws Exception {
        ObjectNode definition = proposal(name);
        definition.set("parameters", textSchema());
        ObjectNode step = json.createObjectNode().put("id", "replaced").put("op", "transform").put("kind", "replace")
                .put("search", "a").put("replacement", replacement).put("all", true);
        step.set("value", ref("input.text"));
        definition.set("steps", json.createArrayNode().add(step));
        definition.set("output", ref("steps.replaced"));
        ArrayNode tests = json.createArrayNode();
        tests.addObject().put("name", "normal").set("input", json.createObjectNode().put("text", "aaba"));
        ((ObjectNode) tests.get(0)).put("expected", replacement + replacement + "b" + replacement);
        tests.addObject().put("name", "empty boundary").set("input", json.createObjectNode().put("text", ""));
        ((ObjectNode) tests.get(1)).put("expected", "");
        definition.set("tests", tests);
        return definition;
    }

    private ObjectNode noteTitle(String name) throws Exception {
        ObjectNode definition = proposal(name);
        definition.set("parameters", noteIdSchema());
        definition.set("steps", read("""
                [{"id":"read","op":"call","tool":"get_note","arguments":{"note_id":{"$ref":"input.note_id"}}},
                 {"id":"formatted","op":"transform","kind":"template","text":"Title:{{steps.read.title}}"}]
                """));
        definition.set("output", ref("steps.formatted"));
        definition.set("tests", read("""
                [{"name":"normal","input":{"note_id":1},"tool_results":[{"tool":"get_note","arguments":{"note_id":1},"result":{"ok":true,"title":"Synthetic title"}}],"expected":"Title:Synthetic title"},
                 {"name":"empty title boundary","input":{"note_id":2},"tool_results":[{"tool":"get_note","arguments":{"note_id":2},"result":{"ok":true,"title":""}}],"expected":"Title:"}]
                """));
        return definition;
    }

    private ObjectNode proposal(String name) {
        ObjectNode definition = json.createObjectNode().put("name", name)
                .put("description", "处理合成笔记文本并返回确定性的结构化结果")
                .put("gap", "现有工具不能直接完成此处需要的确定性文本组合行为");
        definition.putArray("source_refs").addObject().put("path", SOURCE_PATH).put("sha256", SOURCE_HASH);
        return definition;
    }
    private JsonNode textSchema() throws Exception { return read("{\"type\":\"object\",\"properties\":{\"text\":{\"type\":\"string\"}},\"required\":[\"text\"],\"additionalProperties\":false}"); }
    private JsonNode noteIdSchema() throws Exception { return read("{\"type\":\"object\",\"properties\":{\"note_id\":{\"type\":\"integer\",\"minimum\":1}},\"required\":[\"note_id\"],\"additionalProperties\":false}"); }
    private Map<String, Object> evolve(ObjectNode definition) { return service.evolve(definition, builtins, WRITES, "synthetic-session"); }
    private JsonNode executePure(String name, String text) { return service.execute(name, json.createObjectNode().put("text", text), builtins, WRITES, (tool, args) -> { fail("Unexpected service call: " + tool); return null; }); }
    private JsonNode registry() throws Exception { return json.readTree(persisted.get()); }
    private JsonNode entry(String name) throws Exception {
        for (JsonNode entry : registry().path("tools")) if (name.equals(entry.path("name").asText())) return entry;
        throw new AssertionError("Missing synthetic tool " + name);
    }
    private JsonNode read(String raw) throws Exception { return json.readTree(raw); }
    private ObjectNode ref(String path) { return json.createObjectNode().put("$ref", path); }
    private Object builtin(String name) { return Map.of("type", "function", "function", Map.of("name", name, "description", "Synthetic built-in", "parameters", Map.of("type", "object"))); }
    private Object builtinWithSchema(String name, JsonNode schema) { return Map.of("type", "function", "function", Map.of("name", name, "description", "Synthetic built-in", "parameters", json.convertValue(schema, Map.class))); }
    @SuppressWarnings("unchecked") private BiFunction<String, JsonNode, JsonNode> unexpectedCaller() { return mock(BiFunction.class); }
}
