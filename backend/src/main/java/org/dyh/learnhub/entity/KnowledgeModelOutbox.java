package org.dyh.learnhub.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/** Durable upload revision snapshot; text is never returned by the monitoring API. */
@Data
@TableName("knowledge_model_outbox")
public class KnowledgeModelOutbox {
    private Long id;
    private Long fileId;
    private String title;
    private String revision;
    private String textContent;
    private String state;
    private Integer attempts;
    private String remoteJobId;
    private String lastError;
    private LocalDateTime nextAttemptAt;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
