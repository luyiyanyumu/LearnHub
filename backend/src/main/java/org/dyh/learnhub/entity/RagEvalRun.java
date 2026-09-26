package org.dyh.learnhub.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/** 一次检索评测的结果（保留历史，用来对比改动前后） */
@Data
@TableName("rag_eval_run")
public class RagEvalRun {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 本次运行的标签，例如"改前基线" / "加 RRF 融合" */
    private String label;

    private Integer cases;

    private Integer topK;

    /** top-k 内命中的用例占比 */
    private Double recallAtK;

    /** 首个命中位置倒数的均值（能区分"排第1"和"排第5"） */
    private Double mrr;

    /** 只用词面的对照 recall@k */
    private Double keywordRecall;

    /** 只用语义的对照 recall@k */
    private Double vectorRecall;

    /** 逐条结果 JSON */
    private String detail;

    private LocalDateTime createdAt;
}
