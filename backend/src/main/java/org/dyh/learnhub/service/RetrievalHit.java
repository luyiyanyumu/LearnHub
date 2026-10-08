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
                           List<String> channels, List<String> graphRelations) {
    public RetrievalHit {
        channels = channels == null ? List.of() : List.copyOf(channels);
        graphRelations = graphRelations == null ? List.of() : List.copyOf(graphRelations);
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
