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
