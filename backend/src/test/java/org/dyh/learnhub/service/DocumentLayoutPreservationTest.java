package org.dyh.learnhub.service;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.apache.pdfbox.pdmodel.graphics.image.LosslessFactory;
import org.apache.pdfbox.pdmodel.interactive.action.PDActionURI;
import org.apache.pdfbox.pdmodel.interactive.annotation.PDAnnotationLink;
import org.apache.poi.sl.usermodel.PictureData;
import org.apache.poi.sl.usermodel.Placeholder;
import org.apache.poi.xslf.usermodel.XMLSlideShow;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.imageio.ImageIO;
import java.awt.geom.Rectangle2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.*;

/** Synthetic documents only: source layout facts must survive Markdown conversion. */
class DocumentLayoutPreservationTest {
    @TempDir Path temp;

    @Test void wordInlinePictureKeepsSizeSourceOrderAndTheSpacesAtBothSides() throws Exception {
        Path path = word("<w:p><w:pPr><w:jc w:val=\"center\"/></w:pPr><w:r><w:t xml:space=\"preserve\">Before </w:t></w:r>"
                + picture("rId7", "inline", 762000, 381000)
                + "<w:r><w:t xml:space=\"preserve\"> after</w:t></w:r></w:p>", Map.of());

        var result = new WordLayoutExtractor().extractForIngestion(path);
        var blocks = result.pages().getFirst().blocks();

        assertEquals(3, blocks.size());
        assertEquals("Before ", blocks.get(0).rawText());
        assertEquals(" after", blocks.get(2).rawText());
        assertEquals("figure", blocks.get(1).type());
        var image = blocks.get(1).presentation();
        assertEquals("word:word/document.xml#rId7", image.sourceRef());
        assertEquals(80.0, image.widthPx());
        assertEquals(40.0, image.heightPx());
        assertEquals("inline", image.placement());
        assertEquals("center", blocks.get(0).presentation().align());
        assertEquals(blocks.get(0).presentation().groupId(), image.groupId());
        assertEquals(image.groupId(), blocks.get(2).presentation().groupId());
        assertEquals("Before after", result.text());
        assertEquals("Before [原图未能提取，请查看原件] after", result.markdown());
        assertFalse(result.markdown().contains("Before\n\n"), result.markdown());
    }

    @Test void wordCellImageHasOneResolverAndKeepsItsDimensionsInTheCell() throws Exception {
        Path path = word("<w:tbl><w:tr><w:tc><w:p>" + picture("rId8", "inline", 952500, 476250)
                + "</w:p></w:tc></w:tr></w:tbl>", Map.of());

        var result = new WordLayoutExtractor().extractForIngestion(path);
        var blocks = result.pages().getFirst().blocks();

        assertEquals(2, blocks.size());
        assertEquals("[图片]", blocks.getFirst().rawText());
        assertEquals("resolver_only", blocks.get(1).presentation().placement());
        assertEquals(blocks.getFirst().id(), blocks.get(1).presentation().groupId());
        assertEquals(100.0, blocks.get(1).presentation().widthPx());
        // The resolver is retained separately from display Markdown for the later local asset pass.
        assertEquals("word:word/document.xml#rId8", blocks.get(1).src());
        assertTrue(blocks.getFirst().markdown().contains("width=\"100.0\""), blocks.getFirst().markdown());
        assertFalse(result.markdown().contains("\u0005"));
        assertEquals(1, result.markdown().split("原图", -1).length - 1, result.markdown());
    }

    @Test void wordExplicitBreaksAndQuoteRemainStructuralWithoutInventingPrintedPages() throws Exception {
        String body = "<w:p><w:pPr><w:pStyle w:val=\"Quote\"/></w:pPr><w:r><w:t>First line</w:t><w:br/><w:t>Second line</w:t></w:r></w:p>"
                + "<w:p><w:pPr><w:pageBreakBefore/></w:pPr><w:r><w:t>Next section</w:t><w:br w:type=\"page\"/><w:t>After page break</w:t></w:r></w:p>";

        var result = new WordLayoutExtractor().extractForIngestion(word(body, Map.of()));

        assertEquals(1, result.pages().size());
        assertEquals("none", result.pages().getFirst().coordinateUnit());
        assertEquals(2, result.pages().getFirst().blocks().stream().filter(block -> block.type().equals("page_break")).count());
        assertTrue(result.markdown().contains("> First line  \n> Second line"), result.markdown());
        assertTrue(result.markdown().contains("Next section"));
        assertTrue(result.markdown().contains("After page break"));
        assertFalse(result.text().contains("pagebreak"));
    }

