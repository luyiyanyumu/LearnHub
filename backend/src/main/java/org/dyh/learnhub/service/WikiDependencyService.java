package org.dyh.learnhub.service;

import lombok.RequiredArgsConstructor;
import org.dyh.learnhub.entity.WikiPage;
import org.dyh.learnhub.entity.WikiSourceDependency;
import org.dyh.learnhub.mapper.GraphEvidenceSourceMapper;
import org.dyh.learnhub.mapper.WikiPageMapper;
import org.dyh.learnhub.mapper.WikiSourceDependencyMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.util.*;

/**
 * Persists exactly the source chunks used by one Wiki generation. A matching dependency proves
 * that the locator still points to unchanged source material, not that the generated claim is true.
 * The full-source hash deliberately invalidates a page when even an unsampled paragraph changes.
 */
@Service
@RequiredArgsConstructor
public class WikiDependencyService {
    private static final int BATCH_SIZE = 128;
    private static final String NOTICE = "这些记录定位生成时使用的原文片段；来源未变化不等于 Wiki 事实已经逐条核验。";
    private final WikiSourceDependencyMapper dependencies;
    private final GraphEvidenceSourceMapper sources;
    private final WikiPageMapper pages;

    public enum Status { CURRENT, STALE, UNKNOWN }

    public record Freshness(Status status, List<String> reasons, boolean hasDependencies) {
        public Freshness { reasons = List.copyOf(reasons); }
        public Freshness(Status status, List<String> reasons) { this(status, reasons, true); }
        public boolean current() { return status == Status.CURRENT; }
        public boolean known() { return status != Status.UNKNOWN; }
        public boolean legacy() { return status == Status.UNKNOWN && !hasDependencies && reasons.equals(List.of("legacy_no_dependencies")); }
    }

    public record SourceRef(String type, Long id) {
        public SourceRef {
            type = canonicalType(type);
            if (id == null || id <= 0) throw new IllegalArgumentException("来源编号必须大于零");
        }
        public String key() { return type + ":" + id; }
        public String label() { return marker(type) + "#" + id; }
    }

    public record ChunkRef(String type, Long id, int seq) {
        public ChunkRef {
            SourceRef ref = new SourceRef(type, id);
            type = ref.type();
            if (seq < 0) throw new IllegalArgumentException("片段序号不能为负数");
        }
        public SourceRef source() { return new SourceRef(type, id); }
    }

    public record SourceInput(String type, Long id, String title, String fullContent) {
        public SourceInput {
            SourceRef ref = new SourceRef(type, id);
            type = ref.type();
            title = value(title);
            fullContent = value(fullContent);
        }
        public SourceRef ref() { return new SourceRef(type, id); }
    }

    public record SourceChunk(int seq, String heading, String text, String hash) {}

    public record SourceSnapshot(SourceRef ref, String title, String fullContent,
                                 String fullContentHash, int totalChunks, List<SourceChunk> chunks) {
        public SourceSnapshot { chunks = List.copyOf(chunks); }
    }

    public record Snapshot(LocalDateTime capturedAt, List<SourceSnapshot> sources,
                           List<SourceRef> missingSources, String hash) {
        public Snapshot {
            sources = List.copyOf(sources);
            missingSources = List.copyOf(missingSources);
        }
        public boolean complete() { return !sources.isEmpty() && missingSources.isEmpty(); }
        public int chunkCount() { return sources.stream().mapToInt(s -> s.chunks().size()).sum(); }
        public String sourceHash() { return hash; }
        public List<SourceRef> sourceRefs() { return sources.stream().map(SourceSnapshot::ref).toList(); }
    }

    /** Capture once, before calling a model. Never rebuild this baseline from a later database read. */
    public Snapshot captureSnapshot(Collection<SourceRef> refs) {
        LinkedHashSet<SourceRef> requested = new LinkedHashSet<>(refs == null ? List.of() : refs);
        if (requested.contains(null)) throw new IllegalArgumentException("来源不能为空");
        LocalDateTime captured = LocalDateTime.now();
        Map<SourceRef, SourceInput> current = readSources(requested);
        Snapshot snapshot = fromSources(current.values());
        return new Snapshot(captured, snapshot.sources(), requested.stream().filter(r -> !current.containsKey(r)).toList(), snapshot.hash());
    }

