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
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

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
    /** 全量扫描后端（默认）：向量存在 kb_chunk.vec，检索在内存里算余弦 */
    private final org.dyh.learnhub.service.vector.MysqlVectorStore mysqlStore;
    /** ANN 后端：按 kb.vector_backend 切换，连不上时**自动降级**回上面的全扫 */
    private final org.dyh.learnhub.service.vector.MilvusVectorStore milvusStore;
    private final PlatformTransactionManager transactionManager;
    /** 重启或同步失败后先读 MySQL；完整同步成功才信任指定空间和服务的 ANN。 */
    private record AnnReadiness(String space, String uri) { }
    private volatile AnnReadiness annReadiness;

    /**
     * 向量后端开关：空/`mysql` = 全量扫描（默认）；`milvus` = ANN。
     * <p>为什么必须做成开关：① Milvus 不可用时要有回退路径，否则一次部署失误就是"语义检索整体失效"；
     * ② 两个后端要在同一批评测用例上对比（同一问题、同一模型，只有后端不同），
     * 没有开关就只能靠改代码来回切，测出来的东西说不清是不是别的原因。
     */
    public static final String KEY_VECTOR_BACKEND = "kb.vector_backend";

    /**
     * 当前生效的向量后端。Milvus 配置了但探活失败时**静默降级**为 MySQL 全扫：
     * 语义检索是"锦上添花"的一路，不能因为它挂了就让整个对话失败。
     */
    private org.dyh.learnhub.service.vector.VectorStore store() {
        return store(embedder.snapshot());
    }

    private boolean annReadyFor(EmbeddingClient.Snapshot target) {
        AnnReadiness ready = annReadiness;
        return target != null && target.configured() && ready != null
                && ready.space().equals(target.spaceFingerprint())
                && java.util.Objects.equals(ready.uri(), milvusStore.effectiveUri());
    }

    private org.dyh.learnhub.service.vector.VectorStore store(EmbeddingClient.Snapshot target) {
        if (!"milvus".equalsIgnoreCase(String.valueOf(settingsService.effective(KEY_VECTOR_BACKEND)).trim())) {
            return mysqlStore;
        }
        return annReadyFor(target) && milvusStore.healthy() ? milvusStore : mysqlStore;
    }

    /** 当前配置的后端名（不问健康，用于状态展示与写入路径） */
    public String configuredBackend() {
        return store().name();
    }

    // ------------------------------------------------------------------
    // 向量后端的运维入口（体检 / 对比 / 迁移）
    // ------------------------------------------------------------------

    /**
     * 向量后端体检：两个后端各自的块数、是否一致、Milvus 是否可达。
     * <p>为什么要专门看这个：写入路径可能"MySQL 成功、Milvus 失败"（Milvus 临时不可用），
     * 于是两边的向量数会**悄悄漂开**——检索结果变少但没有任何报错。
     * 一致性问题必须能被看见，否则就是下一个"我说不清为什么少了几条"。
     */
    public Map<String, Object> vectorBackendStatus() {
        Map<String, Object> o = new LinkedHashMap<>();
        o.put("configured", String.valueOf(settingsService.effective(KEY_VECTOR_BACKEND)).isBlank()
                ? "mysql" : settingsService.effective(KEY_VECTOR_BACKEND));
        o.put("active", configuredBackend());
        o.put("mysql", mysqlStore.stats());
        EmbeddingClient.Snapshot target = embedder.snapshot();
        List<KbChunk> stored = mapper.loadAll();
        long mysqlChunks = stored.stream().filter(c -> target.configured()
                && target.spaceFingerprint().equals(c.getEmbeddingSpace())).count();
        o.put("mysqlChunks", mysqlChunks);
        o.put("storedChunks", stored.size());
        o.put("embeddingConfigured", target.configured());
        o.put("annReady", annReadyFor(target));
        Map<String, Object> mv = new LinkedHashMap<>();
        mv.put("uri", milvusStore.effectiveUri());
        boolean healthy = milvusStore.healthy();
        mv.put("healthy", healthy);
        long vecCount = healthy && target.configured() ? milvusStore.vectorCount(target.spaceFingerprint()) : -1;
        mv.put("vectors", vecCount);
        if (!milvusStore.lastError().isBlank()) {
            mv.put("lastError", milvusStore.lastError());
        }
        o.put("milvus", mv);
        // 一致性判定必须能表达"不知道"：Milvus 不可达时不是"一致"，而是**没法判断**。
        // （第一版写成 `vecCount < 0 || 相等`，于是"集合还不存在"被显示成"一致"—— 正好是相反的意思。）
        Boolean consistent = !healthy ? null : (vecCount >= 0 && vecCount == mysqlChunks);
        o.put("consistent", consistent);
        o.put("hint", !target.configured() ? "未配置嵌入模型，关键词检索仍可用"
                : stored.size() > mysqlChunks ? "存在旧嵌入空间的向量，请先重建索引再同步"
                : "milvus".equalsIgnoreCase(String.valueOf(settingsService.effective(KEY_VECTOR_BACKEND)).trim())
                  && !annReadyFor(target) ? "当前使用 MySQL 扫描；完整同步到 Milvus 或全量重建成功后启用 ANN"
                : !healthy ? "Milvus 不可达：检索会自动降级为 MySQL 全扫"
                : Boolean.TRUE.equals(consistent) ? "两个后端向量数一致"
                : "Milvus 与 MySQL 向量数不一致（Milvus=" + vecCount + "，MySQL=" + mysqlChunks
                  + "）：POST /api/kb/vector/sync 补齐（向量已在 MySQL，无需重新嵌入）");
        return o;
    }

    /**
     * 把 MySQL 里已有的向量**直接灌进 Milvus**（不重新嵌入、不调模型）。
     * <p>迁移与补数用：换后端后第一次同步一次即可；Milvus 临时挂过之后再同步一次也能补回来。
     *
     * @return {sources, chunks, ms}
     */
    public synchronized Map<String, Object> syncToMilvus() {
        long t0 = System.currentTimeMillis();
        EmbeddingClient.Snapshot target = requireTarget();
        String space = target.spaceFingerprint();
        String annUri = milvusStore.effectiveUri();
        List<KbChunk> all = mapper.loadAll();
        Map<String, List<org.dyh.learnhub.service.vector.VectorStore.VecItem>> bySource = new LinkedHashMap<>();
        int dim = 0;
        for (KbChunk c : all) {
            if (c.getVec() == null || !space.equals(c.getEmbeddingSpace())) {
                continue;
            }
            float[] v = EmbeddingClient.toVector(c.getVec());
            validateVector(v);
            if (c.getVec().length != v.length * 4 || c.getDim() == null || c.getDim() != v.length
                    || (dim != 0 && dim != v.length)) throw new IllegalStateException("索引向量维度不一致，需要重建索引");
            dim = v.length;
            bySource.computeIfAbsent(c.getSourceType() + "#" + c.getSourceId(), k -> new ArrayList<>())
                    .add(new org.dyh.learnhub.service.vector.VectorStore.VecItem(
                            c.getId(), c.getSeq() == null ? 0 : c.getSeq(), v));
        }
        if (!all.isEmpty() && bySource.isEmpty())
            throw new IllegalStateException("没有当前嵌入空间的向量，请先重建索引");
        annReadiness = null;
        assertCurrent(target);
        milvusStore.clear(space);
        if (dim > 0) {
            milvusStore.ensure(space, dim);
        }
        int chunks = 0;
        for (Map.Entry<String, List<org.dyh.learnhub.service.vector.VectorStore.VecItem>> e : bySource.entrySet()) {
            int cut = e.getKey().indexOf('#');
            String type = e.getKey().substring(0, cut);
            Long id = Long.parseLong(e.getKey().substring(cut + 1));
            milvusStore.replaceSource(space, type, id, e.getValue());
            chunks += e.getValue().size();
        }
        Map<String, Object> o = new LinkedHashMap<>();
        assertCurrent(target);
        assertAnnUri(annUri);
        annReadiness = new AnnReadiness(space, annUri);
        o.put("model", target.model());
        o.put("embeddingSpace", space);
        o.put("sources", bySource.size());
        o.put("chunks", chunks);
        o.put("ms", System.currentTimeMillis() - t0);
        o.put("milvus", milvusStore.stats());
        return o;
    }

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

    /** 先准备全部向量，再在一个事务内替换，失败时旧索引完整保留。 */
    private synchronized void rebuild(Job job) {
        try {
            EmbeddingClient.Snapshot target = requireTarget();
            boolean contextual = contextual();
            List<Source> sources = collectSources();
            job.total = sources.size();
            List<PreparedSource> prepared = new ArrayList<>();
            job.stage = "分块并嵌入（旧索引保留）";
            for (Source source : sources) {
                prepared.add(prepareSource(source, target, contextual));
                job.chunks += prepared.get(prepared.size() - 1).rows().size();
                job.chars += source.text().length();
                job.done++;
            }
            validatePrepared(prepared);
            assertCurrent(target, contextual);
            job.stage = "替换索引";
            annReadiness = null;
            try {
                new TransactionTemplate(transactionManager).executeWithoutResult(tx -> {
                    assertCurrent(target, contextual);
                    mapper.delete(Wrappers.<KbChunk>lambdaQuery());
                    stateMapper.delete(Wrappers.<KbIndexState>lambdaQuery());
                    for (PreparedSource source : prepared) persistSource(source, target, contextual);
                    assertCurrent(target, contextual);
                });
            } finally {
                invalidateCache();
            }
            publishPrepared(prepared, target, true, null);
            job.stage = sources.isEmpty() ? "没有可索引的内容" : "完成";
            job.status = "done";
        } catch (Exception e) {
            log.warn("语义索引重建失败，保留已提交索引：{}", e.toString());
            job.status = "failed";
            job.error = e.getMessage() == null ? e.toString() : e.getMessage();
            job.stage = "失败";
        } finally {
            job.finishedAt = System.currentTimeMillis();
        }
    }

    private record PreparedSource(Source source, List<KbChunk> rows) { }

    private PreparedSource prepareSource(Source source, EmbeddingClient.Snapshot target, boolean contextual) {
        List<TextChunker.Chunk> chunks = TextChunker.splitWithHeadings(source.text());
        if (chunks.isEmpty()) return new PreparedSource(source, List.of());
        List<String> inputs = chunks.stream().map(c -> contextual
                ? TextChunker.embedText(source.title(), c.heading(), c.text()) : c.text()).toList();
        List<float[]> vectors = embedder.embedAll(target, inputs);
        if (vectors == null || vectors.size() != chunks.size())
            throw new IllegalStateException("嵌入服务返回的向量数量不匹配");
        List<KbChunk> rows = new ArrayList<>(chunks.size());
        for (int i = 0; i < chunks.size(); i++) {
            float[] vector = vectors.get(i);
            validateVector(vector);
            TextChunker.Chunk chunk = chunks.get(i);
            KbChunk row = new KbChunk();
            row.setSourceType(source.type());
            row.setSourceId(source.id());
            row.setSeq(i);
            row.setTitle(clip(source.title(), 250));
            row.setCategory(clip(source.category(), 250));
            row.setHeading(clip(chunk.heading(), 250));
            row.setChunkText(chunk.text());
            row.setCharLen(chunk.text().length());
            row.setVec(EmbeddingClient.toBytes(vector));
            row.setDim(vector.length);
            row.setModel(clip(target.model(), 64));
            row.setEmbeddingSpace(target.spaceFingerprint());
            row.setCreatedAt(LocalDateTime.now());
            row.setUpdatedAt(row.getCreatedAt());
            rows.add(row);
        }
        return new PreparedSource(source, rows);
    }

    private static void validateVector(float[] vector) {
        if (vector == null || vector.length == 0 || vector.length > 16383)
            throw new IllegalStateException("嵌入向量为空或超出存储限制");
        double norm = 0;
        for (float value : vector) {
            if (!Float.isFinite(value)) throw new IllegalStateException("嵌入向量包含无效值");
            norm += (double) value * value;
        }
        if (norm == 0) throw new IllegalStateException("嵌入服务返回零向量");
    }

    private static void validatePrepared(List<PreparedSource> prepared) {
        int dimension = 0;
        for (PreparedSource source : prepared) for (KbChunk row : source.rows()) {
            if (dimension != 0 && dimension != row.getDim())
                throw new IllegalStateException("同一嵌入空间的向量维度不一致");
            dimension = row.getDim();
        }
    }

    private EmbeddingClient.Snapshot requireTarget() {
        EmbeddingClient.Snapshot target = embedder.snapshot();
        if (target == null || !target.configured())
            throw new IllegalStateException(target == null ? "未配置嵌入模型，关键词检索仍可用" : target.reason());
        return target;
    }

    private boolean contextual() {
        return !"0".equals(settingsService.effective(SETTING_CONTEXTUAL_EMBED));
    }

    private void assertCurrent(EmbeddingClient.Snapshot target) {
        EmbeddingClient.Snapshot current = embedder.snapshot();
        if (current == null || !current.configured()
                || !target.spaceFingerprint().equals(current.spaceFingerprint()))
            throw new IllegalStateException("嵌入模型配置已变更，旧任务已停止，请重新构建索引");
    }

    private void assertCurrent(EmbeddingClient.Snapshot target, boolean contextual) {
        assertCurrent(target);
        if (contextual != contextual())
            throw new IllegalStateException("上下文嵌入设置已变更，请重新构建索引");
    }

    /** 仅在调用方的数据库事务内执行。 */
    private void persistSource(PreparedSource prepared, EmbeddingClient.Snapshot target, boolean contextual) {
        Source source = prepared.source();
        mapper.deleteBySource(source.type(), source.id());
        for (KbChunk row : prepared.rows()) mapper.insert(row);
        upsertState(source, prepared.rows().size(), target, contextual);
    }

    private void assertAnnUri(String expected) {
        if (!java.util.Objects.equals(expected, milvusStore.effectiveUri()))
            throw new IllegalStateException("Milvus 服务地址已改变，请重新完整同步索引");
    }

    private void publishPrepared(List<PreparedSource> prepared, EmbeddingClient.Snapshot target,
                                 boolean clear, AnnReadiness previouslyReady) {
        try {
            String annUri = milvusStore.effectiveUri();
            org.dyh.learnhub.service.vector.VectorStore backend = storeForWrite();
            if (clear) backend.clear(target.spaceFingerprint());
            for (PreparedSource source : prepared) {
                List<org.dyh.learnhub.service.vector.VectorStore.VecItem> items = source.rows().stream()
                        .map(row -> new org.dyh.learnhub.service.vector.VectorStore.VecItem(
                                row.getId(), row.getSeq(), EmbeddingClient.toVector(row.getVec()))).toList();
                backend.replaceSource(target.spaceFingerprint(), source.source().type(), source.source().id(), items);
            }
            if (backend == milvusStore) {
                assertAnnUri(annUri);
                // 局部成功只能保持此前同空间、同服务的完整状态，不能修复更早的失败。
                if (clear || (previouslyReady != null
                        && previouslyReady.space().equals(target.spaceFingerprint())
                        && java.util.Objects.equals(previouslyReady.uri(), annUri)))
                    annReadiness = new AnnReadiness(target.spaceFingerprint(), annUri);
            }
        } catch (Exception e) {
            annReadiness = null;
            milvusStore.markUnhealthy("向量同步失败，可通过 /api/kb/vector/sync 补齐");
            log.warn("向量后端同步失败，检索使用 MySQL：{}", e.toString());
        }
    }

    /**
     * 写入用的后端：按**配置**选择，不因为健康检查失败就改写 MySQL 表之外的东西。
     * <p>注意 MySQL 的向量行**永远**都会写（见上），所以这里选 Milvus 失败也不丢数据。
     */
    private org.dyh.learnhub.service.vector.VectorStore storeForWrite() {
        if (!"milvus".equalsIgnoreCase(String.valueOf(settingsService.effective(KEY_VECTOR_BACKEND)).trim())) {
            return mysqlStore;
        }
        return milvusStore;
    }

    /** 记录/更新该来源已索引内容的指纹（增量索引靠它判断"变了没有"） */
    private static String sourceHash(Source source, String space, boolean contextual) {
        return KgService.sha256(space + "\u0000" + contextual + "\u0000"
                + (source.title() == null ? "" : source.title()) + "\u0000"
                + (source.text() == null ? "" : source.text()));
    }

    private void upsertState(Source source, int chunks, EmbeddingClient.Snapshot target, boolean contextual) {
        String key = KbIndexState.key(source.type(), source.id());
        KbIndexState state = stateMapper.selectById(key);
        boolean insert = state == null;
        if (insert) {
            state = new KbIndexState();
            state.setId(key);
            state.setSourceType(source.type());
            state.setSourceId(source.id());
        }
        state.setContentHash(sourceHash(source, target.spaceFingerprint(), contextual));
        state.setEmbeddingSpace(target.spaceFingerprint());
        state.setChunks(chunks);
        state.setIndexedAt(LocalDateTime.now());
        if (insert) stateMapper.insert(state); else stateMapper.updateById(state);
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
    public synchronized Map<String, Object> reindexChanged() {
        return reindexChanged(false);
    }

    /** 自动增量不得替用户支付切模型后的整库重建成本。 */
    public synchronized Map<String, Object> reindexAutomatically() {
        return reindexChanged(true);
    }

    private Map<String, Object> reindexChanged(boolean automatic) {
        EmbeddingClient.Snapshot target = embedder.snapshot();
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("configured", target != null && target.configured());
        result.put("reindexed", 0);
        result.put("removed", 0);
        result.put("chunks", 0);
        if (target == null || !target.configured()) {
            result.put("reason", target == null ? "未配置嵌入模型，关键词检索仍可用" : target.reason());
            return result;
        }
        if (automatic && (!settingsService.vectorEnabled()
                || "0".equals(settingsService.effective(SETTING_AUTO_INDEX)))) return result;
        boolean contextual = contextual();
        List<KbIndexState> known = stateMapper.selectList(null);
        if (automatic && (known.stream().anyMatch(state -> !target.spaceFingerprint().equals(state.getEmbeddingSpace()))
                || mapper.loadAll().stream().anyMatch(row -> !target.spaceFingerprint().equals(row.getEmbeddingSpace())))) {
            result.put("skipped", true);
            result.put("reason", "嵌入空间已改变或存在旧索引，请手动重建；自动增量已暂停");
            return result;
        }
        List<Source> sources = collectSources();
        Map<String, KbIndexState> bySource = new LinkedHashMap<>();
        for (KbIndexState state : known) bySource.put(KbIndexState.key(state.getSourceType(), state.getSourceId()), state);
        Set<String> alive = new LinkedHashSet<>();
        List<PreparedSource> prepared = new ArrayList<>();
        for (Source source : sources) {
            String key = KbIndexState.key(source.type(), source.id());
            alive.add(key);
            KbIndexState state = bySource.get(key);
            if (state != null && target.spaceFingerprint().equals(state.getEmbeddingSpace())
                    && sourceHash(source, target.spaceFingerprint(), contextual).equals(state.getContentHash())) continue;
            prepared.add(prepareSource(source, target, contextual));
        }
        List<KbIndexState> removed = known.stream().filter(state -> !alive.contains(state.getId())).toList();
        validatePrepared(prepared);
        if (!prepared.isEmpty()) {
            int dimension = prepared.stream().flatMap(source -> source.rows().stream())
                    .mapToInt(KbChunk::getDim).findFirst().orElse(0);
            Set<String> replacing = new LinkedHashSet<>();
            for (PreparedSource source : prepared) replacing.add(KbIndexState.key(source.source().type(), source.source().id()));
            for (KbChunk row : mapper.loadAll()) {
                if (dimension != 0 && target.spaceFingerprint().equals(row.getEmbeddingSpace())
                        && !replacing.contains(KbIndexState.key(row.getSourceType(), row.getSourceId()))
                        && (row.getDim() == null || row.getDim() != dimension))
                    throw new IllegalStateException("嵌入维度已变化，请全量重建索引");
            }
        }
        assertCurrent(target, contextual);
        if (!prepared.isEmpty() || !removed.isEmpty()) {
            AnnReadiness previouslyReady = annReadiness;
            annReadiness = null;
            try {
                new TransactionTemplate(transactionManager).executeWithoutResult(tx -> {
                    assertCurrent(target, contextual);
                    for (PreparedSource source : prepared) persistSource(source, target, contextual);
                    for (KbIndexState state : removed) {
                        mapper.deleteBySource(state.getSourceType(), state.getSourceId());
                        stateMapper.deleteById(state.getId());
                    }
                    assertCurrent(target, contextual);
                });
            } finally {
                invalidateCache();
            }
            // 删去当前空间的孤儿；其它历史空间也会在 MySQL 回查时被严格排除。
            try {
                for (KbIndexState state : removed) if (state.getEmbeddingSpace() != null)
                    storeForWrite().deleteSource(state.getEmbeddingSpace(), state.getSourceType(), state.getSourceId());
                publishPrepared(prepared, target, false, previouslyReady);
            } catch (Exception e) {
                annReadiness = null;
                milvusStore.markUnhealthy("向量同步失败，可通过 /api/kb/vector/sync 补齐");
            }
        }
        result.put("sources", sources.size());
        result.put("reindexed", prepared.size());
        result.put("removed", removed.size());
        result.put("chunks", prepared.stream().mapToInt(source -> source.rows().size()).sum());
        result.put("model", target.model());
        result.put("embeddingSpace", target.spaceFingerprint());
        return result;
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
        EmbeddingClient.Snapshot target = embedder.snapshot();
        return settingsService.vectorEnabled() && target != null && target.configured()
                && !"0".equals(settingsService.effective(SETTING_AUTO_INDEX));
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
        if (!autoIndexEnabled()) return;
        try {
            long t0 = System.currentTimeMillis();
            Map<String, Object> r = reindexAutomatically();
            lastAutoRunAt = System.currentTimeMillis();
            if (Boolean.TRUE.equals(r.get("skipped"))) {
                log.info("自动增量索引跳过：{}", r.get("reason"));
                return;
            }
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

    /**
     * 作废块缓存。
     *
     * <p>缓存本体已经搬到 {@link org.dyh.learnhub.service.vector.MysqlVectorStore}（谁负责全扫，谁负责缓存），
     * 这里保留一个转发是为了让"数据变了就作废"这件事在调用点仍然一眼可见。
     */
    private void invalidateCache() {
        mysqlStore.clear(null);
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
        EmbeddingClient.Snapshot target = embedder.snapshot();
        if (target == null || !target.configured()) return List.of();
        try {
            if (!mysqlStore.hasVectors(target.spaceFingerprint())) return List.of();
        } catch (Exception e) {
            log.warn("读取向量索引失败，本轮无语义召回：{}", e.toString());
            return List.of();
        }
        // 没有任何向量就别调嵌入了（"还没建索引"与"Milvus 刚清空"都会走到这里）。
        // 注意：探活失败时会走 MySQL，所以这个判断要问**当前生效的那个后端**。
        org.dyh.learnhub.service.vector.VectorStore active = store(target);
        try {
            if (!active.hasVectors(target.spaceFingerprint())) active = mysqlStore;
            if (!active.hasVectors(target.spaceFingerprint())) return List.of();
        } catch (Exception e) {
            log.warn("向量后端（{}）状态检查失败，转用 MySQL：{}", active.name(), e.toString());
            active = mysqlStore;
        }
        float[] qv;
        try {
            qv = embedder.embed(target, query);
            validateVector(qv);
        } catch (Exception e) {
            // 嵌入失败不能影响对话：退化成"这轮没有语义命中"
            log.warn("查询嵌入失败（本轮无语义召回）：{}", e.toString());
            return List.of();
        }
        if (qv == null) {
            return List.of();
        }
        // 候选由后端给（MySQL 全扫 / Milvus ANN），阈值与相对带在这里统一施加 ——
        // 放在这里而不是各后端里，是为了保证"换后端不改变命中口径"（否则同一个问题换后端结果不同，
        // 是最难定位的那类 bug）。ANN 需要比 topK 更大的池子，因为相对带还会筛掉一批。
        List<org.dyh.learnhub.service.vector.VectorStore.VecHit> candidates;
        try {
            candidates = active.search(target.spaceFingerprint(), qv, Math.max(topK * 4, 64));
            if (active == milvusStore && candidates.isEmpty())
                candidates = mysqlStore.search(target.spaceFingerprint(), qv, Integer.MAX_VALUE);
        } catch (Exception e) {
            log.warn("向量后端（{}）检索失败，本轮退化为 MySQL 全扫：{}", configuredBackend(), e.toString());
            // 立刻标记不可用：否则 Milvus 真的挂了之后，每轮对话都要先等一次连接超时才降级
            if (active == milvusStore) {
                milvusStore.markUnhealthy(e.getMessage());
            }
            try {
                candidates = mysqlStore.search(target.spaceFingerprint(), qv, Integer.MAX_VALUE);
            } catch (Exception e2) {
                log.warn("回退检索也失败（本轮无语义召回）：{}", e2.toString());
                return List.of();
            }
        }
        List<Hit> hits = new ArrayList<>();
        for (org.dyh.learnhub.service.vector.VectorStore.VecHit c : candidates) {
            if (Double.isFinite(c.score()) && c.score() >= MIN_SCORE) {
                hits.add(new Hit(c.sourceType(), c.sourceId(), c.title(), c.category(),
                        c.text(), c.score(), c.seq()));
            }
        }
        hits.sort(Comparator.comparingDouble(Hit::score).reversed());
        if (hits.isEmpty()) {
            return List.of();
        }
        // 相对带：离最高分太远的尾巴丢掉（同领域相似度被压扁，绝对阈值筛不干净）
        double best = hits.get(0).score();
        List<Hit> band = new ArrayList<>();
        for (Hit h : hits) {
            if (h.score() >= best - RELATIVE_BAND) {
                band.add(h);
            }
        }
        // **来源优先**：先给每个来源留最高分的那一块，让更多**不同来源**进入前 K。
        // 以前同一篇长文档靠块数就能占满前 K（实测 file#19 占 4 行、note#51 占 2 行），
        // 把对症的短资料（如一张速查卡）挤出榜单 —— 这正是评估报告里"一篇长笔记
        // 主导总分"的现象。去重后若仍不足 topK，再用同来源的其余块按原顺序补齐，
        // 所以"来源少、块多"时行为与以前一致。
        List<Hit> distinct = new ArrayList<>();
        List<Hit> extra = new ArrayList<>();
        java.util.Set<String> seenSrc = new java.util.LinkedHashSet<>();
        for (Hit h : band) {
            if (seenSrc.add(key(h.sourceType(), h.sourceId()))) {
                distinct.add(h);
            } else {
                extra.add(h);
            }
        }
        distinct.addAll(extra);
        return distinct.size() > topK ? distinct.subList(0, topK) : distinct;
    }

    // ------------------------------------------------------------------
    // 状态 / 体检
    // ------------------------------------------------------------------

    /** 索引状态：块数、字数、模型、来源数、是否与当前内容一致 */
    public Map<String, Object> status() {
        EmbeddingClient.Snapshot target = embedder.snapshot();
        List<KbChunk> rows = mapper.loadAll();
        List<Source> sources = collectSources();
        List<KbIndexState> states = stateMapper.selectList(null);
        Map<String, KbIndexState> bySource = new LinkedHashMap<>();
        for (KbIndexState state : states) bySource.put(state.getId(), state);
        Set<String> indexedSources = new LinkedHashSet<>();
        Set<String> indexedModels = new LinkedHashSet<>();
        Set<String> indexedSpaces = new LinkedHashSet<>();
        long compatible = 0;
        long legacy = 0;
        long chars = 0;
        boolean configured = target != null && target.configured();
        for (KbChunk row : rows) {
            indexedSources.add(KbIndexState.key(row.getSourceType(), row.getSourceId()));
            if (row.getModel() != null) indexedModels.add(row.getModel());
            if (row.getEmbeddingSpace() == null || row.getEmbeddingSpace().isBlank()) legacy++;
            else indexedSpaces.add(row.getEmbeddingSpace());
            if (configured && target.spaceFingerprint().equals(row.getEmbeddingSpace()) && row.getDim() != null
                    && row.getDim() > 0 && row.getVec() != null && row.getVec().length == row.getDim() * 4) compatible++;
            chars += row.getCharLen() == null ? 0 : row.getCharLen();
        }
        boolean changed = !rows.isEmpty() && (!configured || compatible != rows.size());
        boolean stale = rows.isEmpty() || changed || sources.size() != states.size();
        boolean contextual = contextual();
        for (Source source : sources) {
            KbIndexState state = bySource.get(KbIndexState.key(source.type(), source.id()));
            if (state == null || !configured || !target.spaceFingerprint().equals(state.getEmbeddingSpace())
                    || !sourceHash(source, target.spaceFingerprint(), contextual).equals(state.getContentHash())) stale = true;
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("chunks", rows.size());
        result.put("indexedChars", chars);
        result.put("indexedModel", String.join(", ", indexedModels));
        result.put("indexedSources", indexedSources.size());
        result.put("currentSources", sources.size());
        result.put("model", target == null ? "" : target.model());
        result.put("baseUrl", target == null ? "" : target.baseUrl());
        result.put("provider", target == null ? "" : target.provider());
        result.put("endpoint", target == null ? "" : target.endpoint());
        result.put("enabled", settingsService.vectorEnabled());
        result.put("configured", configured);
        result.put("embeddingSpace", configured ? target.spaceFingerprint() : "");
        result.put("indexedSpaces", indexedSpaces);
        result.put("compatibleChunks", compatible);
        result.put("legacyChunks", legacy);
        result.put("embeddingChanged", changed);
        result.put("annReady", annReadyFor(target));
        result.put("reason", !configured ? (target == null ? "未配置嵌入模型，关键词检索仍可用" : target.reason())
                : changed ? "嵌入空间已改变或存在旧索引，请手动重建；自动增量已暂停" : target.reason());
        result.put("stale", stale);
        result.put("sourceLatestChange", mapper.sourceLatestChange());
        result.put("indexedAt", mapper.indexedAt());
        String backend = settingsService.effective(KEY_VECTOR_BACKEND);
        result.put("vectorBackend", backend == null || backend.isBlank() ? "mysql" : backend);
        return result;
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
        EmbeddingClient.Snapshot target = embedder.snapshot();
        return target == null || !target.configured() ? "未配置嵌入模型" : target.model() + "@" + target.baseUrl();
    }

    // ---------------- 小工具 ----------------

    private static Long num(Object o) {
        return o instanceof Number n ? n.longValue() : null;
    }

    private static String str(Object o) {
        return o == null ? "" : String.valueOf(o);
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
