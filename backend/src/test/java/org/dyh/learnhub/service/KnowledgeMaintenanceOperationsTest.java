package org.dyh.learnhub.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.dyh.learnhub.ai.DeepSeekClient;
import org.dyh.learnhub.ai.EmbeddingClient;
import org.dyh.learnhub.ai.ModelRouting;
import org.dyh.learnhub.entity.AppSetting;
import org.dyh.learnhub.mapper.AppSettingMapper;
import org.dyh.learnhub.mapper.KnowledgeMaintenanceSourceMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Synthetic source versions and completed jobs; no user data, network, or model calls. */
class KnowledgeMaintenanceOperationsTest {
    private final KnowledgeMaintenanceSourceMapper sources = mock(KnowledgeMaintenanceSourceMapper.class);
    private final AppSettingMapper store = mock(AppSettingMapper.class);
    private final KnowledgeBaseSettings config = mock(KnowledgeBaseSettings.class);
    private final EmbeddingClient embedding = mock(EmbeddingClient.class);
    private final ModelRouting routing = mock(ModelRouting.class);
    private final DeepSeekClient client = mock(DeepSeekClient.class);
    private final VectorIndexService index = mock(VectorIndexService.class);
    private final WikiService wiki = mock(WikiService.class);
    private final EntityCompileService entities = mock(EntityCompileService.class);
    private final KgMaintenanceService concepts = mock(KgMaintenanceService.class);
    private final KgPipelineService pipeline = mock(KgPipelineService.class);
    private final KgService documents = mock(KgService.class);
    private final KgGraphService graph = mock(KgGraphService.class);
    private final KgCommunityService communities = mock(KgCommunityService.class);
    private final KgCommunitySummaryService summaries = mock(KgCommunitySummaryService.class);
    private final LintService lint = mock(LintService.class);
    private KnowledgeMaintenanceOperations operations;

    @BeforeEach void setup() {
        when(sources.sources()).thenReturn(List.of(source("full-body-sha-one")));
        when(config.snapshot()).thenReturn(KnowledgeBaseSettings.defaults());
        when(embedding.snapshot()).thenReturn(EmbeddingClient.Snapshot.disabled("合成：未配置嵌入"));
        when(routing.forTask(anyString())).thenReturn(new ModelRouting.ModelTarget(
                "synthetic", "Synthetic model", "https://example.invalid/v1", "synthetic-key", "synthetic-model", false));
        when(client.maxTokensOf(anyString())).thenReturn(8192);
        when(wiki.autoRefreshEnabled()).thenReturn(true);
        operations = new KnowledgeMaintenanceOperations(sources, store, config, embedding, routing, client,
                new ObjectMapper(), index, wiki, entities, concepts, pipeline, documents, graph, communities, summaries, lint);
    }

    @Test void discoveryUsesActualBodyVersionsAndTagsWithoutCallingAnyModelOrGeneratedArtifact() throws Exception {
        String initial = operations.fingerprint();
        assertEquals(initial, operations.fingerprint());
        when(sources.sources()).thenReturn(List.of(source("full-body-sha-two")));
        String updated = operations.fingerprint();
        assertNotEquals(initial, updated);
        when(sources.noteTags()).thenReturn(List.of(Map.of("noteId", 1, "tagId", 9)));
        assertNotEquals(updated, operations.fingerprint());
        verifyNoInteractions(index, entities, concepts, pipeline, documents, graph, communities, summaries, lint);
        verify(client, never()).chatFull(anyList(), any(), anyString(), any(), anyString(), anyInt(),
                anyDouble(), any(), any(), any());
    }

    @Test void generationAndRetrievalSettingsDoNotCreateAMaintenanceFeedbackLoop() {
        String original = operations.fingerprint();
        Map<String, Object> values = new LinkedHashMap<>(KnowledgeBaseSettings.defaults().values());
        values.put("totalChars", 9000);
        when(config.snapshot()).thenReturn(new KnowledgeBaseSettings.Config(values));
        assertEquals(original, operations.fingerprint(), "改变回答上下文不应重新生成全库");
        values.put("chunkTarget", 900);
        when(config.snapshot()).thenReturn(new KnowledgeBaseSettings.Config(values));
        assertNotEquals(original, operations.fingerprint(), "分块配置应排队更新索引");
    }

