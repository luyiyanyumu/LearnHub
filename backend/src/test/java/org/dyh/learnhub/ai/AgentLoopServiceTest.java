package org.dyh.learnhub.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.dyh.learnhub.dto.AiChatRequest;
import org.dyh.learnhub.entity.AgentPendingAction;
import org.dyh.learnhub.service.AgentSessionService;
import org.dyh.learnhub.service.GroundingService;
import org.dyh.learnhub.service.NoteService;
import org.dyh.learnhub.service.NoteContentEditor;
import org.dyh.learnhub.service.RetrievalContextService;
import org.dyh.learnhub.service.SettingsService;
import org.dyh.learnhub.vo.AiChatVO;
import org.dyh.learnhub.vo.NoteVO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Synthetic model transcripts exercise the actual service loop, without a network or database. */
@ExtendWith(MockitoExtension.class)
class AgentLoopServiceTest {
    private static final String SESSION = "synthetic-loop-session";
    private static final String QUESTION = "处理合成任务";
    private final ObjectMapper json = new ObjectMapper();
    private final List<JsonNode> requestSnapshots = new ArrayList<>();
    private final List<Boolean> requestHasTools = new ArrayList<>();
    private final List<JsonNode> memoryRequests = new ArrayList<>();
    private Exception memoryFailure;
    @Mock DeepSeekClient client;
    @Mock ModelRouting routing;
    @Mock SettingsService settings;
    @Mock AgentSessionService sessions;
    @Mock RetrievalContextService contexts;
    @Mock GroundingService grounding;
    @Mock NoteService notes;
    @Mock ToolEvolutionService evolution;
    @InjectMocks AgentService agent;

    @BeforeEach void configure() {
        ReflectionTestUtils.setField(agent, "objectMapper", json);
        when(client.isConfigured()).thenReturn(true);
        when(sessions.ensure(isNull(), isNull(), eq(QUESTION))).thenReturn(SESSION);
        when(settings.effective(SettingsService.KEY_CHAT_PROMPT)).thenReturn("合成系统提示");
        when(contexts.build(QUESTION, 8, "fused", false, false)).thenReturn(RetrievalContextService.Context.empty());
        when(evolution.definitions(anyList(), anySet())).thenReturn(List.of());
    }

    @Test void simpleAnswerFinishesWithoutPlanOrExtraModelRound() throws Exception {
        script(answer("合成直接回答"));

        AiChatVO result = chat();

        assertEquals("合成直接回答", result.getReply());
        assertEquals("answer", result.getRunSummary().get("stopReason"));
        assertEquals(1, result.getRunSummary().get("rounds")); assertEquals(0, result.getRunSummary().get("toolCalls"));
        assertEquals(1, requestSnapshots.size()); verifyNoInteractions(notes);
        verify(sessions).append(eq(SESSION), eq("assistant"), eq("合成直接回答"), isNull(), isNull(), anyInt(), eq("stop"));
    }

    @Test void modelOptionsAreFilteredSavedAndReturnedWithoutExtraActionGenerationRound() throws Exception {
        script(tools(call("options", "set_reply_actions", Map.of("actions", List.of(
                        Map.of("kind", "follow_up", "label", "查看合成示例", "prompt", "给出一个完整合成示例", "url", "https://unused.invalid"),
                        Map.of("kind", "copy", "label", "复制解释"),
                        Map.of("kind", "merge_note", "label", "错误目标", "noteId", 999),
                        Map.of("kind", "javascript", "label", "非法执行", "script", "alert(1)"))))),
                answer("合成解释正文。"));
        AiChatVO result = chat();
        assertEquals(2, result.getReplyActions().size());
        assertEquals(List.of("follow_up", "copy"), result.getReplyActions().stream().map(a -> a.get("kind")).toList());
        assertFalse(result.getReplyActions().toString().contains("url="));
        assertEquals(2, requestSnapshots.size());
        verify(sessions).append(eq(SESSION), eq(AgentSessionService.ROLE_REPLY_ACTIONS), contains("查看合成示例"), isNull(), isNull());
    }

