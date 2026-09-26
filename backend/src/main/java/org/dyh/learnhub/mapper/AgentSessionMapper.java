package org.dyh.learnhub.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;
import org.dyh.learnhub.entity.AgentSession;

@Mapper
public interface AgentSessionMapper extends BaseMapper<AgentSession> {

    /**
     * 只为把 updated_at 推到当前时间。
     * <p>
     * 会话行只有元信息，追加事件时不改任何列，而 MySQL 的
     * {@code ON UPDATE CURRENT_TIMESTAMP} 仅在真的发生 UPDATE 时才生效 ——
     * 所以想让它代表「最近活跃时间」，必须显式戳一下。
     */
    @Update("UPDATE agent_session SET updated_at = CURRENT_TIMESTAMP WHERE id = #{id}")
    int touch(@Param("id") String id);
}
