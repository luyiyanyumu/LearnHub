package org.dyh.learnhub.service;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class TextChunkerStructureTest {
    private static final TextChunker.Options SMALL = new TextChunker.Options(100, 30, 140);

    @Test void longerFenceRequiresSameMarkerAndAtLeastOpeningLength() {
        String code = "````markdown\n# kept inside code\n```java\n~~~\n"
                + "synthetic code line\n".repeat(20) + "````";
        var units = TextChunker.structuralUnits("# Manual\n" + code + "\n## Usage\nNormal text.", SMALL);
        assertEquals(2, units.size());
        // The four-backtick fence includes the inner triple fence and tilde line intact.
        assertEquals(code, units.get(0).text());
        assertTrue(units.get(0).protectedBlock());
        assertEquals("Manual", units.get(0).heading());
        assertEquals("Manual > Usage", units.get(1).heading());
    }

    @Test void oversizedCodeRemainsOneStandaloneChunkAndKeepsIndentation() {
        String code = "  ~~~~~python\n  # this is code\n\n" + "  print('synthetic')\n".repeat(25) + "  ~~~~~~";
        var chunks = TextChunker.splitWithHeadings("# Python\nBefore.\n\n" + code + "\n\nAfter.", SMALL);
        assertEquals(3, chunks.size());
        assertEquals(code, chunks.get(1).text());
        assertTrue(chunks.get(1).text().length() > SMALL.max());
        assertTrue(chunks.stream().allMatch(chunk -> chunk.heading().equals("Python")));
        assertEquals("Before.", chunks.getFirst().text());
        assertEquals("After.", chunks.getLast().text());
    }

    @Test void displayMathDelimitersAndEnvironmentsAreAtomic() {
        List<String> formulas = List.of(
                "$$\n# mathematical symbol, not heading\n" + "a_{n+1}=a_n+1\\\\\n".repeat(15) + "$$",
                "\\[\n" + "x+y=z\\\\\n".repeat(20) + "\\]",
                "\\begin{align*}\n" + "a &= b + c \\\\\n".repeat(20) + "\\end{align*}");
        for (String formula : formulas) {
            String text = "# Derivation\nDefinition.\n\n" + formula + "\n\nExplanation.";
            var units = TextChunker.structuralUnits(text, SMALL);
            assertEquals(3, units.size());
            assertEquals(formula, units.get(1).text());
            assertTrue(units.get(1).protectedBlock());
            assertTrue(units.stream().allMatch(unit -> unit.heading().equals("Derivation")));
            assertEquals(1, TextChunker.splitWithHeadings(text, SMALL).stream()
                    .filter(chunk -> chunk.text().equals(formula)).count());
        }
    }

    @Test void markdownTablePreservesHeaderDelimiterAndEveryRow() {
        String table = "| 参数 | 含义 |\n| :--- | ---: |\n"
                + "| synthetic | example value |\n".repeat(20);
        table = table.stripTrailing();
        var chunks = TextChunker.splitWithHeadings("# Parameters\nIntro.\n\n" + table + "\n\nFollow-up.", SMALL);
        String expected = table;
        assertEquals(1, chunks.stream().filter(chunk -> chunk.text().equals(expected)).count());
        assertEquals(3, chunks.size());
        assertTrue(chunks.stream().allMatch(chunk -> chunk.heading().equals("Parameters")));
    }

    @Test void markdownTableContainingDisplayMathInItsHeaderStaysOneProtectedContainer() {
        for (String formula : List.of("$$x+y$$", "\\[x+y\\]", "\\begin{equation}x+y\\end{equation}")) {
            String table = "| " + formula + " | Meaning |\n| --- | --- |\n"
                    + "| synthetic | example value |\n".repeat(20).stripTrailing();
            var units = TextChunker.structuralUnits("# Table\n" + table + "\n\n## End\nAfter.", SMALL);
            assertEquals(2, units.size());
            assertEquals(new TextChunker.Unit("Table", table, true), units.getFirst());
            assertEquals("Table > End", units.getLast().heading());
            assertEquals(table, TextChunker.splitWithHeadings("# Table\n" + table, SMALL).getFirst().text());
        }
    }

    @Test void htmlTableContainingDisplayMathOnItsOpeningLineStaysOneProtectedContainer() {
        for (String formula : List.of("$$x+y$$", "\\[x+y\\]")) {
            String table = "<table><caption>" + formula + "</caption>\n"
                    + "<tr><td>synthetic</td></tr>\n".repeat(20) + "</table>";
            var units = TextChunker.structuralUnits("# Table\n" + table + "\n\n## End\nAfter.", SMALL);
            assertEquals(2, units.size());
            assertEquals(new TextChunker.Unit("Table", table, true), units.getFirst());
            assertEquals("Table > End", units.getLast().heading());
            var chunks = TextChunker.splitWithHeadings(table, SMALL);
            assertEquals(1, chunks.size());
            assertEquals(table, chunks.getFirst().text());
        }
    }

    @Test void htmlTableAndUnfinishedFenceDoNotLeakHeadings() {
        String table = "<table>\n<tr><td>" + "synthetic".repeat(30) + "</td></tr>\n# still table\n</table>";
        var htmlUnits = TextChunker.structuralUnits("# Report\n" + table + "\n## End\nDone.", SMALL);
        assertEquals(table, htmlUnits.getFirst().text());
        assertTrue(htmlUnits.getFirst().protectedBlock());
        assertEquals("Report > End", htmlUnits.getLast().heading());
        String unfinished = "```sh\n# never a heading\necho synthetic";
        var draft = TextChunker.structuralUnits("# Draft\n" + unfinished, SMALL);
        assertEquals(List.of(new TextChunker.Unit("Draft", unfinished, true)), draft);
    }

    @Test void overlapMayRepeatAnEntireAtomicBlockButNeverAnAtomicSuffix() {
        String code = "```js\nconst synthetic = 1;\n```";
        String table = "| A | B |\n| --- | --- |\n| X | Y |";
        String formula = "$$\nx=1\n$$";
        for (String atomic : List.of(code, table, formula)) {
            var chunks = TextChunker.splitWithHeadings("# Topic\n" + "前文".repeat(30) + "\n\n"
                    + atomic + "\n\n" + "后文".repeat(50), SMALL);
            assertTrue(chunks.stream().anyMatch(chunk -> chunk.text().contains(atomic)));
            for (var chunk : chunks) {
                // Every recognizable atomic delimiter in an output has the whole source block beside it.
                String marker = atomic.substring(0, atomic.indexOf('\n'));
                if (chunk.text().contains(marker)) assertTrue(chunk.text().contains(atomic));
                assertTrue(chunk.text().length() <= SMALL.max());
            }
        }
    }

    @Test void englishSentenceBoundariesDoNotTreatDecimalPointsAsFullStops() {
        String sentence = "The synthetic score is 3.14 and remains stable. ";
        var units = TextChunker.structuralUnits("# Results\n" + sentence.repeat(8), SMALL);
        assertTrue(units.size() > 1);
        assertTrue(units.stream().allMatch(unit -> unit.text().endsWith("stable.")));
        assertTrue(units.stream().allMatch(unit -> unit.text().contains("3.14")));
        assertTrue(units.stream().noneMatch(TextChunker.Unit::protectedBlock));
    }

    @Test void skippedHeadingLevelsKeepTheirActualAncestors() {
        var chunks = TextChunker.splitWithHeadings("# Root\n#### Deep\nA.\n### Mid\nB.\n# Other\nC.", SMALL);
        assertEquals(List.of("Root > Deep", "Root > Mid", "Other"), chunks.stream().map(TextChunker.Chunk::heading).toList());
    }
}
