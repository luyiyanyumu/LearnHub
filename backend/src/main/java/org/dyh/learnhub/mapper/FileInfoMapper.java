package org.dyh.learnhub.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;
import org.dyh.learnhub.entity.FileInfo;

import java.util.List;
import java.util.Map;

@Mapper
public interface FileInfoMapper extends BaseMapper<FileInfo> {

    /** 解除某分类下所有资料文件的分类归属（删除分类前调用） */
    @Update("UPDATE file_info SET category_id = NULL WHERE category_id = #{categoryId}")
    int unbindFilesByCategory(Long categoryId);

    /**
     * 统一检索候选：文件名 / 说明 / 正文一起 LIKE，正文只取前 1200 字
     * （够做命中窗口展示，又不把 MEDIUMTEXT 整篇拉回来）。kw 为空时退化为"最近资料"。
     */
    @Select("<script>"
            + "SELECT f.id AS id, f.origin_name AS originName, f.ext AS ext, f.size AS size, "
            + "f.summary AS summary, f.text_chars AS textChars, f.text_status AS textStatus, "
            + "LEFT(f.text_content, 1200) AS text, c.name AS categoryName, f.created_at AS createdAt "
            + "FROM file_info f LEFT JOIN category c ON c.id = f.category_id "
            + "<where>"
            + "  <if test='kw != null and kw != \"\"'>"
            + "    AND (f.origin_name LIKE CONCAT('%', #{kw}, '%')"
            + "      OR f.summary LIKE CONCAT('%', #{kw}, '%')"
            + "      OR f.text_content LIKE CONCAT('%', #{kw}, '%'))"
            + "  </if>"
            + "</where>"
            + "ORDER BY f.created_at DESC LIMIT #{limit}"
            + "</script>")
    List<Map<String, Object>> searchFiles(@Param("kw") String kw, @Param("limit") int limit);

    /**
     * 自动召回候选：按时间倒序取最近 N 条，正文截前 2000 字用于词面打分。
     * 与笔记侧"扫描最近 200 条轻量列表 VO"的策略一致 —— 刻意只取轻量列。
     */
    @Select("SELECT f.id AS id, f.origin_name AS originName, f.summary AS summary, f.ext AS ext, "
            + "f.category_id AS categoryId, f.text_chars AS textChars, "
            + "LEFT(f.text_content, 2000) AS text, c.name AS categoryName "
            + "FROM file_info f LEFT JOIN category c ON c.id = f.category_id "
            + "ORDER BY f.created_at DESC LIMIT #{limit}")
    List<Map<String, Object>> retrievalScan(@Param("limit") int limit);

    /**
     * 自动召回候选（**按词先过滤，再截断打分**）。
     * <p>
     * 为什么不能只按时间取最近 N 条：那样"只在文档后半段出现的词"永远召不回来
     * （前缀只有 2000 字）。这里先用**整列 LIKE** 把真正含这些词的资料筛出来，
     * 返回时正文仍只截前 2000 字用于打分 —— 召回不被截断限制，打分依然轻量。
     */
    @Select("<script>"
            + "SELECT f.id AS id, f.origin_name AS originName, f.summary AS summary, f.ext AS ext, "
            + "f.category_id AS categoryId, f.text_chars AS textChars, "
            + "LEFT(f.text_content, 2000) AS text, c.name AS categoryName "
            + "FROM file_info f LEFT JOIN category c ON c.id = f.category_id "
            + "<where><foreach collection='terms' item='t' open='(' separator=' OR ' close=')'>"
            + "  f.origin_name LIKE CONCAT('%', #{t}, '%')"
            + "  OR f.summary LIKE CONCAT('%', #{t}, '%')"
            + "  OR f.text_content LIKE CONCAT('%', #{t}, '%')"
            + "</foreach></where>"
            + "ORDER BY f.created_at DESC LIMIT #{limit}"
            + "</script>")
    List<Map<String, Object>> retrievalScanByTerms(@Param("terms") List<String> terms,
                                                   @Param("limit") int limit);
}
