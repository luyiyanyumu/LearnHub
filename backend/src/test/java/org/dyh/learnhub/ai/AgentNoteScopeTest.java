package org.dyh.learnhub.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.dyh.learnhub.dto.AiChatMessage;
import org.dyh.learnhub.dto.AiChatRequest;
import org.dyh.learnhub.dto.NoteEditRequest;
import org.dyh.learnhub.entity.AgentEvent;
import org.dyh.learnhub.entity.AgentPendingAction;
import org.dyh.learnhub.service.AgentSessionService;
import org.dyh.learnhub.service.NoteContentEditor;
import org.dyh.learnhub.service.NoteService;
import org.dyh.learnhub.service.RetrievalContextService;
import org.dyh.learnhub.vo.AiChatVO;
import org.dyh.learnhub.vo.NoteEditVO;
import org.dyh.learnhub.vo.NoteVO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Scope comes from the complete request, even when the proposing tool contains only its first batch. */
@ExtendWith(MockitoExtension.class)
class AgentNoteScopeTest {
    private static final long NOTE_ID = 12L;
    private static final String SESSION = "synthetic-scope-session";
    private static final String ORIGINAL = IntStream.rangeClosed(12, 23)
            .mapToObj(i -> "## 合成" + i + "\n\n保留第" + i + "节。")
            .collect(Collectors.joining("\n\n"));
    private static final String HASH = NoteContentEditor.hash(ORIGINAL);
    private static final String REQUEST = "请把第12至23节全部补充完整，每节给出定义和一个完整公式示例，一次统一预览。";
    private static final String HISTORY = "合成历史约束：保留现有标题和原文，12至23节都需要补充。";
    private static final ModelRouting.ModelTarget TARGET = new ModelRouting.ModelTarget(
            "synthetic", "synthetic", "http://unused.invalid", "dummy", "mock", true);

    private final ObjectMapper json = new ObjectMapper();
    @Mock DeepSeekClient client;
    @Mock ModelRouting routing;
    @Mock NoteService notes;
    @Mock AgentSessionService sessions;
    @InjectMocks AgentService agent;

    @BeforeEach void setup() {
        ReflectionTestUtils.setField(agent, "objectMapper", json);
        NoteVO note = new NoteVO();
        note.setId(NOTE_ID); note.setTitle("合成范围笔记"); note.setContent(ORIGINAL);
        lenient().when(notes.detail(NOTE_ID)).thenReturn(note);
        lenient().when(routing.forTask(ModelRouting.TASK_CHAT)).thenReturn(TARGET);
    }

