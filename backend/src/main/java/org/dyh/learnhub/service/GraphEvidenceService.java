package org.dyh.learnhub.service;

import lombok.RequiredArgsConstructor;
import org.commonmark.node.Code;
import org.commonmark.node.Delimited;
import org.commonmark.node.FencedCodeBlock;
import org.commonmark.node.Image;
import org.commonmark.node.IndentedCodeBlock;
import org.commonmark.node.Link;
import org.commonmark.node.Node;
import org.commonmark.node.SourceSpan;
import org.commonmark.parser.IncludeSourceSpans;
import org.commonmark.parser.Parser;
import org.dyh.learnhub.entity.KgNode;
import org.dyh.learnhub.mapper.GraphEvidenceSourceMapper;
import org.dyh.learnhub.mapper.KgCommunityMapper;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 将图关系投影回可引用的当前正文。关系只是定位线索；完整证据与正文核对通过才参与融合。
 * 删除来源、改掉证据、残留旧块都不能作为图检索命中。整个查询过程只读，不调用模型。
 */
@Service
@RequiredArgsConstructor
public class GraphEvidenceService {
    private static final int MAX_CONCEPTS = 12;
    private static final int MAX_RELATIONS = 60;
    private static final int MAX_SOURCES = 24;
    private static final int MAX_HITS = 24;
    private static final int MAX_CHUNK_OCCURRENCES = 64;
    private static final Pattern SOURCE = Pattern.compile(
            "^(笔记|速查卡|资料|note|quick_ref|file)\\s*#\\s*([1-9][0-9]*)$");
    private static final Parser MARKDOWN = Parser.builder()
            .includeSourceSpans(IncludeSourceSpans.BLOCKS_AND_INLINES).build();
    private static final Pattern MARKUP = Pattern.compile(
            "(?m)^[ \\t]{0,3}(?:#{1,6}[ \\t]+|>[ \\t]*|[-+*][ \\t]+|[0-9]+[.)][ \\t]+)"
                    + "|(?m)^[ \\t]*(?:`{3,}|~{3,})[^\\r\\n]*$"
                    + "|<!--[\\s\\S]*?-->|(?i:</?(?:span|font|b|strong|i|em|u|s|del|mark|a|p|div|br|pre|code)"
                    + "(?:[ \\t][^>\\r\\n]*)?/?>)");

    private final KgGraphService graphService;
    private final KgCommunityMapper communityMapper;
    private final GraphEvidenceSourceMapper sourceMapper;

    public List<RetrievalHit> search(String question, int limit) {
        if (question == null || question.isBlank() || limit <= 0) return List.of();
        int take = Math.min(limit, MAX_HITS);
        Set<String> seeds = new LinkedHashSet<>();
        for (KgNode node : graphService.recognize(question, MAX_CONCEPTS)) {
            if (node != null && node.getId() != null && !node.getId().isBlank()) seeds.add(node.getId());
            if (seeds.size() == MAX_CONCEPTS) break;
        }
        if (seeds.isEmpty()) return List.of();
        List<Relation> relations = candidates(communityMapper.relationsAround(new ArrayList<>(seeds)), seeds);
        Set<SourceRef> refs = new LinkedHashSet<>();
        for (Relation relation : relations) {
            for (SourceRef ref : relation.sources()) {
                if (refs.size() < MAX_SOURCES) refs.add(ref);
            }
        }
        Map<SourceRef, Document> documents = documents(refs);
        Map<SourceRef, List<CurrentChunk>> chunks = new LinkedHashMap<>();
        Map<String, Match> matches = new LinkedHashMap<>();
        for (Relation relation : relations) {
            for (SourceRef ref : relation.sources()) {
                Document doc = documents.get(ref);
                if (doc == null) continue;
                int evidenceAt = doc.normalized().text().indexOf(relation.evidence());
                if (evidenceAt < 0) continue;
                List<CurrentChunk> indexed = chunks.computeIfAbsent(ref, key -> currentChunks(doc));
                boolean found = false;
                for (CurrentChunk chunk : indexed) {
                    if (!chunk.normalized().contains(relation.evidence())) continue;
                    add(matches, ref, doc, chunk.seq(), chunk.text(), relation);
                    found = true;
                }
                if (!found) {
                    String window = doc.normalized().window(doc.content(), evidenceAt, relation.evidence().length());
                    add(matches, ref, doc, -1, window, relation);
                }
            }
        }
        return matches.values().stream().sorted(Comparator.comparingDouble(Match::score).reversed())
                .limit(take).map(Match::hit).toList();
    }

