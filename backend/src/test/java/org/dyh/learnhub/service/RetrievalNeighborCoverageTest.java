package org.dyh.learnhub.service;

import org.dyh.learnhub.service.rerank.QueryAwareExcerpt;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Synthetic retrieval windows exercise evidence coverage without depending on a private corpus. */
class RetrievalNeighborCoverageTest {
    private final KnowledgeRetrievalService retrieval = mock(KnowledgeRetrievalService.class);
    private final RetrievalContextService service = new RetrievalContextService(retrieval, mock(GraphRagService.class));

    @Test void aPrecedingDefinitionCompletesTheRemedyAndKeepsTheOtherRankedSeed() {
        String question = "Why does tokenCompare miss value cycling, and how do revisions help?";
        var remedy = seed(1, 7, "A revision counter records intervening updates and rejects an old revision.");
        var other = seed(2, 0, "Independent evidence: the retry policy stops after three unsuccessful attempts.");
        var definition = neighbor(1, 6, "tokenCompare compares only the current value. Value cycling can restore "
                + "the initial value after another writer changed it, hiding the intervening change.");
        String unrelatedCode = "```text\n" + "scheduler_fairness_log\n".repeat(36) + "```";
        var following = neighbor(1, 8, unrelatedCode);
        var seeds = List.of(remedy, other);
        when(retrieval.relatedSections(seeds, 2)).thenReturn(Map.of(remedy.key(), section("Update protocol", following, definition)));

        var context = build(question, seeds, config(1000, 2, 1000));

        assertEquals(List.of("note:1", "note:2"), context.refs());
        assertEquals(2, context.injectedHits().size());
        assertTrue(context.text().contains(definition.text()), "The explanation before a matched remedy is still evidence");
        assertTrue(context.text().contains(remedy.text()));
        assertTrue(context.text().contains(other.text()), "Expanding one source cannot remove an already reserved seed");
        assertFalse(context.text().contains(unrelatedCode));
        assertEquals(List.of(6, 7), context.retrieved().getFirst().get("expandedSeqs"));
        assertTrue(context.text().length() <= 1000);
    }

    @Test void allowingOneNeighborStillConsidersBothDirections() {
        String question = "Explain tokenCompare value cycling";
        var seed = seed(1, 4, "Use a revision field to reject an obsolete update.");
        var following = neighbor(1, 5, "Log rotation removes old diagnostic files.");
        var preceding = neighbor(1, 3, "tokenCompare cannot observe value cycling when the current value returns to its original form.");
        var seeds = List.of(seed);
        when(retrieval.relatedSections(seeds, 2)).thenReturn(Map.of(seed.key(), section("Update protocol", following, preceding)));

        var context = build(question, seeds, config(2000, 1, 1200));

        verify(retrieval).relatedSections(seeds, 2);
        assertTrue(context.text().contains(preceding.text()));
        assertFalse(context.text().contains(following.text()));
        assertEquals(List.of(3, 4), context.retrieved().getFirst().get("expandedSeqs"));
    }

    @Test void aFollowingImplementationStillCompletesAnEarlierDefinition() {
        String question = "How is registerListener implemented?";
        var seed = seed(1, 3, "The listener receives the startup event from the registry.");
        var following = neighbor(1, 4, "```java\nregistry.registerListener(new SyntheticListener());\n```");
        var preceding = neighbor(1, 2, "A registry is one component in the application lifecycle.");
        var seeds = List.of(seed);
        when(retrieval.relatedSections(seeds, 2)).thenReturn(Map.of(seed.key(), section("Event registry", following, preceding)));

        var context = build(question, seeds, config(2000, 1, 1200));

        assertTrue(context.text().contains(following.text()));
        assertFalse(context.text().contains(preceding.text()));
        assertEquals(List.of(3, 4), context.retrieved().getFirst().get("expandedSeqs"));
    }

    @Test void unstructuredSourcesOnlyExpandNeighborsContainingQuestionEvidence() {
        String question = "Explain revisionGuard and cycleDetection";
        var seed = seed(1, 8, "revisionGuard rejects an obsolete update.");
        var unrelated = neighbor(1, 9, "The photograph catalogue groups images by their capture date.");
        var relevant = neighbor(1, 7, "cycleDetection identifies a transition away from the original value and back again.");
        var seeds = List.of(seed);
        when(retrieval.relatedSections(seeds, 2)).thenReturn(Map.of(seed.key(), section("", unrelated, relevant)));

        var context = build(question, seeds, config(2000, 2, 1200));

        assertTrue(context.text().contains(relevant.text()));
        assertFalse(context.text().contains(unrelated.text()));
        assertEquals(List.of(7, 8), context.retrieved().getFirst().get("expandedSeqs"));
    }

