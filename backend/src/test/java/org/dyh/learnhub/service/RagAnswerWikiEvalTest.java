package org.dyh.learnhub.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.dyh.learnhub.ai.DeepSeekClient;
import org.dyh.learnhub.ai.ModelRouting;
import org.dyh.learnhub.entity.RagEval;
import org.dyh.learnhub.entity.RagEvalAnswerRun;
import org.dyh.learnhub.mapper.KbIndexStateMapper;
import org.dyh.learnhub.mapper.RagEvalAnswerRunMapper;
import org.dyh.learnhub.mapper.RagEvalMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class RagAnswerWikiEvalTest {
    private final ObjectMapper json = new ObjectMapper();
    private RagEvalMapper cases;
    private RagEvalAnswerRunMapper runs;
    private RagEvalService.Retriever retriever;
    private DeepSeekClient client;
    private ModelRouting routing;
    private GroundingService grounding;
    private SettingsService settings;
    private RagAnswerEvalService service;

    @BeforeEach void setup() {
        cases = mock(RagEvalMapper.class);
        runs = mock(RagEvalAnswerRunMapper.class);
        retriever = mock(RagEvalService.Retriever.class);
        client = mock(DeepSeekClient.class);
        routing = mock(ModelRouting.class);
        grounding = mock(GroundingService.class);
        settings = mock(SettingsService.class);
        service = new RagAnswerEvalService(cases, runs, retriever, client, routing, grounding,
                mock(KbIndexStateMapper.class), settings, json);
    }

    private void caseAndAnswer(String answer) throws Exception {
        RagEval item = new RagEval();
        item.setId(1L); item.setQuestion("AgentRewind 如何回滚？");
        item.setExpectRefs("file:6"); item.setExpectWords("回滚");
        when(cases.selectBatchIds(List.of(1L))).thenReturn(List.of(item));
        when(routing.forTask(ModelRouting.TASK_CHAT)).thenReturn(
                new ModelRouting.ModelTarget("test", "test", "http://unused", "dummy", "mock", true));
        var result = new DeepSeekClient.ChatResult(json.createObjectNode().put("content", answer), "stop", 2, 1, 0);
        when(client.chatFull(anyList(), isNull(), anyString(), anyString(), anyString(), anyInt(),
                anyDouble(), anyString(), isNull(), any())).thenReturn(result);
    }

    @Test void generatedGuideIsSeenByAnswerButExcludedFromGroundingAndBothContextsAreFingerprinted() throws Exception {
        caseAndAnswer("回滚到检查点。");
        String raw = "原文：回滚到已保存的检查点 [资料#6]。";
        String guide = "【Wiki 生成导览】模型整理，未核验。";
        when(retriever.evidenceFor(anyString(), eq(5), eq("fused"), eq(true), eq(false)))
                .thenReturn(new RagEvalService.Evidence(List.of("file:6"), raw + "\n" + guide, raw));
        when(grounding.enabled()).thenReturn(true);
        when(grounding.check(anyString(), eq(raw), anyString())).thenReturn(
                GroundingService.Result.skipped("fixture"));
        service.run("wiki", 5, 1, "fused", List.of(1L), true, false);

        ArgumentCaptor<List<Object>> messages = ArgumentCaptor.forClass(List.class);
        verify(client).chatFull(messages.capture(), isNull(), anyString(), anyString(), anyString(), anyInt(),
                anyDouble(), anyString(), isNull(), any());
        assertTrue(messages.getValue().toString().contains(guide));
        verify(grounding).check("AgentRewind 如何回滚？", raw, "回滚到检查点。");
        ArgumentCaptor<RagEvalAnswerRun> saved = ArgumentCaptor.forClass(RagEvalAnswerRun.class);
        verify(runs).insert(saved.capture());
        var detail = json.readTree(saved.getValue().getDetail()).get(0);
        assertEquals(KgService.sha256(raw + "\n" + guide), detail.path("contextHash").asText());
        assertEquals(KgService.sha256(raw), detail.path("groundingHash").asText());
        assertNotEquals(detail.path("contextHash"), detail.path("groundingHash"));
        verify(settings, never()).wikiInjectEnabled();
        verify(settings, never()).kgInjectEnabled();
        verify(settings, never()).update(anyString(), anyString());
        verify(settings, never()).updateAll(anyMap());
    }

    @Test void wikiOnlyRefusalIsReasonableAndNeverChecksGeneratedBodyAsEvidence() throws Exception {
        caseAndAnswer("材料里没有相关内容，无法回答。");
        when(retriever.evidenceFor(anyString(), eq(5), eq("fused"), eq(true), eq(false)))
                .thenReturn(new RagEvalService.Evidence(List.of(), "【Wiki 生成导览】待回查原文。", ""));
        var result = service.run("wiki-only", 5, 1, "fused", List.of(1L), true, false);
        assertEquals(0, result.get("falseRefusals"));
        verifyNoInteractions(grounding);
        ArgumentCaptor<RagEvalAnswerRun> saved = ArgumentCaptor.forClass(RagEvalAnswerRun.class);
        verify(runs).insert(saved.capture());
        var detail = json.readTree(saved.getValue().getDetail()).get(0);
        assertEquals("na", detail.path("noAnswer").asText());
        assertEquals(0, detail.path("groundingEvidenceChars").asInt());
        assertTrue(detail.path("grounded").isNull());
    }

    @Test void invalidModeFailsBeforeDatabaseSettingsOrModelAccess() {
        assertThrows(IllegalArgumentException.class,
                () -> service.run("invalid", 5, 1, "unsupported", List.of(1L), true, false));
        verifyNoInteractions(cases, runs, retriever, client, routing, grounding, settings);
    }
}
