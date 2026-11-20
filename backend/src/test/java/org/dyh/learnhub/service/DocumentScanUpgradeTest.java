package org.dyh.learnhub.service;

import org.dyh.learnhub.entity.FileInfo;
import org.dyh.learnhub.mapper.FileInfoMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.context.ApplicationEventPublisher;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;

import static org.dyh.learnhub.service.DocumentIngestionService.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class DocumentScanUpgradeTest {
    @TempDir Path work;
    String priorDirectory, revision;
    Path source;
    FileInfo file;
    FileInfoMapper mapper;
    DocumentTextService parser;
    FileStorageService storage;
    DocumentExtractionStore store = new DocumentExtractionStore();

    @BeforeEach void setup() throws Exception {
        priorDirectory=System.getProperty("user.dir");
        System.setProperty("user.dir",work.toString());
        source=Files.createDirectories(work.resolve("uploads")).resolve("synthetic.pdf");
        Files.writeString(source,"Synthetic immutable original for staged scan tests");
        revision=HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(source)));
        file=new FileInfo(); file.setId(8L); file.setOriginName("synthetic.pdf"); file.setStoreName("synthetic.pdf");
        file.setExt("pdf"); file.setSize(Files.size(source));
        mapper=mock(FileInfoMapper.class); when(mapper.selectById(8L)).thenReturn(file);
        parser=mock(DocumentTextService.class);
        when(parser.prefersStructured("pdf")).thenReturn(true);
        when(parser.supportsPageExtraction("pdf")).thenReturn(true);
        storage=new FileStorageService(mapper,null,parser,null,null,null,mock(ApplicationEventPublisher.class),null,null,null);
    }

    @AfterEach void cleanup() { System.setProperty("user.dir",priorDirectory); }

    Extraction result(int scanned,int total,String version,String prefix,boolean partial) {
        var pages=new ArrayList<Page>();
        for (int page=1;page<=scanned;page++) {
            String text=prefix+" evidence on source page "+page;
            var block=new Block("p"+page+"-b1","para",text,text,page,new double[]{10,10,110,30},120,160,
                    .98,"accepted",true,-1,-1);
            pages.add(new Page(page,120,160,"pt",version.equals(STRUCTURED_PIPELINE_VERSION)?"ocr":"text",List.of(block)));
        }
        return DocumentMarkdownWriter.render(new Extraction("ok","",0,null,revision,version,List.copyOf(pages),
                new QualityReport(total,scanned,version.equals(STRUCTURED_PIPELINE_VERSION)?scanned:0,0,.98,
                        scanned<total?List.of("已达到单次 20 页 OCR 上限；再次继续抽取可处理剩余页"):List.of(),scanned<total,partial)));
    }

    void publish(Extraction result) throws Exception {
        file.setTextStatus("ok"); file.setTextContent(result.text()); file.setTextChars(result.chars());
        store.save(8L,source,result);
    }

    @Test void stagesMoreThanOneBatchAndPublishesCompletedScanOnlyAfterSeparateReview() throws Exception {
        var old=result(25,25,LEGACY_STRUCTURED_PIPELINE_VERSION,"Old",false); publish(old);
        var batch=result(20,25,STRUCTURED_PIPELINE_VERSION,"New",true);
        var complete=result(25,25,STRUCTURED_PIPELINE_VERSION,"New",false);
        when(parser.extractForJob(eq(source),eq("pdf"),anyLong(),eq(true),isNull(),any())).thenReturn(batch);
        var first=storage.extractInBackground(8L,false,false,ignored -> { });
        assertEquals(true,first.get("pendingUpgrade"));
        assertEquals(old.text(),file.getTextContent());
        assertEquals(20,((Map<?,?>)first.get("scanProgress")).get("completedPages"));
        assertEquals(25,((Map<?,?>)first.get("extraction")).get("completedPages"));
        assertTrue(store.readPendingScanSummary(8L,source).isPresent());
        verify(mapper,never()).updateById(any(FileInfo.class));
        assertEquals(STRUCTURED_PIPELINE_VERSION,((Map<?,?>)((Map<?,?>)storage.extractionOf(8L)).get("scanCandidate")).get("pipelineVersion"));

        when(parser.extractForJob(eq(source),eq("pdf"),anyLong(),eq(true),notNull(),any())).thenAnswer(call -> {
            Extraction previous=call.getArgument(4);
            assertEquals(20,previous.completedPages());
            assertEquals(STRUCTURED_PIPELINE_VERSION,previous.pipelineVersion());
            return complete;
        });
        var second=storage.extractInBackground(8L,false,true,ignored -> { });
        assertEquals(true,second.get("pendingUpgrade"));
        assertEquals(old.text(),file.getTextContent());
        var shown=storage.markdownOf(8L);
        storage.publishScan(8L,revision,(String)shown.get("artifactRevision"));
        second=storage.detailMap(8L);
        assertNull(second.get("pendingUpgrade"));
        assertEquals(complete.text(),file.getTextContent());
        assertTrue(store.readPendingScan(8L,source).isEmpty());
        assertTrue(store.readPendingScanSummary(8L,source).isEmpty());
        assertEquals(25,((Extraction)storage.extractionOf(8L)).completedPages());
    }

    @Test void retryWithoutProgressAndFailedFreshScanBothPreservePublishedEvidenceAndCandidate() throws Exception {
        var old=result(25,25,PIPELINE_VERSION,"Old",false); publish(old);
        var batch=result(20,25,STRUCTURED_PIPELINE_VERSION,"New",true);
        store.savePendingScan(8L,source,batch);
        when(parser.extractForJob(eq(source),eq("pdf"),anyLong(),eq(true),notNull(),any())).thenReturn(batch);
        var stalled=storage.extractInBackground(8L,false,true,ignored -> { });
        assertEquals(true,stalled.get("noProgress"));
        assertEquals(true,stalled.get("pendingUpgrade"));
        assertEquals(old.text(),file.getTextContent());
        when(parser.extractForJob(eq(source),eq("pdf"),anyLong(),eq(true),isNull(),any()))
                .thenThrow(new IllegalStateException("Synthetic service unavailable"));
        assertThrows(IllegalStateException.class,() -> storage.extractInBackground(8L,false,false,ignored -> { }));
        assertEquals(old.text(),file.getTextContent());
        assertEquals(20,store.readPendingScan(8L,source).orElseThrow().completedPages());
        verify(mapper,never()).updateById(any(FileInfo.class));
    }

    @Test void resumingStructuredPublishedSourceKeepsReviewGateWhenConfiguredPreferenceChanges() throws Exception {
        var old=result(2,2,STRUCTURED_PIPELINE_VERSION,"Old",false); publish(old);
        var partial=result(1,2,STRUCTURED_PIPELINE_VERSION,"New",true);
        var complete=result(2,2,STRUCTURED_PIPELINE_VERSION,"New",false);
        store.savePendingScan(8L,source,partial);
        when(parser.prefersStructured("pdf")).thenReturn(false);
        when(parser.extractForJob(eq(source),eq("pdf"),anyLong(),eq(true),notNull(),any())).thenReturn(complete);
        assertEquals(true,storage.extractInBackground(8L,false,true,ignored -> {}).get("pendingUpgrade"));
        assertEquals(old.text(),file.getTextContent());
        assertEquals(2,store.readPendingScan(8L,source).orElseThrow().completedPages());
        verify(mapper,never()).updateById(any(FileInfo.class));
    }

    @Test void completeHighConfidenceScanShowsCorrectedProseWithoutPublishingItsUnreviewedFormula() throws Exception {
        var old=result(1,1,PIPELINE_VERSION,"Misspelled native",false); publish(old);
        var base=result(1,1,STRUCTURED_PIPELINE_VERSION,"Corrected scan",true);
        var blocks=new ArrayList<>(base.pages().getFirst().blocks());
        blocks.add(new Block("p1-formula","formula","","",1,new double[]{20,50,90,80},120,160,null,"non_text",false,
                -1,-1,0,null,"x=8","needs_review","待核对"));
        var next=DocumentMarkdownWriter.render(new Extraction("ok",base.text(),base.chars(),null,revision,STRUCTURED_PIPELINE_VERSION,
                List.of(new Page(1,120,160,"pt","ocr",blocks)),base.report()));
        when(parser.extractForJob(eq(source),eq("pdf"),anyLong(),eq(true),isNull(),any())).thenReturn(next);
        var detail=storage.extractInBackground(8L,false,false,ignored -> { });
        assertEquals(true,detail.get("pendingUpgrade"));
        assertEquals(1L,detail.get("pendingFormulas"));
        assertEquals(old.text(),file.getTextContent());
        assertTrue(filesMarkdown().contains("Corrected scan"));
        assertFalse(file.getTextContent().contains("x=8"));
        assertEquals("needs_review",store.readPendingScan(8L,source).orElseThrow().pages().getFirst().blocks().getLast().latexStatus());
    }

    @Test void unmatchedOldEvidenceKeepsFullCandidateVisibleAndCandidateFormulaReviewCannotModifyPublishedVersion() throws Exception {
        var old=result(1,1,PIPELINE_VERSION,"Old",false); publish(old);
        var next=result(1,1,STRUCTURED_PIPELINE_VERSION,"Candidate",true);
        var prose=next.pages().getFirst().blocks().getFirst();
        var shifted=new Block(prose.id(),prose.type(),prose.rawText(),prose.cleanText(),1,new double[]{10,100,110,130},120,160,
                .98,"accepted",true,-1,-1);
        var formula=new Block("p1-formula","formula","","",1,new double[]{20,50,90,80},120,160,null,"non_text",false,
                -1,-1,0,null,"x=8","needs_review","待核对");
        var candidate=DocumentMarkdownWriter.render(new Extraction("ok",next.text(),next.chars(),null,revision,STRUCTURED_PIPELINE_VERSION,
                List.of(new Page(1,120,160,"pt","ocr",List.of(shifted,formula))),next.report()));
        when(parser.extractForJob(eq(source),eq("pdf"),anyLong(),eq(true),isNull(),any())).thenReturn(candidate);
        assertEquals(true,storage.extractInBackground(8L,false,false,ignored -> { }).get("pendingUpgrade"));
        var response=(Map<?,?>)storage.extractionOf(8L);
        assertTrue(((Map<?,?>)response.get("scanCandidate")).get("text").toString().contains("Candidate"));
        assertEquals(old.text(),response.get("text"));
        assertThrows(IllegalStateException.class,() -> storage.confirmStructuredFormula(8L,"p1-formula",revision,"x=8","x=3",false));
        storage.confirmStructuredFormula(8L,"p1-formula",revision,"x=8","x=3",true);
        assertEquals(old.text(),file.getTextContent());
        assertTrue(store.readPendingScan(8L,source).orElseThrow().text().contains("x=3"));
    }

    @Test void completeScanCannotDropVerifiedFormulaThroughReusedIdsOrUnreviewedCoordinates() throws Exception {
        var old=result(1,1,PIPELINE_VERSION,"Old",false);
        var oldFormula=new Block("p1-b2","formula","","x=8",1,new double[]{20,50,90,80},120,160,null,"accepted",true,
                -1,-1,0,null,"x=8","recognized","已人工核对");
        old=DocumentMarkdownWriter.render(new Extraction("ok",old.text(),old.chars(),null,revision,old.pipelineVersion(),
                List.of(new Page(1,120,160,"pt","text",List.of(old.pages().getFirst().blocks().getFirst(),oldFormula))),old.report()));
        publish(old);
        var next=result(1,1,STRUCTURED_PIPELINE_VERSION,"Corrected",false);
        var collision=new Block("p1-b2","para","Prose mentioning x=8","Prose mentioning x=8",1,new double[]{5,110,100,140},120,160,.98,
                "accepted",true,-1,-1);
        var drifted=new Block("p1-b3","formula","","",1,new double[]{22,50,92,80},120,160,null,"non_text",false,
                -1,-1,0,null,"x=9","needs_review","待核对");
        var candidate=DocumentMarkdownWriter.render(new Extraction("ok",next.text(),next.chars(),null,revision,next.pipelineVersion(),
                List.of(new Page(1,120,160,"pt","ocr",List.of(next.pages().getFirst().blocks().getFirst(),collision,drifted))),next.report()));
        assertFalse(candidate.report().partial());
        when(parser.extractForJob(eq(source),eq("pdf"),anyLong(),eq(true),isNull(),any())).thenReturn(candidate);
        assertEquals(true,storage.extractInBackground(8L,false,false,ignored -> { }).get("pendingUpgrade"));
        assertEquals(old.text(),file.getTextContent());
        assertTrue(file.getTextContent().contains("x=8"));
        assertEquals(true,storage.extractInBackground(8L,false,true,ignored -> { }).get("noProgress"),
                "completed candidate promotion must still protect the reviewed formula");
        assertEquals(true,storage.extractInBackground(8L,true,true,ignored -> { },true).get("noProgress"),
                "explicit regeneration must not bypass a reviewed formula");
        assertEquals(old.text(),file.getTextContent());
        verify(parser,times(1)).extractForJob(eq(source),eq("pdf"),anyLong(),eq(true),isNull(),any());
        storage.confirmStructuredFormula(8L,"p1-b3",revision,"x=9","x=3",true);
        assertEquals(old.text(),file.getTextContent(),"formula confirmation does not approve retrieval publication");
        var shown=storage.markdownOf(8L);
        storage.publishScan(8L,revision,(String)shown.get("artifactRevision"));
        assertTrue(file.getTextContent().contains("x=3"),"explicit review can reconcile the old source formula");
        assertTrue(((Extraction)storage.extractionOf(8L)).pages().getFirst().blocks().stream()
                .filter(block -> "formula".equals(block.type())).allMatch(block -> "x=3".equals(block.latex())));
        assertTrue(store.readPendingScan(8L,source).isEmpty());
    }

    @Test void nonforceMigrationIncludesPendingScanEvenWhenPublishedVersionIsAlreadyComplete() throws Exception {
        publish(result(25,25,STRUCTURED_PIPELINE_VERSION,"Published",false));
        store.savePendingScan(8L,source,result(20,25,STRUCTURED_PIPELINE_VERSION,"Candidate",true));
        when(mapper.selectList(any(com.baomidou.mybatisplus.core.conditions.Wrapper.class))).thenReturn(List.of(file));
        assertEquals(List.of(8L),storage.markdownCandidates(false,false));
    }

    Extraction withAutomaticDescription(Extraction input) {
        var pages=new ArrayList<>(input.pages());
        var first=pages.getFirst(); var blocks=new ArrayList<>(first.blocks());
        String description="Synthetic model description of a possible scene";
        blocks.add(new Block("p1-image","figure","",description,1,new double[]{10,50,110,150},120,160,null,"recognized",true,
                -1,-1,0,null,null,null,"视觉模型仅描述可见事实，请核对原图",null,-1,-1,
                new Presentation(null,"annotation",null,null,null,"page-image:p1-image")));
        pages.set(0,new Page(1,120,160,"pt",first.method(),blocks));
        return DocumentMarkdownWriter.render(new Extraction(input.status(),input.text(),input.chars(),null,revision,input.pipelineVersion(),pages,input.report()));
    }

    @Test void completedHighQualityCandidateRequiresSeparatePublicationAndArchivesOldAutomaticDescription() throws Exception {
        var old=withAutomaticDescription(result(1,1,PIPELINE_VERSION,"Old",false)); publish(old);
        var scan=result(1,1,STRUCTURED_PIPELINE_VERSION,"New visible text",true);
        store.savePendingScan(8L,source,scan);
        var detail=storage.extractInBackground(8L,false,true,ignored -> { });
        assertEquals(true,detail.get("pendingUpgrade")); assertEquals(true,detail.get("noProgress"));
        assertEquals(old.text(),file.getTextContent());
        storage.publishScan(8L,revision,(String)storage.markdownOf(8L).get("artifactRevision"));
        assertEquals(scan.text(),file.getTextContent());
        assertTrue(store.read(8L,source,old.text()).isPresent(),"old model evidence stays in its content-addressed report");
        assertTrue(store.readPendingScan(8L,source).isEmpty());
        verify(parser,never()).extractForJob(any(),anyString(),anyLong(),anyBoolean(),any(),any());
    }

    @Test void incompleteScanCannotRetireOldAutomaticDescription() throws Exception {
        var old=withAutomaticDescription(result(25,25,PIPELINE_VERSION,"Old",false)); publish(old);
        var scan=result(20,25,STRUCTURED_PIPELINE_VERSION,"New visible text",true);
        when(parser.extractForJob(eq(source),eq("pdf"),anyLong(),eq(true),isNull(),any())).thenReturn(scan);
        assertEquals(true,storage.extractInBackground(8L,false,false,ignored -> { }).get("pendingUpgrade"));
        assertEquals(old.text(),file.getTextContent());
        assertEquals(20,store.readPendingScan(8L,source).orElseThrow().completedPages());
    }

    Extraction onePage(String version,List<Block> blocks,boolean partial) {
        return DocumentMarkdownWriter.render(new Extraction("ok","",0,null,revision,version,
                List.of(new Page(1,120,240,"pt",version.equals(STRUCTURED_PIPELINE_VERSION)?"ocr":"text",blocks)),
                new QualityReport(1,1,version.equals(STRUCTURED_PIPELINE_VERSION)?1:0,0,.99,List.of(),false,partial)));
    }

    Block prose(String id,String text,double[] rectangle) {
        return new Block(id,"para",text,text,1,rectangle,120,240,.99,"accepted",true,-1,-1);
    }

    @Test void completeScanAcceptsTighterOcrGlyphHeightWithinBoundedPdfFontAllowance() throws Exception {
        var old=onePage(PIPELINE_VERSION,List.of(prose("p1-b1","Synthetic native line",new double[]{10,10,110,24.8})),false);
        publish(old);
        var scan=onePage(STRUCTURED_PIPELINE_VERSION,List.of(prose("p1-b2","Synthetic corrected line",new double[]{10,12,110,22.56})),true);
        when(parser.extractForJob(eq(source),eq("pdf"),anyLong(),eq(true),isNull(),any())).thenReturn(scan);
        var result=storage.extractInBackground(8L,false,false,ignored -> { });
        assertEquals(true,result.get("pendingUpgrade"));
        assertEquals(old.text(),file.getTextContent());
        storage.publishScan(8L,revision,(String)storage.markdownOf(8L).get("artifactRevision"));
        assertEquals(scan.text(),file.getTextContent());
    }

    @Test void completeScanUsesUnionOfSplitParagraphsButKeepsMissingOrDistantPartsStaged() throws Exception {
        var old=onePage(PIPELINE_VERSION,List.of(prose("p1-b1","Synthetic original paragraph",new double[]{10,10,110,110})),false);
        var upper=prose("p1-b2","Synthetic corrected upper paragraph",new double[]{10,10,110,60});
        var lower=prose("p1-b3","Synthetic corrected lower paragraph",new double[]{10,60,110,110});
        var distant=prose("p1-b4","Synthetic unrelated distant paragraph",new double[]{10,150,110,200});
        for (var parts:List.of(List.of(upper,lower),List.of(upper),List.of(upper,distant))) {
            publish(old);
            var scan=onePage(STRUCTURED_PIPELINE_VERSION,parts,true);
            when(parser.extractForJob(eq(source),eq("pdf"),anyLong(),eq(true),isNull(),any())).thenReturn(scan);
            var result=storage.extractInBackground(8L,false,false,ignored -> { });
            assertEquals(true,result.get("pendingUpgrade"));
            assertEquals(old.text(),file.getTextContent());
            assertEquals(scan.markdown(),filesMarkdown());
        }
    }

    @Test void explicitForceCannotBypassSeparateCandidateReviewOrAcceptUncertainBlocks() throws Exception {
        var old=onePage(PIPELINE_VERSION,List.of(prose("p1-b1","Prior generated automatic prose",new double[]{10,10,110,30})),false);
        publish(old);
        var accepted=prose("p1-b2","Regenerated visible source prose",new double[]{10,100,110,130});
        var uncertain=new Block("p1-b3","para","Synthetic uncertain candidate","Synthetic uncertain candidate",1,
                new double[]{10,150,110,170},120,240,.2,"low_confidence",false,-1,-1);
        var candidate=onePage(STRUCTURED_PIPELINE_VERSION,List.of(accepted,uncertain),true);
        store.savePendingScan(8L,source,candidate);
        assertEquals(true,storage.extractInBackground(8L,false,true,ignored -> { }).get("noProgress"));
        assertEquals(old.text(),file.getTextContent());
        var result=storage.extractInBackground(8L,true,true,ignored -> { },true);
        assertEquals(true,result.get("pendingUpgrade")); assertEquals(true,result.get("noProgress"));
        assertEquals(old.text(),file.getTextContent());
        var shown=storage.markdownOf(8L);
        assertEquals(1L,((Map<?,?>)shown.get("retrievalReview")).get("excludedOtherCount"));
        var staged=store.readPendingScan(8L,source).orElseThrow();
        assertTrue(staged.report().partial());
        assertFalse(staged.pages().getFirst().blocks().getLast().indexed());
        assertFalse(staged.text().contains("uncertain"));
        storage.publishScan(8L,revision,(String)shown.get("artifactRevision"));
        assertEquals(candidate.text(),file.getTextContent());
        assertFalse(file.getTextContent().contains("uncertain"));
        assertTrue(store.read(8L,source,old.text()).isPresent());
        verify(parser,never()).extractForJob(any(),anyString(),anyLong(),anyBoolean(),any(),any());
    }

    @Test void explicitForceStillStagesUnfinishedRejectedCapacityLimitedOrDifferentGeometryResults() throws Exception {
        var old=result(2,2,PIPELINE_VERSION,"Old",false);
        var complete=result(2,2,STRUCTURED_PIPELINE_VERSION,"New",true);
        var report=complete.report();
        var rejected=new Extraction("ok",complete.text(),complete.chars(),null,revision,STRUCTURED_PIPELINE_VERSION,
                List.of(complete.pages().getFirst(),new Page(2,120,160,"pt","ocr",List.of())),
                new QualityReport(2,2,2,1,.98,List.of(),false,true));
        var truncated=new Extraction("ok",complete.text(),complete.chars(),null,revision,STRUCTURED_PIPELINE_VERSION,complete.pages(),
                new QualityReport(2,2,2,0,.98,List.of(),true,true));
        var capacity=new Extraction("ok",complete.text(),complete.chars(),null,revision,STRUCTURED_PIPELINE_VERSION,complete.pages(),
                new QualityReport(2,2,2,0,.98,List.of("累计正文达到 40 万字符上限；请拆分原件"),false,true));
        var changedPage=new Page(1,121,160,"pt","ocr",complete.pages().getFirst().blocks());
        var geometry=new Extraction("ok",complete.text(),complete.chars(),null,revision,STRUCTURED_PIPELINE_VERSION,
                List.of(changedPage,complete.pages().getLast()),report);
        for (var candidate:List.of(result(1,2,STRUCTURED_PIPELINE_VERSION,"New",true),rejected,truncated,capacity,geometry)) {
            publish(old);
            when(parser.extractForJob(eq(source),eq("pdf"),anyLong(),eq(true),isNull(),any())).thenReturn(candidate);
            assertEquals(true,storage.extractInBackground(8L,true,false,ignored -> { },true).get("pendingUpgrade"));
            assertEquals(old.text(),file.getTextContent());
        }
        publish(old);
        var differentSource=new Extraction("ok",complete.text(),complete.chars(),null,"f".repeat(64),STRUCTURED_PIPELINE_VERSION,complete.pages(),report);
        when(parser.extractForJob(eq(source),eq("pdf"),anyLong(),eq(true),isNull(),any())).thenReturn(differentSource);
        assertThrows(IllegalStateException.class,() -> storage.extractInBackground(8L,true,false,ignored -> { },true));
        assertEquals(old.text(),file.getTextContent());
    }

    private String filesMarkdown() { return (String)storage.markdownOf(8L).get("markdown"); }
}
