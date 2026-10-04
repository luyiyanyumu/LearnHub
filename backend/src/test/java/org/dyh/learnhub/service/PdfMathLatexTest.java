package org.dyh.learnhub.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.apache.pdfbox.rendering.ImageType;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.awt.image.BufferedImage;
import javax.imageio.ImageIO;

import static org.junit.jupiter.api.Assertions.*;

class PdfMathLatexTest {
    private static final PDType1Font TEXT = new PDType1Font(Standard14Fonts.FontName.TIMES_ROMAN);
    private static final PDType1Font SYMBOL = new PDType1Font(Standard14Fonts.FontName.SYMBOL);
    @TempDir Path temp;

    @Test
    void realPositionedPdfRecoversGreekSubscriptAndSuperscript() throws Exception {
        Path pdf = fixture("positioned-scripts.pdf", out -> {
            text(out, SYMBOL, 180, 500, 12, "α");
            text(out, TEXT, 188, 495, 7, "i");
            text(out, TEXT, 204, 500, 12, "+");
            text(out, SYMBOL, 220, 500, 12, "β");
            text(out, TEXT, 228, 508, 7, "2");
            text(out, TEXT, 243, 500, 12, "= 0");
        });
        PdfLayoutExtractor.Block formula = onlyFormula(pdf);
        assertEquals("restored", formula.latexStatus(), formula.toString());
        assertTrue(formula.latex().contains("\\alpha_{i}"), formula.latex());
        assertTrue(formula.latex().contains("\\beta^{2}"), formula.latex());
        assertTrue(formula.latex().contains("= 0"), formula.latex());
        assertTrue(formula.text().contains("α"));
        assertTrue(formula.text().contains("β"));
        assertTrue(formula.latexMessage().contains("核对"));
        assertNotNull(formula.rect());
        assertEquals(1, formula.page());
    }

    @Test
    void realPdfRecoversFractionOnlyWithObservedDivisionRule() throws Exception {
        Path pdf = fixture("fraction.pdf", out -> {
            text(out, TEXT, 155, 502, 12, "f =");
            text(out, TEXT, 196, 512, 10, "x + y");
            out.setLineWidth(0.6f);
            out.moveTo(191, 506);
            out.lineTo(224, 506);
            out.stroke();
            text(out, TEXT, 196, 493, 10, "z + 1");
        });
        PdfLayoutExtractor.Block formula = onlyFormula(pdf);
        assertEquals("restored", formula.latexStatus(), formula.toString());
        assertTrue(formula.latex().contains("\\frac{x + y}{z + 1}"), formula.latex());
        assertTrue(formula.latex().startsWith("f ="), formula.latex());
        assertTrue(formula.text().contains("z + 1"), "Original denominator must be retained");
        assertTrue(formula.rect()[1] <= 280 && formula.rect()[3] >= 300, "Crop must retain numerator and denominator");
        assertTrue(formula.rect()[0] <= 191 && formula.rect()[2] >= 224, "Crop must include both observed fraction-rule endpoints");
    }

    @Test
    void standaloneFractionWithoutEqualsIsDetectedAndFilledRuleIsSupported() throws Exception {
        Path pdf = fixture("standalone-fraction.pdf", out -> {
            text(out, TEXT, 192, 512, 12, "a");
            out.addRect(188, 506, 15, 0.6f);
            out.fill();
            text(out, TEXT, 192, 490, 12, "b");
        });
        PdfLayoutExtractor.Block formula = onlyFormula(pdf);
        assertEquals("restored", formula.latexStatus(), formula.toString());
        assertEquals("\\frac{a}{b}", formula.latex());
        assertTrue(formula.text().contains("a") && formula.text().contains("b"));
    }

