package org.dyh.learnhub.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.dyh.learnhub.dto.AiChatRequest;
import org.dyh.learnhub.entity.AgentPendingAction;
import org.dyh.learnhub.service.AgentSessionService;
import org.dyh.learnhub.service.GroundingService;
import org.dyh.learnhub.service.NoteService;
import org.dyh.learnhub.service.RetrievalContextService;
import org.dyh.learnhub.service.SettingsService;
import org.dyh.learnhub.vo.AiChatVO;
import org.dyh.learnhub.vo.NoteVO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.BiFunction;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Synthetic responses prove milestones are emitted during execution, before the final response. */
@ExtendWith(MockitoExtension.class)
class AgentChatStreamServiceTest {
    private static final String SESSION = "synthetic-progress-session";
    private static final String QUESTION = "整理合成笔记";
    private final ObjectMapper json = new ObjectMapper();
    @Mock DeepSeekClient client;
    @Mock ModelRouting routing;
    @Mock SettingsService settings;
    @Mock AgentSessionService sessions;
    @Mock RetrievalContextService contexts;
    @Mock GroundingService grounding;
    @Mock NoteService notes;
    @Mock ToolEvolutionService evolution;
    @InjectMocks AgentService agent;
    private final List<Map<String, Object>> emitted = new ArrayList<>();
    private String observedSession;
    private final AgentService.ChatListener listener = new AgentService.ChatListener() {
        @Override public void onSession(String id) { observedSession = id; }
        @Override public void onProgress(Map<String, Object> row) { emitted.add(row); }
    };

    @BeforeEach void configure() {
        ReflectionTestUtils.setField(agent, "objectMapper", json);
        when(client.isConfigured()).thenReturn(true);
        when(sessions.ensure(isNull(), isNull(), eq(QUESTION))).thenReturn(SESSION);
        when(settings.effective(SettingsService.KEY_CHAT_PROMPT)).thenReturn("合成系统提示");
        when(contexts.build(QUESTION, 8, "fused", false, false)).thenAnswer(invocation -> {
            if (!emitted.isEmpty()) assertEquals("检索相关知识", emitted.getLast().get("title"));
            return RetrievalContextService.Context.empty();
        });
        when(routing.forTask(ModelRouting.TASK_CHAT)).thenReturn(new ModelRouting.ModelTarget(
                "synthetic", "synthetic", "http://unused.invalid", "dummy", "mock", true));
        when(evolution.definitions(anyList(), anySet())).thenReturn(List.of());
    }

    @Test void emitsRealPhasesBeforeBlockingOperationsAndPersistsFinalSnapshotAfterAnswer() throws Exception {
        whenChat().thenAnswer(invocation -> {
            assertEquals(SESSION, observedSession);
            assertEquals("分析请求", emitted.getLast().get("title"));
            assertEquals("running", emitted.getLast().get("status"));
            return answer();
        });
        AiChatVO result = agent.chat(request(), listener);
        assertEquals("合成回答", result.getReply());
        assertEquals(List.of("准备对话", "检索相关知识", "分析请求", "本轮结果", "保存对话"),
                result.getProgress().stream().map(item -> item.get("title")).toList());
        assertTrue(result.getProgress().stream().allMatch(item -> "completed".equals(item.get("status"))));
        assertNotNull(result.getElapsedMs());
        var order = inOrder(sessions);
        order.verify(sessions).append(eq(SESSION), eq("assistant"), eq("合成回答"), isNull(), isNull(), anyInt(), eq("stop"));
        order.verify(sessions).append(eq(SESSION), eq("progress"), anyString(), isNull(), isNull());
        assertEquals(result.getProgress().size(), persisted().path("progress").size());
    }

