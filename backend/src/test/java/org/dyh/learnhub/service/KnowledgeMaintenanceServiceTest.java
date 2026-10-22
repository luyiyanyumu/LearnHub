package org.dyh.learnhub.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.dyh.learnhub.common.KnowledgeChangedEvent;
import org.dyh.learnhub.entity.AppSetting;
import org.dyh.learnhub.mapper.AppSettingMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Fake source fingerprints, time and persistent metadata; never calls real models or a database. */
class KnowledgeMaintenanceServiceTest {
    private static final List<String> ORDER = List.of("index", "wiki", "graph", "documents", "communities", "summaries", "check");
    private final KnowledgeMaintenanceService.Operations operations = mock(KnowledgeMaintenanceService.Operations.class);
    private final AtomicLong now = new AtomicLong();
    private final AtomicReference<String> fingerprint = new AtomicReference<>("synthetic-source-v1");
    private final MemoryStore store = new MemoryStore();
    private KnowledgeMaintenanceService maintenance;

    @BeforeEach void setup() throws Exception {
        when(operations.fingerprint()).thenAnswer(call -> fingerprint.get());
        when(operations.execute(anyString())).thenReturn(new KnowledgeMaintenanceService.StepResult(true, "合成阶段已完成"));
        maintenance = new KnowledgeMaintenanceService(operations, store, now::get);
    }

    @Test void defaultsEnabledAndCompletesAllStagesInTheirFixedOrder() throws Exception {
        maintenance.scan();
        assertEquals(ORDER, executed());
        Map<String, Object> status = maintenance.status();
        assertEquals(true, status.get("enabled"));
        assertEquals(false, status.get("running"));
        assertEquals(false, status.get("pending"));
        assertEquals("idle", status.get("phase"));
        assertFalse(status.get("lastCompletedAt").toString().isBlank());
        assertTrue(stepViews().stream().allMatch(step -> "completed".equals(step.get("status"))));
        var saved = new ObjectMapper().readTree(store.value);
        for (String step : ORDER) assertEquals(fingerprint.get(), saved.path("completed").path(step).asText());
    }

    @Test void applicationReadyStartsBackgroundWorkWithoutAnyPageOrStatusRead() throws Exception {
        CountDownLatch finished = new CountDownLatch(ORDER.size());
        when(operations.execute(anyString())).thenAnswer(call -> {
            finished.countDown();
            return new KnowledgeMaintenanceService.StepResult(true, "合成阶段已完成");
        });
        try {
            maintenance.start();
            assertTrue(finished.await(3, TimeUnit.SECONDS));
            verify(operations).execute("check");
        } finally { maintenance.close(); }
    }

    @Test void statusAndEnabledReadsNeverRunOperationsOrWriteStorage() {
        int saves = store.saves;
        for (int i = 0; i < 10; i++) {
            assertTrue(maintenance.enabled());
            Map<String, Object> status = maintenance.status();
            assertEquals(Set.of("enabled", "running", "pending", "phase", "phaseLabel", "message",
                    "lastStartedAt", "lastCompletedAt", "nextRunAt", "lastError", "steps"), status.keySet());
            assertEquals(ORDER, stepViews().stream().map(step -> step.get("key")).toList());
        }
        assertEquals(saves, store.saves);
        verifyNoInteractions(operations);
    }

    @Test void completedFingerprintsSurviveRestartAndUnchangedSourcesNeverRepeatModelWork() throws Exception {
        maintenance.scan();
        String completedAt = maintenance.status().get("lastCompletedAt").toString();
        clearInvocations(operations);
        now.set(50_000);
        KnowledgeMaintenanceService restarted = new KnowledgeMaintenanceService(operations, store, now::get);
        restarted.scan();
        verify(operations, never()).execute(anyString());
        assertEquals(completedAt, restarted.status().get("lastCompletedAt"));
        assertEquals(false, restarted.status().get("pending"));
        fingerprint.set("synthetic-source-v2");
        now.addAndGet(KnowledgeMaintenanceService.SCAN_MS);
        restarted.scan();
        assertEquals(ORDER, executed());
    }

