package org.dyh.learnhub.controller;

import org.dyh.learnhub.service.KnowledgeMaintenanceService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;
import java.util.Map;

import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class KnowledgeMaintenanceControllerTest {
    @Test void getIsReadOnlyAndExposesTheFixedStatusContract() throws Exception {
        var service = mock(KnowledgeMaintenanceService.class);
        when(service.status()).thenReturn(statusView());
        var mvc = MockMvcBuilders.standaloneSetup(new KnowledgeMaintenanceController(service)).build();
        mvc.perform(get("/api/knowledge/maintenance")).andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.enabled").value(true))
                .andExpect(jsonPath("$.data.phaseLabel").value("等待维护"))
                .andExpect(jsonPath("$.data.steps[0].key").value("index"))
                .andExpect(jsonPath("$.data.steps[0].status").value("pending"));
        verify(service).status();
        verifyNoMoreInteractions(service);
    }

    @Test void putAcceptsOnlyTheBooleanSwitchAndPostQueuesOneManualRun() throws Exception {
        var service = mock(KnowledgeMaintenanceService.class);
        when(service.setEnabled(false)).thenReturn(Map.of("enabled", false, "pending", true, "running", false));
        when(service.requestRun()).thenReturn(Map.of("enabled", false, "pending", true, "running", false));
        var mvc = MockMvcBuilders.standaloneSetup(new KnowledgeMaintenanceController(service)).build();
        mvc.perform(put("/api/knowledge/maintenance").contentType(MediaType.APPLICATION_JSON).content("{\"enabled\":false}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.enabled").value(false));
        mvc.perform(post("/api/knowledge/maintenance/run")).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.pending").value(true)).andExpect(jsonPath("$.data.running").value(false));
        verify(service).setEnabled(false);
        verify(service).requestRun();
        verifyNoMoreInteractions(service);
    }

    @ParameterizedTest
    @ValueSource(strings = {"{}", "{\"enabled\":\"false\"}", "{\"enabled\":0}", "{\"enabled\":null}",
            "{\"enabled\":true,\"force\":true}", "{\"enable\":true}", "null"})
    void putRejectsUnknownFieldsNullAndNonBooleanValues(String body) throws Exception {
        var service = mock(KnowledgeMaintenanceService.class);
        var mvc = MockMvcBuilders.standaloneSetup(new KnowledgeMaintenanceController(service)).build();
        var result = mvc.perform(put("/api/knowledge/maintenance").contentType(MediaType.APPLICATION_JSON).content(body));
        if ("null".equals(body)) result.andExpect(status().isBadRequest());
        else result.andExpect(status().isOk()).andExpect(jsonPath("$.code").value(400));
        verify(service, never()).setEnabled(anyBoolean());
        verifyNoInteractions(service);
    }

    private static Map<String, Object> statusView() {
        return Map.ofEntries(Map.entry("enabled", true), Map.entry("running", false), Map.entry("pending", true),
                Map.entry("phase", "queued"), Map.entry("phaseLabel", "等待维护"), Map.entry("message", "合成排队状态"),
                Map.entry("lastStartedAt", ""), Map.entry("lastCompletedAt", ""), Map.entry("nextRunAt", ""),
                Map.entry("lastError", ""), Map.entry("steps", List.of(Map.of("key", "index", "label", "更新检索索引",
                        "status", "pending", "message", "等待维护"))));
    }
}
