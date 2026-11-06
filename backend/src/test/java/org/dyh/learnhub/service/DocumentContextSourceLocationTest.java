package org.dyh.learnhub.service;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Synthetic page ranges exercise the final injected text, without opening a file or an external service. */
class DocumentContextSourceLocationTest {
    private static final long FILE = 31L;
    private final KnowledgeRetrievalService retrieval = mock(KnowledgeRetrievalService.class);
    private final FileStorageService files = mock(FileStorageService.class);
    private final RetrievalContextService service = new RetrievalContextService(retrieval, mock(GraphRagService.class));

    @Test void finalLocationsIncludeTheParentAndNeighborAndMatchBothRenderedAndMetadataPages() {
        String question = "Catalog method";
        String parentBody = "Catalog method starts by checking the source collection criteria.";
        String seedBody = "Catalog method selects the candidate evidence.";
        String neighborBody = "Catalog method verifies the neighboring evidence before answering.";
        var extraction = extraction(parentBody, seedBody, neighborBody);
        locateAgainst(extraction);
        var seed = file(7, seedBody, List.of("vector"), List.of(location(99)));
        var parent = file(1, parentBody, List.of("section-parent"), List.of());
        var neighbor = file(8, neighborBody, List.of("section-neighbor"), List.of());
        when(retrieval.relatedSections(anyList(), eq(2))).thenReturn(Map.of(seed.key(),
                new KnowledgeRetrievalService.SectionNeighbors("Catalog", List.of(neighbor),
                        new KnowledgeRetrievalService.SectionParent("Catalog method", parent))));

        var context = build(question, seed, config(3000, 2, 1200), true);

        var hit = context.injectedHits().getFirst();
        assertEquals(Set.of(1, 2, 3), pages(hit.sourceLocations()));
        assertTrue(hit.text().contains(parentBody));
        assertTrue(hit.text().contains(seedBody));
        assertTrue(hit.text().contains(neighborBody));
        assertEquals(hit.sourceLocations(), context.retrieved().getFirst().get("sourceLocations"));
        assertPageLabelMatches(context, hit);
        ArgumentCaptor<String> body = ArgumentCaptor.forClass(String.class);
        verify(files).sourceLocations(eq(FILE), body.capture(), eq(question));
        assertEquals(hit.text(), body.getValue(), "position lookup must receive exactly the injected window");
        assertTrue(context.text().length() <= 3000);
    }

    @Test void clippingReplacesSeedCoordinatesWithOnlyThePagesOfTheActualExcerpt() {
        String question = "NeedleMarker";
        String before = "Aspen unrelated introduction. ".repeat(50);
        String selected = "Cedar context. ".repeat(30) + "NeedleMarker is the synthetic evidence being requested. "
                + "Cedar context. ".repeat(30);
        String after = "Birch unrelated appendix. ".repeat(50);
        var extraction = extraction(before, selected, after);
        locateAgainst(extraction);
        var seed = file(0, extraction.text(), List.of("vector"),
                List.of(location(1), location(2), location(3), location(99)));

        var context = build(question, seed, config(1500, 0, 128), true);

        var hit = context.injectedHits().getFirst();
        assertTrue(hit.text().contains(question));
        assertTrue(hit.text().length() <= 128);
        assertFalse(hit.text().contains("Aspen"));
        assertFalse(hit.text().contains("Birch"));
        assertEquals(Set.of(2), pages(hit.sourceLocations()));
        assertEquals(hit.sourceLocations(), context.retrieved().getFirst().get("sourceLocations"));
        assertPageLabelMatches(context, hit);
        verify(files).sourceLocations(FILE, hit.text(), question);
    }

    @Test void unavailableLocationLookupKeepsTextButCannotReuseOldSeedCoordinates() {
        service.setFileStorage(files);
        when(files.sourceLocations(eq(FILE), anyString(), anyString())).thenThrow(new IllegalStateException("synthetic lookup failure"));
        var seed = file(0, "Synthetic evidence remains usable when coordinates are unavailable.",
                List.of("vector"), List.of(location(99)));

        var context = build("Synthetic evidence", seed, config(1500, 0, 1200), true);

        assertEquals(seed.text(), context.injectedHits().getFirst().text());
        assertNoLocations(context);
        assertTrue(context.text().length() <= 1500);
    }

    @Test void oldConstructorAndSyntheticDiagnosticsNeverReadFilesOrRetainUnverifiedCoordinates() {
        var seed = file(0, "Synthetic source text.", List.of("vector"), List.of(location(99)));
        var withoutService = build("Synthetic", seed, config(1500, 0, 1200), true);
        assertNoLocations(withoutService);
        service.setFileStorage(files);

        var synthetic = build("Synthetic", seed, config(1500, 0, 1200), false);

        assertNoLocations(synthetic);
        verifyNoInteractions(files, retrieval);
    }