    @Test void partialBatchDoesNotWriteASuccessWatermarkAndOtherStagesStillGetTheirTurn() throws Exception {
        when(operations.execute("index")).thenReturn(new KnowledgeMaintenanceService.StepResult(false, "合成批次尚有余项"),
                new KnowledgeMaintenanceService.StepResult(true, "合成索引已完成"));
        maintenance.scan();
        assertEquals(ORDER, executed());
        assertEquals("pending", step("index").get("status"));
        assertEquals("completed", step("wiki").get("status"));
        assertEquals("", maintenance.status().get("lastCompletedAt"));
        assertFalse(new ObjectMapper().readTree(store.value).path("completed").has("index"));
        now.addAndGet(KnowledgeMaintenanceService.SCAN_MS);
        maintenance.scan();
        verify(operations, times(2)).execute("index");
        for (String key : ORDER.subList(1, ORDER.size())) verify(operations).execute(key);
        assertEquals(false, maintenance.status().get("pending"));
    }

    @Test void failedStageRetriesWithBackoffWhileSuccessfulStagesAreNotRepeated() throws Exception {
        when(operations.execute("index")).thenThrow(new IllegalStateException("synthetic-secret"))
                .thenThrow(new IllegalStateException("synthetic-secret"))
                .thenReturn(new KnowledgeMaintenanceService.StepResult(true, "合成索引已完成"));
        maintenance.scan();
        assertEquals("error", step("index").get("status"));
        assertEquals("completed", step("wiki").get("status"));
        assertFalse(maintenance.status().toString().contains("synthetic-secret"));
        now.set(29_999);
        maintenance.scan();
        verify(operations).execute("index");
        now.set(30_000);
        maintenance.scan();
        verify(operations, times(2)).execute("index");
        now.set(89_999);
        maintenance.scan();
        verify(operations, times(2)).execute("index");
        now.set(90_000);
        maintenance.scan();
        verify(operations, times(3)).execute("index");
        for (String key : ORDER.subList(1, ORDER.size())) verify(operations).execute(key);
        assertEquals(false, maintenance.status().get("pending"));
        assertEquals("", maintenance.status().get("lastError"));
    }

    @Test void graphBatchesMustFinishBeforeCommunitiesAndTheirSummariesCanAcquireWatermarks() throws Exception {
        when(operations.execute("graph")).thenReturn(new KnowledgeMaintenanceService.StepResult(false, "合成图谱尚有余项"),
                new KnowledgeMaintenanceService.StepResult(true, "合成图谱已完成"));
        maintenance.scan();
        assertEquals(List.of("index", "wiki", "graph", "documents", "check"), executed());
        assertEquals("waiting", step("communities").get("status"));
        assertEquals("waiting", step("summaries").get("status"));
        verify(operations, never()).execute("communities");
        verify(operations, never()).execute("summaries");
        var firstState = new ObjectMapper().readTree(store.value).path("completed");
        assertFalse(firstState.has("graph"));
        assertFalse(firstState.has("communities"));
        assertFalse(firstState.has("summaries"));
        assertEquals("", maintenance.status().get("lastCompletedAt"));
        now.set(KnowledgeMaintenanceService.SCAN_MS);
        maintenance.scan();
        verify(operations, times(2)).execute("graph");
        verify(operations).execute("communities");
        verify(operations).execute("summaries");
        assertEquals(false, maintenance.status().get("pending"));
    }

