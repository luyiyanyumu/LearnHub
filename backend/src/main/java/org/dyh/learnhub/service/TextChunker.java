package org.dyh.learnhub.service;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Structure-aware pure splitter. Atomic code, display math and tables retain their complete syntax. */
public final class TextChunker {
    private TextChunker() { }
    public static final int TARGET = 800;
    /** Character budget; an atomic block is never partially copied for overlap. */
    public static final int OVERLAP = 120;
    /** Hard limit for ordinary text. A single atomic block may exceed it without being broken. */
    public static final int MAX = 1200;

    public record Options(int target, int overlap, int max) {
        public Options {
            if (target < 1 || overlap < 0 || overlap >= target || target > max)
                throw new IllegalArgumentException("分片须满足 0 ≤ overlap < target ≤ max");
        }
        public static Options defaults() { return new Options(TARGET, OVERLAP, MAX); }
    }
    private static final Pattern HEADING = Pattern.compile("^(#{1,6})\\s*(.+?)\\s*#*$");
    private static final Pattern FENCE = Pattern.compile("^\\s*(`{3,}|~{3,})(.*)$");
    private static final Pattern MATH_ENV = Pattern.compile(
            "\\\\begin\\{((?:equation|align|alignat|aligned|gather|gathered|multline|flalign|displaymath|eqnarray|split|cases)\\*?)\\}");
    private static final Pattern TABLE_SEPARATOR = Pattern.compile(
            "^\\s*\\|?\\s*:?-{3,}:?\\s*(?:\\|\\s*:?-{3,}:?\\s*)*\\|?\\s*$");
    private static final Pattern HTML_TABLE_OPEN = Pattern.compile("<table(?:\\s|>)", Pattern.CASE_INSENSITIVE);
    private static final Pattern HTML_TABLE_CLOSE = Pattern.compile("</table\\s*>", Pattern.CASE_INSENSITIVE);
    private static final Pattern HTML_TAG = Pattern.compile("<[^>]{1,200}>");
    private static final Pattern MD_EMPHASIS = Pattern.compile("[*_`~]");

    public static String cleanHeading(String raw) {
        if (raw == null || raw.isBlank()) return "";
        String s = HTML_TAG.matcher(raw).replaceAll("");
        s = MD_EMPHASIS.matcher(s).replaceAll("");
        return s.replaceAll("[\\s\\u00a0]+", " ").trim();
    }
    public record Chunk(String heading, String text) { }
    /** Natural paragraphs/sentence groups or complete protected blocks, in document order. */
    public record Unit(String heading, String text, boolean protectedBlock) { }

