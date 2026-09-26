package org.dyh.learnhub.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 代码库里的**项目/仓库容器**。
 *
 * <p>为什么要这一层：论文常带一个仓库 + 一个演示站，片段挂在项目下才说得清出处；
 * 也允许"只有链接、没有代码"的项目 —— 那样它就是一条**指针**（"这个方法的实现在这"）。
 * 指针不参与检索排名，只在命中片段时作为出处带出来，避免"仓库首页"被当成技术答案。
 */
@Data
@TableName("code_repo")
public class CodeRepo {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String name;

    /** 仓库地址（如 GitHub） */
    private String url;

    /** 演示站地址 */
    private String demoUrl;

    /** 许可证：引用别人的代码必须留痕 */
    private String license;

    /** 一句话说明 */
    private String note;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;
}
