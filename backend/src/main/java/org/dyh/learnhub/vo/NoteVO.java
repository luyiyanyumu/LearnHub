package org.dyh.learnhub.vo;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Data;
import org.dyh.learnhub.entity.Tag;

import java.time.LocalDateTime;
import java.util.List;

@Data
public class NoteVO {

    private Long id;
    private String title;

    /** 摘要：列表展示时截取正文前 120 字 */
    private String summary;

    /** 正文：仅详情接口返回，列表接口不序列化 */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private String content;

    private Long categoryId;
    private String categoryName;
    private List<Tag> tags;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
