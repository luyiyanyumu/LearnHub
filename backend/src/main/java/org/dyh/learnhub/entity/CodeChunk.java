package org.dyh.learnhub.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 代码库的**语义块**（可选索引）。
 *
 * <p>刻意独立于知识库的 {@code kb_chunk}：混在一起会让"我自己的笔记"被大量相似代码挤掉
 *（知识检索的指标是在 373 块上测的，不能拿它冒险）。
 */
@Data
@TableName("code_chunk")
public class CodeChunk {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long snippetId;

    /** 片段内序号（长代码切多块时用） */
    private Integer idx;

    private String heading;

    private String chunkText;

    /** 向量（JSON 数组字符串，1024 维）；embedding 与文本分开存，成本高 */
    private String embedding;

    private LocalDateTime createdAt;
}
