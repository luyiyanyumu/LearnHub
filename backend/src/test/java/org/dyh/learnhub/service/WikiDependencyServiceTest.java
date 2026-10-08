package org.dyh.learnhub.service;

import org.dyh.learnhub.entity.WikiPage;
import org.dyh.learnhub.entity.WikiSourceDependency;
import org.dyh.learnhub.mapper.GraphEvidenceSourceMapper;
import org.dyh.learnhub.mapper.WikiPageMapper;
import org.dyh.learnhub.mapper.WikiSourceDependencyMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class WikiDependencyServiceTest {
    private WikiSourceDependencyMapper dependencies;
    private GraphEvidenceSourceMapper sources;
    private WikiPageMapper pages;
    private WikiDependencyService service;
    private final Map<Long, WikiPage> pageStore = new LinkedHashMap<>();
    private final Map<Long, List<WikiSourceDependency>> dependencyStore = new LinkedHashMap<>();
    private final Map<WikiDependencyService.SourceRef, WikiDependencyService.SourceInput> sourceStore = new LinkedHashMap<>();

    @BeforeEach
    void setup() {
        dependencies = mock(WikiSourceDependencyMapper.class);
        sources = mock(GraphEvidenceSourceMapper.class);
        pages = mock(WikiPageMapper.class);
        service = new WikiDependencyService(dependencies, sources, pages);
        when(dependencies.lockTopic(anyString())).thenAnswer(call -> pageStore.values().stream()
                .filter(page -> call.getArgument(0).equals(page.getTopicKey())).map(WikiPage::getId).findFirst().orElse(null));
        when(dependencies.lockPage(anyLong())).thenAnswer(call -> pageStore.containsKey(call.getArgument(0)) ? call.getArgument(0) : null);
        when(pages.selectById(anyLong())).thenAnswer(call -> pageStore.get(call.getArgument(0)));
        when(pages.insert(any(WikiPage.class))).thenAnswer(call -> {
            WikiPage page = call.getArgument(0); page.setId(100L + pageStore.size()); pageStore.put(page.getId(), page); return 1;
        });
        when(pages.updateById(any(WikiPage.class))).thenAnswer(call -> {
            WikiPage page = call.getArgument(0); pageStore.put(page.getId(), page); return 1;
        });
        when(dependencies.deleteByPage(anyLong())).thenAnswer(call -> dependencyStore.remove(call.getArgument(0)) == null ? 0 : 1);
        when(dependencies.insertBatch(anyList())).thenAnswer(call -> {
            List<WikiSourceDependency> rows = call.getArgument(0);
            for (WikiSourceDependency row : rows) dependencyStore.computeIfAbsent(row.getPageId(), ignored -> new ArrayList<>()).add(row);
            return rows.size();
        });
        when(dependencies.byPages(anyList())).thenAnswer(call -> {
            List<Long> ids = call.getArgument(0);
            return ids.stream().flatMap(id -> dependencyStore.getOrDefault(id, List.of()).stream()).toList();
        });
        when(sources.currentSources(anyString(), anyList())).thenAnswer(call -> {
            String type = call.getArgument(0); List<Long> ids = call.getArgument(1);
            return ids.stream().map(id -> sourceStore.get(new WikiDependencyService.SourceRef(type, id)))
                    .filter(Objects::nonNull).map(source -> Map.<String, Object>of("id", source.id(), "title", source.title(), "content", source.fullContent())).toList();
        });
    }

    @Test
    void captureIsImmutableAndHashesOriginalFullTextIncludingUnsampledTail() {
        var source = input("note", 1, longBody());
        List<WikiDependencyService.SourceInput> callerInputs = new ArrayList<>(List.of(source));
        var captured = WikiDependencyService.fromSources(callerInputs);
        callerInputs.clear();
        assertEquals(1, captured.sources().size());
        assertEquals(WikiDependencyService.sha256(source.fullContent()), captured.sources().get(0).fullContentHash());
        assertThrows(UnsupportedOperationException.class, () -> captured.sources().clear());
        assertThrows(UnsupportedOperationException.class, () -> captured.sources().get(0).chunks().clear());
        assertEquals(captured.sourceHash(), WikiDependencyService.fromSources(List.of(source)).sourceHash(), "capture time cannot change a material hash");
        var tailChange = WikiDependencyService.fromSources(List.of(input("note", 1, source.fullContent() + "\nOnly the unsampled tail changed.")));
        assertNotEquals(captured.sourceHash(), tailChange.sourceHash());
    }

    @Test
    void canonicalTypesAndDeterministicOrderingProduceOneSnapshot() {
        var note = input("note", 1, "# Definition\nMQTT transports messages.");
        var card = input("ref", 2, "# Usage\nSubscribe to a topic.");
        var one = WikiDependencyService.fromSources(List.of(note, card, note));
        var two = WikiDependencyService.fromSources(List.of(card, note));
        assertEquals(one.hash(), two.hash());
        assertEquals("quick_ref", one.sources().get(1).ref().type());
        assertThrows(IllegalArgumentException.class, () -> WikiDependencyService.fromSources(List.of(note, input("note", 1, "Different revision"))));
        assertThrows(IllegalArgumentException.class, () -> new WikiDependencyService.SourceRef("wiki", 1L));
        assertThrows(IllegalArgumentException.class, () -> new WikiDependencyService.SourceRef("note", 0L));
    }

    @Test
    void selectedChunksRemoveUnrelatedSourcesButRetainCompleteSourceHashAndExactHeadings() {
        var relevant = input("note", 1, longBody());
        var unrelated = input("file", 2, "A separate file that never enters this generation.");
        var selected = WikiDependencyService.selectChunks(WikiDependencyService.fromSources(List.of(relevant, unrelated)),
                List.of(new WikiDependencyService.ChunkRef("note", 1L, 1)));
        assertEquals(List.of(relevant.ref()), selected.sourceRefs());
        assertEquals(1, selected.chunkCount());
        assertEquals(1, selected.sources().get(0).chunks().get(0).seq());
        assertEquals("Second section", selected.sources().get(0).chunks().get(0).heading());
        assertEquals(WikiDependencyService.sha256(relevant.fullContent()), selected.sources().get(0).fullContentHash());
        assertTrue(selected.sources().get(0).totalChunks() > 1);
        assertThrows(IllegalArgumentException.class, () -> WikiDependencyService.selectChunks(selected,
                List.of(new WikiDependencyService.ChunkRef("note", 1L, 999))));
    }

    @Test
    void coverageUsesRecordedSelectedChunksAndGenerationTimeSourceLengths() {
        var old = input("note", 1, longBody()); sourceStore.put(old.ref(), old);
        var selected = WikiDependencyService.selectChunks(snapshot(old), List.of(new WikiDependencyService.ChunkRef("note", 1L, 1)));
        WikiPage page = save("entity-coverage", selected);
        sourceStore.put(old.ref(), input("note", 1, "The current source is now much shorter."));
        Map<?, ?> coverage = (Map<?, ?>) service.evidenceView(page.getId()).get("coverage");
        List<Map<?, ?>> parts = (List<Map<?, ?>>) coverage.get("sources");
        assertEquals(1, parts.size());
        Map<?, ?> recorded = parts.get(0);
        assertEquals("note", recorded.get("sourceType")); assertEquals(1L, recorded.get("sourceId"));
        assertEquals(1, recorded.get("sentChunkCount")); assertEquals(3, recorded.get("totalChunkCount"));
        assertEquals(old.fullContent().length(), recorded.get("sourceChars"));
        assertEquals(selected.sources().get(0).chunks().get(0).text().length(), recorded.get("usedChars"));
        assertEquals(false, coverage.get("complete"));
    }

    @Test
    void exactCapturedSourceIsCurrentButNeverSemanticFactVerified() {
        var source = input("note", 1, "# ReAct\nThe agent reads observations.");
        sourceStore.put(source.ref(), source);
        WikiPage page = save("entity-react", snapshot(source));
        var fresh = service.freshness(page.getId());
        assertTrue(fresh.current()); assertTrue(fresh.hasDependencies()); assertFalse(fresh.legacy());
        Map<String, Object> view = service.evidenceView(page.getId());
        assertEquals("current", view.get("status")); assertEquals(true, view.get("sourceUnchanged")); assertEquals(false, view.get("factVerified"));
        Map<?, ?> coverage = (Map<?, ?>) view.get("coverage");
        assertEquals(1, coverage.get("sourceCount")); assertEquals(1, coverage.get("chunkCount")); assertEquals(true, coverage.get("complete"));
        Map<?, ?> detail = ((List<Map<?, ?>>) view.get("dependencies")).get(0);
        assertEquals("笔记#1", detail.get("sourceRef")); assertEquals(0, detail.get("seq")); assertEquals("ReAct", detail.get("heading"));
    }

    @Test
    void modificationOutsideSelectedChunkConservativelyInvalidatesWithoutTimestampDependency() {
        var old = input("note", 1, longBody());
        sourceStore.put(old.ref(), old);
        var captured = WikiDependencyService.selectChunks(snapshot(old), List.of(new WikiDependencyService.ChunkRef("note", 1L, 0)));
        WikiPage page = save("entity-react", captured);
        sourceStore.put(old.ref(), input("note", 1, old.fullContent() + "\nA new conclusion in another section."));
        assertEquals(WikiDependencyService.Status.STALE, service.freshness(page.getId()).status());
        assertEquals(List.of("source_changed"), service.freshness(page.getId()).reasons());
        Map<?, ?> detail = ((List<Map<?, ?>>) service.evidenceView(page.getId()).get("dependencies")).get(0);
        assertEquals(detail.get("chunkHash"), detail.get("currentChunkHash"), "selected chunk stayed the same but its complete source did not");
        assertEquals("source_changed", detail.get("status"));
    }

    @Test
    void sourceChangesDuringGenerationCannotBeBlessedByLaterSave() {
        var capturedSource = input("note", 1, "# Before\nOld source text.");
        var captured = snapshot(capturedSource);
        sourceStore.put(capturedSource.ref(), input("note", 1, "# After\nEdited while the model was generating."));
        WikiPage page = save("entity-race", captured);
        WikiSourceDependency row = dependencyStore.get(page.getId()).get(0);
        assertEquals(WikiDependencyService.sha256(capturedSource.fullContent()), row.getFullContentHash());
        assertEquals("Old source text.", row.getChunkText());
        assertFalse(service.freshness(page.getId()).current());
        assertEquals(List.of("source_changed"), service.freshness(page.getId()).reasons());
    }

    @Test
    void updatingTheWikiBodyWithoutReplacingDependenciesInvalidatesItsLocators() {
        var source = input("file", 7, "Original paper正文"); sourceStore.put(source.ref(), source);
        WikiPage page = save("entity-body", snapshot(source));
        page.setContentMd("A different Wiki revision that used different evidence.");
        var fresh = service.freshness(page.getId());
        assertEquals(WikiDependencyService.Status.STALE, fresh.status());
        assertEquals(List.of("page_changed"), fresh.reasons()); assertFalse(fresh.legacy());
    }

    @Test
    void unrelatedSourcesNeverEnterFreshnessReadsOrInvalidateSelectedPage() {
        var one = input("note", 1, "# ReAct\nA useful definition.");
        var other = input("note", 99, "Unrelated Java details.");
        sourceStore.put(one.ref(), one); sourceStore.put(other.ref(), other);
        var selected = WikiDependencyService.selectChunks(snapshot(one, other), List.of(new WikiDependencyService.ChunkRef("note", 1L, 0)));
        WikiPage page = save("entity-subset", selected);
        sourceStore.put(other.ref(), input("note", 99, "The unrelated text changed entirely."));
        assertTrue(service.freshness(page.getId()).current());
        verify(sources).currentSources("note", List.of(1L));
        verify(sources, never()).currentSources(eq("note"), argThat(ids -> ids.contains(99L)));
    }

    @Test
    void sourceRemovalOrLossOfExtractableTextIsStaleNotUnknownLegacy() {
        var source = input("file", 7, "Extracted PDF body"); sourceStore.put(source.ref(), source);
        WikiPage page = save("entity-delete", snapshot(source));
        sourceStore.remove(source.ref());
        var fresh = service.freshness(page.getId());
        assertEquals(WikiDependencyService.Status.STALE, fresh.status()); assertEquals(List.of("source_missing"), fresh.reasons());
        assertFalse(fresh.legacy());
        sourceStore.put(source.ref(), input("file", 7, " "));
        assertEquals(List.of("source_missing"), service.freshness(page.getId()).reasons());
    }

    @Test
    void legacyPagesAreExplicitlyUnknownAndReadFailuresCannotUseLegacyFallback() {
        WikiPage legacy = page("entity-legacy"); legacy.setId(10L); pageStore.put(10L, legacy);
        var missing = service.freshness(10L);
        assertEquals(WikiDependencyService.Status.UNKNOWN, missing.status()); assertFalse(missing.hasDependencies()); assertTrue(missing.legacy());
        when(dependencies.byPages(anyList())).thenThrow(new IllegalStateException("connection unavailable"));
        var failure = service.freshness(10L);
        assertEquals(WikiDependencyService.Status.UNKNOWN, failure.status()); assertFalse(failure.legacy());
        assertEquals(List.of("dependency_check_failed"), failure.reasons());
    }

    @Test
    void losingAllDependencyRowsDoesNotDowngradeNewGenerationToLegacy() {
        var source = input("note", 1, "# Source\nA body captured for generation."); sourceStore.put(source.ref(), source);
        WikiPage page = save("entity-lost-dependencies", snapshot(source));
        assertEquals(2, page.getDependencyVersion());
        dependencyStore.remove(page.getId());
        var fresh = service.freshness(page.getId());
        assertEquals(WikiDependencyService.Status.UNKNOWN, fresh.status());
        assertEquals(List.of("dependency_record_missing"), fresh.reasons());
        assertFalse(fresh.current()); assertFalse(fresh.legacy());
        assertEquals("unknown", service.evidenceView(page.getId()).get("status"));
        assertEquals(false, service.evidenceView(page.getId()).get("factVerified"));
    }

    @Test
    void changedTitleIsConservativelyStaleEvenIfBodyHashIsIdentical() {
        var source = input("quick_ref", 8, "A command body"); sourceStore.put(source.ref(), source);
        WikiPage page = save("entity-title", snapshot(source));
        sourceStore.put(source.ref(), new WikiDependencyService.SourceInput(source.type(), source.id(), "Renamed source", source.fullContent()));
        assertEquals(List.of("source_changed"), service.freshness(page.getId()).reasons());
    }

    @Test
    void partialOrCorruptedDependencyRowsAreUnknownAndCannotMasqueradeAsFresh() {
        var source = input("note", 1, longBody()); sourceStore.put(source.ref(), source);
        WikiPage page = save("entity-integrity", snapshot(source));
        List<WikiSourceDependency> saved = dependencyStore.get(page.getId());
        saved.remove(saved.size() - 1);
        assertEquals(List.of("dependency_record_invalid"), service.freshness(page.getId()).reasons());
        assertFalse(service.freshness(page.getId()).legacy());
        saved.get(0).setChunkText("An injected fake source sentence.");
        assertEquals(WikiDependencyService.Status.UNKNOWN, service.freshness(page.getId()).status());
        assertEquals(false, service.evidenceView(page.getId()).get("factVerified"));
    }

    @Test
    void captureReportsMissingSourcesAndRefusesSavingIncompleteMaterial() {
        var source = input("note", 1, "Existing body"); sourceStore.put(source.ref(), source);
        var missing = new WikiDependencyService.SourceRef("file", 4L);
        var snapshot = service.captureSnapshot(List.of(source.ref(), missing));
        assertEquals(List.of(missing), snapshot.missingSources()); assertFalse(snapshot.complete());
        assertThrows(IllegalArgumentException.class, () -> service.saveGenerated(page("entity-incomplete"), snapshot));
        verify(pages, never()).insert(any(WikiPage.class)); verify(dependencies, never()).deleteByPage(anyLong());
        var actualMaterial = WikiDependencyService.selectChunks(snapshot, List.of(new WikiDependencyService.ChunkRef("note", 1L, 0)));
        assertTrue(actualMaterial.complete(), "a source that did not enter generation is not a dependency");
    }

    @Test
    void replacingGenerationReplacesAllOldRowsAndBindsTheNewWikiRevision() {
        var one = input("note", 1, "Source one"); var two = input("file", 2, "Source two");
        sourceStore.put(one.ref(), one); sourceStore.put(two.ref(), two);
        WikiPage old = save("entity-replace", snapshot(one));
        WikiPage newer = page(old.getTopicKey()); newer.setContentMd("A new guide from another source.");
        service.saveGenerated(newer, snapshot(two));
        assertEquals(old.getId(), newer.getId()); assertEquals(1, pageStore.size());
        List<WikiSourceDependency> rows = dependencyStore.get(old.getId());
        assertEquals(1, rows.size()); assertEquals("file", rows.get(0).getSourceType()); assertEquals(2L, rows.get(0).getSourceId());
        assertEquals(WikiDependencyService.sha256(newer.getContentMd()), rows.get(0).getPageMdHash());
        assertTrue(service.freshness(newer.getId()).current());
    }

    @Test
    void saveRejectsInventedOrTruncatedChunksBeforeAnyDatabaseWrite() {
        var source = input("note", 1, "# Definition\nThe entire raw source block.");
        var legitimate = snapshot(source);
        var original = legitimate.sources().get(0);
        var truncated = new WikiDependencyService.SourceSnapshot(original.ref(), original.title(), original.fullContent(), original.fullContentHash(), original.totalChunks(),
                List.of(new WikiDependencyService.SourceChunk(0, "Definition", "The entire", WikiDependencyService.sha256("The entire"))));
        var forged = new WikiDependencyService.Snapshot(legitimate.capturedAt(), List.of(truncated), List.of(), legitimate.hash());
        assertThrows(IllegalArgumentException.class, () -> service.saveGenerated(page("entity-forged"), forged));
        verify(pages, never()).insert(any(WikiPage.class)); verify(dependencies, never()).lockTopic(anyString());
    }

    @Test
    void batchFreshnessKeepsEachPageIndependentAndDoesNotReadTheWholeLibrary() {
        var one = input("note", 1, "Current body"); var two = input("quick_ref", 2, "Original card");
        sourceStore.put(one.ref(), one); sourceStore.put(two.ref(), two);
        WikiPage current = save("entity-one", snapshot(one)); WikiPage stale = save("entity-two", snapshot(two));
        sourceStore.put(two.ref(), input("quick_ref", 2, "Changed card"));
        var result = service.freshness(List.of(current, stale));
        assertTrue(result.get(current.getId()).current()); assertEquals(WikiDependencyService.Status.STALE, result.get(stale.getId()).status());
        verify(sources).currentSources("note", List.of(1L)); verify(sources).currentSources("quick_ref", List.of(2L));
        verify(sources, never()).currentSources(eq("file"), anyList());
    }

    private WikiPage save(String key, WikiDependencyService.Snapshot snapshot) { return service.saveGenerated(page(key), snapshot); }
    private static WikiDependencyService.Snapshot snapshot(WikiDependencyService.SourceInput... sources) { return WikiDependencyService.fromSources(List.of(sources)); }
    private static WikiDependencyService.SourceInput input(String type, long id, String text) { return new WikiDependencyService.SourceInput(type, id, "Source " + id, text); }
    private static WikiPage page(String key) {
        WikiPage page = new WikiPage(); page.setTopicKey(key); page.setTopicType("entity"); page.setTitle("Generated guide");
        page.setContentMd("# Generated guide\nA model-produced statement."); return page;
    }
    private static String longBody() {
        return "# First section\n" + "ReAct reads an observation. ".repeat(15) + "\n\n# Second section\n"
                + "Planning changes the next step. ".repeat(15) + "\n\n# Third section\n" + "Other content from the same source. ".repeat(15);
    }
}
