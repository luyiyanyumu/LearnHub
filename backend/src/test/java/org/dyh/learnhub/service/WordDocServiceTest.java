package org.dyh.learnhub.service;

import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFTable;
import org.apache.poi.xwpf.extractor.XWPFWordExtractor;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Word 生成的两层验证：**能被 POI 重新读出来**（真 OOXML）与**内容一个不少**。
 *
 * <p>顺带钉住一个容易回归的点：中文必须走 {@code w:eastAsia}（见 WordDocService 类注释），
 * 所以这里直接检查 document.xml 里有没有该属性 —— 只检查"文件能打开"是发现不了它的。
 */
class WordDocServiceTest {

    private final WordDocService service = new WordDocService();

    private static final String SAMPLE = """
            # Java 并发基础

            这是**加粗**与*斜体*，还有 `inline code`。

            ## 关键点

            - 第一点
              - 子点
            - 第二点

            1. 有序一
            2. 有序二

            > 引用一行

            ### 代码示例

            ```java
            public class A {
                // 中文注释
            }
            ```

            | 组件 | 作用 |
            | --- | --- |
            | JVM | 运行字节码 |
            """;

    @Test
    @DisplayName("生成的 docx 是真 OOXML：POI 能读回，且段落/表格/文字都在")
    void rendersReadableDocx() throws Exception {
        WordDocService.Doc doc = service.render("并发笔记", SAMPLE);

        assertEquals("并发笔记.docx", doc.fileName());
        assertTrue(doc.bytes().length > 1000, "文件太小，可能没写进内容");

        try (XWPFDocument reopened = new XWPFDocument(new ByteArrayInputStream(doc.bytes()));
             XWPFWordExtractor ex = new XWPFWordExtractor(reopened)) {
            String text = ex.getText();
            // 标题、正文、列表、引用、代码、表格 全部不能丢
            for (String must : new String[]{"Java 并发基础", "关键点", "第一点", "子点", "有序一",
                    "引用一行", "public class A", "中文注释", "组件", "运行字节码"}) {
                assertTrue(text.contains(must), "生成的文档里少了内容：" + must + "\n实际：" + text);
            }
            // 行内标记不应原样留在文档里
            assertTrue(!text.contains("**"), "加粗标记 ** 没有被解析掉：" + text);
            assertTrue(!text.contains("`"), "行内代码标记 ` 没有被解析掉：" + text);

            assertEquals(1, reopened.getTables().size(), "表格数量不对");
            XWPFTable table = reopened.getTables().get(0);
            // 源里的 `| --- | --- |` 是 Markdown 分隔行，应被丢掉 → 只剩表头 + 1 行数据
            assertEquals(2, table.getRows().size(), "分隔行没被丢掉，或数据行少了");
            assertTrue(table.getRow(0).getCell(0).getText().contains("组件"), "表头第一格不对");
            assertTrue(table.getRow(0).getCell(1).getText().contains("作用"), "表头第二格不对");
            assertTrue(table.getRow(1).getCell(0).getText().contains("JVM"), "数据行第一格不对");
            assertTrue(table.getRow(1).getCell(1).getText().contains("运行字节码"), "数据行第二格不对");
            assertTrue(reopened.getParagraphs().size() >= 6, "段落数偏少");
        }
    }

    @Test
    @DisplayName("中文必须写 w:eastAsia —— 否则换台电脑字体就变样")
    void setsEastAsiaFontForChinese() throws Exception {
        WordDocService.Doc doc = service.render("中文标题", "正文中文内容");
        String documentXml = readEntry(doc.bytes(), "word/document.xml");
        assertNotNull(documentXml, "document.xml 缺失，不是合法 docx");
        assertTrue(documentXml.contains("w:eastAsia"), "没有设置 w:eastAsia，中文会退化成宿主默认字体");
        assertTrue(documentXml.contains("微软雅黑"), "没有写中文字体名");
        // 代码块用等宽字体，同样要有 eastAsia 兜底（否则中文注释与代码对不齐）
        WordDocService.Doc withCode = service.render("t", "```\ncode 中文\n```");
        assertTrue(readEntry(withCode.bytes(), "word/document.xml").contains("Consolas"), "代码块没用等宽字体");
    }

    @Test
    @DisplayName("标题只出现一次：正文首行的同名一级标题不再重复写一遍")
    void titleIsNotDuplicated() throws Exception {
        // 模型几乎总会把标题也写进 markdown —— 打开文档看到两个标题就难看，抽取正文也会重复
        WordDocService.Doc doc = service.render("Java 并发笔记", "# Java 并发笔记\n\n正文内容。");
        try (XWPFDocument reopened = new XWPFDocument(new ByteArrayInputStream(doc.bytes()));
             XWPFWordExtractor ex = new XWPFWordExtractor(reopened)) {
            String text = ex.getText();
            int first = text.indexOf("Java 并发笔记");
            assertEquals(first, text.lastIndexOf("Java 并发笔记"),
                    "标题出现了两次（标题段 + 正文同名一级标题）：\n" + text);
            assertTrue(text.contains("正文内容。"), "剥标题时把正文也弄丢了：" + text);
        }
        // 标题不同名时，正文首行的 `# 标题` 要保留（不能顺手删掉用户的小节）
        WordDocService.Doc other = service.render("导出文档", "# 真正的小节\n\n正文。");
        try (XWPFDocument reopened = new XWPFDocument(new ByteArrayInputStream(other.bytes()));
             XWPFWordExtractor ex = new XWPFWordExtractor(reopened)) {
            String text = ex.getText();
            assertTrue(text.contains("导出文档"), "文档标题丢了：" + text);
            assertTrue(text.contains("真正的小节"), "不该剥掉不同名的一级标题：" + text);
        }
    }

    @Test
    @DisplayName("不支持的语法按普通文本输出，不丢字（样式可以朴素，内容不能少）")
    void keepsUnknownSyntaxAsPlainText() throws Exception {
        WordDocService.Doc doc = service.render("", "![图片](a.png) 与 <b>html</b> 与 !!! 这种乱写的标记");
        try (XWPFDocument reopened = new XWPFDocument(new ByteArrayInputStream(doc.bytes()));
             XWPFWordExtractor ex = new XWPFWordExtractor(reopened)) {
            String text = ex.getText();
            assertTrue(text.contains("![图片](a.png)"), "不认识的语法被吞了：" + text);
            assertTrue(text.contains("<b>html</b>"), "HTML 被吞了：" + text);
        }
        // 没标题时用正文首行当文件名
        assertEquals("文档.docx", service.render("", "文档").fileName());
        assertEquals("某小节.docx", service.render("", "# 某小节\n正文").fileName());
        // 非法文件名字符要被替换掉（否则落盘会失败）
        String safe = WordDocService.safeFileName("a/b\\c:d*e?f\"g<h>i|j", "");
        assertTrue(safe.matches("a b c d e f g h i j\\.docx"), "文件名没被净化：" + safe);
    }

    private static String readEntry(byte[] zipBytes, String name) throws Exception {
        try (ZipInputStream zin = new ZipInputStream(new ByteArrayInputStream(zipBytes))) {
            ZipEntry e;
            while ((e = zin.getNextEntry()) != null) {
                if (name.equals(e.getName())) {
                    return new String(zin.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
                }
            }
        }
        return null;
    }
}
