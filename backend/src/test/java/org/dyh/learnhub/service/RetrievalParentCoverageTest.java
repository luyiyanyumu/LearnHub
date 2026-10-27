package org.dyh.learnhub.service;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** Synthetic integration contracts for adding an explicit source parent without replacing ranked evidence. */
class RetrievalParentCoverageTest {
    private static final String QUESTION = "Catalog collection method";
    private static final String PARENT_HEADING = "4.1 Catalog Collection";
    private final KnowledgeRetrievalService retrieval = mock(KnowledgeRetrievalService.class);
    private final GraphRagService graph = mock(GraphRagService.class);
    private final RetrievalContextService service = new RetrievalContextService(retrieval, graph);

    @Test
    void aNonAdjacentParentPreservesEveryRankedSeedAndItsExactProtectedBody() {
        var seed = file(1, 20, "Catalog server examples are listed here.");
        var other = file(2, 7, "Independent ranked evidence remains available.");
        String parentText = "Catalog collection method retains explicit filtering criteria.\n\n"
                + "```java\ncollect(catalog);\n```\n\n$$\nS = \\{x \\mid valid(x)\\}\n$$";
        var parent = parent(1, 2, parentText);

        var context = build(QUESTION, List.of(seed, other), Map.of(seed.key(), related(parent)), config(2));

        assertEquals(2, context.injectedHits().size());
        assertEquals(List.of("file:1", "file:2"), context.refs());
        assertTrue(context.text().contains(seed.text()));
        assertTrue(context.text().contains(other.text()));
        assertTrue(context.text().contains(parentText), "Code and formula parent content must remain whole");
        assertTrue(context.text().contains("【父小节片段 seq=2】"));
        assertTrue(context.text().contains("上级小节：" + PARENT_HEADING));
        var metadata = context.retrieved().getFirst();
        assertEquals(PARENT_HEADING, metadata.get("parentSectionHeading"));
        assertEquals(List.of(parent.key(), seed.key()), metadata.get("originalPassageKeys"));
        assertEquals(List.of(2, 20), metadata.get("expandedSeqs"));
        assertEquals(-1, metadata.get("seq"));
        assertEquals(context.injectedHits().getFirst().key(), metadata.get("passageKey"));
        assertEquals(List.of(
                Map.of("passageKey", parent.key(), "seq", 2, "chars", parentText.length(), "truncated", false),
                Map.of("passageKey", seed.key(), "seq", 20, "chars", seed.text().length(), "truncated", false)),
                metadata.get("memberExcerpts"));
        assertTrue(context.text().length() <= 7000);
    }

    @Test
    void aParentAndOrdinaryNeighborShareTheConfiguredExpansionQuota() {
        var seed = file(1, 20, "Catalog server examples.");
        var parent = parent(1, 2, "Catalog collection method and filtering criteria.");
        var neighbor = neighbor(1, 21, "Catalog method explains an additional example.");
        var related = Map.of(seed.key(), related(parent, neighbor));

        var one = build(QUESTION, List.of(seed), related, config(1));
        assertEquals(List.of(2, 20), one.retrieved().getFirst().get("expandedSeqs"));
        assertFalse(one.text().contains(neighbor.text()));

        var two = build(QUESTION, List.of(seed), related, config(2));
        assertEquals(List.of(2, 20, 21), two.retrieved().getFirst().get("expandedSeqs"));
        assertTrue(two.text().contains(parent.text()));
        assertTrue(two.text().contains(neighbor.text()));
        assertEquals(1, occurrences(two.text(), "【父小节片段 seq="));
    }

    @Test
    void atMostOneParentIsAddedAcrossAllSourcesInAnAnswer() {
        var first = file(1, 20, "First catalog seed.");
        var second = file(2, 30, "Second catalog seed.");
        var firstParent = parent(1, 2, "Catalog collection method for the first source.");
        var secondParent = parent(2, 3, "Catalog collection method for the second source.");

        var context = build(QUESTION, List.of(first, second), Map.of(
                first.key(), related(firstParent), second.key(), related(secondParent)), config(2));

        assertEquals(1, occurrences(context.text(), "【父小节片段 seq="));
        assertTrue(context.text().contains(firstParent.text()));
        assertFalse(context.text().contains(secondParent.text()));
        assertTrue(context.text().contains(first.text()));
        assertTrue(context.text().contains(second.text()));
        assertEquals(1, context.retrieved().stream().filter(item -> item.containsKey("parentSectionHeading")).count());
    }

