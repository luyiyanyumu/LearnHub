package org.dyh.learnhub.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/**
 * 智能体对话请求。
 * <p>
 * 2026-09 起上下文由**服务端会话**维护：带上 {@code sessionId} 即可续聊，
 * 历史由后端从事件日志里投影，前端不必再自己拼。
 * <p>
 * {@code history} 仅为**兼容老前端**保留：当带了 sessionId 时它被忽略；
 * 没带 sessionId 时（升级过渡期里浏览器可能还缓存着旧包）用它兜底，避免突然"失忆"。
 */
@Data
public class AiChatRequest {

    @NotBlank(message = "消息不能为空")
    private String message;

    /**
     * 本会话使用的模型档案 id（可空 = 跟随任务分工表）。
     * <p>界面上在智能体面板里切换模型时带上来，实现"每个会话各自选模型"。
     */
    private String modelProfileId;

    /** 会话 id；为空 = 开一个新会话，响应里会带回实际使用的 id */
    private String sessionId;

    /** 历史消息（兼容旧调用方；有 sessionId 时忽略） */
    private List<AiChatMessage> history = new ArrayList<>();

    /** 关联的笔记 id（编辑页发起时可选） */
    private Long noteId;

    /** 当前笔记标题（作为上下文提示） */
    private String noteTitle;

    /** 当前笔记正文节选（编辑页发起时可选，控制长度避免浪费 token） */
    private String noteContext;
}
