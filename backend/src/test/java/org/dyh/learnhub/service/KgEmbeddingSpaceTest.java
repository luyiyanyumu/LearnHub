package org.dyh.learnhub.service;

import org.dyh.learnhub.ai.EmbeddingClient;
import org.dyh.learnhub.entity.KgNode;
import org.dyh.learnhub.mapper.KgNodeMapper;
import org.dyh.learnhub.mapper.KgRelationMapper;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class KgEmbeddingSpaceTest {
    private final KgNodeMapper mapper = mock(KgNodeMapper.class);
    private final EmbeddingClient embedder = mock(EmbeddingClient.class);
    private final KgGraphService graph = new KgGraphService(mapper, mock(KgRelationMapper.class), embedder);
    private final EmbeddingClient.Snapshot a = EmbeddingClient.Snapshot.of("model-a", "http://a.test/v1", "custom", null);
    private final EmbeddingClient.Snapshot b = EmbeddingClient.Snapshot.of("model-b", "http://a.test/v1", "custom", null);

    private KgNode node(String name, String space) {
        KgNode node = new KgNode(); node.setId(name); node.setName(name); node.setBrief("实体说明");
        node.setEmbedding(EmbeddingClient.toBytes(new float[]{1, 0})); node.setEmbeddingSpace(space);
        return node;
    }

    @Test void sameDimensionFromAnotherModelAndLegacyAreNotRecognized() {
        KgNode current = node("Current", a.spaceFingerprint());
        when(embedder.snapshot()).thenReturn(a);
        when(mapper.selectList(null)).thenReturn(List.of(node("Old", b.spaceFingerprint()), node("Legacy", null), current));
        when(embedder.embed(a, "问题")).thenReturn(new float[]{1, 0});
        assertEquals(List.of(current), graph.recognizeByVector("问题", 10));
    }

    @Test void unconfiguredEmbeddingKeepsNamesAndOldVectorsAvailable() {
        KgNode old = node("Planning", a.spaceFingerprint());
        when(mapper.selectList(null)).thenReturn(List.of(old));
        when(embedder.snapshot()).thenReturn(EmbeddingClient.Snapshot.disabled("未配置"));
        assertEquals(List.of(old), graph.recognize("Planning如何工作", 10));
        assertTrue(graph.recognizeByVector("如何规划", 10).isEmpty());
        assertEquals(false, graph.embedEntities(Map.of()).get("configured"));
        verify(embedder, never()).embed(any(EmbeddingClient.Snapshot.class), anyString());
        verify(mapper, never()).updateById(any(KgNode.class));
        assertEquals(a.spaceFingerprint(), old.getEmbeddingSpace());
    }

    @Test void modelSwitchDuringPreparationPreservesEveryOldVector() {
        KgNode old = node("Planning", b.spaceFingerprint()); byte[] before = old.getEmbedding().clone();
        when(mapper.selectList(null)).thenReturn(List.of(old));
        when(embedder.snapshot()).thenReturn(a, b);
        when(embedder.embed(eq(a), anyString())).thenReturn(new float[]{0, 1});
        assertThrows(IllegalStateException.class, () -> graph.embedEntities(Map.of()));
        verify(mapper, never()).updateById(any(KgNode.class));
        assertArrayEquals(before, old.getEmbedding());
        assertEquals(b.spaceFingerprint(), old.getEmbeddingSpace());
    }

    @Test void failedEntityRetainsItsSpaceWhileSuccessfulEntityIsExplicitlyTagged() {
        KgNode ok = node("Planning", b.spaceFingerprint()), failed = node("ReAct", b.spaceFingerprint());
        when(mapper.selectList(null)).thenReturn(List.of(ok, failed));
        when(embedder.snapshot()).thenReturn(a);
        when(embedder.embed(eq(a), anyString())).thenReturn(new float[]{0, 1}).thenThrow(new IllegalStateException("mock failure"));
        Map<String, Object> result = graph.embedEntities(Map.of());
        assertEquals(1, result.get("embedded")); assertEquals(1, result.get("failed"));
        assertEquals(a.spaceFingerprint(), ok.getEmbeddingSpace());
        assertEquals(b.spaceFingerprint(), failed.getEmbeddingSpace());
        verify(mapper).updateById(ok); verify(mapper, never()).updateById(failed);
    }
}
