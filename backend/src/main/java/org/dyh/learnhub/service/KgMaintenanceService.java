package org.dyh.learnhub.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.dyh.learnhub.entity.AppSetting;
import org.dyh.learnhub.entity.KgRelation;
import org.dyh.learnhub.mapper.AppSettingMapper;
import org.dyh.learnhub.mapper.KbChunkMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/** Bounded, restart-safe concept graph upkeep. Model calls never run inside the database transaction. */
@Service
public class KgMaintenanceService {
    static final String STATE_KEY = "internal.kg_maintenance.v1";
    static final int MODEL_BATCHES_PER_SOURCE = 3;
    private final KbChunkMapper sources;
    private final AppSettingMapper settings;
    private final TripleExtractor extractor;
    private final KgGraphService graph;
    private final ObjectMapper json;
    private final TransactionTemplate transaction;

    public KgMaintenanceService(KbChunkMapper sources, AppSettingMapper settings, TripleExtractor extractor,
                                KgGraphService graph, ObjectMapper json, PlatformTransactionManager transactions) {
        this.sources = sources; this.settings = settings; this.extractor = extractor;
        this.graph = graph; this.json = json; this.transaction = new TransactionTemplate(transactions);
    }

    /** Process a bounded number of changed/deleted sources. complete=false means the next pass continues. */
    public synchronized Map<String, Object> maintain(int maxSources) {
        int limit = Math.max(1, Math.min(20, maxSources));
        State state = loadState();
        String model = extractor.modelFingerprint();
        Map<String, Source> snapshot = collectSources();
        List<String> pending = rotate(pending(state, snapshot, model), state.lastAttempt);
        int processed = 0;
        int advanced = 0;
        List<String> failed = new ArrayList<>();
        RuntimeException firstFailure = null;
        for (String cite : pending.subList(0, Math.min(limit, pending.size()))) {
            try {
                SourceOutcome outcome = maintainSource(state, snapshot, cite, model);
                state = outcome.state();
                if (outcome.complete()) processed++; else advanced++;
            } catch (RuntimeException error) {
                // A malformed reply or an unusually large source must not starve every later source.
                // This is only a scheduling cursor: its success checkpoint and existing graph stay intact.
                state.lastAttempt = cite;
                saveState(state);
                failed.add(cite);
                if (firstFailure == null) firstFailure = error;
            }
        }
        if (firstFailure != null) {
            throw new IllegalStateException("概念图谱已维护 " + processed + " 个来源；" + String.join("、", failed)
                    + " 尚未完成，保留已有关系并将在后续重试", firstFailure);
        }
        int remaining = pending(state, collectSources(), extractor.modelFingerprint()).size();
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("complete", remaining == 0);
        result.put("processed", processed);
        result.put("remaining", remaining);
        result.put("message", processed == 0 && advanced == 0 ? "概念图谱素材无变化" : "概念图谱已维护 " + processed + " 个来源"
                + (advanced == 0 ? "" : "，另有 " + advanced + " 个长来源已保存分批进度")
                + (remaining == 0 ? "" : "，剩余 " + remaining + " 个将在后续继续"));
        return result;
    }

