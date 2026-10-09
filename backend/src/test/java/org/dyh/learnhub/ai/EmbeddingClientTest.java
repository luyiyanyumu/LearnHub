package org.dyh.learnhub.ai;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.dyh.learnhub.entity.ModelProfile;
import org.dyh.learnhub.service.ModelProfileService;
import org.dyh.learnhub.service.SettingsService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class EmbeddingClientTest {
    private final ObjectMapper json = new ObjectMapper();
    private final SettingsService settings = mock(SettingsService.class);
    private final ModelProfileService profiles = mock(ModelProfileService.class);
    private final List<Map<String, Object>> requests = new ArrayList<>();
    private final AtomicInteger calls = new AtomicInteger();
    private HttpServer server;
    private String base;
    private volatile String response = "{\"data\":[{\"index\":0,\"embedding\":[1,2]}]}";
    private volatile int responseStatus = 200;
    private volatile Runnable afterRequest = () -> {};
    private EmbeddingClient client;

    @BeforeEach
    void startLocalFixture() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            requests.add(Map.of("path", exchange.getRequestURI().getPath(), "body", json.readTree(body),
                    "authorization", String.valueOf(exchange.getRequestHeaders().getFirst("Authorization"))));
            calls.incrementAndGet();
            byte[] data = response.getBytes(StandardCharsets.UTF_8);
            afterRequest.run();
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(responseStatus, data.length);
            exchange.getResponseBody().write(data);
            exchange.close();
        });
        server.start();
        base = "http://127.0.0.1:" + server.getAddress().getPort();
        client = new EmbeddingClient(settings, json, profiles);
    }

    @AfterEach
    void stopLocalFixture() {
        server.stop(0);
    }

    private EmbeddingClient.Snapshot target() {
        return EmbeddingClient.Snapshot.of("fixture-model", base + "/v1", "custom", "fixture-key-only");
    }

    @Test
    void noRouteDoesNotImplicitlyCallDefaultOllamaOrChat() {
        assertThat(client.snapshot().configured()).isFalse();
        assertThatThrownBy(() -> client.embed("question")).hasMessageContaining("未配置");
        assertThat(calls).hasValue(0);
        verifyNoInteractions(profiles);
        verify(settings, never()).effective(SettingsService.KEY_EMBED_BASE_URL);
    }

    @Test
    void explicitLegacyUsesExistingOllamaSettings() {
        when(settings.effective("ai.model_for_embed")).thenReturn("legacy");
        when(settings.effective(SettingsService.KEY_EMBED_BASE_URL)).thenReturn(base + "/v1/");
        when(settings.effective(SettingsService.KEY_EMBED_MODEL)).thenReturn("existing-embedding");
        response = "{\"embeddings\":[[1,2,3]]}";
        assertThat(client.embed("hello")).containsExactly(1, 2, 3);
        assertThat(requests.get(0).get("path")).isEqualTo("/api/embed");
        assertThat(requests.get(0).get("authorization")).isEqualTo("null");
        assertThat(json.valueToTree(requests.get(0).get("body")).path("model").asText()).isEqualTo("existing-embedding");
    }

    @Test
    void routedProfileUsesItsOwnModelAddressAndKey() {
        ModelProfile profile = profile("embed-profile", "openai");
        when(settings.effective("ai.model_for_embed")).thenReturn(profile.getId());
        when(profiles.embeddingProfile(profile.getId())).thenReturn(profile);
        assertThat(client.embed("hello")).containsExactly(1, 2);
        assertThat(requests.get(0).get("path")).isEqualTo("/v1/embeddings");
        assertThat(requests.get(0).get("authorization")).isEqualTo("Bearer fixture-key-only");
        assertThat(json.valueToTree(requests.get(0).get("body")).path("encoding_format").asText()).isEqualTo("float");
    }

    @Test
    void deletedOrChatProfileDoesNotFallBackToAnotherModel() {
        when(settings.effective("ai.model_for_embed")).thenReturn("chat-profile");
        when(profiles.embeddingProfile("chat-profile")).thenThrow(new IllegalArgumentException("所选嵌入档案不存在或用途不是向量嵌入"));
        assertThat(client.snapshot().configured()).isFalse();
        assertThatThrownBy(() -> client.embed("hello")).hasMessageContaining("用途");
        assertThat(calls).hasValue(0);
        verify(profiles, never()).resolve(any());
    }

    @Test
    void responseIndexRestoresInputOrder() {
        response = "{\"data\":[{\"index\":1,\"embedding\":[3,4]},{\"index\":0,\"embedding\":[1,2]}]}";
        List<float[]> result = client.embedAll(target(), List.of("first", "second"));
        assertThat(result.get(0)).containsExactly(1, 2);
        assertThat(result.get(1)).containsExactly(3, 4);
    }

    @Test
    void everyBatchUsesTheCapturedSnapshotWhenRouteChanges() {
        EmbeddingClient.Snapshot captured = target();
        response = batchResponse(16, 2);
        afterRequest = () -> {
            when(settings.effective("ai.model_for_embed")).thenReturn("disabled");
            response = batchResponse(1, 2);
        };
        assertThat(client.embedAll(captured, java.util.Collections.nCopies(17, "input"))).hasSize(17);
        assertThat(calls).hasValue(2);
        for (Map<String, Object> request : requests) {
            assertThat(json.valueToTree(request.get("body")).path("model").asText()).isEqualTo("fixture-model");
            assertThat(request.get("authorization")).isEqualTo("Bearer fixture-key-only");
        }
        verifyNoInteractions(settings, profiles);
    }

    @Test
    void dimensionsMustStayConsistentAcrossBatches() {
        response = batchResponse(16, 2);
        afterRequest = () -> response = batchResponse(1, 3);
        assertThatThrownBy(() -> client.embedAll(target(), java.util.Collections.nCopies(17, "input")))
                .hasMessageContaining("维数不一致");
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "{\"data\":[]}",
            "{\"data\":[{\"index\":0,\"embedding\":[]}]}",
            "{\"data\":[{\"index\":0,\"embedding\":[0,0]}]}",
            "{\"data\":[{\"index\":0,\"embedding\":[1e100,2]}]}",
            "{\"data\":[{\"index\":0,\"embedding\":[\"1\",2]}]}",
            "{\"data\":[{\"index\":1,\"embedding\":[1,2]}]}",
            "{\"data\":[{\"index\":0.5,\"embedding\":[1,2]}]}",
            "{\"data\":[{\"embedding\":[1,2]}]}",
            "not-json"
    })
    void invalidResponsesAreRejectedBeforePersisting(String invalid) {
        response = invalid;
        assertThatThrownBy(() -> client.embed(target(), "input")).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void duplicateIndexesAndMixedDimensionsAreRejected() {
        response = "{\"data\":[{\"index\":0,\"embedding\":[1,2]},{\"index\":0,\"embedding\":[3,4]}]}";
        assertThatThrownBy(() -> client.embedAll(target(), List.of("first", "second"))).hasMessageContaining("索引重复");
        response = "{\"data\":[{\"index\":0,\"embedding\":[1,2]},{\"index\":1,\"embedding\":[3,4,5]}]}";
        assertThatThrownBy(() -> client.embedAll(target(), List.of("first", "second"))).hasMessageContaining("维数不一致");
    }

    @Test
    void errorMessagesAndSnapshotNeverExposeCredentialsOrText() {
        responseStatus = 401;
        response = "fixture-key-only private-user-text";
        assertThatThrownBy(() -> client.embed(target(), "private-user-text"))
                .hasMessageContaining("401").hasMessageNotContaining("fixture-key-only").hasMessageNotContaining("private-user-text");
        assertThat(target().toString()).doesNotContain("fixture-key-only");
        Map<String, Object> probe = client.probe(target());
        assertThat(probe.get("ok")).isEqualTo(false);
        assertThat(probe.toString()).doesNotContain("fixture-key-only", "private-user-text");
    }

    @Test
    void fingerprintIgnoresKeyAndProviderLabelButDistinguishesActualSpace() {
        var first = EmbeddingClient.Snapshot.of("model", "https://EXAMPLE.test:443/v1/", "openai", "first-fixture-key");
        var same = EmbeddingClient.Snapshot.of("model", "https://example.test/v1/embeddings", "custom", "second-fixture-key");
        assertThat(first.spaceFingerprint()).matches("[a-f0-9]{64}").isEqualTo(same.spaceFingerprint());
        assertThat(EmbeddingClient.Snapshot.of("other", "https://example.test/v1", "openai", "").spaceFingerprint())
                .isNotEqualTo(first.spaceFingerprint());
        assertThat(EmbeddingClient.Snapshot.of("model", "https://other.test/v1", "openai", "").spaceFingerprint())
                .isNotEqualTo(first.spaceFingerprint());
        assertThat(EmbeddingClient.Snapshot.of("model", "https://example.test/v1", "ollama", "").spaceFingerprint())
                .isNotEqualTo(first.spaceFingerprint());
    }

    @Test
    void invalidUrlCredentialsOrHeaderDoNotLeakInValidationError() {
        assertThatThrownBy(() -> EmbeddingClient.Snapshot.of("model", "https://user:fixture-secret@example.test/v1", "custom", ""))
                .hasMessageNotContaining("fixture-secret");
        assertThatThrownBy(() -> EmbeddingClient.Snapshot.of("model", "https://example.test/v1?key=fixture-secret", "custom", ""))
                .hasMessageNotContaining("fixture-secret");
        assertThatThrownBy(() -> EmbeddingClient.Snapshot.of("model", base, "custom", "fixture-secret\r\n"))
                .hasMessageNotContaining("fixture-secret");
        assertThatThrownBy(() -> EmbeddingClient.Snapshot.of("model", base, "custom", "fixture-secret\u4e2d"))
                .hasMessageNotContaining("fixture-secret");
    }

    @Test
    void profileTestEmbedsWithoutActivatingItsRouteOrReturningVector() {
        ModelProfile profile = profile("not-routed", "ollama");
        when(profiles.embeddingProfile(profile.getId())).thenReturn(profile);
        response = "{\"embeddings\":[[1,2,3]]}";
        Map<String, Object> probe = client.probe(client.snapshotForProfile(profile.getId()));
        assertThat(probe).containsEntry("ok", true).containsEntry("dimension", 3);
        assertThat(probe).doesNotContainKeys("embedding", "embeddings", "apiKey", "reply");
        verifyNoInteractions(settings);
    }

    private ModelProfile profile(String id, String provider) {
        ModelProfile profile = new ModelProfile();
        profile.setId(id);
        profile.setPurpose("embedding");
        profile.setProvider(provider);
        profile.setBaseUrl(base + "/v1");
        profile.setModel("fixture-model");
        profile.setApiKey("fixture-key-only");
        return profile;
    }

    private String batchResponse(int count, int dimensions) {
        var body = json.createObjectNode();
        var data = body.putArray("data");
        for (int index = 0; index < count; index++) {
            var item = data.addObject();
            item.put("index", index);
            var vector = item.putArray("embedding");
            for (int dimension = 0; dimension < dimensions; dimension++) vector.add(dimension + 1);
        }
        return body.toString();
    }
}
