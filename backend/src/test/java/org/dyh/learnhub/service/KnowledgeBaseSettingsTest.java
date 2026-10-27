package org.dyh.learnhub.service;

import org.dyh.learnhub.controller.KnowledgeSettingsController;
import org.junit.jupiter.api.Test;
import java.util.LinkedHashMap;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.Mockito.*;

class KnowledgeBaseSettingsTest {
    private final SettingsService store = mock(SettingsService.class);
    private final KnowledgeBaseSettings service = new KnowledgeBaseSettings(store);

    @Test void defaultsAreTypedAndEveryFieldHasOneRegisteredStorageKey() {
        var config = service.snapshot();
        assertEquals("structure", config.chunkStrategy());
        assertEquals(800, config.chunkTarget());
        assertEquals(4000, config.semanticMinChars());
        assertEquals(85, config.semanticBreakpointPercentile());
        assertEquals(24, config.candidates());
        assertEquals(20, config.fusedWindow());
        assertEquals(.45, config.vectorMinScore());
        assertEquals(1, config.vectorSourcePriority());
        assertTrue(config.vectorEnabled());
        assertFalse(config.rerankEnabled());
        assertEquals("mysql", config.vectorBackend());
        assertEquals(KnowledgeBaseSettings.fields().size(), config.values().size());
        assertTrue(SettingsService.knownKeys().containsAll(KnowledgeBaseSettings.settingKeys()));
        assertFalse(config.values().containsKey("milvusToken"));
        assertThrows(UnsupportedOperationException.class, () -> config.values().put("chunkTarget", 100));
    }

    @Test void badCrossFieldBatchIsRejectedBeforeAnyWrite() {
        assertThrows(IllegalArgumentException.class, () -> service.update(Map.of("chunkTarget", 1000, "chunkOverlap", 1000)));
        assertThrows(IllegalArgumentException.class, () -> service.update(Map.of("candidates", 4)));
        assertThrows(IllegalArgumentException.class, () -> service.update(Map.of("fusedWindow", 21)));
        assertThrows(IllegalArgumentException.class, () -> service.update(Map.of("wikiWindow", 20)));
        assertThrows(IllegalArgumentException.class, () -> service.update(Map.of("totalChars", 3000)));
        assertThrows(IllegalArgumentException.class, () -> service.update(Map.of("passageChars", 8000)));
        verify(store, never()).updateAll(anyMap());
    }

    @Test void aValidMergedBatchCanChangeDependentFieldsTogether() {
        var next = service.validatePatch(Map.of("chunkTarget", 1500, "chunkMax", 1800,
                "candidates", 8, "fusedWindow", 8, "maxPassages", 6, "vectorScoreGap", .1));
        assertEquals(1500, next.chunkTarget());
        assertEquals(1800, next.chunkMax());
        assertEquals(8, next.candidates());
        assertEquals(.1, next.vectorScoreGap());
    }

    @Test void invalidTypesUnknownNamesAndCredentialsInUrlsAreRejected() {
        for (Map<String, ?> patch : java.util.List.of(Map.of("candidates", 8.5),
                Map.of("vectorMinScore", Double.NaN), Map.of("rerankBackend", "invalid"),
                Map.of("unknown", true), Map.of("milvusUri", "http://user:secret@host.test"),
                Map.of("rerankUrl", "file:///tmp/test"))) {
            assertThrows(IllegalArgumentException.class, () -> service.update(patch));
        }
        verify(store, never()).updateAll(anyMap());
    }

    @Test void blankResetOnlyWritesSubmittedFieldAndReportsIndexImpact() {
        when(store.effective("kb.chunk_target")).thenReturn("1000");
        var result = service.update(Map.of("chunkTarget", ""));
        assertEquals(800, ((Map<?, ?>) result.get("values")).get("chunkTarget"));
        assertEquals(true, result.get("needsRebuild"));
        Map<String, String> expected = new LinkedHashMap<>();
        expected.put("kb.chunk_target", null);
        verify(store).updateAll(expected);
    }

    @Test void semanticChunkingSettingsPersistTogetherAndInvalidateTheIndex() {
        var result = service.update(Map.of("chunkStrategy", "semantic_long", "semanticMinChars", 6000,
                "semanticBreakpointPercentile", 90));
        var values = (Map<?, ?>) result.get("values");
        assertEquals("semantic_long", values.get("chunkStrategy"));
        assertEquals(6000, values.get("semanticMinChars"));
        assertEquals(90, values.get("semanticBreakpointPercentile"));
        assertEquals(true, result.get("needsRebuild"));
        assertTrue(((java.util.List<?>) result.get("rebuildFields")).containsAll(
                java.util.List.of("chunkStrategy", "semanticMinChars", "semanticBreakpointPercentile")));
        verify(store).updateAll(Map.of("kb.chunk_strategy", "semantic_long", "kb.semantic_min_chars", "6000",
                "kb.semantic_breakpoint_percentile", "90"));

        var defaults = KnowledgeBaseSettings.defaults();
        for (var field : java.util.List.of("chunkStrategy", "semanticMinChars", "semanticBreakpointPercentile")) {
            assertTrue(KnowledgeBaseSettings.fields().stream().filter(f -> f.key().equals(field)).findFirst().orElseThrow().requiresRebuild());
            assertNotEquals(defaults.indexingFingerprint(), KnowledgeBaseSettings.validatePatch(defaults,
                    Map.of(field, values.get(field))).indexingFingerprint());
        }
    }

    @Test void invalidSemanticChunkingOptionsAreRejectedAtomically() {
        for (Map<String, ?> patch : java.util.List.of(Map.of("chunkStrategy", "sentences"),
                Map.of("semanticMinChars", 1999), Map.of("semanticMinChars", 100001),
                Map.of("semanticBreakpointPercentile", 49), Map.of("semanticBreakpointPercentile", 100),
                Map.of("semanticBreakpointPercentile", 85.5))) {
            assertThrows(IllegalArgumentException.class, () -> service.update(patch));
        }
        verify(store, never()).updateAll(anyMap());
    }

    @Test void preSemanticConfigMapsRetainStructureDefaults() {
        var legacy = new LinkedHashMap<>(KnowledgeBaseSettings.defaults().values());
        legacy.keySet().removeAll(java.util.List.of("chunkStrategy", "semanticMinChars", "semanticBreakpointPercentile"));
        var config = new KnowledgeBaseSettings.Config(legacy);
        assertEquals("structure", config.chunkStrategy());
        assertEquals(4000, config.semanticMinChars());
        assertEquals(85, config.semanticBreakpointPercentile());
        assertEquals(KnowledgeBaseSettings.defaults().indexingFingerprint(), config.indexingFingerprint());
    }

    @Test void controllerReturnsReadableValidationFailureAndAcceptsTypedEnvelope() {
        var controller = new KnowledgeSettingsController(service);
        assertEquals(400, controller.update(Map.of("values", Map.of("chunkOverlap", 800))).getCode());
        assertEquals(200, controller.update(Map.of("values", Map.of("rerankEnabled", true))).getCode());
        verify(store).updateAll(Map.of("kb.rerank", "1"));
    }
}
