package org.dyh.learnhub.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.dyh.learnhub.entity.KgEdge;

@Mapper
public interface KgEdgeMapper extends BaseMapper<KgEdge> {

    /**
     * 清掉某个来源的全部关联边。
     * 「重建关联」是整批替换：先把参与重建的条目旧边清掉，再写模型新给的，
     * 否则模型每轮说法不同，旧关联会越积越多。
     */
    @Delete("DELETE FROM kg_edge WHERE origin = #{origin}")
    int deleteByOrigin(@Param("origin") String origin);
}
