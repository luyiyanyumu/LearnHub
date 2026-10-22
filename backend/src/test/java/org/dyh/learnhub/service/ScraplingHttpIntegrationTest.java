package org.dyh.learnhub.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/** The operator-configured sidecar is local; all page content is synthetic, with no external network. */
class ScraplingHttpIntegrationTest {
    private final ObjectMapper json = new ObjectMapper();

    @Test void sendsOnlyAnonymousBoundedFetchParametersAndReadsTheRealHttpResponse() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        AtomicReference<JsonNode> received = new AtomicReference<>();
        AtomicReference<String> credentials = new AtomicReference<>();
        server.createContext("/fetch", exchange -> {
            received.set(json.readTree(exchange.getRequestBody().readAllBytes()));
            credentials.set(exchange.getRequestHeaders().getFirst("Authorization"));
            if (exchange.getRequestHeaders().getFirst("Cookie") != null) credentials.set("unexpected cookie");
            byte[] response = json.createObjectNode().put("ok", true)
                    .put("url", "https://example.invalid/article").put("status", 200)
                    .put("contentType", "text/html").put("title", "合成动态文章")
                    .put("markdown", "## 合成正文\n\n这里是渲染后的合成内容。")
                    .put("truncated", false).put("fetchMode", "dynamic").toString().getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.start();
        try {
            ScraplingClient client = new ScraplingClient(json, address(server));

            ScraplingClient.Result result = client.fetch("https://example.invalid/article", 2_000);

            Set<String> fields = new java.util.HashSet<>();
            received.get().fieldNames().forEachRemaining(fields::add);
            assertEquals(Set.of("url", "timeoutMs", "maxChars"), fields);
            assertEquals(2_000, received.get().path("timeoutMs").asInt());
            assertEquals(100_000, received.get().path("maxChars").asInt());
            assertNull(credentials.get());
            assertEquals("dynamic", result.fetchMode());
            assertTrue(result.markdown().contains("渲染后的合成内容"));
            assertEquals(200, result.status());
        } finally {
            server.stop(0);
        }
    }

    @Test void doesNotFollowAnOperatorEndpointRedirectOrLeakItsResponseBody() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        AtomicInteger followed = new AtomicInteger();
        server.createContext("/fetch", exchange -> {
            exchange.getResponseHeaders().set("Location", "/unexpected");
            byte[] body = "SYNTHETIC-PRIVATE-DIAGNOSTIC".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(302, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.createContext("/unexpected", exchange -> {
            followed.incrementAndGet();
            exchange.sendResponseHeaders(200, -1);
            exchange.close();
        });
        server.start();
        try {
            ScraplingClient client = new ScraplingClient(json, address(server));

            IllegalStateException failure = assertThrows(IllegalStateException.class,
                    () -> client.fetch("https://example.invalid/article", 2_000));

            assertEquals(0, followed.get());
            assertTrue(failure.getMessage().contains("302"));
            assertFalse(failure.getMessage().contains("SYNTHETIC-PRIVATE-DIAGNOSTIC"));
        } finally {
            server.stop(0);
        }
    }

    private static String address(HttpServer server) {
        return "http://" + (server.getAddress().getAddress().getHostAddress().contains(":")
                ? "[" + server.getAddress().getAddress().getHostAddress() + "]"
                : server.getAddress().getAddress().getHostAddress()) + ":" + server.getAddress().getPort();
    }
}
