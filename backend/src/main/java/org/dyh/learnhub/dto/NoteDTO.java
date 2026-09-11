package org.dyh.learnhub.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

import java.util.List;

@Data
public class NoteDTO {

    @NotBlank(message = "标题不能为空")
    private String title;

    private String content;

    private Long categoryId;

    /** 关联标签 id 列表 */
    private List<Long> tagIds;
}