    /** Pure capture from the same immutable full texts that the material sampler receives. */
    public static Snapshot fromSources(Collection<SourceInput> inputs) {
        Map<SourceRef, SourceSnapshot> found = new LinkedHashMap<>();
        for (SourceInput input : inputs == null ? List.<SourceInput>of() : inputs) {
            if (input == null || input.fullContent().isBlank()) throw new IllegalArgumentException("生成来源正文不能为空");
            List<TextChunker.Chunk> split = TextChunker.splitWithHeadings(input.fullContent());
            List<SourceChunk> chunks = new ArrayList<>();
            for (int seq = 0; seq < split.size(); seq++) {
                TextChunker.Chunk chunk = split.get(seq);
                chunks.add(new SourceChunk(seq, chunk.heading(), chunk.text(), sha256(chunk.text())));
            }
            SourceSnapshot source = new SourceSnapshot(input.ref(), input.title(), input.fullContent(),
                    sha256(input.fullContent()), split.size(), chunks);
            SourceSnapshot previous = found.putIfAbsent(input.ref(), source);
            if (previous != null && (!previous.fullContentHash().equals(source.fullContentHash()) || !previous.title().equals(source.title()))) {
                throw new IllegalArgumentException("同一来源出现两个不同版本：" + input.ref().key());
            }
        }
        List<SourceSnapshot> material = sortedSources(found.values());
        return new Snapshot(LocalDateTime.now(), material, List.of(), snapshotHash(material));
    }

    /** Only selected chunks and their actual source documents become page dependencies. */
    public static Snapshot selectChunks(Snapshot snapshot, Collection<ChunkRef> selected) {
        Objects.requireNonNull(snapshot, "snapshot");
        Set<ChunkRef> wanted = new LinkedHashSet<>(selected == null ? List.of() : selected);
        if (wanted.contains(null)) throw new IllegalArgumentException("片段定位不能为空");
        List<SourceSnapshot> material = new ArrayList<>();
        Set<ChunkRef> resolved = new HashSet<>();
        for (SourceSnapshot source : snapshot.sources()) {
            List<SourceChunk> chunks = source.chunks().stream().filter(chunk -> {
                ChunkRef ref = new ChunkRef(source.ref().type(), source.ref().id(), chunk.seq());
                if (!wanted.contains(ref)) return false;
                resolved.add(ref);
                return true;
            }).toList();
            if (!chunks.isEmpty()) material.add(new SourceSnapshot(source.ref(), source.title(), source.fullContent(),
                    source.fullContentHash(), source.totalChunks(), chunks));
        }
        if (!resolved.equals(wanted)) throw new IllegalArgumentException("片段不属于生成时的来源快照");
        // A failed read of an unrelated source is no longer a dependency once selection is explicit.
        return new Snapshot(snapshot.capturedAt(), material, List.of(), snapshotHash(material));
    }

    /** Page body and locators commit together. Public entry point is necessary for Spring transactions. */
    @Transactional(rollbackFor = Exception.class)
    public WikiPage saveGenerated(WikiPage page, Snapshot snapshot) {
        validateSnapshot(snapshot);
        if (page == null || page.getTopicKey() == null || page.getTopicKey().isBlank() || page.getContentMd() == null || page.getContentMd().isBlank()) {
            throw new IllegalArgumentException("知识页主题和正文不能为空");
        }
        page.setDependencyVersion(2);
        Long currentId = dependencies.lockTopic(page.getTopicKey());
        if (currentId != null) {
            if (page.getId() != null && !page.getId().equals(currentId)) throw new IllegalArgumentException("知识页编号与主题不匹配");
            page.setId(currentId);
            if (pages.updateById(page) != 1) throw new IllegalStateException("知识页保存失败");
        } else {
            if (page.getId() != null) throw new IllegalArgumentException("待更新知识页不存在");
            if (pages.insert(page) != 1 || page.getId() == null) throw new IllegalStateException("知识页保存失败");
        }
        replaceRows(page.getId(), page.getContentMd(), snapshot);
        return page;
    }

    /** Joins a caller's transaction if it already saved the generated page in that transaction. */
    @Transactional(rollbackFor = Exception.class)
    public void replaceDependencies(Long pageId, Snapshot snapshot) {
        validateSnapshot(snapshot);
        if (pageId == null || dependencies.lockPage(pageId) == null) throw new IllegalArgumentException("知识页不存在");
        WikiPage page = pages.selectById(pageId);
        if (page == null) throw new IllegalArgumentException("知识页不存在");
        page.setDependencyVersion(2);
        if (pages.updateById(page) != 1) throw new IllegalStateException("知识页依赖版本保存失败");
        replaceRows(pageId, page.getContentMd(), snapshot);
    }

