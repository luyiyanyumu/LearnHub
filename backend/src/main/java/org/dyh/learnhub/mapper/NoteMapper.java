package org.dyh.learnhub.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;
import org.dyh.learnhub.entity.Note;

import java.util.List;

@Mapper
public interface NoteMapper extends BaseMapper<Note> {

    /** 保存笔记-标签关联（忽略重复） */
    @Insert("<script>" +
            "INSERT IGNORE INTO note_tag(note_id, tag_id) VALUES " +
            "<foreach collection='tagIds' item='tagId' separator=','>(#{noteId}, #{tagId})</foreach>" +
            "</script>")
    int insertNoteTags(@Param("noteId") Long noteId, @Param("tagIds") List<Long> tagIds);

    /** 清空某笔记的全部标签关联 */
    @Delete("DELETE FROM note_tag WHERE note_id = #{noteId}")
    int deleteNoteTags(Long noteId);

    /** 按标签查关联的笔记 id */
    @Select("SELECT note_id FROM note_tag WHERE tag_id = #{tagId} ORDER BY note_id")
    List<Long> selectNoteIdsByTag(Long tagId);

    /** 解除某分类下所有笔记的分类归属（删除分类前调用），返回受影响笔记数 */
    @Update("UPDATE note SET category_id = NULL WHERE category_id = #{categoryId}")
    int unbindNotesByCategory(Long categoryId);
}
