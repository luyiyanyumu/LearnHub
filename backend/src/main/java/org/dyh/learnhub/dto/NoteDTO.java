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

    private Long folderId;

    @com.fasterxml.jackson.annotation.JsonIgnore
    private boolean folderIdProvided;

    /** 省略表示保留现有目录；显式 null/0 才移回未收纳。 */
    public void setFolderId(Long folderId) {
        this.folderId = folderId;
        this.folderIdProvided = true;
    }

    /** 关联标签 id 列表 */
    private List<Long> tagIds;
}
