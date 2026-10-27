package org.dyh.learnhub.service;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SourceSectionOutlineTest {
    @Test
    void resolvesTheActualParentOpeningAcrossChunks() {
        var parents = SourceSectionOutline.parents(chunks(
                "7 ENGINE OVERVIEW\n7.2 Revision Design\nA method introduction.\n7.2.1 Local Tokens.",
                "The first subsection body.",
                "7.2.2 Remote Tokens.",
                "The second subsection body."));

        assertEquals(new SourceSectionOutline.Parent(0, "7.2 Revision Design"), parents.get(3));
        assertEquals(parents.get(3), parents.get(1));
        assertFalse(parents.containsKey(0), "Never return the seed itself as its context parent");
        assertThrows(UnsupportedOperationException.class,
                () -> parents.put(10, new SourceSectionOutline.Parent(0, "changed")));
    }

    @Test
    void recognizesHeadingsInsideAChunkAndUsesTheNearestVerifiedAncestor() {
        var parents = SourceSectionOutline.parents(chunks(
                "Opening prose.\n\n4 OVERVIEW\n4.1 Design Choices\n4.1.1 First Branch",
                "4.1.1.1 First Detail\nExplanation.",
                "4.1.1.2 Second Detail\nExplanation.",
                "More detail.",
                "4.1.2 Second Branch\nConclusion."));

        assertEquals(new SourceSectionOutline.Parent(0, "4.1.1 First Branch"), parents.get(3));
        assertEquals(new SourceSectionOutline.Parent(0, "4.1 Design Choices"), parents.get(4));
    }

    @Test
    void overlappingHeadingsKeepTheirFirstOpeningPosition() {
        var parents = SourceSectionOutline.parents(chunks(
                "4 OVERVIEW\n4.1 Design Choices\n4.1.1 First Branch",
                "4 OVERVIEW\n4.1 Design Choices\n4.1.1 First Branch\nBody.",
                "4.1.1 First Branch\nBody.\n4.1.2 Second Branch",
                "More body."));

        assertEquals(new SourceSectionOutline.Parent(0, "4.1 Design Choices"), parents.get(3));
        assertEquals(new SourceSectionOutline.Parent(0, "4.1 Design Choices"), parents.get(1));
    }

    @Test
    void aNewRootOrDisconnectedSectionClearsThePreviousAncestry() {
        List<String> opening = List.of("4 OVERVIEW\n4.1 Design Choices\n4.1.1 First Branch",
                "4.1.2 Second Branch", "Body.");
        for (String barrier : List.of("5 NEXT CHAPTER", "5.2 New Topic", "4.2 New Sibling")) {
            var parents = SourceSectionOutline.parents(chunks(opening.get(0), opening.get(1),
                    opening.get(2), barrier, "A later unrelated paragraph."));
            assertTrue(parents.containsKey(2));
            assertFalse(parents.containsKey(3));
            assertFalse(parents.containsKey(4));
        }
    }

    @Test
    void incompleteOrUnorderedHierarchiesDoNotAuthorizeAnOpening() {
        for (List<TextChunker.Chunk> sample : List.of(
                chunks("4.1 Design Choices\n4.1.1 First Branch\n4.1.2 Second Branch", "Body."),
                chunks("4 OVERVIEW\n4.1.1 First Branch\n4.1.2 Second Branch", "Body."),
                chunks("4 OVERVIEW\n4.1 Design Choices\n4.1.1 First Branch", "Body."),
                chunks("4 OVERVIEW\n4.1 Design Choices\n4.1.2 Second Branch\n4.1.1 First Branch", "Body."),
                chunks("4 OVERVIEW\n4.1 Design Choices\n4.1.1 First Branch\n4.1.1 First Branch", "Body."))) {
            assertTrue(SourceSectionOutline.parents(sample).isEmpty());
        }
    }

    @Test
    void decimalsPageNumbersAndNumberedListsAreNotAnOutline() {
        assertTrue(SourceSectionOutline.parents(chunks(
                "3.14 Measurement Result\n3.15 Another Result\n42\nPage 43",
                "1. First item\n2. Second item\n3.14 is a decimal value.",
                "4 OVERVIEW\n4.1 A long sentence, which should not be a section.\n4.1.1 Child One\n4.1.2 Child Two",
                "Trailing text.")).isEmpty());
    }

    @Test
    void protectedContentCannotSupplyParentOrChildren() {
        for (String protectedText : List.of(
                "```text\n4.1 Design Choices\n4.1.1 First Branch\n4.1.2 Second Branch\n```",
                "$$\n4.1 Design Choices\n4.1.1 First Branch\n4.1.2 Second Branch\n$$",
                "\\[\n4.1 Design Choices\n4.1.1 First Branch\n4.1.2 Second Branch\n\\]",
                "\\begin{align}\n4.1 Design Choices\n4.1.1 First Branch\n4.1.2 Second Branch\n\\end{align}",
                "<table>\n4.1 Design Choices\n4.1.1 First Branch\n4.1.2 Second Branch\n</table>",
                "| Header | Value |\n| --- | --- |\n| 4.1 Design Choices | Data |\n| 4.1.1 First Branch | Data |\n| 4.1.2 Second Branch | Data |")) {
            assertTrue(SourceSectionOutline.parents(chunks("4 OVERVIEW", protectedText, "Body.")).isEmpty());
        }
    }

    @Test
    void protectedNumberedContentDoesNotEndTheRealActiveSection() {
        var parents = SourceSectionOutline.parents(chunks(
                "4 OVERVIEW\n4.1 Design Choices\n4.1.1 First Branch\n4.1.2 Second Branch",
                "```text\n5 OTHER CHAPTER\n5.1 Misleading Numbers\n```",
                "Continued body."));
        assertEquals(new SourceSectionOutline.Parent(0, "4.1 Design Choices"), parents.get(2));
    }

    @Test
    void existingHeadingMetadataAlwaysWins() {
        var input = List.of(new TextChunker.Chunk("Known section", "4 OVERVIEW\n4.1 Design Choices"),
                new TextChunker.Chunk("", "4.1.1 First Branch\n4.1.2 Second Branch"));
        assertEquals(Map.of(), SourceSectionOutline.parents(input));
    }

    @Test
    void supportsShortChineseTitlesAndNonConsecutiveOrderedChildren() {
        var parents = SourceSectionOutline.parents(chunks(
                "4 资料概览\n4.1 研究方法\n4.1.2 数据来源", "4.1.4 筛选流程", "后续说明。"));
        assertEquals(new SourceSectionOutline.Parent(0, "4.1 研究方法"), parents.get(2));
    }

    private static List<TextChunker.Chunk> chunks(String... bodies) {
        return java.util.Arrays.stream(bodies).map(text -> new TextChunker.Chunk("", text)).toList();
    }
}
