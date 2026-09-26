package org.dyh.learnhub.service;

import lombok.extern.slf4j.Slf4j;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.pdfbox.text.TextPosition;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * PDF 的**排版还原**抽取（阅读器用；与检索用的 {@link DocumentTextService#extract} 是两码事）。
 *
 * <h3>为什么不能直接用 PDFTextStripper 的输出</h3>
 * 学术论文大多是**两栏排版**，而 PDFBox 是按坐标把"同一水平线上的文字"并成一行的 ——
 * 左栏一行、右栏一行会被并成同一条"行"，于是抽出来是这样的：
 *
 * <pre>
 *   Abstract et al. 2023; Liu et al. 2024; Wang et al. 2026). During exe-
 *   Many real-world tasks require LLM agents to interact with
 *   their environments over long execution horizons. Errors that urations, or contaminate database states, forcing subsequent
 * </pre>
 *
 * 左右两栏逐行交错、句子被撕成两半：用来检索没问题（关键词都还在），但**没法读**。
 * 所以这里按坐标重建版面：中缝检测 → 分栏 → 栏内成行 → 成段 → 标题/列表/脚注分型，
 * 输出的是一份结构化正文（title / authors / heading / para / bullet / meta），
 * 前端照原文档的样子排版，"抽取正文"和"原文"看起来才是一份东西。
 *
 * <h3>刻意不做的事</h3>
 * <ul>
 *   <li>不改写存库正文（{@code file_info.text_content}）—— 那是检索层的地基，换它就得全量重建向量与 wiki；
 *       排版还原只服务"看"，所以走单独的接口与单独的实现。</li>
 *   <li>不追求一字不差：PDF 里没有段落对象，只能靠坐标推断，目标是"读起来跟原文一致"。</li>
 * </ul>
 */
@Slf4j
@Service
public class PdfLayoutExtractor {

    /** 页数上限：与抽正文保持一致，几百页的文档对阅读没有意义 */
    private static final int MAX_PAGES = 300;

    /** 判定为"中缝"的最小空隙（pt）。普通词间距只有 2~4pt，两栏之间通常 12pt 以上 */
    private static final double MIN_GUTTER = 12.0;

    /** 行内断开成两段的空隙（按空格宽度的倍数）：两栏之间才够宽，justify 拉伸出来的空格不够 */
    private static final double SEG_GAP = 2.2;

    /** 拼字时的空格阈值（按字号）：小于它认为是同一个词的字符级碎片，不插空格 */
    private static final double WORD_JOIN = 0.55;

    private static final Pattern BULLET = Pattern.compile("^\\s*[•●▪‣·◦*]\\s+");
    private static final Pattern NUMBERED = Pattern.compile("^(\\d+(?:\\.\\d+)*)\\.?\\s+\\S.*");
    private static final Pattern CN_HEADING =
            Pattern.compile("^(第[一二三四五六七八九十百]+[章节部分]|[一二三四五六七八九十]+[、.]|（[一二三四五六七八九十]+）)\\s*\\S.*");
    private static final Pattern SENTENCE_END = Pattern.compile("[.!?。！？…][\"'’”）)]?$");
    private static final Pattern PAGE_NUMBER = Pattern.compile("^[0-9IVXLCDMivxlcdm]{1,5}$");

    /** 论文里固定的小节名（字号不一定变大，靠词表兜住） */
    private static final List<String> KNOWN_HEADINGS = List.of(
            "abstract", "introduction", "related work", "background", "preliminaries",
            "method", "methods", "approach", "framework", "experiments", "experiment",
            "evaluation", "results", "discussion", "conclusion", "conclusions",
            "references", "acknowledgments", "acknowledgements", "appendix", "limitations",
            "摘要", "引言", "相关工作", "参考文献", "结论");

    // ------------------------------------------------------------------
    // 对外结构（前端按 type 排版）
    // ------------------------------------------------------------------

