package org.dyh.learnhub.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.dyh.learnhub.dto.AiChatRequest;
import org.dyh.learnhub.entity.AgentPendingAction;
import org.dyh.learnhub.service.AgentSessionService;
import org.dyh.learnhub.service.NoteService;
import org.dyh.learnhub.service.RetrievalContextService;
import org.dyh.learnhub.vo.AiChatVO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.stubbing.OngoingStubbing;
import org.springframework.test.util.ReflectionTestUtils;

import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Synthetic time and model responses only: no sleeping, database access, or network calls. */
@ExtendWith(MockitoExtension.class)
class AgentLongNoteBudgetTest {
    private static final String TITLE = "合成长文预算";
    private static final String OUTLINE = "## 第一节\n介绍合成知识\n## 第二节\n说明合成示例";
    private static final String SESSION = "synthetic-long-note-budget";
    private static final ModelRouting.ModelTarget TARGET = new ModelRouting.ModelTarget(
            "synthetic-budget", "synthetic", "http://unused.invalid", "dummy", "mock", true);
    private final ObjectMapper json = new ObjectMapper();
    private final AtomicLong now = new AtomicLong();
    private final List<JsonNode> drafts = new ArrayList<>();
    private final List<String> summaries = new ArrayList<>();
    private final AiChatVO vo = new AiChatVO();
    @Mock DeepSeekClient client;
    @Mock ModelRouting routing;
    @Mock AgentSessionService sessions;
    @Mock NoteService notes;
    @InjectMocks AgentService agent;

    @BeforeEach void configure() {
        ReflectionTestUtils.setField(agent, "objectMapper", json);
        ReflectionTestUtils.setField(agent, "longNoteClock", (LongSupplier) now::get);
        lenient().when(routing.forTask(ModelRouting.TASK_CHAT)).thenReturn(TARGET);
    }

    @Test void moreThanTwelveSectionsAreGeneratedInOneRequestAndStagedAsOneCompleteNote() throws Exception {
        stageDrafts();
        List<String> headings = IntStream.rangeClosed(1, 15).mapToObj(i -> "合成章节" + i).toList();
        String outline = "## 目录\n由编辑器生成\n" + String.join("\n", headings.stream()
                .map(heading -> "## " + heading + "\n解释该节的合成要点").toList());
        String body = String.join("\n\n", headings.stream().map(heading -> "## " + heading + "\n\n该节的实际合成正文。").toList());
        model().thenReturn(answer(body, false));

        JsonNode result = run(outline, 270_000);

        assertComplete(result, headings.size());
        assertEquals(body, drafts.getFirst().path("content").asText());
        assertEquals(body.length(), result.path("chars").asInt());
        assertEquals(0, result.path("truncatedSections").asInt());
        assertEquals(List.of(), unfinished(result));
        assertFalse(drafts.getFirst().has("_generation"));
        assertTrue(roleText(modelMessages(), "user").contains(outline), "完整大纲必须原样交给同一次生成请求");
        assertTrue(vo.getEvents().stream().noneMatch(event -> event.contains("只写前") || event.contains("大纲已拆成")));
    }

    @Test void unstructuredOutlineGoesDirectlyToOneGenerationWithoutExpansion() throws Exception {
        stageDrafts();
        String outline = "介绍基础知识，再说明完整合成示例与适用条件。";
        String body = "这是直接生成的完整正文，包含基础、示例与适用条件。";
        model().thenReturn(answer(body, false));

        JsonNode result = run(outline, 270_000);

        assertComplete(result, 1);
        assertEquals(body, drafts.getFirst().path("content").asText());
        assertTrue(roleText(modelMessages(), "user").contains(outline));
        assertTrue(vo.getEvents().stream().noneMatch(event -> event.contains("大纲已拆成")));
    }

    @ParameterizedTest
    @ValueSource(ints = {4096, 16384, 32768})
    void wholeNoteUsesTheSelectedProfilesOutputTokenBudget(int tokens) throws Exception {
        stageDrafts();
        when(client.maxTokensOf(TARGET.id())).thenReturn(tokens);
        model().thenReturn(answer("完整的合成笔记正文。", false));

        assertComplete(run(OUTLINE, 270_000), 2);

        verify(client).maxTokensOf(TARGET.id());
        verify(client).chatFull(anyList(), isNull(), eq(TARGET.baseUrl()), eq(TARGET.apiKey()), eq(TARGET.model()),
                eq(tokens), anyDouble(), nullable(String.class), nullable(String.class), eq(Duration.ofSeconds(180)));
        verifyModelCalls(1);
    }

