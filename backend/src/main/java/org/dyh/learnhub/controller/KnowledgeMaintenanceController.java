package org.dyh.learnhub.controller;

import org.dyh.learnhub.common.Result;
import org.dyh.learnhub.service.KnowledgeMaintenanceService;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/knowledge/maintenance")
public class KnowledgeMaintenanceController {
    private final KnowledgeMaintenanceService maintenance;
    public KnowledgeMaintenanceController(KnowledgeMaintenanceService maintenance) { this.maintenance = maintenance; }

    @GetMapping
    public Result<Map<String, Object>> get() { return Result.ok(maintenance.status()); }

    @PutMapping
    public Result<Map<String, Object>> update(@RequestBody Map<String, Object> body) {
        if (body == null || body.size() != 1 || !(body.get("enabled") instanceof Boolean enabled))
            return Result.error(400, "仅接受 enabled 布尔值");
        return Result.ok(maintenance.setEnabled(enabled));
    }

    @PostMapping("/run")
    public Result<Map<String, Object>> run() { return Result.ok(maintenance.requestRun()); }
}
