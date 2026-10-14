package org.dyh.learnhub.service.rerank;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.dyh.learnhub.ai.DeepSeekClient;
import org.dyh.learnhub.ai.ModelRouting;
import org.dyh.learnhub.service.KnowledgeBaseSettings;
import org.dyh.learnhub.service.RerankService;
import org.dyh.learnhub.service.SettingsService;
import org.junit.jupiter.api.Test;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class RerankOutcomeTest {
    private final List<RerankService.Item> items = List.of(new RerankService.Item("a", "合成一", "原文一"),
            new RerankService.Item("b", "合成二", "原文二"), new RerankService.Item("c", "合成三", "原文三"));
    private final KnowledgeBaseSettings.Config enabled = KnowledgeBaseSettings.validatePatch(KnowledgeBaseSettings.defaults(),
            Map.of("rerankEnabled", true));

    @Test void swallowedLlmFailureIsReportedAsFallbackWithSafeReasonAndOriginalOrder() throws Exception {
        var client = mock(DeepSeekClient.class); var routing = routing(); var store = mock(SettingsService.class);
        var llm = new LlmListwiseReranker(client, routing, new ObjectMapper(), store);
        when(client.chat(anyList(), isNull(), anyString(), anyString(), anyString(), anyInt(), anyDouble(),
                anyString(), isNull(), any(Duration.class))).thenThrow(new IllegalStateException("synthetic-secret-do-not-expose"));
        var cross = mock(CrossEncoderReranker.class);
        var result = new RerankService(llm, cross, store).rerankWithOutcome("合成问题", items, enabled);
        assertEquals("fallback", result.status()); assertEquals("llm", result.backend());
        assertEquals(List.of("a", "b", "c"), result.order());
        assertFalse(result.reason().contains("synthetic-secret"));
        verifyNoInteractions(cross);
    }

    @Test void validPartialOrderIsDegradedAndCompleteOrderIsCompleted() throws Exception {
        var client = mock(DeepSeekClient.class); var store = mock(SettingsService.class); var json = new ObjectMapper();
        var llm = new LlmListwiseReranker(client, routing(), json, store);
        when(client.chat(anyList(), isNull(), anyString(), anyString(), anyString(), anyInt(), anyDouble(),
                anyString(), isNull(), any(Duration.class))).thenReturn(
                json.createObjectNode().put("content", "{\"order\":[2]}"),
                json.createObjectNode().put("content", "{\"order\":[3,2,1]}"));
        var service = new RerankService(llm, mock(CrossEncoderReranker.class), store);
        var partial = service.rerankWithOutcome("合成问题", items, enabled);
        assertEquals("degraded", partial.status()); assertEquals(List.of("b", "a", "c"), partial.order());
        var complete = service.rerankWithOutcome("合成问题", items, enabled);
        assertEquals("completed", complete.status()); assertEquals(List.of("c", "b", "a"), complete.order());
    }

    @Test void crossBackendFailureAndDisabledStageHaveDifferentOutcomes() {
        var cross = mock(CrossEncoderReranker.class); var llm = mock(LlmListwiseReranker.class);
        when(cross.available()).thenReturn(true); when(cross.name()).thenReturn("cross");
        when(cross.rerankDetailed(anyString(), anyList(), any())).thenThrow(new IllegalStateException("synthetic failure"));
        when(llm.available()).thenReturn(true); when(llm.name()).thenReturn("llm");
        when(llm.rerankDetailed(anyString(), anyList(), any()))
                .thenReturn(new Reranker.Result(List.of("c", "b", "a"), "completed", ""));
        var service = new RerankService(llm, cross, mock(SettingsService.class));
        var cfg = KnowledgeBaseSettings.validatePatch(enabled, Map.of("rerankBackend", "cross"));
        var fallback = service.rerankWithOutcome("合成问题", items, cfg);
        assertEquals("degraded", fallback.status()); assertEquals("llm", fallback.backend());
        assertEquals(List.of("c", "b", "a"), fallback.order());
        clearInvocations(llm, cross);
        assertEquals("skipped", service.rerankWithOutcome("合成问题", items, KnowledgeBaseSettings.defaults()).status());
        verifyNoInteractions(llm, cross);
    }

    private ModelRouting routing() {
        var routing = mock(ModelRouting.class);
        when(routing.forTask(ModelRouting.TASK_RERANK)).thenReturn(
                new ModelRouting.ModelTarget("fixture", "fixture", "http://fixture.test", "", "fixture-model", true));
        return routing;
    }
}