    private SourceOutcome maintainSource(State state, Map<String, Source> snapshot, String cite, String model) {
        Source source = snapshot.get(cite);
        String expected = fingerprint(source, model);
        // Track even first-time sources before staging results, so deleting one also cleans its partial cache.
        if (source != null && state.inProgress.add(cite)) saveState(state);
        Extraction extracted = source == null ? null : extractBounded(source, model);
        if (extracted != null && !extracted.complete) {
            State next = state.copy();
            next.lastAttempt = cite;
            saveState(next);
            return new SourceOutcome(next, false);
        }
        // Capture all remaining evidence sources after extraction as well, rather than trusting a stale body.
        Map<String, Source> latest = unchangedSources(cite, expected, model);
        // Adopt in-memory progress only after the transaction has actually committed.
        State committed = transaction.execute(status -> {
            Source locked = readLocked(cite);
            if (!model.equals(extractor.modelFingerprint())
                    || !java.util.Objects.equals(expected, fingerprint(locked, model))) {
                throw new IllegalStateException("图谱素材已变化，保留已有关系和维护进度");
            }
            Map<String, String> bodies = new LinkedHashMap<>();
            latest.forEach((key, value) -> bodies.put(key, value.body()));
            if (locked != null) bodies.put(cite, locked.body()); else bodies.remove(cite);
            Set<String> refresh = graph.replaceSourceSupport(cite,
                    extracted == null ? List.of() : extracted.triples, bodies,
                    extracted == null ? null : extracted.model);
            State next = state.copy();
            if (expected == null) next.checkpoints.remove(cite); else next.checkpoints.put(cite, expected);
            next.inProgress.remove(cite);
            next.refresh.remove(cite);
            next.refresh.addAll(refresh);
            next.refresh.retainAll(latest.keySet());
            next.lastAttempt = cite;
            saveState(next);
            settings.deleteById(extractionKey(cite));
            return next;
        });
        return new SourceOutcome(committed, true);
    }

    private Extraction extractBounded(Source source, String model) {
        List<String> parts;
        try { parts = TripleExtractor.splitSource(source.body(), 4000); }
        catch (IllegalStateException oversizedBlock) {
            throw new IllegalStateException(source.cite() + "：" + oversizedBlock.getMessage(), oversizedBlock);
        }
        String expected = source.fingerprint(model);
        Extraction progress = loadExtraction(source.cite(), expected, parts.size());
        List<String> known = graph.nodes().stream().map(node -> node.getName()).toList();
        int end = Math.min(parts.size(), progress.nextBatch + MODEL_BATCHES_PER_SOURCE);
        for (int i = progress.nextBatch; i < end; i++) {
            TripleExtractor.Result batch;
            try { batch = extractor.extractSourceStrict(source.cite(), source.title(), parts.get(i), known); }
            catch (RuntimeException error) {
                throw new IllegalStateException(source.cite() + " 第 " + (i + 1) + "/" + parts.size()
                        + " 批抽取未完成，已保留前 " + i + " 批进度", error);
            }
            unchangedSources(source.cite(), expected, model);
            progress.add(batch.triples());
            progress.model = batch.model();
            progress.nextBatch = i + 1;
            // Keep completed batches across failures/restarts. This is not a successful source watermark.
            saveExtraction(source.cite(), expected, progress);
        }
        progress.complete = progress.nextBatch == parts.size();
        return progress;
    }

    private Map<String, Source> unchangedSources(String cite, String expected, String model) {
        Map<String, Source> latest = collectSources();
        if (!model.equals(extractor.modelFingerprint())
                || !java.util.Objects.equals(expected, fingerprint(latest.get(cite), model))) {
            throw new IllegalStateException("图谱素材或抽取模型已变化，将在下轮重试");
        }
        return latest;
    }

    static String extractionKey(String cite) { return STATE_KEY + ".source." + cite.replace('#', '.'); }

    private Extraction loadExtraction(String cite, String fingerprint, int totalBatches) {
        AppSetting row = settings.selectById(extractionKey(cite));
        Extraction progress = new Extraction();
        if (row == null || row.getSettingValue() == null || row.getSettingValue().isBlank()) return progress;
        try {
            JsonNode root = json.readTree(row.getSettingValue());
            if (!fingerprint.equals(root.path("fingerprint").asText())) return progress;
            if (root.path("version").asInt() != 1 || !root.path("triples").isArray()
                    || !root.path("nextBatch").canConvertToInt()) throw new IllegalArgumentException("分批进度格式无效");
            progress.nextBatch = root.path("nextBatch").asInt();
            if (progress.nextBatch < 0 || progress.nextBatch > totalBatches)
                throw new IllegalArgumentException("分批进度超出来源批次数");
            progress.model = root.path("model").asText("");
            for (JsonNode triple : root.path("triples")) progress.triples.add(json.treeToValue(triple, TripleExtractor.Triple.class));
            return progress;
        } catch (Exception error) {
            throw new IllegalStateException(cite + " 图谱分批进度无法读取，保留已有关系", error);
        }
    }