    @Test void readonlyOptionsCanFollowPendingWriteInTheSameBatch() throws Exception {
        stubPending();
        script(tools(call("write", "create_note", Map.of("title", "合成稿", "content", "合成正文")),
                        call("options", "set_reply_actions", Map.of("actions", List.of(
                                Map.of("kind", "follow_up", "label", "补充示例", "prompt", "请再补充一个合成示例"),
                                Map.of("kind", "copy", "label", "复制说明"))))),
                answer("请确认合成预览。"));
        AiChatVO result = chat();
        assertEquals(1, result.getPendingActions().size());
        assertEquals(2, result.getReplyActions().size());
        assertEquals(2, requestSnapshots.size());
        assertEquals(2, toolResults(requestSnapshots.getLast()).get("options").path("count").asInt());
    }

    @Test void finalEnvelopeCarriesTaskSpecificOptionsWithoutAnotherModelRequest() throws Exception {
        script(answer(json.writeValueAsString(Map.of("reply", "合成最终正文。", "replyActions", List.of(
                Map.of("kind", "follow_up", "label", "比较方案", "prompt", "比较合成的两个方案"),
                Map.of("kind", "copy", "label", "复制方案"))))));
        AiChatVO result = chat();
        assertEquals("合成最终正文。", result.getReply());
        assertEquals(2, result.getReplyActions().size());
        assertEquals(1, requestSnapshots.size());
    }

    @Test void unfinishedPlanMakesPrematureAnswerContinueWithExistingResults() throws Exception {
        source(21, "合成首份原文"); source(22, "合成补充原文");
        script(
                tools(call("plan-start", "update_plan", plan("in_progress", "pending")), call("read-first", "get_note", Map.of("note_id", 21))),
                answer("已经完成合成任务。"),
                tools(call("read-second", "get_note", Map.of("note_id", 22)), call("plan-done", "update_plan", plan("completed", "completed"))),
                answer("两份合成原文都已核对，任务完成。"));

        AiChatVO result = chat();

        assertEquals("两份合成原文都已核对，任务完成。", result.getReply());
        assertEquals("answer", result.getRunSummary().get("stopReason")); assertEquals(4, requestSnapshots.size());
        assertTrue(requestSnapshots.get(2).toString().contains("【任务完成核对】"));
        assertTrue(requestSnapshots.get(2).toString().contains("合成首份原文"));
        assertEquals(21, toolResults(requestSnapshots.get(2)).get("read-first").path("note_id").asInt());
        verify(notes).detail(21L); verify(notes).detail(22L);
        assertEquals(2L, result.getRunSummary().get("completedSteps"));
        assertTrue(result.getProgress().stream().anyMatch(row -> "核对任务进度".equals(row.get("title"))));
    }

    @Test void stagingOneWriteStopsRemainingBatchCallsButKeepsEveryToolResponsePaired() throws Exception {
        stubPending();
        script(tools(
                call("write-first", "create_note", Map.of("title", "合成待确认稿", "content", "合成正文")),
                call("read-after-write", "get_note", Map.of("note_id", 21)),
                call("write-after-write", "create_note", Map.of("title", "第二份合成稿", "content", "不应准备此操作"))),
                answer("合成修改已准备，请确认后写入。"));

        AiChatVO result = chat();

        assertEquals("awaiting_confirmation", result.getRunSummary().get("stopReason"));
        assertEquals(1, result.getPendingActions().size()); assertEquals(2, requestSnapshots.size());
        assertFalse(requestHasTools.get(1), "after staging, only the no-tools summary request may follow");
        Map<String, JsonNode> results = toolResults(requestSnapshots.get(1));
        assertEquals(List.of("write-first", "read-after-write", "write-after-write"), new ArrayList<>(results.keySet()));
        assertTrue(results.get("write-first").path("staged").asBoolean());
        assertEquals("awaiting_confirmation", results.get("read-after-write").path("code").asText());
        assertEquals("awaiting_confirmation", results.get("write-after-write").path("code").asText());
        verify(sessions, times(1)).stageAction(eq(SESSION), eq("create_note"), anyString(), anyString());
        verifyNoInteractions(notes);
        assertPaired(requestSnapshots.get(1));
    }

