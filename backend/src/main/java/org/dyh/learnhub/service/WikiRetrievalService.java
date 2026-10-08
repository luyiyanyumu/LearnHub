package org.dyh.learnhub.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.commonmark.node.*;
import org.commonmark.parser.IncludeSourceSpans;
import org.commonmark.parser.Parser;
import org.dyh.learnhub.ai.AgentService;
import org.dyh.learnhub.entity.WikiPage;
import org.dyh.learnhub.mapper.GraphEvidenceSourceMapper;
import org.dyh.learnhub.mapper.WikiPageMapper;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Wiki sections locate current sources. A generated section is not a verified factual passage. */
@Service
@Slf4j
@RequiredArgsConstructor
public class WikiRetrievalService {
    private static final Set<String> TYPES = Set.of("category", "tag", "entity");
    private static final int MAX_HITS = 24;
    private static final int MAX_SOURCES = 24;
    private static final int VIEW_TEXT_CHARS = 1200;
    private static final int MAX_READ_CHARS = 4000;
    private static final int MAX_SECTION_METADATA = 24;
    private static final Set<String> QUERY_FILLERS = Set.of("关系", "区别", "之间", "两者",
            "有哪些", "有什么", "的关", "的区", "么关", "么区", "相关", "总体", "主要", "内容",
            "what", "which", "how", "why", "does", "is", "are", "the", "and", "with", "explain", "define", "describe",
            "compare", "comparison", "difference", "differences", "relation", "relationship", "between", "versus",
            "can", "could", "should", "do", "in", "to", "of", "for", "as", "or", "an", "on", "about", "please", "gaps", "gap",
            "use", "using", "tell", "me", "work", "works");
    private static final Set<String> SUBJECT_MODIFIERS = Set.of("application", "applications", "protocol", "protocols",
            "model", "models", "system", "systems", "framework", "frameworks", "concept", "concepts", "example", "examples");
    // Remove complete framing before n-gram tokenization. Otherwise 是什么 leaves the spurious subject 是什.
    private static final Pattern QUESTION_FRAMING = Pattern.compile(
            "(?:有什么|有何|是什么)(?:关系|区别|联系|关联)|(?:之间|两者)(?:的)?(?:关系|区别|联系|关联)"
            + "|(?:到底|究竟|具体)?(?:是什么|指什么|是啥|什么意思)|什么是"
            + "|(?:有什么|有何)(?:作用|用途|优点|缺点|特点|优势)"
            + "|(?:的)?(?:工作原理|基本原理|工作方式|工作流程|主要特点)"
            + "|(?:如何|怎么)(?:样)?(?:使用|工作)?|为什么|为何|有哪些|有什么|请问|(?:请|帮我)(?:介绍|解释|说明)|关于|哪些|什么");
    private static final Pattern GAP_QUERY = Pattern.compile(
            "缺口|知识空白|待补充|待完善|待补全|待整理|需要(?:补充|完善)|尚未覆盖|未覆盖(?:的)?(?:知识|主题|内容)|\\bgaps?\\b|\\bmissing (?:knowledge|coverage)\\b",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern GAP_SECTION = Pattern.compile(
            "待补充|知识缺口|待完善|待补全|知识空白|待整理|尚未覆盖|\\b(?:knowledge\\s+)?gaps?\\b|\\bto be completed\\b",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern DEFINITION_QUERY = Pattern.compile("是什么|什么是|指什么|是啥|什么意思|\\b(?:what\\s+(?:is|are)|define|explain)\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern RELATION_QUERY = Pattern.compile(
            "关系|区别|对比|比较|异同|联系|关联|\\b(?:relationships?|relations?|compare|comparison|contrast|differences?|versus|vs)\\b",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern ALIASES = Pattern.compile("(?is)<!--\\s*entity-aliases:\\s*(.*?)\\s*-->");
    private static final Pattern LINK = Pattern.compile("\\[\\[([^\\]\\r\\n]{1,200})]]");
    private static final Pattern SOURCE = Pattern.compile(
            "(?:\\[)?(笔记|速查[、\\s]*卡|资料|note|quick_ref|file)\\s*[#＃:]\\s*(\\d+)(?:])?",
            Pattern.CASE_INSENSITIVE);
    private static final Parser MARKDOWN = Parser.builder().includeSourceSpans(IncludeSourceSpans.BLOCKS_AND_INLINES).build();
    private static final String NOTICE = "Wiki 内容是模型生成的导览，未逐条核验事实。sourceRefs 只定位相关原文，"
            + "不表示该小节的每条结论已得到支持；回答事实问题请读取当前原文核对。";

    private final WikiPageMapper pages;
    private final WikiService wiki;
    private final GraphEvidenceSourceMapper sources;

    public record Section(Long pageId, String topicKey, String pageTitle, String sectionKey,
                          String heading, String text, List<String> sourceRefs, List<String> links, double score) {
        public Section {
            sourceRefs = sourceRefs == null ? List.of() : List.copyOf(sourceRefs);
            links = links == null ? List.of() : List.copyOf(links);
        }
        public String key() { return "wiki:" + pageId + ":" + sectionKey; }
    }

    /** Lexical retrieval only: title, stored aliases, real section heading and visible body. */
    public List<Section> search(String question, int limit) {
        if (question == null || question.isBlank() || limit <= 0) return List.of();
        List<String> terms = queryTerms(question);
        if (terms.isEmpty()) return List.of();
        List<String> subjects = subjectTerms(terms);
        boolean gapQuestion = GAP_QUERY.matcher(question).find();
        boolean definitionQuestion = DEFINITION_QUERY.matcher(question).find() && !RELATION_QUERY.matcher(question).find();
        List<WikiPage> candidates;
        try {
            candidates = pages.selectList(Wrappers.<WikiPage>lambdaQuery().in(WikiPage::getTopicType, TYPES)
                    .eq(WikiPage::getQuality, "ok").isNotNull(WikiPage::getContentMd));
        } catch (Exception e) {
            log.warn("Wiki 检索读取失败：{}", e.toString());
            return List.of();
        }
        if (candidates == null) return List.of();
        candidates = candidates.stream().filter(WikiRetrievalService::eligible).toList();
        Map<String, Boolean> fresh = freshness(candidates);
        List<Section> hits = new ArrayList<>();
        for (WikiPage page : candidates) {
            if (!Boolean.TRUE.equals(fresh.get(page.getTopicKey()))) continue;
            String names = value(page.getTitle()) + " " + String.join(" ", aliases(page.getContentMd()));
            boolean namedSubject = matchesSubject(subjects, names);
            for (Section section : sections(page)) {
                if (!gapQuestion && GAP_SECTION.matcher(section.heading()).find()) continue;
                if (bodyText(section.text()).isBlank()) continue; // A heading alone is navigation, not a useful guide.
                String body = searchable(section.text());
                if (definitionQuestion && !namedSubject && !matchesSubject(subjects, section.heading())) continue;
                if (!matchesSubject(subjects, names + "\n" + section.heading() + "\n" + body)) continue;
                int bodyScore = AgentService.score(terms, "", body);
                int headingScore = AgentService.score(terms, section.heading(), "");
                int nameScore = AgentService.score(terms, names, "");
                double score = 3.0 * bodyScore + 2.0 * headingScore + nameScore;
                if (score > 0 && namedSubject) score += 1000; // Named subjects precede generic long pages.
                if (score > 0) hits.add(scored(section, score));
            }
        }
        hits.sort(Comparator.comparingDouble(Section::score).reversed().thenComparing(Section::topicKey)
                .thenComparingInt(s -> ordinal(s.sectionKey())));
        // Keep room for other pages, then add a second useful section from the same page.
        List<Section> first = new ArrayList<>(), second = new ArrayList<>();
        Map<Long, Integer> counts = new HashMap<>();
        for (Section hit : hits) {
            int count = counts.merge(hit.pageId(), 1, Integer::sum);
            if (count == 1) first.add(hit);
            else if (count == 2) second.add(hit);
        }
        first.addAll(second);
        return List.copyOf(first.subList(0, Math.min(Math.min(limit, MAX_HITS), first.size())));
    }

    public Map<String, Object> searchView(String query, int limit) {
        if (query == null || query.isBlank()) return failure("检索问题不能为空");
        List<Section> hits = search(query, limit);
        Map<String, Object> out = guide();
        out.put("ok", true);
        out.put("query", query);
        out.put("count", hits.size());
        out.put("items", hits.stream().map(s -> view(s, s.text().length() <= VIEW_TEXT_CHARS)).toList());
        return out;
    }

    /** Return complete sections within the budget; never truncate a citation or a fenced block. */
    public Map<String, Object> readPage(String topicKey, String sectionKey, int maxChars) {
        if (topicKey == null || topicKey.isBlank()) return failure("topicKey不能为空");
        if (maxChars <= 0) return failure("读取预算必须大于0");
        maxChars = Math.min(maxChars, MAX_READ_CHARS);
        WikiPage page;
        try {
            page = pages.selectOne(Wrappers.<WikiPage>lambdaQuery().eq(WikiPage::getTopicKey, topicKey.trim()));
        } catch (Exception e) {
            return failure("Wiki 页面暂时无法读取");
        }
        if (!eligible(page)) return failure("页面不存在、为空或质量未通过，不能作为当前导览读取");
        if (!Boolean.TRUE.equals(freshness(List.of(page)).get(page.getTopicKey()))) {
            return failure("页面已过期或来源新鲜度无法确认，请更新页面或读取原文");
        }
        List<Section> all = sections(page);
        List<Section> requested = sectionKey == null || sectionKey.isBlank() ? all
                : all.stream().filter(s -> sectionKey.trim().equals(s.sectionKey())).toList();
        if (requested.isEmpty()) return failure("页面没有可读正文或指定小节不存在");
        List<Map<String, Object>> kept = new ArrayList<>(), omitted = new ArrayList<>();
        int chars = 0;
        for (Section section : requested) {
            int cost = section.text().length() + (kept.isEmpty() ? 0 : 2);
            if (chars + cost > maxChars || kept.size() >= MAX_SECTION_METADATA) {
                if (omitted.size() < MAX_SECTION_METADATA) omitted.add(view(section, false));
            }
            else {
                kept.add(view(section, true));
                chars += cost;
            }
        }
        Map<String, Object> out = guide();
        out.put("ok", !kept.isEmpty());
        if (kept.isEmpty()) out.put("error", "完整小节超过读取预算，未返回截断内容；请读取原文来源");
        out.put("pageId", page.getId());
        out.put("topicKey", page.getTopicKey());
        out.put("pageTitle", value(page.getTitle()));
        out.put("sections", kept);
        out.put("availableSections", all.stream().limit(MAX_SECTION_METADATA).map(s -> view(s, false)).toList());
        out.put("totalSections", all.size());
        out.put("metadataTruncated", all.size() > MAX_SECTION_METADATA || requested.size() - kept.size() > MAX_SECTION_METADATA);
        out.put("omittedSections", omitted);
        out.put("chars", chars);
        out.put("budget", maxChars);
        out.put("budgetScope", "complete_section_text_chars_including_separators");
        out.put("omittedCount", requested.size() - kept.size());
        out.put("complete", requested.size() == kept.size());
        return out;
    }

    /** Resolve page citations to current raw passages; wiki text never becomes raw evidence. */
    public List<RetrievalHit> sourcePassages(String question, List<Section> sections, int limit) {
        if (question == null || question.isBlank() || sections == null || sections.isEmpty() || limit <= 0) return List.of();
        Map<String, Set<String>> headings = new LinkedHashMap<>();
        Map<String, Set<String>> titles = new LinkedHashMap<>();
        for (Section section : sections) {
            if (section == null) continue;
            for (String ref : section.sourceRefs()) {
                if (parseRef(ref) == null || (!headings.containsKey(ref) && headings.size() >= MAX_SOURCES)) continue;
                headings.computeIfAbsent(ref, ignored -> new LinkedHashSet<>()).add(value(section.heading()));
                headings.get(ref).add(value(section.pageTitle()));
                titles.computeIfAbsent(ref, ignored -> new LinkedHashSet<>()).add(value(section.pageTitle()));
            }
        }
        List<String> queryTerms = queryTerms(question);
        List<RetrievalHit> first = new ArrayList<>(), extras = new ArrayList<>();
        for (String type : List.of("note", "quick_ref", "file")) {
            List<Long> ids = headings.keySet().stream().map(WikiRetrievalService::parseRef)
                    .filter(ref -> type.equals(ref.type())).map(Ref::id).toList();
            if (ids.isEmpty()) continue;
            List<Map<String, Object>> docs;
            try {
                docs = sources.currentSources(type, ids);
            } catch (Exception e) {
                log.warn("Wiki 定位原文失败（{}）：{}", type, e.toString());
                continue;
            }
            if (docs == null) continue;
            for (Map<String, Object> row : docs) {
                if (row == null) continue;
                Long id = positiveId(row.get("id"));
                if (id == null || !ids.contains(id) || value(row.get("content")).isBlank()) continue;
                String ref = type + ":" + id;
                List<String> headingTerms = queryTerms(String.join(" ", headings.get(ref)));
                Set<String> sourceSubjects = new LinkedHashSet<>(subjectTerms(queryTerms));
                for (String title : titles.get(ref)) sourceSubjects.addAll(subjectTerms(queryTerms(title)));
                List<TextChunker.Chunk> chunks = TextChunker.splitWithHeadings(value(row.get("content")));
                List<RetrievalHit> ranked = new ArrayList<>();
                for (int seq = 0; seq < chunks.size(); seq++) {
                    TextChunker.Chunk chunk = chunks.get(seq);
                    String body = chunk.heading() + "\n" + chunk.text();
                    // A generic heading such as 定义 is a ranking hint, never sufficient raw-source admission.
                    if (!matchesSubject(new ArrayList<>(sourceSubjects), value(row.get("title")) + "\n" + body)) continue;
                    int score = 3 * AgentService.score(queryTerms, "", body)
                            + 2 * AgentService.score(headingTerms, "", body)
                            + AgentService.score(queryTerms, value(row.get("title")), "");
                    if (score > 0) ranked.add(new RetrievalHit(type, id, value(row.get("title")),
                            value(row.get("category")), chunk.text(), score, seq, List.of("wiki"), List.of()));
                }
                ranked.sort(Comparator.comparingDouble(RetrievalHit::score).reversed().thenComparingInt(RetrievalHit::seq));
                if (!ranked.isEmpty()) first.add(ranked.getFirst());
                if (ranked.size() > 1) extras.add(ranked.get(1));
            }
        }
        Comparator<RetrievalHit> order = Comparator.comparingDouble(RetrievalHit::score).reversed().thenComparing(RetrievalHit::key);
        first.sort(order);
        extras.sort(order);
        first.addAll(extras);
        Map<String, RetrievalHit> unique = new LinkedHashMap<>();
        for (RetrievalHit hit : first) unique.putIfAbsent(hit.key(), hit);
        return unique.values().stream().limit(Math.min(limit, MAX_HITS)).toList();
    }

    private Map<String, Boolean> freshness(List<WikiPage> input) {
        if (input.isEmpty()) return Map.of();
        try {
            Map<String, Boolean> fresh = wiki.retrievalFreshness(input);
            return fresh == null ? Map.of() : fresh;
        } catch (Exception e) {
            log.warn("Wiki 检索新鲜度未知：{}", e.toString());
            return Map.of();
        }
    }

    private static boolean eligible(WikiPage page) {
        return page != null && page.getId() != null && page.getId() > 0 && page.getTopicType() != null && TYPES.contains(page.getTopicType())
                && "ok".equals(page.getQuality()) && page.getTopicKey() != null && !page.getTopicKey().isBlank()
                && page.getContentMd() != null && !page.getContentMd().isBlank();
    }

    private static List<Section> sections(WikiPage page) {
        String text = page.getContentMd().replace("\r\n", "\n").replace('\r', '\n');
        Node document = MARKDOWN.parse(text);
        List<Heading> headings = new ArrayList<>();
        document.accept(new AbstractVisitor() { @Override public void visit(Heading heading) { headings.add(heading); } });
        Set<String> pageRefs = refs(visible(text));
        List<Section> out = new ArrayList<>();
        record Ancestor(int level, String title) {}
        List<Ancestor> path = new ArrayList<>();
        int start = 0, ordinal = 0;
        String title = value(page.getTitle());
        for (Heading heading : headings) {
            SourceSpan span = heading.getSourceSpans().getFirst();
            int at = span.getInputIndex() - span.getColumnIndex();
            addSection(out, page, "section-" + ordinal, title, text.substring(start, at), pageRefs);
            while (!path.isEmpty() && path.getLast().level() >= heading.getLevel()) path.removeLast();
            path.add(new Ancestor(heading.getLevel(), visible(heading, true, true).trim()));
            title = String.join(" > ", path.stream().map(Ancestor::title).toList());
            start = at;
            ordinal++;
        }
        addSection(out, page, "section-" + ordinal, title, text.substring(start), pageRefs);
        return List.copyOf(out);
    }

    private static void addSection(List<Section> out, WikiPage page, String key, String heading,
                                   String raw, Set<String> pageRefs) {
        String text = raw.trim();
        String rendered = visible(text);
        if (searchable(text).isBlank()) return;
        Set<String> localRefs = refs(rendered);
        // Definitions often cite sources in the introduction, while later subsections only link concepts.
        // These are page-level locators, explicitly not claim-level support declarations.
        List<String> sourceRefs = List.copyOf(localRefs.isEmpty() ? pageRefs : localRefs);
        Set<String> links = new LinkedHashSet<>();
        Matcher link = LINK.matcher(rendered);
        while (link.find()) {
            String target = link.group(1).split("\\|", 2)[0].trim();
            if (!target.isEmpty()) links.add(target);
        }
        out.add(new Section(page.getId(), page.getTopicKey(), value(page.getTitle()), key,
                heading, text, sourceRefs, List.copyOf(links), 0));
    }

    private static List<String> aliases(String text) {
        Set<String> out = new LinkedHashSet<>();
        MARKDOWN.parse(value(text)).accept(new AbstractVisitor() {
            @Override public void visit(HtmlBlock block) {
                add(block.getLiteral());
            }
            @Override public void visit(HtmlInline html) { add(html.getLiteral()); }
            private void add(String literal) {
                Matcher match = ALIASES.matcher(literal);
                while (match.find()) for (String alias : match.group(1).split("[,，;；]"))
                    if (!alias.isBlank()) out.add(alias.trim());
            }
        });
        return List.copyOf(out);
    }

    private static List<String> queryTerms(String text) {
        String subjectText = QUESTION_FRAMING.matcher(value(text)).replaceAll(" ");
        return AgentService.retrievalTerms(subjectText).stream().filter(t -> !QUERY_FILLERS.contains(t.toLowerCase(Locale.ROOT))).toList();
    }
    private static List<String> subjectTerms(List<String> terms) {
        List<String> explicit = terms.stream().filter(t -> t.matches("[A-Za-z][A-Za-z0-9_.+#-]*")).toList();
        if (!explicit.isEmpty()) {
            List<String> specific = explicit.stream().filter(t -> !SUBJECT_MODIFIERS.contains(t.toLowerCase(Locale.ROOT))).toList();
            return specific.isEmpty() ? explicit : specific;
        }
        List<String> triples = terms.stream().filter(t -> t.matches("[\\u4e00-\\u9fa5]{3}")).toList();
        return triples.isEmpty() ? terms : triples;
    }
    private static boolean matchesSubject(List<String> subjects, String text) {
        String target = value(text).toLowerCase(Locale.ROOT);
        for (String subject : subjects) {
            String term = subject.toLowerCase(Locale.ROOT);
            if (term.matches("[a-z][a-z0-9_.+#-]*")) {
                if (Pattern.compile("(?<![a-z0-9_])" + Pattern.quote(term) + "(?![a-z0-9_])").matcher(target).find()) return true;
            } else if (target.contains(term)) return true;
        }
        return false;
    }
    private static String visible(String markdown) { return visible(MARKDOWN.parse(value(markdown)), false, true); }
    private static String searchable(String markdown) { return visible(MARKDOWN.parse(value(markdown)), true, true); }
    private static String bodyText(String markdown) { return visible(MARKDOWN.parse(value(markdown)), true, false); }
    private static String visible(Node node, boolean includeCode, boolean includeHeadings) {
        StringBuilder out = new StringBuilder();
        node.accept(new AbstractVisitor() {
            @Override public void visit(Text text) { out.append(text.getLiteral()); }
            @Override public void visit(SoftLineBreak lineBreak) { out.append('\n'); }
            @Override public void visit(HardLineBreak lineBreak) { out.append('\n'); }
            @Override public void visit(Paragraph paragraph) { super.visit(paragraph); out.append('\n'); }
            @Override public void visit(Heading heading) { if (includeHeadings) { super.visit(heading); out.append('\n'); } }
            @Override public void visit(FencedCodeBlock block) { if (includeCode) out.append(block.getLiteral()); out.append('\n'); }
            @Override public void visit(IndentedCodeBlock block) { if (includeCode) out.append(block.getLiteral()); out.append('\n'); }
            @Override public void visit(Code code) { out.append(' '); if (includeCode) out.append(code.getLiteral()).append(' '); }
            @Override public void visit(HtmlInline html) { out.append(' '); }
            @Override public void visit(HtmlBlock html) { out.append('\n'); }
        });
        return out.toString();
    }

    private static Set<String> refs(String text) {
        Set<String> refs = new LinkedHashSet<>();
        Matcher m = SOURCE.matcher(text);
        while (m.find()) {
            Long id = positiveId(m.group(2));
            if (id == null) continue;
            String type = switch (m.group(1).toLowerCase(Locale.ROOT).replaceAll("[、\\s]", "")) {
                case "笔记", "note" -> "note";
                case "速查卡", "quick_ref" -> "quick_ref";
                default -> "file";
            };
            refs.add(type + ":" + id);
        }
        return refs;
    }

    private record Ref(String type, Long id) {}
    private static Ref parseRef(String value) {
        if (value == null) return null;
        String[] parts = value.split(":", -1);
        if (parts.length != 2 || !Set.of("note", "quick_ref", "file").contains(parts[0])) return null;
        Long id = positiveId(parts[1]);
        return id == null ? null : new Ref(parts[0], id);
    }
    private static Long positiveId(Object value) {
        try {
            long id = value instanceof Number number ? number.longValue() : Long.parseLong(String.valueOf(value));
            return id > 0 ? id : null;
        } catch (Exception e) { return null; }
    }
    private static int ordinal(String key) { return Integer.parseInt(key.substring("section-".length())); }
    private static String value(Object value) { return value == null ? "" : String.valueOf(value); }
    private static Section scored(Section s, double score) {
        return new Section(s.pageId(), s.topicKey(), s.pageTitle(), s.sectionKey(), s.heading(), s.text(), s.sourceRefs(), s.links(), score);
    }
    private static Map<String, Object> guide() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("generatedGuide", true);
        out.put("factVerified", false);
        out.put("verificationStatus", "unverified_generated_guide");
        out.put("requires_source_check", true);
        out.put("notice", NOTICE);
        return out;
    }
    private static Map<String, Object> failure(String message) {
        Map<String, Object> out = guide();
        out.put("ok", false);
        out.put("error", message);
        return out;
    }
    private static Map<String, Object> view(Section s, boolean includeText) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("key", s.key());
        out.put("pageId", s.pageId());
        out.put("topicKey", s.topicKey());
        out.put("pageTitle", s.pageTitle());
        out.put("sectionKey", s.sectionKey());
        out.put("heading", s.heading());
        out.put("text", includeText ? s.text() : "");
        out.put("textChars", s.text().length());
        out.put("textOmitted", !includeText);
        out.put("sourceRefs", s.sourceRefs());
        out.put("links", s.links());
        out.put("score", s.score());
        return out;
    }
}
