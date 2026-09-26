package org.dyh.learnhub.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 智能体会话的一条事件（append-only，不修改、不删除）。
 * <p>
 * 顺序由自增 {@code id} 表达。role 取值：
 * <ul>
 *   <li>{@code user} / {@code assistant} —— 参与上下文投影（模型能看到）</li>
 *   <li>{@code tool} —— 工具调用与结果，**仅供审计与界面回看，不投影给模型**</li>
 *   <li>{@code summary} —— 对较早内容的压缩摘要，替代「直接丢弃」（P1 只建结构，压缩在后续步骤接）</li>
 * </ul>
 */
@Data
@TableName("agent_event")
public class AgentEvent {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String sessionId;

    private String role;

    private String content;

    /** 工具名（role=tool 时） */
    private String toolName;

    /** 对应模型返回的 tool_call id（role=tool 时） */
    private String toolCallId;

    /** 该轮 token 用量（接口返回 usage 时记录，可为空） */
    private Integer tokens;

    private LocalDateTime createdAt;
}
