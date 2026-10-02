package org.dyh.learnhub.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 脚注判定的回归测试（纯函数，不依赖 uploads 里的样本）。
 *
 * <p>这一组锁住三个真实踩过的坑：
 * <ol>
 *   <li><b>脚注标记只能吃标记本身</b>：早先的正则写成 {@code ^\s*(标记)\s*\S.*}，
 *       被 {@code replaceFirst("")} 连正文一起删掉 —— {@code ∗These authors contributed equally.}
 *       去标记后成了空串，脚注再也落不了块（用户报"注释被当成正文"，这是那一串连锁反应的终点）。</li>
 *   <li><b>判据不能只按字号</b>：脚注与正文同号时靠标记认；正文里以数字开头的句子
 *       （"3D printing is amazing."）不能被吃掉首字。</li>
 *   <li><b>代码不是脚注</b>：技术书里代码示例的字号比正文小一档，
 *       实测整段 Python（"best_idx, best_score = None, -1e9"）被当成脚注收走。</li>
 * </ol>
 */
class PdfFootnoteRulesTest {

    private static final double BODY = 9.0;
    private static final double PAGE = 792.0;

    /** y 在页面 88% 处的行（脚注区）；{@code mono} 是等宽字符占比 */
    private static PdfLayoutExtractor.Line line(String text, double size, double dom, double mono) {
        return new PdfLayoutExtractor.Line(66, 300, PAGE * 0.88, size, dom, false, mono > 0.5, mono, text);
    }

    private static PdfLayoutExtractor.Line line(String text, double size, double dom) {
        return line(text, size, dom, 0);
    }

    @Test
    @DisplayName("脚注标记只去掉标记：∗These authors contributed equally. 的正文必须保留")
    void markerStripsOnlyTheMarker() {
        assertEquals("These authors contributed equally.",
                PdfLayoutExtractor.stripNoteMarker("∗These authors contributed equally."));
        assertEquals("Corresponding author.",
                PdfLayoutExtractor.stripNoteMarker("†Corresponding author."));
        // 编号型脚注（DPR 论文的 "1The code…"）：标记与正文粘连，吃掉编号即可
        assertEquals("The code and trained models have been released at",
                PdfLayoutExtractor.stripNoteMarker("1The code and trained models have been released at"));
        // 没有标记的普通文本原样返回
        assertEquals("hello world", PdfLayoutExtractor.stripNoteMarker("hello world"));
    }

    @Test
    @DisplayName("与正文同号的脚注靠标记认：∗/† 开头 + 页面下半部 → note")
    void symbolMarkedFootnoteIsDetected() {
        assertTrue(PdfLayoutExtractor.noteLine(
                line("∗These authors contributed equally.", BODY, BODY), BODY, PAGE, 0));
        assertTrue(PdfLayoutExtractor.noteLine(
                line("†Corresponding author.", BODY, BODY), BODY, PAGE, 0));
        // 同样两行若在页面上半部，不是脚注（标题区的作者单位就是这种位置）
        PdfLayoutExtractor.Line top = new PdfLayoutExtractor.Line(66, 300, PAGE * 0.2, BODY, BODY, false, false, 0,
                "∗These authors contributed equally.");
        assertFalse(PdfLayoutExtractor.noteLine(top, BODY, PAGE, 0));
    }

    @Test
    @DisplayName("小字 + 页面最下面 → note；正文最后几行不能被误判")
    void smallTailLineIsNote() {
        // 7.5pt < 0.85×9pt，且最大字号也没超过正文（实测论文脚注是 8pt 对 9pt / 8pt 对 10pt）
        assertTrue(PdfLayoutExtractor.noteLine(
                line("https://github.com/facebookresearch/DPR.", 7.5, 7.5), BODY, PAGE, 0));
        // 主导字号虽小，但行里混着正文大小的词（底部的正文行 + 上标引用）：不算脚注
        assertFalse(PdfLayoutExtractor.noteLine(
                line("with natural paragraphs in our preliminary trials", 10, 8), BODY, PAGE, 0));
        // 小字但在页面上半部（图注、公式里的下标）：不算脚注
        PdfLayoutExtractor.Line middle = new PdfLayoutExtractor.Line(66, 300, PAGE * 0.4, 8, 8, false, false, 0,
                "a small caption");
        assertFalse(PdfLayoutExtractor.noteLine(middle, BODY, PAGE, 0));
    }

    @Test
    @DisplayName("正文里以数字开头的句子不能被当成编号脚注")
    void numberedBodySentenceIsNotFootnote() {
        // "3D printing is amazing." 是正文：字号与正文相同 → 数字不算脚注标记
        assertFalse(PdfLayoutExtractor.noteLine(
                line("3D printing is amazing and widely used.", BODY, BODY), BODY, PAGE, 0));
        // 反向：同样以数字开头，但整行是小字（"2For instance…" 这种粘连脚注）→ 认
        assertTrue(PdfLayoutExtractor.noteLine(
                line("2For instance, the exact match score drops", 7.5, 7.5), BODY, PAGE, 0));
    }

    @Test
    @DisplayName("等宽代码行不是脚注（技术书的代码示例字号也小）")
    void monospaceCodeIsNotFootnote() {
        assertFalse(PdfLayoutExtractor.noteLine(
                line("best_idx, best_score = None, -1e9", 8, 8, 0.9), BODY, PAGE, 0));
        assertFalse(PdfLayoutExtractor.noteLine(
                line("while len(selected) < top_k and candidates:", 8, 8, 0.9), BODY, PAGE, 0));
        // 但脚注里的 URL 续行（带编号标记）仍是脚注
        assertTrue(PdfLayoutExtractor.noteLine(
                line("3https://github.com/huggingface/transformers", 7.5, 7.5, 0.9), BODY, PAGE, 0));
    }

    @Test
    @DisplayName("正文中途的小字不是脚注：必须在最后一行正文之下")
    void smallTextInsideBodyIsNotFootnote() {
        // 实测某本中文讲义：正文 16pt，代码示例字号小一档，却夹在正文中途
        PdfLayoutExtractor.Line code = new PdfLayoutExtractor.Line(66, 400, PAGE * 0.93, 10, 10, false, false, 0,
                "print(\"hello\")");
        assertTrue(PdfLayoutExtractor.noteLine(code, 16, PAGE, 0));                // 没有正文在下方时像脚注
        assertFalse(PdfLayoutExtractor.noteLine(code, 16, PAGE, PAGE * 0.95));     // 下方还有正文 → 不是脚注
    }
}
