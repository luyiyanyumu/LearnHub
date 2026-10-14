package org.dyh.learnhub.service;

import org.dyh.learnhub.ai.EmbeddingClient;
import org.dyh.learnhub.entity.KbChunk;
import org.dyh.learnhub.entity.KbIndexState;
import org.dyh.learnhub.mapper.KbChunkMapper;
import org.dyh.learnhub.mapper.KbIndexStateMapper;
import org.dyh.learnhub.service.vector.MilvusVectorStore;
import org.dyh.learnhub.service.vector.MysqlVectorStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;
import java.util.List;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class VectorIndexParametersTest {
    private final KbChunkMapper chunks = mock(KbChunkMapper.class);
    private final KbIndexStateMapper states = mock(KbIndexStateMapper.class);
    private final EmbeddingClient embedder = mock(EmbeddingClient.class);
    private final SettingsService store = mock(SettingsService.class);
    private final KnowledgeBaseSettings settings = mock(KnowledgeBaseSettings.class);
    private final PlatformTransactionManager tx = mock(PlatformTransactionManager.class);
    private final EmbeddingClient.Snapshot target = EmbeddingClient.Snapshot.of("fixture", "http://fixture.test/v1", "custom", null);
    private final KnowledgeBaseSettings.Config config = KnowledgeBaseSettings.validatePatch(KnowledgeBaseSettings.defaults(),
            Map.of("chunkTarget", 100, "chunkOverlap", 20, "chunkMax", 120, "embedBatchSize", 2, "embeddingInputChars", 256));
    private final String body = "# 合成章节\n" + java.util.stream.IntStream.range(0, 8)
            .mapToObj(i -> "合成段" + i + "内容".repeat(30)).collect(java.util.stream.Collectors.joining("\n\n"));
    private VectorIndexService service;

    @BeforeEach void setup() {
        when(settings.snapshot()).thenReturn(config);
        when(store.vectorEnabled()).thenReturn(true);
        when(embedder.snapshot()).thenReturn(target);
        when(chunks.allNotes()).thenReturn(List.of(Map.of("id", 1, "title", "合成标题", "content", body)));
        when(chunks.allRefs()).thenReturn(List.of()); when(chunks.allFiles()).thenReturn(List.of());
        KbIndexState old = new KbIndexState(); old.setId("note:1"); old.setSourceType("note"); old.setSourceId(1L);
        old.setEmbeddingSpace(target.spaceFingerprint());
        old.setContentHash(KgService.sha256(target.spaceFingerprint() + "\u0000true\u0000合成标题\u0000" + body));
        when(states.selectList(null)).thenReturn(List.of(old)); when(states.selectById("note:1")).thenReturn(old);
        KbChunk row = new KbChunk(); row.setId(1L); row.setSourceType("note"); row.setSourceId(1L);
        row.setDim(2); row.setVec(EmbeddingClient.toBytes(new float[]{1, 0})); row.setEmbeddingSpace(target.spaceFingerprint());
        when(chunks.loadAll()).thenReturn(List.of(row));
        when(tx.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
        when(embedder.embedAll(eq(target), anyList(), eq(2), eq(256))).thenAnswer(inv ->
                ((List<?>) inv.getArgument(1)).stream().map(input -> new float[]{1, 0}).toList());
        var mysql = mock(MysqlVectorStore.class); when(mysql.name()).thenReturn("mysql");
        service = new VectorIndexService(chunks, states, embedder, store, mysql, mock(MilvusVectorStore.class), tx);
        service.setKnowledgeSettings(settings);
    }

    @Test void changedSplitterFingerprintRemainsStaleAfterReloadAndIncrementalUsesNewParameters() {
        assertEquals(true, service.status().get("stale"));
        var result = service.reindexChanged();
        assertEquals(1, result.get("reindexed"));
        assertTrue((Integer) result.get("chunks") > 1);
        verify(embedder).embedAll(eq(target), anyList(), eq(2), eq(256));
        var rows = org.mockito.ArgumentCaptor.forClass(KbChunk.class);
        verify(chunks, atLeastOnce()).insert(rows.capture());
        assertTrue(rows.getAllValues().stream().allMatch(row -> row.getChunkText().length() <= 120));
        verify(states).updateById(argThat((KbIndexState state) -> !state.getContentHash().equals(
                KgService.sha256(target.spaceFingerprint() + "\u0000true\u0000合成标题\u0000" + body))));
    }

    @Test void configSwitchDuringEmbeddingPreservesThePreviouslyCommittedIndex() {
        when(embedder.embedAll(eq(target), anyList(), eq(2), eq(256))).thenAnswer(inv -> {
            when(settings.snapshot()).thenReturn(KnowledgeBaseSettings.defaults());
            return ((List<?>) inv.getArgument(1)).stream().map(input -> new float[]{1, 0}).toList();
        });
        assertThrows(IllegalStateException.class, service::reindexChanged);
        verify(chunks, never()).deleteBySource(anyString(), anyLong());
        verify(chunks, never()).insert(any(KbChunk.class));
        verifyNoInteractions(tx);
    }
}
