package org.dyh.learnhub.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;

/**
 * 知识图谱 / wiki 用的只读辅助查询。
 * <p>
 * 为什么单独一个 mapper：这些查询不需要实体（只返回一个字符串），
 * 塞进 NoteMapper / QuickRefMapper 反而会让"数据访问"与"图谱关注点"混在一起。
 * <p>
 * 指纹的用途：前端要**实时**知道"图变了没有"，于是每几秒问一次后端。
 * 这个接口必须极轻 —— 不能为了算版本号去拉几百行笔记（列表 VO 虽不含正文，但也没必要）。
 * 一次 <code>SELECT COUNT(*) + MAX(updated_at) + MAX(id)</code> 走索引，代价可以忽略。
 */
@Mapper
public interface KgMapper {

    /** 笔记：条数 + 最新更新时间 + 最大 id（增删改都会让指纹变化） */
    @Select("SELECT CONCAT(COUNT(*), ':', IFNULL(MAX(updated_at), '-'), ':', IFNULL(MAX(id), 0)) FROM note")
    String noteFingerprint();

    @Select("SELECT CONCAT(COUNT(*), ':', IFNULL(MAX(updated_at), '-'), ':', IFNULL(MAX(id), 0)) FROM quick_ref")
    String quickRefFingerprint();

    @Select("SELECT CONCAT(COUNT(*), ':', IFNULL(MAX(updated_at), '-'), ':', IFNULL(MAX(id), 0)) FROM category")
    String categoryFingerprint();

    /** 标签表只有 created_at（标签不常改，够用） */
    @Select("SELECT CONCAT(COUNT(*), ':', IFNULL(MAX(created_at), '-'), ':', IFNULL(MAX(id), 0)) FROM tag")
    String tagFingerprint();

    @Select("SELECT CONCAT(COUNT(*), ':', IFNULL(MAX(created_at), '-'), ':', IFNULL(MAX(id), 0)) FROM kg_edge")
    String edgeFingerprint();

    @Select("SELECT CONCAT(COUNT(*), ':', IFNULL(MAX(updated_at), '-'), ':', IFNULL(MAX(id), 0)) FROM wiki_page")
    String wikiFingerprint();

    /**
     * 资料库：条目数 + 最大 id + **有正文的条数** + 正文总字数。
     * <p>
     * 后两项不能省：上传是"先落库、再抽文"，只算条数与 id 的话，
     * 抽完正文版本号不变，前端不会刷新、主题 wiki 也不会察觉"资料现在有正文了"。
     */
    @Select("SELECT CONCAT(COUNT(*), ':', IFNULL(MAX(id), 0), ':', "
            + "IFNULL(SUM(CASE WHEN text_status = 'ok' THEN 1 ELSE 0 END), 0), ':', "
            + "IFNULL(SUM(text_chars), 0)) FROM file_info")
    String fileFingerprint();

    /**
     * 每条笔记正文字数（id → 字数）。
     * <p>用途是**素材覆盖率**：wiki 对笔记只用 255 字摘要，得拿正文长度才能算出"用了百分之几"，
     * 而这个比例以前完全不可见 —— 一条 5.9 万字的笔记只贡献 0.4%，用户却以为整篇都进去了。
     * 只取长度不取正文，很轻。
     */
    @Select("SELECT id, CHAR_LENGTH(content) AS chars FROM note")
    java.util.List<java.util.Map<String, Object>> noteCharLengths();

    /**
     * 长笔记的跨段采样（笔记在 material 里只带摘要，正文要另外取）。
     * <p>取头/中/尾三段各 1200 字：只取开头的话，5.9 万字的长笔记等于没进素材。
     */
    @Select("SELECT id, CONCAT("
            + "LEFT(content, 1200), '\\n……\\n',"
            + "SUBSTRING(content, GREATEST(1, CHAR_LENGTH(content) DIV 2), 1200), '\\n……\\n',"
            + "RIGHT(content, 1200)) AS sample, CHAR_LENGTH(content) AS chars "
            + "FROM note WHERE CHAR_LENGTH(content) > 1200")
    java.util.List<java.util.Map<String, Object>> noteSamples();
}
