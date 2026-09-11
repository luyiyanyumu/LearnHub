package org.dyh.learnhub.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Update;
import org.dyh.learnhub.entity.FileInfo;

@Mapper
public interface FileInfoMapper extends BaseMapper<FileInfo> {

    /** 解除某分类下所有资料文件的分类归属（删除分类前调用） */
    @Update("UPDATE file_info SET category_id = NULL WHERE category_id = #{categoryId}")
    int unbindFilesByCategory(Long categoryId);
}
