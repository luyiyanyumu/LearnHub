package org.dyh.learnhub.service;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Pure serialization: original text stays on blocks; the two Markdown projections have independent offsets. */
public final class DocumentMarkdownWriter {
    private DocumentMarkdownWriter() { }
    private static final Set<String> ACCEPTED = Set.of("accepted", "legacy", "recognized");
    private static final Set<String> INTENTIONAL_EXCLUSIONS = Set.of("page_furniture", "superseded_by_formula");
    private static final Set<String> SAFE_TAGS = Set.of("table", "thead", "tbody", "tfoot", "tr", "td", "th",
            "caption", "colgroup", "col", "br", "strong", "b", "em", "i", "code", "sub", "sup");
    private static final Pattern TAG = Pattern.compile("<(/?)([A-Za-z][A-Za-z0-9:_-]*)([^<>]*)>");
    private static final Pattern SPAN = Pattern.compile("(?i)\\b(rowspan|colspan)\\s*=\\s*[\"']?(\\d{1,3})[\"']?");
    private static final Pattern ASSET = Pattern.compile("assets/[a-f0-9]{64}\\.png");
    private static final int MAX_IMAGE_DIMENSION = 4096;

    public static DocumentIngestionService.Extraction render(DocumentIngestionService.Extraction input) {
        if (input == null) throw new IllegalArgumentException("抽取结果不能为空");
        StringBuilder full = new StringBuilder(), accepted = new StringBuilder();
        List<DocumentIngestionService.Page> pages = new ArrayList<>();
        List<String> warnings = new ArrayList<>(input.report().warnings());
        boolean limited = false, rejected = false;
        for (var page : input.pages()) {
            String marker = pageMarker(page);
            if (!marker.isEmpty()) { separate(full); full.append(marker); }
            DocumentIngestionService.Block previousDisplay = null, previousIndex = null;
            List<DocumentIngestionService.Block> blocks = new ArrayList<>();
            for (var block : page.blocks()) {
                String markdown = displayMarkdown(block, page);
                int markdownStart = -1, markdownEnd = -1, start = -1, end = -1;
                if (!markdown.isEmpty()) {
                    if (!sameInlineGroup(previousDisplay, block)) separate(full);
                    markdownStart = full.length(); full.append(markdown); markdownEnd = full.length();
                    previousDisplay = block;
                }
                boolean picture = Set.of("figure", "image").contains(value(block.type()));
                boolean indexed = block.indexed() && !INTENTIONAL_EXCLUSIONS.contains(value(block.quality()))
                        && !"page_break".equals(block.type())
                        && ACCEPTED.contains(value(block.quality()))
                        && (!picture || !imageLabel(value(block.cleanText()).strip()).isBlank() || reliable(block))
                        && (!"formula".equals(block.type()) || reliable(block));
                String projection = indexed ? indexMarkdown(block, markdown) : "";
                String quality = block.quality();
                String indexSeparator = accepted.isEmpty() ? "" : sameInlineGroup(previousIndex, block) ? " " : "\n\n";
                if (indexed && !projection.isBlank()
                        && accepted.length() + indexSeparator.length() + projection.length() > DocumentTextService.MAX_TEXT_CHARS) {
                    indexed = false; quality = "character_limit"; limited = true;
                }
                if (projection.isBlank()) indexed = false;
                if (indexed) {
                    accepted.append(indexSeparator); start = accepted.length(); accepted.append(projection); end = accepted.length();
                    previousIndex = block;
                }
                rejected |= !INTENTIONAL_EXCLUSIONS.contains(value(block.quality()))
                        && (block.indexed() && !indexed || !Set.of("accepted", "legacy", "recognized", "empty", "non_text").contains(value(block.quality())))
                        && !Set.of("figure", "image", "page_break").contains(value(block.type()));
                blocks.add(new DocumentIngestionService.Block(block.id(), block.type(), block.rawText(), block.cleanText(),
                        block.page(), block.rect(), block.width(), block.height(), block.confidence(), quality, indexed,
                        start, end, block.level(), block.src(), block.latex(), block.latexStatus(), block.latexMessage(),
                        markdown, markdownStart, markdownEnd, block.presentation()));
            }
            pages.add(new DocumentIngestionService.Page(page.page(), page.width(), page.height(), page.coordinateUnit(),
                    page.method(), List.copyOf(blocks), page.label()));
        }
        if (limited && !warnings.contains("Markdown 正文达到 40 万字符上限，未截断代码、公式或表格；剩余块未入索引"))
            warnings.add("Markdown 正文达到 40 万字符上限，未截断代码、公式或表格；剩余块未入索引");
        var old = input.report();
        int rejectedPages = (int) pages.stream().filter(page -> page.blocks().stream().noneMatch(DocumentIngestionService.Block::indexed)).count();
        var report = new DocumentIngestionService.QualityReport(old.totalPages(), old.processedPages(), old.ocrPages(),
                rejectedPages, old.meanConfidence(), List.copyOf(warnings), old.truncated() || limited,
                old.partial() || limited || rejected || rejectedPages > 0);
        String status = accepted.isEmpty() ? "ok".equals(input.status()) ? "empty" : input.status() : "ok";
        String error = accepted.isEmpty() ? input.error() == null ? "没有通过质量校验的检索正文" : input.error() : input.error();
        return new DocumentIngestionService.Extraction(status, accepted.toString(), accepted.length(), error,
                input.sourceSha256(), input.pipelineVersion(), List.copyOf(pages), report, full.toString(), "markdown");
    }

