package org.dyh.learnhub.service;

import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.apache.poi.xwpf.usermodel.XWPFRun;
import org.apache.poi.xwpf.usermodel.XWPFTable;
import org.apache.poi.xwpf.usermodel.XWPFTableCell;
import org.apache.poi.xwpf.usermodel.XWPFTableRow;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTRPr;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTFonts;
import org.springframework.stereotype.Service;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * Markdown → Word（.docx）。给智能体的「生成 Word 文档」工具用。
 *
 * <h3>为什么自己解析 Markdown，而不引第三方库</h3>
 * 输入是**模型产出的 Markdown**，实际只用到很窄的一个子集（标题/列表/表格/代码块/加粗）；
 * 而本工程已经在用 POI 解析上传的 docx/xlsx/pptx，复用它不必再加依赖。
 * flexmark + POI 的组合要多一个依赖，且样式仍要自己调 —— 收益不划算。
 * 解析策略是"**宁可样式朴素，不能少字**"：不认识的语法一律按普通文本输出。
 *
 * <h3>为什么必须显式写 w:eastAsia（踩过的坑）</h3>
 * POI 的 {@code run.setFontFamily("微软雅黑")} 只写 {@code w:ascii}/{@code w:hAnsi}，
 * 而**中文走的是 {@code w:eastAsia}** —— 不设它时，中文会退化成"打开文档那台机器上的默认字体"，
 * 字号与字形都不受控（同一份文件在不同电脑上长得不一样）。所以每个 run 都补一段
 * {@code <w:rFonts w:eastAsia="…"/>}。代码块同理：等宽字体没有中文字形，中文注释要与代码同一字体时
 * 必须显式指定，否则会变成比例字体、与代码对不齐。
 */
@Service
public class WordDocService {

    /** 正文/标题字体：中英文都拿得出手，且 Office 环境普遍存在 */
    private static final String BODY_FONT = "微软雅黑";
    /** 等宽字体：中文注释用雅黑兜底 */
    private static final String MONO_FONT = "Consolas";

    /** 生成结果：文件名（已带 .docx）+ 字节 */
    public record Doc(String fileName, byte[] bytes) {
    }

    /** 一段文字的行内样式（用普通静态类而不是 record：这里要的是可读的派生方法，不是数据载体） */
    private static final class Fmt {
        final boolean bold;
        final boolean italic;
        final boolean mono;
        final int size;

        private Fmt(boolean bold, boolean italic, boolean mono, int size) {
            this.bold = bold;
            this.italic = italic;
            this.mono = mono;
            this.size = size;
        }

        static Fmt body(int size) {
            return new Fmt(false, false, false, size);
        }

        Fmt bold() {
            return new Fmt(true, italic, mono, size);
        }

        Fmt italic() {
            return new Fmt(bold, true, mono, size);
        }

        Fmt mono() {
            return new Fmt(bold, italic, true, size);
        }
    }

