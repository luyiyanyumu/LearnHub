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

    /** 用户手填说明（抽不出正文的文件靠它参与检索） */
    private String summary;

    /** 抽取出的正文（文本 / PDF / Office）。列表查询走 @Select 投影，不拉这列 */
    private String textContent;

    /** pending / ok / empty / unsupported / skipped / failed */
    private String textStatus;

    /** 正文字数（列表展示用，避免把 MEDIUMTEXT 拉回来） */
    private Integer textChars;

    /** 抽取失败/跳过的原因，界面展示与排查用 */
    private String textError;

    /**
     * 上次的阅读位置：页码 / 缩放 / 阅读模式（single·double·continuous）。
     * <p>刻意不存滚动像素 —— 窗口大小一变那个值就没意义了。
     */
    private Integer readPage;
    private java.math.BigDecimal readScale;
    private String readMode;

    private LocalDateTime extractedAt;

    private LocalDateTime createdAt;
}
