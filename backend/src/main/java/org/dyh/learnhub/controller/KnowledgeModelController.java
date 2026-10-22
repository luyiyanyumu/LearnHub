package org.dyh.learnhub.controller;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.dyh.learnhub.common.Result;
import org.dyh.learnhub.service.KnowledgeModelService;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/** Local specialist model monitoring and an explicit model-to-model query entry point. */
@RestController
@RequestMapping("/api/knowledge-model")
public class KnowledgeModelController {
    private final KnowledgeModelService model;
    public KnowledgeModelController(KnowledgeModelService model) { this.model = model; }

    public record SettingsRequest(@NotNull Boolean enabled) { }
    public record QueryRequest(@NotBlank @Size(max = 5000) String question,
                               @Min(1) @Max(512) Integer max_tokens) { }

    @GetMapping("/status")
    public Result<Map<String, Object>> status() { return Result.ok(model.status()); }

    @GetMapping("/jobs")
    public Result<Map<String, Object>> jobs() { return Result.ok(model.jobs()); }

    @PostMapping("/jobs/{id}/retry")
    public Result<Map<String, Object>> retry(@PathVariable Long id) { return Result.ok(model.retry(id)); }

    @PostMapping("/files/{id}/enqueue")
    public Result<Map<String, Object>> enqueue(@PathVariable Long id) { return Result.ok(model.enqueueFile(id)); }

    @PutMapping("/settings")
    public Result<Map<String, Object>> settings(@Valid @RequestBody SettingsRequest body) {
        return Result.ok(model.setEnabled(body.enabled()));
    }

    @PostMapping("/query")
    public Result<Map<String, Object>> query(@Valid @RequestBody QueryRequest body) {
        return Result.ok(model.query(body.question(), body.max_tokens()));
    }

    @ExceptionHandler(KnowledgeModelService.ServiceUnavailableException.class)
    public Result<Void> offline(KnowledgeModelService.ServiceUnavailableException unavailable) {
        return Result.error(503, unavailable.getMessage());
    }
}