    /**
     * 把 Markdown 渲染成 docx。
     *
     * <p>支持：{@code #~####}（映射为四级标题）、段落、无序/有序列表（含两级缩进）、引用、
     * 围栏代码块、表格（首行为表头）、行内 {@code **加粗**} / {@code *斜体*} / {@code `代码`}。
     *
     * <p><b>标题只出现一次</b>：若正文首行的一级标题与 {@code title} 相同（模型几乎总会这么写），
     * 就把它当作文档标题、**不再另写标题段**。否则会出现"标题 + 同名一级标题"两行 ——
     * 打开文档看到两个标题，抽取出的正文里也会重复一遍（笔记那边踩过同一个坑）。
     *
     * @param title    文档标题（空则不写标题段）
     * @param markdown 正文 Markdown
     */
    public Doc render(String title, String markdown) throws IOException {
        String body = markdown == null ? "" : markdown.replace("\r\n", "\n").replace('\r', '\n');
        String fileName = safeFileName(title, body);
        String docTitle = title == null ? "" : title.trim();

        // 首行若与标题重复，剥掉它（标题由文档标题承担）
        boolean titleUsedAsHeading = false;
        if (!docTitle.isEmpty()) {
            int nl = body.indexOf('\n');
            String firstLine = (nl < 0 ? body : body.substring(0, nl)).trim();
            if (firstLine.startsWith("# ")) {
                String text = firstLine.substring(2).trim();
                if (text.equals(docTitle) || text.replaceAll("\\s+", "").equals(docTitle.replaceAll("\\s+", ""))) {
                    body = nl < 0 ? "" : body.substring(nl + 1);
                    titleUsedAsHeading = true;
                }
            }
        }

        try (XWPFDocument doc = new XWPFDocument();
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            // 标题段：无论标题来自参数还是正文首行，都按同一个样式写一次（见方法注释）
            if (!docTitle.isEmpty()) {
                XWPFParagraph p = doc.createParagraph();
                para(p, docTitle, Fmt.body(20).bold());
            }

            List<String> lines = List.of(body.split("\n", -1));
            int i = 0;
            while (i < lines.size()) {
                String raw = lines.get(i);
                String trimmed = raw.trim();

                // 围栏代码块：整段原样保留（缩进与空行都要留）
                if (trimmed.startsWith("```")) {
                    i++;
                    StringBuilder code = new StringBuilder();
                    while (i < lines.size() && !lines.get(i).trim().startsWith("```")) {
                        code.append(lines.get(i)).append('\n');
                        i++;
                    }
                    i++; // 跳过收尾 ```
                    appendCodeBlock(doc, code.toString());
                    continue;
                }

                // 表格：连续以 | 开头的行算一个表
                if (isTableRow(trimmed)) {
                    List<String> block = new ArrayList<>();
                    while (i < lines.size() && isTableRow(lines.get(i).trim())) {
                        block.add(lines.get(i).trim());
                        i++;
                    }
                    appendTable(doc, block);
                    continue;
                }

                if (trimmed.isEmpty()) {
                    i++;
                    continue;
                }

                int level = headingLevel(trimmed);
                if (level > 0) {
                    int size = switch (level) {
                        case 1 -> 18;
                        case 2 -> 16;
                        case 3 -> 14;
                        default -> 13;
                    };
                    XWPFParagraph p = doc.createParagraph();
                    para(p, trimmed.substring(level).trim(), Fmt.body(size).bold());
                    i++;
                    continue;
                }

                if (trimmed.matches("^[-*+]\\s+.*")) {
                    appendBullet(doc, trimmed.replaceFirst("^[-*+]\\s+", ""), indentLevel(raw));
                    i++;
                    continue;
                }
                if (trimmed.matches("^\\d+[.)]\\s+.*")) {
                    String num = trimmed.replaceFirst("^([0-9]+)[.)]\\s+.*$", "$1");
                    String text = trimmed.replaceFirst("^\\d+[.)]\\s+", "");
                    appendBullet(doc, num + ". " + text, indentLevel(raw));
                    i++;
                    continue;
                }
                if (trimmed.startsWith(">")) {
                    XWPFParagraph p = doc.createParagraph();
                    p.setIndentationLeft(480);
                    para(p, trimmed.replaceFirst("^>\\s?", ""), Fmt.body(11).italic());
                    i++;
                    continue;
                }

                // 普通段落：合并紧随其后的非空行（Markdown 里同一段常被折行）
                StringBuilder paraText = new StringBuilder(trimmed);
                i++;
                while (i < lines.size()) {
                    String next = lines.get(i).trim();
                    if (next.isEmpty() || next.startsWith("#") || next.startsWith("```")
                            || next.startsWith(">") || next.matches("^[-*+]\\s+.*")
                            || next.matches("^\\d+[.)]\\s+.*") || isTableRow(next)) {
                        break;
                    }
                    paraText.append(next);
                    i++;
                }
                XWPFParagraph p = doc.createParagraph();
                para(p, paraText.toString(), Fmt.body(11));
            }

            doc.write(out);
            return new Doc(fileName, out.toByteArray());
        }
    }

    // ------------------------------------------------------------------ 块

    private void appendCodeBlock(XWPFDocument doc, String code) {
        if (code.isEmpty()) {
            return;
        }
        XWPFParagraph p = doc.createParagraph();
        p.setIndentationLeft(240);
        String[] cs = code.split("\n", -1);
        for (int k = 0; k < cs.length; k++) {
            if (k > 0) {
                XWPFRun br = p.createRun();
                br.addBreak();
                font(br, MONO_FONT, 10);
            }
            XWPFRun r = p.createRun();
            r.setText(cs[k].isEmpty() ? " " : cs[k]);
            font(r, MONO_FONT, 10);
        }
    }

    private void appendBullet(XWPFDocument doc, String text, int level) {
        XWPFParagraph p = doc.createParagraph();
        p.setIndentationLeft(360 + level * 360);
        p.setIndentationHanging(200);
        XWPFRun bullet = p.createRun();
        bullet.setText("• ");
        font(bullet, BODY_FONT, 11);
        inline(p, text, Fmt.body(11));
    }

    private void appendTable(XWPFDocument doc, List<String> rows) {
        List<List<String>> cells = new ArrayList<>();
        for (String row : rows) {
            String r = row;
            if (r.startsWith("|")) {
                r = r.substring(1);
            }
            if (r.endsWith("|")) {
                r = r.substring(0, r.length() - 1);
            }
            List<String> cols = new ArrayList<>();
            for (String c : r.split("\\|", -1)) {
                cols.add(c.trim());
            }
            cells.add(cols);
        }
        // 第二行是 |---|---| 分隔行 → 丢掉
        if (cells.size() > 1 && cells.get(1).stream().allMatch(c -> c.matches("^:?-{2,}:?$"))) {
            cells.remove(1);
        }
        if (cells.isEmpty()) {
            return;
        }
        int colCount = Math.max(1, cells.stream().mapToInt(List::size).max().orElse(1));
        XWPFTable table = doc.createTable(cells.size(), colCount);
        for (int r = 0; r < cells.size(); r++) {
            XWPFTableRow row = table.getRow(r);
            List<String> cols = cells.get(r);
            for (int c = 0; c < colCount; c++) {
                XWPFTableCell cell = row.getCell(c) != null ? row.getCell(c) : row.createCell();
                XWPFParagraph p = cell.getParagraphs().isEmpty()
                        ? cell.addParagraph() : cell.getParagraphs().get(0);
                inline(p, c < cols.size() ? cols.get(c) : "", r == 0 ? Fmt.body(10).bold() : Fmt.body(10));
            }
        }
        doc.createParagraph(); // 表后留空段，避免与下一段贴住
    }

