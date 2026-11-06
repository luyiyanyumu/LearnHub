package org.dyh.learnhub.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.apache.poi.xslf.usermodel.XMLSlideShow;
import org.apache.poi.sl.usermodel.PictureData.PictureType;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;
import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import static org.dyh.learnhub.service.DocumentIngestionService.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class DocumentMediaProcessorTest {
    @TempDir Path directory;
    DocumentVisionClient vision;
    DocumentOcrClient ocr;
    Path png;
    @BeforeEach void setup() throws Exception {
        png = directory.resolve("synthetic.png"); ImageIO.write(new BufferedImage(120, 80, BufferedImage.TYPE_INT_RGB), "png", png.toFile());
        vision = mock(DocumentVisionClient.class); ocr = mock(DocumentOcrClient.class);
        when(vision.identity()).thenReturn("a".repeat(64));
        when(vision.capabilities()).thenReturn(Map.of("available", true, "enabled", true, "model", "synthetic-vision", "profile", "Synthetic"));
    }
    DocumentVisionClient.Result formula(String status) {
        return new DocumentVisionClient.Result("formula", status, "x_{1}=2", "", "", List.of(), !status.equals("recognized"),
                status.equals("recognized") ? List.of() : List.of("Synthetic uncertainty"), "Synthetic", "synthetic-vision", DocumentVisionClient.PROMPT_VERSION);
    }
    Extraction input() {
        String text = "Synthetic OCR candidate x1 equals 2";
        var block = new Block("p1-b1", "para", text, text, 1, new double[]{2, 2, 100, 40}, 120, 80, .9, "accepted", true, 0, text.length());
        return new Extraction("ok", text, text.length(), null, "b".repeat(64), PIPELINE_VERSION,
                List.of(new Page(1, 120, 80, "px", "ocr", List.of(block))), new QualityReport(1, 1, 1, 0, .9, List.of(), false, false));
    }
    @Test void formulaImagePreservesOriginalAndSupersedesOnlyIndexedOcrCandidates() throws Exception {
        byte[] original = Files.readAllBytes(png); when(vision.analyze(any(), anyString(), any())).thenReturn(formula("recognized"));
        try (var handle = handle()) {
            var result = handle.processor.process(7L, png, "png", input(), false);
            assertEquals("plain", result.format()); assertTrue(result.text().contains("$$\nx_{1}=2\n$$"));
            assertFalse(result.text().contains("OCR candidate"));
            var old = result.pages().getFirst().blocks().getFirst();
            assertEquals("superseded_by_formula", old.quality()); assertFalse(old.indexed()); assertTrue(old.rawText().contains("OCR candidate"));
            var image = result.pages().getFirst().blocks().stream().filter(b -> b.id().equals("p1-media")).findFirst().orElseThrow();
            assertEquals("formula", image.type()); assertTrue(image.src().matches("assets/[a-f0-9]{64}\\.png"));
            assertTrue(Files.isRegularFile(directory.resolve(".derived/7").resolve(image.src())));
            assertTrue(image.markdown().contains("![原图]")); assertNull(image.confidence());
            assertArrayEquals(original, Files.readAllBytes(png));
        }
    }
    @Test void uncertaintyPreservesOcrAndNeverIndexesVisionCandidates() throws Exception {
        when(vision.analyze(any(), anyString(), any())).thenReturn(formula("partial"));
        try (var handle = handle()) {
            var result = handle.processor.process(7L, png, "png", input(), false);
            assertEquals(input().text(), result.text()); assertTrue(result.report().partial());
            var image = result.pages().getFirst().blocks().stream().filter(b -> b.id().equals("p1-media")).findFirst().orElseThrow();
            assertEquals("vision_uncertain", image.quality()); assertFalse(image.indexed()); assertFalse(image.markdown().contains("x_{1}"));
            var again = handle.processor.process(7L, png, "png", input(), false);
            assertEquals(input().text(), again.text()); verify(vision, times(2)).analyze(any(), anyString(), any());
        }
    }
    @Test void cachedRecognitionAvoidsRepeatedModelCallsAndForceFailureKeepsReliableResult() throws Exception {
        when(vision.analyze(any(), anyString(), any())).thenReturn(formula("recognized"));
        try (var handle = handle()) {
            var first = handle.processor.process(7L, png, "png", input(), false);
            var cached = handle.processor.process(7L, png, "png", input(), false);
            assertEquals(first.text(), cached.text()); verify(vision, times(1)).analyze(any(), anyString(), any());
            when(vision.analyze(any(), anyString(), any())).thenReturn(new DocumentVisionClient.Result("image", "failed", "", "", "", List.of(), true, List.of("Synthetic failure"), "Synthetic", "synthetic-vision", DocumentVisionClient.PROMPT_VERSION));
            var forced = handle.processor.process(7L, png, "png", first, true);
            assertEquals(first.text(), forced.text()); assertTrue(forced.report().partial());
            var afterFailure = handle.processor.process(7L, png, "png", input(), false);
            assertEquals(first.text(), afterFailure.text(), "a failed forced attempt must not overwrite the reliable cache");
        }
    }
    @Test void unavailableModelStillPreservesTheImageAndExistingOcr() throws Exception {
        when(vision.capabilities()).thenReturn(Map.of("available", false, "enabled", false));
        try (var handle = handle()) {
            var result = handle.processor.process(7L, png, "png", input(), false);
            assertEquals(input().text(), result.text()); assertTrue(result.pages().getFirst().blocks().stream().anyMatch(b -> b.src() != null && b.src().startsWith("assets/")));
            verify(vision, never()).analyze(any(), anyString(), any());
        }
    }
    @Test void wordExternalRelationshipIsNeverFetchedOrExposedInMarkdown() throws Exception {
        Path word = directory.resolve("external.docx");
        try (var zip = new ZipOutputStream(Files.newOutputStream(word))) {
            zip.putNextEntry(new ZipEntry("word/_rels/document.xml.rels"));
            zip.write(("<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\"><Relationship Id=\"rId1\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/image\" TargetMode=\"External\" Target=\"https://synthetic.invalid/private.png\"/></Relationships>").getBytes(java.nio.charset.StandardCharsets.UTF_8)); zip.closeEntry();
        }
        var figure = new Block("p1-b1", "figure", "", "", 1, null, 0, 0, null, "non_text", false, -1, -1, 0, "word:word/document.xml#rId1", null, null, null);
        var original = new Extraction("empty", "", 0, null, "c".repeat(64), PIPELINE_VERSION, List.of(new Page(1, 0, 0, "logical", "text", List.of(figure))), new QualityReport(1, 1, 0, 1, null, List.of(), false, false));
        try (var handle = handle()) {
            var result = handle.processor.process(7L, word, "docx", original, false);
            var block = result.pages().getFirst().blocks().getFirst();
            assertEquals("word:word/document.xml#rId1", block.src());
            assertFalse(block.markdown().contains("https://")); assertFalse(block.markdown().contains("word:"));
            assertFalse(block.indexed()); verify(vision, never()).analyze(any(), anyString(), any());
        }
    }
    @Test void wordImageAssetsReplaceTableReferencesAndRegionBudgetResumesFromCache() throws Exception {
        Path word = directory.resolve("synthetic-images.docx");
        var figures = new java.util.ArrayList<Block>(); StringBuilder relationships = new StringBuilder(
                "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">");
        try (var zip = new ZipOutputStream(Files.newOutputStream(word))) {
            for (int i = 1; i <= 13; i++) {
                var bitmap = new BufferedImage(40, 30, BufferedImage.TYPE_INT_RGB); bitmap.setRGB(0, 0, i * 1000);
                var bytes = new java.io.ByteArrayOutputStream(); ImageIO.write(bitmap, "png", bytes);
                zip.putNextEntry(new ZipEntry("word/media/image" + i + ".png")); zip.write(bytes.toByteArray()); zip.closeEntry();
                relationships.append("<Relationship Id=\"rId").append(i).append("\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/image\" Target=\"media/image").append(i).append(".png\"/>");
                figures.add(new Block("p1-image" + i, "figure", "", "", 1, null, 0, 0, null, "non_text", false, -1, -1,
                        0, "word:word/document.xml#rId" + i, null, null, null));
            }
            relationships.append("</Relationships>"); zip.putNextEntry(new ZipEntry("word/_rels/document.xml.rels"));
            zip.write(relationships.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8)); zip.closeEntry();
        }
        figures.add(new Block("p1-table", "table", "Synthetic table", "Synthetic table", 1, null, 0, 0, null, "accepted", true, -1, -1,
                0, null, null, null, null, "| image |\n| --- |\n| ![x](word:word/document.xml#rId1) |", -1, -1));
        var source = new Extraction("ok", "Synthetic table", 15, null, "c".repeat(64), PIPELINE_VERSION,
                List.of(new Page(1, 0, 0, "logical", "text", figures)), new QualityReport(1, 1, 0, 0, null, List.of(), false, false));
        when(vision.analyze(any(), anyString(), any())).thenReturn(new DocumentVisionClient.Result("image", "recognized", "", "", "Synthetic visible shape",
                List.of(), false, List.of(), "Synthetic", "synthetic-vision", DocumentVisionClient.PROMPT_VERSION));
        try (var handle = handle()) {
            var first = handle.processor.process(7L, word, "docx", source, false);
            verify(vision, times(12)).analyze(any(), anyString(), any());
            assertTrue(first.report().partial());
            assertFalse(first.report().truncated(), "a resumable media budget is not source text truncation");
            assertEquals(1, first.pages().getFirst().blocks().stream().filter(b -> b.quality().equals("pending")).count());
            var table = first.pages().getFirst().blocks().stream().filter(b -> b.id().equals("p1-table")).findFirst().orElseThrow();
            assertTrue(table.markdown().contains("assets/")); assertFalse(table.markdown().contains("word:"));
            var continued = handle.processor.process(7L, word, "docx", first, false);
            verify(vision, times(13)).analyze(any(), anyString(), any());
            assertTrue(continued.pages().getFirst().blocks().stream().noneMatch(b -> b.quality().equals("pending")));
            assertFalse(continued.report().partial(), "a completed cached continuation clears historical media-only partial state");
            assertEquals("logical", continued.pages().getFirst().coordinateUnit());
        }
    }
    @Test void embeddedPptFormulaUsesFullShapeIndexAndKeepsOriginalPresentation() throws Exception {
        Path path = directory.resolve("synthetic-picture.pptx");
        try (var ppt = new XMLSlideShow(); var out = Files.newOutputStream(path)) {
            var slide = ppt.createSlide(); slide.createTextBox().setText("Synthetic text shape at index zero");
            slide.createPicture(ppt.addPicture(Files.readAllBytes(png), PictureType.PNG)); ppt.write(out);
        }
        byte[] original = Files.readAllBytes(path);
        when(vision.analyze(any(), anyString(), any())).thenReturn(formula("recognized"));
        try (var handle = handle()) {
            var result = handle.processor.process(7L, path, "pptx", officeInput("ppt:1#1", "slide"), false);
            assertEquals("ok", result.status());
            var image = result.pages().getFirst().blocks().getFirst();
            assertEquals("formula", image.type()); assertTrue(image.indexed()); assertNull(image.rect());
            assertEquals(0, image.width()); assertEquals("slide", result.pages().getFirst().coordinateUnit());
            assertTrue(image.src().matches("assets/[a-f0-9]{64}\\.png")); assertTrue(image.markdown().contains("x_{1}=2"));
            assertTrue(Files.isRegularFile(directory.resolve(".derived/7").resolve(image.src())));
            assertArrayEquals(original, Files.readAllBytes(path));
            var repeated = handle.processor.process(7L, path, "pptx", result, false);
            assertEquals(result.text(), repeated.text()); assertEquals(1, repeated.pages().getFirst().blocks().size());
            verify(vision, times(1)).analyze(any(), anyString(), any());
        }
    }
    @Test void spreadsheetPictureIndexIncludesNonPictureShapesAndInvalidIndexIsRejected() throws Exception {
        Path path = directory.resolve("synthetic-picture.xlsx");
        try (var workbook = new XSSFWorkbook(); var out = Files.newOutputStream(path)) {
            var drawing = workbook.createSheet("Synthetic").createDrawingPatriarch();
            drawing.createTextbox(drawing.createAnchor(0, 0, 0, 0, 0, 0, 2, 2));
            drawing.createPicture(drawing.createAnchor(0, 0, 0, 0, 3, 0, 5, 2), workbook.addPicture(Files.readAllBytes(png), Workbook.PICTURE_TYPE_PNG));
            workbook.write(out);
        }
        when(vision.analyze(any(), anyString(), any())).thenReturn(formula("recognized"));
        try (var handle = handle()) {
            var rejected = handle.processor.process(8L, path, "xlsx", officeInput("xlsx:0#0", "sheet"), false);
            assertFalse(rejected.pages().getFirst().blocks().getFirst().indexed());
            assertEquals("xlsx:0#0", rejected.pages().getFirst().blocks().getFirst().src());
            verify(vision, never()).analyze(any(), anyString(), any());
            var recognized = handle.processor.process(7L, path, "xlsx", officeInput("xlsx:0#1", "sheet"), false);
            var image = recognized.pages().getFirst().blocks().getFirst();
            assertEquals("formula", image.type()); assertTrue(image.indexed()); assertNull(image.rect());
            assertEquals("sheet", recognized.pages().getFirst().coordinateUnit());
            var missing = handle.processor.process(9L, path, "xlsx", officeInput("xlsx:9#1", "sheet"), false);
            assertFalse(missing.pages().getFirst().blocks().getFirst().indexed());
            verify(vision, times(1)).analyze(any(), anyString(), any());
        }
    }
    @Test void reprocessingIsIdempotentAndSuccessfulForceReplacesDerivedFormulaSet() throws Exception {
        var initial = new DocumentVisionClient.Result("document", "recognized", "", "Vision OCR duplicate", "Visible synthetic shape",
                List.of("x=1", "y=2"), false, List.of(), "Synthetic", "synthetic-vision", DocumentVisionClient.PROMPT_VERSION);
        when(vision.analyze(any(), anyString(), any())).thenReturn(initial);
        try (var handle = handle()) {
            var first = handle.processor.process(7L, png, "png", input(), false);
            var repeated = handle.processor.process(7L, png, "png", first, false);
            assertEquals(first.text(), repeated.text());
            assertEquals(first.pages().getFirst().blocks().stream().map(Block::id).toList(),
                    repeated.pages().getFirst().blocks().stream().map(Block::id).toList());
            var repeatedAgain = handle.processor.process(7L, png, "png", repeated, false);
            assertEquals(first.text(), repeatedAgain.text());
            assertEquals(2, repeated.pages().getFirst().blocks().stream().filter(b -> b.id().contains("-vision-formula-")).count());
            verify(vision, times(1)).analyze(any(), anyString(), any());
            when(vision.analyze(any(), anyString(), any())).thenReturn(new DocumentVisionClient.Result("document", "recognized", "", "", "Visible synthetic shape",
                    List.of("z=3"), false, List.of(), "Synthetic", "synthetic-vision", DocumentVisionClient.PROMPT_VERSION));
            var forced = handle.processor.process(7L, png, "png", repeated, true);
            assertTrue(forced.text().contains("z=3")); assertFalse(forced.text().contains("x=1")); assertFalse(forced.text().contains("y=2"));
            assertEquals(1, forced.pages().getFirst().blocks().stream().filter(b -> b.id().contains("-vision-formula-")).count());
            when(vision.analyze(any(), anyString(), any())).thenReturn(new DocumentVisionClient.Result("image", "failed", "", "", "", List.of(), true,
                    List.of("Synthetic failure"), "Synthetic", "synthetic-vision", DocumentVisionClient.PROMPT_VERSION));
            var failed = handle.processor.process(7L, png, "png", forced, true);
            assertEquals(forced.text(), failed.text());
        }
    }
    @Test void successfulMediaRetryReassessesPartialButRetainsHistoricalTruncation() {
        var original = input();
        var previous = new Extraction(original.status(), original.text(), original.chars(), original.error(), original.sourceSha256(),
                original.pipelineVersion(), original.pages(), new QualityReport(1, 1, 1, 0, .9,
                List.of("Previously pending synthetic image"), true, true));
        when(vision.analyze(any(), anyString(), any())).thenReturn(formula("recognized"));
        try (var handle = handle()) {
            var repaired = handle.processor.process(7L, png, "png", previous, false);
            assertEquals("ok", repaired.status()); assertFalse(repaired.report().partial()); assertTrue(repaired.report().truncated());
        }
    }
    @Test void corruptedExistingAssetIsVerifiedAndAtomicallyRepairedFromOriginal() throws Exception {
        when(vision.analyze(any(), anyString(), any())).thenReturn(formula("recognized"));
        try (var handle = handle()) {
            var first = handle.processor.process(7L, png, "png", input(), false);
            var image = first.pages().getFirst().blocks().stream().filter(b -> b.id().equals("p1-media")).findFirst().orElseThrow();
            Path asset = directory.resolve(".derived/7").resolve(image.src()); byte[] expected = Files.readAllBytes(asset);
            Files.writeString(asset, "synthetic damaged PNG");
            var repaired = handle.processor.process(7L, png, "png", input(), false);
            assertEquals(first.text(), repaired.text()); assertArrayEquals(expected, Files.readAllBytes(asset));
            assertNotNull(ImageIO.read(asset.toFile())); verify(vision, times(1)).analyze(any(), anyString(), any());
        }
    }
    @Test void rotatedNativePdfWithAnUnreportedRasterGetsHonestWholePageMediaWithoutLosingNativeText() throws Exception {
        Path path = directory.resolve("synthetic-rotated-native.pdf");
        String nativeText = "Synthetic native paragraph remains reliable while its illustration is processed.";
        try (var document = new PDDocument()) {
            var page = new PDPage(); page.setRotation(90); document.addPage(page);
            try (var content = new PDPageContentStream(document, page)) {
                content.beginText(); content.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
                content.newLineAtOffset(40, 720); content.showText(nativeText); content.endText();
                content.drawImage(PDImageXObject.createFromByteArray(document, Files.readAllBytes(png), "synthetic raster"), 100, 100, 360, 250);
            }
            document.save(path.toFile());
        }
        assertTrue(new PdfLayoutExtractor().extract(path).pages().stream().flatMap(p -> p.blocks().stream()).noneMatch(b -> b.type().equals("figure")),
                "the existing native layout intentionally omits rotated figure crops");
        var nativeBlock = new Block("p1-b1", "para", nativeText, nativeText, 1, null, 792, 612, null, "accepted", true, 0, nativeText.length());
        var original = new Extraction("ok", nativeText, nativeText.length(), null, "d".repeat(64), PIPELINE_VERSION,
                List.of(new Page(1, 792, 612, "pt", "text", List.of(nativeBlock))), new QualityReport(1, 1, 0, 0, null, List.of(), false, false));
        when(vision.analyze(any(), anyString(), any())).thenReturn(new DocumentVisionClient.Result("document", "recognized", "", "Synthetic duplicated OCR",
                "Visible synthetic graphic", List.of("a+b=c"), false, List.of(), "Synthetic", "synthetic-vision", DocumentVisionClient.PROMPT_VERSION));
        try (var handle = handle()) {
            var result = handle.processor.process(7L, path, "pdf", original, false);
            assertTrue(result.text().contains(nativeText)); assertFalse(result.text().contains("Synthetic duplicated OCR"));
            assertTrue(result.text().contains("a+b=c"));
            var media = result.pages().getFirst().blocks().stream().filter(b -> b.id().equals("p1-media")).findFirst().orElseThrow();
            assertArrayEquals(new double[]{0, 0, 792, 612}, media.rect());
            assertTrue(media.src().matches("assets/[a-f0-9]{64}\\.png"));
            var bitmap = ImageIO.read(directory.resolve(".derived/7").resolve(media.src()).toFile());
            assertTrue((long) bitmap.getWidth() * bitmap.getHeight() <= 5_000_000);
            assertTrue(result.report().warnings().stream().anyMatch(w -> w.contains("整页定位")));
            var repeated = handle.processor.process(7L, path, "pdf", result, false);
            assertEquals(result.text(), repeated.text()); verify(vision, times(1)).analyze(any(), anyString(), any());
        }
    }
    @Test void transientAssetFailureRetainsOfficeSourceForSuccessfulDefaultRetry() throws Exception {
        Path word = directory.resolve("synthetic-retry.docx");
        try (var zip = new ZipOutputStream(Files.newOutputStream(word))) {
            zip.putNextEntry(new ZipEntry("word/media/image.png")); zip.write(Files.readAllBytes(png)); zip.closeEntry();
            zip.putNextEntry(new ZipEntry("word/_rels/document.xml.rels"));
            zip.write(("<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\"><Relationship Id=\"rId1\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/image\" Target=\"media/image.png\"/></Relationships>").getBytes(java.nio.charset.StandardCharsets.UTF_8)); zip.closeEntry();
        }
        Path blocked = directory.resolve(".derived/7/assets"); Files.createDirectories(blocked.getParent()); Files.writeString(blocked, "synthetic temporary storage failure");
        var source = officeInput("word:word/document.xml#rId1", "logical");
        when(vision.analyze(any(), anyString(), any())).thenReturn(formula("recognized"));
        try (var handle = handle()) {
            var failed = handle.processor.process(7L, word, "docx", source, false);
            var retained = failed.pages().getFirst().blocks().getFirst();
            assertEquals("word:word/document.xml#rId1", retained.src()); assertEquals("pending", retained.quality());
            assertFalse(retained.markdown().contains("word:")); assertFalse(failed.report().truncated());
            verify(vision, never()).analyze(any(), anyString(), any());
            Files.delete(blocked);
            var retried = handle.processor.process(7L, word, "docx", failed, false);
            var image = retried.pages().getFirst().blocks().getFirst();
            assertTrue(image.indexed()); assertEquals("recognized", image.quality());
            assertTrue(image.src().matches("assets/[a-f0-9]{64}\\.png")); assertFalse(retried.report().partial());
            verify(vision, times(1)).analyze(any(), anyString(), any());
        }
    }
    @Test void defaultRetryReusesSuccessesAndRetriesCachedFailedAndPartialRegionsOnlyAfterPendingWork() throws Exception {
        Path path = directory.resolve("synthetic-failed-and-partial.pptx");
        try (var ppt = new XMLSlideShow(); var out = Files.newOutputStream(path)) {
            var slide = ppt.createSlide(); slide.createPicture(ppt.addPicture(Files.readAllBytes(png), PictureType.PNG));
            var bitmap = new BufferedImage(120, 80, BufferedImage.TYPE_INT_RGB); bitmap.setRGB(0, 0, 0x00ff00);
            var bytes = new java.io.ByteArrayOutputStream(); ImageIO.write(bitmap, "png", bytes);
            slide.createPicture(ppt.addPicture(bytes.toByteArray(), PictureType.PNG)); ppt.write(out);
        }
        var blocks = List.of(
                new Block("p1-image0", "figure", "", "", 1, null, 0, 0, null, "non_text", false, -1, -1, 0, "ppt:1#0", null, null, null),
                new Block("p1-image1", "figure", "", "", 1, null, 0, 0, null, "non_text", false, -1, -1, 0, "ppt:1#1", null, null, null));
        var source = new Extraction("empty", "", 0, null, "e".repeat(64), PIPELINE_VERSION,
                List.of(new Page(1, 0, 0, "slide", "text", blocks)), new QualityReport(1, 1, 0, 1, null, List.of(), false, false));
        var failed = new DocumentVisionClient.Result("image", "failed", "", "", "", List.of(), true, List.of("Synthetic temporary failure"),
                "Synthetic", "synthetic-vision", DocumentVisionClient.PROMPT_VERSION);
        when(vision.analyze(any(), anyString(), any())).thenReturn(failed, formula("partial"));
        try (var handle = handle()) {
            var first = handle.processor.process(7L, path, "pptx", source, false);
            verify(vision, times(2)).analyze(any(), anyString(), any());
            assertTrue(first.report().partial()); assertTrue(first.pages().getFirst().blocks().stream().noneMatch(Block::indexed));
            when(vision.analyze(any(), anyString(), any())).thenReturn(formula("recognized"));
            var retried = handle.processor.process(7L, path, "pptx", first, false);
            verify(vision, times(4)).analyze(any(), anyString(), any());
            assertEquals("ok", retried.status()); assertFalse(retried.report().partial());
            assertTrue(retried.pages().getFirst().blocks().stream().allMatch(Block::indexed));
            var again = handle.processor.process(7L, path, "pptx", retried, false);
            assertEquals(retried.text(), again.text()); verify(vision, times(4)).analyze(any(), anyString(), any());
        }
    }
    @Test void cachedOnlyRefreshPreservesLayoutAndLabelsWithoutRequestingModelsOrOcr() throws Exception {
        var base=input();
        var labeled=new Extraction(base.status(),base.text(),base.chars(),base.error(),base.sourceSha256(),base.pipelineVersion(),
                List.of(new Page(1,120,80,"px","ocr",base.pages().getFirst().blocks(),"iv")),base.report());
        when(vision.analyze(any(),anyString(),any())).thenReturn(formula("recognized"));
        try(var handle=handle()) {
            var pending=handle.processor.process(7L,png,"png",labeled,false,false);
            assertEquals("iv",pending.pages().getFirst().label());
            assertTrue(pending.pages().getFirst().blocks().stream().anyMatch(block -> "pending".equals(block.quality())));
            verify(vision,never()).analyze(any(),anyString(),any()); verifyNoInteractions(ocr);
            handle.processor.process(7L,png,"png",labeled,false);
            verify(vision,times(1)).analyze(any(),anyString(),any());
            var reused=handle.processor.process(7L,png,"png",labeled,false,false);
            assertTrue(reused.pages().getFirst().blocks().stream().anyMatch(block -> "recognized".equals(block.latexStatus())));
            assertEquals("iv",reused.pages().getFirst().label());
            verify(vision,times(1)).analyze(any(),anyString(),any());
        }
    }

    @Test void inlinePresentationAndSourceRelationshipsSurviveVisualFormulaTranscription() throws Exception {
        var presentation=new Presentation("word-p1","inline",24.0,16.0,"left","source-image");
        var image=new Block("inline","figure","","",1,new double[]{0,0,120,80},120,80,null,"non_text",false,-1,-1,
                0,null,null,null,null,null,-1,-1,presentation);
        var source=new Extraction("empty","",0,null,"b".repeat(64),PIPELINE_VERSION,
                List.of(new Page(1,120,80,"px","ocr",List.of(image))),input().report());
        when(vision.analyze(any(),anyString(),any())).thenReturn(formula("recognized"));
        try(var handle=handle()) {
            // A retained asset provides a direct image region, independently of its display dimensions.
            var assets=new DocumentMediaAssets(7L,png); var asset=assets.raster(png,0);
            image=new Block(image.id(),image.type(),image.rawText(),image.cleanText(),image.page(),image.rect(),image.width(),image.height(),null,
                    image.quality(),false,-1,-1,0,asset.src(),null,null,null,null,-1,-1,presentation);
            source=new Extraction("empty","",0,null,"b".repeat(64),PIPELINE_VERSION,
                    List.of(new Page(1,120,80,"none","text",List.of(image))),input().report());
            var result=handle.processor.process(7L,png,"docx",source,false);
            assertEquals(presentation,result.pages().getFirst().blocks().getFirst().presentation());
        }
    }

    private Extraction officeInput(String src, String unit) {
        var figure = new Block("p1-image", "figure", "", "", 1, null, 0, 0, null, "non_text", false, -1, -1, 0, src, null, null, null);
        return new Extraction("empty", "", 0, null, "c".repeat(64), PIPELINE_VERSION,
                List.of(new Page(1, 0, 0, unit, "text", List.of(figure))), new QualityReport(1, 1, 0, 1, null, List.of(), false, false));
    }
    Handle handle() { return new Handle(new DocumentMediaProcessor(new PdfLayoutExtractor(), ocr, vision)); }
    record Handle(DocumentMediaProcessor processor) implements AutoCloseable { public void close() { processor.close(); } }
}
