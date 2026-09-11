package org.dyh.learnhub.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Update;
import org.dyh.learnhub.entity.QuickRef;

@Mapper
public interface QuickRefMapper extends BaseMapper<QuickRef> {

    /** 解除某分类下所有速查卡的分类归属（删除分类前调用） */
    @Update("UPDATE quick_ref SET category_id = NULL WHERE category_id = #{categoryId}")
    int unbindQuickRefsByCategory(Long categoryId);
}
