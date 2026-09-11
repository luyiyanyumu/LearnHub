package org.dyh.learnhub.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("note")
public class Note {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String title;

    private String content;

    /**
     * 纯文本摘要（写入时由 content 算好落库，见 NoteService#plainSummary）。
     * <p>
     * 存在的意义：列表接口没必要把整段 LONGTEXT 正文从 MySQL 拉到 Java 再截前 120 字。
     * 单篇笔记正文动辄 5000~8000 字，一页 10 条就是几十 KB 的无用网络传输。
     * 老数据该列为 NULL，由 {@code SummaryBackfillRunner} 在启动时补一次。
     */
    private String summary;

    private Long categoryId;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;
}
