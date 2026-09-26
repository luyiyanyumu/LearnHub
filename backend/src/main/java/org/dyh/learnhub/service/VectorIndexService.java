package org.dyh.learnhub.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.dyh.learnhub.ai.EmbeddingClient;
import org.dyh.learnhub.entity.KbChunk;
import org.dyh.learnhub.entity.KbIndexState;
import org.dyh.learnhub.mapper.KbChunkMapper;
import org.dyh.learnhub.mapper.KbIndexStateMapper;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 语义检索（向量）索引与检索。
 *
 * <h3>为什么要有这一层</h3>
 * 词面检索存在**语言鸿沟**：用户问「撤销暂存区的改动」，资料里写的是 {@code git reset} —— LIKE 直接 0 条。
 * 实测同一句话用语义向量算，与正确片段的相似度 0.67、与无关片段 0.47，区分得很干净。
 * 这就是"资料库 = 智能体知识储备、问什么都知道"的关键一层。
 *
 * <h3>三个刻意的选择</h3>
 * <ol>
 *   <li><b>不引向量库</b>：个人库只有几百块（18 万字 ÷ 800 ≈ 230），Java 里暴力余弦就是毫秒级；
 *       向量存 MySQL 的 BLOB，索引规模 1MB 量级。</li>
 *   <li><b>分块而非整篇</b>：整篇文档的向量会被内容稀释（11.8 万字的书只有一个向量等于没有），
 *       按 800 字切块 + 重叠，才能"定位到那一段"。</li>
 *   <li><b>索引状态可见</b>：块数 / 字数 / 模型 / 是否过期都暴露给界面 ——
 *       否则用户没法判断"我的资料进去了没有"，而这正是上一轮问题的根源。</li>
 * </ol>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class VectorIndexService {

    private final KbChunkMapper mapper;
    /** 按来源的索引指纹：增量索引靠它判断内容变了没有 */
    private final KbIndexStateMapper stateMapper;
    private final EmbeddingClient embedder;
    private final SettingsService settingsService;

    /**
     * 上下文嵌入开关（默认开）：把「文档标题 · 小节」前置到块正文之前再向量化。
     * <p>留成开关是为了能**做 A/B 实测**：关掉重建一次、跑同一组评测用例，用 recall@k 说话。
     * 值写在 app_setting 里；空或缺省都算开。
     */
    public static final String SETTING_CONTEXTUAL_EMBED = "kb.contextual_embed";

    /**
     * 相似度下限。实测（bge-m3，本库）：
     * <pre>
     *   无关问题：「红烧肉怎么做」/「明天天气」 vs 一条 Git 片段 → 0.24 / 0.36
     *   同一知识换说法：「撤销暂存区的改动」 vs 该片段          → ~0.50~0.68
     * </pre>
     * 所以 0.45 落在两者之间：既不会把无关内容拉进来，也不会因为"卡片里写的是『取消暂存』
     * 而用户说的是『撤销暂存』"这种用词差就漏掉（第一版设 0.52，实测漏过这一条）。
     */
    private static final double MIN_SCORE = 0.45;

    /**
     * 相对带：只保留与最高分相差不超过这个值的命中。
     * <p>语义相似度在同一领域内会被"压扁"（一堆 0.5 上下），绝对阈值不足以筛掉尾巴，
     * 用"离最好那条太远就不要"来收紧，比再抬绝对阈值稳。
     *
     * <p><b>实测没能放宽</b>：一度把它从 0.08 放宽到 0.20，想让"分数偏低但正确"的候选进池子交给重排挑。
     * 结果 85 条用例 recall 不变、**MRR 从 0.921 掉到 0.884** —— 池子里多了噪声，重排补不回来。
     * 结论：召回阶段的收紧 + 重排的挑选，这个分工是对的；放宽带子是把噪声问题推给下一层，
     * 而下一层并没有能力全兜住。所以退回 0.08。
     */
    private static final double RELATIVE_BAND = 0.08;
    /** 检索默认返回条数 */
    public static final int DEFAULT_TOP_K = 6;

    // ------------------------------------------------------------------
    // 索引构建（异步任务 + 进度）
    // ------------------------------------------------------------------

    /** 构建任务：界面靠它显示"索引进行到哪了" */
    public static final class Job {
        public final String id;
        public final long startedAt = System.currentTimeMillis();
        public volatile String stage = "收集素材";
        public volatile int total;
        public volatile int done;
        public volatile int chunks;
        public volatile int chars;
        public volatile long finishedAt;
        public volatile String status = "running";
        public volatile String error;

        Job(String id) {
            this.id = id;
        }
    }

    private final ExecutorService runner = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "vector-index");
        t.setDaemon(true);
        return t;
    });
    private final Map<String, Job> jobs = new ConcurrentHashMap<>();

    public Map<String, Object> startRebuild() {
        Job job = new Job(UUID.randomUUID().toString().substring(0, 8));
        jobs.put(job.id, job);
        runner.submit(() -> rebuild(job));
        return jobView(job.id);
    }

    public Map<String, Object> jobView(String id) {
        Job j = jobs.get(id);
        if (j == null) {
            throw new IllegalArgumentException("任务不存在或已过期: " + id);
        }
        Map<String, Object> o = new LinkedHashMap<>();
        o.put("jobId", j.id);
        o.put("stage", j.stage);
        o.put("total", j.total);
        o.put("done", j.done);
        o.put("chunks", j.chunks);
        o.put("chars", j.chars);
        o.put("percent", j.total <= 0 ? 0 : Math.min(100, (int) Math.round(j.done * 100.0 / j.total)));
        o.put("status", j.status);
        o.put("error", j.error);
        o.put("elapsedMs", (j.finishedAt == 0 ? System.currentTimeMillis() : j.finishedAt) - j.startedAt);
        return o;
    }

    /** 全量重建：清空后按来源逐条分块 + 嵌入 + 落库（进度按来源数推进） */
    private void rebuild(Job job) {
        try {
            job.stage = "收集素材";
            List<Source> sources = collectSources();
            job.total = sources.size();
            if (sources.isEmpty()) {
                job.stage = "没有可索引的内容";
                job.status = "done";
                job.finishedAt = System.currentTimeMillis();
                return;
            }
            job.stage = "清空旧索引";
            mapper.delete(Wrappers.<KbChunk>lambdaQuery());
            stateMapper.delete(Wrappers.<KbIndexState>lambdaQuery());
            invalidateCache();

            job.stage = "分块并嵌入";
            String model = embedder.model();
            int inserted = 0;
            for (Source s : sources) {
                inserted += indexSource(s, model);
                job.chunks = inserted;
                job.chars += s.text().length();
                job.done++;
            }
            invalidateCache();
            job.stage = "完成";
            job.status = "done";
            job.finishedAt = System.currentTimeMillis();
            log.info("语义索引重建完成：{} 个来源 → {} 块，{} 字，模型 {}，耗时 {} ms",
                    sources.size(), inserted, job.chars, model, job.finishedAt - job.startedAt);
        } catch (Exception e) {
            log.warn("语义索引重建失败：{}", e.toString());
            job.status = "failed";
            job.error = e.getMessage() == null ? e.toString() : e.getMessage();
            job.stage = "失败";
            job.finishedAt = System.currentTimeMillis();
        }
    }

    /**
     * 索引一个来源：切块（**带小节归属**）→ 前置「标题 · 小节」再嵌入 → 落库 → 记指纹。
     *
     * @return 写入的块数
     */
    private int indexSource(Source s, String model) {
        mapper.deleteBySource(s.type(), s.id());
        List<TextChunker.Chunk> chunks = TextChunker.splitWithHeadings(s.text());
        if (chunks.isEmpty()) {
            stateMapper.delete(Wrappers.<KbIndexState>lambdaQuery()
                    .eq(KbIndexState::getSourceType, s.type())
                    .eq(KbIndexState::getSourceId, s.id()));
            return 0;
        }
        // 嵌入文本 = 上下文 + 正文。注意 chunk_text 存的仍是**正文**（含小节标记会让引用看起来脏），
        // 上下文只影响向量 —— 这样既不丢检索效果，也不污染给模型看的片段。
        // 这个开关存在的意义是**做 A/B 实测**："上下文嵌入到底有没有用"不能靠直觉，
        // 关掉重建一次、跑同一组评测用例，两个 recall@k 一比就知道。
        boolean contextual = !"0".equals(settingsService.effective(SETTING_CONTEXTUAL_EMBED));
        List<String> embedInputs = new ArrayList<>(chunks.size());
        for (TextChunker.Chunk c : chunks) {
            embedInputs.add(contextual
                    ? TextChunker.embedText(s.title(), c.heading(), c.text())
                    : c.text());
        }
        List<float[]> vecs = embedder.embedAll(embedInputs);   // 内部按 16 条一批
        for (int i = 0; i < chunks.size(); i++) {
            TextChunker.Chunk c = chunks.get(i);
            KbChunk row = new KbChunk();
            row.setSourceType(s.type());
            row.setSourceId(s.id());
            row.setSeq(i);
            row.setTitle(clip(s.title(), 250));
            row.setCategory(clip(s.category(), 250));
            row.setHeading(clip(c.heading(), 250));
            row.setChunkText(c.text());
            row.setCharLen(c.text().length());
            row.setVec(EmbeddingClient.toBytes(vecs.get(i)));
            row.setDim(vecs.get(i).length);
            row.setModel(model);
            row.setUpdatedAt(LocalDateTime.now());
            row.setCreatedAt(LocalDateTime.now());
            mapper.insert(row);
        }
        upsertState(s, chunks.size());
        return chunks.size();
    }

    /** 记录/更新该来源已索引内容的指纹（增量索引靠它判断"变了没有"） */
    private void upsertState(Source s, int chunkCount) {
        String hash = KgService.sha256((s.title() == null ? "" : s.title())
                + "\u0000" + (s.text() == null ? "" : s.text()));
        String key = KbIndexState.key(s.type(), s.id());
        KbIndexState st = stateMapper.selectById(key);
        if (st == null) {
            st = new KbIndexState();
            st.setId(key);
            st.setSourceType(s.type());
            st.setSourceId(s.id());
            st.setContentHash(hash);
            st.setChunks(chunkCount);
            stateMapper.insert(st);
        } else {
            st.setContentHash(hash);
            st.setChunks(chunkCount);
            st.setIndexedAt(LocalDateTime.now());
            stateMapper.updateById(st);
        }
    }

    /**
     * 增量索引：只重编**内容指纹变了**的来源，顺带清掉已不存在的来源。
     *
     * <p>为什么用指纹对比而不是"接收变更事件里的 id"：
     * 写入路径不止一条（页面保存、智能体确认执行、资料上传、直接改库），
     * 靠事件传 id 一定会漏；而指纹对比是**幂等且完备**的 —— 跑一次就能收敛，
     * 漏掉的、删除的都能发现。代价只是对每个来源算一次 sha256（几百 KB 文本，毫秒级）。
     *
     * @return 重编了多少个来源、删了多少个来源
     */
    public Map<String, Object> reindexChanged() {
        List<Source> sources = collectSources();
        String model = embedder.model();
        Map<String, KbIndexState> known = new LinkedHashMap<>();
        for (KbIndexState st : stateMapper.selectList(null)) {
            known.put(st.getSourceType() + "#" + st.getSourceId(), st);
        }
        int reindexed = 0;
        int removed = 0;
        int chunks = 0;
        Set<String> alive = new LinkedHashSet<>();
        for (Source s : sources) {
            String key = s.type() + "#" + s.id();
            alive.add(key);
            String hash = KgService.sha256((s.title() == null ? "" : s.title())
                    + "\u0000" + (s.text() == null ? "" : s.text()));
            KbIndexState st = known.get(key);
            if (st != null && hash.equals(st.getContentHash())) {
                continue;   // 没变，跳过（这就是省下来的时间）
            }
            chunks += indexSource(s, model);
            reindexed++;
        }
        for (Map.Entry<String, KbIndexState> e : known.entrySet()) {
            if (!alive.contains(e.getKey())) {
                KbIndexState st = e.getValue();
                mapper.deleteBySource(st.getSourceType(), st.getSourceId());
                stateMapper.deleteById(st.getId());
                removed++;
            }
        }
        if (reindexed > 0 || removed > 0) {
            invalidateCache();
            log.info("增量索引：重编 {} 个来源（{} 块），清理 {} 个已删除来源", reindexed, chunks, removed);
        }
        Map<String, Object> o = new LinkedHashMap<>();
        o.put("sources", sources.size());
        o.put("reindexed", reindexed);
        o.put("removed", removed);
        o.put("chunks", chunks);
        return o;
    }

    private record Source(String type, Long id, String title, String category, String text) {
    }

    // ------------------------------------------------------------------
    // 自动增量索引（挂在知识变更事件上）
    // ------------------------------------------------------------------

    /**
     * 自动增量索引开关（默认开）。
     * <p>实现在监听器里做两件事：**防抖**（连续编辑只跑一趟）与**指纹再验**（见 reindexChanged）。
     * 有指纹兜底，所以"改一个字就花一次嵌入"不会发生 —— 只有内容真的变了才有嵌入调用；
     * 而指纹对比本身只要 245ms（12 个来源）。
     */
    public static final String SETTING_AUTO_INDEX = "kb.auto_index";
    private static final long AUTO_INDEX_DEBOUNCE_MS = 20_000;
    private static final long AUTO_INDEX_MIN_GAP_MS = 30_000;

    private final java.util.concurrent.ScheduledExecutorService scheduler =
            java.util.concurrent.Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "vector-auto-index");
                t.setDaemon(true);
                return t;
            });
    private final java.util.concurrent.atomic.AtomicBoolean autoQueued =
            new java.util.concurrent.atomic.AtomicBoolean(false);
    private volatile long lastAutoRunAt = 0;

    public boolean autoIndexEnabled() {
        return !"0".equals(settingsService.effective(SETTING_AUTO_INDEX));
    }

    /**
     * 内容变更 → 排一次增量索引。
     * <p><b>等事务提交后</b>再跑：后台线程走另一条连接，提交前读不到新内容
     * （wiki 那边踩过这个坑，见 WikiService#onKnowledgeChanged）。
     * <p>只做**增量**、绝不自动全量重建：全量会重算所有块的向量（23 秒、真花钱），
     * 而增量只对变了的来源付嵌入成本。
     */
    @org.springframework.transaction.event.TransactionalEventListener(
            phase = org.springframework.transaction.event.TransactionPhase.AFTER_COMMIT,
            fallbackExecution = true)
    public void onKnowledgeChanged(org.dyh.learnhub.common.KnowledgeChangedEvent event) {
        if (!autoIndexEnabled()) {
            return;
        }
        long since = System.currentTimeMillis() - lastAutoRunAt;
        if (since < AUTO_INDEX_MIN_GAP_MS) {
            // 刚跑过就跳过：短时间内高频改动没必要每次都扫（下次事件会再来）
            return;
        }
        if (autoQueued.compareAndSet(false, true)) {
            scheduler.schedule(this::runAutoIndex, AUTO_INDEX_DEBOUNCE_MS,
                    java.util.concurrent.TimeUnit.MILLISECONDS);
        }
    }

    private void runAutoIndex() {
        autoQueued.set(false);
        try {
            long t0 = System.currentTimeMillis();
            Map<String, Object> r = reindexChanged();
            lastAutoRunAt = System.currentTimeMillis();
            log.info("自动增量索引：重编 {} 个来源、清理 {} 个，耗时 {} ms",
                    r.get("reindexed"), r.get("removed"), lastAutoRunAt - t0);
        } catch (Exception e) {
            log.warn("自动增量索引失败（下次变更会再试）：{}", e.toString());
        }
    }

    private List<Source> collectSources() {
        List<Source> list = new ArrayList<>();
        for (Map<String, Object> r : mapper.allNotes()) {
            list.add(new Source("note", num(r.get("id")), str(r.get("title")), str(r.get("category")), str(r.get("content"))));
        }
        for (Map<String, Object> r : mapper.allRefs()) {
            list.add(new Source("quick_ref", num(r.get("id")), str(r.get("title")), str(r.get("category")), str(r.get("content"))));
        }
        for (Map<String, Object> r : mapper.allFiles()) {
            list.add(new Source("file", num(r.get("id")), str(r.get("title")), str(r.get("category")), str(r.get("content"))));
        }
        return list;
    }

    // ------------------------------------------------------------------
    // 检索
    // ------------------------------------------------------------------

    /** 一条语义命中 */
    public record Hit(String sourceType, Long sourceId, String title, String category,
                      String text, double score, int seq) {
    }

    /** 内存缓存：几百块 + 向量约 1MB，避免每次提问都从库里读 */
    private volatile List<KbChunk> cache;
    private volatile String cacheFingerprint;

    private void invalidateCache() {
        cache = null;
        cacheFingerprint = null;
    }

    private List<KbChunk> chunks() {
        String fp = mapper.fingerprint();
        List<KbChunk> local = cache;
        if (local != null && fp != null && fp.equals(cacheFingerprint)) {
            return local;
        }
        local = mapper.loadAll();
        cache = local;
        cacheFingerprint = fp;
        return local;
    }

    /**
     * 语义检索。
     *
     * @param query 用户问题（原话即可，不需要关键词）
     * @param topK  返回条数上限
     * @return 按相似度降序；低于 {@link #MIN_SCORE} 的丢掉
     */
    public List<Hit> search(String query, int topK) {
        if (query == null || query.isBlank() || !settingsService.vectorEnabled()) {
            return List.of();
        }
        List<KbChunk> all = chunks();
        if (all.isEmpty()) {
            return List.of();
        }
        float[] qv;
        try {
            qv = embedder.embed(query);
        } catch (Exception e) {
            // 嵌入失败不能影响对话：退化成"这轮没有语义命中"
            log.warn("查询嵌入失败（本轮无语义召回）：{}", e.toString());
            return List.of();
        }
        if (qv == null) {
            return List.of();
        }
        List<Hit> hits = new ArrayList<>();
        for (KbChunk c : all) {
            float[] v = EmbeddingClient.toVector(c.getVec());
            double score = EmbeddingClient.cosine(qv, v);
            if (score >= MIN_SCORE) {
                hits.add(new Hit(c.getSourceType(), c.getSourceId(), c.getTitle(), c.getCategory(),
                        c.getChunkText(), score, c.getSeq()));
            }
        }
        hits.sort(Comparator.comparingDouble(Hit::score).reversed());
        if (hits.isEmpty()) {
            return List.of();
        }
        // 相对带：离最高分太远的尾巴丢掉（同领域相似度被压扁，绝对阈值筛不干净）
        double best = hits.get(0).score();
        List<Hit> kept = new ArrayList<>();
        for (Hit h : hits) {
            if (h.score() >= best - RELATIVE_BAND) {
                kept.add(h);
            }
        }
        return kept.size() > topK ? kept.subList(0, topK) : kept;
    }

    // ------------------------------------------------------------------
    // 状态 / 体检
    // ------------------------------------------------------------------

    /** 索引状态：块数、字数、模型、来源数、是否与当前内容一致 */
    public Map<String, Object> status() {
        Map<String, Object> o = new LinkedHashMap<>();
        long chunks = mapper.selectCount(null);
        String fp = mapper.fingerprint();
        String[] parts = fp == null ? new String[]{"0", "0", "", "0"} : fp.split(":", -1);
        o.put("chunks", chunks);
        o.put("indexedChars", parts.length > 1 ? parseLong(parts[1]) : 0);
        o.put("indexedModel", parts.length > 2 ? parts[2] : "");
        o.put("indexedSources", parts.length > 3 ? parseLong(parts[3]) : 0);
        o.put("currentSources", collectSources().size());
        o.put("model", embedder.model());
        o.put("baseUrl", embedder.baseUrl());
        o.put("enabled", settingsService.vectorEnabled());
        String sourceLatest = mapper.sourceLatestChange();
        String indexedAt = mapper.indexedAt();
        o.put("sourceLatestChange", sourceLatest);
        o.put("indexedAt", indexedAt);
        // 过期判定（三选一即过期）：
        //   ① 从来没有索引过；② 来源数量变了（增删）；③ 有内容在索引之后被改过
        // ③ 是关键：只比数量的话，"改一篇笔记"探测不到 —— 索引里的向量会一直是旧的。
        boolean stale = chunks == 0
                || !String.valueOf(o.get("currentSources")).equals(String.valueOf(o.get("indexedSources")))
                || !embedder.model().equals(o.get("indexedModel"))
                || (!sourceLatest.isBlank() && !indexedAt.isBlank() && sourceLatest.compareTo(indexedAt) > 0);
        o.put("stale", stale);
        return o;
    }

    /**
     * 检索体检：同一个问题，三种检索各返回什么。
     * <p>这是"我把资料做成知识储备了没有"**可验证**的地方 —— 不用感觉，直接对比：
     * 换一种说法的问句，词面可能 0 条，语义应该照样命中。
     */
    public Map<String, Object> probe(String query, int topK) {
        Map<String, Object> o = new LinkedHashMap<>();
        o.put("query", query);
        o.put("keyword", List.of());   // 由 KnowledgeService 填（避免这里反向依赖）
        o.put("vector", search(query, topK).stream().map(h -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("type", h.sourceType());
            m.put("id", h.sourceId());
            m.put("title", h.title());
            m.put("category", h.category());
            m.put("score", Math.round(h.score() * 1000) / 1000.0);
            m.put("text", clip(h.text(), 160));
            return m;
        }).toList());
        return o;
    }

    /** 供调试/日志：索引里有什么模型 */
    public String modelInfo() {
        return embedder.model() + "@" + embedder.baseUrl();
    }

    // ---------------- 小工具 ----------------

    private static Long num(Object o) {
        return o instanceof Number n ? n.longValue() : null;
    }

    private static String str(Object o) {
        return o == null ? "" : String.valueOf(o);
    }

    private static long parseLong(String s) {
        try {
            return Long.parseLong(s.trim());
        } catch (Exception e) {
            return 0;
        }
    }

    private static String clip(String s, int max) {
        if (s == null) {
            return null;
        }
        String t = s.trim();
        return t.length() > max ? t.substring(0, max) : t;
    }

    /** 去重用的键（hybrid 合并时用） */
    public static String key(String sourceType, Long sourceId) {
        return sourceType + "-" + sourceId;
    }

    /** 去重：同一来源只保留最高分的那条 */
    public static Set<String> keysOf(List<Hit> hits) {
        Set<String> out = new LinkedHashSet<>();
        for (Hit h : hits) {
            out.add(key(h.sourceType(), h.sourceId()));
        }
        return out;
    }
}
