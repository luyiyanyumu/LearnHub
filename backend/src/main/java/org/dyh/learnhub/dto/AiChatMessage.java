package org.dyh.learnhub.dto;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/**
 * 单条对话消息（与 OpenAI messages 结构对齐）。
 */
@Data
public class AiChatMessage {

    /** system / user / assistant */
    private String role;

    private String content;

    public AiChatMessage() {
    }

    public AiChatMessage(String role, String content) {
        this.role = role;
        this.content = content;
    }
}
