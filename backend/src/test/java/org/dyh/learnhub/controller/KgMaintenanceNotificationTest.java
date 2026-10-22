package org.dyh.learnhub.controller;

import org.dyh.learnhub.service.KgGraphService;
import org.dyh.learnhub.service.KgPipelineService;
import org.dyh.learnhub.service.KgService;
import org.dyh.learnhub.service.KnowledgeMaintenanceService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.StaticListableBeanFactory;

import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class KgMaintenanceNotificationTest {
    @Test void successfulManualChangesScheduleDependentMaintenanceButReadsAndFailuresDoNot() {
        KgGraphService graph = mock(KgGraphService.class);
        KgPipelineService pipeline = mock(KgPipelineService.class);
        KnowledgeMaintenanceService maintenance = mock(KnowledgeMaintenanceService.class);
        KgController controller = new KgController(mock(KgService.class), graph, pipeline);
        StaticListableBeanFactory beans = new StaticListableBeanFactory();
        beans.addBean("maintenance", maintenance);
        controller.setMaintenance(beans.getBeanProvider(KnowledgeMaintenanceService.class));
        when(graph.graph()).thenReturn(Map.of());
        when(graph.mergeNodes("synthetic-a", "synthetic-b")).thenReturn(Map.of("merged", true));
        when(graph.deleteNode("synthetic-a")).thenReturn(Map.of("deleted", true));
        when(graph.deleteRelation(91L)).thenReturn(Map.of("deleted", true));
        when(pipeline.start()).thenReturn(Map.of("jobId", "synthetic"));
        when(graph.reason()).thenReturn(Map.of("added", 0));
        controller.concept();
        verifyNoInteractions(maintenance);
        controller.merge("synthetic-a", "synthetic-b");
        controller.deleteNode("synthetic-a");
        controller.deleteRelation(91L);
        controller.build();
        controller.reason();
        controller.linkWiki();
        verify(maintenance, times(6)).graphChanged();
        when(graph.deleteRelation(92L)).thenThrow(new IllegalArgumentException("synthetic missing relation"));
        assertThrows(IllegalArgumentException.class, () -> controller.deleteRelation(92L));
        verifyNoMoreInteractions(maintenance);
    }
}
