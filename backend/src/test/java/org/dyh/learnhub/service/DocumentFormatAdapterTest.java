package org.dyh.learnhub.service;

import org.apache.poi.xslf.usermodel.XMLSlideShow;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.apache.poi.xssf.usermodel.XSSFClientAnchor;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.io.ByteArrayOutputStream;
import java.awt.image.BufferedImage;
import javax.imageio.ImageIO;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.*;

class DocumentFormatAdapterTest {
    @TempDir Path temp;

    @Test void wordKeepsHeadingEquationTableAndInlineImageOrderWithoutEmbeddingAWordRelationship() throws Exception {
        Path source = word("<w:p><w:pPr><w:outlineLvl w:val=\"1\"/></w:pPr><w:r><w:t>Synthetic heading</w:t></w:r></w:p>"
                + "<w:p><w:r><w:t>Before image</w:t></w:r><w:r><w:drawing><a:blip r:embed=\"rId7\"/></w:drawing></w:r><w:r><w:t>After image</w:t></w:r></w:p>"
                + "<m:oMathPara><m:oMath><m:f><m:num><m:r><m:t>a</m:t></m:r></m:num><m:den><m:r><m:t>b</m:t></m:r></m:den></m:f></m:oMath></m:oMathPara>"
                + "<w:tbl><w:tr><w:tc><w:p><w:r><w:t>Name</w:t></w:r></w:p></w:tc><w:tc><w:p/></w:tc><w:tc><w:p><w:r><w:t>Value</w:t></w:r></w:p></w:tc></w:tr>"
                + "<w:tr><w:tc><w:p><w:r><w:t>Cedar</w:t></w:r></w:p></w:tc><w:tc><w:p/></w:tc><w:tc><w:p><w:r><w:t>7</w:t></w:r></w:p></w:tc></w:tr></w:tbl>");
        byte[] original = Files.readAllBytes(source);

        var result = new DocumentTextService().extractDetailed(source, "docx", Files.size(source));

        assertEquals("ok", result.status());
        assertEquals("markdown", result.format());
        assertTrue(result.text().contains("## Synthetic heading"));
        assertTrue(result.text().contains("\\frac{a}{b}"));
        assertTrue(result.text().contains("| Name |  | Value |"));
        assertTrue(result.text().contains("| Cedar |  | 7 |"));
        assertTrue(result.markdown().contains("[原图未能提取，请查看原件]"));
        assertFalse(result.markdown().contains("word:"));
        assertFalse(result.text().contains("word:"));
        var blocks = result.pages().getFirst().blocks();
        int before = blocks.stream().map(DocumentIngestionService.Block::cleanText).toList().indexOf("Before image");
        assertTrue(before >= 0);
        assertEquals("figure", blocks.get(before + 1).type());
        assertEquals("word:word/document.xml#rId7", blocks.get(before + 1).src());
        assertEquals("After image", blocks.get(before + 2).cleanText());
        assertArrayEquals(original, Files.readAllBytes(source));
        DocumentMarkdownWriterTest.assertOffsets(result);
    }

    @Test void spreadsheetsKeepBlankColumnPositionsFormulasAndSheetBoundaries() throws Exception {
        Path path = temp.resolve("synthetic.xlsx");
        try (var book = new XSSFWorkbook()) {
            var sheet = book.createSheet("Metrics");
            var heading = sheet.createRow(0); heading.createCell(0).setCellValue("Name"); heading.createCell(2).setCellValue("Score");
            var data = sheet.createRow(1); data.createCell(0).setCellValue("Cedar"); data.createCell(2).setCellFormula("1+1");
            book.createSheet("Other").createRow(0).createCell(0).setCellValue("Independent sheet");
            try (var output = Files.newOutputStream(path)) { book.write(output); }
        }

        var result = new DocumentTextService().extractDetailed(path, "xlsx", Files.size(path));

        assertEquals(2, result.pages().size());
        assertTrue(result.markdown().contains("# Metrics"));
        assertTrue(result.markdown().contains("# Other"));
        assertTrue(result.text().contains("| Name |  | Score |"));
        assertTrue(result.text().contains("| Cedar |  | =1+1 |"));
        assertEquals("sheet", result.pages().getFirst().coordinateUnit());
        DocumentMarkdownWriterTest.assertOffsets(result);
    }

