package org.dyh.learnhub.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 向量索引的**按来源指纹**：记录每个来源"上次索引时内容是什么"。
 *
 * <p>增量索引靠它判断"变了没有"：指纹相同就跳过，不再为改一条笔记重建全库。
 * 用指纹而不是"接收变更事件里的 id"，是因为写入路径不止一条（页面保存 / 智能体确认执行 /
 * 资料上传 / 直接改库），靠事件传 id 一定会漏；指纹对比是幂等且完备的。
 * <p>主键用 {@code type:id} 单列（如 {@code note:5}）：MyBatis-Plus 对复合主键支持很别扭，
 * 而单列主键让 selectById / deleteById 都能直接用。
 */
@Data
@TableName("kb_index_state")
public class KbIndexState {

    /** note:5 / quick_ref:3 / file:2 */
    @TableId(type = IdType.INPUT)
    private String id;

    private String sourceType;

    private Long sourceId;

    /** 该来源当前内容的 sha256（标题 + 正文） */
    private String contentHash;

    private String embeddingSpace;

    private Integer chunks;

    private LocalDateTime indexedAt;

    public static String key(String type, Long id) {
        return type + ":" + id;
    }
}
