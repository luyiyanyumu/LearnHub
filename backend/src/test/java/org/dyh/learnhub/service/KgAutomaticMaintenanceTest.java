package org.dyh.learnhub.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.dyh.learnhub.ai.DeepSeekClient;
import org.dyh.learnhub.ai.ModelRouting;
import org.dyh.learnhub.common.PageResult;
import org.dyh.learnhub.entity.KgEdge;
import org.dyh.learnhub.mapper.KgEdgeMapper;
import org.dyh.learnhub.mapper.KgMapper;
import org.dyh.learnhub.vo.NoteVO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.SimpleTransactionStatus;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** In-memory transactional workspace and synthetic model responses; no database or network. */
class KgAutomaticMaintenanceTest {
    private static final String VALID = "{\"edges\":[{\"a\":\"note:91001\",\"b\":\"note:91002\",\"relation\":\"related\","
            + "\"reason\":\"同一合成主题\",\"weight\":0.8}]}";
    private final NoteService notes = mock(NoteService.class);
    private final QuickRefService refs = mock(QuickRefService.class);
    private final FileStorageService files = mock(FileStorageService.class);
    private final KgEdgeMapper edges = mock(KgEdgeMapper.class);
    private final DeepSeekClient client = mock(DeepSeekClient.class);
    private final ModelRouting routing = mock(ModelRouting.class);
    private final ObjectMapper json = new ObjectMapper();
    private final MemoryTransactions transactions = new MemoryTransactions();
    private KgService service;
    private boolean failWrite;

