package org.dyh.learnhub.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.dyh.learnhub.dto.FolderCountRow;
import org.dyh.learnhub.entity.ContentFolder;

import java.util.List;

@Mapper
public interface ContentFolderMapper extends BaseMapper<ContentFolder> {
    /** 结构修改统一按 kind、id 顺序锁定，避免并发互相移动产生目录环。 */
    @Select("SELECT * FROM content_folder WHERE kind = #{kind} ORDER BY id FOR UPDATE")
    List<ContentFolder> lockKind(@Param("kind") String kind);

    /** 分配条目时锁住目标，使删除/分配在同一事务顺序完成。 */
    @Select("SELECT * FROM content_folder WHERE id = #{id} FOR UPDATE")
    ContentFolder selectForUpdate(@Param("id") Long id);

    @Select("SELECT folder_id AS folderId, COUNT(*) AS itemCount FROM note WHERE folder_id IS NOT NULL GROUP BY folder_id")
    List<FolderCountRow> noteCounts();

    @Select("SELECT folder_id AS folderId, COUNT(*) AS itemCount FROM file_info WHERE folder_id IS NOT NULL GROUP BY folder_id")
    List<FolderCountRow> fileCounts();
}