    @Test void nestedAdaptiveToolsAppearUnderParentAndWritesEndAtWaitingInsteadOfCompleted() throws Exception {
        String adaptive = "adaptive_synthetic_workflow";
        when(evolution.isAdaptive(adaptive)).thenReturn(true);
        when(evolution.execute(eq(adaptive), any(), anyList(), anySet(), any(), any())).thenAnswer(invocation -> {
            @SuppressWarnings("unchecked") var caller = (BiFunction<String, JsonNode, JsonNode>) invocation.getArgument(4);
            caller.apply("get_note", json.valueToTree(Map.of("note_id", 21)));
            return caller.apply("create_note", json.valueToTree(Map.of("title", "合成笔记", "content", "合成正文")));
        });
        NoteVO note = new NoteVO();
        note.setId(21L); note.setTitle("合成原文"); note.setContent("不可出现在过程摘要里的合成正文");
        when(notes.detail(21L)).thenReturn(note);
        stubPending();
        whenChat().thenReturn(tool(adaptive, Map.of()), answer());

        AiChatVO result = agent.chat(request(), listener);
        List<Map<String, Object>> tools = result.getProgress().stream().filter(item -> "tool".equals(item.get("kind"))).toList();
        assertEquals(3, tools.size());
        assertEquals(adaptive, tools.get(0).get("toolName"));
        assertEquals("waiting", tools.get(0).get("status"));
        assertEquals("completed", tools.get(1).get("status"));
        assertEquals("waiting", tools.get(2).get("status"));
        assertEquals(101L, tools.get(0).get("actionId"));
        assertEquals(101L, tools.get(2).get("actionId"));
        assertEquals(tools.get(0).get("id"), tools.get(1).get("parentId"));
        assertEquals(tools.get(0).get("id"), tools.get(2).get("parentId"));
        assertFalse(json.writeValueAsString(result.getProgress()).contains("不可出现在过程摘要"));
        assertEquals(1, result.getPendingActions().size());
        verify(notes, never()).save(any());
    }

    @Test void generatedCodeHasANestedLiveStepWithoutExposingItsSourceOrInput() throws Exception {
        String adaptive = "adaptive_synthetic_parser";
        when(evolution.isAdaptive(adaptive)).thenReturn(true);
        when(evolution.execute(eq(adaptive), any(), anyList(), anySet(), any(), any())).thenAnswer(invocation -> {
            java.util.function.BiConsumer<String, JsonNode> observer = invocation.getArgument(5);
            observer.accept("start", json.valueToTree(Map.of("id", "parse", "source", "private-source", "input", "private-input")));
            assertEquals("运行 Python 代码", emitted.getLast().get("title"));
            assertEquals("running", emitted.getLast().get("status"));
            observer.accept("finish", json.valueToTree(Map.of("id", "parse", "ok", true, "elapsedMs", 200)));
            return json.valueToTree(Map.of("ok", true, "output", List.of("synthetic")));
        });
        whenChat().thenReturn(tool(adaptive, Map.of()), answer());

        AiChatVO result = agent.chat(request(), listener);

        var steps = result.getProgress().stream().filter(step -> "tool".equals(step.get("kind"))).toList();
        assertEquals(2, steps.size());
        assertEquals(steps.getFirst().get("id"), steps.getLast().get("parentId"));
        assertEquals("completed", steps.getLast().get("status"));
        assertFalse(json.writeValueAsString(result.getProgress()).contains("private-"));
    }

    @Test void sameSessionCannotStartAnotherTurnWhileItsDisconnectedWorkIsRunning() throws Exception {
        CountDownLatch modelEntered = new CountDownLatch(1);
        CountDownLatch finishModel = new CountDownLatch(1);
        AtomicInteger rounds = new AtomicInteger();
        whenChat().thenAnswer(invocation -> {
            if (rounds.incrementAndGet() == 1) {
                modelEntered.countDown();
                assertTrue(finishModel.await(5, TimeUnit.SECONDS));
            }
            return answer();
        });
        try (var worker = Executors.newSingleThreadExecutor()) {
            var running = worker.submit(() -> agent.chat(request(), listener));
            try {
                assertTrue(modelEntered.await(3, TimeUnit.SECONDS));
                assertThrows(AgentService.ChatBusyException.class, () -> agent.chat(request()));
                verify(sessions, times(1)).append(eq(SESSION), eq("user"), eq(QUESTION), isNull(), isNull());
            } finally { finishModel.countDown(); }
            assertEquals("合成回答", running.get(5, TimeUnit.SECONDS).getReply());
            assertEquals("合成回答", agent.chat(request(), listener).getReply());
        }
        verify(sessions, times(2)).append(eq(SESSION), eq("user"), eq(QUESTION), isNull(), isNull());
        verify(sessions, times(2)).append(eq(SESSION), eq("progress"), anyString(), isNull(), isNull());
    }