    @Test
    void realPdfRecoversCentredSummationLimits() throws Exception {
        Path pdf = fixture("sum-limits.pdf", out -> {
            text(out, TEXT, 155, 502, 12, "S =");
            text(out, SYMBOL, 189, 500, 22, "∑");
            text(out, TEXT, 194, 523, 8, "N");
            text(out, TEXT, 189, 487, 8, "i=1");
            text(out, TEXT, 220, 502, 12, "x");
            text(out, TEXT, 227, 496, 7, "i");
        });
        PdfLayoutExtractor.Block formula = onlyFormula(pdf);
        assertEquals("restored", formula.latexStatus(), formula.toString());
        assertTrue(formula.latex().contains("\\sum\\limits_{i = 1}^{N}"), formula.latex());
        assertTrue(formula.latex().contains("x_{i}"), formula.latex());
        assertTrue(formula.text().contains("N") && formula.text().contains("i=1"));
    }

    @Test
    void standaloneSquareRootRequiresItsActualVinculum() throws Exception {
        Path pdf = fixture("square-root.pdf", out -> {
            text(out, SYMBOL, 180, 500, 14, "√");
            text(out, TEXT, 192, 500, 12, "x + 1");
            out.addRect(190, 511, 31, 0.6f);
            out.fill();
        });
        PdfLayoutExtractor.Block formula = onlyFormula(pdf);
        assertEquals("restored", formula.latexStatus(), formula.toString());
        assertEquals("\\sqrt{x + 1}", formula.latex());
        assertTrue(formula.rect()[0] <= 190 && formula.rect()[2] >= 221, "Crop must include the full radical vinculum");
        assertTrue(formula.rect()[1] <= 792 - 511.6, "Crop must include the radical vinculum top");
        List<PdfMathLatex.Glyph> glyphs = List.of(glyph("√", 10, 20, 12), glyph("x", 17, 20, 12));
        PdfMathLatex.Restoration ambiguous = PdfMathLatex.restore(glyphs, List.of(), new double[]{0, 0, 50, 40}, "√x", true);
        assertEquals("partial", ambiguous.status());
        assertFalse(ambiguous.latex().contains("\\sqrt{"));
    }

    @Test
    void detachedEqualSizeFragmentsWithoutFractionBarAreOnlyCandidates() throws Exception {
        Path pdf = fixture("ambiguous-fragments.pdf", out -> {
            text(out, TEXT, 155, 510, 12, "f = a");
            text(out, TEXT, 178, 498, 12, "b = c");
        });
        PdfLayoutExtractor.Block formula = onlyFormula(pdf);
        assertEquals("partial", formula.latexStatus(), formula.toString());
        assertFalse(formula.latex().contains("\\frac"), formula.latex());
        assertTrue(formula.latex().contains("a") && formula.latex().contains("b"));
        assertTrue(formula.text().contains("f = a") && formula.text().contains("b = c"));
    }

    @Test
    void unresolvedGlyphAndMissingGeometryCannotClaimSuccessfulRestoration() throws Exception {
        Path pdf = fixture("unknown-glyph.pdf", out -> text(out, TEXT, 180, 500, 12, "E = ?"));
        PdfLayoutExtractor.Block formula = onlyFormula(pdf);
        assertEquals("partial", formula.latexStatus());
        assertEquals("E=?", formula.text().replace(" ", ""));
        assertTrue(formula.latex().contains("?"));
        PdfMathLatex.Restoration missing = PdfMathLatex.restore(List.of(), List.of(), null, "a/b", true);
        assertEquals("unavailable", missing.status());
        assertNull(missing.latex());
        PdfMathLatex.Restoration rotated = PdfMathLatex.restore(List.of(), List.of(), new double[]{0, 0, 20, 20}, "a/b", false);
        assertEquals("unavailable", rotated.status());
    }