    @Test void aRecoveredStageClearsItsErrorEvenWhileAnotherStageStillHasBatches() throws Exception {
        when(operations.execute("wiki")).thenThrow(new IllegalStateException("没有与该实体相关的有效正文片段，原页面已保留"))
                .thenReturn(new KnowledgeMaintenanceService.StepResult(true, "保留 1 页：缺少相关正文，其余页面已同步"));
        when(operations.execute("graph")).thenReturn(new KnowledgeMaintenanceService.StepResult(false, "图谱还有待办来源"));
        maintenance.scan();
        assertTrue(maintenance.status().get("lastError").toString().contains("有效正文片段"));
        assertEquals("1970-01-01T00:00:30Z", step("wiki").get("nextRetryAt"));
        now.set(30_000);
        maintenance.scan();
        assertEquals("", maintenance.status().get("lastError"));
        assertEquals("", step("wiki").get("error"));
        assertEquals(true, maintenance.status().get("pending"));
        now.set(40_000);
        maintenance.scan();
        assertTrue(step("wiki").get("message").toString().contains("保留 1 页"));
        KnowledgeMaintenanceService restarted = new KnowledgeMaintenanceService(operations, store, now::get);
        assertTrue(restarted.status().get("lastError").toString().isBlank());
        assertTrue(((List<?>) restarted.status().get("steps")).toString().contains("保留 1 页"));
    }

    @Test void explicitRetryBypassesOnlyTheBackoffAndDoesNotRegenerateCompletedStages() throws Exception {
        when(operations.execute("wiki")).thenThrow(new IllegalStateException("知识页生成暂时失败"))
                .thenReturn(new KnowledgeMaintenanceService.StepResult(true, "知识页已恢复"));
        maintenance.scan();
        now.set(1_000);
        maintenance.requestRun();
        maintenance.scan();
        verify(operations, times(2)).execute("wiki");
        verify(operations, times(1)).execute("index");
        verify(operations, times(1)).execute("documents");
        assertEquals(false, maintenance.status().get("pending"));
    }

    @Test void databaseFailuresShowSafeReasonsWithoutSqlOrParameterValues() {
        var duplicate = new java.sql.SQLIntegrityConstraintViolationException(
                "Duplicate entry synthetic-private-value for key synthetic-private-index", "23000", 1062);
        String reason = KnowledgeMaintenanceService.failureReason(new org.springframework.dao.DuplicateKeyException(
                "synthetic-private-statement-and-parameters", duplicate));
        assertTrue(reason.contains("数据库唯一键冲突"));
        assertTrue(reason.contains("1062"));
        assertFalse(reason.contains("synthetic-private"));
        assertTrue(KnowledgeMaintenanceService.failureReason(new java.sql.SQLException("private", "22001", 1406))
                .contains("字段长度不足"));
        assertTrue(KnowledgeMaintenanceService.failureReason(new java.sql.SQLException("private", "40001", 1213))
                .contains("写入锁冲突"));
        assertTrue(KnowledgeMaintenanceService.failureReason(new java.sql.SQLException("private", "HY000", 9999))
                .contains("数据库操作失败"));
    }

    @Test void networkFailuresKeepSafeActionableReasonsThroughNestedCauses() {
        String secret = "synthetic-private-transport-data";
        var handshake = new javax.net.ssl.SSLHandshakeException(secret);
        assertTrue(KnowledgeMaintenanceService.failureReason(new IllegalStateException("图谱来源抽取失败", handshake))
                .contains("TLS 握手失败"));
        assertFalse(KnowledgeMaintenanceService.failureReason(handshake).contains(secret));
        assertTrue(KnowledgeMaintenanceService.failureReason(new javax.net.ssl.SSLException(secret))
                .contains("安全连接中断"));
        assertTrue(KnowledgeMaintenanceService.failureReason(new java.net.ConnectException(secret))
                .contains("无法连接模型服务"));
        assertTrue(KnowledgeMaintenanceService.failureReason(new java.net.UnknownHostException(secret))
                .contains("无法解析模型服务地址"));
        assertTrue(KnowledgeMaintenanceService.failureReason(new java.nio.channels.UnresolvedAddressException())
                .contains("无法解析模型服务地址"));
    }