    /** 与关键词及向量索引使用同一正文分块器；无需向量索引也能生成相同块序号。 */
    private static List<CurrentChunk> currentChunks(Document document) {
        List<TextChunker.Chunk> pieces = TextChunker.splitWithHeadings(document.content());
        Normalized literal = literal(document.content());
        Map<String, String> located = new LinkedHashMap<>();
        List<CurrentChunk> out = new ArrayList<>();
        for (int seq = 0; seq < pieces.size(); seq++) {
            String text = pieces.get(seq).text();
            String key = literal(text).text();
            // 子块可能从围栏代码中间开始，不能脱离全文重新解释 Markdown 装饰。
            // 保留所有原样符号定位，只容忍切块器对段落空白的整理。
            String normalized = located.computeIfAbsent(key, one -> locateChunk(one, literal, document.normalized()));
            out.add(new CurrentChunk(seq, text, normalized));
        }
        return out;
    }

    private static String locateChunk(String chunk, Normalized literal, Normalized document) {
        if (chunk.isEmpty()) return "";
        String found = null;
        int count = 0;
        for (int at = literal.text().indexOf(chunk); at >= 0; at = literal.text().indexOf(chunk, at + 1)) {
            if (++count > MAX_CHUNK_OCCURRENCES) return "";
            int start = literal.offsets()[at], end = literal.offsets()[at + chunk.length() - 1] + 1;
            String scoped = document.within(start, end);
            // 同样原样文本可能同时出现于代码与普通段落；位置含糊时使用已核验的原文窗口。
            if (found != null && !found.equals(scoped)) return "";
            found = scoped;
        }
        return found == null ? "" : found;
    }

