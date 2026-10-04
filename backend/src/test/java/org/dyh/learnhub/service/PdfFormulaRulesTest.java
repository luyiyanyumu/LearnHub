package org.dyh.learnhub.service;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * 公式处理的回归测试：字形名恢复、公式判据，以及完整区域与正文分离。
 *
 * <p>锁住实测踩过的事：
 * <ol>
 *   <li>"像公式"不能只看 ASCII 符号密度：提示词模板 {@code {{email_body}} ..<END_EMAIL..>} 会中招，
 *       所以要求**至少一个真数学符号**（希腊字母/运算符）且不是模板/HTML 片段；</li>
 *   <li>把 {@code =} 紧跟在转义序列后面写进字符类会被静默吞掉
 *       （实测把希腊字母范围与等号写在同一对方括号里，等号不生效、公式判据整条失效）—— 靠
 *       {@link #formulaLinesAreDetected()} 的第三条守住；</li>
 *   <li>公式块的几何要能裁出式子的**上下标**（高度按字号算，不是按首末行差），
 *       否则裁出来的图上只有一条基线；</li>
 *   <li>撇号、函数赋值、右侧编号和大括号不能让公式漏判或裁碎，也不能混进前后的正文。</li>
 * </ol>
 */
class PdfFormulaRulesTest {

    @TempDir Path temp;
    private static final PDType1Font ROMAN = new PDType1Font(Standard14Fonts.FontName.TIMES_ROMAN);
    private static final PDType1Font ITALIC = new PDType1Font(Standard14Fonts.FontName.TIMES_ITALIC);
    private static final PDType1Font SYMBOL = new PDType1Font(Standard14Fonts.FontName.SYMBOL);

    @Test
    @DisplayName("真公式行认得出：希腊字母/求和号 + 足够的符号密度")
    void formulaLinesAreDetected() {
        assertTrue(PdfLayoutExtractor.mathish("i,j i) − βDKL(πθ∥πref). (10)"));
        assertTrue(PdfLayoutExtractor.mathish("τi}G i=1∼πθold i=1 i t=1 i j=1"));
        // 等号必须算数（字符类里跟在转义后面的等号曾经被静默吞掉）
        assertTrue(PdfLayoutExtractor.mathish("θ⋆ = argmaxJ (θ), (3)"));
    }

    @Test
    @DisplayName("不是公式的别认：模板占位符、HTML 片段、带公式的正文句子")
    void nonFormulasAreRejected() {
        assertFalse(PdfLayoutExtractor.mathish("{{email_body}} ..<END_EMAIL..>"));
        assertFalse(PdfLayoutExtractor.mathish("题目：{{question}} 标准答案：{{gold_answer}}"));
        // 正文里带等号的一句话：符号密度不够（0.03 级别）
        assertFalse(PdfLayoutExtractor.mathish("the exact match score on SQuAD v1.1 drops from 80% to 40%"));
    }

    @Test
    @DisplayName("公式块的几何要算出式子的高度（不是一条基线）")
    void formulaRectCoversTheWholeFormula() throws Exception {
        List<Path> pdfs = samplePdfs();
        assumeTrue(!pdfs.isEmpty(), "uploads/ 下没有 PDF 样本，跳过");
        PdfLayoutExtractor ex = new PdfLayoutExtractor();
        int checked = 0;
        int withRect = 0;
        for (Path pdf : pdfs) {
            for (PdfLayoutExtractor.PageLayout p : ex.extract(pdf).pages()) {
                for (PdfLayoutExtractor.Block b : p.blocks()) {
                    if (!"formula".equals(b.type())) {
                        continue;
                    }
                    checked++;
                    if (b.rect() == null) {
                        // 标题区（首页顶部）里的公式没有几何：那是 headBlocks 的产物，
                        // 界面会退回文本渲染，不算错
                        continue;
                    }
                    withRect++;
                    assertNotNull(b.page(), pdf.getFileName() + " 的公式块没有页码");
                    double w = b.rect()[2] - b.rect()[0];
                    double h = b.rect()[3] - b.rect()[1];
                    // 再小的公式也有一行字高；纯基线（h≈0）说明裁出来是一片空白
                    // i=0 等短式可以小于 20pt；空白裁剪另由字形边界和像素回归测试检查。
                    assertTrue(h >= 8 && w >= 8,
                            pdf.getFileName() + " 公式块几何过小: " + w + "×" + h + " " + b.text());
                }
            }
        }
        System.out.println("  公式块 " + checked + " 块，其中带几何 " + withRect + " 块");
    }

    @Test
    @DisplayName("带撇号的多行赋值和轨迹等式保持完整，编号留在公式内，正文不吸入公式")
    void primedAssignmentAndTrajectoryStaySeparateFromProse() throws Exception {
        Path pdf = temp.resolve("primed-equation-regression.pdf");
        try (PDDocument doc = new PDDocument()) {
            PDPage cover = new PDPage(PDRectangle.LETTER);
            doc.addPage(cover);
            try (PDPageContentStream out = new PDPageContentStream(doc, cover)) {
                body(out);
            }
            PDPage page = new PDPage(PDRectangle.LETTER);
            doc.addPage(page);
            try (PDPageContentStream out = new PDPageContentStream(doc, page)) {
                body(out);
                draw(out, 54, 216, 10, "The controlled environment and recorded context are restored:", ROMAN);
                primed(out, 180, 234, "s", "k");
                draw(out, 197, 234, 10, "←", SYMBOL);
                draw(out, 212, 234, 10, "s", ITALIC);
                draw(out, 218, 237, 6, "k", ITALIC);
                draw(out, 223, 234, 10, ",", ROMAN);
                primed(out, 180, 249, "c", "k");
                draw(out, 197, 249, 10, "←", SYMBOL);
                draw(out, 212, 249, 10, "Inject(c", ROMAN);
                draw(out, 244, 252, 6, "k", ITALIC);
                draw(out, 250, 249, 10, ", M).", ROMAN);
                draw(out, 550, 242, 10, "(5)", ROMAN);
                String resumes = "The agent subsequently resumes execution from (";
                draw(out, 54, 270, 10, resumes, ROMAN);
                float inlineX = 54 + ROMAN.getStringWidth(resumes) / 1000 * 10;
                primed(out, inlineX, 270, "c", "k");
                draw(out, inlineX + 12, 270, 10, ",", ROMAN);
                primed(out, inlineX + 18, 270, "s", "k");
                draw(out, inlineX + 30, 270, 10, "),", ROMAN);
                draw(out, 54, 284, 10, "producing a new continuation of the trajectory:", ROMAN);
                draw(out, 180, 308, 10, "τ", SYMBOL);
                draw(out, 186, 304, 6, "'", ROMAN);
                draw(out, 195, 308, 10, "= ((", ROMAN);
                primed(out, 218, 308, "c", "k");
                draw(out, 230, 308, 10, ",", ROMAN);
                primed(out, 236, 308, "s", "k");
                draw(out, 248, 308, 10, "),", ROMAN);
                primed(out, 261, 308, "u", "k");
                draw(out, 273, 308, 10, ",", ROMAN);
                primed(out, 279, 308, "o", "k+1");
                draw(out, 300, 308, 10, ", ...).", ROMAN);
                draw(out, 550, 308, 10, "(6)", ROMAN);
                draw(out, 54, 342, 10, "Figure one illustrates this recoverable execution process.", ROMAN);
            }
            doc.save(pdf.toFile());
        }
        List<PdfLayoutExtractor.Block> blocks = new PdfLayoutExtractor().extract(pdf).pages().stream()
                .filter(p -> p.page() == 2).findFirst().orElseThrow().blocks();
        PdfLayoutExtractor.Block fifth = numberedFormula(blocks, 5);
        PdfLayoutExtractor.Block sixth = numberedFormula(blocks, 6);
        assertTrue(fifth.text().contains("Inject"), fifth.text());
        assertTrue(fifth.text().contains("s") && fifth.text().contains("c"), fifth.text());
        assertTrue(sixth.text().contains("k+1") && sixth.text().contains("u") && sixth.text().contains("o"), sixth.text());
        assertTrue(primeCount(fifth.text()) >= 2, "Both assignment primes must remain in the formula");
        assertTrue(primeCount(sixth.text()) >= 5, "Every trajectory variable must retain its prime");
        assertTrue(fifth.rect()[1] <= 225 && fifth.rect()[3] >= 253.8,
                "The multiline assignment must include both primes and subscripts");
        assertTrue(sixth.rect()[1] <= 298.6 && sixth.rect()[3] >= 312.8,
                "The trajectory must include the raised primes and lowered k+1");
        assertTrue(fifth.rect()[3] < sixth.rect()[1], "The prose between equations must not be cropped as math");
        assertNoEquationInProse(blocks, "Inject", "k+1");
        assertTrue(blocks.stream().filter(b -> "para".equals(b.type()))
                .anyMatch(b -> b.text().contains("producing a new continuation of the trajectory:")));
        String middle = blocks.stream().filter(b -> "para".equals(b.type()))
                .map(PdfLayoutExtractor.Block::text).filter(t -> t.contains("The agent subsequently resumes"))
                .findFirst().orElseThrow();
        assertTrue(middle.startsWith("The agent subsequently resumes"), middle);
        assertTrue(middle.contains("c") && middle.contains("s") && middle.contains("k"), middle);
        assertTrue(blocks.stream().filter(b -> "formula".equals(b.type()))
                .noneMatch(b -> b.text().contains("The agent") || b.text().contains("Figure one")));
    }

    @Test
    @DisplayName("AgentRewind 第3页式5/6完整落块：不再漏判赋值或把大括号塞进上一段正文")
    void agentRewindFifthAndSixthEquationsKeepTheirCompleteRegions() throws Exception {
        Path pdf = Path.of(System.getProperty("user.dir"), "uploads", "6d4ee4e580614268a7bfdbe32bb608aa.pdf");
        assumeTrue(Files.isRegularFile(pdf), "AgentRewind PDF sample is unavailable");
        List<PdfLayoutExtractor.Block> blocks = new PdfLayoutExtractor().extract(pdf).pages().stream()
                .filter(p -> p.page() == 3).findFirst().orElseThrow().blocks();
        PdfLayoutExtractor.Block fifth = numberedFormula(blocks, 5);
        PdfLayoutExtractor.Block sixth = numberedFormula(blocks, 6);
        assertEquals(3, fifth.page());
        assertEquals(3, sixth.page());
        assertTrue(fifth.text().contains("Inject") && fifth.text().contains("←"), fifth.text());
        assertTrue(sixth.text().contains("τ") && sixth.text().contains("k+1"), sixth.text());
        assertTrue(primeCount(fifth.text()) >= 2, fifth.text());
        assertTrue(primeCount(sixth.text()) >= 5, sixth.text());
        assertTrue(fifth.rect()[0] <= 130.808 && fifth.rect()[2] >= 292.501,
                "The assignment crop must include the left variables and the right equation number");
        assertTrue(fifth.rect()[1] <= 459.026 && fifth.rect()[3] >= 488.061,
                "The assignment crop must include both rows, primes, and subscripts");
        assertTrue(sixth.rect()[0] <= 113.375 && sixth.rect()[2] >= 292.503,
                "The trajectory crop must include tau and the right equation number");
        assertTrue(sixth.rect()[1] <= 520.276 && sixth.rect()[3] >= 534.051,
                "The trajectory crop must include all five primes and lowered k+1");
        assertTrue(fifth.rect()[3] < 492.7 && sixth.rect()[1] > 515.3,
                "Crops must exclude the actual text lines between equations");
        assertNoEquationInProse(blocks, "Inject", "k+1");
        String middle = blocks.stream().filter(b -> "para".equals(b.type()))
                .map(PdfLayoutExtractor.Block::text)
                .filter(t -> t.contains("The agent subsequently resumes"))
                .findFirst().orElseThrow();
        assertTrue(middle.startsWith("The agent subsequently resumes"), middle);
        assertTrue(middle.contains("producing a new continuation of the trajectory:"), middle);
        assertFalse(middle.contains("con(tinuation") || middle.contains("trajecto)ry"), middle);
        assertFalse(middle.contains("(5)") || middle.contains("(6)"), middle);
        assertTrue(blocks.stream().filter(b -> "formula".equals(b.type()))
                .noneMatch(b -> b.text().contains("The agent") || b.text().contains("Figure 1(b)")));
    }

    private static PdfLayoutExtractor.Block numberedFormula(List<PdfLayoutExtractor.Block> blocks, int number) {
        List<PdfLayoutExtractor.Block> matching = blocks.stream()
                .filter(b -> "formula".equals(b.type()) && b.text().contains("(" + number + ")")).toList();
        assertEquals(1, matching.size(), "Equation " + number + " must be one complete formula block: " + blocks);
        PdfLayoutExtractor.Block block = matching.get(0);
        assertNotNull(block.rect());
        assertNotNull(block.page());
        assertFalse(block.text().contains("(" + (number == 5 ? 6 : 5) + ")"),
                "Equations separated by prose must remain separate");
        return block;
    }

    private static void assertNoEquationInProse(List<PdfLayoutExtractor.Block> blocks, String... fragments) {
        for (PdfLayoutExtractor.Block block : blocks) {
            if ("formula".equals(block.type())) continue;
            for (String fragment : fragments) {
                assertFalse(block.text().contains(fragment), "Equation content leaked into " + block.type() + ": " + block.text());
            }
            assertFalse(block.text().contains("(5)") || block.text().contains("(6)"), block.text());
        }
    }

    private static long primeCount(String text) {
        return text.codePoints().filter(c -> c == '\'' || c == '′').count();
    }

    private static void body(PDPageContentStream out) throws Exception {
        for (int i = 0; i < 8; i++) {
            draw(out, 54, 72 + i * 14, 10,
                    "Ordinary prose establishes the body font and the left margin of this page.", ROMAN);
        }
    }

    private static void primed(PDPageContentStream out, float x, float baseline, String variable, String subscript)
            throws Exception {
        draw(out, x, baseline, 10, variable, ITALIC);
        draw(out, x + 6, baseline - 4, 6, "'", ROMAN);
        draw(out, x + 6, baseline + 3, 6, subscript, ITALIC);
    }

    private static void draw(PDPageContentStream out, float x, float baselineFromTop, float size,
                             String text, PDType1Font font) throws Exception {
        out.beginText();
        out.setFont(font, size);
        out.newLineAtOffset(x, PDRectangle.LETTER.getHeight() - baselineFromTop);
        out.showText(text);
        out.endText();
    }

    private List<Path> samplePdfs() throws Exception {
        Path uploads = Path.of(System.getProperty("user.dir"), "uploads");
        if (!Files.isDirectory(uploads)) {
            return List.of();
        }
        try (Stream<Path> s = Files.list(uploads)) {
            return s.filter(p -> p.getFileName().toString().toLowerCase().endsWith(".pdf")).sorted().toList();
        }
    }
}
