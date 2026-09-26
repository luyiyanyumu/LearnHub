package org.dyh.learnhub.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 一条检索评测用例。
 *
 * <p>写法要点：问题用**真实口语问法**，不要照抄标题 —— 照抄标题测的是"字符串相等"，
 * 而真实场景的难点是"用户这么说、资料里那么写"。这正是 {@code note} 列要记的东西。
 */
@Data
@TableName("rag_eval")
public class RagEval {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String question;

    /** 应命中的来源，形如 {@code note:5|file:2}（| 分隔，命中任一即算召回） */
    private String expectRefs;

    /** 答案里应出现的关键词，| 分隔（人工核对用，不参与自动打分） */
    private String expectWords;

    /** 这条用例想验证什么 */
    private String note;

    private Integer enabled;

    private LocalDateTime createdAt;
}
