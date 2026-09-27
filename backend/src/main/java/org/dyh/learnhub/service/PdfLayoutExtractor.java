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
 * <h3>为什么要单独做一遍</h3>
 * 论文基本是两栏排版，而默认抽文是"按行"的纯文本，左右两栏逐行交错、句子被撕成两半：
 *
 * <pre>
 *   Abstract et al. 2023; Liu et al. 2024; Wang et al. 2026). During exe-
 *   Many real-world tasks require LLM agents to interact with
 *   their environments over long execution horizons. Errors that urations, or contaminate database states, forcing subsequent
 * </pre>
 *
 * 检索没问题（关键词都在），但**没法读**。这里按键面坐标重建版面：
 * 定中缝 → 分栏 → 栏内成行 → 成段 → 标题/列表/脚注分型，前端照原文档的样子排版。
 *
 * <h3>PDFBox 的回调粒度（实测，别照直觉写）</h3>
 * <ul>
 *   <li>{@code writeString(String, List<TextPosition>)} 是**按词**回调的（19 页论文首页调了 679 次），
 *       而且不调 1 参版本；词与词之间的**空格与换行是另外两个回调**
 *       （{@code writeWordSeparator()} / {@code writeLineSeparator()}）。</li>
 *   <li>只重写 writeString 会把整页拼成 "AgentRewind:RecoverableExecution" 这样的连体字 ——
 *       按坐标猜空格也救不回来（有空格字形时几何间隙是 0）。正确做法是同时接管这两个分隔符回调，
 *       于是**行分组直接拿 PDFBox 的**，我们只做"行内按中缝切栏"。</li>
 *   <li>PDFBox 的行分组是"同一水平线"级的：跨栏的两个词会被并进同一行（这正是交错的原因），
 *       所以行内还要按中缝的空隙再切一刀。</li>
 * </ul>
 *
 * <h3>版面识别的三条实测依据（拿 uploads 里的论文调出来的）</h3>
 * <ol>
 *   <li><b>中缝靠投票</b>：首页顶部是通栏大标题，直接找"一条全页无字的竖带"会被标题盖住而误判单栏；
 *       改成给"同一行里的大空隙"投票，票数最多且落在页面中部的就是中缝。</li>
 *   <li><b>正文左边界取分位点</b>：摘要区常比正文再缩进一个字符位（实测 64 vs 54pt），
 *       取"行首 x 的众数"会取到摘要的缩进值，于是正文每行都被当成首行缩进、碎成一句一段；
 *       改用第 5 百分位，并且"上一行也在缩进位"时不算新段落。</li>
 *   <li><b>首页标题区整体成块</b>：标题/作者/机构是居中的，行首既不贴正文左边界、也不贴右栏左边界 ——
 *       用"正文之前 + 行首不在任何栏的边界上"圈出这一整块；否则作者行会被中缝切成两半，
 *       前半截塞进左栏、后半截跑到右栏开头。</li>
 * </ol>
 *
 * <h3>刻意不做的事</h3>
 * <ul>
 *   <li>不改写存库正文（{@code file_info.text_content}）—— 那是检索层的地基，换它就得全量重建向量与 wiki；
 *       排版还原只服务"看"，所以单独接口、单独实现。</li>
 *   <li>不追求一字不差：PDF 里没有段落对象，全靠坐标推断，目标是"读起来跟原文一致"。</li>
 * </ul>
 */
@Slf4j
@Service
public class PdfLayoutExtractor {

    /** 页数上限：与抽正文保持一致，几百页的文档对阅读没有意义 */
    private static final int MAX_PAGES = 300;

    /** 判定为"中缝"的最小空隙（pt）。普通词间距只有 2~4pt，两栏之间通常 12pt 以上 */
    private static final double MIN_GUTTER = 12.0;

    /** 行首离栏边界多近算"贴边"（按字号倍数） */
    private static final double FLUSH_TOL = 1.2;

