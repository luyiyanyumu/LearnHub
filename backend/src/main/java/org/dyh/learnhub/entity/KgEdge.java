package org.dyh.learnhub.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 知识图谱的语义关联边（模型推断出来的那些）。
 * <p>
 * 结构关系不在这里：笔记→分类、笔记→标签 这类事实是查询时直接算出来的（见 KgService#graph），
 * 落库只会带来一致性问题（改个分类要同步删边）。这张表只存"模型认为这两条有关联"。
 * <p>
 * 重建关联时按 {@code origin} 整批替换：模型每次给的关联都会变，
 * 增量合并会让旧关联越积越多、逐渐失真。
 */
@Data
@TableName("kg_edge")
public class KgEdge {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** note / ref */
    private String sourceType;

    private Long sourceId;

    /** note / ref */
    private String targetType;

    private Long targetId;

    /** related（相关）/ contrast（易混）/ prerequisite（前置） */
    private String relation;

    /** 模型给出的关联理由，界面悬浮时展示 —— 没有理由的连线用户无法判断该不该信 */
    private String reason;

    /** 关联强度 0~1 */
    private Double weight;

    /** llm / user */
    private String origin;

    private LocalDateTime createdAt;
}