    @Test void firstBatchProposalIsExpandedToTheEntireRequestedScopeBeforeOneApproval() throws Exception {
        AtomicInteger planners = new AtomicInteger(), children = new AtomicInteger();
        AtomicInteger running = new AtomicInteger(), peak = new AtomicInteger();
        CountDownLatch firstThree = new CountDownLatch(3);
        Set<String> generated = ConcurrentHashMap.newKeySet();
        model().thenAnswer(inv -> {
            JsonNode messages = json.valueToTree(inv.getArgument(0));
            String system = messages.get(0).path("content").asText();
            assertNull(inv.getArgument(1), "规划器和写作子智能体都不能获得写入工具");
            if (system.contains("完整任务范围规划器")) {
                planners.incrementAndGet();
                String planningContext = messages.toString();
                assertTrue(planningContext.contains(REQUEST), "不能只依据原工具提出的12–14节规划");
                assertTrue(planningContext.contains(HISTORY), "完整任务范围必须考虑用户历史约束");
                assertTrue(planningContext.contains("合成23"), "规划器应收到完整原文定位范围");
                return response(plan(12).toString(), "stop");
            }
            assertTrue(system.contains("无工具权限"));
            String user = messages.get(1).path("content").asText();
            var heading = java.util.regex.Pattern.compile("负责分段：(合成\\d+)").matcher(user);
            assertTrue(heading.find());
            assertTrue(generated.add(heading.group(1)), "每一项完整任务只生成一次");
            children.incrementAndGet();
            int active = running.incrementAndGet(); peak.accumulateAndGet(active, Math::max);
            firstThree.countDown();
            try {
                assertTrue(firstThree.await(2, TimeUnit.SECONDS));
                return response("\n\n" + heading.group(1) + "完整补充。\n\n$$\nx^2+y^2=z^2\n$$\n", "stop");
            } finally { running.decrementAndGet(); }
        });
        stageUsingRealEditor();
        AiChatVO vo = new AiChatVO();

        JsonNode result = run("edit_note", firstBatch(), vo);

        assertTrue(result.path("staged").asBoolean());
        assertEquals(1, planners.get());
        assertEquals(12, children.get());
        assertEquals(3, peak.get(), "分段由系统内部限流，无需用户逐批发起");
        assertEquals(IntStream.rangeClosed(12, 23).mapToObj(i -> "合成" + i).collect(Collectors.toSet()), generated);
        assertEquals(1, vo.getPendingActions().size());
        verify(sessions).stageAction(eq(SESSION), eq("edit_note"), argThat(raw -> {
            try {
                JsonNode edit = json.readTree(raw);
                return edit.path("note_id").asLong() == NOTE_ID
                        && HASH.equals(edit.path("expected_hash").asText()) && edit.path("operations").size() == 12;
            } catch (Exception failure) { return false; }
        }), anyString());
        verify(notes, never()).editContent(anyLong(), any());
    }

    @Test void declaredUnitCountMismatchCannotStageTheFirstBatchOrGenerateChildren() throws Exception {
        ObjectNode plan = plan(12); plan.put("unitCount", 13);
        assertRejectedPlan(plan);
    }

    @Test void duplicatePlannedUnitsCannotStageTheFirstBatchOrGenerateChildren() throws Exception {
        ObjectNode plan = plan(12);
        ((ObjectNode) plan.path("units").get(1)).put("id", "u12");
        assertRejectedPlan(plan);
    }

    @Test void explicitlyIncompletePlanCannotStageTheFirstBatchOrGenerateChildren() throws Exception {
        ObjectNode plan = plan(12); plan.put("complete", false);
        assertRejectedPlan(plan);
    }

    @Test void staleOriginalHashStopsBeforePlanningOrWriting() throws Exception {
        ObjectNode args = firstBatch(); args.put("expected_hash", "synthetic-stale-hash");

        assertThrows(IllegalArgumentException.class, () -> run("edit_note", args, new AiChatVO()));

        verifyNoInteractions(client, sessions);
        verify(notes, never()).previewEdit(anyLong(), any());
        verify(notes, never()).editContent(anyLong(), any());
    }

    @Test void oneTruncatedChildPreventsAnyPartialApprovalAcrossTheCompleteScope() throws Exception {
        AtomicInteger children = new AtomicInteger();
        model().thenAnswer(inv -> {
            JsonNode messages = json.valueToTree(inv.getArgument(0));
            assertNull(inv.getArgument(1));
            if (messages.get(0).path("content").asText().contains("完整任务范围规划器"))
                return response(plan(12).toString(), "stop");
            return response("合成完整片段。", children.getAndIncrement() == 0 ? "length" : "stop");
        });

        JsonNode result = run("edit_note", firstBatch(), new AiChatVO());

        assertFalse(result.path("ok").asBoolean());
        assertEquals("note_sections_incomplete", result.path("code").asText());
        assertEquals(12, children.get());
        verify(sessions, never()).stageAction(any(), any(), any(), any());
        verify(notes, never()).previewEdit(anyLong(), any());
        verify(notes, never()).editContent(anyLong(), any());
    }

