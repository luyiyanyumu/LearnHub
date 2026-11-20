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
import java.util.function.Consumer;
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
        when(files.processMedia(eq(8L),eq(false),eq(false),any(DocumentMediaProcessor.LocalScanSession.class))).thenReturn(Map.of("textStatus","ok","extraction",Map.of("report",Map.of("partial",true))));
        String id=jobs.startMigration(false,true).get("id").toString();
        assertEquals("partial",completed(id,"migration").get("status"));
        verify(files).processMedia(eq(8L),eq(false),eq(false),any(DocumentMediaProcessor.LocalScanSession.class)); verify(files,never()).reextract(anyLong(),anyBoolean());
    }

    @Test void explicitJobsContinuePendingRegionsAndReuseTheSuccessfulResults() throws Exception {
        when(files.markdownCandidates(false,true)).thenReturn(List.of(8L)); when(files.isCurrentMarkdown(8L)).thenReturn(true);
        var partial=Map.<String,Object>of("textStatus","ok","extraction",Map.of("report",Map.of("partial",true)));
        var finished=Map.<String,Object>of("textStatus","ok","extraction",Map.of("report",Map.of("partial",false)));
        when(files.processMedia(eq(8L),eq(false),eq(false),any(DocumentMediaProcessor.LocalScanSession.class))).thenReturn(partial,partial,finished);
        when(files.pendingMediaRegions(8L)).thenReturn(25,13,0);
        String id=jobs.startMigration(false,true).get("id").toString();
        assertEquals("completed",completed(id,"migration").get("status"));
        verify(files,times(3)).processMedia(eq(8L),eq(false),eq(false),any(DocumentMediaProcessor.LocalScanSession.class)); verify(vectors,times(1)).reindexChanged();
    }

    @Test void localScanCannotSilentlyRejoinAnOrdinaryMediaJobButDuplicateLocalRequestsRejoin() throws Exception {
        var ordinaryStarted=new CountDownLatch(1); var ordinaryRelease=new CountDownLatch(1);
        var result=Map.<String,Object>of("textStatus","ok","extraction",Map.of("report",Map.of("partial",false)));
        when(files.processMedia(eq(8L),eq(false),eq(false),any(DocumentMediaProcessor.LocalScanSession.class))).thenAnswer(call -> {
            ordinaryStarted.countDown(); assertTrue(ordinaryRelease.await(5,TimeUnit.SECONDS)); return result;
        });
        String ordinary=jobs.startMedia(8L,false).get("id").toString();
        try {
            assertTrue(ordinaryStarted.await(5,TimeUnit.SECONDS));
            assertThrows(IllegalArgumentException.class,() -> jobs.startMedia(8L,false,true));
            assertEquals(ordinary,jobs.startMedia(8L,false).get("id"));
        } finally { ordinaryRelease.countDown(); }
        assertEquals("completed",completed(ordinary,"media").get("status"));

        var localStarted=new CountDownLatch(1); var localRelease=new CountDownLatch(1);
        when(files.processMedia(eq(8L),eq(false),eq(true),any(DocumentMediaProcessor.LocalScanSession.class))).thenAnswer(call -> {
            localStarted.countDown(); assertTrue(localRelease.await(5,TimeUnit.SECONDS)); return result;
        });
        String local=jobs.startMedia(8L,false,true).get("id").toString();
        try {
            assertTrue(localStarted.await(5,TimeUnit.SECONDS));
            assertEquals(local,jobs.startMedia(8L,false,true).get("id"));
            assertThrows(IllegalArgumentException.class,() -> jobs.startMedia(8L,false));
        } finally { localRelease.countDown(); }
        assertEquals("completed",completed(local,"media").get("status"));
        verify(files,times(1)).processMedia(eq(8L),eq(false),eq(false),any(DocumentMediaProcessor.LocalScanSession.class));
        verify(files,times(1)).processMedia(eq(8L),eq(false),eq(true),any(DocumentMediaProcessor.LocalScanSession.class));
    }

    @Test void targetedLocalScanKeepsItsFlagAcrossAllMediaRoundsWithoutExternalForce() throws Exception {
        var partial=Map.<String,Object>of("textStatus","ok","extraction",Map.of("report",Map.of("partial",true)));
        var finished=Map.<String,Object>of("textStatus","ok","extraction",Map.of("report",Map.of("partial",false)));
        when(files.processMedia(eq(8L),eq(false),eq(true),any(DocumentMediaProcessor.LocalScanSession.class))).thenReturn(partial,partial,finished);
        when(files.pendingMediaRegions(8L)).thenReturn(24,12,0);
        String id=jobs.startMedia(8L,false,true).get("id").toString();
        assertEquals("completed",completed(id,"media").get("status"));
        verify(files,times(3)).processMedia(eq(8L),eq(false),eq(true),any(DocumentMediaProcessor.LocalScanSession.class));
        verify(files,never()).processMedia(anyLong(),anyBoolean());
        verify(files,never()).processMedia(anyLong(),eq(true),anyBoolean());
        verify(files,never()).processMedia(anyLong(),eq(true),anyBoolean(),any(DocumentMediaProcessor.LocalScanSession.class));
        verify(files,never()).reextract(anyLong(),anyBoolean(),anyBoolean());
        verify(vectors,times(1)).reindexChanged();
    }

    @Test void twoBatchesOfQualityFailuresStillAdvanceFreshCoverageAndDoNotStarveLaterImages() throws Exception {
        when(files.processMedia(eq(45L),eq(false),eq(true),any(DocumentMediaProcessor.LocalScanSession.class))).thenReturn(localDetail(26,0,12,14,12,0,0),
                localDetail(26,0,24,2,24,0,0),localDetail(26,2,26,0,24,0,0));
        when(files.pendingMediaRegions(45L)).thenReturn(26,26,24);
        var state=completed(jobs.startMedia(45L,false,true).get("id").toString(),"media");
        assertEquals("partial",state.get("status")); assertEquals(0,state.get("failed"));
        verify(files,times(3)).processMedia(eq(45L),eq(false),eq(true),any(DocumentMediaProcessor.LocalScanSession.class));
        var progress=(Map<?,?>)((Map<?,?>)((List<?>)state.get("items")).getFirst()).get("localScanProgress");
        assertEquals(26,progress.get("actualAttempted")); assertEquals(0,progress.get("unattempted"));
        assertEquals(24,progress.get("needsReview"));
    }

    @Test void boundedRequestRetriesCountAsProgressWithoutCallingAReviewOrExhaustedFailureAgain() throws Exception {
        when(files.processMedia(eq(46L),eq(false),eq(true),any(DocumentMediaProcessor.LocalScanSession.class))).thenReturn(localDetail(13,1,13,0,0,12,12),localDetail(13,1,13,0,0,12,0));
        when(files.pendingMediaRegions(46L)).thenReturn(12);
        assertEquals("partial",completed(jobs.startMedia(46L,false,true).get("id").toString(),"media").get("status"));
        verify(files,times(2)).processMedia(eq(46L),eq(false),eq(true),any(DocumentMediaProcessor.LocalScanSession.class));
    }

    private static Map<String,Object> localDetail(int regions,int completed,int attempted,int fresh,int review,int failed,int retryable) {
        return Map.of("textStatus","ok","extraction",Map.of("report",Map.of("partial",true),"localScanProgress",
                new DocumentMediaProcessor.LocalScanProgress(true,regions,completed,attempted,fresh,review,failed,retryable)));
    }

    @Test void newTaskSharesOneRetryBudgetAndFailedRequestsStillAdvanceAcrossSeveralOldFailureBatches() throws Exception {
        var sessions=new java.util.ArrayList<DocumentMediaProcessor.LocalScanSession>();
        when(files.processMedia(eq(47L),eq(false),eq(true),any(DocumentMediaProcessor.LocalScanSession.class))).thenAnswer(call -> {
            DocumentMediaProcessor.LocalScanSession session=call.getArgument(3); sessions.add(session);
            session.attempted("synthetic-image-"+sessions.size());
            int retryable=sessions.size()<3?26:sessions.size()==3?14:0;
            return localDetail(26,0,26,0,0,26,retryable);
        });
        when(files.pendingMediaRegions(47L)).thenReturn(26);
        var state=completed(jobs.startMedia(47L,false,true).get("id").toString(),"media");
        assertEquals("partial",state.get("status")); assertEquals(4,sessions.size());
        assertTrue(sessions.stream().allMatch(session -> session==sessions.getFirst()));
        assertEquals(4,sessions.getFirst().totalCalls());
        verify(files,times(4)).processMedia(eq(47L),eq(false),eq(true),any(DocumentMediaProcessor.LocalScanSession.class));
    }

    @Test void continuationStopsIfTheRoundCannotReducePendingWork() throws Exception {
        when(files.processMedia(eq(8L),eq(false),eq(false),any(DocumentMediaProcessor.LocalScanSession.class))).thenReturn(Map.of("textStatus","ok","extraction",Map.of("report",Map.of("partial",true))));
        when(files.pendingMediaRegions(8L)).thenReturn(2);
        String id=jobs.startMedia(8L,false).get("id").toString();
        assertEquals("partial",completed(id,"media").get("status"));
        verify(files,times(2)).processMedia(eq(8L),eq(false),eq(false),any(DocumentMediaProcessor.LocalScanSession.class));
    }

    @Test void duplicateStructureRequestRejoinsAndReportsUnreviewedFormulas() throws Exception {
        var started = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        when(files.extractInBackground(eq(8L),eq(true),eq(false),any())).thenAnswer(call -> {
            started.countDown();
            assertTrue(release.await(2, TimeUnit.SECONDS));
            return Map.of("textStatus", "ok", "pendingFormulas", 1,
                    "extraction", Map.of("report", Map.of("partial", false)));
        });
        var task = jobs.startStructure(8L);
        String id = task.get("id").toString();
        assertEquals("8", task.get("fileId"));
        assertTrue(started.await(2, TimeUnit.SECONDS));
        assertEquals(id, jobs.startStructure(8L).get("id"));
        release.countDown();
        var finished = completed(id, "structure");
        assertEquals("partial", finished.get("status"));
        assertEquals(1, finished.get("done"));
        assertTrue(finished.get("stage").toString().contains("1 处公式待核对"));
        verify(files).extractInBackground(eq(8L),eq(true),eq(false),any());
        verify(files, never()).processMedia(anyLong(), anyBoolean());
        verify(vectors).reindexChanged();
    }

    @Test void resumeReportsLivePageProgressAndRejectsConflictingWorkForTheSameFile() throws Exception {
        var started=new CountDownLatch(1);
        var release=new CountDownLatch(1);
        when(files.extractInBackground(eq(22L),eq(false),eq(true),any())).thenAnswer(call -> {
            Consumer<DocumentIngestionService.Progress> progress=call.getArgument(3);
            progress.accept(new DocumentIngestionService.Progress("extracting",9,431,10));
            progress.accept(new DocumentIngestionService.Progress("preparing",0,0,0));
            started.countDown();
            assertTrue(release.await(3,TimeUnit.SECONDS));
            progress.accept(new DocumentIngestionService.Progress("saving",21,431,0));
            progress.accept(new DocumentIngestionService.Progress("extracting",20,431,21));
            return Map.of("textStatus","ok","extraction",Map.of("report",Map.of("partial",true,
                    "warnings",List.of("已达到 120 秒任务预算；再次继续抽取可处理剩余页"))));
        });
        var task=jobs.startExtraction(22L,true);
        String id=task.get("id").toString();
        try {
            assertEquals("22",task.get("fileId"));
            assertEquals(true,task.get("resume"));
            assertTrue(started.await(3,TimeUnit.SECONDS));
            var running=jobs.status(id,"extraction");
            assertEquals("running",running.get("status"));
            assertEquals(0,running.get("done"));
            assertEquals(1,running.get("total"));
            assertEquals("正在抽取第 10 页",running.get("stage"));
            assertEquals(Map.of("stage","extracting","completedPages",9,"totalPages",431,"currentPage",10),running.get("pageProgress"));
            assertEquals(id,jobs.startExtraction(22L,true).get("id"));
            assertThrows(IllegalArgumentException.class,() -> jobs.startExtraction(22L,false));
            assertThrows(IllegalArgumentException.class,() -> jobs.startStructure(22L,true));
            assertThrows(IllegalArgumentException.class,() -> jobs.startMedia(22L,false));
        } finally {
            release.countDown();
        }
        var finished=completed(id,"extraction");
        assertEquals("partial",finished.get("status"));
        assertEquals("本批达到 120 秒预算，可继续抽取剩余页",finished.get("stage"));
        assertEquals(1,finished.get("done"));
        assertEquals(Map.of("stage","saving","completedPages",21,"totalPages",431,"currentPage",0),finished.get("pageProgress"));
        verify(files).extractInBackground(eq(22L),eq(false),eq(true),any());
        verify(vectors).reindexChanged();
        jobs.close();
        jobs=new DocumentProcessingJobs(files,vectors);
        assertEquals(finished.get("pageProgress"),jobs.status(id,"extraction").get("pageProgress"));
    }

    @Test void noGainKeepsPriorResultAndDoesNotRefreshTheIndex() throws Exception {
        String reason="本批未增加通过质量校验的页面。已保留上次正文和定位";
        when(files.extractInBackground(eq(23L),eq(false),eq(true),any())).thenReturn(
                Map.of("textStatus","ok","noProgress",true,"resumeMessage",reason));
        String id=jobs.startExtraction(23L,true).get("id").toString();
        var finished=completed(id,"extraction");
        assertEquals("partial",finished.get("status"));
        assertEquals(reason,finished.get("stage"));
        var item=(Map<?,?>)((List<?>)finished.get("items")).getFirst();
        assertEquals(true,item.get("noProgress"));
        verify(files).extractInBackground(eq(23L),eq(false),eq(true),any());
        verifyNoInteractions(vectors);
    }

    private Map<String,Object> scanSummary(int completed,int processed,int total,boolean partial) {
        return Map.of("pipelineVersion",DocumentIngestionService.STRUCTURED_PIPELINE_VERSION,"completedPages",completed,
                "report",Map.of("totalPages",total,"processedPages",processed,"partial",partial));
    }

    @Test void configuredDefaultScanContinuesCandidateBatchesAndClearsPendingStatusAfterPromotion() throws Exception {
        when(files.prefersScan(31L)).thenReturn(true);
        when(files.extractInBackground(eq(31L),eq(false),eq(false),any())).thenReturn(Map.of("textStatus","ok",
                "pendingUpgrade",true,"scanProgress",scanSummary(20,20,25,true),
                "extraction",Map.of("completedPages",25,"report",Map.of("totalPages",25,"partial",false))));
        when(files.extractInBackground(eq(31L),eq(false),eq(true),any())).thenReturn(Map.of("textStatus","ok",
                "extraction",scanSummary(25,25,25,false)));
        var task=jobs.startExtraction(31L,false);
        assertEquals(true,task.get("scanMode"));
        var state=completed(task.get("id").toString(),"extraction");
        assertEquals("completed",state.get("status"));
        assertEquals(false,state.get("pendingUpgrade"));
        assertEquals(25,((Map<?,?>)state.get("scanProgress")).get("completedPages"));
        verify(files).extractInBackground(eq(31L),eq(false),eq(false),any());
        verify(files).extractInBackground(eq(31L),eq(false),eq(true),any());
        verify(vectors).reindexChanged();
    }

    @Test void stalledCandidateDoesNotSpinOrReindexTheOldPublishedVersion() throws Exception {
        var pending=Map.<String,Object>of("textStatus","ok","pendingUpgrade",true,
                "scanProgress",scanSummary(20,21,25,true),"resumeMessage","扫描候选已保留");
        when(files.extractInBackground(eq(32L),eq(true),anyBoolean(),any())).thenReturn(pending);
        var state=completed(jobs.startStructure(32L,false).get("id").toString(),"structure");
        assertEquals("partial",state.get("status"));
        assertEquals(true,state.get("pendingUpgrade"));
        verify(files,times(2)).extractInBackground(eq(32L),eq(true),anyBoolean(),any());
        verifyNoInteractions(vectors);
    }

    @Test void migrationResumesPersistedScanCandidateAndDoesNotIssueAnExternalMediaRound() throws Exception {
        when(files.markdownCandidates(true,true)).thenReturn(List.of(33L));
        when(files.prefersScan(33L)).thenReturn(true);
        when(files.hasPendingScan(33L)).thenReturn(true);
        when(files.extractInBackground(eq(33L),eq(true),eq(true),any(),eq(true))).thenReturn(Map.of("textStatus","ok",
                "extraction",scanSummary(25,25,25,false)));
        var state=completed(jobs.startMigration(true,true).get("id").toString(),"migration");
        assertEquals("completed",state.get("status"));
        verify(files).extractInBackground(eq(33L),eq(true),eq(true),any(),eq(true));
        verify(files,never()).processMedia(anyLong(),anyBoolean());
        verify(files,never()).pendingMediaRegions(anyLong());
    }

    @Test void migrationReportsFormulaReviewEvenWhenAllScannedTextPagesAreComplete() throws Exception {
        when(files.markdownCandidates(true,true)).thenReturn(List.of(34L));
        when(files.prefersScan(34L)).thenReturn(true);
        when(files.extractInBackground(eq(34L),eq(true),eq(false),any(),eq(true))).thenReturn(Map.of("textStatus","ok","pendingFormulas",2L,
                "extraction",scanSummary(1,1,1,false)));
        var state=completed(jobs.startMigration(true,true).get("id").toString(),"migration");
        assertEquals("partial",state.get("status"));
        assertEquals(1,state.get("partial"));
        assertEquals(2L,((Map<?,?>)((List<?>)state.get("items")).getFirst()).get("pendingFormulas"));
        verify(files,times(1)).extractInBackground(eq(34L),eq(true),eq(false),any(),eq(true));
    }

    @Test void forcedOfficeMigrationRequestsLocalScanWithoutForcingExternalMedia() throws Exception {
        when(files.markdownCandidates(true,true)).thenReturn(List.of(35L));
        when(files.reextract(eq(35L),eq(true),eq(true),any(DocumentMediaProcessor.LocalScanSession.class))).thenReturn(Map.of("textStatus","ok","extraction",Map.of("report",Map.of("partial",true))));
        when(files.pendingMediaRegions(35L)).thenReturn(1,0);
        when(files.processMedia(eq(35L),eq(false),eq(true),any(DocumentMediaProcessor.LocalScanSession.class))).thenReturn(Map.of("textStatus","ok","extraction",Map.of("report",Map.of("partial",false))));
        var state=completed(jobs.startMigration(true,true).get("id").toString(),"migration");
        assertEquals("completed",state.get("status"));
        verify(files).reextract(eq(35L),eq(true),eq(true),any(DocumentMediaProcessor.LocalScanSession.class));
        verify(files).processMedia(eq(35L),eq(false),eq(true),any(DocumentMediaProcessor.LocalScanSession.class));
        verify(files,never()).processMedia(eq(35L),eq(true),eq(false),any(DocumentMediaProcessor.LocalScanSession.class));
    }

    @Test void onlyForcedMigrationPropagatesGeneratedProseReplacementAcrossEveryPageRound() throws Exception {
        when(files.markdownCandidates(true,false)).thenReturn(List.of(36L));
        when(files.prefersScan(36L)).thenReturn(true);
        when(files.extractInBackground(eq(36L),eq(true),eq(false),any(),eq(true))).thenReturn(Map.of("textStatus","ok",
                "pendingUpgrade",true,"scanProgress",scanSummary(20,20,25,true)));
        when(files.extractInBackground(eq(36L),eq(true),eq(true),any(),eq(true))).thenReturn(Map.of("textStatus","ok",
                "extraction",scanSummary(25,25,25,true)));
        var state=completed(jobs.startMigration(true,false).get("id").toString(),"migration");
        assertEquals("partial",state.get("status"),"quality warnings remain visible after publication");
        verify(files).extractInBackground(eq(36L),eq(true),eq(false),any(),eq(true));
        verify(files).extractInBackground(eq(36L),eq(true),eq(true),any(),eq(true));
        verify(files,never()).extractInBackground(eq(36L),anyBoolean(),anyBoolean(),any());

        when(files.extractInBackground(eq(37L),eq(false),eq(true),any())).thenReturn(Map.of("textStatus","ok","noProgress",true));
        completed(jobs.startExtraction(37L,true).get("id").toString(),"extraction");
        verify(files).extractInBackground(eq(37L),eq(false),eq(true),any());
        verify(files,never()).extractInBackground(eq(37L),anyBoolean(),anyBoolean(),any(),anyBoolean());
    }

    @Test void explicitForcedResumePropagatesReplacementAcrossRoundsAndRejectsAnActiveDifferentMode() throws Exception {
        var started=new CountDownLatch(1); var release=new CountDownLatch(1);
        when(files.extractInBackground(eq(38L),eq(false),eq(true),any(),eq(true))).thenAnswer(call -> {
            started.countDown(); assertTrue(release.await(5,TimeUnit.SECONDS));
            var summary=new java.util.LinkedHashMap<String,Object>(scanSummary(20,22,25,true));
            summary.put("limitReasons",List.of(Map.of("code","document_capacity")));
            summary.put("canResumeCharacterLimit",true);
            return Map.of("textStatus","ok","pendingUpgrade",true,"scanProgress",summary);
        }).thenReturn(Map.of("textStatus","ok","extraction",scanSummary(25,25,25,false)));
        String id=jobs.startExtraction(38L,true,true).get("id").toString();
        try {
            assertTrue(started.await(5,TimeUnit.SECONDS));
            assertThrows(IllegalArgumentException.class,() -> jobs.startExtraction(38L,true));
            assertEquals(id,jobs.startExtraction(38L,true,true).get("id"));
        } finally { release.countDown(); }
        assertEquals("completed",completed(id,"extraction").get("status"));
        verify(files,times(2)).extractInBackground(eq(38L),eq(false),eq(true),any(),eq(true));
        verify(files,never()).extractInBackground(eq(38L),anyBoolean(),anyBoolean(),any());
    }

    @Test void currentCapacityStopsForcedResumeEvenWhenUnprocessedPagesRemain() throws Exception {
        var summary=new java.util.LinkedHashMap<String,Object>(scanSummary(20,21,25,true));
        summary.put("limitReasons",List.of(Map.of("code","document_capacity")));
        summary.put("canResumeCharacterLimit",false);
        when(files.extractInBackground(eq(39L),eq(false),eq(true),any(),eq(true)))
                .thenReturn(Map.of("textStatus","ok","pendingUpgrade",true,"scanProgress",summary));
        String id=jobs.startExtraction(39L,true,true).get("id").toString();
        assertEquals("partial",completed(id,"extraction").get("status"));
        verify(files,times(1)).extractInBackground(eq(39L),eq(false),eq(true),any(),eq(true));
        summary.put("canResumeCharacterLimit",true);
        when(files.extractInBackground(eq(40L),eq(false),eq(true),any(),eq(true)))
                .thenReturn(Map.of("textStatus","ok","pendingUpgrade",true,"scanProgress",summary));
        assertEquals("partial",completed(jobs.startExtraction(40L,true,true).get("id").toString(),"extraction").get("status"));
        verify(files,times(2)).extractInBackground(eq(40L),eq(false),eq(true),any(),eq(true));
    }

    @Test void ordinaryActiveResumeCannotSilentlyAcceptAnExplicitForcedRequest() throws Exception {
        var started=new CountDownLatch(1); var release=new CountDownLatch(1);
        when(files.extractInBackground(eq(41L),eq(false),eq(true),any())).thenAnswer(call -> {
            started.countDown(); assertTrue(release.await(5,TimeUnit.SECONDS));
            return Map.of("textStatus","ok","noProgress",true);
        });
        String id=jobs.startExtraction(41L,true).get("id").toString();
        try {
            assertTrue(started.await(5,TimeUnit.SECONDS));
            assertThrows(IllegalArgumentException.class,() -> jobs.startExtraction(41L,true,true));
            assertEquals(id,jobs.startExtraction(41L,true).get("id"));
        } finally { release.countDown(); }
        completed(id,"extraction");
        verify(files,never()).extractInBackground(eq(41L),anyBoolean(),anyBoolean(),any(),eq(true));
    }

    @Test void unsupportedFileAndExtractionFailureHaveDistinctOutcomes() throws Exception {
        when(files.extractInBackground(eq(24L),eq(false),eq(false),any())).thenReturn(
                Map.of("textStatus","unsupported","textError","暂不支持此格式"));
        when(files.extractInBackground(eq(25L),eq(false),eq(false),any())).thenReturn(
                Map.of("textStatus","failed","textError","无法读取文档"));
        var unsupported=completed(jobs.startExtraction(24L,false).get("id").toString(),"extraction");
        var failed=completed(jobs.startExtraction(25L,false).get("id").toString(),"extraction");
        assertEquals("completed",unsupported.get("status"));
        assertEquals("暂不支持此格式",unsupported.get("stage"));
        assertEquals("failed",failed.get("status"));
        assertEquals("无法读取文档",failed.get("error"));
        verifyNoInteractions(vectors);
    }

    @Test void staleRunningRecordIsInterruptedAndNeverSilentlyResumesModelCalls() throws Exception {
        String id="11111111-1111-1111-1111-111111111111";
        Path directory=work.resolve("uploads/.derived/document-jobs"); Files.createDirectories(directory);
        new com.fasterxml.jackson.databind.ObjectMapper().writeValue(directory.resolve(id+".json").toFile(),
                Map.of("id",id,"kind","media","status","running","done",0,"total",1));
        assertEquals("failed",jobs.status(id,"media").get("status"));
        var pages=Map.of("stage","extracting","completedPages",9,"totalPages",431,"currentPage",10);
        new com.fasterxml.jackson.databind.ObjectMapper().writeValue(directory.resolve(id+".json").toFile(),
                Map.of("id",id,"kind","extraction","status","running","pageProgress",pages));
        var interrupted=jobs.status(id,"extraction");
        assertEquals("failed",interrupted.get("status"));
        assertEquals(pages,interrupted.get("pageProgress"));
        verifyNoInteractions(files,vectors);
        assertThrows(IllegalArgumentException.class,()->jobs.status("../invalid","media"));
    }
}
