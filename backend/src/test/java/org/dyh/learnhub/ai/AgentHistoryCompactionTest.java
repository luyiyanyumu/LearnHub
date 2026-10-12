package org.dyh.learnhub.ai;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.dyh.learnhub.entity.AgentEvent;
import org.dyh.learnhub.service.AgentSessionService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Synthetic history and model responses; no live model or database. */
@ExtendWith(MockitoExtension.class)
class AgentHistoryCompactionTest {
    private static final String SESSION = "synthetic-history-compaction";
    private static final String PREVIOUS = "合成前情：保留既定方案与约束。";
    private final ObjectMapper json = new ObjectMapper();
    private final List<AgentEvent> pending = new ArrayList<>();
    @Mock DeepSeekClient client;
    @Mock ModelRouting routing;
    @Mock AgentSessionService sessions;
    @InjectMocks AgentService agent;

    @BeforeEach void configure() {
        ReflectionTestUtils.setField(agent, "objectMapper", json);
        for (long id = 1; id <= AgentSessionService.COMPACT_MIN_EVENTS; id++) {
            AgentEvent event = new AgentEvent();
            event.setId(id);
            event.setRole(id % 2 == 1 ? "user" : "assistant");
            event.setContent("合成事件 " + id + "：必须保留的独立事实。");
            pending.add(event);
        }
        when(sessions.pendingCompaction(SESSION, AgentSessionService.PROJECTION_ROUNDS)).thenReturn(pending);
        when(sessions.latestSummary(SESSION)).thenReturn(PREVIOUS);
        when(routing.forTask(ModelRouting.TASK_CHAT)).thenReturn(new ModelRouting.ModelTarget(
                "synthetic", "synthetic", "http://unused.invalid", "dummy", "mock", true));
    }

    @Test void nonEmptyButTruncatedSummaryFallsBackBeforeAdvancingTheCoverageWatermark() throws Exception {
        respond("只有开头的合成摘要，后面的事件还未覆盖", "length");

        compact();

        ArgumentCaptor<String> saved = ArgumentCaptor.forClass(String.class);
        verify(sessions).appendSummary(eq(SESSION), saved.capture(), eq(pending.getLast().getId()));
        assertEquals(AgentService.fallbackDigest(PREVIOUS, pending), saved.getValue());
        assertTrue(saved.getValue().startsWith(PREVIOUS));
        for (AgentEvent event : pending) assertTrue(saved.getValue().contains(event.getContent()));
        assertFalse(saved.getValue().contains("只有开头的合成摘要"));
        verifyModelWasCalled();
    }

    @Test void completeNonEmptySummaryIsSavedWithTheExactCoveredEventId() throws Exception {
        String complete = "完整合成摘要：已有约束及八条新事件全部归纳完毕。";
        respond(complete, "stop");

        compact();

        verify(sessions).appendSummary(SESSION, complete, pending.getLast().getId());
        verifyModelWasCalled();
    }

    private void respond(String text, String finishReason) throws Exception {
        when(client.chatFull(anyList(), nullable(List.class), anyString(), anyString(), anyString(),
                anyInt(), anyDouble(), nullable(String.class), nullable(String.class), any(Duration.class)))
                .thenReturn(new DeepSeekClient.ChatResult(
                        json.createObjectNode().put("role", "assistant").put("content", text), finishReason, 50, 50, 0));
    }

    private void compact() {
        ReflectionTestUtils.invokeMethod(agent, "compactIfNeeded", SESSION, System.currentTimeMillis() + 270_000);
    }

    private void verifyModelWasCalled() throws Exception {
        verify(client).chatFull(anyList(), nullable(List.class), anyString(), anyString(), anyString(),
                anyInt(), anyDouble(), nullable(String.class), nullable(String.class), any(Duration.class));
    }
}
