package org.dyh.learnhub.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.dyh.learnhub.ai.AgentService;
import org.dyh.learnhub.common.GlobalExceptionHandler;
import org.dyh.learnhub.service.AgentSessionService;
import org.dyh.learnhub.service.SkillService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** Standalone MVC only: no application context, database, tools, or model requests. */
class AgentActionStatusControllerTest {
    private final AgentService agent = mock(AgentService.class);
    private final AgentSessionService sessions = mock(AgentSessionService.class);
    private final SkillService skills = mock(SkillService.class);
    private final AiController controller = new AiController(agent, sessions, skills);
    private final MockMvc mvc = MockMvcBuilders.standaloneSetup(controller)
            .setControllerAdvice(new GlobalExceptionHandler()).build();
    private final ObjectMapper json = new ObjectMapper();

    @AfterEach void shutdown() { controller.shutdownStreamPool(); }

    @ParameterizedTest
    @ValueSource(strings = {"pending", "executing", "approved", "rejected", "failed"})
    void getReturnsTheReadOnlyProjectionForEveryActionState(String state) throws Exception {
        Map<String, Object> projection = new LinkedHashMap<>();
        projection.put("id", 42L);
        projection.put("status", state);
        projection.put("toolName", "edit_note");
        projection.put("summary", "更新合成笔记");
        projection.put("createdAt", "2026-10-14T08:30:00");
        projection.put("resolvedAt", "2026-10-14T08:31:00");
        when(agent.pendingActionStatus(42L)).thenReturn(projection);

        String body = mvc.perform(get("/api/ai/actions/42"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.id").value(42))
                .andExpect(jsonPath("$.data.status").value(state))
                .andExpect(jsonPath("$.data.toolName").value("edit_note"))
                .andExpect(jsonPath("$.data.summary").value("更新合成笔记"))
                .andExpect(jsonPath("$.data.createdAt").exists())
                .andExpect(jsonPath("$.data.resolvedAt").exists())
                .andExpect(jsonPath("$.data.sessionId").doesNotExist())
                .andExpect(jsonPath("$.data.argsJson").doesNotExist())
                .andExpect(jsonPath("$.data.result").doesNotExist())
                .andExpect(jsonPath("$.data.preview").doesNotExist())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);

        assertEquals(6, json.readTree(body).path("data").size());
        verifyOnlyStatusRead(42L);
    }

    @Test void missingActionUsesTheExistingValidationErrorEnvelope() throws Exception {
        when(agent.pendingActionStatus(999L))
                .thenThrow(new IllegalArgumentException("待确认操作不存在: 999"));

        mvc.perform(get("/api/ai/actions/999"))
                // Existing IllegalArgumentException handling uses a business code in an HTTP 200 envelope.
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(400))
                .andExpect(jsonPath("$.msg").value("待确认操作不存在: 999"))
                .andExpect(jsonPath("$.data").doesNotExist());

        verifyOnlyStatusRead(999L);
    }

    private void verifyOnlyStatusRead(Long id) {
        verify(agent).pendingActionStatus(id);
        verifyNoMoreInteractions(agent);
        verifyNoInteractions(sessions, skills);
    }
}