    @Test void failedToolIsVisibleAndTheFollowingAnswerCanStillComplete() throws Exception {
        when(notes.detail(21L)).thenThrow(new IllegalArgumentException("synthetic-private-detail"));
        whenChat().thenReturn(tool("get_note", Map.of("note_id", 21)), answer());
        AiChatVO result = agent.chat(request(), listener);
        var toolStep = result.getProgress().stream().filter(item -> "tool".equals(item.get("kind"))).findFirst().orElseThrow();
        assertEquals("error", toolStep.get("status"));
        assertEquals("合成回答", result.getReply());
        assertFalse(json.writeValueAsString(result.getProgress()).contains("synthetic-private-detail"));
    }

    @Test void modelFailureAfterStagingPersistsPartialTraceAndKeepsApproval() throws Exception {
        stubPending();
        whenChat().thenReturn(tool("create_note", Map.of("title", "合成笔记", "content", "合成正文")))
                .thenThrow(new IllegalStateException("synthetic-provider-error"));
        AiChatVO result = agent.chat(request(), listener);
        assertEquals("awaiting_confirmation", result.getRunSummary().get("stopReason"));
        assertEquals(1, result.getPendingActions().size());
        assertTrue(result.getReply().contains("确认"));
        assertFalse(result.getReply().contains("synthetic-provider-error"));
        JsonNode trace = persisted().path("progress");
        assertTrue(trace.findValuesAsText("status").contains("waiting"));
        assertTrue(trace.findValuesAsText("status").contains("error"));
        assertFalse(trace.toString().contains("synthetic-provider-error"));
        verify(sessions).stageAction(eq(SESSION), eq("create_note"), anyString(), anyString());
        verify(sessions).append(eq(SESSION), eq("assistant"), eq(result.getReply()), isNull(), isNull(), isNull(), isNull());
        verify(notes, never()).save(any());
        doReturn(answer()).when(client).chatFull(anyList(), nullable(List.class), anyString(), anyString(), anyString(), anyInt(),
                anyDouble(), nullable(String.class), nullable(String.class), any(Duration.class));
        assertDoesNotThrow(() -> agent.chat(request(), listener), "failed turns must also release the session guard");
    }

    @Test void brokenTransportStillSavesAndReturnsACompleteConversation() throws Exception {
        whenChat().thenReturn(answer());
        AgentService.ChatListener disconnected = new AgentService.ChatListener() {
            @Override public void onSession(String id) { throw new IllegalStateException("synthetic-disconnect"); }
            @Override public void onProgress(Map<String, Object> row) { throw new IllegalStateException("synthetic-disconnect"); }
        };
        AiChatVO result = agent.chat(request(), disconnected);
        assertEquals("合成回答", result.getReply());
        assertEquals("completed", result.getProgress().getLast().get("status"));
        assertFalse(persisted().path("progress").isEmpty());
    }

    private AiChatRequest request() {
        AiChatRequest request = new AiChatRequest();
        request.setMessage(QUESTION);
        return request;
    }

    private void stubPending() {
        when(sessions.stageAction(eq(SESSION), eq("create_note"), anyString(), anyString())).thenAnswer(invocation -> {
            AgentPendingAction action = new AgentPendingAction();
            action.setId(101L); action.setSessionId(SESSION); action.setToolName("create_note");
            action.setSummary("新建合成笔记"); action.setStatus("pending"); action.setArgsJson(invocation.getArgument(2));
            return action;
        });
    }

    private JsonNode persisted() throws Exception {
        ArgumentCaptor<String> data = ArgumentCaptor.forClass(String.class);
        verify(sessions).append(eq(SESSION), eq("progress"), data.capture(), isNull(), isNull());
        return json.readTree(data.getValue());
    }

    private org.mockito.stubbing.OngoingStubbing<DeepSeekClient.ChatResult> whenChat() throws Exception {
        return when(client.chatFull(anyList(), nullable(List.class), anyString(), anyString(), anyString(), anyInt(),
                anyDouble(), nullable(String.class), nullable(String.class), any(Duration.class)));
    }

    private DeepSeekClient.ChatResult answer() {
        return new DeepSeekClient.ChatResult(json.createObjectNode().put("content", "合成回答"), "stop", 2, 2, 0);
    }

    private DeepSeekClient.ChatResult tool(String name, Map<String, Object> args) throws Exception {
        var message = json.createObjectNode();
        message.putArray("tool_calls").addObject().put("id", "synthetic-call").put("type", "function")
                .putObject("function").put("name", name).put("arguments", json.writeValueAsString(args));
        return new DeepSeekClient.ChatResult(message, "tool_calls", 2, 2, 0);
    }
}
