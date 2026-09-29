package org.dyh.learnhub.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * 排版还原的测试。
 *
 * <p>单元测试部分锁住"乱码修复"这条容易回归的逻辑；
 * 真实 PDF 部分对 {@code uploads/} 里的**每一份**样本跑一遍**版面无关**的断言，
 * 并把第一份样本的首页前几块打出来供人工核对。
 *
 * <p>为什么断言必须与版面无关于：最初这组用例写的是"第一份样本必须是两栏、必须有 title/authors"
 * —— 那是照着某一篇 AAAI 论文的版面写的。后来库里多了一篇单栏论文（按文件名排在前面），
 * 提取结果完全正确却把用例跑红了。**拿单个样本的版面特征当断言，测的是样本不是代码**。
 * 现在改成对任意论文都成立的性质：
 * <ul>
 *   <li>能出结果（status=ok）、每页有块、块不为空、乱码已修；</li>
 *   <li>正文成段（平均段落够长，碎块占比很低）——这条能抓住"行没拼回段"的回归；</li>
 *   <li>页码这类页眉页脚噪声被清掉；</li>
 *   <li>**有条件断言**：有 title 块时，它不能是页边水印（实测 arXiv 竖排水印会抢标题位）。</li>
 * </ul>
 * 没有样本文件时自动跳过，不会让 CI 变红。
 */
class PdfLayoutExtractorTest {

    private final PdfLayoutExtractor extractor = new PdfLayoutExtractor();

    @Test
    @DisplayName("双重编码乱码要修回来：– ’ ∗ 这类符号最常被 PDF 抽成 â€“")
    void repairMojibake() {
        assertEquals("a – b", DocumentTextService.repairMojibake("a â\u0080\u0093 b"));
        assertEquals("it’s", DocumentTextService.repairMojibake("itâ\u0080\u0099s"));
        assertEquals("∗", DocumentTextService.repairMojibake("â\u0088\u0097"));
        // 真正的 Latin-1 文本（解不出 UTF-8）必须原样保留
        assertEquals("café", DocumentTextService.repairMojibake("café"));
        assertEquals("naïve", DocumentTextService.repairMojibake("naïve"));
        // 中文不受影响
        assertEquals("中文摘要", DocumentTextService.repairMojibake("中文摘要"));
    }

    @Test
    @DisplayName("每一份样本都要能还原：结果完整、无乱码、页码与页边水印不进正文")
    void everySampleLooksSane() throws Exception {
        List<Path> pdfs = samplePdfs();
        assumeTrue(!pdfs.isEmpty(), "uploads/ 下没有 PDF 样本，跳过");
        boolean printed = false;
        for (Path pdf : pdfs) {
            PdfLayoutExtractor.Layout layout = extractor.extract(pdf);
            String name = pdf.getFileName().toString();
            assertEquals("ok", layout.status(), name + " 抽取状态异常: " + layout.error());
            assertFalse(layout.pages().isEmpty(), name + " 没有解析出任何页");
            assertTrue(layout.chars() > 500, name + " 还原出的正文太少: " + layout.chars());

            List<PdfLayoutExtractor.Block> blocks = layout.pages().stream()
                    .flatMap(p -> p.blocks().stream()).toList();
            assertTrue(blocks.size() >= 20, name + " 块太少（可能整页被当成一块）: " + blocks.size());
            for (PdfLayoutExtractor.Block b : blocks) {
                if ("figure".equals(b.type())) {
                    continue;                    // 图块本来就没有文字：它靠 src 去原 PDF 上按位置裁图
                }
                assertFalse(b.text() == null || b.text().isBlank(), name + " 有空块（type=" + b.type() + "）");
                assertFalse(b.text().contains("â"), name + " 有没修掉的乱码: " + b.text());
            }
            // 纯数字的块要少：它们是页码噪声的信号。**但这些论文里确实有大量数字是表格单元格/列表序号**
            // （实测：结果表里 1~60 的数字、以及 "• 3" 这类列表标记），所以只能按比例断言，不能要求为 0
            long numeric = blocks.stream().filter(b -> b.text().trim().matches("\\d{1,4}")).count();
            assertTrue(numeric * 10 < blocks.size(),
                    name + " 纯数字块占比过高（" + numeric + "/" + blocks.size() + "），像页码没清掉");

            // 有条件断言：有 title 块时，它必须像标题而不是页边 arXiv 水印（实测踩过）
            layout.pages().get(0).blocks().stream()
                    .filter(b -> "title".equals(b.type()))
                    .forEach(b -> assertFalse(
                            b.text().toLowerCase().contains("arxiv.org") || b.text().startsWith("arXiv:"),
                            name + " 标题是页边水印: " + b.text()));

            // 标题不能以数字为主：图表的刻度/图例长得和标题一模一样（短、加粗、字号更大），
            // 实测某篇论文的柱状图数字被认成了 [heading1] "60 68.8 72.3"、"39 RAG-Seq 40"。
            // 这条与版面无关于 —— 真标题必然以文字为主（编号小节如 "2.5.1 数据建模" 里数字只占少数）。
            // 判据取"至少一个字母 + 数字不超过字母的两倍"：够挡住纯刻度行，又不会误伤
            // 中文书里的短标题（新样本里出现过 "3 线+" 这样的三级标题，只有 1 个汉字）。
            blocks.stream().filter(b -> "heading".equals(b.type())).forEach(b -> {
                int letters = (int) b.text().codePoints().filter(Character::isLetter).count();
                int digits = (int) b.text().codePoints().filter(Character::isDigit).count();
                assertTrue(letters >= 1 && digits <= letters * 2,
                        name + " 标题块以数字为主（像图表刻度）: " + b.text());
            });
            // 表格块必须是多行的：单行文本被塞进 table 只会让它看起来像表格，其实不是
            blocks.stream().filter(b -> "table".equals(b.type()))
                    .forEach(b -> assertTrue(b.text().contains("\n"),
                            name + " 表格块只有一行: " + b.text()));

            // 图块：src 必须是"页-序号"（前端拿它在原 PDF 上按位置裁图），而且裁剪框要落在页面内。
            // 这条同样与版面无关于：样本里有位图就必须满足，样本里没有位图也不会误报。
            for (PdfLayoutExtractor.Block b : blocks) {
                if (!"figure".equals(b.type())) {
                    continue;
                }
                assertTrue(b.text().isEmpty() && b.src() != null && b.src().matches("\\d+-\\d+"),
                        name + " 图块格式不对: text=" + b.text() + " src=" + b.src());
                int page = Integer.parseInt(b.src().split("-")[0]);
                int idx = Integer.parseInt(b.src().split("-")[1]);
                int[] rect = extractor.figureRect(pdf, page, idx, 72);
                assertTrue(rect != null, name + " 图块取不到裁剪框: " + b.src());
                assertTrue(rect[0] >= 0 && rect[1] >= 0 && rect[2] >= 24 && rect[3] >= 24,
                        name + " 裁剪框不合理: " + java.util.Arrays.toString(rect));
            }

            if (!printed) {
                printSample(layout.pages().get(0), 8);
                printed = true;
            }
        }
    }