    @ParameterizedTest
    @ValueSource(ints = {0, -1})
    void invalidMockTokenBudgetsFallBackTo8192(int tokens) throws Exception {
        stageDrafts();
        when(client.maxTokensOf(TARGET.id())).thenReturn(tokens);
        model().thenReturn(answer("完整的合成笔记正文。", false));

        assertComplete(run(OUTLINE, 270_000), 2);

        verify(client).chatFull(anyList(), isNull(), eq(TARGET.baseUrl()), eq(TARGET.apiKey()), eq(TARGET.model()),
                eq(8192), anyDouble(), nullable(String.class), nullable(String.class), any(Duration.class));
        verifyModelCalls(1);
    }

    @Test void wholeGenerationSharesTheChatDeadlineAndReservesFiveSecondsForItsApprovalCard() throws Exception {
        stageDrafts();
        now.set(100_000);
        model().thenAnswer(invocation -> {
            assertEquals(Duration.ofSeconds(165), invocation.getArgument(9));
            now.set(265_000);
            return answer("在剩余预算内一次生成的完整合成正文。", false);
        });

        assertComplete(run(OUTLINE, 270_000), 2);

        verifyModelCalls(1);
        verifyNoInteractions(notes);
    }

    @ParameterizedTest
    @ValueSource(longs = {-1, 0, 5_000})
    void expiredOrReservedChatBudgetPreventsGenerationAndEmptyApprovalCards(long deadline) throws Exception {
        JsonNode result = run(OUTLINE, deadline);

        assertUnstaged(result);
        assertEquals("round_budget_exhausted", result.path("code").asText());
        assertEquals(List.of("第一节", "第二节"), unfinished(result));
        verifyNoInteractions(client);
    }

    @Test void lengthLimitStartsTwoIndependentChildRequestsAndMergesTheirBodiesIntoOneApprovalCard() throws Exception {
        stageDrafts();
        AtomicInteger wholeCalls = new AtomicInteger();
        String firstBody = "## 第一节\n\n合成公式：\n\n$$\np(y\\mid x)=\\frac{e^{s_y}}{\\sum_j e^{s_j}}\n$$";
        String secondBody = "第二节的真实合成实现：\n\n" + "```python\ndef example(value):\n    return value * 2\n```";
        model().thenAnswer(invocation -> {
            String prompt = json.valueToTree(invocation.getArgument(0)).toString();
            if (!prompt.contains("负责的小节：")) {
                assertEquals(1, wholeCalls.incrementAndGet(), "结构化大纲不应额外规划或模型合并全文");
                return answer("首轮实际生成但被截断的正文", true);
            }
            assertTrue(prompt.contains("子智能体"));
            assertEquals(Duration.ofSeconds(180), invocation.getArgument(9));
            if (prompt.contains("负责的小节：第一节")) return answer(firstBody, false);
            if (prompt.contains("负责的小节：第二节")) return answer(secondBody, false);
            fail("未知的子智能体分工");
            return null;
        });

        JsonNode result = run(OUTLINE, 270_000);

        assertComplete(result, 2, "sub_agents");
        assertEquals(2, result.path("subAgents").asInt());
        String body = drafts.getFirst().path("content").asText();
        assertTrue(body.contains(firstBody));
        assertTrue(body.contains(secondBody));
        assertEquals(1, headingCount(body, "第一节"));
        assertEquals(1, headingCount(body, "第二节"));
        assertTrue(body.indexOf("## 第一节") < body.indexOf("## 第二节"));
        assertFalse(body.contains("首轮实际生成但被截断的正文"));
        verifyModelCalls(3);
    }

