package org.dyh.learnhub.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Data
@Component
@ConfigurationProperties(prefix = "ai.deepseek")
public class AiProperties {

    /** DeepSeek API 基础地址 */
    private String baseUrl = "https://api.deepseek.com";

    /** API Key */
    private String apiKey = "";

    /** 模型名：deepseek-flash（快、便宜）/ deepseek-v4-pro（旗舰），可在设置面板覆盖 */
    private String model = "deepseek-flash";

    /** 采样温度 0-2（仅非思考模式生效；V4 思考模式会忽略该值） */
    private double temperature = 0.4;
}
