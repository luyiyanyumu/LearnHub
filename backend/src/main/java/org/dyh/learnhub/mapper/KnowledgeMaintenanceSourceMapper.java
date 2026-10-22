package org.dyh.learnhub.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;
import java.util.List;
import java.util.Map;

/** Stable source versions; full text stays in MySQL during the periodic discovery scan. */
@Mapper
public interface KnowledgeMaintenanceSourceMapper {
    @Select("""
            SELECT 'note' AS sourceType, id, title, category_id AS categoryId,
                   SHA2(COALESCE(content,''),256) AS contentHash,
                   CHAR_LENGTH(COALESCE(content,'')) AS contentChars, '' AS textStatus FROM note
            UNION ALL
            SELECT 'quick_ref', id, title, category_id, SHA2(COALESCE(content,''),256),
                   CHAR_LENGTH(COALESCE(content,'')), '' FROM quick_ref
            UNION ALL
            SELECT 'file', id, origin_name, category_id,
                   SHA2(CONCAT(COALESCE(summary,''),'\\n',COALESCE(text_content,'')),256),
                   CHAR_LENGTH(CONCAT(COALESCE(summary,''),COALESCE(text_content,''))), text_status FROM file_info
            ORDER BY sourceType,id
            """)
    List<Map<String, Object>> sources();

    @Select("SELECT id, parent_id AS parentId, name FROM category ORDER BY id")
    List<Map<String, Object>> categories();

    @Select("SELECT id, name FROM tag ORDER BY id")
    List<Map<String, Object>> tags();

    @Select("SELECT note_id AS noteId, tag_id AS tagId FROM note_tag ORDER BY note_id,tag_id")
    List<Map<String, Object>> noteTags();
}
