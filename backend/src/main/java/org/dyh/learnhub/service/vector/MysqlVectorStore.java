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
 * <p>指纹缓存：{@link KbChunkMapper#cacheFingerprint()} 变了才重新 {@code loadAll()}，
 * 否则每轮对话都全表拉一遍。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MysqlVectorStore implements VectorStore {

    private final KbChunkMapper mapper;

    /** 全量块缓存 + 产生它的指纹（指纹变了就作废） */
    private record Cached(String fingerprint, List<KbChunk> rows) { }
    private volatile Cached cache;

    @Override
    public String name() {
        return "mysql";
    }

    @Override
    public void ensure(String space, int dim) {
        // kb_chunk 的表结构固定带 vec/dim/model 列，不需要建什么
    }

    @Override
    public boolean hasVectors(String space) {
        return space != null && !space.isBlank() && chunks().stream()
                .anyMatch(c -> space.equals(c.getEmbeddingSpace()) && c.getVec() != null
                        && c.getDim() != null && c.getDim() > 0 && c.getVec().length == c.getDim() * 4);
    }

    @Override
    public void replaceSource(String space, String sourceType, Long sourceId, List<VecItem> items) {
        // 向量随行写进 kb_chunk，这里只需使缓存失效。
        // 但缓存必须作废，否则新块在缓存里看不见。
        invalidate();
    }

    @Override
    public void deleteSource(String space, String sourceType, Long sourceId) {
        invalidate();
    }

    @Override
    public void clear(String space) {
        invalidate();
    }

    @Override
    public List<VecHit> search(String space, float[] query, int pool) {
        if (space == null || space.isBlank() || query == null || query.length == 0) return List.of();
        List<KbChunk> all = chunks();
        List<VecHit> out = new ArrayList<>(all.size());
        int skipped = 0;
        for (KbChunk c : all) {
            if (c.getVec() == null || c.getVec().length != query.length * 4
                    || c.getDim() == null || c.getDim() != query.length) {
                continue;   // 半成品行（嵌入失败/历史遗留）：跳过而不是抛 NPE
            }
            // 换过嵌入模型的旧向量与当前查询向量**不可比**（空间不同），拿它们算余弦只会出噪声。
            // 协议、实际接口与模型均须相同；历史 NULL 空间不能推断为当前空间。
            if (!space.equals(c.getEmbeddingSpace())) {
                skipped++;
                continue;
            }
            double score = EmbeddingClient.cosine(query, EmbeddingClient.toVector(c.getVec()));
            if (!Double.isFinite(score)) continue;
            out.add(new VecHit(c.getId(), c.getSourceType(), c.getSourceId(),
                    c.getSeq() == null ? 0 : c.getSeq(), c.getTitle(), c.getCategory(),
                    c.getChunkText(), score));
        }
        if (skipped > 0) {
            log.debug("语义检索跳过 {} 块：嵌入空间与当前查询不一致，需要重建索引", skipped);
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
        String fp = mapper.cacheFingerprint();
        Cached local = cache;
        if (local != null && fp != null && fp.equals(local.fingerprint())) {
            return local.rows();
        }
        List<KbChunk> rows = mapper.loadAll();
        cache = new Cached(fp, rows);
        return rows;
    }

    private void invalidate() {
        cache = null;
    }

    /** 供体检：当前缓存是否命中（能看到"有没有重复全表拉取"） */
    public boolean cacheHit() {
        Cached local = cache;
        return local != null && local.fingerprint() != null && local.fingerprint().equals(mapper.cacheFingerprint());
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
