package org.dyh.learnhub.mapper;

import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;
import java.util.Map;

/**
 * 社区检测用的图数据访问（GraphRAG 的社区层）。
 *
 * <p>图是**概念图**：点来自 {@code kg_node}，边来自 {@code kg_relation} —— 不是 {@code kg_edge}
 * （那张表连的是文档：笔记↔速查卡，属于相似度图，参与社区划分没有意义）。
 *
 * <p>边为什么按 (head, tail) 求和：本体里同一对实体可以有多条**不同关系**的边（Git —属于→ 版本控制，
 * 同时 Git —前置→ 分支）。社区划分只关心"这两点连得紧不紧"，所以把关系数换算成更强的一条边；
 * 保留关系种类是 wiki/检索那一层的事。
 */
@Mapper
public interface KgCommunityMapper {

    /** 图里的全部节点（只取 id；社区划分不需要名字） */
    @Select("SELECT id FROM kg_node ORDER BY id")
    List<String> nodeIds();

    /** 全部边：同一对实体按权重求和（见类注释） */
    @Select("SELECT head_id AS head, tail_id AS tail, "
            + "SUM(CASE WHEN origin = 'derived' THEN weight * 0.35 ELSE weight END) AS w "
            + "FROM kg_relation GROUP BY head_id, tail_id ORDER BY head_id, tail_id")
    List<Map<String, Object>> edges();

    /** 重算前清空：社区是一次性全局划分，整批替换（与 kg_edge 的 origin 整批替换同一思路） */
    @Delete("DELETE FROM kg_community")
    int clear();

    /**
     * 图谱是否在上次划分之后又变过：1=需要重算，0=没变（可跳过）。
     *
     * <p>用时间戳比而不是存指纹：省掉一张状态表，也不会出现"指纹存了但没写成功"的两处一致性问题。
     * 变化源覆盖"实体新增/改名"（{@code kg_node.updated_at}）与"新增三元组"（{@code kg_relation.created_at}）。
     *
     * <p>已知盲区：**删除**实体/三元组不会让上面两个时间戳变大（MySQL 没有行删除时间），
     * 所以"只删不增"的那次不会自动重算 —— 下次有任何增改都会补上。刻意不为此引触发器等重机制：
     * 社区划分是本地 2 秒的事，而删除在图谱里远少于新增。
     */
    @Select("SELECT CASE WHEN GREATEST("
            + "IFNULL((SELECT MAX(updated_at) FROM kg_node), '1970-01-01'), "
            + "IFNULL((SELECT MAX(created_at) FROM kg_relation), '1970-01-01')) "
            + "> IFNULL((SELECT MAX(computed_at) FROM kg_community), '1970-01-01') THEN 1 ELSE 0 END")
    int staleFlag();

    /** 划分输入的持久指纹：删除边、同秒更新也能检测，重启后不会丢失。 */
    @Select("SELECT setting_value FROM app_setting WHERE setting_key = 'internal.kg.community_fingerprint'")
    String partitionFingerprint();

    @Insert("INSERT INTO app_setting (setting_key, setting_value) "
            + "VALUES ('internal.kg.community_fingerprint', #{fingerprint}) "
            + "ON DUPLICATE KEY UPDATE setting_value = VALUES(setting_value)")
    int savePartitionFingerprint(@Param("fingerprint") String fingerprint);

    @Insert("INSERT INTO kg_community (node_id, community_id, level, size, modularity, computed_at) "
            + "VALUES (#{nodeId}, #{communityId}, #{level}, #{size}, #{modularity}, NOW())")
    int insert(@Param("nodeId") String nodeId, @Param("communityId") int communityId,
               @Param("level") int level, @Param("size") int size, @Param("modularity") double modularity);

    /** 社区归属（页面着色 / 社区摘要取成员都走它） */
    @Select("SELECT node_id AS nodeId, community_id AS communityId, size, level, modularity, computed_at AS computedAt "
            + "FROM kg_community ORDER BY community_id, node_id")
    List<Map<String, Object>> all();

    // ---- 社区摘要（GraphRAG 全局视角）----

    /** 写摘要的输入之一：成员的名字 + 一句话说明 */
    @Select("<script>SELECT id, name, brief, type, aliases FROM kg_node WHERE id IN "
            + "<foreach collection='ids' item='i' open='(' separator=',' close=')'>#{i}</foreach></script>")
    List<Map<String, Object>> nodeInfos(@Param("ids") List<String> ids);

    /** 写摘要的输入之二：**社区内部**的三元组（跨社区的关系属于"社区之间"，不进来） */
    @Select("<script>SELECT id, head_id AS head, relation, tail_id AS tail, evidence, sources, weight, origin, "
            + "derived_from AS derivedFrom FROM kg_relation WHERE head_id IN "
            + "<foreach collection='ids' item='i' open='(' separator=',' close=')'>#{i}</foreach> AND tail_id IN "
            + "<foreach collection='ids' item='i' open='(' separator=',' close=')'>#{i}</foreach></script>")
    List<Map<String, Object>> innerRelations(@Param("ids") List<String> ids);

