package org.dyh.learnhub.service.rerank;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.dyh.learnhub.ai.DeepSeekClient;
import org.dyh.learnhub.ai.ModelRouting;
import org.dyh.learnhub.service.SettingsService;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class LlmListwiseRerankerTest {
    @Test
    @SuppressWarnings({"rawtypes", "unchecked"})
    void suppliesTitleSectionAndMatchingTailToTheModelWithinTheConfiguredBudget() throws Exception {
        DeepSeekClient client = mock(DeepSeekClient.class);
        ModelRouting routing = mock(ModelRouting.class);
        SettingsService settings = mock(SettingsService.class);
        ObjectMapper mapper = new ObjectMapper();
        when(settings.effective(Reranker.KEY_SNIPPET)).thenReturn("120");
        when(routing.forTask(ModelRouting.TASK_RERANK)).thenReturn(
                new ModelRouting.ModelTarget("test", "test", "http://unused", "dummy", "mock", true));
        when(client.chat(anyList(), isNull(), anyString(), anyString(), anyString(), anyInt(), anyDouble(),
                anyString(), isNull(), any())).thenReturn(mapper.readTree("{\"content\":\"{\\\"order\\\":[1,2,3]}\"}"));
        String snippet = "小节：监听器注册\n" + "前面的其他概念介绍。".repeat(30)
                + "alphaClient 使用 registerHandler 完成注册。";
        LlmListwiseReranker reranker = new LlmListwiseReranker(client, routing, mapper, settings);
        assertEquals(List.of("one", "two", "three"), reranker.rerank("alphaClient registerHandler", List.of(
                new Reranker.Item("one", "应用手册", snippet),
                new Reranker.Item("two", "第二份文档", "无关段落。"),
                new Reranker.Item("three", "第三份文档", "其他概念。"))));

        ArgumentCaptor<List> messages = ArgumentCaptor.forClass(List.class);
        verify(client).chat(messages.capture(), isNull(), anyString(), anyString(), anyString(), anyInt(),
                anyDouble(), anyString(), isNull(), any());
        String user = (String) ((Map) messages.getValue().get(1)).get("content");
        String line = user.lines().filter(value -> value.startsWith("1. ")).findFirst().orElseThrow();
        assertTrue(line.startsWith("1. 应用手册｜小节：监听器注册｜"));
        assertTrue(line.contains("registerHandler"));
        assertTrue(line.substring("1. 应用手册｜".length()).length() <= 120);
    }
}
