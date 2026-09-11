package org.dyh.learnhub.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/**
 * 智能体对话请求。
 * <p>
 * 服务端无状态：前端把最近的历史（含上一条 assistant 回复）整体带回；
 * 若从笔记编辑页发起，可附带当前笔记 id 与标题作为上下文。
 */
@Data
public class AiChatRequest {

    @NotBlank(message = "消息不能为空")
    private String message;

    /** 历史消息（不含本次 message），按时间正序，最多保留 12 条 */
    private List<AiChatMessage> history = new ArrayList<>();

    /** 关联的笔记 id（编辑页发起时可选） */
    private Long noteId;

    /** 当前笔记标题（作为上下文提示） */
    private String noteTitle;

    /** 当前笔记正文节选（编辑页发起时可选，控制长度避免浪费 token） */
    private String noteContext;
}
