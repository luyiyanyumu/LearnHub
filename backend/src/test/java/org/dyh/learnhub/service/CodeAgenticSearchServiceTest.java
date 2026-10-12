package org.dyh.learnhub.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.dyh.learnhub.ai.DeepSeekClient;
import org.dyh.learnhub.ai.ModelRouting;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class CodeAgenticSearchServiceTest {
    private final ObjectMapper json = new ObjectMapper();
    private final CodeLibraryService code = mock(CodeLibraryService.class);
    private final DeepSeekClient client = mock(DeepSeekClient.class);
    private final ModelRouting routing = mock(ModelRouting.class);
    private CodeAgenticSearchService service;

    @BeforeEach void setup() {
        service = new CodeAgenticSearchService(code, client, routing, json);
        when(code.list(eq("java"), eq(7L), eq(false), isNull(), eq(12))).thenReturn(List.of(row(1L)));
        when(routing.forTask(ModelRouting.TASK_CODE_SEARCH)).thenReturn(
                new ModelRouting.ModelTarget("fixture", "测试", "https://example.invalid", "fixture-token", "fixture-model", false));
        when(code.scopedView(eq(1L), eq("java"), eq(7L), eq(false))).thenReturn(row(1L));
        when(code.readWindow(eq(1L), eq("java"), eq(7L), eq(false), eq(2), eq(3))).thenReturn(window(1L, 2, 3, "connect();\nreconnect();"));
    }

    @Test void changesQueryReadsThenFinishesWithVerifiedLinesAndReasoningMessage() throws Exception {
        when(code.search(eq("TLS"), eq("symbol"), eq("java"), eq(7L), eq(false), eq(""), eq(8))).thenReturn(List.of());
        when(code.search(eq("reconnect"), eq("all"), eq("java"), eq(7L), eq(false), eq(""), eq(8))).thenReturn(List.of(row(1L)));
        var first = call("a", "search_code", Map.of("query", "TLS", "mode", "symbol", "repoId", 999));
        ((com.fasterxml.jackson.databind.node.ObjectNode) first.message()).put("reasoning_content", "fixture reasoning");
        reply(first, call("b", "search_code", Map.of("query", "reconnect")), read("c", 1L), finalReply(1L, "reconnect();"));
        Map<String, Object> result = service.search(request());
        assertThat(result).containsEntry("status", "completed").containsEntry("rounds", 4);
        assertThat(results(result)).hasSize(1);
        assertThat(results(result).getFirst().get("evidence").toString()).contains("connect();\nreconnect();");
        verify(code, never()).search(anyString(), anyString(), anyString(), eq(999L), anyBoolean(), any(), anyInt());
        var captor = org.mockito.ArgumentCaptor.forClass(List.class);
        verify(client, times(4)).chatFull(captor.capture(), anyList(), anyString(), anyString(), anyString(), anyInt(), anyDouble(), anyString(), isNull(), any(Duration.class));
        assertThat(captor.getValue()).contains(first.message());
        assertThat(captor.getValue().toString()).contains("fixture reasoning", "tool_call_id=a", "tool_call_id=b", "tool_call_id=c");
    }

    @Test void rejectsInventedIdAndMetadataOnlyFinalAnswer() throws Exception {
        reply(read("a", 999L), finalReply(999L, "imaginary();"));
        var result = service.search(request());
        assertThat(result).containsEntry("status", "limited");
        assertThat(results(result)).isEmpty();
        verify(code, never()).readWindow(eq(999L), any(), any(), anyBoolean(), anyInt(), anyInt());
        assertThat(result.get("answer").toString()).doesNotContain("已完成实现");
    }

    @Test void fabricatedQuoteCannotBecomeEvidenceOrModelSummary() throws Exception {
        reply(read("a", 1L), finalReply(1L, "deleteEverything();"));
        var result = service.search(request());
        assertThat(result).containsEntry("status", "limited");
        assertThat(result.toString()).doesNotContain("deleteEverything", "已完成实现");
        assertThat(results(result)).hasSize(1);
    }

    @Test void changedSourceCannotBeCitedAfterItWasRead() throws Exception {
        when(code.readWindow(eq(1L), eq("java"), eq(7L), eq(false), eq(2), eq(3)))
                .thenReturn(window(1L, 2, 3, "connect();\nreconnect();"), window(1L, 2, 3, "changed();\nreconnect();"));
        reply(read("a", 1L), finalReply(1L, "reconnect();"));
        var result = service.search(request());
        assertThat(result).containsEntry("status", "limited");
        assertThat(results(result)).isEmpty();
    }

    @Test void modelFailureReturnsScopedKeywordCandidatesWithoutSensitiveException() throws Exception {
        when(code.search(eq("reconnect 在哪实现"), eq("all"), eq("java"), eq(7L), eq(false), isNull(), eq(8)))
                .thenReturn(List.of(row(1L), outside(2L)));
        when(client.chatFull(anyList(), anyList(), anyString(), anyString(), anyString(), anyInt(), anyDouble(), anyString(), isNull(), any(Duration.class)))
                .thenThrow(new IllegalStateException("fixture-secret-payload"));
        var result = service.search(request());
        assertThat(result).containsEntry("status", "fallback");
        assertThat(result.toString()).doesNotContain("fixture-secret-payload", "fixture-token");
        assertThat(results(result)).hasSize(1);
        assertThat(results(result).getFirst().get("evidence")).isEqualTo(List.of());
    }

    @Test void repeatedArgumentsDoNotRepeatSearchAndRoundLimitStops() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        when(client.chatFull(anyList(), anyList(), anyString(), anyString(), anyString(), anyInt(), anyDouble(), anyString(), isNull(), any(Duration.class)))
                .thenAnswer(inv -> call("call-" + calls.incrementAndGet(), "search_code", Map.of("query", "missing")));
        var result = service.search(request());
        assertThat(result).containsEntry("status", "limited").containsEntry("rounds", 6);
        verify(code, times(1)).search(eq("missing"), eq("all"), eq("java"), eq(7L), eq(false), eq(""), eq(8));
        assertThat(result.get("steps").toString()).contains("repeat_skipped");
    }

    @Test void toolBudgetRepliesToEveryCallAndStopsWithoutExtraModelCall() throws Exception {
        AtomicInteger turns = new AtomicInteger();
        List<List<?>> snapshots = new ArrayList<>();
        when(client.chatFull(anyList(), anyList(), anyString(), anyString(), anyString(), anyInt(), anyDouble(), anyString(), isNull(), any(Duration.class)))
                .thenAnswer(inv -> {
                    snapshots.add(new ArrayList<>(inv.<List<?>>getArgument(0)));
                    int turn = turns.incrementAndGet();
                    List<Object> calls = new ArrayList<>();
                    for (int i = 0; i < 5; i++) calls.add(toolCall("c" + turn + "-" + i, "search_code", Map.of("query", "word" + turn + i)));
                    return result(json.valueToTree(Map.of("role", "assistant", "tool_calls", calls)), "tool_calls");
                });
        var response = service.search(request());
        assertThat(response).containsEntry("status", "limited").containsEntry("rounds", 3);
        verify(code, times(11)).search(anyString(), eq("all"), eq("java"), eq(7L), eq(false), eq(""), eq(8));
        verify(client, times(3)).chatFull(anyList(), anyList(), anyString(), anyString(), anyString(), anyInt(), anyDouble(), anyString(), isNull(), any(Duration.class));
        assertThat(snapshots.get(2).toString()).contains("tool_call_id=c2-4");
    }

    @Test void duplicateToolIdStopsWithoutExecutingAgain() throws Exception {
        reply(call("same", "search_code", Map.of("query", "missing")), call("same", "list_files", Map.of()));
        var result = service.search(request());
        assertThat(result).containsEntry("status", "limited");
        verify(code, times(1)).list(any(), any(), anyBoolean(), any(), anyInt());
    }

    @Test void unreadWindowOverReadBudgetIsNeverReturnedAsEvidence() throws Exception {
        String source = "x".repeat(3900);
        List<Map<String, Object>> inventory = new ArrayList<>();
        List<Object> calls = new ArrayList<>();
        for (long id = 1; id <= 5; id++) {
            inventory.add(row(id));
            calls.add(toolCall("read-" + id, "read_code", Map.of("snippet_id", id, "start_line", 2, "end_line", 3)));
            Map<String, Object> read = new LinkedHashMap<>(window(id, 2, 3, source + "\nx"));
            read.put("numberedCode", "2: " + source + "\n3: x\n");
            when(code.readWindow(eq(id), eq("java"), eq(7L), eq(false), eq(2), eq(3))).thenReturn(read);
            when(code.scopedView(eq(id), eq("java"), eq(7L), eq(false))).thenReturn(row(id));
        }
        when(code.list(eq("java"), eq(7L), eq(false), isNull(), eq(12))).thenReturn(inventory);
        reply(result(json.valueToTree(Map.of("role", "assistant", "tool_calls", calls)), "tool_calls"));
        var response = service.search(request());
        assertThat(response).containsEntry("status", "limited").containsEntry("rounds", 1);
        assertThat(results(response)).hasSize(4);
        assertThat(results(response)).noneMatch(row -> row.get("id").equals(5L));
    }

    @Test void duplicateReadArgumentsWithDifferentIgnoredScopeAreNotExecutedAgain() throws Exception {
        reply(read("a", 1L), call("b", "read_code", Map.of("end_line", 3, "start_line", 2, "snippet_id", 1L, "repoId", 999)), finalReply(1L, "reconnect();"));
        assertThat(service.search(request())).containsEntry("status", "completed");
        // Once to provide the window, once to validate the final quote; the repeated tool is skipped.
        verify(code, times(2)).readWindow(eq(1L), eq("java"), eq(7L), eq(false), eq(2), eq(3));
    }

    @Test void distinctQueryAndPathCannotCollideInToolDeduplication() throws Exception {
        reply(call("a", "search_code", Map.of("query", "a, all, b", "mode", "all", "path", "c")),
                call("b", "search_code", Map.of("query", "a", "mode", "all", "path", "b, all, c")),
                finalReply(1L, "reconnect();"));
        var response = service.search(request());
        assertThat(response).containsEntry("rounds", 3);
        verify(code).search(eq("a, all, b"), eq("all"), eq("java"), eq(7L), eq(false), eq("c"), eq(8));
        verify(code).search(eq("a"), eq("all"), eq("java"), eq(7L), eq(false), eq("b, all, c"), eq(8));
        assertThat(response.get("steps").toString()).doesNotContain("repeat_skipped");
    }

    @Test void truncatedReplyCannotBeCompleted() throws Exception {
        reply(result(finalReply(1L, "reconnect();").message(), "length"));
        assertThat(service.search(request())).containsEntry("status", "limited");
    }

    @Test void emptyScopeAvoidsModelCallAndInventedAnswer() {
        when(code.list(any(), any(), anyBoolean(), any(), anyInt())).thenReturn(List.of());
        var result = service.search(request());
        assertThat(result).containsEntry("status", "limited").containsEntry("rounds", 0);
        assertThat(results(result)).isEmpty();
        verifyNoInteractions(client, routing);
    }

    @Test void unassignedAndLanguageRemainBoundForEveryTool() throws Exception {
        when(code.list(eq("python"), eq(12L), eq(true), isNull(), eq(12))).thenReturn(List.of(unassignedRow(3L)));
        when(code.search(eq("connect"), eq("all"), eq("python"), eq(12L), eq(true), eq(""), eq(8)))
                .thenReturn(List.of(unassignedRow(3L), row(1L)));
        when(code.readWindow(eq(3L), eq("python"), eq(12L), eq(true), eq(2), eq(3)))
                .thenReturn(window(3L, 2, 3, "connect();\nreconnect();"));
        when(code.scopedView(eq(3L), eq("python"), eq(12L), eq(true))).thenReturn(unassignedRow(3L));
        reply(call("a", "search_code", Map.of("query", "connect", "lang", "java", "repoId", 7)), read("b", 3L), finalReply(3L, "reconnect();"));
        var result = service.search(new CodeAgenticSearchService.Request("connect", "python", 12L, true, 5));
        assertThat(result).containsEntry("status", "completed");
        assertThat(results(result).getFirst()).containsEntry("id", 3L);
        verify(code, never()).readWindow(eq(3L), eq("java"), any(), anyBoolean(), anyInt(), anyInt());
    }

    @Test void interruptedModelCallRestoresInterruptFlag() throws Exception {
        when(client.chatFull(anyList(), anyList(), anyString(), anyString(), anyString(), anyInt(), anyDouble(), anyString(), isNull(), any(Duration.class)))
                .thenThrow(new InterruptedException());
        try {
            assertThat(service.search(request())).containsEntry("status", "fallback");
            assertThat(Thread.currentThread().isInterrupted()).isTrue();
        } finally { Thread.interrupted(); }
    }

    @Test void invalidQuestionFailsBeforeAnyDataOrModelRead() {
        assertThatThrownBy(() -> service.search(new CodeAgenticSearchService.Request(" ", null, null, null, null)))
                .isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(code, client, routing);
    }

    private void reply(DeepSeekClient.ChatResult... replies) throws Exception {
        var stub = when(client.chatFull(anyList(), anyList(), anyString(), anyString(), anyString(), anyInt(), anyDouble(), anyString(), isNull(), any(Duration.class)));
        stub.thenReturn(replies[0], java.util.Arrays.copyOfRange(replies, 1, replies.length));
    }
    private DeepSeekClient.ChatResult read(String callId, Long id) throws Exception {
        return call(callId, "read_code", Map.of("snippet_id", id, "start_line", 2, "end_line", 3));
    }
    private DeepSeekClient.ChatResult call(String id, String name, Map<String, Object> arguments) throws Exception {
        return result(json.valueToTree(Map.of("role", "assistant", "tool_calls", List.of(toolCall(id, name, arguments)))), "tool_calls");
    }
    private Map<String, Object> toolCall(String id, String name, Map<String, Object> args) throws Exception {
        return Map.of("id", id, "type", "function", "function", Map.of("name", name, "arguments", json.writeValueAsString(args)));
    }
    private DeepSeekClient.ChatResult finalReply(Long id, String quote) throws Exception {
        String content = json.writeValueAsString(Map.of("answer", "已完成实现，见测试片段 L2-L3。", "results", List.of(
                Map.of("snippet_id", id, "reason", "重连实现", "evidence", List.of(Map.of("start_line", 2, "end_line", 3, "quote", quote))))));
        return result(json.valueToTree(Map.of("role", "assistant", "content", content)), "stop");
    }
    private static DeepSeekClient.ChatResult result(JsonNode node, String finish) { return new DeepSeekClient.ChatResult(node, finish, 0, 0, 0); }
    private static CodeAgenticSearchService.Request request() { return new CodeAgenticSearchService.Request("reconnect 在哪实现", "java", 7L, false, 8); }
    private static Map<String, Object> row(Long id) { return new LinkedHashMap<>(Map.of("id", id, "title", "测试片段", "lang", "java", "repoId", 7L)); }
    private static Map<String, Object> outside(Long id) { return Map.of("id", id, "title", "其它项目", "lang", "java", "repoId", 99L); }
    private static Map<String, Object> unassignedRow(Long id) {
        Map<String, Object> row = row(id); row.put("lang", "python"); row.put("repoId", null); return row;
    }
    private static Map<String, Object> window(Long id, int start, int end, String quote) {
        return Map.of("snippetId", id, "startLine", start, "endLine", end, "quote", quote,
                "numberedCode", "2: connect();\n3: reconnect();\n", "hasMore", false);
    }
    @SuppressWarnings("unchecked") private static List<Map<String, Object>> results(Map<String, Object> result) {
        return (List<Map<String, Object>>) result.get("results");
    }
}
