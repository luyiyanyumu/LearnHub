package org.dyh.learnhub.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Synthetic regression cases for retaining answer context around structural boundaries. */
class TextChunkerRetrievalBoundaryTest {
    private static final TextChunker.Options OPTIONS = new TextChunker.Options(800, 120, 1200);

    @Test
    @DisplayName("结构分块接近目标大小，不沿用语义小单元的提前截断")
    void completeSentencesCanFillTheStructuralTarget() {
        List<String> sentences = sentences(6, 266);
        List<TextChunker.Chunk> chunks = TextChunker.splitWithHeadings(String.join("", sentences), OPTIONS);

        assertFalse(chunks.isEmpty());
        String first = chunks.getFirst().text();
        assertTrue(first.contains(sentences.get(0)) && first.contains(sentences.get(1))
                        && first.contains(sentences.get(2)),
                "前三句合计接近 target，不能因语义小单元的分隔而过早发出小块");
        assertTrue(chunks.stream().allMatch(chunk -> chunk.text().length() <= OPTIONS.max()));
    }

    @Test
    @DisplayName("未超过硬上限的长句保持完整")
    void sentenceBetweenTargetAndMaxIsNotHardCutIntoSemanticUnits() {
        String sentence = sentence(0, 900);
        List<TextChunker.Chunk> chunks = TextChunker.splitWithHeadings(sentence, OPTIONS);

        assertTrue(chunks.stream().anyMatch(chunk -> chunk.text().contains(sentence)),
                "900 字完整句可以超过 target，但仍在 max 内，不应被按 target/3 切断");
        assertTrue(chunks.stream().allMatch(chunk -> chunk.text().length() <= OPTIONS.max()));
    }

    @Test
    @DisplayName("英文 PDF 句内折行不作为长句分割边界")
    void pdfLineWrapsDoNotSplitCompleteEnglishSentences() {
        List<String> sentences = IntStream.range(0, 3).mapToObj(index -> {
            String plain = "PDF paragraph sentence " + index + ": "
                    + "documentation context remains attached ".repeat(22) + "closing explanation.";
            StringBuilder wrapped = new StringBuilder();
            for (int start = 0; start < plain.length(); start += 60) {
                if (!wrapped.isEmpty()) wrapped.append('\n');
                wrapped.append(plain, start, Math.min(start + 60, plain.length()));
            }
            return wrapped.toString();
        }).toList();
        String paragraph = String.join("\n", sentences);
        assertTrue(paragraph.length() > OPTIONS.max());
        assertTrue(sentences.stream().allMatch(sentence -> sentence.length() > OPTIONS.target()
                && sentence.length() <= OPTIONS.max()));

        List<TextChunker.Chunk> chunks = TextChunker.splitWithHeadings(paragraph, OPTIONS);

        for (String sentence : sentences) {
            assertTrue(chunks.stream().anyMatch(chunk -> chunk.text().contains(sentence)),
                    "PDF 的句内换行不应使仍在 max 内的完整英文句子被拆断");
        }
        assertTrue(chunks.stream().allMatch(chunk -> chunk.text().length() <= OPTIONS.max()));
    }

    @Test
    @DisplayName("数字列表标记与条目正文保持相连，小数点不作为句末")
    void numberedItemsKeepTheirOpeningTextAndDecimalValue() {
        String prefaceStart = "Synthetic preceding explanation ";
        String preface = prefaceStart + "p".repeat(790 - prefaceStart.length() - 1) + ".";
        String itemStart = "Synthetic numbered item keeps a value of 3.14 with its explanation ";
        String item = itemStart + "n".repeat(900 - itemStart.length() - 1) + ".";
        String followingStart = "Additional concluding explanation ";
        String following = followingStart + "f".repeat(300 - followingStart.length() - 1) + ".";

        for (String marker : List.of("1.", "12.")) {
            String indentation = marker.equals("12.") ? "    " : "";
            String paragraph = preface + "\n" + indentation + marker + " " + item + "\n" + following;
            assertTrue(paragraph.length() > OPTIONS.max());
            List<TextChunker.Chunk> chunks = TextChunker.splitWithHeadings(paragraph, OPTIONS);

            for (TextChunker.Chunk chunk : chunks) {
                if (chunk.text().contains(marker)) {
                    assertTrue(chunk.text().contains(marker + " " + itemStart),
                            "行首编号不能作为上一句句末，独自留在前一个块中：" + marker);
                }
                assertTrue(chunk.text().length() <= OPTIONS.max());
            }
            assertTrue(chunks.stream().anyMatch(chunk -> chunk.text().contains(marker + " " + item)),
                    "仍在 max 内的编号条目应保留完整正文，包括句内小数 3.14");
        }
    }

