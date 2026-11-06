package org.dyh.learnhub.service;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;

/** A source passage shared by keyword, vector and graph retrieval. Score is a ranking score, not confidence. */
public record RetrievalHit(String sourceType, Long sourceId, String title, String category,
                           String text, double score, Integer seq,
                           List<String> channels, List<String> graphRelations,
                           @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_EMPTY)
                           List<DocumentExtractionStore.SourceLocation> sourceLocations) {
    public RetrievalHit {
        channels = channels == null ? List.of() : List.copyOf(channels);
        graphRelations = graphRelations == null ? List.of() : List.copyOf(graphRelations);
        sourceLocations = sourceLocations == null ? List.of() : List.copyOf(sourceLocations);
    }

    public RetrievalHit(String sourceType, Long sourceId, String title, String category, String text, double score,
                        Integer seq, List<String> channels, List<String> graphRelations) {
        this(sourceType, sourceId, title, category, text, score, seq, channels, graphRelations, List.of());
    }

    public RetrievalHit withSourceLocations(List<DocumentExtractionStore.SourceLocation> locations) {
        return new RetrievalHit(sourceType, sourceId, title, category, text, score, seq, channels, graphRelations, locations);
    }

    @JsonProperty("passageKey")
    public String key() {
        return sourceType + ":" + sourceId + ":" + (seq == null || seq < 0 ? "window:" + digest(text) : seq);
    }

    public String sourceRef() { return sourceType + ":" + sourceId; }

    private static String digest(String text) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest((text == null ? "" : text).getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
