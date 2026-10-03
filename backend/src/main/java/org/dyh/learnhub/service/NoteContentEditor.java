package org.dyh.learnhub.service;

import org.commonmark.node.*;
import org.commonmark.parser.IncludeSourceSpans;
import org.commonmark.parser.Parser;
import org.dyh.learnhub.dto.NoteEditRequest;
import org.dyh.learnhub.vo.NoteEditVO.Change;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;
import java.util.regex.Pattern;

/** 在原始字符串上打补丁，AST 仅用于验证格式目标和提取标题；所有非目标字符原样保留。 */
@Component
public class NoteContentEditor {
    private static final Parser PARSER = Parser.builder()
            .includeSourceSpans(IncludeSourceSpans.BLOCKS_AND_INLINES).build();
    private static final String TOC_START = "<!-- learn-hub-toc:start -->";
    private static final String TOC_END = "<!-- learn-hub-toc:end -->";
    private static final Pattern HTML = Pattern.compile("(?s)<!--.*?-->|<[^<>]*>");
    private static final Set<String> COLORS = Set.of("red", "blue", "green", "orange", "purple", "yellow",
            "black", "white", "gray", "grey", "teal", "pink", "brown", "inherit");

    public record Edit(String content, List<Change> changes) {}
    private record Range(int from, int to) {}
    private record TocHeading(int level, String label, String slug) {}
    private record FontContext(int from, int to, String style) {}
    private record OpenFont(String tag, int from, String style) {}

