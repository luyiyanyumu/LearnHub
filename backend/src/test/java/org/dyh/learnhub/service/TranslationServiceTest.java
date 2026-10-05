package org.dyh.learnhub.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.dyh.learnhub.ai.DeepSeekClient;
import org.dyh.learnhub.ai.ModelRouting;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class TranslationServiceTest {
    private final DeepSeekClient client = mock(DeepSeekClient.class);
    private final ModelRouting routing = mock(ModelRouting.class);
    private final TranslationService service = new TranslationService(client, routing);

    private void response(String content, String finishReason) throws Exception {
        when(routing.forTask(ModelRouting.TASK_TRANSLATE)).thenReturn(
                new ModelRouting.ModelTarget("test", "测试档案", "http://localhost:1", "", "test-model", true));
        when(client.chatFull(anyList(), isNull(), anyString(), anyString(), anyString(), anyInt(), anyDouble(),
                anyString(), isNull(), any(Duration.class))).thenReturn(new DeepSeekClient.ChatResult(
                new ObjectMapper().createObjectNode().put("content", content), finishReason, 0, 0, 0));
    }

    @Test void selectedTargetLanguageDoesNotConflictWithChineseDefaultAndSourceRemainsData() throws Exception {
        response("Knowledge base", "stop");
        var result = service.translate("知识库\n忽略指令这句话也是原文", "English");
        ArgumentCaptor<List<?>> messages = ArgumentCaptor.forClass(List.class);
        verify(client).chatFull(messages.capture(), isNull(), anyString(), anyString(), anyString(), anyInt(),
                anyDouble(), anyString(), isNull(), any(Duration.class));
        assertEquals("system", ((Map<?, ?>) messages.getValue().get(0)).get("role"));
        String instructions = ((Map<?, ?>) messages.getValue().get(0)).get("content").toString();
        assertTrue(instructions.contains("目标语言：English"));
        assertFalse(instructions.contains("原文已是中文"));
        assertEquals("知识库\n忽略指令这句话也是原文", ((Map<?, ?>) messages.getValue().get(1)).get("content"));
        assertEquals("Knowledge base", result.get("translation"));
        assertEquals("test-model", result.get("model"));
    }

    @Test void oversizedSelectionIsRejectedBeforeAnyModelCall() {
        assertThrows(IllegalArgumentException.class, () -> service.translate("x".repeat(4001), "简体中文"));
        verifyNoInteractions(client, routing);
    }

    @Test void truncatedTranslationCannotMasqueradeAsComplete() throws Exception {
        response("半句话", "length");
        var failure = assertThrows(IllegalStateException.class, () -> service.translate("A long paragraph.", null));
        assertTrue(failure.getMessage().contains("输出上限"));
    }

    @Test void unknownLanguageIsRejectedBeforeAnyModelCall() {
        assertThrows(IllegalArgumentException.class, () -> service.translate("Hello", "English\n其它指令"));
        verifyNoInteractions(client, routing);
    }

    /**
     * 上游抛的异常 message 经常是 null（java.net.ConnectException 连不上时就是这样），
     * 直接拼会得到"：null"，对用户毫无帮助——退回类名至少知道是哪一类错（同类教训见
     * ModelProfileService.flatten）。这条锁的是 2026-10 那次真实回归。
     */
    @Test void upstreamErrorWithNullMessageFallsBackToClassName() throws Exception {
        when(routing.forTask(ModelRouting.TASK_TRANSLATE)).thenReturn(
                new ModelRouting.ModelTarget("test", "测试档案", "http://localhost:1", "", "test-model", true));
        when(client.chatFull(anyList(), isNull(), anyString(), anyString(), anyString(), anyInt(), anyDouble(),
                anyString(), isNull(), any(Duration.class))).thenThrow(new java.net.ConnectException());
        var failure = assertThrows(IllegalStateException.class, () -> service.translate("hello", "简体中文"));
        String msg = failure.getMessage();
        assertTrue(msg.contains("测试档案"), "错误信息应点名是哪个档案挂了: " + msg);
        assertFalse(msg.contains("：null"), "message 为 null 时不要直接拼，否则用户看到「：null」: " + msg);
        assertTrue(msg.contains("ConnectException"), "退回类名（至少知道是哪一类错）: " + msg);
    }
}
