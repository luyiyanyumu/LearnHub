package org.dyh.learnhub.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;
import java.util.Map;

/** 查询期证据核对：按有限来源编号读取当前全文，不读取缓存摘要或向量。 */
@Mapper
public interface GraphEvidenceSourceMapper {

    @Select("<script><choose>"
            + "<when test='type == \"note\"'>"
            + "SELECT n.id, n.title, n.content, c.name AS category FROM note n "
            + "LEFT JOIN category c ON c.id = n.category_id WHERE n.id IN "
            + "<foreach collection='ids' item='id' open='(' separator=',' close=')'>#{id}</foreach>"
            + "</when><when test='type == \"quick_ref\"'>"
            + "SELECT q.id, q.title, q.content, c.name AS category FROM quick_ref q "
            + "LEFT JOIN category c ON c.id = q.category_id WHERE q.id IN "
            + "<foreach collection='ids' item='id' open='(' separator=',' close=')'>#{id}</foreach>"
            + "</when><when test='type == \"file\"'>"
            + "SELECT f.id, f.origin_name AS title, "
            + "CONCAT(IFNULL(f.summary, ''), CASE WHEN IFNULL(f.summary,'') = '' THEN '' ELSE '\\n' END, "
            + "IFNULL(f.text_content, '')) AS content, "
            + "c.name AS category FROM file_info f LEFT JOIN category c ON c.id = f.category_id "
            + "WHERE f.text_status = 'ok' AND f.text_content IS NOT NULL AND f.id IN "
            + "<foreach collection='ids' item='id' open='(' separator=',' close=')'>#{id}</foreach>"
            + "</when><otherwise>SELECT NULL AS id WHERE 1 = 0</otherwise>"
            + "</choose></script>")
    List<Map<String, Object>> currentSources(@Param("type") String type, @Param("ids") List<Long> ids);
}