    @Test void exactPlanPreservesTheOriginalEditInsteadOfInventingSectionTasks() throws Exception {
        model().thenReturn(response("{\"mode\":\"exact\",\"goal\":\"保留明确指定的合成改动\",\"complete\":true}", "stop"));
        stageUsingRealEditor();
        ObjectNode args = firstBatch();

        assertTrue(run("edit_note", args, new AiChatVO()).path("staged").asBoolean());

        verify(sessions).stageAction(eq(SESSION), eq("edit_note"), argThat(raw -> {
            try {
                JsonNode edit = json.readTree(raw);
                return args.path("operations").equals(edit.path("operations"))
                        && HASH.equals(edit.path("expected_hash").asText());
            } catch (Exception failure) { return false; }
        }), anyString());
        verifyModelCalls(1);
    }

    @Test void exactPlanCannotMakeSectionGenerationToolPretendItsScopeIsComplete() throws Exception {
        model().thenReturn(response("{\"mode\":\"exact\",\"goal\":\"合成目标\",\"complete\":true}", "stop"));
        ObjectNode args = json.createObjectNode().put("note_id", NOTE_ID).put("expected_hash", HASH);
        args.putArray("tasks").addObject().put("heading", "合成12").put("action", "insert_after")
                .put("text", "## 合成12").put("instructions", "补充合成完整示例。");

        assertThrows(IllegalArgumentException.class, () -> run("write_note_sections", args, new AiChatVO()));

        verify(sessions, never()).stageAction(any(), any(), any(), any());
        verify(notes, never()).previewEdit(anyLong(), any());
    }

    @Test void persistedConversationProvidesTheWholeTaskAndBatchHistoryDoesNotBecomeAPauseBoundary() throws Exception {
        String oldAssistant = "合成旧回复：本次只提交12至14节，请确认后再继续，后续批次还有15至23节。";
        String summary = "合成摘要：用户要求完整补充第12至23节。";
        String memory = "合成任务记忆：完整目标12项，不能让用户逐批确认。";
        List<Map<String, Object>> actions = List.of(Map.of("id", 17, "status", "pending", "toolName", "edit_note",
                "summary", "合成12至14节仍待确认，未写入"));
        when(sessions.projection(SESSION, AgentSessionService.PROJECTION_ROUNDS)).thenReturn(List.of(
                event("user", REQUEST), event("assistant", oldAssistant)));
        when(sessions.latestSummary(SESSION)).thenReturn(summary);
        when(sessions.latestTaskMemory(SESSION)).thenReturn(memory);
        when(sessions.actionContext(SESSION)).thenReturn(actions);
        AtomicInteger children = completeScopeModel(input -> {
            assertEquals("继续，把原任务剩余部分全部完成。", input.path("current_request").asText());
            String conversation = input.path("conversation").toString();
            for (String required : List.of(REQUEST, oldAssistant, summary, memory)) assertTrue(conversation.contains(required));
            assertFalse(conversation.contains("合成过渡旧客户端"), "服务端历史存在时不能混入旧客户端的局部任务");
            assertEquals(json.valueToTree(actions), input.path("recorded_action_states"));
        });
        stageUsingRealEditor();
        AiChatVO vo = new AiChatVO();

        JsonNode result = run("edit_note", firstBatch(), vo, legacyRequest("继续，把原任务剩余部分全部完成。"));

        assertTrue(result.path("scopeComplete").asBoolean());
        assertEquals(12, children.get());
        assertOneFullApproval(vo);
    }

