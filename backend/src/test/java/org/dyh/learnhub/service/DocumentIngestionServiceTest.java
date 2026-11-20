package org.dyh.learnhub.service;

import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.io.IOUtils;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;
import org.apache.pdfbox.pdmodel.common.PDStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.imageio.ImageIO;
import javax.imageio.IIOImage;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class DocumentIngestionServiceTest {
    @TempDir Path temp;

    private Path image() throws Exception {
        Path path = temp.resolve("synthetic.png");
        ImageIO.write(new BufferedImage(120, 80, BufferedImage.TYPE_INT_RGB), "png", path.toFile());
        return path;
    }

    private DocumentOcrClient client(List<DocumentOcrClient.Block> blocks) {
        var client = mock(DocumentOcrClient.class);
        when(client.configured()).thenReturn(true);
        when(client.recognize(any(), any())).thenAnswer(call -> {
            BufferedImage bitmap = ImageIO.read(new ByteArrayInputStream(call.getArgument(0)));
            return new DocumentOcrClient.Result("tesseract", "synthetic", bitmap.getWidth(), bitmap.getHeight(), blocks, List.of());
        });
        return client;
    }

    private DocumentOcrClient.Block block(String text, double confidence) {
        return new DocumentOcrClient.Block("b1", "paragraph", text, new double[]{0, 0, 100, 50}, confidence, 2);
    }

    @Test void mixedPdfProcessesEachPageAndUsesRotatedDisplayCoordinates() throws Exception {
        Path path = temp.resolve("mixed.pdf");
        try (PDDocument pdf = new PDDocument()) {
            PDPage digital = new PDPage(); pdf.addPage(digital);
            try (var content = new PDPageContentStream(pdf, digital)) {
                content.beginText(); content.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
                content.newLineAtOffset(50, 700); content.showText("Synthetic digital knowledge contains enough native characters."); content.endText();
            }
            PDPage scan = new PDPage(); scan.setRotation(90); pdf.addPage(scan);
            ByteArrayOutputStream bitmap = new ByteArrayOutputStream();
            ImageIO.write(new BufferedImage(120, 80, BufferedImage.TYPE_INT_RGB), "png", bitmap);
            try (var content = new PDPageContentStream(pdf, scan)) {
                content.drawImage(PDImageXObject.createFromByteArray(pdf, bitmap.toByteArray(), "synthetic"), 0, 0, 600, 700);
            }
            pdf.save(path.toFile());
        }
        byte[] before = Files.readAllBytes(path);
        var client = client(List.of(block("Synthetic scanned knowledge", 0.95)));
        try (var service = closeable(new DocumentIngestionService(new PdfLayoutExtractor(), client))) {
            var extraction = service.value.extract(path, "pdf");
            assertEquals("ok", extraction.status());
            assertEquals(2, extraction.report().processedPages());
            assertEquals(1, extraction.report().ocrPages());
            assertEquals("text", extraction.pages().getFirst().method());
            var scan = extraction.pages().get(1);
            assertEquals("ocr", scan.method());
            assertEquals(792, scan.width()); assertEquals(612, scan.height());
            assertEquals("pt", scan.coordinateUnit());
            assertTrue(extraction.text().contains("digital knowledge"));
            assertTrue(extraction.text().contains("scanned knowledge"));
            assertTrue(scan.blocks().getFirst().rect()[2] < 100);
            assertEquals(64, extraction.sourceSha256().length());
            assertArrayEquals(before, Files.readAllBytes(path));
            assertOffsets(extraction);
            verify(client, times(1)).recognize(any(), any());
        }
    }

    @Test void oversizedPdfScansFitBothOcrEnginesAndKeepRotatedCropCoordinatesOnResume() throws Exception {
        Path path = temp.resolve("synthetic-oversized.pdf");
        try (PDDocument pdf = new PDDocument()) {
            var scan = new PDPage(new PDRectangle(2600, 3300));
            scan.setCropBox(new PDRectangle(100, 150, 2400, 3000));
            scan.setRotation(90);
            pdf.addPage(scan);
            var pixels = new ByteArrayOutputStream();
            ImageIO.write(new BufferedImage(120, 80, BufferedImage.TYPE_INT_RGB), "png", pixels);
            try (var content = new PDPageContentStream(pdf, scan)) {
                content.drawImage(PDImageXObject.createFromByteArray(pdf, pixels.toByteArray(), "synthetic"), 100, 150, 2400, 3000);
            }
            pdf.save(path.toFile());
        }
        byte[] original = Files.readAllBytes(path);
        var basic = mock(DocumentOcrClient.class);
        when(basic.configured()).thenReturn(true);
        when(basic.recognize(any(), any())).thenAnswer(call -> {
            var size = assertAdaptiveRaster(call.getArgument(0));
            return new DocumentOcrClient.Result("tesseract", "synthetic", size[0], size[1],
                    List.of(new DocumentOcrClient.Block("b1", "para", "Synthetic adaptive OCR", centralRect(size), 0.96, 3)),
                    List.of());
        });
        var structure = mock(DocumentStructureClient.class);
        when(structure.configured()).thenReturn(true);
        when(structure.recognize(any(), any())).thenAnswer(call -> {
            var size = assertAdaptiveRaster(call.getArgument(0));
            return new DocumentStructureClient.Result(size[0], size[1], List.of(new DocumentStructureClient.Block(
                    "para", "Synthetic adaptive layout", centralRect(size), 0.96, 0, List.of(), null)), List.of());
        });
        try (var service = closeable(new DocumentIngestionService(new PdfLayoutExtractor(), basic, structure))) {
            var plain = service.value.extract(path, "pdf", false, null, null);
            assertAdaptiveCoordinates(plain);
            var previous = new DocumentIngestionService.Extraction("empty", "", 0, null, plain.sourceSha256(),
                    plain.pipelineVersion(), List.of(new DocumentIngestionService.Page(1, 3000, 2400, "pt", "text", List.of())),
                    new DocumentIngestionService.QualityReport(1, 1, 0, 1, null,
                            List.of("第 1 页未进入索引：图像像素预算超限"), true, true));
            var structured = service.value.extract(path, "pdf", true, previous, null);
            assertAdaptiveCoordinates(structured);
            assertEquals(plain.sourceSha256(), structured.sourceSha256());
            assertFalse(structured.report().warnings().stream().anyMatch(warning -> warning.contains("图像像素预算超限")));
            assertArrayEquals(original, Files.readAllBytes(path));
            verify(basic, times(1)).recognize(any(), any());
            verify(structure, times(1)).recognize(any(), any());
        }
    }

    private static int[] assertAdaptiveRaster(byte[] png) {
        // PNG stores pixel dimensions in the IHDR chunk. Checking it avoids decoding another large bitmap in the test.
        var header = ByteBuffer.wrap(png, 16, 8);
        int width = header.getInt(), height = header.getInt();
        assertTrue((long) width * height <= 12_000_000);
        assertTrue(width > height);
        assertTrue(width >= 3800, "use the highest useful resolution within the budget");
        return new int[]{width, height};
    }

    private static double[] centralRect(int[] size) {
        return new double[]{size[0] / 4.0, size[1] / 5.0, size[0] * 0.75, size[1] * 0.8};
    }

    private static void assertAdaptiveCoordinates(DocumentIngestionService.Extraction extraction) {
        assertEquals("ok", extraction.status());
        assertFalse(extraction.report().partial());
        assertFalse(extraction.report().truncated());
        assertEquals(1, extraction.completedPages());
        var scan = extraction.pages().getFirst();
        assertEquals("ocr", scan.method());
        assertEquals("pt", scan.coordinateUnit());
        assertEquals(3000, scan.width());
        assertEquals(2400, scan.height());
        assertArrayEquals(new double[]{750, 480, 2250, 1920}, scan.blocks().getFirst().rect(), 0.001);
        assertTrue(extraction.report().warnings().stream().anyMatch(warning -> warning.contains("自适应渲染至")));
        assertOffsets(extraction);
    }

    @Test void pdfOverOldSizeLimitStillReachesTheExtractor() throws Exception {
        Path path = temp.resolve("large.pdf");
        try (PDDocument pdf = new PDDocument(IOUtils.createTempFileOnlyStreamCache())) {
            PDPage page = new PDPage();
            pdf.addPage(page);
            try (var content = new PDPageContentStream(pdf, page)) {
                content.beginText();
                content.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
                content.newLineAtOffset(50, 700);
                content.showText("Synthetic knowledge in a large PDF.");
                content.endText();
            }
            var padding = new PDStream(pdf);
            pdf.getDocumentCatalog().getCOSObject().setItem(COSName.getPDFName("SyntheticPadding"), padding);
            try (var out = padding.createOutputStream()) {
                byte[] zeroes = new byte[1024 * 1024];
                for (int mib = 0; mib < 65; mib++) out.write(zeroes);
            }
            pdf.save(path.toFile());
        }
        assertTrue(Files.size(path) > 64L * 1024 * 1024);
        try (var service = closeable(new DocumentIngestionService(new PdfLayoutExtractor(), new DocumentOcrClient("")))) {
            var extraction = service.value.extract(path, "pdf");
            assertEquals("ok", extraction.status());
            assertTrue(extraction.text().contains("Synthetic knowledge in a large PDF."));
            assertEquals(1, extraction.report().processedPages());
        }
    }

    @Test void qualityGateRetainsRawBlocksButOnlyIndexesAcceptedText() throws Exception {
        var client = client(List.of(block("  accepted first  ", 0.95), block("low confidence", 0.4),
                block("broken \uFFFD text", 0.99), block("accepted final", 0.9)));
        try (var service = closeable(new DocumentIngestionService(new PdfLayoutExtractor(), client))) {
            var extraction = service.value.extract(image(), "png");
            assertEquals("ok", extraction.status());
            assertEquals("accepted first\n\naccepted final", extraction.text());
            assertTrue(extraction.report().partial());
            var blocks = extraction.pages().getFirst().blocks();
            assertEquals("  accepted first  ", blocks.getFirst().rawText());
            assertEquals("low_confidence", blocks.get(1).quality());
            assertEquals("garbled", blocks.get(2).quality());
            assertEquals(-1, blocks.get(1).start());
            assertOffsets(extraction);
        }
    }

    @Test void noAcceptedOcrTextCannotBecomeSuccessful() throws Exception {
        try (var service = closeable(new DocumentIngestionService(new PdfLayoutExtractor(), client(List.of(block("unreliable", 0.1)))))) {
            var extraction = service.value.extract(image(), "png");
            assertEquals("empty", extraction.status());
            assertTrue(extraction.text().isEmpty());
            assertEquals(1, extraction.report().rejectedPages());
            assertTrue(extraction.report().partial());
        }
    }

    @Test void documentedOcrLimitNoticeDoesNotMarkACompleteRecognitionPartial() throws Exception {
        var client = mock(DocumentOcrClient.class); when(client.configured()).thenReturn(true);
        when(client.recognize(any(), any())).thenReturn(new DocumentOcrClient.Result("tesseract", "synthetic", 120, 80,
                List.of(block("Synthetic complete recognition", 0.95)),
                List.of("ocr_text_only: formulas and complex tables require manual verification")));
        try (var service = closeable(new DocumentIngestionService(new PdfLayoutExtractor(), client))) {
            var result = service.value.extract(image(), "png");
            assertEquals("ok", result.status()); assertFalse(result.report().partial());
            assertFalse(result.report().warnings().isEmpty());
        }
    }

    @Test void unavailableOcrAndTimeoutAreVisibleAndDoNotThrow() throws Exception {
        var disabled = new DocumentOcrClient("");
        try (var service = closeable(new DocumentIngestionService(new PdfLayoutExtractor(), disabled))) {
            var extraction = service.value.extract(image(), "png");
            assertNotEquals("ok", extraction.status());
            assertTrue(extraction.report().warnings().stream().anyMatch(w -> w.contains("未配置")));
        }
        var slow = mock(DocumentOcrClient.class); when(slow.configured()).thenReturn(true);
        when(slow.recognize(any(), any())).thenAnswer(call -> { Thread.sleep(1000); throw new IllegalStateException("synthetic timeout"); });
        try (var service = closeable(new DocumentIngestionService(new PdfLayoutExtractor(), slow, 50))) {
            long started = System.nanoTime();
            var extraction = service.value.extract(image(), "png");
            assertNotEquals("ok", extraction.status());
            assertTrue(extraction.report().truncated());
            assertTrue(Duration.ofNanos(System.nanoTime() - started).toMillis() < 700);
        }
    }

    @Test void preservesRestoredFormulaAndRejectsUncertainCandidate() throws Exception {
        Path path = temp.resolve("formula.pdf");
        try (PDDocument pdf = new PDDocument()) { pdf.addPage(new PDPage()); pdf.save(path.toFile()); }
        var layout = mock(PdfLayoutExtractor.class);
        when(layout.extractPage(any(), eq(1))).thenReturn(new PdfLayoutExtractor.PageLayout(1, 1, List.of(
                new PdfLayoutExtractor.Block("para", "Synthetic introductory paragraph has sufficient native text.", 0),
                new PdfLayoutExtractor.Block("formula", "a / b", 0, null, 1, new double[]{10, 10, 60, 30}, "\\frac{a}{b}", "restored", "synthetic"),
                new PdfLayoutExtractor.Block("formula", "uncertain symbols", 0, null, 1, null, "x^2", "partial", "check original"))));
        try (var service = closeable(new DocumentIngestionService(layout, new DocumentOcrClient("")))) {
            var result = service.value.extract(path, "pdf");
            assertTrue(result.text().contains("$$\n\\frac{a}{b}\n$$"));
            assertFalse(result.text().contains("x^2"));
            assertEquals("uncertain_formula", result.pages().getFirst().blocks().get(2).quality());
            assertTrue(result.report().partial());
            verify(layout, times(1)).extractPage(any(), eq(1));
            assertOffsets(result);
        }
    }

    @Test void compatibilityEntryPointKeepsStandaloneParserAndDetailedDelegation() throws Exception {
        Path plain = temp.resolve("synthetic.txt"); Files.writeString(plain, "Synthetic text\r\n\r\n\r\ncleaned");
        var texts = new DocumentTextService();
        assertEquals("ok", texts.extract(plain, "txt", Files.size(plain)).status());
        assertEquals("unsupported", texts.extract(image(), "png", 100).status());
        var detailed = texts.extractDetailed(plain, "txt", Files.size(plain));
        assertEquals("Synthetic text\n\ncleaned", detailed.text());
        assertOffsets(detailed);
        try (var service = closeable(new DocumentIngestionService(new PdfLayoutExtractor(), client(List.of(block("Image text", 0.9)))))) {
            texts.setDocumentIngestionService(service.value);
            assertEquals("Image text", texts.extract(image(), "png", 100).text());
            assertEquals("ocr", texts.extractDetailed(image(), "png", 100).pages().getFirst().method());
        }
    }

    @Test void largeScanWithThirtyCharacterHeaderStillRequiresOcr() throws Exception {
        Path path = temp.resolve("header-scan.pdf");
        try (PDDocument pdf = new PDDocument()) {
            PDPage page = new PDPage(); pdf.addPage(page);
            ByteArrayOutputStream bitmap = new ByteArrayOutputStream();
            ImageIO.write(new BufferedImage(120, 80, BufferedImage.TYPE_INT_RGB), "png", bitmap);
            try (var content = new PDPageContentStream(pdf, page)) {
                content.drawImage(PDImageXObject.createFromByteArray(pdf, bitmap.toByteArray(), "synthetic scan"), 0, 0, 612, 720);
                content.beginText(); content.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
                content.newLineAtOffset(20, 760); content.showText("Synthetic header thirty characters only"); content.endText();
            }
            pdf.save(path.toFile());
        }
        try (var service = closeable(new DocumentIngestionService(new PdfLayoutExtractor(), new DocumentOcrClient("")))) {
            var result = service.value.extract(path, "pdf");
            assertNotEquals("ok", result.status());
            assertTrue(result.text().isEmpty());
            assertTrue(result.pages().getFirst().blocks().stream().anyMatch(b -> b.rawText().contains("Synthetic header")
                    && b.quality().equals("ocr_required") && !b.indexed()));
        }
        var client = client(List.of(block("Recognized scan body", 0.9)));
        try (var service = closeable(new DocumentIngestionService(new PdfLayoutExtractor(), client))) {
            var result = service.value.extract(path, "pdf");
            assertEquals("Recognized scan body", result.text());
            assertEquals(1, result.report().ocrPages());
        }
    }

    @Test void shortNativeOnlyPdfDoesNotRequireOcrOrLoseItsText() throws Exception {
        Path path = temp.resolve("short-native.pdf");
        try (PDDocument pdf = new PDDocument()) {
            PDPage page = new PDPage(); pdf.addPage(page);
            try (var content = new PDPageContentStream(pdf, page)) {
                content.beginText(); content.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
                content.newLineAtOffset(20, 700); content.showText("Short native text."); content.endText();
            }
            pdf.save(path.toFile());
        }
        var client = mock(DocumentOcrClient.class);
        try (var service = closeable(new DocumentIngestionService(new PdfLayoutExtractor(), client))) {
            var result = service.value.extract(path, "pdf");
            assertEquals("ok", result.status()); assertEquals("Short native text.", result.text());
            assertEquals(0, result.report().ocrPages()); assertFalse(result.report().partial());
            verifyNoInteractions(client);
        }
    }

    @Test void tiffResumesPastTheOcrCapAndRetriesRejectedPagesAfterMissingPages() throws Exception {
        Path path = temp.resolve("synthetic-multipage.tiff");
        var writer = ImageIO.getImageWritersByFormatName("TIFF").next();
        try (var output = ImageIO.createImageOutputStream(path.toFile())) {
            writer.setOutput(output); writer.prepareWriteSequence(null);
            for (int i = 0; i < 21; i++) writer.writeToSequence(
                    new IIOImage(new BufferedImage(120, 80, BufferedImage.TYPE_INT_RGB), null, null), null);
            writer.endWriteSequence();
        } finally { writer.dispose(); }
        var client = mock(DocumentOcrClient.class);
        var calls = new AtomicInteger();
        when(client.configured()).thenReturn(true);
        when(client.recognize(any(), any())).thenAnswer(call -> {
            int number = calls.incrementAndGet();
            var diagnostics = number == 2 ? IntStream.range(0, 205)
                    .mapToObj(index -> "ocr_text_only: synthetic advisory " + index).toList() : List.<String>of();
            return new DocumentOcrClient.Result("tesseract", "synthetic", 120, 80,
                    List.of(block("Synthetic TIFF OCR attempt " + number, number == 1 ? 0.2 : 0.9)), diagnostics);
        });
        try (var service = closeable(new DocumentIngestionService(new PdfLayoutExtractor(), client))) {
            List<DocumentIngestionService.Progress> progress = new ArrayList<>();
            var result = service.value.extract(path, "tiff", false, null, progress::add);
            assertEquals("ok", result.status());
            assertEquals(21, result.report().totalPages());
            assertEquals(20, result.report().processedPages());
            assertEquals(19, result.completedPages());
            assertEquals(20, result.report().ocrPages());
            assertTrue(result.report().partial()); assertTrue(result.report().truncated());
            assertTrue(result.report().warnings().stream().anyMatch(w -> w.contains("20 页")));
            assertEquals(200, result.report().warnings().size());
            assertEquals(new DocumentIngestionService.Progress("extracting", 19, 21, 20), progress.getLast());

            progress.clear();
            var resumed = service.value.extract(path, "tiff", false, result, progress::add);
            assertEquals(21, resumed.report().processedPages());
            assertEquals(21, resumed.completedPages());
            assertEquals(21, resumed.report().ocrPages());
            assertEquals(List.of(21, 1), progress.stream().map(DocumentIngestionService.Progress::currentPage)
                    .filter(page -> page > 0).distinct().toList());
            assertEquals(new DocumentIngestionService.Progress("extracting", 19, 21, 0), progress.get(1));
            assertEquals(new DocumentIngestionService.Progress("extracting", 21, 21, 1), progress.getLast());
            assertTrue(resumed.text().startsWith("Synthetic TIFF OCR attempt 22\n\nSynthetic TIFF OCR attempt 2"));
            assertTrue(resumed.text().endsWith("Synthetic TIFF OCR attempt 21"));
            assertEquals(21, resumed.pages().getLast().page());
            assertFalse(resumed.report().partial()); assertFalse(resumed.report().truncated());
            assertFalse(resumed.report().warnings().stream().anyMatch(w -> w.contains("20 页") || w.contains("low_confidence")));
            assertOffsets(resumed);
            verify(client, times(22)).recognize(any(), any());
        }
    }

    @Test void changedSourceNeverReusesEarlierIndexedText() throws Exception {
        Path path = image();
        var ocr = client(List.of(block("Prior document text", 0.9)));
        try (var service = closeable(new DocumentIngestionService(new PdfLayoutExtractor(), ocr))) {
            var previous = service.value.extract(path, "png");
            BufferedImage replacement = new BufferedImage(120, 80, BufferedImage.TYPE_INT_RGB);
            replacement.setRGB(0, 0, 0xFFFFFF);
            ImageIO.write(replacement, "png", path.toFile());
            doReturn(new DocumentOcrClient.Result("tesseract", "synthetic", 120, 80,
                    List.of(block("Current document text", 0.9)), List.of())).when(ocr).recognize(any(), any());
            var result = service.value.extract(path, "png", false, previous, null);
            assertNotEquals(previous.sourceSha256(), result.sourceSha256());
            assertEquals("Current document text", result.text());
            verify(ocr, times(2)).recognize(any(), any());
        }
    }

    @Test void resumeKeepsPriorAcceptedBlocksWhenAdditionalPagesRevealRepeatedHeaders() throws Exception {
        Path path = temp.resolve("margins.pdf");
        try (PDDocument pdf = new PDDocument()) {
            for (int i = 0; i < 3; i++) pdf.addPage(new PDPage());
            pdf.save(path.toFile());
        }
        var layout = mock(PdfLayoutExtractor.class);
        when(layout.extractPage(any(), anyInt())).thenAnswer(call -> nativePage(call.getArgument(1)));
        when(layout.extractPage(any(), eq(3))).thenThrow(new IllegalStateException("synthetic unavailable page"))
                .thenReturn(nativePage(3));
        try (var service = closeable(new DocumentIngestionService(layout, new DocumentOcrClient("")))) {
            var previous = service.value.extract(path, "pdf");
            assertEquals(2, previous.completedPages());
            var resumed = service.value.extract(path, "pdf", false, previous, null);
            assertEquals(3, resumed.completedPages());
            for (int i = 0; i < 2; i++) {
                var original = previous.pages().get(i).blocks().getFirst();
                var retained = resumed.pages().get(i).blocks().getFirst();
                assertEquals(original, retained);
                assertTrue(retained.indexed());
            }
            assertEquals("page_furniture", resumed.pages().getLast().blocks().getFirst().quality());
            assertFalse(resumed.report().partial());
            assertOffsets(resumed);
            verify(layout, times(1)).extractPage(any(), eq(1));
            verify(layout, times(1)).extractPage(any(), eq(2));
            verify(layout, times(2)).extractPage(any(), eq(3));
        }
    }

    @Test void emptyPdfPageIsCompleteWithoutAnOcrAttempt() throws Exception {
        Path path = temp.resolve("with-blank.pdf");
        try (PDDocument pdf = new PDDocument()) {
            pdf.addPage(new PDPage());
            pdf.addPage(new PDPage());
            pdf.save(path.toFile());
        }
        var layout = mock(PdfLayoutExtractor.class);
        when(layout.extractPage(any(), eq(1))).thenReturn(new PdfLayoutExtractor.PageLayout(1, 1, List.of()));
        when(layout.extractPage(any(), eq(2))).thenReturn(nativePage(2));
        var ocr = mock(DocumentOcrClient.class);
        try (var service = closeable(new DocumentIngestionService(layout, ocr))) {
            var result = service.value.extract(path, "pdf");
            assertEquals("blank", result.pages().getFirst().method());
            assertEquals(2, result.completedPages());
            assertEquals(0, result.report().rejectedPages());
            assertFalse(result.report().partial());
            verifyNoInteractions(ocr);
        }
    }

    private static PdfLayoutExtractor.PageLayout nativePage(int number) {
        return new PdfLayoutExtractor.PageLayout(number, 1, List.of(
                new PdfLayoutExtractor.Block("para", "Repeated margin text", 0, null, number,
                        new double[]{10, 10, 100, 20}, null, null, null),
                new PdfLayoutExtractor.Block("para", "Original body on page " + number, 0, null, number,
                        new double[]{30, 200, 400, 230}, null, null, null)));
    }

    @Test void totalCharacterCapDoesNotSplitBlocksAndLegacyWrappingDoesNotRejectExistingText() throws Exception {
        Path path = temp.resolve("long-native.pdf");
        try (PDDocument pdf = new PDDocument()) { pdf.addPage(new PDPage()); pdf.save(path.toFile()); }
        var layout = mock(PdfLayoutExtractor.class);
        String first = "a".repeat(DocumentTextService.MAX_TEXT_CHARS - 20);
        when(layout.extractPage(any(), eq(1))).thenReturn(new PdfLayoutExtractor.PageLayout(1, 1, List.of(
                new PdfLayoutExtractor.Block("para", first, 0), new PdfLayoutExtractor.Block("para", "Whole tail paragraph", 0))));
        try (var service = closeable(new DocumentIngestionService(layout, new DocumentOcrClient("")))) {
            var result = service.value.extract(path, "pdf");
            assertEquals(first, result.text());
            assertTrue(result.report().truncated()); assertTrue(result.report().partial());
            assertEquals("character_limit", result.pages().getFirst().blocks().get(1).quality());
            assertOffsets(result);
        }
        Path markdown = temp.resolve("existing.md"); String original = "Symbols only: \uE000";
        Files.writeString(markdown, original);
        var legacy = new DocumentTextService().extractDetailed(markdown, "md", Files.size(markdown));
        assertEquals("ok", legacy.status()); assertEquals(original, legacy.text());
    }

    @Test void nativePdfPageCapReportsUnprocessedTail() throws Exception {
        Path path = temp.resolve("synthetic-501-pages.pdf");
        try (PDDocument pdf = new PDDocument()) {
            for (int i = 0; i < 501; i++) pdf.addPage(new PDPage());
            pdf.save(path.toFile());
        }
        var layout = mock(PdfLayoutExtractor.class);
        when(layout.extractPage(any(), anyInt())).thenAnswer(call -> new PdfLayoutExtractor.PageLayout(call.getArgument(1), 1,
                List.of(new PdfLayoutExtractor.Block("para", "Synthetic bounded native page content", 0))));
        try (var service = closeable(new DocumentIngestionService(layout, new DocumentOcrClient("")))) {
            var result = service.value.extract(path, "pdf");
            assertEquals(501, result.report().totalPages()); assertEquals(500, result.report().processedPages());
            assertTrue(result.report().partial()); assertTrue(result.report().truncated());
            assertEquals(500, result.pages().getLast().page());
            var resumed = service.value.extract(path, "pdf", false, result, null);
            assertEquals(501, resumed.completedPages());
            assertEquals(501, resumed.pages().getLast().page());
            assertFalse(resumed.report().partial()); assertFalse(resumed.report().truncated());
            verify(layout, times(501)).extractPage(any(), anyInt());
        }
    }

    private static void assertOffsets(DocumentIngestionService.Extraction extraction) {
        for (var page : extraction.pages()) for (var block : page.blocks()) {
            if (block.indexed()) {
                String expected="markdown".equals(extraction.format())
                        ? DocumentMarkdownWriter.projectForIndex(block.markdown()) : block.cleanText();
                assertEquals(expected, extraction.text().substring(block.start(), block.end()));
                if ("markdown".equals(extraction.format())) assertEquals(block.markdown(),
                        extraction.markdown().substring(block.markdownStart(),block.markdownEnd()));
            }
            else { assertEquals(-1, block.start()); assertEquals(-1, block.end()); }
        }
        assertEquals(extraction.text().length(), extraction.chars());
    }
    private static ServiceHandle closeable(DocumentIngestionService value) { return new ServiceHandle(value); }
    private record ServiceHandle(DocumentIngestionService value) implements AutoCloseable {
        public void close() { value.close(); }
    }
}
