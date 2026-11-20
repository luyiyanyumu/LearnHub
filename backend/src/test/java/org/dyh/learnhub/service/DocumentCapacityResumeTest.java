package org.dyh.learnhub.service;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;

import static org.dyh.learnhub.service.DocumentIngestionService.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class DocumentCapacityResumeTest {
    @TempDir Path directory;

    @Test void resumingHigherCapacityReplacesTheWholeBoundaryBeforeUnseenPagesAndRetainsEarlierEvidence() throws Exception {
        Path source=pdf();
        var previous=limited(source,"Preserved synthetic body. ".repeat(15_998));
        assertEquals(1,previous.completedPages(),"an accepted prefix is not a complete capacity-limited page");
        var scanner=scanner();
        when(scanner.recognize(any(),any())).thenReturn(response("Boundary prefix accepted","Restored omitted tail. ".repeat(1000)))
                .thenReturn(response("New final page body"));
        var ingestion=new DocumentIngestionService(new PdfLayoutExtractor(),mock(DocumentOcrClient.class),scanner);
        try {
            var progress=new java.util.ArrayList<Progress>();
            var result=DocumentMarkdownWriter.render(ingestion.extract(source,"pdf",true,previous,progress::add));
            assertEquals(3,result.completedPages());
            assertTrue(result.chars()>400_000);
            assertFalse(result.report().truncated());
            assertFalse(result.report().partial());
            assertTrue(result.limitReasons().isEmpty());
            assertTrue(result.report().warnings().stream().noneMatch(w -> w.contains("character_limit") || w.contains("40 万")));
            assertEquals(List.of(2,3),progress.stream().filter(p -> p.currentPage()>0).map(Progress::currentPage).distinct().toList());
            var retained=result.pages().getFirst().blocks().getFirst();
            var old=previous.pages().getFirst().blocks().getFirst();
            assertEquals(old.rawText(),retained.rawText()); assertEquals(old.cleanText(),retained.cleanText());
            assertArrayEquals(old.rect(),retained.rect()); assertEquals(old.confidence(),retained.confidence());
            assertEquals(previous.sourceSha256(),result.sourceSha256());
            assertTrue(result.pages().get(1).blocks().stream().noneMatch(b -> "character_limit".equals(b.quality())));
            assertTrue(result.text().contains("Restored omitted tail."));
            assertTrue(result.text().endsWith("New final page body"));
            for(var page:result.pages()) for(var block:page.blocks()) if(block.indexed())
                assertEquals(DocumentMarkdownWriter.projectForIndex(block.markdown()),result.text().substring(block.start(),block.end()));
            verify(scanner,times(2)).recognize(any(),any());
        } finally { ingestion.close(); }
    }

    @Test void failedBoundaryRetryRetainsPriorAcceptedPrefixAndCapacityWarning() throws Exception {
        Path source=pdf(); var previous=limited(source,"Reliable synthetic original body");
        var scanner=scanner(); when(scanner.recognize(any(),any())).thenThrow(new IllegalStateException("Synthetic scanner unavailable"));
        var ingestion=new DocumentIngestionService(new PdfLayoutExtractor(),mock(DocumentOcrClient.class),scanner);
        try {
            var result=ingestion.extract(source,"pdf",true,previous,null);
            assertEquals(previous.pages().get(1).blocks().stream().map(Block::cleanText).toList(),
                    result.pages().get(1).blocks().stream().map(Block::cleanText).toList());
            assertEquals(1,result.completedPages());
            assertTrue(result.report().truncated());
            assertTrue(result.limitReasons().stream().anyMatch(limit -> limit.code().equals("document_capacity")));
            verify(scanner,times(2)).recognize(any(),any());
        } finally { ingestion.close(); }
    }

    @Test void moreMarkedPagesThanOneBatchContinueUntilEveryOldCapacityBoundaryIsRepaired() throws Exception {
        Path source=pdf(22); var base=limited(source,"Reliable first page");
        var pages=new java.util.ArrayList<Page>(); pages.add(base.pages().getFirst());
        for(int number=2;number<=22;number++) {
            var prefix=new Block("p"+number+"-prefix","para","Prior prefix","Prior prefix",number,new double[]{5,20,65,30},72,72,.99,"accepted",true,-1,-1);
            var boundary=new Block("p"+number+"-limit","para","Prior omitted tail","Prior omitted tail",number,new double[]{5,35,65,45},72,72,.99,"character_limit",false,-1,-1);
            pages.add(new Page(number,72,72,"pt","ocr",List.of(prefix,boundary)));
        }
        var previous=new Extraction("ok",base.text(),base.chars(),null,base.sourceSha256(),STRUCTURED_PIPELINE_VERSION,pages,
                new QualityReport(22,22,22,0,.99,List.of("Markdown 正文达到 40 万字符上限，未截断代码、公式或表格；剩余块未入索引"),true,true));
        var scanner=scanner(); var calls=new java.util.concurrent.atomic.AtomicInteger();
        when(scanner.recognize(any(),any())).thenAnswer(call -> response("Recovered synthetic boundary "+calls.incrementAndGet()));
        var ingestion=new DocumentIngestionService(new PdfLayoutExtractor(),mock(DocumentOcrClient.class),scanner);
        try {
            var first=ingestion.extract(source,"pdf",true,previous,null);
            assertEquals(21,first.completedPages());
            assertTrue(first.report().truncated());
            assertTrue(canResumeCharacterLimit(first));
            assertEquals(true,DocumentExtractionStore.summary(first).get("canResumeCharacterLimit"));
            var last=ingestion.extract(source,"pdf",true,first,null);
            assertEquals(22,last.completedPages()); assertFalse(last.report().truncated());
            assertFalse(canResumeCharacterLimit(last)); assertTrue(last.limitReasons().isEmpty());
            assertEquals("Reliable first page",last.pages().getFirst().blocks().getFirst().cleanText());
            verify(scanner,times(21)).recognize(any(),any());
        } finally { ingestion.close(); }
    }

    @Test void stillInsufficientCapacityDoesNotAdvancePastTheUnrepairedBoundary() throws Exception {
        Path source=pdf(); var old=limited(source,"Synthetic body before expansion");
        var first=old.pages().getFirst().blocks().getFirst();
        var expanded=new Block(first.id(),first.type(),"Compact synthetic source", "a".repeat(DocumentTextService.MAX_TEXT_CHARS-20),1,
                first.rect(),72,72,.99,"accepted",true,0,DocumentTextService.MAX_TEXT_CHARS-20);
        var previous=new Extraction("ok",expanded.cleanText(),expanded.cleanText().length(),null,old.sourceSha256(),old.pipelineVersion(),
                List.of(new Page(1,72,72,"pt","ocr",List.of(expanded)),old.pages().get(1)),old.report());
        var scanner=scanner(); when(scanner.recognize(any(),any())).thenReturn(response("Boundary replacement exceeds the remaining character budget"));
        var ingestion=new DocumentIngestionService(new PdfLayoutExtractor(),mock(DocumentOcrClient.class),scanner);
        try {
            var result=ingestion.extract(source,"pdf",true,previous,null);
            assertEquals(2,result.report().processedPages());
            assertEquals(1,result.completedPages());
            assertTrue(result.report().truncated());
            assertTrue(result.limitReasons().stream().anyMatch(limit -> limit.code().equals("document_capacity")));
            assertTrue(result.pages().get(1).blocks().stream().anyMatch(b -> "character_limit".equals(b.quality())));
            assertFalse(canResumeCharacterLimit(result),"the current capacity must stop automatic continuation");
            verify(scanner,times(1)).recognize(any(),any());
        } finally { ingestion.close(); }
    }

    @Test void unlocatedRawOrBlockCapacityDoesNotGuessABoundaryOrScanNewPages() throws Exception {
        Path source=pdf(); var old=limited(source,"Reliable synthetic original body");
        var previous=new Extraction(old.status(),old.text(),old.chars(),null,old.sourceSha256(),old.pipelineVersion(),old.pages(),
                new QualityReport(3,2,2,0,.99,List.of("累计抽取结构达到 20000 块/80 万原始字符上限；请拆分原件以抽取剩余内容"),true,true));
        var scanner=scanner();
        var ingestion=new DocumentIngestionService(new PdfLayoutExtractor(),mock(DocumentOcrClient.class),scanner);
        try {
            var result=ingestion.extract(source,"pdf",true,previous,null);
            assertEquals(2,result.report().processedPages());
            assertTrue(result.report().truncated());
            assertTrue(result.limitReasons().stream().anyMatch(limit -> limit.code().equals("document_capacity")));
            verify(scanner,never()).recognize(any(),any());
        } finally { ingestion.close(); }
    }

    @Test void aKnownCharacterBoundaryCannotClearAnAdditionalUnknownStorageOrReadCapacity() throws Exception {
        Path source=pdf(); var old=limited(source,"Reliable synthetic original body");
        for(String warning:List.of("正文达到存储上限，合成存储限制","文本输入超过读取上限，合成读取限制")) {
            var previous=new Extraction(old.status(),old.text(),old.chars(),null,old.sourceSha256(),old.pipelineVersion(),old.pages(),
                    new QualityReport(3,2,2,0,.99,List.of(old.report().warnings().getFirst(),warning),true,true));
            var scanner=scanner(); var ingestion=new DocumentIngestionService(new PdfLayoutExtractor(),mock(DocumentOcrClient.class),scanner);
            try {
                var result=ingestion.extract(source,"pdf",true,previous,null);
                assertTrue(result.report().warnings().contains(warning));
                assertTrue(result.report().truncated()); assertFalse(canResumeCharacterLimit(result));
                assertEquals(2,result.report().processedPages());
                verify(scanner,never()).recognize(any(),any());
            } finally { ingestion.close(); }
        }
    }

    @Test void changedSourceOrGenerationCannotReuseEvenExplicitBoundaryPages() throws Exception {
        Path source=pdf(); var old=limited(source,"Reliable synthetic original body");
        for(boolean changedSource:List.of(false,true)) {
            var previous=new Extraction(old.status(),old.text(),old.chars(),null,changedSource?"a".repeat(64):old.sourceSha256(),
                    changedSource?old.pipelineVersion():LEGACY_HQ_PIPELINE_VERSION,old.pages(),old.report());
            var scanner=scanner(); when(scanner.recognize(any(),any())).thenReturn(response("Fresh source scan"));
            var ingestion=new DocumentIngestionService(new PdfLayoutExtractor(),mock(DocumentOcrClient.class),scanner);
            try {
                var result=ingestion.extract(source,"pdf",true,previous,null);
                assertEquals(3,result.completedPages()); assertFalse(result.report().truncated());
                assertFalse(result.text().contains("Reliable synthetic original body"));
                verify(scanner,times(3)).recognize(any(),any());
            } finally { ingestion.close(); }
        }
    }

    private Path pdf() throws Exception { return pdf(3); }

    private Path pdf(int total) throws Exception {
        Path source=directory.resolve("synthetic.pdf");
        try(var pdf=new PDDocument()) {
            for(int i=0;i<total;i++) {
                var page=new PDPage(new PDRectangle(72,72)); pdf.addPage(page);
                try(var content=new PDPageContentStream(pdf,page)) {
                    content.beginText(); content.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA),8);
                    content.newLineAtOffset(5,35); content.showText("Synthetic page "+(i+1)); content.endText();
                }
            }
            pdf.save(source.toFile());
        }
        return source;
    }

    private static Extraction limited(Path source,String body) throws Exception {
        String sha=HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(source)));
        var first=new Block("p1-body","para",body,body,1,new double[]{5,25,65,45},72,72,.99,"accepted",true,0,body.length());
        String prefix="Boundary prefix accepted";
        var accepted=new Block("p2-prefix","para",prefix,prefix,2,new double[]{5,20,65,30},72,72,.99,"accepted",true,body.length()+2,body.length()+2+prefix.length());
        var limited=new Block("p2-limit","para","Omitted boundary tail","Omitted boundary tail",2,new double[]{5,35,65,45},72,72,.99,"character_limit",false,-1,-1);
        String text=body+"\n\n"+prefix;
        return new Extraction("ok",text,text.length(),null,sha,STRUCTURED_PIPELINE_VERSION,
                List.of(new Page(1,72,72,"pt","ocr",List.of(first)),new Page(2,72,72,"pt","ocr",List.of(accepted,limited))),
                new QualityReport(3,2,2,0,.99,List.of("累计正文达到 40 万字符上限；请拆分原件以抽取剩余内容","第 2 页存在未通过质量门槛的块：character_limit"),true,true));
    }

    private static DocumentStructureClient scanner() {
        var scanner=mock(DocumentStructureClient.class); when(scanner.configured()).thenReturn(true); return scanner;
    }

    private static DocumentStructureClient.Result response(String... text) {
        var blocks=new java.util.ArrayList<DocumentStructureClient.Block>();
        for(int i=0;i<text.length;i++) blocks.add(new DocumentStructureClient.Block("para",text[i],new double[]{20,100+i*30,280,125+i*30},.99,0,List.of(),""));
        return new DocumentStructureClient.Result(300,300,blocks,List.of());
    }
}
