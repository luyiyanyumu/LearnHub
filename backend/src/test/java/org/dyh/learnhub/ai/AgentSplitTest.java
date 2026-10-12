package org.dyh.learnhub.ai;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 分块逻辑的单元测试（纯静态函数，不需要 Spring 上下文 / 数据库）。
 *
 * <p>这些用例针对的是真实踩过的两个坑：
 * <ol>
 *   <li>4 反引号围栏里嵌了 3 反引号 → 旧代码用 boolean 翻转，会误判成闭合；</li>
 *   <li>超长代码块被按行硬切 → 切出两片都不闭合的围栏，拼回去整段变成代码。</li>
 * </ol>
 */
class AgentSplitTest {

    private static final String FENCE = "```";
    /** 只看「是不是围栏行」（允许后面跟语言标识，如 ```java） */
    private static final Pattern FENCE_LINE = Pattern.compile("^\\s*`{3,}.*$");
    /** 严格匹配「裸围栏行」（``` 后面什么都没）—— 用来识别我们自己补出来的那两行 */
    private static final Pattern BARE_FENCE_LINE = Pattern.compile("^\\s*`{3,}\\s*$");

    // ------------------------------------------------------------------
    // splitForPolish：空行 / 围栏边界
    // ------------------------------------------------------------------

    @Test
    @DisplayName("短文本不切块")
    void shortTextIsNotSplit() {
        String text = "# 标题\n\n正文一段。";
        assertEquals(List.of(text), AgentService.splitForPolish(text));
    }

    @Test
    @DisplayName("围栏代码块内的空行不算块边界")
    void blankLinesInsideFenceAreNotBoundaries() {
        // 单块 4000 字以内 → 不会走到硬切，整体一块
        StringBuilder sb = new StringBuilder("```java\n");
        for (int i = 0; i < 200; i++) {
            sb.append("int a").append(i).append(" = ").append(i).append(";\n\n");  // 每行后面都跟空行
        }
        sb.append("```");
        String text = sb.toString();
        assertTrue(text.length() > 1000, "构造的文本应该足够长");

        List<String> chunks = AgentService.splitForPolish(text);
        assertEquals(1, chunks.size(), "围栏内的空行不应该把代码块切开");
        assertEquals(text, chunks.get(0));
    }

    @Test
    @DisplayName("4 反引号围栏不会被内部的 3 反引号提前闭合")
    void fourBacktickFenceSurvivesInnerThreeBacktick() {
        // 用 ```` 包住一段 markdown 示例，示例里含 ``` 代码块
        StringBuilder sb = new StringBuilder("````markdown\n");
        sb.append("下面是示例：\n\n```js\nconsole.log(1)\n```\n\n");
        // 补到超过一个分片，逼出「按空行切块」这条路径
        for (int i = 0; i < 400; i++) {
            sb.append("填充行 ").append(i).append(" 用来把总长度撑过 4000 字。\n\n");
        }
        sb.append("````");
        String text = sb.toString();
        assertTrue(text.length() > AgentService.POLISH_CHUNK_CHARS, "文本应超过单块上限");

        List<String> chunks = AgentService.splitForPolish(text);
        assertTrue(chunks.size() > 1, "应该被切成多块");
        // 每一块里，围栏行（```` / ```）都必须成对出现 —— 否则说明切在了围栏内部
        for (int i = 0; i < chunks.size(); i++) {
            long fences = chunks.get(i).lines().filter(l -> FENCE_LINE.matcher(l).matches()).count();
            assertEquals(0, fences % 2, "第 " + (i + 1) + " 块的围栏不成对：" + fences);
        }
        // 而且每块都必须自带围栏开头，不能出现「后半段没有 ```` 开头」的情况
        assertTrue(chunks.stream().skip(1).anyMatch(c -> c.startsWith("````") || c.contains("````")));
    }

    @Test
    @DisplayName("跨分片边界的多行公式保留内部空行")
    void blankLinesInsideMathAreNotBoundaries() {
        String formula = "$$\n\\begin{aligned}\ns(q,d) &= \\langle E_q(q), E_d(d) \\rangle \\\\\n\n"
                + "p(y \\mid x) &= \\frac{e^{s_y}}{\\sum_j e^{s_j}}\n\\end{aligned}\n$$";
        String prefix = "前文：" + "合成说明".repeat(970);
        String text = prefix + "\n\n" + formula + "\n\n结论：相似度可表示为向量内积。";
        assertTrue(text.length() > AgentService.POLISH_CHUNK_CHARS);

        List<String> chunks = AgentService.splitForPolish(text);
        assertEquals(2, chunks.size());
        assertTrue(chunks.get(1).contains(formula), "公式和内部空行必须原样处于同一分片");
        assertEquals(text, String.join("\n\n", chunks));
    }