    public static List<Chunk> splitWithHeadings(String text) {
        return splitWithHeadings(text, Options.defaults());
    }
    public static List<Chunk> splitWithHeadings(String text, Options options) {
        Options effective = options == null ? Options.defaults() : options;
        List<Chunk> out = new ArrayList<>();
        List<Unit> current = new ArrayList<>();
        String heading = null;
        // Semantic boundary detection needs small local units; ordinary structure chunking
        // must not inherit that granularity and split an otherwise complete sentence.
        for (Unit unit : structureUnits(text, effective)) {
            if (heading != null && !heading.equals(unit.heading())) {
                emit(out, heading, current);
                current.clear();
            }
            heading = unit.heading();
            if (unit.protectedBlock() && unit.text().length() > effective.max()) {
                emit(out, heading, current);
                current.clear();
                out.add(new Chunk(heading, unit.text()));
                continue;
            }
            int length = joinedLength(current);
            int combined = length + 2 + unit.text().length();
            boolean closerAfterAdding = Math.abs(combined - effective.target()) <= Math.abs(length - effective.target());
            if (!current.isEmpty() && (combined > effective.max()
                    || combined > effective.target() && !closerAfterAdding)) {
                emit(out, heading, current);
                List<Unit> overlap = overlapUnits(current, effective.overlap());
                current.clear();
                // Never emit an overlap-only duplicate because the next atomic block is large.
                if (!overlap.isEmpty() && joinedLength(overlap) + 2 + unit.text().length() <= effective.max())
                    current.addAll(overlap);
            }
            current.add(unit);
        }
        emit(out, heading, current);
        return List.copyOf(out);
    }
    private static List<Unit> structureUnits(String text, Options options) {
        List<Unit> units = new ArrayList<>();
        for (Unit block : scanBlocks(text)) {
            if (block.protectedBlock() || block.text().length() <= options.max()) {
                units.add(block);
                continue;
            }
            // Extract complete sentences from long PDF/plain-text paragraphs. A line wrap
            // is not a semantic boundary; retain it inside the original sentence.
            int start = 0;
            for (int i = 0; i < block.text().length(); i++) {
                if (!proseSentenceEnd(block.text(), i)) continue;
                addOrdinary(units, block.heading(), block.text().substring(start, i + 1), options.max());
                start = i + 1;
            }
            addOrdinary(units, block.heading(), block.text().substring(start), options.max());
        }
        return units;
    }
    private static void addOrdinary(List<Unit> units, String heading, String text, int max) {
        String rest = text.strip();
        int start = 0;
        while (rest.length() - start > max) {
            int cut = start + max;
            // Only an individual sentence exceeding the hard limit needs a fallback cut.
            // Prefer a word boundary without turning a short prefix into its own chunk.
            for (int i = cut; i >= start + max / 2; i--) {
                if (Character.isWhitespace(rest.charAt(i))) { cut = i; break; }
            }
            if (Character.isHighSurrogate(rest.charAt(cut - 1)) && Character.isLowSurrogate(rest.charAt(cut))) cut--;
            if (cut == start) cut = Math.min(rest.length(), start + 2);
            units.add(new Unit(heading, rest.substring(start, cut).strip(), false));
            start = cut;
            while (start < rest.length() && Character.isWhitespace(rest.charAt(start))) start++;
        }
        if (start < rest.length()) units.add(new Unit(heading, rest.substring(start), false));
    }
    /**
     * Normalize line endings, retain protected block contents, and subdivide long ordinary paragraphs.
     * Sentence groups use a smaller budget so optional semantic aggregation can compare local topics.
     */
    public static List<Unit> structuralUnits(String text, Options options) {
        Options effective = options == null ? Options.defaults() : options;
        int budget = Math.min(effective.max(), Math.max(100, effective.target() / 3));
        List<Unit> units = new ArrayList<>();
        for (Unit block : scanBlocks(text)) {
            if (block.protectedBlock()) units.add(block);
            else for (String piece : sentenceGroups(block.text(), budget))
                units.add(new Unit(block.heading(), piece, false));
        }
        return List.copyOf(units);
    }
    /** @deprecated Prefer the version preserving heading context. */
    @Deprecated
    public static List<String> split(String text) {
        return splitWithHeadings(text).stream().map(Chunk::text).toList();
    }
    public static String embedText(String docTitle, String heading, String body) {
        StringBuilder sb = new StringBuilder();
        if (docTitle != null && !docTitle.isBlank()) sb.append(docTitle.trim());
        if (heading != null && !heading.isBlank()) {
            if (!sb.isEmpty()) sb.append(" · ");
            sb.append(heading.trim());
        }
        if (sb.isEmpty()) return body == null ? "" : body;
        return sb.append('\n').append(body == null ? "" : body).toString();
    }