    private void saveExtraction(String cite, String fingerprint, Extraction progress) {
        AppSetting row = new AppSetting(); row.setSettingKey(extractionKey(cite));
        try {
            row.setSettingValue(json.writeValueAsString(Map.of("version", 1, "fingerprint", fingerprint,
                    "nextBatch", progress.nextBatch, "model", progress.model == null ? "" : progress.model,
                    "triples", progress.triples)));
        } catch (Exception error) { throw new IllegalStateException("图谱分批进度无法保存", error); }
        if (settings.selectById(row.getSettingKey()) == null) settings.insert(row); else settings.updateById(row);
    }

    private static List<String> rotate(List<String> pending, String cursor) {
        if (cursor == null || pending.isEmpty()) return pending;
        int start = 0;
        while (start < pending.size() && pending.get(start).compareTo(cursor) <= 0) start++;
        if (start == 0 || start == pending.size()) return pending;
        List<String> rotated = new ArrayList<>(pending.subList(start, pending.size()));
        rotated.addAll(pending.subList(0, start));
        return rotated;
    }

    private List<String> pending(State state, Map<String, Source> current, String model) {
        Set<String> keys = new TreeSet<>(current.keySet());
        keys.addAll(state.checkpoints.keySet());
        keys.addAll(state.refresh);
        keys.addAll(state.inProgress);
        // Adopt pre-existing automatic facts too: deleted legacy sources must not remain forever.
        for (KgRelation relation : graph.relations()) {
            if (!"llm".equals(relation.getOrigin())) continue;
            for (String cite : KgGraphService.splitAliases(relation.getSources())) {
                if (validCite(cite)) keys.add(cite);
            }
        }
        List<String> changed = new ArrayList<>();
        for (String cite : keys) {
            Source source = current.get(cite);
            if (source == null || state.refresh.contains(cite) || state.inProgress.contains(cite)
                    || !source.fingerprint(model).equals(state.checkpoints.get(cite))) changed.add(cite);
        }
        return changed;
    }

    private Map<String, Source> collectSources() {
        Map<String, Source> result = new TreeMap<>();
        addSources(result, "笔记", sources.allNotes());
        addSources(result, "速查卡", sources.allRefs());
        addSources(result, "资料", sources.allFiles());
        return result;
    }

    private static void addSources(Map<String, Source> target, String marker, List<Map<String, Object>> rows) {
        if (rows == null) return;
        for (Map<String, Object> row : rows) {
            if (row.get("id") == null) continue;
            String cite = marker + "#" + row.get("id");
            Source source = source(cite, row);
            if (source != null) target.put(cite, source);
        }
    }

    private Source readLocked(String cite) {
        String[] key = cite.split("#", 2);
        long id = Long.parseLong(key[1]);
        Map<String, Object> row = switch (key[0]) {
            case "笔记" -> sources.maintenanceNote(id);
            case "速查卡" -> sources.maintenanceRef(id);
            case "资料" -> sources.maintenanceFile(id);
            default -> throw new IllegalStateException("维护来源标识无效");
        };
        return source(cite, row);
    }

    private static Source source(String cite, Map<String, Object> row) {
        if (row == null) return null;
        String body = text(row.get("content"));
        return body.isBlank() ? null : new Source(cite, text(row.get("title")), body);
    }