    @Test
    @DisplayName("超长 aligned 公式整体保留，不补写或拆分数学环境")
    void hugeAlignedMathStaysIntact() {
        String formula = "$$\n\\begin{aligned}\n"
                + "a_{i+1} &= a_i + \\sum_{j=1}^{n} w_j x_j \\\\\n\n".repeat(210)
                + "\\end{aligned}\n$$";
        assertTrue(formula.length() > AgentService.POLISH_CHUNK_CHARS * 2);

        assertEquals(List.of(formula), AgentService.splitForPolish(formula));
        assertEquals(List.of(formula), AgentService.hardSplit(formula));
    }

    @Test
    @DisplayName("公式前后无空行时，硬切仍只能发生在公式外")
    void hardSplitKeepsMathBetweenAdjacentProse() {
        String prefix = "公式之前".repeat(980);
        String formula = "$$\n\\begin{aligned}\n"
                + "f(x) &= \\sum_{i=1}^{n} x_i^2 \\\\\n".repeat(180)
                + "\\end{aligned}\n$$";
        String suffix = "公式之后".repeat(980);
        String block = prefix + "\n" + formula + "\n" + suffix;

        List<String> chunks = AgentService.hardSplit(block);
        assertEquals(List.of(prefix, formula, suffix), chunks);
        assertEquals(block, String.join("\n", chunks));
    }

    @Test
    @DisplayName("兼容方括号数学块、同一行公式以及多个公式之间的正文")
    void multipleMathDelimitersAndProseKeepOrder() {
        String first = "\\[\n\\begin{aligned}\nA &= BC \\\\\n\nD &= EF\n\\end{aligned}\n\\]";
        String middle = "中间解释".repeat(980);
        String second = "$$s(q,d) = \\langle q,d \\rangle$$";
        String tail = "后续解释".repeat(980);
        String text = first + "\n\n" + middle + "\n\n" + second + "\n\n" + tail;

        List<String> chunks = AgentService.splitForPolish(text);
        assertTrue(chunks.size() > 1);
        assertTrue(chunks.stream().anyMatch(c -> c.contains(first)));
        assertTrue(chunks.stream().anyMatch(c -> c.contains(second)));
        assertEquals(text, String.join("\n\n", chunks));
    }

    @Test
    @DisplayName("代码围栏里的 $$ 不被当成数学块，长代码仍正常续接")
    void dollarsInsideCodeDoNotStartMath() {
        String block = "```text\n$$\n"
                + "这里是合成代码示例行，不是公式。\n\n".repeat(650)
                + "```";

        List<String> chunks = AgentService.splitForPolish(block);
        assertTrue(chunks.size() > 1, "代码里的未配对 $$ 不应阻止正常分片");
        for (String chunk : chunks) {
            assertTrue(chunk.startsWith("```text\n"));
            assertTrue(chunk.endsWith("```"));
            assertEquals(2, chunk.lines().filter(l -> FENCE_LINE.matcher(l).matches()).count());
        }
        assertEquals(1, chunks.stream().mapToLong(c -> c.lines().filter("$$"::equals).count()).sum());
    }

    @Test
    @DisplayName("同一行闭合的数学块不会吞掉后续长正文")
    void oneLineMathDoesNotKeepFollowingProseTogether() {
        String formula = "$$f(x)=x^2$$";
        String prose = "下一行仍是普通正文，不属于数学块。\n".repeat(650).stripTrailing();
        List<String> chunks = AgentService.hardSplit(formula + "\n" + prose);

        assertTrue(chunks.size() > 1);
        assertTrue(chunks.stream().allMatch(c -> c.length() <= AgentService.POLISH_CHUNK_CHARS));
        assertEquals(formula + "\n" + prose, String.join("\n", chunks));
    }

    @Test
    @DisplayName("未闭合数学块和转义的分隔符保留到文末，不制造半个公式")
    void unclosedMathAndEscapedDelimitersAreNotSplit() {
        String formula = "$$\n\\begin{aligned}\n"
                + "x &= \\text{literal \\$$} + y \\\\\n\n".repeat(250);
        assertTrue(formula.length() > AgentService.POLISH_CHUNK_CHARS);

        assertEquals(List.of(formula), AgentService.splitForPolish(formula));
        assertEquals(List.of(formula), AgentService.hardSplit(formula));
    }

    // ------------------------------------------------------------------
    // hardSplit：超长代码块 / 超长表格
    // ------------------------------------------------------------------