    @Test void presentationsKeepTheSlideBoundaryRatherThanConcatenatingEveryShape() throws Exception {
        Path path = temp.resolve("synthetic.pptx");
        try (var show = new XMLSlideShow()) {
            show.createSlide().createTextBox().setText("First synthetic slide");
            show.createSlide().createTextBox().setText("Second synthetic slide");
            try (var output = Files.newOutputStream(path)) { show.write(output); }
        }

        var result = new DocumentTextService().extractDetailed(path, "pptx", Files.size(path));

        assertEquals(2, result.pages().size());
        assertEquals("slide", result.pages().getFirst().coordinateUnit());
        assertFalse(result.text().contains("幻灯片"));
        assertFalse(result.pages().getFirst().blocks().getFirst().indexed());
        assertFalse(result.pages().get(1).blocks().getFirst().indexed());
        assertTrue(result.pages().getFirst().blocks().stream().anyMatch(block -> block.cleanText().contains("First synthetic")));
        assertFalse(result.pages().getFirst().blocks().stream().anyMatch(block -> block.cleanText().contains("Second synthetic")));
        DocumentMarkdownWriterTest.assertOffsets(result);
    }

    @Test void originalMarkdownIsNotDoubleEscapedAndSourceCodeRetainsLiteralLinksAndFences() throws Exception {
        Path markdown = temp.resolve("synthetic.md"), code = temp.resolve("synthetic.java");
        String original = "# Heading\n\n- item\n\n[Visible source](https://example.com/synthetic)\n\n$$\nx^2\n$$\n\n```html\n<script>inert sample</script>\n```";
        String implementation = "// [literal sample](https://example.com/code)\nString delimiter = \"```\";\n";
        Files.writeString(markdown, original); Files.writeString(code, implementation);

        var md = new DocumentTextService().extractDetailed(markdown, "md", Files.size(markdown));
        var java = new DocumentTextService().extractDetailed(code, "java", Files.size(code));

        assertEquals(original, md.markdown());
        assertTrue(md.text().contains("Visible source"));
        assertFalse(md.text().contains("https://example.com/synthetic"));
        assertTrue(java.markdown().startsWith("````java\n"));
        assertTrue(java.markdown().endsWith("````"));
        assertTrue(java.text().contains(implementation));
        assertEquals(original, Files.readString(markdown));
        assertEquals(implementation, Files.readString(code));
        DocumentMarkdownWriterTest.assertOffsets(md); DocumentMarkdownWriterTest.assertOffsets(java);
    }

    @Test void csvPreservesQuotedNewlinesAndEmptyCellsWhilePlainTextCannotExecuteHtml() throws Exception {
        Path csv = temp.resolve("synthetic.csv"), plain = temp.resolve("synthetic.txt");
        Files.writeString(csv, "Name,Missing,Value\nCedar,,\"first\nsecond\"\n");
        Files.writeString(plain, "# literal heading\n\n<script>synthetic sample</script>");

        var table = new DocumentTextService().extractDetailed(csv, "csv", Files.size(csv));
        var text = new DocumentTextService().extractDetailed(plain, "txt", Files.size(plain));

        assertTrue(table.markdown().contains("| Cedar |  | first<br>second |"));
        assertTrue(text.markdown().contains("\\# literal heading"));
        assertFalse(text.markdown().contains("<script>"));
        DocumentMarkdownWriterTest.assertOffsets(table); DocumentMarkdownWriterTest.assertOffsets(text);
    }

    @Test void anUnrestoredLegacyFormulaIsVisibleForReviewButCannotBecomeIndexedEvidence() throws Exception {
        Path source = word("<w:p><w:r><w:t>Accepted paragraph.</w:t></w:r></w:p>"
                + "<w:p><w:r><w:object><v:imagedata r:id=\"rId9\"/><o:OLEObject ProgID=\"Equation.3\"/></w:object></w:r></w:p>");

        var result = new WordLayoutExtractor().extractForIngestion(source);

        assertTrue(result.text().contains("Accepted paragraph"));
        assertFalse(result.text().contains("嵌入式旧版公式"));
        assertTrue(result.markdown().contains("[原图未能提取，请查看原件]"));
        assertTrue(result.pages().getFirst().blocks().stream().anyMatch(block -> "word:word/document.xml#rId9".equals(block.src())));
        assertTrue(result.report().partial());
        assertTrue(result.pages().getFirst().blocks().stream().anyMatch(block -> block.latexStatus() != null
                && !block.indexed() && "unavailable".equals(block.latexStatus())));
    }