    private static final Pattern BULLET = Pattern.compile("^\\s*[•●▪‣·◦*]\\s+");
    private static final Pattern NUMBERED = Pattern.compile("^(\\d+(?:\\.\\d+)*)\\.?\\s+\\S.*");
    private static final Pattern CN_HEADING =
            Pattern.compile("^(第[一二三四五六七八九十百]+[章节部分]|[一二三四五六七八九十]+[、.]|（[一二三四五六七八九十]+）)\\s*\\S.*");
    private static final Pattern SENTENCE_END = Pattern.compile("[.!?。！？…][\"'’”）)]?$");
    private static final Pattern PAGE_NUMBER = Pattern.compile("^[0-9IVXLCDMivxlcdm]{1,5}$");
    private static final Pattern SPACE_BEFORE_PUNCT = Pattern.compile("\\s+([,.;:!?%)])");

    /**
     * arXiv 的页边标记（"arXiv:2510.05592v2 [cs.AI] 22 Jul 2026"）。
     * <p>它**永远不是标题**，但会让"最大字号 + 够宽"的标题规则中招：实测有一篇论文的第一页
     * 把它排成了横排且字号最大，于是整篇文档的标题变成了一个 arXiv 编号。
     * 这类标记是排印噪声，直接按模式识别、并按页眉页脚丢弃。
     */
    private static final Pattern ARXIV_STAMP = Pattern.compile("^arxiv:\\s*\\S+", Pattern.CASE_INSENSITIVE);

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

    /** 一个词（PDFBox 的一次调用）：左上角 + 右边界 + 字号 + "前面有没有空格" */
    private record Word(double x0, double x1, double y, double size, boolean bold, boolean spaceBefore,
                        String text) {
    }

    /** 一栏内的一行 */
    private record Line(double x0, double x1, double y, double size, boolean bold, String text) {
    }

    /** 带纵向位置的块：用来把"跨栏通栏块"按 y 插回栏内阅读顺序 */
    private record Placed(Block block, double y) {
    }

    /** 一栏：几何 + 行 */
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
    // 单页
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
        collector.getText(doc);                      // 只为触发回调，返回值不用

        List<List<Word>> rows = collector.rows;
        if (rows.isEmpty()) {
            return new PageLayout(pageNo, 1, List.of());
        }
        double bodySize = bodySize(rows);
        // 旋转页（横排大表）坐标系被换过，硬做分栏只会更乱 —— 退化成"一段一段往下排"
        double gutter = rotation == 0 ? detectGutter(rows, width) : width + 1;
        boolean twoColumn = gutter < width;
        double leftFlush = flushOf(rows, bodySize, width, true);
        double rightFlush = flushOf(rows, bodySize, width, false);
        double bodyStartY = bodyStartY(rows, bodySize, leftFlush, rightFlush, gutter, twoColumn);

        // 分流：行内先按中缝切，再决定这一段属于哪一栏（居中块整行当通栏，不切）
        List<List<Word>> wide = new ArrayList<>();
        List<List<Word>> left = new ArrayList<>();
        List<List<Word>> right = new ArrayList<>();
        for (List<Word> row : rows) {
            double rowSize = row.stream().mapToDouble(Word::size).max().orElse(bodySize);
            boolean aboveBody = row.get(0).y() < bodyStartY - 0.6 * rowSize;
            boolean centeredRow = aboveBody && row.stream().anyMatch(w -> !nearFlush(w, leftFlush, rightFlush, gutter));
            for (List<Word> part : splitByGutter(row, twoColumn && !centeredRow, gutter)) {
                if (!twoColumn || centeredRow) {
                    wide.add(part);
                } else if (crossesGutter(part, gutter)) {
                    wide.add(part);
                } else if (part.get(0).x0() >= gutter) {
                    right.add(part);
                } else {
                    left.add(part);
                }
            }
        }

        if (!twoColumn) {
            List<Line> lines = toLines(wide);
            List<Line> head = new ArrayList<>();
            List<Line> body = new ArrayList<>();
            for (Line ln : lines) {
                if (ln.y() < bodyStartY - 0.6 * ln.size()) {
                    head.add(ln);
                } else {
                    body.add(ln);
                }
            }
            List<Block> blocks = new ArrayList<>(headBlocks(head, bodySize, pageNo, width));
            blocks.addAll(plain(columnPlaced(columnOf(body), bodySize, pageNo, height)));
            return new PageLayout(pageNo, 1, blocks);
        }

