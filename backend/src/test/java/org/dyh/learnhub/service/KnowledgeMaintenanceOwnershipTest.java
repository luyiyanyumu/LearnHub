package org.dyh.learnhub.service;

import org.dyh.learnhub.common.KnowledgeChangedEvent;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import java.util.Set;
import static org.mockito.Mockito.*;

class KnowledgeMaintenanceOwnershipTest {
    @Test void installedCoordinatorAlwaysOwnsEventsIncludingWhenAutomaticMaintenanceIsPaused() {
        KnowledgeMaintenanceService maintenance = mock(KnowledgeMaintenanceService.class);
        when(maintenance.enabled()).thenReturn(false);
        StaticListableBeanFactory beans = new StaticListableBeanFactory();
        beans.addBean("maintenance", maintenance);
        // No legacy dependencies are supplied: neither event listener may enter its old scheduling path.
        VectorIndexService index = new VectorIndexService(null, null, null, null, null, null, null);
        WikiService wiki = new WikiService(null, null, null, null, null, null, null, null,
                null, null, null, null, null, null, null);
        index.setMaintenanceProvider(beans.getBeanProvider(KnowledgeMaintenanceService.class));
        wiki.setMaintenanceProvider(beans.getBeanProvider(KnowledgeMaintenanceService.class));
        try {
            KnowledgeChangedEvent event = new KnowledgeChangedEvent(Set.of(91001L), "synthetic");
            index.onKnowledgeChanged(event);
            wiki.onKnowledgeChanged(event);
            verify(maintenance, never()).enabled();
        } finally { wiki.shutdown(); }
    }
}
