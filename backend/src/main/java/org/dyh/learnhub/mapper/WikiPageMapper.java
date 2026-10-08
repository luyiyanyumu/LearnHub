package org.dyh.learnhub.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.dyh.learnhub.entity.WikiPage;

import java.util.List;
import java.util.Map;

@Mapper
public interface WikiPageMapper extends BaseMapper<WikiPage> {
    /** Generation uses the newest 200 files. Fetch one extra ID to detect incomplete scope without reading text. */
    @Select("SELECT id, category_id AS categoryId FROM file_info ORDER BY created_at DESC LIMIT #{limit}")
    List<Map<String, Object>> retrievalFileScope(@Param("limit") int limit);
}