    @Test
    @DisplayName("正文要成段：平均段落够长，碎块只是少数")
    void paragraphsAreFormed() throws Exception {
        List<Path> pdfs = samplePdfs();
        assumeTrue(!pdfs.isEmpty(), "uploads/ 下没有 PDF 样本，跳过");
        for (Path pdf : pdfs) {
            PdfLayoutExtractor.Layout layout = extractor.extract(pdf);
            String name = pdf.getFileName().toString();
            List<String> paras = layout.pages().stream()
                    .flatMap(p -> p.blocks().stream())
                    .filter(b -> "para".equals(b.type()))
                    .map(PdfLayoutExtractor.Block::text)
                    .toList();
            assertTrue(paras.size() > 10, name + " 段落太少，说明没成段: " + paras.size());

            // ★ 成段的正向证据：必须存在"明显是一整段"的长块。
            //   判据取 300 字而不是凭感觉的数字：**一行装不下 300 字**（本组样本最宽的一行也就 90 来个
            //   拉丁字符 / 50 来个汉字），所以出现 ≥300 字的块只可能是多行真的拼回了一段。
            //   （早期这里写的是 600：那是照着某篇长段落的论文定的，后来 96 页的中文讲义最长段只有 360 字，
            //   提取完全正确却把用例跑红了 —— 又犯了"拿单个样本当断言"的老毛病。）
            int longest = paras.stream().mapToInt(String::length).max().orElse(0);
            assertTrue(longest >= 300, name + " 最长的正文块只有 " + longest + " 字：行没有拼回段");
            double avg = paras.stream().mapToInt(String::length).average().orElse(0);
            long longOnes = paras.stream().filter(t -> t.length() >= 300).count();
            long shortOnes = paras.stream().filter(t -> t.length() < 40).count();
            // 每个样本打一行统计：阈值该定多少要看真实分布，不要凭感觉（这行就是证据）
            System.out.printf("  %-40s pages=%-3d cols=%-2d paras=%-4d avg=%-5.0f long300=%-4d short40=%-4d%n",
                    name, layout.pages().size(), layout.pages().get(0).columns(),
                    paras.size(), avg, longOnes, shortOnes);

            assertTrue(longOnes >= 3, name + " 300 字以上的长段只有 " + longOnes + " 个：成段不充分");
            // 平均值只能当"根本没有拼起来"的兜底：技术书/讲义类 PDF 天生短块多（列表、代码、图注、表格），
            // 实测某本 96 页的讲义平均只有 62 字，但它的长段照样有几千字 —— 用平均值当主判据会误伤
            assertTrue(avg > 40, name + " 平均段落只有 " + (int) avg + " 字，行根本没拼起来");

            // 短块本身是合理的（表格单元格、图注、列表项、逐行给的代码）——实测那本 96 页的中文讲义里
            // 短块接近一半，因为它的 API 参数说明就是一行一条。所以这里只拦"失控"级别的切碎，
            // 真正判断"有没有成段"靠上面那两条正向证据（最长块 / 长段个数）。
            assertTrue(shortOnes * 100 < paras.size() * 60,
                    name + " 短块占比过高（" + shortOnes + "/" + paras.size() + "），正文被切碎了");
        }
    }

    // ------------------------------------------------------------------

    private List<Path> samplePdfs() throws Exception {
        Path uploads = Path.of(System.getProperty("user.dir"), "uploads");
        if (!Files.isDirectory(uploads)) {
            return List.of();
        }
        try (Stream<Path> s = Files.list(uploads)) {
            // 全部样本都跑：只测「第一份」会让用例跟着文件名排序漂移（已经踩过一次）
            return s.filter(p -> p.getFileName().toString().toLowerCase().endsWith(".pdf"))
                    .sorted().toList();
        }
    }

    private static void printSample(PdfLayoutExtractor.PageLayout page, int limit) {
        List<String> lines = new ArrayList<>();
        for (PdfLayoutExtractor.Block b : page.blocks()) {
            if (lines.size() >= limit) {
                break;
            }
            String t = b.text();
            lines.add("[" + b.type() + (b.level() > 0 ? b.level() : "") + "] "
                    + (t.length() > 110 ? t.substring(0, 110) + "…" : t));
        }
        System.out.println("—— 第 " + page.page() + " 页（" + page.columns() + " 栏）还原样本 ——");
        lines.forEach(System.out::println);
    }
}
