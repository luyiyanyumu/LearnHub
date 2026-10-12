package org.dyh.learnhub.ai;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class AgentChatProgressTest {
    @Test void updatesReuseIdsAndKeepAlreadyDeliveredEventsImmutable() {
        List<Map<String, Object>> pushed = new ArrayList<>();
        var trace = new AgentChatProgress(new AgentService.ChatListener() {
            @Override public void onProgress(Map<String, Object> item) { pushed.add(item); }
        });
        String id = trace.phase("检索相关知识", "合成来源");
        trace.complete(id, "找到 2 条来源");
        assertEquals(2, pushed.size());
        assertEquals(id, pushed.get(0).get("id"));
        assertEquals(id, pushed.get(1).get("id"));
        assertEquals("running", pushed.get(0).get("status"));
        assertEquals("completed", pushed.get(1).get("status"));
        assertEquals(1, trace.snapshot().size());
        assertEquals("找到 2 条来源", trace.snapshot().getFirst().get("detail"));
        assertThrows(UnsupportedOperationException.class, () -> pushed.getFirst().put("title", "mutated"));
    }

    @Test void nestedToolsRetainParentAndWaitingActionsAreNotMarkedFailed() {
        var trace = new AgentChatProgress(null);
        String parent = trace.tool("adaptive_synthetic", null);
        String child = trace.tool("edit_note", parent);
        trace.finish(child, "waiting", "等待确认");
        trace.finish(parent, "waiting", "等待确认");
        trace.phase("整理回答", null);
        trace.failRunning("合成模型超时");
        assertEquals(parent, trace.snapshot().get(1).get("parentId"));
        assertEquals("waiting", trace.snapshot().get(0).get("status"));
        assertEquals("waiting", trace.snapshot().get(1).get("status"));
        assertEquals("error", trace.snapshot().get(2).get("status"));
    }

    @Test void disconnectedListenerCannotInterruptExecutionOrHistorySnapshot() {
        var trace = new AgentChatProgress(new AgentService.ChatListener() {
            @Override public void onSession(String id) { throw new IllegalStateException("synthetic disconnect"); }
            @Override public void onProgress(Map<String, Object> item) { throw new IllegalStateException("synthetic disconnect"); }
        });
        assertDoesNotThrow(() -> {
            trace.session("synthetic-session");
            trace.userRecorded();
            trace.complete(trace.phase("保存对话", null), "已保存");
        });
        assertEquals("completed", trace.snapshot().getFirst().get("status"));
        assertTrue(trace.canPersist());
    }

    @Test void boundsProgressAndDoesNotAttachAnEarlySetupFailureToThePreviousAnswer() {
        var trace = new AgentChatProgress(null);
        trace.session("synthetic-existing-session");
        assertFalse(trace.canPersist());
        for (int i = 0; i < 200; i++) trace.complete(trace.phase("合成步骤", "x".repeat(500)), "完成");
        assertEquals(160, trace.snapshot().size());
        assertTrue(trace.elapsedMs() >= 0);
    }
}