    public static String hash(String content) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(Objects.requireNonNullElse(content, "").getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    public Edit apply(String original, NoteEditRequest request) {
        String content = Objects.requireNonNullElse(original, "");
        if (request == null || request.expectedHash() == null || !hash(content).equals(request.expectedHash())) {
            throw new IllegalArgumentException("笔记正文已变化，请重新读取笔记并生成改动预览（本次未修改）");
        }
        if (request.operations() == null || request.operations().isEmpty() || request.operations().size() > 50) {
            throw new IllegalArgumentException("operations 必须包含 1～50 项改动");
        }
        List<Change> changes = new ArrayList<>();
        for (var op : request.operations()) {
            if (op == null || op.action() == null) throw new IllegalArgumentException("缺少 action");
            if (op.action().equals("insert_toc")) {
                String after = insertToc(content, op);
                changes.add(change(op.action(), "生成目录", 1, content, after));
                content = after;
                continue;
            }
            if (!Set.of("replace", "delete", "insert_before", "insert_after", "format").contains(op.action())) {
                throw new IllegalArgumentException("不支持的 action：" + op.action());
            }
            List<Range> targets = targets(content, op);
            String replacement = switch (op.action()) {
                case "delete" -> "";
                case "replace", "insert_before", "insert_after" -> {
                    if (op.value() == null) throw new IllegalArgumentException("" + op.action() + " 需要 value");
                    yield op.value();
                }
                case "format" -> format(op);
                default -> throw new IllegalArgumentException("不支持的操作");
            };
            if (op.action().equals("format")) validateFormatTargets(content, targets);
            String before = content;
            List<FontContext> fonts = op.action().equals("format") ? fontContexts(before) : List.of();
            // 位置都来自操作前的原文，一次拼接完成；全部删字也不会反复复制长笔记。
            StringBuilder patched = new StringBuilder(content.length());
            int cursor = 0;
            for (Range r : targets) {
                String next = switch (op.action()) {
                    case "insert_before" -> replacement + op.text();
                    case "insert_after" -> op.text() + replacement;
                    default -> replacement;
                };
                if (op.action().equals("format") && Set.of("color", "highlight", "font_size").contains(op.style())) {
                    // 同种 mark 在块编辑器中只有一个实例；把祖先字体样式合并，避免改单字字号时丢掉颜色。
                    next = withInheritedFont(replacement, fonts, r);
                }
                patched.append(before, cursor, r.from()).append(next);
                cursor = r.to();
            }
            content = patched.append(before, cursor, before.length()).toString();
            String label = switch (op.action()) {
                case "delete" -> "删除文字";
                case "replace" -> "替换文字";
                case "insert_before" -> "在文字前插入";
                case "insert_after" -> "在文字后插入";
                default -> "设置格式（" + switch (op.style()) {
                    case "bold" -> "加粗";
                    case "italic" -> "斜体";
                    case "underline" -> "下划线";
                    case "strike" -> "删除线";
                    case "code" -> "行内代码";
                    case "color" -> "文字颜色";
                    case "highlight" -> "背景颜色";
                    case "font_size" -> "字号";
                    case "superscript" -> "上标";
                    case "subscript" -> "下标";
                    default -> op.style();
                } + "）";
            };
            changes.add(change(op.action(), label, targets.size(), before, content));
        }
        return new Edit(content, List.copyOf(changes));
    }

    private List<Range> targets(String content, NoteEditRequest.Operation op) {
        if (op.text() == null || op.text().isEmpty()) throw new IllegalArgumentException("text 不能为空；请提供要定位的原文");
        if (Boolean.TRUE.equals(op.all()) && op.occurrence() != null) {
            throw new IllegalArgumentException("all 与 occurrence 不能同时指定");
        }
        List<Range> matches = new ArrayList<>();
        int cursor = 0;
        while (cursor <= content.length() - op.text().length()) {
            int at = content.indexOf(op.text(), cursor);
            if (at < 0) break;
            int end = at + op.text().length();
            boolean prefixOk = op.prefix() == null || content.startsWith(op.prefix(), at - op.prefix().length());
            boolean suffixOk = op.suffix() == null || content.startsWith(op.suffix(), end);
            if (prefixOk && suffixOk) {
                if (splitsSurrogate(content, at) || splitsSurrogate(content, end)) {
                    throw new IllegalArgumentException("目标会切断一个 Unicode 字符，请选取完整字符");
                }
                matches.add(new Range(at, end));
            }
            cursor = end;
        }
        if (matches.isEmpty()) throw new IllegalArgumentException("未找到指定原文或上下文：" + shorten(op.text(), 100));
        if (op.occurrence() != null) {
            int n = op.occurrence();
            if (n < 1 || n > matches.size()) throw new IllegalArgumentException("occurrence 超出范围，共匹配 " + matches.size() + " 处");
            return List.of(matches.get(n - 1));
        }
        if (!Boolean.TRUE.equals(op.all()) && matches.size() != 1) {
            throw new IllegalArgumentException("原文匹配 " + matches.size()
                    + " 处；请指定 occurrence（从 1 开始）、紧邻的 prefix/suffix，或明确 all=true（本次未修改）");
        }
        return matches;
    }

    private static boolean splitsSurrogate(String s, int at) {
        return at > 0 && at < s.length() && Character.isHighSurrogate(s.charAt(at - 1)) && Character.isLowSurrogate(s.charAt(at));
    }

    private String format(NoteEditRequest.Operation op) {
        String text = op.text();
        if (text.contains("\n") || text.contains("\r")) throw new IllegalArgumentException("format 只处理行内文字，请逐段指定");
        String style = Objects.requireNonNullElse(op.style(), "");
        return switch (style) {
            // HTML 标签在英文单词内部同样生效，避免 a**b**c 的 Markdown 分隔符歧义。
            case "bold" -> "<strong>" + text + "</strong>";
            case "italic" -> "<em>" + text + "</em>";
            case "underline" -> "<u>" + text + "</u>";
            case "strike" -> "<s>" + text + "</s>";
            case "superscript" -> "<sup>" + text + "</sup>";
            case "subscript" -> "<sub>" + text + "</sub>";
            case "code" -> inlineCode(text);
            case "color", "highlight" -> {
                String color = Objects.requireNonNullElse(op.value(), "").toLowerCase(Locale.ROOT).trim();
                if (!COLORS.contains(color) && !color.matches("#[0-9a-f]{3}(?:[0-9a-f]{3})?")) {
                    throw new IllegalArgumentException("颜色请使用 #RGB、#RRGGBB 或英文颜色名（如 red）");
                }
                yield "<font style=\"" + (style.equals("color") ? "color" : "background-color") + ": " + color + "\">" + text + "</font>";
            }
            case "font_size" -> {
                String value = Objects.requireNonNullElse(op.value(), "");
                if (!value.matches("[0-9]{1,2}") || Integer.parseInt(value) < 8 || Integer.parseInt(value) > 72) {
                    throw new IllegalArgumentException("字号 value 必须是 8～72 的整数（px）");
                }
                yield "<font style=\"font-size: " + value + "px\">" + text + "</font>";
            }
            default -> throw new IllegalArgumentException("style 支持 bold/italic/underline/strike/code/color/highlight/font_size/superscript/subscript");
        };
    }

    private void validateFormatTargets(String source, List<Range> targets) {
        List<Range> textRanges = new ArrayList<>();
        PARSER.parse(source).accept(new AbstractVisitor() {
            @Override public void visit(Text text) { addSpans(text, textRanges); }
            @Override public void visit(HtmlBlock html) {
                // 现有笔记大量使用 <p>/<font>；允许其中的正文，保护属性、注释和原始脚本内容。
                for (SourceSpan span : html.getSourceSpans()) {
                    int from = span.getInputIndex(), to = from + span.getLength();
                    textRanges.add(new Range(from, to));
                }
            }
        });
        List<Range> protectedRanges = new ArrayList<>();
        var tags = HTML.matcher(source);
        while (tags.find()) protectedRanges.add(new Range(tags.start(), tags.end()));
        var raw = Pattern.compile("(?is)<(script|style|pre|code)\\b[^>]*>.*?</\\1\\s*>").matcher(source);
        while (raw.find()) protectedRanges.add(new Range(raw.start(), raw.end()));
        var entities = Pattern.compile("&(?:#[0-9]+|#x[0-9a-fA-F]+|[a-zA-Z][a-zA-Z0-9]+);").matcher(source);
        while (entities.find()) {
            Range entity = new Range(entities.start(), entities.end());
            for (Range target : targets) {
                if (target.from() < entity.to() && target.to() > entity.from()
                        && (target.from() > entity.from() || target.to() < entity.to())) {
                    throw new IllegalArgumentException("格式目标会切断 HTML 实体，请定位完整文字或实体");
                }
            }
        }
        // 同样保护跨行 HTML 注释（不能把注释里的字误认为正文）。
        for (Range r : targets) {
            boolean plain = textRanges.stream().anyMatch(t -> r.from() >= t.from() && r.to() <= t.to());
            boolean protectedTarget = protectedRanges.stream().anyMatch(t -> r.from() < t.to() && r.to() > t.from());
            if (!plain || protectedTarget) {
                throw new IllegalArgumentException("格式目标包含代码、链接地址、HTML 属性或 Markdown 结构；请只定位正文文字");
            }
        }
    }

    private static void addSpans(Node node, List<Range> ranges) {
        for (SourceSpan s : node.getSourceSpans()) ranges.add(new Range(s.getInputIndex(), s.getInputIndex() + s.getLength()));
    }

    private static List<FontContext> fontContexts(String source) {
        List<FontContext> contexts = new ArrayList<>();
        Deque<OpenFont> stack = new ArrayDeque<>();
        var tags = Pattern.compile("(?is)<(/?)(font|span)\\b([^<>]*)>").matcher(source);
        while (tags.find()) {
            String tag = tags.group(2).toLowerCase(Locale.ROOT);
            if (tags.group(1).isEmpty()) {
                var style = Pattern.compile("(?is)\\bstyle\\s*=\\s*([\"'])(.*?)\\1").matcher(tags.group(3));
                stack.push(new OpenFont(tag, tags.end(), style.find() ? style.group(2) : ""));
            } else if (!stack.isEmpty() && stack.peek().tag().equals(tag)) {
                OpenFont open = stack.pop();
                contexts.add(new FontContext(open.from(), tags.start(), open.style()));
            }
        }
        for (OpenFont open : stack) contexts.add(new FontContext(open.from(), source.length(), open.style()));
        contexts.sort(Comparator.comparingInt(FontContext::from));
        return contexts;
    }

    private static String withInheritedFont(String replacement, List<FontContext> fonts, Range target) {
        Map<String, String> css = new LinkedHashMap<>();
        for (FontContext font : fonts) {
            if (font.from() <= target.from() && font.to() >= target.to()) mergeCss(css, font.style());
        }
        if (css.isEmpty()) return replacement;
        int start = replacement.indexOf('"') + 1, end = replacement.indexOf('"', start);
        mergeCss(css, replacement.substring(start, end));
        String style = css.entrySet().stream().map(e -> e.getKey() + ": " + e.getValue())
                .collect(java.util.stream.Collectors.joining("; "));
        style = style.replace("&", "&amp;").replace("\"", "&quot;").replace("<", "&lt;").replace(">", "&gt;");
        return replacement.substring(0, start) + style + replacement.substring(end);
    }

    private static void mergeCss(Map<String, String> css, String style) {
        for (String declaration : style.split(";")) {
            int colon = declaration.indexOf(':');
            if (colon > 0) css.put(declaration.substring(0, colon).trim().toLowerCase(Locale.ROOT), declaration.substring(colon + 1).trim());
        }
    }

    private String insertToc(String source, NoteEditRequest.Operation op) {
        Map<String, List<Integer>> markers = new HashMap<>();
        PARSER.parse(source).accept(new AbstractVisitor() {
            private void marker(Node node, String literal) {
                String value = literal.trim();
                if ((!value.equals(TOC_START) && !value.equals(TOC_END)) || node.getSourceSpans().isEmpty()) return;
                int from = node.getSourceSpans().getFirst().getInputIndex();
                markers.computeIfAbsent(value, ignored -> new ArrayList<>()).add(source.indexOf(value, from));
            }
            @Override public void visit(HtmlBlock html) { marker(html, html.getLiteral()); }
            @Override public void visit(HtmlInline html) { marker(html, html.getLiteral()); }
        });
        List<Integer> starts = markers.getOrDefault(TOC_START, List.of());
        List<Integer> ends = markers.getOrDefault(TOC_END, List.of());
        int start = starts.isEmpty() ? -1 : starts.getFirst(), end = ends.isEmpty() ? -1 : ends.getFirst();
        if ((start < 0) != (end < 0) || (start >= 0 && (end < start
                || starts.size() > 1 || ends.size() > 1))) {
            throw new IllegalArgumentException("已有目录标记不完整或存在多个目录，请先整理标记");
        }
        String forHeadings = start < 0 ? source : source.substring(0, start) + source.substring(end + TOC_END.length());
        int min = op.minLevel() == null ? 1 : op.minLevel();
        int max = op.maxLevel() == null ? 6 : op.maxLevel();
        if (min < 1 || max > 6 || min > max) throw new IllegalArgumentException("目录标题级别必须满足 1 ≤ min_level ≤ max_level ≤ 6");
        List<TocHeading> headings = new ArrayList<>();
        Map<String, Integer> seen = new HashMap<>();
        Set<String> usedIds = new HashSet<>();
        PARSER.parse(forHeadings).accept(new AbstractVisitor() {
            @Override public void visit(Heading heading) {
                List<Range> inline = new ArrayList<>();
                for (Node n = heading.getFirstChild(); n != null; n = n.getNext()) addSpans(n, inline);
                int from = inline.stream().mapToInt(Range::from).min().orElse(0);
                int to = inline.stream().mapToInt(Range::to).max().orElse(from);
                String raw = inline.isEmpty() ? "" : forHeadings.substring(from, to).trim();
                String base = headingSlug(raw);
                int count = seen.getOrDefault(base, 0);
                String slug = count == 0 ? base : base + "-" + count;
                while (usedIds.contains(slug)) slug = base + "-" + (++count);
                seen.put(base, count + 1);
                usedIds.add(slug);
                if (heading.getLevel() >= min && heading.getLevel() <= max) {
                    String label = raw.replaceAll("<[^<>]*>", "").replaceAll("\\[([^]]*)]\\([^)]*\\)", "$1")
                            .replaceAll("[*`~]", "").replaceAll("\\s+", " ");
                    if (label.isBlank()) label = "（无标题）";
                    headings.add(new TocHeading(heading.getLevel(), label, slug));
                }
            }
        });
        if (headings.isEmpty()) throw new IllegalArgumentException("笔记中没有指定级别的 Markdown 标题，无法生成目录");
        String nl = source.contains("\r\n") ? "\r\n" : "\n";
        int baseLevel = headings.stream().mapToInt(TocHeading::level).min().orElse(min);
        StringBuilder toc = new StringBuilder(TOC_START).append(nl).append(nl).append("**目录**").append(nl).append(nl);
        for (TocHeading h : headings) {
            toc.append("  ".repeat(h.level() - baseLevel)).append("- [")
                    .append(h.label().replace("\\", "\\\\").replace("[", "\\[").replace("]", "\\]"))
                    .append("](#").append(h.slug()).append(")").append(nl);
        }
        toc.append(nl).append(TOC_END);
        if (start >= 0) return source.substring(0, start) + toc + source.substring(end + TOC_END.length());
        String placement = Objects.requireNonNullElse(op.placement(), "start");
        if (placement.equals("start")) return toc + nl + nl + source;
        if (placement.equals("end")) return source + nl + nl + toc + nl;
        if (!Set.of("before", "after").contains(placement)) throw new IllegalArgumentException("目录 placement 支持 start/end/before/after");
        Range at = targets(source, op).getFirst();
        if (Boolean.TRUE.equals(op.all())) throw new IllegalArgumentException("目录只能插入一处，不支持 all");
        int offset = placement.equals("before") ? at.from() : at.to();
        return source.substring(0, offset) + nl + nl + toc + nl + nl + source.substring(offset);
    }

    /** 与 frontend/src/utils/mdAnchor.js 保持同一规则，包含未进入目录的标题也参与重名计数。 */
    static String headingSlug(String raw) {
        String slug = raw.replaceAll("<[^<>]*>", "").replaceAll("[*`~]", "").trim().toLowerCase(Locale.ROOT)
                .replaceAll("[^\\u4e00-\\u9fa5a-z0-9\\s_-]", "").replaceAll("[\\s_]+", "-")
                .replaceAll("-{2,}", "-").replaceAll("^-+|-+$", "");
        return slug.isEmpty() ? "section" : slug;
    }

    private static String inlineCode(String source) {
        StringBuilder decoded = new StringBuilder();
        PARSER.parse(source).accept(new AbstractVisitor() {
            @Override public void visit(Text text) { decoded.append(text.getLiteral()); }
        });
        String text = decoded.isEmpty() ? source : decoded.toString();
        int maxRun = 0;
        var runs = Pattern.compile("`+").matcher(text);
        while (runs.find()) maxRun = Math.max(maxRun, runs.end() - runs.start());
        String fence = "`".repeat(maxRun + 1);
        boolean pad = text.startsWith("`") || text.endsWith("`")
                || (text.startsWith(" ") && text.endsWith(" ") && !text.isBlank());
        return fence + (pad ? " " : "") + text + (pad ? " " : "") + fence;
    }

    private static Change change(String action, String label, int count, String before, String after) {
        int left = 0;
        while (left < Math.min(before.length(), after.length()) && before.charAt(left) == after.charAt(left)) left++;
        int tail = 0;
        while (tail < Math.min(before.length(), after.length()) - left
                && before.charAt(before.length() - 1 - tail) == after.charAt(after.length() - 1 - tail)) tail++;
        int from = Math.max(0, left - 40);
        if (splitsSurrogate(before, from)) from--;
        String old = before.substring(from, Math.min(before.length(), before.length() - tail + 40));
        String next = after.substring(Math.min(from, after.length()), Math.min(after.length(), after.length() - tail + 40));
        return new Change(action, label, count, shorten(old, 500), shorten(next, 500));
    }

    private static String shorten(String text, int max) {
        if (text.length() <= max) return text;
        int end = splitsSurrogate(text, max) ? max - 1 : max;
        return text.substring(0, end) + "\n…（片段已截取）";
    }
}