    @Test
    @DisplayName("★ 超长代码块被切开后，每一片的围栏都是闭合的")
    void hugeCodeBlockIsSplitWithBalancedFences() {
        List<String> body = new ArrayList<>();
        for (int i = 0; i < 600; i++) {
            body.add("    int value" + i + " = computeSomething(" + i + ", \"padding\");");
        }
        String block = FENCE + "java\n" + String.join("\n", body) + "\n" + FENCE;
        assertTrue(block.length() > AgentService.POLISH_CHUNK_CHARS * 2, "构造的代码块应超过两块");

        List<String> chunks = AgentService.hardSplit(block);
        assertTrue(chunks.size() >= 2, "超长代码块必须被切开");

        for (int i = 0; i < chunks.size(); i++) {
            String c = chunks.get(i);
            assertTrue(c.startsWith(FENCE + "java"), "第 " + (i + 1) + " 片应以开栏围栏开头");
            assertTrue(c.stripTrailing().endsWith(FENCE), "第 " + (i + 1) + " 片应以闭合围栏结尾");
            long fences = c.lines().filter(l -> FENCE_LINE.matcher(l).matches()).count();
            assertEquals(2, fences, "第 " + (i + 1) + " 片应恰好有一对围栏，实际 " + fences);
        }
    }

    @Test
    @DisplayName("★ 超长代码块切开后内容不丢、顺序不变")
    void hugeCodeBlockKeepsAllLinesInOrder() {
        List<String> body = new ArrayList<>();
        for (int i = 0; i < 600; i++) {
            body.add("line" + i + " = \"content-" + i + "\";");
        }
        String block = FENCE + "\n" + String.join("\n", body) + "\n" + FENCE;

        List<String> chunks = AgentService.hardSplit(block);
        List<String> restored = new ArrayList<>();
        for (String c : chunks) {
            for (String line : c.split("\n", -1)) {
                if (BARE_FENCE_LINE.matcher(line).matches()) {
                    continue;   // 跳过（我们补上的）围栏行
                }
                restored.add(line);
            }
        }
        // 首尾各有一个空行是 `\n` 拼接带来的，去掉后再比
        while (!restored.isEmpty() && restored.get(0).isEmpty()) {
            restored.remove(0);
        }
        while (!restored.isEmpty() && restored.get(restored.size() - 1).isEmpty()) {
            restored.remove(restored.size() - 1);
        }
        assertEquals(body, restored, "切片再拼回来的内容行必须与原文完全一致");
    }

    @Test
    @DisplayName("★ 超长表格切开后，后续每一片都补上了表头 + 分隔行")
    void hugeTableRepeatsHeaderOnEachChunk() {
        List<String> rows = new ArrayList<>();
        rows.add("| 字段 | 说明 |");
        rows.add("| --- | --- |");
        for (int i = 0; i < 400; i++) {
            rows.add("| field" + i + " | 这是第 " + i + " 行的说明文字 |");
        }
        String table = String.join("\n", rows);
        assertTrue(table.length() > AgentService.POLISH_CHUNK_CHARS * 2, "构造的表格应超过两块");

        List<String> chunks = AgentService.hardSplit(table);
        assertTrue(chunks.size() >= 2, "超长表格必须被切开");
        assertEquals(rows.get(0), chunks.get(0).split("\n", -1)[0], "第一片第一行应是表头");
        for (int i = 1; i < chunks.size(); i++) {
            String[] lines = chunks.get(i).split("\n", -1);
            assertEquals(rows.get(0), lines[0], "第 " + (i + 1) + " 片应补上表头");
            assertEquals(rows.get(1), lines[1], "第 " + (i + 1) + " 片应补上分隔行（否则后半张表不成表）");
        }
    }

    @Test
    @DisplayName("普通长段落（无围栏无表格）按行切开，不注入任何多余内容")
    void plainTextSplitHasNoInjectedLines() {
        List<String> body = new ArrayList<>();
        for (int i = 0; i < 500; i++) {
            body.add("这是第 " + i + " 段普通的说明文字，用来把单个顶层块撑到超过上限。");
        }
        String block = String.join("\n", body);

        List<String> chunks = AgentService.hardSplit(block);
        assertTrue(chunks.size() >= 2);
        assertEquals(body, List.of(String.join("\n", chunks).split("\n", -1)),
                "没有结构的文本不该被注入任何内容");
    }

    @Test
    @DisplayName("缩进 4 空格的反引号行是「缩进代码块」，不算围栏")
    void indentedFenceIsNotAFence() {
        // 4 空格缩进 → 不是围栏，因此这一块不会被当成围栏内容，
        // 这里只断言不抛异常且内容完整
        String block = "    ```\n    not a fence\n    ```\n";
        List<String> chunks = AgentService.hardSplit(block);
        assertEquals(1, chunks.size());
        assertFalse(chunks.get(0).isEmpty());
    }
}
