package org.dyh.learnhub.vo;

import lombok.Data;

import java.time.LocalDateTime;

@Data
public class QuickRefVO {

    private Long id;
    private String title;
    private String content;
    private Long categoryId;
    private String categoryName;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
