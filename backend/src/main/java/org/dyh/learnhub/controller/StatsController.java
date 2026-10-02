package org.dyh.learnhub.controller;

import lombok.RequiredArgsConstructor;
import org.dyh.learnhub.common.Result;
import org.dyh.learnhub.service.StatsService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/api/stats")
@RequiredArgsConstructor
public class StatsController {

    private final StatsService statsService;

    /** 工作台总览统计 */
    @GetMapping
    public Result<Map<String, Object>> dashboard() {
        return Result.ok(statsService.dashboard());
    }

    /** 首页年度学习活动热力图 */
    @GetMapping("/activity")
    public Result<Map<String, Object>> activity(@RequestParam(required = false) Integer year) {
        return Result.ok(statsService.activity(year));
    }
}