    private State loadState() {
        AppSetting row = settings.selectById(STATE_KEY);
        State state = new State();
        if (row == null || row.getSettingValue() == null || row.getSettingValue().isBlank()) return state;
        try {
            JsonNode root = json.readTree(row.getSettingValue());
            if (!root.isObject() || root.path("version").asInt() != 1 || !root.path("checkpoints").isObject()
                    || !root.path("refresh").isArray()) throw new IllegalArgumentException("检查点格式无效");
            root.path("checkpoints").fields().forEachRemaining(entry -> {
                if (validCite(entry.getKey()) && entry.getValue().isTextual())
                    state.checkpoints.put(entry.getKey(), entry.getValue().asText());
            });
            for (JsonNode cite : root.path("refresh")) if (cite.isTextual() && validCite(cite.asText())) state.refresh.add(cite.asText());
            for (JsonNode cite : root.path("inProgress")) if (cite.isTextual() && validCite(cite.asText())) state.inProgress.add(cite.asText());
            String cursor = root.path("lastAttempt").asText("");
            if (validCite(cursor)) state.lastAttempt = cursor;
            return state;
        } catch (Exception error) {
            throw new IllegalStateException("概念图谱维护检查点无法读取，保留已有关系", error);
        }
    }

    private void saveState(State state) {
        AppSetting row = new AppSetting(); row.setSettingKey(STATE_KEY);
        try {
            row.setSettingValue(json.writeValueAsString(Map.of("version", 1, "checkpoints", state.checkpoints,
                    "refresh", state.refresh, "inProgress", state.inProgress,
                    "lastAttempt", state.lastAttempt == null ? "" : state.lastAttempt)));
        } catch (Exception error) { throw new IllegalStateException("图谱维护检查点无法保存", error); }
        if (settings.selectById(STATE_KEY) == null) settings.insert(row); else settings.updateById(row);
    }

    private static String text(Object value) { return value == null ? "" : String.valueOf(value); }
    private static boolean validCite(String cite) {
        if (cite == null || !cite.matches("(?:笔记|速查卡|资料)#[0-9]+")) return false;
        try { Long.parseLong(cite.substring(cite.indexOf('#') + 1)); return true; }
        catch (NumberFormatException invalidLegacyId) { return false; }
    }
    private static String fingerprint(Source source, String model) { return source == null ? null : source.fingerprint(model); }
    private record Source(String cite, String title, String body) {
        String fingerprint(String model) {
            try {
                MessageDigest digest = MessageDigest.getInstance("SHA-256");
                for (String part : List.of("kg-source-v1", cite, title, body, model)) {
                    byte[] bytes = part.getBytes(StandardCharsets.UTF_8);
                    digest.update(java.nio.ByteBuffer.allocate(4).putInt(bytes.length).array()); digest.update(bytes);
                }
                return HexFormat.of().formatHex(digest.digest());
            } catch (Exception error) { throw new IllegalStateException(error); }
        }
    }
    private record SourceOutcome(State state, boolean complete) { }
    private static final class Extraction {
        int nextBatch;
        String model;
        boolean complete;
        final List<TripleExtractor.Triple> triples = new ArrayList<>();
        void add(List<TripleExtractor.Triple> batch) {
            Set<String> seen = new LinkedHashSet<>();
            for (TripleExtractor.Triple triple : triples) seen.add(key(triple));
            for (TripleExtractor.Triple triple : batch) if (seen.add(key(triple))) triples.add(triple);
        }
        private static String key(TripleExtractor.Triple triple) {
            return EntityLinker.normalize(triple.head()) + "|" + triple.relation() + "|" + EntityLinker.normalize(triple.tail());
        }
    }
    private static final class State {
        final Map<String, String> checkpoints = new TreeMap<>();
        final Set<String> refresh = new LinkedHashSet<>();
        final Set<String> inProgress = new LinkedHashSet<>();
        String lastAttempt;
        State copy() {
            State copy = new State(); copy.checkpoints.putAll(checkpoints); copy.refresh.addAll(refresh);
            copy.inProgress.addAll(inProgress); copy.lastAttempt = lastAttempt; return copy;
        }
    }
}
