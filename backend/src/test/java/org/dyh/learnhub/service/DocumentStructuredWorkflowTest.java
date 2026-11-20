package org.dyh.learnhub.service;

import org.dyh.learnhub.entity.FileInfo;
import org.dyh.learnhub.mapper.FileInfoMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.context.ApplicationEventPublisher;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.dyh.learnhub.service.DocumentIngestionService.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class DocumentStructuredWorkflowTest {
    @TempDir Path work;

    @Test void structuredFormulaIsCroppedAndExcludedUntilRevisionBoundReview() throws Exception {
        var path = Files.createDirectories(work.resolve("uploads")).resolve("synthetic.png");
        ImageIO.write(new BufferedImage(120, 60, BufferedImage.TYPE_INT_RGB), "png", path.toFile());
        var file = new FileInfo();
        file.setId(8L);
        file.setOriginName("合成扫描页.png");
        file.setStoreName("synthetic.png");
        file.setExt("png");
        file.setSize(Files.size(path));
        var mapper = mock(FileInfoMapper.class);
        when(mapper.selectById(8L)).thenReturn(file);
        var parser = mock(DocumentTextService.class);
        var ocr = mock(DocumentOcrClient.class);
        var vision = mock(DocumentVisionClient.class);
        var media = new DocumentMediaProcessor(null, ocr, vision);
        var storage = new FileStorageService(mapper, null, parser, null, null, null,
                mock(ApplicationEventPublisher.class), null, null, null);
        storage.setMediaProcessor(media);
        String revision = java.util.HexFormat.of().formatHex(
                java.security.MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(path)));
        String body = "合成说明仅用于验证资料检索。";
        var text = new Block("p1-b1", "para", body, body, 1, new double[]{5, 6, 111, 18}, 120, 60,
                .99, "accepted", true, 0, body.length());
        var formula = new Block("p1-b2", "formula", "", "", 1, new double[]{20, 30, 90, 47}, 120, 60,
                null, "non_text", false, -1, -1, 0, null, "x=8", "needs_review", "待核对", null, -1, -1,
                new Presentation(null, "block", null, null, null, "structure-formula"));
        var page = new Page(1, 120, 60, "px", "ocr", List.of(text, formula));
        var report = new QualityReport(1, 1, 1, 0, .99, List.of(), false, false);
        when(parser.extractForJob(eq(path), eq("png"), anyLong(), eq(true), isNull(), any())).thenReturn(
                new Extraction("ok", body, body.length(), null, revision, STRUCTURED_PIPELINE_VERSION, List.of(page), report));
        String originalDir = System.getProperty("user.dir");
        try {
            System.setProperty("user.dir", work.toString());
            var processed = storage.reextractStructured(8L);
            assertEquals(1L, processed.get("pendingFormulas"));
            var pending = (Extraction) storage.extractionOf(8L);
            var candidate = pending.pages().getFirst().blocks().get(1);
            assertFalse(candidate.indexed());
            assertFalse(pending.text().contains("x=8"));
            assertTrue(pending.markdown().contains("公式待核对"));
            assertArrayEquals(formula.rect(), candidate.rect());
            byte[] crop = storage.markdownAsset(8L, candidate.src().substring(7, 71), revision);
            var image = ImageIO.read(new ByteArrayInputStream(crop));
            assertEquals(70, image.getWidth());
            assertEquals(17, image.getHeight());
            assertThrows(IllegalStateException.class, () ->
                    storage.confirmStructuredFormula(8L, candidate.id(), revision, "x=0", "x=3"));
            storage.confirmStructuredFormula(8L, candidate.id(), revision, "x=8", "x=3");
            assertTrue(storage.textOf(8L).get("text").toString().contains("x=3"));
            storage.reextractStructured(8L);
            assertTrue(storage.textOf(8L).get("text").toString().contains("x=3"));
            verifyNoInteractions(ocr, vision);
        } finally {
            media.close();
            System.setProperty("user.dir", originalDir);
        }
    }
}