    private static void separate(StringBuilder text) { if (!text.isEmpty()) text.append("\n\n"); }
    private static boolean sameInlineGroup(DocumentIngestionService.Block before, DocumentIngestionService.Block after) {
        if (before == null || before.presentation() == null || after.presentation() == null) return false;
        if ("page_break".equals(before.type()) || "page_break".equals(after.type())) return false;
        var left = before.presentation(); var right = after.presentation();
        return "inline".equals(left.placement()) && "inline".equals(right.placement())
                && left.groupId() != null && !left.groupId().isBlank() && left.groupId().equals(right.groupId());
    }
    private static boolean hidden(DocumentIngestionService.Block block) {
        return block.presentation() != null && Set.of("resolver_only", "annotation").contains(value(block.presentation().placement()));
    }
    private static boolean imageOrigin(DocumentIngestionService.Block block) {
        return Set.of("formula", "figure", "image").contains(value(block.type()))
                && block.presentation() != null && block.presentation().sourceRef() != null
                && !block.presentation().sourceRef().isBlank() && block.src() != null && !block.src().isBlank();
    }
    private static String pageMarker(DocumentIngestionService.Page page) {
        if (page.page() < 1 || !Set.of("pt", "px").contains(value(page.coordinateUnit()))) return "";
        String label = page.label() == null || page.label().isBlank() ? "" : "印刷页码 "
                + inlineText(page.label().replace('\n', ' ').replace('\r', ' ')) + " · ";
        return "<a id=\"source-page-" + page.page() + "\"></a>\n\n###### " + label + "原件第 " + page.page() + " 页";
    }
    private static String displayMarkdown(DocumentIngestionService.Block block, DocumentIngestionService.Page page) {
        if (hidden(block) || INTENTIONAL_EXCLUSIONS.contains(value(block.quality()))) return "";
        if ("page_break".equals(block.type())) return "---";
        boolean picture = Set.of("figure", "image").contains(value(block.type()));
        // Older Office tables already contain this resolver image in its original cell.
        if (picture && block.presentation() == null && "".equals(block.markdown())) return "";
        if (picture || "formula".equals(block.type()) && imageOrigin(block)) return image(block, page);
        if ("formula".equals(block.type())) {
            if (reliable(block)) return formulaMarkdown(block);
            return ASSET.matcher(value(block.src())).matches() ? image(block, page) : inlineText(value(block.rawText()).isBlank()
                    ? value(block.cleanText()) : block.rawText());
        }
        return block.markdown() == null ? format(block) : block.markdown();
    }
    /** Hidden model annotations and figures keep searchable evidence independently of their display image. */
    static String indexMarkdown(DocumentIngestionService.Block block, String display) {
        if ("page_break".equals(block.type())) return "";
        if (hidden(block) || imageOrigin(block) || Set.of("figure", "image").contains(value(block.type()))) {
            if (reliable(block)) return formulaMarkdown(block);
            return projectForIndex(imageLabel(value(block.cleanText()).strip()));
        }
        return projectForIndex(display);
    }
    private static String formulaMarkdown(DocumentIngestionService.Block block) {
        return block.presentation() != null && "inline".equals(block.presentation().placement())
                ? "\\(" + block.latex().strip() + "\\)" : "$$\n" + block.latex().strip() + "\n$$";
    }
    private static boolean reliable(DocumentIngestionService.Block block) {
        return block.latex() != null && !block.latex().isBlank()
                && Set.of("restored", "recognized").contains(value(block.latexStatus()));
    }