    @Test
    void textParagraphsAndFootnotesKeepOriginalContentAndHaveNoLatexMetadata() throws Exception {
        Path pdf = fixture("prose-and-notes.pdf", out -> {
            text(out, TEXT, 54, 500, 10, "The expression E = mc appears inside this explanatory paragraph.");
            text(out, TEXT, 54, 60, 7, "1 This footnote contains the letter x and the value 2.");
        });
        List<PdfLayoutExtractor.Block> blocks = blocks(pdf);
        assertTrue(blocks.stream().anyMatch(b -> b.text().contains("explanatory paragraph")));
        assertTrue(blocks.stream().anyMatch(b -> b.text().contains("This footnote")));
        blocks.stream().filter(b -> !"formula".equals(b.type())).forEach(b -> {
            assertNull(b.latex());
            assertNull(b.latexStatus());
            assertNull(b.latexMessage());
        });
    }

    @Test
    void flatTextContainingAQuestionMarkRemainsPartial() {
        List<PdfMathLatex.Glyph> glyphs = List.of(glyph("x", 10, 20, 12), glyph("=", 22, 20, 12), glyph("?", 34, 20, 12));
        PdfMathLatex.Restoration r = PdfMathLatex.restore(glyphs, List.of(), new double[]{0, 0, 60, 40}, "x = ?", true);
        assertEquals("partial", r.status());
        assertTrue(r.latex().contains("\\text{?}"));
    }

    @Test
    void unicodeScriptsProduceOneValidLatexArgument() {
        List<PdfMathLatex.Glyph> glyphs = List.of(glyph("x", 10, 20, 12), glyph("¹", 17, 20, 12), glyph("²", 23, 20, 12),
                glyph("=", 35, 20, 12), glyph("0", 46, 20, 12));
        PdfMathLatex.Restoration r = PdfMathLatex.restore(glyphs, List.of(), new double[]{0, 0, 70, 40}, "x¹² = 0", true);
        assertEquals("restored", r.status());
        assertTrue(r.latex().contains("^{12}"), r.latex());
        assertFalse(r.latex().contains("^{1}"), r.latex());
    }

    @Test
    void stackedFragmentsAreNotConvertedToFractionsWithoutRuleEvidence() {
        List<PdfMathLatex.Glyph> glyphs = List.of(glyph("a", 10, 15, 12), glyph("b", 10, 32, 12));
        PdfMathLatex.Restoration r = PdfMathLatex.restore(glyphs, List.of(), new double[]{0, 0, 30, 50}, "a\nb", true);
        assertEquals("partial", r.status());
        assertFalse(r.latex().contains("\\frac"));
        assertTrue(r.latex().contains("a") && r.latex().contains("b"));
        assertTrue(r.latex().indexOf('a', r.latex().indexOf('}') + 1) < r.latex().lastIndexOf('b'), r.latex());
    }

