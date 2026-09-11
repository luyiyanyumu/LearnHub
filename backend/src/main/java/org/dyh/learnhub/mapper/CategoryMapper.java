package org.dyh.learnhub.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.dyh.learnhub.entity.Category;

@Mapper
public interface CategoryMapper extends BaseMapper<Category> {
}