    @Test void safeDomainCausesAreReadableButProviderCredentialsAndArbitraryBodiesAreNotExposed() throws Exception {
        IllegalStateException failure = new IllegalStateException("图谱来源抽取失败，保留已有关系",
                new IllegalStateException("单个公式或代码块超过图谱抽取批次上限"));
        when(operations.execute("graph")).thenThrow(failure);
        maintenance.scan();
        String visible = maintenance.status().get("lastError").toString();
        assertTrue(visible.contains("单个公式或代码块"));
        assertTrue(step("graph").get("message").toString().contains("30 秒"));
        assertFalse(KnowledgeMaintenanceService.failureReason(new IllegalStateException("synthetic-private-body"))
                .contains("synthetic-private-body"));
        String credential = KnowledgeMaintenanceService.failureReason(new IllegalStateException(
                "模型请求失败 api_key=synthetic-private-key https://user:synthetic-password@example.invalid/v1 sk-synthetic-credential"));
        assertFalse(credential.contains("synthetic-private-key"));
        assertFalse(credential.contains("synthetic-password"));
        assertFalse(credential.contains("sk-synthetic"));
        assertTrue(new ObjectMapper().readTree(store.value).path("steps").path("graph").path("error")
                .asText().contains("单个公式"));
    }

    @Test void restartPreservesStageBackoffAndItsLastErrorWithoutImmediateModelRetries() throws Exception {
        when(operations.execute("graph")).thenThrow(new IllegalStateException("synthetic-private-detail"))
                .thenThrow(new IllegalStateException("synthetic-private-detail"))
                .thenReturn(new KnowledgeMaintenanceService.StepResult(true, "合成图谱已完成"));
        maintenance.scan();
        var saved = new ObjectMapper().readTree(store.value);
        assertEquals(1, saved.path("steps").path("graph").path("failures").asInt());
        assertEquals(30_000, saved.path("steps").path("graph").path("retryAt").asLong());
        assertFalse(saved.path("lastError").asText().isBlank());
        clearInvocations(operations);
        now.set(1_000);
        KnowledgeMaintenanceService restarted = new KnowledgeMaintenanceService(operations, store, now::get);
        assertFalse(restarted.status().get("lastError").toString().isBlank());
        assertFalse(restarted.status().toString().contains("synthetic-private-detail"));
        restarted.scan();
        verify(operations, never()).execute(anyString());
        now.set(30_000);
        restarted.scan();
        verify(operations).execute("graph");
        saved = new ObjectMapper().readTree(store.value);
        assertEquals(2, saved.path("steps").path("graph").path("failures").asInt());
        assertEquals(90_000, saved.path("steps").path("graph").path("retryAt").asLong());
        clearInvocations(operations);
        now.set(31_000);
        KnowledgeMaintenanceService restartedAgain = new KnowledgeMaintenanceService(operations, store, now::get);
        restartedAgain.scan();
        verify(operations, never()).execute(anyString());
        now.set(90_000);
        restartedAgain.scan();
        verify(operations).execute("graph");
        verify(operations).execute("communities");
        verify(operations).execute("summaries");
        assertEquals("", restartedAgain.status().get("lastError"));
        assertEquals("", new ObjectMapper().readTree(store.value).path("lastError").asText());
    }

    @Test void partialWikiBlocksItsDependentCheckButDoesNotBlockRawSourceGraphOrDocuments() throws Exception {
        when(operations.execute("wiki")).thenReturn(new KnowledgeMaintenanceService.StepResult(false, "合成Wiki仍在分批"),
                new KnowledgeMaintenanceService.StepResult(true, "合成Wiki已完成"));
        maintenance.scan();
        assertEquals(List.of("index", "wiki", "graph", "documents"), executed());
        assertEquals("waiting", step("check").get("status"));
        assertEquals("waiting", step("communities").get("status"));
        assertEquals("waiting", step("summaries").get("status"));
        assertEquals("completed", step("graph").get("status"));
        now.set(KnowledgeMaintenanceService.SCAN_MS);
        maintenance.scan();
        verify(operations, times(2)).execute("wiki");
        verify(operations).execute("check");
        verify(operations).execute("communities");
        verify(operations).execute("summaries");
        assertEquals(false, maintenance.status().get("pending"));
    }

