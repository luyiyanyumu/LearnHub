package org.dyh.learnhub.vo;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/**
 * 智能体对话响应：正文 + 工具操作事件流。
 */
@Data
public class AiChatVO {

    /** 助手最终文本回复（Markdown） */
    private String reply;

    /** 本轮对话中发生的工具操作记录（如“已创建笔记 xx”），前端可作气泡/角标提示 */
    private List<String> events = new ArrayList<>();

    /** 是否发生过工具调用（写操作） */
    private boolean toolUsed;
}
