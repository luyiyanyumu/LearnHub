package org.dyh.learnhub.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class AgentContextMemoryTest {
    private final ObjectMapper json = new ObjectMapper();
    private final String goal = "请完整处理全部合成资料，保留来源及全部待处理目标；不要缩小范围。";

    @Test void smallTranscriptDoesNotSummarizeOrMutateAnything() throws Exception {
        List<Object> messages = conversation("small source", "latest one", "latest two");
        JsonNode before = json.valueToTree(messages);
        Recorder recorder = new Recorder();
        AgentContextMemory.Update update = AgentContextMemory.compact(messages, json, goal, recorder);
        assertEquals(0, update.exchanges());
        assertNull(update.memory());
        assertTrue(recorder.requests.isEmpty());
        assertEquals(before, json.valueToTree(messages));
    }

    @Test void oldWholeExchangeIsReplacedOnlyAfterEverySourceCharacterIncludingTailWasSubmitted() throws Exception {
        String source = "synthetic complete old source\n\n".repeat(5_000) + "OLD-FULL-TAIL-SENTINEL";
        List<Object> messages = conversation(source, "latest one", "latest two");
        JsonNode latest = json.valueToTree(messages.subList(4, 8));
        ObjectNode oldResult = (ObjectNode) messages.get(3);
        String originalResult = oldResult.path("content").asText();
        Recorder recorder = new Recorder();

        AgentContextMemory.Update update = AgentContextMemory.compact(messages, json, goal, recorder);

        assertEquals(1, update.exchanges());
        assertTrue(update.parts() > 1);
        assertEquals(originalResult.length() + "{\"note_id\":701}".length(), update.processedChars());
        assertEquals(source, recorder.field("old:result", "$/text"));
        assertTrue(recorder.requests.stream().anyMatch(r -> r.toString().contains("OLD-FULL-TAIL-SENTINEL")));
        assertEquals(originalResult, oldResult.path("content").asText());
        assertEquals(latest, json.valueToTree(messages.subList(3, 7)));
        assertNoToolId(messages, "old");
        assertPairs(messages);
        assertEquals(goal, json.valueToTree(messages.get(1)).path("content").asText());
        JsonNode memory = memory(update.memory());
        assertEquals(goal, memory.path("goal").asText());
        assertEquals(update.parts(), memory.path("parts_this_update").asInt());
        assertTrue(memory.path("notice").asText().contains("不是原文或全文核验证明"));
        assertEquals(2, memory.path("sources").size());
        assertTrue(update.memoryChars() <= AgentContextMemory.MEMORY_CHARS);
        recorder.assertBudgetsAndMathPrompt();
    }

    @Test void hugeSingleNewestSourceIsFullyProcessedRatherThanDiscardedAtHardLimit() throws Exception {
        String source = "single newest document ".repeat(14_000) + "SINGLE-SOURCE-LAST-FACT";
        List<Object> messages = new ArrayList<>(List.of(message("system", "全部合成系统约定"), message("user", goal),
                call("newest"), result("newest", source)));
        Recorder recorder = new Recorder();
        AgentContextMemory.Update update = AgentContextMemory.compact(messages, json, goal, recorder);
        assertEquals(1, update.exchanges());
        assertEquals(source, recorder.field("newest:result", "$/text"));
        assertTrue(json.valueToTree(messages).toString().length() < AgentLoopContext.HARD_CHARS);
        assertEquals(3, messages.size());
        assertEquals("user", json.valueToTree(messages.get(2)).path("role").asText());
        assertNoToolId(messages, "newest");
    }

    @Test void latestTwoGroupsRemainExactWhenTheyFitUnderHardBudgetWithoutOlderGroups() throws Exception {
        List<Object> messages = new ArrayList<>(List.of(message("system", "合成约定"), message("user", goal),
                call("one"), result("one", "one ".repeat(17_000)), call("two"), result("two", "two ".repeat(17_000))));
        JsonNode before = json.valueToTree(messages);
        Recorder recorder = new Recorder();
        AgentContextMemory.Update update = AgentContextMemory.compact(messages, json, goal, recorder);
        assertEquals(0, update.exchanges());
        assertNull(update.memory());
        assertEquals(before, json.valueToTree(messages));
        assertTrue(recorder.requests.isEmpty());
    }

    @Test void hardBudgetSelectionReservesSpaceForNewMemoryBeforeChoosingFreshGroups() throws Exception {
        String freshOne = "a".repeat(105_000) + "FRESH-ONE-TAIL";
        String freshTwo = "b".repeat(105_000) + "FRESH-TWO-TAIL";
        List<Object> messages = conversation("old full source ".repeat(10_000), freshOne, freshTwo);
        JsonNode newest = json.valueToTree(messages.subList(6, 8));
        Recorder recorder = new Recorder();
        AgentContextMemory.Update update = AgentContextMemory.compact(messages, json, goal, recorder);
        assertEquals(2, update.exchanges(), "210k remaining text needs room for the replacement memory");
        assertEquals(freshOne, recorder.field("fresh-one:result", "$/text"));
        assertEquals(newest, json.valueToTree(messages.subList(messages.size() - 2, messages.size())));
        assertTrue(json.valueToTree(messages).toString().length() <= AgentLoopContext.HARD_CHARS);
        assertPairs(messages);
    }

    @Test void twoFreshGroupsBetween190kAndHardLimitStayVerbatimWithoutUnnecessaryMemory() throws Exception {
        List<Object> messages = new ArrayList<>(List.of(message("system", "全部合成约定"), message("user", goal),
                call("fresh-a"), result("fresh-a", "a".repeat(100_000)), call("fresh-b"), result("fresh-b", "b".repeat(100_000))));
        JsonNode before = json.valueToTree(messages);
        assertTrue(before.toString().length() > AgentLoopContext.HARD_CHARS - AgentContextMemory.MEMORY_CHARS);
        assertTrue(before.toString().length() < AgentLoopContext.HARD_CHARS);
        Recorder recorder = new Recorder();
        AgentContextMemory.Update update = AgentContextMemory.compact(messages, json, goal, recorder);
        assertEquals(0, update.exchanges());
        assertNull(update.memory());
        assertTrue(recorder.requests.isEmpty());
        assertEquals(before, json.valueToTree(messages));
    }

    @Test void multiCallGroupKeepsEverySourceIdHashAndActualStagedOrErrorStatus() throws Exception {
        ObjectNode assistant = call("multi-a");
        assistant.withArray("tool_calls").add(call("multi-b").path("tool_calls").get(0));
        ObjectNode body = json.createObjectNode().put("ok", true).put("staged", true).put("action_id", 73);
        body.putArray("sources").addObject().put("id", 801).put("source", "synthetic-first.md")
                .put("content_hash", "a".repeat(64)).put("text", "source A ".repeat(17_000));
        body.withArray("sources").addObject().put("id", 802).put("source", "synthetic-second.md")
                .put("expected_hash", "b".repeat(64)).put("content", "source B ".repeat(17_000));
        List<Object> messages = new ArrayList<>(List.of(message("system", "合成约定"), message("user", goal), assistant,
                message("tool", body.toString()).put("tool_call_id", "multi-a").put("name", "search_knowledge"),
                message("tool", "{\"ok\":false,\"error\":\"synthetic-error\",\"id\":803}")
                        .put("tool_call_id", "multi-b").put("name", "get_file"),
                call("fresh-one"), result("fresh-one", "fresh"), call("fresh-two"), result("fresh-two", "fresh")));
        Recorder recorder = new Recorder();
        AgentContextMemory.Update update = AgentContextMemory.compact(messages, json, goal, recorder);
        JsonNode provenance = memory(update.memory()).path("sources");
        JsonNode sourceA = descriptor(provenance, "multi-a:result").path("metadata");
        assertTrue(sourceA.path("staged").asBoolean());
        assertEquals(73, sourceA.path("action_id").asInt());
        assertEquals(801, sourceA.path("sources").get(0).path("id").asInt());
        assertEquals("a".repeat(64), sourceA.path("sources").get(0).path("content_hash").asText());
        assertEquals(802, sourceA.path("sources").get(1).path("id").asInt());
        assertEquals("b".repeat(64), sourceA.path("sources").get(1).path("expected_hash").asText());
        assertFalse(descriptor(provenance, "multi-b:result").path("metadata").path("ok").asBoolean());
        assertEquals("synthetic-error", descriptor(provenance, "multi-b:result").path("metadata").path("error").asText());
        assertEquals(body.path("sources").get(1).path("content").asText(), recorder.field("multi-a:result", "$/sources/1/content"));
        assertNoToolId(messages, "multi-a");
        assertNoToolId(messages, "multi-b");
        assertPairs(messages);
    }

    @Test void completeLargeArgumentsAndAssistantBodyAreAlsoProcessedAndNeverReplayed() throws Exception {
        String argumentsText = "synthetic proposed edit ".repeat(8_000) + "ARGUMENT-END";
        ObjectNode assistant = call("old");
        assistant.put("content", "历史解释及约束");
        assistant.withArray("tool_calls").get(0).withObject("function").put("arguments",
                json.createObjectNode().put("note_id", 701).put("expected_hash", "c".repeat(64)).put("content", argumentsText).toString());
        assistant.put("reasoning_content", "synthetic private non-body reasoning ".repeat(8_000));
        List<Object> messages = conversation("source", "latest one", "latest two");
        messages.set(2, assistant);
        Recorder recorder = new Recorder();
        AgentContextMemory.Update update = AgentContextMemory.compact(messages, json, goal, recorder);
        assertEquals(argumentsText, recorder.field("old:arguments", "$/content"));
        assertEquals("历史解释及约束", recorder.field("old:assistant_body", "$"));
        assertFalse(recorder.requests.toString().contains("private non-body reasoning"));
        assertEquals("c".repeat(64), descriptor(memory(update.memory()).path("sources"), "old:arguments")
                .path("metadata").path("expected_hash").asText());
        assertNoToolId(messages, "old");
    }

    @Test void everyUnicodeEscapeAndBlankLineIsCoveredWithSerializedRequestsUnderBudget() throws Exception {
        String text = "\u0001\"\\🧪\n\n汉字\n".repeat(10_000) + "ESCAPED-LAST-FACT";
        List<Object> messages = conversation(text, "one", "two");
        Recorder recorder = new Recorder();
        AgentContextMemory.compact(messages, json, goal, recorder);
        assertEquals(text, recorder.field("old:result", "$/text"));
        recorder.assertBudgetsAndMathPrompt();
        for (JsonNode field : recorder.fields("old:result", "$/text")) {
            String fragment = field.path("fragment").asText();
            if (!fragment.isEmpty()) assertFalse(Character.isHighSurrogate(fragment.charAt(fragment.length() - 1)));
        }
    }

    @Test void displayMathAndBareNestedEnvironmentsStayWholeAcrossChunkBoundaries() throws Exception {
        String dollar = "$$\n\\begin{aligned}\n" + "a &= b + c \\\\\n".repeat(2_400) + "\\end{aligned}\n$$\n";
        String bracket = "\\[\n" + "x = y + z\n".repeat(400) + "\\]\n";
        String bare = "\\begin{align}\n\\begin{aligned}\na &= b\n\\end{aligned}\n"
                + "u &= v\n".repeat(2_000) + "\\end{align}\n";
        String text = "ordinary before\n".repeat(4_000) + dollar + "\n" + bracket + "\n" + bare + "after\n".repeat(6_000);
        Recorder recorder = new Recorder();
        AgentContextMemory.compact(conversation(text, "one", "two"), json, goal, recorder);
        assertEquals(text, recorder.field("old:result", "$/text"));
        for (String formula : List.of(dollar, bracket, bare))
            assertTrue(recorder.fields("old:result", "$/text").stream().anyMatch(f -> f.path("fragment").asText().contains(formula)));
    }

    @Test void escapedClosingDelimitersDoNotSplitMultiLineMath() throws Exception {
        String dollar = "$$\n\\begin{aligned}\n"
                + "x &= \\text{literal \\$$} + y \\\\\n"
                + "z &= w + q \\\\\n".repeat(2_400) + "\\end{aligned}\n$$\n";
        String bracket = "\\[\nx = \\text{literal \\\\]}\n" + "x = y + z\n".repeat(2_600) + "\\]\n";
        String text = "before\n".repeat(8_000) + dollar + bracket + "after\n".repeat(8_000);
        Recorder recorder = new Recorder();
        AgentContextMemory.compact(conversation(text, "one", "two"), json, goal, recorder);
        assertEquals(text, recorder.field("old:result", "$/text"));
        assertTrue(recorder.fields("old:result", "$/text").stream().anyMatch(f -> f.path("fragment").asText().contains(dollar)));
        assertTrue(recorder.fields("old:result", "$/text").stream().anyMatch(f -> f.path("fragment").asText().contains(bracket)));
    }

    @Test void hugeUnbreakableMathFailsBeforeCallbacksAndLeavesTranscriptExactlyIntact() {
        String formula = "$$\n" + "x &= \u0001\n".repeat(20_000) + "$$\n";
        List<Object> messages = conversation(formula, "one", "two");
        JsonNode before = json.valueToTree(messages);
        Recorder recorder = new Recorder();
        assertThrows(IllegalArgumentException.class, () -> AgentContextMemory.compact(messages, json, goal, recorder));
        assertEquals(before, json.valueToTree(messages));
        assertTrue(recorder.requests.isEmpty());
    }

    @Test void callbackFailureOrTimeBudgetFailureDoesNotLoseSourcesOrEarlierMemory() throws Exception {
        List<Object> messages = conversation("old text ".repeat(14_000), "one", "two");
        Recorder first = new Recorder();
        AgentContextMemory.compact(messages, json, goal, first);
        messages.add(call("new"));
        messages.add(result("new", "new full source ".repeat(25_000)));
        JsonNode before = json.valueToTree(messages);
        AtomicInteger calls = new AtomicInteger();
        assertThrows(IOException.class, () -> AgentContextMemory.compact(messages, json, goal, request -> {
            if (calls.incrementAndGet() == 2) throw new IOException("synthetic deadline exhausted");
            return new AgentContextMemory.Summary("已处理分片的合成摘要，仍需继续。", false);
        }));
        assertEquals(before, json.valueToTree(messages));
        assertEquals(2, calls.get());
        assertTrue(messages.stream().anyMatch(m -> json.valueToTree(m).path("content").asText("").startsWith(AgentContextMemory.PREFIX)));
    }

    @Test void nullEmptyTruncatedAndOversizedSummariesAllLeaveMessagesUnchanged() {
        List<AgentContextMemory.Summary> invalid = Arrays.asList(null, new AgentContextMemory.Summary("", false),
                new AgentContextMemory.Summary("  ", false), new AgentContextMemory.Summary("partial", true),
                new AgentContextMemory.Summary("x".repeat(6_001), false));
        for (AgentContextMemory.Summary value : invalid) {
            List<Object> messages = conversation("old text ".repeat(14_000), "one", "two");
            JsonNode before = json.valueToTree(messages);
            assertThrows(IllegalArgumentException.class, () -> AgentContextMemory.compact(messages, json, goal, ignored -> value));
            assertEquals(before, json.valueToTree(messages));
        }
    }

    @Test void reducerFailureAfterAllMapPartsStillLeavesEveryOriginalMessage() {
        List<Object> messages = conversation("old text ".repeat(14_000), "one", "two");
        JsonNode before = json.valueToTree(messages);
        AtomicInteger maps = new AtomicInteger();
        assertThrows(IOException.class, () -> AgentContextMemory.compact(messages, json, goal, request -> {
            if (input(request).path("phase").asText().equals("reduce")) throw new IOException("synthetic reduce failure");
            maps.incrementAndGet();
            return new AgentContextMemory.Summary("合成分片摘要，尚未完成用户目标。", false);
        }));
        assertTrue(maps.get() > 1);
        assertEquals(before, json.valueToTree(messages));
    }

    @Test void successfulRollingMemoryIncludesEarlierSourcesAndNewFullTails() throws Exception {
        List<Object> messages = conversation("first old source ".repeat(9_000) + "FIRST-TAIL", "one", "two");
        AgentContextMemory.Update first = AgentContextMemory.compact(messages, json, goal, new Recorder());
        messages.add(call("new"));
        String newSource = "new full source ".repeat(25_000) + "NEW-TAIL";
        messages.add(result("new", newSource));
        Recorder secondRecorder = new Recorder();
        AgentContextMemory.Update second = AgentContextMemory.compact(messages, json, goal, secondRecorder);
        JsonNode rolled = memory(second.memory());
        assertEquals(first.processedChars() + second.processedChars(), rolled.path("processed_chars").asLong());
        assertEquals(first.parts() + second.parts(), rolled.path("parts").asInt());
        assertTrue(descriptor(rolled.path("sources"), "old:result").path("historical").asBoolean());
        assertEquals("a".repeat(64), descriptor(rolled.path("sources"), "old:result").path("metadata").path("content_hash").asText());
        assertEquals(newSource, secondRecorder.field("new:result", "$/text"));
        assertTrue(secondRecorder.requests.toString().contains("historical-memory-0"));
        assertEquals(1, messages.stream().filter(m -> json.valueToTree(m).path("content").asText("").startsWith(AgentContextMemory.PREFIX)).count());
        assertPairs(messages);
        secondRecorder.assertBudgetsAndMathPrompt();
    }

    @Test void followUpContinueKeepsEveryOriginalGoalVerbatimOutsideLossySummary() throws Exception {
        List<Object> messages = conversation("first source ".repeat(12_000), "one", "two");
        AgentContextMemory.Update first = AgentContextMemory.compact(messages, json, goal, new Recorder());
        assertEquals(goal, memory(first.memory()).path("goals").get(0).asText());
        messages.add(message("user", "继续"));
        messages.add(call("continue-source"));
        messages.add(result("continue-source", "new complete source ".repeat(20_000) + "CONTINUE-SOURCE-TAIL"));
        Recorder recorder = new Recorder();
        AgentContextMemory.Update next = AgentContextMemory.compact(messages, json, "继续", recorder);
        JsonNode saved = memory(next.memory());
        assertEquals("继续", saved.path("goal").asText());
        assertEquals(List.of(goal, "继续"), json.convertValue(saved.path("goals"), List.class));
        assertTrue(saved.path("notice").asText().contains("当前的显式修改优先"));
        assertTrue(recorder.requests.toString().contains("CONTINUE-SOURCE-TAIL"));
        for (List<Object> request : recorder.requests)
            assertEquals(List.of(goal, "继续"), json.convertValue(input(request).path("goals"), List.class));
        assertEquals(1, messages.stream().filter(m -> json.valueToTree(m).path("content").asText("").startsWith(AgentContextMemory.PREFIX)).count());
        assertPairs(messages);
    }

    @Test void oversizedSourceMetadataIsRejectedRatherThanLosingIdsOrHashes() {
        ObjectNode body = json.createObjectNode().put("ok", true);
        ArrayNode sources = body.putArray("sources");
        for (int i = 0; i < 800; i++) sources.addObject().put("id", i).put("content_hash", "d".repeat(64))
                .put("url", "https://synthetic.invalid/" + "x".repeat(90)).put("text", "synthetic source body");
        List<Object> messages = conversation("unused", "one", "two");
        ((ObjectNode) messages.get(3)).put("content", body.toString());
        JsonNode before = json.valueToTree(messages);
        Recorder recorder = new Recorder();
        assertThrows(IllegalArgumentException.class, () -> AgentContextMemory.compact(messages, json, goal, recorder));
        assertEquals(before, json.valueToTree(messages));
        assertTrue(recorder.requests.isEmpty());
    }

    @Test void longScalarReferenceCollectionsKeepAllIdsHashesAndAliasesWithoutRetainingBodyArrays() throws Exception {
        ObjectNode body = json.createObjectNode().put("ok", true).put("text", "synthetic full body ".repeat(8_000));
        for (String key : List.of("source_ids", "content_hashes", "sourceRefs", "source_refs")) {
            ArrayNode references = body.putArray(key);
            for (int i = 0; i < 32; i++) references.add(key.equals("source_ids") ? json.getNodeFactory().numberNode(i)
                    : json.getNodeFactory().textNode(key + "-" + i + "-" + "a".repeat(30)));
            assertTrue(references.toString().length() > 600 || key.equals("source_ids"));
        }
        // Force a scalar-ID list itself beyond the old 600-character cutoff.
        for (int i = 32; i < 200; i++) body.withArray("source_ids").add(100_000 + i);
        body.putArray("paragraphs").add("ordinary source paragraph ".repeat(200)).add("second ordinary paragraph ".repeat(200));
        List<Object> messages = conversation("unused", "one", "two");
        ((ObjectNode) messages.get(3)).put("content", body.toString());
        Recorder recorder = new Recorder();
        AgentContextMemory.Update update = AgentContextMemory.compact(messages, json, goal, recorder);
        JsonNode saved = descriptor(memory(update.memory()).path("sources"), "old:result").path("metadata");
        for (String key : List.of("source_ids", "content_hashes", "sourceRefs", "source_refs")) assertEquals(body.get(key), saved.get(key));
        assertFalse(saved.has("paragraphs"));
        assertEquals(body.path("paragraphs").get(1).asText(), recorder.field("old:result", "$/paragraphs/1"));
    }

    @Test void oversizedScalarReferenceCollectionFailsBeforeCallbacksInsteadOfDroppingItsTail() {
        ObjectNode body = json.createObjectNode().put("ok", true);
        ArrayNode references = body.putArray("source_refs");
        for (int i = 0; i < 1_000; i++) references.add("synthetic-source-" + i + "-" + "x".repeat(110));
        List<Object> messages = conversation("unused", "one", "two");
        ((ObjectNode) messages.get(3)).put("content", body.toString());
        JsonNode before = json.valueToTree(messages);
        Recorder recorder = new Recorder();
        assertThrows(IllegalArgumentException.class, () -> AgentContextMemory.compact(messages, json, goal, recorder));
        assertTrue(recorder.requests.isEmpty());
        assertEquals(before, json.valueToTree(messages));
    }

    @Test void summaryMustFitActualSerializedMemoryBudgetNotOnlyCharacterCount() {
        List<Object> messages = conversation("old source ".repeat(14_000), "one", "two");
        JsonNode before = json.valueToTree(messages);
        assertThrows(IllegalArgumentException.class, () -> AgentContextMemory.compact(messages, json, goal,
                ignored -> new AgentContextMemory.Summary("\u0001".repeat(5_900), false)));
        assertEquals(before, json.valueToTree(messages));
    }

    @Test void incompleteToolExchangeIsNeverRemovedOrSummarized() throws Exception {
        ObjectNode incomplete = call("pending");
        incomplete.withArray("tool_calls").get(0).withObject("function").put("arguments", "x".repeat(250_000));
        List<Object> messages = new ArrayList<>(List.of(message("system", "约定"), message("user", goal), incomplete));
        JsonNode before = json.valueToTree(messages);
        Recorder recorder = new Recorder();
        assertEquals(0, AgentContextMemory.compact(messages, json, goal, recorder).exchanges());
        assertEquals(before, json.valueToTree(messages));
        assertTrue(recorder.requests.isEmpty());
    }

    private List<Object> conversation(String old, String one, String two) {
        return new ArrayList<>(List.of(message("system", "全部合成系统约定"), message("user", goal),
                call("old"), result("old", old), call("fresh-one"), result("fresh-one", one),
                call("fresh-two"), result("fresh-two", two)));
    }
    private ObjectNode message(String role, String content) { return json.createObjectNode().put("role", role).put("content", content); }
    private ObjectNode call(String id) {
        ObjectNode message = message("assistant", "");
        message.putArray("tool_calls").addObject().put("id", id).put("type", "function").putObject("function")
                .put("name", "get_note").put("arguments", "{\"note_id\":701}");
        return message;
    }
    private ObjectNode result(String id, String text) {
        return message("tool", json.createObjectNode().put("ok", true).put("note_id", 701).put("content_hash", "a".repeat(64))
                .put("text", text).toString()).put("tool_call_id", id).put("name", "get_note");
    }
    private JsonNode memory(String content) throws Exception {
        assertTrue(content.startsWith(AgentContextMemory.PREFIX));
        return json.readTree(content.substring(AgentContextMemory.PREFIX.length()));
    }
    private JsonNode descriptor(JsonNode sources, String ref) {
        for (JsonNode source : sources) if (source.path("source_ref").asText().equals(ref)) return source;
        fail("missing source " + ref);
        return null;
    }
    private JsonNode input(List<Object> request) throws Exception { return json.readTree(json.valueToTree(request.get(1)).path("content").asText()); }
    private void assertNoToolId(List<Object> messages, String id) {
        for (Object raw : messages) {
            JsonNode message = json.valueToTree(raw);
            assertNotEquals(id, message.path("tool_call_id").asText());
            for (JsonNode call : message.path("tool_calls")) assertNotEquals(id, call.path("id").asText());
        }
    }
    private void assertPairs(List<Object> messages) {
        for (int i = 0; i < messages.size(); i++) {
            JsonNode message = json.valueToTree(messages.get(i));
            if (!message.path("tool_calls").isArray()) continue;
            int at = i + 1;
            for (JsonNode call : message.path("tool_calls")) {
                JsonNode result = json.valueToTree(messages.get(at++));
                assertEquals("tool", result.path("role").asText());
                assertEquals(call.path("id").asText(), result.path("tool_call_id").asText());
            }
        }
    }
    private final class Recorder implements AgentContextMemory.Summarizer {
        final List<List<Object>> requests = new ArrayList<>();
        @Override public AgentContextMemory.Summary summarize(List<Object> messages) {
            requests.add(messages);
            return new AgentContextMemory.Summary("来源合成摘要：保留来源记录与完整用户目标，尚未核验完成，继续处理未覆盖事项。", false);
        }
        List<JsonNode> fields(String ref, String path) throws Exception {
            List<JsonNode> found = new ArrayList<>();
            for (List<Object> request : requests) for (JsonNode part : input(request).path("parts")) {
                if (!part.path("source").path("source_ref").asText().equals(ref)) continue;
                for (JsonNode field : part.path("fields")) if (field.path("path").asText().equals(path)) found.add(field);
            }
            return found;
        }
        String field(String ref, String path) throws Exception {
            Map<Integer, String> ordered = new TreeMap<>();
            for (JsonNode field : fields(ref, path)) {
                assertNull(ordered.put(field.path("offset").asInt(), field.path("fragment").asText()), "part repeated");
            }
            StringBuilder full = new StringBuilder();
            for (Map.Entry<Integer, String> part : ordered.entrySet()) {
                assertEquals(full.length(), part.getKey(), "source field gap");
                full.append(part.getValue());
            }
            return full.toString();
        }
        void assertBudgetsAndMathPrompt() throws Exception {
            assertFalse(requests.isEmpty());
            for (List<Object> request : requests) {
                assertTrue(json.valueToTree(request).toString().length() <= AgentContextMemory.REQUEST_CHARS);
                String system = json.valueToTree(request.get(0)).path("content").asText();
                assertTrue(system.startsWith(AgentContextMemory.SUMMARY_PREFIX));
                assertTrue(system.contains(NoteMathPrompt.GENERATE));
                assertEquals(goal, input(request).path("goal").asText());
                assertEquals(2, request.size());
            }
        }
    }
}
