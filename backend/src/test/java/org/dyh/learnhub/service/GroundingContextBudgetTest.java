package org.dyh.learnhub.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.dyh.learnhub.ai.DeepSeekClient;
import org.dyh.learnhub.ai.ModelRouting;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class GroundingContextBudgetTest {
    @Test
    void answerBeyondTheBudgetSkipsVerificationWithoutCallingAModel() {
        var client = mock(DeepSeekClient.class);
        var routing = mock(ModelRouting.class);
        var settings = mock(SettingsService.class);
        when(settings.effective(GroundingService.SETTING_GROUNDING)).thenReturn("1");
        String answer = "答".repeat(3001);

        GroundingService.Result result = new GroundingService(client, routing, new ObjectMapper(), settings)
                .check("问题", "原文证据", answer);

        assertFalse(result.checked());
        assertEquals("本轮答案超过校验预算，未进行完整核对", result.note());
        assertEquals(0, result.evidenceChars());
        verifyNoInteractions(client, routing);
    }

    @Test
    @SuppressWarnings("unchecked")
    void exactlyTheAnswerBudgetIsVerifiedWithTheEntireAnswer() throws Exception {
        var client = mock(DeepSeekClient.class);
        var routing = mock(ModelRouting.class);
        var settings = mock(SettingsService.class);
        var mapper = new ObjectMapper();
        when(settings.effective(GroundingService.SETTING_GROUNDING)).thenReturn("1");
        when(routing.forTask(ModelRouting.TASK_GROUNDING)).thenReturn(
                new ModelRouting.ModelTarget("test", "test", "http://test.invalid", "", "test", true));
        String tail = "需要核对的回答末尾断言";
        String answer = "答".repeat(3000 - tail.length()) + tail;
        AtomicReference<String> seen = new AtomicReference<>();
        when(client.chat(anyList(), isNull(), anyString(), anyString(), anyString(), anyInt(), anyDouble(),
                anyString(), isNull(), any(Duration.class))).thenAnswer(invocation -> {
            List<Map<String, String>> messages = invocation.getArgument(0);
            seen.set(messages.get(1).get("content"));
            return mapper.createObjectNode().put("content", "{\"grounded\":true,\"unsupported\":[]}");
        });

        GroundingService.Result result = new GroundingService(client, routing, mapper, settings)
                .check("问题", "原文证据", answer);

        assertTrue(result.checked());
        assertEquals(4, result.evidenceChars());
        assertTrue(seen.get().endsWith("【助手回答】\n" + answer));
    }

    @Test
    void evidenceBeyondTheBudgetSkipsVerificationWithoutCallingAModel() {
        var client = mock(DeepSeekClient.class);
        var routing = mock(ModelRouting.class);
        var settings = mock(SettingsService.class);
        when(settings.effective(GroundingService.SETTING_GROUNDING)).thenReturn("1");
        String evidence = "证".repeat(RetrievalContextService.TOTAL_CHARS + 1);

        GroundingService.Result result = new GroundingService(client, routing, new ObjectMapper(), settings)
                .check("问题", evidence, "答案来自证据末尾");

        assertFalse(result.checked());
        assertEquals("本轮证据超过校验预算，未进行完整核对", result.note());
        assertEquals(0, result.evidenceChars());
        assertEquals(RetrievalContextService.TOTAL_CHARS,
                new GroundingService(client, routing, new ObjectMapper(), settings).status().get("evidenceChars"));
        verifyNoInteractions(client, routing);
    }

    @Test
    @SuppressWarnings("unchecked")
    void exactlyTheBudgetIsVerifiedWithTheEntireEvidence() throws Exception {
        var client = mock(DeepSeekClient.class);
        var routing = mock(ModelRouting.class);
        var settings = mock(SettingsService.class);
        var mapper = new ObjectMapper();
        when(settings.effective(GroundingService.SETTING_GROUNDING)).thenReturn("1");
        when(routing.forTask(ModelRouting.TASK_GROUNDING)).thenReturn(
                new ModelRouting.ModelTarget("test", "test", "http://test.invalid", "", "test", true));
        String tail = "最后的工具正文证据";
        String evidence = "证".repeat(RetrievalContextService.TOTAL_CHARS - tail.length()) + tail;
        AtomicReference<String> seen = new AtomicReference<>();
        when(client.chat(anyList(), isNull(), anyString(), anyString(), anyString(), anyInt(), anyDouble(),
                anyString(), isNull(), any(Duration.class))).thenAnswer(invocation -> {
            List<Map<String, String>> messages = invocation.getArgument(0);
            seen.set(messages.get(1).get("content"));
            return mapper.createObjectNode().put("content", "{\"grounded\":true,\"unsupported\":[]}");
        });

        GroundingService.Result result = new GroundingService(client, routing, mapper, settings)
                .check("问题", evidence, "有依据的答案");

        assertTrue(result.checked());
        assertEquals(RetrievalContextService.TOTAL_CHARS, result.evidenceChars());
        assertTrue(seen.get().contains("【系统检索到的材料】\n" + evidence + "\n\n【助手回答】"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void answerVerificationSeesTheCommunityEvidenceAtTheEndOfTheActualContext() throws Exception {
        var client = mock(DeepSeekClient.class);
        var routing = mock(ModelRouting.class);
        var settings = mock(SettingsService.class);
        var mapper = new ObjectMapper();
        when(settings.effective(GroundingService.SETTING_GROUNDING)).thenReturn("1");
        when(routing.forTask(ModelRouting.TASK_GROUNDING)).thenReturn(
                new ModelRouting.ModelTarget("test", "test", "http://test.invalid", "", "test", true));
        AtomicReference<String> seen = new AtomicReference<>();
        when(client.chat(anyList(), isNull(), anyString(), anyString(), anyString(), anyInt(), anyDouble(),
                anyString(), isNull(), any(Duration.class))).thenAnswer(invocation -> {
            List<Map<String, String>> messages = invocation.getArgument(0);
            seen.set(messages.get(1).get("content"));
            return mapper.readTree("{\"content\":\"{\\\"grounded\\\":true,\\\"unsupported\\\":[],\\\"note\\\":\\\"有依据\\\"}\"}");
        });
        String evidence = "正文材料".repeat(1600) + "\n[社区#3] 最后的摘要证据来自笔记#31。";
        assertTrue(evidence.length() > 5000 && evidence.length() < RetrievalContextService.TOTAL_CHARS);
        new GroundingService(client, routing, mapper, settings).check("整体主题", evidence, "基于社区证据的答案");
        assertNotNull(seen.get());
        assertTrue(seen.get().contains("最后的摘要证据来自笔记#31。"));
    }
}
