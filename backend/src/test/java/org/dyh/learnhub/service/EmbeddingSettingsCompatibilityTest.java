package org.dyh.learnhub.service;

import org.dyh.learnhub.entity.AppSetting;
import org.dyh.learnhub.mapper.AppSettingMapper;
import org.junit.jupiter.api.Test;
import org.springframework.core.env.SystemEnvironmentPropertySource;
import org.springframework.mock.env.MockEnvironment;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class EmbeddingSettingsCompatibilityTest {
    @Test
    void legacyEnvironmentNamesStillResolveButDoNotImplicitlyEnableEmbedding() {
        AppSettingMapper mapper = mock(AppSettingMapper.class);
        MockEnvironment environment = new MockEnvironment();
        environment.getPropertySources().addFirst(new SystemEnvironmentPropertySource("fixture", Map.of(
                "KB_EMBED_BASE_URL", "http://legacy.invalid:11434", "KB_EMBED_MODEL", "legacy-model",
                "KB_VECTOR_ENABLED", "0")));
        SettingsService settings = new SettingsService(mapper, environment);
        assertThat(settings.effective(SettingsService.KEY_EMBED_BASE_URL)).isEqualTo("http://legacy.invalid:11434");
        assertThat(settings.effective(SettingsService.KEY_EMBED_MODEL)).isEqualTo("legacy-model");
        assertThat(settings.vectorEnabled()).isFalse();
        assertThat(settings.effective("ai.model_for_embed")).isEqualTo("disabled");
    }

    @Test
    void databaseRouteOverridesEnvironmentAndDefaultOnlyAppliesWithoutOverrides() {
        AppSettingMapper mapper = mock(AppSettingMapper.class);
        MockEnvironment environment = new MockEnvironment().withProperty("ai.model-for-embed", "legacy");
        SettingsService settings = new SettingsService(mapper, environment);
        AppSetting setting = new AppSetting();
        setting.setSettingKey("ai.model_for_embed");
        setting.setSettingValue("fixture-vector-profile");
        when(mapper.selectById("ai.model_for_embed")).thenReturn(setting).thenReturn(null);
        assertThat(settings.effective("ai.model_for_embed")).isEqualTo("fixture-vector-profile");
        assertThat(settings.effective("ai.model_for_embed")).isEqualTo("legacy");
    }
}