    @Test void truncatedToolCallsNeverExecuteEvenWhenTheirArgumentsAreOtherwiseValid() throws Exception {
        ObjectNode message = (ObjectNode) tools(
                call("truncated-read", "get_note", Map.of("note_id", 21)),
                call("truncated-write", "create_note", Map.of("title", "合成标题", "content", "合成正文"))).message();
        script(new DeepSeekClient.ChatResult(message, "length", 100, 100, 0), answer("本批请求未完整生成，尚未执行。"));

        AiChatVO result = chat();

        assertEquals("output_limit", result.getRunSummary().get("stopReason")); assertEquals(2, requestSnapshots.size());
        assertFalse(requestHasTools.get(1));
        Map<String, JsonNode> results = toolResults(requestSnapshots.get(1));
        assertEquals(2, results.size()); assertTrue(results.values().stream().allMatch(row -> "incomplete_tool_call".equals(row.path("code").asText())));
        verifyNoInteractions(notes); verify(sessions, never()).stageAction(anyString(), anyString(), anyString(), anyString());
        assertTrue(result.getPendingActions().isEmpty()); assertPaired(requestSnapshots.get(1));
    }

    @Test void oneIOExceptionCanRetryWithTheExecutedResultWithoutRepeatingTheTool() throws Exception {
        source(21, "合成已读取证据");
        script(tools(call("read-before-error", "get_note", Map.of("note_id", 21))),
                new IOException("synthetic transient connection failure"), answer("已根据合成证据完成回答。"));

        AiChatVO result = chat();

        assertEquals("已根据合成证据完成回答。", result.getReply());
        assertEquals("answer", result.getRunSummary().get("stopReason")); assertEquals(3, requestSnapshots.size());
        assertEquals(toolResults(requestSnapshots.get(1)), toolResults(requestSnapshots.get(2)));
        assertTrue(requestSnapshots.get(2).toString().contains("合成已读取证据"));
        verify(notes, times(1)).detail(21L);
        assertTrue(result.getProgress().stream().anyMatch(row -> "恢复模型请求".equals(row.get("title"))));
        assertPaired(requestSnapshots.get(2));
    }

    @Test void aSecondIOExceptionAfterMoreWorkStopsInsteadOfRetryingAgainAndRetainsBothResults() throws Exception {
        source(21, "合成先前证据"); source(22, "合成后续证据");
        script(tools(call("first-read", "get_note", Map.of("note_id", 21))),
                new IOException("synthetic first connection failure"),
                tools(call("second-read", "get_note", Map.of("note_id", 22))),
                new IOException("synthetic second connection failure"));

        AiChatVO result = chat();

        assertEquals("model_error", result.getRunSummary().get("stopReason")); assertEquals(4, requestSnapshots.size());
        assertEquals(List.of("first-read", "second-read"), new ArrayList<>(toolResults(requestSnapshots.get(3)).keySet()));
        verify(notes, times(1)).detail(21L); verify(notes, times(1)).detail(22L);
        assertEquals(2, result.getRetrieved().size()); assertFalse(result.getReply().isBlank());
        assertTrue(result.getEvents().stream().anyMatch(event -> event.contains("模型请求未完成")));
        verify(sessions).append(eq(SESSION), eq("assistant"), anyString(), isNull(), isNull(), isNull(), isNull());
        assertPaired(requestSnapshots.get(3));
    }

    @Test void nonIoModelErrorDoesNotRetryAndStillPersistsPreviouslyReadEvidence() throws Exception {
        source(21, "合成错误前证据");
        script(tools(call("prior-read", "get_note", Map.of("note_id", 21))), new IllegalStateException("synthetic provider rejection"));

        AiChatVO result = chat();

        assertEquals("model_error", result.getRunSummary().get("stopReason")); assertEquals(2, requestSnapshots.size());
        assertEquals(1, result.getRetrieved().size()); verify(notes).detail(21L);
        assertFalse(result.getReply().contains("synthetic provider rejection"));
        verify(sessions).append(eq(SESSION), eq("assistant"), anyString(), isNull(), isNull(), isNull(), isNull());
    }

    @Test void unusedRegisteredToolRemainsIncompleteAfterBothCompletionRemindersAreSpent() throws Exception {
        String adaptive = "adaptive_synthetic_unrun";
        JsonNode definition = json.valueToTree(Map.of("name", adaptive, "description", "合成注册结果，无真实源码或资料"));
        when(evolution.evolve(eq(definition), anyList(), anySet(), eq(SESSION)))
                .thenReturn(Map.of("ok", true, "registered", true, "name", adaptive, "version", 1));
        script(tools(call("register-only", "evolve_tool", Map.of("definition", definition))),
                answer("合成工具注册完成。"), answer("合成工具已准备。"), answer("工具已注册，但没有执行原任务。"));

        AiChatVO result = chat();

        assertEquals("incomplete", result.getRunSummary().get("stopReason"));
        assertEquals(2, result.getRunSummary().get("completionReminders"));
        assertEquals(1, result.getRunSummary().get("awaitingToolCount"));
        assertEquals(0, result.getRunSummary().get("planSteps")); assertEquals(4, requestSnapshots.size());
        assertTrue(requestSnapshots.get(2).toString().contains("【任务完成核对】"));
        assertTrue(requestSnapshots.get(3).toString().contains(adaptive));
        verify(evolution, never()).execute(anyString(), any(), anyList(), anySet(), any(), any());
    }

