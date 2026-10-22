package org.dyh.learnhub.mapper;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;
import org.dyh.learnhub.entity.KnowledgeModelOutbox;

import java.util.List;
import java.util.Map;

@Mapper
public interface KnowledgeModelOutboxMapper {
    @Insert("""
            INSERT INTO knowledge_model_outbox (file_id, title, revision, text_content)
            VALUES (#{fileId}, #{title}, #{revision}, #{textContent})
            ON DUPLICATE KEY UPDATE id = id
            """)
    int insertRevision(KnowledgeModelOutbox row);

    @Select("SELECT * FROM knowledge_model_outbox WHERE file_id=#{fileId} AND revision=#{revision}")
    KnowledgeModelOutbox findRevision(@Param("fileId") Long fileId, @Param("revision") String revision);

    @Select("SELECT * FROM knowledge_model_outbox WHERE id=#{id}")
    KnowledgeModelOutbox findById(Long id);

    @Select("""
            SELECT * FROM knowledge_model_outbox
            WHERE state IN ('pending','retry') AND next_attempt_at <= CURRENT_TIMESTAMP
            ORDER BY id LIMIT 1
            """)
    KnowledgeModelOutbox nextDue();

    @Select("""
            SELECT id, file_id, title, revision, state, attempts, remote_job_id, last_error,
                   next_attempt_at, created_at, updated_at
            FROM knowledge_model_outbox ORDER BY id DESC LIMIT 100
            """)
    List<KnowledgeModelOutbox> recent();

    @Select("SELECT state, COUNT(*) AS total FROM knowledge_model_outbox GROUP BY state")
    List<Map<String, Object>> counts();

    @Update("""
            UPDATE knowledge_model_outbox SET state='submitted', remote_job_id=#{remoteJobId},
                   attempts=attempts+1, last_error=NULL, updated_at=CURRENT_TIMESTAMP WHERE id=#{id}
            """)
    int submitted(@Param("id") Long id, @Param("remoteJobId") String remoteJobId);

    @Update("""
            UPDATE knowledge_model_outbox SET state='retry', attempts=attempts+1, last_error=#{error},
                   next_attempt_at=TIMESTAMPADD(SECOND, #{seconds}, CURRENT_TIMESTAMP),
                   updated_at=CURRENT_TIMESTAMP WHERE id=#{id}
            """)
    int failed(@Param("id") Long id, @Param("error") String error, @Param("seconds") int seconds);

    @Update("""
            UPDATE knowledge_model_outbox SET state='pending', last_error=NULL,
                   next_attempt_at=CURRENT_TIMESTAMP, updated_at=CURRENT_TIMESTAMP WHERE id=#{id}
            """)
    int retry(Long id);
}
