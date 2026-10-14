package org.dyh.learnhub.ai;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.dyh.learnhub.dto.AiChatRequest;
import org.dyh.learnhub.service.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.*;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class AgentKnowledgeParametersTest {
    @Spy ObjectMapper json = new ObjectMapper();
    @Mock KnowledgeRetrievalService retrieval;
    @Mock RetrievalContextService contexts;
    @Mock SettingsService settings;
    @Mock AgentSessionService sessions;
    @Mock DeepSeekClient client;
    @Mock ModelRouting routing;
    @Mock GroundingService grounding;
    @Mock KnowledgeBaseSettings knowledge;
    @InjectMocks AgentService agent;

    @Test void automaticChatUsesCapturedPassageLimitAndLargerActualEvidenceBudget() throws Exception {
        ReflectionTestUtils.setField(agent, "objectMapper", json);
        agent.setKnowledgeSettings(knowledge);
        var cfg = KnowledgeBaseSettings.validatePatch(KnowledgeBaseSettings.defaults(), Map.of(
                "maxPassages", 12, "totalChars", 12000, "groundingEnabled", true, "wikiInject", false, "kgInject", false));
        when(knowledge.snapshot()).thenReturn(cfg);
        var hits = java.util.stream.IntStream.rangeClosed(1, 12).mapToObj(index ->
                new RetrievalHit("note", (long) index, "合成标题", "", "合成正文", 1, 0, List.of("keyword"), List.of())).toList();
        String actualSource = "合".repeat(9000);
        var context = new RetrievalContextService.Context(List.of(actualSource), hits,
                hits.stream().map(hit -> Map.<String, Object>of("type", "note", "id", hit.sourceId())).toList());
        when(contexts.buildWithConfig("合成问题", 12, "fused", false, false, cfg)).thenReturn(context);
        when(client.isConfigured()).thenReturn(true);
        when(sessions.ensure(null, null, "合成问题")).thenReturn("synthetic-session");
        when(settings.effective(SettingsService.KEY_CHAT_PROMPT)).thenReturn("合成系统提示");
        when(routing.forTask(ModelRouting.TASK_CHAT)).thenReturn(
                new ModelRouting.ModelTarget("fixture", "fixture", "http://fixture.test", "", "fixture", true));
        var response = new DeepSeekClient.ChatResult(json.readTree("{\"content\":\"合成回答\"}"), "stop", 1, 1, 0);
        when(client.chatFull(anyList(), anyList(), anyString(), anyString(), anyString(), anyInt(), anyDouble(),
                nullable(String.class), nullable(String.class), any(Duration.class))).thenReturn(response);
        when(grounding.enabled()).thenReturn(true);
        when(grounding.check("合成问题", actualSource, "合成回答"))
                .thenReturn(new GroundingService.Result(true, true, List.of(), "", 9000));
        var request = new AiChatRequest(); request.setMessage("合成问题");
        var reply = agent.chat(request);
        assertEquals(12, reply.getRetrieved().size());
        assertEquals(true, reply.getGrounding().get("checked"));
        assertEquals(9000, reply.getGrounding().get("evidenceChars"));
        verify(knowledge, times(1)).snapshot();
        verify(contexts).buildWithConfig("合成问题", 12, "fused", false, false, cfg);
        verify(contexts, never()).build(anyString(), anyInt(), anyString(), anyBoolean(), anyBoolean());
        verify(grounding).check("合成问题", actualSource, "合成回答");
    }
}
