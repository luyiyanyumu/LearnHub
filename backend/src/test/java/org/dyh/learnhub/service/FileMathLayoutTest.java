package org.dyh.learnhub.service;

import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.dyh.learnhub.entity.FileInfo;
import org.dyh.learnhub.mapper.FileInfoMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTP;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class FileMathLayoutTest {
    @TempDir Path temp;
    private String previousDirectory;
    private FileInfo info;
    private FileStorageService files;

    @BeforeEach void prepare() throws Exception {
        previousDirectory = System.getProperty("user.dir");
        System.setProperty("user.dir", temp.toString());
        Files.createDirectories(temp.resolve("uploads"));
        info = new FileInfo(); info.setId(7L); info.setTextStatus("ok"); info.setTextContent("old cached text");
        var mapper = mock(FileInfoMapper.class);
        when(mapper.selectById(7L)).thenReturn(info);
        files = new FileStorageService(mapper, null, new DocumentTextService(), new PdfLayoutExtractor(),
                new WordLayoutExtractor(), new TextMathLayout(), null, null, null);
    }
    @AfterEach void restoreDirectory() { System.setProperty("user.dir", previousDirectory); }

    private List<PdfLayoutExtractor.Block> blocks(Map<String, Object> layout) {
        List<?> pages = (List<?>) layout.get("pages");
        return ((PdfLayoutExtractor.PageLayout) pages.getFirst()).blocks();
    }

    @Test void markdownLayoutReadsCurrentOriginalAndReturnsLatex() throws Exception {
        info.setExt("md"); info.setStoreName("math.md");
        Files.writeString(temp.resolve("uploads/math.md"), "Before.\n\n$$\\frac{a}{b}$$\n\nAfter.");
        var layout = files.layoutOf(7L);
        assertEquals("ok", layout.get("status"));
        assertEquals("\\frac{a}{b}", blocks(layout).get(1).latex());
        assertFalse(blocks(layout).stream().anyMatch(block -> block.text().contains("old cached")));
        assertEquals("old cached text", info.getTextContent(), "opening the reader must not rewrite the retrieval cache");
        if (Boolean.getBoolean("reader.math.qa")) {
            Path target = Path.of(previousDirectory, "target"); Files.createDirectories(target);
            new com.fasterxml.jackson.databind.ObjectMapper().writeValue(target.resolve("math-reader-markdown-layout.json").toFile(), layout);
            Files.copy(temp.resolve("uploads/math.md"), target.resolve("math-reader-markdown.md"), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        }
    }

    @Test void wordLayoutReturnsAnOmmlFractionInsteadOfLosingMath() throws Exception {
        info.setExt("docx"); info.setStoreName("math.docx");
        try (var document = new XWPFDocument()) {
            document.createParagraph().createRun().setText("Before the formula.");
            document.getDocument().getBody().addNewP().set(CTP.Factory.parse("""
                    <xml-fragment xmlns:m="http://schemas.openxmlformats.org/officeDocument/2006/math">
                      <m:oMathPara><m:oMath><m:f><m:num><m:r><m:t>a</m:t></m:r></m:num>
                        <m:den><m:r><m:t>b</m:t></m:r></m:den></m:f></m:oMath></m:oMathPara>
                    </xml-fragment>"""));
            document.createParagraph().createRun().setText("After the formula.");
            try (var output = Files.newOutputStream(temp.resolve("uploads/math.docx"))) { document.write(output); }
        }
        var layout = files.layoutOf(7L);
        assertEquals("ok", layout.get("status"));
        var equation = blocks(layout).stream().filter(block -> block.type().equals("formula")).findFirst().orElseThrow();
        assertTrue(equation.latex().contains("\\frac{a}{b}"), equation.latex());
        assertTrue(blocks(layout).stream().anyMatch(block -> block.text().contains("After the formula.")));
        if (Boolean.getBoolean("reader.math.qa")) {
            Path target = Path.of(previousDirectory, "target"); Files.createDirectories(target);
            var json = new com.fasterxml.jackson.databind.ObjectMapper();
            json.writeValue(target.resolve("math-reader-word-layout.json").toFile(), layout);
            json.writeValue(target.resolve("math-reader-word-preview.json").toFile(),
                    new DocumentPreviewService(null).render(temp.resolve("uploads/math.docx"), "docx"));
            Files.copy(temp.resolve("uploads/math.docx"), target.resolve("math-reader-word.docx"), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        }
    }

    @Test void unsupportedAndMissingSourcesRemainExplicit() {
        info.setExt("xls");
        assertEquals("unsupported", files.layoutOf(7L).get("status"));
        info.setExt("docx"); info.setStoreName("missing.docx");
        assertEquals("missing", files.layoutOf(7L).get("status"));
    }
}
