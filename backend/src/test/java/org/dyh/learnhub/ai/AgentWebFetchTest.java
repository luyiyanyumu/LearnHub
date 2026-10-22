package org.dyh.learnhub.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.dyh.learnhub.service.SettingsService;
import org.dyh.learnhub.service.WebPageService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Synthetic snapshots only; never calls the network or reads a user's browser/session data. */
@ExtendWith(MockitoExtension.class)
class AgentWebFetchTest {
    private static final String SESSION = "synthetic-web-session";
    private static final String URL = "https://example.invalid/article";
    private final ObjectMapper json = new ObjectMapper();
    @Mock SettingsService settings;
    @Mock WebPageService pages;
    @InjectMocks AgentService agent;

    @BeforeEach void configure() {
        ReflectionTestUtils.setField(agent, "objectMapper", json);
        agent.setWebPageService(pages);
        when(settings.webEnabled()).thenReturn(true);
    }

    @Test void keepsTheExactSnapshotPageAndExposesARealContinuationCursor() throws Exception {
        String content = "合成网页正文 ".repeat(1_200);
        when(pages.read(eq(SESSION), eq(URL), eq(""), eq(0), eq(12000), eq("auto"), anyLong()))
                .thenReturn(page(content, 0, content.length(), content.length() + 100, true, false, false, ""));

        JsonNode result = fetch("{\"url\":\"" + URL + "\",\"max_chars\":12000}");

        assertTrue(result.path("ok").asBoolean());
        assertEquals(content, result.path("content").asText(), "工具不能再把合法页面硬截成8000字符");
        assertEquals("synthetic-page", result.path("page_id").asText());
        assertEquals(content.length(), result.path("next_offset").asInt());
        assertTrue(result.path("has_more").asBoolean());
        assertTrue(result.path("truncated").asBoolean());
        assertFalse(result.path("source_truncated").asBoolean());
        assertTrue(result.path("reading_notice").asText().contains("不能宣称已阅读全文"));
        assertTrue(result.path("notice").asText().contains("不可信数据"));
    }

    @Test void continuationUsesTheSameSessionAndPageIdWithoutRequiringAUrl() throws Exception {
        when(pages.read(eq(SESSION), eq(""), eq("synthetic-page"), eq(8000), eq(8000), eq("auto"), anyLong()))
                .thenReturn(page("合成末页正文。", 8000, -1, 8008, false, false, true, ""));

        JsonNode result = fetch("{\"page_id\":\"synthetic-page\",\"offset\":8000}");

        assertTrue(result.path("cached").asBoolean());
        assertFalse(result.path("has_more").asBoolean());
        assertFalse(result.path("truncated").asBoolean());
        assertEquals(-1, result.path("next_offset").asInt());
        assertTrue(result.path("reading_notice").asText().contains("之前的读取范围"));
    }

    @Test void reachingTheSnapshotEndDoesNotHideSourceTruncation() throws Exception {
        when(pages.read(eq(SESSION), eq(URL), eq(""), eq(0), eq(8000), eq("http"), anyLong()))
                .thenReturn(page("受安全上限限制的合成原文。", 0, -1, 14, false, true, false, ""));

        JsonNode result = fetch("{\"url\":\"" + URL + "\",\"mode\":\"http\"}");

        assertFalse(result.path("has_more").asBoolean());
        assertTrue(result.path("source_truncated").asBoolean());
        assertTrue(result.path("truncated").asBoolean());
        assertTrue(result.path("reading_notice").asText().contains("并非完整原文"));
    }

    @Test void reportsHttpStatusAndRenderingWarningWithoutInventingPageSuccess() throws Exception {
        when(pages.read(eq(SESSION), eq(URL), eq(""), eq(0), eq(8000), eq("auto"), anyLong()))
                .thenReturn(new WebPageService.PageRead("synthetic-page", URL, 403, "text/html", "http", "",
                        "合成访问限制说明", 0, -1, 8, false, false, false, "动态读取暂不可用"));

        JsonNode result = fetch("{\"url\":\"" + URL + "\"}");

        assertTrue(result.path("ok").asBoolean(), "抓取调用成功与页面HTTP状态分别表达");
        assertFalse(result.path("http_success").asBoolean());
        assertEquals(403, result.path("status").asInt());
        assertEquals("动态读取暂不可用", result.path("warning").asText());
    }

