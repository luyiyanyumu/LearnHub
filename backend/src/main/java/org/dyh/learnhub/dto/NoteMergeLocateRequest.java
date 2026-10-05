package org.dyh.learnhub.dto;

import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 「分节融入」第一步（定位）的请求体。
 *
 * <p>为什么只传**大纲**而不是整篇正文：这一步要回答的是"放哪一节"，
 * 看结构就够；把上万字正文塞进来只会让这一步变贵变慢，而且它的输出本来就只有一行 JSON。
 * 大纲由前端的分节工具生成（`frontend/src/utils/noteSections.js` 的 `buildNoteOutline`），
 * 每行形如 `3.【1832 字】8.Collection包结构 — 子节：… — 开头：…`。
 */
@Data
public class NoteMergeLocateRequest {

    /** 笔记标题（判断术语与命名风格） */
    @Size(max = 300, message = "标题过长")
    private String title;

    /** 笔记大纲：带编号的小节清单（**编号即 index**，模型按编号回话，避免重复标题贴错地方） */
    private String outline;

    /** 用户当时的提问（只用于判断主题归属） */
    private String question;

    /** 要融入的新内容（通常是智能体的回答） */
    private String answer;
}
