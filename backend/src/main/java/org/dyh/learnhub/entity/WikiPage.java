package org.dyh.learnhub.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * LLM wiki 页：一个主题（分类/标签）一份，由模型把该主题下的笔记与速查卡整理成结构化长文。
 * <p>
 * 为什么要落库而不是每次现算：生成一次要几秒到几十秒、还花 token，
 * 刷新页面或换个标签页回来不该重算。
 * <p>
 * {@code sourceHash} 是生成时「素材」的指纹（见 WikiService#sourceHash）：
 * 与当前素材不一致就说明这份 wiki 已经过期，界面据此显示「待更新」，也支撑"改完笔记自动增量更新"。
 */
@Data
@TableName("wiki_page")
public class WikiPage {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** category / tag */
    private String topicType;

    /** 分类或标签 id */
    private Long topicId;

    /** 主题唯一键：cat-3 / tag-2 */
    private String topicKey;

    /** 主题标题（取分类名） */
    private String title;

    /** 模型生成的结构化正文（Markdown） */
    private String contentMd;

    /** 生成时素材的指纹 */
    private String sourceHash;

    /** 2 表示采用独立的生成来源依赖；即使依赖行全部缺失，也不能回落为旧页检查。 */
    private Integer dependencyVersion;

    /** 生成时的条目数 */
    private Integer itemCount;

    /** 生成用的模型名（排查用） */
    private String model;

    /** 质量校验结论：ok / warn（warn 表示有硬性问题且重生成后仍存在） */
    private String quality;

    /** 校验发现的问题（多条用；分隔），界面悬浮展示 */
    private String qualityNote;

    /** 用哪个模型目标生成的：main / local */
    private String targetId;

    private LocalDateTime generatedAt;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;
}