    /** type: title / authors / heading / para / bullet / meta；heading 用 level 表示层级（1 最高） */
    public record Block(String type, String text, int level) {
    }

    public record PageLayout(int page, int columns, List<Block> blocks) {
    }

    public record Layout(String status, String error, List<PageLayout> pages, int chars, int pageCount) {
    }

    /** 行内碎片：被大空隙切开的一段（同一条"行"里可能同时有左栏与右栏） */
    private record Segment(double x0, double x1, double y, double size, boolean bold, boolean mono, String text) {
    }

    /** 栏内的一行 */
    private record Line(double x0, double x1, double y, double size, boolean bold, boolean mono, String text) {
    }

    /** 一栏：几何（左边界 = 段落 flush-left 位置）+ 行 */
    private record Column(double left, double right, double top, double bottom, List<Line> lines) {
    }

    // ------------------------------------------------------------------
    // 入口
    // ------------------------------------------------------------------

    /**
     * 还原整份 PDF 的版面。
     * <p><b>异常绝不外抛</b>：阅读器拿不到排版就退回纯文本段落，不该因为一次抽取失败让"阅读"整体失败。
     */
    public Layout extract(Path path) {
        try (PDDocument doc = Loader.loadPDF(path.toFile())) {
            int total = doc.getNumberOfPages();
            int pages = Math.min(total, MAX_PAGES);
            List<PageLayout> out = new ArrayList<>();
            for (int i = 1; i <= pages; i++) {
                PageLayout pl = layoutPage(doc, doc.getPage(i - 1), i);
                if (!pl.blocks().isEmpty()) {
                    out.add(pl);
                }
            }
            if (out.isEmpty()) {
                return new Layout("empty", "这份 PDF 里没有可提取的文字（扫描件需要 OCR）", List.of(), 0, total);
            }
            List<PageLayout> cleaned = dropFurniture(out);
            int chars = 0;
            for (PageLayout p : cleaned) {
                for (Block b : p.blocks()) {
                    chars += b.text().length();
                }
            }
            return new Layout("ok", null, cleaned, chars, total);
        } catch (Throwable t) {
            log.warn("PDF 排版还原失败: {} - {}", path.getFileName(), t.toString());
            return new Layout("failed", brief(t.getMessage() == null ? t.toString() : t.getMessage()), List.of(), 0, 0);
        }
    }

    // ------------------------------------------------------------------
    // 单页：收集碎片 → 定中缝 → 分栏 → 成行 → 成段
    // ------------------------------------------------------------------

