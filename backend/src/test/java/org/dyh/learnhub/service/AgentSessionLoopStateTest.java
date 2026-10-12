package org.dyh.learnhub.service;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.dyh.learnhub.entity.AgentEvent;
import org.dyh.learnhub.entity.AgentPendingAction;
import org.dyh.learnhub.mapper.AgentEventMapper;
import org.dyh.learnhub.mapper.AgentPendingActionMapper;
import org.dyh.learnhub.mapper.AgentSessionMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/** Synthetic events and compare-and-set storage only; no model or actual database. */
class AgentSessionLoopStateTest {
    private static final String SESSION = "synthetic-loop-session";
    private final AgentEventMapper events = mock(AgentEventMapper.class);
    private final AgentPendingActionMapper actions = mock(AgentPendingActionMapper.class);
    private final AgentSessionMapper sessionMapper = mock(AgentSessionMapper.class);
    private final LearningActivityService activity = mock(LearningActivityService.class);
    private final AgentSessionService sessions = new AgentSessionService(sessionMapper, events, actions, activity);
    private final AtomicReference<List<AgentEvent>> turns = new AtomicReference<>(List.of());
    private final AtomicReference<AgentEvent> summary = new AtomicReference<>();

    @BeforeEach void initializeLambdaMetadata() {
        MapperBuilderAssistant assistant = new MapperBuilderAssistant(new MybatisConfiguration(), "synthetic-loop-state");
        TableInfoHelper.initTableInfo(assistant, AgentEvent.class);
        TableInfoHelper.initTableInfo(assistant, AgentPendingAction.class);
    }

    @Test void summaryKeepsPlainTextAndRecordsOnlyTheLastActuallyCoveredEvent() {
        sessions.appendSummary(SESSION, "合成前情：尚未完成图表整理。", 8);
        ArgumentCaptor<AgentEvent> saved = ArgumentCaptor.forClass(AgentEvent.class);
        verify(events).insert(saved.capture());
        assertEquals("summary", saved.getValue().getRole());
        assertEquals("合成前情：尚未完成图表整理。", saved.getValue().getContent());
        assertEquals("summary-through:8", saved.getValue().getToolCallId());
        assertNull(saved.getValue().getToolName());
        verify(sessionMapper).touch(SESSION);
        verifyNoInteractions(activity);
    }

    @Test void waitingZeroToSevenEventsStayVisibleAndLaterCompactionNeverSkipsOldWindowEvents() {
        mockEventQueries();
        summary.set(summary(25, 8));
        for (int count = 0; count < AgentSessionService.COMPACT_MIN_EVENTS; count++) {
            turns.set(turnsAfterFirstSummary(count));
            assertTrue(sessions.pendingCompaction(SESSION, 8).isEmpty());
            List<AgentEvent> projected = sessions.projection(SESSION, 8);
            assertEquals(16 + count, projected.size(), "Uncompacted spillover count " + count);
            assertEquals(9L, projected.getFirst().getId());
            assertEquals(turns.get().getLast().getId(), projected.getLast().getId());
        }

        turns.set(turnsAfterFirstSummary(8));
        List<AgentEvent> pending = sessions.pendingCompaction(SESSION, 8);
        assertEquals(List.of(9L, 10L, 11L, 12L, 13L, 14L, 15L, 16L), ids(pending));
        // These events all preceded the first summary's id=25, but were not in that summary.
        summary.set(summary(34, pending.getLast().getId()));
        assertTrue(sessions.pendingCompaction(SESSION, 8).isEmpty());
        assertEquals(16, sessions.projection(SESSION, 8).size());
        assertEquals(17L, sessions.projection(SESSION, 8).getFirst().getId());

        List<AgentEvent> further = new ArrayList<>(turns.get());
        for (long id = 35; id <= 42; id++) further.add(turn(id));
        turns.set(further);
        assertEquals(List.of(17L, 18L, 19L, 20L, 21L, 22L, 23L, 24L), ids(sessions.pendingCompaction(SESSION, 8)));
    }

    @Test void legacyOrCorruptSummaryMarkersDoNotPretendToCoverEveryEarlierMessage() {
        mockEventQueries();
        turns.set(turnsAfterFirstSummary(0));
        AgentEvent legacy = event(25, "summary", "合成旧摘要");
        for (String marker : new String[]{null, "old-tool-call", "summary-through:not-a-number", "summary-through:25", "summary-through:999"}) {
            legacy.setToolCallId(marker);
            summary.set(legacy);
            assertEquals(List.of(1L, 2L, 3L, 4L, 5L, 6L, 7L, 8L), ids(sessions.pendingCompaction(SESSION, 8)));
            assertEquals(23, sessions.projection(SESSION, 8).size(), "Backlog remains bounded before conservative recompaction");
            assertEquals("合成旧摘要", sessions.latestSummary(SESSION));
        }
    }