    /**
     * local 检索用：命中概念**周围**的关系（跨社区也算），并且把两端名字与**证据句**一起带出来。
     * 证据不能省：没有证据的关系模型会当成事实用，而这正是图谱最容易被误信的地方。
     */
    @Select("<script>SELECT r.id, r.head_id AS headId, hn.name AS headName, r.relation AS relation, "
            + "r.tail_id AS tailId, tn.name AS tailName, r.evidence AS evidence, r.origin AS origin, "
            + "r.weight, r.sources, r.derived_from AS derivedFrom "
            + "FROM kg_relation r JOIN kg_node hn ON hn.id = r.head_id JOIN kg_node tn ON tn.id = r.tail_id "
            + "WHERE r.head_id IN <foreach collection='ids' item='i' open='(' separator=',' close=')'>#{i}</foreach> "
            + "OR r.tail_id IN <foreach collection='ids' item='i' open='(' separator=',' close=')'>#{i}</foreach> "
            + "ORDER BY CASE WHEN r.origin = 'derived' THEN 1 ELSE 0 END, "
            + "CASE WHEN r.evidence IS NULL OR TRIM(r.evidence) = '' THEN 1 ELSE 0 END, "
            + "CASE WHEN r.head_id IN <foreach collection='ids' item='i' open='(' separator=',' close=')'>#{i}</foreach> "
            + "AND r.tail_id IN <foreach collection='ids' item='i' open='(' separator=',' close=')'>#{i}</foreach> THEN 0 ELSE 1 END, "
            + "r.weight DESC, r.id LIMIT 60</script>")
    List<Map<String, Object>> relationsAround(@Param("ids") List<String> ids);

    /** 摘要校验批量读取，避免每个社区分别查节点/关系。 */
    @Select("SELECT id, name, brief, type, aliases FROM kg_node ORDER BY id")
    List<Map<String, Object>> allNodeInfos();

    @Select("SELECT id, head_id AS head, relation, tail_id AS tail, evidence, sources, weight, origin, "
            + "derived_from AS derivedFrom FROM kg_relation ORDER BY head_id, relation, tail_id")
    List<Map<String, Object>> allRelations();

    @Select("<script>SELECT id, title, content, SHA2(CONCAT(IFNULL(title, ''), CHAR(0), IFNULL(content, '')), 256) AS contentHash "
            + "FROM note WHERE id IN <foreach collection='ids' item='i' open='(' separator=',' close=')'>#{i}</foreach></script>")
    List<Map<String, Object>> noteSources(@Param("ids") List<Long> ids);

    @Select("<script>SELECT id, title, content, SHA2(CONCAT(IFNULL(title, ''), CHAR(0), IFNULL(content, '')), 256) AS contentHash "
            + "FROM quick_ref WHERE id IN <foreach collection='ids' item='i' open='(' separator=',' close=')'>#{i}</foreach></script>")
    List<Map<String, Object>> refSources(@Param("ids") List<Long> ids);

    @Select("<script>SELECT id, origin_name AS title, CONCAT(IFNULL(summary, ''), CHAR(10), IFNULL(text_content, '')) AS content, "
            + "SHA2(CONCAT(origin_name, CHAR(0), IFNULL(summary, ''), CHAR(0), IFNULL(text_content, ''), "
            + "CHAR(0), IFNULL(text_status, '')), 256) AS contentHash "
            + "FROM file_info WHERE id IN <foreach collection='ids' item='i' open='(' separator=',' close=')'>#{i}</foreach></script>")
    List<Map<String, Object>> fileSources(@Param("ids") List<Long> ids);

    /** 该社区已存摘要的成员指纹；没有则 null */
    @Select("SELECT member_hash FROM kg_community_summary WHERE community_id = #{communityId} AND level = 0")
    String summaryHash(@Param("communityId") int communityId);

    /**
     * 这个成员集合是否已经有摘要（**按成员集合查，不看社区编号**）。
     *
     * <p>为什么必须这样查：Leiden 每次重算都可能给同一个社区**换编号**（编号只是当次划分的输出），
     * 而"成员没变"才是"摘要还有效"的真正判据。按编号查的话，图谱里加一个新概念就能让
     * 十几个线程编号错位、摘要全部重写 —— 那是纯浪费（真金白银）。
     */
    @Select("SELECT COUNT(*) FROM kg_community_summary WHERE member_hash = #{memberHash}")
    int countByMemberHash(@Param("memberHash") String memberHash);

    /** 写/覆盖摘要（成员指纹或写作模型变了就重写） */
    @Insert("INSERT INTO kg_community_summary (community_id, level, member_hash, model, summary, size, computed_at) "
            + "VALUES (#{communityId}, 0, #{memberHash}, #{model}, #{summary}, #{size}, NOW()) "
            + "ON DUPLICATE KEY UPDATE member_hash = VALUES(member_hash), model = VALUES(model), "
            + "summary = VALUES(summary), size = VALUES(size), computed_at = NOW()")
    int upsertSummary(@Param("communityId") int communityId, @Param("memberHash") String memberHash,
                      @Param("model") String model, @Param("summary") String summary, @Param("size") int size);

    /** 全部社区摘要（规模大的在前 —— 全局检索优先看大社区） */
    @Select("SELECT community_id AS communityId, member_hash AS memberHash, model, summary, size, "
            + "computed_at AS computedAt FROM kg_community_summary ORDER BY size DESC, community_id")
    List<Map<String, Object>> summaries();
}