    @Test void oversizedFreshSourceIsProcessedInFullBatchesThenAnswersWithoutRepeatingItsReadOrClaimingFullVerification() throws Exception {
        String original = "合成来源事实：方案甲使用两级索引。\n" + "合成原文材料".repeat(50_000) + "synthetic-unread-tail";
        source(21, original);
        when(grounding.enabled()).thenReturn(true);
        script(tools(call("huge-read", "get_note", Map.of("note_id", 21))), answer("根据已读节选，合成方案甲使用两级索引；其余内容尚未核对。"));

        AiChatVO result = chat();

        assertEquals("answer", result.getRunSummary().get("stopReason"));
        assertEquals(2, requestSnapshots.size());
        assertTrue(requestHasTools.get(1), "the original task can still use tools after preparing working memory");
        assertFalse(memoryRequests.isEmpty());
        assertTrue(memoryRequests.stream().anyMatch(part -> part.toString().contains("synthetic-unread-tail")), "even the final source characters must reach a summary batch");
        assertTrue(memoryRequests.stream().allMatch(part -> part.toString().length() <= 90_000));
        assertTrue(AgentLoopContext.size(json.convertValue(requestSnapshots.get(1), List.class), json) <= AgentLoopContext.HARD_CHARS);
        assertFalse(requestSnapshots.get(1).toString().contains("synthetic-unread-tail"));
        String memory = taskMemory(requestSnapshots.get(1));
        assertTrue(memory.contains(NoteContentEditor.hash(original)));
        assertTrue(memoryRequests.stream().anyMatch(part -> part.toString().contains("合成来源事实")));
        assertTrue(requestSnapshots.get(1).toString().contains(QUESTION));
        assertTrue(result.getReply().startsWith("根据已读节选"));
        assertFalse(result.getReply().contains("模型没有给出文字答复"));
        assertEquals(1, result.getRetrieved().size());
        assertEquals(false, result.getGrounding().get("checked"));
        verify(grounding, never()).check(anyString(), anyString(), anyString());
        verify(notes, times(1)).detail(21L);
        verify(sessions).appendTaskMemory(eq(SESSION), eq(memory));
        verify(sessions).append(eq(SESSION), eq("assistant"), startsWith("根据已读节选"), isNull(), isNull(), anyInt(), eq("stop"));
        assertPaired(requestSnapshots.get(1));
    }

    @Test void twelveSourcesInTheNewestTwoBatchesStillProduceABoundedPartialAnswer() throws Exception {
        List<JsonNode> first = new ArrayList<>(), second = new ArrayList<>();
        for (int i = 0; i < 12; i++) {
            long id = 21L + i;
            source(id, "合成来源 " + id + "：独立证据前缀。" + "原文".repeat(11_000));
            (i < 6 ? first : second).add(call("source-" + id, "get_note", Map.of("note_id", id)));
        }
        script(tools(first.toArray(JsonNode[]::new)), tools(second.toArray(JsonNode[]::new)), answer("十二份合成来源的已读节选存在共同前缀，尚需继续核对正文。"));

        AiChatVO result = chat();

        assertEquals(3, requestSnapshots.size());
        assertEquals(List.of(true, true, true), requestHasTools);
        assertEquals("answer", result.getRunSummary().get("stopReason"));
        assertEquals(12, result.getRunSummary().get("executedCalls"));
        assertTrue(result.getReply().startsWith("十二份合成来源"));
        assertTrue(AgentLoopContext.size(json.convertValue(requestSnapshots.get(2), List.class), json) <= AgentLoopContext.HARD_CHARS);
        assertFalse(memoryRequests.isEmpty());
        for (int i = 0; i < 12; i++) {
            long id = 21L + i;
            assertTrue(requestSnapshots.get(2).toString().contains("source-" + id), "all source identities must survive into the final task context");
            verify(notes, times(1)).detail(id);
        }
        assertEquals(12, result.getRetrieved().size());
        assertPaired(requestSnapshots.get(2));
    }

