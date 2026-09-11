package org.dyh.learnhub.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.dyh.learnhub.service.NoteService.plainSummary;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 摘要推导（{@code NoteService#plainSummary}）的单元测试 —— 纯函数，不需要 Spring 上下文。
 *
 * <p>这一组用例锁的是「先摘 HTML、再清 Markdown」这个顺序。真实踩坑：
 * 笔记正文里全是语雀粘贴来的 {@code <font style="color:rgb(0,0,0)">一、Java核心基础</font>}，
 * 旧实现先跑 Markdown 标点清洗，把标签里的 {@code >} 和括号吃掉，
 * 标签就不成标签了，列表预览直接显示 {@code <font style=color:rgb0, 0, 0一、Java核心基础}。
 */
class NoteSummaryTest {

    @Test
    @DisplayName("HTML 标签应被摘掉，而不是残留成乱码")
    void htmlTagIsStrippedNotLeaked() {
        String content = "# <font style=\"color:rgb(0, 0, 0)\">一、Java核心基础</font>\n"
                + "## <font style=\"color:#E4495B\">（一）Java基础</font>";

        String s = plainSummary(content);

        assertEquals("一、Java核心基础 （一）Java基础", s);
        // 这条才是真正的护栏：任何 '<' 漏出来都说明顺序又错了
        assertFalse(s.contains("<"), "摘要里不应出现 HTML 标签残骸，实际=" + s);
    }

    @Test
    @DisplayName("行内混合彩色文字 + 加粗，只留纯文字")
    void mixedInlineMarkupBecomesPlainText() {
        String content = "<font style=\"color:rgb(77, 77, 77)\">Java 是一门 </font>"
                + "**<font style=\"color:rgb(77, 77, 77)\">面向对象、跨平台</font>**"
                + "<font style=\"color:rgb(77, 77, 77)\"> 的高级语言。</font>";

        assertEquals("Java 是一门 面向对象、跨平台 的高级语言。", plainSummary(content));
    }

    @Test
    @DisplayName("围栏代码块整块丢弃")
    void codeBlockIsDropped() {
        String content = "先看代码：\n\n```java\npublic class A {}\n```\n\n然后解释。";

        String s = plainSummary(content);

        assertTrue(s.startsWith("先看代码："), s);
        assertFalse(s.contains("public class"), s);
        assertTrue(s.endsWith("然后解释。"), s);
    }

    @Test
    @DisplayName("链接保留文字、丢掉地址")
    void linkKeepsText() {
        assertEquals("参考 官方文档 里的说明",
                plainSummary("参考 [官方文档](https://example.com/a_b) 里的说明"));
    }

    @Test
    @DisplayName("C# / F# 里的井号不能被当成标题符号啃掉")
    void sharpInLanguageNameSurvives() {
        String s = plainSummary("# 语言对比\nC# 和 F# 都是 .NET 系。");

        assertTrue(s.contains("C#"), s);
        assertTrue(s.contains("F#"), s);
        assertTrue(s.startsWith("语言对比"), s);
    }

    @Test
    @DisplayName("正文里的连字符不能被当成列表符吃掉")
    void hyphenInsideWordSurvives() {
        assertEquals("基于发布-订阅模式，适合 IoT 低带宽场景。",
                plainSummary("- 基于发布-订阅模式，适合 IoT 低带宽场景。"));
    }

    @Test
    @DisplayName("HTML 实体应解码，且 &#39; 不被当成标题符号")
    void entitiesAreDecoded() {
        assertEquals("Tom & Jerry 的 10 <= 20，引号 'x'。",
                plainSummary("Tom &amp; Jerry 的 10 &lt;= 20，引号 &#39;x&#39;。"));
    }

    @Test
    @DisplayName("断在中间的残尾标签应被丢掉，但数学小于号要保留")
    void brokenTagTailDroppedButLessThanKept() {
        assertFalse(plainSummary("正文<font style=\"color:rgb(1,2,3)").contains("<"));

        // 「价格 < 100」里的 < 后面不是标签名 → 必须原样保留
        assertEquals("价格 < 100 时触发。", plainSummary("价格 < 100 时触发。"));
    }

    @Test
    @DisplayName("超长正文截到 120 字并加省略号")
    void truncatesWithEllipsis() {
        String s = plainSummary("甲".repeat(200));

        assertEquals(121, s.length());
        assertEquals("…", s.substring(s.length() - 1));
    }

    @Test
    @DisplayName("空 / 纯空白正文返回空串")
    void blankReturnsEmpty() {
        assertEquals("", plainSummary(null));
        assertEquals("", plainSummary("   "));
        assertEquals("", plainSummary("\n\n"));
    }

    @Test
    @DisplayName("分隔线整行去掉，表格竖线也不残留")
    void separatorAndTablePipesGone() {
        String s = plainSummary("甲\n\n---\n\n| 列A | 列B |\n|---|---|\n| 1 | 2 |");

        assertFalse(s.contains("-"), s);
        assertFalse(s.contains("|"), s);
        assertTrue(s.contains("甲"), s);
        assertTrue(s.contains("列A"), s);
    }
}
