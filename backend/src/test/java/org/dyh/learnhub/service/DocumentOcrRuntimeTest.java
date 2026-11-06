package org.dyh.learnhub.service;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.apache.pdfbox.pdmodel.graphics.image.LosslessFactory;
import org.dyh.learnhub.ai.DeepSeekClient;
import org.dyh.learnhub.ai.ModelRouting;
import org.dyh.learnhub.entity.FileInfo;
import org.dyh.learnhub.mapper.FileInfoMapper;
import org.dyh.learnhub.mapper.KbChunkMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.context.ApplicationEventPublisher;

import java.awt.Color;
import java.awt.Font;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Explicit opt-in real OCR acceptance. Uses only generated paper and a disposable local service. */
@EnabledIfEnvironmentVariable(named = "LEARNHUB_DOCUMENT_OCR_SMOKE_URL", matches = "http://127\\.0\\.0\\.1:[0-9]+/?")
class DocumentOcrRuntimeTest {
    @TempDir Path work;

    @Test void mixedPdfIsRecognizedIndexedAndLocatedOnTheMatchingScannedPage() throws Exception {
        String priorDirectory = System.getProperty("user.dir");
        System.setProperty("user.dir", work.toString());
        var ingestion = new DocumentIngestionService(new PdfLayoutExtractor(),
                new DocumentOcrClient(System.getenv("LEARNHUB_DOCUMENT_OCR_SMOKE_URL")));
        try {
            Path source = work.resolve("uploads/synthetic-mixed.pdf"); Files.createDirectories(source.getParent());
            try (PDDocument document = new PDDocument()) {
                PDPage nativePage = new PDPage(new PDRectangle(600,800)); document.addPage(nativePage);
                try (PDPageContentStream content = new PDPageContentStream(document,nativePage)) {
                    content.beginText(); content.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA),16);
                    content.newLineAtOffset(50,700); content.showText("Native Birch evidence belongs to the first synthetic page."); content.endText();
                }
                BufferedImage paper = new BufferedImage(2400,3200,BufferedImage.TYPE_INT_RGB);
                var graphics = paper.createGraphics();
                graphics.setColor(Color.WHITE); graphics.fillRect(0,0,paper.getWidth(),paper.getHeight());
                graphics.setColor(Color.BLACK); graphics.setFont(new Font(Font.SANS_SERIF,Font.PLAIN,56));
                graphics.drawString("ScanCedar evidence belongs to the second synthetic page.",120,220);
                graphics.drawString("Original page positions must survive knowledge retrieval.",120,340);
                graphics.dispose();
                PDPage scannedPage = new PDPage(new PDRectangle(600,800)); document.addPage(scannedPage);
                try (PDPageContentStream content = new PDPageContentStream(document,scannedPage)) {
                    content.drawImage(LosslessFactory.createFromImage(document,paper),0,0,600,800);
                }
                paper.flush(); document.save(source.toFile());
            }
            var text = new DocumentTextService(); text.setDocumentIngestionService(ingestion);
            var info = new FileInfo(); info.setId(51L); info.setExt("pdf"); info.setStoreName("synthetic-mixed.pdf");
            info.setOriginName("Synthetic mixed paper"); info.setSize(Files.size(source));
            var mapper = mock(FileInfoMapper.class); when(mapper.selectById(51L)).thenReturn(info);
            var files = new FileStorageService(mapper,null,text,new PdfLayoutExtractor(),new WordLayoutExtractor(),new TextMathLayout(),
                    mock(ApplicationEventPublisher.class),null,null,mock(FolderService.class));
            files.reextract(51L);
            assertEquals("ok",info.getTextStatus(),String.valueOf(info.getTextError()));
            assertTrue(info.getTextContent().contains("Native Birch"),info.getTextContent());
            assertTrue(info.getTextContent().contains("ScanCedar"),info.getTextContent());
            var extraction = (DocumentIngestionService.Extraction) files.extractionOf(51L);
            assertEquals(2,extraction.report().totalPages()); assertEquals(1,extraction.report().ocrPages());
            assertEquals("text",extraction.pages().getFirst().method()); assertEquals("ocr",extraction.pages().get(1).method());
            assertFalse(extraction.report().partial(),extraction.report().warnings().toString());

            var sources = mock(KbChunkMapper.class);
            when(sources.allFiles()).thenReturn(List.of(Map.of("id",51L,"title",info.getOriginName(),"content",info.getTextContent())));
            var retrieval = new KnowledgeRetrievalService(sources,mock(VectorIndexService.class),mock(GraphEvidenceService.class),mock(RerankService.class),
                    mock(SettingsService.class),mock(ModelRouting.class),mock(DeepSeekClient.class),mock(WikiRetrievalService.class));
            retrieval.setFileStorage(files);
            var hits = retrieval.search("ScanCedar",5,"keyword",false);
            assertFalse(hits.isEmpty());
            var location = hits.getFirst().sourceLocations().getFirst();
            assertEquals(2,location.page(),"a multi-page chunk must open the page matching the question");
            assertNotNull(location.rect()); assertEquals("ocr",location.method());
            assertEquals(extraction.sourceSha256(),location.sourceRevision());
            assertTrue(Files.isRegularFile(source));
        } finally { ingestion.close(); System.setProperty("user.dir",priorDirectory); }
    }
}
