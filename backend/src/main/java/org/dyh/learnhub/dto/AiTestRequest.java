package org.dyh.learnhub.dto;

import lombok.Data;

/**
 * 「测试连接」请求：所有字段可选。
 * <p>传了就临时用该值去试，不传则回落到当前生效配置——
 * 这样设置面板可以在「保存之前」先验证地址 / 密钥 / 模型是否可用。
 */
@Data
public class AiTestRequest {

    private String baseUrl;
    private String apiKey;
    private String model;
    private String maxTokens;
    private String temperature;
    /** 思考模式："enabled" / "disabled" / 空（自动） */
    private String thinking;
    /** 思考强度："low" / "high" / "max" / 空（服务端默认） */
    private String reasoningEffort;
}