    private static String format(DocumentIngestionService.Block block) {
        String type = value(block.type()), text = value(block.cleanText());
        if (text.isBlank()) return "";
        if ("table".equals(type)) return text.strip().startsWith("<table") ? safeMarkdown(text) : text;
        if (Set.of("code", "codeblock").contains(type)) return fenced(text, "");
        if ("heading".equals(type) || type.matches("heading[1-6]")) {
            int level = block.level() > 0 ? block.level() : type.matches("heading[1-6]") ? type.charAt(7) - '0' : 1;
            return "#".repeat(Math.max(1, Math.min(6, level))) + " " + inlineText(text.replace('\n', ' '));
        }
        if (Set.of("bullet", "list", "list_item").contains(type))
            return "- " + inlineText(text.replaceFirst("^\\s*(?:[-*+•]|\\d+[.)])\\s+", "")).replace("\n", "\n  ");
        return inlineText(text);
    }

    /** Preserve actual math while quoting Markdown punctuation originating in a non-Markdown parser. */
    static String inlineText(String text) { return transform(value(text), false, true, true); }
    static String safeMarkdown(String markdown) { return transform(value(markdown), false, true, false); }

    public static String fenced(String source, String language) {
        int longest = 0, run = 0;
        for (char c : source.toCharArray()) { run = c == '`' ? run + 1 : 0; longest = Math.max(longest, run); }
        String fence = "`".repeat(Math.max(3, longest + 1));
        return fence + (language == null ? "" : language.replaceAll("[^A-Za-z0-9_+-]", "")) + "\n" + source
                + (source.endsWith("\n") ? "" : "\n") + fence;
    }

    private static String image(DocumentIngestionService.Block block, DocumentIngestionService.Page page) {
        String src = value(block.src());
        if (!ASSET.matcher(src).matches()) return "[原图未能提取，请查看原件]";
        Double width = block.presentation() == null ? null : dimension(block.presentation().widthPx());
        Double height = block.presentation() == null ? null : dimension(block.presentation().heightPx());
        if (width == null && height == null && Set.of("pt", "px").contains(value(page.coordinateUnit()))
                && block.rect() != null && block.rect().length == 4) {
            double factor = "pt".equals(page.coordinateUnit()) ? 96.0 / 72 : 1;
            width = dimension((block.rect()[2] - block.rect()[0]) * factor);
            height = dimension((block.rect()[3] - block.rect()[1]) * factor);
        }
        double scale = Math.min(1, MAX_IMAGE_DIMENSION / Math.max(width == null ? 1 : width, height == null ? 1 : height));
        return "<img src=\"" + src + "\"" + imageDimension("width", width, scale)
                + imageDimension("height", height, scale) + " alt=\"原图\">";
    }
    private static Double dimension(Double value) { return value != null && Double.isFinite(value) && value > 0 ? value : null; }
    private static String imageDimension(String name, Double value, double scale) {
        return value == null ? "" : " " + name + "=\"" + Math.max(1, Math.round(value * scale)) + "\"";
    }

