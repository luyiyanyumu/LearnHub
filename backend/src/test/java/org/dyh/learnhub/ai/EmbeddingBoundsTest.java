package org.dyh.learnhub.ai;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.dyh.learnhub.service.ModelProfileService;
import org.dyh.learnhub.service.SettingsService;
import org.junit.jupiter.api.Test;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class EmbeddingBoundsTest {
    @Test void operationBatchAndInputBoundsReachTheHttpProvider() throws Exception {
        var json = new ObjectMapper();
        List<List<String>> batches = new ArrayList<>();
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/embeddings", exchange -> {
            var body = json.readTree(exchange.getRequestBody().readAllBytes());
            List<String> inputs = new ArrayList<>(); body.path("input").forEach(node -> inputs.add(node.asText()));
            batches.add(inputs);
            var response = json.createObjectNode(); var data = response.putArray("data");
            for (int index = 0; index < inputs.size(); index++) {
                var row = data.addObject().put("index", index); row.putArray("embedding").add(1).add(0);
            }
            byte[] bytes = json.writeValueAsBytes(response);
            exchange.sendResponseHeaders(200, bytes.length); exchange.getResponseBody().write(bytes); exchange.close();
        });
        server.start();
        try {
            var client = new EmbeddingClient(mock(SettingsService.class), json, mock(ModelProfileService.class));
            var target = EmbeddingClient.Snapshot.of("fixture", "http://127.0.0.1:" + server.getAddress().getPort() + "/v1", "custom", null);
            assertEquals(17, client.embedAll(target, java.util.Collections.nCopies(17, "合".repeat(7000)), 32, 8000).size());
            assertEquals(1, batches.size()); assertEquals(17, batches.getFirst().size());
            assertEquals(7000, batches.getFirst().getFirst().length());
            batches.clear();
            assertEquals(5, client.embedAll(target, java.util.Collections.nCopies(5, "合".repeat(7000)), 2, 128).size());
            assertEquals(List.of(2, 2, 1), batches.stream().map(List::size).toList());
            assertTrue(batches.stream().flatMap(List::stream).allMatch(text -> text.length() == 128));
        } finally { server.stop(0); }
    }
}
