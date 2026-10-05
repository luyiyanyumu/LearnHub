package org.dyh.learnhub.dto;

import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 「分节融入」第二步（节内改写）的请求体。
 *
 * <p>只传**目标小节**，不传整篇：这样模型的输出量由"这一节"决定，与被融入的笔记多长无关 ——
 * 长笔记融不进去的根因（输出 = 整篇）就没了。整篇的其它部分由前端按区间贴补丁，
 * **根本不经过模型**，所以不可能被改写掉。
 */
@Data
public class NoteMergeSectionRequest {

    /** 笔记标题（判断术语与命名风格） */
    @Size(max = 300, message = "标题过长")
    private String title;

    /** 笔记大纲：让模型知道这一节在整篇里的位置（用它决定内容怎么组织，不要输出别的节） */
    private String outline;

    /** 目标小节的标题文本（不含 `#`）；标题行由前端原样保留，模型只输出正文 */
    @Size(max = 300, message = "小节标题过长")
    private String heading;

    /** 目标小节的正文（**不含标题行**）：改写模式下是全部原文，插入模式下是判断重复用的上下文 */
    private String section;

    /** 用户当时的提问 */
    private String question;

    /** 要融入的新内容 */
    private String answer;

    /** `rewrite`（默认，整节重写）/ `insert`（该节太大，只产出新增子小节） */
    private String mode;
}