    private static String safeSource(String source) {
        if (source == null || source.isBlank() || source.length() > 2048 || source.matches("(?s).*[\\\\\\r\\n<>\"\u0000].*")) return "";
        String src = source.trim();
        if (src.startsWith("word:") && src.matches("word:[A-Za-z0-9_./-]+#[A-Za-z0-9_.-]+") && !src.contains("..")) return src;
        if (src.matches("(?:ppt|xlsx):\\d+#\\d+")) return src;
        if (src.matches("[A-Za-z0-9_./#%?=&+-]+") && !src.startsWith("//") && !src.matches(".*(?:^|/)\\.\\.(?:/|$).*")) return src;
        try {
            URI uri = URI.create(src);
            if (Set.of("http", "https").contains(value(uri.getScheme()).toLowerCase(Locale.ROOT))
                    && uri.getHost() != null && uri.getUserInfo() == null)
                return src.replace("(", "%28").replace(")", "%29");
        } catch (IllegalArgumentException ignored) { }
        return "";
    }

    /** Strip destinations only outside code/math. Matched brackets are precomputed in linear time per line. */
    public static String projectForIndex(String markdown) { return transform(value(markdown), true, false, false).strip(); }

    private static String transform(String text, boolean links, boolean html, boolean quotePunctuation) {
        StringBuilder result = new StringBuilder();
        String fence = null, mathEnd = null;
        boolean firstLine = true;
        for (String line : text.split("\n", -1)) {
            if (!firstLine) result.append('\n');
            firstLine = false;
            String trimmed = line.stripLeading();
            if (fence != null) {
                result.append(line);
                int closing = run(trimmed, 0, fence.charAt(0));
                if (closing >= fence.length() && trimmed.substring(closing).isBlank()) fence = null;
                continue;
            }
            Matcher code = Pattern.compile("^(`{3,}|~{3,}).*$").matcher(trimmed);
            if (code.matches()) { fence = code.group(1); result.append(line); continue; }
            if (mathEnd != null) {
                result.append(line); if (line.contains(mathEnd)) mathEnd = null; continue;
            }
            if (trimmed.startsWith("$$") && trimmed.indexOf("$$", 2) < 0) { mathEnd = "$$"; result.append(line); continue; }
            if (trimmed.startsWith("\\[") && !trimmed.contains("\\]")) { mathEnd = "\\]"; result.append(line); continue; }
            Matcher environment = Pattern.compile("^\\\\begin\\{([A-Za-z*]+)\\}.*").matcher(trimmed);
            if (environment.matches()) {
                String close = "\\end{" + environment.group(1) + "}";
                if (!line.contains(close)) mathEnd = close;
                result.append(line); continue;
            }
            if (line.startsWith("    ") || line.startsWith("\t")) { result.append(line); continue; }
            Matcher footnote = Pattern.compile("^[ \\t]*\\[\\^[^]\\r\\n]{1,1000}]:[ \\t]*(.*)$").matcher(line);
            if (links && footnote.matches()) {
                result.append(inline(footnote.group(1), true, false, false)); continue;
            }
            if (links && trimmed.matches("\\[(?!\\^)[^]\\r\\n]{1,1000}]:[ \\t]*.*")) continue;
            result.append(inline(line, links, html, quotePunctuation));
        }
        return result.toString();
    }

    private static String inline(String line, boolean links, boolean html, boolean quotePunctuation) {
        return inline(line, links, html, quotePunctuation, 0);
    }

