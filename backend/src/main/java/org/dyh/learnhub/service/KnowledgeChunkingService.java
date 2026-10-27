package org.dyh.learnhub.service;

import lombok.RequiredArgsConstructor;
import org.dyh.learnhub.ai.EmbeddingClient;
import org.springframework.stereotype.Service;

import java.util.List;

/** Index-time only: optional local embedding boundaries for long uploaded documents. */
@Service
@RequiredArgsConstructor
public class KnowledgeChunkingService {
    private final EmbeddingClient embedder;

    public List<TextChunker.Chunk> split(String sourceType, String text, EmbeddingClient.Snapshot target,
                                        KnowledgeBaseSettings.Config config) {
        if (!"semantic_long".equals(config.chunkStrategy()) || !"file".equals(sourceType)
                || text == null || text.length() < config.semanticMinChars())
            return TextChunker.splitWithHeadings(text, config.chunkOptions());
        List<TextChunker.Unit> units = TextChunker.structuralUnits(text, config.chunkOptions());
        List<String> prose = units.stream().filter(unit -> !unit.protectedBlock())
                .map(TextChunker.Unit::text).toList();
        if (prose.size() < 2) return TextChunker.splitWithHeadings(text, config.chunkOptions());
        // A failed boundary call aborts preparation; the previous committed index remains intact.
        List<float[]> vectors = embedder.embedAll(target, prose, config.embedBatchSize(), config.embeddingInputChars());
        return SemanticChunker.split(units, config.chunkOptions(), vectors, config.semanticBreakpointPercentile());
    }
}