    @Test
    void aParentAlreadyReservedAsASeedAndRepeatedKeysAreNotInjectedAgain() {
        var existing = file(1, 2, "Catalog collection method already appears as ranked evidence.");
        var child = file(1, 20, "Catalog child details.");
        var second = file(2, 30, "Second catalog seed.");
        var secondParent = parent(2, 3, "Catalog collection method from another source.");
        // The repeated child has exactly the same passage key and must not reserve another seed slot.
        var context = build(QUESTION, List.of(existing, child, child, second), Map.of(
                child.key(), related(parent(1, 2, existing.text())),
                second.key(), related(secondParent)), config(2));

        assertEquals(3, context.injectedHits().size());
        assertEquals(1, occurrences(context.text(), existing.text()));
        assertEquals(1, occurrences(context.text(), child.text()));
        assertEquals(1, occurrences(context.text(), "【父小节片段 seq="));
        assertEquals(existing.key(), context.retrieved().getFirst().get("passageKey"));
        assertFalse(context.retrieved().getFirst().containsKey("parentSectionHeading"));
        assertFalse(context.retrieved().get(1).containsKey("parentSectionHeading"));
        assertEquals(PARENT_HEADING, context.retrieved().get(2).get("parentSectionHeading"));
    }

    @Test
    void anOversizedParentIsOmittedWholeWhileAnOrdinaryNeighborCanStillFit() {
        var seed = file(1, 20, "Catalog seed.");
        String oversized = "Catalog collection method.\n```text\n" + "atomic source block ".repeat(20) + "\n```";
        var parent = parent(1, 2, oversized);
        var neighbor = neighbor(1, 21, "Catalog method neighbor remains useful.");

        var context = build(QUESTION, List.of(seed), Map.of(seed.key(), related(parent, neighbor)),
                config(2, 7000, 128));

        assertFalse(context.text().contains("atomic source block"));
        assertFalse(context.text().contains("【父小节片段 seq="));
        assertFalse(context.retrieved().getFirst().containsKey("parentSectionHeading"));
        assertEquals(List.of(seed.key(), neighbor.key()), context.retrieved().getFirst().get("originalPassageKeys"));
        assertEquals(List.of(20, 21), context.retrieved().getFirst().get("expandedSeqs"));
        assertTrue(context.text().contains(neighbor.text()));
    }

    @Test
    void totalBudgetRejectsTheWholeParentWithoutDroppingSeedsOrBlockingANeighbor() {
        var seed = file(1, 20, "Catalog seed evidence. " + "a".repeat(580));
        var other = file(2, 7, "Independent evidence. " + "b".repeat(580));
        var seeds = List.of(seed, other);
        var baseline = build(QUESTION, seeds, Map.of(), config(0));
        int total = baseline.text().length() + 200;
        var parent = parent(1, 2, "Catalog collection method. " + "p".repeat(700));
        var neighbor = neighbor(1, 21, "Catalog method nearby detail.");

        var context = build(QUESTION, seeds, Map.of(seed.key(), related(parent, neighbor)),
                config(2, total, 1200));

        assertEquals(2, context.injectedHits().size());
        assertTrue(context.text().contains(seed.text()));
        assertTrue(context.text().contains(other.text()));
        assertTrue(context.text().contains(neighbor.text()));
        assertFalse(context.text().contains("p".repeat(100)));
        assertFalse(context.text().contains("【父小节片段 seq="));
        assertFalse(context.retrieved().getFirst().containsKey("parentSectionHeading"));
        assertEquals(List.of(20, 21), context.retrieved().getFirst().get("expandedSeqs"));
        assertTrue(context.text().length() <= total);
    }

    @Test
    void malformedOrForeignParentCandidatesCannotEnterContext() {
        var seed = file(1, 20, "Catalog seed.");
        String rejected = "Catalog collection method must remain outside this context.";
        for (RetrievalHit bad : List.of(
                parent(99, 2, rejected),
                hit("note", 1, 2, rejected, List.of("section-parent"), List.of()),
                hit("file", 1, null, rejected, List.of("section-parent"), List.of()),
                parent(1, -1, rejected),
                parent(1, 20, rejected),
                parent(1, 21, rejected),
                hit("file", 1, 2, rejected, List.of("vector"), List.of()),
                hit("file", 1, 2, rejected, List.of("section-parent"), List.of("unverified relation")))) {
            var context = build(QUESTION, List.of(seed), Map.of(seed.key(), related(bad)), config(2));
            assertEquals(seed.text(), context.injectedHits().getFirst().text(), bad.toString());
            assertFalse(context.text().contains(rejected));
            assertFalse(context.retrieved().getFirst().containsKey("parentSectionHeading"));
            assertFalse(context.retrieved().getFirst().containsKey("originalPassageKeys"));
        }
    }

