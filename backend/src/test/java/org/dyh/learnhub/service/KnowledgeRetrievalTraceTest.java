package org.dyh.learnhub.service;

import org.dyh.learnhub.ai.DeepSeekClient;
import org.dyh.learnhub.ai.ModelRouting;
import org.dyh.learnhub.mapper.KbChunkMapper;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class KnowledgeRetrievalTraceTest {
    @Test
    void providerFallbackIsDegradedInsteadOfClaimingModelSuccess() {
        var sources = mock(KbChunkMapper.class); var vectors = mock(VectorIndexService.class);
        var graph = mock(GraphEvidenceService.class); var reranker = mock(RerankService.class);
        var settings = mock(SettingsService.class); var routing = mock(ModelRouting.class);
        var client = mock(DeepSeekClient.class); var wiki = mock(WikiRetrievalService.class);
        var service = new KnowledgeRetrievalService(sources, vectors, graph, reranker, settings, routing, client, wiki);
        when(sources.allNotes()).thenReturn(List.of(Map.of("id", 1L, "title", "合成线程池", "content", "线程池复用线程。")));
        when(reranker.rerankWithOutcome(anyString(), anyList(), any())).thenReturn(
                new RerankService.Outcome(List.of("note:1:0"), "llm", "fallback", "重排服务不可用，保持原顺序"));
        var trace = new KnowledgeRetrievalService.Trace();
        var result = service.searchWithTrace("线程池", 8, "fused", false, false, KnowledgeBaseSettings.defaults(), trace);
        assertEquals(1, result.passages().size());
        assertEquals("degraded", trace.stages().stream().filter(stage -> "rerank".equals(stage.get("id"))).findFirst().orElseThrow().get("status"));
        verify(reranker, never()).rerank(anyString(), anyList());
        verifyNoInteractions(vectors, graph, wiki, routing, client);
    }

    @Test
    void passiveTraceDoesNotChangeResultsAndRecordsRealOrderCounts() {
        var sources = mock(KbChunkMapper.class); var vectors = mock(VectorIndexService.class);
        var graph = mock(GraphEvidenceService.class); var reranker = mock(RerankService.class);
        var settings = mock(SettingsService.class); var routing = mock(ModelRouting.class);
        var client = mock(DeepSeekClient.class); var wiki = mock(WikiRetrievalService.class);
        var service = new KnowledgeRetrievalService(sources, vectors, graph, reranker, settings, routing, client, wiki);
        when(sources.allNotes()).thenReturn(List.of(Map.of("id", 1L, "title", "合成线程池", "content", "线程池复用工作线程。"),
                Map.of("id", 2L, "title", "合成队列", "content", "线程池使用队列保存任务。")));
        when(reranker.rerank(anyString(), anyList())).thenReturn(List.of("note:2:0", "note:1:0"));
        List<RetrievalHit> normal = service.searchWithWiki("线程池", 8, "fused", false, false).passages();
        var trace = new KnowledgeRetrievalService.Trace();
        var observed = service.searchWithTrace("线程池", 8, "fused", false, false, KnowledgeBaseSettings.defaults(), trace);
        assertEquals(normal, observed.passages());
        assertEquals(2, trace.channels().get("keyword").size());
        assertEquals(List.of("note:1:0", "note:2:0"), trace.beforeRerank().stream().map(RetrievalHit::key).toList());
        assertEquals(List.of("note:2:0", "note:1:0"), trace.afterRerank().stream().map(RetrievalHit::key).toList());
        assertEquals("topK", trace.stages().getLast().get("id"));
        assertTrue(trace.stages().stream().allMatch(stage -> ((Number) stage.get("elapsedMs")).longValue() >= 0));
        verifyNoInteractions(client, routing, vectors, graph, wiki);
    }

    @Test
    void operationSnapshotControlsRrfAndCandidateBudgetWithoutChangingDefaults() {
        var sources = mock(KbChunkMapper.class); var vectors = mock(VectorIndexService.class);
        var graph = mock(GraphEvidenceService.class); var reranker = mock(RerankService.class);
        var settings = mock(SettingsService.class); var routing = mock(ModelRouting.class);
        var client = mock(DeepSeekClient.class); var wiki = mock(WikiRetrievalService.class);
        var service = new KnowledgeRetrievalService(sources, vectors, graph, reranker, settings, routing, client, wiki);
        var cfg = KnowledgeBaseSettings.validatePatch(KnowledgeBaseSettings.defaults(), Map.of("rrfK", 10, "candidates", 8, "fusedWindow", 8));
        List<Map<String, Object>> docs = List.of(Map.of("source_type", "note", "source_id", 900001L, "title", "合成线程池", "content", "线程池复用线程。"));
        var first = service.searchSynthetic("线程池", 8, "fused", docs, cfg, new KnowledgeRetrievalService.Trace());
        var second = service.searchSynthetic("线程池", 8, "fused", docs, KnowledgeBaseSettings.defaults(), new KnowledgeRetrievalService.Trace());
        assertEquals(1.0 / 11, first.passages().getFirst().score(), 1e-12);
        assertEquals(1.0 / 61, second.passages().getFirst().score(), 1e-12);
        verifyNoInteractions(sources, vectors, graph, wiki, client, routing);
    }
}