    // ------------------------------------------------------------------ 行内

    /** 单段纯文本（无行内标记） */
    private void para(XWPFParagraph p, String text, Fmt fmt) {
        inline(p, text, fmt);
    }

    /**
     * 行内富文本：{@code **加粗**}、{@code *斜体*}、{@code `代码`}。
     *
     * <p>用一次扫描而不是正则替换：正则会在这里反复踩"** 与 * 的优先级"以及中文标点的坑；
     * 扫描一遍只需一个循环，且**认不出的标记一律当普通文本**，不会吞字。
     * 每段文字都新建 run（而不是在同一个 run 上继续写）—— 否则加粗会"泄漏"到后面的普通文字上。
     */
    private void inline(XWPFParagraph p, String text, Fmt base) {
        StringBuilder buf = new StringBuilder();
        int i = 0;
        while (i < text.length()) {
            // **加粗**
            if (text.startsWith("**", i)) {
                int end = text.indexOf("**", i + 2);
                if (end > i + 2) {
                    emit(p, buf, base);
                    emit(p, text.substring(i + 2, end), base.bold());
                    i = end + 2;
                    continue;
                }
            }
            // `代码`
            if (text.charAt(i) == '`') {
                int end = text.indexOf('`', i + 1);
                if (end > i + 1) {
                    emit(p, buf, base);
                    emit(p, text.substring(i + 1, end), base.mono());
                    i = end + 1;
                    continue;
                }
            }
            // *斜体*（避开 ** 已被上面处理的情况）
            if (text.charAt(i) == '*') {
                int end = text.indexOf('*', i + 1);
                if (end > i + 1) {
                    emit(p, buf, base);
                    emit(p, text.substring(i + 1, end), base.italic());
                    i = end + 1;
                    continue;
                }
            }
            buf.append(text.charAt(i));
            i++;
        }
        emit(p, buf, base);
    }

    /** 把缓冲区里的普通文字写成一个 run */
    private void emit(XWPFParagraph p, StringBuilder buf, Fmt fmt) {
        if (buf.isEmpty()) {
            return;
        }
        emit(p, buf.toString(), fmt);
        buf.setLength(0);
    }

    /** 一段应用了样式的文字 → 一个 run */
    private void emit(XWPFParagraph p, String text, Fmt fmt) {
        if (text.isEmpty()) {
            return;
        }
        XWPFRun r = p.createRun();
        r.setText(text);
        r.setBold(fmt.bold);
        r.setItalic(fmt.italic);
        font(r, fmt.mono ? MONO_FONT : BODY_FONT, fmt.size);
    }

    /**
     * 同时设置西文与**东亚**字体。只设 {@code w:ascii} 时中文会退化（见类注释）。
     */
    private void font(XWPFRun run, String family, int size) {
        run.setFontFamily(family);
        run.setFontSize(size);
        CTRPr rPr = run.getCTR().isSetRPr() ? run.getCTR().getRPr() : run.getCTR().addNewRPr();
        CTFonts fonts = rPr.sizeOfRFontsArray() > 0 ? rPr.getRFontsArray(0) : rPr.addNewRFonts();
        fonts.setAscii(family);
        fonts.setHAnsi(family);
        fonts.setEastAsia(family);
        fonts.setCs(family);
    }

    // ------------------------------------------------------------------ 小工具

    private static int headingLevel(String line) {
        int n = 0;
        while (n < line.length() && line.charAt(n) == '#') {
            n++;
        }
        if (n == 0 || n > 6 || n >= line.length() || line.charAt(n) != ' ') {
            return 0;
        }
        return n;
    }

    private static boolean isTableRow(String trimmed) {
        return trimmed.startsWith("|") && trimmed.indexOf('|', 1) > 0;
    }

    private static int indentLevel(String line) {
        int spaces = 0;
        while (spaces < line.length() && line.charAt(spaces) == ' ') {
            spaces++;
        }
        return Math.min(3, spaces / 2);
    }

    /** 文件名：标题优先，其次正文首个非空行，最后兜底；统一做安全字符处理 */
    static String safeFileName(String title, String body) {
        String base = title == null ? "" : title.trim();
        if (base.isEmpty()) {
            for (String line : body.split("\n")) {
                String t = line.replaceFirst("^#+\\s*", "").trim();
                if (!t.isEmpty()) {
                    base = t;
                    break;
                }
            }
        }
        if (base.isEmpty()) {
            base = "文档";
        }
        base = base.replaceAll("[\\\\/:*?\"<>|\\r\\n\\t]", " ").replaceAll("\\s+", " ").trim();
        if (base.length() > 60) {
            base = base.substring(0, 60);
        }
        return base + ".docx";
    }
}