    private PageLayout layoutPage(PDDocument doc, PDPage page, int pageNo) throws IOException {
        PDRectangle box = page.getCropBox() != null ? page.getCropBox()
                : (page.getMediaBox() != null ? page.getMediaBox() : PDRectangle.A4);
        double width = box.getWidth();
        double height = box.getHeight();
        int rotation = ((page.getRotation() % 360) + 360) % 360;

        RowCollector collector = new RowCollector();
        collector.setSortByPosition(true);           // 必须按坐标排序：默认是内容流顺序，栏间会乱跳
        collector.setSuppressDuplicateOverlappingText(true);
        collector.setStartPage(pageNo);
        collector.setEndPage(pageNo);
        collector.getText(doc);                      // 只为触发 writeString 回调，返回值不用

        List<List<Segment>> rows = collector.rows;
        if (rows.isEmpty()) {
            return new PageLayout(pageNo, 1, List.of());
        }

        // 旋转页（横排大表）：坐标系被换过，硬做分栏只会更乱 —— 退化成"一段一段往下排"
        boolean twoColumn = rotation == 0 && detectGutter(rows, width) < width;
        double gutter = twoColumn ? detectGutter(rows, width) : width + 1;

        List<Segment> wideSegs = new ArrayList<>();
        List<Segment> leftSegs = new ArrayList<>();
        List<Segment> rightSegs = new ArrayList<>();
        for (List<Segment> row : rows) {
            for (Segment s : row) {
                if (!twoColumn) {
                    wideSegs.add(s);
                } else if (s.x1() <= gutter + 2) {
                    leftSegs.add(s);
                } else if (s.x0() >= gutter - 2) {
                    rightSegs.add(s);
                } else {
                    // 跨过中缝：整行是全宽内容（大标题 / 跨栏表格 / 图注）
                    wideSegs.add(s);
                }
            }
        }

        List<Line> wideLines = toLines(wideSegs);
        List<Line> leftLines = toLines(leftSegs);
        List<Line> rightLines = toLines(rightSegs);
        double bodySize = bodySize(wideLines, leftLines, rightLines);

        Column left = columnOf(leftLines);
        Column right = columnOf(rightLines);
        double columnTop = Math.min(left == null ? Double.MAX_VALUE : left.top(),
                right == null ? Double.MAX_VALUE : right.top());

        // 阅读顺序：正文上方通栏（标题/作者/摘要）→ 左栏 → 右栏 → 正文下方通栏（跨栏图注/脚注）
        List<Line> head = new ArrayList<>();
        List<Line> tail = new ArrayList<>();
        for (Line ln : wideLines) {
            if (ln.y() < columnTop - 1) {
                head.add(ln);
            } else {
                tail.add(ln);
            }
        }

        List<Block> leftBlocks = columnBlocks(left, bodySize, pageNo, height);
        List<Block> rightBlocks = columnBlocks(right, bodySize, pageNo, height);
        mergeColumnSeam(leftBlocks, rightBlocks);

        List<Block> blocks = new ArrayList<>(headBlocks(head, bodySize, pageNo));
        blocks.addAll(leftBlocks);
        blocks.addAll(rightBlocks);
        blocks.addAll(columnBlocks(columnOf(tail), bodySize, pageNo, height));
        return new PageLayout(pageNo, twoColumn ? 2 : 1, blocks);
    }

    /**
     * 中缝检测：给"同一行里的大空隙"投票，票数最多、且落在页面中部的那条就是中缝。
     * <p>为什么不直接找"一条全页无字的竖带"：论文首页顶部是通栏大标题，会把那条竖带盖住，
     * 于是被误判成单栏（实测这类误判会把两栏又拼回交错的样子）。
     */
    private double detectGutter(List<List<Segment>> rows, double pageWidth) {
        double lo = pageWidth * 0.28;
        double hi = pageWidth * 0.72;
        Map<Integer, Integer> votes = new HashMap<>();
        int rowsWithGap = 0;
        for (List<Segment> row : rows) {
            if (row.size() < 2) {
                continue;
            }
            boolean voted = false;
            for (int i = 1; i < row.size(); i++) {
                Segment prev = row.get(i - 1);
                Segment cur = row.get(i);
                double gap = cur.x0() - prev.x1();
                if (gap < Math.max(MIN_GUTTER, SEG_GAP * spaceWidth(cur))) {
                    continue;
                }
                double mid = (prev.x1() + cur.x0()) / 2;
                if (mid < lo || mid > hi) {
                    continue;
                }
                votes.merge((int) Math.round(mid / 5.0), 1, Integer::sum);
                voted = true;
            }
            if (voted) {
                rowsWithGap++;
            }
        }
        if (rowsWithGap == 0 || votes.isEmpty()) {
            return pageWidth + 1;
        }
        Map.Entry<Integer, Integer> best = votes.entrySet().stream()
                .max(Comparator.comparingInt(Map.Entry::getValue)).orElseThrow();
        // 至少要有四分之一的"多段行"投同一条中缝才认，免得把表格里的空档当成栏缝
        if (best.getValue() < Math.max(2, Math.round(rowsWithGap * 0.25))) {
            return pageWidth + 1;
        }
        return best.getKey() * 5.0;
    }

