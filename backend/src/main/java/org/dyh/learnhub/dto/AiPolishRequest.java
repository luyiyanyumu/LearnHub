package org.dyh.learnhub.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/**
 * 笔记正文的 AI 处理请求：语言润色 / 整理格式。
 */
@Data
public class AiPolishRequest {

    @NotBlank(message = "内容不能为空")
    private String text;

    /** polish=语言润色 | format=整理 Markdown 格式，默认 polish */
    private String mode = "polish";
}
