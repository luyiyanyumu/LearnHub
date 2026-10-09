package org.dyh.learnhub.service.rerank;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.dyh.learnhub.service.RerankService;
import org.dyh.learnhub.service.SettingsService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class CrossEncoderRerankerTest {
    private final ObjectMapper mapper = new ObjectMapper();
    private final AtomicReference<JsonNode> received = new AtomicReference<>();
    private final AtomicInteger status = new AtomicInteger(200);
    private HttpServer server;
    private SettingsService settings;
    private CrossEncoderReranker cross;

    @BeforeEach
    void setup() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/health", exchange -> {
            byte[] reply = "{\"status\":\"ok\"}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, reply.length);
            exchange.getResponseBody().write(reply);
            exchange.close();
        });
        server.createContext("/rerank", exchange -> {
            received.set(mapper.readTree(exchange.getRequestBody().readAllBytes()));
            byte[] reply = (status.get() == 200
                    ? "{\"results\":[{\"index\":1,\"relevance_score\":0.9},{\"index\":0,\"relevance_score\":0.8}]}"
                    : "{\"error\":\"offline\"}").getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(status.get(), reply.length);
            exchange.getResponseBody().write(reply);
            exchange.close();
        });
        server.start();
        settings = mock(SettingsService.class);
        when(settings.effective(CrossEncoderReranker.KEY_URL)).thenReturn(
                "http://127.0.0.1:" + server.getAddress().getPort() + "/rerank");
        cross = new CrossEncoderReranker(settings, mapper);
    }

    @AfterEach
    void tearDown() {
        if (server != null) server.stop(0);
    }

    @Test
    void postsTitleSectionAndMatchingTailWithinTheConfiguredExcerptBudget() {
        when(settings.effective(Reranker.KEY_SNIPPET)).thenReturn("120");
        String source = "小节：监听器注册\n" + "前面的其他概念介绍。".repeat(40)
                + "alphaClient 使用 registerHandler 完成注册。";

        List<String> order = cross.rerank("alphaClient registerHandler", List.of(
                new Reranker.Item("one", "应用手册", source),
                new Reranker.Item("two", "其他手册", "另一段内容。")));

        assertEquals(List.of("two", "one"), order);
        assertEquals("alphaClient registerHandler", received.get().path("query").asText());
        String document = received.get().path("documents").get(0).asText();
        assertTrue(document.startsWith("应用手册｜小节：监听器注册｜"));
        assertTrue(document.contains("alphaClient"));
        assertTrue(document.contains("registerHandler"), "a useful tail must reach the actual HTTP service");
        assertTrue(document.substring("应用手册｜".length()).length() <= 120);
    }

    @Test
    void noMatchPreservesTheCompactedSourcePrefixWithinACustomBudget() {
        when(settings.effective(Reranker.KEY_SNIPPET)).thenReturn("48");
        String source = "概念介绍与背景说明。\n".repeat(20);

        cross.rerank("registerHandler", List.of(new Reranker.Item("one", "手册", source)));

        String compact = source.replaceAll("\\s+", " ").trim();
        assertEquals("手册｜" + compact.substring(0, 48),
                received.get().path("documents").get(0).asText());
    }

    @Test
    void serviceFailureStillFallsBackToTheLlmWithoutLosingCandidateKeys() {
        status.set(503);
        when(settings.effective(RerankService.SETTING_RERANK)).thenReturn("1");
        when(settings.effective(RerankService.SETTING_BACKEND)).thenReturn("cross");
        LlmListwiseReranker llm = mock(LlmListwiseReranker.class);
        when(llm.available()).thenReturn(true);
        List<RerankService.Item> items = List.of(
                new RerankService.Item("one", "手册一", "内容一"),
                new RerankService.Item("two", "手册二", "内容二"),
                new RerankService.Item("three", "手册三", "内容三"));
        List<Reranker.Item> converted = items.stream()
                .map(item -> new Reranker.Item(item.key(), item.title(), item.snippet())).toList();
        when(llm.rerank("query", converted)).thenReturn(List.of("three", "one", "two"));

        List<String> order = new RerankService(llm, cross, settings).rerank("query", items);

        assertEquals(List.of("three", "one", "two"), order);
        verify(llm, times(1)).rerank("query", converted);
        assertEquals(3, received.get().path("documents").size());
    }
}