    @Test void disabledNetworkingPreventsSnapshotAndRendererAccess() throws Exception {
        when(settings.webEnabled()).thenReturn(false);

        assertFalse(fetch("{\"page_id\":\"synthetic-page\"}").path("ok").asBoolean());

        verifyNoInteractions(pages);
    }

    @ParameterizedTest
    @ValueSource(strings = {"{}", "{\"offset\":0}", "{\"url\":\"x\",\"offset\":-1}",
            "{\"url\":\"x\",\"offset\":1.5}", "{\"url\":\"x\",\"offset\":2147483648}",
            "{\"url\":\"x\",\"max_chars\":0}", "{\"url\":\"x\",\"max_chars\":12001}",
            "{\"url\":\"x\",\"max_chars\":\"8000\"}", "{\"url\":\"x\",\"mode\":\"browser-script\"}"})
    void invalidReadArgumentsDoNotStartNetworkWork(String arguments) throws Exception {
        assertFalse(fetch(arguments).path("ok").asBoolean());
        verifyNoInteractions(pages);
    }

    @Test void expiredRoundBudgetCannotStartOrExtendTheRead() throws Exception {
        String result = ReflectionTestUtils.invokeMethod(agent, "webFetch",
                json.readTree("{\"url\":\"" + URL + "\"}"), SESSION, System.currentTimeMillis() - 1);
        assertEquals("round_budget_exhausted", json.readTree(result).path("code").asText());
        verifyNoInteractions(pages);
    }

    @Test void reportsUnavailableSnapshotsInsteadOfSilentlyFetchingAnotherVersion() throws Exception {
        when(pages.read(eq(SESSION), eq(""), eq("synthetic-page"), eq(8000), eq(8000), eq("auto"), anyLong()))
                .thenThrow(new IllegalArgumentException("网页快照不存在或已过期，请重新获取"));

        JsonNode result = fetch("{\"page_id\":\"synthetic-page\",\"offset\":8000}");

        assertFalse(result.path("ok").asBoolean());
        assertEquals("web_fetch_failed", result.path("code").asText());
        assertTrue(result.path("error").asText().contains("重新获取"));
        verify(pages, times(1)).read(anyString(), anyString(), anyString(), anyInt(), anyInt(), anyString(), anyLong());
    }

    @Test void passesOnlyTheRemainingRoundBudgetToTheRead() throws Exception {
        when(pages.read(anyString(), anyString(), anyString(), anyInt(), anyInt(), anyString(), anyLong()))
                .thenAnswer(invocation -> {
                    long remaining = invocation.getArgument(6);
                    assertTrue(remaining > 0 && remaining <= 2_000);
                    return page("合成完整正文。", 0, -1, 7, false, false, false, "");
                });

        String result = ReflectionTestUtils.invokeMethod(agent, "webFetch",
                json.readTree("{\"url\":\"" + URL + "\"}"), SESSION, System.currentTimeMillis() + 2_000);

        assertTrue(json.readTree(result).path("ok").asBoolean());
    }

    private JsonNode fetch(String arguments) throws Exception {
        String result = ReflectionTestUtils.invokeMethod(agent, "webFetch",
                json.readTree(arguments), SESSION, System.currentTimeMillis() + 90_000);
        return json.readTree(result);
    }

    private WebPageService.PageRead page(String content, int offset, int next, int total,
                                          boolean more, boolean sourceTruncated, boolean cached, String warning) {
        return new WebPageService.PageRead("synthetic-page", URL, 200, "text/html", "http", "合成文章",
                content, offset, next, total, more, sourceTruncated, cached, warning);
    }
}