    /** 碎片 → 行：y 相近的拼成一行，另外的另起一行 */
    private List<Line> toLines(List<Segment> segs) {
        if (segs.isEmpty()) {
            return List.of();
        }
        List<Segment> sorted = new ArrayList<>(segs);
        sorted.sort(Comparator.comparingDouble(Segment::y).thenComparingDouble(Segment::x0));
        List<Line> lines = new ArrayList<>();
        List<Segment> cur = new ArrayList<>();
        for (Segment s : sorted) {
            if (!cur.isEmpty()) {
                Segment prev = cur.get(cur.size() - 1);
                if (Math.abs(s.y() - prev.y()) > 0.45 * Math.max(s.size(), prev.size())) {
                    lines.add(finishLine(cur));
                    cur = new ArrayList<>();
                }
            }
            cur.add(s);
        }
        lines.add(finishLine(cur));
        return lines;
    }

    private Line finishLine(List<Segment> segs) {
        StringBuilder sb = new StringBuilder();
        int total = 0;
        int boldChars = 0;
        boolean mono = true;
        double x0 = Double.MAX_VALUE;
        double x1 = -Double.MAX_VALUE;
        double y = segs.get(0).y();
        double size = 0;
        for (int i = 0; i < segs.size(); i++) {
            Segment s = segs.get(i);
            if (i > 0 && s.x0() - segs.get(i - 1).x1() > WORD_JOIN * s.size()
                    && sb.length() > 0 && sb.charAt(sb.length() - 1) != ' ') {
                sb.append(' ');
            }
            sb.append(s.text());
            int n = Math.max(1, s.text().length());
            total += n;
            if (s.bold()) {
                boldChars += n;
            }
            mono = mono && s.mono();
            x0 = Math.min(x0, s.x0());
            x1 = Math.max(x1, s.x1());
            size = Math.max(size, s.size());
        }
        return new Line(x0, x1, y, size, boldChars * 2 > total, mono, sb.toString().trim());
    }

    /** 正文字号：按字数加权的众数（0.5pt 一档）—— 标题与脚注是少数派，不该拉偏基准 */
    @SafeVarargs
    private final double bodySize(List<Line>... groups) {
        Map<Integer, Integer> weight = new HashMap<>();
        for (List<Line> group : groups) {
            for (Line ln : group) {
                weight.merge((int) Math.round(ln.size() * 2), ln.text().length(), Integer::sum);
            }
        }
        int best = weight.entrySet().stream().max(Map.Entry.comparingByValue())
                .map(Map.Entry::getKey).orElse(20);
        return best / 2.0;
    }

    /** 栏几何：行首 x 的众数就是段落 flush-left 位置（缩进行不影响它） */
    private Column columnOf(List<Line> lines) {
        if (lines == null || lines.isEmpty()) {
            return null;
        }
        List<Line> sorted = new ArrayList<>(lines);
        sorted.sort(Comparator.comparingDouble(Line::y));
        Map<Integer, Integer> starts = new HashMap<>();
        double right = 0;
        double top = Double.MAX_VALUE;
        double bottom = 0;
        for (Line ln : sorted) {
            starts.merge((int) Math.round(ln.x0()), 1, Integer::sum);
            right = Math.max(right, ln.x1());
            top = Math.min(top, ln.y());
            bottom = Math.max(bottom, ln.y());
        }
        int left = starts.entrySet().stream().max(Map.Entry.comparingByValue())
                .map(Map.Entry::getKey).orElse(0);
        return new Column(left, right, top, bottom, sorted);
    }

    // ------------------------------------------------------------------
    // 成段
    // ------------------------------------------------------------------

    /** 正文上方通栏：首页大字号 = 标题，次大 = 作者，其余按元信息/正文处理 */
    private List<Block> headBlocks(List<Line> lines, double bodySize, int pageNo) {
        List<Block> out = new ArrayList<>();
        boolean titleDone = false;
        for (Line ln : lines) {
            String text = ln.text();
            if (text.isEmpty()) {
                continue;
            }
            if (pageNo == 1 && !titleDone && ln.size() >= bodySize * 1.45) {
                out.add(new Block("title", text, 0));
                titleDone = true;
                continue;
            }
            if (pageNo == 1 && titleDone && ln.size() > bodySize * 1.08 && ln.size() < bodySize * 1.45) {
                out.add(new Block("authors", text, 0));
                continue;
            }
            out.add(new Block(ln.size() < bodySize * 0.94 ? "meta" : "para", text, 0));
        }
        return out;
    }

