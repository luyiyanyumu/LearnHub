-- Upload revision snapshots survive an offline training service and application restart.
-- No existing file is enrolled and no automatic-training setting is enabled by this migration.
CREATE TABLE IF NOT EXISTS knowledge_model_outbox (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    file_id BIGINT NOT NULL,
    title VARCHAR(255) NOT NULL,
    revision CHAR(64) NOT NULL,
    text_content MEDIUMTEXT NOT NULL,
    state VARCHAR(16) NOT NULL DEFAULT 'pending',
    attempts INT NOT NULL DEFAULT 0,
    remote_job_id VARCHAR(128) NULL,
    last_error VARCHAR(1000) NULL,
    next_attempt_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY uk_knowledge_model_revision (file_id, revision),
    KEY idx_knowledge_model_due (state, next_attempt_at, id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='本地专用模型上传训练发件箱';
