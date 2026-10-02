package org.dyh.learnhub.service;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** Exercises the complete extraction pipeline with small, reproducible PDF pages. */
class PdfReadingRegressionTest {
    @TempDir Path temp;
    private static final PDType1Font TEXT = new PDType1Font(Standard14Fonts.FontName.TIMES_ROMAN);

    @Test
    void footnotesKeepMarkersAndWrappedLinesTogether() throws Exception {
        Path pdf = temp.resolve("notes.pdf");
        try (PDDocument doc = new PDDocument()) {
            PDPage page = new PDPage(PDRectangle.LETTER);
            doc.addPage(page);
            try (PDPageContentStream out = new PDPageContentStream(doc, page)) {
                body(out);
                text(out, 54, 92, 10, "* These authors contributed equally.");
                text(out, 54, 74, 8, "1 The code and trained models have been released at");
                text(out, 62, 63, 8, "https://example.org/code.");
                text(out, 69, 48, 8, "2 A separate footnote can explain docu-");
                text(out, 54, 37, 8, "ments with a hanging continuation.");
            }
            doc.save(pdf.toFile());
        }
        List<PdfLayoutExtractor.Block> blocks = blocks(pdf);
        List<String> notes = blocks.stream().filter(b -> "note".equals(b.type()))
                .map(PdfLayoutExtractor.Block::text).toList();
        assertEquals(3, notes.size(), notes.toString());
        assertEquals("* These authors contributed equally.", notes.get(0));
        assertEquals("1 The code and trained models have been released at https://example.org/code.", notes.get(1));
        assertTrue(notes.get(2).startsWith("2 "));
        assertTrue(notes.get(2).contains("documents with a hanging continuation"));
        assertTrue(blocks.stream().filter(b -> "para".equals(b.type()))
                .noneMatch(b -> b.text().contains("authors contributed")));
    }

    @Test
    void footnoteSuperscriptAndMixedSmallFontsStayTogether() throws Exception {
        Path pdf = temp.resolve("mixed-note-fonts.pdf");
        try (PDDocument doc = new PDDocument()) {
            PDPage page = new PDPage(PDRectangle.LETTER);
            doc.addPage(page);
            try (PDPageContentStream out = new PDPageContentStream(doc, page)) {
                body(out);
                text(out, 66, 94, 5, "3");
                text(out, 70, 90, 9, "The ideal size of a passage depends on the retriever.");
                text(out, 54, 80, 9, "Its continuation has the same small font.");
                text(out, 66, 60, 9, "4 A second numbered note starts here.");
                text(out, 54, 50, 8, "This line uses a slightly smaller font.");
                text(out, 290, 16, 8, "1");
            }
            doc.save(pdf.toFile());
        }
        List<String> notes = blocks(pdf).stream().filter(b -> "note".equals(b.type()))
                .map(PdfLayoutExtractor.Block::text).toList();
        assertEquals(2, notes.size(), notes.toString());
        assertTrue(notes.get(0).startsWith("3 The ideal"));
        assertTrue(notes.get(0).contains("Its continuation"));
        assertTrue(notes.get(1).startsWith("4 "));
        assertTrue(notes.get(1).contains("slightly smaller font"));
    }

    @Test
    void equationsAreSeparatedFromAdjacentProseAndFromDistantEquations() throws Exception {
        Path pdf = temp.resolve("equations.pdf");
        try (PDDocument doc = new PDDocument()) {
            PDPage page = new PDPage(PDRectangle.LETTER);
            doc.addPage(page);
            try (PDPageContentStream out = new PDPageContentStream(doc, page)) {
                body(out);
                text(out, 54, 590, 10, "The following equation describes the energy");
                text(out, 180, 578, 10, "E = m c^2 (1)");
                text(out, 54, 566, 10, "where m is the mass and c is the speed of light.");
                text(out, 180, 450, 10, "x + y = z (2)");
                text(out, 180, 330, 10, "a + b = c (3)");
            }
            doc.save(pdf.toFile());
        }
        List<PdfLayoutExtractor.Block> blocks = blocks(pdf);
        List<PdfLayoutExtractor.Block> formulas = blocks.stream().filter(b -> "formula".equals(b.type())).toList();
        assertEquals(3, formulas.size(), blocks.toString());
        assertTrue(formulas.get(0).text().contains("E = m c^2"));
        assertTrue(formulas.stream().noneMatch(b -> b.text().contains("where m")));
        assertTrue(formulas.stream().allMatch(b -> b.page() != null && b.rect() != null));
    }

    @Test
    void narrowColumnGapKeepsEquationSeparateFromOtherColumnProse() throws Exception {
        Path pdf = temp.resolve("narrow-columns.pdf");
        try (PDDocument doc = new PDDocument()) {
            PDPage first = new PDPage(PDRectangle.LETTER);
            doc.addPage(first);
            try (PDPageContentStream out = new PDPageContentStream(doc, first)) {
                body(out);
            }
            PDPage page = new PDPage(PDRectangle.LETTER);
            doc.addPage(page);
            try (PDPageContentStream out = new PDPageContentStream(doc, page)) {
                String left = "Left column has ordinary prose to establish the page layout.";
                String right = "Right column prose must never enter the equation.";
                float leftWidth = TEXT.getStringWidth(left) / 1000 * 10;
                for (int i = 0; i < 10; i++) {
                    // A slightly larger body font must not create a title zone on later pages.
                    text(out, 284 - leftWidth * 1.1f, 700 - i * 14, 11, left);
                    text(out, 316, 700 - i * 14, 10, right);
                }
                String equation = "sim(q, p) = EQ(q) EP(p). (1)";
                float width = TEXT.getStringWidth(equation) / 1000 * 10;
                text(out, 294 - width, 520, 10, equation);
                text(out, 305, 520, 10, right);
            }
            doc.save(pdf.toFile());
        }
        List<PdfLayoutExtractor.Block> formulas = blocks(pdf).stream()
                .filter(b -> "formula".equals(b.type())).toList();
        assertEquals(1, formulas.size(), formulas.toString());
        assertTrue(formulas.get(0).text().startsWith("sim(q, p)"));
        assertFalse(formulas.get(0).text().contains("Right column"));
        assertEquals(2, formulas.get(0).page());
        assertTrue(formulas.get(0).rect()[2] < 300, "Crop includes the other column");
    }

