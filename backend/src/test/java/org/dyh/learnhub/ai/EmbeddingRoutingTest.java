package org.dyh.learnhub.ai;

import org.dyh.learnhub.controller.SettingsController;
import org.dyh.learnhub.controller.ModelController;
import org.dyh.learnhub.entity.ModelProfile;
import org.dyh.learnhub.service.AgentSessionService;
import org.dyh.learnhub.service.ModelDiscoveryService;
import org.dyh.learnhub.service.ModelProfileService;
import org.dyh.learnhub.service.SettingsService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class EmbeddingRoutingTest {
    private final SettingsService settings = mock(SettingsService.class);
    private final ModelProfileService profiles = mock(ModelProfileService.class);
    private ModelRouting routing;
    private ModelProfile chat, embedding;

    @BeforeEach
    void setup() {
        chat = profile("chat", "chat", "https://example.test/v1");
        embedding = profile("vector", "embedding", "http://localhost:11434/v1");
        when(profiles.all()).thenReturn(List.of(embedding, chat));
        when(profiles.activeId()).thenReturn("chat");
        routing = new ModelRouting(settings, profiles);
    }

    @Test
    void embeddingHasOwnOptionalDefaultAndChatFallbackExcludesEmbedding() {
        assertThat(ModelRouting.allTasks()).contains("embed");
        assertThat(SettingsService.knownKeys()).contains("ai.model_for_embed");
        assertThat(routing.targetIdOf("embed")).isEqualTo("disabled");
        assertThat(routing.targets()).extracting(ModelRouting.ModelTarget::id).containsExactly("chat");
        assertThat(routing.firstLocal()).isNull();
        assertThat(routing.targetIdOf("wiki")).isEqualTo("chat");
        assertThatThrownBy(() -> routing.forProfile("vector")).hasMessageContaining("不能用于对话");
    }

    @Test
    void routeOnlyAcceptsEmbeddingProfileAndExplicitLegacy() {
        when(settings.effective("ai.model_for_embed")).thenReturn("vector", "chat", "missing", "legacy");
        assertThat(routing.targetIdOf("embed")).isEqualTo("vector");
        assertThat(routing.targetIdOf("embed")).isEqualTo("disabled");
        assertThat(routing.targetIdOf("embed")).isEqualTo("disabled");
        assertThat(routing.targetIdOf("embed")).isEqualTo("legacy");
    }

    @Test
    @SuppressWarnings("unchecked")
    void routingRowExposesOnlyEmbeddingTargetsAndNoKeys() {
        embedding.setApiKey("fixture-key-only");
        when(settings.effective("ai.model_for_embed")).thenReturn("vector");
        Map<String, Object> row = routing.table().stream().filter(r -> "embed".equals(r.get("task"))).findFirst().orElseThrow();
        assertThat(row).containsEntry("field", "modelForEmbed").containsEntry("configured", true)
                .containsEntry("target", "vector").containsEntry("effectiveModel", "fixture-model");
        List<Map<String, Object>> options = (List<Map<String, Object>>) row.get("targets");
        assertThat(options).extracting(option -> option.get("id")).containsExactly("disabled", "legacy", "vector");
        assertThat(row.toString()).doesNotContain("fixture-key-only");
    }

    @Test
    void deletedOrReclassifiedEmbeddingRouteBecomesDisabled() {
        when(settings.effective("ai.model_for_embed")).thenReturn("vector");
        embedding.setPurpose("chat");
        assertThat(routing.targetIdOf("embed")).isEqualTo("disabled");
        Map<String, Object> row = routing.table().stream().filter(r -> "embed".equals(r.get("task"))).findFirst().orElseThrow();
        assertThat(row).containsEntry("configured", false).containsEntry("target", "disabled");
    }

    @Test
    @SuppressWarnings("unchecked")
    void invalidSelectedAddressDoesNotHideOtherEmbeddingOptions() {
        embedding.setBaseUrl("invalid-address");
        ModelProfile other = profile("other-vector", "embedding", "http://localhost:11434");
        when(profiles.all()).thenReturn(List.of(embedding, other, chat));
        when(settings.effective("ai.model_for_embed")).thenReturn("vector");
        Map<String, Object> row = routing.table().stream().filter(r -> "embed".equals(r.get("task"))).findFirst().orElseThrow();
        assertThat(row).containsEntry("configured", false);
        List<Map<String, Object>> options = (List<Map<String, Object>>) row.get("targets");
        assertThat(options).extracting(option -> option.get("id"))
                .containsExactly("disabled", "legacy", "vector", "other-vector");
    }

    @Test
    void sessionEntrypointsRejectEmbeddingBeforeWritingSession() {
        doThrow(new IllegalArgumentException("对话只能选择对话模型档案"))
                .when(profiles).requireChatProfile("vector");
        AgentSessionService sessions = mock(AgentSessionService.class);
        var controller = new ModelController(profiles, routing, sessions, mock(ModelDiscoveryService.class));
        assertThatThrownBy(() -> controller.newSession(Map.of("modelProfileId", "vector")))
                .hasMessageContaining("只能选择对话");
        assertThatThrownBy(() -> controller.setSessionModel("session", Map.of("profileId", "vector")))
                .hasMessageContaining("只能选择对话");
        verifyNoInteractions(sessions);
    }

    @Test
    void defaultSessionSelectionKeepsFollowingChatRouting() {
        AgentSessionService sessions = mock(AgentSessionService.class);
        when(sessions.createNew(null, null)).thenReturn(Map.of("id", "session"));
        var controller = new ModelController(profiles, routing, sessions, mock(ModelDiscoveryService.class));
        controller.newSession(null);
        assertThat(controller.setSessionModel("session", Map.of("profileId", "")).getData())
                .isInstanceOfSatisfying(Map.class, data -> assertThat(data).containsKey("profileId"));
        verify(sessions).createNew(null, null);
        verify(sessions).setModelProfile("session", "");
    }

    @Test
    void settingsRejectsChatProfileForEmbeddingWithoutWriting() {
        when(profiles.embeddingProfile("chat")).thenThrow(new IllegalArgumentException("用途不是向量嵌入"));
        var controller = new SettingsController(settings, profiles);
        assertThat(controller.update(Map.of("modelForEmbed", "chat")).getCode()).isEqualTo(400);
        verify(settings, never()).updateAll(any());
    }

    @Test
    void settingsMapsEmbeddingRouteAndPreservesExistingReservedChoices() {
        var controller = new SettingsController(settings, profiles);
        controller.update(Map.of("modelForEmbed", "legacy", "modelForWiki", "local"));
        verify(settings).updateAll(Map.of("ai.model_for_embed", "legacy", "ai.model_for_wiki", "local"));
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