    @Test void progressReportsTheActualWholeAttemptChildTasksAndMergeUnderOneWaitingTool() throws Exception {
        stageDrafts();
        List<Map<String, Object>> pushed = new CopyOnWriteArrayList<>();
        AgentChatProgress progress = new AgentChatProgress(new AgentService.ChatListener() {
            @Override public void onProgress(Map<String, Object> item) { pushed.add(item); }
        });
        String externalParentId = "synthetic-parent-step";
        model().thenAnswer(invocation -> {
            String prompt = json.valueToTree(invocation.getArgument(0)).toString();
            if (prompt.contains("负责的小节：第一节")) return answer("第一节的完整合成正文。", false);
            if (prompt.contains("负责的小节：第二节")) return answer("第二节的完整合成正文。", false);
            return answer("首轮实际生成但被截断的全文", true);
        });
        String args = json.valueToTree(Map.of("title", TITLE, "outline", OUTLINE)).toString();

        JsonNode result = ReflectionTestUtils.invokeMethod(agent, "runChatTool", "write_long_note", args,
                vo.getEvents(), SESSION, vo, new AiChatRequest(), new AgentService.WikiToolBudget(),
                new AgentToolEvidence(RetrievalContextService.Context.empty(), json),
                new AgentService.EvolutionBudget(), 270_000L, progress, externalParentId);

        assertComplete(result, 2, "sub_agents");
        List<Map<String, Object>> snapshot = progress.snapshot();
        List<Map<String, Object>> tools = snapshot.stream()
                .filter(row -> "tool".equals(row.get("kind")) && "write_long_note".equals(row.get("toolName"))).toList();
        assertEquals(1, tools.size(), "整篇与子任务共用一张最终待确认工具卡");
        Map<String, Object> tool = tools.getFirst();
        String toolId = (String) tool.get("id");
        assertEquals(externalParentId, tool.get("parentId"));
        assertEquals("waiting", tool.get("status"));
        assertEquals(41L, tool.get("actionId"));
        List<Map<String, Object>> phases = snapshot.stream().filter(row -> "phase".equals(row.get("kind"))).toList();
        assertEquals(4, phases.size());
        assertEquals(List.of("尝试一次生成全文", "子智能体：第一节", "子智能体：第二节", "主智能体合并正文"),
                phases.stream().map(row -> row.get("title")).toList());
        for (Map<String, Object> phase : phases) {
            assertEquals(toolId, phase.get("parentId"), "生成阶段应归属本次长笔记工具调用");
            assertEquals("completed", phase.get("status"));
            String phaseId = (String) phase.get("id");
            assertTrue(emittedIndex(pushed, phaseId, "running") < emittedIndex(pushed, phaseId, "completed"));
        }
        String wholeId = (String) phases.get(0).get("id");
        String firstChildId = (String) phases.get(1).get("id");
        String secondChildId = (String) phases.get(2).get("id");
        String mergeId = (String) phases.get(3).get("id");
        assertTrue(emittedIndex(pushed, toolId, "running") < emittedIndex(pushed, wholeId, "running"));
        assertTrue(emittedIndex(pushed, wholeId, "completed") < emittedIndex(pushed, firstChildId, "running"));
        assertTrue(emittedIndex(pushed, wholeId, "completed") < emittedIndex(pushed, secondChildId, "running"));
        assertTrue(emittedIndex(pushed, firstChildId, "completed") < emittedIndex(pushed, mergeId, "running"));
        assertTrue(emittedIndex(pushed, secondChildId, "completed") < emittedIndex(pushed, mergeId, "running"));
        assertTrue(emittedIndex(pushed, mergeId, "completed") < emittedIndex(pushed, toolId, "waiting"));
        assertEquals(toolId, pushed.getLast().get("id"));
        assertEquals("waiting", pushed.getLast().get("status"));
        assertEquals(41L, pushed.getLast().get("actionId"));
        verify(sessions).stageAction(eq(SESSION), eq("create_note"), anyString(), anyString());
        verifyModelCalls(3);
    }

    @ParameterizedTest
    @ValueSource(strings = {"    # synthetic comment\n    return value", "    return value + 1"})
    void wholeNoteKeepsLeadingIndentedCodeExactlyAsReturned(String body) throws Exception {
        stageDrafts();
        model().thenReturn(answer(body, false));

        assertComplete(run(OUTLINE, 270_000), 2);

        assertEquals(body, drafts.getFirst().path("content").asText());
        verifyModelCalls(1);
    }

    @Test void mergedChildKeepsItsLeadingCodeIndentation() throws Exception {
        stageDrafts();
        String firstBody = "    # synthetic child comment\n    return value * 2";
        model().thenAnswer(invocation -> {
            String prompt = json.valueToTree(invocation.getArgument(0)).toString();
            if (prompt.contains("负责的小节：第一节")) return answer(firstBody, false);
            if (prompt.contains("负责的小节：第二节")) return answer("第二节完整的合成正文。", false);
            return answer("首轮实际但被截断的正文", true);
        });

        assertComplete(run(OUTLINE, 270_000), 2, "sub_agents");

        assertTrue(drafts.getFirst().path("content").asText().contains("## 第一节\n\n" + firstBody));
        verifyModelCalls(3);
    }

