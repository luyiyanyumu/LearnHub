package org.dyh.learnhub.service;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class WikiMaterialSamplerTest {
    @Test
    void namedConceptFindsFullTextAfterTwentyThousandCharacters() {
        StringBuilder full = new StringBuilder();
        for (int i = 0; i < 40; i++) {
            full.append("## Unrelated section ").append(i).append('\n')
                    .append("A different topic provides background only. ".repeat(18)).append("\n\n");
        }
        full.append("## AgentRewind state rollback\n")
                .append("The saved checkpoint restores the external state and injects rewind memories.\n")
                .append("The context and environment resume together from the saved checkpoint.");
        assertTrue(full.indexOf("saved checkpoint") > 20_000);
        WikiMaterialSampler.Material material = WikiMaterialSampler.sample("AgentRewind", List.of(),
                List.of(source("file", 6, "Collected papers", full.toString())));
        assertFalse(material.chunks().isEmpty());
        assertTrue(material.prompt().contains("saved checkpoint"));
        assertFalse(material.prompt().contains("A different topic"));
    }

    @Test
    void matchingDocumentTitleStillSamplesLateMechanismAndDifferentSections() {
        StringBuilder full = new StringBuilder("# AgentRewind\nA method for rewind and recovery.\n");
        for (int i = 0; i < 35; i++) {
            full.append("## Background ").append(i).append('\n')
                    .append("General learning systems perform iterative evaluation. ".repeat(17)).append("\n");
        }
        full.append("## State rollback mechanism\n")
                .append("Restore state s_k and inject saved memory M into context c_k.");
        assertTrue(full.indexOf("Restore state") > 20_000);
        WikiMaterialSampler.Material material = WikiMaterialSampler.sample("AgentRewind", List.of(),
                List.of(source("file", 6, "AgentRewind paper.pdf", full.toString())));
        assertTrue(material.prompt().contains("Restore state s_k"));
        assertTrue(material.chunks().stream().map(WikiMaterialSampler.Chunk::heading).distinct().count() >= 4);
        assertEquals(6, material.chunks().size());
        assertTrue(material.chunks().stream().anyMatch(c -> c.seq() > 30));
    }

    @Test
    void excludesAuthorsReferencesAndTitleOnlyChunksWithoutLosingActualBody() {
        String full = """
                # AgentRewind
                AgentRewind
                Authors: A. Writer, B. Writer
                Email: writer@example.org
                ## Authors
                AgentRewind authors work at Example University.
                ## State rollback mechanism
                AgentRewind restores external state and recorded context from a checkpoint.
                ## References
                AgentRewind appears as a bibliography entry.
                """;
        WikiMaterialSampler.Material material = WikiMaterialSampler.sample("AgentRewind", List.of(),
                List.of(source("file", 6, "AgentRewind", full)));
        assertEquals(1, material.chunks().size());
        assertEquals("AgentRewind > State rollback mechanism", material.chunks().getFirst().heading());
        assertFalse(material.prompt().contains("writer@example.org"));
        assertFalse(material.prompt().contains("bibliography entry"));
        assertTrue(material.prompt().contains("restores external state"));
    }

    @Test
    void metadataOnlySourceDoesNotProduceEvidenceOrDependency() {
        WikiMaterialSampler.Material material = WikiMaterialSampler.sample("MQTT", List.of(),
                List.of(source("file", 8, "MQTT", "MQTT\nAuthors: Author One\nDOI: 10.1/example")));
        assertEquals("", material.prompt());
        assertTrue(material.chunks().isEmpty());
        assertTrue(material.sourceHashes().isEmpty());
    }

    @Test
    void conceptNameOrMarkdownHeadingAloneIsNotSubstantiveEvidence() {
        WikiMaterialSampler.Material material = WikiMaterialSampler.sample("MQTT", List.of(),
                List.of(source("file", 1, "MQTT.pdf", "# MQTT"),
                        source("note", 2, "General notes", "MQTT"),
                        source("file", 3, "Collected title records", "## 标题与作者\nMQTT protocol paper authors."),
                        source("file", 4, "References", "## 7. References\nMQTT protocol paper citation.")));
        assertTrue(material.chunks().isEmpty());
        assertTrue(material.sourceHashes().isEmpty());
    }

    @Test
    void selectedChunkTextAndSeqExactlyMatchSharedChunker() {
        String full = "# Tool\r\n## Mechanism\r\nTool executes this sequence.\r\n\r\n"
                + "Another paragraph has exact punctuation, $$x_t$$ and [citation]. ".repeat(50);
        List<TextChunker.Chunk> originals = TextChunker.splitWithHeadings(full);
        WikiMaterialSampler.Material material = WikiMaterialSampler.sample("Tool", List.of(),
                List.of(source("note", 4, "Tool", full)));
        for (WikiMaterialSampler.Chunk chunk : material.chunks()) {
            assertEquals(originals.get(chunk.seq()).text(), chunk.chunkText());
            assertEquals(originals.get(chunk.seq()).heading(), chunk.heading());
            assertEquals("note:4:" + chunk.seq(), chunk.key());
            assertEquals(WikiMaterialSampler.sha256(full), chunk.fullContentHash());
            assertTrue(material.prompt().contains(chunk.chunkText()));
        }
        assertEquals(Map.of("note:4", WikiMaterialSampler.sha256(full)), material.sourceHashes());
    }

    @Test
    void fullContentHashIncludesUnselectedTailAndOriginalNewlines() {
        String full = numberedSections(30, "Body text for testing uniform samples. ".repeat(10));
        WikiMaterialSampler.Material first = WikiMaterialSampler.sample("", List.of(),
                List.of(source("file", 1, "Large book", full)));
        // 均匀采样不会送第二小节；全文依赖仍必须感知这段变化。
        assertFalse(first.chunks().stream().anyMatch(c -> c.heading().equals("Section 1")));
        String changed = full.replace("## Section 1\n", "## Section 1\nNew unsampled sentence.\n");
        WikiMaterialSampler.Material second = WikiMaterialSampler.sample("", List.of(),
                List.of(source("file", 1, "Large book", changed)));
        assertNotEquals(first.sourceHashes(), second.sourceHashes());
        assertNotEquals(WikiMaterialSampler.sha256("line\r\n"), WikiMaterialSampler.sha256("line\n"));
        assertEquals(64, first.chunks().getFirst().fullContentHash().length());
    }

    @Test
    void materialBudgetCountsHeadersAndKeepsWholeChunks() {
        List<WikiMaterialSampler.Source> sources = new ArrayList<>();
        String body = numberedSections(15, "A substantive sentence about the full source content. ".repeat(20));
        for (long i = 1; i <= 25; i++) {
            sources.add(source("file", i, "Source title with metadata ".repeat(15), body));
        }
        WikiMaterialSampler.Material material = WikiMaterialSampler.sample("", List.of(), sources);
        assertTrue(material.chars() <= WikiMaterialSampler.MAX_MATERIAL_CHARS);
        assertEquals(material.prompt().length(), material.chars());
        assertTrue(material.chars() > 20_000);
        assertTrue(material.chunks().stream().mapToInt(c -> c.chunkText().length()).sum() < material.chars());
        List<TextChunker.Chunk> originals = TextChunker.splitWithHeadings(body);
        for (WikiMaterialSampler.Chunk chunk : material.chunks()) {
            assertEquals(originals.get(chunk.seq()).text(), chunk.chunkText());
        }
        assertEquals(new HashSet<>(material.chunks().stream().map(WikiMaterialSampler.Chunk::sourceKey).toList()),
                material.sourceHashes().keySet());
    }

    @Test
    void callerBudgetIsStrictAndCannotExceedHardMaximum() {
        String body = numberedSections(25, "A complete source paragraph with substantive contents. ".repeat(20));
        List<WikiMaterialSampler.Source> sources = List.of(source("note", 1, "First", body),
                source("note", 2, "Second", body), source("file", 3, "Third", body),
                source("file", 4, "Fourth", body), source("file", 5, "Fifth", body));
        WikiMaterialSampler.Material limited = WikiMaterialSampler.sample("", List.of(), sources, 1200);
        assertTrue(limited.chars() <= 1200);
        assertEquals(1, limited.chunks().size());
        assertEquals(TextChunker.splitWithHeadings(body).get(limited.chunks().getFirst().seq()).text(),
                limited.chunks().getFirst().chunkText());
        assertTrue(WikiMaterialSampler.sample("", List.of(), sources, Integer.MAX_VALUE).chars() <= 24_000);
        assertEquals(0, WikiMaterialSampler.sample("", List.of(), sources, -1).chars());
    }

    @Test
    void budgetDoesNotCutLongSourceTitleIntoFakeLocator() {
        WikiMaterialSampler.Material material = WikiMaterialSampler.sample("", List.of(),
                List.of(source("note", 1, "Title ".repeat(5000), "Substantive original content."),
                        source("note", 2, "Small title", "Another complete original paragraph.")));
        assertEquals(1, material.chunks().size());
        assertEquals(2L, material.chunks().getFirst().sourceId());
        assertFalse(material.sourceHashes().containsKey("note:1"));
        assertTrue(material.prompt().contains("《Small title》"));
    }

    @Test
    void perSourceLimitAndRoundRobinKeepSmallSourcesVisible() {
        String body = numberedSections(20, "This section covers a complete practical topic. ".repeat(10));
        WikiMaterialSampler.Material material = WikiMaterialSampler.sample("", List.of(),
                List.of(source("file", 1, "Long source", body),
                        source("note", 2, "Small source", "The second source has an independent useful sentence.")));
        assertEquals(6, material.chunks().stream().filter(c -> c.sourceId() == 1).count());
        assertEquals("file:1", material.chunks().get(0).sourceKey());
        assertEquals("note:2", material.chunks().get(1).sourceKey());
        assertTrue(material.prompt().contains("independent useful sentence"));
    }

    @Test
    void genericScopeFallbackCoversFullBookIncludingEnd() {
        String full = numberedSections(30, "This is a meaningful chapter in the book. ".repeat(10));
        WikiMaterialSampler.Material material = WikiMaterialSampler.sample("", List.of(),
                List.of(source("file", 1, "A book", full)));
        assertEquals(6, material.chunks().size());
        assertEquals(0, material.chunks().getFirst().seq());
        assertEquals(29, material.chunks().getLast().seq());
        assertTrue(material.chunks().stream().anyMatch(c -> c.seq() >= 10 && c.seq() <= 20));
    }

    @Test
    void namedSourceFilteringNeverFallsBackToUnrelatedDocuments() {
        WikiMaterialSampler.Material material = WikiMaterialSampler.sample("MQTT", List.of(),
                List.of(source("note", 1, "Spring Boot", "Spring Boot 是什么以及如何运行。"),
                        source("note", 2, "MQTT notes", "MQTT 使用发布与订阅的消息机制。")));
        assertEquals(1, material.chunks().size());
        assertEquals("note:2", material.chunks().getFirst().sourceKey());
        assertFalse(material.prompt().contains("Spring Boot"));
        assertEquals(1, material.sourceHashes().size());
    }

    @Test
    void aliasesUseExactIdentifierBoundariesAndSupportChinese() {
        WikiMaterialSampler.Material material = WikiMaterialSampler.sample("消息队列遥测传输", List.of("MQTT"),
                List.of(source("note", 1, "协议记录", "MQTT 使用发布订阅。"),
                        source("note", 2, "其他协议", "MQTTX 是另一个客户端工具。"),
                        source("note", 3, "中文协议", "消息队列遥测传输用于轻量消息通信。")));
        assertEquals(List.of("note:1", "note:3"), material.chunks().stream()
                .map(WikiMaterialSampler.Chunk::sourceKey).toList());
        WikiMaterialSampler.Material agent = WikiMaterialSampler.sample("Agent", List.of(),
                List.of(source("file", 4, "AgentRewind", "AgentRewind rolls back state.")));
        assertTrue(agent.chunks().isEmpty());
    }

    @Test
    void codeFenceDoesNotCreateInventedHeadingOrChangeSeq() {
        String full = """
                # MQTT
                ## 使用示例
                ```bash
                # author example
                mqtt-client --topic demo
                ```
                示例通过发布订阅传递消息。
                """;
        WikiMaterialSampler.Material material = WikiMaterialSampler.sample("MQTT", List.of(),
                List.of(source("note", 5, "MQTT", full)));
        assertEquals(1, material.chunks().size());
        assertEquals("MQTT > 使用示例", material.chunks().getFirst().heading());
        assertTrue(material.prompt().contains("# author example"));
        assertEquals(0, material.chunks().getFirst().seq());
    }

    @Test
    void identicalDuplicateSourcesAreDeduplicatedButConflictingSnapshotsFail() {
        WikiMaterialSampler.Source source = source("note", 1, "Topic", "A substantive complete sentence.");
        WikiMaterialSampler.Material material = WikiMaterialSampler.sample("", List.of(), List.of(source, source));
        assertEquals(1, material.chunks().size());
        assertThrows(IllegalArgumentException.class, () -> WikiMaterialSampler.sample("", List.of(),
                List.of(source, source("note", 1, "Topic", "A different current version."))));
    }

    @Test
    void invalidEmptySourcesAreIgnoredAndResultCollectionsAreImmutable() {
        List<WikiMaterialSampler.Source> sources = new ArrayList<>();
        sources.add(null);
        sources.add(source(null, 1, "Invalid", "Body"));
        sources.add(source("wiki", 1, "Generated guide", "Body"));
        sources.add(source("note", 0, "Invalid id", "Body"));
        sources.add(source("note", 2, "Empty", " "));
        sources.add(source("quick_ref", 3, "Usable", "Real original reference content."));
        WikiMaterialSampler.Material material = WikiMaterialSampler.sample(null, null, sources);
        assertEquals(1, material.chunks().size());
        assertThrows(UnsupportedOperationException.class, () -> material.chunks().clear());
        assertThrows(UnsupportedOperationException.class, () -> material.sourceHashes().clear());
        assertEquals(0, WikiMaterialSampler.sample(null, null, null).chars());
    }

    private static WikiMaterialSampler.Source source(String type, long id, String title, String full) {
        return new WikiMaterialSampler.Source(type, id, title, full);
    }

    private static String numberedSections(int count, String body) {
        StringBuilder full = new StringBuilder();
        for (int i = 0; i < count; i++) full.append("## Section ").append(i).append('\n').append(body).append('\n');
        return full.toString();
    }
}
