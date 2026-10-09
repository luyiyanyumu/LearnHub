package org.dyh.learnhub.service.vector;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.dyh.learnhub.ai.EmbeddingClient;
import org.dyh.learnhub.entity.KbChunk;
import org.dyh.learnhub.mapper.KbChunkMapper;
import org.dyh.learnhub.service.SettingsService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Local REST stub only: no Milvus deployment or embedding endpoint is contacted. */
class MilvusVectorSpaceTest {
    private final ObjectMapper json = new ObjectMapper();
    private final KbChunkMapper mapper = mock(KbChunkMapper.class);
    private final EmbeddingClient embedder = mock(EmbeddingClient.class);
    private final SettingsService settings = mock(SettingsService.class);
    private final Map<String, Integer> collections = new LinkedHashMap<>();
    private final List<JsonNode> writes = new ArrayList<>();
    private HttpServer server;
    private MilvusVectorStore store;
    private final String a = "a".repeat(64), b = "b".repeat(64);

    @BeforeEach void start() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            JsonNode body = json.readTree(exchange.getRequestBody());
            String path = exchange.getRequestURI().getPath();
            Object data = Map.of();
            if (path.endsWith("/list")) data = new ArrayList<>(collections.keySet());
            else if (path.endsWith("/create") && path.contains("/collections/")) {
                collections.put(body.path("collectionName").asText(), body.path("dimension").asInt());
                writes.add(body);
            } else if (path.endsWith("/describe")) data = Map.of("fields", List.of(Map.of("name", "vector", "params",
                    List.of(Map.of("key", "dim", "value", String.valueOf(collections.get(body.path("collectionName").asText())))))));
            else if (path.endsWith("/search")) data = List.of(Map.of("id", 1, "distance", .9),
                    Map.of("id", 2, "distance", .8), Map.of("id", 3, "distance", .7));
            else if (path.endsWith("/insert") || path.endsWith("/delete")) writes.add(body);
            byte[] response = json.writeValueAsBytes(Map.of("code", 0, "data", data));
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response); exchange.close();
        });
        server.start();
        when(settings.effective(MilvusVectorStore.KEY_MILVUS_URI)).thenReturn("http://127.0.0.1:" + server.getAddress().getPort());
        when(settings.effective(MilvusVectorStore.KEY_MILVUS_TOKEN)).thenReturn("stub-only");
        store = new MilvusVectorStore(mapper, embedder, settings, json);
    }

    @AfterEach void stop() { server.stop(0); }

    @Test void explicitSpaceControlsWritesWithoutReReadingLiveModel() {
        store.replaceSource(a, "note", 1L, List.of(new VectorStore.VecItem(1, 0, new float[]{1, 0})));
        store.replaceSource(b, "note", 1L, List.of(new VectorStore.VecItem(2, 0, new float[]{1, 0})));
        assertEquals(2, collections.size());
        assertTrue(writes.stream().anyMatch(body -> body.path("collectionName").asText().equals(MilvusVectorStore.collection(a))));
        assertTrue(writes.stream().anyMatch(body -> body.path("collectionName").asText().equals(MilvusVectorStore.collection(b))));
        verifyNoInteractions(embedder);
        assertThrows(IllegalArgumentException.class, () -> MilvusVectorStore.collection("model/a"));
    }

    @Test void mysqlHydrationRejectsAnotherSpaceAndLegacyEvenIfAnnReturnsIds() {
        collections.put(MilvusVectorStore.collection(a), 2);
        KbChunk current = row(1, a), other = row(2, b), legacy = row(3, null);
        when(mapper.selectBatchIds(anyCollection())).thenReturn(List.of(current, other, legacy));
        assertEquals(List.of(1L), store.search(a, new float[]{1, 0}, 10).stream().map(VectorStore.VecHit::id).toList());
    }

    @Test void existingCollectionDimensionIsCheckedBeforeDeletingAnything() {
        collections.put(MilvusVectorStore.collection(a), 3);
        assertThrows(IllegalStateException.class, () -> store.replaceSource(a, "note", 1L,
                List.of(new VectorStore.VecItem(1, 0, new float[]{1, 0}))));
        assertTrue(writes.isEmpty());
    }

    private KbChunk row(long id, String space) {
        KbChunk row = new KbChunk(); row.setId(id); row.setSourceType("note"); row.setSourceId(id);
        row.setDim(2); row.setEmbeddingSpace(space); return row;
    }
}