    @Test void changedEmbeddingSpaceAwaitsARealSuccessfulRebuild() throws Exception {
        operations.fingerprint();
        when(index.status()).thenReturn(Map.of("configured", true, "embeddingChanged", true),
                Map.of("configured", true, "stale", false));
        when(index.startRebuild()).thenReturn(job("done"));
        assertTrue(operations.execute("index").complete());
        verify(index).startRebuild();
        verify(index, never()).reindexAutomatically();
    }

    @Test void aFailedRebuildCannotBeReportedAsMaintained() {
        operations.fingerprint();
        when(index.status()).thenReturn(Map.of("configured", true, "embeddingChanged", true));
        when(index.startRebuild()).thenReturn(job("failed"));
        assertThrows(IllegalStateException.class, () -> operations.execute("index"));
    }

    @Test void emptyLibraryDoesNotKeepWaitingForAnEmbeddingModel() throws Exception {
        when(sources.sources()).thenReturn(List.of());
        operations.fingerprint();
        when(index.status()).thenReturn(Map.of("configured", false));
        when(documents.maintainSemantic()).thenReturn(Map.of("edges", 0));
        assertTrue(operations.execute("index").complete());
        assertTrue(operations.execute("wiki").complete());
        assertTrue(operations.execute("documents").complete());
        assertTrue(operations.execute("check").complete());
        verifyNoInteractions(entities, lint);
        verify(documents).maintainSemantic();
    }

    @Test void wikiContinuesAnExistingRevisionWithoutRecompilingEveryBatch() throws Exception {
        String fingerprint = operations.fingerprint();
        AppSetting revision = new AppSetting(); revision.setSettingValue(fingerprint);
        when(store.selectById(KnowledgeMaintenanceOperations.ENTITY_REVISION)).thenReturn(revision);
        when(wiki.topics()).thenReturn(List.of(topic(false))).thenReturn(List.of(topic(true)));
        when(wiki.startGenerate("cat-1", null)).thenReturn(job("done"));
        assertTrue(operations.execute("wiki").complete());
        verifyNoInteractions(entities);
        verify(wiki).startGenerate("cat-1", null);
    }

    @Test void failedEntityExtractionNeverAdvancesTheDurableRevision() {
        operations.fingerprint();
        when(entities.startAutomatically()).thenReturn(job("failed"));
        assertThrows(IllegalStateException.class, () -> operations.execute("wiki"));
        verify(store, never()).insert(any(AppSetting.class));
        verify(store, never()).updateById(any(AppSetting.class));
    }

    @Test void theCategoryToggleDoesNotDisableEntityMaintenance() throws Exception {
        when(wiki.autoRefreshEnabled()).thenReturn(false);
        operations.fingerprint();
        when(entities.startAutomatically()).thenReturn(job("done"));
        when(wiki.topics()).thenReturn(List.of(topic(false)));
        assertTrue(operations.execute("wiki").complete());
        verify(entities).startAutomatically();
        verify(wiki, never()).startGenerate(anyString(), any());
    }

    @Test void anEditDuringEntityCompilationKeepsTheNextVersionPending() throws Exception {
        operations.fingerprint();
        when(entities.startAutomatically()).thenAnswer(call -> {
            when(sources.sources()).thenReturn(List.of(source("edited-while-generating")));
            return job("done");
        });
        assertFalse(operations.execute("wiki").complete());
        verify(store, never()).insert(any(AppSetting.class));
    }

