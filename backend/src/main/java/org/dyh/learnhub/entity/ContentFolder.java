package org.dyh.learnhub.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/** 收纳目录独立于知识分类；移动目录不改变检索、Wiki 或图谱归属。 */
@Data
@TableName("content_folder")
public class ContentFolder {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String kind;
    private String name;
    private Long parentId;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
