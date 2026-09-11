package org.dyh.learnhub.controller;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.dyh.learnhub.common.Result;
import org.dyh.learnhub.entity.Category;
import org.dyh.learnhub.service.CategoryService;
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
@RequestMapping("/api/categories")
@RequiredArgsConstructor
public class CategoryController {

    private final CategoryService categoryService;

    /** 分类树 */
    @GetMapping
    public Result<List<Category>> tree() {
        return Result.ok(categoryService.tree());
    }

    @GetMapping("/{id}")
    public Result<Category> get(@PathVariable Long id) {
        return Result.ok(categoryService.getById(id));
    }

    @PostMapping
    public Result<Void> save(@Valid @RequestBody Category category) {
        categoryService.save(category);
        return Result.ok();
    }

    @PutMapping("/{id}")
    public Result<Void> update(@PathVariable Long id, @RequestBody Category category) {
        categoryService.update(id, category);
        return Result.ok();
    }

    /** 删除分类（有子分类拒绝）；返回受影响笔记数 */
    @DeleteMapping("/{id}")
    public Result<Map<String, Object>> delete(@PathVariable Long id) {
        int affected = categoryService.delete(id);
        return Result.ok(Map.of("affectedNotes", affected));
    }
}
