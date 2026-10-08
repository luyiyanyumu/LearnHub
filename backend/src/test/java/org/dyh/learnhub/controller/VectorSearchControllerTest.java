package org.dyh.learnhub.controller;

import org.dyh.learnhub.service.KnowledgeRetrievalService;
import org.dyh.learnhub.service.RetrievalHit;
import org.dyh.learnhub.service.VectorIndexService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class VectorSearchControllerTest {
    @Mock KnowledgeRetrievalService retrieval;
    @Mock VectorIndexService vectors;
    @InjectMocks VectorController controller;

    @Test void primarySearchUsesSharedFusionAndPreservesAnArrayOfPassages() {
        var hits = List.of(new RetrievalHit("note", 7L, "标题", "", "正文", .03, 0,
                List.of("keyword", "graph"), List.of("A 关联 B")));
        when(retrieval.search("问题", 10)).thenReturn(hits);
        assertSame(hits, controller.search(" 问题 ", null).getData());
        verify(retrieval).search("问题", 10);
        verifyNoInteractions(vectors);
    }

    @Test void explicitTopKIsForwardedToSharedSearch() {
        controller.search("q", 3);
        verify(retrieval).search("q", 3);
        verifyNoInteractions(vectors);
    }

    @Test void blankQueryReturnsEmptyArrayWithoutRetrieval() {
        assertEquals(List.of(), controller.search("  ", null).getData());
        assertEquals(List.of(), controller.search(null, 3).getData());
        verifyNoInteractions(retrieval, vectors);
    }
}