    @Test void pageLabelsAreReservedBeforeSelectionAndCannotOverflowTheTotalContextBudget() {
        String body = "Synthetic budget evidence is retained as one complete passage. ".repeat(24);
        var seed = file(0, body, List.of("vector"), List.of());
        var baseline = build("budget", seed, config(7000, 0, body.length()), true);
        int tightBudget = baseline.text().length() + 1;
        service.setFileStorage(files);
        List<DocumentExtractionStore.SourceLocation> locations = IntStream.rangeClosed(101, 112)
                .mapToObj(DocumentContextSourceLocationTest::location).toList();
        when(files.sourceLocations(eq(FILE), anyString(), eq("budget"))).thenReturn(locations);

        var tight = build("budget", seed, config(tightBudget, 0, body.length()), true);
        assertTrue(tight.text().length() <= tightBudget);
        assertTrue(tight.injectedHits().isEmpty(), "do not reserve a passage that has no room for verified page labels");
        verifyNoInteractions(files);

        var roomy = build("budget", seed, config(baseline.text().length() + 200, 0, body.length()), true);
        var hit = roomy.injectedHits().getFirst();
        assertEquals(12, hit.sourceLocations().size());
        assertEquals(hit.sourceLocations(), roomy.retrieved().getFirst().get("sourceLocations"));
        assertPageLabelMatches(roomy, hit);
        assertTrue(roomy.text().length() <= baseline.text().length() + 200);
    }

    @Test void visionTranscriptionsCarryAnExplicitSourceNoticeWithinTheReservedBudget() {
        String body = "Synthetic visual Cedar description and restored formula.";
        service.setFileStorage(files);
        var visual = new DocumentExtractionStore.SourceLocation(2,new double[]{10,20,300,80},600,800,
                "px","visual",null,"vision","a".repeat(64));
        when(files.sourceLocations(eq(FILE),anyString(),eq("Cedar"))).thenReturn(List.of(visual));
        var context=build("Cedar",file(0,body,List.of("keyword"),List.of()),config(1500,0,1200),true);
        assertEquals(List.of(visual),context.injectedHits().getFirst().sourceLocations());
        assertTrue(context.text().contains("提取来源：含图片模型转写或图像描述"));
        assertTrue(context.text().length()<=1500);
    }

    private void locateAgainst(DocumentIngestionService.Extraction extraction) {
        service.setFileStorage(files);
        when(files.sourceLocations(eq(FILE), anyString(), anyString())).thenAnswer(call ->
                DocumentExtractionStore.locate(extraction, call.getArgument(1), call.getArgument(2)));
    }

    private RetrievalContextService.Context build(String question, RetrievalHit seed, KnowledgeBaseSettings.Config config,
                                                 boolean expand) {
        return service.buildFromSearch(question, 1,
                new KnowledgeRetrievalService.SearchResult(List.of(seed), List.of()), false, false, expand, config);
    }

    private static KnowledgeBaseSettings.Config config(int total, int neighbors, int passage) {
        return KnowledgeBaseSettings.validatePatch(KnowledgeBaseSettings.defaults(), Map.of(
                "totalChars", total, "neighborCount", neighbors, "passageChars", passage,
                "wikiChars", 0, "globalGraphChars", 0));
    }

    private static RetrievalHit file(int seq, String text, List<String> channels,
                                     List<DocumentExtractionStore.SourceLocation> locations) {
        return new RetrievalHit("file", FILE, "Synthetic source", "", text, 1, seq, channels, List.of(), locations);
    }

    private static DocumentExtractionStore.SourceLocation location(int page) {
        return new DocumentExtractionStore.SourceLocation(page, new double[]{10, 20, 300, 60}, 600, 800,
                "pt", "p" + page + "-b1", null, "text", "a".repeat(64));
    }

    private static DocumentIngestionService.Extraction extraction(String... textByPage) {
        String body = String.join("\n\n", textByPage);
        List<DocumentIngestionService.Page> pages = new ArrayList<>();
        int offset = 0;
        for (int i = 0; i < textByPage.length; i++) {
            String text = textByPage[i];
            var block = new DocumentIngestionService.Block("p" + (i + 1) + "-b1", "paragraph", text, text,
                    i + 1, new double[]{10, 20, 300, 60}, 600, 800, null, "accepted", true, offset, offset + text.length());
            pages.add(new DocumentIngestionService.Page(i + 1, 600, 800, "pt", "text", List.of(block)));
            offset += text.length() + 2;
        }
        return new DocumentIngestionService.Extraction("ok", body, body.length(), null, "a".repeat(64),
                DocumentIngestionService.PIPELINE_VERSION, pages,
                new DocumentIngestionService.QualityReport(pages.size(), pages.size(), 0, 0, null, List.of(), false, false));
    }

    private static Set<Integer> pages(List<DocumentExtractionStore.SourceLocation> locations) {
        return locations.stream().map(DocumentExtractionStore.SourceLocation::page).collect(Collectors.toSet());
    }

    private static void assertPageLabelMatches(RetrievalContextService.Context context, RetrievalHit hit) {
        String labels = hit.sourceLocations().stream().map(location -> String.valueOf(location.page())).distinct()
                .collect(Collectors.joining("、"));
        assertTrue(context.text().contains("\n原文页码：" + labels + "\n"));
    }

    private static void assertNoLocations(RetrievalContextService.Context context) {
        assertTrue(context.injectedHits().getFirst().sourceLocations().isEmpty());
        assertFalse(context.retrieved().getFirst().containsKey("sourceLocations"));
        assertFalse(context.text().contains("原文页码："));
    }
}
