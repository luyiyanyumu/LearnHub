package org.dyh.learnhub.service;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class DocumentMarkdownWriterTest {
    @Test void structureAndRejectedCandidatesHaveSeparateMarkdownAndSearchOffsetsAndRenderingIsIdempotent() {
        String asset = "assets/" + "a".repeat(64) + ".png";
        var input = extraction(List.of(
                block("heading", "Synthetic document", 2, true, "accepted", null, null, null, null),
                block("bullet", "First item", 0, true, "accepted", null, null, null, null),
                block("table", "Name\tValue\nCedar\t7", 0, true, "accepted", null, null, null, "| Name | Value |\n| --- | --- |\n| Cedar | 7 |"),
                block("formula", "x squared", 0, true, "accepted", null, "x^2", "restored", null),
                block("code", "literal ``` marker\n![code sample](assets/inside-code.png)", 0, true, "accepted", null, null, null, null),
                block("figure", "Synthetic diagram", 0, false, "non_text", asset, null, null, null),
                block("paragraph", "Rejected synthetic OCR candidate", 0, false, "low_confidence", null, null, null, null),
                block("formula", "Unverified formula symbols", 0, false, "uncertain_formula", asset, "x?", "partial", null)));

        var result = DocumentMarkdownWriter.render(input);

        assertEquals("markdown", result.format());
        assertTrue(result.text().contains("## Synthetic document"));
        assertTrue(result.text().contains("- First item"));
        assertTrue(result.text().contains("$$\nx^2\n$$"));
        assertTrue(result.text().contains("| Cedar | 7 |"));
        assertTrue(result.text().contains("````\nliteral ``` marker"));
        assertTrue(result.text().contains("![code sample](assets/inside-code.png)"));
        assertFalse(result.text().contains("Rejected synthetic"));
        assertFalse(result.text().contains("Unverified formula"));
        assertFalse(result.text().contains(asset));
        assertTrue(result.markdown().contains(asset));
        assertTrue(result.markdown().contains("Rejected synthetic OCR candidate"));
        assertTrue(result.markdown().contains("Unverified formula symbols") || result.markdown().contains("<img"));
        assertFalse(result.markdown().contains("待核对（"));
        assertOffsets(result);
        for (int i = 0; i < input.pages().getFirst().blocks().size(); i++) {
            assertEquals(input.pages().getFirst().blocks().get(i).rawText(), result.pages().getFirst().blocks().get(i).rawText());
            assertEquals(input.pages().getFirst().blocks().get(i).cleanText(), result.pages().getFirst().blocks().get(i).cleanText());
        }
        assertEquals(result, DocumentMarkdownWriter.render(result));
    }

    @Test void destinationsAreRemovedButCodeMathAndVisibleLabelsRemain() {
        String markdown = "[Visible label](https://example.com/path) ![diagram](assets/outside.png)\n"
                + "[![nested diagram](assets/nested.png)](https://example.org/)\n"
                + "![reference][picture]\n[picture]: assets/reference.png\n\n"
                + "`![inline example](assets/inline-code.png)`\n"
                + "```markdown\n![example](assets/fenced-code.png)\n````\n"
                + "![after closing fence](assets/after-code.png)\n"
                + "    ![indented code](assets/indented-code.png)\n"
                + "$$\n[x](assets/math.png)\n$$\n"
                + "\\([x](assets/inline-math.png)\\)\n"
                + "<table><tr><td><img src=\"assets/html.png\" alt=\"HTML diagram\"></td></tr></table>";

        String index = DocumentMarkdownWriter.projectForIndex(markdown);

        for (String omitted : List.of("https://example.com", "assets/outside", "assets/nested", "assets/reference", "assets/after-code", "assets/html"))
            assertFalse(index.contains(omitted), omitted);
        for (String retained : List.of("Visible label", "nested diagram", "reference", "after closing fence", "HTML diagram",
                "assets/inline-code.png", "assets/fenced-code.png", "assets/indented-code.png", "assets/math.png", "assets/inline-math.png"))
            assertTrue(index.contains(retained), retained);
    }

    @Test void untrustedHtmlInOriginalMarkdownIsNotExecutableAndSafeTablesKeepTheirStructure() {
        String markdown = "# Heading\n\n<script>alert('synthetic')</script>\n"
                + "<table onclick=\"bad()\"><tr><td colspan=\"2\" onmouseover=\"bad()\">A</td></tr></table>\n"
                + "<img src=\"javascript:bad()\" alt=\"untrusted image\">\n"
                + "```html\n<script>literal code sample</script>\n```";

        String safe = DocumentMarkdownWriter.safeMarkdown(markdown);

        assertTrue(safe.startsWith("# Heading"));
        assertTrue(safe.contains("&lt;script&gt;"));
        assertFalse(safe.contains("onclick"));
        assertFalse(safe.contains("onmouseover"));
        assertFalse(safe.contains("javascript:"));
        assertTrue(safe.contains("<td colspan=\"2\">A</td>"));
        assertTrue(safe.contains("```html\n<script>literal code sample</script>\n```"));
    }

    @Test void aMediaUpdateToAlreadyRenderedBlocksIsRecomputedRatherThanSkipped() {
        var once = DocumentMarkdownWriter.render(extraction(List.of(block("paragraph", "Original", 0, true, "accepted", null, null, null, null))));
        var blocks = new ArrayList<>(once.pages().getFirst().blocks());
        blocks.add(block("paragraph", "New accepted image description", 0, true, "accepted", null, null, null,
                "New accepted image description ![diagram](assets/" + "b".repeat(64) + ".png)"));
        var changed = new DocumentIngestionService.Extraction(once.status(), once.text(), once.chars(), once.error(), once.sourceSha256(),
                once.pipelineVersion(), List.of(new DocumentIngestionService.Page(1, 600, 800, "pt", "text", blocks)), once.report(), once.markdown(), "markdown");

        var result = DocumentMarkdownWriter.render(changed);

        assertTrue(result.markdown().contains("assets/"));
        assertFalse(result.text().contains("assets/"));
        assertTrue(result.text().contains("New accepted image description diagram"));
        assertOffsets(result);
        assertEquals(result, DocumentMarkdownWriter.render(result));
    }

    @Test void anAtomicBlockOverTheIndexLimitIsReportedAndNeverSilentlyCut() {
        String source = "synthetic code line\n".repeat(22000);
        var result = DocumentMarkdownWriter.render(extraction(List.of(block("code", source, 0, true, "accepted", null, null, null, null))));
        assertTrue(result.text().isEmpty());
        assertTrue(result.markdown().contains(source));
        assertTrue(result.markdown().endsWith("```"));
        assertTrue(result.report().partial());
        assertTrue(result.report().truncated());
        assertEquals("empty", result.status());
        assertOffsets(result);
        assertEquals(result, DocumentMarkdownWriter.render(result));
    }

    @Test void reliableVisionCaptionsAndFormulaPicturesEnterSearchWithoutGenericAltOrAssetHashes() {
        String asset = "assets/" + "c".repeat(64) + ".png";
        var result = DocumentMarkdownWriter.render(extraction(List.of(
                block("figure", "Cedar pipeline connects extraction to verified evidence.", 0, true, "recognized", asset, null, null,
                        "![原图](" + asset + ")\n\nCedar pipeline connects extraction to verified evidence."),
                block("formula", "x squared", 0, true, "recognized", asset, "x^2", "recognized",
                        "![公式原图](" + asset + ")\n\n$$\nx^2\n$$"),
                block("figure", "", 0, true, "recognized", asset, null, null, "![原图](" + asset + ")"),
                block("formula", "uncertain symbols", 0, true, "uncertain_formula", asset, "x?", "partial", null))));
        assertTrue(result.text().contains("Cedar pipeline connects extraction to verified evidence."));
        assertTrue(result.text().contains("$$\nx^2\n$$"));
        assertFalse(result.text().contains("原图"));
        assertFalse(result.text().contains("assets/"));
        assertFalse(result.text().contains("c".repeat(64)));
        assertFalse(result.text().contains("x?"));
        assertTrue(result.markdown().contains(asset));
        assertFalse(result.pages().getFirst().blocks().get(2).indexed());
        assertFalse(result.pages().getFirst().blocks().get(3).indexed());
        assertOffsets(result);
        assertEquals(result, DocumentMarkdownWriter.render(result));
    }

    @Test void intentionalFurnitureAndSupersededFormulaCandidatesDoNotMakeAnOtherwiseCompletePagePartial() {
        var result = DocumentMarkdownWriter.render(extraction(List.of(
                block("paragraph", "Synthetic accepted body.", 0, true, "accepted", null, null, null, null),
                block("meta", "Repeated synthetic page header", 0, false, "page_furniture", null, null, null, ""),
                block("formula", "Original uncertain formula", 0, false, "superseded_by_formula", null, null, "partial", ""),
                block("formula", "Verified replacement", 0, true, "recognized", null, "a+b", "recognized", null))));

        assertEquals("ok", result.status());
        assertFalse(result.report().partial());
        assertEquals(0, result.report().rejectedPages());
        assertTrue(result.text().contains("Synthetic accepted body."));
        assertTrue(result.text().contains("$$\na+b\n$$"));
        assertFalse(result.text().contains("Repeated synthetic"));
        assertFalse(result.text().contains("Original uncertain"));
        assertEquals(result, DocumentMarkdownWriter.render(result));
    }

    @Test void intentionalExclusionsCannotHideAPageWithNoRemainingAcceptedEvidence() {
        var result = DocumentMarkdownWriter.render(extraction(List.of(
                block("meta", "Synthetic header only", 0, false, "page_furniture", null, null, null, ""),
                block("formula", "Missing replacement", 0, false, "superseded_by_formula", null, null, "partial", ""))));
        assertTrue(result.text().isEmpty());
        assertEquals("empty", result.status());
        assertEquals(1, result.report().rejectedPages());
        assertTrue(result.report().partial());
    }

    @Test void imageDimensionsUseSourceGeometryOrPresentationWithoutCopyingPageWidth() {
        String asset = "assets/" + "d".repeat(64) + ".png";
        var geometry = block("figure", "", 0, false, "non_text", asset, null, null, null);
        var explicit = presented(block("figure", "", 0, false, "non_text", asset, null, null, null),
                new DocumentIngestionService.Presentation(null, "block", 240.0, 90.0, "center", "word:word/document.xml#rId7"));
        var invalid = presented(block("figure", "", 0, false, "non_text", "word:word/document.xml#rId8", null, null, null),
                new DocumentIngestionService.Presentation(null, "block", Double.NaN, -1.0, null, null));

        var result = DocumentMarkdownWriter.render(extraction(List.of(geometry, explicit, invalid)));

        assertTrue(result.markdown().contains("src=\"" + asset + "\" width=\"120\" height=\"53\""));
        assertTrue(result.markdown().contains("src=\"" + asset + "\" width=\"240\" height=\"90\""));
        assertFalse(result.markdown().contains("width=\"600\""));
        assertFalse(result.markdown().contains("word:"));
        assertTrue(result.markdown().contains("原图未能提取"));
        assertFalse(result.markdown().contains("NaN"));
        assertOffsets(result);
        assertEquals(result, DocumentMarkdownWriter.render(result));
    }

    @Test void inlineImagesKeepTheirOriginalFlowWhileModelEvidenceHasIndependentIndexOffsets() {
        String asset = "assets/" + "e".repeat(64) + ".png";
        var inline = new DocumentIngestionService.Presentation("paragraph-1", "inline", null, null, null, null);
        var image = new DocumentIngestionService.Presentation("paragraph-1", "inline", 32.0, 18.0, null, "word:word/document.xml#rId9");
        var annotation = new DocumentIngestionService.Presentation(null, "annotation", null, null, null, "word:word/document.xml#rId9");
        var resolver = new DocumentIngestionService.Presentation(null, "resolver_only", null, null, null, "word:word/document.xml#rId10");
        var result = DocumentMarkdownWriter.render(extraction(List.of(
                presented(block("paragraph", "Before ", 0, true, "accepted", null, null, null, null), inline),
                presented(block("formula", "model formula", 0, true, "recognized", asset, "x^2", "recognized", "MODEL DISPLAY MUST NOT LEAK"), image),
                presented(block("paragraph", " after", 0, true, "accepted", null, null, null, null), inline),
                presented(block("formula", "derived model formula", 0, true, "recognized", asset, "a+b", "recognized", "MODEL ANNOTATION"), annotation),
                presented(block("figure", "Synthetic visible diagram caption", 0, true, "recognized", asset, null, null, "MODEL CAPTION DISPLAY"), resolver))));

        assertTrue(result.markdown().contains("Before <img src=\"" + asset + "\" width=\"32\" height=\"18\" alt=\"原图\"> after"));
        assertFalse(result.markdown().contains("MODEL"));
        assertFalse(result.markdown().contains("x^2"));
        assertFalse(result.markdown().contains("a+b"));
        assertFalse(result.markdown().contains("Synthetic visible diagram caption"));
        assertTrue(result.text().contains("Before \\(x^2\\) after"));
        assertTrue(result.text().contains("$$\na+b\n$$"));
        assertTrue(result.text().contains("Synthetic visible diagram caption"));
        assertFalse(result.text().contains(asset));
        assertEquals(-1, result.pages().getFirst().blocks().get(3).markdownStart());
        assertOffsets(result);
        assertEquals(result, DocumentMarkdownWriter.render(result));
    }

    @Test void nativeInlineAndBlockFormulasDoNotDuplicateTheirOriginalImage() {
        String asset = "assets/" + "f".repeat(64) + ".png";
        var result = DocumentMarkdownWriter.render(extraction(List.of(
                presented(block("formula", "native inline", 0, true, "accepted", asset, "y_i", "restored", "old image and formula"),
                        new DocumentIngestionService.Presentation("native", "inline", null, null, null, null)),
                block("formula", "native display", 0, true, "accepted", asset, "z^2", "restored", "old image and formula"))));
        assertTrue(result.markdown().contains("\\(y_i\\)"));
        assertTrue(result.markdown().contains("$$\nz^2\n$$"));
        assertFalse(result.markdown().contains("<img"));
        assertFalse(result.markdown().contains(asset));
        assertEquals(asset, result.pages().getFirst().blocks().getFirst().src());
        assertOffsets(result);
        assertEquals(result, DocumentMarkdownWriter.render(result));
    }

    @Test void realPageAnchorsAndPrintedLabelsAreDisplayOnlyAndLogicalPagesAreNotFabricated() {
        var base = block("paragraph", "Synthetic source evidence.", 0, true, "accepted", null, null, null, null);
        var input = new DocumentIngestionService.Extraction("ok", "", 0, null, "a".repeat(64), DocumentIngestionService.PIPELINE_VERSION,
                List.of(new DocumentIngestionService.Page(3, 600, 800, "pt", "text", List.of(base), "iv"),
                        new DocumentIngestionService.Page(4, 1000, 1200, "px", "ocr", List.of(base)),
                        new DocumentIngestionService.Page(5, 0, 0, "none", "text", List.of(base))),
                new DocumentIngestionService.QualityReport(3, 3, 1, 0, null, List.of(), false, false));

        var result = DocumentMarkdownWriter.render(input);

        assertTrue(result.markdown().contains("<a id=\"source-page-3\"></a>"));
        assertTrue(result.markdown().contains("###### 印刷页码 iv · 原件第 3 页"));
        assertTrue(result.markdown().contains("###### 原件第 4 页"));
        assertFalse(result.markdown().contains("source-page-5"));
        assertFalse(result.text().contains("原件第"));
        assertFalse(result.text().contains("印刷页码"));
        assertFalse(result.text().contains("source-page-"));
        assertEquals("iv", result.pages().getFirst().label());
        assertOffsets(result);
        assertEquals(result, DocumentMarkdownWriter.render(result));
    }

    @Test void originalMarkdownQuotesLinksCitationsFootnotesAndSoftHardBreaksSurviveSanitizing() {
        String source = "\n> Synthetic quotation\n> second quoted line\n\n"
                + "A soft\nline and a hard  \nbreak and a slash\\\nbreak.\n\n"
                + "<https://example.org/synthetic> <reader@example.org>\n"
                + "[angle](<https://example.org/angle>) [reference][ref] [^note]\n\n"
                + "[ref]: <https://example.org/reference> \"Synthetic title\"\n"
                + "[^note]: Synthetic footnote body.\n\n"
                + "<a id=\"citation-1\"></a><sup>[1]</sup><br><sub>x</sub>\n";
        assertEquals(source, DocumentMarkdownWriter.safeMarkdown(source));
        String index = DocumentMarkdownWriter.projectForIndex(source);
        assertTrue(index.contains("Synthetic quotation"));
        assertTrue(index.contains("Synthetic footnote body."));
        assertFalse(index.contains("[^note]:"));
        assertFalse(index.contains("https://example.org/reference"));
        assertTrue(DocumentMarkdownWriter.inlineText("> ordinary text").startsWith("&gt;"));
    }

    @Test void controlledHtmlKeepsOnlyBoundedImageDimensionsAndSafeAnchors() {
        String asset = "assets/" + "1".repeat(64) + ".png";
        String source = "<img src=\"" + asset + "\" width=\"240\" height=\"96\" style=\"width:100vw\" onerror=\"bad()\" alt=\"Synthetic\">"
                + "<a id=\"source-page-2\" onclick=\"bad()\"></a>"
                + "<img src=\"javascript:bad()\" width=\"999999\" alt=\"Rejected image\">"
                + "<a href=\"javascript:bad()\">Safe visible label</a>";
        String safe = DocumentMarkdownWriter.safeMarkdown(source);
        assertTrue(safe.contains("<img src=\"" + asset + "\" width=\"240\" height=\"96\" alt=\"Synthetic\">"));
        assertTrue(safe.contains("<a id=\"source-page-2\"></a>"));
        assertFalse(safe.contains("style="));
        assertFalse(safe.contains("onerror"));
        assertFalse(safe.contains("onclick"));
        assertFalse(safe.contains("javascript:"));
        assertFalse(safe.contains("999999"));
        assertTrue(safe.contains("Safe visible label"));
        String oversized = DocumentMarkdownWriter.safeMarkdown("<img src=\"" + asset + "\" width=\"8192\" height=\"4096\" alt=\"Synthetic\">");
        assertTrue(oversized.contains("width=\"4096\" height=\"2048\""));
    }

    @Test void footnoteDefinitionBodyRemainsSearchableWithValidSeparateSourceOffsets() {
        String source = "Synthetic evidence points to a footnote[^fn-id].\n\n"
                + "[^fn-id]: Synthetic calibration used seven samples; [source](https://example.org/synthetic-footnote).";
        var result = DocumentMarkdownWriter.render(extraction(List.of(
                block("markdown", source, 0, true, "accepted", null, null, null, DocumentMarkdownWriter.safeMarkdown(source)))));
        assertTrue(result.markdown().contains("[^fn-id]: Synthetic calibration"));
        assertTrue(result.text().contains("Synthetic calibration used seven samples; source."));
        assertFalse(result.text().contains("[^fn-id]:"));
        assertFalse(result.text().contains("https://example.org/synthetic-footnote"));
        var retained = result.pages().getFirst().blocks().getFirst();
        assertTrue(retained.indexed());
        assertEquals(result.text(), result.text().substring(retained.start(), retained.end()));
        assertEquals(source, result.markdown().substring(retained.markdownStart(), retained.markdownEnd()));
        assertOffsets(result);
        assertEquals(result, DocumentMarkdownWriter.render(result));
    }

    @Test void excludedFurnitureAndUncertainTextNeverGenerateEditorialBlockquotes() {
        var result = DocumentMarkdownWriter.render(extraction(List.of(
                block("paragraph", "Original uncertain prose", 0, false, "low_confidence", null, null, null, null),
                block("meta", "Synthetic repeated footer", 0, false, "page_furniture", null, null, null, null),
                block("formula", "Synthetic superseded candidate", 0, false, "superseded_by_formula", null, null, "partial", null),
                block("paragraph", "Accepted body", 0, true, "accepted", null, null, null, null))));
        assertTrue(result.markdown().contains("Original uncertain prose"));
        assertFalse(result.markdown().contains("Synthetic repeated footer"));
        assertFalse(result.markdown().contains("Synthetic superseded candidate"));
        assertFalse(result.markdown().contains("待核对"));
        assertFalse(result.text().contains("Original uncertain prose"));
        assertTrue(result.report().partial());
        assertOffsets(result);
        assertEquals(result, DocumentMarkdownWriter.render(result));
    }

    @Test void explicitWordPageBreakIsVisibleWithoutInventingPageNumbersOrSearchText() {
        var flow = new DocumentIngestionService.Presentation("synthetic-paragraph", "inline", null, null, null, null);
        var blocks = List.of(
                presented(block("paragraph", "Before ", 0, true, "accepted", null, null, null, null), flow),
                presented(block("page_break", "", 0, false, "non_text", null, null, null, null), flow),
                presented(block("paragraph", "after", 0, true, "accepted", null, null, null, null), flow));
        var input = new DocumentIngestionService.Extraction("ok", "", 0, null, "a".repeat(64), DocumentIngestionService.PIPELINE_VERSION,
                List.of(new DocumentIngestionService.Page(1, 0, 0, "none", "text", blocks)),
                new DocumentIngestionService.QualityReport(1, 1, 0, 0, null, List.of(), false, false));

        var result = DocumentMarkdownWriter.render(input);

        assertEquals("Before \n\n---\n\nafter", result.markdown());
        assertEquals("Before after", result.text());
        assertFalse(result.markdown().contains("原件第"));
        assertFalse(result.markdown().contains("source-page-"));
        assertFalse(result.pages().getFirst().blocks().get(1).indexed());
        assertEquals("---", result.pages().getFirst().blocks().get(1).markdown());
        assertFalse(result.report().partial());
        assertOffsets(result);
        assertEquals(result, DocumentMarkdownWriter.render(result));
    }

    private static DocumentIngestionService.Block presented(DocumentIngestionService.Block block, DocumentIngestionService.Presentation presentation) {
        return new DocumentIngestionService.Block(block.id(), block.type(), block.rawText(), block.cleanText(), block.page(), block.rect(),
                block.width(), block.height(), block.confidence(), block.quality(), block.indexed(), block.start(), block.end(),
                block.level(), block.src(), block.latex(), block.latexStatus(), block.latexMessage(), block.markdown(), block.markdownStart(), block.markdownEnd(), presentation);
    }

    private static DocumentIngestionService.Block block(String type, String text, int level, boolean indexed, String quality,
                                                         String src, String latex, String latexStatus, String markdown) {
        return new DocumentIngestionService.Block("b-" + type + text.length(), type, text, text, 1, new double[]{10, 20, 100, 60},
                600, 800, null, quality, indexed, -1, -1, level, src, latex, latexStatus, null, markdown, -1, -1);
    }
    private static DocumentIngestionService.Extraction extraction(List<DocumentIngestionService.Block> blocks) {
        return new DocumentIngestionService.Extraction("ok", "old text", 8, null, "a".repeat(64), DocumentIngestionService.PIPELINE_VERSION,
                List.of(new DocumentIngestionService.Page(1, 600, 800, "pt", "text", blocks)),
                new DocumentIngestionService.QualityReport(1, 1, 0, 0, null, List.of(), false, false));
    }
    static void assertOffsets(DocumentIngestionService.Extraction extraction) {
        for (var page : extraction.pages()) for (var block : page.blocks()) {
            if (block.markdownStart() >= 0) assertEquals(block.markdown(), extraction.markdown().substring(block.markdownStart(), block.markdownEnd()));
            if (block.indexed()) assertEquals(DocumentMarkdownWriter.indexMarkdown(block, block.markdown()), extraction.text().substring(block.start(), block.end()));
            else { assertEquals(-1, block.start()); assertEquals(-1, block.end()); }
        }
        assertEquals(extraction.text().length(), extraction.chars());
    }
}
