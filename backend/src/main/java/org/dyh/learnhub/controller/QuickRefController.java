package org.dyh.learnhub.controller;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.dyh.learnhub.common.Result;
import org.dyh.learnhub.dto.QuickRefDTO;
import org.dyh.learnhub.service.QuickRefService;
import org.dyh.learnhub.vo.QuickRefVO;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/quick-refs")
@RequiredArgsConstructor
public class QuickRefController {

    private final QuickRefService quickRefService;

    @GetMapping
    public Result<List<QuickRefVO>> list(@RequestParam(required = false) Long categoryId,
                                         @RequestParam(required = false) String kw) {
        return Result.ok(quickRefService.list(categoryId, kw));
    }

    @GetMapping("/{id}")
    public Result<QuickRefVO> detail(@PathVariable Long id) {
        return Result.ok(quickRefService.detail(id));
    }

    @PostMapping
    public Result<QuickRefVO> save(@Valid @RequestBody QuickRefDTO dto) {
        return Result.ok(quickRefService.save(dto));
    }

    @PutMapping("/{id}")
    public Result<QuickRefVO> update(@PathVariable Long id, @Valid @RequestBody QuickRefDTO dto) {
        return Result.ok(quickRefService.update(id, dto));
    }

    @DeleteMapping("/{id}")
    public Result<Void> delete(@PathVariable Long id) {
        quickRefService.delete(id);
        return Result.ok();
    }
}
