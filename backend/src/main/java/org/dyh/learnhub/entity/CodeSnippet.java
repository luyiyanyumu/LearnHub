package org.dyh.learnhub.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 一段**代码片段**（代码库里真正存代码的实体）。
 *
 * <p>字段的取舍：{@code explainText} 与 {@code code} 同等重要 —— 没有"为什么这么写/怎么用"的
 * 代码片段检索价值很低（这一点在选型时就明确过：值得进库的不是代码，而是关于代码的知识）。
 * 所以界面上把说明做成必填引导，而不是可选的附属。
 */
@Data
@TableName("code_snippet")
public class CodeSnippet {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 所属项目（可空） */
    private Long repoId;

    private String title;

    /** java / python / js / ts / go / sql / shell … */
    private String lang;

    /** 代码原文 */
    private String code;

    /** 说明：为什么这么写 / 怎么用 / 踩过什么坑（列名 explain 是 MySQL 保留字，故加 _text） */
    private String explainText;

    /** 原文件路径，用于"在哪定义"的定位 */
    private String filePath;

    /** 出处链接（仓库文件页 / 官方文档） */
    private String sourceUrl;

    /** 抽取出的标识符（空格分隔），关键词/前缀检索用 */
    private String symbols;

    private Integer lineCount;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;
}