    @Test void hugeStagedWriteKeepsItsOriginalApprovalPayloadWhileOnlyTheFinalTranscriptIsExcerpted() throws Exception {
        String original = "合成待确认正文".repeat(50_000) + "synthetic-staged-tail";
        stubPending();
        script(tools(call("huge-staged-write", "create_note", Map.of("title", "合成待确认标题", "content", original))),
                answer("合成长文预览已准备，请在下方确认后写入。"));

        AiChatVO result = chat();

        assertEquals("awaiting_confirmation", result.getRunSummary().get("stopReason"));
        assertEquals(2, requestSnapshots.size());
        assertFalse(requestHasTools.get(1));
        assertTrue(AgentLoopContext.size(json.convertValue(requestSnapshots.get(1), List.class), json) <= AgentLoopContext.SOFT_CHARS);
        assertFalse(requestSnapshots.get(1).toString().contains("synthetic-staged-tail"));
        assertTrue(toolResults(requestSnapshots.get(1)).get("huge-staged-write").path("staged").asBoolean());
        assertEquals(1, result.getPendingActions().size());
        verify(sessions).stageAction(eq(SESSION), eq("create_note"), argThat(args -> {
            try { return original.equals(json.readTree(args).path("content").asText()); }
            catch (Exception malformed) { return false; }
        }), anyString());
        verifyNoInteractions(notes);
        assertPaired(requestSnapshots.get(1));
    }

    @Test void failedEmptyOrToolCallingFinalReplyNeverRestartsTheStoppedToolLoop() throws Exception {
        memoryFailure = new IOException("synthetic summary unavailable");
        Object[] endings = { new IOException("synthetic final model failure"), answer(""), tools(call("unrequested-write", "create_note", Map.of("title", "合成标题", "content", "不能执行"))) };
        for (Object ending : endings) {
            requestSnapshots.clear(); requestHasTools.clear(); memoryRequests.clear(); clearInvocations(notes, sessions);
            source(21, "合成超长原文".repeat(50_000));
            script(tools(call("only-read", "get_note", Map.of("note_id", 21))), ending);

            AiChatVO result = chat();

            assertEquals(2, requestSnapshots.size());
            assertFalse(requestHasTools.get(1));
            assertEquals("context_limit", result.getRunSummary().get("stopReason"));
            assertTrue(result.getReply().contains("暂未生成可用的文字结论"));
            assertFalse(result.getReply().contains("模型没有给出文字答复"));
            assertEquals(1, result.getRetrieved().size());
            assertTrue(result.getPendingActions().isEmpty());
            verify(notes, times(1)).detail(21L);
            verify(sessions, never()).stageAction(anyString(), anyString(), anyString(), anyString());
            assertPaired(requestSnapshots.get(1));
        }
    }

    @Test void oversizedProtectedSystemDoesNotSendAnyOversizedModelRequest() throws Exception {
        when(settings.effective(SettingsService.KEY_CHAT_PROMPT)).thenReturn("合成不可丢弃系统约定".repeat(40_000));

        AiChatVO result = chat();

        assertEquals("context_limit", result.getRunSummary().get("stopReason"));
        assertTrue(result.getReply().contains("暂未生成可用的文字结论"));
        verify(client, never()).chatFull(anyList(), nullable(List.class), anyString(), anyString(), anyString(), anyInt(), anyDouble(), nullable(String.class), nullable(String.class), any(Duration.class));
        verifyNoInteractions(notes);
    }

    @Test void successfulWorkingMemoryIsSavedAndAvailableWhenTheSameSessionContinues() throws Exception {
        source(21, "合成完整材料".repeat(50_000));
        script(tools(call("remembered-source", "get_note", Map.of("note_id", 21))), answer("合成第一阶段结论。"));
        chat();
        String saved = taskMemory(requestSnapshots.get(1));
        assertTrue(saved.startsWith(AgentContextMemory.PREFIX));
        verify(sessions).appendTaskMemory(SESSION, saved);
        when(sessions.latestTaskMemory(SESSION)).thenReturn(saved);
        requestSnapshots.clear(); requestHasTools.clear(); memoryRequests.clear();
        script(answer("接着已处理的合成来源继续回答。"));

        AiChatVO continued = chat();

        assertTrue(requestSnapshots.getFirst().toString().contains("remembered-source"));
        assertTrue(continued.getReply().startsWith("接着已处理的合成来源"));
        verify(notes, times(1)).detail(21L);
    }