    @Test void invalidSummaryCannotCreateAFalseCoverageMarker() {
        assertThrows(IllegalArgumentException.class, () -> sessions.appendSummary(SESSION, "合成摘要", 0));
        assertThrows(IllegalArgumentException.class, () -> sessions.appendSummary(SESSION, " ", 8));
        verifyNoInteractions(events, sessionMapper);
    }

    @Test void nextTurnActionContextIsBoundedOmitsArgumentsAndDistinguishesExecutedFromPending() {
        List<AgentPendingAction> stored = new ArrayList<>();
        AgentPendingAction foreign = action(100, "approved"); foreign.setSessionId("other-synthetic-session");
        stored.add(foreign);
        for (int i = 0; i < 11; i++) {
            AgentPendingAction action = action(99 - i, List.of("approved", "pending", "executing", "rejected", "failed").get(i % 5));
            action.setSummary(i == 0 ? "下载 https://synthetic:fake-password@example.test/file?token=fake-token" : "合成修改预览 " + i);
            action.setArgsJson("{\"private_argument\":\"fake-private-content\"}");
            stored.add(action);
        }
        when(actions.selectList(any())).thenAnswer(invocation -> {
            LambdaQueryWrapper<AgentPendingAction> query = invocation.getArgument(0);
            assertTrue(query.getSqlSegment().contains("LIMIT 8"));
            assertFalse(query.getSqlSelect().contains("args_json"), "Do not even load parameter payloads for model context");
            assertTrue(query.getParamNameValuePairs().containsValue(SESSION));
            return stored;
        });
        var context = sessions.actionContext(SESSION);
        assertEquals(8, context.size());
        assertEquals("executed", context.getFirst().get("status"));
        assertEquals("pending", context.get(1).get("status"));
        assertEquals("executing", context.get(2).get("status"));
        assertEquals(java.util.Set.of("id", "tool", "status", "summary"), context.getFirst().keySet());
        assertFalse(context.toString().contains("fake-private-content"));
        assertFalse(context.toString().contains("fake-password"));
        assertFalse(context.toString().contains("fake-token"));
        assertFalse(context.stream().anyMatch(item -> Long.valueOf(100).equals(item.get("id"))));
        assertTrue(sessions.actionContext(" ").isEmpty());
    }