    @Test void wordLinksBookmarksAndFootnotesKeepVisibleReferencesAndSafeTargets() throws Exception {
        String body = "<w:p><w:bookmarkStart w:id=\"1\" w:name=\"source_anchor\"/><w:r><w:t>Start </w:t></w:r>"
                + "<w:hyperlink r:id=\"rIdLink\"><w:r><w:t>Source</w:t></w:r></w:hyperlink>"
                + "<w:r><w:t> and </w:t></w:r><w:hyperlink w:anchor=\"source_anchor\"><w:r><w:t>back</w:t></w:r></w:hyperlink>"
                + "<w:r><w:footnoteReference w:id=\"1\"/><w:endnoteReference w:id=\"2\"/></w:r></w:p>"
                + "<w:p><w:hyperlink r:id=\"rIdUnsafe\"><w:r><w:t>Unsafe label</w:t></w:r></w:hyperlink></w:p>";
        Map<String, String> extra = new LinkedHashMap<>();
        extra.put("word/_rels/document.xml.rels", "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">"
                + "<Relationship Id=\"rIdLink\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/hyperlink\" Target=\"https://example.com/synthetic\" TargetMode=\"External\"/>"
                + "<Relationship Id=\"rIdUnsafe\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/hyperlink\" Target=\"javascript:alert(1)\" TargetMode=\"External\"/></Relationships>");
        extra.put("word/footnotes.xml", "<w:footnotes xmlns:w=\"" + OmmlLatexConverter.W + "\"><w:footnote w:id=\"1\"><w:p><w:r><w:t>Footnote evidence</w:t></w:r></w:p></w:footnote></w:footnotes>");
        extra.put("word/endnotes.xml", "<w:endnotes xmlns:w=\"" + OmmlLatexConverter.W + "\"><w:endnote w:id=\"2\"><w:p><w:r><w:t>Endnote evidence</w:t></w:r></w:p></w:endnote></w:endnotes>");

        var result = new WordLayoutExtractor().extractForIngestion(word(body, extra));

        assertTrue(result.markdown().contains("<a id=\"word-bookmark-source_anchor\"></a>"), result.markdown());
        assertTrue(result.markdown().contains("[Source](https://example.com/synthetic)"), result.markdown());
        assertTrue(result.markdown().contains("[back](#word-bookmark-source_anchor)"));
        assertTrue(result.markdown().contains("[^fn-1]"));
        assertTrue(result.markdown().contains("[^fn-1]: Footnote evidence"), result.markdown());
        assertTrue(result.markdown().contains("[^en-2]: Endnote evidence"));
        assertTrue(result.text().contains("Footnote evidence"));
        assertTrue(result.text().contains("Unsafe label"));
        assertFalse(result.markdown().contains("javascript:"));
        assertFalse(result.text().contains("example.com"));
    }

    @Test void powerpointKeepsRealTitleHardBreaksAndPictureAnchorSize() throws Exception {
        Path path = temp.resolve("synthetic.pptx");
        try (var show = new XMLSlideShow()) {
            var slide = show.createSlide();
            var title = slide.createTextBox(); title.setPlaceholder(Placeholder.TITLE); title.setText("Actual title");
            slide.createTextBox().setText("First line\nSecond line");
            var picture = slide.createPicture(show.addPicture(png(), PictureData.PictureType.PNG));
            picture.setAnchor(new Rectangle2D.Double(10, 20, 72, 36));
            try (var output = Files.newOutputStream(path)) { show.write(output); }
        }

        var result = DocumentFormatAdapter.extract(path, "pptx");
        var blocks = result.pages().getFirst().blocks();

        assertFalse(blocks.getFirst().indexed());
        assertFalse(result.text().contains("幻灯片"));
        assertTrue(blocks.stream().anyMatch(block -> block.type().equals("heading") && block.rawText().equals("Actual title")));
        assertTrue(result.markdown().contains("First line  \nSecond line"), result.markdown());
        var picture = blocks.stream().filter(block -> block.type().equals("figure")).findFirst().orElseThrow();
        assertEquals("ppt:1#2", picture.src());
        assertEquals(96.0, picture.presentation().widthPx());
        assertEquals(48.0, picture.presentation().heightPx());
        assertEquals(picture.src(), picture.presentation().sourceRef());
        assertNull(picture.rect());
    }