    private void source(long id, String content) {
        NoteVO note = new NoteVO(); note.setId(id); note.setTitle("合成笔记 " + id); note.setContent(content);
        when(notes.detail(id)).thenReturn(note);
    }

    private void stubPending() {
        when(sessions.stageAction(eq(SESSION), eq("create_note"), anyString(), anyString())).thenAnswer(invocation -> {
            AgentPendingAction action = new AgentPendingAction(); action.setId(901L); action.setSessionId(SESSION);
            action.setToolName("create_note"); action.setArgsJson(invocation.getArgument(2)); action.setSummary("合成待确认操作"); action.setStatus("pending");
            return action;
        });
    }

    private void script(Object... results) throws Exception {
        when(routing.forTask(ModelRouting.TASK_CHAT)).thenReturn(new ModelRouting.ModelTarget(
                "synthetic", "synthetic", "http://unused.invalid", "dummy", "mock", true));
        doAnswer(invocation -> {
            JsonNode snapshot = json.valueToTree(invocation.getArgument(0)).deepCopy();
            for (JsonNode message : snapshot) {
                if ("system".equals(message.path("role").asText())
                        && message.path("content").asText().startsWith(AgentContextMemory.SUMMARY_PREFIX)) {
                    memoryRequests.add(snapshot);
                    assertNull(invocation.getArgument(1), "source summarization must never have executable tools");
                    if (memoryFailure != null) throw memoryFailure;
                    return answer("合成工作记忆：保留已处理的来源说明、关键结论与未覆盖事项；摘要不代替原文核验。");
                }
            }
            int index = requestSnapshots.size();
            requestSnapshots.add(snapshot);
            List<?> tools = invocation.getArgument(1); requestHasTools.add(tools != null && !tools.isEmpty());
            if (index >= results.length) throw new AssertionError("unexpected extra synthetic model request: " + index);
            Object result = results[index]; if (result instanceof Exception error) throw error;
            return result;
        }).when(client).chatFull(anyList(), nullable(List.class), anyString(), anyString(), anyString(), anyInt(), anyDouble(), nullable(String.class), nullable(String.class), any(Duration.class));
    }

    private Map<String, Object> plan(String first, String second) {
        return Map.of("plan", List.of(Map.of("step", "读取原文", "status", first), Map.of("step", "核对补充资料", "status", second)));
    }

    private JsonNode call(String id, String name, Map<String, ?> args) throws Exception {
        ObjectNode call = json.createObjectNode().put("id", id).put("type", "function");
        call.putObject("function").put("name", name).put("arguments", json.writeValueAsString(args)); return call;
    }

    private DeepSeekClient.ChatResult tools(JsonNode... calls) {
        ObjectNode message = json.createObjectNode().put("role", "assistant"); message.putArray("tool_calls").addAll(List.of(calls));
        return new DeepSeekClient.ChatResult(message, "tool_calls", 4, 4, 0);
    }

    private DeepSeekClient.ChatResult answer(String text) {
        return new DeepSeekClient.ChatResult(json.createObjectNode().put("role", "assistant").put("content", text), "stop", 4, 4, 0);
    }

    private AiChatVO chat() {
        AiChatRequest request = new AiChatRequest(); request.setMessage(QUESTION); return agent.chat(request);
    }

    private Map<String, JsonNode> toolResults(JsonNode transcript) throws Exception {
        Map<String, JsonNode> results = new LinkedHashMap<>();
        for (JsonNode message : transcript) if ("tool".equals(message.path("role").asText()))
            assertNull(results.put(message.path("tool_call_id").asText(), json.readTree(message.path("content").asText())), "tool response ids must remain unique");
        return results;
    }

    private String taskMemory(JsonNode transcript) {
        for (JsonNode message : transcript) {
            String content = message.path("content").asText();
            if (content.startsWith(AgentContextMemory.PREFIX)) return content;
        }
        throw new AssertionError("the task must retain its successfully processed source memory");
    }

    private void assertPaired(JsonNode transcript) throws Exception {
        List<String> expected = new ArrayList<>();
        for (JsonNode message : transcript) if (message.path("tool_calls").isArray())
            for (JsonNode call : message.path("tool_calls")) expected.add(call.path("id").asText());
        assertEquals(expected, new ArrayList<>(toolResults(transcript).keySet()));
    }
}
