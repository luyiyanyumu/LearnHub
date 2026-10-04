package org.dyh.learnhub.controller;

import lombok.RequiredArgsConstructor;
import org.dyh.learnhub.common.Result;
import org.dyh.learnhub.service.FormulaRecognitionService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.context.request.async.DeferredResult;

import java.util.Map;
import java.util.concurrent.CompletionException;

@RestController
@RequestMapping("/api/files")
@RequiredArgsConstructor
public class FormulaRecognitionController {
    private final FormulaRecognitionService service;

    @GetMapping("/formula-recognition/capabilities")
    public Result<Map<String, Object>> capabilities() {
        return Result.ok(service.capabilities());
    }

    @PostMapping("/{id}/formula-recognition/accept")
    public Result<Map<String, Object>> accept(
            @PathVariable Long id, @RequestBody FormulaRecognitionService.AcceptRequest request) {
        return Result.ok(service.accept(id, request));
    }

    @PostMapping("/{id}/formula-recognition")
    public DeferredResult<Result<Map<String, Object>>> recognize(
            @PathVariable Long id, @RequestBody FormulaRecognitionService.Request request) {
        // Model inference can take longer than the servlet container's default async timeout.
        var response = new DeferredResult<Result<Map<String, Object>>>(150_000L);
        response.onTimeout(() -> response.setResult(Result.error(504, "公式识别等待超时，请稍后重试；原图仍可查看")));
        service.recognize(id, request).whenComplete((result, failure) -> {
            if (failure == null) {
                response.setResult(Result.ok(result));
                return;
            }
            Throwable cause = failure;
            while (cause instanceof CompletionException && cause.getCause() != null) cause = cause.getCause();
            // Service 的预期错误只包含面向用户的提示，不回显供应商请求/响应。
            if (cause instanceof IllegalArgumentException) response.setResult(Result.error(400, cause.getMessage()));
            else if (cause instanceof IllegalStateException) response.setResult(Result.error(500, cause.getMessage()));
            else response.setResult(Result.error(500, "公式原图识别失败，请重试"));
        });
        return response;
    }
}