    @Test void officePicturesReferToTheCompleteShapeListInsteadOfRenumberingOnlyPictures() throws Exception {
        Path presentation = temp.resolve("pictures.pptx"), spreadsheet = temp.resolve("pictures.xlsx");
        byte[] png = picture();
        try (var show = new XMLSlideShow()) {
            var slide = show.createSlide(); slide.createTextBox().setText("Shape zero is text.");
            slide.createPicture(show.addPicture(png, org.apache.poi.sl.usermodel.PictureData.PictureType.PNG));
            try (var output = Files.newOutputStream(presentation)) { show.write(output); }
        }
        try (var book = new XSSFWorkbook()) {
            var sheet = book.createSheet("Pictures"); sheet.createRow(0).createCell(0).setCellValue("Synthetic sheet");
            var drawing = sheet.createDrawingPatriarch();
            drawing.createSimpleShape(new XSSFClientAnchor(0, 0, 0, 0, 0, 0, 1, 1))
                    .setShapeType(org.apache.poi.ss.usermodel.ShapeTypes.RECT);
            drawing.createPicture(new XSSFClientAnchor(0, 0, 0, 0, 1, 0, 2, 1), book.addPicture(png, org.apache.poi.ss.usermodel.Workbook.PICTURE_TYPE_PNG));
            try (var output = Files.newOutputStream(spreadsheet)) { book.write(output); }
        }

        var ppt = new DocumentTextService().extractDetailed(presentation, "pptx", Files.size(presentation));
        var xlsx = new DocumentTextService().extractDetailed(spreadsheet, "xlsx", Files.size(spreadsheet));

        var pptImage = ppt.pages().getFirst().blocks().stream().filter(block -> block.type().equals("figure")).findFirst().orElseThrow();
        var sheetImage = xlsx.pages().getFirst().blocks().stream().filter(block -> block.type().equals("figure")).findFirst().orElseThrow();
        assertEquals("ppt:1#1", pptImage.src());
        assertEquals("xlsx:0#1", sheetImage.src());
        assertFalse(pptImage.indexed()); assertFalse(sheetImage.indexed());
        assertNull(pptImage.rect()); assertNull(sheetImage.rect());
        assertFalse(ppt.text().contains("ppt:")); assertFalse(xlsx.text().contains("xlsx:"));
        assertTrue(ppt.markdown().contains("[原图未能提取，请查看原件]"));
        assertTrue(xlsx.markdown().contains("[原图未能提取，请查看原件]"));
        assertFalse(ppt.markdown().contains("ppt:")); assertFalse(xlsx.markdown().contains("xlsx:"));
        DocumentMarkdownWriterTest.assertOffsets(ppt); DocumentMarkdownWriterTest.assertOffsets(xlsx);
    }

    @Test void aStandaloneWordDrawingAtBothParagraphEdgesSurvivesTextNormalization() throws Exception {
        Path source = word("<w:p><w:r><w:drawing><a:blip r:embed=\"rId10\"/></w:drawing></w:r></w:p>"
                + "<w:p><w:r><w:t>Accepted synthetic text after the picture.</w:t></w:r></w:p>");
        var result = new WordLayoutExtractor().extractForIngestion(source);
        assertEquals("figure", result.pages().getFirst().blocks().getFirst().type());
        assertEquals("word:word/document.xml#rId10", result.pages().getFirst().blocks().getFirst().src());
        assertTrue(result.markdown().contains("[原图未能提取，请查看原件]"));
        assertFalse(result.markdown().contains("word:"));
        assertTrue(result.text().contains("Accepted synthetic text"));
        assertFalse(result.text().contains("image0"));
        assertFalse(result.markdown().contains("\u0001"));
        assertFalse(result.markdown().contains("\u0002"));
        DocumentMarkdownWriterTest.assertOffsets(result);
    }

    private static byte[] picture() throws Exception {
        BufferedImage image = new BufferedImage(4, 4, BufferedImage.TYPE_INT_RGB);
        try { ByteArrayOutputStream out = new ByteArrayOutputStream(); ImageIO.write(image, "png", out); return out.toByteArray(); }
        finally { image.flush(); }
    }

    private Path word(String body) throws Exception {
        Path path = temp.resolve("synthetic.docx");
        try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(path))) {
            zip.putNextEntry(new ZipEntry("word/document.xml"));
            zip.write(("<w:document xmlns:w=\"http://schemas.openxmlformats.org/wordprocessingml/2006/main\" "
                    + "xmlns:m=\"http://schemas.openxmlformats.org/officeDocument/2006/math\" "
                    + "xmlns:r=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships\" "
                    + "xmlns:a=\"http://schemas.openxmlformats.org/drawingml/2006/main\" "
                    + "xmlns:v=\"urn:schemas-microsoft-com:vml\" xmlns:o=\"urn:schemas-microsoft-com:office:office\">"
                    + "<w:body>" + body + "</w:body></w:document>").getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
        }
        return path;
    }

}
