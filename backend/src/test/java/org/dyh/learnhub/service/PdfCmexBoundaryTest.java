package org.dyh.learnhub.service;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * A 9.5 KB fixture contains only a reused CMEX font subset and new test text.
 * It preserves the original top-origin delimiters, which PDFBox previously grouped
 * into "con(tinuation" and "trajecto)ry" on the preceding prose baseline.
 */
class PdfCmexBoundaryTest {
    @Test
    void topOriginDelimitersBelongToTheDisplayEquationRatherThanThePreviousSentence() throws Exception {
        var resource = getClass().getResource("/pdf/cmex-display-boundaries.pdf");
        assertNotNull(resource);
        var layout = new PdfLayoutExtractor().extract(Path.of(resource.toURI()));
        assertEquals("ok", layout.status(), layout.error());
        var blocks = layout.pages().stream().filter(p -> p.page() == 2).findFirst().orElseThrow().blocks();
        var formulas = blocks.stream().filter(b -> "formula".equals(b.type())).toList();
        assertEquals(1, formulas.size(), blocks.toString());
        var formula = formulas.get(0);
        assertTrue(formula.text().contains("(6)") && formula.text().contains("k+1"), formula.text());
        assertEquals(5, formula.text().codePoints().filter(c -> c == '\'' || c == '′').count(), formula.text());
        assertTrue(formula.text().chars().filter(c -> c == '(').count() >= 3, formula.text());
        assertTrue(formula.text().chars().filter(c -> c == ')').count() >= 3, formula.text());
        double[] rect = formula.rect();
        assertNotNull(rect);
        assertTrue(rect[0] <= 113.375 && rect[2] >= 292.503, "Crop must include the whole equation and its number");
        assertTrue(rect[1] > 515.3 && rect[1] <= 520.276, "Crop must include primes and exclude the prose above");
        assertTrue(rect[3] >= 534.051, "Crop must include the large delimiters and the lowered k+1");
        String prose = blocks.stream().filter(b -> "para".equals(b.type()))
                .map(PdfLayoutExtractor.Block::text).filter(t -> t.contains("producing a new"))
                .findFirst().orElseThrow();
        assertTrue(prose.contains("producing a new continuation of the trajectory:"), prose);
        assertFalse(prose.contains("con(tinuation") || prose.contains("trajecto)ry"), prose);
        assertFalse(prose.contains("(6)") || prose.contains("k+1"), prose);
        assertFalse(formula.text().contains("producing") || formula.text().contains("Figure one"), formula.text());
    }
}
