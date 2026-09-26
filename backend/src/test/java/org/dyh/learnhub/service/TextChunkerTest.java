package org.dyh.learnhub.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 分块器的单元测试：锁住两个**实测踩过**的坑。
 *
 * <p>分块是检索的地基：块切错，后面向量、融合、重排做得再好都救不回来 ——
 * 而且这类错误很隐蔽（检索"看起来还能用"，只是某些问题永远答不准）。
 */
class TextChunkerTest {

    @Test
    @DisplayName("代码围栏里的 # 注释不能被当成小节标题")
    void hashInsideCodeFenceIsNotHeading() {
        // 实测：SQL 示例里的 `# 查询表中所有字段` 被当成分节标题，
        // 结果一堆块的小节路径变成"查询表中所有字段"，真实层级被冲掉
        String md = """
                # Java 数据库
                ## JDBC 连接
                ```sql
                # 查询表中所有字段
                SELECT * FROM user;
                # 插入一条数据
                INSERT INTO user VALUES ('张三', 20);
                ```
                后面这段正文属于 JDBC 这一节。
                """;
        List<TextChunker.Chunk> chunks = TextChunker.splitWithHeadings(md);
        for (TextChunker.Chunk c : chunks) {
            assertFalse(c.heading().contains("查询表中所有字段"),
                    "代码注释漏成了小节标题：" + c.heading());
            assertFalse(c.heading().contains("插入一条数据"),
                    "代码注释漏成了小节标题：" + c.heading());
        }
        // 真实层级必须保留
        assertTrue(chunks.stream().anyMatch(c -> c.heading().contains("Java 数据库")),
                "真实标题丢了：" + chunks);
    }

    @Test
    @DisplayName("标题里的 HTML 标签与强调标记要被清掉（否则嵌进向量就是噪声）")
    void htmlInHeadingIsCleaned() {
        String md = """
                # <font style="color:rgb(0, 0, 0)">一、Java核心基础</font>
                ## **<font style="color:#E4495B">（一）Java基础</font>**
                正文。
                """;
        List<TextChunker.Chunk> chunks = TextChunker.splitWithHeadings(md);
        assertEquals(1, chunks.size());
        String h = chunks.get(0).heading();
        assertEquals("一、Java核心基础 > （一）Java基础", h);
        assertFalse(h.contains("font"), "HTML 标签没清掉：" + h);
        assertFalse(h.contains("rgb"), "样式内容没清掉：" + h);
        assertFalse(h.contains("*"), "强调标记没清掉：" + h);
    }

    @Test
    @DisplayName("上下文嵌入文本 = 文档标题 · 小节 + 正文")
    void embedTextShape() {
        String t = TextChunker.embedText("Java学习笔记", "一、Java核心基础 > 1.Java简介", "正文内容");
        assertEquals("Java学习笔记 · 一、Java核心基础 > 1.Java简介\n正文内容", t);
        // 没有标题时不能留下孤零零的分隔符
        assertEquals("只有正文", TextChunker.embedText("", "", "只有正文"));
        assertEquals("标题\n正文", TextChunker.embedText("标题", null, "正文"));
    }

    @Test
    @DisplayName("多级标题要组成路径，同级标题要回退覆盖")
    void headingPathStack() {
        String md = """
                # A
                ## A1
                正文1
                ## A2
                正文2
                # B
                正文3
                """;
        List<TextChunker.Chunk> chunks = TextChunker.splitWithHeadings(md);
        List<String> heads = chunks.stream().map(TextChunker.Chunk::heading).toList();
        assertTrue(heads.contains("A > A1"), "应为: " + heads);
        assertTrue(heads.contains("A > A2"), "应为: " + heads);
        // 到 # B 时 A1/A2 必须被弹出，不能变成 "A > A2 > B"
        assertTrue(heads.contains("B"), "应为: " + heads);
        assertFalse(heads.stream().anyMatch(h -> h.contains("A2 > B")), "层级没回退: " + heads);
    }

    @Test
    @DisplayName("没有 Markdown 标题的纯文本也要能切（PDF 就是这种）")
    void plainTextStillSplits() {
        String plain = "第一段内容。".repeat(200);
        List<TextChunker.Chunk> chunks = TextChunker.splitWithHeadings(plain);
        assertFalse(chunks.isEmpty());
        assertTrue(chunks.stream().allMatch(c -> c.heading().isEmpty()), "纯文本不该有小节");
        // 每块不超过硬上限
        assertTrue(chunks.stream().allMatch(c -> c.text().length() <= TextChunker.MAX + 50),
                "有块超过上限");
    }
}