    @Test void aLaterChangeEventExtendsDebounceAndCannotBeLost() throws Exception {
        maintenance.changed(event());
        now.set(1_000);
        maintenance.changed(event());
        now.set(2_000);
        maintenance.scan();
        verifyNoInteractions(operations);
        now.set(2_999);
        maintenance.scan();
        verifyNoInteractions(operations);
        now.set(3_000);
        maintenance.scan();
        assertEquals(ORDER, executed());
        assertEquals(false, maintenance.status().get("pending"));
    }

    @Test void sourceChangesDuringTheLastStageCannotMarkTheLibraryComplete() throws Exception {
        when(operations.execute("check")).thenAnswer(call -> {
            fingerprint.set("synthetic-source-v2");
            maintenance.changed(event());
            return new KnowledgeMaintenanceService.StepResult(true, "旧来源检查已结束");
        }).thenReturn(new KnowledgeMaintenanceService.StepResult(true, "新来源检查已结束"));
        maintenance.scan();
        assertEquals(true, maintenance.status().get("pending"));
        assertEquals("", maintenance.status().get("lastCompletedAt"));
        assertFalse(new ObjectMapper().readTree(store.value).path("completed").has("check"));
        now.set(10_000);
        maintenance.scan();
        for (String key : ORDER) verify(operations, times(2)).execute(key);
        assertEquals(false, maintenance.status().get("pending"));
        assertFalse(maintenance.status().get("lastCompletedAt").toString().isBlank());
    }

    @Test void anEventArrivingDuringACompletedPassStillGetsASubsequentFingerprintCheck() throws Exception {
        when(operations.execute("check")).thenAnswer(call -> {
            maintenance.changed(event());
            return new KnowledgeMaintenanceService.StepResult(true, "合成检查已完成");
        });
        maintenance.scan();
        assertEquals(true, maintenance.status().get("pending"));
        assertEquals("", maintenance.status().get("lastCompletedAt"));
        now.set(10_000);
        maintenance.scan();
        for (String key : ORDER) verify(operations).execute(key);
        assertEquals(false, maintenance.status().get("pending"));
        assertFalse(maintenance.status().get("lastCompletedAt").toString().isBlank());
    }

    @Test void disablingDuringWorkFinishesOnlyTheCurrentStepAndResumeUsesItsSavedWatermark() throws Exception {
        when(operations.execute("index")).thenAnswer(call -> {
            maintenance.setEnabled(false);
            return new KnowledgeMaintenanceService.StepResult(true, "合成索引已完成");
        });
        maintenance.scan();
        assertEquals(List.of("index"), executed());
        assertFalse(maintenance.enabled());
        assertEquals("paused", maintenance.status().get("phase"));
        assertEquals("", maintenance.status().get("lastCompletedAt"));
        now.set(10_000);
        maintenance.scan();
        assertEquals(List.of("index"), executed());
        maintenance.setEnabled(true);
        maintenance.scan();
        assertEquals(ORDER, executed());
        assertEquals(false, maintenance.status().get("pending"));
    }

    @Test void aManualRunIsOnlyQueuedAndCanRunOnceWhileAutomaticMaintenanceIsDisabled() throws Exception {
        maintenance.setEnabled(false);
        Map<String, Object> queued = maintenance.requestRun();
        assertEquals(false, queued.get("running"));
        assertEquals(true, queued.get("pending"));
        verifyNoInteractions(operations);
        maintenance.scan();
        assertEquals(ORDER, executed());
        assertFalse(maintenance.enabled());
        clearInvocations(operations);
        now.set(20_000);
        maintenance.scan();
        verifyNoInteractions(operations);
        KnowledgeMaintenanceService restarted = new KnowledgeMaintenanceService(operations, store, now::get);
        assertFalse(restarted.enabled());
    }

