package org.dyh.learnhub.service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.regex.Pattern;

/** Stable limit codes for saved reports, including reports written before codes were exposed. */
public final class DocumentExtractionLimits {
    private static final Pattern BODY_TIME = Pattern.compile("^已达到\\s+(\\d{1,6})\\s+(秒|毫秒)任务预算");
    private static final Pattern OCR_PAGES = Pattern.compile("^(?:已)?达到(?:单次)?\\s*(\\d{1,6})\\s*页\\s*OCR\\s*上限");
    private static final Pattern FILE_PAGES = Pattern.compile(
            "^(?:PDF 超过\\s+(\\d{1,6})\\s+页，仅处理前|已达到单次 (?:PDF|图像) 抽取\\s+(\\d{1,6})\\s+(?:页|帧/页)上限)");
    private static final Pattern PAGE_PIXELS = Pattern.compile("^第\\s+\\d+\\s+[页张](?:图像)?未进入索引：图像像素预算超限$");
    private static final Pattern MEDIA_REGIONS = Pattern.compile("^区域 p[0-9]+[-a-z0-9]*：本次已达到 12 个视觉区域上限");
    private static final Pattern TEXT_CAPACITY = Pattern.compile("^(?:累计正文达到|Markdown 正文达到|视觉结果超过) (\\d{1,6}) 万字符上限.*");
    private static final List<String> PRIORITY = List.of("document_capacity", "page_pixels", "batch_time",
            "batch_pixels", "batch_ocr_pages", "batch_pages", "pixel_budget", "interrupted", "media_limit");

    private DocumentExtractionLimits() { }

    /** Amount is in seconds for time, pages for page limits, and pixels for pixel limits. */
    public record Reason(String code, Integer amount) { }

    static boolean isDocumentCapacity(String warning) {
        Reason reason=warning==null?null:classify(warning);
        return reason!=null && "document_capacity".equals(reason.code());
    }

    static boolean olderTextCapacity(String warning) {
        var matcher=TEXT_CAPACITY.matcher(warning==null?"":warning);
        return matcher.matches() && Long.parseLong(matcher.group(1))*10_000<DocumentTextService.MAX_TEXT_CHARS;
    }

    static boolean isTextCapacity(String warning) {
        return TEXT_CAPACITY.matcher(warning==null?"":warning).matches();
    }

    public static List<Reason> fromWarnings(List<String> warnings) {
        var reasons = new LinkedHashMap<String, Reason>();
        if (warnings == null) return List.of();
        for (String warning : warnings) {
            if (warning == null) continue;
            Reason reason = classify(warning);
            if (reason != null) reasons.putIfAbsent(reason.code(), reason);
        }
        if (reasons.containsKey("page_pixels") || reasons.containsKey("batch_pixels")) reasons.remove("pixel_budget");
        return PRIORITY.stream().filter(reasons::containsKey).map(reasons::get).toList();
    }

    private static Reason classify(String warning) {
        var time = BODY_TIME.matcher(warning);
        if (time.find()) return new Reason("batch_time", "秒".equals(time.group(2)) ? Integer.valueOf(time.group(1)) : null);
        var ocr = OCR_PAGES.matcher(warning);
        if (ocr.find()) return new Reason("batch_ocr_pages", Integer.valueOf(ocr.group(1)));
        var pages = FILE_PAGES.matcher(warning);
        if (pages.find()) return new Reason("batch_pages", Integer.valueOf(pages.group(1) == null ? pages.group(2) : pages.group(1)));
        if (warning.startsWith("已达到单次 8000 万像素预算")) return new Reason("batch_pixels", 80_000_000);
        if (warning.startsWith("图像超过单页 1200 万像素上限") || PAGE_PIXELS.matcher(warning).matches())
            return new Reason("page_pixels", 12_000_000);
        if (warning.startsWith("PDF 自适应降采样后仍超过单页像素或边长上限"))
            return new Reason("page_pixels", null);
        if (warning.startsWith("图像超过单页 1200 万/全任务 8000 万像素预算")) return new Reason("pixel_budget", null);
        if (warning.startsWith("累计抽取结构达到") || warning.startsWith("累计正文达到")
                || warning.matches("^Markdown 正文达到 \\d+ 万字符上限.*")
                || warning.matches("^视觉结果超过 \\d+ 万字符上限.*") || warning.startsWith("正文达到存储上限")
                || warning.startsWith("文本输入超过读取上限")) return new Reason("document_capacity", null);
        if (warning.startsWith("文档抽取被中断")) return new Reason("interrupted", null);
        if (warning.startsWith("图片处理达到 180 秒预算") || warning.startsWith("图片处理超时或未完成")
                || warning.startsWith("图片处理已中断") || MEDIA_REGIONS.matcher(warning).find())
            return new Reason("media_limit", null);
        return null;
    }

    static String jobHint(List<String> warnings) {
        return fromWarnings(warnings).stream().findFirst().map(reason -> switch (reason.code()) {
            case "batch_time" -> "本批达到" + (reason.amount() == null ? "时间" : " " + reason.amount() + " 秒")
                    + "预算，可继续抽取剩余页";
            case "batch_pixels" -> "本批已用完像素预算，可继续抽取剩余页";
            case "batch_ocr_pages", "batch_pages" -> "本批已用完页数预算，可继续抽取剩余页";
            case "page_pixels" -> "有页面尺寸超出 OCR 处理范围，请在抽取报告中查看受影响页";
            case "document_capacity" -> "已达到单份资料的累计容量上限，可按章节拆分后抽取剩余内容";
            case "interrupted" -> "本批抽取已中断，可继续抽取剩余页";
            case "media_limit" -> "本批媒体处理结束，可继续处理公式与图片";
            default -> "存在像素预算限制，请在抽取报告中查看受影响页";
        }).orElse(null);
    }
}
