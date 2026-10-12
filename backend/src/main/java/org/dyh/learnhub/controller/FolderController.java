package org.dyh.learnhub.controller;

import lombok.RequiredArgsConstructor;
import org.dyh.learnhub.common.Result;
import org.dyh.learnhub.dto.FolderMoveRequest;
import org.dyh.learnhub.dto.FolderRequest;
import org.dyh.learnhub.service.FolderService;
import org.dyh.learnhub.vo.FolderVO;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/folders")
@RequiredArgsConstructor
public class FolderController {
    private final FolderService service;

    @GetMapping
    public Result<List<FolderVO>> list(@RequestParam String kind) {
        return Result.ok(service.list(kind));
    }

    @PostMapping
    public Result<FolderVO> create(@RequestBody FolderRequest request) {
        return Result.ok(service.create(request));
    }

    @PutMapping("/{id}")
    public Result<FolderVO> update(@PathVariable Long id, @RequestBody FolderRequest request) {
        return Result.ok(service.update(id, request));
    }

    @DeleteMapping("/{id}")
    public Result<Map<String, Integer>> delete(@PathVariable Long id) {
        return Result.ok(Map.of("unfiled", service.delete(id)));
    }

    @PostMapping("/move-items")
    public Result<Map<String, Integer>> moveItems(@RequestBody FolderMoveRequest request) {
        return Result.ok(Map.of("moved", service.moveItems(request)));
    }
}