    /** 定位副本保留全部标点、Markdown 与代码字符，只去掉分块器会重新整理的空白。 */
    private static Normalized literal(String raw) {
        StringBuilder text = new StringBuilder();
        int[] offsets = new int[raw.length()];
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            if (Character.isWhitespace(c) || Character.isSpaceChar(c)) continue;
            offsets[text.length()] = i;
            text.append(c);
        }
        return new Normalized(text.toString(), Arrays.copyOf(offsets, text.length()));
    }

    private static List<Relation> candidates(List<Map<String, Object>> rows, Set<String> seeds) {
        List<Relation> out = new ArrayList<>();
        for (Map<String, Object> row : rows.stream().limit(MAX_RELATIONS).toList()) {
            String head = value(row.get("headId")), tail = value(row.get("tailId"));
            String kind = value(row.get("relation"));
            if (head.isBlank() || tail.isBlank() || head.equals(tail)
                    || !(seeds.contains(head) || seeds.contains(tail)) || !KgOntology.known(kind)
                    || "derived".equalsIgnoreCase(value(row.get("origin")))) continue;
            String evidence = normalize(value(row.get("evidence"))).text();
            if (evidence.isEmpty()) continue;
            List<SourceRef> refs = sourceRefs(row.get("sources"));
            if (refs.isEmpty()) continue;
            boolean bridging = seeds.contains(head) && seeds.contains(tail);
            double weight = row.get("weight") instanceof Number n ? n.doubleValue() : 0;
            if (!Double.isFinite(weight)) weight = 0;
            double score = (bridging ? 2 : 1) + Math.clamp(weight, 0, 1) * .1;
            String label = name(row, "headName", head) + " —" + KgGraphService.labelOf(kind)
                    + "→ " + name(row, "tailName", tail);
            if (KgOntology.byId(kind).symmetric() && head.compareTo(tail) > 0) {
                String temp = head; head = tail; tail = temp;
            }
            out.add(new Relation(head + ":" + kind + ":" + tail, label, evidence, refs, score));
        }
        out.sort(Comparator.comparingDouble(Relation::score).reversed().thenComparing(Relation::key));
        return out;
    }

    private Map<SourceRef, Document> documents(Set<SourceRef> refs) {
        Map<SourceRef, Document> out = new LinkedHashMap<>();
        for (String type : List.of("note", "quick_ref", "file")) {
            List<Long> ids = refs.stream().filter(ref -> type.equals(ref.type())).map(SourceRef::id).toList();
            if (ids.isEmpty()) continue;
            for (Map<String, Object> row : sourceMapper.currentSources(type, ids)) {
                Long id = positiveId(row.get("id"));
                if (id == null || !ids.contains(id)) continue;
                String content = value(row.get("content"));
                if (content.isBlank()) continue;
                out.put(new SourceRef(type, id), new Document(value(row.get("title")), value(row.get("category")),
                        content, normalize(content)));
            }
        }
        return out;
    }

    private static void add(Map<String, Match> matches, SourceRef ref, Document doc, int seq,
                            String text, Relation relation) {
        // 跨块证据仍保留同源的不同窗口，不能让第一条窗口吞掉后面的有效证据。
        String key = ref.type() + ":" + ref.id() + ":" + seq + (seq < 0 ? ":" + text : "");
        Match match = matches.computeIfAbsent(key, ignored -> new Match(ref, doc, seq, text, relation.score(),
                new LinkedHashMap<>()));
        match.relations().putIfAbsent(relation.key(), relation.label());
    }

    private static List<SourceRef> sourceRefs(Object raw) {
        Set<SourceRef> refs = new LinkedHashSet<>();
        List<?> values = raw instanceof List<?> list ? list : List.of(value(raw).split("[|、,，;；\\r\\n]+"));
        for (Object one : values) {
            Matcher matcher = SOURCE.matcher(value(one).strip());
            if (!matcher.matches()) continue;
            Long id = positiveId(matcher.group(2));
            if (id == null) continue;
            String type = switch (matcher.group(1)) {
                case "笔记", "note" -> "note";
                case "速查卡", "quick_ref" -> "quick_ref";
                default -> "file";
            };
            refs.add(new SourceRef(type, id));
            if (refs.size() == MAX_SOURCES) break;
        }
        return new ArrayList<>(refs);
    }

    /** 保留可见字符到原文的偏移，只忽略 Markdown 装饰和排版空白，保留词内符号与英文词界。 */
    private static Normalized normalize(String raw) {
        boolean[] ignored = new boolean[raw.length()];
        boolean[] code = new boolean[raw.length()];
        markSyntax(MARKDOWN.parse(raw), raw, ignored, code);
        Matcher markup = MARKUP.matcher(raw);
        while (markup.find()) {
            boolean inCode = false;
            for (int i = markup.start(); i < markup.end() && !inCode; i++) inCode = code[i];
            if (!inCode) Arrays.fill(ignored, markup.start(), markup.end(), true);
        }
        StringBuilder text = new StringBuilder();
        int[] offsets = new int[raw.length()];
        int whitespace = -1;
        for (int i = 0; i < raw.length(); i++) {
            if (ignored[i]) continue;
            char c = raw.charAt(i);
            if (Character.isWhitespace(c) || Character.isSpaceChar(c)) {
                if (whitespace < 0) whitespace = i;
                continue;
            }
            if (whitespace >= 0 && !text.isEmpty() && wordChar(text.charAt(text.length() - 1)) && wordChar(c)) {
                offsets[text.length()] = whitespace;
                text.append(' ');
            }
            whitespace = -1;
            offsets[text.length()] = i;
            text.append(c);
        }
        return new Normalized(text.toString(), Arrays.copyOf(offsets, text.length()));
    }

    /** 只移除 CommonMark 确认为装饰的边界；代码里的泛型、强调符号和链接目标都是原文。 */
    private static void markSyntax(Node node, String raw, boolean[] ignored, boolean[] code) {
        List<SourceSpan> spans = node.getSourceSpans();
        if (!spans.isEmpty()) {
            int start = spans.getFirst().getInputIndex();
            int end = spanEnd(spans.getLast());
            if (node instanceof Code || node instanceof FencedCodeBlock || node instanceof IndentedCodeBlock) {
                for (SourceSpan span : spans) Arrays.fill(code, span.getInputIndex(), spanEnd(span), true);
                if (node instanceof Code) {
                    int delimiter = 0;
                    while (start + delimiter < end && raw.charAt(start + delimiter) == '`') delimiter++;
                    Arrays.fill(ignored, start, start + delimiter, true);
                    Arrays.fill(ignored, end - delimiter, end, true);
                } else if (node instanceof FencedCodeBlock fence) {
                    Arrays.fill(ignored, start, spanEnd(spans.getFirst()), true);
                    if (fence.getClosingFenceLength() != null) {
                        Arrays.fill(ignored, spans.getLast().getInputIndex(), end, true);
                    }
                }
                return;
            }
            if (node instanceof Delimited delimiter) {
                Arrays.fill(ignored, start, start + delimiter.getOpeningDelimiter().length(), true);
                Arrays.fill(ignored, end - delimiter.getClosingDelimiter().length(), end, true);
            } else if (node instanceof Link || node instanceof Image) {
                int contentStart = end, contentEnd = start;
                for (Node child = node.getFirstChild(); child != null; child = child.getNext()) {
                    for (SourceSpan span : child.getSourceSpans()) {
                        contentStart = Math.min(contentStart, span.getInputIndex());
                        contentEnd = Math.max(contentEnd, spanEnd(span));
                    }
                }
                if (contentStart <= contentEnd) {
                    Arrays.fill(ignored, start, contentStart, true);
                    Arrays.fill(ignored, contentEnd, end, true);
                }
            }
        }
        for (Node child = node.getFirstChild(); child != null; child = child.getNext()) {
            markSyntax(child, raw, ignored, code);
        }
    }

    private static int spanEnd(SourceSpan span) { return span.getInputIndex() + span.getLength(); }

    private static boolean wordChar(char c) {
        return Character.isLetterOrDigit(c) && Character.UnicodeScript.of(c) != Character.UnicodeScript.HAN;
    }

    private static Long positiveId(Object value) {
        try { long id = Long.parseLong(value(value)); return id > 0 ? id : null; }
        catch (NumberFormatException e) { return null; }
    }

    private static String value(Object value) { return value == null ? "" : value.toString(); }
    private static String name(Map<String, Object> row, String field, String fallback) {
        return value(row.get(field)).isBlank() ? fallback : value(row.get(field));
    }

    private record SourceRef(String type, Long id) {}
    private record Relation(String key, String label, String evidence, List<SourceRef> sources, double score) {}
    private record Document(String title, String category, String content, Normalized normalized) {}
    private record CurrentChunk(int seq, String text, String normalized) {}
    private record Normalized(String text, int[] offsets) {
        String within(int rawStart, int rawEnd) {
            int start = Arrays.binarySearch(offsets, rawStart);
            int end = Arrays.binarySearch(offsets, rawEnd);
            if (start < 0) start = -start - 1;
            if (end < 0) end = -end - 1;
            return text.substring(start, end);
        }

        String window(String raw, int at, int length) {
            int start = Math.max(0, offsets[at] - 180);
            int end = Math.min(raw.length(), offsets[at + length - 1] + 1 + 240);
            // 避免在 UTF-16 代理对中间截断。
            if (start > 0 && Character.isLowSurrogate(raw.charAt(start))) start--;
            if (end < raw.length() && Character.isLowSurrogate(raw.charAt(end))) end++;
            return raw.substring(start, end).strip();
        }
    }
    private record Match(SourceRef ref, Document document, int seq, String text, double score,
                         Map<String, String> relations) {
        RetrievalHit hit() {
            return new RetrievalHit(ref.type(), ref.id(), document.title(), document.category(), text, score, seq,
                    List.of("graph"), List.copyOf(relations.values()));
        }
    }
}