    @Transactional(rollbackFor = Exception.class)
    public void deleteDependencies(Long pageId) {
        if (pageId != null && dependencies.lockPage(pageId) != null) dependencies.deleteByPage(pageId);
    }

    public Freshness freshness(Long pageId) {
        try {
            WikiPage page = pageId == null ? null : pages.selectById(pageId);
            if (page == null) return unknown("page_missing");
            return freshness(List.of(page)).getOrDefault(pageId, unknown("dependency_check_failed"));
        } catch (RuntimeException e) {
            return unknown("dependency_check_failed");
        }
    }

    /** Batch check reads only referenced source IDs; unrelated database edits never invalidate a page. */
    public Map<Long, Freshness> freshness(List<WikiPage> input) {
        Map<Long, WikiPage> requested = pageMap(input);
        if (requested.isEmpty()) return Map.of();
        try {
            List<WikiSourceDependency> rows = dependencyRows(requested.keySet());
            Map<SourceRef, SourceState> current = sourceStates(readSources(sourceRefs(rows)));
            Map<Long, Freshness> out = new LinkedHashMap<>();
            for (WikiPage page : requested.values()) out.put(page.getId(), check(page, pageRows(rows, page.getId()), current).freshness());
            return out;
        } catch (RuntimeException e) {
            Map<Long, Freshness> out = new LinkedHashMap<>();
            requested.keySet().forEach(id -> out.put(id, unknown("dependency_check_failed")));
            return out;
        }
    }

