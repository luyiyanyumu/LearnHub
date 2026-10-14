package org.dyh.learnhub.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.dyh.learnhub.ai.DeepSeekClient;
import org.dyh.learnhub.ai.EmbeddingClient;
import org.dyh.learnhub.ai.ModelRouting;
import org.dyh.learnhub.mapper.KbChunkMapper;
import org.dyh.learnhub.mapper.KbIndexStateMapper;
import org.dyh.learnhub.service.vector.MilvusVectorStore;
import org.dyh.learnhub.service.vector.MysqlVectorStore;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;
import java.util.List;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class KnowledgeRuntimeParametersTest {
    @Test void groundingActuallyChecksNineThousandCharactersWhenConfiguredBudgetIsTwelveThousand() throws Exception {
        var store = mock(SettingsService.class); var config = mock(KnowledgeBaseSettings.class);
        when(config.snapshot()).thenReturn(KnowledgeBaseSettings.validatePatch(KnowledgeBaseSettings.defaults(),
                Map.of("groundingEnabled", true, "totalChars", 12000)));
        var client = mock(DeepSeekClient.class); var routing = mock(ModelRouting.class); var json = new ObjectMapper();
        when(routing.forTask(ModelRouting.TASK_GROUNDING)).thenReturn(
                new ModelRouting.ModelTarget("fixture", "fixture", "http://fixture.test", "", "fixture", true));
        when(client.chat(anyList(), isNull(), anyString(), anyString(), anyString(), anyInt(), anyDouble(),
                anyString(), isNull(), any(java.time.Duration.class))).thenReturn(
                json.createObjectNode().put("content", "{\"grounded\":true,\"unsupported\":[]}"));
        var service = new GroundingService(client, routing, json, store); service.setKnowledgeSettings(config);
        var result = service.check("合成问题", "合".repeat(9000), "合成回答");
        assertTrue(result.checked()); assertEquals(9000, result.evidenceChars());
    }

    @Test void automaticContextCanInjectTwelvePassagesWithOneCapturedConfig() {
        var retrieval = mock(KnowledgeRetrievalService.class); var graph = mock(GraphRagService.class);
        var cfg = KnowledgeBaseSettings.validatePatch(KnowledgeBaseSettings.defaults(),
                Map.of("maxPassages", 12, "totalChars", 12000, "neighborCount", 0));
        var hits = java.util.stream.IntStream.rangeClosed(1, 12).mapToObj(index ->
                new RetrievalHit("note", (long) index, "合成标题" + index, "", "合".repeat(750), 1,
                        0, List.of("keyword"), List.of())).toList();
        when(retrieval.searchWithTrace("合成问题", 24, "fused", false, false, cfg, null))
                .thenReturn(new KnowledgeRetrievalService.SearchResult(hits, List.of()));
        var service = new RetrievalContextService(retrieval, graph);
        var context = service.buildWithConfig("合成问题", 12, "fused", false, false, cfg);
        assertEquals(12, context.injectedHits().size());
        assertTrue(context.groundingText().length() > 9000);
        assertTrue(context.text().length() <= 12000);
        verify(retrieval).searchWithTrace("合成问题", 24, "fused", false, false, cfg, null);
        verify(retrieval, never()).search(anyString(), anyInt(), anyString(), anyBoolean());
        verify(retrieval, never()).searchWithWiki(anyString(), anyInt(), anyString(), anyBoolean(), anyBoolean());
        verifyNoInteractions(graph);
    }

    @Test void syntheticVectorFilteringUsesRuntimeThresholdsWithoutReadingAnyTables() {
        var store = mock(SettingsService.class);
        var config = mock(KnowledgeBaseSettings.class);
        when(config.snapshot()).thenReturn(KnowledgeBaseSettings.validatePatch(KnowledgeBaseSettings.defaults(),
                Map.of("vectorMinScore", .95, "vectorScoreGap", 1, "embedBatchSize", 2, "embeddingInputChars", 200)));
        var embedder = mock(EmbeddingClient.class);
        var target = EmbeddingClient.Snapshot.of("fixture-model", "http://fixture.test/v1", "custom", null);
        when(embedder.snapshot()).thenReturn(target);
        when(embedder.embedAll(eq(target), anyList(), eq(2), eq(200)))
                .thenReturn(List.of(new float[]{1, 0}, new float[]{1, 0}, new float[]{.8f, .6f}));
        var chunks = mock(KbChunkMapper.class); var states = mock(KbIndexStateMapper.class);
        var mysql = mock(MysqlVectorStore.class); var milvus = mock(MilvusVectorStore.class);
        var service = new VectorIndexService(chunks, states, embedder, store, mysql, milvus,
                mock(PlatformTransactionManager.class));
        service.setKnowledgeSettings(config);
        var hits = service.searchEphemeral("合成查询", List.of(
                Map.of("source_type", "note", "source_id", 1, "title", "合成一", "content", "合成片段一"),
                Map.of("source_type", "note", "source_id", 2, "title", "合成二", "content", "合成片段二")));
        assertEquals(1, hits.size()); assertEquals(1L, hits.getFirst().sourceId());
        verifyNoInteractions(chunks, states, mysql, milvus);
    }

    @Test void suppliedSearchRespectsRuntimeBudgetAndDoesNotReadRealSources() {
        var retrieval = mock(KnowledgeRetrievalService.class); var graph = mock(GraphRagService.class);
        var config = mock(KnowledgeBaseSettings.class);
        when(config.snapshot()).thenReturn(KnowledgeBaseSettings.validatePatch(KnowledgeBaseSettings.defaults(),
                Map.of("maxPassages", 1, "totalChars", 1600, "passageChars", 200, "wikiChars", 0, "globalGraphChars", 0)));
        var service = new RetrievalContextService(retrieval, graph); service.setKnowledgeSettings(config);
        var first = new RetrievalHit("note", 1L, "合成标题", "", "合成".repeat(300), 1, 0, List.of("keyword"), List.of());
        var second = new RetrievalHit("note", 2L, "另一个合成标题", "", "其他原文", .5, 0, List.of("keyword"), List.of());
        var result = service.buildFromSearch("合成内容", 8,
                new KnowledgeRetrievalService.SearchResult(List.of(first, second), List.of()), true, true, false);
        assertEquals(1, result.injectedHits().size());
        assertTrue(result.injectedHits().getFirst().text().length() <= 200);
        assertTrue(result.text().length() <= 1600);
        verifyNoInteractions(retrieval, graph);
    }

    @Test void groundingTracksConfiguredContextAndAnswerBudgets() {
        var store = mock(SettingsService.class); var config = mock(KnowledgeBaseSettings.class);
        when(config.snapshot()).thenReturn(KnowledgeBaseSettings.validatePatch(KnowledgeBaseSettings.defaults(),
                Map.of("groundingEnabled", true, "totalChars", 1600, "wikiChars", 0, "globalGraphChars", 0,
                        "groundingAnswerChars", 200)));
        var client = mock(DeepSeekClient.class); var routing = mock(ModelRouting.class);
        var service = new GroundingService(client, routing, new ObjectMapper(), store); service.setKnowledgeSettings(config);
        assertFalse(service.check("问题", "证".repeat(1601), "答案").checked());
        assertFalse(service.check("问题", "证据", "答".repeat(201)).checked());
        assertEquals(1600, service.status().get("evidenceChars"));
        verifyNoInteractions(client, routing);
    }
}
