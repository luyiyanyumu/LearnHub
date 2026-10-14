package org.dyh.learnhub.controller;

import lombok.RequiredArgsConstructor;
import org.dyh.learnhub.common.Result;
import org.dyh.learnhub.service.KnowledgeDiagnosticService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/api/kb/diagnostics")
@RequiredArgsConstructor
public class KnowledgeDiagnosticController {
    private final KnowledgeDiagnosticService diagnostics;

    @GetMapping("/cases")
    public Result<Map<String, Object>> cases() { return Result.ok(diagnostics.cases()); }

    @PostMapping
    public Result<Map<String, Object>> inspect(@RequestBody KnowledgeDiagnosticService.Request request) {
        try { return Result.ok(diagnostics.inspect(request)); }
        catch (IllegalArgumentException validation) { throw validation; }
        catch (Exception unavailable) { return Result.error(500, "检索诊断暂时不可用，请稍后重试"); }
    }
}
