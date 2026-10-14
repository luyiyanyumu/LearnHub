package org.dyh.learnhub.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.dyh.learnhub.entity.AgentEvent;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class AgentLoopContextTest {
    private final ObjectMapper json = new ObjectMapper();

    @Test void belowSoftLimitKeepsEveryMessageUnchanged() {
        List<Object> messages = conversation("x".repeat(8_000), "fresh one", "fresh two");
        JsonNode before = json.valueToTree(messages);
        Object firstResult = messages.get(3);

        assertTrue(AgentLoopContext.size(messages, json) < AgentLoopContext.SOFT_CHARS);
        assertEquals(0, AgentLoopContext.compact(messages, json));

        assertEquals(before, json.valueToTree(messages));
        assertSame(firstResult, messages.get(3));
    }

    @Test void oldResultShrinksWithoutLosingToolPairsOrSourceVersionMetadata() throws Exception {
        String oldSource = "synthetic-old-source-".repeat(8_000);
        String freshSourceOne = "synthetic-fresh-one-".repeat(500);
        String freshSourceTwo = "synthetic-fresh-two-".repeat(500);
        List<Object> messages = conversation(oldSource, freshSourceOne, freshSourceTwo);
        JsonNode before = json.valueToTree(messages);
        ObjectNode oldResult = (ObjectNode) messages.get(3);
        int originalSize = AgentLoopContext.size(messages, json);

        assertTrue(originalSize > AgentLoopContext.SOFT_CHARS);
        assertEquals(1, AgentLoopContext.compact(messages, json));

        assertEquals(before.size(), messages.size());
        assertTrue(AgentLoopContext.size(messages, json) < AgentLoopContext.SOFT_CHARS);
        assertTrue(AgentLoopContext.size(messages, json) < originalSize);
        for (int index : List.of(0, 1, 2, 4, 5, 6, 7)) {
            assertEquals(before.get(index), json.valueToTree(messages.get(index)), "only the old tool result may change");
        }
        JsonNode compactedMessage = json.valueToTree(messages.get(3));
        assertEquals("tool", compactedMessage.path("role").asText());
        assertEquals("call-old", compactedMessage.path("tool_call_id").asText());
        assertEquals("get_note", compactedMessage.path("name").asText());
        JsonNode digest = json.readTree(compactedMessage.path("content").asText());
        assertTrue(digest.path("context_compacted").asBoolean());
        assertTrue(digest.path("notice").asText().contains("重新读取原文"));
        assertEquals(1800, digest.path("excerpt").asText().length());
        assertEquals(101, digest.path("note_id").asInt());
        assertEquals("a".repeat(64), digest.path("content_hash").asText());
        assertEquals("b".repeat(64), digest.path("expected_hash").asText());
        assertEquals("synthetic-code-tool", digest.path("adaptive_tool").asText());
        assertEquals(3, digest.path("adaptive_version").asInt());
        assertTrue(digest.path("ok").asBoolean());
        assertFalse(digest.has("source"), "the excerpt is explicitly incomplete, not a replacement source body");
        assertEquals(oldSource, json.readTree(oldResult.path("content").asText()).path("source").asText(),
                "replacing a message must not mutate its original result object");
        assertCallResultPair(messages, 2, 3, "call-old");
        assertCallResultPair(messages, 4, 5, "call-fresh-one");
        assertCallResultPair(messages, 6, 7, "call-fresh-two");
    }

    @Test void fewerThanTwoToolGroupsNeverTrimsFreshSourceEvenAboveHardLimit() {
        List<Object> messages = new ArrayList<>();
        messages.add(message("user", "核对合成资料"));
        messages.add(call("call-fresh-one"));
        messages.add(result("call-fresh-one", "x".repeat(AgentLoopContext.HARD_CHARS + 1_000)));
        JsonNode before = json.valueToTree(messages);

        assertTrue(AgentLoopContext.size(messages, json) > AgentLoopContext.HARD_CHARS);
        assertEquals(0, AgentLoopContext.compact(messages, json));
        assertEquals(before, json.valueToTree(messages));
    }

    @Test void newestTwoGroupsRemainExactEvenWhenTheyAloneExceedHardLimit() {
        List<Object> messages = conversation("old".repeat(40_000), "first-source".repeat(15_000), "second-source".repeat(15_000));
        JsonNode freshBefore = json.valueToTree(messages.subList(4, 8));

        assertEquals(1, AgentLoopContext.compact(messages, json));

        assertEquals(freshBefore, json.valueToTree(messages.subList(4, 8)));
        assertTrue(AgentLoopContext.size(messages, json) > AgentLoopContext.HARD_CHARS,
                "the caller must stop at the hard limit rather than silently truncate fresh editing inputs");
    }

    @Test void longSystemAndUserInstructionsAreNotMistakenForToolResults() {
        List<Object> messages = conversation("short old result", "fresh one", "fresh two");
        messages.set(0, message("system", "系统约定".repeat(30_000)));
        messages.set(1, message("user", "当前任务".repeat(30_000)));
        JsonNode before = json.valueToTree(messages);

        assertTrue(AgentLoopContext.size(messages, json) > AgentLoopContext.SOFT_CHARS);
        assertEquals(0, AgentLoopContext.compact(messages, json));
        assertEquals(before, json.valueToTree(messages));
    }

    @Test void oldPlainTextResultGetsAnExplicitExcerptAndRetainsItsMatchingCall() throws Exception {
        String original = "synthetic plain-text source\n".repeat(6_000);
        List<Object> messages = conversation("unused", "fresh one", "fresh two");
        ((ObjectNode) messages.get(3)).put("content", original);

        assertEquals(1, AgentLoopContext.compact(messages, json));

        JsonNode digest = json.readTree(((ObjectNode) messages.get(3)).path("content").asText());
        assertEquals(original.substring(0, 1800), digest.path("excerpt").asText());
        assertTrue(digest.path("context_compacted").asBoolean());
        assertCallResultPair(messages, 2, 3, "call-old");
    }

    @Test void fallbackDigestKeepsPreviousSummaryAlongsideNewExtractiveFacts() {
        String previous = "合成前情：使用方案甲，保留三项既定约束。";
        AgentEvent user = event("user", "新的合成问题：\n请核对方案乙。");
        AgentEvent assistant = event("assistant", "新的合成结论：\t尚需核验边界情况。");

        String digest = AgentService.fallbackDigest(previous, List.of(user, assistant));

        assertTrue(digest.startsWith(previous + "\n\n"));
        assertTrue(digest.contains("模型摘要不可用"));
        assertTrue(digest.contains("· 问：新的合成问题： 请核对方案乙。"));
        assertTrue(digest.contains("· 答：新的合成结论： 尚需核验边界情况。"));
        assertEquals(1, digest.split("合成前情", -1).length - 1);
    }

    @Test void fallbackDigestWithoutPreviousSummaryStillIncludesNewEvidence() {
        String digest = AgentService.fallbackDigest(null, List.of(event("user", "仅包含新的合成输入")));
        assertTrue(digest.startsWith("（模型摘要不可用"));
        assertTrue(digest.contains("· 问：仅包含新的合成输入"));
        assertFalse(digest.contains("null"));
    }

    @Test void finalAnswerBelowBudgetIsAnExactIndependentCopy() {
        List<Object> messages = conversation("small source", "one", "two");
        JsonNode before = json.valueToTree(messages);

        List<Object> finalMessages = AgentLoopContext.forFinalAnswer(messages, json, "请核对合成资料");

        assertEquals(before, json.valueToTree(finalMessages));
        assertNotSame(messages.get(3), finalMessages.get(3));
        ((ObjectNode) finalMessages.get(3)).put("content", "修改副本");
        ((ObjectNode) finalMessages.get(2)).withArray("tool_calls").get(0).withObject("function")
                .put("arguments", "{}");
        assertEquals(before, json.valueToTree(messages));
    }

    @Test void finalAnswerTrimsHugeLatestSingleBatchIncludingHistoricalArguments() throws Exception {
        String question = "请总结合成单批材料，但不要执行新操作。";
        ObjectNode latestCall = call("latest-call");
        String fullArguments = json.createObjectNode().put("note_id", 701)
                .put("expected_hash", "c".repeat(64)).put("content", "synthetic draft ".repeat(30_000)).toString();
        latestCall.withArray("tool_calls").get(0).withObject("function").put("arguments", fullArguments);
        List<Object> messages = new ArrayList<>(List.of(message("system", "全部系统约定必须保留"),
                message("user", question), latestCall, result("latest-call", "latest source ".repeat(30_000))));
        JsonNode before = json.valueToTree(messages);

        List<Object> finalMessages = AgentLoopContext.forFinalAnswer(messages, json, question);

        assertFinalBudget(finalMessages);
        assertEquals(before, json.valueToTree(messages));
        assertEquals(before.get(0), json.valueToTree(finalMessages.get(0)));
        assertEquals(before.get(1), json.valueToTree(finalMessages.get(1)));
        assertCallResultPair(finalMessages, 2, 3, "latest-call");
        JsonNode arguments = json.readTree(json.valueToTree(finalMessages.get(2)).path("tool_calls").get(0)
                .path("function").path("arguments").asText());
        assertTrue(arguments.path("context_compacted").asBoolean());
        assertTrue(arguments.path("notice").asText().contains("不是新的调用请求"));
        assertTrue(arguments.path("notice").asText().contains("不得重放"));
        assertEquals(701, arguments.path("note_id").asInt());
        assertEquals("c".repeat(64), arguments.path("expected_hash").asText());
        JsonNode digest = json.readTree(json.valueToTree(finalMessages.get(3)).path("content").asText());
        assertTrue(digest.path("context_compacted").asBoolean());
        assertTrue(digest.path("notice").asText().contains("不得声称全文"));
        assertFalse(digest.has("source"));
        assertFalse(digest.path("excerpt").asText().isBlank());
    }

    @Test void finalAnswerCanTrimBothNewestGroupsWithoutChangingNormalCompactionProtection() throws Exception {
        List<Object> messages = conversation("old source ".repeat(12_000), "first latest ".repeat(18_000),
                "second latest ".repeat(18_000));
        for (int index : List.of(4, 6)) {
            ((ObjectNode) messages.get(index)).withArray("tool_calls").get(0).withObject("function")
                    .put("arguments", json.createObjectNode().put("note_id", index)
                            .put("content", "latest synthetic edit ".repeat(15_000)).toString());
        }
        JsonNode latestBefore = json.valueToTree(messages.subList(4, 8));
        assertEquals(1, AgentLoopContext.compact(messages, json));
        assertEquals(latestBefore, json.valueToTree(messages.subList(4, 8)));
        JsonNode original = json.valueToTree(messages);

        List<Object> finalMessages = AgentLoopContext.forFinalAnswer(messages, json, "请核对合成资料");

        assertFinalBudget(finalMessages);
        assertEquals(original, json.valueToTree(messages));
        for (int index : List.of(3, 5, 7)) {
            JsonNode digest = json.readTree(json.valueToTree(finalMessages.get(index)).path("content").asText());
            assertTrue(digest.path("context_compacted").asBoolean());
            assertTrue(digest.path("notice").asText().contains("不得声称全文"));
        }
        assertCallResultPair(finalMessages, 2, 3, "call-old");
        assertCallResultPair(finalMessages, 4, 5, "call-fresh-one");
        assertCallResultPair(finalMessages, 6, 7, "call-fresh-two");
    }

    @Test void finalAnswerRetainsNestedMultipleSourceIdsHashesAndStagedActionMetadata() throws Exception {
        String question = "请汇总已检索的合成来源";
        ObjectNode body = json.createObjectNode().put("ok", true).put("staged", true).put("action_id", 42)
                .put("error", "synthetic validation warning");
        ArrayNode sources = body.putArray("sources");
        sources.addObject().put("id", 201).put("source", "synthetic-one.md")
                .put("content_hash", "d".repeat(64)).put("content", "first source ".repeat(15_000));
        sources.addObject().put("id", 202).put("source", "synthetic-two.md")
                .put("expected_hash", "e".repeat(64)).put("text", "second source ".repeat(15_000));
        List<Object> messages = new ArrayList<>(List.of(message("system", "合成资料约定"), message("user", question),
                call("multi-source-call"), message("tool", body.toString()).put("tool_call_id", "multi-source-call")
                        .put("name", "search_knowledge")));

        List<Object> finalMessages = AgentLoopContext.forFinalAnswer(messages, json, question);

        assertFinalBudget(finalMessages);
        assertCallResultPair(finalMessages, 2, 3, "multi-source-call");
        JsonNode tool = json.valueToTree(finalMessages.get(3));
        assertEquals("search_knowledge", tool.path("name").asText());
        JsonNode digest = json.readTree(tool.path("content").asText());
        assertTrue(digest.path("ok").asBoolean());
        assertTrue(digest.path("staged").asBoolean());
        assertEquals(42, digest.path("action_id").asInt());
        assertEquals("synthetic validation warning", digest.path("error").asText());
        assertEquals(2, digest.path("sources").size());
        assertEquals(201, digest.path("sources").get(0).path("id").asInt());
        assertEquals("synthetic-one.md", digest.path("sources").get(0).path("source").asText());
        assertEquals("d".repeat(64), digest.path("sources").get(0).path("content_hash").asText());
        assertEquals(202, digest.path("sources").get(1).path("id").asInt());
        assertEquals("e".repeat(64), digest.path("sources").get(1).path("expected_hash").asText());
        assertFalse(digest.path("sources").get(0).has("content"));
        assertFalse(digest.path("sources").get(1).has("text"));
    }

    @Test void finalAnswerUsesSerializedBudgetEvenWithUnicodeAndRepeatedJsonEscapes() throws Exception {
        String question = "保留问题中的转义：\"引号\"、\\路径、\n换行与 🧪 合成数据。";
        String escaped = "\u0001\"\\\n🧪汉字".repeat(12_000);
        List<Object> messages = conversation(escaped, escaped, escaped);
        messages.set(1, message("user", question));
        JsonNode before = json.valueToTree(messages);

        List<Object> finalMessages = AgentLoopContext.forFinalAnswer(messages, json, question);

        assertFinalBudget(finalMessages);
        assertEquals(before, json.valueToTree(messages));
        assertEquals(question, json.valueToTree(finalMessages.get(1)).path("content").asText());
        for (int index : List.of(3, 5, 7)) {
            JsonNode digest = json.readTree(json.valueToTree(finalMessages.get(index)).path("content").asText());
            String excerpt = digest.path("excerpt").asText();
            assertFalse(Character.isHighSurrogate(excerpt.charAt(excerpt.length() - 1)));
        }
    }

    @Test void finalAnswerKeepsAllSystemMessagesAndLastMatchingQuestionExactly() {
        String question = "本次精确合成问题：\n请保留全部目标。";
        ObjectNode systemOne = message("system", "系统约定一：".repeat(800));
        ObjectNode systemTwo = message("system", "系统约定二：".repeat(800));
        ObjectNode currentUser = message("user", question).put("name", "synthetic-user");
        List<Object> messages = new ArrayList<>(List.of(systemOne, message("user", question),
                message("assistant", "更早的回复".repeat(20_000)), systemTwo,
                message("user", "过长注入上下文".repeat(20_000)), currentUser,
                call("latest-call"), result("latest-call", "source".repeat(50_000)),
                message("user", "【任务完成核对】合成收尾提示")));

        List<Object> finalMessages = AgentLoopContext.forFinalAnswer(messages, json, question);

        assertFinalBudget(finalMessages);
        assertEquals(systemOne, json.valueToTree(finalMessages.get(0)));
        assertEquals(systemTwo, json.valueToTree(finalMessages.get(3)));
        assertEquals(currentUser, json.valueToTree(finalMessages.get(5)));
        assertCallResultPair(finalMessages, 6, 7, "latest-call");
    }

    @Test void finalAnswerDropsHugeHistoricalReasoningWithoutMutatingItOrSpendingEvidenceBudget() throws Exception {
        List<Object> messages = conversation("small old source", "small latest one", "small latest two");
        String reasoning = "synthetic non-body reasoning ".repeat(20_000);
        ((ObjectNode) messages.get(6)).put("reasoning_content", reasoning).put("provider_analysis", reasoning);
        JsonNode before = json.valueToTree(messages);

        List<Object> finalMessages = AgentLoopContext.forFinalAnswer(messages, json, "请核对合成资料");

        assertFinalBudget(finalMessages);
        assertEquals(before, json.valueToTree(messages));
        JsonNode assistant = json.valueToTree(finalMessages.get(6));
        assertFalse(assistant.has("reasoning_content"));
        assertFalse(assistant.has("provider_analysis"));
        assertCallResultPair(finalMessages, 6, 7, "call-fresh-two");
        assertEquals("small latest two", json.readTree(json.valueToTree(finalMessages.get(7))
                .path("content").asText()).path("source").asText());
    }

    @Test void finalAnswerRejectsProtectedPromptsTooLargeAfterJsonEscaping() {
        String question = "精确问题".repeat(26_000);
        List<Object> messages = new ArrayList<>(List.of(message("system", "合成约定"), message("user", question)));
        JsonNode before = json.valueToTree(messages);
        assertThrows(IllegalArgumentException.class, () -> AgentLoopContext.forFinalAnswer(messages, json, question));
        assertEquals(before, json.valueToTree(messages));

        String escapedQuestion = "\u0001".repeat(17_000);
        List<Object> escaped = new ArrayList<>(List.of(message("system", "合成约定"), message("user", escapedQuestion)));
        assertThrows(IllegalArgumentException.class,
                () -> AgentLoopContext.forFinalAnswer(escaped, json, escapedQuestion));
        List<Object> hugeSystem = new ArrayList<>(List.of(message("system", "系统约定".repeat(26_000)),
                message("user", "短问题")));
        assertThrows(IllegalArgumentException.class, () -> AgentLoopContext.forFinalAnswer(hugeSystem, json, "短问题"));
    }

    @Test void finalAnswerNeverGuessesWhichUserGoalToProtectWhenQuestionIsMissing() {
        List<Object> messages = conversation("source", "one", "two");
        assertThrows(IllegalArgumentException.class,
                () -> AgentLoopContext.forFinalAnswer(messages, json, "未出现在历史中的合成问题"));
    }

    private void assertFinalBudget(List<Object> messages) {
        assertTrue(json.valueToTree(messages).toString().length() <= AgentLoopContext.SOFT_CHARS - 4_000,
                "serialized transcript must leave room for the final instruction, including JSON escaping");
    }

    private List<Object> conversation(String oldSource, String freshOne, String freshTwo) {
        return new ArrayList<>(List.of(
                message("system", "保留合成任务约定"), message("user", "请核对合成资料"),
                call("call-old"), result("call-old", oldSource),
                call("call-fresh-one"), result("call-fresh-one", freshOne),
                call("call-fresh-two"), result("call-fresh-two", freshTwo)));
    }

    private ObjectNode message(String role, String content) {
        return json.createObjectNode().put("role", role).put("content", content);
    }

    private ObjectNode call(String id) {
        ObjectNode assistant = message("assistant", "");
        ArrayNode calls = assistant.putArray("tool_calls");
        calls.addObject().put("id", id).put("type", "function").putObject("function")
                .put("name", "get_note").put("arguments", "{\"note_id\":101}");
        return assistant;
    }

    private ObjectNode result(String callId, String source) {
        ObjectNode body = json.createObjectNode().put("ok", true).put("source", source).put("note_id", 101)
                .put("content_hash", "a".repeat(64)).put("expected_hash", "b".repeat(64))
                .put("adaptive_tool", "synthetic-code-tool").put("adaptive_version", 3);
        return message("tool", body.toString()).put("tool_call_id", callId).put("name", "get_note");
    }

    private void assertCallResultPair(List<Object> messages, int assistantIndex, int toolIndex, String expectedId) {
        JsonNode assistant = json.valueToTree(messages.get(assistantIndex));
        JsonNode result = json.valueToTree(messages.get(toolIndex));
        assertEquals(expectedId, assistant.path("tool_calls").get(0).path("id").asText());
        assertEquals(expectedId, result.path("tool_call_id").asText());
    }

    private AgentEvent event(String role, String content) {
        AgentEvent event = new AgentEvent();
        event.setRole(role);
        event.setContent(content);
        return event;
    }
}
