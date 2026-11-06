package org.dyh.learnhub.service;

import org.dyh.learnhub.ai.DeepSeekClient;
import org.dyh.learnhub.ai.ModelRouting;
import org.dyh.learnhub.entity.FileInfo;
import org.dyh.learnhub.mapper.FileInfoMapper;
import org.dyh.learnhub.mapper.KbChunkMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipInputStream;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class MarkdownKnowledgeIntegrationTest {
    @TempDir Path work;

    @Test void markdownRetainsBaselineHitsAddsVisualEvidenceAndKeepsExactSourceBlocks() throws Exception {
        String prior=System.getProperty("user.dir"); System.setProperty("user.dir",work.toString());
        try {
            Files.createDirectories(work.resolve("uploads"));
            var mapper=mock(FileInfoMapper.class); Map<Long,FileInfo> rows=new HashMap<>();
            when(mapper.selectById(anyLong())).thenAnswer(call -> rows.get(call.<Long>getArgument(0)));
            var files=new FileStorageService(mapper,null,null,new PdfLayoutExtractor(),null,null,null,null,null,mock(FolderService.class));
            List<Map<String,Object>> originalSources=new ArrayList<>(),markdownSources=new ArrayList<>();
            String[] labels={"SpectraCedar","TableMaple","LossOrchid","CodeJuniper","VisualHarbor"};
            String[] raw={"SpectraCedar preserves fifteen neighboring passages.",
                    "TableMaple throughput is 42 requests per second.",
                    "LossOrchid is the synthetic loss function.",
                    "const CodeJuniper = `https://example.invalid/assets/not-an-image.png`;",
                    "VisualHarbor connects the synthetic blue node to the green node."};
            byte[] image;
            try(var encoded=new java.io.ByteArrayOutputStream()) {
                var bitmap=new java.awt.image.BufferedImage(20,20,java.awt.image.BufferedImage.TYPE_INT_RGB);
                javax.imageio.ImageIO.write(bitmap,"png",encoded); image=encoded.toByteArray(); bitmap.flush();
            }
            String imageHash=HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(image));
            for(int n=0;n<labels.length;n++) {
                long id=n+1; int page=n+2; Path source=work.resolve("uploads/source-"+id+".pdf");
                Files.writeString(source,"Immutable synthetic original "+id);
                String sha=HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(source)));
                String type=n==1?"table":n==2?"formula":n==3?"code":n==4?"figure":"para";
                String latex=n==2?"\\mathcal{L}_{\\mathrm{LossOrchid}} = (x-y)^2":null;
                String markdown=n==1?"| Item | Rate |\n| --- | --- |\n| TableMaple | 42 requests per second |":
                        n==4?"![原图](assets/"+imageHash+".png)\n\n"+raw[n]:null;
                var block=new DocumentIngestionService.Block("p"+page+"-b1",type,raw[n],n==2?latex:raw[n],page,
                        new double[]{20,30,400,100},600,800,null,n==4?"recognized":"accepted",true,-1,-1,0,
                        n==4?"assets/"+imageHash+".png":null,latex,n==2?"restored":null,null,markdown,-1,-1);
                var extraction=DocumentMarkdownWriter.render(new DocumentIngestionService.Extraction("ok","",0,null,sha,
                        DocumentIngestionService.PIPELINE_VERSION,List.of(new DocumentIngestionService.Page(page,600,800,"pt","text",List.of(block))),
                        new DocumentIngestionService.QualityReport(1,1,0,0,null,List.of(),false,false)));
                var info=new FileInfo(); info.setId(id); info.setExt("pdf"); info.setStoreName(source.getFileName().toString());
                info.setOriginName("Synthetic source "+id); info.setTextStatus("ok"); info.setTextContent(extraction.text()); rows.put(id,info);
                if(n==4) { Path asset=work.resolve("uploads/.derived/"+id+"/assets/"+imageHash+".png"); Files.createDirectories(asset.getParent()); Files.write(asset,image); }
                new DocumentExtractionStore().save(id,source,extraction);
                originalSources.add(Map.of("id",id,"title",info.getOriginName(),"content",n==4?"":raw[n]));
                markdownSources.add(Map.of("id",id,"title",info.getOriginName(),"content",extraction.text()));
                var located=DocumentExtractionStore.locate(extraction,extraction.text(),labels[n]);
                assertEquals(page,located.getFirst().page());
                assertArrayEquals(block.rect(),located.getFirst().rect());
                assertEquals("Immutable synthetic original "+id,Files.readString(source));
            }
            var sources=mock(KbChunkMapper.class);
            var retrieval=new KnowledgeRetrievalService(sources,mock(VectorIndexService.class),mock(GraphEvidenceService.class),mock(RerankService.class),
                    mock(SettingsService.class),mock(ModelRouting.class),mock(DeepSeekClient.class),mock(WikiRetrievalService.class));
            for(int n=0;n<labels.length;n++) {
                when(sources.allFiles()).thenReturn(originalSources);
                if(n==4) assertTrue(retrieval.search(labels[n],1,"keyword",false).isEmpty(),"an untranscribed image had no searchable description");
                else assertEquals((long)n+1,retrieval.search(labels[n],1,"keyword",false).getFirst().sourceId());
                when(sources.allFiles()).thenReturn(markdownSources); retrieval.setFileStorage(files);
                var found=retrieval.search(labels[n],1,"keyword",false).getFirst();
                assertEquals((long)n+1,found.sourceId()); assertEquals(n+2,found.sourceLocations().getFirst().page());
            }
            var md=files.markdownOf(5L);
            assertTrue(md.get("markdown").toString().contains("assets/"+imageHash+".png"));
            assertFalse(md.get("indexedMarkdown").toString().contains(imageHash));
            List<String> entries=new ArrayList<>();
            try(var archive=new ZipInputStream(new java.io.ByteArrayInputStream(files.markdownBundle(5L)))) {
                for(java.util.zip.ZipEntry entry;(entry=archive.getNextEntry())!=null;) { entries.add(entry.getName()); assertFalse(entry.getName().contains("..")); }
            }
            assertEquals(List.of("document.md","locations.json","assets/"+imageHash+".png"),entries);
            String revision=((DocumentIngestionService.Extraction)files.extractionOf(5L)).sourceSha256();
            assertArrayEquals(image,files.markdownAsset(5L,imageHash,revision));
            assertThrows(IllegalArgumentException.class,()->files.markdownAsset(5L,imageHash,"f".repeat(64)));
        } finally { System.setProperty("user.dir",prior); }
    }
}
