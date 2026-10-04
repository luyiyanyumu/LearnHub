package org.dyh.learnhub.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 文件名入库前的规范化（`origin_name` 是 VARCHAR(255)）。
 *
 * <p>两条规则都由实测暴露，不是"顺手加的"：
 * <ul>
 *   <li><b>控制字符</b>：`\n` 在表格里被 CSS 折叠成空格 → 界面显示的名字与真实名字不一致，
 *       用户按界面看到的名字去文件系统里找会找不到。</li>
 *   <li><b>超长</b>：上传路径此前不限长度，超过 255 会让 INSERT 直接失败
 *       （严格模式报 Data too long → 500），用户只看到"上传失败"。</li>
 * </ul>
 */
class FileNameNormalizeTest {

    @Test
    @DisplayName("控制字符替换成空格：换行/制表符不再混进名字")
    void stripsControlCharacters() {
        assertEquals("Line one Line two.pdf", FileStorageService.normalizeOriginName("Line one\nLine two.pdf"));
        assertEquals("a b c.pdf", FileStorageService.normalizeOriginName("a\tb\rc.pdf"));
        // 连续空白压成一个，且首尾去掉
        assertEquals("a b.pdf", FileStorageService.normalizeOriginName("  a   b.pdf  "));
        // NUL 与 DEL 也算控制字符
        assertEquals("x y.pdf", FileStorageService.normalizeOriginName("x\u0000y\u007f.pdf"));
    }

    @Test
    @DisplayName("空名字兜底，不写空串进库")
    void fallsBackForBlank() {
        assertEquals("unnamed", FileStorageService.normalizeOriginName(null));
        assertEquals("unnamed", FileStorageService.normalizeOriginName(""));
        assertEquals("unnamed", FileStorageService.normalizeOriginName("   \n\t "));
    }

    @Test
    @DisplayName("超长截到 255，且**保留扩展名**（扩展名决定用哪个解析器抽正文）")
    void truncatesToColumnLimitKeepingExtension() {
        String longBase = "很长的文件名".repeat(60);          // 360 字
        String name = longBase + ".pdf";
        String out = FileStorageService.normalizeOriginName(name);

        assertEquals(255, out.length(), "必须正好截到列上限，否则 INSERT 会 Data too long");
        assertTrue(out.endsWith(".pdf"), "截断把扩展名弄丢了，抽正文就选错解析器：" + out);
    }

    @Test
    @DisplayName("没有扩展名的超长名：直接截到 255")
    void truncatesWithoutExtension() {
        String out = FileStorageService.normalizeOriginName("x".repeat(400));
        assertEquals(255, out.length());
    }

    @Test
    @DisplayName("正好 255 与更短的名字一字不改（避免把正常名字改坏）")
    void leavesNormalNamesAlone() {
        String exactly = "a".repeat(251) + ".pdf";            // 251 + 4 = 255
        assertEquals(exactly, FileStorageService.normalizeOriginName(exactly));
        assertEquals("Q3 Board Deck — FINAL (revised) v12 [approved by legal].pdf",
                FileStorageService.normalizeOriginName("Q3 Board Deck — FINAL (revised) v12 [approved by legal].pdf"));
        assertEquals("🦊 狐狸笔记.pdf", FileStorageService.normalizeOriginName("🦊 狐狸笔记.pdf"));
    }
}
