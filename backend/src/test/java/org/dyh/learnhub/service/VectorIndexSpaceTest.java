package org.dyh.learnhub.service;

import org.dyh.learnhub.ai.EmbeddingClient;
import org.dyh.learnhub.entity.KbChunk;
import org.dyh.learnhub.entity.KbIndexState;
import org.dyh.learnhub.mapper.KbChunkMapper;
import org.dyh.learnhub.mapper.KbIndexStateMapper;
import org.dyh.learnhub.service.vector.MilvusVectorStore;
import org.dyh.learnhub.service.vector.MysqlVectorStore;
import org.dyh.learnhub.service.vector.VectorStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;

import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class VectorIndexSpaceTest {
    private final KbChunkMapper chunks = mock(KbChunkMapper.class);
    private final KbIndexStateMapper states = mock(KbIndexStateMapper.class);
    private final EmbeddingClient embedder = mock(EmbeddingClient.class);
    private final SettingsService settings = mock(SettingsService.class);
    private final MysqlVectorStore mysql = mock(MysqlVectorStore.class);
    private final MilvusVectorStore milvus = mock(MilvusVectorStore.class);
    private final PlatformTransactionManager transactions = mock(PlatformTransactionManager.class);
    private final VectorIndexService service = new VectorIndexService(chunks, states, embedder, settings, mysql, milvus, transactions);
    private final EmbeddingClient.Snapshot a = EmbeddingClient.Snapshot.of("same-model", "http://a.test/v1", "custom", null);
    private final EmbeddingClient.Snapshot b = EmbeddingClient.Snapshot.of("same-model", "http://b.test/v1", "custom", null);

    @BeforeEach void defaults() {
        when(embedder.snapshot()).thenReturn(a);
        when(settings.effective(anyString())).thenReturn("");
        when(settings.vectorEnabled()).thenReturn(true);
        when(mysql.name()).thenReturn("mysql"); when(milvus.name()).thenReturn("milvus");
        when(milvus.effectiveUri()).thenReturn("http://milvus.test");
        when(milvus.lastError()).thenReturn("");
        when(chunks.allNotes()).thenReturn(List.of(source(1)));
        when(chunks.allRefs()).thenReturn(List.of()); when(chunks.allFiles()).thenReturn(List.of());
        when(chunks.loadAll()).thenReturn(List.of()); when(states.selectList(null)).thenReturn(List.of());
        when(transactions.getTransaction(any())).thenAnswer(inv -> new SimpleTransactionStatus());
        when(embedder.embedAll(eq(a), anyList())).thenAnswer(inv -> ((List<?>) inv.getArgument(1)).stream()
                .map(text -> new float[]{1, 0}).toList());
        AtomicLong ids = new AtomicLong(100);
        when(chunks.insert(any(KbChunk.class))).thenAnswer(inv -> {
            ((KbChunk) inv.getArgument(0)).setId(ids.incrementAndGet()); return 1;
        });
    }

    private Map<String, Object> source(long id) {
        return Map.of("id", id, "title", "标题", "content", "正文说明，保留来源内容。", "category", "分类");
    }

    private KbIndexState state(String space) {
        KbIndexState state = new KbIndexState(); state.setId("note:1"); state.setSourceType("note"); state.setSourceId(1L);
        state.setEmbeddingSpace(space); state.setChunks(1);
        state.setContentHash(KgService.sha256(space + "\u0000" + true + "\u0000标题\u0000正文说明，保留来源内容。"));
        return state;
    }

    private KbChunk storedRow(long id) {
        KbChunk row = new KbChunk(); row.setId(id); row.setSourceType("note"); row.setSourceId(id);
        row.setSeq(0); row.setDim(2); row.setVec(EmbeddingClient.toBytes(new float[]{1, 0}));
        row.setModel(a.model()); row.setEmbeddingSpace(a.spaceFingerprint()); return row;
    }

    private void syncAnn() {
        when(settings.effective(VectorIndexService.KEY_VECTOR_BACKEND)).thenReturn("milvus");
        when(milvus.healthy()).thenReturn(true);
        when(chunks.loadAll()).thenReturn(List.of(storedRow(1)));
        service.syncToMilvus();
        assertEquals(true, service.vectorBackendStatus().get("annReady"));
    }

    private VectorIndexService.Job rebuild() throws Exception {
        VectorIndexService.Job job = new VectorIndexService.Job("test");
        Method rebuild = VectorIndexService.class.getDeclaredMethod("rebuild", VectorIndexService.Job.class);
        rebuild.setAccessible(true); rebuild.invoke(service, job); return job;
    }

    @Test void unconfiguredRoutesNeverReadSourcesDeleteOrCallEmbedding() throws Exception {
        when(embedder.snapshot()).thenReturn(EmbeddingClient.Snapshot.disabled("未配置"));
        assertEquals(false, service.reindexChanged().get("configured"));
        assertTrue(service.search("问题", 6).isEmpty());
        assertEquals("failed", rebuild().status);
        verify(chunks, never()).allNotes();
        verify(chunks, never()).delete(any()); verify(chunks, never()).deleteBySource(anyString(), anyLong());
        verify(embedder, never()).embedAll(any(EmbeddingClient.Snapshot.class), anyList());
        verifyNoInteractions(transactions);
    }

    @Test void failedEmbeddingPreservesOldRowsInIncrementalAndFullRebuild() throws Exception {
        when(embedder.embedAll(eq(a), anyList())).thenThrow(new IllegalStateException("model unavailable"));
        assertThrows(IllegalStateException.class, service::reindexChanged);
        assertEquals("failed", rebuild().status);
        verify(chunks, never()).delete(any()); verify(chunks, never()).deleteBySource(anyString(), anyLong());
        verify(states, never()).delete(any()); verifyNoInteractions(transactions);
    }

    @Test void spaceSwitchDuringEmbeddingStopsBeforeAnyReplacement() throws Exception {
        when(embedder.embedAll(eq(a), anyList())).thenAnswer(inv -> {
            when(embedder.snapshot()).thenReturn(b); return List.of(new float[]{1, 0});
        });
        assertEquals("failed", rebuild().status);
        verify(chunks, never()).delete(any()); verify(chunks, never()).deleteBySource(anyString(), anyLong());
        verifyNoInteractions(transactions);
    }

    @Test void invalidVectorsStopBeforeAnyReplacement() throws Exception {
        when(embedder.embedAll(eq(a), anyList())).thenReturn(List.of(new float[]{Float.NaN, 1}));
        assertEquals("failed", rebuild().status);
        verify(chunks, never()).delete(any()); verifyNoInteractions(transactions);
    }

    @Test void dimensionMismatchAcrossSourcesStopsBeforeReplacement() throws Exception {
        when(chunks.allNotes()).thenReturn(List.of(source(1), source(2)));
        when(embedder.embedAll(eq(a), anyList())).thenReturn(List.of(new float[]{1, 0}), List.of(new float[]{1, 0, 0}));
        assertEquals("failed", rebuild().status);
        verify(chunks, never()).delete(any()); verifyNoInteractions(transactions);
    }

    @Test void unchangedContentAtAnotherEndpointIsReindexedAndTagged() {
        when(states.selectList(null)).thenReturn(List.of(state(b.spaceFingerprint())));
        Map<String, Object> result = service.reindexChanged();
        assertEquals(1, result.get("reindexed"));
        ArgumentCaptor<KbChunk> row = ArgumentCaptor.forClass(KbChunk.class);
        verify(chunks).insert(row.capture());
        assertEquals(a.model(), row.getValue().getModel()); assertEquals(a.spaceFingerprint(), row.getValue().getEmbeddingSpace());
        verify(transactions).commit(any());
    }

    @Test void unchangedContentInSameSpaceSkipsEmbeddingAndDatabaseMutation() {
        when(states.selectList(null)).thenReturn(List.of(state(a.spaceFingerprint())));
        assertEquals(0, service.reindexChanged().get("reindexed"));
        verify(embedder, never()).embedAll(any(EmbeddingClient.Snapshot.class), anyList());
        verify(chunks, never()).deleteBySource(anyString(), anyLong()); verifyNoInteractions(transactions);
    }

    @Test void databaseFailureRollsBackReplacementAndDoesNotPublishAnn() throws Exception {
        when(chunks.insert(any(KbChunk.class))).thenThrow(new IllegalStateException("database write failed"));
        assertEquals("failed", rebuild().status);
        verify(transactions).rollback(any()); verify(transactions, never()).commit(any());
        verify(milvus, never()).replaceSource(anyString(), anyString(), anyLong(), anyList());
    }

    @Test void statusMarksLegacyVectorsStaleWithoutProbingModel() {
        KbChunk old = new KbChunk(); old.setId(1L); old.setSourceType("note"); old.setSourceId(1L); old.setModel(a.model());
        old.setCharLen(8); when(chunks.loadAll()).thenReturn(List.of(old));
        when(states.selectList(null)).thenReturn(List.of(state(null)));
        Map<String, Object> status = service.status();
        assertEquals(true, status.get("configured")); assertEquals(true, status.get("stale"));
        assertEquals(true, status.get("embeddingChanged")); assertEquals(1L, status.get("legacyChunks"));
        assertEquals(0L, status.get("compatibleChunks"));
        verify(embedder, never()).embed(any(EmbeddingClient.Snapshot.class), anyString());
        verify(embedder, never()).embedAll(any(EmbeddingClient.Snapshot.class), anyList());
    }

    @Test void searchCapturesOneSpaceAndFallsBackFromEmptyAnnUsingThatSpace() {
        syncAnn();
        when(milvus.healthy()).thenReturn(true); when(milvus.hasVectors(a.spaceFingerprint())).thenReturn(true);
        when(mysql.hasVectors(a.spaceFingerprint())).thenReturn(true);
        when(embedder.embed(a, "问题")).thenAnswer(inv -> {
            when(embedder.snapshot()).thenReturn(b); return new float[]{1, 0};
        });
        when(milvus.search(eq(a.spaceFingerprint()), any(float[].class), anyInt())).thenReturn(List.of());
        when(mysql.search(eq(a.spaceFingerprint()), any(float[].class), anyInt())).thenReturn(List.of(
                new VectorStore.VecHit(1, "note", 1L, 0, "标题", "", "正文", .9)));
        assertEquals(1, service.search("问题", 6).size());
        verify(mysql).search(eq(a.spaceFingerprint()), any(float[].class), anyInt());
        verify(mysql, never()).search(eq(b.spaceFingerprint()), any(float[].class), anyInt());
    }

    @Test void automaticIncrementalSkipsChangedAndMixedSpacesWithoutEmbedding() {
        when(states.selectList(null)).thenReturn(List.of(state(a.spaceFingerprint()), state(b.spaceFingerprint())));
        assertEquals(true, service.reindexAutomatically().get("skipped"));
        verify(embedder, never()).embedAll(any(EmbeddingClient.Snapshot.class), anyList());
        verify(chunks, never()).deleteBySource(anyString(), anyLong()); verifyNoInteractions(transactions);
    }

    @Test void automaticIncrementalSkipsLegacyChunksEvenWithoutAnIndexState() {
        KbChunk legacy = new KbChunk(); legacy.setId(1L);
        when(chunks.loadAll()).thenReturn(List.of(legacy));
        assertEquals(true, service.reindexAutomatically().get("skipped"));
        verify(embedder, never()).embedAll(any(EmbeddingClient.Snapshot.class), anyList());
        verify(chunks, never()).allNotes(); verifyNoInteractions(transactions);
    }

    @Test void newEmptyIndexCanStillIndexChangedSourcesAutomatically() {
        Map<String, Object> result = service.reindexAutomatically();
        assertEquals(1, result.get("reindexed")); assertNotEquals(true, result.get("skipped"));
        verify(transactions).commit(any());
    }

    @Test void startupUsesMysqlUntilACompleteAnnSync() {
        when(settings.effective(VectorIndexService.KEY_VECTOR_BACKEND)).thenReturn("milvus");
        when(milvus.healthy()).thenReturn(true);
        Map<String, Object> status = service.vectorBackendStatus();
        assertEquals(false, status.get("annReady")); assertEquals("mysql", status.get("active"));
        assertTrue(String.valueOf(status.get("hint")).contains("完整同步"));
        syncAnn();
        assertEquals("milvus", service.vectorBackendStatus().get("active"));
    }

    @Test void failedAnnSourceThenAnotherPartialSuccessCannotRecoverReadiness() {
        syncAnn();
        doThrow(new IllegalStateException("mock ANN insert failure")).when(milvus)
                .replaceSource(eq(a.spaceFingerprint()), eq("note"), eq(1L), anyList());
        assertEquals(1, service.reindexChanged().get("reindexed"));
        assertEquals(false, service.vectorBackendStatus().get("annReady"));
        when(chunks.allNotes()).thenReturn(List.of(source(1), source(2)));
        when(states.selectList(null)).thenReturn(List.of(state(a.spaceFingerprint())));
        assertEquals(1, service.reindexChanged().get("reindexed"));
        assertEquals(false, service.vectorBackendStatus().get("annReady"));
        assertEquals("mysql", service.vectorBackendStatus().get("active"));
        doNothing().when(milvus).replaceSource(anyString(), anyString(), anyLong(), anyList());
        when(chunks.loadAll()).thenReturn(List.of(storedRow(1), storedRow(2)));
        service.syncToMilvus();
        assertEquals(true, service.vectorBackendStatus().get("annReady"));
        verify(milvus, times(2)).clear(a.spaceFingerprint());
    }

    @Test void successfulPartialUpdatePreservesAlreadyCompleteAnn() {
        syncAnn();
        assertEquals(1, service.reindexChanged().get("reindexed"));
        assertEquals(true, service.vectorBackendStatus().get("annReady"));
    }

    @Test void readinessFromAnotherMilvusServiceCannotEnablePartialSync() {
        syncAnn();
        when(milvus.effectiveUri()).thenReturn("http://other-milvus.test");
        assertEquals(1, service.reindexChanged().get("reindexed"));
        assertEquals(false, service.vectorBackendStatus().get("annReady"));
        assertEquals("mysql", service.vectorBackendStatus().get("active"));
    }

    @Test void failedFullAnnRebuildKeepsMysqlActiveAfterDatabaseCommit() throws Exception {
        syncAnn();
        doThrow(new IllegalStateException("mock ANN clear failure")).when(milvus).clear(a.spaceFingerprint());
        assertEquals("done", rebuild().status);
        assertEquals(false, service.vectorBackendStatus().get("annReady"));
        assertEquals("mysql", service.vectorBackendStatus().get("active"));
        verify(transactions).commit(any());
    }
}
