package org.dyh.learnhub.service;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.extern.slf4j.Slf4j;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.contentstream.PDFStreamEngine;
import org.apache.pdfbox.contentstream.operator.Operator;
import org.apache.pdfbox.cos.COSBase;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.graphics.PDXObject;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.pdfbox.text.TextPosition;
import org.apache.pdfbox.util.Matrix;
import org.apache.pdfbox.util.Vector;
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
    private static final Pattern NUMBERED = Pattern.compile("^(\\d+(?:\\.\\d+)*)\\.?\\s+(\\S.*)");
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

    /**
     * 纯数字/刻度的行（"60 68.8 72.3"、"0"、"20.0"…）。
     * <p>坐标轴刻度与表格数据都是这个形态：以前它们会被"加粗 + 字号大"的行规则判成**小标题**
     * （实测某篇论文的柱状图数字变成了 `[heading1] 60 68.8 72.3`）。
     */
    private static final Pattern NUMBERISH = Pattern.compile("^[\\d\\s.,%$€¥:+\\-–—/()\\[\\]{}=]*\\d[\\d\\s.,%$€¥:+\\-–—/()\\[\\]{}=]*$");

    /** 代码/数据结构行的形态（不等宽字体也能认出来：键值对、括号串、注解、命令行…） */
    private static final Pattern CODE_LINE = Pattern.compile(
            "^\\s*(?:[\\{\\}\\[\\]<>]|\"[^\"]{1,40}\"\\s*[:=]|//|/\\*|\\*\\s|@[\\w.]+\\s*[({]|"
            + "[A-Za-z_$][\\w.$-]*\\s*[:=]\\s|\\$\\s|>>>|sudo\\s|npm\\s|mvn\\s|git\\s|docker\\s|curl\\s)");

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

    /**
     * type: title / authors / heading / para / bullet / meta / note / code / table / figure；heading 用 level 表示层级（1 最高）。
     * <p>{@code src} 只有 {@code figure} 用得上：值形如 {@code "12-0"}（第 12 页的第 0 张图），
     * 前端据此拼出取图地址 —— 抽取这一层拿不到 file_id（它只认识文件路径）。
     * <p>{@code meta} 是居中元信息（作者单位、邮箱），{@code note} 是页面底部的脚注小字（左对齐）。
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Block(String type, String text, int level, String src) {
        public Block(String type, String text, int level) {
            this(type, text, level, null);
        }
    }

    public record PageLayout(int page, int columns, List<Block> blocks) {
    }

    public record Layout(String status, String error, List<PageLayout> pages, int chars, int pageCount) {
    }

    /** 页面上的一块图片：{@code idx} 是页内序号，几何是设备坐标（左上角原点、y 向下，和行坐标同一套） */
    public record Figure(int idx, double x0, double y0, double x1, double y1) {
    }

    /** 一个词（PDFBox 的一次调用）：左上角 + 右边界 + 字号 + "前面有没有空格" + 是不是等宽字体 */
    private record Word(double x0, double x1, double y, double size, boolean bold, boolean spaceBefore,
                        boolean mono, String text) {
    }

    /** 一栏内的一行 */
    private record Line(double x0, double x1, double y, double size, boolean bold, boolean mono, String text) {
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
        List<Figure> figures = scanFigures(page, pageNo, width, height, rotation);
        if (rows.isEmpty()) {
            // 整页没字但有图（扫描件、纯图页）：**不硬"识别"**，把这一页当一张图交给界面
            List<Block> only = new ArrayList<>();
            for (Figure f : figures) {
                only.add(figureBlock(pageNo, f));
            }
            return new PageLayout(pageNo, 1, only);
        }
        double bodySize = bodySize(rows);
        // 旋转页（横排大表）坐标系被换过，硬做分栏只会更乱 —— 退化成"一段一段往下排"
        double gutter = rotation == 0 ? detectGutter(rows, width) : width + 1;
        boolean twoColumn = gutter < width;
        double leftFlush = flushOf(rows, bodySize, width, true);
        double rightFlush = flushOf(rows, bodySize, width, false);
        double bodyStartY = bodyStartY(rows, bodySize, leftFlush, rightFlush, gutter, twoColumn, width, height);

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
            if (pageNo == 1) {
                for (Line ln : lines) {
                    if (ln.y() < bodyStartY - 0.6 * ln.size()) {
                        head.add(ln);
                    } else {
                        body.add(ln);
                    }
                }
            } else {
                // 非首页没有"标题区"：标题锚点偶尔会把页首几行（甚至一整块代码）划进 head，
                // 而 head 里的行是**逐行**落块的，一块 JSON 会被切成十几行 `[para]`（实测 p94）。
                body.addAll(lines);
            }
            List<Block> blocks = new ArrayList<>(headBlocks(head, bodySize, pageNo, width));
            blocks.addAll(plain(columnPlaced(columnOf(body), bodySize, pageNo, height, figures)));
            return new PageLayout(pageNo, 1, blocks);
        }

        List<Figure> leftFigures = pickFigures(figures, f -> centerX(f) < gutter && !spansGutter(f, gutter));
        List<Figure> rightFigures = pickFigures(figures, f -> centerX(f) >= gutter && !spansGutter(f, gutter));
        List<Figure> wideFigures = pickFigures(figures, f -> spansGutter(f, gutter));

        List<Line> leftLines = toLines(left);
        List<Line> rightLines = toLines(right);
        List<Placed> leftBlocks = columnPlaced(columnOf(leftLines), bodySize, pageNo, height, leftFigures);
        List<Placed> rightBlocks = columnPlaced(columnOf(rightLines), bodySize, pageNo, height, rightFigures);
        mergeColumnSeam(leftBlocks, rightBlocks);

        // 通栏块：**首页**栏目之上的（标题区）走标题处理；其余（含后续页面的页首通栏行）
        // 一律按正文顺序走，交给 columnPlaced 做段落/表格/代码合并 ——
        // 实测把后续页面的通栏行塞进 headBlocks，会变成"一行一个段落"，整页碎掉。
        List<Line> head = new ArrayList<>();
        List<Line> spanningLines = new ArrayList<>();
        double columnTop = Math.min(firstY(leftLines), firstY(rightLines));
        for (Line ln : toLines(wide)) {
            if (pageNo == 1 && ln.y() < columnTop - 1) {
                head.add(ln);
            } else {
                spanningLines.add(ln);
            }
        }
        List<Placed> spanning = columnPlaced(columnOf(spanningLines), bodySize, pageNo, height, wideFigures);
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
                    w = new Word(w.x0(), w.x1(), w.y(), w.size(), w.bold(), false, w.mono(), w.text());
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
        // 支持率要够：原来是"有缝行"的 1/6 就算数，实测有一页只有零星几行凑出中缝，
        // 整页正文被切成"通栏 + 左栏 + 右栏"三份，读起来七零八落。改成按**整页行数**算，要求 ≥25%。
        int need = Math.max(3, (int) Math.round(rows.size() * 0.25));
        if (best.getValue() < need) {
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
                              double gutter, boolean twoColumn, double pageWidth, double pageHeight) {
        double first = Double.MAX_VALUE;
        for (List<Word> row : rows) {
            first = Math.min(first, row.get(0).y());
        }
        // 页眉（"Published as a conference paper at ICLR 2026"）的字号常常**和正文一样**、又贴栏边，
        // 于是被当成了"正文第一行"，整块标题区被并进正文 —— 实测某篇论文的标题因此变成了 meta。
        // 修正：标题这类"字号更大 + 够宽"的行是分界锚点，正文起点只能落在最后一个锚点下面。
        double anchor = Double.NEGATIVE_INFINITY;
        for (List<Word> row : rows) {
            double rowSize = row.stream().mapToDouble(Word::size).max().orElse(bodySize);
            double x0 = row.get(0).x0();
            double x1 = row.get(row.size() - 1).x1();
            if (rowSize >= bodySize * 1.08 && x1 - x0 >= pageWidth * 0.25 && row.get(0).y() < pageHeight * 0.35) {
                anchor = Math.max(anchor, row.get(0).y());
            }
        }
        double floor = anchor == Double.NEGATIVE_INFINITY ? 0 : anchor + 1;
        double min = Double.MAX_VALUE;
        double relaxed = Double.MAX_VALUE;
        for (List<Word> row : rows) {
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
                relaxed = Math.min(relaxed, w.y());
                if (w.y() >= floor) {
                    min = Math.min(min, w.y());
                }
            }
        }
        if (min != Double.MAX_VALUE) {
            return min;
        }
        if (relaxed != Double.MAX_VALUE) {
            return relaxed;                              // 锚点下面再没有正文（罕见）：退回原判据
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
            int monoChars = 0;
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
                if (w.mono()) {
                    monoChars += n;
                }
                x0 = Math.min(x0, w.x0());
                x1 = Math.max(x1, w.x1());
                size = Math.max(size, w.size());
            }
            out.add(new Line(x0, x1, sorted.get(0).y(), size, boldChars * 2 > total,
                    monoChars * 2 > total, tidyInline(sb.toString())));
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
        // arXiv 标记要排除在"最大字号"之外：实测某篇论文把它横排成 20pt（比 17pt 的标题还大），
        // 于是 maxSize 被它抬走，真正的标题连"够大"这条都过不了，最后一个标题块都没有。
        double maxSize = lines.stream()
                .filter(l -> !ARXIV_STAMP.matcher(l.text()).find())
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
            // 1.12 而不是 1.2：有些期刊把标题排成 1.15 倍正文（实测某篇论文标题没被认出来，
            // 整条标题变成了正文第一段）；同时要求它在本页头部里确实"够大"。
            if (pageNo == 1 && wideEnough && maxSize >= bodySize * 1.12 && ln.size() >= maxSize * 0.93) {
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
            // 首页标题区里剩下的行要分两类：长的完整句子是摘要/正文，短碎片是元信息（单位、邮箱、日期、脚注）。
            // 不能一律当"小字"（会把表格整块变灰字，实测第 5 页实验结果表），也不能一律当正文
            // （作者单位会挤进摘要首段）。判据按"像不像句子"来定。
            boolean prose = text.length() >= 120
                    || (text.length() >= 60 && SENTENCE_END.matcher(text).find());
            double center = (ln.x0() + ln.x1()) / 2;
            boolean centered = center > pageWidth * 0.40 && center < pageWidth * 0.60;
            boolean metaFragment = (centered && ln.size() < bodySize * 1.02)
                    || (text.length() < 60 && text.split("\\s+").length <= 6
                        && !SENTENCE_END.matcher(text).find());
            out.add(new Block(prose || !metaFragment ? "para" : "meta", text, 0));
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

    /**
     * 一栏 → 块（带 y，便于通栏块插回阅读顺序）。
     * <p>{@code figures} 是这一栏范围内的图片：它们按 y 插进正文流里（对应"截图到相应位置"）——
     * 图不能一律堆到页尾，否则读者要自己找它对应哪段话。
     */
    private List<Placed> columnPlaced(Column column, double bodySize, int pageNo, double pageHeight,
                                      List<Figure> figures) {
        List<Placed> out = new ArrayList<>();
        if (column == null) {
            return out;
        }
        StringBuilder para = new StringBuilder();
        double paraY = 0;
        boolean bullet = false;
        double pitch = linePitch(column.lines());
        // 代码/数据段：连续的"代码样"行攒成**一个** code 块（不这样，一页 JSON 会变成二十个段落）
        StringBuilder code = new StringBuilder();
        int codeLines = 0;
        double codeY = 0;
        // 表格行：连续 3 行以上"像表格"的行攒成一个 table 块（行距判定同代码段）
        StringBuilder rows = new StringBuilder();
        int rowLines = 0;
        double rowY = 0;
        Line prev = null;
        int figureAt = 0;

        for (Line ln : column.lines()) {
            String text = ln.text();
            if (text.isEmpty()) {
                continue;
            }
            // 这一行之前的图：先落地（图也要按 y 排进阅读顺序）
            while (figureAt < figures.size() && figures.get(figureAt).y0() < ln.y()) {
                Figure f = figures.get(figureAt++);
                flushPlaced(out, para, bullet, paraY);
                bullet = false;
                flushCode(out, code, codeLines, codeY);
                codeLines = 0;
                flushRows(out, rows, rowLines, rowY);
                rowLines = 0;
                out.add(new Placed(figureBlock(pageNo, f), f.y0()));
            }
            boolean rowishLine = rowish(text, ln, column);
            // 表格行结束：只要遇到一行"不像表格"的，就先把攒着的表格落地（这样下面所有分支都不用管它）
            if (rowLines > 0 && !rowishLine) {
                flushRows(out, rows, rowLines, rowY);
                rowLines = 0;
            }
            int level = headingLevel(text, ln, bodySize);
            if (level > 0) {
                flushPlaced(out, para, bullet, paraY);
                bullet = false;
                flushCode(out, code, codeLines, codeY);
                codeLines = 0;
                out.add(new Placed(new Block("heading", text, level), ln.y()));
                prev = ln;
                continue;
            }
            // 代码/数据行：等宽字体是字体级证据；不是等宽也能靠形态认（键值对、括号、注解、命令）
            if (ln.mono() || looksLikeCode(text)) {
                flushPlaced(out, para, bullet, paraY);
                bullet = false;
                // 与原代码段隔了明显空行 → 当作另一段。判据同样要看**实际行距**：
                // 代码区行距本来就比正文松，只按字号比会把每行都当成新的一段（一块 JSON 又碎成十几段）
                double codeGap = Math.max(2.2 * Math.max(ln.size(), bodySize), 2.5 * pitch);
                if (codeLines > 0 && prev != null && ln.y() - prev.y() > codeGap) {
                    flushCode(out, code, codeLines, codeY);
                    codeLines = 0;
                }
                if (codeLines == 0) {
                    codeY = ln.y();
                }
                if (code.length() > 0) {
                    code.append('\n');
                }
                code.append(text);
                codeLines++;
                prev = ln;
                continue;
            }
            // 非代码行：先把攒着的代码段落地
            if (codeLines > 0) {
                flushCode(out, code, codeLines, codeY);
                codeLines = 0;
                prev = ln;
            }
            // 表格行：短、无句末标点、不含汉字、以大写/数字/符号开头（"Model NQ TQA"、"Gold 44.9"）。
            // 先攒着，结束或成段时再决定是"一张表"还是"几个短段落"。
            if (rowishLine) {
                flushPlaced(out, para, bullet, paraY);
                bullet = false;
                double rowGap = Math.max(2.2 * Math.max(ln.size(), bodySize), 2.5 * pitch);
                if (rowLines > 0 && prev != null && ln.y() - prev.y() > rowGap) {
                    flushRows(out, rows, rowLines, rowY);
                    rowLines = 0;
                }
                if (rowLines == 0) {
                    rowY = ln.y();
                }
                if (rows.length() > 0) {
                    rows.append('\n');
                }
                rows.append(text);
                rowLines++;
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
            // 页脚/脚注：字号**明显**小一档（<0.88 倍正文）+ 挨着页面底部。**不管段落是否正在拼**都要切一刀 ——
            // 论文底部的脚注常被当成上一段正文的续行，两三条脚注黏成一段（实测某篇论文首页：
            // "∗Equal contribution 1The code and trained models have been released at …"）。
            // 字号这一刀要够狠：正文最后几行也在页面底部，0.92 那种松阈值会把它们误判成脚注（实测踩过）。
            // 类型单独给 `note`（而不是 meta）：脚注**左对齐**、作者单位**居中**，两者排版不是一回事。
            if (ln.size() < bodySize * 0.88 && text.length() < 300 && ln.y() > pageHeight * 0.84) {
                flushPlaced(out, para, bullet, paraY);
                bullet = false;
                out.add(new Placed(new Block("note", text, 0), ln.y()));
                prev = ln;
                continue;
            }
            if (para.length() > 0 && prev != null && paragraphBreak(column, prev, ln, bodySize, pitch)) {
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
        flushCode(out, code, codeLines, codeY);
        flushRows(out, rows, rowLines, rowY);
        // 栏目末尾剩下的图（正文比图短时会出现）
        while (figureAt < figures.size()) {
            Figure f = figures.get(figureAt++);
            out.add(new Placed(figureBlock(pageNo, f), f.y0()));
        }
        // 表格/图表里的短块（单元格、坐标轴刻度、图例）合成一个块：
        // 不合并的话它们会以"正文段落"的样子出现，一页结果表能刷出几十个一句一行的段落
        return mergeTableCellRuns(out);
    }

    /**
     * 扫描一页里的图片位置。
     *
     * <p>为什么要"扫位置"而不是整页截图：论文的图是位图/矢量混排的，整页截图会把两栏正文一起塞进图里。
     * 这里拿到的是**一张图被画在哪**（单位正方形经 CTM 变换后的包围盒），于是可以只裁那一块，
     * 而且因为 y 与正文行同一套坐标，能按 y 插回阅读顺序。
     */
    private List<Figure> scanFigures(PDPage page, int pageNo, double width, double height, int rotation) {
        if (rotation != 0) {
            return List.of();                     // 旋转页坐标系被换过，裁出来的位置对不上，宁可不贴图
        }
        List<double[]> raw = new PdfFigureScanner(page).scan();
        double pageArea = width * height;
        List<double[]> kept = new ArrayList<>();
        for (double[] r : raw) {
            double x0 = Math.max(0, r[0]);
            double y0 = Math.max(0, r[1]);
            double x1 = Math.min(width, r[2]);
            double y1 = Math.min(height, r[3]);
            if (x1 - x0 < 24 || y1 - y0 < 24 || (x1 - x0) * (y1 - y0) < pageArea * 0.01) {
                continue;                          // 分隔线、图标、项目符号、页边 logo 都不是"图"
            }
            kept.add(new double[]{x0, y0, x1, y1});
        }
        // 同一张图常被重复画（掩膜、拼贴、多次 Do）：去掉被更大的框大部分盖住的那个
        kept.sort(Comparator.comparingDouble((double[] r) -> -(r[2] - r[0]) * (r[3] - r[1])));
        List<double[]> uniq = new ArrayList<>();
        for (double[] r : kept) {
            boolean covered = uniq.stream().anyMatch(k -> overlapRatio(k, r) > 0.8);
            if (!covered) {
                uniq.add(r);
            }
        }
        uniq.sort(Comparator.comparingDouble(r -> r[1]));
        List<Figure> out = new ArrayList<>();
        for (int i = 0; i < uniq.size(); i++) {
            double[] r = uniq.get(i);
            out.add(new Figure(i, r[0], r[1], r[2], r[3]));
        }
        return out;
    }

    private static Block figureBlock(int pageNo, Figure f) {
        return new Block("figure", "", 0, pageNo + "-" + f.idx());
    }

    private static List<Figure> pickFigures(List<Figure> all, java.util.function.Predicate<Figure> p) {
        List<Figure> out = new ArrayList<>();
        for (Figure f : all) {
            if (p.test(f)) {
                out.add(f);
            }
        }
        return out;
    }

    private static double centerX(Figure f) {
        return (f.x0() + f.x1()) / 2;
    }

    private static boolean spansGutter(Figure f, double gutter) {
        return f.x0() < gutter && f.x1() > gutter;
    }

    /** b 有多大比例落在 a 里 */
    private static double overlapRatio(double[] a, double[] b) {
        double w = Math.min(a[2], b[2]) - Math.max(a[0], b[0]);
        double h = Math.min(a[3], b[3]) - Math.max(a[1], b[1]);
        if (w <= 0 || h <= 0) {
            return 0;
        }
        return (w * h) / ((b[2] - b[0]) * (b[3] - b[1]));
    }

    /**
     * 某页第 {@code idx} 张图的裁剪框（px，左上角原点）——给"按需渲染图片"用。
     * <p>{@code dpi/72} 是渲染缩放：PDF 的坐标是 pt（1/72 英寸），渲染出来是像素。
     */
    public int[] figureRect(Path path, int pageNo, int idx, double dpi) {
        try (PDDocument doc = Loader.loadPDF(path.toFile())) {
            if (pageNo < 1 || pageNo > doc.getNumberOfPages()) {
                return null;
            }
            PDPage page = doc.getPage(pageNo - 1);
            PDRectangle box = page.getCropBox() != null ? page.getCropBox() : page.getMediaBox();
            int rotation = ((page.getRotation() % 360) + 360) % 360;
            for (Figure f : scanFigures(page, pageNo, box.getWidth(), box.getHeight(), rotation)) {
                if (f.idx() == idx) {
                    double s = dpi / 72.0;
                    int x = (int) Math.round(f.x0() * s);
                    int y = (int) Math.round(f.y0() * s);
                    int w = (int) Math.round((f.x1() - f.x0()) * s);
                    int h = (int) Math.round((f.y1() - f.y0()) * s);
                    return new int[]{x, y, Math.max(1, w), Math.max(1, h)};
                }
            }
            return null;
        } catch (Throwable t) {
            log.warn("取图片区域失败: {} p{} #{} - {}", path.getFileName(), pageNo, idx, t.toString());
            return null;
        }
    }

    /**
     * 把攒下来的表格行落地。
     * <p>≥3 行才算一张表；少于 3 行更可能是正文里的短句（"the correct answer"），按普通短段落逐行输出，
     * 免得把正文伪装成表格。
     */
    private static void flushRows(List<Placed> out, StringBuilder rows, int lines, double y) {
        String text = rows.toString();
        rows.setLength(0);
        if (text.isEmpty()) {
            return;
        }
        if (lines >= 3) {
            out.add(new Placed(new Block("table", text, 0), y));
            return;
        }
        for (String row : text.split("\n")) {
            out.add(new Placed(new Block("para", row, 0), y));
        }
    }

    /**
     * 像表格/图表标签的一行：短、没有句末标点、不是列表项、不含汉字、以大写字母/数字/符号开头，
     * 而且**没有顶到栏的右边界**。
     * <p>不含汉字这条很重要：中文短行（"本章主要涉及的知识点有："）绝大多数是正文小标题或列表，
     * 把它们当单元格会让整段说明变成"一张表"。右边界这条同样重要：正文的折行会顶到右边界，
     * 表格/图里的单元格不会 —— 少了它，正文里任何一行短句都会被割出来（实测段落被切回一行一段）。
     */
    private static boolean rowish(String text, Line ln, Column column) {
        String t = text.trim();
        if (t.isEmpty() || t.length() > 70) {
            return false;
        }
        if (SENTENCE_END.matcher(t).find() || BULLET.matcher(t).find()) {
            return false;
        }
        if (t.codePoints().anyMatch(c -> c >= 0x2E80)) {
            return false;
        }
        if (column.right() - ln.x1() < 1.2 * ln.size()) {
            return false;
        }
        char first = t.charAt(0);
        boolean strongStart = Character.isUpperCase(first) || Character.isDigit(first)
                || "([{<\"'#+-".indexOf(first) >= 0;
        if (!strongStart) {
            return false;
        }
        // 纯数字行（刻度、数据行）长度可以放到 70："32.1 33.1 17.5 22.3 55.5 …" 这种整行都是数
        return NUMBERISH.matcher(t).matches() || (allStrong(t) && t.length() <= 60);
    }

    /**
     * 每个词都以大写字母/数字/符号开头，且至少两个词。
     * <p>这是"表格行 / 图表图例"最稳的形态特征（"Model NQ TQA"、"Gold 44.9"、"Task Input"）；
     * 正文折行里几乎总有 the/of/and 这类小写词（"the original"）。单看"短"是不够的，
     * 短句子太多了，会把正常段落割开。
     */
    private static boolean allStrong(String t) {
        String[] words = t.split("\\s+");
        if (words.length < 2) {
            return false;
        }
        for (String w : words) {
            if (!w.isEmpty() && Character.isLowerCase(w.charAt(0))) {
                return false;
            }
        }
        return true;
    }

    /**
     * 把攒下来的代码段落地。
     * <p>只认**连续两行以上**：单行"像代码"的文本很可能是正文里的一句（例如以 `@` 开头的注解说明），
     * 硬塞进 code 块反而会把它从段落里割出来 —— 单行就按普通段落处理。
     */
    private static void flushCode(List<Placed> out, StringBuilder code, int lines, double y) {
        String text = code.toString().trim();
        code.setLength(0);
        if (text.isEmpty()) {
            return;
        }
        out.add(new Placed(new Block(lines >= 2 ? "code" : "para", text, 0), y));
    }

    /** 像代码/数据结构的一行：等宽之外的第二重证据（很多书与论文的代码并不用等宽字体） */
    private static boolean looksLikeCode(String text) {
        String t = text.trim();
        if (t.isEmpty() || t.length() > 90 || SENTENCE_END.matcher(t).find()) {
            return false;
        }
        if (CODE_LINE.matcher(t).find()) {
            return true;
        }
        // 括号/引号/等号密集且很短 → 多半是 JSON、SQL、配置或命令行
        int symbol = 0;
        for (char c : t.toCharArray()) {
            if ("{}()[]\"'=;:<>|\\".indexOf(c) >= 0) {
                symbol++;
            }
        }
        return symbol >= 4 && symbol * 4 >= t.length();
    }

    /**
     * 表格 / 图表区域合并。
     * <p>判据：连续 ≥3 个"短、且不像完整句子"的段落块。这类块在真实文档里几乎只有两种来源 ——
     * 表格单元格（"Doc 3"、"Task Input"）与图里的刻度/图例（"0"、"60 68.8 72.3"）。
     * 合成一个 `table` 块后，界面按"一行一行"紧凑渲染，而不是排版成一段段正文。
     */
    private static List<Placed> mergeTableCellRuns(List<Placed> blocks) {
        List<Placed> out = new ArrayList<>();
        int i = 0;
        while (i < blocks.size()) {
            if (!cellish(blocks.get(i).block())) {
                out.add(blocks.get(i));
                i++;
                continue;
            }
            int j = i;
            while (j < blocks.size() && cellish(blocks.get(j).block())) {
                j++;
            }
            if (j - i >= 3) {
                StringBuilder sb = new StringBuilder();
                for (int k = i; k < j; k++) {
                    if (sb.length() > 0) {
                        sb.append('\n');
                    }
                    sb.append(blocks.get(k).block().text().trim());
                }
                out.add(new Placed(new Block("table", sb.toString(), 0), blocks.get(i).y()));
                i = j;
            } else {
                out.add(blocks.get(i));
                i++;
            }
        }
        return out;
    }

    private static boolean cellish(Block b) {
        if (!"para".equals(b.type())) {
            return false;
        }
        String t = b.text().trim();
        if (t.isEmpty() || t.length() > 36) {
            return false;
        }
        // 完整句子（有句末标点）或列表项不算单元格
        if (SENTENCE_END.matcher(t).find() || BULLET.matcher(t).find()) {
            return false;
        }
        // 纯刻度数字，或很短的标签（表格单元格/图例）
        return NUMBERISH.matcher(t).matches() || t.length() <= 24;
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
     * <p>段间距也不能只跟字号比：行距 1.6 倍的中文书里"相邻两行"就超过 1.55×字号，
     * 结果整本书被按行切碎（实测 44% 的段落不到 40 字）。所以要跟**实测行距**取大者。
     */
    private boolean paragraphBreak(Column column, Line prev, Line cur, double bodySize, double pitch) {
        double limit = Math.max(1.55 * Math.max(prev.size(), bodySize), 1.45 * pitch);
        limit = Math.min(limit, 2.5 * Math.max(prev.size(), bodySize));
        if (cur.y() - prev.y() > limit) {
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

    /**
     * 栏内行距：相邻行 y 差的 40% 分位。
     * <p>用分位而不是中位数：段与段之间的大空隙是少数，中位数会被它们抬高，
     * 抬到最后"任何空隙都不算换段"。40% 分位落在"同一段内的相邻行"上。
     */
    private static double linePitch(List<Line> lines) {
        List<Double> gaps = new ArrayList<>();
        for (int i = 1; i < lines.size(); i++) {
            double g = lines.get(i).y() - lines.get(i - 1).y();
            if (g > 0.5) {
                gaps.add(g);
            }
        }
        if (gaps.isEmpty()) {
            return 0;
        }
        gaps.sort(Double::compare);
        return gaps.get(Math.min(gaps.size() - 1, (int) Math.floor(gaps.size() * 0.4)));
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
        // 先过"是不是文字"这一关：坐标轴刻度（"60 68.8 72.3"）、表格里的数字串都长成标题的样子
        // （短、加粗、还常常比正文大一号），实测某篇论文的柱状图数字变成了 [heading1]。
        int letters = 0;
        int digits = 0;
        for (char c : t.toCharArray()) {
            if (Character.isLetter(c)) {
                letters++;
            } else if (Character.isDigit(c)) {
                digits++;
            }
        }
        if (letters == 0 || digits > letters * 2) {
            return 0;
        }
        // 编号小节要"像小节"：章节号不会长到 2016，整行也要够短，而且编号后面要是"词"——
        // 图表里的轴标签/图例偏偏长着"数字 + 空格 + 大写词"的样子（"39 RAG-Seq 40"、
        // "2016 world leaders and 68% ..."），公式片段更像（"1 ∗ a"），实测都被当成 [heading1] 混进正文。
        var numbered = NUMBERED.matcher(t);
        if (numbered.matches() && !SENTENCE_END.matcher(t).find()
                && t.length() <= 70 && t.split("\\s+").length <= 12
                && sectionNumberish(numbered.group(1))
                && startsLikeHeadingWord(numbered.group(2))) {
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
        // 「加粗 + 大一号」这条最容易被图表里的标签蹭到，所以额外收三个口子：
        //   ① 长度 ≤60（更长的加粗文本多半是图注/要点句，不是小节标题）；
        //   ② 不以数字开头（"80 Before tuning" 是图的纵轴标签）；
        //   ③ 单个拉丁词不算（"Bamboogle" 是横轴刻度；真正的小节词如 References 已在词表里）。
        if (!ln.bold() || ln.size() <= bodySize * 1.04 || t.split("\\s+").length > 10) {
            return 0;
        }
        if (t.length() > 60 || t.matches("^[\\d.]+\\s.*")) {
            return 0;
        }
        // 图里的数值标签（"Acc: 77.2% (+17.2%) Acc: 76.0%"）也常加粗：一半以上是数字/符号的直接否掉
        if (letters * 2 < t.length()) {
            return 0;
        }
        boolean cjk = t.codePoints().anyMatch(c -> c >= 0x2E80);
        return (cjk || t.split("\\s+").length >= 2) ? 2 : 0;
    }

    /** 章节号是否可信：首段 ≤30（"2016" 这种年份不是章节号） */
    private static boolean sectionNumberish(String num) {
        String head = num.split("\\.")[0];
        try {
            return Integer.parseInt(head) <= 30;
        } catch (NumberFormatException e) {
            return false;
        }
    }

    /** 章节号后面接的是不是"标题词"：大写开头的拉丁词、数字、汉字，或者括号编号（"(1) …"） */
    private static boolean startsLikeHeadingWord(String rest) {
        if (rest.isEmpty()) {
            return false;
        }
        char c = rest.charAt(0);
        return Character.isUpperCase(c) || Character.isDigit(c) || c >= 0x2E80 || "（(【".indexOf(c) >= 0;
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
                    first.getYDirAdj(), fontSize(first), isBold(first), spacePending, isMono(first), word));
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

        /** 等宽字体：代码块的**字体级**证据（很多书/论文的代码也用等宽，这是最可靠的信号） */
        private static boolean isMono(TextPosition p) {
            String n = fontName(p);
            return n.contains("mono") || n.contains("courier") || n.contains("consol") || n.contains("menlo")
                    || n.contains("typewriter") || n.contains("ttype") || n.contains("code");
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