    @Test void pdfKeepsImageBoundingBoxAndMapsOnlySafeGlyphBackedLinks() throws Exception {
        Path path = temp.resolve("synthetic.pdf");
        try (var document = new PDDocument()) {
            var page = new PDPage(PDRectangle.LETTER); document.addPage(page);
            var font = new PDType1Font(Standard14Fonts.FontName.HELVETICA);
            try (var stream = new PDPageContentStream(document, page)) {
                stream.beginText(); stream.setFont(font, 12); stream.newLineAtOffset(60, 700);
                stream.showText("Visit Source for synthetic evidence."); stream.endText();
                stream.drawImage(LosslessFactory.createFromImage(document, new BufferedImage(32, 16, BufferedImage.TYPE_INT_RGB)), 80, 400, 160, 80);
            }
            float start = 60 + font.getStringWidth("Visit ") / 1000 * 12;
            var annotation = new PDAnnotationLink();
            annotation.setRectangle(new PDRectangle(start, 698, font.getStringWidth("Source") / 1000 * 12, 15));
            var action = new PDActionURI(); action.setURI("https://example.com/synthetic"); annotation.setAction(action);
            page.getAnnotations().add(annotation);
            var unsafe = new PDAnnotationLink(); unsafe.setRectangle(new PDRectangle(60, 698, 22, 15));
            var badAction = new PDActionURI(); badAction.setURI("javascript:alert(1)"); unsafe.setAction(badAction); page.getAnnotations().add(unsafe);
            document.save(path.toFile());
        }

        var result = new PdfLayoutExtractor().extract(path);
        var blocks = result.pages().getFirst().blocks();

        var image = blocks.stream().filter(block -> block.type().equals("figure")).findFirst().orElseThrow();
        assertArrayEquals(new double[]{80, 312, 240, 392}, image.rect(), 0.1);
        assertEquals(1, image.page());
        var linked = blocks.stream().filter(block -> block.markdown() != null).findFirst().orElseThrow();
        assertTrue(linked.markdown().contains("[Source](https://example.com/synthetic)"), linked.markdown());
        assertTrue(linked.text().contains("Visit Source for synthetic evidence."));
        assertFalse(linked.markdown().contains("javascript:"));
    }

    private Path word(String body, Map<String, String> parts) throws Exception {
        Path path = temp.resolve("synthetic.docx");
        String document = "<w:document xmlns:w=\"" + OmmlLatexConverter.W + "\" xmlns:r=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships\""
                + " xmlns:a=\"http://schemas.openxmlformats.org/drawingml/2006/main\" xmlns:wp=\"http://schemas.openxmlformats.org/drawingml/2006/wordprocessingDrawing\">"
                + "<w:body>" + body + "</w:body></w:document>";
        try (var zip = new ZipOutputStream(Files.newOutputStream(path))) {
            entry(zip, "word/document.xml", document);
            for (var part : parts.entrySet()) entry(zip, part.getKey(), part.getValue());
        }
        return path;
    }

    private static String picture(String relationship, String placement, long width, long height) {
        return "<w:r><w:drawing><wp:" + placement + "><wp:extent cx=\"" + width + "\" cy=\"" + height + "\"/>"
                + "<a:graphic><a:blip r:embed=\"" + relationship + "\"/></a:graphic></wp:" + placement + "></w:drawing></w:r>";
    }

    private static void entry(ZipOutputStream zip, String name, String value) throws Exception {
        zip.putNextEntry(new ZipEntry(name)); zip.write(value.getBytes(StandardCharsets.UTF_8)); zip.closeEntry();
    }

    private static byte[] png() throws Exception {
        var output = new ByteArrayOutputStream(); ImageIO.write(new BufferedImage(16, 8, BufferedImage.TYPE_INT_RGB), "png", output); return output.toByteArray();
    }
}
