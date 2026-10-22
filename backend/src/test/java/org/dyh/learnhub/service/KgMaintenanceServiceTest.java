package org.dyh.learnhub.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.dyh.learnhub.entity.AppSetting;
import org.dyh.learnhub.entity.KgRelation;
import org.dyh.learnhub.mapper.AppSettingMapper;
import org.dyh.learnhub.mapper.KbChunkMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class KgMaintenanceServiceTest {
    private final KbChunkMapper sources = mock(KbChunkMapper.class);
    private final AppSettingMapper settings = mock(AppSettingMapper.class);
    private final TripleExtractor extractor = mock(TripleExtractor.class);
    private final KgGraphService graph = mock(KgGraphService.class);
    private final PlatformTransactionManager transactions = mock(PlatformTransactionManager.class);
    private final Map<Long, Map<String, Object>> notes = new LinkedHashMap<>();
    private final AtomicReference<AppSetting> persisted = new AtomicReference<>();
    private final Map<String, AppSetting> stored = new LinkedHashMap<>();
    private final ObjectMapper json = new ObjectMapper();
    private KgMaintenanceService service;

    @BeforeEach void setup() {
        service = createService();
        when(sources.allNotes()).thenAnswer(call -> new ArrayList<>(notes.values()));
        when(sources.allRefs()).thenReturn(List.of()); when(sources.allFiles()).thenReturn(List.of());
        when(sources.maintenanceNote(anyLong())).thenAnswer(call -> notes.get(call.getArgument(0)));
        when(settings.selectById(anyString())).thenAnswer(call -> stored.get(call.getArgument(0)));
        when(settings.insert(any(AppSetting.class))).thenAnswer(call -> store(call.getArgument(0)));
        when(settings.updateById(any(AppSetting.class))).thenAnswer(call -> store(call.getArgument(0)));
        when(settings.deleteById(anyString())).thenAnswer(call -> stored.remove(call.getArgument(0)) == null ? 0 : 1);
        when(extractor.modelFingerprint()).thenReturn("synthetic-profile\nhttps://model.example/v1\nsynthetic-model");
        when(extractor.extractSourceStrict(anyString(), anyString(), anyString(), anyList()))
                .thenReturn(new TripleExtractor.Result(List.of(), 1, 0, 0, 0, "synthetic-model", false));
        when(graph.nodes()).thenReturn(List.of()); when(graph.relations()).thenReturn(List.of());
        when(graph.replaceSourceSupport(anyString(), anyList(), anyMap(), nullable(String.class))).thenReturn(Set.of());
        when(transactions.getTransaction(any())).thenAnswer(call -> new SimpleTransactionStatus());
    }

    private KgMaintenanceService createService() {
        return new KgMaintenanceService(sources, settings, extractor, graph, json, transactions);
    }
    private int store(AppSetting row) {
        stored.put(row.getSettingKey(), row);
        if (KgMaintenanceService.STATE_KEY.equals(row.getSettingKey())) persisted.set(row);
        return 1;
    }
    private Map<String, Object> note(long id, String body) {
        return Map.of("id", id, "title", "合成主题 " + id, "content", body);
    }
    private void add(long id) { notes.put(id, note(id, "合成正文 " + id + "，仅供自动维护回归使用。")); }

    @Test void boundedPassesEventuallyProcessEverySourcePastFortyAndPersistAcrossRestart() {
        for (long id = 1; id <= 45; id++) add(id);
        Map<String, Object> first = service.maintain(20);
        assertEquals(false, first.get("complete")); assertEquals(20, first.get("processed"));
        assertEquals(25, first.get("remaining"));
        service = createService();
        assertEquals(5, service.maintain(20).get("remaining"));
        assertEquals(true, service.maintain(20).get("complete"));
        ArgumentCaptor<String> citations = ArgumentCaptor.forClass(String.class);
        verify(extractor, times(45)).extractSourceStrict(citations.capture(), anyString(), anyString(), anyList());
        assertEquals(45, Set.copyOf(citations.getAllValues()).size());
        assertTrue(citations.getAllValues().contains("笔记#41"));
        clearInvocations(extractor, graph);
        assertEquals(0, service.maintain(20).get("processed"));
        verify(extractor, never()).extractSourceStrict(anyString(), anyString(), anyString(), anyList());
        verify(graph, never()).replaceSourceSupport(anyString(), anyList(), anyMap(), nullable(String.class));
    }

    @Test void onlyChangedSourceIsReextractedAndModelSwitchInvalidatesAllSourceCheckpoints() {
        add(1); add(2); service.maintain(20); clearInvocations(extractor);
        notes.put(2L, note(2, "更新后的合成正文。"));
        assertEquals(1, service.maintain(20).get("processed"));
        verify(extractor).extractSourceStrict(eq("笔记#2"), anyString(), eq("更新后的合成正文。"), anyList());
        clearInvocations(extractor);
        when(extractor.modelFingerprint()).thenReturn("different-profile-and-model");
        assertEquals(2, service.maintain(20).get("processed"));
        verify(extractor, times(2)).extractSourceStrict(anyString(), anyString(), anyString(), anyList());
    }

    @Test void deletingOrEmptyingASourceWithdrawsItsSupportWithoutCallingTheModel() {
        add(1); service.maintain(20); clearInvocations(extractor, graph);
        notes.put(1L, note(1, " \n "));
        assertEquals(true, service.maintain(3).get("complete"));
        verify(extractor, never()).extractSourceStrict(anyString(), anyString(), anyString(), anyList());
        verify(graph).replaceSourceSupport(eq("笔记#1"), eq(List.of()), eq(Map.of()), isNull());
        assertFalse(checkpoints().has("笔记#1"));
    }

    @Test void deletedLegacySupportWithoutAnyCheckpointIsAlsoCleaned() {
        KgRelation legacy = new KgRelation(); legacy.setOrigin("llm"); legacy.setSources("笔记#91");
        List<KgRelation> rows = new ArrayList<>(List.of(legacy));
        when(graph.relations()).thenAnswer(call -> List.copyOf(rows));
        when(graph.replaceSourceSupport(eq("笔记#91"), anyList(), anyMap(), isNull()))
                .thenAnswer(call -> { rows.clear(); return Set.of(); });
        assertEquals(1, service.maintain(3).get("processed"));
        verify(extractor, never()).extractSourceStrict(anyString(), anyString(), anyString(), anyList());
    }

    @Test void failedExtractionKeepsPreviousGraphAndCheckpoint() {
        add(1); service.maintain(3); var checkpoint = checkpoints();
        notes.put(1L, note(1, "修改过的合成正文。")); clearInvocations(graph, transactions);
        when(extractor.extractSourceStrict(anyString(), anyString(), anyString(), anyList()))
                .thenThrow(new IllegalStateException("合成抽取失败"));
        assertThrows(IllegalStateException.class, () -> service.maintain(3));
        assertEquals(checkpoint, checkpoints());
        verify(graph, never()).replaceSourceSupport(anyString(), anyList(), anyMap(), nullable(String.class));
        verifyNoInteractions(transactions);
    }

    @Test void editsDuringModelCallDoNotWithdrawOldFactsOrAdvanceCheckpoint() {
        add(1);
        when(extractor.extractSourceStrict(anyString(), anyString(), anyString(), anyList())).thenAnswer(call -> {
            notes.put(1L, note(1, "模型生成过程中发生的第二次编辑。"));
            return new TripleExtractor.Result(List.of(), 1, 0, 0, 0, "synthetic", false);
        });
        assertThrows(IllegalStateException.class, () -> service.maintain(3));
        assertTrue(checkpoints().isEmpty());
        verify(graph, never()).replaceSourceSupport(anyString(), anyList(), anyMap(), nullable(String.class));
        verifyNoInteractions(transactions);
    }

    @Test void lockedFinalReadCatchesAnEditAfterThePostModelScanAndRollsBack() {
        add(1);
        when(sources.maintenanceNote(1L)).thenReturn(note(1, "提交前刚编辑过的合成正文。"));
        assertThrows(IllegalStateException.class, () -> service.maintain(3));
        assertTrue(checkpoints().isEmpty());
        verify(graph, never()).replaceSourceSupport(anyString(), anyList(), anyMap(), nullable(String.class));
        verify(transactions).rollback(any());
    }

    @Test void sharedEvidenceRepairIsPersistedForTheNextBoundedPass() {
        add(1); add(2); service.maintain(20);
        notes.remove(1L);
        when(graph.replaceSourceSupport(eq("笔记#1"), anyList(), anyMap(), isNull())).thenReturn(Set.of("笔记#2"));
        assertEquals(false, service.maintain(1).get("complete"));
        service = createService(); clearInvocations(extractor);
        assertEquals(true, service.maintain(1).get("complete"));
        verify(extractor).extractSourceStrict(eq("笔记#2"), anyString(), anyString(), anyList());
    }

    @Test void graphFailureBeforeCheckpointWriteDoesNotAdvanceProgress() {
        add(1);
        when(graph.replaceSourceSupport(anyString(), anyList(), anyMap(), nullable(String.class)))
                .thenThrow(new IllegalStateException("合成存储失败")).thenReturn(Set.of());
        assertThrows(IllegalStateException.class, () -> service.maintain(3));
        assertTrue(checkpoints().isEmpty()); verify(transactions).rollback(any());
        service = createService();
        assertEquals(true, service.maintain(3).get("complete"));
        verify(extractor, times(1)).extractSourceStrict(anyString(), anyString(), anyString(), anyList());
    }

    @Test void oneFailedSourceDoesNotPreventOtherSourcesInTheSameBoundedPass() {
        add(1); add(2); add(3); add(4);
        when(extractor.extractSourceStrict(eq("笔记#1"), anyString(), anyString(), anyList()))
                .thenThrow(new IllegalStateException("合成失败，必须保留待重试"));
        IllegalStateException error = assertThrows(IllegalStateException.class, () -> service.maintain(3));
        assertTrue(error.getMessage().contains("已维护 2 个来源"));
        assertTrue(error.getMessage().contains("笔记#1"));
        assertFalse(checkpoints().has("笔记#1"));
        assertTrue(checkpoints().has("笔记#2")); assertTrue(checkpoints().has("笔记#3"));
        verify(extractor, never()).extractSourceStrict(eq("笔记#4"), anyString(), anyString(), anyList());
        verify(graph, never()).replaceSourceSupport(eq("笔记#1"), anyList(), anyMap(), nullable(String.class));
    }

    @Test void failedSourcesRotateAcrossRestartWithoutBeingMarkedSuccessful() {
        add(1); add(2); add(3); add(4);
        when(extractor.extractSourceStrict(eq("笔记#1"), anyString(), anyString(), anyList()))
                .thenThrow(new IllegalStateException("合成持续故障"));
        assertThrows(IllegalStateException.class, () -> service.maintain(1));
        assertFalse(checkpoints().has("笔记#1"));
        service = createService(); clearInvocations(extractor);
        assertEquals(1, service.maintain(1).get("processed"));
        verify(extractor).extractSourceStrict(eq("笔记#2"), anyString(), anyString(), anyList());
        service = createService();
        assertEquals(1, service.maintain(1).get("processed"));
        assertEquals(1, service.maintain(1).get("processed"));
        assertThrows(IllegalStateException.class, () -> service.maintain(1));
        assertEquals(3, checkpoints().size());
    }

    @Test void oversizedLegacyNumericCitationCannotPoisonTheMaintenanceQueue() {
        KgRelation legacy = new KgRelation(); legacy.setOrigin("llm");
        legacy.setSources("笔记#999999999999999999999999999999999999");
        when(graph.relations()).thenReturn(List.of(legacy));
        add(1);
        assertEquals(true, service.maintain(3).get("complete"));
        assertTrue(checkpoints().has("笔记#1"));
        verify(sources, times(1)).maintenanceNote(1L);
    }

    @Test void longSourcesResumeBoundedModelBatchesAcrossRestartAndOnlyThenReplaceTheGraph() {
        String body = "合成的长来源正文，用于验证模型调用有界且不会重复花费。\n".repeat(1_100);
        notes.put(1L, note(1, body));
        int total = TripleExtractor.splitSource(body, 4000).size();
        assertTrue(total > KgMaintenanceService.MODEL_BATCHES_PER_SOURCE);
        assertEquals(false, service.maintain(1).get("complete"));
        verify(extractor, times(KgMaintenanceService.MODEL_BATCHES_PER_SOURCE))
                .extractSourceStrict(anyString(), anyString(), anyString(), anyList());
        assertFalse(checkpoints().has("笔记#1"));
        verify(graph, never()).replaceSourceSupport(anyString(), anyList(), anyMap(), nullable(String.class));
        service = createService();
        int passes = 1;
        while (!Boolean.TRUE.equals(service.maintain(1).get("complete"))) {
            assertTrue(++passes < 20);
            service = createService();
        }
        verify(extractor, times(total)).extractSourceStrict(anyString(), anyString(), anyString(), anyList());
        assertTrue(checkpoints().has("笔记#1"));
        assertFalse(stored.containsKey(KgMaintenanceService.extractionKey("笔记#1")));
        verify(graph, times(1)).replaceSourceSupport(eq("笔记#1"), anyList(), anyMap(), anyString());
    }

    @Test void failedLaterBatchResumesAfterPreviouslyPaidBatchesInsteadOfStartingTheBookOver() {
        String first = "甲".repeat(3999) + "\n";
        String second = "乙".repeat(3999) + "\n";
        String third = "丙".repeat(3999) + "\n";
        notes.put(1L, note(1, first + second + third));
        TripleExtractor.Result ok = new TripleExtractor.Result(List.of(), 1, 0, 0, 0, "synthetic-model", false);
        when(extractor.extractSourceStrict(eq("笔记#1"), anyString(), eq(second), anyList()))
                .thenThrow(new IllegalStateException("合成的第二批暂时失败")).thenReturn(ok);
        assertThrows(IllegalStateException.class, () -> service.maintain(1));
        assertFalse(checkpoints().has("笔记#1"));
        service = createService();
        assertEquals(true, service.maintain(1).get("complete"));
        verify(extractor, times(1)).extractSourceStrict(eq("笔记#1"), anyString(), eq(first), anyList());
        verify(extractor, times(2)).extractSourceStrict(eq("笔记#1"), anyString(), eq(second), anyList());
        verify(extractor, times(1)).extractSourceStrict(eq("笔记#1"), anyString(), eq(third), anyList());
    }

    @Test void acceptedTriplesSurviveTheStagingRoundTripAndDeduplicateAcrossBatches() {
        notes.put(1L, note(1, ("甲".repeat(3999) + "\n").repeat(4)));
        var batch = new java.util.concurrent.atomic.AtomicInteger();
        when(extractor.extractSourceStrict(anyString(), anyString(), anyString(), anyList())).thenAnswer(call -> {
            String head = List.of("Alpha", "Alpha", "Gamma", "Delta").get(batch.getAndIncrement());
            var triple = new TripleExtractor.Triple(head, "part_of", "Beta", "合成证据。", "笔记#1", 0.9);
            return new TripleExtractor.Result(List.of(triple), 1, 0, 0, 0, "synthetic-model", false);
        });
        assertEquals(false, service.maintain(1).get("complete"));
        service = createService();
        assertEquals(true, service.maintain(1).get("complete"));
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<TripleExtractor.Triple>> triples = ArgumentCaptor.forClass(List.class);
        verify(graph).replaceSourceSupport(eq("笔记#1"), triples.capture(), anyMap(), eq("synthetic-model"));
        assertEquals(List.of("Alpha", "Gamma", "Delta"), triples.getValue().stream().map(TripleExtractor.Triple::head).toList());
        assertTrue(triples.getValue().stream().allMatch(triple -> "笔记#1".equals(triple.cite())
                && "合成证据。".equals(triple.evidence()) && triple.weight() == 0.9));
    }

    @Test void sourceEditInvalidatesSavedPartialExtractionBeforeAnyGraphReplacement() {
        String original = "甲".repeat(3999) + "\n";
        notes.put(1L, note(1, original.repeat(4)));
        assertEquals(false, service.maintain(1).get("complete"));
        verify(graph, never()).replaceSourceSupport(anyString(), anyList(), anyMap(), nullable(String.class));
        notes.put(1L, note(1, "修改后的短合成内容。"));
        service = createService(); clearInvocations(extractor);
        assertEquals(true, service.maintain(1).get("complete"));
        verify(extractor).extractSourceStrict(eq("笔记#1"), anyString(), eq("修改后的短合成内容。"), anyList());
    }

    @Test void deletingAPartiallyReadSourceCleansItsSavedBatchesWithoutCallingTheModel() {
        notes.put(1L, note(1, ("甲".repeat(3999) + "\n").repeat(4)));
        assertEquals(false, service.maintain(1).get("complete"));
        assertTrue(stored.containsKey(KgMaintenanceService.extractionKey("笔记#1")));
        notes.remove(1L); service = createService(); clearInvocations(extractor);
        assertEquals(true, service.maintain(1).get("complete"));
        assertFalse(stored.containsKey(KgMaintenanceService.extractionKey("笔记#1")));
        verify(extractor, never()).extractSourceStrict(anyString(), anyString(), anyString(), anyList());
    }

    @Test void sourceBeyondAtomicHardCeilingFailsBeforeAnyModelCallButDoesNotBlockTheNextSource() {
        notes.put(1L, note(1, "$$\n" + "x".repeat(32_001) + "\n$$\n")); add(2);
        IllegalStateException error = assertThrows(IllegalStateException.class, () -> service.maintain(3));
        assertTrue(error.getCause().getMessage().contains("笔记#1"));
        assertTrue(error.getCause().getMessage().contains("32000"));
        assertFalse(checkpoints().has("笔记#1")); assertTrue(checkpoints().has("笔记#2"));
        verify(extractor, never()).extractSourceStrict(eq("笔记#1"), anyString(), anyString(), anyList());
    }

    private com.fasterxml.jackson.databind.JsonNode checkpoints() {
        try { return json.readTree(persisted.get().getSettingValue()).path("checkpoints"); }
        catch (Exception error) { throw new AssertionError(error); }
    }
}
