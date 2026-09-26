package org.dyh.learnhub.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

/**
 * **符号索引**的一行：某个片段里某个类/函数/方法/文件叫什么、在第几行。
 *
 * <p>为什么单独一张表而不是在代码里 LIKE：'在哪定义' 是**精确**问题，
 * 用向量或全文模糊匹配既慢又不准；有名字+行号才能直接跳过去。
 * 这也正是"代码该不该进知识库"那次讨论的结论：符号定位靠索引，不靠语义检索。
 */
@Data
@TableName("code_symbol")
public class CodeSymbol {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long snippetId;

    /** class / interface / enum / record / function / method / const / file */
    private String kind;

    private String name;

    /** 定义所在行（1 起） */
    private Integer line;
}
