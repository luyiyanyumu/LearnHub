package org.dyh.learnhub.service;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.dyh.learnhub.service.DocumentExtractionLimits.*;
import static org.junit.jupiter.api.Assertions.*;

class DocumentExtractionLimitsTest {
    @Test void distinguishesABatchTimeoutFromPermanentDocumentCapacity() {
        String time = "已达到 120 秒任务预算；再次继续抽取可处理剩余页";
        String capacity = "累计正文达到 40 万字符上限；请拆分原件以抽取剩余内容";
        assertEquals(List.of(new Reason("batch_time", 120)), fromWarnings(List.of(time, time)));
        assertEquals("本批达到 120 秒预算，可继续抽取剩余页", jobHint(List.of(time)));
        assertEquals(List.of(new Reason("document_capacity", null), new Reason("batch_time", 120)),
                fromWarnings(List.of(time, capacity)));
        assertTrue(jobHint(List.of(time, capacity)).contains("按章节拆分"));
    }

    @Test void identifiesTheSpecificLegacyPixelLimitWhenPageEvidenceIsPresent() {
        String ambiguous = "图像超过单页 1200 万/全任务 8000 万像素预算，相关内容未识别";
        assertEquals(List.of(new Reason("pixel_budget", null)), fromWarnings(List.of(ambiguous)));
        assertEquals(List.of(new Reason("page_pixels", 12_000_000)),
                fromWarnings(List.of(ambiguous, "第 35 页未进入索引：图像像素预算超限")));
        assertEquals(List.of(new Reason("batch_pixels", 80_000_000)),
                fromWarnings(List.of("已达到单次 8000 万像素预算；再次继续抽取可处理剩余页")));
    }

    @Test void advisoryAndQualityWarningsDoNotBecomeLimits() {
        assertEquals(List.of(), fromWarnings(List.of(
                "第 4 页部分 OCR 块因 low_confidence 未进入索引",
                "区域 p1-media：视觉识别失败、超时或结果未通过校验，已保留原图",
                "第 2 页已按自适应分辨率渲染")));
    }
}
