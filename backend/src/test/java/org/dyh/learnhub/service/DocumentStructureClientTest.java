package org.dyh.learnhub.service;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class DocumentStructureClientTest {
    private static final Duration WAIT = Duration.ofSeconds(3);
    private HttpServer server;
    private DocumentStructureClient client;
    private volatile String response;
    private volatile String health;
    private volatile String contentType;
    private volatile byte[] received;

    @BeforeEach void setup() throws Exception {
        response = """
                {"engine":"pp-structurev3","version":"test","profile":"high-quality-v2","width":120,"height":80,
                 "blocks":[{"type":"heading","text":"Heading","rect":[1,2,60,12],"level":2,"confidence":0.91},
                           {"type":"table","text":"","rect":[1,14,60,50],"rows":[["Name","Number"],["Test","1"]]},
                           {"type":"formula","text":"","rect":[1,54,60,75],"latex":"x^2=1"}],
                 "warnings":["pp_ocr_lines_recovered","pp_formula_needs_review"]}
                """;
        health="{\"ready\":true,\"engine\":\"pp-structurev3\",\"version\":\"test\",\"profile\":\"high-quality-v2\"}";
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/ocr", exchange -> {
            received = exchange.getRequestBody().readAllBytes();
            contentType = exchange.getRequestHeaders().getFirst("Content-Type");
            byte[] body = response.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            try (var output = exchange.getResponseBody()) { output.write(body); }
        });
        server.createContext("/health", exchange -> {
            byte[] body = health.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            try (var output = exchange.getResponseBody()) { output.write(body); }
        });
        server.start();
        client = new DocumentStructureClient("http://127.0.0.1:" + server.getAddress().getPort());
    }

    @AfterEach void stop() { server.stop(0); }

    @Test void exchangesOnlyPngWithConfiguredServiceAndParsesOrderedBlocks() {
        byte[] input = {1, 2, 3};
        var result = client.recognize(input, WAIT);
        assertArrayEquals(input, received);
        assertEquals("image/png", contentType);
        assertEquals(120, result.width());
        assertEquals(80, result.height());
        assertEquals(2, result.blocks().getFirst().level());
        assertEquals(List.of("Test", "1"), result.blocks().get(1).rows().getLast());
        assertEquals("x^2=1", result.blocks().getLast().latex());
        assertArrayEquals(new double[]{1, 54, 60, 75}, result.blocks().getLast().rect());
        assertEquals(List.of("pp_ocr_lines_recovered", "pp_formula_needs_review"), result.warnings());
        assertEquals(true, client.capabilities().get("available"));
        assertEquals("high-quality-v2", client.capabilities().get("profile"));
    }

    @Test void legacyMobileServerIsNeverReportedOrAcceptedAsTheHighQualityGeneration() {
        health=health.replace("high-quality-v2", "high-quality-v1");
        response=response.replace("high-quality-v2", "high-quality-v1");
        assertEquals(false,client.capabilities().get("available"));
        assertEquals("unknown",client.capabilities().get("profile"));
        var failed=assertThrows(IllegalStateException.class,() -> client.recognize(new byte[]{1},WAIT));
        assertTrue(failed.getMessage().contains("高精度模型"));
    }

    @Test void rejectsInvalidCoordinatesRaggedRowsAndMissingTextConfidence() {
        String valid = response;
        response = valid.replace("[1,2,60,12]", "[1,2,121,12]");
        assertThrows(IllegalStateException.class, () -> client.recognize(new byte[]{1}, WAIT));
        response = valid.replace("[\"Test\",\"1\"]", "[\"Test\"]");
        assertThrows(IllegalStateException.class, () -> client.recognize(new byte[]{1}, WAIT));
        response = valid.replace(",\"confidence\":0.91", "");
        assertThrows(IllegalStateException.class, () -> client.recognize(new byte[]{1}, WAIT));
    }

    @Test void limitsResponseWithoutExposingDocumentTextAndRequiresSafeBase() {
        response = "synthetic-payload".repeat(150000);
        var error = assertThrows(IllegalStateException.class, () -> client.recognize(new byte[]{1}, WAIT));
        assertFalse(error.getMessage().contains("synthetic-payload"));
        var disabled = new DocumentStructureClient("");
        assertEquals(false, disabled.capabilities().get("available"));
        assertThrows(IllegalStateException.class, () -> disabled.recognize(new byte[]{1}, WAIT));
        assertThrows(IllegalArgumentException.class, () -> new DocumentStructureClient("http://user:secret@127.0.0.1"));
        assertThrows(IllegalArgumentException.class, () -> new DocumentStructureClient("http://127.0.0.1/?secret=token"));
    }
}
