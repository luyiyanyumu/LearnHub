package org.dyh.learnhub.ai;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.dyh.learnhub.service.RetrievalContextService;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class AgentEvidenceBudgetTest {
    @Test void capturedLargerBudgetKeepsAllActualEvidenceInsteadOfUsingTheOldSevenThousandCap() {
        String source = "合".repeat(9000);
        var context = new RetrievalContextService.Context(List.of(source), List.of(), List.of());
        var evidence = new AgentToolEvidence(context, new ObjectMapper(), 12000);
        assertNull(evidence.skipReason());
        assertEquals(source, evidence.text());
        evidence.addEditorExcerpt("补充当前编辑器原文");
        assertNull(evidence.skipReason());
        assertTrue(evidence.text().startsWith(source));
        evidence.addEditorExcerpt("新".repeat(4000));
        assertNotNull(evidence.skipReason());
    }

    @Test void legacyConstructorStillUsesItsOriginalDefaultBudget() {
        var context = new RetrievalContextService.Context(List.of("合".repeat(7001)), List.of(), List.of());
        var evidence = new AgentToolEvidence(context, new ObjectMapper());
        assertNotNull(evidence.skipReason()); assertEquals("", evidence.text());
    }
}
