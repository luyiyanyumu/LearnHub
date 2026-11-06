package org.dyh.learnhub.service;

import org.dyh.learnhub.entity.FileInfo;
import org.dyh.learnhub.mapper.FileInfoMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.context.ApplicationEventPublisher;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.dyh.learnhub.service.DocumentIngestionService.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Synthetic OOXML exercises the actual version migration, cached semantics and both offset projections. */
class DocumentLayoutRefreshTest {
    @TempDir Path directory;
    @Test void layoutMigrationReusesVisualResultsAndRestoresInlineImageWithoutChangingTheOriginal() throws Exception {
        refresh(false);
    }
    @Test void layoutMigrationRetainsAcceptedLocalOcrWhenTheVisualCacheContainsAFailedRequest() throws Exception {
        refresh(true);
    }
    private void refresh(boolean localFallback) throws Exception {
        String prior=System.getProperty("user.dir"); System.setProperty("user.dir",directory.toString());
        try {
            Path source=directory.resolve("uploads/synthetic.docx"); Files.createDirectories(source.getParent());
            var pixels=new java.awt.image.BufferedImage(120,60,java.awt.image.BufferedImage.TYPE_INT_RGB);
            var encoded=new java.io.ByteArrayOutputStream(); javax.imageio.ImageIO.write(pixels,"png",encoded); pixels.flush();
            try(var zip=new ZipOutputStream(Files.newOutputStream(source))) {
                add(zip,"word/document.xml",("<w:document xmlns:w=\"http://schemas.openxmlformats.org/wordprocessingml/2006/main\" xmlns:r=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships\" xmlns:wp=\"http://schemas.openxmlformats.org/drawingml/2006/wordprocessingDrawing\" xmlns:a=\"http://schemas.openxmlformats.org/drawingml/2006/main\"><w:body><w:p><w:r><w:t xml:space=\"preserve\">Before </w:t></w:r><w:r><w:drawing><wp:inline><wp:extent cx=\"228600\" cy=\"114300\"/><a:graphic><a:graphicData><a:blip r:embed=\"rId1\"/></a:graphicData></a:graphic></wp:inline></w:drawing></w:r><w:r><w:t xml:space=\"preserve\"> after</w:t></w:r></w:p></w:body></w:document>").getBytes(java.nio.charset.StandardCharsets.UTF_8));
                add(zip,"word/_rels/document.xml.rels",("<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\"><Relationship Id=\"rId1\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/image\" Target=\"media/image.png\"/></Relationships>").getBytes(java.nio.charset.StandardCharsets.UTF_8));
                add(zip,"word/media/image.png",encoded.toByteArray());
            }
            byte[] original=Files.readAllBytes(source);
            String sha=HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(original));
            var oldBlocks=List.of(
                    new Block("p1-b1","para","Before","Before",1,null,0,0,null,"accepted",true,-1,-1),
                    new Block("p1-b2","figure","","",1,null,0,0,null,"non_text",false,-1,-1,0,"word:word/document.xml#rId1",null,null,null),
                    new Block("p1-b3","para","after","after",1,null,0,0,null,"accepted",true,-1,-1));
            var old=new Extraction("ok","",0,null,sha,"document-ingestion-v2-md",List.of(new Page(1,0,0,"none","text",oldBlocks)),
                    new QualityReport(1,1,0,0,null,List.of(),false,false));
            var vision=mock(DocumentVisionClient.class); when(vision.identity()).thenReturn("a".repeat(64));
            when(vision.capabilities()).thenReturn(Map.of("available",true));
            when(vision.analyze(any(),anyString(),any())).thenReturn(new DocumentVisionClient.Result("formula","recognized","x=2","","",List.of(),false,List.of(),"Synthetic","synthetic-vision",DocumentVisionClient.PROMPT_VERSION));
            var ocr=mock(DocumentOcrClient.class);
            if(localFallback) {
                when(vision.analyze(any(),anyString(),any())).thenReturn(new DocumentVisionClient.Result("image","failed","","","",List.of(),true,List.of("Synthetic provider failure"),"Synthetic","synthetic-vision",DocumentVisionClient.PROMPT_VERSION));
                when(ocr.configured()).thenReturn(true);
                when(ocr.recognize(any(),any())).thenReturn(new DocumentOcrClient.Result("synthetic-ocr","1",120,60,
                        List.of(new DocumentOcrClient.Block("ocr","para","Cedar retained OCR",new double[]{0,0,120,60},.95,3)),List.of()));
            }
            var media=new DocumentMediaProcessor(new PdfLayoutExtractor(),ocr,vision);
            try {
                var seeded=DocumentMarkdownWriter.render(media.process(71L,source,"docx",old,false));
                var legacy=new Extraction(seeded.status(),seeded.text(),seeded.chars(),seeded.error(),sha,"document-ingestion-v2-md",seeded.pages(),seeded.report(),seeded.markdown(),"markdown");
                new DocumentExtractionStore().save(71L,source,legacy);
                var info=new FileInfo(); info.setId(71L); info.setExt("docx"); info.setStoreName("synthetic.docx"); info.setSize((long)original.length);
                info.setTextStatus("ok"); info.setTextContent(legacy.text());
                var mapper=mock(FileInfoMapper.class); when(mapper.selectById(71L)).thenReturn(info);
                var storage=new FileStorageService(mapper,null,new DocumentTextService(),new PdfLayoutExtractor(),new WordLayoutExtractor(),new TextMathLayout(),mock(ApplicationEventPublisher.class),null,null,mock(FolderService.class));
                storage.setMediaProcessor(media); storage.reextract(71L,false);
                var result=(Extraction)storage.extractionOf(71L);
                assertEquals(PIPELINE_VERSION,result.pipelineVersion());
                assertTrue(result.markdown().startsWith("Before <img src=\"assets/"));
                assertTrue(result.markdown().endsWith(" after"));
                assertTrue(result.markdown().contains("width=\"24\" height=\"12\""));
                String evidence=localFallback?"Cedar retained OCR":"x=2";
                assertFalse(result.markdown().contains(evidence),"transcription is searchable without duplicating the original image in display");
                assertTrue(result.text().contains(evidence));
                assertFalse(storage.sourceLocations(71L,evidence).isEmpty());
                assertArrayEquals(original,Files.readAllBytes(source));
                verify(vision,times(1)).analyze(any(),anyString(),any());
                if(localFallback) verify(ocr,times(1)).recognize(any(),any()); else verifyNoInteractions(ocr);
                DocumentMarkdownWriterTest.assertOffsets(result);
            } finally { media.close(); }
        } finally {System.setProperty("user.dir",prior);}
    }
    private static void add(ZipOutputStream zip,String name,byte[] bytes) throws Exception {
        zip.putNextEntry(new ZipEntry(name)); zip.write(bytes); zip.closeEntry();
    }
}