    @Test void anEntityWithoutCurrentMaterialIsPreservedWithoutBlockingOtherWikiPages() throws Exception {
        compiledRevision();
        Map<String, Object> orphan = entityTopic("entity-orphan");
        when(wiki.topics()).thenReturn(List.of(orphan, topic(false)), List.of(orphan, topic(true)));
        when(entities.unavailableRegenerationMaterials(List.of("entity-orphan")))
                .thenReturn(Map.of("entity-orphan", "当前没有相关有效正文，原页面已保留"));
        when(wiki.startGenerate("cat-1", null)).thenReturn(job("done"));

        var result = operations.execute("wiki");

        assertTrue(result.complete(), "保留缺素材旧页不应阻塞图谱、社区和自检阶段");
        assertTrue(result.message().contains("保留 1 页"));
        assertTrue(result.message().contains("缺少相关的有效正文片段"));
        assertTrue(result.message().contains("来源变化后重新检查"));
        verify(wiki).startGenerate("cat-1", null);
        verify(wiki, never()).startGenerate("entity-orphan", null);
        verify(entities, never()).startAutomatically();
    }

    @Test void orphanMaterialDecisionsAreCachedWithoutRepeatedLibraryReadsOrModels() throws Exception {
        compiledRevision();
        when(wiki.topics()).thenReturn(List.of(entityTopic("entity-orphan")));
        when(entities.unavailableRegenerationMaterials(List.of("entity-orphan")))
                .thenReturn(Map.of("entity-orphan", "缺少有效正文"));

        assertTrue(operations.execute("wiki").complete());
        assertTrue(operations.execute("wiki").complete());

        verify(entities, times(1)).unavailableRegenerationMaterials(List.of("entity-orphan"));
        verify(entities, never()).startAutomatically();
        verify(wiki, never()).startGenerate(anyString(), any());
    }

    @Test void editedSourcesRecheckAndReactivatePreviouslyUnsupportedEntityPages() throws Exception {
        compiledRevision();
        Map<String, Object> orphan = entityTopic("entity-orphan");
        when(wiki.topics()).thenReturn(List.of(orphan));
        when(entities.unavailableRegenerationMaterials(List.of("entity-orphan")))
                .thenReturn(Map.of("entity-orphan", "缺少有效正文"), Map.of());
        assertTrue(operations.execute("wiki").complete());

        when(sources.sources()).thenReturn(List.of(source("new-related-body-sha")));
        compiledRevision();
        when(wiki.startGenerate("entity-orphan", null)).thenReturn(job("done"));
        when(wiki.topics()).thenReturn(List.of(orphan), List.of());

        assertTrue(operations.execute("wiki").complete());
        verify(entities, times(2)).unavailableRegenerationMaterials(List.of("entity-orphan"));
        verify(wiki).startGenerate("entity-orphan", null);
        verify(entities, never()).startAutomatically();
    }

    @Test void ordinaryPageFailuresDoNotStarveLaterPagesAndEachBatchRemainsBounded() throws Exception {
        compiledRevision();
        List<Map<String, Object>> topics = List.of(categoryTopic("cat-1"), categoryTopic("cat-2"),
                categoryTopic("cat-3"), categoryTopic("cat-4"));
        when(wiki.topics()).thenReturn(topics);
        when(wiki.startGenerate(anyString(), isNull())).thenReturn(job("done"));
        when(wiki.startGenerate("cat-1", null)).thenReturn(job("failed"));

        IllegalStateException first = assertThrows(IllegalStateException.class, () -> operations.execute("wiki"));
        assertTrue(first.getMessage().contains("本轮更新 2 页"));
        assertTrue(first.getMessage().contains("cat-1"));
        verify(wiki).startGenerate("cat-2", null);
        verify(wiki).startGenerate("cat-3", null);
        verify(wiki, never()).startGenerate("cat-4", null);

        assertThrows(IllegalStateException.class, () -> operations.execute("wiki"));
        var order = inOrder(wiki);
        order.verify(wiki).startGenerate("cat-1", null);
        order.verify(wiki).startGenerate("cat-2", null);
        order.verify(wiki).startGenerate("cat-3", null);
        order.verify(wiki).startGenerate("cat-4", null);
        order.verify(wiki).startGenerate("cat-1", null);
        order.verify(wiki).startGenerate("cat-2", null);
        verify(wiki, times(6)).startGenerate(anyString(), isNull());
    }