    private static String inline(String line, boolean links, boolean html, boolean quotePunctuation, int depth) {
        int[] squares = links ? pairs(line, '[', ']') : null, parens = links ? pairs(line, '(', ')') : null;
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < line.length();) {
            char c = line.charAt(i);
            if (c == '\\' && i + 1 < line.length() && line.charAt(i + 1) != '(') { out.append(c).append(line.charAt(i + 1)); i += 2; continue; }
            String delimiter = c == '`' ? "`".repeat(run(line, i, '`')) : c == '$' ? "$".repeat(Math.min(2, run(line, i, '$')))
                    : line.startsWith("\\(", i) ? "\\(" : null;
            if (delimiter != null) {
                String end = delimiter.equals("\\(") ? "\\)" : delimiter;
                int close = delimiter.charAt(0) == '`' ? closingTicks(line, i + delimiter.length(), delimiter.length())
                        : line.indexOf(end, i + delimiter.length());
                if (close >= 0) { out.append(line, i, close + end.length()); i = close + end.length(); continue; }
            }
            int opening = c == '!' && i + 1 < line.length() && line.charAt(i + 1) == '[' ? i + 1 : c == '[' ? i : -1;
            if (links && opening >= 0 && squares[opening] >= 0) {
                int close = squares[opening], dest = close + 1;
                if (dest < line.length() && line.charAt(dest) == '(' && parens[dest] >= 0) {
                    String label = depth < 16 ? inline(line.substring(opening + 1, close), true, false, false, depth + 1) : "[嵌套链接]";
                    out.append(c == '!' ? imageLabel(label) : label);
                    i = parens[dest] + 1; continue;
                }
                if (dest < line.length() && line.charAt(dest) == '[' && squares[dest] >= 0) {
                    String label = depth < 16 ? inline(line.substring(opening + 1, close), true, false, false, depth + 1) : "[嵌套链接]";
                    out.append(c == '!' ? imageLabel(label) : label);
                    i = squares[dest] + 1; continue;
                }
            }
            if ((html || links) && c == '<') {
                int autoEnd = html && !quotePunctuation ? line.indexOf('>', i + 1) : -1;
                if (autoEnd > i && autoEnd - i <= 2048 && autolink(line.substring(i + 1, autoEnd))) {
                    out.append(line, i, autoEnd + 1); i = autoEnd + 1; continue;
                }
                Matcher tag = TAG.matcher(line); tag.region(i, line.length());
                if (tag.lookingAt()) {
                    String name = tag.group(2).toLowerCase(Locale.ROOT);
                    if (links && Set.of("img", "a").contains(name)) {
                        if (name.equals("img")) out.append(imageLabel(attribute(tag.group(3), "alt")));
                        i = tag.end(); continue;
                    }
                    if (html && !quotePunctuation && Set.of("img", "a").contains(name)) {
                        String target = safeSource(attribute(tag.group(3), name.equals("img") ? "src" : "href"));
                        if (name.equals("img")) {
                            if (tag.group(1).isEmpty() && ASSET.matcher(target).matches()) {
                                Double width = htmlDimension(attribute(tag.group(3), "width"));
                                Double height = htmlDimension(attribute(tag.group(3), "height"));
                                double scale = Math.min(1, MAX_IMAGE_DIMENSION / Math.max(width == null ? 1 : width, height == null ? 1 : height));
                                out.append("<img src=\"").append(htmlAttribute(target)).append('"')
                                        .append(imageDimension("width", width, scale)).append(imageDimension("height", height, scale))
                                        .append(" alt=\"").append(htmlAttribute(attribute(tag.group(3), "alt"))).append("\">");
                            } else out.append(escape(attribute(tag.group(3), "alt")));
                        } else if (!tag.group(1).isEmpty()) out.append("</a>");
                        else {
                            String id = attribute(tag.group(3), "id");
                            if (!id.matches("[A-Za-z][A-Za-z0-9_.:-]{0,199}")) id = "";
                            if (!target.isEmpty() || !id.isEmpty()) {
                                out.append("<a");
                                if (!target.isEmpty()) out.append(" href=\"").append(htmlAttribute(target)).append('"');
                                if (!id.isEmpty()) out.append(" id=\"").append(id).append('"');
                                out.append('>');
                            }
                        }
                        i = tag.end(); continue;
                    }
                    if (html && !quotePunctuation && SAFE_TAGS.contains(name)) {
                        out.append('<').append(tag.group(1)).append(name);
                        if (tag.group(1).isEmpty() && Set.of("td", "th").contains(name)) {
                            Matcher spans = SPAN.matcher(tag.group(3));
                            while (spans.find()) if (Integer.parseInt(spans.group(2)) > 0)
                                out.append(' ').append(spans.group(1).toLowerCase(Locale.ROOT)).append("=\"").append(spans.group(2)).append('"');
                        }
                        out.append('>'); i = tag.end(); continue;
                    }
                    if (html) { out.append(escape(tag.group())); i = tag.end(); continue; }
                }
                if (html) { out.append("&lt;"); i++; continue; }
            }
            if (html && quotePunctuation && c == '>') out.append("&gt;");
            else { if (quotePunctuation && "*_[]`#|".indexOf(c) >= 0) out.append('\\'); out.append(c); }
            i++;
        }
        return out.toString();
    }

    private static boolean autolink(String source) {
        if (source.matches("(?i)https?://[^<>\\s]+")) return !safeSource(source).isEmpty();
        return source.matches("(?:mailto:)?[A-Za-z0-9.!#$%&'*+/=?^_`{|}~-]+@[A-Za-z0-9](?:[A-Za-z0-9.-]*[A-Za-z0-9])?\\.[A-Za-z]{2,63}");
    }
    private static Double htmlDimension(String source) {
        if (!source.matches("[0-9]{1,5}(?:\\.[0-9]{1,3})?")) return null;
        return dimension(Double.valueOf(source));
    }

    private static int[] pairs(String text, char open, char close) {
        int[] ends = new int[text.length()], stack = new int[text.length()];
        java.util.Arrays.fill(ends, -1); int count = 0;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '\\') { i++; continue; }
            if (c == open) stack[count++] = i;
            else if (c == close && count > 0) ends[stack[--count]] = i;
        }
        return ends;
    }
    private static int run(String text, int start, char c) { int i = start; while (i < text.length() && text.charAt(i) == c) i++; return i - start; }
    private static int closingTicks(String text, int from, int count) {
        for (int i = from; i < text.length();) {
            if (text.charAt(i) != '`') { i++; continue; }
            int length = run(text, i, '`'); if (length == count) return i; i += length;
        }
        return -1;
    }
    private static String attribute(String attributes, String name) {
        Matcher value = Pattern.compile("(?i)\\b" + name + "\\s*=\\s*(?:\"([^\"]*)\"|'([^']*)'|([^\\s\"'=<>]+))").matcher(attributes);
        if (!value.find()) return "";
        return value.group(1) != null ? value.group(1) : value.group(2) != null ? value.group(2) : value.group(3);
    }
    private static String htmlAttribute(String value) { return value.replace("&", "&amp;").replace("\"", "&quot;").replace("<", "&lt;").replace(">", "&gt;"); }
    private static String imageLabel(String label) {
        return Set.of("原图", "图片", "图像", "公式原图", "待核对公式原图", "待核对图片", "image", "figure", "original image")
                .contains(label.strip().toLowerCase(Locale.ROOT)) ? "" : label;
    }
    private static String escape(String text) { return text.replace("\\", "\\\\").replace("[", "\\[").replace("]", "\\]").replace("<", "&lt;").replace(">", "&gt;"); }
    private static String value(String text) { return text == null ? "" : text; }
}