    @Test void aNonStopReasonKeepsActualWholeTextPartialWithoutStartingChildGeneration() throws Exception {
        stageDrafts();
        String body = "模型在非正常结束前实际生成的合成正文，完整性仍需核对。";
        model().thenReturn(new DeepSeekClient.ChatResult(json.createObjectNode().put("content", body),
                "content_filter", 20, 30, 0));

        JsonNode result = run(OUTLINE, 270_000);

        assertPartial(result, List.of("第一节", "第二节"), 0);
        assertEquals("whole", result.path("generationMode").asText());
        assertEquals("long_note_generation_incomplete", result.path("code").asText());
        assertEquals(body, drafts.getFirst().path("content").asText());
        verifyModelCalls(1);
    }

    @Test void failedChildDoesNotDiscardAnotherCompletedChildOrPretendTheWholeNoteIsComplete() throws Exception {
        stageDrafts();
        model().thenAnswer(invocation -> {
            String prompt = json.valueToTree(invocation.getArgument(0)).toString();
            if (!prompt.contains("负责的小节：")) return answer("首轮被截断的实际正文", true);
            if (prompt.contains("负责的小节：第一节")) return answer("已完整生成第一节的合成正文。", false);
            if (prompt.contains("负责的小节：第二节")) throw new HttpTimeoutException("synthetic child timeout");
            fail("未知的子智能体分工");
            return null;
        });

        JsonNode result = run(OUTLINE, 270_000);

        assertPartial(result, List.of("第二节"), 1);
        assertEquals("sub_agents", result.path("generationMode").asText());
        assertEquals(2, result.path("subAgents").asInt());
        assertEquals(2, result.path("sections").asInt());
        assertEquals("long_note_generation_timeout", result.path("code").asText());
        String body = drafts.getFirst().path("content").asText();
        assertTrue(body.contains("已完整生成第一节的合成正文。"));
        assertFalse(body.contains("首轮被截断的实际正文"));
        assertFalse(body.contains("## 第二节"));
        assertFalse(body.contains("生成失败"));
        verifyModelCalls(3);
    }

    @Test void childrenThatAlsoReachTheirLengthLimitCannotBeReportedAsCompletedSections() throws Exception {
        stageDrafts();
        String initialBody = "首轮真正生成但未完成的全文，须保留为未完成草稿。";
        model().thenAnswer(invocation -> {
            String prompt = json.valueToTree(invocation.getArgument(0)).toString();
            return prompt.contains("负责的小节：") ? answer("子节也没有完成的片段", true) : answer(initialBody, true);
        });

        JsonNode result = run(OUTLINE, 270_000);

        assertPartial(result, List.of("第一节", "第二节"), 0);
        assertEquals("sub_agents", result.path("generationMode").asText());
        assertEquals(2, result.path("subAgents").asInt());
        assertEquals("long_note_output_limit", result.path("code").asText());
        assertEquals(initialBody, drafts.getFirst().path("content").asText());
        assertFalse(drafts.getFirst().path("content").asText().contains("子节也没有完成的片段"));
        verifyModelCalls(3);
    }

    @Test void unstructuredOutlineIsPlannedOnlyAfterWholeGenerationReachesItsLengthLimit() throws Exception {
        stageDrafts();
        AtomicInteger mainCalls = new AtomicInteger();
        model().thenAnswer(invocation -> {
            String prompt = json.valueToTree(invocation.getArgument(0)).toString();
            if (prompt.contains("负责的小节：")) {
                assertTrue(prompt.contains("子智能体"));
                if (prompt.contains("负责的小节：基础")) return answer("基础的完整合成正文。", false);
                if (prompt.contains("负责的小节：应用")) return answer("应用的完整合成正文。", false);
                fail("未知的子智能体分工");
            }
            int call = mainCalls.incrementAndGet();
            if (call == 1) return answer("首轮实际生成但未完成的正文", true);
            assertEquals(2, call, "规划后不应再让模型重写合并全文");
            return answer("{\"sections\":[{\"heading\":\"基础\",\"points\":\"合成概念\"},{\"heading\":\"应用\",\"points\":\"合成例子\"}]}", false);
        });

        JsonNode result = run("先说明合成基础，再解释具体应用。", 270_000);

        assertComplete(result, 2, "sub_agents");
        assertEquals(2, result.path("subAgents").asInt());
        String body = drafts.getFirst().path("content").asText();
        assertTrue(body.indexOf("## 基础") < body.indexOf("## 应用"));
        assertTrue(body.contains("基础的完整合成正文。"));
        assertTrue(body.contains("应用的完整合成正文。"));
        assertEquals(2, mainCalls.get());
        verifyModelCalls(4);
    }