    @Test
    @DisplayName("公式与紧随其后的解释在硬上限内保留共同上下文")
    void displayFormulaCanShareAChunkWithItsExplanation() {
        String formula = "$$\n" + "x".repeat(494) + "\n$$";
        String explanation = "参数说明：" + "参".repeat(294) + "。";
        assertEquals(500, formula.length());
        assertEquals(300, explanation.length());

        List<TextChunker.Chunk> chunks = TextChunker.splitWithHeadings(formula + "\n\n" + explanation, OPTIONS);

        assertTrue(chunks.stream().anyMatch(chunk -> chunk.text().contains(formula)
                        && chunk.text().contains(explanation) && chunk.text().length() <= OPTIONS.max()),
                "公式和解释仅略超 target 时应在 max 内共同出现，保留回答所需的关系");
    }

    @Test
    @DisplayName("长于重叠预算的句子不会被复制半句到下一块")
    void overlapOnlyCopiesCompleteSentences() {
        List<String> sentences = sentences(6, 300);
        List<TextChunker.Chunk> chunks = TextChunker.splitWithHeadings(String.join("", sentences), OPTIONS);

        assertTrue(chunks.size() > 1);
        for (TextChunker.Chunk chunk : chunks) {
            for (int i = 0; i < sentences.size(); i++) {
                char filler = filler(i);
                if (chunk.text().indexOf(filler) >= 0) {
                    assertTrue(chunk.text().contains(sentences.get(i)),
                            "出现该句的独有正文字符时必须保留整句，不能只重叠尾部：句 " + i);
                }
            }
        }
    }

    @Test
    @DisplayName("零重叠时正文按顺序出现一次")
    void zeroOverlapDoesNotDuplicateProse() {
        List<String> sentences = sentences(6, 300);
        String input = String.join("", sentences);
        List<TextChunker.Chunk> chunks = TextChunker.splitWithHeadings(input,
                new TextChunker.Options(800, 0, 1200));

        assertEquals(input, chunks.stream().map(TextChunker.Chunk::text)
                .map(text -> text.replace("\n", "")).reduce("", String::concat));
    }

    @Test
    @DisplayName("标题边界不合并正文或携带上一小节的重叠")
    void headingChangeSeparatesContext() {
        String first = sentence(0, 300);
        String second = sentence(1, 300);
        List<TextChunker.Chunk> chunks = TextChunker.splitWithHeadings(
                "# 主题甲\n" + first + "\n\n# 主题乙\n" + second, OPTIONS);

        assertEquals(2, chunks.size());
        assertEquals("主题甲", chunks.get(0).heading());
        assertEquals(first, chunks.get(0).text());
        assertEquals("主题乙", chunks.get(1).heading());
        assertEquals(second, chunks.get(1).text());
    }

    @Test
    @DisplayName("必须硬切的超长普通文本仍保持完整 Unicode 字符")
    void forcedCutsDoNotSplitSurrogatePairs() {
        String input = "📘".repeat(1500);
        List<TextChunker.Chunk> chunks = TextChunker.splitWithHeadings(input,
                new TextChunker.Options(799, 119, 1199));

        assertTrue(chunks.size() > 1);
        for (TextChunker.Chunk chunk : chunks) {
            assertTrue(chunk.text().length() <= 1199);
            assertValidSurrogates(chunk.text());
        }
    }

    @Test
    @DisplayName("超出硬上限的代码、公式和表格仍作为完整原子块保留")
    void oversizedProtectedBlocksStayWhole() {
        String code = "```java\n" + "int syntheticValue = 123;\n".repeat(70) + "```";
        String formula = "$$\n" + "x + ".repeat(400) + "y\n$$";
        String table = "| 项目 | 说明 |\n| --- | --- |\n" + "| 合成项目 | 合成测试内容与参数说明 |\n".repeat(70);

        for (String raw : List.of(code, formula, table)) {
            String block = raw.strip();
            assertTrue(block.length() > OPTIONS.max());
            List<TextChunker.Chunk> chunks = TextChunker.splitWithHeadings(
                    "# 合成结构\n前言。\n\n" + block + "\n\n后记。", OPTIONS);
            assertEquals(1L, chunks.stream().filter(chunk -> chunk.text().equals(block)).count(),
                    "超长原子块必须原样出现一次，不能拆断或产生重叠残片");
            assertTrue(chunks.stream().allMatch(chunk -> chunk.text().equals(block)
                            || chunk.text().equals("前言。") || chunk.text().equals("后记。")),
                    "原子块以外只能剩下完整的前后正文");
        }
    }

    private static List<String> sentences(int count, int length) {
        return IntStream.range(0, count).mapToObj(i -> sentence(i, length)).toList();
    }

    private static String sentence(int index, int length) {
        String prefix = "合成句" + index + "：";
        return prefix + String.valueOf(filler(index)).repeat(length - prefix.length() - 1) + "。";
    }

    private static char filler(int index) {
        return (char) ('甲' + index);
    }

    private static void assertValidSurrogates(String text) {
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (Character.isHighSurrogate(c)) {
                assertTrue(i + 1 < text.length() && Character.isLowSurrogate(text.charAt(i + 1)),
                        "高代理项后缺少低代理项");
                i++;
            } else {
                assertFalse(Character.isLowSurrogate(c), "出现孤立低代理项");
            }
        }
    }
}
