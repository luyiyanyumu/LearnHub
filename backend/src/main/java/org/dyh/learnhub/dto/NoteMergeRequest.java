package org.dyh.learnhub.dto;

import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 「把智能体的回答融入当前笔记」请求体。
 *
 * <p>为什么单独一个 DTO 而不是复用 {@code AiPolishRequest}：润色只需要一段文本，
 * 而"融入"必须同时拿到**原笔记**（判断结构）、**新内容**（要放进去的知识）和
 * **用户当时问的问题**（判断主题归属）。三者缺一，模型就只能瞎猜位置 ——
 * 而那正是这一版要修掉的问题。
 */
@Data
public class NoteMergeRequest {

    /** 目标笔记 id（仅用于日志与前端回执，不参与模型调用） */
    private Long noteId;

    /** 笔记标题（可选：模型据此判断术语与风格） */
    @Size(max = 300, message = "标题过长")
    private String title;

    /** 原笔记正文（Markdown） */
    private String note;

    /** 用户当时的提问（可选：用于判断主题与放置位置） */
    private String question;

    /** 要融入的新内容：通常是智能体的回答 */
    private String answer;
}
