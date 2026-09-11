package org.dyh.learnhub.controller;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.dyh.learnhub.common.Result;
import org.dyh.learnhub.entity.Tag;
import org.dyh.learnhub.service.TagService;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/tags")
@RequiredArgsConstructor
public class TagController {

    private final TagService tagService;

    /** 全部标签（含使用次数） */
    @GetMapping
    public Result<List<Tag>> list() {
        return Result.ok(tagService.list());
    }

    /** 手动新增标签（不存在则创建） */
    @PostMapping
    public Result<Tag> create(@Valid @RequestBody Tag tag) {
        return Result.ok(tagService.findOrCreate(tag.getName()));
    }

    /** 重命名标签 */
    @PutMapping("/{id}")
    public Result<Void> rename(@PathVariable Long id, @RequestBody Tag tag) {
        tagService.rename(id, tag.getName());
        return Result.ok();
    }

    /** 删除标签；返回受影响笔记数 */
    @DeleteMapping("/{id}")
    public Result<Map<String, Object>> remove(@PathVariable Long id) {
        int affected = tagService.remove(id);
        return Result.ok(Map.of("affectedNotes", affected));
    }
}
