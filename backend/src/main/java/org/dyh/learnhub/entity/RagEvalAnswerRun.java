package org.dyh.learnhub.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 一次**答案级**评测的结果（保留历史，用来对比改动前后）。
 *
 * <p>与 {@link RagEvalRun}（检索级）的区别：那张表只回答"期望来源有没有被召回"，
 * 这张表回答"答得对不对、有没有依据、该说不知道时有没有说、花了多少时间与 token"。
 *
 * <p>为什么必须把 {@code corpusHash} / {@code model} / {@code promptVersion} 一起存：
 * 三者任一变了，分数就不再可比。缺了它们，历史里两条不同条件的记录会看起来像"退步了"。
 */
@Data
@TableName("rag_eval_answer_run")
public class RagEvalAnswerRun {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 本次运行的标签，例如"答案基线" / "换 prompt 后" */
    private String label;

    /** 实际跑了多少条用例 */
    private Integer cases;

    private Integer topK;

    /** 检索模式：fused / keyword / vector */
    private String mode;

    /** 语料指纹（各来源内容哈希的聚合），语料变了分数不可比 */
    private String corpusHash;

    /** 生成答案所用的模型名 */
    private String model;

    /** 生成与评分 prompt 的版本号 */
    private String promptVersion;

    /** 本轮是否注入 wiki 块（报告要求的四臂对照：基础 / +wiki / +图谱 / 组合） */
    private Integer wikiInject;

    /** 本轮是否注入概念图谱块 */
    private Integer kgInject;

    /** 答案正确性：expect_words 覆盖率（只统计有标注的题） */
    private Double answerAccuracy;

    /** 引用支持：答案被证据支撑的占比（grounding 判定） */
    private Double citationSupport;

    /** 引用完整性：必要来源**全部**被召回的占比（ALL 而非 ANY） */
    private Double citationCoverage;

    /** 无答案处理：缺口题正确拒答率（该说不知道时说了的比例）；无缺口题时为 0，表示未测 */
    private Double noAnswerScore;

    /** 有材料却拒答的条数（诊断项，与 noAnswerScore 分开记） */
    private Integer falseRefusals;

    /** 整轮墙钟耗时 */
    private Long elapsedMs;

    /** 单题平均生成耗时 */
    private Integer avgLatencyMs;

    private Long promptTokens;

    private Long completionTokens;

    private Long totalTokens;

    /** 估算成本；未配置单价时为 0（此时只信 token 数） */
    private Double estCost;

    /** 逐条结果 JSON（答案、引用、各项判定与耗时） */
    private String detail;

    private LocalDateTime createdAt;
}
