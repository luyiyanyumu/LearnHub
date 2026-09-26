package org.dyh.learnhub.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 知识图谱的节点（实体 / 概念）。
 *
 * <p>与 wiki 的实体页是**两件事**：实体页是给人读的一段结构化长文；这里的节点是图里的一个点，
 * 身份由 {@code norm}（归一化名）唯一决定，靠它才能把"模型这次写 Git、下次写 git"收成同一个点。
 * 两者通过 {@code wiki_key} 互链。
 */
@Data
@TableName("kg_node")
public class KgNode {

    /** e-<sha256(归一化名) 前 10 位>，由 EntityLinker.id() 生成（应用侧赋值，不是自增） */
    @TableId(type = IdType.INPUT)
    private String id;

    /** 显示名（首次出现时的写法，保留大小写） */
    private String name;

    /** 归一化名：折叠空白/全角转半角/去括注/小写。唯一键 */
    private String norm;

    /** concept / tool / language / framework / command / term */
    private String type;

    /** 别名，用 | 分隔（含括注里的写法） */
    private String aliases;

    /** 一句话说明：它是什么 */
    private String brief;

    /** 对应 wiki 实体页的 topic_key */
    private String wikiKey;

    /** 提到它的素材条数 */
    private Integer sourceCount;

    /** bge-m3 向量（float32 小端），实体级相似度/消歧用；未计算时为 null */
    private byte[] embedding;

    private LocalDateTime updatedAt;

    private LocalDateTime createdAt;
}
