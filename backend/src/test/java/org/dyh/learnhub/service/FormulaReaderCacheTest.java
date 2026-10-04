package org.dyh.learnhub.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.dyh.learnhub.entity.FileInfo;
import org.dyh.learnhub.mapper.FileInfoMapper;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.parallel.ResourceLock;

import javax.imageio.ImageIO;
import java.io.ByteArrayInputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ResourceLock("java.lang.System.properties")
class FormulaReaderCacheTest {
    @TempDir Path temp;
    private String originalDir;
    private FileStorageService files;
    private FileInfo info;
    private final double[] rect = {20, 40, 60, 72};

    @BeforeEach void prepare() throws Exception {
        originalDir = System.getProperty("user.dir");
        System.setProperty("user.dir", temp.toString());
        Files.createDirectories(temp.resolve("uploads"));
        writeSource(1);
        info = new FileInfo(); info.setId(5L); info.setExt("pdf"); info.setStoreName("math.pdf");
        info.setTextContent("retrieval text remains unchanged");
        var mapper = mock(FileInfoMapper.class); when(mapper.selectById(5L)).thenReturn(info);
        var extractor = mock(PdfLayoutExtractor.class);
        when(extractor.extract(any())).thenReturn(new PdfLayoutExtractor.Layout("ok", null,
                List.of(new PdfLayoutExtractor.PageLayout(1, 1, List.of(
                        new PdfLayoutExtractor.Block("formula", "detached original", 0, null, 1, rect,
                                "x t", "partial", "geometry is uncertain")))), 20, 1));
        files = new FileStorageService(mapper, null, null, extractor, null, null, null, null, null);
    }

    @AfterEach void restore() { System.setProperty("user.dir", originalDir); }

    private void writeSource(int pages) throws Exception {
        try (var document = new PDDocument()) {
            for (int i = 0; i < pages; i++) {
                var page = new PDPage(PDRectangle.LETTER); document.addPage(page);
                try (var canvas = new PDPageContentStream(document, page)) {
                    canvas.addRect(25, 792 - 55, 10, 10); canvas.fill();
                }
            }
            document.save(temp.resolve("uploads/math.pdf").toFile());
        }
    }

    private PdfLayoutExtractor.Block block() {
        var pages = (List<?>) files.layoutOf(5L).get("pages");
        return ((PdfLayoutExtractor.PageLayout) pages.getFirst()).blocks().getFirst();
    }

    @Test void recognitionCropContainsClearHighResolutionPixelsAndWhiteBackground() throws Exception {
        var image = ImageIO.read(new ByteArrayInputStream(files.formulaImage(5L, 1, rect)));
        assertEquals(184, image.getWidth()); // 44 pt with 2 pt padding at 300 DPI.
        assertEquals(151, image.getHeight());
        assertEquals(0xffffff, image.getRGB(0, 0) & 0xffffff);
        assertEquals(0, image.getRGB(50, 50) & 0xffffff, "the selected vector content must be rendered, not an empty crop");
        assertThrows(IllegalArgumentException.class, () -> files.formulaImage(5L, 2, rect));
        assertThrows(IllegalArgumentException.class, () -> files.formulaImage(5L, 1, new double[]{0, 0, 612, 792}));
        assertThrows(IllegalArgumentException.class, () -> files.formulaImage(5L, 1, new double[]{0, 0, Double.NaN, 10}));
        assertThrows(IllegalArgumentException.class, () -> files.formulaImage(5L, 1, new double[]{800, 800, 900, 900}));
    }

    @Test void openingReaderUsesSameSourceRecognitionAndRetainsOriginalForComparison() throws Exception {
        String key = files.formulaRecognitionKey(5L, 1, rect);
        var cached = files.formulaRecognitionCachePath(5L, key);
        Files.createDirectories(cached.getParent());
        new ObjectMapper().writeValue(cached.toFile(), Map.of("latex", "x_{t+1}", "status", "recognized",
                "profile", "Vision", "model", "image-model", "message", "请核对"));
        var result = block();
        assertEquals("x_{t+1}", result.latex()); assertEquals("recognized", result.latexStatus());
        assertEquals("detached original", result.text()); assertArrayEquals(rect, result.rect());
        assertTrue(result.latexMessage().contains("image-model"));
        assertTrue(result.latexMessage().contains("已缓存"));
        assertEquals("retrieval text remains unchanged", info.getTextContent());
        writeSource(2);
        assertNotEquals(key, files.formulaRecognitionKey(5L, 1, rect));
        assertEquals("x t", block().latex(), "another source version must not reuse an old recognition");
    }

    @Test void malformedOversizedAndUnrelatedCacheNeverBreaksReader() throws Exception {
        var cached = files.formulaRecognitionCachePath(5L, files.formulaRecognitionKey(5L, 1, rect));
        Files.createDirectories(cached.getParent()); Files.writeString(cached, "not json");
        assertEquals("partial", block().latexStatus());
        Files.writeString(cached, " ".repeat(65_537)); assertEquals("x t", block().latex());
        new ObjectMapper().writeValue(cached.toFile(), Map.of("latex", "bad replacement", "status", "failed"));
        assertEquals("x t", block().latex());
        assertThrows(IllegalArgumentException.class, () -> files.formulaRecognitionCachePath(5L, "../../unexpected"));
    }
}
