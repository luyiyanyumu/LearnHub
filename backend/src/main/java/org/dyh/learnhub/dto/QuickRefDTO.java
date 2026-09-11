package org.dyh.learnhub.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class QuickRefDTO {

    @NotBlank(message = "速查项标题不能为空")
    private String title;

    private String content;

    private Long categoryId;
}
