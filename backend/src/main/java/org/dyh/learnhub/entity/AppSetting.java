package org.dyh.learnhub.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("app_setting")
public class AppSetting {

    /** 设置键，如 ai.model / ai.polish_prompt / ai.format_prompt */
    @TableId(type = IdType.INPUT)
    private String settingKey;

    /** 设置值；为空表示使用内置默认 */
    private String settingValue;

    private LocalDateTime updatedAt;
}
