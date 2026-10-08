package org.dyh.learnhub.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/** Generation-time source locators. Matching these hashes is not semantic fact verification. */
@Data
@TableName("wiki_source_dependency")
public class WikiSourceDependency {
    private Long pageId;
    private String sourceType;
    private Long sourceId;
    private Integer seq;
    private String sourceTitle;
    private String heading;
    private String chunkText;
    private String chunkHash;
    private String fullContentHash;
    private Integer sourceChars;
    private Integer sourceChunkCount;
    private String pageMdHash;
    private String snapshotHash;
    private LocalDateTime capturedAt;
}
