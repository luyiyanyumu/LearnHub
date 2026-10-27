package org.dyh.learnhub.service;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.*;

class SemanticChunkerTest {
    @Test void topicChangesDoNotCarryOldTopicOverlapIntoNewChunk() {
        var units = IntStream.range(0, 8).mapToObj(i -> new TextChunker.Unit("合成主题",
                (i < 4 ? "缓存一致性与失效策略。" : "梯度下降与模型训练。").repeat(4), false)).toList();
        var vectors = IntStream.range(0, 8).mapToObj(i -> i < 4 ? new float[]{1, 0} : new float[]{0, 1}).toList();
        var result = SemanticChunker.split(units, new TextChunker.Options(180, 80, 240), vectors, 85);
        assertFalse(result.isEmpty());
        assertTrue(result.stream().noneMatch(chunk -> chunk.text().contains("缓存") && chunk.text().contains("梯度")));
        assertTrue(result.stream().allMatch(chunk -> chunk.text().length() <= 240));
        for (var unit : units) assertTrue(result.stream().anyMatch(chunk -> chunk.text().contains(unit.text())));
    }

    @Test void protectsOversizeCodeAndDoesNotJoinAcrossHeadings() {
        String code = "```java\n" + "// 合成完整代码\n".repeat(100) + "```";
        var units = List.of(new TextChunker.Unit("第一节", "缓存策略。".repeat(6), false),
                new TextChunker.Unit("第一节", code, true),
                new TextChunker.Unit("第二节", "训练策略。".repeat(6), false));
        var result = SemanticChunker.split(units, new TextChunker.Options(80, 20, 120),
                List.of(new float[]{1, 0}, new float[]{1, 0}), 85);
        assertEquals(3, result.size());
        assertEquals(code, result.get(1).text());
        assertEquals("第二节", result.get(2).heading());
    }

    @Test void rejectsInvalidBoundaryVectorsInsteadOfPublishingDifferentBoundaries() {
        var units = List.of(new TextChunker.Unit("", "第一段", false), new TextChunker.Unit("", "第二段", false));
        var options = TextChunker.Options.defaults();
        assertThrows(IllegalStateException.class, () -> SemanticChunker.split(units, options, List.of(new float[]{1}), 85));
        assertThrows(IllegalStateException.class, () -> SemanticChunker.split(units, options,
                List.of(new float[]{1}, new float[]{1, 0}), 85));
        assertThrows(IllegalStateException.class, () -> SemanticChunker.split(units, options,
                List.of(new float[]{1}, new float[]{Float.NaN}), 85));
        assertThrows(IllegalStateException.class, () -> SemanticChunker.split(units, options,
                List.of(new float[]{1}, new float[]{0}), 85));
    }
}