    @Test
    void readerQaFixtureContainsRestoredStructuresAndAnExplicitPartialResult() throws Exception {
        Path pdf = fixture("latex-reader-qa.pdf", out -> {
            text(out, SYMBOL, 180, 540, 12, "α");
            text(out, TEXT, 188, 535, 7, "i");
            text(out, TEXT, 204, 540, 12, "+");
            text(out, SYMBOL, 220, 540, 12, "β");
            text(out, TEXT, 228, 548, 7, "2");
            text(out, TEXT, 243, 540, 12, "= 0");

            text(out, TEXT, 155, 452, 12, "f =");
            text(out, TEXT, 196, 462, 10, "x + y");
            out.setLineWidth(0.6f);
            out.moveTo(191, 456);
            out.lineTo(224, 456);
            out.stroke();
            text(out, TEXT, 196, 443, 10, "z + 1");

            text(out, TEXT, 155, 352, 12, "S =");
            text(out, SYMBOL, 189, 350, 22, "∑");
            text(out, TEXT, 194, 373, 8, "N");
            text(out, TEXT, 189, 337, 8, "i=1");
            text(out, TEXT, 220, 352, 12, "x");
            text(out, TEXT, 227, 346, 7, "i");

            text(out, SYMBOL, 180, 250, 14, "√");
            text(out, TEXT, 192, 250, 12, "x + 1");
            out.addRect(190, 261, 31, 0.6f);
            out.fill();

            text(out, TEXT, 180, 160, 12, "E = ?");
            text(out, TEXT, 54, 65, 7, "1 Original note content remains available for checking.");
        });
        PdfLayoutExtractor.Layout layout = new PdfLayoutExtractor().extract(pdf);
        List<PdfLayoutExtractor.Block> formulas = layout.pages().stream().flatMap(p -> p.blocks().stream())
                .filter(b -> "formula".equals(b.type())).toList();
        assertEquals(5, formulas.size(), formulas.toString());
        assertEquals(4, formulas.stream().filter(b -> "restored".equals(b.latexStatus())).count(), formulas.toString());
        assertEquals(1, formulas.stream().filter(b -> "partial".equals(b.latexStatus())).count(), formulas.toString());
        Path target = Path.of("target").toAbsolutePath();
        Files.createDirectories(target);
        Files.copy(pdf, target.resolve("latex-reader-qa.pdf"), StandardCopyOption.REPLACE_EXISTING);
        Files.writeString(target.resolve("latex-reader-qa-layout.json"), new ObjectMapper().writerWithDefaultPrettyPrinter().writeValueAsString(layout));
        try (PDDocument doc = Loader.loadPDF(pdf.toFile())) {
            BufferedImage page = new PDFRenderer(doc).renderImageWithDPI(0, 150, ImageType.RGB);
            ImageIO.write(page, "png", target.resolve("latex-reader-qa-page.png").toFile());
            for (int i = 0; i < formulas.size(); i++) {
                double[] r = formulas.get(i).rect();
                int x = Math.max(0, (int) Math.floor(r[0] * 150 / 72));
                int y = Math.max(0, (int) Math.floor(r[1] * 150 / 72));
                int right = Math.min(page.getWidth(), (int) Math.ceil(r[2] * 150 / 72));
                int bottom = Math.min(page.getHeight(), (int) Math.ceil(r[3] * 150 / 72));
                ImageIO.write(page.getSubimage(x, y, right - x, bottom - y), "png",
                        target.resolve("latex-reader-qa-formula-" + i + ".png").toFile());
            }
        }
    }

    private static PdfMathLatex.Glyph glyph(String text, double x, double y, double size) {
        return new PdfMathLatex.Glyph(text, x, x + size * 0.5, y, size, y - size * 0.9, y + size * 0.3);
    }

    private PdfLayoutExtractor.Block onlyFormula(Path pdf) {
        List<PdfLayoutExtractor.Block> formulas = blocks(pdf).stream().filter(b -> "formula".equals(b.type())).toList();
        assertEquals(1, formulas.size(), formulas.toString());
        return formulas.getFirst();
    }

    private List<PdfLayoutExtractor.Block> blocks(Path pdf) {
        PdfLayoutExtractor.Layout layout = new PdfLayoutExtractor().extract(pdf);
        assertEquals("ok", layout.status(), layout.error());
        return layout.pages().stream().flatMap(p -> p.blocks().stream()).toList();
    }

    private Path fixture(String filename, Equation equation) throws Exception {
        Path pdf = temp.resolve(filename);
        try (PDDocument doc = new PDDocument()) {
            PDPage page = new PDPage(PDRectangle.LETTER);
            doc.addPage(page);
            try (PDPageContentStream out = new PDPageContentStream(doc, page)) {
                for (int i = 0; i < 8; i++) {
                    text(out, TEXT, 54, 710 - i * 14, 10,
                            "This is a normal paragraph with sufficient text to establish the body font size.");
                }
                equation.draw(out);
            }
            doc.save(pdf.toFile());
        }
        return pdf;
    }

    private static void text(PDPageContentStream out, PDType1Font font, float x, float y, float size, String value) throws Exception {
        out.beginText();
        out.setFont(font, size);
        out.newLineAtOffset(x, y);
        out.showText(value);
        out.endText();
    }

    @FunctionalInterface private interface Equation { void draw(PDPageContentStream out) throws Exception; }
}