    public Map<String, Object> evidenceView(Long pageId) {
        Check check;
        List<WikiSourceDependency> rows = List.of();
        try {
            WikiPage page = pageId == null ? null : pages.selectById(pageId);
            if (page == null) check = new Check(unknown("page_missing"), List.of());
            else {
                rows = dependencyRows(Set.of(pageId));
                check = check(page, rows, sourceStates(readSources(sourceRefs(rows))));
            }
        } catch (RuntimeException e) {
            check = new Check(unknown("dependency_check_failed"), List.of());
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("pageId", pageId);
        out.put("status", check.freshness().status().name().toLowerCase(Locale.ROOT));
        out.put("sourceUnchanged", check.freshness().current());
        out.put("hasDependencies", check.freshness().hasDependencies());
        out.put("factVerified", false);
        out.put("notice", NOTICE);
        out.put("reasons", check.freshness().reasons());
        out.put("coverage", coverage(rows));
        out.put("dependencies", check.details());
        return out;
    }

    private void replaceRows(Long pageId, String pageContent, Snapshot snapshot) {
        List<WikiSourceDependency> rows = toRows(pageId, sha256(value(pageContent)), snapshot);
        dependencies.deleteByPage(pageId);
        for (int i = 0; i < rows.size(); i += BATCH_SIZE) {
            List<WikiSourceDependency> batch = rows.subList(i, Math.min(rows.size(), i + BATCH_SIZE));
            if (dependencies.insertBatch(batch) != batch.size()) throw new IllegalStateException("知识页来源依赖保存不完整");
        }
    }

    private static void validateSnapshot(Snapshot snapshot) {
        if (snapshot == null || !snapshot.complete() || snapshot.chunkCount() == 0 || snapshot.capturedAt() == null) {
            throw new IllegalArgumentException("生成时来源快照不完整");
        }
        if (!snapshotHash(snapshot.sources()).equals(snapshot.hash())) throw new IllegalArgumentException("来源快照指纹不匹配");
        Set<SourceRef> refs = new HashSet<>();
        for (SourceSnapshot source : snapshot.sources()) {
            if (!refs.add(source.ref()) || !sha256(source.fullContent()).equals(source.fullContentHash())) throw new IllegalArgumentException("来源快照版本不匹配");
            List<TextChunker.Chunk> original = TextChunker.splitWithHeadings(source.fullContent());
            if (source.totalChunks() != original.size() || source.chunks().isEmpty()) throw new IllegalArgumentException("来源片段数量不匹配");
            Set<Integer> seqs = new HashSet<>();
            for (SourceChunk chunk : source.chunks()) {
                if (!seqs.add(chunk.seq()) || chunk.seq() < 0 || chunk.seq() >= original.size()) throw new IllegalArgumentException("来源片段序号不匹配");
                TextChunker.Chunk expected = original.get(chunk.seq());
                if (!expected.heading().equals(chunk.heading()) || !expected.text().equals(chunk.text()) || !sha256(chunk.text()).equals(chunk.hash())) {
                    throw new IllegalArgumentException("来源片段不是原始快照中的完整片段");
                }
            }
        }
    }

    private Map<SourceRef, SourceInput> readSources(Collection<SourceRef> requested) {
        Map<SourceRef, SourceInput> out = new LinkedHashMap<>();
        for (String type : List.of("note", "quick_ref", "file")) {
            List<Long> ids = requested.stream().filter(ref -> type.equals(ref.type())).map(SourceRef::id).distinct().sorted().toList();
            for (int i = 0; i < ids.size(); i += BATCH_SIZE) {
                List<Long> batch = ids.subList(i, Math.min(ids.size(), i + BATCH_SIZE));
                List<Map<String, Object>> result = sources.currentSources(type, batch);
                if (result == null) throw new IllegalStateException("来源读取未完成");
                for (Map<String, Object> row : result) {
                    Long id = number(row.get("id"));
                    if (id == null || !batch.contains(id) || value(row.get("content")).isBlank()) continue;
                    SourceInput source = new SourceInput(type, id, value(row.get("title")), value(row.get("content")));
                    if (out.putIfAbsent(source.ref(), source) != null) throw new IllegalStateException("来源读取出现重复版本");
                }
            }
        }
        return out;
    }

    private List<WikiSourceDependency> dependencyRows(Collection<Long> pageIds) {
        List<Long> ids = pageIds.stream().sorted().toList();
        List<WikiSourceDependency> rows = new ArrayList<>();
        for (int i = 0; i < ids.size(); i += BATCH_SIZE) {
            List<WikiSourceDependency> result = dependencies.byPages(ids.subList(i, Math.min(ids.size(), i + BATCH_SIZE)));
            if (result == null) throw new IllegalStateException("依赖读取未完成");
            result.stream().filter(row -> row != null && pageIds.contains(row.getPageId())).forEach(rows::add);
        }
        return rows;
    }

    private record Check(Freshness freshness, List<Map<String, Object>> details) {}

    /** Request-local memoization: pages often share one long original document. */
    private static final class SourceState {
        private final SourceInput source;
        private final String fullHash;
        private List<TextChunker.Chunk> chunks;
        private SourceState(SourceInput source) { this.source = source; this.fullHash = sha256(source.fullContent()); }
        private List<TextChunker.Chunk> chunks() {
            if (chunks == null) chunks = TextChunker.splitWithHeadings(source.fullContent());
            return chunks;
        }
    }

    private static Map<SourceRef, SourceState> sourceStates(Map<SourceRef, SourceInput> input) {
        Map<SourceRef, SourceState> out = new LinkedHashMap<>();
        input.forEach((ref, source) -> out.put(ref, new SourceState(source)));
        return out;
    }

    private static Check check(WikiPage page, List<WikiSourceDependency> rows, Map<SourceRef, SourceState> current) {
        if (rows.isEmpty()) return new Check(unknown(page.getDependencyVersion() != null && page.getDependencyVersion() >= 2
                ? "dependency_record_missing" : "legacy_no_dependencies"), List.of());
        boolean integrity = rows.stream().allMatch(WikiDependencyService::validRow)
                && rows.stream().map(WikiSourceDependency::getSnapshotHash).distinct().count() == 1
                && rowFingerprint(rows).equals(rows.get(0).getSnapshotHash());
        if (!integrity) return new Check(unknown("dependency_record_invalid"), List.of());
        String pageHash = sha256(value(page.getContentMd()));
        Set<String> reasons = new LinkedHashSet<>();
        List<Map<String, Object>> details = new ArrayList<>();
        for (WikiSourceDependency row : rows) {
            SourceRef ref = new SourceRef(row.getSourceType(), row.getSourceId());
            SourceState source = current.get(ref);
            String fullHash = source == null ? "" : source.fullHash;
            List<TextChunker.Chunk> split = source == null ? List.of() : source.chunks();
            TextChunker.Chunk chunk = row.getSeq() < split.size() ? split.get(row.getSeq()) : null;
            String chunkHash = chunk == null ? "" : sha256(chunk.text());
            String status = "current";
            if (!row.getPageMdHash().equals(pageHash)) status = "page_changed";
            else if (source == null) status = "source_missing";
            else if (!row.getFullContentHash().equals(fullHash) || !row.getSourceTitle().equals(source.source.title())) status = "source_changed";
            else if (chunk == null || !row.getChunkHash().equals(chunkHash) || !row.getHeading().equals(chunk.heading())) status = "chunk_changed";
            if (!"current".equals(status)) reasons.add(status);
            Map<String, Object> detail = new LinkedHashMap<>();
            detail.put("sourceType", ref.type()); detail.put("sourceId", ref.id()); detail.put("sourceRef", ref.label());
            detail.put("title", row.getSourceTitle()); detail.put("seq", row.getSeq()); detail.put("heading", row.getHeading());
            detail.put("status", status); detail.put("reason", "current".equals(status) ? "source_unchanged" : status);
            detail.put("fullContentHash", row.getFullContentHash()); detail.put("chunkHash", row.getChunkHash());
            detail.put("currentFullContentHash", fullHash); detail.put("currentChunkHash", chunkHash);
            detail.put("chunkText", row.getChunkText()); detail.put("capturedAt", row.getCapturedAt());
            details.add(detail);
        }
        return new Check(new Freshness(reasons.isEmpty() ? Status.CURRENT : Status.STALE, List.copyOf(reasons)), List.copyOf(details));
    }

    private static boolean validRow(WikiSourceDependency row) {
        return row != null && row.getSourceId() != null && row.getSourceId() > 0 && validType(row.getSourceType())
                && row.getSeq() != null && row.getSeq() >= 0 && row.getSourceChunkCount() != null && row.getSeq() < row.getSourceChunkCount()
                && row.getSourceChars() != null && row.getSourceChars() > 0 && row.getSourceTitle() != null && row.getHeading() != null
                && row.getChunkText() != null && !row.getChunkText().isBlank() && sha256(row.getChunkText()).equals(row.getChunkHash())
                && hashValid(row.getFullContentHash()) && hashValid(row.getPageMdHash()) && hashValid(row.getSnapshotHash()) && row.getCapturedAt() != null;
    }

    private static List<WikiSourceDependency> toRows(Long pageId, String pageHash, Snapshot snapshot) {
        List<WikiSourceDependency> rows = new ArrayList<>();
        for (SourceSnapshot source : snapshot.sources()) for (SourceChunk chunk : source.chunks()) {
            WikiSourceDependency row = new WikiSourceDependency();
            row.setPageId(pageId); row.setSourceType(source.ref().type()); row.setSourceId(source.ref().id()); row.setSeq(chunk.seq());
            row.setSourceTitle(source.title()); row.setHeading(chunk.heading()); row.setChunkText(chunk.text()); row.setChunkHash(chunk.hash());
            row.setFullContentHash(source.fullContentHash()); row.setSourceChars(source.fullContent().length()); row.setSourceChunkCount(source.totalChunks());
            row.setPageMdHash(pageHash); row.setSnapshotHash(snapshot.hash()); row.setCapturedAt(snapshot.capturedAt()); rows.add(row);
        }
        return rows;
    }

    private static String snapshotHash(List<SourceSnapshot> material) {
        return rowFingerprint(toRows(0L, "", new Snapshot(LocalDateTime.MIN, material, List.of(), "")));
    }

    private static String rowFingerprint(List<WikiSourceDependency> rows) {
        StringBuilder fingerprint = new StringBuilder();
        rows.stream().sorted(Comparator.comparing(WikiSourceDependency::getSourceType).thenComparing(WikiSourceDependency::getSourceId).thenComparing(WikiSourceDependency::getSeq))
                .forEach(row -> {
                    for (Object field : List.of(row.getSourceType(), row.getSourceId(), row.getSeq(), row.getSourceTitle(), row.getHeading(),
                            row.getChunkHash(), row.getFullContentHash(), row.getSourceChars(), row.getSourceChunkCount())) {
                        String text = String.valueOf(field); fingerprint.append(text.length()).append(':').append(text).append(';');
                    }
                });
        return sha256(fingerprint.toString());
    }

    private static Map<String, Object> coverage(List<WikiSourceDependency> rows) {
        Map<String, WikiSourceDependency> distinct = new LinkedHashMap<>();
        rows.stream().filter(WikiDependencyService::validRow).forEach(row -> distinct.putIfAbsent(row.getSourceType() + ":" + row.getSourceId(), row));
        int totalChunks = distinct.values().stream().mapToInt(WikiSourceDependency::getSourceChunkCount).sum();
        Map<String, Object> coverage = new LinkedHashMap<>();
        coverage.put("sourceCount", distinct.size()); coverage.put("chunkCount", rows.size()); coverage.put("totalChunks", totalChunks);
        coverage.put("selectedChars", rows.stream().mapToInt(row -> value(row.getChunkText()).length()).sum());
        coverage.put("sourceChars", distinct.values().stream().mapToInt(WikiSourceDependency::getSourceChars).sum());
        coverage.put("complete", !rows.isEmpty() && rows.size() == totalChunks);
        List<Map<String, Object>> perSource = new ArrayList<>();
        for (WikiSourceDependency source : distinct.values()) {
            List<WikiSourceDependency> selected = rows.stream().filter(WikiDependencyService::validRow)
                    .filter(row -> row.getSourceType().equals(source.getSourceType()) && row.getSourceId().equals(source.getSourceId())).toList();
            Map<String, Object> part = new LinkedHashMap<>();
            part.put("sourceType", source.getSourceType()); part.put("sourceId", source.getSourceId()); part.put("title", source.getSourceTitle());
            part.put("sentChunkCount", selected.size()); part.put("totalChunkCount", source.getSourceChunkCount());
            part.put("usedChars", selected.stream().mapToInt(row -> row.getChunkText().length()).sum()); part.put("sourceChars", source.getSourceChars());
            perSource.add(part);
        }
        coverage.put("sources", List.copyOf(perSource));
        return coverage;
    }

    private static Set<SourceRef> sourceRefs(List<WikiSourceDependency> rows) {
        Set<SourceRef> refs = new LinkedHashSet<>();
        for (WikiSourceDependency row : rows) if (row.getSourceId() != null && row.getSourceId() > 0 && validType(row.getSourceType())) {
            refs.add(new SourceRef(row.getSourceType(), row.getSourceId()));
        }
        return refs;
    }

    private static Map<Long, WikiPage> pageMap(List<WikiPage> input) {
        Map<Long, WikiPage> out = new LinkedHashMap<>();
        for (WikiPage page : input == null ? List.<WikiPage>of() : input) if (page != null && page.getId() != null && page.getId() > 0) out.put(page.getId(), page);
        return out;
    }

    private static List<WikiSourceDependency> pageRows(List<WikiSourceDependency> rows, Long pageId) {
        return rows.stream().filter(row -> pageId.equals(row.getPageId())).toList();
    }

    private static List<SourceSnapshot> sortedSources(Collection<SourceSnapshot> material) {
        return material.stream().sorted(Comparator.comparing((SourceSnapshot s) -> s.ref().type()).thenComparing(s -> s.ref().id())).toList();
    }

    private static Freshness unknown(String reason) { return new Freshness(Status.UNKNOWN, List.of(reason), "dependency_record_invalid".equals(reason)); }
    private static String value(Object raw) { return raw == null ? "" : String.valueOf(raw); }
    private static Long number(Object raw) { return raw instanceof Number n && n.longValue() > 0 ? n.longValue() : null; }
    private static boolean hashValid(String hash) { return hash != null && hash.matches("[0-9a-f]{64}"); }
    private static boolean validType(String type) { return "note".equals(type) || "quick_ref".equals(type) || "file".equals(type); }
    private static String canonicalType(String type) {
        return switch (value(type)) {
            case "note" -> "note";
            case "ref", "quick_ref" -> "quick_ref";
            case "file" -> "file";
            default -> throw new IllegalArgumentException("不支持的来源类型：" + type);
        };
    }
    private static String marker(String type) { return switch (type) { case "note" -> "笔记"; case "quick_ref" -> "速查卡"; default -> "资料"; }; }
    public static String sha256(String raw) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value(raw).getBytes(StandardCharsets.UTF_8))); }
        catch (NoSuchAlgorithmException e) { throw new IllegalStateException("SHA-256 unavailable", e); }
    }
}
