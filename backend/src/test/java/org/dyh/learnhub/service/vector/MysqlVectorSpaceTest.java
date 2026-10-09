package org.dyh.learnhub.service.vector;

import org.dyh.learnhub.ai.EmbeddingClient;
import org.dyh.learnhub.entity.KbChunk;
import org.dyh.learnhub.mapper.KbChunkMapper;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class MysqlVectorSpaceTest {
    private final KbChunkMapper mapper = mock(KbChunkMapper.class);
    private final MysqlVectorStore store = new MysqlVectorStore(mapper);

    private KbChunk row(long id, String space, float... vector) {
        KbChunk row = new KbChunk();
        row.setId(id); row.setSourceId(id); row.setSourceType("note"); row.setModel("same-model");
        row.setEmbeddingSpace(space); row.setDim(vector.length); row.setVec(EmbeddingClient.toBytes(vector));
        return row;
    }

    @Test void sameModelAndDimensionAtDifferentEndpointsNeverMix() {
        String a = EmbeddingClient.Snapshot.of("same-model", "http://a.test/v1", "custom", null).spaceFingerprint();
        String b = EmbeddingClient.Snapshot.of("same-model", "http://b.test/v1", "custom", null).spaceFingerprint();
        when(mapper.loadAll()).thenReturn(List.of(row(1, a, 1, 0), row(2, b, 1, 0), row(3, null, 1, 0)));
        assertEquals(List.of(1L), store.search(a, new float[]{1, 0}, 10).stream().map(VectorStore.VecHit::id).toList());
        assertEquals(List.of(2L), store.search(b, new float[]{1, 0}, 10).stream().map(VectorStore.VecHit::id).toList());
    }

    @Test void legacyAndMalformedVectorsDoNotMakeAnIndexAvailable() {
        String space = "a".repeat(64);
        KbChunk broken = row(2, space, 1, 0); broken.setVec(new byte[3]);
        when(mapper.loadAll()).thenReturn(List.of(row(1, null, 1, 0), broken));
        assertFalse(store.hasVectors(space));
        assertTrue(store.search(space, new float[]{1, 0}, 10).isEmpty());
        assertTrue(store.search(null, new float[]{1, 0}, 10).isEmpty());
    }

    @Test void dimensionAndNonFiniteVectorsAreExcluded() {
        String space = "a".repeat(64);
        when(mapper.loadAll()).thenReturn(List.of(row(1, space, 1, 0, 0), row(2, space, Float.NaN, 0), row(3, space, 1, 0)));
        assertEquals(List.of(3L), store.search(space, new float[]{1, 0}, 10).stream().map(VectorStore.VecHit::id).toList());
    }

    @Test void cacheRefreshesWhenSpaceChangesWithoutScaleChanging() {
        String a = "a".repeat(64), b = "b".repeat(64);
        when(mapper.cacheFingerprint()).thenReturn("first", "second");
        when(mapper.loadAll()).thenReturn(List.of(row(1, a, 1, 0)), List.of(row(1, b, 1, 0)));
        assertTrue(store.hasVectors(a));
        assertFalse(store.hasVectors(a));
        verify(mapper, times(2)).loadAll();
    }
}
