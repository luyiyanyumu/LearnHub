package org.dyh.learnhub.ai;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.dyh.learnhub.entity.AgentPendingAction;
import org.dyh.learnhub.service.AgentSessionService;
import org.dyh.learnhub.service.FileStorageService;
import org.dyh.learnhub.service.NoteService;
import org.dyh.learnhub.service.WebService;
import org.dyh.learnhub.service.WordDocService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Only synthetic stored actions and mocks: status reads cannot execute an action. */
@ExtendWith(MockitoExtension.class)
class AgentActionStatusTest {
    private static final long ACTION_ID = 42L;
    private static final LocalDateTime CREATED = LocalDateTime.of(2026, 10, 14, 8, 30);
    private static final LocalDateTime RESOLVED = CREATED.plusMinutes(1);
    private static final String PRIVATE_SESSION = "synthetic-private-session";
    private static final String PRIVATE_ARGUMENT = "synthetic-secret-action-argument";

    @Mock AgentSessionService sessions;
    @Mock DeepSeekClient client;
    @Mock ModelRouting routing;
    @Mock NoteService notes;
    @Mock FileStorageService files;
    @Mock WordDocService documents;
    @Mock WebService web;
    @Mock ToolEvolutionService evolution;
    @InjectMocks AgentService agent;

    @ParameterizedTest
    @ValueSource(strings = {"pending", "executing", "approved", "rejected", "failed"})
    void everyStoredStateHasOnlyThePublicStatusFields(String status) throws Exception {
        AgentPendingAction stored = action(status);
        stored.setCreatedAt(CREATED);
        boolean terminal = Set.of("approved", "rejected", "failed").contains(status);
        if (terminal) stored.setResolvedAt(RESOLVED);
        when(sessions.getAction(ACTION_ID)).thenReturn(stored);

        Map<String, Object> result = agent.pendingActionStatus(ACTION_ID);

        assertEquals(terminal
                ? Set.of("id", "status", "toolName", "summary", "createdAt", "resolvedAt")
                : Set.of("id", "status", "toolName", "summary", "createdAt"), result.keySet());
        assertEquals(ACTION_ID, result.get("id"));
        assertEquals(status, result.get("status"));
        assertEquals("edit_note", result.get("toolName"));
        assertEquals("更新合成笔记", result.get("summary"));
        assertNotNull(result.get("createdAt"));
        if (terminal) assertNotNull(result.get("resolvedAt"));
        String json = new ObjectMapper().findAndRegisterModules().writeValueAsString(result);
        assertFalse(json.contains(PRIVATE_SESSION));
        assertFalse(json.contains(PRIVATE_ARGUMENT));
        assertFalse(json.contains("synthetic-private-preview"));
        assertEquals(status, stored.getStatus(), "A status lookup must not mutate the stored action");
        verifyOnlyStatusRead(1);
    }

    @Test void absentTimestampsAreOmittedWhileRequiredFieldsRemainPresent() {
        AgentPendingAction stored = action("pending");
        stored.setSummary(null);
        when(sessions.getAction(ACTION_ID)).thenReturn(stored);

        Map<String, Object> result = agent.pendingActionStatus(ACTION_ID);

        assertEquals(Set.of("id", "status", "toolName", "summary"), result.keySet());
        assertTrue(result.containsKey("summary"));
        assertNull(result.get("summary"));
        verifyOnlyStatusRead(1);
    }

    @Test void missingActionIsAValidationFailureWithoutClaimingOrExecutingAnything() {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> agent.pendingActionStatus(ACTION_ID));

        assertTrue(error.getMessage().contains(Long.toString(ACTION_ID)));
        verifyOnlyStatusRead(1);
    }

    @Test void repeatedTerminalReadsDoNotReplayTheStoredSideEffect() {
        AgentPendingAction stored = action("approved");
        when(sessions.getAction(ACTION_ID)).thenReturn(stored);

        Map<String, Object> first = agent.pendingActionStatus(ACTION_ID);
        Map<String, Object> second = agent.pendingActionStatus(ACTION_ID);

        assertEquals(first, second);
        assertEquals("approved", stored.getStatus());
        verifyOnlyStatusRead(2);
    }

    @Test void eachReadObservesTheLatestExecutingOrTerminalSnapshot() {
        when(sessions.getAction(ACTION_ID)).thenReturn(action("executing"), action("approved"));

        assertEquals("executing", agent.pendingActionStatus(ACTION_ID).get("status"));
        assertEquals("approved", agent.pendingActionStatus(ACTION_ID).get("status"));

        verifyOnlyStatusRead(2);
    }

    private AgentPendingAction action(String status) {
        AgentPendingAction action = new AgentPendingAction();
        action.setId(ACTION_ID);
        action.setSessionId(PRIVATE_SESSION);
        action.setToolName("edit_note");
        action.setSummary("更新合成笔记");
        action.setStatus(status);
        action.setArgsJson("{\"token\":\"" + PRIVATE_ARGUMENT
                + "\",\"_edit_preview\":{\"before\":\"synthetic-private-preview\"},"
                + "\"result\":\"synthetic-private-result\"}");
        return action;
    }

    private void verifyOnlyStatusRead(int count) {
        verify(sessions, times(count)).getAction(ACTION_ID);
        verifyNoMoreInteractions(sessions);
        verifyNoInteractions(client, routing, notes, files, documents, web, evolution);
    }
}
