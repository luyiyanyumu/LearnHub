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
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Synthetic time and model responses only: no sleeping, database access, or network calls. */
@ExtendWith(MockitoExtension.class)
class AgentLongNoteBudgetTest {
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
        when(routing.forTask(ModelRouting.TASK_CHAT)).thenReturn(TARGET);
    }

    @Test void expiredChatDeadlinePreventsGenerationAndEmptyApprovalCards() throws Exception {
        JsonNode result = run(OUTLINE, -1);

        assertFalse(result.path("ok").asBoolean());
        assertFalse(result.path("staged").asBoolean());
        assertEquals("round_budget_exhausted", result.path("code").asText());
        assertEquals(List.of("第一节", "第二节"), unfinished(result));
        verifyNoInteractions(client, sessions, notes);
    }

    @Test void allSectionsShare270SecondsAndReserveFiveSecondsForTheApprovalCard() throws Exception {
        stageDrafts();
        List<Duration> timeouts = new ArrayList<>();
        model().thenAnswer(invocation -> {
            timeouts.add(invocation.getArgument(9));
            now.set(timeouts.size() == 1 ? 180_000 : 265_000);
            return answer("合成正文 " + timeouts.size(), false);
        });

        JsonNode result = run(OUTLINE, 270_000);

        assertEquals(List.of(Duration.ofSeconds(180), Duration.ofSeconds(85)), timeouts);
        assertTrue(result.path("ok").asBoolean());
        assertFalse(result.path("partial").asBoolean());
        assertTrue(result.path("staged").asBoolean());
        assertEquals(2, result.path("completedSections").asInt());
        assertEquals(1, drafts.size());
        assertTrue(drafts.getFirst().path("content").asText().contains("## 第二节"));
        verifyNoInteractions(notes);
    }

    @Test void deadlineAfterOneSectionPreservesOnlyTheActualBodyAsAnExplicitPartialDraft() throws Exception {
        stageDrafts();
        model().thenAnswer(invocation -> {
            now.set(265_000);
            return answer("第一节的真实合成正文。", false);
        });

        JsonNode result = run(OUTLINE, 270_000);

        assertPartial(result, List.of("第二节"));
        assertEquals(1, result.path("completedSections").asInt());
        assertEquals("round_budget_exhausted", result.path("code").asText());
        assertEquals("## 第一节\n\n第一节的真实合成正文。\n", drafts.getFirst().path("content").asText());
        assertTrue(drafts.getFirst().path("_generation").path("partial").asBoolean());
        verifyModelCalls(1);
    }

    @Test void exhaustedBudgetDoesNotRetryATruncatedSectionOrDiscardItsExistingBody() throws Exception {
        stageDrafts();
        model().thenAnswer(invocation -> {
            now.set(265_000);
            return answer("已生成但还没有写完的合成正文", true);
        });

        JsonNode result = run(OUTLINE, 270_000);

        assertPartial(result, List.of("第一节", "第二节"));
        assertEquals(0, result.path("completedSections").asInt());
        assertEquals(1, result.path("truncatedSections").asInt());
        assertTrue(drafts.getFirst().path("content").asText().contains("已生成但还没有写完的合成正文"));
        verifyModelCalls(1);
    }

    @Test void truncatedSectionRetryReceivesOnlyRemainingTime() throws Exception {
        stageDrafts();
        List<Duration> timeouts = new ArrayList<>();
        model().thenAnswer(invocation -> {
            timeouts.add(invocation.getArgument(9));
            now.set(timeouts.size() == 1 ? 100_000 : 265_000);
            return answer(timeouts.size() == 1 ? "未完成版本" : "精炼后的完整正文。", timeouts.size() == 1);
        });

        JsonNode result = run(OUTLINE, 270_000);

        assertEquals(List.of(Duration.ofSeconds(180), Duration.ofSeconds(165)), timeouts);
        assertPartial(result, List.of("第二节"));
        assertEquals(1, result.path("completedSections").asInt());
        assertTrue(drafts.getFirst().path("content").asText().contains("精炼后的完整正文。"));
        assertFalse(drafts.getFirst().path("content").asText().contains("未完成版本"));
    }

    @Test void outlineExpansionConsumesTheSameGenerationBudget() throws Exception {
        stageDrafts();
        List<Duration> timeouts = new ArrayList<>();
        model().thenAnswer(invocation -> {
            timeouts.add(invocation.getArgument(9));
            now.addAndGet(timeouts.size() == 1 ? 20_000 : 10_000);
            return answer(timeouts.size() == 1
                    ? "{\"sections\":[{\"heading\":\"第一节\"},{\"heading\":\"第二节\"}]}"
                    : "合成章节正文。", false);
        });

        JsonNode result = run("介绍基础知识，再说明具体合成示例", 60_000);

        assertEquals(List.of(Duration.ofSeconds(55), Duration.ofSeconds(35), Duration.ofSeconds(25)), timeouts);
        assertTrue(result.path("ok").asBoolean());
        assertEquals(2, result.path("completedSections").asInt());
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "## 第一节"})
    void emptyOrHeadingOnlyModelOutputDoesNotCreatePlaceholderNotes(String body) throws Exception {
        model().thenReturn(answer(body, false));

        JsonNode result = run(OUTLINE, 270_000);

        assertFalse(result.path("ok").asBoolean());
        assertFalse(result.path("staged").asBoolean());
        assertEquals(0, result.path("chars").asInt());
        assertEquals(List.of("第一节", "第二节"), unfinished(result));
        verifyModelCalls(1);
        verifyNoInteractions(sessions, notes);
    }

    @Test void modelTimeoutStopsLaterSectionsAndRetainsCompletedSections() throws Exception {
        stageDrafts();
        model().thenReturn(answer("合成的第一节正文。", false)).thenThrow(new HttpTimeoutException("synthetic timeout"));

        JsonNode result = run(OUTLINE + "\n## 第三节", 270_000);

        assertPartial(result, List.of("第二节", "第三节"));
        assertEquals("long_note_generation_timeout", result.path("code").asText());
        assertFalse(drafts.getFirst().path("content").asText().contains("生成失败"));
        verifyModelCalls(2);
    }

    private JsonNode run(String outline, long deadline) {
        return ReflectionTestUtils.invokeMethod(agent, "runChatTool", "write_long_note",
                json.valueToTree(Map.of("title", "合成长文预算", "outline", outline)).toString(),
                vo.getEvents(), SESSION, vo, new AiChatRequest(), new AgentService.WikiToolBudget(),
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

    private void assertPartial(JsonNode result, List<String> unfinished) {
        assertFalse(result.path("ok").asBoolean());
        assertFalse(result.path("complete").asBoolean());
        assertTrue(result.path("partial").asBoolean());
        assertTrue(result.path("staged").asBoolean());
        assertEquals(unfinished, unfinished(result));
        assertEquals(1, drafts.size());
        assertEquals(1, vo.getPendingActions().size());
        assertTrue(summaries.getFirst().contains("未完成草稿"));
        verifyNoInteractions(notes);
    }

    private void verifyModelCalls(int count) throws Exception {
        verify(client, times(count)).chatFull(anyList(), isNull(), anyString(), anyString(), anyString(),
                anyInt(), anyDouble(), nullable(String.class), nullable(String.class), any(Duration.class));
    }
}
