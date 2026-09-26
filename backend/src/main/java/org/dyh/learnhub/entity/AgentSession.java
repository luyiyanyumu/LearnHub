package org.dyh.learnhub.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 智能体会话。
 * <p>
 * 一条会话 = 一串 {@link AgentEvent} 事件；本表只存会话级的元信息（标题、来源笔记、时间），
 * 供「最近会话」列表与界面标题使用。
 */
@Data
@TableName("agent_session")
public class AgentSession {

    /** 会话 id（UUID，由服务端生成） */
    @TableId(type = IdType.INPUT)
    private String id;

    /** 标题：取首条用户消息的前若干字，便于在列表里认出来 */
    private String title;

    /**
     * 本会话使用的模型档案 id（空 = 跟随任务分工表）。
     * <p>"新开不同会话"要能各自选模型 —— 例如一个会话固定用便宜模型问杂事、
     * 另一个固定用强模型做分析，不必每次去设置里改全局分工。
     */
    private String modelProfileId;

    /** 发起时所在的笔记 id（从编辑页打开智能体时带上，可为空） */
    private Long noteId;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;
}
