package org.dyh.learnhub.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.dyh.learnhub.dto.AiChatRequest;
import org.dyh.learnhub.entity.AgentPendingAction;
import org.dyh.learnhub.service.AgentSessionService;
import org.dyh.learnhub.service.NoteService;
import org.dyh.learnhub.vo.AiChatVO;
import org.dyh.learnhub.vo.NoteEditVO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class AgentNoteEditTest {
    @Spy ObjectMapper json = new ObjectMapper();
    @Mock NoteService notes;
    @Mock AgentSessionService sessions;
    @InjectMocks AgentService agent;
    private static final String ARGS = """
            {"note_id":12,"expected_hash":"old-version","operations":[{"action":"delete","text":"旧"}]}
            """;
    private NoteEditVO preview(boolean changed) {
        return new NoteEditVO(12L, "标题", "old-version", "new-version", changed,
                List.of(new NoteEditVO.Change("delete", "删除文字", 1, "旧知识", "知识")));
    }
    @BeforeEach void useSpyByFieldName() { ReflectionTestUtils.setField(agent, "objectMapper", json); }

    @Test void validatedPreviewIsStoredAndRestoredWithoutWritingNote() throws Exception {
        when(notes.previewEdit(eq(12L), any())).thenReturn(preview(true));
        when(sessions.stageAction(eq("s1"), eq("edit_note"), anyString(), anyString())).thenAnswer(inv -> {
            var a = new AgentPendingAction();
            a.setId(8L); a.setToolName("edit_note"); a.setArgsJson(inv.getArgument(2)); a.setSummary(inv.getArgument(3));
            return a;
        });
        AiChatVO vo = new AiChatVO();
        assertTrue(json.readTree(agent.stageNoteEdit(ARGS, "s1", vo, null)).path("staged").asBoolean());
        assertEquals(1, vo.getPendingActions().size());
        JsonNode persistedPreview = (JsonNode) vo.getPendingActions().getFirst().get("preview");
        assertEquals("旧知识", persistedPreview.path("changes").get(0).path("before").asText());
        verify(notes, never()).editContent(anyLong(), any());
    }

    @Test void ambiguousPreviewFailureDoesNotCreateDeadConfirmationCard() {
        when(notes.previewEdit(eq(12L), any())).thenThrow(new IllegalArgumentException("原文匹配 3 处"));
        assertThrows(IllegalArgumentException.class, () -> agent.stageNoteEdit(ARGS, "s1", new AiChatVO(), null));
        verifyNoInteractions(sessions);
    }

    @Test void unsavedCurrentNoteCannotStageOldPersistedContent() {
        var ctx = new AiChatRequest(); ctx.setNoteId(12L); ctx.setNoteDirty(true);
        assertThrows(IllegalArgumentException.class, () -> agent.stageNoteEdit(ARGS, "s1", new AiChatVO(), ctx));
        verifyNoInteractions(notes, sessions);
    }

    @Test void alreadyCorrectContentDoesNotStageAnotherAction() throws Exception {
        when(notes.previewEdit(eq(12L), any())).thenReturn(preview(false));
        assertFalse(json.readTree(agent.stageNoteEdit(ARGS, "s1", new AiChatVO(), null)).path("changed").asBoolean());
        verifyNoInteractions(sessions);
    }

    @Test void approvalReplaysVersionAndReportsActualFailure() {
        var action = new AgentPendingAction();
        action.setId(8L); action.setSessionId("s1"); action.setStatus("pending"); action.setToolName("edit_note");
        action.setArgsJson(ARGS); action.setSummary("删除旧字");
        when(sessions.getAction(8L)).thenReturn(action);
        when(notes.editContent(eq(12L), any())).thenThrow(new IllegalArgumentException("笔记正文已变化"));
        var result = agent.executePendingAction(8L);
        assertEquals(false, result.get("ok"));
        assertEquals("笔记正文已变化", result.get("error"));
        assertFalse(result.containsKey("noteEdit"));
        verify(notes).editContent(eq(12L), argThat(r -> r.expectedHash().equals("old-version") && r.operations().size() == 1));
    }

    @Test void toolIsRegisteredWithStructuredOperations() {
        List<?> defs = ReflectionTestUtils.invokeMethod(agent, "toolDefinitions");
        JsonNode schema = defs.stream().map(n -> json.<JsonNode>valueToTree(n)).map(n -> n.path("function"))
                .filter(n -> n.path("name").asText().equals("edit_note")).findFirst().orElseThrow().path("parameters");
        assertEquals("array", schema.path("properties").path("operations").path("type").asText());
        assertTrue(schema.path("properties").path("operations").path("items").path("properties").has("occurrence"));
        assertEquals(true, ReflectionTestUtils.invokeMethod(agent, "requiresApproval", "edit_note"));
    }
}
