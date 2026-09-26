package org.dyh.learnhub.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.dyh.learnhub.entity.AgentEvent;

@Mapper
public interface AgentEventMapper extends BaseMapper<AgentEvent> {

    /** 删除会话时连带清掉事件（session 表有外键约束的话也能靠级联，这里显式删更直观） */
    @Delete("DELETE FROM agent_event WHERE session_id = #{sessionId}")
    int deleteBySessionId(@Param("sessionId") String sessionId);
}
