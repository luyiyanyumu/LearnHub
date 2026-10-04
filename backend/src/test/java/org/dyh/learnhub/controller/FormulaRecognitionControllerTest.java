package org.dyh.learnhub.controller;

import org.dyh.learnhub.service.FormulaRecognitionService;
import org.dyh.learnhub.common.Result;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class FormulaRecognitionControllerTest {
    private final FormulaRecognitionService service = mock(FormulaRecognitionService.class);
    private final FormulaRecognitionController controller = new FormulaRecognitionController(service);
    private final FormulaRecognitionService.Request request = new FormulaRecognitionService.Request(1, 10, 20, 200, 100, false);

    @Test void asynchronousResultUsesExistingApiEnvelope() {
        Map<String, Object> result = Map.of("latex", "x_{t}", "status", "recognized");
        when(service.recognize(7L, request)).thenReturn(CompletableFuture.completedFuture(result));
        var response = (Result<?>) controller.recognize(7L, request).getResult();
        assertEquals(200, response.getCode());
        assertSame(result, response.getData());
    }

    @Test void expectedAsyncFailureReturnsUserFacingMessage() {
        when(service.recognize(7L, request)).thenReturn(CompletableFuture.failedFuture(
                new IllegalStateException("请选择支持图片输入的公式模型")));
        var response = (Result<?>) controller.recognize(7L, request).getResult();
        assertEquals(500, response.getCode());
        assertEquals("请选择支持图片输入的公式模型", response.getMsg());
    }

    @Test void unexpectedFailureCannotEchoSensitiveUpstreamResponse() {
        when(service.recognize(7L, request)).thenReturn(CompletableFuture.failedFuture(
                new RuntimeException("data:image/png;base64,SECRET")));
        var response = (Result<?>) controller.recognize(7L, request).getResult();
        assertEquals(500, response.getCode());
        assertFalse(response.getMsg().contains("SECRET"));
    }

    @Test void capabilityFieldsArePassedThrough() {
        Map<String, Object> caps = Map.of("enabled", true, "task", "formula", "model", "test-vision");
        when(service.capabilities()).thenReturn(caps);
        assertEquals(caps, controller.capabilities().getData());
    }

    @Test void pendingModelReleasesRequestUntilItCompletes() {
        var pending = new CompletableFuture<Map<String, Object>>();
        when(service.recognize(7L, request)).thenReturn(pending);
        var response = controller.recognize(7L, request);
        assertNull(response.getResult());
        pending.complete(Map.of("latex", "x_{t+1}"));
        assertEquals("x_{t+1}", ((Map<?, ?>) ((Result<?>) response.getResult()).getData()).get("latex"));
    }

    @Test void confirmationPromotesOnlyTheRequestedCandidateUsingExistingApiEnvelope() {
        var accepted = new FormulaRecognitionService.AcceptRequest(1, 10, 20, 200, 100, "a".repeat(64));
        Map<String, Object> result = Map.of("latex", "x_{t}", "status", "recognized", "cacheKey", "a".repeat(64));
        when(service.accept(7L, accepted)).thenReturn(result);
        var response = controller.accept(7L, accepted);
        assertEquals(200, response.getCode());
        assertSame(result, response.getData());
        verify(service).accept(7L, accepted);
    }
}
