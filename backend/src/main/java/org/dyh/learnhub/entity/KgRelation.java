package org.dyh.learnhub.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 知识图谱的三元组：（头实体，关系，尾实体）。
 *
 * <p>与 {@link KgEdge} 的区别：kg_edge 连的是**文档**（笔记↔速查卡，"这两篇相关"），
 * 是相似度图；这张表连的是**概念**（Git —属于→ 版本控制），才是知识图谱。
 * 两者都保留：前者适合"先看哪篇"，后者适合"这两个概念什么关系、还能顺出什么"。
 */
@Data
@TableName("kg_relation")
public class KgRelation {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String headId;

    /** 本体里的规范关系 id（封闭词表，见 KgOntology） */
    private String relation;

    private String tailId;

    /** 证据句（原文摘录）—— 没有证据的三元组不该进图 */
    private String evidence;

    /** 来源，形如 笔记#5|资料#2 */
    private String sources;

    private Double weight;

    /** llm=模型抽取 / derived=规则推导 */
    private String origin;

    /** 推导依据（两条边的 key），便于溯源与撤回整批推导 */
    private String derivedFrom;

    private String model;

    private LocalDateTime createdAt;
}