    @BeforeEach void setup() {
        service = new KgService(notes, refs, mock(CategoryService.class), files,
                edges, mock(KgMapper.class), client, json, routing);
        service.setTransactionManager(transactions);
        when(routing.forTask(ModelRouting.TASK_GRAPH)).thenReturn(new ModelRouting.ModelTarget(
                "synthetic-graph", "synthetic", "https://model.example.invalid/v1", "", "synthetic-model", true));
        when(notes.page(null, null, null, 1, 200)).thenReturn(PageResult.of(2, List.of(note(91001L), note(91002L))));
        when(refs.list(null, null)).thenReturn(List.of());
        when(files.retrievalScan(anyInt())).thenReturn(List.of());
        transactions.committed.add(edge(1L, "llm", "旧合成关联"));
        transactions.committed.add(edge(2L, "user", "用户合成关联"));
        when(edges.deleteByOrigin(anyString())).thenAnswer(call -> {
            List<KgEdge> working = transactions.active();
            int count = working.size();
            String origin = call.getArgument(0);
            working.removeIf(edge -> origin.equals(edge.getOrigin()));
            return count - working.size();
        });
        when(edges.insert(any(KgEdge.class))).thenAnswer(call -> {
            transactions.active().add(call.getArgument(0));
            if (failWrite) throw new IllegalStateException("synthetic write failure");
            return 1;
        });
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "not-json", "{}", "{\"edges\":{}}", "{\"edges\":[null]}",
            "{\"edges\":[]} {\"edges\":[]}", "{\"edges\":[]} trailing"})
    void automaticSemanticMaintenanceRejectsMalformedJsonBeforeDeletingOldEdges(String body) throws Exception {
        respond(body, "stop");
        assertThrows(IllegalStateException.class, service::maintainSemantic);
        assertOldEdgesIntact();
        verify(edges, never()).deleteByOrigin(anyString());
        verify(edges, never()).insert(any(KgEdge.class));
        assertEquals(0, transactions.started);
        verifyFullCall();
    }

    @Test void aTruncatedSemanticResponseCannotDeleteExistingEdgesEvenIfItsVisibleJsonIsValid() throws Exception {
        respond(VALID, "length");
        assertThrows(IllegalStateException.class, service::maintainSemantic);
        assertOldEdgesIntact();
        verify(edges, never()).deleteByOrigin(anyString());
        verify(edges, never()).insert(any(KgEdge.class));
        assertEquals(0, transactions.started);
        verifyFullCall();
    }

    @Test void fullyInvalidSourceReferencesDoNotEraseThePreviousSemanticGraph() throws Exception {
        respond(VALID.replace("note:91001", "note:99999"), "stop");
        assertThrows(IllegalStateException.class, service::maintainSemantic);
        assertOldEdgesIntact();
        verify(edges, never()).deleteByOrigin(anyString());
        assertEquals(0, transactions.started);
    }

    @Test void validReplacementDeletesAndInsertsInsideOneTransactionWhileKeepingUserEdges() throws Exception {
        respond(VALID, "stop");
        Map<String, Object> result = service.maintainSemantic();
        assertEquals(1, result.get("edges"));
        assertEquals(1, transactions.started);
        assertEquals(1, transactions.commits);
        assertEquals(0, transactions.rollbacks);
        assertNull(transactions.working);
        assertEquals(2, transactions.committed.size());
        assertTrue(transactions.committed.stream().anyMatch(edge -> "user".equals(edge.getOrigin())
                && "用户合成关联".equals(edge.getReason())));
        assertFalse(transactions.committed.stream().anyMatch(edge -> "旧合成关联".equals(edge.getReason())));
        KgEdge accepted = transactions.committed.stream().filter(edge -> "llm".equals(edge.getOrigin())).findFirst().orElseThrow();
        assertEquals(91001L, accepted.getSourceId().longValue());
        assertEquals(91002L, accepted.getTargetId().longValue());
        assertEquals("同一合成主题", accepted.getReason());
        var order = inOrder(edges);
        order.verify(edges).deleteByOrigin("llm");
        order.verify(edges).insert(any(KgEdge.class));
        verifyFullCall();
    }

    @Test void insertionFailureRollsBackBothTheDeleteAndAnyPartialInsert() throws Exception {
        respond(VALID, "stop");
        failWrite = true;
        assertThrows(IllegalStateException.class, service::maintainSemantic);
        assertEquals(1, transactions.started);
        assertEquals(0, transactions.commits);
        assertEquals(1, transactions.rollbacks);
        assertNull(transactions.working);
        assertOldEdgesIntact();
        verify(edges).deleteByOrigin("llm");
        verify(edges).insert(any(KgEdge.class));
    }

    @Test void strictMaintenanceCannotReplaceOldEdgesWithoutATransactionManager() throws Exception {
        respond(VALID, "stop");
        service.setTransactionManager(null);
        assertThrows(IllegalStateException.class, service::maintainSemantic);
        assertOldEdgesIntact();
        verify(edges, never()).deleteByOrigin(anyString());
        verify(edges, never()).insert(any(KgEdge.class));
    }

    @Test void aFileOnlyLibraryParticipatesInAutomaticSemanticAssociations() throws Exception {
        when(notes.page(null, null, null, 1, 200)).thenReturn(PageResult.of(0, List.of()));
        when(files.retrievalScan(anyInt())).thenReturn(List.of(
                Map.of("id", 92001L, "originName", "合成资料一.txt", "summary", "合成主题", "text", "合成原文一"),
                Map.of("id", 92002L, "originName", "合成资料二.txt", "summary", "合成主题", "text", "合成原文二")));
        respond(VALID.replace("note:91001", "file:92001").replace("note:91002", "file:92002"), "stop");
        Map<String, Object> result = service.maintainSemantic();
        assertEquals(2, result.get("items"));
        assertEquals(1, result.get("edges"));
        KgEdge accepted = transactions.committed.stream().filter(edge -> "llm".equals(edge.getOrigin())).findFirst().orElseThrow();
        assertEquals("file", accepted.getSourceType());
        assertEquals("file", accepted.getTargetType());
        assertEquals(92001L, accepted.getSourceId().longValue());
        assertEquals(92002L, accepted.getTargetId().longValue());
        assertEquals(1, transactions.commits);
        verifyFullCall();
    }

    @Test void deletingTheLastSourceClearsOnlyLlmEdgesInATransactionWithoutCallingAModel() {
        when(notes.page(null, null, null, 1, 200)).thenReturn(PageResult.of(0, List.of()));
        Map<String, Object> result = service.maintainSemantic();
        assertEquals(0, result.get("items"));
        assertEquals(0, result.get("edges"));
        assertEquals(1, transactions.started);
        assertEquals(1, transactions.commits);
        assertEquals(List.of("用户合成关联"), transactions.committed.stream().map(KgEdge::getReason).toList());
        verify(edges).deleteByOrigin("llm");
        verify(edges, never()).insert(any(KgEdge.class));
        verifyNoInteractions(client, routing);
    }

    private void respond(String body, String finishReason) throws Exception {
        when(client.chatFull(anyList(), isNull(), anyString(), nullable(String.class), anyString(),
                anyInt(), anyDouble(), nullable(String.class), nullable(String.class), any(Duration.class)))
                .thenReturn(new DeepSeekClient.ChatResult(json.createObjectNode().put("content", body), finishReason, 12, 20, 0));
    }

    private void verifyFullCall() throws Exception {
        verify(client).chatFull(anyList(), isNull(), anyString(), nullable(String.class), anyString(),
                anyInt(), anyDouble(), eq("disabled"), isNull(), any(Duration.class));
        verify(client, never()).chat(anyList(), nullable(List.class), anyString(), nullable(String.class), anyString(),
                anyInt(), anyDouble(), nullable(String.class), nullable(String.class), any(Duration.class));
    }

    private void assertOldEdgesIntact() {
        assertEquals(List.of("旧合成关联", "用户合成关联"), transactions.committed.stream().map(KgEdge::getReason).toList());
    }

    private static NoteVO note(long id) {
        NoteVO note = new NoteVO();
        note.setId(id); note.setTitle("合成主题 " + id); note.setSummary("用于自动关联事务回归的合成摘要。");
        return note;
    }

    private static KgEdge edge(long id, String origin, String reason) {
        KgEdge edge = new KgEdge();
        edge.setId(id); edge.setOrigin(origin); edge.setReason(reason);
        return edge;
    }

    /** Writes touch an isolated workspace; only commit replaces the visible synthetic rows. */
    private static final class MemoryTransactions implements PlatformTransactionManager {
        final List<KgEdge> committed = new ArrayList<>();
        List<KgEdge> working;
        int started, commits, rollbacks;
        public TransactionStatus getTransaction(TransactionDefinition definition) {
            assertNull(working, "不得开启嵌套替换事务");
            working = new ArrayList<>(committed); started++;
            return new SimpleTransactionStatus();
        }
        List<KgEdge> active() {
            assertNotNull(working, "删除与新增都必须在事务内");
            return working;
        }
        public void commit(TransactionStatus status) {
            committed.clear(); committed.addAll(active()); working = null; commits++;
        }
        public void rollback(TransactionStatus status) { assertNotNull(working); working = null; rollbacks++; }
    }
}
