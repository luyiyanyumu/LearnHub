package org.dyh.learnhub.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("file_info")
public class FileInfo {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 原始文件名（展示用） */
    private String originName;

    /** 存储文件名（uuid + 扩展名，避免重名冲突） */
    private String storeName;

    /** 文件大小（字节） */
    private Long size;

    /** 扩展名（小写，不含点） */
    private String ext;

    /** 可选：所属分类 */
    private Long categoryId;

    private LocalDateTime createdAt;
}
