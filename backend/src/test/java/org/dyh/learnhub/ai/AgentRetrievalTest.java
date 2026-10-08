package org.dyh.learnhub.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.dyh.learnhub.dto.AiChatRequest;
import org.dyh.learnhub.service.AgentSessionService;
import org.dyh.learnhub.service.GroundingService;
import org.dyh.learnhub.service.KnowledgeRetrievalService;
import org.dyh.learnhub.service.RetrievalContextService;
import org.dyh.learnhub.service.RetrievalHit;
import org.dyh.learnhub.service.SettingsService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class AgentRetrievalTest {
    @Spy ObjectMapper json = new ObjectMapper();
    @Mock KnowledgeRetrievalService retrieval;
    @Mock RetrievalContextService contexts;
    @Mock SettingsService settings;
    @Mock AgentSessionService sessions;
    @Mock DeepSeekClient client;
    @Mock ModelRouting routing;
    @Mock GroundingService grounding;
    @InjectMocks AgentService agent;

    @BeforeEach void mapper() { ReflectionTestUtils.setField(agent, "objectMapper", json); }

    @Test void retrievalEvaluationUsesSharedSearchAndDeduplicatesSourceRefs() {
        when(settings.kgInjectEnabled()).thenReturn(true);
        when(retrieval.search("q", 5, "keyword", true)).thenReturn(List.of(hit(1, 0), hit(1, 1), hit(2, 0)));
        assertEquals(List.of("note:1", "note:2"), agent.retrieveRefs("q", 5, "keyword"));
        verify(retrieval).search("q", 5, "keyword", true);
        verifyNoInteractions(contexts, client);
    }

    @Test void zeroRequestedSourcesDoesNotRetrieveOneAnyway() {
        assertTrue(agent.retrieveRefs("q", 0, "keyword").isEmpty());
        assertTrue(agent.retrieveRefs("q", -1, "keyword").isEmpty());
        verifyNoInteractions(retrieval, contexts, settings, client);
    }

    @Test void answerEvaluationReturnsOnlyContextInjectedRefsAndIncludesGlobalEvidence() {
        var context = new RetrievalContextService.Context(List.of("原文片段", "GraphRAG 全局摘要"),
                List.of(hit(3, 0)), List.of());
        when(contexts.build("整体问题", 5, "fused", true, true)).thenReturn(context);

        var evidence = agent.evidenceFor("整体问题", 5, "fused", true, true);

        assertEquals(List.of("note:3"), evidence.refs());
        assertEquals(context.text(), evidence.text());
        assertTrue(evidence.text().contains("全局摘要"));
        verifyNoInteractions(retrieval, client);
    }

    @Test void answerEvaluationKeepsWikiForGenerationButExcludesItFromGrounding() {
        String guide = "【Wiki 生成导览】没有核验的生成解释。";
        String source = "实际原文。";
        var context = new RetrievalContextService.Context(List.of(guide, source), List.of(hit(3, 0)), List.of());
        when(contexts.build("概念关系", 5, "fused", true, false)).thenReturn(context);

        var evidence = agent.evidenceFor("概念关系", 5, "fused", true, false);

        assertTrue(evidence.text().contains(guide));
        assertEquals(source, evidence.groundingText());
        assertFalse(evidence.groundingText().contains("没有核验"));
        verifyNoInteractions(retrieval, client);
    }

    @Test void knowledgeToolKeepsItsArgumentsAndResponseShapeWhileUsingSharedSearch() throws Exception {
        when(retrieval.search("JVM", 24)).thenReturn(List.of(hit(4, 2)));
        String result = ReflectionTestUtils.invokeMethod(agent, "searchKnowledge", json.readTree("{\"keyword\":\" JVM \"}"));
        JsonNode out = json.readTree(result);
        assertTrue(out.path("ok").asBoolean());
        assertEquals("JVM", out.path("keyword").asText());
        JsonNode item = out.path("items").get(0);
        assertEquals("note", item.path("type").asText());
        assertEquals(4L, item.path("id").asLong());
        assertEquals("原文", item.path("snippet").asText());
        assertEquals("分类", item.path("category").asText());
        assertEquals("note:4:2", item.path("passageKey").asText());
        verify(retrieval).search("JVM", 24);
    }

    @Test void chatInjectsAndReportsTheSameContextUsedByEvaluation() throws Exception {
        var context = new RetrievalContextService.Context(List.of("证据块一", "社区摘要块"), List.of(hit(1, 0)),
                List.of(Map.of("type", "note", "id", 1L, "title", "测试来源", "seq", 0, "channels", List.of("graph"))));
        when(client.isConfigured()).thenReturn(true);
        when(sessions.ensure(null, null, "问题")).thenReturn("session");
        when(settings.effective(SettingsService.KEY_CHAT_PROMPT)).thenReturn("系统提示");
        when(settings.wikiInjectEnabled()).thenReturn(true);
        when(settings.kgInjectEnabled()).thenReturn(true);
        when(contexts.build("问题", 8, "fused", true, true)).thenReturn(context);
        when(routing.forTask(ModelRouting.TASK_CHAT)).thenReturn(
                new ModelRouting.ModelTarget("test", "test", "http://unused", "dummy", "mock", true));
        var response = new DeepSeekClient.ChatResult(json.readTree("{\"content\":\"已回答\"}"), "stop", 1, 1, 0);
        when(client.chatFull(anyList(), anyList(), anyString(), anyString(), anyString(), anyInt(),
                anyDouble(), nullable(String.class), nullable(String.class), any(Duration.class)))
                .thenReturn(response);
        var request = new AiChatRequest();
        request.setMessage("问题");

        var chat = agent.chat(request);
        var evidence = agent.evidenceFor("问题", 8, "fused", true, true);

        assertEquals(context.retrieved(), chat.getRetrieved());
        assertEquals(context.text(), evidence.text());
        ArgumentCaptor<List<?>> messages = ArgumentCaptor.forClass(List.class);
        verify(client).chatFull(messages.capture(), anyList(), anyString(), anyString(), anyString(), anyInt(),
                anyDouble(), nullable(String.class), nullable(String.class), any(Duration.class));
        List<JsonNode> asJson = messages.getValue().stream().map(json::valueToTree).map(JsonNode.class::cast).toList();
        assertTrue(asJson.stream().anyMatch(m -> "证据块一".equals(m.path("content").asText())));
        assertTrue(asJson.stream().anyMatch(m -> "社区摘要块".equals(m.path("content").asText())));
        assertEquals("问题", asJson.getLast().path("content").asText());
        verify(contexts, times(2)).build("问题", 8, "fused", true, true);
    }

    private static RetrievalHit hit(long id, int seq) {
        return new RetrievalHit("note", id, "标题", "分类", "原文", 1, seq, List.of("keyword"), List.of());
    }
}