    @Test void persistedSummaryAndTaskMemoryRetainTheFullScopeWhenRecentProjectionIsEmpty() throws Exception {
        String summary = "合成摘要中的原始任务：把12至23全部补充完整。";
        String memory = "合成任务记忆中的边界：十二项都需要完成，旧助手的后续批次不是用户暂停。";
        when(sessions.projection(SESSION, AgentSessionService.PROJECTION_ROUNDS)).thenReturn(List.of());
        when(sessions.latestSummary(SESSION)).thenReturn(summary);
        when(sessions.latestTaskMemory(SESSION)).thenReturn(memory);
        AtomicInteger children = completeScopeModel(input -> {
            String conversation = input.path("conversation").toString();
            assertTrue(conversation.contains(summary));
            assertTrue(conversation.contains(memory));
            assertFalse(conversation.contains("合成过渡旧客户端"), "持久摘要应优先于客户端的局部分批历史");
        });
        stageUsingRealEditor();
        AiChatVO vo = new AiChatVO();

        JsonNode result = run("edit_note", firstBatch(), vo, legacyRequest("继续完成。"));

        assertTrue(result.path("scopeComplete").asBoolean());
        assertEquals(12, children.get());
        assertOneFullApproval(vo);
    }

    @Test void actualToolGatewayPlansALongEditAcrossTheCompleteTaskBeforeStaging() throws Exception {
        AtomicInteger children = completeScopeModel(input -> {
            assertEquals("edit_note", input.path("attempted_tool").asText());
            assertEquals(3, input.path("partial_proposal_not_scope").path("operations").size());
            assertEquals(REQUEST, input.path("current_request").asText());
        });
        stageUsingRealEditor();
        AiChatVO vo = new AiChatVO();
        AiChatRequest request = new AiChatRequest(); request.setMessage(REQUEST); request.setNoteId(NOTE_ID);

        JsonNode result = ReflectionTestUtils.invokeMethod(agent, "runChatTool", "edit_note", firstBatch().toString(),
                vo.getEvents(), SESSION, vo, request, new AgentService.WikiToolBudget(),
                new AgentToolEvidence(RetrievalContextService.Context.empty(), json), new AgentService.EvolutionBudget(),
                System.currentTimeMillis() + 270_000);

        assertNotNull(result);
        assertTrue(result.path("staged").asBoolean(), result.toString());
        assertEquals(12, result.path("plannedSections").asInt());
        assertEquals(12, children.get(), "真实工具入口必须生成完整任务，而不是直接审批3项长正文");
        assertOneFullApproval(vo);
    }

    private AtomicInteger completeScopeModel(Consumer<JsonNode> inspectPlannerInput) throws Exception {
        AtomicInteger children = new AtomicInteger();
        model().thenAnswer(inv -> {
            JsonNode messages = json.valueToTree(inv.getArgument(0));
            assertNull(inv.getArgument(1));
            String system = messages.get(0).path("content").asText();
            if (system.contains("完整任务范围规划器")) {
                assertTrue(system.contains("历史助手说的"));
                assertTrue(system.contains("不是用户设置的暂停边界"));
                inspectPlannerInput.accept(json.readTree(messages.get(1).path("content").asText()));
                return response(plan(12).toString(), "stop");
            }
            assertTrue(system.contains("无工具权限"));
            children.incrementAndGet();
            return response("\n\n合成完整补充。\n\n$$\nx+y=z\n$$\n", "stop");
        });
        return children;
    }

    private void assertOneFullApproval(AiChatVO vo) throws Exception {
        assertEquals(1, vo.getPendingActions().size());
        verify(sessions, times(1)).stageAction(anyString(), anyString(), anyString(), anyString());
        verify(sessions).stageAction(eq(SESSION), eq("edit_note"), argThat(raw -> {
            try { return json.readTree(raw).path("operations").size() == 12; }
            catch (Exception failure) { return false; }
        }), anyString());
        verifyModelCalls(13);
        verify(notes, never()).editContent(anyLong(), any());
    }

    private AgentEvent event(String role, String content) {
        AgentEvent event = new AgentEvent(); event.setRole(role); event.setContent(content); event.setSessionId(SESSION);
        return event;
    }

    private AiChatRequest legacyRequest(String message) {
        AiChatRequest request = new AiChatRequest(); request.setMessage(message); request.setNoteId(NOTE_ID);
        request.setHistory(List.of(new AiChatMessage("user", "合成过渡旧客户端：只补充12至14，不是完整原始目标。")));
        return request;
    }