    @ParameterizedTest
    @ValueSource(strings = {"not-json", "{}", "{\"sections\":[]}", "{\"sections\":[{\"heading\":\"仅一节\"}]}"})
    void invalidFallbackPlanKeepsTheActualPartialWholeTextWithoutPlaceholderSections(String plan) throws Exception {
        stageDrafts();
        String initialBody = "这是首轮已经生成的真实合成正文，但还没有结束。";
        model().thenReturn(answer(initialBody, true), answer(plan, false));

        JsonNode result = run("根据这个没有结构的合成主题写完整说明。", 270_000);

        assertPartial(result, List.of("全文"), 0);
        assertEquals("long_note_subagents_plan_failed", result.path("code").asText());
        assertEquals(initialBody, drafts.getFirst().path("content").asText());
        verifyModelCalls(2);
    }

    @Test void truncatedFallbackPlanCannotBeUsedAsCompletedChildAssignments() throws Exception {
        stageDrafts();
        String initialBody = "首轮实际生成的未完成合成全文。";
        String plan = "{\"sections\":[{\"heading\":\"基础\"},{\"heading\":\"应用\"}]}";
        model().thenReturn(answer(initialBody, true), answer(plan, true));

        JsonNode result = run("根据合成主题写完整解释。", 270_000);

        assertPartial(result, List.of("全文"), 0);
        assertEquals("long_note_subagents_plan_failed", result.path("code").asText());
        assertEquals(initialBody, drafts.getFirst().path("content").asText());
        verifyModelCalls(2);
    }

