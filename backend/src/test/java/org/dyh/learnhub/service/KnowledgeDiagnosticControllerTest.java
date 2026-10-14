package org.dyh.learnhub.service;

import org.dyh.learnhub.controller.KnowledgeDiagnosticController;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class KnowledgeDiagnosticControllerTest {
    @Test
    void casesAndInspectionExposeStableJsonRoutesWithoutSpringDatabaseStartup() throws Exception {
        var service = mock(KnowledgeDiagnosticService.class);
        when(service.cases()).thenReturn(Map.of("cases", List.of(new KnowledgeDiagnosticService.DiagnosticCase(
                "synthetic", "合成用例", "exact", "线程池", "合成描述", List.of("note:900001"))), "corpusSummary", Map.of("sources", 5)));
        when(service.inspect(any())).thenReturn(Map.of("dataset", "current", "hits", List.of(), "warnings", List.of()));
        var mvc = MockMvcBuilders.standaloneSetup(new KnowledgeDiagnosticController(service)).build();
        mvc.perform(get("/api/kb/diagnostics/cases"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.cases[0].expectedSourceRefs[0]").value("note:900001"));
        mvc.perform(post("/api/kb/diagnostics").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"dataset\":\"current\",\"question\":\"线程池\",\"mode\":\"keyword\",\"includeWiki\":false,\"includeGraph\":true}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.dataset").value("current"));
        var capture = ArgumentCaptor.forClass(KnowledgeDiagnosticService.Request.class);
        verify(service).inspect(capture.capture());
        assertEquals("线程池", capture.getValue().question());
        assertEquals(false, capture.getValue().includeWiki()); assertEquals(true, capture.getValue().includeGraph());
        assertNull(capture.getValue().topK()); assertNull(capture.getValue().includeContext());
    }

    @Test
    void unexpectedServiceErrorsNeverExposeTheirMessages() throws Exception {
        var service = mock(KnowledgeDiagnosticService.class);
        when(service.inspect(any())).thenThrow(new IllegalStateException("credential-token-must-not-leak"));
        var mvc = MockMvcBuilders.standaloneSetup(new KnowledgeDiagnosticController(service)).build();
        mvc.perform(post("/api/kb/diagnostics").contentType(MediaType.APPLICATION_JSON).content("{\"question\":\"合成\"}"))
                .andExpect(jsonPath("$.code").value(500)).andExpect(jsonPath("$.msg").value("检索诊断暂时不可用，请稍后重试"));
    }
}
