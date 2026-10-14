package org.dyh.learnhub.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.dyh.learnhub.entity.AgentEvent;
import org.dyh.learnhub.entity.AgentPendingAction;
import org.dyh.learnhub.mapper.AgentEventMapper;
import org.dyh.learnhub.mapper.AgentPendingActionMapper;
import org.dyh.learnhub.mapper.AgentSessionMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class AgentSessionProgressTest {
    private final AgentEventMapper events = mock(AgentEventMapper.class);
    private final AgentPendingActionMapper actions = mock(AgentPendingActionMapper.class);
    private final AgentSessionService sessions = new AgentSessionService(mock(AgentSessionMapper.class), events,
            actions, mock(LearningActivityService.class));
    private final ObjectMapper json = new ObjectMapper();

    @BeforeEach void initializeActionQueryMetadata() {
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), "synthetic-action-progress"),
                AgentPendingAction.class);
    }

    @Test void completedSnapshotRestoresOntoItsAnswerWithoutCreatingExtraMessages() throws Exception {
        when(events.selectList(any())).thenReturn(List.of(event("user", "合成问题"), event("tool", "读取原文"),
                event("assistant", "合成回答"), event("progress", snapshot("completed")), event("tool", "已确认写入")));
        var messages = sessions.messagesForDisplay("synthetic-session");
        assertEquals(2, messages.size());
        assertEquals("合成回答", messages.get(1).get("content"));
        assertEquals(List.of("读取原文", "已确认写入"), messages.get(1).get("events"));
        assertEquals(120L, messages.get(1).get("elapsedMs"));
        assertEquals("completed", rows(messages.get(1)).getFirst().get("status"));
    }

    @Test void failedRoundWithoutAnswerRemainsVisibleBeforeTheNextQuestion() throws Exception {
        when(events.selectList(any())).thenReturn(List.of(event("user", "合成问题一"), event("tool", "已准备确认预览"),
                event("progress", snapshot("error")), event("user", "合成问题二"), event("assistant", "合成回答二")));
        var messages = sessions.messagesForDisplay("synthetic-session");
        assertEquals(4, messages.size());
        assertEquals("assistant", messages.get(1).get("role"));
        assertEquals("", messages.get(1).get("content"));
        assertEquals("error", rows(messages.get(1)).getFirst().get("status"));
        assertTrue(rows(messages.get(3)).isEmpty());
    }

    @Test void failedLastRoundRestoresItsTraceAndCorruptRowsDoNotHideHistory() throws Exception {
        when(events.selectList(any())).thenReturn(List.of(event("user", "合成问题一"), event("assistant", "合成回答一"),
                event("progress", "{bad"), event("user", "合成问题二"), event("progress", snapshot("error"))));
        var messages = sessions.messagesForDisplay("synthetic-session");
        assertEquals(4, messages.size());
        assertTrue(rows(messages.get(1)).isEmpty());
        assertEquals("error", rows(messages.get(3)).getFirst().get("status"));
    }

    @Test void snapshotDecoderOnlyExposesTheSupportedDisplayFields() throws Exception {
        String payload = json.writeValueAsString(Map.of("progress", List.of(Map.of("id", "step-1", "kind", "tool",
                "status", "completed", "title", "读取笔记", "arguments", "synthetic-secret-input",
                "rawReasoning", "synthetic-hidden-prompt", "elapsedMs", -1)), "elapsedMs", -2));
        when(events.selectList(any())).thenReturn(List.of(event("user", "合成问题"), event("progress", payload)));
        var messages = sessions.messagesForDisplay("synthetic-session");
        var row = rows(messages.get(1)).getFirst();
        assertFalse(row.containsKey("arguments"));
        assertFalse(row.containsKey("rawReasoning"));
        assertEquals(0L, row.get("elapsedMs"));
        assertEquals(0L, messages.get(1).get("elapsedMs"));
    }

    @Test void resolvedActionsUpdateBothParentAndChildAndCancelIsNotAnExecutionError() throws Exception {
        String payload = json.writeValueAsString(Map.of("progress", List.of(waiting("parent", 101L), waiting("child", 101L),
                waiting("cancel", 102L), waiting("failed", 103L), waiting("other-session", 104L))));
        when(events.selectList(any())).thenReturn(List.of(event("user", "合成问题"), event("assistant", "合成回答"), event("progress", payload)));
        AgentPendingAction other = action(104L, "approved"); other.setSessionId("different-synthetic-session");
        when(actions.selectBatchIds(any())).thenReturn(List.of(action(101L, "approved"), action(102L, "rejected"), action(103L, "failed"), other));
        var steps = rows(sessions.messagesForDisplay("synthetic-session").get(1));
        assertEquals(List.of("completed", "completed", "completed", "error", "waiting"), steps.stream().map(row -> row.get("status")).toList());
        assertEquals("已取消，本项未执行", steps.get(2).get("detail"));
    }

    @Test void executingAndPendingActionsRecoverAuthoritativeStatesWithoutWritingHistory() throws Exception {
        String payload = json.writeValueAsString(Map.of("progress", List.of(waiting("parent", 101L), waiting("child", 101L),
                Map.of("id", "pending", "kind", "tool", "status", "running", "title", "合成操作", "actionId", 102L),
                Map.of("id", "finished", "kind", "tool", "status", "running", "title", "合成操作", "actionId", 103L),
                waiting("other-session", 104L), waiting("unknown-state", 105L))));
        AgentEvent saved = event("progress", payload);
        when(events.selectList(any())).thenReturn(List.of(event("user", "合成问题"), event("assistant", "合成回答"), saved));
        AgentPendingAction other = action(104L, "executing"); other.setSessionId("different-synthetic-session");
        when(actions.selectBatchIds(any())).thenReturn(List.of(action(101L, "executing"), action(102L, "pending"),
                action(103L, "approved"), other, action(105L, "unknown")));

        var steps = rows(sessions.messagesForDisplay("synthetic-session").get(1));
        assertEquals(List.of("running", "running", "waiting", "completed", "waiting", "waiting"),
                steps.stream().map(row -> row.get("status")).toList());
        assertEquals("正在执行，请勿重复确认", steps.get(0).get("detail"));
        assertEquals("等待确认执行", steps.get(2).get("detail"));
        assertEquals("已确认执行", steps.get(3).get("detail"));
        assertEquals(payload, saved.getContent());
        verify(actions).selectBatchIds(any());
        verifyNoMoreInteractions(actions);
        verify(events).selectList(any());
        verifyNoMoreInteractions(events);
    }

    @Test @SuppressWarnings("unchecked") void unfinishedActionQueryIncludesExecutingAndBriefExposesStatusWithoutArguments() {
        AgentPendingAction pending = action(101L, "pending");
        pending.setToolName("add_file_from_url"); pending.setSummary("合成文件待确认");
        pending.setArgsJson("{\"url\":\"https://synthetic.invalid/file.pdf\",\"private\":\"synthetic-hidden-args\"}");
        AgentPendingAction executing = action(102L, "executing");
        executing.setToolName("add_file_from_url"); executing.setSummary("合成文件执行中");
        executing.setArgsJson(pending.getArgsJson());
        when(actions.selectList(any())).thenReturn(List.of(pending, executing));

        List<Map<String, Object>> briefs = sessions.pendingActions("synthetic-session").stream()
                .map(AgentSessionService::actionBrief).toList();
        assertEquals(List.of("pending", "executing"), briefs.stream().map(row -> row.get("status")).toList());
        assertEquals(java.util.Set.of("id", "status", "toolName", "summary"), briefs.getFirst().keySet());
        assertFalse(briefs.toString().contains("synthetic-hidden-args"));
        ArgumentCaptor<LambdaQueryWrapper<AgentPendingAction>> query = ArgumentCaptor.forClass(LambdaQueryWrapper.class);
        verify(actions).selectList(query.capture());
        assertTrue(query.getValue().getSqlSegment().contains("status IN"));
        assertTrue(query.getValue().getParamNameValuePairs().containsValue("synthetic-session"));
        assertTrue(query.getValue().getParamNameValuePairs().containsValue("pending"));
        assertTrue(query.getValue().getParamNameValuePairs().containsValue("executing"));
        assertFalse(query.getValue().getParamNameValuePairs().containsValue("approved"));
        verifyNoMoreInteractions(actions);
        verifyNoInteractions(events);
    }

    private Map<String, Object> waiting(String id, long actionId) {
        return Map.of("id", id, "kind", "tool", "status", "waiting", "title", "合成操作", "actionId", actionId);
    }

    private AgentPendingAction action(long id, String status) {
        AgentPendingAction action = new AgentPendingAction();
        action.setId(id); action.setSessionId("synthetic-session"); action.setStatus(status);
        return action;
    }

    private String snapshot(String status) throws Exception {
        return json.writeValueAsString(Map.of("progress", List.of(Map.of("id", "step-1", "kind", "phase",
                "status", status, "title", "合成步骤", "elapsedMs", 100)), "elapsedMs", 120));
    }

    private AgentEvent event(String role, String text) {
        AgentEvent event = new AgentEvent();
        event.setRole(role);
        event.setContent(text);
        return event;
    }

    @SuppressWarnings("unchecked") private List<Map<String, Object>> rows(Map<String, Object> message) {
        return (List<Map<String, Object>>) message.get("progress");
    }
}