    @Test void reachingTheSharedDeadlineAfterTheWholeAttemptDoesNotStartChildCalls() throws Exception {
        stageDrafts();
        String initialBody = "已经生成但被截断的合成全文。";
        model().thenAnswer(invocation -> {
            now.set(265_000);
            return answer(initialBody, true);
        });

        JsonNode result = run(OUTLINE, 270_000);

        assertPartial(result, List.of("第一节", "第二节"), 0);
        assertEquals("round_budget_exhausted", result.path("code").asText());
        assertEquals(initialBody, drafts.getFirst().path("content").asText());
        verifyModelCalls(1);
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "  \n\t", "# 合成长文预算", "## 第一节", "# 合成长文预算\n\n## 第一节\n\n## 第二节"})
    void emptyOrHeadingOnlyModelOutputDoesNotCreatePlaceholderNotes(String body) throws Exception {
        model().thenReturn(answer(body, false));

        JsonNode result = run(OUTLINE, 270_000);

        assertUnstaged(result);
        assertEquals(0, result.path("chars").asInt());
        assertEquals(0, result.path("completedSections").asInt());
        assertEquals(List.of("第一节", "第二节"), unfinished(result));
        verifyModelCalls(1);
    }

    @Test void modelTimeoutDoesNotStageAPlaceholderOrRetry() throws Exception {
        model().thenThrow(new HttpTimeoutException("synthetic timeout"));

        JsonNode result = run(OUTLINE, 270_000);

        assertUnstaged(result);
        assertEquals("long_note_generation_timeout", result.path("code").asText());
        assertEquals(List.of("第一节", "第二节"), unfinished(result));
        verifyModelCalls(1);
    }

    @Test void modelInterruptionIsRestoredAndDoesNotStageOrRetry() throws Exception {
        model().thenThrow(new InterruptedException("synthetic interruption"));
        try {
            JsonNode result = run(OUTLINE, 270_000);

            assertUnstaged(result);
            assertEquals("long_note_interrupted", result.path("code").asText());
            assertTrue(Thread.currentThread().isInterrupted());
            verifyModelCalls(1);
        } finally {
            Thread.interrupted();
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"", " ", "\n"})
    void missingSessionPreventsModelCallsAndApprovalCards(String session) {
        JsonNode result = run(json.valueToTree(Map.of("title", TITLE, "outline", OUTLINE)), session, vo, 270_000);

        assertUnstaged(result);
        verifyNoInteractions(client);
    }

    @Test void nullSessionPreventsModelCallsAndApprovalCards() {
        JsonNode result = run(json.valueToTree(Map.of("title", TITLE, "outline", OUTLINE)), null, vo, 270_000);

        assertUnstaged(result);
        verifyNoInteractions(client);
    }

    @Test void missingResponseContextPreventsModelCallsAndApprovalCards() {
        JsonNode result = run(json.valueToTree(Map.of("title", TITLE, "outline", OUTLINE)), SESSION, null, 270_000);

        assertUnstaged(result);
        verifyNoInteractions(client);
    }

    @ParameterizedTest
    @ValueSource(strings = {"{}", "{\"title\":\"合成标题\"}", "{\"outline\":\"合成大纲\"}",
            "{\"title\":\" \",\"outline\":\"合成大纲\"}", "{\"title\":\"合成标题\",\"outline\":\" \"}"})
    void invalidArgumentsPreventModelCallsAndApprovalCards(String args) throws Exception {
        JsonNode result = run(json.readTree(args), SESSION, vo, 270_000);

        assertUnstaged(result);
        verifyNoInteractions(client);
    }

    @Test void onlyAnExactlyMatchingLeadingH1IsRemovedAndRealMarkdownRemainsIntact() throws Exception {
        stageDrafts();
        String body = """
                ## 第一节

                $$
                p(y\\mid x)=\\frac{e^{s_y}}{\\sum_j e^{s_j}}
                $$

                ```python
                def probability(score, denominator):
                    return score / denominator
                ```
                """.strip();
        model().thenReturn(answer("# " + TITLE + "\n\n" + body, false));

        assertComplete(run(OUTLINE, 270_000), 2);

        assertEquals(body, drafts.getFirst().path("content").asText());
        verifyModelCalls(1);
    }

    @ParameterizedTest
    @ValueSource(strings = {"# 另一个合成标题\n\n实际正文。", "## 合成长文预算\n\n实际正文。"})
    void aDifferentH1OrAMatchingH2IsPreserved(String body) throws Exception {
        stageDrafts();
        model().thenReturn(answer(body, false));

        assertComplete(run(OUTLINE, 270_000), 2);

        assertEquals(body, drafts.getFirst().path("content").asText());
        verifyModelCalls(1);
    }

    @Test void theExistingCreateNotePayloadKeepsCategoryAndDoesNotWriteTheNoteBeforeApproval() throws Exception {
        stageDrafts();
        model().thenReturn(answer("等待确认后才会保存的完整合成正文。", false));
        JsonNode args = json.valueToTree(Map.of("title", TITLE, "outline", OUTLINE, "category_name", "合成分类"));

        assertComplete(run(args, SESSION, vo, 270_000), 2);

        assertEquals(TITLE, drafts.getFirst().path("title").asText());
        assertEquals("合成分类", drafts.getFirst().path("category_name").asText());
        assertEquals("create_note", vo.getPendingActions().getFirst().get("toolName"));
        verify(sessions).stageAction(eq(SESSION), eq("create_note"), anyString(), anyString());
        verifyNoMoreInteractions(sessions);
        verifyNoInteractions(notes);
        verifyModelCalls(1);
    }

    private JsonNode run(String outline, long deadline) {
        return run(json.valueToTree(Map.of("title", TITLE, "outline", outline)), SESSION, vo, deadline);
    }

    private JsonNode run(JsonNode args, String sessionId, AiChatVO response, long deadline) {
        return ReflectionTestUtils.invokeMethod(agent, "runChatTool", "write_long_note", args.toString(),
                vo.getEvents(), sessionId, response, new AiChatRequest(), new AgentService.WikiToolBudget(),
                new AgentToolEvidence(RetrievalContextService.Context.empty(), json),
                new AgentService.EvolutionBudget(), deadline);
    }

    private void stageDrafts() {
        when(sessions.stageAction(eq(SESSION), eq("create_note"), anyString(), anyString())).thenAnswer(invocation -> {
            drafts.add(json.readTree(invocation.getArgument(2, String.class)));
            summaries.add(invocation.getArgument(3));
            AgentPendingAction action = new AgentPendingAction();
            action.setId(41L);
            action.setSessionId(SESSION);
            action.setToolName("create_note");
            action.setArgsJson(invocation.getArgument(2));
            action.setSummary(invocation.getArgument(3));
            action.setStatus("pending");
            return action;
        });
    }

    private OngoingStubbing<DeepSeekClient.ChatResult> model() throws Exception {
        return when(client.chatFull(anyList(), isNull(), eq(TARGET.baseUrl()), eq(TARGET.apiKey()), eq(TARGET.model()),
                anyInt(), anyDouble(), nullable(String.class), nullable(String.class), any(Duration.class)));
    }

    private DeepSeekClient.ChatResult answer(String body, boolean truncated) {
        return new DeepSeekClient.ChatResult(json.createObjectNode().put("content", body),
                truncated ? "length" : "stop", 20, 30, 0);
    }

    private List<String> unfinished(JsonNode result) {
        List<String> headings = new ArrayList<>();
        result.path("unfinishedSections").forEach(heading -> headings.add(heading.asText()));
        return headings;
    }

    private void assertComplete(JsonNode result, int sections) {
        assertComplete(result, sections, "whole");
    }

    private void assertComplete(JsonNode result, int sections, String mode) {
        assertTrue(result.path("ok").asBoolean());
        assertTrue(result.path("complete").asBoolean());
        assertFalse(result.path("partial").asBoolean());
        assertTrue(result.path("staged").asBoolean());
        assertEquals(mode, result.path("generationMode").asText());
        assertEquals(sections, result.path("sections").asInt());
        assertEquals(sections, result.path("completedSections").asInt());
        assertEquals(1, drafts.size());
        assertEquals(1, vo.getPendingActions().size());
        verifyNoInteractions(notes);
    }

    private void assertUnstaged(JsonNode result) {
        assertFalse(result.path("ok").asBoolean());
        assertFalse(result.path("complete").asBoolean());
        assertFalse(result.path("staged").asBoolean());
        assertEquals(List.of(), drafts);
        assertTrue(vo.getPendingActions().isEmpty());
        verifyNoInteractions(sessions, notes);
    }

    private void assertPartial(JsonNode result, List<String> unfinished, int completedSections) {
        assertFalse(result.path("ok").asBoolean());
        assertFalse(result.path("complete").asBoolean());
        assertTrue(result.path("partial").asBoolean());
        assertTrue(result.path("staged").asBoolean());
        assertEquals(unfinished, unfinished(result));
        assertEquals(completedSections, result.path("completedSections").asInt());
        assertEquals(1, drafts.size());
        assertEquals(1, vo.getPendingActions().size());
        assertTrue(summaries.getFirst().contains("未完成草稿"));
        JsonNode generation = drafts.getFirst().path("_generation");
        assertTrue(generation.path("partial").asBoolean());
        assertEquals(completedSections, generation.path("completedSections").asInt());
        assertEquals(unfinished, unfinished(generation));
        verifyNoInteractions(notes);
    }

    private static long headingCount(String body, String heading) {
        return body.lines().filter(line -> line.strip().equals("## " + heading)).count();
    }

    private static int emittedIndex(List<Map<String, Object>> pushed, String id, String status) {
        return IntStream.range(0, pushed.size())
                .filter(index -> id.equals(pushed.get(index).get("id")) && status.equals(pushed.get(index).get("status")))
                .findFirst().orElseThrow(() -> new AssertionError("缺少进度回调：" + id + " / " + status));
    }

    private List<JsonNode> modelMessages() throws Exception {
        ArgumentCaptor<List<?>> captured = ArgumentCaptor.forClass(List.class);
        verify(client).chatFull(captured.capture(), isNull(), eq(TARGET.baseUrl()), eq(TARGET.apiKey()), eq(TARGET.model()),
                anyInt(), anyDouble(), nullable(String.class), nullable(String.class), any(Duration.class));
        List<JsonNode> messages = new ArrayList<>();
        for (Object message : captured.getValue()) messages.add(json.valueToTree(message));
        return messages;
    }

    private static String roleText(List<JsonNode> messages, String role) {
        return String.join("\n", messages.stream().filter(message -> role.equals(message.path("role").asText()))
                .map(message -> message.path("content").asText()).toList());
    }

    private void verifyModelCalls(int count) throws Exception {
        verify(client, times(count)).chatFull(anyList(), isNull(), anyString(), anyString(), anyString(),
                anyInt(), anyDouble(), nullable(String.class), nullable(String.class), any(Duration.class));
    }
}
