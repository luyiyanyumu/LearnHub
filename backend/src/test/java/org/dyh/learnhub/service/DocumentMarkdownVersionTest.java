package org.dyh.learnhub.service;

import org.dyh.learnhub.entity.FileInfo;
import org.dyh.learnhub.mapper.FileInfoMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.context.ApplicationEventPublisher;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipInputStream;

import static org.dyh.learnhub.service.DocumentIngestionService.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class DocumentMarkdownVersionTest {
    @TempDir Path work;
    String priorDirectory, sourceRevision;
    Path source;
    FileInfo info;
    FileInfoMapper mapper;
    ApplicationEventPublisher events;
    FileStorageService files;
    DocumentExtractionStore store = new DocumentExtractionStore();

    @BeforeEach void setup() throws Exception {
        priorDirectory=System.getProperty("user.dir"); System.setProperty("user.dir",work.toString());
        source=Files.createDirectories(work.resolve("uploads")).resolve("synthetic.pdf");
        Files.writeString(source,"Synthetic unchanged source for markdown version review");
        sourceRevision=HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(source)));
        info=new FileInfo(); info.setId(93L); info.setExt("pdf"); info.setStoreName("synthetic.pdf");
        info.setOriginName("Synthetic document"); info.setSize(Files.size(source)); info.setTextStatus("ok");
        mapper=mock(FileInfoMapper.class); when(mapper.selectById(93L)).thenReturn(info);
        events=mock(ApplicationEventPublisher.class);
        files=new FileStorageService(mapper,null,mock(DocumentTextService.class),null,null,null,events,null,null,null);
    }

    @AfterEach void restore() { System.setProperty("user.dir",priorDirectory); }

    private Extraction extraction(String label,String pipeline,String image,boolean formula) {
        var blocks=new ArrayList<Block>();
        blocks.add(new Block("p1-text","heading",label,label,1,new double[]{10,10,100,30},120,160,.99,"accepted",true,-1,-1));
        if (image!=null) blocks.add(new Block("p1-image","figure","","",1,new double[]{10,35,100,70},120,160,null,
                "non_text",false,-1,-1,0,image,null,null,null));
        if (formula) blocks.add(new Block("p1-formula","formula","","",1,new double[]{10,80,100,100},120,160,.5,
                "non_text",false,-1,-1,0,null,"x=8","needs_review","待核对"));
        return DocumentMarkdownWriter.render(new Extraction("ok","",0,null,sourceRevision,pipeline,
                List.of(new Page(1,120,160,"pt",STRUCTURED_PIPELINE_VERSION.equals(pipeline)?"ocr":"text",blocks)),
                new QualityReport(1,1,0,0,.99,List.of(),false,false)));
    }

    private void publish(Extraction value) throws Exception {
        store.save(93L,source,value); info.setTextContent(value.text()); info.setTextChars(value.chars());
    }

    private String image(Color color) throws Exception {
        var bitmap=new BufferedImage(20,20,BufferedImage.TYPE_INT_RGB);
        var graphics=bitmap.createGraphics(); graphics.setColor(color); graphics.fillRect(0,0,20,20); graphics.dispose();
        var out=new ByteArrayOutputStream(); ImageIO.write(bitmap,"png",out);
        return new DocumentMediaAssets(93L,source).save(out.toByteArray()).src();
    }

    private Map<String,byte[]> entries(byte[] bytes) throws Exception {
        Map<String,byte[]> result=new LinkedHashMap<>();
        try(var zip=new ZipInputStream(new ByteArrayInputStream(bytes),StandardCharsets.UTF_8)) {
            for(var entry=zip.getNextEntry();entry!=null;entry=zip.getNextEntry()) result.put(entry.getName(),zip.readAllBytes());
        }
        return result;
    }

    @Test void latestPreviewAndBothExportsUseTheSameCandidateWithoutChangingPublishedEvidence() throws Exception {
        String oldImage=image(Color.BLUE), newImage=image(Color.GREEN);
        var old=extraction("Cedar old heading",PIPELINE_VERSION,oldImage,false);
        var candidate=extraction("Maple latest heading",STRUCTURED_PIPELINE_VERSION,newImage,true);
        publish(old); store.savePendingScan(93L,source,candidate);
        var shown=files.markdownOf(93L);
        assertEquals("candidate",shown.get("selectedVersion"));
        assertEquals(candidate.markdown(),shown.get("markdown"));
        var json=new com.fasterxml.jackson.databind.ObjectMapper();
        assertEquals(json.writeValueAsString(candidate.pages()),json.writeValueAsString(shown.get("pages")));
        assertEquals(old.text(),shown.get("indexedMarkdown"));
        assertEquals(1L,((Map<?,?>)shown.get("reviewSummary")).get("formulaCount"));
        assertEquals(false,((Map<?,?>)shown.get("retrievalReview")).get("canPublish"));
        var manifests=(List<DocumentExtractionStore.Asset>)shown.get("assets");
        assertEquals(List.of(newImage),manifests.stream().map(DocumentExtractionStore.Asset::name).toList());
        String artifact=(String)shown.get("artifactRevision");
        assertEquals(candidate.markdown(),new String(files.markdownBytes(93L,"candidate",sourceRevision,artifact),StandardCharsets.UTF_8));
        var zip=entries(files.markdownBundle(93L,"candidate",sourceRevision,artifact));
        assertEquals(candidate.markdown(),new String(zip.get("document.md"),StandardCharsets.UTF_8));
        assertTrue(zip.containsKey(newImage)); assertFalse(zip.containsKey(oldImage));
        var bundled=new com.fasterxml.jackson.databind.ObjectMapper().readValue(zip.get("locations.json"),Extraction.class);
        assertEquals(candidate.markdown(),bundled.markdown());
        assertEquals("Maple latest heading",bundled.pages().getFirst().blocks().getFirst().cleanText());
        assertEquals(old.markdown(),files.markdownOf(93L,"published",null,null).get("markdown"));
        assertEquals(old.text(),info.getTextContent());
        verify(mapper,never()).updateById(any(FileInfo.class)); verifyNoInteractions(events);
    }

    @Test void onlyLatestFallsBackAndStaleSourceOrArtifactIsRejected() throws Exception {
        var old=extraction("Cedar old",PIPELINE_VERSION,null,false); publish(old);
        assertEquals("published",files.markdownOf(93L).get("selectedVersion"));
        assertThrows(IllegalStateException.class,() -> files.markdownOf(93L,"candidate",null,null));
        assertThrows(IllegalArgumentException.class,() -> files.markdownOf(93L,"unknown",null,null));
        var candidate=extraction("Maple latest",STRUCTURED_PIPELINE_VERSION,null,false);
        store.savePendingScan(93L,source,candidate);
        var shown=files.markdownOf(93L); String artifact=(String)shown.get("artifactRevision");
        assertThrows(IllegalStateException.class,() -> files.markdownBytes(93L,"latest","f".repeat(64),artifact));
        store.savePendingScan(93L,source,extraction("Birch changed scan",STRUCTURED_PIPELINE_VERSION,null,false));
        assertThrows(IllegalStateException.class,() -> files.markdownBundle(93L,"candidate",sourceRevision,artifact));
        assertThrows(IllegalStateException.class,() -> files.publishScan(93L,sourceRevision,artifact));
        Files.writeString(source,"A different synthetic source");
        assertThrows(IllegalStateException.class,() -> files.markdownOf(93L,"candidate",null,null));
        assertEquals("not_processed",files.markdownOf(93L).get("status"));
        assertEquals(old.text(),info.getTextContent()); verifyNoInteractions(events);
    }

    @Test void lastFormulaConfirmationStaysStagedAndSeparatePublicationRequiresItsExactReviewedRevision() throws Exception {
        var old=extraction("Cedar old",PIPELINE_VERSION,null,false); publish(old);
        store.savePendingScan(93L,source,extraction("Maple latest",STRUCTURED_PIPELINE_VERSION,null,true));
        String before=(String)files.markdownOf(93L).get("artifactRevision");
        assertThrows(IllegalStateException.class,() -> files.publishScan(93L,sourceRevision,before));
        assertThrows(IllegalStateException.class,() -> files.confirmStructuredFormula(93L,"p1-formula",sourceRevision,"x=8","x=3",true,"f".repeat(64)));
        files.confirmStructuredFormula(93L,"p1-formula",sourceRevision,"x=8","x=3",true,before);
        assertEquals(old.text(),info.getTextContent()); verifyNoInteractions(events);
        var after=files.markdownOf(93L);
        assertNotEquals(before,after.get("artifactRevision"));
        assertEquals(true,((Map<?,?>)after.get("retrievalReview")).get("canPublish"));
        assertThrows(IllegalStateException.class,() -> files.publishScan(93L,sourceRevision,before));
        files.publishScan(93L,sourceRevision,(String)after.get("artifactRevision"));
        assertTrue(info.getTextContent().contains("Maple latest")); assertTrue(info.getTextContent().contains("x=3"));
        assertEquals("published",files.markdownOf(93L).get("selectedVersion"));
        assertTrue(store.readPendingScan(93L,source).isEmpty());
        verify(events).publishEvent(any(org.dyh.learnhub.common.KnowledgeChangedEvent.class));
        assertThrows(IllegalStateException.class,() -> files.publishScan(93L,sourceRevision,(String)after.get("artifactRevision")));
    }

    @Test void candidateFromAnOldScanProfileNeverOverridesPublishedMarkdown() throws Exception {
        var old=extraction("Cedar published",PIPELINE_VERSION,null,false); publish(old);
        var current=extraction("Maple scan",STRUCTURED_PIPELINE_VERSION,null,false);
        store.savePendingScan(93L,source,current);
        var incompatible=extraction("Birch obsolete profile",LEGACY_STRUCTURED_PIPELINE_VERSION,null,false);
        new com.fasterxml.jackson.databind.ObjectMapper().writeValue(
                work.resolve("uploads/.derived/93/pending-scan.json").toFile(),incompatible);
        assertEquals("published",files.markdownOf(93L).get("selectedVersion"));
        assertEquals(old.markdown(),files.markdownOf(93L).get("markdown"));
        assertEquals(false,files.markdownOf(93L).get("candidateAvailable"));
        assertThrows(IllegalStateException.class,() -> files.markdownBytes(93L,"candidate",null,null));
        verifyNoInteractions(events);
    }
}
