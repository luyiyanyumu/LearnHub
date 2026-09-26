package org.dyh.learnhub.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/** 语义检索的分块索引（一块 = 一段可独立检索的文字 + 它的向量） */
@Data
@TableName("kb_chunk")
public class KbChunk {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** note / quick_ref / file */
    private String sourceType;

    private Long sourceId;

    /** 该来源的第几块（从 0 开始） */
    private Integer seq;

    /** 来源标题（冗余存储，检索结果展示用，避免再查一次原表） */
    private String title;

    /**
     * 所属小节路径（如 {@code 二、JVM > 内存结构}）。
     * <p>嵌入时用它 + 标题前置到正文之前（contextual retrieval）：
     * 只喂正文的话，"它包含以下三种"这种句子脱离小节就无从判断指什么。
     * 展示用的片段仍是 {@code chunkText} 原文，不受影响。
     */
    private String heading;

    private String category;

    private String chunkText;

    private Integer charLen;

    /** 向量：dim × float32（小端） */
    private byte[] vec;

    private Integer dim;

    /** 嵌入模型名：换模型后旧向量不可比，需要重建 */
    private String model;

    private LocalDateTime updatedAt;

    private LocalDateTime createdAt;
}