    private static List<Unit> scanBlocks(String text) {
        List<Unit> blocks = new ArrayList<>();
        if (text == null || text.isBlank()) return blocks;
        String[] lines = text.replace("\r\n", "\n").replace('\r', '\n').split("\n", -1);
        String[] headings = new String[6];
        String heading = "";
        StringBuilder paragraph = new StringBuilder();
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];
            int blockEnd = protectedEnd(lines, i);
            if (blockEnd >= i) {
                flushParagraph(blocks, paragraph, heading);
                blocks.add(new Unit(heading, joinLines(lines, i, blockEnd), true));
                i = blockEnd;
                continue;
            }
            Matcher title = HEADING.matcher(line.strip());
            if (title.matches()) {
                flushParagraph(blocks, paragraph, heading);
                String clean = cleanHeading(title.group(2));
                if (clean.isEmpty()) continue;
                int level = title.group(1).length() - 1;
                for (int j = level; j < headings.length; j++) headings[j] = null;
                headings[level] = clean;
                List<String> path = new ArrayList<>();
                for (String part : headings) if (part != null) path.add(part);
                heading = String.join(" > ", path);
            } else if (line.isBlank()) {
                flushParagraph(blocks, paragraph, heading);
            } else {
                if (!paragraph.isEmpty()) paragraph.append('\n');
                paragraph.append(line);
            }
        }
        flushParagraph(blocks, paragraph, heading);
        return blocks;
    }
    private static int protectedEnd(String[] lines, int start) {
        String line = lines[start];
        Matcher fence = FENCE.matcher(line);
        if (fence.matches()) {
            String marker = fence.group(1);
            char type = marker.charAt(0);
            for (int i = start + 1; i < lines.length; i++) {
                String candidate = lines[i].strip();
                int n = 0;
                while (n < candidate.length() && candidate.charAt(n) == type) n++;
                if (n >= marker.length() && candidate.substring(n).isBlank()) return i;
            }
            // Drafts with an unfinished fence must not turn code comments into headings.
            return lines.length - 1;
        }
        // Recognize the outer container before math syntax inside its caption/header cells.
        if (HTML_TABLE_OPEN.matcher(line).find()) {
            int depth = 0;
            for (int i = start; i < lines.length; i++) {
                depth += matchCount(HTML_TABLE_OPEN, lines[i]) - matchCount(HTML_TABLE_CLOSE, lines[i]);
                if (depth <= 0) return i;
            }
            return lines.length - 1;
        }
        if (start + 1 < lines.length && hasPipe(line) && TABLE_SEPARATOR.matcher(lines[start + 1]).matches()) {
            int end = start + 1;
            while (end + 1 < lines.length && !lines[end + 1].isBlank() && hasPipe(lines[end + 1])) end++;
            return end;
        }
        int dollars = unescapedIndex(line, "$$", 0);
        if (dollars >= 0) return delimitedEnd(lines, start, "$$", dollars + 2);
        int bracket = unescapedIndex(line, "\\[", 0);
        if (bracket >= 0) return delimitedEnd(lines, start, "\\]", bracket + 2);
        Matcher environment = MATH_ENV.matcher(line);
        if (environment.find()) {
            String open = "\\begin{" + environment.group(1) + "}";
            String close = "\\end{" + environment.group(1) + "}";
            int depth = 0;
            for (int i = start; i < lines.length; i++) {
                depth += occurrences(lines[i], open) - occurrences(lines[i], close);
                if (depth <= 0) return i;
            }
            return lines.length - 1;
        }
        return -1;
    }
    private static int delimitedEnd(String[] lines, int start, String close, int firstOffset) {
        for (int i = start; i < lines.length; i++)
            if (unescapedIndex(lines[i], close, i == start ? firstOffset : 0) >= 0) return i;
        return lines.length - 1;
    }
    private static int unescapedIndex(String text, String marker, int from) {
        for (int pos = text.indexOf(marker, from); pos >= 0; pos = text.indexOf(marker, pos + marker.length())) {
            int escapes = 0;
            for (int i = pos - 1; i >= 0 && text.charAt(i) == '\\'; i--) escapes++;
            if (escapes % 2 == 0) return pos;
        }
        return -1;
    }
    private static boolean hasPipe(String line) { return unescapedIndex(line, "|", 0) >= 0; }
    private static int occurrences(String text, String token) {
        int count = 0;
        for (int pos = text.indexOf(token); pos >= 0; pos = text.indexOf(token, pos + token.length())) count++;
        return count;
    }
    private static int matchCount(Pattern pattern, String text) {
        int count = 0;
        Matcher matcher = pattern.matcher(text);
        while (matcher.find()) count++;
        return count;
    }
    private static String joinLines(String[] lines, int start, int end) {
        StringBuilder text = new StringBuilder();
        for (int i = start; i <= end; i++) {
            if (i > start) text.append('\n');
            text.append(lines[i]);
        }
        return text.toString();
    }
    private static void flushParagraph(List<Unit> blocks, StringBuilder paragraph, String heading) {
        if (!paragraph.isEmpty()) {
            blocks.add(new Unit(heading, paragraph.toString().strip(), false));
            paragraph.setLength(0);
        }
    }
    private static List<String> sentenceGroups(String text, int budget) {
        List<String> pieces = new ArrayList<>();
        int start = 0;
        while (start < text.length()) {
            int limit = Math.min(text.length(), start + budget);
            int cut = limit;
            if (limit < text.length()) {
                int boundary = -1;
                for (int i = start; i < limit; i++) if (sentenceEnd(text, i)) boundary = i + 1;
                if (boundary > start) cut = boundary;
                if (cut > start && Character.isHighSurrogate(text.charAt(cut - 1))
                        && Character.isLowSurrogate(text.charAt(cut))) cut--;
                if (cut == start) cut = Math.min(text.length(), start + 2);
            }
            String piece = text.substring(start, cut).strip();
            if (!piece.isEmpty()) pieces.add(piece);
            start = cut;
            while (start < text.length() && Character.isWhitespace(text.charAt(start))) start++;
        }
        return pieces;
    }
    private static boolean sentenceEnd(String text, int i) {
        char c = text.charAt(i);
        if ("。！？；!?;\n".indexOf(c) >= 0) return true;
        if (c != '.') return false;
        if (i > 0 && i + 1 < text.length() && Character.isDigit(text.charAt(i - 1))
                && Character.isDigit(text.charAt(i + 1))) return false;
        return i + 1 == text.length() || Character.isWhitespace(text.charAt(i + 1));
    }
    private static boolean proseSentenceEnd(String text, int i) {
        char c = text.charAt(i);
        if (c == '\n' || !sentenceEnd(text, i)) return false;
        if (c == '.' && i > 0 && Character.isDigit(text.charAt(i - 1))) {
            // A numbered item such as "  12. Explain ..." starts a sentence;
            // its marker must not be emitted as the end of the previous chunk.
            int start = i;
            while (start > 0 && Character.isDigit(text.charAt(start - 1))) start--;
            while (start > 0 && (text.charAt(start - 1) == ' ' || text.charAt(start - 1) == '\t'
                    || text.charAt(start - 1) == '\r')) start--;
            if (start == 0 || text.charAt(start - 1) == '\n') return false;
        }
        return true;
    }
    private static int joinedLength(List<Unit> units) {
        return units.stream().mapToInt(unit -> unit.text().length()).sum() + Math.max(0, units.size() - 1) * 2;
    }
    private static void emit(List<Chunk> out, String heading, List<Unit> units) {
        if (!units.isEmpty()) out.add(new Chunk(heading == null ? "" : heading,
                String.join("\n\n", units.stream().map(Unit::text).toList())));
    }
    private static List<Unit> overlapUnits(List<Unit> units, int budget) {
        List<Unit> tail = new ArrayList<>();
        int used = 0;
        for (int i = units.size() - 1; i >= 0 && used < budget; i--) {
            Unit unit = units.get(i);
            int separator = tail.isEmpty() ? 0 : 2;
            int remaining = budget - used - separator;
            if (remaining <= 0) break;
            if (unit.text().length() <= remaining) {
                tail.add(0, unit);
                used += separator + unit.text().length();
            } else {
                if (!unit.protectedBlock()) {
                    String suffix = plainTail(unit.text(), remaining);
                    if (!suffix.isEmpty()) tail.add(0, new Unit(unit.heading(), suffix, false));
                }
                break;
            }
        }
        return tail;
    }
    private static String plainTail(String text, int budget) {
        int earliest = Math.max(0, text.length() - budget);
        int start = -1;
        for (int i = Math.max(0, earliest - 1); i < text.length() - 1; i++) {
            if (proseSentenceEnd(text, i)) {
                start = i + 1;
                break;
            }
        }
        if (start < 0) return "";
        String tail = text.substring(start).strip();
        // Do not transplant an unfinished inline-code span as ordinary overlap.
        if (tail.indexOf('`') >= 0 && text.substring(0, start).chars().filter(c -> c == '`').count() % 2 != 0)
            return "";
        return tail;
    }
}
