package org.dyh.learnhub.service;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class DocumentProcessingJobsTest {
    @TempDir Path work;
    String priorDirectory;
    FileStorageService files;
    VectorIndexService vectors;
    DocumentProcessingJobs jobs;
    @BeforeEach void setup() {
        priorDirectory=System.getProperty("user.dir"); System.setProperty("user.dir",work.toString());
        files=mock(FileStorageService.class); vectors=mock(VectorIndexService.class);
        when(vectors.reindexChanged()).thenReturn(Map.of("configured",false,"reason","未配置嵌入，关键词与结构分块可用"));
        jobs=new DocumentProcessingJobs(files,vectors);
    }
    @AfterEach void cleanup() { jobs.close(); System.setProperty("user.dir",priorDirectory); }
    private Map<String,Object> completed(String id,String kind) throws Exception {
        long deadline=System.nanoTime()+Duration.ofSeconds(5).toNanos();
        Map<String,Object> state;
        do {
            state=jobs.status(id,kind);
            if (!List.of("queued","running").contains(state.get("status"))) return state;
            Thread.sleep(10);
        } while(System.nanoTime()<deadline);
        throw new AssertionError("synthetic job did not finish");
    }

    @Test void duplicateMigrationRejoinsAndAFileFailureDoesNotDiscardOtherResults() throws Exception {
        var started=new CountDownLatch(1); var release=new CountDownLatch(1);
        when(files.markdownCandidates(false,true)).thenReturn(List.of(11L,12L));
        when(files.reextract(11L,true)).thenAnswer(call -> {
            started.countDown(); assertTrue(release.await(2,TimeUnit.SECONDS));
            return Map.of("textStatus","ok","extraction",Map.of("report",Map.of("partial",false)));
        });
        when(files.reextract(12L,true)).thenThrow(new IllegalStateException("合成转换失败，旧版本保留"));
        String id=jobs.startMigration(false,true).get("id").toString();
        assertTrue(started.await(2,TimeUnit.SECONDS));
        assertEquals(id,jobs.startMigration(false,true).get("id")); release.countDown();
        var state=completed(id,"migration");
        assertEquals("partial",state.get("status")); assertEquals(2,state.get("done")); assertEquals(1,state.get("failed"));
        verify(vectors,times(1)).reindexChanged();
        var index=(Map<?,?>)state.get("index"); assertEquals(false,index.get("configured"));
        jobs.close(); jobs=new DocumentProcessingJobs(files,vectors);
        assertEquals("partial",jobs.status(id,"migration").get("status"),"progress survives a process restart");
    }

    @Test void alreadyConvertedMediaUsesRetainedRegionsAndReportsPendingWork() throws Exception {
        when(files.markdownCandidates(false,true)).thenReturn(List.of(8L)); when(files.isCurrentMarkdown(8L)).thenReturn(true);
        when(files.processMedia(8L,false)).thenReturn(Map.of("textStatus","ok","extraction",Map.of("report",Map.of("partial",true))));
        String id=jobs.startMigration(false,true).get("id").toString();
        assertEquals("partial",completed(id,"migration").get("status"));
        verify(files).processMedia(8L,false); verify(files,never()).reextract(anyLong(),anyBoolean());
    }

    @Test void explicitJobsContinuePendingRegionsAndReuseTheSuccessfulResults() throws Exception {
        when(files.markdownCandidates(false,true)).thenReturn(List.of(8L)); when(files.isCurrentMarkdown(8L)).thenReturn(true);
        var partial=Map.<String,Object>of("textStatus","ok","extraction",Map.of("report",Map.of("partial",true)));
        var finished=Map.<String,Object>of("textStatus","ok","extraction",Map.of("report",Map.of("partial",false)));
        when(files.processMedia(8L,false)).thenReturn(partial,partial,finished);
        when(files.pendingMediaRegions(8L)).thenReturn(25,13,0);
        String id=jobs.startMigration(false,true).get("id").toString();
        assertEquals("completed",completed(id,"migration").get("status"));
        verify(files,times(3)).processMedia(8L,false); verify(vectors,times(1)).reindexChanged();
    }

    @Test void continuationStopsIfTheRoundCannotReducePendingWork() throws Exception {
        when(files.processMedia(8L,false)).thenReturn(Map.of("textStatus","ok","extraction",Map.of("report",Map.of("partial",true))));
        when(files.pendingMediaRegions(8L)).thenReturn(2);
        String id=jobs.startMedia(8L,false).get("id").toString();
        assertEquals("partial",completed(id,"media").get("status"));
        verify(files,times(2)).processMedia(8L,false);
    }

    @Test void staleRunningRecordIsInterruptedAndNeverSilentlyResumesModelCalls() throws Exception {
        String id="11111111-1111-1111-1111-111111111111";
        Path directory=work.resolve("uploads/.derived/document-jobs"); Files.createDirectories(directory);
        new com.fasterxml.jackson.databind.ObjectMapper().writeValue(directory.resolve(id+".json").toFile(),
                Map.of("id",id,"kind","media","status","running","done",0,"total",1));
        assertEquals("failed",jobs.status(id,"media").get("status"));
        verifyNoInteractions(files,vectors);
        assertThrows(IllegalArgumentException.class,()->jobs.status("../invalid","media"));
    }
}
