package org.dyh.learnhub.service;

import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.UnderlinePatterns;
import org.apache.poi.wp.usermodel.HeaderFooterType;
import org.apache.poi.util.Units;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTP;
import org.springframework.core.io.FileSystemResource;
import org.springframework.http.MediaType;

import java.io.ByteArrayInputStream;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class DocumentPreviewServiceTest {
    @TempDir Path temp;
    private final DocumentPreviewService service = new DocumentPreviewService(null);

    @Test
    void markdownKeepsOriginalSourceAndStripsOnlyUtf8Bom() throws Exception {
        String original = "# 标题\r\n\r\n<!-- 保留注释 -->\r\n $a^2$  两个空格  \r\n";
        Path path = temp.resolve("original.markdown");
        Files.writeString(path, "\uFEFF" + original, StandardCharsets.UTF_8);
        var result = service.render(path, "markdown");
        assertEquals("markdown", result.get("format"));
        assertEquals(original, result.get("source"));
        assertEquals("", result.get("html"));
    }

    @Test
    void invalidUtf8RefusesRatherThanShowingReplacementCharacters() throws Exception {
        Path path = temp.resolve("bad.md");
        Files.write(path, new byte[]{(byte) 0xc3, 0x28});
        var error = assertThrows(IllegalArgumentException.class, () -> service.render(path, "md"));
        assertTrue(error.getMessage().contains("UTF-8"));
    }

    @Test
    void endpointServiceReadsOriginalAndNeverRetrievalText() throws Exception {
        Path path = temp.resolve("original.md");
        Files.writeString(path, "# Original\n\nA formula $x$", StandardCharsets.UTF_8);
        FileStorageService files = mock(FileStorageService.class);
        when(files.download(42L)).thenReturn(new FileStorageService.DownloadItem(new FileSystemResource(path), "original.md", "Original.MD", MediaType.TEXT_PLAIN));
        var result = new DocumentPreviewService(files).preview(42L);
        assertEquals(42L, result.get("id"));
        assertEquals("md", result.get("ext"));
        assertEquals("# Original\n\nA formula $x$", result.get("source"));
        verify(files).download(42L);
        verifyNoMoreInteractions(files);
    }

    @Test
    void wordPreservesOrderHeadingFormattingTableAndEmbeddedImage() throws Exception {
        Path path = temp.resolve("rich.docx");
        try (var doc = new XWPFDocument()) {
            var heading = doc.createParagraph(); heading.setStyle("Heading2"); heading.createRun().setText("第二节");
            var paragraph = doc.createParagraph();
            var run = paragraph.createRun(); run.setText("<script>alert('x')</script>");
            run.setBold(true); run.setItalic(true); run.setColor("FF0000"); run.setUnderline(UnderlinePatterns.SINGLE); run.setFontSize(14);
            doc.createTable(1, 2).getRow(0).getCell(0).setText("表格原文");
            byte[] png = java.util.Base64.getDecoder().decode("iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+jxZkAAAAASUVORK5CYII=");
            doc.createParagraph().createRun().addPicture(new ByteArrayInputStream(png), XWPFDocument.PICTURE_TYPE_PNG, "pixel.png", Units.toEMU(20), Units.toEMU(20));
            write(doc, path);
        }
        var result = service.render(path, "docx");
        String html = (String) result.get("html");
        assertEquals("word", result.get("format"));
        assertTrue(html.contains("<h2"), html);
        assertTrue(html.contains("<strong><em>"), html);
        assertTrue(html.contains("color:#FF0000"), html);
        assertTrue(html.contains("font-size:14.0pt"));
        assertTrue(html.contains("text-decoration:underline"));
        assertTrue(html.contains("<table><tbody><tr><td>"), html);
        assertTrue(html.contains("data:image/png;base64,"), html);
        assertTrue(html.indexOf("第二节") < html.indexOf("表格原文"));
        assertFalse(html.contains("<script>"));
        assertTrue(html.contains("&lt;script&gt;"));
    }

    @Test
    void wordKeepsFootnotesCommentsHeadersAndReadableFormula() throws Exception {
        Path path = temp.resolve("annotations.docx");
        try (var doc = new XWPFDocument()) {
            var heading = doc.createParagraph(); heading.setStyle("Heading1"); heading.createRun().setText("Word 原文阅读 · 测试文档");
            var introduction = doc.createParagraph().createRun(); introduction.setBold(true); introduction.setColor("C64F55");
            introduction.setText("Original documents preserve structure, formatting, and references.");
            doc.createParagraph().createRun().setText("Select any sentence to translate it. The original stays visible while the translation appears in the side panel.");
            var table = doc.createTable(2, 2); table.getRow(0).getCell(0).setText("Original format"); table.getRow(0).getCell(1).setText("Reading support");
            table.getRow(1).getCell(0).setText("Word / Markdown / PDF"); table.getRow(1).getCell(1).setText("Selection translation");
            var note = doc.createFootnote(); note.createParagraph().createRun().setText("脚注不能丢");
            var paragraph = doc.createParagraph(); paragraph.createRun().setText("有脚注");
            paragraph.createRun().getCTR().addNewFootnoteReference().setId(note.getId());
            var comment = doc.createComments().createComment(BigInteger.valueOf(7)); comment.setAuthor("作者");
            comment.createParagraph().createRun().setText("这里是批注");
            paragraph.createRun().getCTR().addNewCommentReference().setId(BigInteger.valueOf(7));
            doc.createHeader(HeaderFooterType.DEFAULT).createParagraph().createRun().setText("页眉文字");
            doc.getDocument().getBody().addNewP().set(CTP.Factory.parse("""
                    <xml-fragment xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main"
                         xmlns:m="http://schemas.openxmlformats.org/officeDocument/2006/math">
                      <m:oMath><m:f><m:num><m:r><m:t>a</m:t></m:r></m:num><m:den><m:r><m:t>b</m:t></m:r></m:den></m:f>
                        <m:sSup><m:e><m:r><m:t>x</m:t></m:r></m:e><m:sup><m:r><m:t>2</m:t></m:r></m:sup></m:sSup>
                      </m:oMath>
                    </xml-fragment>"""));
            write(doc, path);
        }
        var result = service.render(path, "docx");
        String html = (String) result.get("html");
        assertTrue(html.contains("脚注不能丢"), html);
        assertTrue(html.contains("这里是批注"), html);
        assertTrue(html.contains("作者"), html);
        assertTrue(html.contains("页眉文字"), html);
        assertTrue(html.contains("href=\"#word-footnote-"), html);
        assertTrue(html.contains("href=\"#word-comment-7"), html);
        assertTrue(html.contains("(a)/(b)x<sup>2</sup>"), html);
        assertTrue(warnings(result).stream().anyMatch(w -> w.contains("公式")));
        assertTrue(warnings(result).stream().anyMatch(w -> w.contains("页眉")));
        if (Boolean.getBoolean("reader.qa.export")) {
            Path target = Path.of("target").toAbsolutePath(); Files.createDirectories(target);
            Path fixture = target.resolve("reader-qa.docx"); Files.copy(path, fixture, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            FileStorageService files = mock(FileStorageService.class);
            when(files.download(987654321L)).thenReturn(new FileStorageService.DownloadItem(new FileSystemResource(fixture), "reader-qa.docx", "原文阅读测试.docx", MediaType.APPLICATION_OCTET_STREAM));
            var preview = new DocumentPreviewService(files).preview(987654321L);
            new com.fasterxml.jackson.databind.ObjectMapper().writeValue(target.resolve("reader-qa-word.json").toFile(), preview);
        }
    }

    @Test
    void safeLinksOnlyAndNoExternalImageRequests() throws Exception {
        Path path = temp.resolve("links.docx");
        try (var doc = new XWPFDocument()) {
            var p = doc.createParagraph();
            p.createHyperlinkRun("javascript:alert(1)").setText("危险链接文字仍可读");
            p.createHyperlinkRun("https://example.com/?a=1&b=2").setText("安全链接");
            doc.getDocument().getBody().addNewP().set(CTP.Factory.parse("""
                    <xml-fragment xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main"
                         xmlns:a="http://schemas.openxmlformats.org/drawingml/2006/main"
                         xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships">
                      <w:r><w:drawing><a:blip r:link="externalImage"/></w:drawing></w:r>
                    </xml-fragment>"""));
            write(doc, path);
        }
        var result = service.render(path, "docx");
        String html = (String) result.get("html");
        assertTrue(html.contains("危险链接文字仍可读"));
        assertFalse(html.contains("javascript:"));
        assertTrue(html.contains("https://example.com/?a=1&amp;b=2"));
        assertFalse(html.contains("externalImage"));
        assertTrue(warnings(result).stream().anyMatch(w -> w.contains("外部链接图片")));
    }

    @Test
    void imageRelationshipsInsideHeaderAreResolvedInTheirOwnPart() throws Exception {
        Path path = temp.resolve("header-picture.docx");
        try (var doc = new XWPFDocument()) {
            doc.createParagraph().createRun().setText("Document body");
            byte[] png = java.util.Base64.getDecoder().decode("iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+jxZkAAAAASUVORK5CYII=");
            doc.createHeader(HeaderFooterType.DEFAULT).createParagraph().createRun()
                    .addPicture(new ByteArrayInputStream(png), XWPFDocument.PICTURE_TYPE_PNG, "header.png", Units.EMU_PER_PIXEL * 35, Units.EMU_PER_PIXEL * 35);
            write(doc, path);
        }
        String html = (String) service.render(path, "docx").get("html");
        assertTrue(html.contains("data:image/png;base64,"), html);
        assertTrue(html.contains("width:35.0px"), html);
    }

    @Test
    void headingUsesNamedStyleWhenIdIsNotHeadingAndMergedCellsPreserveTheirContent() throws Exception {
        Path path = temp.resolve("styles.docm");
        try (var doc = new XWPFDocument()) {
            var styles = doc.createStyles();
            var style = org.openxmlformats.schemas.wordprocessingml.x2006.main.CTStyle.Factory.newInstance();
            style.setStyleId("custom-id"); style.addNewName().setVal("Heading 3");
            styles.addStyle(new org.apache.poi.xwpf.usermodel.XWPFStyle(style));
            var p = doc.createParagraph(); p.setStyle("custom-id"); p.createRun().setText("Named heading");
            var table = doc.createTable(1, 1); var cell = table.getRow(0).getCell(0);
            cell.setText("Merged cell text"); cell.getCTTc().addNewTcPr().addNewGridSpan().setVal(BigInteger.TWO);
            write(doc, path);
        }
        String html = (String) service.render(path, "docm").get("html");
        assertTrue(html.contains("<h3"), html);
        assertTrue(html.contains("<td colspan=\"2\">"), html);
        assertTrue(html.contains("Merged cell text"));
    }

    @Test
    void oversizedEmbeddedPictureGetsExplicitWarning() throws Exception {
        Path path = temp.resolve("large-image.docx");
        try (var doc = new XWPFDocument()) {
            // Deterministic incompressible bytes avoid triggering POI's separate ZIP-bomb ratio guard.
            byte[] bytes = new byte[2 * 1024 * 1024 + 1]; new java.util.Random(1).nextBytes(bytes);
            doc.createParagraph().createRun().setText("正文保留");
            doc.createParagraph().createRun().addPicture(new ByteArrayInputStream(bytes), XWPFDocument.PICTURE_TYPE_PNG, "large.png", Units.toEMU(20), Units.toEMU(20));
            write(doc, path);
        }
        var result = service.render(path, "docx");
        assertTrue(((String) result.get("html")).contains("正文保留"));
        assertFalse(((String) result.get("html")).contains("data:image/"));
        assertTrue(warnings(result).stream().anyMatch(w -> w.contains("大小限制")));
    }

    @Test
    void compressedContentSizeIsLimitedBeforePoiParsing() throws Exception {
        Path path = temp.resolve("bomb.docx");
        try (var zip = new ZipOutputStream(Files.newOutputStream(path))) {
            zip.putNextEntry(new ZipEntry("word/document.xml"));
            byte[] chunk = new byte[1024 * 1024];
            for (int i = 0; i < 33; i++) zip.write(chunk);
            zip.closeEntry();
        }
        var error = assertThrows(IllegalArgumentException.class, () -> service.render(path, "docx"));
        assertTrue(error.getMessage().contains("解压"));
    }

    @Test
    void originalSizeAndUnsupportedFormatsAreExplicitlyRefused() throws Exception {
        Path path = temp.resolve("large.md");
        try (var channel = java.nio.channels.FileChannel.open(path, java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.WRITE)) {
            channel.position(20L * 1024 * 1024); channel.write(java.nio.ByteBuffer.wrap(new byte[]{1}));
        }
        assertTrue(assertThrows(IllegalArgumentException.class, () -> service.render(path, "md")).getMessage().contains("20 MiB"));
        assertThrows(IllegalArgumentException.class, () -> service.render(path, "html"));
    }

    @Test
    void corruptWordDoesNotPretendToBePlainTextOriginal() throws Exception {
        Path path = temp.resolve("corrupt.doc"); Files.writeString(path, "Some extracted-looking text");
        var error = assertThrows(IllegalStateException.class, () -> service.render(path, "doc"));
        assertTrue(error.getMessage().contains("原文预览失败"));
    }

    private void write(XWPFDocument doc, Path path) throws Exception {
        try (var out = Files.newOutputStream(path)) { doc.write(out); }
    }

    @SuppressWarnings("unchecked")
    private List<String> warnings(Map<String, Object> result) { return (List<String>) result.get("warnings"); }
}
