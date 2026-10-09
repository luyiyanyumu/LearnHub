package org.dyh.learnhub.service.vector;

import java.util.List;
import java.util.Map;

/**
 * 向量检索后端的抽象（2026-09-29 抽出）。
 *
 * <h3>为什么要抽</h3>
 * 之前"存储 + 全量扫描 + 指纹缓存"三件事揉在 {@link org.dyh.learnhub.service.VectorIndexService} 里，
 * 每换一个后端都要动检索核心。抽完之后换后端 = 加一个实现类，
 * 检索、评测、注入、重排那条链路一行都不用改。
 *
 * <h3>谁存什么（这条分工是刻意的）</h3>
 * <ul>
 *   <li><b>MySQL 是权威</b>：块文本、来源、块 id，以及一份 float32 向量。
 *       检索结果要展示原文、要按来源过滤、要能重建索引，这些只有 MySQL 能给。
 *       留着这份向量还意味着<b>换后端不用重新嵌入</b>（迁移成本几乎为零）。</li>
 *   <li><b>向量后端只负责"谁是近邻"</b>：拿 id + 向量做相似度查询。
 *       所以 MySQL 实现是全量扫描、Milvus 实现是 ANN —— 对外返回同一种结构，调用方看不出区别。</li>
 * </ul>
 *
 * <h3>约定</h3>
 * <ul>
 *   <li>所有方法都可能抛异常：调用方必须能降级（检索失败 = 本轮没有语义召回，不能影响对话）。</li>
 *   <li>{@link #search} 返回的候选**不做阈值过滤**：绝对阈值与相对带由调用方统一施加，
 *       否则两个后端的口径会漂移（同一个问题换后端命中不同，是最难查的那种 bug）。</li>
 * </ul>
 */
public interface VectorStore {

    /** 后端名，用于日志与状态展示：{@code mysql} / {@code milvus} */
    String name();

    /**
     * 后端里现在到底有没有向量。
     *
     * <p>检索前用它短路一次：没有索引就别白白调一次嵌入（外加可能失败的网络往返）——
     * 这条路径在"还没建索引"和"Milvus 刚清空"时都会被走到。
     */
    boolean hasVectors(String space);

    /**
     * 确保后端结构就绪（Milvus：建集合 + 建索引 + load；MySQL：无操作）。
     *
     * @param space 协议、实际接口与模型的稳定空间标识
     * @param dim   向量维度
     */
    void ensure(String space, int dim);

    /**
     * 用给定向量**替换**某来源的全部块（幂等：先删该来源，再插入）。
     *
     * <p>之所以是"替换"而不是"追加"：重新索引一个来源时 MySQL 会分配新的块 id，
     * 追加会把旧向量留在库里，检索到时又查不到对应的块行（静默丢结果）。
     */
    void replaceSource(String space, String sourceType, Long sourceId, List<VecItem> items);

    /** 删除某来源的全部向量（资料/笔记被删时调用，否则 ANN 库里会一直留着孤儿向量） */
    void deleteSource(String space, String sourceType, Long sourceId);

    /** 清空指定空间的全部向量（全量重建成功后调用） */
    void clear(String space);

    /**
     * 近邻查询。
     *
     * @param space 当前查询向量的空间（决定查哪个集合）
     * @param query 查询向量
     * @param pool  期望的候选池大小；全扫实现可以忽略它（成本与 pool 无关），ANN 实现按它取
     * @return 候选（可能为空），调用方负责阈值/相对带/topK
     */
    List<VecHit> search(String space, float[] query, int pool);

    /** 状态与诊断信息（进 /api/kb/status 或索引体检） */
    Map<String, Object> stats();

    /** 一个块的向量（id 由 MySQL 插入后回填，两个后端靠它对齐） */
    record VecItem(long id, int seq, float[] vec) {
    }

    /** 一条候选：已带上 MySQL 里的展示字段，调用方不用再查一次库 */
    record VecHit(long id, String sourceType, Long sourceId, int seq,
                  String title, String category, String text, double score) {
    }
}
