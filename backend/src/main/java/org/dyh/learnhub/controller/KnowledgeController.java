package org.dyh.learnhub.controller;

import org.dyh.learnhub.common.Result;
import org.dyh.learnhub.service.KnowledgeService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 知识库统一检索接口：跨「笔记 + 速查卡」的聚合搜索。
 * kw 为空时返回最近知识，供知识库页面的空态展示。
 */
@RestController
@RequestMapping("/api/knowledge")
public class KnowledgeController {

    private final KnowledgeService knowledgeService;

    public KnowledgeController(KnowledgeService knowledgeService) {
        this.knowledgeService = knowledgeService;
    }

    @GetMapping("/search")
    public Result<Map<String, Object>> search(@RequestParam(required = false) String kw) {
        return Result.ok(knowledgeService.search(kw));
    }
}