    @Test
    void graphSeedsAndNonFileSourcesNeverReceiveAParent() {
        String rejected = "Catalog collection method must remain outside this context.";
        for (String type : List.of("note", "quick_ref", "file")) {
            List<String> relations = "file".equals(type) ? List.of("a checked graph path") : List.of();
            var seed = hit(type, 1, 20, "Catalog seed.", List.of("vector"), relations);
            var candidate = hit(type, 1, 2, rejected, List.of("section-parent"), List.of());
            var context = build(QUESTION, List.of(seed), Map.of(seed.key(), related(candidate)), config(2));

            assertEquals(seed.text(), context.injectedHits().getFirst().text());
            assertFalse(context.text().contains(rejected));
            assertFalse(context.retrieved().getFirst().containsKey("parentSectionHeading"));
        }
    }

    @Test
    void unrelatedQueriesAndMissingParentTitlesDoNotSpendContextBudget() {
        var seed = file(1, 20, "A selected passage.");
        var parent = parent(1, 2, "Catalog collection method and filtering criteria.");
        var unrelated = build("Photosynthesis chlorophyll", List.of(seed),
                Map.of(seed.key(), related(parent)), config(2));
        assertEquals(seed.text(), unrelated.injectedHits().getFirst().text());
        assertFalse(unrelated.retrieved().getFirst().containsKey("parentSectionHeading"));

        var missingTitle = new KnowledgeRetrievalService.SectionNeighbors("", List.of(),
                new KnowledgeRetrievalService.SectionParent(" ", parent));
        var untitled = build(QUESTION, List.of(seed), Map.of(seed.key(), missingTitle), config(2));
        assertEquals(seed.text(), untitled.injectedHits().getFirst().text());
        assertFalse(untitled.retrieved().getFirst().containsKey("parentSectionHeading"));
    }

    private RetrievalContextService.Context build(String question, List<RetrievalHit> seeds,
                                                 Map<String, KnowledgeRetrievalService.SectionNeighbors> related,
                                                 KnowledgeBaseSettings.Config cfg) {
        when(retrieval.relatedSections(anyList(), eq(2))).thenReturn(related);
        var context = service.buildFromSearch(question, 8,
                new KnowledgeRetrievalService.SearchResult(seeds, List.of()), false, false, true, cfg);
        verifyNoInteractions(graph);
        return context;
    }

    private static KnowledgeBaseSettings.Config config(int neighbors) {
        return config(neighbors, 7000, 1200);
    }

    private static KnowledgeBaseSettings.Config config(int neighbors, int total, int passage) {
        Map<String, Object> values = new LinkedHashMap<>(KnowledgeBaseSettings.defaults().values());
        values.put("neighborCount", neighbors);
        values.put("totalChars", total);
        values.put("passageChars", passage);
        return new KnowledgeBaseSettings.Config(values);
    }

    private static KnowledgeRetrievalService.SectionNeighbors related(RetrievalHit parent,
                                                                     RetrievalHit... neighbors) {
        return new KnowledgeRetrievalService.SectionNeighbors("Catalog Examples", List.of(neighbors),
                new KnowledgeRetrievalService.SectionParent(PARENT_HEADING, parent));
    }

    private static RetrievalHit file(long id, Integer seq, String text) {
        return hit("file", id, seq, text, List.of("vector"), List.of());
    }

    private static RetrievalHit parent(long id, Integer seq, String text) {
        return hit("file", id, seq, text, List.of("section-parent"), List.of());
    }

    private static RetrievalHit neighbor(long id, int seq, String text) {
        return hit("file", id, seq, text, List.of("section-neighbor"), List.of());
    }

    private static RetrievalHit hit(String type, long id, Integer seq, String text,
                                    List<String> channels, List<String> relations) {
        return new RetrievalHit(type, id, "Synthetic source " + id, "", text, 1, seq, channels, relations);
    }

    private static int occurrences(String text, String needle) {
        int count = 0;
        for (int at = text.indexOf(needle); at >= 0; at = text.indexOf(needle, at + needle.length())) count++;
        return count;
    }
}
