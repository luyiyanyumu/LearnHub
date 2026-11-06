package org.dyh.learnhub.service;

import org.dyh.learnhub.ai.DeepSeekClient;
import org.dyh.learnhub.ai.ModelRouting;
import org.dyh.learnhub.common.FileTextExtractedEvent;
import org.dyh.learnhub.common.KnowledgeChangedEvent;
import org.dyh.learnhub.entity.FileInfo;
import org.dyh.learnhub.mapper.FileInfoMapper;
import org.dyh.learnhub.mapper.KbChunkMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.context.ApplicationEventPublisher;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class DocumentIngestionRetrievalTest {
    @TempDir Path work;

    @Test void acceptedOcrBecomesSearchableEvidenceAndCarriesVerifiedOriginalCoordinates() throws Exception {
        String priorDirectory = System.getProperty("user.dir");
        System.setProperty("user.dir", work.toString());
        try {
            Path source = work.resolve("uploads/synthetic.png"); Files.createDirectories(source.getParent());
            Files.writeString(source, "synthetic-original-fixture");
            String sha = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(source)));
            String accepted = "Cedar synthesis keeps the source coordinates for retrieval.";
            String rejected = "Maple false reading must remain outside indexed knowledge.";
            var good = new DocumentIngestionService.Block("p1-b1", "para", accepted, accepted, 1,
                    new double[]{10,20,300,60}, 600, 800, .98, "accepted", true, 0, accepted.length());
            var bad = new DocumentIngestionService.Block("p1-b2", "para", rejected, rejected, 1,
                    new double[]{10,80,300,120}, 600, 800, .25, "rejected", false, -1, -1);
            var result = new DocumentIngestionService.Extraction("ok", accepted, accepted.length(), "部分区域待核对", sha,
                    "document-ingestion-v1", List.of(new DocumentIngestionService.Page(1, 600, 800, "px", "ocr", List.of(good,bad))),
                    new DocumentIngestionService.QualityReport(1,1,1,0,.615,List.of("一个低置信度区域未入索引"),false,true));
            var info = new FileInfo(); info.setId(31L); info.setStoreName("synthetic.png"); info.setOriginName("OCR synthetic fixture");
            info.setExt("png"); info.setSize(Files.size(source));
            var mapper = mock(FileInfoMapper.class); when(mapper.selectById(31L)).thenReturn(info);
            var text = mock(DocumentTextService.class); when(text.extractDetailed(source,"png",info.getSize())).thenReturn(result);
            var events = mock(ApplicationEventPublisher.class);
            var files = new FileStorageService(mapper,null,text,new PdfLayoutExtractor(),new WordLayoutExtractor(),new TextMathLayout(),
                    events,null,null,mock(FolderService.class));
            files.reextract(31L);
            assertEquals("ok",info.getTextStatus()); assertEquals(accepted,info.getTextContent());
            verify(events).publishEvent(isA(KnowledgeChangedEvent.class));
            verify(events, never()).publishEvent(isA(FileTextExtractedEvent.class));
            assertTrue(files.extractionOf(31L) instanceof DocumentIngestionService.Extraction);
            assertEquals(1,((List<?>) ((Map<?,?>)((List<?>)files.layoutOf(31L).get("pages")).getFirst()).get("blocks")).size(),
                    "rejected OCR must not look like accepted text in the reading/copy view");

            var sources = mock(KbChunkMapper.class);
            when(sources.allFiles()).thenAnswer(inv -> List.of(Map.of("id",31L,"title",info.getOriginName(),"content",info.getTextContent())));
            var vectors = mock(VectorIndexService.class);
            var retrieval = new KnowledgeRetrievalService(sources,vectors,mock(GraphEvidenceService.class),mock(RerankService.class),
                    mock(SettingsService.class),mock(ModelRouting.class),mock(DeepSeekClient.class),mock(WikiRetrievalService.class));
            retrieval.setFileStorage(files);
            var hits = retrieval.search("Cedar",5,"keyword",false);
            assertEquals(1,hits.size());
            assertEquals(accepted,hits.getFirst().text());
            assertEquals("p1-b1",hits.getFirst().sourceLocations().getFirst().blockId());
            assertArrayEquals(new double[]{10,20,300,60},hits.getFirst().sourceLocations().getFirst().rect());
            assertTrue(retrieval.search("Maple",5,"keyword",false).isEmpty());
            assertEquals("synthetic-original-fixture",Files.readString(source));
            Files.writeString(source,"synthetic-replaced-fixture");
            assertTrue(files.sourceLocations(31L,accepted).isEmpty(),"a changed original must not receive stale highlights");
        } finally { System.setProperty("user.dir",priorDirectory); }
    }
}