    @Test void nestedManualRequestsRemainQueuedAndNeverExecuteConcurrentPasses() throws Exception {
        when(operations.execute("index")).thenAnswer(call -> {
            maintenance.requestRun();
            maintenance.scan();
            return new KnowledgeMaintenanceService.StepResult(true, "合成索引已完成");
        });
        maintenance.requestRun();
        maintenance.scan();
        assertEquals(true, maintenance.status().get("pending"));
        maintenance.scan();
        assertEquals(ORDER, executed());
        assertEquals(false, maintenance.status().get("pending"));
    }

    @Test void explicitGraphEditsHaveADurableRevisionWhileOrdinaryGeneratedOutputCannotSelfTrigger() throws Exception {
        maintenance.scan();
        clearInvocations(operations);
        maintenance.graphChanged();
        var saved = new ObjectMapper().readTree(store.value);
        assertFalse(saved.path("manualGraphRevision").asText().isBlank());
        now.set(KnowledgeMaintenanceService.DEBOUNCE_MS);
        maintenance.scan();
        assertEquals(ORDER, executed());
        clearInvocations(operations);
        now.addAndGet(KnowledgeMaintenanceService.SCAN_MS);
        maintenance.scan();
        verify(operations, never()).execute(anyString());
        KnowledgeMaintenanceService restarted = new KnowledgeMaintenanceService(operations, store, now::get);
        restarted.scan();
        verify(operations, never()).execute(anyString());
        assertEquals(false, restarted.status().get("pending"));
    }

    @Test void graphRevisionPersistenceFailureDoesNotTurnAnAlreadySuccessfulEditIntoAnError() throws Exception {
        AtomicReference<String> saved = new AtomicReference<>();
        java.util.concurrent.atomic.AtomicBoolean failing = new java.util.concurrent.atomic.AtomicBoolean(true);
        KnowledgeMaintenanceService.Store temporarilyUnavailable = new KnowledgeMaintenanceService.Store() {
            public String load() { return saved.get(); }
            public void save(String state) {
                if (failing.getAndSet(false)) throw new IllegalStateException("synthetic-store-unavailable");
                saved.set(state);
            }
        };
        KnowledgeMaintenanceService service = new KnowledgeMaintenanceService(operations, temporarilyUnavailable, now::get);
        assertDoesNotThrow(service::graphChanged);
        assertEquals(true, service.status().get("pending"));
        assertFalse(service.status().get("lastError").toString().isBlank());
        now.set(KnowledgeMaintenanceService.DEBOUNCE_MS);
        service.scan();
        assertFalse(new ObjectMapper().readTree(saved.get()).path("manualGraphRevision").asText().isBlank());
        assertEquals(false, service.status().get("pending"));
    }

    @Test void productionStateUsesOneInternalAppSettingRowWithoutSettingsWhitelist() throws Exception {
        AppSettingMapper mapper = mock(AppSettingMapper.class);
        KnowledgeMaintenanceService service = new KnowledgeMaintenanceService(operations, mapper, new ObjectMapper());
        service.scan();
        var writes = org.mockito.ArgumentCaptor.forClass(AppSetting.class);
        verify(mapper, atLeastOnce()).insert(writes.capture());
        assertTrue(writes.getAllValues().stream().allMatch(row ->
                KnowledgeMaintenanceService.SETTING_KEY.equals(row.getSettingKey())));
        assertTrue(writes.getValue().getSettingValue().contains("completedFingerprint"));
    }

    private List<String> executed() throws Exception {
        var captured = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(operations, atLeastOnce()).execute(captured.capture());
        return captured.getAllValues();
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> stepViews() {
        return (List<Map<String, Object>>) maintenance.status().get("steps");
    }

    private Map<String, Object> step(String key) {
        return stepViews().stream().filter(step -> key.equals(step.get("key"))).findFirst().orElseThrow();
    }

    private static KnowledgeChangedEvent event() { return new KnowledgeChangedEvent(Set.of(900001L), "synthetic-note"); }

    private static final class MemoryStore implements KnowledgeMaintenanceService.Store {
        String value;
        int saves;
        public synchronized String load() { return value; }
        public synchronized void save(String state) { value = state; saves++; }
    }
}
