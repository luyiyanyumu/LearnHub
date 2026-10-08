package org.dyh.learnhub.mapper;

import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.dyh.learnhub.entity.WikiSourceDependency;

import java.util.List;

@Mapper
public interface WikiSourceDependencyMapper {
    @Select("<script>SELECT * FROM wiki_source_dependency WHERE page_id IN "
            + "<foreach collection='pageIds' item='id' open='(' separator=',' close=')'>#{id}</foreach> "
            + "ORDER BY page_id, source_type, source_id, seq</script>")
    List<WikiSourceDependency> byPages(@Param("pageIds") List<Long> pageIds);

    @Select("SELECT id FROM wiki_page WHERE id = #{pageId} FOR UPDATE")
    Long lockPage(@Param("pageId") Long pageId);

    @Select("SELECT id FROM wiki_page WHERE topic_key = #{topicKey} FOR UPDATE")
    Long lockTopic(@Param("topicKey") String topicKey);

    @Delete("DELETE FROM wiki_source_dependency WHERE page_id = #{pageId}")
    int deleteByPage(@Param("pageId") Long pageId);

    @Insert("<script>INSERT INTO wiki_source_dependency "
            + "(page_id, source_type, source_id, seq, source_title, heading, chunk_text, chunk_hash, "
            + "full_content_hash, source_chars, source_chunk_count, page_md_hash, snapshot_hash, captured_at) VALUES "
            + "<foreach collection='rows' item='r' separator=','>"
            + "(#{r.pageId},#{r.sourceType},#{r.sourceId},#{r.seq},#{r.sourceTitle},#{r.heading},#{r.chunkText},"
            + "#{r.chunkHash},#{r.fullContentHash},#{r.sourceChars},#{r.sourceChunkCount},#{r.pageMdHash},"
            + "#{r.snapshotHash},#{r.capturedAt})</foreach></script>")
    int insertBatch(@Param("rows") List<WikiSourceDependency> rows);
}
