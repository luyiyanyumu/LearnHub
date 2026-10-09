package org.dyh.learnhub.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 一个**模型配置档案**：provider + 基址 + 密钥 + 模型名。
 *
 * <p>为什么不是"主模型 + 本地模型"两个固定位置：实际会同时配好几个
 * （DeepSeek 云端、Kimi、火山方舟、本地 Ollama、LM Studio、vLLM…），
 * 而且"哪个任务用哪个档案"是本应用最需要自由的地方 —— 判断类任务用强模型、
 * 批量摘要类用便宜/本地模型。所以档案做成**可无限新增的列表**，任务各自指向一个。
 *
 * <p><b>密钥永不回传</b>：接口只返回"是否已配置 + 尾号"（{@code sk-****4810} 这种），
 * 避免前端/日志里出现明文。
 */
@Data
@TableName("model_profile")
public class ModelProfile {

    @TableId(type = IdType.INPUT)
    private String id;

    /** 显示名，如「DeepSeek 云端」「本地 Ollama」 */
    private String name;

    /** deepseek / openai / kimi / ark / ollama / lmstudio / vllm / custom */
    private String provider;

    /** chat（对话/生成）或 embedding（向量嵌入）；历史档案默认 chat。 */
    private String purpose;

    /** OpenAI 兼容基址 */
    private String baseUrl;

    /** 密钥；接口层不返回，见 ModelProfileService#mask */
    private String apiKey;

    private String model;

    /** 备注：这档准备用来干什么 */
    private String note;

    // ------------------------------------------------------------------
    // 生成参数：**跟着档案走**（而不是全局一组）。
    //
    // 为什么：本地 qwen3:8b 与云端 deepseek-flash 想要的参数不一样 —— 小模型要更小的输出上限、
    // 思考型模型下发温度会被忽略、机械任务要关掉思考才快。全局单值等于逼所有模型用同一套折中值。
    // **四者留空 = 跟随全局默认**，所以不填时行为与以前完全一致。
    // ------------------------------------------------------------------

    /** 输出上限；null = 跟随全局默认 */
    private Integer maxTokens;

    /** 温度；null = 跟随全局默认。用 BigDecimal 对应 DECIMAL(3,2)，避免二进制浮点误差 */
    private java.math.BigDecimal temperature;

    /** 思考模式：null=自动 / enabled / disabled */
    private String thinking;

    /** 思考强度：null=服务端默认 / none / minimal / low / medium / high / xhigh / max */
    private String reasoningEffort;

    private Integer sortOrder;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;
}