    private List<Block> columnBlocks(Column column, double bodySize, int pageNo, double pageHeight) {
        List<Block> out = new ArrayList<>();
        if (column == null) {
            return out;
        }
        StringBuilder para = new StringBuilder();
        boolean bullet = false;
        Line prev = null;

        for (Line ln : column.lines()) {
            String text = ln.text();
            if (text.isEmpty()) {
                continue;
            }
            int level = headingLevel(text, ln, bodySize);
            if (level > 0) {
                flush(out, para, bullet);
                bullet = false;
                out.add(new Block("heading", text, level));
                prev = ln;
                continue;
            }
            if (BULLET.matcher(text).find()) {
                flush(out, para, bullet);
                bullet = true;
                para.append(BULLET.matcher(text).replaceFirst(""));
                prev = ln;
                continue;
            }
            // 页脚/侧边注释：字号小一档 + 挨着页面底部
            if (ln.size() < bodySize * 0.92 && text.length() < 160 && ln.y() > pageHeight * 0.78
                    && para.length() == 0) {
                out.add(new Block("meta", text, 0));
                prev = ln;
                continue;
            }
            if (para.length() > 0 && prev != null && paragraphBreak(column, prev, ln, bodySize)) {
                flush(out, para, bullet);
                bullet = false;
            }
            if (para.length() > 0) {
                joinLine(para, prev, text);
            } else {
                para.append(text);
            }
            prev = ln;
        }
        flush(out, para, bullet);
        return out;
    }

    /** 段落断开判定：段间距 / 首行缩进 / 上一行是"没写满的最后一行" */
    private boolean paragraphBreak(Column column, Line prev, Line cur, double bodySize) {
        if (cur.y() - prev.y() > 1.55 * Math.max(prev.size(), bodySize)) {
            return true;
        }
        double indent = cur.x0() - column.left();
        if (indent > 0.6 * cur.size() && indent < (column.right() - column.left()) * 0.25) {
            return true;
        }
        return SENTENCE_END.matcher(prev.text()).find()
                && column.right() - prev.x1() > 1.6 * prev.size();
    }

    /** 行与行拼接：连字符续行要吃掉连字符，否则满篇 "exe-cution" */
    private void joinLine(StringBuilder para, Line prev, String next) {
        if (prev != null && para.length() > 1 && para.charAt(para.length() - 1) == '-'
                && Character.isLetter(para.charAt(para.length() - 2)) && isWordChar(next)) {
            para.setLength(para.length() - 1);
            para.append(next);
            return;
        }
        if (prev != null && needsSpace(prev.text(), next)) {
            para.append(' ');
        }
        para.append(next);
    }

    private static boolean needsSpace(String prevText, String next) {
        char a = prevText.isEmpty() ? ' ' : prevText.charAt(prevText.length() - 1);
        char b = next.isEmpty() ? ' ' : next.charAt(0);
        return !(isCjk(a) && isCjk(b));       // 中文之间不加空格，西文词之间要加
    }

    private static boolean isCjk(char c) {
        return c >= 0x2E80;
    }

    private static boolean isWordChar(String s) {
        return !s.isEmpty() && Character.isLetter(s.charAt(0));
    }

    private void flush(List<Block> out, StringBuilder para, boolean bullet) {
        String text = para.toString().trim();
        para.setLength(0);
        if (!text.isEmpty()) {
            out.add(new Block(bullet ? "bullet" : "para", text, 0));
        }
    }

