package org.dyh.learnhub.service;

import org.junit.jupiter.api.Test;
import java.util.stream.IntStream;
import static org.junit.jupiter.api.Assertions.*;

class TextChunkerParametersTest {
    @Test void customTargetActuallyChangesPassageCountAndPreservesHeading() {
        String body = IntStream.range(0, 8).mapToObj(i -> "合成段落" + i + "内容".repeat(25))
                .collect(java.util.stream.Collectors.joining("\n\n"));
        String text = "# 合成主题\n" + body;
        assertEquals(1, TextChunker.splitWithHeadings(text).size());
        var small = TextChunker.splitWithHeadings(text, new TextChunker.Options(100, 20, 160));
        assertTrue(small.size() > 1);
        assertTrue(small.stream().allMatch(chunk -> chunk.heading().equals("合成主题") && chunk.text().length() <= 160));
    }

    @Test void maximumStillHoldsWhenParagraphOverlapWouldOtherwiseExceedIt() {
        String first = "甲".repeat(45) + "。" + "前".repeat(23) + "。";
        String second = "乙".repeat(114) + "。";
        String text = first + "\n\n" + second;
        var chunks = TextChunker.splitWithHeadings(text, new TextChunker.Options(100, 30, 120));
        // The 24-character complete-sentence overlap cannot fit beside the intact
        // 115-character paragraph. Drop overlap rather than splitting that paragraph.
        assertEquals(2, chunks.size());
        assertEquals(first, chunks.getFirst().text());
        assertEquals(second, chunks.getLast().text());
        assertTrue(chunks.stream().allMatch(chunk -> chunk.text().length() <= 120));
    }

    @Test void zeroOverlapAndInvalidOptionsAreHandled() {
        var chunks = TextChunker.splitWithHeadings("甲".repeat(150), new TextChunker.Options(60, 0, 80));
        assertEquals(150, chunks.stream().mapToInt(chunk -> chunk.text().length()).sum());
        assertThrows(IllegalArgumentException.class, () -> new TextChunker.Options(100, 100, 120));
        assertThrows(IllegalArgumentException.class, () -> new TextChunker.Options(100, -1, 120));
    }
}
