package org.dyh.learnhub.service;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.dyh.learnhub.entity.AgentEvent;
import org.dyh.learnhub.mapper.AgentEventMapper;
import org.dyh.learnhub.mapper.AgentPendingActionMapper;
import org.dyh.learnhub.mapper.AgentSessionMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/** Synthetic mapper snapshots only: no database, model, network or actual session content. */
class AgentTaskMemoryTest {
    private static final String SESSION = "synthetic-memory-session-a";
    private static final String OTHER = "synthetic-memory-session-b";
    private final AgentEventMapper events = mock(AgentEventMapper.class);
    private final AgentSessionMapper sessionMapper = mock(AgentSessionMapper.class);
    private final AgentPendingActionMapper actions = mock(AgentPendingActionMapper.class);
    private final LearningActivityService activity = mock(LearningActivityService.class);
    private final AgentSessionService sessions = new AgentSessionService(sessionMapper, events, actions, activity);

    @BeforeEach void initializeEventQueryMetadata() {
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), "synthetic-task-memory"), AgentEvent.class);
    }

    @Test void completeMemoryAppendsOnlyItsHiddenRoleAndPreservesExactText() {
        String content = "\n合成任务：逐批整理全部资料。\n已核对第 1 批，下一批从第 2 批继续。  \n";
        sessions.appendTaskMemory(SESSION, content);
        ArgumentCaptor<AgentEvent> saved = ArgumentCaptor.forClass(AgentEvent.class);
        verify(events).insert(saved.capture());
        assertEquals("task_memory", AgentSessionService.ROLE_TASK_MEMORY);
        assertEquals(SESSION, saved.getValue().getSessionId());
        assertEquals(AgentSessionService.ROLE_TASK_MEMORY, saved.getValue().getRole());
        assertEquals(content, saved.getValue().getContent());
        assertNull(saved.getValue().getToolName());
        assertNull(saved.getValue().getToolCallId(), "Working memory must not invent a summary watermark");
        assertNull(saved.getValue().getTokens());
        assertNull(saved.getValue().getFinishReason());
        verify(sessionMapper).touch(SESSION);
        verifyNoMoreInteractions(events, sessionMapper);
        verifyNoInteractions(actions, activity);
    }

    @Test void blankMemoryDoesNotWriteOrTouchTheSession() {
        for (String content : new String[]{null, "", " ", "\n\t "}) sessions.appendTaskMemory(SESSION, content);
        verifyNoInteractions(events, sessionMapper, actions, activity);
    }

    @Test void maximumLengthIsAcceptedWithoutTruncation() {
        assertEquals(40_000, AgentSessionService.MAX_TASK_MEMORY_CHARS);
        String content = "合".repeat(40_000);
        sessions.appendTaskMemory(SESSION, content);
        ArgumentCaptor<AgentEvent> saved = ArgumentCaptor.forClass(AgentEvent.class);
        verify(events).insert(saved.capture());
        assertEquals(content, saved.getValue().getContent());
        verifyNoInteractions(actions, activity);
    }

    @Test void oversizeMemoryIsRejectedBeforeAnyWrite() {
        assertThrows(IllegalArgumentException.class, () -> sessions.appendTaskMemory(SESSION, "合".repeat(40_001)));
        assertThrows(IllegalArgumentException.class, () -> sessions.appendTaskMemory(SESSION, " ".repeat(40_001)));
        verifyNoInteractions(events, sessionMapper, actions, activity);
    }

    @Test void missingSessionCannotReceiveMemoryAndBlankSessionReadsDoNotQueryStorage() {
        for (String session : new String[]{null, "", " \t"}) {
            assertThrows(IllegalArgumentException.class, () -> sessions.appendTaskMemory(session, "合成事实"));
            assertNull(sessions.latestTaskMemory(session));
        }
        verifyNoInteractions(events, sessionMapper, actions, activity);
    }

    @Test @SuppressWarnings("unchecked") void latestMemoryQueryIsSessionScopedNewestFirstAndLimitedToOne() {
        mockReads(List.of(event(11, SESSION, AgentSessionService.ROLE_TASK_MEMORY, "甲旧记忆"),
                event(12, SESSION, AgentSessionService.ROLE_TASK_MEMORY, "甲最新记忆"),
                event(30, SESSION, AgentSessionService.ROLE_SUMMARY, "甲摘要"),
                event(99, OTHER, AgentSessionService.ROLE_TASK_MEMORY, "乙最新记忆")));
        assertEquals("甲最新记忆", sessions.latestTaskMemory(SESSION));
        assertEquals("乙最新记忆", sessions.latestTaskMemory(OTHER));
        ArgumentCaptor<LambdaQueryWrapper<AgentEvent>> queries = ArgumentCaptor.forClass(LambdaQueryWrapper.class);
        verify(events, times(2)).selectList(queries.capture());
        for (int index = 0; index < 2; index++) {
            var query = queries.getAllValues().get(index);
            assertTrue(query.getSqlSegment().contains("ORDER BY id DESC"));
            assertTrue(query.getSqlSegment().endsWith("LIMIT 1"));
            assertTrue(query.getParamNameValuePairs().containsValue(index == 0 ? SESSION : OTHER));
            assertTrue(query.getParamNameValuePairs().containsValue(AgentSessionService.ROLE_TASK_MEMORY));
            assertFalse(query.getParamNameValuePairs().containsValue(AgentSessionService.ROLE_SUMMARY));
        }
        verifyNoMoreInteractions(events);
        verifyNoInteractions(sessionMapper, actions, activity);
    }

    @Test void noMemoryReturnsNullWithoutFallingBackToAnotherRoleOrSession() {
        mockReads(List.of(event(1, SESSION, AgentSessionService.ROLE_SUMMARY, "合成摘要"),
                event(2, OTHER, AgentSessionService.ROLE_TASK_MEMORY, "其他会话记忆")));
        assertNull(sessions.latestTaskMemory(SESSION));
        verifyNoInteractions(sessionMapper, actions, activity);
    }

    @Test void hiddenMemoryNeverBecomesAVisibleMessageOrToolEvent() {
        mockReads(List.of(event(1, SESSION, AgentSessionService.ROLE_TASK_MEMORY, "synthetic-hidden-facts")));
        assertTrue(sessions.messagesForDisplay(SESSION).isEmpty());
        assertTrue(sessions.projection(SESSION, 8).isEmpty());
        assertTrue(sessions.pendingCompaction(SESSION, 8).isEmpty());
        verifyNoInteractions(sessionMapper, actions, activity);
    }

    @Test void laterTaskMemoryCannotAdvanceTheSummaryWatermarkOrReplaceConversationTurns() {
        List<AgentEvent> stored = new ArrayList<>();
        for (long id = 1; id <= 32; id++) stored.add(event(id, SESSION,
                id % 2 == 1 ? AgentSessionService.ROLE_USER : AgentSessionService.ROLE_ASSISTANT, "合成消息 " + id));
        AgentEvent summary = event(40, SESSION, AgentSessionService.ROLE_SUMMARY, "合成历史摘要");
        summary.setToolCallId("summary-through:8");
        stored.add(summary);
        AgentEvent memory = event(90, SESSION, AgentSessionService.ROLE_TASK_MEMORY, "synthetic-hidden-facts");
        memory.setToolCallId("summary-through:31"); // Even malformed legacy metadata must not count as a summary.
        stored.add(memory);
        stored.add(event(999, OTHER, AgentSessionService.ROLE_TASK_MEMORY, "other-synthetic-hidden-facts"));
        mockReads(stored);

        assertEquals("synthetic-hidden-facts", sessions.latestTaskMemory(SESSION));
        assertEquals("合成历史摘要", sessions.latestSummary(SESSION));
        assertEquals(List.of(9L, 10L, 11L, 12L, 13L, 14L, 15L, 16L),
                sessions.pendingCompaction(SESSION, 8).stream().map(AgentEvent::getId).toList());
        var projected = sessions.projection(SESSION, 8);
        assertEquals(23, projected.size());
        assertEquals(10L, projected.getFirst().getId());
        assertEquals(32L, projected.getLast().getId());
        assertTrue(projected.stream().allMatch(e -> List.of("user", "assistant").contains(e.getRole())));
        var visible = sessions.messagesForDisplay(SESSION);
        assertEquals(32, visible.size());
        assertFalse(visible.toString().contains("synthetic-hidden-facts"));
        assertFalse(visible.toString().contains("合成历史摘要"));
        verifyNoInteractions(sessionMapper, actions, activity);
    }

    private AgentEvent event(long id, String session, String role, String content) {
        AgentEvent event = new AgentEvent();
        event.setId(id); event.setSessionId(session); event.setRole(role); event.setContent(content);
        return event;
    }

    @SuppressWarnings("unchecked") private void mockReads(List<AgentEvent> stored) {
        when(events.selectList(any())).thenAnswer(invocation -> {
            LambdaQueryWrapper<AgentEvent> query = invocation.getArgument(0);
            String sql = query.getSqlSegment();
            Map<String, Object> values = query.getParamNameValuePairs();
            String session = values.containsValue(SESSION) ? SESSION : OTHER;
            List<AgentEvent> selected = stored.stream().filter(e -> session.equals(e.getSessionId())).filter(e -> {
                if (values.containsValue(AgentSessionService.ROLE_TASK_MEMORY)) return AgentSessionService.ROLE_TASK_MEMORY.equals(e.getRole());
                if (values.containsValue(AgentSessionService.ROLE_SUMMARY)) return AgentSessionService.ROLE_SUMMARY.equals(e.getRole());
                if (sql.contains("role IN")) {
                    assertTrue(values.containsValue("user") && values.containsValue("assistant"));
                    assertFalse(values.containsValue(AgentSessionService.ROLE_TASK_MEMORY));
                    return List.of("user", "assistant").contains(e.getRole());
                }
                return true;
            }).sorted(sql.contains("id DESC") ? Comparator.comparing(AgentEvent::getId).reversed()
                    : Comparator.comparing(AgentEvent::getId)).toList();
            return sql.endsWith("LIMIT 1") ? selected.stream().limit(1).toList() : selected;
        });
    }
}
