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

import static org.dyh.learnhub.service.DocumentIngestionService.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class DocumentOfficeScanRefreshTest {
    @TempDir Path directory;

    @Test void forcedOfficeScanRefreshesVerifiedNativeBodyWithoutReparsingOrDroppingPriorImageText() throws Exception {
        String prior=System.getProperty("user.dir"); System.setProperty("user.dir",directory.toString());
        try {
            Path source=Files.createDirectories(directory.resolve("uploads")).resolve("synthetic.docx");
            Files.writeString(source,"synthetic source bytes");
            var old=nativeExtraction(source,false);
            var store=new DocumentExtractionStore(); store.save(71L,source,old);
            var info=file(source,old);
            var mapper=mock(FileInfoMapper.class); when(mapper.selectById(71L)).thenReturn(info);
            var parser=mock(DocumentTextService.class);
            var media=mock(DocumentMediaProcessor.class);
            when(media.process(eq(71L),eq(source),eq("docx"),any(),eq(false),eq(true),eq(true),any(DocumentMediaProcessor.LocalScanSession.class))).thenAnswer(call -> {
                Extraction input=call.getArgument(3);
                assertEquals(old.text(),input.text());
                assertEquals(old.pages().getFirst().blocks().stream().map(Block::id).toList(),
                        input.pages().getFirst().blocks().stream().map(Block::id).toList());
                var blocks=new java.util.ArrayList<>(input.pages().getFirst().blocks());
                blocks.add(new Block("image-local-scan","para","Fresh HQ image text","Fresh HQ image text",1,null,0,0,.98,
                        "accepted",true,-1,-1));
                return new Extraction("ok","",0,null,input.sourceSha256(),input.pipelineVersion(),
                        List.of(new Page(1,0,0,"logical","text",blocks)),input.report());
            });
            var storage=storage(mapper,parser,media);
            storage.reextract(71L,true,true);
            verify(parser,never()).extractDetailed(any(),anyString(),anyLong());
            verify(media).process(eq(71L),eq(source),eq("docx"),any(),eq(false),eq(true),eq(true),any(DocumentMediaProcessor.LocalScanSession.class));
            var saved=store.read(71L,source,info.getTextContent()).orElseThrow();
            assertTrue(saved.text().contains("Native synthetic paragraph"));
            assertTrue(saved.text().contains("Reliable legacy image OCR"));
            assertTrue(saved.text().contains("Fresh HQ image text"));
            assertEquals("x=7",saved.pages().getFirst().blocks().stream().filter(b -> b.id().equals("formula")).findFirst().orElseThrow().latex());
            assertTrue(store.read(71L,source,old.text()).isPresent(),"the prior content-addressed report remains available");
        } finally { System.setProperty("user.dir",prior); }
    }

    @Test void forcedOfficeRefreshNeverReusesChangedSourceOrIncompleteNativeCoverage() throws Exception {
        String prior=System.getProperty("user.dir");
        try {
            for(boolean changedSource:List.of(false,true)) {
                Path working=directory.resolve(changedSource?"changed":"incomplete"); System.setProperty("user.dir",working.toString());
                Path source=Files.createDirectories(working.resolve("uploads")).resolve("synthetic.docx");
                Files.writeString(source,"synthetic source bytes");
                var old=nativeExtraction(source,!changedSource);
                var store=new DocumentExtractionStore(); store.save(71L,source,old);
                var info=file(source,old);
                if(changedSource) Files.writeString(source,"changed synthetic source bytes");
                var parsed=nativeExtraction(source,false);
                var mapper=mock(FileInfoMapper.class); when(mapper.selectById(71L)).thenReturn(info);
                var parser=mock(DocumentTextService.class);
                when(parser.extractDetailed(eq(source),eq("docx"),anyLong())).thenReturn(parsed);
                var media=mock(DocumentMediaProcessor.class);
                when(media.process(eq(71L),eq(source),eq("docx"),any(),eq(false),eq(true),eq(true),any(DocumentMediaProcessor.LocalScanSession.class))).thenAnswer(call -> call.getArgument(3));
                storage(mapper,parser,media).reextract(71L,true,true);
                verify(parser).extractDetailed(eq(source),eq("docx"),anyLong());
                var saved=store.read(71L,source,info.getTextContent()).orElseThrow();
                assertEquals(parsed.sourceSha256(),saved.sourceSha256());
                assertFalse(saved.report().truncated());
            }
        } finally { System.setProperty("user.dir",prior); }
    }

    private static Extraction nativeExtraction(Path source,boolean incomplete) throws Exception {
        String sha=HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(source)));
        var paragraph=new Block("native","para","Native synthetic paragraph","Native synthetic paragraph",1,null,0,0,null,"accepted",true,-1,-1);
        var formula=new Block("formula","formula","x=7","x=7",1,null,0,0,null,"accepted",true,-1,-1,0,null,"x=7","restored","已人工核对");
        var image=new Block("image","figure","Reliable legacy image OCR","Reliable legacy image OCR",1,null,0,0,null,"recognized",true,-1,-1,0,
                "word:word/document.xml#rId1",null,null,"视觉识别未完成；本地 OCR 如有合格正文则保留");
        return DocumentMarkdownWriter.render(new Extraction("ok","",0,null,sha,PIPELINE_VERSION,
                List.of(new Page(1,0,0,"logical","text",List.of(paragraph,formula,image))),
                new QualityReport(incomplete?2:1,1,0,0,null,List.of("Synthetic media uncertainty"),incomplete,true)));
    }

    private static FileInfo file(Path source,Extraction extraction) throws Exception {
        var info=new FileInfo(); info.setId(71L); info.setExt("docx"); info.setStoreName(source.getFileName().toString());
        info.setSize(Files.size(source)); info.setTextStatus("ok"); info.setTextContent(extraction.text()); return info;
    }

    private static FileStorageService storage(FileInfoMapper mapper,DocumentTextService parser,DocumentMediaProcessor media) {
        var storage=new FileStorageService(mapper,null,parser,null,null,null,mock(ApplicationEventPublisher.class),null,null,null);
        storage.setMediaProcessor(media); return storage;
    }
}
