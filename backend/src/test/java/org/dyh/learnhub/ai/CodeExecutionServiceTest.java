package org.dyh.learnhub.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class CodeExecutionServiceTest {
    private final ObjectMapper json = new ObjectMapper();
    private final List<HttpServer> servers = new ArrayList<>();
    private static final String SOURCE = "def run(input):\n    return input['values']\n";

    @AfterEach void cleanup() { servers.forEach(server -> server.stop(0)); }

    @Test void disabledOrInvalidEndpointNeverRunsCodeOnTheHost() {
        for (String endpoint : List.of("", "file:///tmp/runner", "http://example.test/path",
                "http://user:password@example.test", "http://example.test?source=x", "http://example.test/#fragment")) {
            CodeExecutionService service = new CodeExecutionService(endpoint);
            assertFalse(service.available());
            assertFalse(service.status().path("available").asBoolean());
            assertEquals("code_runner_unavailable", service.execute(SOURCE, json.createObjectNode()).path("code").asText());
            assertFalse(service.status().toString().contains("password"));
            assertFalse(service.status().toString().contains("example.test"));
        }
    }

    @Test void healthRequiresExplicitReadinessAndCachesProbes() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        CodeExecutionService service = service(exchange -> {
            calls.incrementAndGet();
            assertEquals("/health", exchange.getRequestURI().getPath());
            assertEquals("GET", exchange.getRequestMethod());
            send(exchange, 200, "{\"ok\":true,\"ready\":true}");
        });
        assertTrue(service.available());
        assertTrue(service.available());
        assertTrue(service.status().path("available").asBoolean());
        assertEquals(1, calls.get());
        assertEquals("python", service.status().path("language").asText());
        CodeExecutionService notReady = service(exchange -> send(exchange, 200, "{\"ok\":true,\"ready\":false}"));
        assertFalse(notReady.available());
        CodeExecutionService unknown = service(exchange -> send(exchange, 200, "{\"ok\":true}"));
        assertFalse(unknown.available());
    }

    @Test void executeUsesOnlyConfiguredEndpointAndBoundedJsonContract() throws Exception {
        AtomicReference<JsonNode> body = new AtomicReference<>();
        CodeExecutionService service = service(exchange -> {
            assertEquals("/execute", exchange.getRequestURI().getPath());
            assertEquals("POST", exchange.getRequestMethod());
            assertEquals("application/json", exchange.getRequestHeaders().getFirst("Content-Type"));
            body.set(json.readTree(exchange.getRequestBody()));
            send(exchange, 200, """
                    {"ok":true,"output":{"total":8},"stdout":"合成日志","stderr":"","elapsedMs":7,
                     "sourceFingerprint":"1234","internalConfig":"must not cross boundary"}
                    """);
        });
        JsonNode input = json.readTree("{\"values\":[3,5],\"url\":\"http://unused.example.test\"}");
        JsonNode result = service.execute(SOURCE, input);
        assertEquals(SOURCE, body.get().path("source").asText());
        assertEquals(input, body.get().path("input"));
        assertEquals(10_000, body.get().path("timeoutMs").asInt());
        assertEquals(8, result.path("output").path("total").asInt());
        assertEquals("合成日志", result.path("stdout").asText());
        assertEquals("1234", result.path("sourceFingerprint").asText());
        assertFalse(result.has("internalConfig"));
    }

    @Test void analyzeDoesNotSubmitInputOrExecuteAndReturnsCanonicalFingerprint() throws Exception {
        CodeExecutionService service = service(exchange -> {
            assertEquals("/analyze", exchange.getRequestURI().getPath());
            JsonNode body = json.readTree(exchange.getRequestBody());
            assertEquals(SOURCE, body.path("source").asText());
            assertFalse(body.has("input"));
            assertEquals(1, body.size(), "analyze accepts source only, never execution overrides");
            send(exchange, 200, "{\"ok\":true,\"output\":{\"fingerprint\":\"canonical-ast-sha256\"}}");
        });
        assertEquals("canonical-ast-sha256", service.analyze(SOURCE).path("output").path("fingerprint").asText());
    }

    @Test void localBudgetsRejectInvalidPayloadBeforeSendingIt() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        CodeExecutionService service = service(exchange -> {
            calls.incrementAndGet(); send(exchange, 200, "{\"ok\":true,\"output\":null}");
        });
        assertEquals("code_invalid_request", service.execute(" ", json.createObjectNode()).path("code").asText());
        assertEquals("code_invalid_request", service.execute(SOURCE, null).path("code").asText());
        assertEquals("code_limit_exceeded", service.analyze("x".repeat(CodeExecutionService.MAX_SOURCE_CHARS + 1)).path("code").asText());
        assertEquals("code_limit_exceeded", service.execute(SOURCE,
                json.createObjectNode().put("large", "x".repeat(CodeExecutionService.MAX_JSON_CHARS))).path("code").asText());
        assertEquals(0, calls.get());
    }

    @Test void serverRedirectIsNotFollowed() throws Exception {
        AtomicInteger redirects = new AtomicInteger();
        CodeExecutionService service = service(exchange -> {
            if (exchange.getRequestURI().getPath().equals("/target")) redirects.incrementAndGet();
            exchange.getResponseHeaders().set("Location", "/target");
            send(exchange, 302, "redirect");
        });
        assertEquals("code_runner_unavailable", service.execute(SOURCE, json.createObjectNode()).path("code").asText());
        assertEquals(0, redirects.get());
    }

    @Test void malformedAndUnboundedResponsesFailClosed() throws Exception {
        for (String response : List.of("not json", "null", "[]", "{\"ok\":true}", "{\"ok\":\"true\",\"output\":1}")) {
            CodeExecutionService service = service(exchange -> send(exchange, 200, response));
            assertEquals("code_runner_unavailable", service.execute(SOURCE, json.createObjectNode()).path("code").asText());
        }
        CodeExecutionService tooManyBytes = service(exchange -> send(exchange, 200, "x".repeat(1_000_001)));
        assertEquals("code_runner_unavailable", tooManyBytes.execute(SOURCE, json.createObjectNode()).path("code").asText());
        CodeExecutionService tooMuchJson = service(exchange -> send(exchange, 200,
                "{\"ok\":true,\"output\":\"" + "x".repeat(CodeExecutionService.MAX_JSON_CHARS) + "\"}"));
        assertEquals("code_limit_exceeded", tooMuchJson.execute(SOURCE, json.createObjectNode()).path("code").asText());
    }

    @Test void executionFailureKeepsCompilerDiagnosticsAndTruncatesLogs() throws Exception {
        CodeExecutionService service = service(exchange -> send(exchange, 200, json.createObjectNode()
                .put("ok", false).put("code", "code_syntax_error").put("error", "line 1: invalid syntax")
                .put("stderr", "x".repeat(20_000)).put("elapsedMs", 4).toString()));
        JsonNode result = service.execute(SOURCE, json.createObjectNode());
        assertFalse(result.path("ok").asBoolean());
        assertEquals("code_syntax_error", result.path("code").asText());
        assertEquals("line 1: invalid syntax", result.path("error").asText());
        assertEquals(8_000, result.path("stderr").asText().length());
        assertEquals(4, result.path("elapsedMs").asInt());
    }

    @Test void validationResponsesRetainDiagnosticsButBusyAndServerFailuresAreInfrastructureErrors() throws Exception {
        for (int status : List.of(400, 408, 413, 422)) {
            CodeExecutionService service = service(exchange -> send(exchange, status,
                    "{\"ok\":false,\"code\":\"code_syntax_error\",\"error\":\"line 2: expected ':'\"}"));
            JsonNode result = service.analyze(SOURCE);
            assertEquals("code_syntax_error", result.path("code").asText());
            assertEquals("line 2: expected ':'", result.path("error").asText());
        }
        CodeExecutionService busy = service(exchange -> send(exchange, 429, "busy"));
        assertEquals("code_runner_busy", busy.execute(SOURCE, json.createObjectNode()).path("code").asText());
        CodeExecutionService failed = service(exchange -> send(exchange, 500,
                "{\"ok\":false,\"code\":\"internal_error\",\"error\":\"internal path should stay private\"}"));
        JsonNode result = failed.execute(SOURCE, json.createObjectNode());
        assertEquals("code_runner_unavailable", result.path("code").asText());
        assertFalse(result.toString().contains("internal path"));
    }

    @Test void uppercaseRunnerCodesAreNormalizedAndInfrastructureFailuresStayDistinct() throws Exception {
        for (String code : List.of("RUNNER_UNAVAILABLE", "CLEANUP_FAILED", "RUNNER_BUSY", "REQUEST_TIMEOUT", "SYNTAX_ERROR", "TIMEOUT")) {
            CodeExecutionService service = service(exchange -> send(exchange, 200,
                    json.createObjectNode().put("ok", false).put("code", code).put("error", "synthetic failure").toString()));
            String expected = switch (code) {
                case "RUNNER_UNAVAILABLE", "CLEANUP_FAILED" -> "code_runner_unavailable";
                case "RUNNER_BUSY" -> "code_runner_busy";
                case "REQUEST_TIMEOUT" -> "code_runner_timeout";
                case "SYNTAX_ERROR" -> "code_syntax_error";
                default -> "code_timeout";
            };
            assertEquals(expected, service.execute(SOURCE, json.createObjectNode()).path("code").asText());
        }
    }

    @Test void unreachableRunnerReportsInfrastructureFailureWithoutLeakingEndpoint() {
        CodeExecutionService service = new CodeExecutionService("http://127.0.0.1:1");
        JsonNode result = service.execute(SOURCE, json.createObjectNode());
        assertFalse(result.path("ok").asBoolean());
        assertEquals("code_runner_unavailable", result.path("code").asText());
        assertFalse(result.toString().contains("127.0.0.1"));
    }

    @Test void transportDeadlineIncludesAResponseBodyThatNeverFinishes() throws Exception {
        CountDownLatch releaseServer = new CountDownLatch(1);
        CodeExecutionService service = service(exchange -> {
            exchange.sendResponseHeaders(200, 100);
            exchange.getResponseBody().write('{');
            exchange.getResponseBody().flush();
            try { releaseServer.await(20, TimeUnit.SECONDS); }
            catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        });
        try {
            JsonNode result = assertTimeoutPreemptively(Duration.ofSeconds(15),
                    () -> service.execute(SOURCE, json.createObjectNode()));
            assertEquals("code_runner_timeout", result.path("code").asText());
        } finally { releaseServer.countDown(); }
    }

    private CodeExecutionService service(Handler handler) throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        servers.add(server);
        server.createContext("/", exchange -> {
            try { handler.handle(exchange); }
            finally { exchange.close(); }
        });
        server.start();
        return new CodeExecutionService("http://127.0.0.1:" + server.getAddress().getPort());
    }

    private static void send(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
    }

    @FunctionalInterface private interface Handler { void handle(HttpExchange exchange) throws IOException; }
}
