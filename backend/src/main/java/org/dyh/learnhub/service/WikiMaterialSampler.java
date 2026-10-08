package org.dyh.learnhub.service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Wiki 编译素材的确定性采样：在当前全文上切块，优先概念正文与小节，再覆盖不同位置。
 * 不调模型，不读数据库，也不将标题/作者信息当作概念机制的证据。
 *
 * <p>返回的块始终是 {@link TextChunker#splitWithHeadings(String)} 的完整输出，序号从零开始。
 * 预算不足时舍弃整个块，绝不把一个截断摘录冒充可回查的原文块。来源全文的哈希由本次输入
 * 计算，调用方应把这些输入及选中块用于生成依赖，不能在模型完成后重查另一份全文作为基准。
 */
public final class WikiMaterialSampler {
    /** 引用头、小节、正文全部计算在内。 */
    public static final int MAX_MATERIAL_CHARS = 24_000;
    public static final int MAX_CHUNKS_PER_SOURCE = 6;
    private static final int RELEVANT_SLOTS = 4;
    private static final Pattern METADATA_HEADING = Pattern.compile(
            "(?iu)^(?:authors?|affiliations?|acknowledg(?:e)?ments?|references?|bibliography|"
                    + "table of contents|contents|copyright|作者(?:信息)?|作者与单位|单位|致谢|"
                    + "参考文献|文献引用|目录|版权(?:声明)?|题名|标题|标题与作者|作者列表|"
                    + "论文信息|元信息|元数据|metadata)$");
    private static final Pattern METADATA_LINE = Pattern.compile(
            "(?iu)^(?:authors?|affiliations?|doi|arxiv|copyright|作者|单位|作者单位|题名|标题|"
                    + "邮箱|电子邮件|关键词|keywords?|收稿日期|发表日期)\\s*[:：].*|"
                    + ".*(?:[a-z0-9._%+-]+@[a-z0-9.-]+\\.[a-z]{2,}|https?://arxiv\\.org/abs/).*|"
                    + "^(?:©|\\(c\\)).*");
    private static final Pattern MECHANISM_HEADING = Pattern.compile(
            "(?iu).*(?:mechanism|method|architecture|algorithm|state|workflow|implementation|"
                    + "usage|example|limitation|机制|方法|算法|架构|原理|状态|回滚|恢复|流程|"
                    + "实现|用法|示例|限制|注意|易错).*");

    private WikiMaterialSampler() {
    }

    public record Source(String sourceType, Long sourceId, String title, String fullContent) {
        public String key() {
            return sourceType + ":" + sourceId;
        }
    }

    /** chunkText 不包含额外拼接的标题、采样标记或引用头。 */
    public record Chunk(String sourceType, Long sourceId, int seq, String heading,
                        String chunkText, String fullContentHash) {
        public String sourceKey() {
            return sourceType + ":" + sourceId;
        }

        public String key() {
            return sourceKey() + ":" + seq;
        }
    }

    /** sourceHashes 仅含实际送进 prompt 的来源；chars 是完整 prompt.length()。 */
    public record Material(String prompt, List<Chunk> chunks, Map<String, String> sourceHashes, int chars) {
        public Material {
            prompt = prompt == null ? "" : prompt;
            chunks = List.copyOf(chunks);
            sourceHashes = java.util.Collections.unmodifiableMap(new LinkedHashMap<>(sourceHashes));
            chars = prompt.length();
        }
    }

    private record Candidate(Source source, Chunk chunk, double score) {
    }

    /**
     * @param conceptName 空串用于已知分类/标签范围的整体采样；有名字时必须真实命中来源
     *                    标题、小节或正文，不能把未匹配来源作为均匀回退素材。
     * @param aliases 同一概念的别名，按完整名称匹配，避免 Agent 命中 AgentRewind 等其他实体
     */
    public static Material sample(String conceptName, List<String> aliases, List<Source> sources) {
        return sample(conceptName, aliases, sources, MAX_MATERIAL_CHARS);
    }

    /** 按调用方上下文选择较小预算；请求超过硬上限时仍最多 24k 字。 */
    public static Material sample(String conceptName, List<String> aliases, List<Source> sources, int budget) {
        int maxChars = Math.max(0, Math.min(MAX_MATERIAL_CHARS, budget));
        if (maxChars == 0) return new Material("", List.of(), Map.of(), 0);
        List<String> needles = names(conceptName, aliases);
        List<List<Candidate>> selections = new ArrayList<>();
        for (Source source : uniqueSources(sources)) {
            List<Candidate> candidates = candidates(source, needles);
            if (!candidates.isEmpty()) {
                selections.add(selectSource(candidates, !needles.isEmpty()));
            }
        }
        // 每轮每来源一块，防止一个长来源先占满预算而遮住其他真实来源。
        StringBuilder prompt = new StringBuilder();
        List<Chunk> selected = new ArrayList<>();
        Map<String, String> hashes = new LinkedHashMap<>();
        for (int round = 0; round < MAX_CHUNKS_PER_SOURCE; round++) {
            for (List<Candidate> perSource : selections) {
                if (round >= perSource.size()) continue;
                Candidate candidate = perSource.get(round);
                String rendered = render(candidate);
                if (prompt.length() + rendered.length() > maxChars) continue;
                prompt.append(rendered);
                selected.add(candidate.chunk());
                hashes.put(candidate.source().key(), candidate.chunk().fullContentHash());
            }
        }
        return new Material(prompt.toString(), selected, hashes, prompt.length());
    }

    /** 来源/块依赖使用同一种精确 UTF-8 SHA-256，不修剪或归一化换行。 */
    public static String sha256(String text) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest((text == null ? "" : text).getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 unavailable", impossible);
        }
    }

    private static List<Source> uniqueSources(List<Source> sources) {
        Map<String, Source> unique = new LinkedHashMap<>();
        if (sources == null) return List.of();
        for (Source source : sources) {
            if (source == null || source.sourceType() == null
                    || !Set.of("note", "quick_ref", "file").contains(source.sourceType())
                    || source.sourceId() == null || source.sourceId() <= 0
                    || source.fullContent() == null || source.fullContent().isBlank()) continue;
            Source prior = unique.putIfAbsent(source.key(), source);
            if (prior != null && (!prior.fullContent().equals(source.fullContent())
                    || !value(prior.title()).equals(value(source.title())))) {
                throw new IllegalArgumentException("同一来源存在不一致的全文快照：" + source.key());
            }
        }
        return List.copyOf(unique.values());
    }

    private static List<String> names(String conceptName, List<String> aliases) {
        Set<String> names = new LinkedHashSet<>();
        if (conceptName != null && !conceptName.isBlank()) names.add(conceptName.trim().toLowerCase(Locale.ROOT));
        if (aliases != null) {
            for (String alias : aliases) {
                if (alias != null && !alias.isBlank()) names.add(alias.trim().toLowerCase(Locale.ROOT));
            }
        }
        return List.copyOf(names);
    }

    private static List<Candidate> candidates(Source source, List<String> needles) {
        List<TextChunker.Chunk> chunks = TextChunker.splitWithHeadings(source.fullContent());
        String hash = sha256(source.fullContent());
        boolean scopedTitle = matches(source.title(), needles) > 0;
        Map<Integer, Candidate> relevant = new LinkedHashMap<>();
        List<Candidate> all = new ArrayList<>();
        for (int seq = 0; seq < chunks.size(); seq++) {
            TextChunker.Chunk raw = chunks.get(seq);
            if (metadataOnly(source.title(), raw, needles)) continue;
            int bodyMatches = matches(raw.text(), needles);
            int headingMatches = matches(raw.heading(), needles);
            double score = Math.min(bodyMatches, 8) * 8.0 + Math.min(headingMatches, 4) * 12.0;
            if (MECHANISM_HEADING.matcher(leaf(raw.heading())).matches()) score += 6;
            Chunk chunk = new Chunk(source.sourceType(), source.sourceId(), seq, raw.heading(), raw.text(), hash);
            Candidate candidate = new Candidate(source, chunk, score);
            all.add(candidate);
            if (needles.isEmpty() || scopedTitle || bodyMatches > 0 || headingMatches > 0) relevant.put(seq, candidate);
        }
        if (needles.isEmpty() || scopedTitle) return all;
        // 名称只在某段出现时，仅补该段所属小节的相邻完整块，避免把全书其他章节当概念证据。
        Set<Integer> direct = new LinkedHashSet<>(relevant.keySet());
        for (Candidate candidate : all) {
            int seq = candidate.chunk().seq();
            for (int neighbor : List.of(seq - 1, seq + 1)) {
                Candidate hit = relevant.get(neighbor);
                if (direct.contains(neighbor) && hit != null
                        && !candidate.chunk().heading().isBlank()
                        && candidate.chunk().heading().equals(hit.chunk().heading())) {
                    relevant.putIfAbsent(seq, candidate);
                }
            }
        }
        return relevant.values().stream().sorted(Comparator.comparingInt(c -> c.chunk().seq())).toList();
    }

    private static List<Candidate> selectSource(List<Candidate> candidates, boolean named) {
        if (candidates.size() <= MAX_CHUNKS_PER_SOURCE) return List.copyOf(candidates);
        Map<Integer, Candidate> chosen = new LinkedHashMap<>();
        if (named) {
            List<Candidate> ranked = candidates.stream().sorted(Comparator.comparingDouble(Candidate::score).reversed()
                    .thenComparingInt(c -> c.chunk().seq())).toList();
            Set<String> headings = new LinkedHashSet<>();
            // 不同小节优先，避免同一开头小节的重复段落占掉全部相关名额。
            for (Candidate candidate : ranked) {
                if (candidate.score() > 0 && headings.add(candidate.chunk().heading())) {
                    chosen.put(candidate.chunk().seq(), candidate);
                    if (chosen.size() == RELEVANT_SLOTS) break;
                }
            }
            for (Candidate candidate : ranked) {
                if (chosen.size() == RELEVANT_SLOTS) break;
                if (candidate.score() > 0) chosen.putIfAbsent(candidate.chunk().seq(), candidate);
            }
        }
        // 未知概念抽取时从完整范围均匀取样；已知概念从符合主题的小节中补不同位置。
        int remaining = MAX_CHUNKS_PER_SOURCE - chosen.size();
        List<Candidate> pool = candidates.stream().filter(c -> !chosen.containsKey(c.chunk().seq())).toList();
        for (int i = 0; i < remaining && !pool.isEmpty(); i++) {
            int at = remaining == 1 ? pool.size() / 2
                    : (int) Math.round(i * (pool.size() - 1.0) / (remaining - 1.0));
            Candidate candidate = pool.get(at);
            chosen.putIfAbsent(candidate.chunk().seq(), candidate);
        }
        // 小池子四舍五入可能重复，补齐空余名额。
        for (Candidate candidate : pool) {
            if (chosen.size() == MAX_CHUNKS_PER_SOURCE) break;
            chosen.putIfAbsent(candidate.chunk().seq(), candidate);
        }
        return chosen.values().stream().sorted(Comparator.comparingInt(c -> c.chunk().seq())).toList();
    }

    private static boolean metadataOnly(String title, TextChunker.Chunk chunk, List<String> needles) {
        String lastHeading = leaf(chunk.heading()).replaceFirst("^\\s*\\d+(?:\\.\\d+)*[.、．]?\\s*", "");
        if (METADATA_HEADING.matcher(lastHeading).matches()) return true;
        List<String> lines = chunk.text().lines().map(String::trim).filter(s -> !s.isEmpty()).toList();
        if (lines.isEmpty()) return true;
        int metadataChars = 0;
        int meaningfulChars = 0;
        String compactTitle = compact(value(title).replaceFirst("(?iu)\\.(?:pdf|docx?|md|txt)$", ""));
        for (String line : lines) {
            String compactLine = compact(line.replaceFirst("^#{1,6}\\s*", ""));
            boolean metadata = METADATA_LINE.matcher(line).matches()
                    || (!compactTitle.isEmpty() && compactTitle.equals(compactLine))
                    || needles.stream().anyMatch(n -> compact(n).equals(compactLine))
                    || line.matches("^\\d+(?:\\.\\d+)*$");
            if (metadata) metadataChars += line.length();
            else meaningfulChars += line.length();
        }
        return meaningfulChars == 0 || (metadataChars > meaningfulChars * 2 && meaningfulChars < 80);
    }

    private static int matches(String text, Collection<String> needles) {
        if (text == null || text.isBlank() || needles.isEmpty()) return 0;
        String lower = text.toLowerCase(Locale.ROOT);
        int matches = 0;
        for (String needle : needles) {
            int from = 0;
            while (from < lower.length()) {
                int at = lower.indexOf(needle, from);
                if (at < 0) break;
                int end = at + needle.length();
                boolean left = !identifier(needle.charAt(0)) || at == 0 || !identifier(lower.charAt(at - 1));
                boolean right = !identifier(needle.charAt(needle.length() - 1))
                        || end == lower.length() || !identifier(lower.charAt(end));
                if (left && right) matches++;
                from = end;
            }
        }
        return matches;
    }

    private static boolean identifier(char c) {
        return c >= 'a' && c <= 'z' || c >= '0' && c <= '9' || c == '_';
    }

    private static String render(Candidate candidate) {
        Chunk chunk = candidate.chunk();
        String marker = switch (chunk.sourceType()) {
            case "file" -> "资料";
            case "quick_ref" -> "速查卡";
            default -> "笔记";
        };
        return "【" + marker + "#" + chunk.sourceId() + "】《" + value(candidate.source().title())
                + "》\n[原文块#" + chunk.seq() + (chunk.heading().isBlank() ? "" : " · " + chunk.heading())
                + "]\n" + chunk.chunkText() + "\n\n";
    }

    private static String leaf(String heading) {
        String path = value(heading);
        int at = path.lastIndexOf(" > ");
        return at < 0 ? path.trim() : path.substring(at + 3).trim();
    }

    private static String compact(String text) {
        return TextChunker.cleanHeading(value(text)).replaceAll("\\s+", "").toLowerCase(Locale.ROOT);
    }

    private static String value(String text) {
        return text == null ? "" : text;
    }
}
