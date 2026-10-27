package org.dyh.learnhub.service;

import org.dyh.learnhub.ai.AgentService;
import org.dyh.learnhub.service.rerank.QueryAwareExcerpt;

import java.util.ArrayList;
import java.util.List;
import java.util.TreeSet;

/** A hard context budget must omit an oversized atomic block rather than send half its syntax. */
public final class StructureAwareExcerpt {
    private StructureAwareExcerpt() { }
    private record Range(int start, int end) { }

    public static String excerpt(String question, String text, int limit) {
        if (text == null || limit <= 0) return "";
        if (text.length() <= limit) return text;
        List<Range> protectedRanges = new ArrayList<>();
        int offset = 0;
        for (TextChunker.Unit unit : TextChunker.structuralUnits(text, TextChunker.Options.defaults())) {
            int start = text.indexOf(unit.text(), offset);
            if (start < 0) continue;
            offset = start + unit.text().length();
            if (unit.protectedBlock()) protectedRanges.add(new Range(start, offset));
        }
        String preferred = QueryAwareExcerpt.excerpt(question, text, limit);
        if (protectedRanges.isEmpty()) return preferred;
        int anchor = Math.max(0, text.indexOf(preferred));
        TreeSet<Integer> starts = new TreeSet<>();
        starts.add(0);
        starts.add(anchor);
        for (Range range : protectedRanges) {
            starts.add(range.start());
            starts.add(range.end());
            starts.add(Math.max(0, range.end() - limit));
        }
        List<String> terms = AgentService.retrievalTerms(question == null ? "" : question);
        String best = "";
        int bestScore = -1;
        int bestDistance = Integer.MAX_VALUE;
        for (int candidate : starts) {
            int start = candidate;
            for (Range range : protectedRanges)
                if (start > range.start() && start < range.end()) start = range.end();
            int end = Math.min(text.length(), start + limit);
            for (Range range : protectedRanges)
                if (end > range.start() && end < range.end()) { end = range.start(); break; }
            if (end <= start) continue;
            if (start < text.length() && Character.isLowSurrogate(text.charAt(start))) start++;
            if (end < text.length() && end > start && Character.isHighSurrogate(text.charAt(end - 1))) end--;
            String body = text.substring(start, end).strip();
            if (body.isEmpty()) continue;
            int score = AgentService.score(terms, "", body);
            int distance = Math.abs(start - anchor);
            if (score > bestScore || score == bestScore && distance < bestDistance) {
                best = body;
                bestScore = score;
                bestDistance = distance;
            }
        }
        return best;
    }
}
