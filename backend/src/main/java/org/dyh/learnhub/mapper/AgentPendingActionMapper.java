package org.dyh.learnhub.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.dyh.learnhub.entity.AgentPendingAction;

@Mapper
public interface AgentPendingActionMapper extends BaseMapper<AgentPendingAction> {

    /** 删除会话时一并清掉它的待确认操作 */
    @Delete("DELETE FROM agent_pending_action WHERE session_id = #{sessionId}")
    int deleteBySessionId(@Param("sessionId") String sessionId);
}
