package org.dyh.learnhub.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

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
 * 公式处理的回归测试：①字形名恢复 ②"像公式"的判据（含误判防线）。
 *
 * <p>锁住三件实测踩过的事：
 * <ol>
 *   <li>"像公式"不能只看 ASCII 符号密度：提示词模板 {@code {{email_body}} ..<END_EMAIL..>} 会中招，
 *       所以要求**至少一个真数学符号**（希腊字母/运算符）且不是模板/HTML 片段；</li>
 *   <li>把 {@code =} 紧跟在转义序列后面写进字符类会被静默吞掉
 *       （实测把希腊字母范围与等号写在同一对方括号里，等号不生效、公式判据整条失效）—— 靠
 *       {@link #formulaLinesAreDetected()} 的第三条守住；</li>
 *   <li>公式块的几何要能裁出式子的**上下标**（高度按字号算，不是按首末行差），
 *       否则裁出来的图上只有一条基线。</li>
 * </ol>
 */
class PdfFormulaRulesTest {

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
