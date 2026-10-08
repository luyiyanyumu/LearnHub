package org.dyh.learnhub.service;

import org.dyh.learnhub.mapper.KgCommunityMapper;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class KgCommunityServiceTest {
    private final KgCommunityMapper mapper = mock(KgCommunityMapper.class);
    private final KgCommunityService service = new KgCommunityService(mapper);
    private final List<String> nodes = List.of("a", "b");
    private final List<Map<String, Object>> edge = List.of(Map.of("head", "a", "tail", "b", "w", 1.0));

    private void graph() {
        when(mapper.nodeIds()).thenReturn(nodes);
        when(mapper.edges()).thenReturn(edge);
        when(mapper.all()).thenReturn(List.of(Map.of("nodeId", "a"), Map.of("nodeId", "b")));
    }

    @Test void unchangedPersistedFingerprintSkipsLocalRecompute() {
        graph();
        when(mapper.partitionFingerprint()).thenReturn(KgCommunityService.graphFingerprint(nodes, edge));
        assertEquals(true, service.recompute(false).get("skipped"));
        verify(mapper, never()).clear();
        verify(mapper, never()).savePartitionFingerprint(anyString());
    }

    @Test void deletingOnlyAnEdgeInvalidatesEvenWhenNoTimestampAdvanced() {
        graph();
        when(mapper.partitionFingerprint()).thenReturn(KgCommunityService.graphFingerprint(nodes, edge));
        when(mapper.edges()).thenReturn(List.of());
        assertEquals(false, service.recompute(false).get("skipped"));
        verify(mapper).clear();
        verify(mapper).savePartitionFingerprint(KgCommunityService.graphFingerprint(nodes, List.of()));
    }

    @Test void missingFingerprintAfterUpgradeConservativelyRecomputes() {
        graph();
        assertEquals(false, service.recompute(false).get("skipped"));
        verify(mapper).savePartitionFingerprint(anyString());
    }

    @Test void incompletePartitionIsRepairedDespiteMatchingGraphFingerprint() {
        graph();
        when(mapper.partitionFingerprint()).thenReturn(KgCommunityService.graphFingerprint(nodes, edge));
        when(mapper.all()).thenReturn(List.of(Map.of("nodeId", "a")));
        assertEquals(false, service.recompute(false).get("skipped"));
        verify(mapper).insert(eq("b"), anyInt(), eq(0), anyInt(), anyDouble());
    }

    @Test void nodeOrderingDoesNotInvalidateAStableGraph() {
        assertEquals(KgCommunityService.graphFingerprint(List.of("a", "b"), edge),
                KgCommunityService.graphFingerprint(List.of("b", "a"), edge));
    }

    @Test void groupedReadsHaveActualSortedMembersAndDoNotWrite() {
        when(mapper.all()).thenReturn(List.of(
                Map.of("nodeId", "b", "communityId", 4L, "size", 999L, "modularity", .4),
                Map.of("nodeId", "a", "communityId", 4L, "size", 999L, "modularity", .4)));
        var group = service.grouped().getFirst();
        assertEquals(2, group.get("size"));
        assertEquals(List.of("a", "b"), group.get("nodeIds"));
        verify(mapper, never()).clear();
        verify(mapper, never()).savePartitionFingerprint(anyString());
    }
}
