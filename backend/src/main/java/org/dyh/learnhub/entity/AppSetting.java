package org.dyh.learnhub.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("app_setting")
public class AppSetting {

    /** 设置键，如 ai.model / ai.temperature / ai.chat_prompt（润色、格式的提示词已改为技能文件，不在这里） */
    @TableId(type = IdType.INPUT)
    private String settingKey;

    /** 设置值；为空表示使用内置默认 */
    private String settingValue;

    private LocalDateTime updatedAt;
}
