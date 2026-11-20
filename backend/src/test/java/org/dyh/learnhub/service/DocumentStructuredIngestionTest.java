package org.dyh.learnhub.service;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.apache.pdfbox.pdmodel.graphics.image.LosslessFactory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class DocumentStructuredIngestionTest {
    @TempDir Path temp;

    @Test void mapsOrderedBlocksWithSafeTablesAndRetainsOnlyReviewableFormulaCandidates() throws Exception {
        var ocr = mock(DocumentOcrClient.class);
        var structure = mock(DocumentStructureClient.class);
        when(structure.configured()).thenReturn(true);
        when(structure.recognize(any(), any())).thenReturn(new DocumentStructureClient.Result(120, 80, List.of(
                block("heading", "Synthetic heading", new double[]{0, 0, 120, 12}, 0.98, 2),
                block("para", "Recovered OCR line", new double[]{0, 12, 120, 24}, 0.95, 0),
                new DocumentStructureClient.Block("table", "", new double[]{0, 25, 120, 42}, 0.9, 0,
                        List.of(List.of("Cell A", "<script>cell B</script>")), ""),
                new DocumentStructureClient.Block("formula", "", new double[]{0, 45, 120, 55}, null, 0,
                        List.of(), "z^2=7"),
                new DocumentStructureClient.Block("formula", "", new double[]{0, 56, 120, 65}, null, 0,
                        List.of(), "<img src=x>"),
                block("para", "Low confidence line", new double[]{0, 66, 120, 80}, 0.2, 0)),
                List.of("pp_ocr_lines_recovered")));
        var ingestion = new DocumentIngestionService(new PdfLayoutExtractor(), ocr, structure);
        try {
            var result = DocumentMarkdownWriter.render(ingestion.extractStructured(image(), "png"));
            var page = result.pages().getFirst();
            assertEquals(DocumentIngestionService.STRUCTURED_PIPELINE_VERSION, result.pipelineVersion());
            assertTrue(DocumentIngestionService.isCurrentPipeline(result.pipelineVersion()));
            assertEquals("ocr", page.method());
            assertEquals("px", page.coordinateUnit());
            assertTrue(result.markdown().contains("## Synthetic heading"));
            assertTrue(result.text().indexOf("Recovered OCR line") < result.text().indexOf("<table>"));
            assertTrue(result.text().contains("<td>Cell A</td><td>&lt;script&gt;cell B&lt;/script&gt;</td>"));
            assertFalse(result.markdown().contains("<script>"));
            assertFalse(result.text().contains("Low confidence line"));
            assertFalse(result.text().contains("z^2=7"));
            assertFalse(result.markdown().contains("z^2=7"));
            var formula = page.blocks().get(3);
            assertEquals("z^2=7", formula.latex());
            assertEquals("needs_review", formula.latexStatus());
            assertFalse(formula.indexed());
            assertEquals(-1, formula.start());
            assertArrayEquals(new double[]{0, 45, 120, 55}, formula.rect());
            assertNull(page.blocks().get(4).latex());
            assertTrue(result.report().warnings().stream().anyMatch(message -> message.contains("已补回")));
            assertTrue(result.report().partial());
            verifyNoInteractions(ocr);
        } finally { ingestion.close(); }
    }

    @Test void defaultScansBothNativeAndRasterPdfPagesWithOriginalRotatedCoordinates() throws Exception {
        Path path = temp.resolve("mixed.pdf");
        try (PDDocument pdf = new PDDocument()) {
            PDPage nativePage = new PDPage();
            pdf.addPage(nativePage);
            try (var content = new PDPageContentStream(pdf, nativePage)) {
                content.beginText();
                content.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
                content.newLineAtOffset(50, 700);
                content.showText("Synthetic authoritative native PDF text.");
                content.endText();
            }
            PDPage scan = new PDPage();
            scan.setRotation(90);
            pdf.addPage(scan);
            try (var content = new PDPageContentStream(pdf, scan)) {
                content.drawImage(LosslessFactory.createFromImage(pdf,
                        new BufferedImage(120, 80, BufferedImage.TYPE_INT_RGB)), 0, 0, 600, 700);
            }
            pdf.save(path.toFile());
        }
        var ocr = mock(DocumentOcrClient.class);
        var structure = mock(DocumentStructureClient.class);
        var rasterSize = new java.util.concurrent.atomic.AtomicReference<int[]>();
        when(structure.configured()).thenReturn(true);
        when(structure.recognize(any(), any())).thenAnswer(call -> {
            BufferedImage bitmap = ImageIO.read(new ByteArrayInputStream(call.getArgument(0)));
            int width = bitmap.getWidth(), height = bitmap.getHeight();
            rasterSize.set(new int[]{width, height});
            return new DocumentStructureClient.Result(width, height,
                    List.of(block("para", "Structured scan", new double[]{width / 2d, height / 2d, width, height}, 0.9, 0)),
                    List.of());
        });
        var ingestion = new DocumentIngestionService(new PdfLayoutExtractor(), ocr, structure);
        try {
            var result = ingestion.extract(path, "pdf");
            assertEquals("ocr", result.pages().getFirst().method());
            assertFalse(result.text().contains("Synthetic authoritative native PDF text."));
            assertEquals(2, result.pages().size(), () -> result.report().warnings().toString());
            assertNotNull(rasterSize.get());
            assertEquals(3300, rasterSize.get()[0], 1);
            assertEquals(2550, rasterSize.get()[1], 1);
            var scan = result.pages().getLast();
            assertEquals("ocr", scan.method());
            assertEquals("pt", scan.coordinateUnit());
            assertArrayEquals(new double[]{396, 306, 792, 612}, scan.blocks().getFirst().rect(), 0.001);
            assertEquals(2, result.report().ocrPages());
            assertFalse(result.report().partial());
            verify(structure, times(2)).recognize(any(), any());
            verifyNoInteractions(ocr);
        } finally { ingestion.close(); }
    }

    @Test void structuredUpgradeNeverReusesCompletedPagesFromAnOlderPipeline() throws Exception {
        Path path = temp.resolve("resume-mode.pdf");
        try (PDDocument pdf = new PDDocument()) {
            for (int i = 0; i < 2; i++) {
                PDPage page = new PDPage(new org.apache.pdfbox.pdmodel.common.PDRectangle(72, 72));
                pdf.addPage(page);
                try (var content = new PDPageContentStream(pdf, page)) {
                    content.drawImage(LosslessFactory.createFromImage(pdf,
                            new BufferedImage(20, 20, BufferedImage.TYPE_INT_RGB)), 0, 0, 72, 72);
                }
            }
            pdf.save(path.toFile());
        }
        var ocr = mock(DocumentOcrClient.class);
        when(ocr.configured()).thenReturn(true);
        when(ocr.recognize(any(), any())).thenReturn(new DocumentOcrClient.Result("tesseract", "test", 300, 300,
                List.of(new DocumentOcrClient.Block("b1", "para", "Prior accepted OCR", new double[]{10, 30, 200, 200}, 0.9, 2)),
                List.of())).thenThrow(new IllegalStateException("synthetic retryable failure"));
        var structure = mock(DocumentStructureClient.class);
        when(structure.configured()).thenReturn(true);
        when(structure.recognize(any(), any())).thenReturn(new DocumentStructureClient.Result(300, 300,
                List.of(block("para", "High quality scan", new double[]{10, 10, 100, 100}, 0.9, 0)), List.of()));
        var ingestion = new DocumentIngestionService(new PdfLayoutExtractor(), ocr, structure);
        try {
            var previous = ingestion.extract(path, "pdf", false, null, null);
            assertEquals(1, previous.completedPages());
            var resumed = ingestion.extract(path, "pdf", true, previous, null);
            assertEquals(2, resumed.completedPages());
            assertFalse(resumed.text().contains("Prior accepted OCR"));
            assertEquals("High quality scan\n\nHigh quality scan", resumed.text());
            assertEquals(DocumentIngestionService.STRUCTURED_PIPELINE_VERSION, resumed.pipelineVersion());
            assertFalse(resumed.report().partial());
            var legacyStructure = new DocumentIngestionService.Extraction(resumed.status(),resumed.text(),resumed.chars(),resumed.error(),
                    resumed.sourceSha256(),DocumentIngestionService.LEGACY_STRUCTURED_PIPELINE_VERSION,resumed.pages(),resumed.report());
            assertEquals(2, ingestion.extract(path,"pdf",true,legacyStructure,null).completedPages());
            var legacyHq = new DocumentIngestionService.Extraction(resumed.status(),resumed.text(),resumed.chars(),resumed.error(),
                    resumed.sourceSha256(),DocumentIngestionService.LEGACY_HQ_PIPELINE_VERSION,resumed.pages(),resumed.report());
            assertTrue(DocumentIngestionService.isStructuredPipeline(legacyHq.pipelineVersion()));
            assertFalse(DocumentIngestionService.isCurrentPipeline(legacyHq.pipelineVersion()));
            assertEquals(2,ingestion.extract(path,"pdf",true,legacyHq,null).completedPages());
            verify(ocr, times(2)).recognize(any(), any());
            verify(structure, times(6)).recognize(any(), any());
        } finally { ingestion.close(); }
    }

    @Test void keepsFastExtractionAvailableDuringStructuredJobAndReportsStructuredFailure() throws Exception {
        var ocr = mock(DocumentOcrClient.class);
        when(ocr.configured()).thenReturn(true);
        when(ocr.recognize(any(), any())).thenReturn(new DocumentOcrClient.Result("tesseract", "test", 120, 80,
                List.of(new DocumentOcrClient.Block("b1", "para", "Fast OCR", new double[]{0, 0, 120, 80}, 0.9, 2)),
                List.of()));
        var structure = mock(DocumentStructureClient.class);
        when(structure.configured()).thenReturn(true);
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        when(structure.recognize(any(), any())).thenAnswer(call -> {
            started.countDown();
            assertTrue(release.await(10, TimeUnit.SECONDS));
            throw new IllegalStateException("结构化 OCR 请求失败");
        });
        var ingestion = new DocumentIngestionService(new PdfLayoutExtractor(), ocr, structure);
        var path = image();
        try (var callers = Executors.newSingleThreadExecutor()) {
            var pending = callers.submit(() -> ingestion.extractStructured(path, "png"));
            try {
                assertTrue(started.await(5, TimeUnit.SECONDS));
                assertEquals("failed", ingestion.extractStructured(path, "png").status());
                var fast = ingestion.extract(path, "png", false, null, null);
                assertEquals(DocumentIngestionService.PIPELINE_VERSION, fast.pipelineVersion());
                assertEquals("Fast OCR", fast.text());
            } finally { release.countDown(); }
            var rejected = pending.get(5, TimeUnit.SECONDS);
            assertTrue(rejected.report().partial());
            assertTrue(rejected.text().isEmpty());
            assertTrue(rejected.report().warnings().stream().anyMatch(message -> message.contains("结构化 OCR 请求失败")));
            verify(ocr, times(1)).recognize(any(), any());
        } finally { ingestion.close(); }
    }

    @Test void configuredButUnavailableScannerDoesNotSilentlyFallBackToBasicOcr() throws Exception {
        var ocr = mock(DocumentOcrClient.class);
        var structure = mock(DocumentStructureClient.class);
        when(structure.configured()).thenReturn(true);
        when(structure.recognize(any(), any())).thenThrow(new IllegalStateException("synthetic scanner unavailable"));
        var ingestion = new DocumentIngestionService(new PdfLayoutExtractor(), ocr, structure);
        try {
            var result = ingestion.extract(image(), "png");
            assertEquals(DocumentIngestionService.STRUCTURED_PIPELINE_VERSION, result.pipelineVersion());
            assertTrue(result.text().isEmpty());
            assertTrue(result.report().partial());
            verifyNoInteractions(ocr);
        } finally { ingestion.close(); }
    }

    private Path image() throws Exception {
        Path path = temp.resolve("structured.png");
        ImageIO.write(new BufferedImage(120, 80, BufferedImage.TYPE_INT_RGB), "png", path.toFile());
        return path;
    }

    private static DocumentStructureClient.Block block(String type, String text, double[] rect, double confidence, int level) {
        return new DocumentStructureClient.Block(type, text, rect, confidence, level, List.of(), "");
    }
}
