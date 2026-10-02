package org.dyh.learnhub.service;

import lombok.extern.slf4j.Slf4j;
import org.apache.pdfbox.pdmodel.font.PDFont;
import org.apache.pdfbox.pdmodel.font.PDSimpleFont;
import org.apache.pdfbox.text.TextPosition;

/**
 * 字形名工具：把"字体缺 ToUnicode 映射"的字符救回来。
 *
 * <h3>为什么需要它</h3>
 * 数学字体（TeX 的 CMMI/CMSY 等）常常没有 ToUnicode CMap，PDFBox 于是把符号抽成 {@code ?}：
 * 实测某篇论文的公式 {@code (θ|q)} 抽出来是 {@code (??|q)}。但**字形名是好的** ——
 * PDFBox 自己就在警告里写着 {@code No Unicode mapping for lessmuch (28) in font CMSY10}。
 *
 * <h3>刻意保守</h3>
 * 只处理"简单字体"（{@link PDSimpleFont}：Type1/TrueType，字形名可靠）；
 * CID 子集字体的字形名是 {@code g12} 这种内部编号，认不出来就返回 {@code null}（**不猜**），
 * 上层保留原来的 {@code ?} —— 猜错的符号比 "?" 更误导人。
 */
@Slf4j
final class GlyphNames {

    private GlyphNames() {
    }

    /** 这个 TextPosition 对应的字符码（简单字体上一个位置就是一个码），拿不到返回 -1 */
    static int codeOf(TextPosition p) {
        try {
            int[] codes = p.getCharacterCodes();
            return codes == null || codes.length == 0 ? -1 : codes[0];
        } catch (Exception e) {
            return -1;
        }
    }

    /** 字形名（拿不到返回 null）：PDFont → 编码表 → 码位对应的名字 */
    static String nameOf(PDFont font, int code) {
        if (font == null || code < 0 || !(font instanceof PDSimpleFont simple)) {
            return null;
        }
        try {
            return simple.getEncoding() == null ? null : simple.getEncoding().getName(code);
        } catch (Exception e) {
            return null;
        }
    }
}
