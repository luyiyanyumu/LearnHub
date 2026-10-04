package org.dyh.learnhub.service;

import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class TextMathLayoutTest {
    private final TextMathLayout extractor = new TextMathLayout();
    private List<PdfLayoutExtractor.Block> blocks(String source) {
        return extractor.extract(source).pages().getFirst().blocks();
    }

    @Test void keepsInlineMathAndRestoresAuthoredDisplayEquations() {
        var result = blocks("# Mathematics\n\nEnergy $E=mc^2$ stays inline.\n\n$$\n\\frac{a}{b} + \\sqrt{x}\n$$  \n\nAfter the formula.");
        assertEquals(List.of("heading", "para", "formula", "para"), result.stream().map(PdfLayoutExtractor.Block::type).toList());
        assertEquals("Energy $E=mc^2$ stays inline.", result.get(1).text());
        assertEquals("\\frac{a}{b} + \\sqrt{x}", result.get(2).latex());
        assertEquals("restored", result.get(2).latexStatus());
        assertEquals("After the formula.", result.get(3).text());
    }

    @Test void codeAndMoneyAreNeverConvertedIntoFormulaBlocks() {
        var result = blocks("Costs $20 and $30.\n\n```python\n\nexpression = '$$x$$'\n```\n\n~~~text\n\\[code\\]\n~~~");
        assertEquals(List.of("para", "code", "code"), result.stream().map(PdfLayoutExtractor.Block::type).toList());
        assertEquals("Costs $20 and $30.", result.getFirst().text());
        assertEquals("\nexpression = '$$x$$'", result.get(1).text());
        assertTrue(result.stream().allMatch(block -> block.latex() == null));
    }

    @Test void recognizesBracketAndSingleLineDisplayMath() {
        var result = blocks("$$x_i^{2}$$\n\n\\[\n\\sum_{i=1}^{n}x_i\n\\]\n");
        assertEquals(2, result.size());
        assertEquals("x_i^{2}", result.get(0).latex());
        assertEquals("\\sum_{i=1}^{n}x_i", result.get(1).latex());
    }

    @Test void unclosedMathKeepsFollowingProseAndHeading() {
        var result = blocks("$$ unfinished\n\nFollowing prose.\n\n## Still a heading\n\nAn equation later: $a=b$.");
        assertTrue(result.stream().noneMatch(block -> "formula".equals(block.type())));
        String all = result.stream().map(PdfLayoutExtractor.Block::text).reduce("", (a, b) -> a + "\n" + b);
        assertTrue(all.contains("$$ unfinished"));
        assertTrue(all.contains("Following prose."));
        assertTrue(result.stream().anyMatch(block -> block.type().equals("heading") && block.text().equals("Still a heading")));
    }

    @Test void emptyTextReturnsNoSyntheticBody() {
        var result = extractor.extract("  \n\n");
        assertEquals("empty", result.status());
        assertTrue(result.pages().isEmpty());
    }

    @Test void indentedCodeAndFencesCannotCloseAnUnfinishedEquation() {
        var indented = blocks("    $$x^2$$\n    \\[literal\\]\n\nText.");
        assertEquals(List.of("code", "para"), indented.stream().map(PdfLayoutExtractor.Block::type).toList());
        assertEquals("$$x^2$$\n\\[literal\\]", indented.getFirst().text());
        var fenced = blocks("$$ unfinished\n```text\n$$\n```\n\nAfter.");
        assertEquals(List.of("para", "code", "para"), fenced.stream().map(PdfLayoutExtractor.Block::type).toList());
        assertTrue(fenced.stream().noneMatch(block -> "formula".equals(block.type())));
    }
}
