package org.dyh.learnhub.service.vector;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.dyh.learnhub.ai.EmbeddingClient;
import org.dyh.learnhub.entity.KbChunk;
import org.dyh.learnhub.mapper.KbChunkMapper;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 全量扫描实现（默认后端，2026-09-29 前是唯一实现）。
 *
 * <p>向量就存在 {@code kb_chunk.vec}（float32 小端），检索 = 把全部块载入内存逐条算余弦。
 * 为什么可以这样：实测一次查询嵌入要 ~300ms，而 1024 维全量扫描在
 * 1.3k 块时约 3ms、2 万块约 60ms、10 万块约 294ms —— **扫描比嵌入便宜两个数量级**，
 * 所以到 2 万块以上才值得上 ANN（见 {@link MilvusVectorStore}）。
 *
 * <p>指纹缓存：{@link KbChunkMapper#fingerprint()} 变了才重新 {@code loadAll()}，
 * 否则每轮对话都全表拉一遍。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MysqlVectorStore implements VectorStore {

    private final KbChunkMapper mapper;

    /** 全量块缓存 + 产生它的指纹（指纹变了就作废） */
    private volatile List<KbChunk> cache;
    private volatile String cacheFingerprint;

    @Override
    public String name() {
        return "mysql";
    }

    @Override
    public void ensure(String model, int dim) {
        // kb_chunk 的表结构固定带 vec/dim/model 列，不需要建什么
    }

    @Override
    public boolean hasVectors() {
        return !chunks().isEmpty();
    }

    @Override
    public void replaceSource(String sourceType, Long sourceId, List<VecItem> items) {
        // 向量随行写进 kb_chunk（见 VectorIndexService#indexSource），这里没有额外动作。
        // 但缓存必须作废，否则新块在缓存里看不见。
        invalidate();
    }

    @Override
    public void deleteSource(String sourceType, Long sourceId) {
        invalidate();
    }

    @Override
    public void clear() {
        invalidate();
    }

    @Override
    public List<VecHit> search(String model, float[] query, int pool) {
        List<KbChunk> all = chunks();
        List<VecHit> out = new ArrayList<>(all.size());
        int skipped = 0;
        for (KbChunk c : all) {
            if (c.getVec() == null) {
                continue;   // 半成品行（嵌入失败/历史遗留）：跳过而不是抛 NPE
            }
            // 换过嵌入模型的旧向量与当前查询向量**不可比**（空间不同），拿它们算余弦只会出噪声。
            // 这里按行上的 model 直接跳过：不额外查库，也不会把两个空间的距离混在一起。
            if (model != null && c.getModel() != null && !model.equals(c.getModel())) {
                skipped++;
                continue;
            }
            double score = EmbeddingClient.cosine(query, EmbeddingClient.toVector(c.getVec()));
            out.add(new VecHit(c.getId(), c.getSourceType(), c.getSourceId(),
                    c.getSeq() == null ? 0 : c.getSeq(), c.getTitle(), c.getCategory(),
                    c.getChunkText(), score));
        }
        if (skipped > 0) {
            log.warn("语义检索跳过 {} 块：它们的嵌入模型与当前模型（{}）不一致，需要重建索引", skipped, model);
        }
        // 全扫的成本与候选数无关，所以直接全给调用方，由它套阈值 + 相对带（口径与改前一致）
        return out;
    }

    @Override
    public Map<String, Object> stats() {
        Map<String, Object> o = new LinkedHashMap<>();
        o.put("backend", name());
        o.put("chunks", chunks().size());
        o.put("note", "全量扫描（float32 余弦），无外部依赖");
        return o;
    }

    /** 带指纹缓存的全量块载入 */
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

    private void invalidate() {
        cache = null;
        cacheFingerprint = null;
    }

    /** 供体检：当前缓存是否命中（能看到"有没有重复全表拉取"） */
    public boolean cacheHit() {
        return cache != null && cacheFingerprint != null && cacheFingerprint.equals(mapper.fingerprint());
    }

    /** 兜底：清掉缓存后强制重载（诊断用） */
    public List<KbChunk> reload() {
        invalidate();
        return chunks();
    }

    /** 便捷：按来源统计块数（诊断用） */
    public long countBySource(String sourceType, Long sourceId) {
        return mapper.selectCount(Wrappers.<KbChunk>lambdaQuery()
                .eq(KbChunk::getSourceType, sourceType)
                .eq(KbChunk::getSourceId, sourceId));
    }

    /** kb_chunk 总行数（与 Milvus 的向量数对齐检查用） */
    public long countAll() {
        return mapper.selectCount(null);
    }
}
