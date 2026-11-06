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

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class DocumentMarkdownPersistenceTest {
    @TempDir Path directory;

    @Test void failedConversionKeepsThePriorSearchBodyAndItsExactLocations() throws Exception {
        exerciseFailure(false,false);
    }

    @Test void failedDatabaseCommitCannotMakeStagedLocationsReplaceTheCommittedVersion() throws Exception {
        exerciseFailure(true,false);
    }

    @Test void comparisonCannotTreatLiteralPunctuationAcrossSeparateBlocksAsNewMarkdownSyntax() throws Exception {
        String prior=System.getProperty("user.dir"); System.setProperty("user.dir",directory.toString());
        try {
            Path source=directory.resolve("uploads/synthetic.pdf"); Files.createDirectories(source.getParent()); Files.writeString(source,"synthetic-original");
            String sha=HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(source)));
            String first="Part _open",second="closing_ end",old=first+"\n\n"+second;
            var blocks=List.of(new DocumentIngestionService.Block("first","para",first,first,1,null,0,0,null,"accepted",true,0,first.length()),
                    new DocumentIngestionService.Block("second","para",second,second,1,null,0,0,null,"accepted",true,first.length()+2,old.length()));
            var previous=new DocumentIngestionService.Extraction("ok",old,old.length(),null,sha,"document-ingestion-v2-md",
                    List.of(new DocumentIngestionService.Page(1,600,800,"pt","text",blocks)),
                    new DocumentIngestionService.QualityReport(1,1,0,0,null,List.of(),false,false));
            new DocumentExtractionStore().save(71L,source,previous);
            var info=new FileInfo(); info.setId(71L); info.setExt("pdf"); info.setStoreName("synthetic.pdf"); info.setSize(Files.size(source));
            info.setTextStatus("ok"); info.setTextContent(old);
            var mapper=mock(FileInfoMapper.class); when(mapper.selectById(71L)).thenReturn(info);
            var next=new DocumentIngestionService.Extraction("ok",old,old.length(),null,sha,DocumentIngestionService.PIPELINE_VERSION,
                    previous.pages(),new DocumentIngestionService.QualityReport(1,1,0,0,null,List.of("synthetic pending media"),false,true));
            var text=mock(DocumentTextService.class); when(text.extractDetailed(eq(source),eq("pdf"),anyLong())).thenReturn(next);
            var storage=new FileStorageService(mapper,null,text,new PdfLayoutExtractor(),new WordLayoutExtractor(),new TextMathLayout(),
                    mock(ApplicationEventPublisher.class),null,null,mock(FolderService.class));
            storage.reextract(71L,false);
            assertTrue(storage.isCurrentMarkdown(71L));
            assertEquals(first+"\n"+second,DocumentExtractionStore.visibleText(info.getTextContent()));
        } finally {System.setProperty("user.dir",prior);}
    }

    @Test void partialSuccessCannotReplacePreviouslyCompleteEvidenceWithAMissingPage() throws Exception {
        exerciseFailure(false,true);
    }

    @Test void currentMarkdownWithPendingMediaIsSelectedForContinuationWithoutReparsingCompleteDocuments() throws Exception {
        String prior=System.getProperty("user.dir"); System.setProperty("user.dir",directory.toString());
        try {
            Path source=directory.resolve("uploads/synthetic.docx"); Files.createDirectories(source.getParent());
            Files.writeString(source,"synthetic-original");
            String sha=HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(source)));
            var base=extraction(sha,"Cedar reliable evidence",1);
            var pending=new DocumentIngestionService.Block("image","figure","","",1,null,0,0,null,"pending",false,-1,-1,
                    0,"word:word/document.xml#rId5",null,null,null);
            var extraction=DocumentMarkdownWriter.render(new DocumentIngestionService.Extraction("ok",base.text(),base.chars(),null,sha,
                    DocumentIngestionService.PIPELINE_VERSION,List.of(new DocumentIngestionService.Page(1,0,0,"none","text",
                    List.of(base.pages().getFirst().blocks().getFirst(),pending))),base.report()));
            new DocumentExtractionStore().save(71L,source,extraction);
            var info=new FileInfo(); info.setId(71L); info.setExt("docx"); info.setStoreName("synthetic.docx");
            info.setTextStatus("ok"); info.setTextContent(extraction.text());
            var mapper=mock(FileInfoMapper.class); when(mapper.selectList(any())).thenReturn(List.of(info));
            var storage=new FileStorageService(mapper,null,mock(DocumentTextService.class),new PdfLayoutExtractor(),new WordLayoutExtractor(),
                    new TextMathLayout(),mock(ApplicationEventPublisher.class),null,null,mock(FolderService.class));
            assertEquals(List.of(71L),storage.markdownCandidates(false,true));
            assertTrue(storage.markdownCandidates(false,false).isEmpty());
        } finally { System.setProperty("user.dir",prior); }
    }

    @Test void aPartialMigrationThatRetainsAllPriorEvidenceMayImproveBothLegacyAndVerifiedDocuments() throws Exception {
        String prior=System.getProperty("user.dir");
        try {
            for(boolean verified:List.of(false,true)) {
                Path working=directory.resolve(verified?"verified":"legacy"); System.setProperty("user.dir",working.toString());
                Path source=working.resolve("uploads/synthetic.pdf"); Files.createDirectories(source.getParent()); Files.writeString(source,"synthetic-original");
                String sha=HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(source)));
                String old="Cedar prior reliable evidence.";
                if(verified) new DocumentExtractionStore().save(71L,source,extraction(sha,old,2));
                var info=new FileInfo(); info.setId(71L); info.setExt("pdf"); info.setStoreName("synthetic.pdf"); info.setSize(Files.size(source));
                info.setTextStatus("ok"); info.setTextContent(old);
                var mapper=mock(FileInfoMapper.class); when(mapper.selectById(71L)).thenReturn(info);
                var parsed=extraction(sha,old+" Maple additional reliable evidence.",2);
                var next=new DocumentIngestionService.Extraction("ok",parsed.text(),parsed.chars(),null,sha,DocumentIngestionService.PIPELINE_VERSION,
                        parsed.pages(),new DocumentIngestionService.QualityReport(3,1,0,0,null,List.of("synthetic page budget"),true,true));
                var text=mock(DocumentTextService.class); when(text.extractDetailed(eq(source),eq("pdf"),anyLong())).thenReturn(next);
                var storage=new FileStorageService(mapper,null,text,new PdfLayoutExtractor(),new WordLayoutExtractor(),new TextMathLayout(),
                        mock(ApplicationEventPublisher.class),null,null,mock(FolderService.class));
                storage.reextract(71L,false);
                assertEquals(parsed.text(),storage.textOf(71L).get("text"));
                assertTrue(storage.isCurrentMarkdown(71L));
                assertEquals(2,storage.sourceLocations(71L,old).getFirst().page());
            }
        } finally { System.setProperty("user.dir",prior); }
    }

    @Test void theSameLegacyPdfPageLimitAllowsVerifiedReadingOrderChangesWithoutInventingCompleteCoverage() throws Exception {
        String prior=System.getProperty("user.dir"); System.setProperty("user.dir",directory.toString());
        try {
            Path source=directory.resolve("uploads/synthetic.pdf"); Files.createDirectories(source.getParent());
            String first="Cedar first synthetic evidence.",second="Maple second synthetic evidence.";
            try(var pdf=new org.apache.pdfbox.pdmodel.PDDocument()) {
                for(int i=0;i<301;i++) pdf.addPage(new org.apache.pdfbox.pdmodel.PDPage());
                try(var content=new org.apache.pdfbox.pdmodel.PDPageContentStream(pdf,pdf.getPage(0))) {
                    content.beginText(); content.setFont(new org.apache.pdfbox.pdmodel.font.PDType1Font(org.apache.pdfbox.pdmodel.font.Standard14Fonts.FontName.HELVETICA),12);
                    content.newLineAtOffset(40,700); content.showText(first); content.newLineAtOffset(0,-30); content.showText(second); content.endText();
                }
                pdf.save(source.toFile());
            }
            String old=new DocumentTextService().extract(source,"pdf",Files.size(source)).text();
            String sha=HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(source)));
            var pages=new java.util.ArrayList<DocumentIngestionService.Page>();
            for(int i=1;i<=300;i++) {
                var blocks=i==1?List.of(
                        new DocumentIngestionService.Block("second","para",second,second,1,new double[]{0,0,200,20},600,800,null,"accepted",true,-1,-1),
                        new DocumentIngestionService.Block("first","para",first,first,1,new double[]{0,20,200,40},600,800,null,"accepted",true,-1,-1))
                        :List.<DocumentIngestionService.Block>of();
                pages.add(new DocumentIngestionService.Page(i,600,800,"pt","text",blocks));
            }
            var next=new DocumentIngestionService.Extraction("ok","",0,null,sha,DocumentIngestionService.PIPELINE_VERSION,pages,
                    new DocumentIngestionService.QualityReport(301,300,0,299,null,List.of("PDF 超过 300 页，仅处理前 300 页"),true,true));
            var info=new FileInfo(); info.setId(71L); info.setExt("pdf"); info.setStoreName("synthetic.pdf"); info.setSize(Files.size(source));
            info.setTextStatus("ok"); info.setTextContent(old);
            var mapper=mock(FileInfoMapper.class); when(mapper.selectById(71L)).thenReturn(info);
            var text=mock(DocumentTextService.class); when(text.extractDetailed(eq(source),eq("pdf"),anyLong())).thenReturn(next);
            var storage=new FileStorageService(mapper,null,text,new PdfLayoutExtractor(),new WordLayoutExtractor(),new TextMathLayout(),
                    mock(ApplicationEventPublisher.class),null,null,mock(FolderService.class));
            storage.reextract(71L,false);
            assertEquals(second+"\n\n"+first,info.getTextContent());
            var report=(DocumentIngestionService.Extraction)storage.extractionOf(71L);
            assertEquals("markdown",report.format()); assertTrue(report.report().partial()); assertTrue(report.report().truncated());
            assertEquals(301,report.report().totalPages()); assertEquals(300,report.report().processedPages());
        } finally {System.setProperty("user.dir",prior);}
    }

    private void exerciseFailure(boolean databaseFailure,boolean partial) throws Exception {
        String prior=System.getProperty("user.dir");
        System.setProperty("user.dir",directory.toString());
        try {
            Path source=directory.resolve("uploads/synthetic.pdf");
            Files.createDirectories(source.getParent()); Files.writeString(source,"synthetic-original");
            String sha=HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(source)));
            String old="Cedar committed evidence remains searchable.";
            var oldExtraction=extraction(sha,old,2);
            new DocumentExtractionStore().save(71L,source,oldExtraction);
            var mapper=mock(FileInfoMapper.class);
            // Each read sees the last committed row, not a Java object modified before updateById.
            when(mapper.selectById(71L)).thenAnswer(call -> {
                var info=new FileInfo(); info.setId(71L); info.setExt("pdf"); info.setStoreName("synthetic.pdf");
                info.setOriginName("Synthetic Cedar"); info.setSize(Files.size(source));
                info.setTextStatus("ok"); info.setTextContent(old); info.setTextChars(old.length()); return info;
            });
            if(databaseFailure) when(mapper.updateById(any(FileInfo.class))).thenThrow(new IllegalStateException("synthetic commit failure"));
            var text=mock(DocumentTextService.class);
            var parsed=extraction(sha,"# Maple staged conversion",9);
            var next=partial?new DocumentIngestionService.Extraction("ok",parsed.text(),parsed.chars(),null,sha,
                    DocumentIngestionService.PIPELINE_VERSION,parsed.pages(),
                    new DocumentIngestionService.QualityReport(2,1,0,0,null,List.of("synthetic OCR interruption"),false,true))
                    : databaseFailure?parsed:
                    new DocumentIngestionService.Extraction("failed","",0,"synthetic parser failure",sha,
                            DocumentIngestionService.PIPELINE_VERSION,List.of(),oldExtraction.report());
            when(text.extractDetailed(eq(source),eq("pdf"),anyLong())).thenReturn(next);
            var events=mock(ApplicationEventPublisher.class);
            var storage=new FileStorageService(mapper,null,text,new PdfLayoutExtractor(),new WordLayoutExtractor(),new TextMathLayout(),
                    events,null,null,mock(FolderService.class));
            assertThrows(IllegalStateException.class,() -> storage.reextract(71L,false));
            assertEquals(old,storage.textOf(71L).get("text"));
            var retained=(DocumentIngestionService.Extraction)storage.extractionOf(71L);
            assertEquals(old,retained.text());
            assertEquals(2,storage.sourceLocations(71L,old).getFirst().page());
            assertEquals("synthetic-original",Files.readString(source));
            verifyNoInteractions(events);
        } finally { System.setProperty("user.dir",prior); }
    }

    private static DocumentIngestionService.Extraction extraction(String sha,String body,int page) {
        var block=new DocumentIngestionService.Block("p"+page,"para",body,body,page,new double[]{10,20,300,60},
                600,800,null,"accepted",true,0,body.length());
        return new DocumentIngestionService.Extraction("ok",body,body.length(),null,sha,DocumentIngestionService.PIPELINE_VERSION,
                List.of(new DocumentIngestionService.Page(page,600,800,"pt","text",List.of(block))),
                new DocumentIngestionService.QualityReport(1,1,0,0,null,List.of(),false,false));
    }
}