    /**
     * 标题判定：编号小节（1 / 1.1 / 一、）、词表里的固定小节、明显加粗且大一号。
     * <p>三个条件都必须"短 + 不以句末标点结尾" —— 正文里出现 "Introduction" 一个词不该被当成标题。
     */
    private int headingLevel(String text, Line ln, double bodySize) {
        String t = text.trim();
        if (t.isEmpty() || t.length() > 120) {
            return 0;
        }
        var numbered = NUMBERED.matcher(t);
        if (numbered.matches() && !SENTENCE_END.matcher(t).find()) {
            return Math.min(3, numbered.group(1).split("\\.").length);
        }
        if (SENTENCE_END.matcher(t).find()) {
            return 0;
        }
        if (t.split("\\s+").length > 16) {
            return 0;
        }
        if (CN_HEADING.matcher(t).find()) {
            return 1;
        }
        String bare = t.toLowerCase(Locale.ROOT).replaceAll("[^a-z\\s]", "").trim();
        if (KNOWN_HEADINGS.contains(bare)) {
            return ln.size() >= bodySize * 1.05 ? 1 : 2;
        }
        return ln.bold() && ln.size() > bodySize * 1.04 && t.split("\\s+").length <= 10 ? 2 : 0;
    }

    /**
     * 栏缝续接：摘要这类"左栏写到底、右栏接着写"的段落会被栏边界切成两段。
     * 判据很直接：左栏最后一段没写完（无句末标点），右栏第一段又以小写字母/汉字开头。
     */
    private void mergeColumnSeam(List<Block> leftBlocks, List<Block> rightBlocks) {
        if (leftBlocks.isEmpty() || rightBlocks.isEmpty()) {
            return;
        }
        Block a = leftBlocks.get(leftBlocks.size() - 1);
        Block b = rightBlocks.get(0);
        if (!"para".equals(a.type()) || !"para".equals(b.type()) || SENTENCE_END.matcher(a.text()).find()) {
            return;
        }
        char first = b.text().isEmpty() ? ' ' : b.text().charAt(0);
        if (!Character.isLowerCase(first) && !isCjk(first)) {
            return;
        }
        leftBlocks.set(leftBlocks.size() - 1,
                new Block("para", a.text() + (isCjk(first) ? "" : " ") + b.text(), 0));
        rightBlocks.remove(0);
    }

    // ------------------------------------------------------------------
    // 页眉 / 页脚 / 侧边水印：跨页重复出现的短块一律丢掉
    // ------------------------------------------------------------------

    private List<PageLayout> dropFurniture(List<PageLayout> pages) {
        if (pages.size() < 3) {
            return pages;
        }
        Map<String, Integer> freq = new HashMap<>();
        for (PageLayout p : pages) {
            for (Block b : p.blocks()) {
                String key = furnitureKey(b.text());
                if (key != null) {
                    freq.merge(key, 1, Integer::sum);
                }
            }
        }
        int need = Math.max(3, (int) Math.round(pages.size() * 0.6));
        List<PageLayout> out = new ArrayList<>();
        for (PageLayout p : pages) {
            List<Block> all = p.blocks();
            List<Block> keep = new ArrayList<>();
            for (int i = 0; i < all.size(); i++) {
                Block b = all.get(i);
                String key = furnitureKey(b.text());
                if (key != null && freq.getOrDefault(key, 0) >= need) {
                    continue;                                    // 页眉 / 页脚 / 侧边 arXiv 水印
                }
                if ((i < 2 || i >= all.size() - 2) && PAGE_NUMBER.matcher(b.text().trim()).matches()) {
                    continue;                                    // 页码
                }
                keep.add(b);
            }
            if (!keep.isEmpty()) {
                out.add(new PageLayout(p.page(), p.columns(), keep));
            }
        }
        return out;
    }

    private static String furnitureKey(String text) {
        String t = text == null ? "" : text.trim();
        if (t.isEmpty() || t.length() > 80) {
            return null;
        }
        String key = t.toLowerCase(Locale.ROOT).replaceAll("\\d+", "#").replaceAll("\\s+", " ").trim();
        return key.length() >= 2 ? key : null;
    }

