package org.dyh.learnhub.service.rerank;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Selects a bounded, contiguous source excerpt around the user's question terms. */
public final class QueryAwareExcerpt {
    private static final Pattern WORD = Pattern.compile("[A-Za-z][A-Za-z0-9_$]*");
    private static final Pattern HAN = Pattern.compile("[\\p{IsHan}]{2,}");
    private static final Set<String> STOP_WORDS = Set.of("a", "an", "the", "is", "are", "was", "were",
            "of", "to", "in", "on", "and", "or", "for", "with", "how", "what", "which", "why", "can",
            "does", "do", "please", "tell", "me", "it", "this", "that", "from", "by", "as", "about");
    private static final Set<String> STOP_HAN = Set.of("什么", "怎么", "如何", "哪些", "请问", "说明",
            "介绍", "解释", "一下", "一个", "这个", "这种", "是否", "可以", "需要", "使用", "问题",
            "根据", "资料", "知识", "笔记", "给出", "提供", "中的", "以及", "之间", "分别", "具体");
    private static final int MAX_TERMS = 128;
    private static final int MAX_MATCHES = 768;

    private QueryAwareExcerpt() { }

    /**
     * Returns an exact substring of {@code text}, preserving code and newlines. With no matching
     * question terms the prefix is retained. No generated paraphrase is added to the evidence.
     */
    public static String excerpt(String question, String text, int limit) {
        if (text == null || text.isEmpty() || limit <= 0) return "";
        if (text.length() <= limit) return text;
        List<Term> terms = terms(question);
        List<Match> matches = matches(text, terms);
        if (matches.isEmpty()) return slice(text, 0, limit);

        // Candidate windows include surrounding explanation, not just the matched identifier.
        TreeSet<Integer> starts = new TreeSet<>();
        starts.add(0);
        for (Match match : matches) {
            starts.add(clamp(match.start() - limit / 3, text.length(), limit));
            starts.add(clamp(match.start(), text.length(), limit));
            starts.add(clamp(match.end() - limit, text.length(), limit));
        }
        int bestStart = 0;
        double bestScore = -1;
        double bestContextCost = Double.MAX_VALUE;
        for (int start : starts) {
            double score = score(matches, start, start + limit);
            double contextCost = contextCost(matches, start, start + limit);
            if (score > bestScore || score == bestScore && contextCost < bestContextCost) {
                bestScore = score;
                bestContextCost = contextCost;
                bestStart = start;
            }
        }

        // Prefer a nearby natural boundary only if it preserves all of the selected matches.
        int lower = Math.max(0, bestStart - Math.min(24, limit / 5));
        for (int start = bestStart; start >= lower; start--) {
            if ((start == 0 || boundary(text.charAt(start - 1)))
                    && score(matches, start, start + limit) >= bestScore
                    && contextCost(matches, start, start + limit) <= bestContextCost) {
                bestStart = start;
                break;
            }
        }
        return slice(text, bestStart, limit);
    }

    /** Keeps the section label and question-centered body inside the same listwise character budget. */
    public static String rerankExcerpt(String question, String text, int limit) {
        if (text == null || text.isBlank() || limit <= 0) return "";
        String raw = text.trim();
        int newline = raw.indexOf('\n');
        if (raw.startsWith("小节：") && newline > 0) {
            String heading = compact(raw.substring(3, newline));
            String body = compact(raw.substring(newline + 1));
            if (!heading.isEmpty() && !body.isEmpty() && limit >= 16) {
                int headingLimit = Math.min(40, limit / 3);
                String prefix = "小节：" + excerpt(question, heading, headingLimit) + "｜";
                return prefix + excerpt(question, body, limit - prefix.length());
            }
        }
        return excerpt(question, compact(raw), limit);
    }

    private static String compact(String text) {
        return text.replaceAll("\\s+", " ").trim();
    }

    private static List<Term> terms(String question) {
        if (question == null || question.isBlank()) return List.of();
        Map<String, Term> terms = new LinkedHashMap<>();
        Matcher words = WORD.matcher(question);
        while (words.find()) {
            String word = words.group().toLowerCase(Locale.ROOT);
            if (word.length() >= 2 && !STOP_WORDS.contains(word)) {
                terms.putIfAbsent(word, new Term(word, 6 + Math.min(24, word.length()) * .5, true));
            }
        }
        Matcher chinese = HAN.matcher(question);
        while (chinese.find()) {
            String phrase = chinese.group();
            for (int size = Math.min(4, phrase.length()); size >= 2; size--) {
                for (int start = 0; start + size <= phrase.length(); start++) {
                    String term = phrase.substring(start, start + size);
                    if (!STOP_HAN.contains(term)) {
                        terms.putIfAbsent(term, new Term(term, size * .5, false));
                    }
                }
            }
        }
        return terms.values().stream().sorted(Comparator.comparingDouble(Term::weight).reversed())
                .limit(MAX_TERMS).toList();
    }

    private static List<Match> matches(String text, List<Term> terms) {
        List<Match> result = new ArrayList<>();
        for (int id = 0; id < terms.size(); id++) {
            Term term = terms.get(id);
            String expression = Pattern.quote(term.text());
            if (term.word()) expression = "(?<![A-Za-z0-9_$])" + expression + "(?![A-Za-z0-9_$])";
            Matcher matcher = Pattern.compile(expression, Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE).matcher(text);
            int count = 0;
            while (matcher.find() && count++ < 24 && result.size() < MAX_MATCHES) {
                result.add(new Match(matcher.start(), matcher.end(), id, term.weight()));
            }
            if (result.size() >= MAX_MATCHES) break;
        }
        return result;
    }

    private static double score(List<Match> matches, int start, int end) {
        // Repetition of a query word cannot beat a window containing several requested concepts.
        Map<Integer, Double> distinct = new LinkedHashMap<>();
        for (Match match : matches) {
            if (match.start() >= start && match.end() <= end) {
                distinct.putIfAbsent(match.term(), match.weight());
            }
        }
        return distinct.values().stream().mapToDouble(Double::doubleValue).sum();
    }

    private static double contextCost(List<Match> matches, int start, int end) {
        // Equal keyword coverage should keep explanation/code after the match. An earliest-window
        // tie would put a late topic label at the final character and cut its following example.
        double target = start + (end - start) / 3.0;
        Map<Integer, Double> distance = new LinkedHashMap<>();
        for (Match match : matches) {
            if (match.start() >= start && match.end() <= end) {
                double value = Math.abs((match.start() + match.end()) / 2.0 - target);
                distance.merge(match.term(), value, Math::min);
            }
        }
        return distance.values().stream().mapToDouble(Double::doubleValue).sum();
    }

    private static int clamp(int start, int length, int limit) {
        return Math.max(0, Math.min(start, length - limit));
    }

    private static boolean boundary(char ch) {
        return Character.isWhitespace(ch) || ch == '。' || ch == '；' || ch == ';' || ch == '：' || ch == ':';
    }

    private static String slice(String text, int start, int limit) {
        int end = Math.min(text.length(), start + limit);
        if (start > 0 && Character.isLowSurrogate(text.charAt(start))) start++;
        if (end < text.length() && end > start && Character.isHighSurrogate(text.charAt(end - 1))) end--;
        return text.substring(start, end);
    }

    private record Term(String text, double weight, boolean word) { }
    private record Match(int start, int end, int term, double weight) { }
}
