package org.dyh.learnhub.controller;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.dyh.learnhub.common.PageResult;
import org.dyh.learnhub.common.Result;
import org.dyh.learnhub.dto.NoteDTO;
import org.dyh.learnhub.service.NoteService;
import org.dyh.learnhub.vo.NoteVO;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/notes")
@RequiredArgsConstructor
public class NoteController {

    private final NoteService noteService;

    @GetMapping
    public Result<PageResult<NoteVO>> page(@RequestParam(required = false) Long categoryId,
                                           @RequestParam(required = false) Long tagId,
                                           @RequestParam(required = false) String kw,
                                           @RequestParam(defaultValue = "1") long page,
                                           @RequestParam(defaultValue = "10") long size) {
        return Result.ok(noteService.page(categoryId, tagId, kw, page, size));
    }

    @GetMapping("/{id}")
    public Result<NoteVO> detail(@PathVariable Long id) {
        return Result.ok(noteService.detail(id));
    }

    @PostMapping
    public Result<NoteVO> save(@Valid @RequestBody NoteDTO dto) {
        return Result.ok(noteService.save(dto));
    }

    @PutMapping("/{id}")
    public Result<NoteVO> update(@PathVariable Long id, @Valid @RequestBody NoteDTO dto) {
        return Result.ok(noteService.update(id, dto));
    }

    @DeleteMapping("/{id}")
    public Result<Void> delete(@PathVariable Long id) {
        noteService.delete(id);
        return Result.ok();
    }
}
