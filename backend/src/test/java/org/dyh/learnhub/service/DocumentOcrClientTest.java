package org.dyh.learnhub.service;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class DocumentOcrClientTest {
    private HttpServer server;
    private String base;
    private String response;
    private int status = 200;
    private byte[] received;
    private String contentType;

    @BeforeEach void start() throws Exception {
        response = """
                {"engine":"tesseract","version":"synthetic-1","width":120,"height":80,
                 "blocks":[{"id":"b1","type":"paragraph","text":"Synthetic text","rect":[1,2,119,78],"confidence":0.91,"words":2}],"warnings":[]}
                """;
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/ocr", exchange -> {
            received = exchange.getRequestBody().readAllBytes();
            contentType = exchange.getRequestHeaders().getFirst("Content-Type");
            if (status == 302) exchange.getResponseHeaders().set("Location", "/redirected");
            byte[] body = response.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(status, body.length);
            try (var output = exchange.getResponseBody()) { output.write(body); }
        });
        server.createContext("/health", exchange -> {
            byte[] body = "{\"ok\":true,\"engine\":\"tesseract\",\"version\":\"synthetic\",\"languages\":[\"eng\",\"chi_sim\"]}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            try (var output = exchange.getResponseBody()) { output.write(body); }
        });
        server.start(); base = "http://127.0.0.1:" + server.getAddress().getPort();
    }
    @AfterEach void stop() { server.stop(0); }

    @Test void postsOnlyPngToConfiguredServiceAndValidatesResult() {
        var client = new DocumentOcrClient(base);
        var result = client.recognize(new byte[]{1, 2, 3}, Duration.ofSeconds(2));
        assertArrayEquals(new byte[]{1, 2, 3}, received);
        assertEquals("image/png", contentType);
        assertEquals(120, result.width());
        assertEquals(2, result.blocks().getFirst().words());
        assertArrayEquals(new double[]{1, 2, 119, 78}, result.blocks().getFirst().rect());
        assertTrue((Boolean) client.capabilities().get("available"));
        assertEquals(java.util.List.of("eng", "chi_sim"), client.capabilities().get("languages"));
    }

    @Test void rejectsOutOfBoundsCoordinatesAndInvalidConfidence() {
        var client = new DocumentOcrClient(base);
        response = response.replace("119,78", "121,78");
        assertThrows(IllegalStateException.class, () -> client.recognize(new byte[]{1}, Duration.ofSeconds(2)));
        response = response.replace("121,78", "119,78").replace("0.91", "1.1");
        assertThrows(IllegalStateException.class, () -> client.recognize(new byte[]{1}, Duration.ofSeconds(2)));
    }

    @Test void rejectsOversizeBodyAndDoesNotEchoContents() {
        response = "sensitive-synthetic-content".repeat(100000);
        var failure = assertThrows(IllegalStateException.class,
                () -> new DocumentOcrClient(base).recognize(new byte[]{1}, Duration.ofSeconds(3)));
        assertFalse(failure.getMessage().contains("sensitive-synthetic-content"));
    }

    @Test void neverFollowsRedirects() {
        AtomicInteger redirected = new AtomicInteger();
        server.createContext("/redirected", exchange -> { redirected.incrementAndGet(); exchange.sendResponseHeaders(200, -1); exchange.close(); });
        status = 302;
        assertThrows(IllegalStateException.class,
                () -> new DocumentOcrClient(base).recognize(new byte[]{1}, Duration.ofSeconds(2)));
        assertEquals(0, redirected.get());
    }

    @Test void disabledAndMalformedConfigurationRemainExplicit() {
        var disabled = new DocumentOcrClient("");
        assertFalse(disabled.configured());
        assertFalse((Boolean) disabled.capabilities().get("available"));
        assertThrows(IllegalStateException.class, () -> disabled.recognize(new byte[]{1}, Duration.ofSeconds(1)));
        assertThrows(IllegalArgumentException.class, () -> new DocumentOcrClient("http://user:secret@127.0.0.1"));
        assertThrows(IllegalArgumentException.class, () -> new DocumentOcrClient("http://127.0.0.1/?token=synthetic"));
    }
}