    @Test void twoConcurrentApprovalsCanClaimExactlyOneExecution() throws Exception {
        AtomicReference<String> status = new AtomicReference<>("pending");
        CountDownLatch bothRead = new CountDownLatch(2);
        when(actions.selectById(42L)).thenAnswer(invocation -> {
            AgentPendingAction snapshot = action(42, status.get());
            bothRead.countDown();
            assertTrue(bothRead.await(5, TimeUnit.SECONDS));
            return snapshot;
        });
        mockActionTransitions(status);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var first = executor.submit(() -> tryClaim());
            var second = executor.submit(() -> tryClaim());
            int claimed = (first.get(5, TimeUnit.SECONDS) ? 1 : 0) + (second.get(5, TimeUnit.SECONDS) ? 1 : 0);
            assertEquals(1, claimed);
        }
        assertEquals("executing", status.get());
        verify(actions, times(2)).update(any(AgentPendingAction.class), any(Wrapper.class));
    }

    @Test void approvalAndRejectionCannotOverwriteEachOtherOrTerminalResults() {
        AtomicReference<String> status = new AtomicReference<>("pending");
        when(actions.selectById(42L)).thenAnswer(invocation -> action(42, status.get()));
        mockActionTransitions(status);
        assertEquals("executing", sessions.claimAction(42L).getStatus());
        assertThrows(IllegalStateException.class, () -> sessions.resolveAction(42L, "rejected"));
        sessions.resolveAction(42L, "approved");
        assertEquals("approved", status.get());
        assertThrows(IllegalStateException.class, () -> sessions.resolveAction(42L, "failed"));
        assertThrows(IllegalStateException.class, () -> sessions.claimAction(42L));

        status.set("pending");
        sessions.resolveAction(42L, "rejected");
        assertThrows(IllegalStateException.class, () -> sessions.claimAction(42L));
        assertEquals("rejected", status.get());
        assertThrows(IllegalArgumentException.class, () -> sessions.resolveAction(42L, "pending"));
        verify(actions, never()).updateById(any(AgentPendingAction.class));
    }

    @Test void missingActionsCannotBeClaimedAndFailedExecutionCanOnlyFinishFromExecuting() {
        assertThrows(IllegalArgumentException.class, () -> sessions.claimAction(999L));
        AtomicReference<String> status = new AtomicReference<>("pending");
        mockActionTransitions(status);
        assertThrows(IllegalStateException.class, () -> sessions.resolveAction(42L, "failed"));
        status.set("executing");
        sessions.resolveAction(42L, "failed");
        assertEquals("failed", status.get());
    }

    @Test @SuppressWarnings("unchecked") void savedPlanRowsKeepPendingRunningAndCompletedStates() throws Exception {
        String snapshot = new ObjectMapper().writeValueAsString(Map.of("progress", List.of(
                Map.of("id", "plan-1", "kind", "plan", "status", "pending", "title", "准备合成验证"),
                Map.of("id", "plan-2", "kind", "plan", "status", "running", "title", "检查合成来源"),
                Map.of("id", "plan-3", "kind", "plan", "status", "completed", "title", "读取合成输入"),
                Map.of("id", "plan-4", "kind", "plan", "status", "cancelled", "title", "取消合成旧方案")), "elapsedMs", 12));
        when(events.selectList(any())).thenReturn(List.of(event(1, "user", "合成请求"),
                event(2, "assistant", "合成回复"), event(3, "progress", snapshot)));
        var messages = sessions.messagesForDisplay(SESSION);
        var plan = (List<Map<String, Object>>) messages.getLast().get("progress");
        assertEquals(List.of("pending", "running", "completed", "cancelled"), plan.stream().map(row -> row.get("status")).toList());
        assertTrue(plan.stream().allMatch(row -> "plan".equals(row.get("kind"))));
    }

    private boolean tryClaim() {
        try { sessions.claimAction(42L); return true; }
        catch (IllegalStateException alreadyClaimed) { return false; }
    }

    @SuppressWarnings("unchecked") private void mockActionTransitions(AtomicReference<String> status) {
        when(actions.update(any(AgentPendingAction.class), any(Wrapper.class))).thenAnswer(invocation -> {
            AgentPendingAction update = invocation.getArgument(0);
            LambdaUpdateWrapper<AgentPendingAction> condition = invocation.getArgument(1);
            assertTrue(condition.getSqlSegment().contains("status"));
            assertTrue(condition.getParamNameValuePairs().containsValue(42L));
            String expected = condition.getParamNameValuePairs().containsValue("pending") ? "pending" : "executing";
            if (!"executing".equals(update.getStatus())) assertNotNull(update.getResolvedAt());
            return status.compareAndSet(expected, update.getStatus()) ? 1 : 0;
        });
    }

    private void mockEventQueries() {
        when(events.selectList(any())).thenAnswer(invocation -> {
            LambdaQueryWrapper<AgentEvent> query = invocation.getArgument(0);
            query.getSqlSegment();
            if (query.getParamNameValuePairs().containsValue("summary")) {
                return summary.get() == null ? List.of() : List.of(summary.get());
            }
            return turns.get();
        });
    }

    private List<AgentEvent> turnsAfterFirstSummary(int laterCount) {
        List<AgentEvent> values = new ArrayList<>();
        for (long id = 1; id <= 24; id++) values.add(turn(id));
        for (long id = 26; id < 26 + laterCount; id++) values.add(turn(id));
        return values;
    }

    private AgentEvent turn(long id) { return event(id, id % 2 == 1 ? "user" : "assistant", "合成消息 " + id); }
    private AgentEvent summary(long id, long through) {
        AgentEvent event = event(id, "summary", "合成前情"); event.setToolCallId("summary-through:" + through); return event;
    }
    private AgentEvent event(long id, String role, String text) {
        AgentEvent event = new AgentEvent(); event.setId(id); event.setSessionId(SESSION); event.setRole(role); event.setContent(text); return event;
    }
    private AgentPendingAction action(long id, String status) {
        AgentPendingAction action = new AgentPendingAction(); action.setId(id); action.setSessionId(SESSION);
        action.setStatus(status); action.setToolName("edit_note"); action.setSummary("合成修改预览"); return action;
    }
    private List<Long> ids(List<AgentEvent> values) { return values.stream().map(AgentEvent::getId).toList(); }
}