        List<Line> leftLines = toLines(left);
        List<Line> rightLines = toLines(right);
        List<Placed> leftBlocks = columnPlaced(columnOf(leftLines), bodySize, pageNo, height);
        List<Placed> rightBlocks = columnPlaced(columnOf(rightLines), bodySize, pageNo, height);
        mergeColumnSeam(leftBlocks, rightBlocks);

        // 通栏块：栏目之上的（标题区）走标题处理，栏目之间/之下的按 y 插回左栏顺序
        List<Line> head = new ArrayList<>();
        List<Line> spanningLines = new ArrayList<>();
        double columnTop = Math.min(firstY(leftLines), firstY(rightLines));
        for (Line ln : toLines(wide)) {
            if (ln.y() < columnTop - 1) {
                head.add(ln);
            } else {
                spanningLines.add(ln);
            }
        }
        List<Placed> spanning = columnPlaced(columnOf(spanningLines), bodySize, pageNo, height);
        List<Block> blocks = new ArrayList<>(headBlocks(head, bodySize, pageNo, width));
        blocks.addAll(plain(interleave(leftBlocks, spanning)));
        blocks.addAll(plain(rightBlocks));
        return new PageLayout(pageNo, 2, blocks);
    }

    /**
     * 行内按中缝切：PDFBox 把同一水平线上的词并成一行，两栏的正文因此被并在一起；
     * 只要某一对相邻词之间的空隙足够大、且落在页面中部，就在那里切开。
     */
    private List<List<Word>> splitByGutter(List<Word> row, boolean enable, double gutter) {
        if (!enable || row.size() < 2) {
            return List.of(row);
        }
        List<Word> sorted = new ArrayList<>(row);
        sorted.sort(Comparator.comparingDouble(Word::x0));
        List<List<Word>> parts = new ArrayList<>();
        List<Word> cur = new ArrayList<>();
        for (Word w : sorted) {
            if (!cur.isEmpty()) {
                Word prev = cur.get(cur.size() - 1);
                double gap = w.x0() - prev.x1();
                double mid = (prev.x1() + w.x0()) / 2;
                // 只在"中缝附近"切：正文里的宽空格（表格、公式）不该被当成栏缝
                if (gap >= MIN_GUTTER && Math.abs(mid - gutter) <= gap / 2 + 8) {
                    parts.add(cur);
                    cur = new ArrayList<>();
                    w = new Word(w.x0(), w.x1(), w.y(), w.size(), w.bold(), false, w.text());
                }
            }
            cur.add(w);
        }
        parts.add(cur);
        return parts;
    }

    /** 中缝检测：给"同一行里的大空隙"投票，票数最多、且落在页面中部的那条就是中缝 */
    private double detectGutter(List<List<Word>> rows, double pageWidth) {
        double lo = pageWidth * 0.28;
        double hi = pageWidth * 0.72;
        Map<Integer, Integer> votes = new HashMap<>();
        int rowsWithGap = 0;
        for (List<Word> row : rows) {
            List<Word> sorted = new ArrayList<>(row);
            sorted.sort(Comparator.comparingDouble(Word::x0));
            boolean voted = false;
            for (int i = 1; i < sorted.size(); i++) {
                double gap = sorted.get(i).x0() - sorted.get(i - 1).x1();
                if (gap < MIN_GUTTER) {
                    continue;
                }
                double mid = (sorted.get(i - 1).x1() + sorted.get(i).x0()) / 2;
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
        // 至少要有六分之一的"有缝行"投同一条中缝才认，免得把表格里的空档当成栏缝
        if (best.getValue() < Math.max(2, Math.round(rowsWithGap / 6.0))) {
            return pageWidth + 1;
        }
        return best.getKey() * 5.0;
    }

    /** 栏的正文左边界：取 5% 分位（避开居中标题与页边水印），只统计"像正文"的整行 */
    private double flushOf(List<List<Word>> rows, double bodySize, double pageWidth, boolean leftHalf) {
        List<Double> xs = new ArrayList<>();
        for (List<Word> row : rows) {
            double x0 = row.stream().mapToDouble(Word::x0).min().orElse(-1);
            double size = row.stream().mapToDouble(Word::size).max().orElse(bodySize);
            if (x0 < 0 || text(row).length() < 20 || size < bodySize * 0.75 || size > bodySize * 1.35) {
                continue;
            }
            boolean inHalf = leftHalf ? x0 < pageWidth * 0.55 : x0 >= pageWidth * 0.45;
            if (inHalf) {
                xs.add(x0);
            }
        }
        if (xs.isEmpty()) {
            return leftHalf ? 0 : pageWidth;
        }
        xs.sort(Double::compare);
        return xs.get(Math.min(xs.size() - 1, (int) Math.floor(xs.size() * 0.05)));
    }

    /** 正文第一行的 y：首页标题区与正文的分界线（"够长 + 贴栏边"的行里最高的那行） */
    private double bodyStartY(List<List<Word>> rows, double bodySize, double leftFlush, double rightFlush,
                              double gutter, boolean twoColumn) {
        double min = Double.MAX_VALUE;
        double first = Double.MAX_VALUE;
        for (List<Word> row : rows) {
            first = Math.min(first, row.get(0).y());
            if (text(row).length() < 40) {
                continue;
            }
            // 逐词判断：首页那一行常常是"左栏小标题 + 右栏正文"拼在一起，
            // 只看整行最左边的 x 会把右栏正文一起判成居中块（实测摘要行就是这么被吞掉的）
            for (Word w : row) {
                if (w.size() < bodySize * 0.75 || w.size() > bodySize * 1.35) {
                    continue;
                }
                if (twoColumn && !nearFlush(w, leftFlush, rightFlush, gutter)) {
                    continue;
                }
                min = Math.min(min, w.y());
            }
        }
        if (min != Double.MAX_VALUE) {
            return min;
        }
        // 整页都是标题/图（找不到"像正文"的行）：那就从页面最上面开始，不要留空档
        return first == Double.MAX_VALUE ? 0 : first - 1;
    }

    private static boolean crossesGutter(List<Word> part, double gutter) {
        double x0 = part.get(0).x0();
        double x1 = part.get(part.size() - 1).x1();
        return x0 < gutter - 2 && x1 > gutter + 2;
    }

    private static boolean nearFlush(Word w, double leftFlush, double rightFlush, double gutter) {
        double flush = w.x0() >= gutter ? rightFlush : leftFlush;
        return Math.abs(w.x0() - flush) <= FLUSH_TOL * w.size();
    }

    private static double firstY(List<Line> lines) {
        double min = Double.MAX_VALUE;
        for (Line ln : lines) {
            min = Math.min(min, ln.y());
        }
        return min;
    }

    // ------------------------------------------------------------------
    // 词 → 行
    // ------------------------------------------------------------------

    /** 每一段词拼成一行（空格来自 PDFBox 的词分隔回调，不要按坐标猜） */
    private List<Line> toLines(List<List<Word>> parts) {
        List<Line> out = new ArrayList<>();
        for (List<Word> part : parts) {
            if (part.isEmpty()) {
                continue;
            }
            List<Word> sorted = new ArrayList<>(part);
            sorted.sort(Comparator.comparingDouble(Word::x0));
            StringBuilder sb = new StringBuilder();
            int total = 0;
            int boldChars = 0;
            double x0 = Double.MAX_VALUE;
            double x1 = -Double.MAX_VALUE;
            double size = 0;
            for (Word w : sorted) {
                if (w.spaceBefore() && sb.length() > 0) {
                    char last = sb.charAt(sb.length() - 1);
                    char next = w.text().isEmpty() ? ' ' : w.text().charAt(0);
                    if (!(isCjk(last) && isCjk(next))) {
                        sb.append(' ');
                    }
                }
                sb.append(w.text());
                int n = Math.max(1, w.text().length());
                total += n;
                if (w.bold()) {
                    boldChars += n;
                }
                x0 = Math.min(x0, w.x0());
                x1 = Math.max(x1, w.x1());
                size = Math.max(size, w.size());
            }
            out.add(new Line(x0, x1, sorted.get(0).y(), size, boldChars * 2 > total, tidyInline(sb.toString())));
        }
        out.sort(Comparator.comparingDouble(Line::y));
        return out;
    }

    /** 一行拼成的纯文本（只用于长度判断） */
    private static String text(List<Word> row) {
        StringBuilder sb = new StringBuilder();
        for (Word w : row) {
            if (w.spaceBefore() && sb.length() > 0 && !(isCjk(sb.charAt(sb.length() - 1))
                    && !w.text().isEmpty() && isCjk(w.text().charAt(0)))) {
                sb.append(' ');
            }
            sb.append(w.text());
        }
        return sb.toString();
    }

    /** 行首 x 的众数就是段落 flush-left 位置（缩进行不影响它） */
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

    /** 正文字号：按字数加权的众数（0.5pt 一档）—— 标题与脚注是少数派，不该拉偏基准 */
    private double bodySize(List<List<Word>> rows) {
        Map<Integer, Integer> weight = new HashMap<>();
        for (List<Word> row : rows) {
            for (Word w : row) {
                weight.merge((int) Math.round(w.size() * 2), Math.max(1, w.text().length()), Integer::sum);
            }
        }
        int best = weight.entrySet().stream().max(Map.Entry.comparingByValue())
                .map(Map.Entry::getKey).orElse(20);
        return best / 2.0;
    }

    // ------------------------------------------------------------------
    // 成段
    // ------------------------------------------------------------------

    /**
     * 首页标题区：最大字号并成标题、次大并成作者，其余按元信息/小标题处理。
     * <p>标题还要求"够宽"：论文页边常有一条**竖排的 arXiv 水印**（"arXiv:2608.14380v1 [cs.AI] 14 Aug 2026"），
     * 它的字号是整页最大的、宽度却只有一个字高 —— 不加这条会被它抢走标题位（实测踩过）。
     */
    private List<Block> headBlocks(List<Line> lines, double bodySize, int pageNo, double pageWidth) {
        List<Block> out = new ArrayList<>();
        StringBuilder title = new StringBuilder();
        StringBuilder authors = new StringBuilder();
        double minTitleWidth = pageWidth * 0.25;
        double maxSize = lines.stream()
                .filter(l -> l.x1() - l.x0() >= minTitleWidth)
                .mapToDouble(Line::size).max().orElse(bodySize);
        for (Line ln : lines) {
            String text = ln.text();
            if (text.isEmpty()) {
                continue;
            }
            // 上标脚注标记（"1,3,4 ∗"）单独成行，纯噪声；arXiv 页边标记也不是内容
            if (text.length() < 24 && ln.size() < bodySize * 0.85) {
                continue;
            }
            if (ARXIV_STAMP.matcher(text).find()) {
                continue;
            }
            // 标题/作者判定要排在"小标题判定"之前：论文标题本身常常是加粗的，
            // 先走 headingLevel 会把大标题判成二级小标题（实测踩过）
            boolean wideEnough = ln.x1() - ln.x0() >= minTitleWidth;
            if (pageNo == 1 && wideEnough && maxSize >= bodySize * 1.2 && ln.size() >= maxSize * 0.95) {
                append(title, text);                       // 标题常折成两三行：并成一条
                continue;
            }
            if (pageNo == 1 && title.length() > 0 && ln.size() >= bodySize * 1.1) {
                append(authors, text);
                continue;
            }
            int level = headingLevel(text, ln, bodySize);
            if (level > 0) {
                flushHead(out, title, authors);
                out.add(new Block("heading", text, level));
                continue;
            }
            flushHead(out, title, authors);
            // 首页标题区里的小字不都是元信息：机构/邮箱是居中的，而表格、公式是贴边的 ——
            // 一律按"小字"处理会把表格整块变成居中的灰字（实测第 5 页的实验结果表就是这样）
            double center = (ln.x0() + ln.x1()) / 2;
            boolean centered = center > pageWidth * 0.40 && center < pageWidth * 0.60;
            out.add(new Block(centered && ln.size() < bodySize * 1.02 ? "meta" : "para", text, 0));
        }
        flushHead(out, title, authors);
        return out;
    }

    private static void append(StringBuilder sb, String text) {
        if (sb.length() > 0) {
            sb.append(' ');
        }
        sb.append(text);
    }

    private static void flushHead(List<Block> out, StringBuilder title, StringBuilder authors) {
        if (title.length() > 0) {
            out.add(new Block("title", tidyInline(title.toString()), 0));
            title.setLength(0);
        }
        if (authors.length() > 0) {
            out.add(new Block("authors", tidyInline(authors.toString()), 0));
            authors.setLength(0);
        }
    }

    /** 一栏 → 块（带 y，便于通栏块插回阅读顺序） */
    private List<Placed> columnPlaced(Column column, double bodySize, int pageNo, double pageHeight) {
        List<Placed> out = new ArrayList<>();
        if (column == null) {
            return out;
        }
        StringBuilder para = new StringBuilder();
        double paraY = 0;
        boolean bullet = false;
        Line prev = null;

        for (Line ln : column.lines()) {
            String text = ln.text();
            if (text.isEmpty()) {
                continue;
            }
            int level = headingLevel(text, ln, bodySize);
            if (level > 0) {
                flushPlaced(out, para, bullet, paraY);
                bullet = false;
                out.add(new Placed(new Block("heading", text, level), ln.y()));
                prev = ln;
                continue;
            }
            if (BULLET.matcher(text).find()) {
                flushPlaced(out, para, bullet, paraY);
                bullet = true;
                paraY = ln.y();
                para.append(BULLET.matcher(text).replaceFirst(""));
                prev = ln;
                continue;
            }
            // 页脚/侧边注释：字号小一档 + 挨着页面底部 + 不是正在拼的段落
            if (para.length() == 0 && ln.size() < bodySize * 0.92 && text.length() < 160
                    && ln.y() > pageHeight * 0.78) {
                out.add(new Placed(new Block("meta", text, 0), ln.y()));
                prev = ln;
                continue;
            }
            if (para.length() > 0 && prev != null && paragraphBreak(column, prev, ln, bodySize)) {
                flushPlaced(out, para, bullet, paraY);
                bullet = false;
            }
            if (para.length() > 0) {
                joinLine(para, prev, text);
            } else {
                paraY = ln.y();
                para.append(text);
            }
            prev = ln;
        }
        flushPlaced(out, para, bullet, paraY);
        return out;
    }

    private static List<Block> plain(List<Placed> placed) {
        List<Block> out = new ArrayList<>(placed.size());
        for (Placed p : placed) {
            out.add(p.block());
        }
        return out;
    }

    /**
     * 段落断开判定：段间距 / 首行缩进 / 上一行是"没写满的最后一行"。
     * <p>首行缩进必须**和上一行比较**：摘要区整体比正文再缩进一个字位，
     * 只看"离栏左边界多远"会把摘要里每一行都当成新段落（一句一段）。
     */
    private boolean paragraphBreak(Column column, Line prev, Line cur, double bodySize) {
        if (cur.y() - prev.y() > 1.55 * Math.max(prev.size(), bodySize)) {
            return true;
        }
        double indentCur = cur.x0() - column.left();
        double indentPrev = prev.x0() - column.left();
        if (indentCur > 0.55 * cur.size() && indentCur < (column.right() - column.left()) * 0.25
                && indentPrev <= 0.55 * prev.size()) {
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

    private static void flushPlaced(List<Placed> out, StringBuilder para, boolean bullet, double y) {
        String text = para.toString().trim();
        para.setLength(0);
        if (!text.isEmpty()) {
            out.add(new Placed(new Block(bullet ? "bullet" : "para", tidyInline(text), 0), y));
        }
    }

    /**
     * 标题判定：编号小节（1 / 1.1 / 一、）、词表里的固定小节、明显加粗且大一号。
     * <p>都必须"短 + 不以句末标点结尾" —— 正文里出现 "Introduction" 一个词不该被当成标题。
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
     * 栏缝续接：左栏最后一段没写完（无句末标点），右栏第一段又以小写字母/汉字开头 ——
     * 说明它们本来是同一段，被栏边界切开了（实测首页摘要就是这样）。
     */
    private void mergeColumnSeam(List<Placed> leftBlocks, List<Placed> rightBlocks) {
        if (leftBlocks.isEmpty() || rightBlocks.isEmpty()) {
            return;
        }
        Placed a = leftBlocks.get(leftBlocks.size() - 1);
        Placed b = rightBlocks.get(0);
        if (!"para".equals(a.block().type()) || !"para".equals(b.block().type())
                || SENTENCE_END.matcher(a.block().text()).find()) {
            return;
        }
        char first = b.block().text().isEmpty() ? ' ' : b.block().text().charAt(0);
        if (!Character.isLowerCase(first) && !isCjk(first)) {
            return;
        }
        String merged = a.block().text() + (isCjk(first) ? "" : " ") + b.block().text();
        leftBlocks.set(leftBlocks.size() - 1, new Placed(new Block("para", merged, 0), a.y()));
        rightBlocks.remove(0);
    }

    /**
     * 把跨栏通栏块按 y 插回栏内顺序。
     * <p>典型场景：摘要下面的 "Code — https://…" 是通栏的，但阅读顺序上紧跟摘要；
     * 直接放到页面最后就成了"读到结尾突然冒出两行链接"。
     */
    private List<Placed> interleave(List<Placed> column, List<Placed> spanning) {
        if (spanning.isEmpty()) {
            return column;
        }
        List<Placed> sorted = new ArrayList<>(spanning);
        sorted.sort(Comparator.comparingDouble(Placed::y));
        List<Placed> out = new ArrayList<>();
        int i = 0;
        for (Placed p : column) {
            while (i < sorted.size() && sorted.get(i).y() < p.y()) {
                out.add(sorted.get(i));
                i++;
            }
            out.add(p);
        }
        while (i < sorted.size()) {
            out.add(sorted.get(i));
            i++;
        }
        return out;
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
                if (ARXIV_STAMP.matcher(b.text().trim()).find()) {
                    continue;                                    // arXiv 页边标记（每页都有，且常与页码粘连）
                }
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

    /** 行内小清洗：修双重编码乱码、去掉标点前多余空格、压掉重复空格 */
    private static String tidyInline(String s) {
        return SPACE_BEFORE_PUNCT.matcher(DocumentTextService.repairMojibake(s).replaceAll("\\s+", " "))
                .replaceAll("$1").trim();
    }

    private static String brief(String s) {
        String t = s.replaceAll("\\s+", " ").trim();
        return t.length() > 200 ? t.substring(0, 200) + "…" : t;
    }

    // ------------------------------------------------------------------
    // PDFBox 回调：词 + 词分隔 + 行分隔
    // ------------------------------------------------------------------

    /**
     * 收集"行 → 词"。PDFBox 按词回调 {@code writeString}，空格与换行靠
     * {@code writeWordSeparator()} / {@code writeLineSeparator()} 两个回调表达 ——
     * 只重写 writeString 会丢掉所有空格（实测），所以两个分隔符都要接管。
     */
    private static final class RowCollector extends PDFTextStripper {

        private final List<List<Word>> rows = new ArrayList<>();
        private List<Word> cur = new ArrayList<>();
        private boolean spacePending = false;

        private RowCollector() throws IOException {
            super();
        }

        @Override
        protected void writeWordSeparator() {
            spacePending = true;
        }

        @Override
        protected void writeLineSeparator() {
            flushLine();
            spacePending = false;
        }

        @Override
        protected void writeString(String text, List<TextPosition> positions) {
            if (positions == null || positions.isEmpty()) {
                return;
            }
            StringBuilder sb = new StringBuilder();
            for (TextPosition p : positions) {
                String u = p.getUnicode();
                if (u != null) {
                    sb.append(u);
                }
            }
            String word = sb.toString();
            if (word.isBlank()) {
                spacePending = true;
                return;
            }
            TextPosition first = positions.get(0);
            TextPosition last = positions.get(positions.size() - 1);
            cur.add(new Word(first.getXDirAdj(), last.getXDirAdj() + last.getWidthDirAdj(),
                    first.getYDirAdj(), fontSize(first), isBold(first), spacePending, word));
            spacePending = false;
        }

        private void flushLine() {
            if (!cur.isEmpty()) {
                rows.add(cur);
                cur = new ArrayList<>();
            }
        }

        private static double fontSize(TextPosition p) {
            double s = p.getFontSizeInPt();
            return s > 0 ? s : 10;
        }

        private static boolean isBold(TextPosition p) {
            String n = fontName(p);
            return n.contains("bold") || n.contains("black") || n.contains("heavy");
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
