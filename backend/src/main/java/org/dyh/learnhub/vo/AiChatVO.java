package org.dyh.learnhub.vo;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 智能体对话响应：正文 + 工具操作事件流 + 会话 id。
 */
@Data
public class AiChatVO {

    /** 本次对话所属会话 id（前端存下来，下次带上即可续聊；刷新页面也不丢） */
    private String sessionId;

    /** 助手最终文本回复（Markdown） */
    private String reply;

    /**
     * 本轮的**思考过程**（thinking 模型返回的 reasoning_content；模型不支持思考或思考关闭时为空）。
     * <p>
     * 为什么单独给一个字段而不是拼进 reply：思考是"过程"，回答是"结论"，
     * 混在一起既会污染可保存为笔记的正文，也会让人分不清哪句是承诺。
     * 界面把它渲染成可折叠的「思考过程」，与回答分层展示。
     */
    private String reasoning;

    /** 本轮对话中发生的工具操作记录（如“已创建笔记 xx”），前端可作气泡/角标提示 */
    private List<String> events = new ArrayList<>();

    /** 是否发生过工具调用（写操作） */
    private boolean toolUsed;

    /**
     * 本轮自动检索命中的记录（{type,id,title}，按相关度排序，最多 5 条）。
     * <p>
     * 为什么返回给前端：这是**透明度** —— 用户能一眼看出这次回答有没有站在他自己的笔记上，
     * 而不是只能猜。顺带也让它可被测试断言，不必去翻服务端日志。
     */
    private List<Map<String, Object>> retrieved = new ArrayList<>();

    /**
     * 本轮发起的、**等待用户确认**的写操作（{id, toolName, summary}）。
     * <p>界面上渲染成确认卡片；在用户点确认之前，数据库零改动。
     */
    private List<Map<String, Object>> pendingActions = new ArrayList<>();

    /**
     * 答案级校验结果（没开启 / 没检索到材料时为 null）。
     * <p>字段：{@code grounded} 是否全部有证据支撑、{@code unsupported} 不被支撑的断言、
     * {@code note} 说明、{@code evidenceChars} 本轮证据字数。
     * <p>为什么要回报给界面：检索只能保证"材料在上下文里"，不能保证"模型按材料说话"。
     * 把核对结论亮出来，用户才知道这句回答是"你记过的"还是"它自己补的"。
     */
    private Map<String, Object> grounding;
}
