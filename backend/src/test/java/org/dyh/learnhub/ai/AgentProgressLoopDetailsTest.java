package org.dyh.learnhub.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.dyh.learnhub.dto.AiChatRequest;
import org.dyh.learnhub.service.AgentSessionService;
import org.dyh.learnhub.service.RetrievalContextService;
import org.dyh.learnhub.service.SettingsService;
import org.dyh.learnhub.service.WebPageService;
import org.dyh.learnhub.service.WebService;
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
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Exercises real orchestration, progress SSE, and persisted snapshots with synthetic providers only. */
@ExtendWith(MockitoExtension.class)
class AgentProgressLoopDetailsTest {
    @Mock DeepSeekClient client;
    @Mock ModelRouting routing;
    @Mock SettingsService settings;
    @Mock AgentSessionService sessions;
    @Mock RetrievalContextService retrieval;
    @Mock WebService web;
    @Mock WebPageService pages;
    @InjectMocks AgentService agent;

    @Test void actualLoopPublishesRetrievedSourcesChosenToolsSearchWebsitesAndFetchFailures() throws Exception {
        ObjectMapper json = new ObjectMapper();
        ReflectionTestUtils.setField(agent, "objectMapper", json);
        agent.setWebPageService(pages);
        when(client.isConfigured()).thenReturn(true);
        when(sessions.ensure(isNull(), isNull(), eq("合成联网任务"))).thenReturn("synthetic-details-session");
        when(settings.effective(SettingsService.KEY_CHAT_PROMPT)).thenReturn("合成系统约定");
        when(settings.webEnabled()).thenReturn(true);
        when(retrieval.build("合成联网任务", 8, "fused", false, false)).thenReturn(new RetrievalContextService.Context(
                List.of(), List.of(), List.of(Map.of("type", "note", "id", 751, "title", "合成检索笔记", "channels", List.of("keyword")))));
        when(routing.forTask(ModelRouting.TASK_CHAT)).thenReturn(new ModelRouting.ModelTarget(
                "synthetic", "synthetic", "http://unused.invalid", "dummy", "mock", true));
        when(web.search("合成检索词")).thenReturn(List.of(new WebService.Source("https://example.com/synthetic-guide", "合成参考网站", "", "合成搜索摘要")));
        when(pages.read(eq("synthetic-details-session"), eq("https://example.com/synthetic-guide"), eq(""), eq(0), anyInt(), eq("auto"), anyLong()))
                .thenThrow(new IllegalStateException("合成网页连接超时"));

        var search = json.createObjectNode().put("role", "assistant");
        search.putArray("tool_calls").addObject().put("id", "synthetic-search").put("type", "function")
                .putObject("function").put("name", "web_search").put("arguments", "{\"query\":\"合成检索词\"}");
        var fetch = json.createObjectNode().put("role", "assistant");
        fetch.putArray("tool_calls").addObject().put("id", "synthetic-read").put("type", "function")
                .putObject("function").put("name", "web_fetch").put("arguments", "{\"url\":\"https://example.com/synthetic-guide\"}");
        when(client.chatFull(anyList(), nullable(List.class), anyString(), anyString(), anyString(), anyInt(), anyDouble(), nullable(String.class), nullable(String.class), any(Duration.class)))
                .thenReturn(new DeepSeekClient.ChatResult(search, "tool_calls", 3, 3, 0),
                        new DeepSeekClient.ChatResult(fetch, "tool_calls", 3, 3, 0),
                        new DeepSeekClient.ChatResult(json.createObjectNode().put("role", "assistant").put("content", "已查到合成网站；网页读取超时。"), "stop", 3, 3, 0));
        List<Map<String, Object>> pushed = new ArrayList<>();
        AiChatRequest req = new AiChatRequest(); req.setMessage("合成联网任务");
        var response = agent.chat(req, new AgentService.ChatListener() {
            @Override public void onProgress(Map<String, Object> row) { pushed.add(row); }
        });

        JsonNode progress = json.valueToTree(response.getProgress());
        JsonNode retrieved = find(progress, "title", "检索相关知识");
        assertEquals(751, retrieved.path("sources").get(0).path("id").asInt());
        assertEquals("web_search", find(progress, "title", "分析请求").path("tools").get(0).path("name").asText());
        JsonNode searchStep = find(progress, "toolName", "web_search");
        assertEquals("https://example.com/synthetic-guide", searchStep.path("websites").get(0).path("url").asText());
        assertEquals("合成参考网站", searchStep.path("websites").get(0).path("title").asText());
        JsonNode readStep = find(progress, "toolName", "web_fetch");
        assertEquals("error", readStep.path("status").asText());
        assertEquals("合成网页连接超时", readStep.path("error").asText());
        assertEquals("https://example.com/synthetic-guide", readStep.path("tools").get(0).path("arguments").path("url").asText());
        JsonNode firstRead = find(json.valueToTree(pushed), "toolName", "web_fetch");
        assertEquals("running", firstRead.path("status").asText());
        assertFalse(firstRead.has("error"));
        ArgumentCaptor<String> saved = ArgumentCaptor.forClass(String.class);
        verify(sessions).append(eq("synthetic-details-session"), eq(AgentSessionService.ROLE_PROGRESS), saved.capture(), isNull(), isNull());
        // Normalize JSON number node types across the in-memory Long values and parsed wire payload.
        assertEquals(json.readTree(json.writeValueAsString(response.getProgress())), json.readTree(saved.getValue()).path("progress"));
        verify(web, times(1)).search(anyString());
        verify(client, times(3)).chatFull(anyList(), nullable(List.class), anyString(), anyString(), anyString(), anyInt(), anyDouble(), nullable(String.class), nullable(String.class), any(Duration.class));
    }

    private static JsonNode find(JsonNode rows, String field, String value) {
        for (JsonNode row : rows) if (value.equals(row.path(field).asText())) return row;
        throw new AssertionError("Missing execution row: " + field + "=" + value);
    }
}