    @Test void interruptionStopsTheWikiBatchImmediately() {
        compiledRevision();
        when(wiki.topics()).thenReturn(List.of(categoryTopic("cat-1"), categoryTopic("cat-2")));
        when(wiki.startGenerate("cat-1", null)).thenReturn(job("running"));
        Thread.currentThread().interrupt();
        try {
            assertThrows(InterruptedException.class, () -> operations.execute("wiki"));
            verify(wiki, never()).startGenerate("cat-2", null);
        } finally {
            Thread.interrupted();
        }
    }

    @Test void conceptBatchesRemainPendingUntilTheLastSourceIsProcessed() throws Exception {
        operations.fingerprint();
        when(concepts.maintain(3)).thenReturn(Map.of("complete", false, "message", "还有4个来源"));
        assertFalse(operations.execute("graph").complete());
        verify(concepts).maintain(3);
        verifyNoInteractions(graph, communities);
    }

    @Test void aManualGraphBuildIsNotDuplicatedByTheAutomaticRunner() throws Exception {
        operations.fingerprint();
        when(pipeline.hasRunningJobs()).thenReturn(true);
        assertFalse(operations.execute("graph").complete());
        assertFalse(operations.execute("communities").complete());
        verifyNoInteractions(concepts, graph, communities);
    }

    @Test void summaryBudgetIsBoundedAndRemainingWorkContinuesLater() throws Exception {
        operations.fingerprint();
        when(summaries.status()).thenReturn(Map.of("missing", 4, "stale", 0), Map.of("missing", 1, "stale", 0));
        when(summaries.summarize(3)).thenReturn(Map.of("failed", 0));
        assertFalse(operations.execute("summaries").complete());
        verify(summaries).summarize(3);
    }

    @Test void graphEntityVectorsFollowTheConfiguredEmbeddingSpaceAfterWikiAndGraphAreReady() throws Exception {
        when(embedding.snapshot()).thenReturn(EmbeddingClient.Snapshot.of("synthetic-embed", "http://embed.test/v1", "custom", null));
        when(pipeline.refreshEntityEmbeddings()).thenReturn(Map.of("configured", true, "failed", 0));
        operations.fingerprint();
        assertTrue(operations.execute("communities").complete());
        verify(pipeline).relinkWikiPages();
        verify(graph).reason();
        verify(communities).recompute(false);
        verify(pipeline).refreshEntityEmbeddings();
    }

    @Test void failedEntityVectorSynchronizationCannotAdvanceTheCommunityStageWatermark() {
        when(embedding.snapshot()).thenReturn(EmbeddingClient.Snapshot.of("synthetic-embed", "http://embed.test/v1", "custom", null));
        when(pipeline.refreshEntityEmbeddings()).thenReturn(Map.of("configured", true, "failed", 1));
        operations.fingerprint();
        assertThrows(IllegalStateException.class, () -> operations.execute("communities"));
    }

    @Test void semanticLintFailureDoesNotMasqueradeAsASuccessfulHealthCheck() {
        operations.fingerprint();
        when(lint.run()).thenReturn(Map.of("modelCheckSucceeded", false));
        assertThrows(IllegalStateException.class, () -> operations.execute("check"));
    }

    private static Map<String, Object> source(String hash) {
        return Map.of("sourceType", "note", "id", 1, "title", "Synthetic note", "contentHash", hash,
                "contentChars", 1200, "textStatus", "");
    }
    private static Map<String, Object> topic(boolean generated) {
        return Map.of("topicKey", "cat-1", "topicType", "category", "itemCount", 1,
                "generated", generated, "stale", !generated);
    }
    private void compiledRevision() {
        AppSetting revision = new AppSetting(); revision.setSettingValue(operations.fingerprint());
        when(store.selectById(KnowledgeMaintenanceOperations.ENTITY_REVISION)).thenReturn(revision);
    }
    private static Map<String, Object> entityTopic(String key) {
        return Map.of("topicKey", key, "topicType", "entity", "itemCount", 1, "generated", true, "stale", true);
    }
    private static Map<String, Object> categoryTopic(String key) {
        return Map.of("topicKey", key, "topicType", "category", "itemCount", 1, "generated", false, "stale", true);
    }
    private static Map<String, Object> job(String status) {
        return Map.of("jobId", "synthetic-job", "status", status, "error", "synthetic failure", "automatic", true);
    }
}
