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
import org.apache.pdfbox.pdmodel.font.encoding.GlyphList;
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
import java.util.regex.Matcher;
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

    /**
     * 脚注标记（"∗These authors contributed equally."、"†Corresponding author."、"1The code…"）。
     *
     * <p><b>只匹配标记本身</b>，正文一律留给调用方（见 {@link #stripNoteMarker}）：
     * 早先这里写成 {@code ^\s*(标记)\s*\S.*} —— 末尾那个 {@code \S.*} 会把正文一起吃掉，
     * {@code replaceFirst("")} 之后脚注整条变成空串（实测踩过：
     * {@code ∗These authors contributed equally.} 去标记后什么都不剩，脚注再也落不了块）。
     *
     * <p>编号兼容紧贴和空格分隔（{@code 1The}、{@code 1 The}）；是否为脚注仍由
     * {@link #noteLine} 的页脚位置、字号与正文边界共同判断，不能仅凭行首数字分类。
     */
    private static final Pattern NOTE_MARKER = Pattern.compile(
            "^\\s*(?:[*∗†‡§¶]+|\\d{1,2}[∗†‡]|\\d{1,2}\\s*(?=[A-Za-z\\u4e00-\\u9fa5]))");

    /**
     * 符号型脚注标记（{@code ∗} {@code †} {@code ‡}）：只有它们是"一定是脚注"的强证据。
     *
     * <p><b>不能带"后面还要有字"的尾巴</b>：这里曾经写成 {@code ^\s*[*∗†‡§¶]+\s*\S}，
     * 而 {@link #stripNoteMarker} 用 {@code matcher.end()} 截断 —— 那个 {@code \S} 正好是
     * 正文的第一个字母，于是 {@code ∗These authors…} 被削成 {@code hese authors…}
     * （实测踩过：脚注首字母消失）。判定"是不是标记"和"标记到哪结束"必须用同一个正则。
     */
    private static final Pattern NOTE_SYMBOL = Pattern.compile("^\\s*[*∗†‡§¶]+");
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
    // 数学字形恢复（公式里的 "?" 有一类来自字体缺 ToUnicode 映射）
    // ------------------------------------------------------------------

    /**
     * Adobe 数学字体的子集名（CMMI=数学斜体、CMSY=数学符号、CMEX=大符号、MSAM/MSBM=AMS 符号）。
     * <p>只在**这些字体**里做字形名恢复：它们是标准化的 TeX 字体，字形名与 Unicode 一一对应；
     * 别的字体（尤其是 CID 子集）恢复不可靠，宁可保留 "?"。
     */
    private static final Pattern MATH_FONT =
            Pattern.compile("(?:^|[+|])(?:CM(?:MI|SY|EX|R|BX|TI|SS)|MS[AB]M|EUSM|RSFS)", Pattern.CASE_INSENSITIVE);

    /**
     * 字形名 → Unicode（只为数学字体准备；名字取 Adobe Glyph List 的标准写法）。
     * <p>实测依据：在 uploads 的 6 份 PDF（594 页）里，PDFBox 报出的"缺映射"字形只有 5 种
     * （{@code star} / {@code latticetop} / {@code lessmuch} + 两个 Dingbats），
     * 所以这张表的收益主要在**同类论文/讲义**上；表里其余条目是数学字体里最常被用到的符号，
     * 宁可多几条也不让常见符号漏成 "?"。
     */
    private static final Map<String, String> MATH_GLYPH_UNICODE = Map.ofEntries(
            // 希腊字母（小写）
            Map.entry("alpha", "α"), Map.entry("beta", "β"), Map.entry("gamma", "γ"),
            Map.entry("delta", "δ"), Map.entry("epsilon", "ε"), Map.entry("varepsilon", "ε"),
            Map.entry("zeta", "ζ"), Map.entry("eta", "η"), Map.entry("theta", "θ"),
            Map.entry("vartheta", "ϑ"), Map.entry("iota", "ι"), Map.entry("kappa", "κ"),
            Map.entry("lambda", "λ"), Map.entry("mu", "μ"), Map.entry("nu", "ν"),
            Map.entry("xi", "ξ"), Map.entry("pi", "π"), Map.entry("varpi", "ϖ"),
            Map.entry("rho", "ρ"), Map.entry("varrho", "ϱ"), Map.entry("sigma", "σ"),
            Map.entry("varsigma", "ς"), Map.entry("tau", "τ"), Map.entry("upsilon", "υ"),
            Map.entry("phi", "φ"), Map.entry("varphi", "φ"), Map.entry("chi", "χ"),
            Map.entry("psi", "ψ"), Map.entry("omega", "ω"),
            // 希腊字母（大写）
            Map.entry("Gamma", "Γ"), Map.entry("Delta", "Δ"), Map.entry("Theta", "Θ"),
            Map.entry("Lambda", "Λ"), Map.entry("Xi", "Ξ"), Map.entry("Pi", "Π"),
            Map.entry("Sigma", "Σ"), Map.entry("Upsilon", "Υ"), Map.entry("Phi", "Φ"),
            Map.entry("Psi", "Ψ"), Map.entry("Omega", "Ω"),
            // 关系与运算
            Map.entry("star", "⋆"), Map.entry("asteriskmath", "∗"),
            Map.entry("plusminus", "±"), Map.entry("minus", "−"), Map.entry("minusplus", "∓"),
            Map.entry("multiply", "×"), Map.entry("divide", "÷"), Map.entry("circlemultiply", "⊗"),
            Map.entry("circleplus", "⊕"), Map.entry("circledot", "⊙"), Map.entry("bullet", "•"),
            Map.entry("periodcentered", "·"), Map.entry("dotmath", "⋅"),
            Map.entry("lessmuch", "≪"), Map.entry("greatermuch", "≫"),
            Map.entry("lessequal", "≤"), Map.entry("greaterequal", "≥"),
            Map.entry("notequal", "≠"), Map.entry("approxequal", "≈"),
            Map.entry("equivalence", "≡"), Map.entry("similar", "∼"), Map.entry("congruent", "≅"),
            Map.entry("proportional", "∝"), Map.entry("reflexsubset", "⊆"), Map.entry("reflexsuperset", "⊇"),
            Map.entry("propersubset", "⊂"), Map.entry("propersuperset", "⊃"),
            Map.entry("element", "∈"), Map.entry("notelement", "∉"),
            Map.entry("union", "∪"), Map.entry("intersection", "∩"), Map.entry("emptyset", "∅"),
            Map.entry("infinity", "∞"), Map.entry("partialdiff", "∂"), Map.entry("gradient", "∇"),
            Map.entry("summation", "∑"), Map.entry("product", "∏"), Map.entry("integral", "∫"),
            Map.entry("radical", "√"),
            Map.entry("forall", "∀"), Map.entry("existential", "∃"), Map.entry("therefore", "∴"),
            Map.entry("angle", "∠"), Map.entry("perpendicular", "⊥"), Map.entry("parallel", "∥"),
            Map.entry("logicalnot", "¬"), Map.entry("logicaland", "∧"), Map.entry("logicalor", "∨"),
            Map.entry("arrowright", "→"), Map.entry("arrowleft", "←"),
            Map.entry("arrowboth", "↔"), Map.entry("arrowdblright", "⇒"),
            Map.entry("arrowdblleft", "⇐"), Map.entry("arrowdblboth", "⇔"),
            Map.entry("latticetop", "⊤"),
            Map.entry("prime", "′"), Map.entry("second", "″"),
            Map.entry("ellipsis", "…"), Map.entry("summationdisplay", "∑"),
            Map.entry("floorleft", "⌊"), Map.entry("floorright", "⌋"),
            Map.entry("ceilingleft", "⌈"), Map.entry("ceilingright", "⌉"),
            Map.entry("angleleft", "⟨"), Map.entry("angleright", "⟩"),
            Map.entry("bardbl", "‖"), Map.entry("dagger", "†"), Map.entry("daggerdbl", "‡"));

    /** 未映射的占位字符：PDFBox 在字体缺 cmap/ToUnicode 时给的就是这些 */
    private static boolean placeholder(String u) {
        return u == null || u.isEmpty() || u.charAt(0) == '?' || u.charAt(0) == '\uFFFD';
    }

    /**
     * 有字形名就用字形名换真符号（限已知数学字体与 Dingbats 的标准映射）。
     * <p>为什么不用 PDFBox 的 {@code toUnicode}：它给出的就是 {@code ?} —— 缺映射时无从下手，
     * 而**字形名是好的**（PDFBox 自己的警告里写着 {@code No Unicode mapping for lessmuch (28)}），
     * 所以拿编码去问 {@code PDFont} 要字形名，再查表。
     */
    private static String recoverGlyph(TextPosition p) {
        try {
            if (p.getFont() == null) {
                return null;
            }
            String name = glyphName(p);
            String fontName = String.valueOf(p.getFont().getName());
            if (fontName.toLowerCase(Locale.ROOT).contains("dingbats")) {
                return name == null ? null : GlyphList.getZapfDingbats().toUnicode(name);
            }
            if (!MATH_FONT.matcher(fontName).find()) {
                return null;
            }
            return name == null ? null : MATH_GLYPH_UNICODE.get(name);
        } catch (Exception e) {
            return null;
        }
    }

    /** 取字形名：PDFont → 编码表 → 码位对应的名字，拿不到就返回 null（不猜） */
    private static String glyphName(TextPosition p) {
        return GlyphNames.nameOf(p.getFont(), GlyphNames.codeOf(p));
    }

    // ------------------------------------------------------------------
    // 对外结构（前端按 type 排版）
    // ------------------------------------------------------------------

    /**
     * type: title / authors / heading / para / bullet / meta / note / code / table / figure / formula；
     * heading 用 level 表示层级（1 最高）。
     * <p>{@code src} 只有 {@code figure} 用得上：值形如 {@code "12-0"}（第 12 页的第 0 张图），
     * 前端据此拼出取图地址 —— 抽取这一层拿不到 file_id（它只认识文件路径）。
     * <p>{@code page} 与 {@code rect}（pt，左上角原点、y 向下）是**可选几何**：`formula` 块带它，
     * 界面就能把这块按原 PDF 渲染出来贴上去（公式的排版、上下标只有原图是准的，见 §5.3）。
     * <p>{@code meta} 是居中元信息（作者单位、邮箱），{@code note} 是页面底部的脚注小字（左对齐），
     * {@code formula} 是"像公式"的行（见 {@link #mergeFormulaRuns}：不做 LaTeX 化，只单独成块、不与正文混排）。
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Block(String type, String text, int level, String src, Integer page, double[] rect) {
        public Block(String type, String text, int level) {
            this(type, text, level, null, null, null);
        }

        public Block(String type, String text, int level, String src) {
            this(type, text, level, src, null, null);
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
                        boolean mono, String text, double top, double bottom) {
    }

    /** 一栏内的一行：{@code monoRatio} 是等宽字符占比（代码块的字体级证据，脚注判定要用它排除代码） */
    record Line(double x0, double x1, double y, double size, double domSize, boolean bold, boolean mono,
                double monoRatio, String text, double top, double bottom) {
        Line(double x0, double x1, double y, double size, double domSize, boolean bold, boolean mono,
             double monoRatio, String text) {
            this(x0, x1, y, size, domSize, bold, mono, monoRatio, text, y - size, y + size * 0.3);
        }
    }

    /** 带纵向位置的块：用来把"跨栏通栏块"按 y 插回栏内阅读顺序 */
    private record Placed(Block block, double y) {
    }

    /** 段落累加器：文本 + 几何（几何给"公式块按原图渲染"用，见 {@link #mergeFormulaRuns}） */
    private static final class Para {
        private final StringBuilder text = new StringBuilder();
        private double y;
        private double x0 = Double.MAX_VALUE;
        private double x1 = -Double.MAX_VALUE;
        private double y0 = Double.MAX_VALUE;
        private double y1 = -Double.MAX_VALUE;
        private double size;
        private boolean bullet;

        void add(Line ln, String t, boolean asBullet) {
            if (text.length() == 0) {
                y = ln.y();
            }
            text.append(t);
            x0 = Math.min(x0, ln.x0());
            x1 = Math.max(x1, ln.x1());
            y0 = Math.min(y0, ln.top());
            y1 = Math.max(y1, ln.bottom());
            size = Math.max(size, ln.size());
            bullet = asBullet;
        }

        /** 续接一行（空格/连字符的规则由调用方处理），只更新几何 */
        void extend(Line ln, String t) {
            text.append(t);
            x0 = Math.min(x0, ln.x0());
            x1 = Math.max(x1, ln.x1());
            y0 = Math.min(y0, ln.top());
            y1 = Math.max(y1, ln.bottom());
            size = Math.max(size, ln.size());
        }

        boolean isEmpty() {
            return text.length() == 0;
        }

        int length() {
            return text.length();
        }

        /**
         * 当前几何（pt，左上角原点、y 向下）。
         *
         * <p>取所有字形边界的并集并留少量边距；上下标和大运算符不能只用第一字的基线推算。
         */
        double[] rect() {
            if (text.length() == 0) {
                return null;
            }
            double padding = Math.max(2, size * 0.18);
            return new double[]{Math.max(0, x0 - padding), Math.max(0, y0 - padding),
                    x1 + padding, y1 + padding};
        }

        void clear() {
            text.setLength(0);
            x0 = Double.MAX_VALUE;
            x1 = -Double.MAX_VALUE;
            y0 = Double.MAX_VALUE;
            y1 = -Double.MAX_VALUE;
            size = 0;
            bullet = false;
        }
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
        collector.flushLine();                      // PDFBox 的页尾不保证回调 writeLineSeparator

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

        // 分流：行内先按中缝切，再决定这一段属于哪一栏（居中块整行当通栏，不切）。
        // 首页底部还多一刀"按字号切"：论文的脚注常常与正文**同一条基线**（PDFBox 于是把它们并进
        // 同一行），实测某篇论文首页第 87% 行是
        // "∗These authors contributed equally. real-world engineering resources, to evaluate task com-" ——
        // 左边是 5/8pt 的脚注、右边是 9pt 正文。不切的话脚注会被并进正文段落（用户报的就是这个）。
        List<List<Word>> wide = new ArrayList<>();
        List<List<Word>> left = new ArrayList<>();
        List<List<Word>> right = new ArrayList<>();
        for (List<Word> row : rows) {
            double rowSize = row.stream().mapToDouble(Word::size).max().orElse(bodySize);
            boolean aboveBody = pageNo == 1 && row.get(0).y() < bodyStartY - 0.6 * rowSize;
            boolean centeredRow = aboveBody && row.stream().anyMatch(w -> !nearFlush(w, leftFlush, rightFlush, gutter));
            boolean headZone = pageNo == 1 && row.get(0).y() < bodyStartY - 0.6 * rowSize;
            // 脚注切分门槛按"这一行自己"的形态定（行首文本 + y）：<0 表示这一行不切
            double noteBoundary = noteSplitBoundary(toLine(row), bodySize, height);
            List<List<Word>> parts = new ArrayList<>();
            for (List<Word> head : splitBySize(row, noteBoundary)) {
                parts.addAll(splitByGutter(head, twoColumn && !centeredRow, gutter));
            }
            for (List<Word> part : parts) {
                double partSize = part.stream().mapToDouble(Word::size).max().orElse(bodySize);
                if (headZone && part.get(0).y() < bodyStartY - 0.6 * partSize) {
                    wide.add(part);                  // 标题区：不按栏分，交给 headBlocks（脚注在里面按字号切开了）
                } else if (!twoColumn || centeredRow) {
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
            List<Block> blocks = new ArrayList<>(
                    headBlocks(head, bodySize, pageNo, width, height, bodyBottomOf(head, bodySize, height)));
            blocks.addAll(mergeFormulaRuns(columnPlaced(columnOf(body), bodySize, pageNo, height, figures), pageNo));
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
        List<Block> blocks = new ArrayList<>(
                headBlocks(head, bodySize, pageNo, width, height, bodyBottomOf(head, bodySize, height)));
        blocks.addAll(mergeFormulaRuns(interleave(leftBlocks, spanning), pageNo));
        blocks.addAll(mergeFormulaRuns(rightBlocks, pageNo));
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
                // 只在"中缝附近"切：正文里的宽空格（表格、公式）不该被当成栏缝。
                // 容差取 gap/2 + 12：实测两栏论文的**公式行**投票太少（整行只投一票），中缝估计会偏
                // 十来点，容差太小就漏切 —— 左右两栏的公式被拼成一行（"J(θ) := E … GTi |at| j , 1 ≤ t ≤ T"）。
                // 中缝已由整页投票确认时，允许较窄但确实跨过中缝的间隔（DPR 的正文栏距约 11pt）。
                boolean confirmedNarrowGap = gap >= 6 && prev.x1() <= gutter && w.x0() >= gutter;
                if ((gap >= MIN_GUTTER || confirmedNarrowGap) && Math.abs(mid - gutter) <= gap / 2 + 12) {
                    parts.add(cur);
                    cur = new ArrayList<>();
                    w = new Word(w.x0(), w.x1(), w.y(), w.size(), w.bold(), false, w.mono(), w.text(),
                            w.top(), w.bottom());
                }
            }
            cur.add(w);
        }
        parts.add(cur);
        return parts;
    }

    /**
     * 行内按**字号**切：论文的脚注与正文在同一条基线上时，PDFBox 会把它们并进同一行，
     * 不切就分不出"哪几个词是脚注"（实测某篇论文首页：
     * {@code ∗These authors contributed equally. real-world engineering resources, to evaluate task com-}，
     * 5/8pt 的脚注和 9pt 的正文被拼成了一行）。
     *
     * <p>{@code boundary} 是"脚注字号上限"（{@link #noteSplitBoundary}，&le;0 表示这一行不切）。
     * 切点要求：两边的字号差 &gt;0.4、空隙 &gt;1.6 倍小字号、双方都在门槛之下；
     * **而且切开后每一段至少两个词** —— 单字符的碎段一定是误切（实测把
     * {@code ∗These authors…} 的 "T" 单独切了出去，脚注正文变成 "hese authors…"）。
     */
    private List<List<Word>> splitBySize(List<Word> row, double boundary) {
        if (row.size() < 2 || boundary <= 0) {
            return List.of(row);
        }
        List<Word> sorted = new ArrayList<>(row);
        sorted.sort(Comparator.comparingDouble(Word::x0));
        if (sorted.stream().mapToDouble(Word::size).max().orElse(0) <= boundary) {
            return List.of(row);                     // 整行都在门槛之下：没有"正文/脚注"两种字号
        }
        int[] starts = splitPoints(sorted, boundary);
        if (starts.length == 0) {
            return List.of(row);
        }
        List<List<Word>> parts = new ArrayList<>();
        int from = 0;
        for (int s : starts) {
            parts.add(new ArrayList<>(sorted.subList(from, s)));
            from = s;
        }
        parts.add(new ArrayList<>(sorted.subList(from, sorted.size())));
        return parts;
    }

    /** 候选切点（下标）；相邻候选之间至少两个词，否则整组作废（宁可整行不切） */
    private static int[] splitPoints(List<Word> sorted, double boundary) {
        List<Integer> cuts = new ArrayList<>();
        for (int i = 1; i < sorted.size(); i++) {
            Word prev = sorted.get(i - 1);
            Word w = sorted.get(i);
            double gap = w.x0() - prev.x1();
            if (Math.abs(w.size() - prev.size()) > 0.4
                    && gap > 1.6 * Math.min(w.size(), prev.size())
                    && Math.max(w.size(), prev.size()) <= boundary
                    && (cuts.isEmpty() || i - cuts.get(cuts.size() - 1) >= 2)
                    && sorted.size() - i >= 2) {
                cuts.add(i);
            }
        }
        return cuts.stream().mapToInt(Integer::intValue).toArray();
    }

    /**
     * 脚注门槛只关心"行首文本 + 行的 y + 字号"，这里做一份轻量 Line 给它用
     * （真正的 Line 要等分流之后才成形，而切分必须发生在分流之前）。
     */
    private static Line toLine(List<Word> words) {
        List<Word> sorted = new ArrayList<>(words);
        sorted.sort(Comparator.comparingDouble(Word::x0));
        StringBuilder sb = new StringBuilder();
        for (Word w : sorted) {
            if (w.spaceBefore() && sb.length() > 0) {
                sb.append(' ');
            }
            sb.append(w.text());
        }
        return new Line(sorted.get(0).x0(), sorted.get(sorted.size() - 1).x1(), sorted.get(0).y(),
                sorted.stream().mapToDouble(Word::size).max().orElse(0), dominantSize(sorted), false, false, 0,
                sb.toString());
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
            double top = Double.MAX_VALUE;
            double bottom = -Double.MAX_VALUE;
            Word prevWord = null;
            for (Word w : sorted) {
                if (sb.length() > 0 && (w.spaceBefore() || wideGap(prevWord, w))) {
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
                top = Math.min(top, w.top());
                bottom = Math.max(bottom, w.bottom());
                prevWord = w;
            }
            String lineText = tidyInline(sb.toString());
            out.add(new Line(x0, x1, baseline(sorted), size, dominantSize(sorted), boldChars * 2 > total,
                    monoChars * 2 > total, total == 0 ? 0 : (double) monoChars / total, lineText, top, bottom));
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

    /**
     * 一行的**主导字号**：按字符数加权的众数（0.5pt 一档）。
     * <p>为什么不用"最大字号"：脚注行里常混着上标（{@code 1The code…}），最大字号会被上标或
     * 混进来的正文词抬到正文字号，于是"小字 = 脚注"这条判据失效（实测踩过）。
     */
    private static double dominantSize(List<Word> words) {
        Map<Integer, Integer> weight = new HashMap<>();
        for (Word w : words) {
            weight.merge((int) Math.round(w.size() * 2), Math.max(1, w.text().length()), Integer::sum);
        }
        return weight.entrySet().stream().max(Map.Entry.comparingByValue())
                .map(e -> e.getKey() / 2.0).orElse(0.0);
    }

    /**
     * 词与词之间"该有空格却丢了"的宽空隙。
     * <p>PDFBox 的 {@code writeWordSeparator} 只在**有空格字形**时回调；公式/带字距排版的文字没有，
     * 于是抽出来是 {@code R(q o ? KL o | q)} 这样挤在一起的式子。这里用几何空隙补一刀：
     * 空隙超过 0.2 倍字号（正常词距只有 0.05~0.3 倍字号，但**缺空格**时通常 ≥0.4 倍）才补，
     * 避免中文按字分词时被塞进空格（中文词间本来就没有空隙）。
     */
    private static boolean wideGap(Word prev, Word cur) {
        if (prev == null || cur == null) {
            return false;
        }
        double gap = cur.x0() - prev.x1();
        double size = Math.max(prev.size(), cur.size());
        return gap > 0.25 * size && gap > 0.4;
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
    private List<Block> headBlocks(List<Line> lines, double bodySize, int pageNo, double pageWidth,
                                   double pageHeight, double bodyBottom) {
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
            // 脚注：首页标题区里混着的**小字**（作者单位的上标、邮箱、脚注）——
            // 只有真正的脚注该单独成块（作者单位/邮箱要走 meta，标题区里它们更常见）。
            // 判据取"主导字号明显小一档 + 页面下半部"，见 columnPlaced 的同一判据。
            if (noteLine(ln, bodySize, pageHeight, bodyBottom)) {
                flushHead(out, title, authors);
                out.add(new Block("note", text, 0));
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
            out.add(new Block(prose || !isMetaFragment(ln, text, bodySize, pageWidth) ? "para" : "meta", text, 0));
        }
        flushHead(out, title, authors);
        return out;
    }

    /** 短碎片 + 居中：作者单位、邮箱、日期这类元信息（不是正文，但不该当脚注） */
    private static boolean isMetaFragment(Line ln, String text, double bodySize, double pageWidth) {
        double center = (ln.x0() + ln.x1()) / 2;
        boolean centered = center > pageWidth * 0.40 && center < pageWidth * 0.60;
        return (centered && ln.size() < bodySize * 1.02)
                || (text.length() < 60 && text.split("\\s+").length <= 6
                    && !SENTENCE_END.matcher(text).find());
    }

    /**
     * 这一行是不是**脚注**。
     *
     * <p>判据一：主导字号比正文小一档（&lt;0.9 倍）**且**落在页面最下面 18%。
     * 用主导字号而不是最大字号：脚注行里常混着上标（{@code 1The code…}），最大字号会被抬到正文字号，
     * 判据直接失效（实测 DPR 论文的 {@code 1The code and trained models…}）。
     *
     * <p>判据二：行首带脚注标记（{@code ∗ / † / 1These}）—— 有些排版的脚注与正文**同号**，
     * 光看字号分不出来（用户截图里的 {@code *These authors contributed equally.} 就是这样）。
     *
     * <p>位置这一刀必须同时用，而且不能松：正文的摘要/正文也可能比"全书正文字号"小一档
     * （实测某篇论文摘要区 8pt、正文 9pt），只按字号判会把摘要整段误判成脚注。
     */
    /**
     * 这一行是不是**脚注**。
     *
     * <p>两条判据，各自的"位置门槛"不同：
     * <ul>
     *   <li><b>小字</b>（见 {@link #smallLine}）且落在页面最下面 15% —— 论文脚注的主流形态；</li>
     *   <li><b>带脚注标记</b>（{@code ∗ / † / ‡}）且落在下半页 —— 有些排版的脚注与正文**同号**
     *       （用户截图里的 {@code *These authors contributed equally.} 就是这样），字号分不出来。</li>
     * </ul>
     *
     * <p>标记那条可以放到 0.82：标记本身是强证据，而 87% 处的脚注
     * （实测某篇论文首页的 {@code ∗These authors contributed equally.}）靠它才抓得住。
     */
    /**
     * 这一行是不是**脚注**。
     *
     * <p>先定"页脚区"：{@code bodyBottom} 是这一栏**最后一行正文字号**的 y —— 脚注必然在它下面。
     * 这一刀挡掉的是"正文中途的小字"：技术书里的代码示例/提示框字号比正文小一档，
     * 排版上却夹在正文中间（实测某本中文讲义：正文 16pt，代码 12pt 却排在正文**下方**之后）。
     *
     * <p>然后再分两条：
     * <ul>
     *   <li><b>小字</b>（见 {@link #smallLine}）且落在页面最下面 15%；</li>
     *   <li><b>带脚注标记</b>（{@code ∗ / † / ‡}）—— 有些排版的脚注与正文同号
     *       （用户截图里的 {@code *These authors contributed equally.} 就是这样），字号分不出来。</li>
     * </ul>
     */
    static boolean noteLine(Line ln, double bodySize, double pageHeight, double bodyBottom) {
        if (ln.text().length() >= 400 || ln.y() <= bodyBottom + 0.5) {
            return false;
        }
        double pct = ln.y() / pageHeight;
        // 代码行**永远不是脚注**：实测某本中文技术书的代码示例字号比正文小一档，
        // 而 looksLikeCode 认不出 Python 片段（"best_idx, best_score = None, -1e9"），
        // 于是整段代码被当脚注收走。等宽字体占比是这里最可靠的证据。
        if (monoish(ln) && !NOTE_MARKER.matcher(ln.text()).find()) {
            return false;
        }
        return (smallLine(ln, bodySize) && pct > 0.85)
                || (pct > 0.82 && (NOTE_SYMBOL.matcher(ln.text()).find()
                    || (noteSizedLine(ln, bodySize) && NOTE_MARKER.matcher(ln.text()).find())));
    }

    /** 这一栏里**最后一行正文字号**的 y（脚注必然在它下面；找不到就是页脚区从页底开始） */
    private static double bodyBottomOf(List<Line> lines, double bodySize, double pageHeight) {
        double bottom = 0;
        for (Line ln : lines) {
            if (ln.domSize() > bodySize * 0.94
                    && !(ln.y() > pageHeight * 0.82 && NOTE_SYMBOL.matcher(ln.text()).find())) {
                bottom = Math.max(bottom, ln.y());
            }
        }
        return bottom;
    }

    /**
     * 行内的脚注部分从哪个字号算起（&le;0 表示这一行整行都不是脚注）。
     *
     * <p>论文的脚注常与正文**同一条基线**（PDFBox 于是把它们并进同一行），实测某篇论文首页第 87% 行是
     * {@code ∗These authors contributed equally. real-world engineering resources, to evaluate task com-}：
     * 左边 5/8pt 的脚注、右边 9pt 的正文。切不切开，脚注就会并进正文段落（用户报的就是这个）。
     * 门槛取"小字判据的 0.9"（{@code 0.9 × 0.9 = 0.81} 倍正文），与 {@link #noteLine} 同一套口径。
     */
    private static double noteSplitBoundary(Line ln, double bodySize, double pageHeight) {
        if (ln.text().length() >= 400) {
            return 0;
        }
        double pct = ln.y() / pageHeight;
        boolean note = (smallLine(ln, bodySize) && pct > 0.85)
                || (pct > 0.82 && NOTE_SYMBOL.matcher(ln.text()).find());
        return note ? 0.9 * bodySize : 0;
    }

    /**
     * 去掉行首脚注标记："∗These authors contributed equally." → "These authors contributed equally."。
     * <p>{@code numberedOk} 表示这一行已经确认是"小字脚注"（见 {@link #noteLine}）：
     * 只有这时才允许把行首数字当标记（"1The code…"）；正文 "3D printing is amazing." 不能被吃掉 "3"。
     */
    static String stripNoteMarker(String text, boolean numberedOk) {
        if (numberedOk) {
            return stripNoteMarker(text);              // 小字脚注：编号标记（"1The code…"）也去掉
        }
        Matcher symbol = NOTE_SYMBOL.matcher(text.trim());
        return symbol.find() ? text.trim().substring(symbol.end()).trim() : text;
    }

    /** 去掉行首脚注标记："∗These authors contributed equally." → "These authors contributed equally." */
    static String stripNoteMarker(String text) {
        String t = text.trim();
        Matcher m = NOTE_MARKER.matcher(t);
        return m.find() ? t.substring(m.end()).trim() : t;
    }

    /** 这一行是不是"小字"（比正文字号小一档）：数字型脚注标记要靠它才敢认 */
    private static boolean monoish(Line ln) {
        return ln.monoRatio() >= 0.4;
    }

    /**
     * 这一行是不是"整行都小一档"。
     *
     * <p>门槛取 0.85 而不是 0.9：技术书/讲义里"正文 16pt + 提示框/代码 14pt"很常见，
     * 0.9 会把整页提示框都判成脚注（实测某本 300 页的中文讲义）。0.85 仍然接得住论文脚注
     * （实测 8pt 对 9pt、8pt 对 10pt 都在门槛之下）。
     *
     * <p>还要求最大字号也不超过正文：只按主导字号判会把"底部的正文行"拉进来
     * （行里混着上标引用时主导字号被拉到 8pt、最大字号仍是正文的 10pt）。
     */
    private static boolean smallLine(Line ln, double bodySize) {
        return ln.domSize() > 0 && ln.domSize() < bodySize * 0.85 && ln.size() <= bodySize * 1.02;
    }

    /** 带编号或已确认脚注的续行允许只小一档，仍排除正文大小与等宽代码。 */
    private static boolean noteSizedLine(Line ln, double bodySize) {
        return ln.domSize() > 0 && ln.domSize() < bodySize * 0.95
                && ln.size() <= bodySize * 1.02 && !monoish(ln);
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
        double bodyBottom = column == null ? 0 : bodyBottomOf(column.lines(), bodySize, pageHeight);
        List<Placed> out = new ArrayList<>();
        if (column == null) {
            return out;
        }
        Para para = new Para();
        double pitch = linePitch(column.lines());
        // 代码/数据段：连续的"代码样"行攒成**一个** code 块（不这样，一页 JSON 会变成二十个段落）
        StringBuilder code = new StringBuilder();
        int codeLines = 0;
        double codeY = 0;
        // 表格行：连续 3 行以上"像表格"的行攒成一个 table 块（行距判定同代码段）
        StringBuilder rows = new StringBuilder();
        int rowLines = 0;
        double rowY = 0;
        // 脚注：连续的小字/带标记行攒成**一个** note 块 —— 实测某篇论文首页的脚注折成两行
        // （"1The code and trained models have been released at" + "https://github.com/…"），
        // 一行一块会读成两条互不相干的注。
        NoteBuffer note = new NoteBuffer();
        boolean[] formulaLines = formulaLines(column.lines());
        Line prev = null;
        int figureAt = 0;

        for (int lineAt = 0; lineAt < column.lines().size(); lineAt++) {
            Line ln = column.lines().get(lineAt);
            String text = ln.text();
            if (text.isEmpty()) {
                continue;
            }
            // 这一行之前的图：先落地（图也要按 y 排进阅读顺序）
            while (figureAt < figures.size() && figures.get(figureAt).y0() < ln.y()) {
                Figure f = figures.get(figureAt++);
                flushAll(out, para, note);
                flushCode(out, code, codeLines, codeY);
                codeLines = 0;
                flushRows(out, rows, rowLines, rowY);
                rowLines = 0;
                out.add(new Placed(figureBlock(pageNo, f), f.y0()));
            }
            // 脚注先于标题、列表和代码："* text" 与 "1 The ..." 也会命中那些形态规则。
            boolean markerOnly = text.matches("\\d{1,2}");
            Line next = lineAt + 1 < column.lines().size() ? column.lines().get(lineAt + 1) : null;
            boolean separateMarker = markerOnly && next != null && ln.y() > bodyBottom + 0.5
                    && ln.y() > pageHeight * 0.82 && noteSizedLine(ln, bodySize) && noteSizedLine(next, bodySize)
                    && next.y() >= ln.y() - 0.5 && next.y() - ln.y() <= next.size() * 1.5
                    && Math.abs(next.x0() - ln.x0()) <= next.size() * 2;
            boolean noteContinuation = !markerOnly && noteSizedLine(ln, bodySize) && note.continues(ln);
            if (((!markerOnly && noteLine(ln, bodySize, pageHeight, bodyBottom))
                    || separateMarker || noteContinuation) && !formulaLines[lineAt]) {
                flushPlaced(out, para, para.y);
                flushCode(out, code, codeLines, codeY);
                codeLines = 0;
                flushRows(out, rows, rowLines, rowY);
                rowLines = 0;
                if (!note.continues(ln)) {
                    flushNote(out, note);
                }
                note.add(ln);
                prev = ln;
                continue;
            }
            flushNote(out, note);
            // 在正文拼接之前单独落公式，保留周围说明文字的段落边界。
            if (formulaLines[lineAt]) {
                flushPlaced(out, para, para.y);
                flushCode(out, code, codeLines, codeY);
                codeLines = 0;
                flushRows(out, rows, rowLines, rowY);
                rowLines = 0;
                Para equation = new Para();
                equation.add(ln, text, false);
                out.add(new Placed(new Block("formula", text, 0, null, pageNo, equation.rect()), ln.y()));
                prev = ln;
                continue;
            }
            boolean rowishLine = rowish(text, ln, column);
            // 表格行结束：只要遇到一行"不像表格"的，就先把攒着的表格落地（这样下面所有分支都不用管它）
            if (rowLines > 0 && !rowishLine) {
                flushRows(out, rows, rowLines, rowY);
                rowLines = 0;
            }
            int level = headingLevel(text, ln, bodySize);
            if (level > 0) {
                flushAll(out, para, note);
                flushCode(out, code, codeLines, codeY);
                codeLines = 0;
                out.add(new Placed(new Block("heading", text, level), ln.y()));
                prev = ln;
                continue;
            }
            // 代码/数据行：等宽字体是字体级证据；不是等宽也能靠形态认（键值对、括号、注解、命令）
            if (ln.mono() || looksLikeCode(text)) {
                flushAll(out, para, note);
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
                flushAll(out, para, note);
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
                flushAll(out, para, note);
                para.add(ln, BULLET.matcher(text).replaceFirst(""), true);
                prev = ln;
                continue;
            }
            // 页脚/脚注：主导字号**明显**小一档（<0.9 倍正文）+ 页面下半部，或行首带脚注标记（∗/†/1）。
            // **不管段落是否正在拼**都要切一刀 —— 论文底部的脚注常被当成上一段正文的续行。
            // 类型单独给 `note`（而不是 meta）：脚注**左对齐**、作者单位**居中**，两者排版不是一回事。
            if (!para.isEmpty() && prev != null && paragraphBreak(column, prev, ln, bodySize, pitch)) {
                flushAll(out, para, note);
            }
            if (!para.isEmpty()) {
                joinLine(para, prev, ln, text);
            } else {
                para.add(ln, text, false);
            }
            prev = ln;
        }
        flushAll(out, para, note);
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
     * 数学符号（公式行的形态证据）。
     * <p>PDF 里没有"公式对象"：公式是散落在坐标上的一堆字形（上下标字号还不一样），
     * 抽出来必然是 {@code (θ|q) R(q o … KL …)} 这种粘连的碎片。这里做不了 LaTeX 化，
     * 能做的只有两件事：把公式行单独成块（不与正文段落混排）、按原样保留换行。
     */
    private static final Pattern MATH_GLYPH = Pattern.compile("[=+−±×÷∑∫√≤≥≈≠∈∀∃∂∇^_{}|<>]|[\\u0370-\\u03FF]");

    /**
     * **真公式才有的**符号：希腊字母、数学运算符、比较符、集合/微积分符号。
     * <p>花括号不算数学证据；等号等运算符还须结合变量词与密度判断，模板另行排除。
     */
    private static final Pattern REAL_MATH =
            Pattern.compile("[=+−±×÷∑∫√≤≥≈≠∈∀∃∂∇∞∅∼∝→←↔≪≫]|[\\u0370-\\u03FF]");

    /** 模板/HTML 片段（{@code {{x}}}、{@code <END_EMAIL>}）：它们不是公式 */
    private static final Pattern TEMPLATEISH = Pattern.compile("\\{\\{|\\}\\}|<[A-Za-z_/][A-Za-z0-9_]*>");
    private static final Pattern LATIN_WORD = Pattern.compile("[A-Za-z]+");
    private static final List<String> MATH_WORDS = List.of(
            "argmax", "argmin", "sin", "cos", "tan", "log", "exp", "lim", "max", "min", "det", "mod", "sum", "sim");

    /**
     * 这一行像不像公式：短、不以句末标点结尾，而且**数学符号够密**。
     * <p>紧凑的变量等式单独识别；其他行要求符号密度至少 13%，避免把带等号的说明句截走。
     *
     * <p>两条排除：**至少一个真数学符号**（{@link #REAL_MATH}，光靠花括号凑密度的模板不算），
     * 以及**不是模板/HTML 片段**（{@link #TEMPLATEISH}）。
     */
    static boolean mathish(String text) {
        String t = text.trim();
        if (t.isEmpty() || t.length() > 120 || t.indexOf('\n') >= 0) {
            return false;
        }
        if (t.split("\\s+").length > 14) {
            return false;
        }
        if (TEMPLATEISH.matcher(t).find() || !REAL_MATH.matcher(t).find()) {
            return false;
        }
        int math = (int) MATH_GLYPH.matcher(t).results().count();
        boolean relation = t.matches(".*[=≤≥≈≠].*");
        boolean compact = t.length() <= 55 && relation
                && LATIN_WORD.matcher(t).results()
                    .allMatch(m -> m.group().length() <= 2 || MATH_WORDS.contains(m.group().toLowerCase(Locale.ROOT)))
                && t.codePoints().noneMatch(c -> c >= 0x2E80);
        // 短的 ASCII 等式（x+y=z、E=mc^2）也需要保留；有正文词的句子仍走保守判据。
        if (compact && math >= 1) {
            return true;
        }
        return !SENTENCE_END.matcher(t).find() && math >= 3 && math * 100 >= t.length() * 13;
    }

    /** 上标脚注号不是整行的基线，按文字数量取中位数，避免续行间距被放大。 */
    private static double baseline(List<Word> words) {
        List<Word> sorted = new ArrayList<>(words);
        sorted.sort(Comparator.comparingDouble(Word::y));
        int half = (sorted.stream().mapToInt(w -> Math.max(1, w.text().length())).sum() + 1) / 2;
        int accumulated = 0;
        for (Word w : sorted) {
            accumulated += Math.max(1, w.text().length());
            if (accumulated >= half) {
                return w.y();
            }
        }
        return sorted.get(0).y();
    }

    /** 标记公式主行及紧邻的独立上下标碎行；按位置约束，避免把说明文字收进公式。 */
    private static boolean[] formulaLines(List<Line> lines) {
        boolean[] result = new boolean[lines.size()];
        for (int i = 0; i < lines.size(); i++) {
            Line main = lines.get(i);
            if (main.mono() || !mathish(main.text())) {
                continue;
            }
            result[i] = true;
            Line anchor = main;
            for (int direction : new int[]{-1, 1}) {
                for (int step = 1; step <= 3; step++) {
                    int j = i + step * direction;
                    if (j < 0 || j >= lines.size() || !scriptFragment(lines.get(j), anchor)) {
                        break; // 不能越过正文/表格，把远处的单字符收成一张公式图。
                    }
                    result[j] = true;
                    Line fragment = lines.get(j);
                    anchor = new Line(Math.min(anchor.x0(), fragment.x0()), Math.max(anchor.x1(), fragment.x1()),
                            main.y(), main.size(), main.domSize(), false, false, 0, main.text());
                }
            }
        }
        return result;
    }

    private static boolean scriptFragment(Line fragment, Line main) {
        String t = fragment.text();
        boolean notation = REAL_MATH.matcher(t).find()
                || LATIN_WORD.matcher(t).results().anyMatch(m -> MATH_WORDS.contains(m.group().toLowerCase(Locale.ROOT)))
                || t.matches(".*\\(\\d{1,3}\\)$");
        return !fragment.mono() && fragment.size() <= main.size() * 1.2
                && (fragment.size() <= main.size() * 0.8 || notation)
                && t.length() <= 60 && t.split("\\s+").length <= 12
                && !SENTENCE_END.matcher(t).find() && !TEMPLATEISH.matcher(t).find()
                && !CODE_LINE.matcher(t).find() && t.codePoints().noneMatch(c -> c >= 0x2E80)
                && LATIN_WORD.matcher(t).results().allMatch(m -> m.group().length() <= 2
                    || MATH_WORDS.contains(m.group().toLowerCase(Locale.ROOT)))
                && Math.abs(fragment.y() - main.y()) <= main.size() * 1.8
                && fragment.x0() <= main.x1() + main.size()
                && fragment.x1() >= main.x0() - main.size();
    }

    /**
     * "像公式"的段落 → `formula` 块（与 code/table 一样按行保留、不进翻译链路）。
     *
     * <p>**连续 ≥2 行**的公式行会合成一块（多行公式、公式组），单独一行的公式行也**单独成块** ——
     * 它带着自己的几何，界面就能把原 PDF 那块渲染出来贴上去（公式的排版与上下标只有原图是准的，
     * 见 docs/pdf-layout-design.md §5.3）。单行**不成块**的只有一种情况：它本来就在正文段落里
     * （{@code isMath} 只认整段都像公式的段落，所以不会把正文里带公式的半句话割出来）。
     */
    private static List<Block> mergeFormulaRuns(List<Placed> placed, int pageNo) {
        List<Block> out = new ArrayList<>();
        int i = 0;
        while (i < placed.size()) {
            if (!isMath(placed.get(i).block())) {
                out.add(placed.get(i).block());
                i++;
                continue;
            }
            int j = i;
            double[] runRect = placed.get(i).block().rect();
            while (j < placed.size() && isMath(placed.get(j).block())
                    && (j == i || nearbyFormula(runRect, placed.get(j).block().rect())
                        || connectedByFollowingFormula(placed, j, runRect))) {
                runRect = unionRect(runRect, placed.get(j).block().rect());
                j++;
            }
            StringBuilder sb = new StringBuilder();
            double[] union = null;
            for (int k = i; k < j; k++) {
                if (sb.length() > 0) {
                    sb.append('\n');
                }
                Block b = placed.get(k).block();
                sb.append(b.text().trim());
                union = unionRect(union, b.rect());
            }
            out.add(new Block("formula", sb.toString(), 0, null, pageNo, union));
            i = j;
        }
        return out;
    }

    /** 已识别的公式块，或整段文本都像公式的段落。 */
    private static boolean isMath(Block b) {
        return "formula".equals(b.type()) || ("para".equals(b.type()) && mathish(b.text()));
    }

    /** 两个上标可能横向分离，但紧接的公式主行同时连接它们，不能提前截断其中一个。 */
    private static boolean connectedByFollowingFormula(List<Placed> placed, int at, double[] runRect) {
        if (at + 1 >= placed.size() || !isMath(placed.get(at + 1).block())) {
            return false;
        }
        double[] main = placed.get(at + 1).block().rect();
        return nearbyFormula(runRect, main) && nearbyFormula(placed.get(at).block().rect(), main);
    }

    /** 只合并版面上相邻且水平重叠的公式；隔很远的两式不能裁成一张包含正文的大图。 */
    private static boolean nearbyFormula(double[] x, double[] y) {
        if (x == null || y == null) {
            return false;
        }
        double height = Math.max(x[3] - x[1], y[3] - y[1]);
        return y[1] >= x[1] - height && y[1] - x[3] <= height * 1.2
                && Math.min(x[2], y[2]) > Math.max(x[0], y[0]);
    }

    /** 两个矩形（x0,y0,x1,y1）的并集；任一为空就返回另一个 */
    private static double[] unionRect(double[] a, double[] b) {
        if (b == null) {
            return a;
        }
        if (a == null) {
            return new double[]{b[0], b[1], b[2], b[3]};
        }
        return new double[]{Math.min(a[0], b[0]), Math.min(a[1], b[1]),
                Math.max(a[2], b[2]), Math.max(a[3], b[3])};
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
        // 含数学符号的短行是公式/符号说明，不是表格单元格（公式另有 formula 块，见 mergeFormulaRuns）
        if (MATH_GLYPH.matcher(t).find()) {
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
    private void joinLine(Para para, Line prev, Line cur, String next) {
        StringBuilder sb = para.text;
        if (prev != null && sb.length() > 1 && sb.charAt(sb.length() - 1) == '-'
                && Character.isLetter(sb.charAt(sb.length() - 2)) && isWordChar(next)) {
            sb.setLength(sb.length() - 1);
            para.extend(cur, next);
            return;
        }
        if (prev != null && needsSpace(prev.text(), next)) {
            sb.append(' ');
        }
        para.extend(cur, next);
    }

    /** 用标记、行间距和悬挂缩进区分脚注续行与另一条脚注。 */
    private static final class NoteBuffer {
        private final StringBuilder text = new StringBuilder();
        private Line first;
        private Line tail;

        boolean continues(Line ln) {
            return tail != null && !NOTE_MARKER.matcher(ln.text()).find()
                    && ln.y() - tail.y() >= -0.5
                    && ln.y() - tail.y() <= Math.max(ln.size(), tail.size()) * 2
                    && Math.abs(ln.x0() - first.x0()) <= Math.max(ln.size(), tail.size()) * 2;
        }

        void add(Line ln) {
            if (text.length() == 0) {
                first = ln;
            } else if (text.length() > 1 && text.charAt(text.length() - 1) == '-'
                    && Character.isLetter(text.charAt(text.length() - 2)) && isWordChar(ln.text())) {
                text.setLength(text.length() - 1);
            } else if (needsSpace(text.toString(), ln.text())) {
                text.append(' ');
            }
            // 保留标记：读者需要区分脚注编号，并与原文中的上标对应。
            text.append(ln.text());
            tail = ln;
        }

        void clear() {
            text.setLength(0);
            first = null;
            tail = null;
        }
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

    /** 段落落地（不含脚注累加器：表格/行内清洗这些地方用得到） */
    private static void flushPlaced(List<Placed> out, Para para, double y) {
        String text = para.text.toString().trim();
        double[] rect = para.rect();
        boolean bullet = para.bullet;
        para.clear();
        if (!text.isEmpty()) {
            out.add(new Placed(new Block(bullet ? "bullet" : "para", tidyInline(text), 0, null, null, rect), y));
        }
    }

    /**
     * 段落落地，顺带清空脚注累加器 —— 段落与脚注是互相打断的两种流，
     * 每个分支都要把攒着的脚注先落地（否则最后一条脚注会被后一行带跑）。
     */
    private static void flushAll(List<Placed> out, Para para, NoteBuffer note) {
        flushNote(out, note);
        flushPlaced(out, para, para.y);
    }

    /** 攒着的脚注落地（清空累加器） */
    private static void flushNote(List<Placed> out, NoteBuffer note) {
        if (note.text.length() > 0) {
            out.add(new Placed(new Block("note", tidyInline(note.text.toString()), 0), note.first.y()));
            note.clear();
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
                if ("formula".equals(b.type()) || "note".equals(b.type())) {
                    continue;
                }
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
                if ("formula".equals(b.type()) || "note".equals(b.type())) {
                    keep.add(b);
                    continue;
                }
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
                if (placeholder(u)) {
                    String fixed = recoverGlyph(p);          // 数学字体：用字形名换回真符号
                    if (fixed != null) {
                        u = fixed;
                    }
                }
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
            double x0 = Double.MAX_VALUE, x1 = -Double.MAX_VALUE;
            double top = Double.MAX_VALUE, bottom = -Double.MAX_VALUE, size = 0;
            for (TextPosition p : positions) {
                double s = Math.max(1, fontSize(p));
                x0 = Math.min(x0, p.getXDirAdj());
                x1 = Math.max(x1, p.getXDirAdj() + p.getWidthDirAdj());
                top = Math.min(top, p.getYDirAdj() - Math.max(p.getHeightDir(), s * 0.9));
                bottom = Math.max(bottom, p.getYDirAdj() + s * 0.3);
                size = Math.max(size, s);
            }
            cur.add(new Word(x0, x1, first.getYDirAdj(), size, isBold(first), spacePending,
                    isMono(first), word, top, bottom));
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
