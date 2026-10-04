package org.dyh.learnhub.service;

import org.apache.poi.wp.usermodel.HeaderFooterType;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTP;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.*;

class WordLayoutExtractorTest {
    @TempDir Path temp;
    private final WordLayoutExtractor extractor = new WordLayoutExtractor();

    @Test void trueWordEquationsAreRestoredAndEveryOtherContentPartSurvives() throws Exception {
        Path path = temp.resolve("math.docx");
        try (XWPFDocument document = new XWPFDocument()) {
            var heading = document.createParagraph(); heading.setStyle("Heading2"); heading.createRun().setText("公式与正文");
            addXml(document, "<w:r><w:t>先读 </w:t></w:r><m:oMath>" + fraction() + "</m:oMath><w:r><w:t> 再读正文</w:t></w:r>");
            addXml(document, "<m:oMathPara><m:oMath>" + fraction() + "</m:oMath></m:oMathPara>");
            var table = document.createTable(1, 2); table.getRow(0).getCell(0).setText("表格第一格");
            table.getRow(0).getCell(1).setText("表格第二格");
            var footnote = document.createFootnote(); footnote.createParagraph().createRun().setText("脚注不丢");
            var endnote = document.createEndnote(); endnote.createParagraph().createRun().setText("尾注不丢");
            var comment = document.createComments().createComment(BigInteger.valueOf(3)); comment.setAuthor("审阅人");
            comment.createParagraph().createRun().setText("批注不丢");
            document.createHeader(HeaderFooterType.DEFAULT).createParagraph().createRun().setText("页眉不丢");
            document.createFooter(HeaderFooterType.DEFAULT).createParagraph().createRun().setText("页脚不丢");
            document.createParagraph().createRun().setText("最后正文");
            try (var output = Files.newOutputStream(path)) { document.write(output); }
        }
        var layout = extractor.extract(path);
        assertEquals("ok", layout.status());
        var blocks = layout.pages().getFirst().blocks();
        assertEquals("heading", blocks.getFirst().type());
        assertEquals(2, blocks.getFirst().level());
        assertEquals("先读 \\(\\frac{a}{b}\\) 再读正文", blocks.get(1).text());
        assertEquals("formula", blocks.get(2).type());
        assertEquals("\\frac{a}{b}", blocks.get(2).latex());
        assertEquals("restored", blocks.get(2).latexStatus());
        String text = extractor.plainText(path);
        assertTrue(text.contains("$$\\frac{a}{b}$$"), text);
        for (String content : List.of("表格第一格", "表格第二格", "脚注不丢", "尾注不丢", "批注不丢", "审阅人", "页眉不丢", "页脚不丢", "最后正文"))
            assertTrue(text.contains(content), "丢失：" + content + "\n" + text);
        assertTrue(text.indexOf("最后正文") < text.indexOf("脚注不丢"));
        var stored = new DocumentTextService().extract(path, "docx", Files.size(path));
        assertEquals("ok", stored.status());
        assertTrue(stored.text().contains("\\frac{a}{b}"));
        if (Boolean.getBoolean("reader.qa.export")) {
            Path target = Path.of("target").toAbsolutePath(); Files.createDirectories(target);
            Files.copy(path, target.resolve("reader-latex-qa.docx"), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            new com.fasterxml.jackson.databind.ObjectMapper().writeValue(target.resolve("reader-latex-qa-word-layout.json").toFile(), layout);
        }
    }

    @Test void equationsInsideTablesNotesAndContentControlsRetainLatexAndPosition() throws Exception {
        String body = "<w:p><w:r><w:t>开头</w:t></w:r></w:p>"
                + "<w:sdt><w:sdtContent><w:p><w:r><w:t>内容控件 </w:t></w:r><m:oMath>" + fraction() + "</m:oMath></w:p></w:sdtContent></w:sdt>"
                + "<w:tbl><w:tr><w:tc><w:p><w:r><w:t>单元格 </w:t></w:r><m:oMath>" + fraction() + "</m:oMath></w:p></w:tc></w:tr></w:tbl>"
                + "<w:p><w:r><w:drawing><w:txbxContent><w:p><w:r><w:t>文本框正文</w:t></w:r></w:p></w:txbxContent></w:drawing></w:r></w:p>";
        Path path = zip("structures.docm", xml(body));
        var blocks = extractor.extract(path).pages().getFirst().blocks();
        assertEquals("开头", blocks.getFirst().text());
        assertEquals("内容控件 \\(\\frac{a}{b}\\)", blocks.get(1).text());
        assertEquals("table", blocks.get(2).type());
        assertTrue(blocks.get(2).text().contains("\\(\\frac{a}{b}\\)"));
        assertEquals("文本框正文", blocks.get(3).text());
        assertEquals("ok", new DocumentTextService().extract(path, "docm", Files.size(path)).status());
    }

    @Test void unsupportedMathContentIsKeptAndShownAsPartial() throws Exception {
        Path path = zip("partial.docx", xml("<w:p><m:oMath><m:mystery>" + OmmlLatexConverterTest.run("x") + "</m:mystery></m:oMath></w:p>"));
        var block = extractor.extract(path).pages().getFirst().blocks().getFirst();
        assertEquals("formula", block.type());
        assertEquals("x", block.text());
        assertEquals("partial", block.latexStatus());
        assertTrue(block.latexMessage().contains("mystery"));
        assertTrue(extractor.plainText(path).contains("公式未完整还原"));
    }

    @Test void contentControlsAroundTableRowsAndCellsDoNotLoseTheirContents() throws Exception {
        String body = "<w:tbl><w:sdt><w:sdtContent><w:tr><w:sdt><w:sdtContent><w:tc><w:p><w:r><w:t>包装单元格</w:t></w:r></w:p></w:tc>"
                + "</w:sdtContent></w:sdt></w:tr></w:sdtContent></w:sdt></w:tbl>";
        Path path = zip("wrapped-table.docx", xml(body));
        var block = extractor.extract(path).pages().getFirst().blocks().getFirst();
        assertEquals("table", block.type());
        assertEquals("包装单元格", block.text());
    }

    @Test void inlineNumericEquationIsExplicitSoItCannotBeMistakenForCurrency() throws Exception {
        Path path = zip("number.docx", xml("<w:p><w:r><w:t>数值 </w:t></w:r><m:oMath>" + OmmlLatexConverterTest.run("2") + "</m:oMath></w:p>"));
        assertEquals("数值 \\(2\\)", extractor.extract(path).pages().getFirst().blocks().getFirst().text());
    }

    @Test void equationInsideFootnoteIsRestoredWithoutBeingMovedIntoBody() throws Exception {
        Path path = temp.resolve("note-math.docx");
        try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(path))) {
            zip.putNextEntry(new ZipEntry("word/document.xml")); zip.write(xml("<w:p><w:r><w:t>正文</w:t></w:r></w:p>").getBytes(StandardCharsets.UTF_8)); zip.closeEntry();
            zip.putNextEntry(new ZipEntry("word/footnotes.xml"));
            zip.write(("<w:footnotes xmlns:w=\"" + OmmlLatexConverter.W + "\" xmlns:m=\"" + OmmlLatexConverter.M + "\"><w:footnote w:id=\"3\"><w:p><w:r><w:t>脚注公式 </w:t></w:r><m:oMath>" + fraction() + "</m:oMath></w:p></w:footnote></w:footnotes>").getBytes(StandardCharsets.UTF_8)); zip.closeEntry();
        }
        var blocks = extractor.extract(path).pages().getFirst().blocks();
        assertEquals("正文", blocks.getFirst().text());
        assertEquals("note", blocks.get(2).type());
        assertEquals("脚注公式 \\(\\frac{a}{b}\\)", blocks.get(2).text());
    }

    @Test void externalEntityAndDtdCannotReadLocalFiles() throws Exception {
        Path secret = temp.resolve("secret.txt"); Files.writeString(secret, "MUST_NOT_LEAK");
        String document = "<!DOCTYPE w:document [<!ENTITY xxe SYSTEM '" + secret.toUri() + "'>]>"
                + xml("<w:p><w:r><w:t>&xxe;</w:t></w:r></w:p>");
        Path path = zip("xxe.docx", document);
        var error = assertThrows(java.io.IOException.class, () -> extractor.extract(path));
        assertTrue(error.getMessage().contains("安全解析"));
        var stored = new DocumentTextService().extract(path, "docx", Files.size(path));
        assertEquals("failed", stored.status());
        assertNull(stored.text());
    }

    @Test void excessiveWordNestingFailsWithAnExplicitLimit() throws Exception {
        Path path = zip("deep.docx", xml("<w:sdt>".repeat(120) + "<w:p><w:r><w:t>x</w:t></w:r></w:p>" + "</w:sdt>".repeat(120)));
        assertTrue(assertThrows(java.io.IOException.class, () -> extractor.extract(path)).getMessage().contains("过深"));
    }

    @Test void equationObjectCannotBeSilentlyDiscarded() throws Exception {
        String body = "<w:p><w:object><o:OLEObject xmlns:o=\"urn:schemas-microsoft-com:office:office\" ProgID=\"Equation.3\"/></w:object></w:p>";
        Path path = zip("old-equation.docx", xml(body));
        var block = extractor.extract(path).pages().getFirst().blocks().getFirst();
        assertEquals("unavailable", block.latexStatus());
        assertTrue(block.latexMessage().contains("OLE/MathType"));
    }

    private static String fraction() { return "<m:f><m:num>" + OmmlLatexConverterTest.run("a") + "</m:num><m:den>" + OmmlLatexConverterTest.run("b") + "</m:den></m:f>"; }
    private static String xml(String body) { return "<w:document xmlns:w=\"" + OmmlLatexConverter.W + "\" xmlns:m=\"" + OmmlLatexConverter.M + "\"><w:body>" + body + "</w:body></w:document>"; }
    private void addXml(XWPFDocument document, String contents) throws Exception {
        document.getDocument().getBody().addNewP().set(CTP.Factory.parse("<xml-fragment xmlns:w=\"" + OmmlLatexConverter.W + "\" xmlns:m=\"" + OmmlLatexConverter.M + "\">" + contents + "</xml-fragment>"));
    }
    private Path zip(String name, String document) throws Exception {
        Path path = temp.resolve(name);
        try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(path))) {
            zip.putNextEntry(new ZipEntry("word/document.xml")); zip.write(document.getBytes(StandardCharsets.UTF_8)); zip.closeEntry();
        }
        return path;
    }
}
