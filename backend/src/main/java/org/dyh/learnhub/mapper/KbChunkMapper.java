package org.dyh.learnhub.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.dyh.learnhub.entity.KbChunk;

import java.util.List;
import java.util.Map;

@Mapper
public interface KbChunkMapper extends BaseMapper<KbChunk> {

    /**
     * 取全部块用于检索。
     * <p>刻意**不做 SQL 侧向量检索**（MySQL 8 没有向量索引）：个人库只有几百块，
     * 一次读回内存（1MB 量级）再暴力余弦，比"把向量取出来算"更简单也更快。
     */
    @Select("SELECT id, source_type AS sourceType, source_id AS sourceId, seq, title, category, "
            + "chunk_text AS chunkText, char_len AS charLen, vec, dim, model FROM kb_chunk")
    List<KbChunk> loadAll();

    /** 索引规模与模型（界面展示 / 判断是否需要重建） */
    @Select("SELECT CONCAT(COUNT(*), ':', IFNULL(SUM(char_len), 0), ':', IFNULL(MAX(model), ''), ':', "
            + "IFNULL(COUNT(DISTINCT CONCAT(source_type, '-', source_id)), 0)) FROM kb_chunk")
    String fingerprint();

    /** 按来源清空（增量重建单条来源时用） */
    @org.apache.ibatis.annotations.Delete("DELETE FROM kb_chunk WHERE source_type = #{sourceType} AND source_id = #{sourceId}")
    void deleteBySource(@Param("sourceType") String sourceType, @Param("sourceId") Long sourceId);

    /**
     * 刷新某个来源的**冗余标题**（kb_chunk.title）。
     *
     * <p>用途：资料改名后，检索结果里展示的标题要跟着变 —— 它是冗余列，不同步的话
     * 改了名检索命中的还是旧标题，用户会以为没生效。只需改这一列，**不必重建向量**
     * （向量只跟 chunk_text 有关）。
     *
     * @return 受影响行数
     */
    @org.apache.ibatis.annotations.Update("UPDATE kb_chunk SET title = #{title} "
            + "WHERE source_type = #{sourceType} AND source_id = #{sourceId}")
    int refreshTitleBySource(@Param("sourceType") String sourceType,
                             @Param("sourceId") Long sourceId,
                             @Param("title") String title);

    /**
     * 内容侧"最后修改时间"：判断索引是否过期。
     * <p>只比来源**数量**是不够的 —— 改一篇笔记的内容数量没变，但索引里的向量已经过时了。
     */
    @Select("SELECT IFNULL(MAX(t), '') FROM ("
            + "SELECT MAX(updated_at) AS t FROM note "
            + "UNION ALL SELECT MAX(updated_at) AS t FROM quick_ref "
            + "UNION ALL SELECT MAX(GREATEST(created_at, IFNULL(extracted_at, created_at))) AS t FROM file_info"
            + ") x")
    String sourceLatestChange();

    /** 索引自身的构建时间（与 sourceLatestChange 比较即可判断过期） */
    @Select("SELECT IFNULL(MAX(created_at), '') FROM kb_chunk")
    String indexedAt();

    /**
     * 全部来源的"最后修改时间"，用于**实体页的过期判定**（评估报告 P1-2）。
     *
     * <p>刻意一次查回三类来源：实体页有几十个，读侧在内存里比对即可，
     * 不会变成"每页一次查询"。
     *
     * <p>口径与实体编译的取材范围（{@code EntityCompileService.sourceDocs()}）保持一致：
     * 笔记/速查卡要求正文非空、资料要求 {@code text_status='ok'} —— 不再满足这个条件的来源
     * 等于"已经不能作为素材"，查不到就会被判成来源不存在 → 相关页标脏。
     *
     * <p>资料的"修改时间"沿用 {@link #sourceLatestChange} 的写法
     * {@code GREATEST(created_at, extracted_at)}：file_info 的 created_at 在重新抽取正文时不变，
     * 只比它会把"重新抽过正文"漏掉。
     *
     * @return 每行 {t=note/quick_ref/file, id=来源 id, ts=最后修改时间}
     */
    @Select("SELECT 'note' AS t, id AS id, updated_at AS ts FROM note "
            + "WHERE content IS NOT NULL AND content <> '' "
            + "UNION ALL SELECT 'quick_ref', id, updated_at FROM quick_ref "
            + "WHERE content IS NOT NULL AND content <> '' "
            + "UNION ALL SELECT 'file', id, GREATEST(created_at, IFNULL(extracted_at, created_at)) FROM file_info "
            + "WHERE text_status = 'ok' AND text_content IS NOT NULL")
    List<Map<String, Object>> allSourceTimes();

    /**
     * 最近更新的素材（三类合并）。
     * <p>用于"摄入后局部重编译"：拿最近改动的内容去做影响分析，判断该更新哪些页面。
     */
    @Select("SELECT * FROM ("
            + "SELECT 'note' AS t, id AS id, title AS title, content AS content, updated_at AS ts FROM note "
            + "UNION ALL SELECT 'quick_ref', id, title, content, updated_at FROM quick_ref "
            + "UNION ALL SELECT 'file', id, origin_name, CONCAT(IFNULL(summary,''), '\\n', IFNULL(text_content,'')), "
            + "GREATEST(created_at, IFNULL(extracted_at, created_at)) FROM file_info WHERE text_status = 'ok'"
            + ") x ORDER BY ts DESC LIMIT #{limit}")
    List<Map<String, Object>> recentSources(@Param("limit") int limit);

    // ---------------- 索引原料：三类来源的全文（列表 VO 不含正文，所以这里单独取） ----------------

    @Select("SELECT n.id AS id, n.title AS title, n.content AS content, c.name AS category "
            + "FROM note n LEFT JOIN category c ON c.id = n.category_id "
            + "WHERE n.content IS NOT NULL AND n.content <> ''")
    List<Map<String, Object>> allNotes();

    @Select("SELECT q.id AS id, q.title AS title, q.content AS content, c.name AS category "
            + "FROM quick_ref q LEFT JOIN category c ON c.id = q.category_id "
            + "WHERE q.content IS NOT NULL AND q.content <> ''")
    List<Map<String, Object>> allRefs();

    /** 资料：把用户手填说明拼在前面 —— 说明本身也是"这份文档讲了什么"的高价值上下文 */
    @Select("SELECT f.id AS id, f.origin_name AS title, "
            + "CONCAT(IFNULL(f.summary, ''), CASE WHEN IFNULL(f.summary,'') = '' THEN '' ELSE '\\n' END, "
            + "IFNULL(f.text_content, '')) AS content, c.name AS category "
            + "FROM file_info f LEFT JOIN category c ON c.id = f.category_id "
            + "WHERE f.text_status = 'ok' AND f.text_content IS NOT NULL")
    List<Map<String, Object>> allFiles();
}
