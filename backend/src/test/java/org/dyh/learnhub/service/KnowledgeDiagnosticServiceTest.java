package org.dyh.learnhub.service;

import org.dyh.learnhub.ai.DeepSeekClient;
import org.dyh.learnhub.ai.EmbeddingClient;
import org.dyh.learnhub.ai.ModelRouting;
import org.dyh.learnhub.mapper.KbChunkMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class KnowledgeDiagnosticServiceTest {
    KbChunkMapper sources;
    VectorIndexService vectors;
    GraphEvidenceService graph;
    RerankService reranker;
    SettingsService toggles;
    ModelRouting routing;
    DeepSeekClient client;
    WikiRetrievalService wiki;
    KnowledgeBaseSettings settings;
    EmbeddingClient embedder;
    RetrievalContextService contexts;
    KnowledgeRetrievalService retrieval;
    KnowledgeDiagnosticService diagnostics;

    @BeforeEach
    void setup() {
        sources = mock(KbChunkMapper.class); vectors = mock(VectorIndexService.class); graph = mock(GraphEvidenceService.class);
        reranker = mock(RerankService.class); toggles = mock(SettingsService.class); routing = mock(ModelRouting.class);
        client = mock(DeepSeekClient.class); wiki = mock(WikiRetrievalService.class); settings = mock(KnowledgeBaseSettings.class);
        embedder = mock(EmbeddingClient.class); contexts = mock(RetrievalContextService.class);
        when(settings.snapshot()).thenReturn(KnowledgeBaseSettings.validatePatch(KnowledgeBaseSettings.defaults(),
                Map.of("vectorEnabled", false, "queryRewrite", false)));
        when(embedder.snapshot()).thenReturn(EmbeddingClient.Snapshot.disabled("合成测试：未配置"));
        retrieval = new KnowledgeRetrievalService(sources, vectors, graph, reranker, toggles, routing, client, wiki);
        retrieval.setKnowledgeSettings(settings);
        diagnostics = new KnowledgeDiagnosticService(retrieval, settings, vectors, embedder, contexts);
    }

    @Test
    void builtinUsesSharedPipelineAndNeverReadsCurrentCorpusOrWritesIndex() {
        Map<String, Object> out = diagnostics.inspect(request("exact-threadpool", "builtin", "fused", false));
        List<Map<String, Object>> hits = maps(out.get("hits"));
        assertEquals(900001L, hits.getFirst().get("sourceId"));
        assertEquals(List.of("keyword"), hits.getFirst().get("channels"));
        assertTrue(maps(out.get("stages")).stream().anyMatch(s -> "graph".equals(s.get("id")) && "notApplicable".equals(s.get("status"))));
        assertTrue(maps(out.get("stages")).stream().anyMatch(s -> "wiki".equals(s.get("id")) && "notApplicable".equals(s.get("status"))));
        assertEquals(1.0, ((Map<?, ?>) out.get("metrics")).get("recallAtK"));
        verifyNoInteractions(sources, vectors, graph, wiki, routing, client, contexts);
        verify(reranker, times(1)).rerank(anyString(), anyList());
    }

    @Test
    void builtinVectorUsesEphemeralRealEmbeddingAdapterOnly() {
        when(settings.snapshot()).thenReturn(KnowledgeBaseSettings.validatePatch(KnowledgeBaseSettings.defaults(), Map.of("queryRewrite", false)));
        when(embedder.snapshot()).thenReturn(EmbeddingClient.Snapshot.of("synthetic-model", "http://127.0.0.1:9999", "ollama", null));
        when(vectors.searchEphemeral(anyString(), anyList(), any())).thenAnswer(inv -> {
            List<Map<String, Object>> docs = inv.getArgument(1);
            assertEquals(5, docs.size());
            assertTrue(docs.stream().allMatch(row -> ((Number) row.get("source_id")).longValue() >= 900001));
            Map<String, Object> row = docs.getFirst();
            String firstChunk = TextChunker.splitWithHeadings(row.get("content").toString()).getFirst().text();
            return List.of(new VectorIndexService.Hit("note", 900001L, "合成示例", "", firstChunk, .88, 0));
        });
        Map<String, Object> out = diagnostics.inspect(request("semantic-backpressure", "builtin", "vector", false));
        assertEquals(List.of("vector"), maps(out.get("hits")).getFirst().get("channels"));
        verify(vectors, times(1)).searchEphemeral(anyString(), anyList(), any(KnowledgeBaseSettings.Config.class));
        verify(vectors, never()).search(anyString(), anyInt()); verify(vectors, never()).status();
        verifyNoInteractions(sources, graph, wiki, client, routing);
    }

    @Test
    void negativeAndCustomQueriesDoNotInventPerfectRecall() {
        Map<String, Object> out = diagnostics.inspect(request("uncovered-topic", "builtin", "keyword", false));
        Map<?, ?> metrics = (Map<?, ?>) out.get("metrics");
        assertEquals(0, metrics.get("totalExpected"));
        assertNull(metrics.get("hitRate")); assertNull(metrics.get("recallAtK")); assertNull(metrics.get("mrr"));
        Map<String, Object> custom = diagnostics.inspect(new KnowledgeDiagnosticService.Request("exact-threadpool", "页面颜色", "builtin", "keyword", 8, false, false, false));
        assertNull(custom.get("metrics"), "changing a case question invalidates its gold sources");
    }

    @Test
    void currentDiagnosticsReusesOneSearchForContextAndWhitelistsIndexStatus() {
        when(sources.allNotes()).thenReturn(List.of(Map.of("id", 11L, "title", "合成测试笔记", "content", "线程池复用线程。")));
        when(vectors.status()).thenReturn(Map.of("chunks", 1, "configured", false, "baseUrl", "sensitive-endpoint", "apiKey", "must-not-appear"));
        when(contexts.buildFromSearch(anyString(), anyInt(), any(), anyBoolean(), anyBoolean(), eq(true), any()))
                .thenAnswer(inv -> new RetrievalContextService.Context(List.of("合成上下文"), ((KnowledgeRetrievalService.SearchResult) inv.getArgument(2)).passages(), List.of()));
        Map<String, Object> out = diagnostics.inspect(new KnowledgeDiagnosticService.Request(null, "线程池", "current", "keyword", 8, false, false, true));
        assertNull(out.get("metrics"));
        assertFalse(((Map<?, ?>) out.get("indexStatus")).containsKey("apiKey"));
        assertFalse(((Map<?, ?>) out.get("indexStatus")).containsKey("baseUrl"));
        assertEquals("合成上下文", ((Map<?, ?>) out.get("context")).get("text"));
        verify(sources, times(1)).allNotes(); verify(sources, times(1)).allRefs(); verify(sources, times(1)).allFiles();
        verify(contexts, times(1)).buildFromSearch(eq("线程池"), eq(8), any(), eq(false), eq(false), eq(true), any(KnowledgeBaseSettings.Config.class));
        verify(contexts, never()).build(anyString(), anyInt(), anyString(), anyBoolean(), anyBoolean());
        verifyNoInteractions(client, routing, graph, wiki);
    }

    @Test
    void builtinContextNeverRequestsLiveNeighborsOrGlobalGraph() {
        when(contexts.buildFromSearch(anyString(), anyInt(), any(), eq(false), eq(false), eq(false), any()))
                .thenReturn(RetrievalContextService.Context.empty());
        diagnostics.inspect(request("exact-threadpool", "builtin", "keyword", true));
        verify(contexts).buildFromSearch(anyString(), eq(8), any(), eq(false), eq(false), eq(false), any(KnowledgeBaseSettings.Config.class));
        verifyNoInteractions(sources, graph, wiki, vectors, client, routing);
    }

    @Test
    void contextReceivesFullCandidatePoolWhileVisibleHitsAndGoldMetricsUseTopK() {
        when(sources.allNotes()).thenReturn(List.of(
                Map.of("id", 11L, "title", "合成线程池 A", "content", "线程池复用线程。"),
                Map.of("id", 12L, "title", "合成线程池 B", "content", "线程池排队任务。"),
                Map.of("id", 13L, "title", "合成线程池 C", "content", "线程池处理拒绝。")));
        when(vectors.status()).thenReturn(Map.of("configured", false));
        when(contexts.buildFromSearch(anyString(), anyInt(), any(), anyBoolean(), anyBoolean(), anyBoolean(), any()))
                .thenReturn(RetrievalContextService.Context.empty());
        var current = diagnostics.inspect(new KnowledgeDiagnosticService.Request(null, "线程池", "current", "keyword", 1, false, false, true));
        assertEquals(1, maps(current.get("hits")).size());
        var result = org.mockito.ArgumentCaptor.forClass(KnowledgeRetrievalService.SearchResult.class);
        verify(contexts).buildFromSearch(eq("线程池"), eq(1), result.capture(), eq(false), eq(false), eq(true), any(KnowledgeBaseSettings.Config.class));
        assertEquals(3, result.getValue().passages().size(), "context can skip an oversized seed and backfill from the full sorted pool");
        var builtin = diagnostics.inspect(new KnowledgeDiagnosticService.Request("rag-pipeline", null, "builtin", "keyword", 1, false, false, true));
        assertEquals(1, maps(builtin.get("hits")).size());
        var metrics = (Map<?, ?>) builtin.get("metrics");
        assertEquals(2, metrics.get("totalExpected")); assertEquals(1, metrics.get("matchedExpected"));
        assertEquals(.5, metrics.get("recallAtK"), "metrics must not use undisplayed backfill candidates");
        verify(sources, times(1)).allNotes();
    }

    @Test
    void exceptionsExposeStageClassButNeverExceptionText() {
        when(settings.snapshot()).thenReturn(KnowledgeBaseSettings.validatePatch(KnowledgeBaseSettings.defaults(), Map.of("queryRewrite", false)));
        when(embedder.snapshot()).thenReturn(EmbeddingClient.Snapshot.of("synthetic", "http://127.0.0.1:9999", "ollama", null));
        when(vectors.searchEphemeral(anyString(), anyList(), any())).thenThrow(new IllegalStateException("api-key-secret-should-not-leak"));
        Map<String, Object> out = diagnostics.inspect(request("exact-threadpool", "builtin", "fused", false));
        assertFalse(out.toString().contains("api-key-secret"));
        assertTrue(maps(out.get("stages")).stream().anyMatch(s -> "vector".equals(s.get("id")) && "error".equals(s.get("status"))));
        assertFalse(maps(out.get("hits")).isEmpty(), "keyword evidence remains available");
    }

    @Test
    void metricsUseDistinctExpectedSourcesAndFirstRelevantPassageRank() {
        RetrievalHit other = new RetrievalHit("note", 1L, "合成无关", "", "无关", 1, 0, List.of(), List.of());
        RetrievalHit a = new RetrievalHit("file", 900003L, "合成相关", "", "分块", 1, 0, List.of(), List.of());
        RetrievalHit repeated = new RetrievalHit("file", 900003L, "合成相关", "", "向量", 1, 1, List.of(), List.of());
        var result = KnowledgeDiagnosticService.metrics(List.of("file:900003", "quick_ref:900004"), List.of(other, a, repeated));
        assertEquals(1, result.get("matchedExpected")); assertEquals(.5, result.get("recallAtK")); assertEquals(.5, result.get("mrr"));
    }

    private static KnowledgeDiagnosticService.Request request(String id, String dataset, String mode, boolean context) {
        return new KnowledgeDiagnosticService.Request(id, null, dataset, mode, 8, true, true, context);
    }
    @SuppressWarnings("unchecked") private static List<Map<String, Object>> maps(Object value) { return (List<Map<String, Object>>) value; }
}