    @Test void anUnrelatedUntitledNeighborDoesNotClaimExpandedEvidence() {
        var seed = seed(1, 2, "revisionGuard rejects an obsolete update.");
        var neighbor = neighbor(1, 3, "A short recipe lists the ingredients for the afternoon meal.");
        var seeds = List.of(seed);
        when(retrieval.relatedSections(seeds, 2)).thenReturn(Map.of(seed.key(), section(" ", neighbor)));

        var context = build("revisionGuard cycleDetection", seeds, config(2000, 2, 1200));

        assertEquals(seed.text(), context.injectedHits().getFirst().text());
        assertEquals(seed.key(), context.retrieved().getFirst().get("passageKey"));
        assertFalse(context.retrieved().getFirst().containsKey("expandedSeqs"));
        assertFalse(context.retrieved().getFirst().containsKey("memberExcerpts"));
    }

    @Test void newlyCoveredConceptWinsOverMoreMatchesAlreadyCoveredByTheSeed() {
        String question = "revisionGuard scheduling throughput backoff fairness coherence consistency";
        String covered = "scheduling throughput backoff fairness coherence consistency";
        var seed = seed(1, 5, covered);
        var repeated = neighbor(1, 6, (covered + ". ").repeat(3));
        var missing = neighbor(1, 4, "revisionGuard verifies the update generation.");
        var seeds = List.of(seed);
        assertTrue(QueryAwareExcerpt.relevance(question, repeated.text(), covered).total()
                > QueryAwareExcerpt.relevance(question, missing.text(), covered).total());
        when(retrieval.relatedSections(seeds, 2)).thenReturn(Map.of(seed.key(), section("Update protocol", repeated, missing)));

        var context = build(question, seeds, config(2000, 1, 1200));

        assertTrue(context.text().contains(missing.text()));
        assertFalse(context.text().contains(repeated.text()));
        assertEquals(List.of(4, 5), context.retrieved().getFirst().get("expandedSeqs"));
    }

    @Test void equalCoverageRetainsTheFollowingNeighborPreference() {
        String question = "revisionGuard";
        var seed = seed(1, 3, "The revisionGuard protocol has two examples.");
        var following = neighbor(1, 4, "revisionGuard: the following example uses an integer revision.");
        var preceding = neighbor(1, 2, "revisionGuard: the preceding example uses a stamped reference.");
        var seeds = List.of(seed);
        when(retrieval.relatedSections(seeds, 2)).thenReturn(Map.of(seed.key(), section("Examples", following, preceding)));

        var context = build(question, seeds, config(2000, 1, 1200));

        assertTrue(context.text().contains(following.text()));
        assertFalse(context.text().contains(preceding.text()));
        assertEquals(List.of(3, 4), context.retrieved().getFirst().get("expandedSeqs"));
    }

    @Test void relevanceCountsDistinctConceptsAndDoesNotRewardRepetition() {
        String question = "revisionGuard cycleDetection";
        var once = QueryAwareExcerpt.relevance(question, "revisionGuard", "");
        var repeated = QueryAwareExcerpt.relevance(question, "revisionGuard ".repeat(200), "");
        var complementary = QueryAwareExcerpt.relevance(question, "revisionGuard and cycleDetection", "");

        assertTrue(once.total() > 0);
        assertEquals(once.total(), repeated.total(), 0.000001);
        assertEquals(once.additional(), repeated.additional(), 0.000001);
        assertTrue(complementary.total() > repeated.total());
        var alreadyCovered = QueryAwareExcerpt.relevance(question, "revisionGuard cycleDetection", "REVISIONGUARD");
        assertEquals(complementary.total(), alreadyCovered.total(), 0.000001);
        assertTrue(alreadyCovered.additional() > 0);
        assertTrue(alreadyCovered.additional() < alreadyCovered.total());
    }

    @Test void asciiIdentifiersMatchWholeWordsAndIgnoreCase() {
        assertEquals(0, QueryAwareExcerpt.relevance("token revision", "tokenizer revisionary $token token_2", "").total());
        var exact = QueryAwareExcerpt.relevance("token revision", "TOKEN; Revision.", "token");
        assertTrue(exact.total() > 0);
        assertTrue(exact.additional() > 0);
        assertTrue(exact.additional() < exact.total());
        assertEquals(0, QueryAwareExcerpt.relevance("token", "token", "TOKEN").additional());
        assertEquals(0, QueryAwareExcerpt.relevance("how is the", "the following explanation", "").total());
    }

