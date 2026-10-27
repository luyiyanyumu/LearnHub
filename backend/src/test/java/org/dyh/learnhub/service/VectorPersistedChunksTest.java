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

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class VectorPersistedChunksTest {
    private final KbChunkMapper chunks = mock(KbChunkMapper.class);
    private final KbIndexStateMapper states = mock(KbIndexStateMapper.class);
    private final EmbeddingClient embedder = mock(EmbeddingClient.class);
    private final SettingsService settings = mock(SettingsService.class);
    private final KnowledgeBaseSettings knowledgeSettings = mock(KnowledgeBaseSettings.class);
    private final PlatformTransactionManager tx = mock(PlatformTransactionManager.class);
    private final VectorIndexService service = new VectorIndexService(chunks, states, embedder, settings,
            mock(MysqlVectorStore.class), mock(MilvusVectorStore.class), tx);
    private final EmbeddingClient.Snapshot target = EmbeddingClient.Snapshot.of("synthetic", "http://example.test", "ollama", null);
    private final KnowledgeBaseSettings.Config config = KnowledgeBaseSettings.validatePatch(KnowledgeBaseSettings.defaults(),
            Map.of("chunkStrategy", "semantic_long", "semanticMinChars", 2000));
    private final String body = "# 合成缓存\n\n" + "缓存失效和数据库一致性的处理。".repeat(160);
    private final List<KbChunk> rows = new ArrayList<>();
    private final AtomicReference<KbIndexState> saved = new AtomicReference<>();
    private final Map<String, Object> document = Map.of("source_type", "file", "source_id", 1L,
            "title", "合成资料", "content", body, "category", "");

    @BeforeEach void setup() {
        service.setKnowledgeSettings(knowledgeSettings);
        when(knowledgeSettings.snapshot()).thenReturn(config);
        when(settings.effective(anyString())).thenReturn("");
        when(embedder.snapshot()).thenReturn(target);
        when(chunks.allNotes()).thenReturn(List.of());
        when(chunks.allRefs()).thenReturn(List.of());
        when(chunks.allFiles()).thenReturn(List.of(Map.of("id", 1L, "title", "合成资料", "content", body)));
        when(chunks.loadAll()).thenAnswer(inv -> List.copyOf(rows));
        when(chunks.loadPassages()).thenAnswer(inv -> new ArrayList<>(rows));
        when(states.selectList(null)).thenAnswer(inv -> saved.get() == null ? List.of() : List.of(saved.get()));
        when(states.selectById("file:1")).thenAnswer(inv -> saved.get());
        when(states.insert(any(KbIndexState.class))).thenAnswer(inv -> { saved.set(inv.getArgument(0)); return 1; });
        when(chunks.insert(any(KbChunk.class))).thenAnswer(inv -> {
            KbChunk row = inv.getArgument(0);
            row.setId((long) rows.size() + 1);
            rows.add(row);
            return 1;
        });
        when(tx.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
        when(embedder.embedAll(eq(target), anyList())).thenAnswer(inv ->
                ((List<?>) inv.getArgument(1)).stream().map(value -> new float[]{1, 0}).toList());
        when(embedder.embedAll(eq(target), anyList(), anyInt(), anyInt())).thenAnswer(inv ->
                ((List<?>) inv.getArgument(1)).stream().map(value -> new float[]{1, 0}).toList());
    }

    @Test void reusesExactPersistedSemanticBoundariesWithoutAnotherModelCall() {
        service.reindexChanged();
        assertFalse(rows.isEmpty());
        clearInvocations(embedder);
        var result = service.persistedChunks(List.of(document), config);
        assertEquals(rows.stream().map(KbChunk::getChunkText).toList(), result.get("file:1").stream().map(TextChunker.Chunk::text).toList());
        assertTrue(result.get("file:1").stream().allMatch(chunk -> chunk.heading().equals("合成缓存")));
        verify(embedder).snapshot();
        verifyNoMoreInteractions(embedder);
    }

    @Test void editsSettingsChangesAndIncompleteRowsNeverReuseOldBoundaries() {
        service.reindexChanged();
        var changed = new java.util.LinkedHashMap<>(document);
        changed.put("content", body + "\n内容已修改。");
        assertTrue(service.persistedChunks(List.of(changed), config).isEmpty());
        changed.put("content", body);
        changed.put("title", "主题已改变但正文相同");
        assertTrue(service.persistedChunks(List.of(changed), config).isEmpty());
        var next = KnowledgeBaseSettings.validatePatch(config, Map.of("semanticBreakpointPercentile", 90));
        assertTrue(service.persistedChunks(List.of(document), next).isEmpty());
        rows.removeLast();
        assertTrue(service.persistedChunks(List.of(document), config).isEmpty());
    }
}
