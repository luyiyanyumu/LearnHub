package org.dyh.learnhub.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 待确认的写操作（审批队列）。
 * <p>
 * 设计要点：确认时**按 {@code argsJson} 原样执行**，不用"当前对话状态"重算 ——
 * 否则用户在确认前又聊了几轮，实际执行的参数可能已经不是他看到的那一份了。
 */
@Data
@TableName("agent_pending_action")
public class AgentPendingAction {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String sessionId;

    /** 要执行的工具名 */
    private String toolName;

    /** 原样保存的调用参数（JSON 字符串） */
    private String argsJson;

    /** 给用户看的一行摘要 */
    private String summary;

    /** pending / approved / rejected */
    private String status;

    private LocalDateTime createdAt;

    private LocalDateTime resolvedAt;
}
