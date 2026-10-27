package org.dyh.learnhub.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class NoteSectionScopeTest {
    private final ObjectMapper json = new ObjectMapper();
    private static final long NOTE_ID = 701;
    private static final String HASH = "synthetic-content-hash";

    @Test void sectionToolAlwaysPlansAndUnrelatedToolsNeverPlan() {
        assertTrue(NoteSectionScope.mayNeedPlanning("write_note_sections", null));
        assertTrue(NoteSectionScope.mayNeedPlanning("write_note_sections", json.createObjectNode()));
        for (String name : List.of("create_note", "get_note", "update_note", "edit_note "))
            assertFalse(NoteSectionScope.mayNeedPlanning(name, edits(operation("replace", "合".repeat(1_200)))));
        assertFalse(NoteSectionScope.mayNeedPlanning(null, null));
        assertFalse(NoteSectionScope.mayNeedPlanning("edit_note", null));
        assertFalse(NoteSectionScope.mayNeedPlanning("append_to_note", json.createArrayNode()));
    }

    @Test void editSizeThresholdIncludesOnlyNewOrReplacementValues() {
        assertFalse(needs(operation("replace", "合".repeat(1_199))));
        assertTrue(needs(operation("replace", "合".repeat(1_200))));
        assertTrue(needs(operation("replace", "合".repeat(700)), operation("insert_after", "成".repeat(500))));
        assertFalse(needs(operation("format", "合".repeat(2_000)), operation("delete", "成".repeat(2_000))));
    }

    @Test void multipleInsertionsTriggerAtFourHundredCombinedCharacters() {
        assertFalse(needs(operation("insert_after", "合".repeat(399))));
        assertFalse(needs(operation("insert_after", "合".repeat(199)), operation("insert_before", "成".repeat(200))));
        assertTrue(needs(operation("insert_after", "合".repeat(200)), operation("insert_before", "成".repeat(200))));
        assertFalse(needs(operation("replace", "合".repeat(200)), operation("insert_before", "成".repeat(200))));
    }

    @Test void headingsAcrossOperationsTriggerWithoutCountingFencedProgramComments() {
        assertTrue(needs(operation("replace", "## 合成第一节\n正文\n\n## 合成第二节\n正文")));
        assertTrue(needs(operation("replace", "## 合成甲\n正文"), operation("insert_after", "## 合成乙\n正文")));
        assertFalse(needs(operation("replace", "```python\n# comment one\n# comment two\n```")));
        assertFalse(needs(operation("format", "## 合成甲\n\n## 合成乙")));
        assertTrue(needs(operation("replace", "合成甲\n===\n正文\n\n合成乙\n---\n正文")));
    }

    @Test void appendAcceptsProductionMarkdownAndLegacyContentKeys() {
        for (String key : List.of("markdown", "content")) {
            assertFalse(NoteSectionScope.mayNeedPlanning("append_to_note", json.createObjectNode().put(key, "合".repeat(1_199))));
            assertTrue(NoteSectionScope.mayNeedPlanning("append_to_note", json.createObjectNode().put(key, "合".repeat(1_200))));
            assertTrue(NoteSectionScope.mayNeedPlanning("append_to_note", json.createObjectNode().put(key, "## 合成甲\n正文\n\n## 合成乙\n正文")));
        }
        assertTrue(NoteSectionScope.mayNeedPlanning("append_to_note", json.createObjectNode()
                .put("markdown", "").put("content", "合".repeat(1_200))));
    }

    @Test void exactCannotReplaceOriginalOperationsOrBindToAModelChosenNote() {
        ObjectNode model = json.createObjectNode().put("mode", "exact").put("note_id", 999)
                .put("expected_hash", "untrusted-model-hash");
        model.putArray("operations").add(operation("replace", "不应采用"));
        NoteSectionScope.Plan parsed = NoteSectionScope.parsePlan(model, NOTE_ID, HASH);
        assertEquals("exact", parsed.mode());
        assertEquals("", parsed.goal());
        assertEquals(2, parsed.arguments().size());
        assertEquals(NOTE_ID, parsed.arguments().path("note_id").asLong());
        assertEquals(HASH, parsed.arguments().path("expected_hash").asText());
        assertFalse(parsed.arguments().has("operations"));
    }

    @Test void completePlanFreezesFiftyUnitsInOrderAndKeepsExactWhitespaceInAnchors() {
        ObjectNode model = plan(50);
        ObjectNode first = (ObjectNode) model.path("units").get(0);
        first.put("text", "  合成定位\n").put("occurrence", 2).put("prefix", " 前文 ").put("suffix", "\n后文");
        NoteSectionScope.Plan parsed = NoteSectionScope.parsePlan(model, NOTE_ID, HASH);
        assertEquals("sections", parsed.mode());
        assertEquals("完整补写合成资料全部章节", parsed.goal());
        assertEquals(50, parsed.arguments().path("tasks").size());
        JsonNode task = parsed.arguments().path("tasks").get(0);
        assertEquals("unit-1", task.path("unit_id").asText());
        assertFalse(task.has("id"));
        assertEquals("  合成定位\n", task.path("text").asText());
        assertEquals(2, task.path("occurrence").asInt());
        assertEquals(" 前文 ", task.path("prefix").asText());
        assertEquals("\n后文", task.path("suffix").asText());
        assertDoesNotThrow(() -> NoteSectionScope.validateCoverage(parsed.arguments(), parsed.arguments()));
    }

    @Test void planDefensivelyCopiesBothInputAndReturnedArguments() {
        ObjectNode model = plan(2);
        NoteSectionScope.Plan parsed = NoteSectionScope.parsePlan(model, NOTE_ID, HASH);
        ((ObjectNode) model.path("units").get(0)).put("text", "调用者改动");
        ObjectNode returned = parsed.arguments();
        ((ObjectNode) returned.path("tasks").get(0)).put("text", "接收者改动");
        assertEquals("合成原文定位1", parsed.arguments().path("tasks").get(0).path("text").asText());
        ObjectNode supplied = json.createObjectNode().put("marker", "before");
        NoteSectionScope.Plan constructed = new NoteSectionScope.Plan("exact", "", supplied);
        supplied.put("marker", "after");
        assertEquals("before", constructed.arguments().path("marker").asText());
    }

    @Test void incompleteOrDishonestManifestIsRejected() {
        for (JsonNode value : List.<JsonNode>of(json.valueToTree(false), json.valueToTree("true"), json.nullNode())) {
            ObjectNode invalid = plan(2); invalid.set("complete", value);
            rejects(invalid);
        }
        ObjectNode missingComplete = plan(2); missingComplete.remove("complete"); rejects(missingComplete);
        ObjectNode missingCount = plan(2); missingCount.remove("unitCount"); rejects(missingCount);
        for (JsonNode count : List.<JsonNode>of(json.valueToTree(1), json.valueToTree(3), json.valueToTree(2.0), json.valueToTree("2"))) {
            ObjectNode invalid = plan(2); invalid.set("unitCount", count); rejects(invalid);
        }
    }

    @Test void emptyOversizeOrNonArrayScopeIsRejected() {
        rejects(plan(0)); rejects(plan(51));
        ObjectNode invalid = plan(1); invalid.set("units", json.createObjectNode()); rejects(invalid);
        ObjectNode malformed = plan(1); ((ArrayNode) malformed.path("units")).set(0, json.valueToTree("not-a-unit")); rejects(malformed);
    }

    @Test void missingDuplicateOrInvalidUnitFieldsAreRejected() {
        for (String key : List.of("id", "heading", "instructions", "action", "text")) {
            ObjectNode missing = plan(1); ((ObjectNode) missing.path("units").get(0)).remove(key); rejects(missing);
            ObjectNode blank = plan(1); ((ObjectNode) blank.path("units").get(0)).put(key, " \n"); rejects(blank);
            ObjectNode nonText = plan(1); ((ObjectNode) nonText.path("units").get(0)).put(key, 42); rejects(nonText);
        }
        ObjectNode duplicate = plan(2); ((ObjectNode) duplicate.path("units").get(1)).put("id", "unit-1"); rejects(duplicate);
        for (String action : List.of("delete", "format", "insert_toc", "append")) {
            ObjectNode invalid = plan(1); ((ObjectNode) invalid.path("units").get(0)).put("action", action); rejects(invalid);
        }
    }

    @Test void headingAndInstructionsAllowTwelveThousandCharactersButNotMore() {
        for (String key : List.of("heading", "instructions")) {
            ObjectNode boundary = plan(1); ((ObjectNode) boundary.path("units").get(0)).put(key, "合".repeat(12_000));
            assertDoesNotThrow(() -> NoteSectionScope.parsePlan(boundary, NOTE_ID, HASH));
            ((ObjectNode) boundary.path("units").get(0)).put(key, "合".repeat(12_001)); rejects(boundary);
        }
    }

    @Test void optionalLocatorFieldsMustBeValidAndAllThreeActionsAreAccepted() {
        for (String action : List.of("insert_before", "insert_after", "replace")) {
            ObjectNode accepted = plan(1); ((ObjectNode) accepted.path("units").get(0)).put("action", action).put("prefix", "").put("suffix", "");
            assertDoesNotThrow(() -> NoteSectionScope.parsePlan(accepted, NOTE_ID, HASH));
        }
        for (JsonNode occurrence : List.<JsonNode>of(json.valueToTree(0), json.valueToTree(-1), json.valueToTree(1.5), json.valueToTree("1"), json.nullNode())) {
            ObjectNode invalid = plan(1); ((ObjectNode) invalid.path("units").get(0)).set("occurrence", occurrence); rejects(invalid);
        }
        for (String key : List.of("prefix", "suffix")) {
            ObjectNode invalid = plan(1); ((ObjectNode) invalid.path("units").get(0)).put(key, 3); rejects(invalid);
        }
    }

    @Test void unknownModesMissingGoalAndInvalidBindingAreRejected() {
        for (String mode : List.of("", "partial", "Sections", "all")) {
            ObjectNode invalid = plan(1); invalid.put("mode", mode); rejects(invalid);
        }
        ObjectNode missingGoal = plan(1); missingGoal.remove("goal"); rejects(missingGoal);
        assertThrows(IllegalArgumentException.class, () -> NoteSectionScope.parsePlan(null, NOTE_ID, HASH));
        assertThrows(IllegalArgumentException.class, () -> NoteSectionScope.parsePlan(json.createArrayNode(), NOTE_ID, HASH));
        assertThrows(IllegalArgumentException.class, () -> NoteSectionScope.parsePlan(plan(1), 0, HASH));
        assertThrows(IllegalArgumentException.class, () -> NoteSectionScope.parsePlan(plan(1), NOTE_ID, " "));
    }

    @Test void coverageRejectsMissingExtraDuplicateAndReorderedUnits() {
        ObjectNode planned = NoteSectionScope.parsePlan(plan(3), NOTE_ID, HASH).arguments();
        ObjectNode missing = planned.deepCopy(); ((ArrayNode) missing.path("tasks")).remove(2); rejectsCoverage(planned, missing);
        ObjectNode extra = planned.deepCopy(); ((ArrayNode) extra.path("tasks")).add(planned.path("tasks").get(0)); rejectsCoverage(planned, extra);
        ObjectNode duplicate = planned.deepCopy(); ((ObjectNode) duplicate.path("tasks").get(1)).put("unit_id", "unit-1"); rejectsCoverage(planned, duplicate);
        ObjectNode reordered = planned.deepCopy(); ArrayNode tasks = (ArrayNode) reordered.path("tasks");
        JsonNode first = tasks.get(0); tasks.set(0, tasks.get(1)); tasks.set(1, first); rejectsCoverage(planned, reordered);
    }

    @Test void coverageRejectsChangedVersionScopeInstructionsAndLocators() {
        ObjectNode model = plan(1); ((ObjectNode) model.path("units").get(0)).put("occurrence", 2).put("prefix", "前").put("suffix", "后");
        ObjectNode planned = NoteSectionScope.parsePlan(model, NOTE_ID, HASH).arguments();
        ObjectNode changedId = planned.deepCopy(); changedId.put("note_id", NOTE_ID + 1); rejectsCoverage(planned, changedId);
        ObjectNode changedHash = planned.deepCopy(); changedHash.put("expected_hash", "different-synthetic-version"); rejectsCoverage(planned, changedHash);
        for (String field : List.of("unit_id", "heading", "instructions", "action", "text", "occurrence", "prefix", "suffix")) {
            ObjectNode changed = planned.deepCopy(); ((ObjectNode) changed.path("tasks").get(0)).put(field, "changed"); rejectsCoverage(planned, changed);
            ObjectNode missing = planned.deepCopy(); ((ObjectNode) missing.path("tasks").get(0)).remove(field); rejectsCoverage(planned, missing);
        }
        assertThrows(IllegalArgumentException.class, () -> NoteSectionScope.validateCoverage(null, planned));
        assertThrows(IllegalArgumentException.class, () -> NoteSectionScope.validateCoverage(planned, null));
        ObjectNode missingTasks = planned.deepCopy(); missingTasks.remove("tasks"); rejectsCoverage(planned, missingTasks);
        ObjectNode missingUnitId = planned.deepCopy(); ((ObjectNode) missingUnitId.path("tasks").get(0)).remove("unit_id"); rejectsCoverage(planned, missingUnitId);
        ObjectNode invalidVersion = planned.deepCopy(); invalidVersion.remove("expected_hash"); rejectsCoverage(invalidVersion, invalidVersion);
        ObjectNode intNoteId = planned.deepCopy(); intNoteId.put("note_id", (int) NOTE_ID);
        assertDoesNotThrow(() -> NoteSectionScope.validateCoverage(planned, intNoteId));
    }

    private boolean needs(ObjectNode... operations) { return NoteSectionScope.mayNeedPlanning("edit_note", edits(operations)); }

    private ObjectNode edits(ObjectNode... operations) {
        ObjectNode args = json.createObjectNode();
        ArrayNode values = args.putArray("operations");
        for (ObjectNode operation : operations) values.add(operation);
        return args;
    }

    private ObjectNode operation(String action, String value) {
        return json.createObjectNode().put("action", action).put("text", "合成定位").put("value", value);
    }

    private ObjectNode plan(int count) {
        ObjectNode plan = json.createObjectNode().put("mode", "sections").put("goal", "完整补写合成资料全部章节")
                .put("complete", true).put("unitCount", count);
        ArrayNode units = plan.putArray("units");
        for (int index = 1; index <= count; index++) units.addObject().put("id", "unit-" + index)
                .put("heading", "合成章节" + index).put("instructions", "完整补写合成定义与示例" + index)
                .put("action", "insert_after").put("text", "合成原文定位" + index);
        return plan;
    }

    private void rejects(JsonNode model) {
        assertThrows(IllegalArgumentException.class, () -> NoteSectionScope.parsePlan(model, NOTE_ID, HASH));
    }

    private void rejectsCoverage(JsonNode planned, JsonNode actual) {
        assertThrows(IllegalArgumentException.class, () -> NoteSectionScope.validateCoverage(planned, actual));
    }
}
