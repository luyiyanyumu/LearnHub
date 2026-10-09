package org.dyh.learnhub.service;

import org.dyh.learnhub.ai.DeepSeekClient;
import org.dyh.learnhub.ai.EmbeddingClient;
import org.dyh.learnhub.config.AiProperties;
import org.dyh.learnhub.entity.ModelProfile;
import org.dyh.learnhub.mapper.ModelProfileMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class ModelProfilePurposeTest {
    private final ModelProfileMapper mapper = mock(ModelProfileMapper.class);
    private final SettingsService settings = mock(SettingsService.class);
    private final Map<String, ModelProfile> stored = new LinkedHashMap<>();
    private ModelProfileService service;
    private ObjectProvider<EmbeddingClient> embeddingProvider;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setup() {
        ObjectProvider<DeepSeekClient> chatProvider = mock(ObjectProvider.class);
        embeddingProvider = mock(ObjectProvider.class);
        service = new ModelProfileService(mapper, settings, new AiProperties(), chatProvider, embeddingProvider);
        when(mapper.selectList(any())).thenAnswer(invocation -> new ArrayList<>(stored.values()));
        when(mapper.selectById(any())).thenAnswer(invocation -> stored.get(invocation.<String>getArgument(0)));
        when(mapper.insert(any(ModelProfile.class))).thenAnswer(invocation -> {
            ModelProfile profile = invocation.getArgument(0);
            stored.put(profile.getId(), profile);
            return 1;
        });
    }

    @Test
    void newEmbeddingProfileDoesNotBecomeDefaultChatAndKeyRemainsMasked() {
        Map<String, Object> view = service.create(body("embedding"));
        assertThat(view).containsEntry("purpose", "embedding").containsEntry("active", false).containsEntry("hasKey", true);
        assertThat(view.toString()).doesNotContain("fixture-key-only");
        verify(settings, never()).update(eq(ModelProfileService.SETTING_ACTIVE), any());
    }

    @Test
    void omittedPurposeIsChatForLegacyClientsAndFirstChatActivates() {
        Map<String, Object> payload = new LinkedHashMap<>(body("chat"));
        payload.remove("purpose");
        Map<String, Object> view = service.create(payload);
        assertThat(view.get("purpose")).isEqualTo("chat");
        verify(settings).update(eq(ModelProfileService.SETTING_ACTIVE), eq(String.valueOf(view.get("id"))));
    }

    @Test
    void invalidPurposeIsRejectedWithoutDatabaseWrite() {
        assertThatThrownBy(() -> service.create(body("unknown"))).hasMessageContaining("用途");
        verify(mapper, never()).insert(any(ModelProfile.class));
    }

    @Test
    void embeddingCannotBeActivatedOrResolvedForChat() {
        stored.put("vector", profile("vector", "embedding", "http://localhost:11434/v1"));
        assertThatThrownBy(() -> service.activate("vector")).hasMessageContaining("不能设为对话");
        assertThatThrownBy(() -> service.resolve("vector")).hasMessageContaining("不能用于对话");
        assertThatThrownBy(() -> service.requireChatProfile("vector")).hasMessageContaining("只能选择对话");
        verify(settings, never()).update(any(), any());
    }

    @Test
    void activeChatCannotBeConvertedToEmbeddingUntilAnotherChatIsActivated() {
        stored.put("chat", profile("chat", "chat", "https://example.test/v1"));
        when(settings.effective(ModelProfileService.SETTING_ACTIVE)).thenReturn("chat");
        assertThatThrownBy(() -> service.update("chat", Map.of("purpose", "embedding"))).hasMessageContaining("请先激活");
        verify(mapper, never()).update(any(), any());
        assertThat(stored.get("chat").getPurpose()).isEqualTo("chat");
    }

    @Test
    void implicitResolutionAndLocalSelectionSkipEmbeddingProfiles() {
        stored.put("vector", profile("vector", "embedding", "http://localhost:11434/v1"));
        stored.put("chat", profile("chat", null, "https://example.test/v1"));
        when(settings.effective(ModelProfileService.SETTING_ACTIVE)).thenReturn("vector");
        assertThat(service.resolve(null).id()).isEqualTo("chat");
        assertThat(service.firstLocal()).isNull();
        assertThat(service.chatProfiles()).extracting(ModelProfile::getId).containsExactly("chat");
    }

    @Test
    void embeddingLookupIsStrictAndDoesNotUseChatFallback() {
        ModelProfile embedding = profile("vector", "embedding", "http://localhost:11434/v1");
        stored.put("vector", embedding);
        stored.put("chat", profile("chat", "chat", "https://example.test/v1"));
        assertThat(service.embeddingProfile("vector")).isSameAs(embedding);
        assertThatThrownBy(() -> service.embeddingProfile("missing")).hasMessageContaining("不存在");
        assertThatThrownBy(() -> service.embeddingProfile("chat")).hasMessageContaining("用途");
    }

    @Test
    void profileTestUsesEmbeddingClientWithoutChatRequest() {
        stored.put("vector", profile("vector", "embedding", "http://localhost:11434/v1"));
        EmbeddingClient client = mock(EmbeddingClient.class);
        EmbeddingClient.Snapshot snapshot = EmbeddingClient.Snapshot.of("fixture-model", "http://127.0.0.1:1", "ollama", "");
        when(embeddingProvider.getIfAvailable()).thenReturn(client);
        when(client.snapshotForProfile("vector")).thenReturn(snapshot);
        when(client.probe(snapshot)).thenReturn(Map.of("ok", true, "dimension", 3));
        assertThat(service.probe("vector")).containsEntry("dimension", 3);
        verify(client).probe(snapshot);
    }

    private static Map<String, Object> body(String purpose) {
        return Map.of("name", "fixture profile", "provider", "custom", "purpose", purpose,
                "baseUrl", "https://example.test/v1", "model", "fixture-model", "apiKey", "fixture-key-only");
    }

    private static ModelProfile profile(String id, String purpose, String base) {
        ModelProfile profile = new ModelProfile();
        profile.setId(id);
        profile.setName(id);
        profile.setPurpose(purpose);
        profile.setProvider("custom");
        profile.setModel("fixture-model");
        profile.setBaseUrl(base);
        return profile;
    }
}