    @Test void completeCodeAndFormulaRemainExactInsideAnExpandedWindow() {
        var seed = seed(1, 3, "The revisionGuard invariant compares the revision together with the value.");
        var code = neighbor(1, 4, "```java\nboolean revisionGuard = expectedRevision == currentRevision;\n```");
        var formula = neighbor(1, 2, "The revisionGuard invariant is\n$$\n\\begin{aligned}\nr_{t+1} &= r_t + 1 \\\\\nv_{t+1} &= f(v_t)\n\\end{aligned}\n$$");
        var seeds = List.of(seed);
        when(retrieval.relatedSections(seeds, 2)).thenReturn(Map.of(seed.key(), section("Invariant", code, formula)));

        var context = build("revisionGuard invariant", seeds, config(3000, 2, 1200));

        assertTrue(context.text().contains(code.text()));
        assertTrue(context.text().contains(formula.text()));
        assertEquals(List.of(2, 3, 4), context.retrieved().getFirst().get("expandedSeqs"));
        @SuppressWarnings("unchecked")
        var excerpts = (List<Map<String, Object>>) context.retrieved().getFirst().get("memberExcerpts");
        assertTrue(excerpts.stream().allMatch(item -> Boolean.FALSE.equals(item.get("truncated"))));
    }

    @Test void protectedNeighborsThatCannotFitDoNotLeavePartialSyntaxOrMetadata() {
        List<String> bodies = List.of(
                "```java\n" + "boolean revisionGuard = true;\n".repeat(20) + "```",
                "$$\n\\begin{aligned}\n" + "revisionGuard_t &= revisionGuard_{t-1} + 1 \\\\\n".repeat(12) + "\\end{aligned}\n$$");
        for (String body : bodies) {
            var seed = seed(1, 2, "The revisionGuard invariant is defined in the adjacent block.");
            var neighbor = neighbor(1, 3, body);
            var seeds = List.of(seed);
            when(retrieval.relatedSections(seeds, 2)).thenReturn(Map.of(seed.key(), section("Invariant", neighbor)));

            var context = build("revisionGuard invariant", seeds, config(1000, 1, 128));

            assertEquals(seed.text(), context.injectedHits().getFirst().text());
            assertFalse(context.text().contains("```"));
            assertFalse(context.text().contains("$$"));
            assertFalse(context.retrieved().getFirst().containsKey("expandedSeqs"));
            assertFalse(context.retrieved().getFirst().containsKey("originalPassageKeys"));
            assertFalse(context.retrieved().getFirst().containsKey("memberExcerpts"));
            assertTrue(context.text().length() <= 1000);
        }
    }

    @Test void relevanceCannotPromoteAForeignOrNonAdjacentPassageIntoTheWindow() {
        var seed = seed(1, 4, "revisionGuard records the current revision.");
        var foreign = neighbor(99, 5, "revisionGuard cycleDetection foreign evidence");
        var distant = neighbor(1, 10, "revisionGuard cycleDetection distant evidence");
        var graph = new RetrievalHit("note", 1L, "Synthetic note 1", "", "revisionGuard cycleDetection graph evidence", 1,
                5, List.of("graph"), List.of("A —part_of→ B"));
        var seeds = List.of(seed);
        when(retrieval.relatedSections(seeds, 2)).thenReturn(Map.of(seed.key(), section("Update protocol", foreign, distant, graph)));

        var context = build("revisionGuard cycleDetection", seeds, config(2000, 2, 1200));

        assertEquals(seed.text(), context.injectedHits().getFirst().text());
        assertFalse(context.retrieved().getFirst().containsKey("expandedSeqs"));
        assertEquals(List.of("note:1"), context.refs());
    }

    private RetrievalContextService.Context build(String question, List<RetrievalHit> seeds, KnowledgeBaseSettings.Config cfg) {
        return service.buildFromSearch(question, seeds.size(), new KnowledgeRetrievalService.SearchResult(seeds, List.of()),
                false, false, true, cfg);
    }

    private static KnowledgeBaseSettings.Config config(int totalChars, int neighborCount, int passageChars) {
        return KnowledgeBaseSettings.validatePatch(KnowledgeBaseSettings.defaults(), Map.of(
                "totalChars", totalChars, "neighborCount", neighborCount, "passageChars", passageChars,
                "wikiChars", 0, "globalGraphChars", 0));
    }

    private static KnowledgeRetrievalService.SectionNeighbors section(String heading, RetrievalHit... neighbors) {
        return new KnowledgeRetrievalService.SectionNeighbors(heading, List.of(neighbors));
    }

    private static RetrievalHit seed(long id, int seq, String body) {
        return new RetrievalHit("note", id, "Synthetic note " + id, "", body, 1, seq, List.of("vector"), List.of());
    }

    private static RetrievalHit neighbor(long id, int seq, String body) {
        return new RetrievalHit("note", id, "Synthetic note " + id, "", body, 1, seq,
                List.of("vector", "section-neighbor"), List.of());
    }
}
