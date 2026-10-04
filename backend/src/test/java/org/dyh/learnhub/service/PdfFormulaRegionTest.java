package org.dyh.learnhub.service;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class PdfFormulaRegionTest {
    private static PdfLayoutExtractor.Line line(double x0, double x1, double y, double size, String text) {
        return new PdfLayoutExtractor.Line(x0, x1, y, size, size, false, false, 0, text);
    }

    @Test
    void reconnectsPrimesAndTupleFragmentsAlongOneDisplayBaseline() {
        var main = line(100, 145, 250, 10, "τ′ = (c′k, s");
        var rows = PdfLayoutExtractor.reconnectMathRows(List.of(
                line(145, 148, 246, 6, "′"), main,
                line(145, 166, 253, 10, "k), u"),
                line(166, 170, 246, 6, "′"),
                line(166, 186, 253, 10, "k, o"),
                line(186, 265, 250, 10, "k+1,...). (6)")));
        assertEquals(1, rows.size());
        assertEquals(100, rows.getFirst().x0());
        assertEquals(265, rows.getFirst().x1());
        assertTrue(PdfLayoutExtractor.mathish(rows.getFirst().text()));
    }

    @Test
    void inlinePrimeReturnsToItsProseRowAndDoesNotBecomeParagraphPrefix() {
        var main = line(50, 280, 200, 10, "The agent resumes execution from (c′k, s");
        var rows = PdfLayoutExtractor.reconnectMathRows(List.of(line(280, 283, 196, 6, "′"),
                main, line(280, 292, 203, 10, "k),")));
        assertEquals(1, rows.size());
        assertTrue(rows.getFirst().text().startsWith("The agent"));
        assertTrue(rows.getFirst().text().endsWith("s ′ k),"));
        assertFalse(PdfLayoutExtractor.mathish(rows.getFirst().text()));
    }

    @Test
    void doesNotReachAcrossWhitespaceToAnotherEquationOrShortAnnotation() {
        var main = line(50, 120, 100, 10, "x=y (1)");
        var rows = PdfLayoutExtractor.reconnectMathRows(List.of(main,
                line(270, 290, 101, 8, "p. 2"), line(300, 370, 100, 10, "z=w (2)")));
        assertEquals(3, rows.size());
        assertTrue(rows.contains(main));
    }

    @Test
    void proseBlocksTheBridgeToDetachedNotation() {
        var main = line(50, 120, 100, 10, "x=y");
        var rows = PdfLayoutExtractor.reconnectMathRows(List.of(main,
                line(125, 150, 103, 10, "Therefore,"), line(151, 155, 106, 6, "′")));
        assertEquals(2, rows.size());
        assertTrue(rows.contains(main));
        assertTrue(rows.stream().anyMatch(row -> row.text().startsWith("Therefore,")));
    }

    @Test
    void namedFunctionAssignmentsDoNotNeedPaperSpecificVocabulary() {
        assertTrue(PdfLayoutExtractor.mathish("c′k ← Reconstruct(ck, M)."));
        assertTrue(PdfLayoutExtractor.mathish("s′k ← sk,"));
        assertFalse(PdfLayoutExtractor.mathish("The agent assigns c ← Reconstruct(c, M)."));
    }

    @Test
    void inlineScriptRepairDoesNotConsumeNumberedOrNarrowEquations() {
        var prose = line(50, 150, 100, 10, "The value follows.");
        var rows = PdfLayoutExtractor.reconnectMathRows(List.of(prose,
                line(90, 120, 103, 10, "c=(x)"), line(155, 165, 101, 8, "(5)")));
        assertEquals(3, rows.size());
        assertTrue(rows.contains(prose));
    }
}
