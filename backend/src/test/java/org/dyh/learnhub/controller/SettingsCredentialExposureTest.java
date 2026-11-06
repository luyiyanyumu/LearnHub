package org.dyh.learnhub.controller;

import org.dyh.learnhub.entity.AppSetting;
import org.dyh.learnhub.mapper.AppSettingMapper;
import org.dyh.learnhub.service.SettingsService;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class SettingsCredentialExposureTest {
    @Test
    void diagnosticDefaultsAndSafeSubsetKeepTheirSourceDetails() {
        var mapper = mock(AppSettingMapper.class);
        when(mapper.selectById(SettingsService.KEY_MODEL))
                .thenReturn(setting(SettingsService.KEY_MODEL, "synthetic-db-model"));
        var environment = new MockEnvironment().withProperty("ai.model", "synthetic-env-model");
        var controller = new SettingsController(new SettingsService(mapper, environment));

        var defaults = controller.effective(null);
        assertThat(defaults.getCode()).isEqualTo(200);
        assertThat(defaults.getData()).extracting(row -> row.get("key")).containsExactly(
                "ai.embed_base_url", "ai.embed_model", "kb.vector_enabled",
                "ai.model", "ai.base_url", "kb.vector_backend");

        var subset = controller.effective(" ai.model ");
        assertThat(subset.getCode()).isEqualTo(200);
        assertThat(subset.getData()).containsExactly(Map.of(
                "key", "ai.model", "envName", "AI_MODEL", "canonical", "ai.model",
                "fromDb", "synthetic-db-model", "fromExternal", "synthetic-env-model",
                "effective", "synthetic-db-model"));
    }

    @Test
    void diagnosticRejectsEveryRequestContainingAKeyOutsideTheAllowlistBeforeReading() {
        var settings = mock(SettingsService.class);
        var controller = new SettingsController(settings);
        for (String key : List.of("ai.api_key", "ai.wiki_api_key", "kb.milvus_token", "spring.datasource.password")) {
            var result = controller.effective("ai.model," + key);
            assertThat(result.getCode()).isEqualTo(400);
            assertThat(result.getMsg()).contains("ai.model").doesNotContain(key);
            assertThat(result.getData()).isNull();
        }
        verifyNoInteractions(settings);
    }

    @Test
    void readAndWriteSnapshotsHideWikiCredentialWhileExplicitClearStillWorks() {
        var mapper = mock(AppSettingMapper.class);
        when(mapper.selectById(SettingsService.KEY_WIKI_API_KEY))
                .thenReturn(setting(SettingsService.KEY_WIKI_API_KEY, "synthetic-wiki-credential"));
        var settings = new SettingsService(mapper, new MockEnvironment());
        var controller = new SettingsController(settings);

        assertThat(controller.get().getData())
                .containsEntry("wikiApiKey", "").containsEntry("hasWikiApiKey", true);
        var updated = controller.update(Map.of("wikiModel", "synthetic-wiki-model"));
        assertThat(updated.getCode()).isEqualTo(200);
        assertThat(updated.getData())
                .containsEntry("wikiApiKey", "").containsEntry("hasWikiApiKey", true);
        assertThat(settings.effective(SettingsService.KEY_WIKI_API_KEY)).isEqualTo("synthetic-wiki-credential");
        verify(mapper, never()).deleteById(SettingsService.KEY_WIKI_API_KEY);

        assertThat(controller.update(Map.of("wikiApiKey", "")).getCode()).isEqualTo(200);
        verify(mapper).deleteById(SettingsService.KEY_WIKI_API_KEY);
    }

    private static AppSetting setting(String key, String value) {
        var setting = new AppSetting();
        setting.setSettingKey(key);
        setting.setSettingValue(value);
        return setting;
    }
}
