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
            var structure=mock(DocumentStructureClient.class);
            when(structure.configured()).thenReturn(true);
            when(structure.recognize(any(),any())).thenReturn(new DocumentStructureClient.Result(40,30,
                    List.of(new DocumentStructureClient.Block("para","Synthetic fresh local scan",new double[]{0,0,40,30},.99,0,List.of(),"")),List.of()));
            handle.processor.setStructureClient(structure);
            var saturatedWarnings=java.util.stream.IntStream.range(0,198).mapToObj(i -> "Synthetic historical warning "+i)
                    .collect(java.util.stream.Collectors.toCollection(java.util.ArrayList::new));
            saturatedWarnings.add("累计正文达到 40 万字符上限；合成历史容量提示");
            saturatedWarnings.add("Synthetic historical image failure");
            var saturated=new Extraction(continued.status(),continued.text(),continued.chars(),continued.error(),continued.sourceSha256(),continued.pipelineVersion(),
                    continued.pages(),new QualityReport(1,1,0,0,null,saturatedWarnings,false,true));
            var firstLocal=handle.processor.process(7L,word,"docx",saturated,false,true,true);
            assertEquals(1,DocumentMediaProcessor.pendingLocalScans(firstLocal));
            assertEquals(201,firstLocal.report().warnings().size());
            assertTrue(firstLocal.report().warnings().containsAll(saturatedWarnings));
            verify(structure,times(12)).recognize(any(),any());
            var missingNotice=new Extraction(firstLocal.status(),firstLocal.text(),firstLocal.chars(),firstLocal.error(),firstLocal.sourceSha256(),firstLocal.pipelineVersion(),
                    firstLocal.pages(),new QualityReport(1,1,0,0,null,saturatedWarnings,false,true));
            assertEquals(1,DocumentMediaProcessor.pendingLocalScans(missingNotice),"current markers restore intent when a saturated old report lost its notice");
            var lastLocal=handle.processor.process(7L,word,"docx",missingNotice,false);
            assertEquals(0,DocumentMediaProcessor.pendingLocalScans(lastLocal));
            assertFalse(lastLocal.report().partial());
            assertEquals(201,lastLocal.report().warnings().size());
            assertTrue(lastLocal.report().warnings().containsAll(saturatedWarnings));
            assertEquals(lastLocal.report().warnings(),DocumentMarkdownWriter.render(lastLocal).report().warnings());
            var repeated=handle.processor.process(7L,word,"docx",lastLocal,false,true,true);
            assertEquals(lastLocal.report().warnings(),repeated.report().warnings(),"repeated requests cannot evict historical warnings");
            assertEquals(13,lastLocal.pages().getFirst().blocks().stream().filter(b -> b.id().endsWith("-local-scan")).count());
            assertEquals(lastLocal.pages().getFirst().blocks().size(),lastLocal.pages().getFirst().blocks().stream().map(Block::id).distinct().count());
            verify(structure,times(13)).recognize(any(),any());
            verify(vision,times(13)).analyze(any(),anyString(),any());
        }
    }

    @Test void saturatedWarningsKeepLocalIntentWhenProcessingIsBusyBeforeTheFirstMarker() throws Exception {
        var initial=officeInput("ppt:1#0","slide");
        var warnings=java.util.stream.IntStream.range(0,200).mapToObj(i -> "Synthetic historical warning "+i).toList();
        var input=new Extraction(initial.status(),initial.text(),initial.chars(),initial.error(),initial.sourceSha256(),initial.pipelineVersion(),
                initial.pages(),new QualityReport(1,1,0,0,null,warnings,false,true));
        var structure=mock(DocumentStructureClient.class); when(structure.configured()).thenReturn(true);
        try(var handle=handle()) {
            handle.processor.setStructureClient(structure);
            var slots=(java.util.concurrent.Semaphore)org.springframework.test.util.ReflectionTestUtils.getField(handle.processor,"slots");
            assertNotNull(slots); assertTrue(slots.tryAcquire(2));
            try {
                var result=handle.processor.process(7L,png,"pptx",input,false,true,true);
                assertEquals(201,result.report().warnings().size());
                assertTrue(result.report().warnings().containsAll(warnings));
                assertEquals(1,DocumentMediaProcessor.pendingLocalScans(result));
                assertTrue(result.pages().getFirst().blocks().stream().noneMatch(b -> b.id().endsWith("-local-scan")));
                verify(structure,never()).recognize(any(),any());
            } finally { slots.release(2); }
        }
    }

    @Test void localCompletionRequiresTheCurrentProfileAndCannotLeakAcrossPages() {
        String src="assets/"+"c".repeat(64)+".png";
        var parent=new Block("shared","figure","","",1,null,0,0,null,"non_text",false,-1,-1,0,src,null,null,null);
        var current=new Block("shared-local-scan","para","","",1,null,0,0,null,"empty",false,-1,-1,0,src,null,null,"；结果：empty；尝试次数：1",null,-1,-1,
                new Presentation(null,"annotation",null,null,null,"local-scan:"+DocumentStructureClient.PROFILE+":"+src));
        var legacy=new Block("shared-local-scan","para","","",2,null,0,0,null,"empty",false,-1,-1,0,src,null,null,null,null,-1,-1,
                new Presentation(null,"annotation",null,null,null,"local-scan:high-quality-v1:"+src));
        var second=new Block("shared","figure","","",2,null,0,0,null,"non_text",false,-1,-1,0,src,null,null,null);
        var report=new QualityReport(2,2,0,0,null,List.of(),false,true);
        var both=new Extraction("empty","",0,null,"b".repeat(64),PIPELINE_VERSION,
                List.of(new Page(1,0,0,"slide","text",List.of(parent,current)),new Page(2,0,0,"slide","text",List.of(second,legacy))),report);
        assertEquals(1,DocumentMediaProcessor.pendingLocalScans(both));
        var onlyLegacy=new Extraction("empty","",0,null,both.sourceSha256(),PIPELINE_VERSION,List.of(both.pages().get(1)),report);
        assertEquals(0,DocumentMediaProcessor.pendingLocalScans(onlyLegacy),"an old profile cannot silently request a new-generation scan");
        var requested=new Extraction("empty","",0,null,both.sourceSha256(),PIPELINE_VERSION,onlyLegacy.pages(),
                new QualityReport(2,1,0,0,null,List.of("本次要求 Office 图片使用高精度扫描："+DocumentStructureClient.PROFILE),false,true));
        assertEquals(1,DocumentMediaProcessor.pendingLocalScans(requested),"an old-profile success is not a current-profile completion");
    }

    @Test void savedCurrentMarkersRecoverAllPendingRegionsWhenTheOldWarningBudgetLostTheNotice() {
        String src="assets/"+"c".repeat(64)+".png";
        var blocks=new java.util.ArrayList<Block>();
        for(int i=0;i<861;i++) {
            String id="synthetic-image-"+i;
            blocks.add(new Block(id,"figure","Retained synthetic image evidence","Retained synthetic image evidence",1,null,0,0,null,
                    "recognized",true,-1,-1,0,src,null,null,null));
            String quality=i<56?"empty":i==56?"accepted":"pending";
            String text=i==56?"Synthetic local scan":"";
            blocks.add(new Block(id+"-local-scan","para",text,text,1,null,0,0,i==56?.99:null,quality,i==56,-1,-1,0,src,null,null,null,null,-1,-1,
                    new Presentation(null,"annotation",null,null,null,"local-scan:"+DocumentStructureClient.PROFILE+":"+src)));
        }
        var warnings=java.util.stream.IntStream.range(0,200).mapToObj(i -> "Synthetic historical warning "+i).toList();
        var input=new Extraction("ok","Synthetic body",14,null,"b".repeat(64),PIPELINE_VERSION,
                List.of(new Page(1,0,0,"logical","text",blocks)),new QualityReport(1,1,0,0,null,warnings,false,true));
        assertEquals(861,DocumentMediaProcessor.pendingLocalScans(input),"old outcomes require verification under block-level review rules");
        assertEquals(0,DocumentMediaProcessor.localScanProgress(input).actualAttempted());
        assertEquals(804,blocks.stream().filter(b -> "pending".equals(b.quality())).count());
        assertEquals(200,input.report().warnings().size(),"read-only counting never rewrites a saved report");
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

    @Test void embeddedOfficeImageLocalTextUsesConfiguredScannerWithoutBasicOcrDowngrade() throws Exception {
        Path path = directory.resolve("synthetic-scanned-picture.pptx");
        try (var ppt = new XMLSlideShow(); var out = Files.newOutputStream(path)) {
            ppt.createSlide().createPicture(ppt.addPicture(Files.readAllBytes(png), PictureType.PNG)); ppt.write(out);
        }
        when(vision.capabilities()).thenReturn(Map.of("available",false));
        var structure=mock(DocumentStructureClient.class);
        when(structure.configured()).thenReturn(true);
        when(structure.recognize(any(),any())).thenReturn(new DocumentStructureClient.Result(120,80,
                List.of(new DocumentStructureClient.Block("para","Synthetic high quality image text",new double[]{0,0,120,80},
                        .98,0,List.of(),"")),List.of())).thenThrow(new IllegalStateException("synthetic scanner down"));
        try (var handle=handle()) {
            handle.processor.setStructureClient(structure);
            var result=handle.processor.process(7L,path,"pptx",officeInput("ppt:1#0","slide"),false);
            assertTrue(result.text().contains("Synthetic high quality image text"));
            var unavailable=handle.processor.process(8L,path,"pptx",officeInput("ppt:1#0","slide"),false);
            assertFalse(unavailable.text().contains("Synthetic high quality image text"));
            verify(structure,times(2)).recognize(any(),any());
            verifyNoInteractions(ocr);
            verify(vision,never()).analyze(any(),anyString(),any());
        }
    }

    @Test void forcedOfficeScanBypassesSuccessfulVisionCacheButPreservesItOnLocalFailure() throws Exception {
        Path path=directory.resolve("synthetic-cached-image.pptx");
        try (var ppt=new XMLSlideShow(); var out=Files.newOutputStream(path)) {
            ppt.createSlide().createPicture(ppt.addPicture(Files.readAllBytes(png),PictureType.PNG)); ppt.write(out);
        }
        when(vision.analyze(any(),anyString(),any())).thenReturn(new DocumentVisionClient.Result("image","recognized","",
                "Old cached OCR","Retained synthetic caption",List.of(),false,List.of(),"Synthetic","synthetic-vision",DocumentVisionClient.PROMPT_VERSION));
        var structure=mock(DocumentStructureClient.class);
        when(structure.configured()).thenReturn(true);
        when(structure.recognize(any(),any())).thenReturn(new DocumentStructureClient.Result(120,80,
                List.of(new DocumentStructureClient.Block("para","Fresh HQ visible text",new double[]{0,0,120,80},.98,0,List.of(),"")),List.of()));
        try (var handle=handle()) {
            var old=handle.processor.process(7L,path,"pptx",officeInput("ppt:1#0","slide"),false);
            handle.processor.setStructureClient(structure);
            handle.processor.process(7L,path,"pptx",old,false,false,true);
            verifyNoInteractions(structure);
            var scan=handle.processor.process(7L,path,"pptx",old,false,true,true);
            assertTrue(scan.text().contains("Fresh HQ visible text"));
            assertTrue(scan.text().contains("Retained synthetic caption"));
            assertEquals(0,DocumentMediaProcessor.pendingLocalScans(scan));
            handle.processor.process(7L,path,"pptx",scan,false,true,true);
            verify(structure,times(1)).recognize(any(),any());
            verify(vision,times(1)).analyze(any(),anyString(),any());

            Path cache;
            try (var saved=Files.list(directory.resolve(".derived/7/local-scan"))) { cache=saved.findFirst().orElseThrow(); }
            var json=new com.fasterxml.jackson.databind.ObjectMapper();
            var wrongProfile=(com.fasterxml.jackson.databind.node.ObjectNode)json.readTree(cache.toFile());
            wrongProfile.put("profile","high-quality-v1"); json.writeValue(cache.toFile(),wrongProfile);
            when(structure.recognize(any(),any())).thenThrow(new IllegalStateException("synthetic scanner unavailable"));
            var failed=handle.processor.process(7L,path,"pptx",old,false,true,true);
            assertTrue(failed.text().contains("Old cached OCR"));
            assertTrue(failed.text().contains("Retained synthetic caption"));
            assertTrue(failed.report().partial());
            assertEquals(1,DocumentMediaProcessor.pendingLocalScans(failed));
            verify(structure,times(2)).recognize(any(),any());
            verify(vision,times(1)).analyze(any(),anyString(),any());
            verifyNoInteractions(ocr);
        }
    }

    @Test void localOfficeRefreshRetainsReliableFallbackOcrEvenWhenAnotherVisualResultWasCached() throws Exception {
        Path path=directory.resolve("synthetic-retained-fallback.pptx");
        try (var ppt=new XMLSlideShow(); var out=Files.newOutputStream(path)) {
            ppt.createSlide().createPicture(ppt.addPicture(Files.readAllBytes(png),PictureType.PNG)); ppt.write(out);
        }
        when(vision.analyze(any(),anyString(),any())).thenReturn(new DocumentVisionClient.Result("image","recognized","","",
                "Different cached visual description",List.of(),false,List.of(),"Synthetic","synthetic-vision",DocumentVisionClient.PROMPT_VERSION));
        var structure=mock(DocumentStructureClient.class);
        when(structure.configured()).thenReturn(true);
        when(structure.recognize(any(),any())).thenReturn(new DocumentStructureClient.Result(120,80,
                List.of(new DocumentStructureClient.Block("para","Fresh HQ image evidence",new double[]{0,0,120,80},.99,0,List.of(),"")),List.of()));
        try (var handle=handle()) {
            var initial=officeInput("ppt:1#0","slide");
            var cached=handle.processor.process(7L,path,"pptx",initial,false);
            var image=cached.pages().getFirst().blocks().getFirst();
            String reliable="Reliable prior local OCR evidence";
            var retained=new Block(image.id(),"figure",reliable,reliable,1,image.rect(),image.width(),image.height(),null,"recognized",true,-1,-1,
                    0,image.src(),null,null,"视觉识别未完成；本地 OCR 如有合格正文则保留","![原图]("+image.src()+")\n\n"+reliable,-1,-1,image.presentation());
            var original=new Extraction("ok",reliable,reliable.length(),null,cached.sourceSha256(),PIPELINE_VERSION,
                    List.of(new Page(1,0,0,"slide","text",List.of(retained))),cached.report());
            handle.processor.setStructureClient(structure);
            var refreshed=handle.processor.process(7L,path,"pptx",original,false,true,true);
            assertTrue(refreshed.text().contains(reliable));
            assertTrue(refreshed.text().contains("Fresh HQ image evidence"));
            assertFalse(refreshed.text().contains("Different cached visual description"));
            assertEquals(0,DocumentMediaProcessor.pendingLocalScans(refreshed));
            verify(structure,times(1)).recognize(any(),any());
            verify(vision,times(1)).analyze(any(),anyString(),any());
            verifyNoInteractions(ocr);
        }
    }

    @Test void ordinaryMediaContinuationScansAnImageWithoutAMarkerAfterAPartialLocalCheckpoint() throws Exception {
        Path path=directory.resolve("synthetic-two-images.pptx");
        var bitmap=new BufferedImage(120,80,BufferedImage.TYPE_INT_RGB); bitmap.setRGB(0,0,0xffffff);
        var bytes=new java.io.ByteArrayOutputStream(); ImageIO.write(bitmap,"png",bytes);
        try (var ppt=new XMLSlideShow(); var out=Files.newOutputStream(path)) {
            var slide=ppt.createSlide();
            slide.createPicture(ppt.addPicture(Files.readAllBytes(png),PictureType.PNG));
            slide.createPicture(ppt.addPicture(bytes.toByteArray(),PictureType.PNG)); ppt.write(out);
        }
        var first=officeInput("ppt:1#0","slide");
        var second=new Block("p1-image2","figure","","",1,null,0,0,null,"non_text",false,-1,-1,0,"ppt:1#1",null,null,null);
        var two=new Extraction("empty","",0,null,first.sourceSha256(),PIPELINE_VERSION,
                List.of(new Page(1,0,0,"slide","text",List.of(first.pages().getFirst().blocks().getFirst(),second))),first.report());
        when(vision.analyze(any(),anyString(),any())).thenReturn(new DocumentVisionClient.Result("image","recognized","","",
                "Synthetic cached caption",List.of(),false,List.of(),"Synthetic","synthetic-vision",DocumentVisionClient.PROMPT_VERSION));
        var structure=mock(DocumentStructureClient.class);
        when(structure.configured()).thenReturn(true);
        when(structure.recognize(any(),any())).thenReturn(new DocumentStructureClient.Result(120,80,
                List.of(new DocumentStructureClient.Block("para","New HQ image evidence",new double[]{0,0,120,80},.99,0,List.of(),"")),List.of()));
        try (var handle=handle()) {
            var cached=handle.processor.process(7L,path,"pptx",two,false);
            handle.processor.setStructureClient(structure);
            var onlyFirst=new Extraction("ok",cached.text(),cached.chars(),null,cached.sourceSha256(),PIPELINE_VERSION,
                    List.of(new Page(1,0,0,"slide","text",List.of(cached.pages().getFirst().blocks().getFirst()))),cached.report());
            var firstScanned=handle.processor.process(7L,path,"pptx",onlyFirst,false,true,true);
            var checkpointBlocks=new java.util.ArrayList<>(firstScanned.pages().getFirst().blocks());
            checkpointBlocks.add(cached.pages().getFirst().blocks().get(1));
            var oldReport=firstScanned.report();
            var checkpoint=new Extraction("ok",firstScanned.text(),firstScanned.chars(),null,first.sourceSha256(),PIPELINE_VERSION,
                    List.of(new Page(1,0,0,"slide","text",checkpointBlocks)),new QualityReport(1,1,0,0,null,oldReport.warnings(),false,true));
            assertEquals(1,DocumentMediaProcessor.pendingLocalScans(checkpoint));
            assertTrue(checkpoint.pages().getFirst().blocks().stream().noneMatch(b -> "pending".equals(b.quality())),
                    "the timeout checkpoint has no marker for its untouched second image");
            var resumed=handle.processor.process(7L,path,"pptx",checkpoint,false);
            assertEquals(0,DocumentMediaProcessor.pendingLocalScans(resumed));
            assertFalse(resumed.report().partial());
            assertEquals(resumed.pages().getFirst().blocks().size(),resumed.pages().getFirst().blocks().stream().map(Block::id).distinct().count());
            verify(structure,times(2)).recognize(any(),any());
            verify(vision,times(2)).analyze(any(),anyString(),any());
        }
    }

    @Test void repeatedForcedLocalScansNeverAutomaticallyRetryFailedExternalVisionCache() throws Exception {
        Path path=directory.resolve("synthetic-failed-vision-cache.pptx");
        try (var ppt=new XMLSlideShow(); var out=Files.newOutputStream(path)) {
            ppt.createSlide().createPicture(ppt.addPicture(Files.readAllBytes(png),PictureType.PNG)); ppt.write(out);
        }
        when(vision.analyze(any(),anyString(),any())).thenReturn(new DocumentVisionClient.Result("image","failed","","","",
                List.of(),true,List.of("Synthetic prior failure"),"Synthetic","synthetic-vision",DocumentVisionClient.PROMPT_VERSION));
        var structure=mock(DocumentStructureClient.class);
        when(structure.configured()).thenReturn(true);
        when(structure.recognize(any(),any())).thenReturn(new DocumentStructureClient.Result(120,80,
                List.of(new DocumentStructureClient.Block("para","HQ image evidence",new double[]{0,0,120,80},.99,0,List.of(),"")),List.of()));
        try (var handle=handle()) {
            var failed=handle.processor.process(7L,path,"pptx",officeInput("ppt:1#0","slide"),false);
            verify(vision,times(1)).analyze(any(),anyString(),any());
            handle.processor.setStructureClient(structure);
            var first=handle.processor.process(7L,path,"pptx",failed,false,true,true);
            var repeated=handle.processor.process(7L,path,"pptx",first,false,true,true);
            assertTrue(repeated.text().contains("HQ image evidence"));
            assertEquals(0,DocumentMediaProcessor.pendingLocalScans(repeated));
            verify(structure,times(1)).recognize(any(),any());
            verify(vision,times(1)).analyze(any(),anyString(),any());
            handle.processor.process(7L,path,"pptx",repeated,true);
            verify(vision,times(2)).analyze(any(),anyString(),any());
        }
    }
    @Test void qualityFailuresYieldToFreshImagesAndEveryImageGetsOneAttemptBeforeTheJobStops() throws Exception {
        var fixture=localQueue(26,true);
        var structure=mock(DocumentStructureClient.class); when(structure.configured()).thenReturn(true);
        var calls=new java.util.concurrent.atomic.AtomicInteger();
        when(structure.recognize(any(),any())).thenAnswer(call -> {
            int attempt=calls.incrementAndGet();
            return new DocumentStructureClient.Result(40,30,List.of(
                    new DocumentStructureClient.Block("para","Reliable synthetic OCR line",new double[]{0,0,40,15},.99,0,List.of(),""),
                    new DocumentStructureClient.Block("para","Another synthetic OCR line",new double[]{0,15,40,30},attempt<=24?.31:.99,0,List.of(),"")),List.of());
        });
        try(var handle=handle()) {
            handle.processor.setStructureClient(structure);
            var first=handle.processor.process(7L,fixture.path(),"docx",fixture.extraction(),false,true,true);
            var a=DocumentMediaProcessor.localScanProgress(first);
            assertEquals(13,a.actualAttempted()); assertEquals(14,a.unattempted()); assertEquals(13,a.needsReview());
            assertEquals(0,a.completed()); assertEquals(0,a.failed());
            var second=handle.processor.process(7L,fixture.path(),"docx",first,false,true,true);
            var b=DocumentMediaProcessor.localScanProgress(second);
            assertEquals(25,b.actualAttempted()); assertEquals(2,b.unattempted()); assertEquals(25,b.needsReview());
            var third=handle.processor.process(7L,fixture.path(),"docx",second,false,true,true);
            var c=DocumentMediaProcessor.localScanProgress(third);
            assertEquals(27,c.actualAttempted()); assertEquals(0,c.unattempted());
            assertEquals(2,c.completed()); assertEquals(25,c.needsReview()); assertEquals(0,c.retryableFailures());
            assertTrue(third.report().partial());
            assertTrue(third.text().contains("Retained synthetic source image 1"));
            assertTrue(third.pages().getFirst().blocks().stream().filter(block -> block.id().endsWith("-local-scan")
                            && block.latexMessage().contains("结果：needs_review"))
                    .allMatch(block -> block.indexed() && block.latexMessage().endsWith("尝试次数：1")));
            handle.processor.process(7L,fixture.path(),"docx",third,false,true,true);
            verify(structure,times(26)).recognize(any(),any());
            verify(vision,never()).analyze(any(),anyString(),any());
            verifyNoInteractions(ocr);
        }
    }

    @Test void requestFailuresRetryAtMostOnceOnlyAfterAllFreshImagesAndDuplicatesShareTheOutcome() throws Exception {
        var fixture=localQueue(13,true);
        var structure=mock(DocumentStructureClient.class); when(structure.configured()).thenReturn(true);
        var calls=new java.util.concurrent.atomic.AtomicInteger();
        when(structure.recognize(any(),any())).thenAnswer(call -> {
            int attempt=calls.incrementAndGet();
            if(attempt!=13 && attempt<=25) throw new IllegalStateException("Synthetic local request failed");
            return new DocumentStructureClient.Result(40,30,List.of(new DocumentStructureClient.Block("para","Synthetic fresh last image",new double[]{0,0,40,30},.99,0,List.of(),"")),List.of());
        });
        try(var handle=handle()) {
            handle.processor.setStructureClient(structure);
            var session=new DocumentMediaProcessor.LocalScanSession();
            var first=handle.processor.process(7L,fixture.path(),"docx",fixture.extraction(),false,true,true,session);
            var a=DocumentMediaProcessor.localScanProgress(first);
            assertEquals(13,a.failed()); assertEquals(1,a.unattempted()); assertEquals(13,a.retryableFailures());
            var fresh=handle.processor.process(7L,fixture.path(),"docx",first,false,true,true,session);
            var b=DocumentMediaProcessor.localScanProgress(fresh);
            assertEquals(1,b.completed()); assertEquals(0,b.unattempted()); assertEquals(13,b.failed());
            assertEquals(13,calls.get(),"failed earlier images must not displace the last fresh image");
            var retried=handle.processor.process(7L,fixture.path(),"docx",fresh,false,true,true,session);
            var c=DocumentMediaProcessor.localScanProgress(retried);
            assertEquals(14,c.actualAttempted()); assertEquals(13,c.failed()); assertEquals(0,c.retryableFailures());
            assertTrue(retried.pages().getFirst().blocks().stream().filter(block -> "local_scan_failed".equals(block.quality()))
                    .allMatch(block -> block.latexMessage().contains("结果：request_failed") && block.latexMessage().endsWith("尝试次数：2")));
            assertTrue(retried.report().partial());
            handle.processor.process(7L,fixture.path(),"docx",retried,false,true,true,session);
            verify(structure,times(25)).recognize(any(),any());
            var recovered=handle.processor.process(7L,fixture.path(),"docx",retried,false,true,true,new DocumentMediaProcessor.LocalScanSession());
            assertEquals(14,DocumentMediaProcessor.localScanProgress(recovered).completed());
            assertEquals(0,DocumentMediaProcessor.localScanProgress(recovered).failed());
            assertTrue(recovered.pages().getFirst().blocks().stream().filter(block -> block.id().endsWith("-local-scan")
                            && block.latexMessage().contains("尝试次数：3"))
                    .allMatch(block -> block.latexMessage().contains("本任务尝试次数：1")));
            verify(structure,times(37)).recognize(any(),any());
            verify(vision,never()).analyze(any(),anyString(),any());
        }
    }

    @Test void partialImageIndexesOnlyQualifiedBlocksAndCachedOutcomeStaysPartialWithSaturatedWarnings() throws Exception {
        var fixture=localQueue(1,false); var source=fixture.extraction();
        var warnings=java.util.stream.IntStream.range(0,200).mapToObj(i -> "Synthetic historical warning "+i).toList();
        var input=new Extraction(source.status(),source.text(),source.chars(),source.error(),source.sourceSha256(),source.pipelineVersion(),source.pages(),
                new QualityReport(1,1,0,0,null,warnings,false,true));
        var structure=mock(DocumentStructureClient.class); when(structure.configured()).thenReturn(true);
        var blocks=new java.util.ArrayList<DocumentStructureClient.Block>();
        for(int i=0;i<44;i++) blocks.add(new DocumentStructureClient.Block("para","Accepted synthetic line "+i,new double[]{0,0,40,30},.98,0,List.of(),""));
        blocks.add(new DocumentStructureClient.Block("para","Rejected synthetic uncertain line",new double[]{0,0,40,30},.31,0,List.of(),""));
        when(structure.recognize(any(),any())).thenReturn(new DocumentStructureClient.Result(40,30,blocks,List.of()));
        try(var handle=handle()) {
            handle.processor.setStructureClient(structure);
            var first=DocumentMarkdownWriter.render(handle.processor.process(7L,fixture.path(),"docx",input,false,true,true));
            assertTrue(first.text().contains("Retained synthetic source image 1"));
            for(int i=0;i<44;i++) assertTrue(first.text().contains("Accepted synthetic line "+i));
            assertFalse(first.text().contains("Rejected synthetic uncertain line"));
            assertTrue(first.report().partial()); assertEquals(201,first.report().warnings().size());
            var marker=first.pages().getFirst().blocks().stream().filter(b -> b.id().endsWith("-local-scan")).findFirst().orElseThrow();
            assertTrue(marker.indexed()); assertEquals("accepted",marker.quality());
            assertTrue(marker.latexMessage().contains("结果：needs_review；合格块：44；待核对块：1"));
            var progress=DocumentMediaProcessor.localScanProgress(first);
            assertEquals(1,progress.actualAttempted()); assertEquals(0,progress.unattempted()); assertEquals(0,progress.completed());
            assertEquals(1,progress.needsReview()); assertEquals(1,progress.acceptedTextRegions());
            var incorrectlyCleared=new Extraction(first.status(),first.text(),first.chars(),first.error(),first.sourceSha256(),first.pipelineVersion(),first.pages(),
                    new QualityReport(1,1,0,0,null,first.report().warnings(),false,false));
            assertTrue(DocumentMarkdownWriter.render(incorrectlyCleared).report().partial(),"the fixed image outcome independently preserves partial state");
            var repeated=DocumentMarkdownWriter.render(handle.processor.process(7L,fixture.path(),"docx",first,false,true,true));
            assertEquals(first.text(),repeated.text()); assertTrue(repeated.report().partial());
            assertEquals(progress,DocumentMediaProcessor.localScanProgress(repeated));
            verify(structure,times(1)).recognize(any(),any()); verify(vision,never()).analyze(any(),anyString(),any());
        }
    }

    @Test void partialChineseCacheLargerThan128KiBIsReusedAndFormulaCandidatesRemainUnindexed() throws Exception {
        var fixture=localQueue(1,false);
        String accepted="这是用于缓存验证的合成中文内容。".repeat(3000);
        assertTrue(accepted.getBytes(java.nio.charset.StandardCharsets.UTF_8).length>128_000); assertTrue(accepted.length()<60_000);
        var structure=mock(DocumentStructureClient.class); when(structure.configured()).thenReturn(true);
        when(structure.recognize(any(),any())).thenReturn(new DocumentStructureClient.Result(40,30,List.of(
                new DocumentStructureClient.Block("para",accepted,new double[]{0,0,40,15},.99,0,List.of(),""),
                new DocumentStructureClient.Block("formula","",new double[]{0,15,40,30},null,0,List.of(),"x_{synthetic}=9")),List.of()));
        try(var handle=handle()) {
            handle.processor.setStructureClient(structure);
            var first=DocumentMarkdownWriter.render(handle.processor.process(7L,fixture.path(),"docx",fixture.extraction(),false,true,true));
            assertTrue(first.text().contains(accepted)); assertFalse(first.text().contains("x_{synthetic}=9")); assertTrue(first.report().partial());
            assertEquals(1,DocumentMediaProcessor.localScanProgress(first).needsReview());
            var repeated=DocumentMarkdownWriter.render(handle.processor.process(7L,fixture.path(),"docx",first,false,true,true));
            assertEquals(first.text(),repeated.text()); assertTrue(repeated.report().partial());
            verify(structure,times(1)).recognize(any(),any());
        }
    }

    @Test void aLaterExplicitTaskGivesEveryOldFailedImageABoundedNewChanceAcrossMultipleBatches() throws Exception {
        var fixture=localQueue(26,false);
        var structure=mock(DocumentStructureClient.class); when(structure.configured()).thenReturn(true);
        when(structure.recognize(any(),any())).thenThrow(new IllegalStateException("Synthetic request failed"));
        try(var handle=handle()) {
            handle.processor.setStructureClient(structure);
            Extraction result=fixture.extraction();
            for(int task=0;task<2;task++) {
                var session=new DocumentMediaProcessor.LocalScanSession();
                for(int round=0;round<10;round++) {
                    result=handle.processor.process(7L,fixture.path(),"docx",result,false,true,true,session);
                    var progress=DocumentMediaProcessor.localScanProgress(result);
                    if(progress.unattempted()==0 && progress.retryableFailures()==0) break;
                }
                assertEquals(52,session.totalCalls(),"each distinct image gets at most two real requests in this task");
                var progress=DocumentMediaProcessor.localScanProgress(result);
                assertEquals(26,progress.failed()); assertEquals(0,progress.unattempted()); assertEquals(0,progress.retryableFailures());
                assertEquals(0,progress.completed()); assertTrue(result.report().partial());
                int total=(task+1)*2;
                assertTrue(result.pages().getFirst().blocks().stream().filter(block -> block.id().endsWith("-local-scan"))
                        .allMatch(block -> block.latexMessage().endsWith("尝试次数："+total)));
            }
            verify(structure,times(104)).recognize(any(),any());
            verify(vision,never()).analyze(any(),anyString(),any());
        }
    }

    @Test void oldSuccessfulCachesWithoutOutcomesAreReverifiedAcrossBatchesWithoutLosingTheirReliableText() throws Exception {
        var fixture=localQueue(13,false);
        var structure=mock(DocumentStructureClient.class); when(structure.configured()).thenReturn(true);
        when(structure.recognize(any(),any())).thenReturn(new DocumentStructureClient.Result(40,30,List.of(
                new DocumentStructureClient.Block("para","Legacy reliable synthetic local evidence",new double[]{0,0,40,30},.99,0,List.of(),"")),List.of()));
        try(var handle=handle()) {
            handle.processor.setStructureClient(structure);
            var first=handle.processor.process(7L,fixture.path(),"docx",fixture.extraction(),false,true,true);
            var completed=handle.processor.process(7L,fixture.path(),"docx",first,false,true,true);
            assertEquals(13,DocumentMediaProcessor.localScanProgress(completed).completed());
            var mapper=new com.fasterxml.jackson.databind.ObjectMapper();
            try(var caches=Files.list(directory.resolve(".derived/7/local-scan"))) {
                for(Path cache:caches.toList()) {
                    var node=(com.fasterxml.jackson.databind.node.ObjectNode)mapper.readTree(cache.toFile());
                    node.remove(List.of("outcome","attempts","acceptedBlocks","rejectedBlocks")); mapper.writeValue(cache.toFile(),node);
                }
            }
            var page=completed.pages().getFirst();
            var blocks=page.blocks().stream().map(b -> b.id().endsWith("-local-scan")
                    ? new Block(b.id(),b.type(),b.rawText(),b.cleanText(),b.page(),b.rect(),b.width(),b.height(),b.confidence(),b.quality(),b.indexed(),
                        b.start(),b.end(),b.level(),b.src(),b.latex(),b.latexStatus(),null,b.markdown(),b.markdownStart(),b.markdownEnd(),b.presentation()):b).toList();
            var legacy=new Extraction(completed.status(),completed.text(),completed.chars(),null,completed.sourceSha256(),completed.pipelineVersion(),
                    List.of(new Page(page.page(),page.width(),page.height(),page.coordinateUnit(),page.method(),blocks)),completed.report());
            assertEquals(13,DocumentMediaProcessor.localScanProgress(legacy).unattempted());
            when(structure.recognize(any(),any())).thenReturn(new DocumentStructureClient.Result(40,30,List.of(
                    new DocumentStructureClient.Block("formula","",new double[]{0,0,40,30},null,0,List.of(),"x_{synthetic}=1")),List.of()));
            var a=DocumentMarkdownWriter.render(handle.processor.process(7L,fixture.path(),"docx",legacy,false,true,true));
            assertEquals(1,DocumentMediaProcessor.localScanProgress(a).unattempted());
            assertEquals(12,DocumentMediaProcessor.localScanProgress(a).needsReview());
            assertTrue(a.text().contains("Legacy reliable synthetic local evidence")); assertTrue(a.report().partial());
            assertEquals(13,a.pages().getFirst().blocks().stream().filter(b -> b.id().endsWith("-local-scan-retained")).count());
            var b=DocumentMarkdownWriter.render(handle.processor.process(7L,fixture.path(),"docx",a,false,true,true));
            assertEquals(0,DocumentMediaProcessor.localScanProgress(b).unattempted());
            assertEquals(13,DocumentMediaProcessor.localScanProgress(b).needsReview());
            assertTrue(b.text().contains("Legacy reliable synthetic local evidence"));
            assertFalse(b.text().contains("x_{synthetic}=1"));
            handle.processor.process(7L,fixture.path(),"docx",b,false,true,true);
            verify(structure,times(26)).recognize(any(),any());
            verify(vision,never()).analyze(any(),anyString(),any());
        }
    }

    private record LocalQueue(Path path,Extraction extraction) { }

    private LocalQueue localQueue(int images,boolean duplicate) throws Exception {
        Path word=directory.resolve("synthetic-local-queue.docx");
        var blocks=new java.util.ArrayList<Block>();
        var relationships=new StringBuilder("<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">");
        try(var zip=new ZipOutputStream(Files.newOutputStream(word))) {
            for(int i=1;i<=images;i++) {
                var bitmap=new BufferedImage(40,30,BufferedImage.TYPE_INT_RGB); bitmap.setRGB(0,0,i*1000);
                var bytes=new java.io.ByteArrayOutputStream(); ImageIO.write(bitmap,"png",bytes);
                zip.putNextEntry(new ZipEntry("word/media/image"+i+".png")); zip.write(bytes.toByteArray()); zip.closeEntry();
                relationships.append("<Relationship Id=\"rId").append(i).append("\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/image\" Target=\"media/image").append(i).append(".png\"/>");
                String id="p1-image"+i,text="Retained synthetic source image "+i,src="word:word/document.xml#rId"+i;
                blocks.add(new Block(id,"figure",text,text,1,null,0,0,null,"recognized",true,-1,-1,0,src,null,null,null,text,-1,-1));
                blocks.add(new Block(id+"-local-scan","para","","",1,null,0,0,null,"pending",false,-1,-1,0,src,null,null,
                        "本地高精度扫描："+DocumentStructureClient.PROFILE,null,-1,-1,
                        new Presentation(null,"annotation",null,null,null,"local-scan:"+DocumentStructureClient.PROFILE+":"+src)));
            }
            relationships.append("</Relationships>"); zip.putNextEntry(new ZipEntry("word/_rels/document.xml.rels"));
            zip.write(relationships.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8)); zip.closeEntry();
        }
        if(duplicate) blocks.add(new Block("p1-duplicate","figure","Retained repeated source","Retained repeated source",1,null,0,0,null,
                "recognized",true,-1,-1,0,"word:word/document.xml#rId1",null,null,null,"Retained repeated source",-1,-1));
        return new LocalQueue(word,new Extraction("ok","Retained synthetic source",25,null,"c".repeat(64),PIPELINE_VERSION,
                List.of(new Page(1,0,0,"logical","text",blocks)),new QualityReport(1,1,0,0,null,List.of(),false,true)));
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