    @Test
    void formulaCropIncludesRaisedAndLoweredGlyphs() throws Exception {
        Path pdf = temp.resolve("scripts.pdf");
        try (PDDocument doc = new PDDocument()) {
            PDPage page = new PDPage(PDRectangle.LETTER);
            doc.addPage(page);
            try (PDPageContentStream out = new PDPageContentStream(doc, page)) {
                body(out);
                String equation = "sim(q, p) = EQ(q)";
                text(out, 180, 500, 12, equation);
                float tailX = 180 + TEXT.getStringWidth(equation) / 1000 * 12;
                text(out, tailX, 508, 7, "T");
                text(out, tailX + 5, 500, 12, "EP(p). (1)");
                text(out, 185, 512, 7, "2");
                text(out, 185, 488, 7, "i");
            }
            doc.save(pdf.toFile());
        }
        List<PdfLayoutExtractor.Block> formulas = blocks(pdf).stream()
                .filter(b -> "formula".equals(b.type())).toList();
        assertEquals(1, formulas.size(), formulas.toString());
        double[] rect = formulas.get(0).rect();
        assertNotNull(rect);
        assertTrue(rect[1] <= 274, "Superscript is outside crop: " + java.util.Arrays.toString(rect));
        assertTrue(rect[3] >= 305, "Subscript is outside crop: " + java.util.Arrays.toString(rect));
        assertTrue(formulas.get(0).text().contains("i"));
        assertTrue(formulas.get(0).text().contains("EP(p). (1)"), "Equation tail was lost");
    }

    private static void body(PDPageContentStream out) throws Exception {
        for (int i = 0; i < 8; i++) {
            text(out, 54, 710 - i * 14, 10,
                    "This is a normal paragraph with sufficient text to establish the body font size.");
        }
    }

    @Test
    void repeatedFootnotesRemainOnEveryPage() throws Exception {
        Path pdf = temp.resolve("repeated-notes.pdf");
        try (PDDocument doc = new PDDocument()) {
            for (int i = 0; i < 3; i++) {
                PDPage page = new PDPage(PDRectangle.LETTER);
                doc.addPage(page);
                try (PDPageContentStream out = new PDPageContentStream(doc, page)) {
                    body(out);
                    text(out, 54, 74, 8, "1 This repeated footnote is content, not a page footer.");
                }
            }
            doc.save(pdf.toFile());
        }
        PdfLayoutExtractor.Layout layout = new PdfLayoutExtractor().extract(pdf);
        assertEquals(3, layout.pages().size());
        assertTrue(layout.pages().stream().allMatch(p -> p.blocks().stream()
                .anyMatch(b -> "note".equals(b.type()) && b.text().contains("repeated footnote"))));
        assertEquals(layout.pages().stream().flatMap(p -> p.blocks().stream()).mapToInt(b -> b.text().length()).sum(),
                layout.chars());
    }

    @Test
    void monospaceAssignmentsAreCodeRatherThanFormulas() throws Exception {
        Path pdf = temp.resolve("code.pdf");
        try (PDDocument doc = new PDDocument()) {
            PDPage page = new PDPage(PDRectangle.LETTER);
            doc.addPage(page);
            try (PDPageContentStream out = new PDPageContentStream(doc, page)) {
                body(out);
                PDType1Font code = new PDType1Font(Standard14Fonts.FontName.COURIER);
                out.beginText();
                out.setFont(code, 9);
                out.newLineAtOffset(54, 500);
                out.showText("x = y + z");
                out.newLineAtOffset(0, -12);
                out.showText("a = b + c");
                out.endText();
            }
            doc.save(pdf.toFile());
        }
        List<PdfLayoutExtractor.Block> blocks = blocks(pdf);
        assertTrue(blocks.stream().noneMatch(b -> "formula".equals(b.type())));
        assertTrue(blocks.stream().anyMatch(b -> "code".equals(b.type()) && b.text().contains("a = b + c")));
        assertFalse(PdfLayoutExtractor.mathish("x = 2 for all y"));
        assertFalse(PdfLayoutExtractor.mathish("config = true"));
    }

    private static void text(PDPageContentStream out, float x, float y, float size, String text) throws Exception {
        out.beginText();
        out.setFont(TEXT, size);
        out.newLineAtOffset(x, y);
        out.showText(text);
        out.endText();
    }

    private static List<PdfLayoutExtractor.Block> blocks(Path pdf) {
        PdfLayoutExtractor.Layout layout = new PdfLayoutExtractor().extract(pdf);
        assertEquals("ok", layout.status(), layout.error());
        return layout.pages().stream().flatMap(p -> p.blocks().stream()).toList();
    }
}
