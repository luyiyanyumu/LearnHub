package org.dyh.learnhub.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.dyh.learnhub.dto.NoteTagRow;
import org.dyh.learnhub.entity.Tag;

import java.util.Collection;
import java.util.List;

@Mapper
public interface TagMapper extends BaseMapper<Tag> {

    /** 查某篇笔记关联的标签 */
    @Select("SELECT t.* FROM tag t JOIN note_tag nt ON t.id = nt.tag_id WHERE nt.note_id = #{noteId} ORDER BY t.id")
    List<Tag> selectByNoteId(Long noteId);

    /**
     * 批量查多篇笔记的标签（列表页专用，消灭 N+1）。
     * <p>
     * 原来列表页是在 for 循环里对每篇笔记调一次 {@link #selectByNoteId}：
     * 一页 10 条 = 1 次分页 + 10 次标签查询。现在固定 1 次。
     * <p>
     * 返回的是「笔记-标签」明细行（带 note_id），由调用方按 noteId 分组 —— MyBatis
     * 没有内置的一对多集合映射，硬做要给 Tag 塞一个 noteId 非表字段，反而更绕。
     */
    @Select("<script>" +
            "SELECT nt.note_id AS noteId, t.id AS tagId, t.name AS name, t.created_at AS createdAt " +
            "FROM note_tag nt JOIN tag t ON t.id = nt.tag_id " +
            "WHERE nt.note_id IN " +
            "<foreach collection='noteIds' item='nid' open='(' separator=',' close=')'>#{nid}</foreach> " +
            "ORDER BY nt.note_id, t.id" +
            "</script>")
    List<NoteTagRow> selectByNoteIds(@Param("noteIds") Collection<Long> noteIds);

    /** 全部标签 + 被引用次数（列表管理用） */
    @Select("SELECT t.*, (SELECT COUNT(*) FROM note_tag nt WHERE nt.tag_id = t.id) AS use_count " +
            "FROM tag t ORDER BY t.id")
    List<Tag> selectAllWithCount();

    /** 删除某标签与全部笔记的关联（删除标签前调用） */
    @Delete("DELETE FROM note_tag WHERE tag_id = #{tagId}")
    int deleteNoteLinks(Long tagId);

    /** 按 id 批量查标签（保持传入顺序） */
    @Select("<script>" +
            "SELECT * FROM tag WHERE id IN " +
            "<foreach collection='ids' item='id' open='(' separator=',' close=')'>#{id}</foreach>" +
            "</script>")
    List<Tag> selectBatchIdsOrdered(java.util.Collection<Long> ids);
}
