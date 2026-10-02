package org.dyh.learnhub.service;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 主题 wiki 的**质量校验器**（纯函数，可脱离 Spring 单测）。
 *
 * <h3>为什么需要它</h3>
 * 本地小模型（如 Ollama 的 qwen3:8b）能把 wiki 写通顺，但会犯三类**可探测**的错：
 * <ol>
 *   <li><b>引用不存在的素材</b>：写出 {@code [笔记#99]}，而该主题根本没有 99 号笔记 ——
 *       前端会渲染成一个点不开的死链，用户却以为"知识库里有这条"。</li>
 *   <li><b>标记写坏</b>：实测出现过 {@code [速查、卡#8]}（词里多一个顿号）。</li>
 *   <li><b>结构缺失</b>：没有概览、没有分节，或干脆只写了两三行。</li>
 * </ol>
 * 这些都能**确定性地**查出来，不该靠用户肉眼发现。校验结论落库（{@code wiki_page.quality}）并在界面上标出来；
 * 硬性问题（长度/结构/无有效引用/引用不存在）会触发**一次带反馈的重生成**。
 *
 * <p>刻意不做"逐句事实核对"——那需要再调一次模型，成本与收益不成正比，也做不到可靠。
 * 这里只做能百分之百判定的检查。
 */
public final class WikiQuality {

    private WikiQuality() {
    }

    /** 标记正则刻意宽容：允许中文词里混入标点（本地模型会这么写） */
    private static final Pattern MARKER = Pattern.compile("\\[([^\\]#]{1,8})#(\\d+)\\]");
    /** 分节标题 */
    private static final Pattern SECTION = Pattern.compile("(?m)^#{2,4}\\s+\\S");

    /** 判定为"太短、不像一页 wiki"的字数下限 */
    private static final int MIN_CHARS = 260;
    /** 至少要有一处分节 */
    private static final int MIN_SECTIONS = 1;

    /**
     * 校验结果。
     *
     * @param content    规范化后的正文（坏标记已修好、无效引用已降级为纯文本）
     * @param ok         全部通过
     * @param hardIssues 硬性问题（触发重生成）
     * @param softIssues 软性提示（只展示，不重生成）
     */
    public record Result(String content, boolean ok, List<String> hardIssues, List<String> softIssues) {
        public String summary() {
            List<String> all = new ArrayList<>(hardIssues);
            all.addAll(softIssues);
            return String.join("；", all);
        }
    }

    /**
     * 校验并规范化。
     *
     * @param content  模型返回的正文
     * @param validIds 本次**真正送进模型**的素材 id 集合（形如 "note-3" / "ref-8" / "file-2"）
     */
    public static Result check(String content, Set<String> validIds) {
        List<String> hard = new ArrayList<>();
        List<String> soft = new ArrayList<>();
        String text = content == null ? "" : content.trim();
        if (text.isEmpty()) {
            return new Result("", false, List.of("模型没有返回内容"), List.of());
        }

        // ① 规范化标记：把 [速查、卡#8] 这类写坏的形式修成 [速查卡#8]，并统计无效引用
        Set<String> unknown = new LinkedHashSet<>();
        int validCitations = 0;
        Matcher m = MARKER.matcher(text);
        StringBuilder sb = new StringBuilder();
        int last = 0;
        while (m.find()) {
            sb.append(text, last, m.start());
            String kind = normalizeKind(m.group(1));
            long id = parseLong(m.group(2));
            if (kind == null) {
                // 不是来源标记（比如 [1#2]），原样保留
                sb.append(m.group());
            } else {
                String ref = kind + "-" + id;
                if (validIds.contains(ref) || validIds.contains(altRef(ref))) {
                    validCitations++;
                    sb.append('[').append(kindLabel(kind)).append('#').append(id).append(']');
                } else {
                    // 引用不存在的素材：降级成纯文本，避免渲染成点不开的死链
                    unknown.add(kindLabel(kind) + "#" + id);
                    sb.append(kindLabel(kind)).append('#').append(id);
                }
            }
            last = m.end();
        }
        sb.append(text.substring(last));
        String normalized = sb.toString();

        if (!unknown.isEmpty()) {
            hard.add("引用了不存在的素材：" + String.join("、", unknown));
        }

        // ② 结构检查
        if (normalized.length() < MIN_CHARS) {
            hard.add("正文过短（" + normalized.length() + " 字，少于 " + MIN_CHARS + "）");
        }
        long sections = SECTION.matcher(normalized).results().count();
        if (sections < MIN_SECTIONS) {
            hard.add("缺少分节标题（## …）");
        }

        // ③ 引用覆盖：一页 wiki 至少该有一处来源标注；一处都没有说明它没按素材写
        if (validCitations == 0) {
            hard.add("没有任何有效的来源标注（[笔记#N] / [速查卡#N] / [资料#N]）");
        } else if (validCitations < Math.max(2, validIds.size() / 3)) {
            soft.add("来源标注偏少（仅 " + validCitations + " 处，素材共 " + validIds.size() + " 条）");
        }

        boolean ok = hard.isEmpty();
        return new Result(normalized, ok, hard, soft);
    }

    /** 重生成时追加给模型的纠正指令（把具体问题告诉它，比"请重写"有效得多） */
    public static String retryInstruction(List<String> hardIssues) {
        return "\n\n【重要】你上一次的输出不合格，问题："
                + String.join("；", hardIssues)
                + "。请重新输出完整正文："
                + "只能引用下面列出的素材编号（不要编造编号）；至少 300 字；"
                + "必须有 ## 分节；每条具体事实后标注 [笔记#N] / [速查卡#N] / [资料#N]。";
    }

    /** 把各种写坏的"种类"归一：只看关键词是否包含 */
    private static String normalizeKind(String raw) {
        String k = raw == null ? "" : raw.replaceAll("[^\\u4e00-\\u9fa5]", "");
        if (k.isEmpty()) {
            return null;
        }
        if (k.contains("资料")) {
            return "file";
        }
        if (k.contains("速查")) {
            return "quick_ref";
        }
        if (k.contains("笔记")) {
            return "note";
        }
        return null;
    }

    private static String kindLabel(String kind) {
        return switch (kind) {
            case "file" -> "资料";
            case "quick_ref", "ref" -> "速查卡";
            default -> "笔记";
        };
    }

    /**
     * 来源类型的另一套写法。
     *
     * <p>类型在代码里有**两套命名**：KbChunk / EntityCompileService 用 {@code quick_ref}，
     * 而 KgService / WikiService 用 {@code ref}。校验"合法引用"时必须两种都认，
     * 否则真实存在的速查卡引用会被误判成"引用不存在的素材"，
     * 标记被剥掉方括号降级成纯文本 —— 正是评估报告里 {@code @GetMapping}、dsh、git 等页
     * 出现"引用不存在"告警且失去出处链接的原因。
     */
    private static String altRef(String ref) {
        if (ref.startsWith("quick_ref-")) {
            return "ref-" + ref.substring("quick_ref-".length());
        }
        if (ref.startsWith("ref-")) {
            return "quick_ref-" + ref.substring("ref-".length());
        }
        return ref;
    }

    private static long parseLong(String s) {
        try {
            return Long.parseLong(s);
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    /** 素材清单 → 合法 id 集合（"note-3" / "ref-8" / "file-2"） */
    public static Set<String> idsOf(Iterable<? extends Object[]> items) {
        Set<String> ids = new LinkedHashSet<>();
        for (Object[] it : items) {
            ids.add(String.valueOf(it[0]).toLowerCase(Locale.ROOT) + "-" + it[1]);
        }
        return ids;
    }
}