    private static double spaceWidth(Segment s) {
        return Math.max(1.5, 0.25 * s.size());
    }

    private static String brief(String s) {
        String t = s.replaceAll("\\s+", " ").trim();
        return t.length() > 200 ? t.substring(0, 200) + "…" : t;
    }

    // ------------------------------------------------------------------
    // PDFBox 回调：把"一行"按大空隙拆成碎片（左栏 / 右栏）
    // ------------------------------------------------------------------

    /**
     * 收集每一行的文字碎片。
     * <p>PDFBox 的 {@code writeString} 已经把同一水平线上的文字并成了一行（跨栏的也并进来），
     * 这里只做一件事：遇到大空隙就断开 —— 于是"左栏结尾 + 右栏开头"那种拼接会被拆回两段。
     * <p>碎片位置来自 {@link TextPosition}：{@code xDirAdj} 是"从上到下、从左到右"的坐标，
     * 每页都是从 0 开始，正好可以直接当版面坐标用。
     */
    private static final class RowCollector extends PDFTextStripper {

        private final List<List<Segment>> rows = new ArrayList<>();

        private RowCollector() throws IOException {
            super();
        }

        @Override
        protected void writeString(String text, List<TextPosition> positions) {
            if (positions == null || positions.isEmpty()) {
                return;
            }
            List<Segment> row = new ArrayList<>();
            StringBuilder sb = new StringBuilder();
            double x0 = positions.get(0).getXDirAdj();
            double x1 = x0;
            double y = positions.get(0).getYDirAdj();
            double size = fontSize(positions.get(0));
            boolean bold = isBold(positions.get(0));
            boolean mono = isMono(positions.get(0));

            for (int i = 0; i < positions.size(); i++) {
                TextPosition p = positions.get(i);
                if (i > 0) {
                    TextPosition prev = positions.get(i - 1);
                    double gap = p.getXDirAdj() - (prev.getXDirAdj() + prev.getWidthDirAdj());
                    double space = spaceWidth(p);
                    if (gap > SEG_GAP * space && sb.length() > 0) {
                        row.add(new Segment(x0, x1, y, size, bold, mono, sb.toString()));
                        sb.setLength(0);
                        x0 = p.getXDirAdj();
                        y = p.getYDirAdj();
                        size = fontSize(p);
                        bold = isBold(p);
                        mono = isMono(p);
                    } else if (gap > WORD_JOIN * fontSize(p) && sb.length() > 0
                            && sb.charAt(sb.length() - 1) != ' ') {
                        sb.append(' ');
                    }
                }
                String u = p.getUnicode();
                if (u != null) {
                    sb.append(u);
                }
                x1 = p.getXDirAdj() + p.getWidthDirAdj();
            }
            if (sb.length() > 0) {
                row.add(new Segment(x0, x1, y, size, bold, mono, sb.toString().trim()));
            }
            if (!row.isEmpty()) {
                rows.add(row);
            }
        }

        private static double spaceWidth(TextPosition p) {
            double w = p.getWidthOfSpace();
            return w > 0 ? w : 0.25 * fontSize(p);
        }

        private static double fontSize(TextPosition p) {
            double s = p.getFontSizeInPt();
            return s > 0 ? s : 10;
        }

        private static boolean isBold(TextPosition p) {
            String n = fontName(p);
            return n.contains("bold") || n.contains("black") || n.contains("heavy");
        }

        private static boolean isMono(TextPosition p) {
            String n = fontName(p);
            return n.contains("mono") || n.contains("courier") || n.contains("consol");
        }

        private static String fontName(TextPosition p) {
            try {
                return p.getFont() == null ? "" : String.valueOf(p.getFont().getName()).toLowerCase(Locale.ROOT);
            } catch (Exception e) {
                return "";
            }
        }
    }
}