    private void assertRejectedPlan(ObjectNode plan) throws Exception {
        model().thenReturn(response(plan.toString(), "stop"));

        assertThrows(IllegalArgumentException.class, () -> run("edit_note", firstBatch(), new AiChatVO()));

        verifyModelCalls(1);
        verify(sessions, never()).stageAction(any(), any(), any(), any());
        verify(notes, never()).previewEdit(anyLong(), any());
        verify(notes, never()).editContent(anyLong(), any());
    }

    private void stageUsingRealEditor() {
        when(notes.previewEdit(eq(NOTE_ID), any())).thenAnswer(inv -> {
            NoteEditRequest request = inv.getArgument(1);
            var patched = new NoteContentEditor().apply(ORIGINAL, request);
            for (int i = 12; i <= 23; i++) assertTrue(patched.content().contains("保留第" + i + "节。"));
            return new NoteEditVO(NOTE_ID, "合成范围笔记", HASH, NoteContentEditor.hash(patched.content()), true, patched.changes());
        });
        when(sessions.stageAction(anyString(), eq("edit_note"), anyString(), anyString())).thenAnswer(inv -> {
            AgentPendingAction action = new AgentPendingAction(); action.setId(9L); action.setStatus("pending");
            action.setToolName("edit_note"); action.setArgsJson(inv.getArgument(2)); action.setSummary(inv.getArgument(3));
            return action;
        });
    }

    private org.mockito.stubbing.OngoingStubbing<DeepSeekClient.ChatResult> model() throws Exception {
        return when(client.chatFull(anyList(), isNull(), eq(TARGET.baseUrl()), eq(TARGET.apiKey()), eq(TARGET.model()),
                anyInt(), anyDouble(), nullable(String.class), nullable(String.class), any(Duration.class)));
    }

    private void verifyModelCalls(int count) throws Exception {
        verify(client, times(count)).chatFull(anyList(), isNull(), eq(TARGET.baseUrl()), eq(TARGET.apiKey()), eq(TARGET.model()),
                anyInt(), anyDouble(), nullable(String.class), nullable(String.class), any(Duration.class));
    }

    private JsonNode run(String function, JsonNode args, AiChatVO vo) throws Exception {
        AiChatRequest request = new AiChatRequest(); request.setMessage(REQUEST); request.setNoteId(NOTE_ID);
        request.setHistory(List.of(new AiChatMessage("user", HISTORY)));
        return run(function, args, vo, request);
    }

    private JsonNode run(String function, JsonNode args, AiChatVO vo, AiChatRequest request) throws Exception {
        return agent.planAndWriteNoteSections(function, args, vo.getEvents(), SESSION, vo, request,
                System.currentTimeMillis() + 270_000, null, null);
    }

    private ObjectNode firstBatch() {
        ObjectNode args = json.createObjectNode().put("note_id", NOTE_ID).put("expected_hash", HASH);
        var edits = args.putArray("operations");
        for (int i = 12; i <= 14; i++) edits.addObject().put("action", "insert_after")
                .put("text", "## 合成" + i).put("value", "\n\n" + ("仅第一批提案" + i + "。").repeat(180));
        return args;
    }

    private ObjectNode plan(int count) {
        ObjectNode plan = json.createObjectNode().put("mode", "sections").put("goal", REQUEST)
                .put("complete", true).put("unitCount", count);
        var units = plan.putArray("units");
        for (int i = 12; i < 12 + count; i++) units.addObject().put("id", "u" + i).put("heading", "合成" + i)
                .put("action", "insert_after").put("text", "## 合成" + i)
                .put("instructions", "为第" + i + "节补充完整定义和公式示例，保留原文。");
        return plan;
    }

    private DeepSeekClient.ChatResult response(String content, String reason) {
        return new DeepSeekClient.ChatResult(json.createObjectNode().put("role", "assistant").put("content", content), reason, 30, 30, 0);
    }
}
