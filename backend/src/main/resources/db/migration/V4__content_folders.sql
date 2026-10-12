-- Logical folders are independent from knowledge categories and physical upload paths.
-- Existing content remains unfiled; no document, tag, vector, or category is changed.
-- Idempotent additions permit adoption of databases initialized with schema.sql.

CREATE TABLE IF NOT EXISTS content_folder (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    kind VARCHAR(16) NOT NULL COMMENT 'note / file',
    name VARCHAR(100) NOT NULL COMMENT '收纳文件夹名称',
    parent_id BIGINT NOT NULL DEFAULT 0 COMMENT '父文件夹id，0为根',
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY uk_content_folder_sibling (kind, parent_id, name),
    KEY idx_content_folder_parent (kind, parent_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='笔记与资料收纳目录';

SET @learnhub_column_exists := (SELECT COUNT(*) FROM information_schema.COLUMNS
  WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'note' AND COLUMN_NAME = 'folder_id');
SET @learnhub_ddl := IF(@learnhub_column_exists = 0,
  'ALTER TABLE note ADD COLUMN folder_id BIGINT NULL COMMENT ''收纳文件夹id'' AFTER category_id', 'DO 0');
PREPARE learnhub_stmt FROM @learnhub_ddl;
EXECUTE learnhub_stmt;
DEALLOCATE PREPARE learnhub_stmt;

SET @learnhub_column_exists := (SELECT COUNT(*) FROM information_schema.COLUMNS
  WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'file_info' AND COLUMN_NAME = 'folder_id');
SET @learnhub_ddl := IF(@learnhub_column_exists = 0,
  'ALTER TABLE file_info ADD COLUMN folder_id BIGINT NULL COMMENT ''收纳文件夹id'' AFTER category_id', 'DO 0');
PREPARE learnhub_stmt FROM @learnhub_ddl;
EXECUTE learnhub_stmt;
DEALLOCATE PREPARE learnhub_stmt;

SET @learnhub_index_exists := (SELECT COUNT(*) FROM information_schema.STATISTICS
  WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'note' AND INDEX_NAME = 'idx_note_folder');
SET @learnhub_ddl := IF(@learnhub_index_exists = 0,
  'CREATE INDEX idx_note_folder ON note (folder_id, updated_at)', 'DO 0');
PREPARE learnhub_stmt FROM @learnhub_ddl;
EXECUTE learnhub_stmt;
DEALLOCATE PREPARE learnhub_stmt;

SET @learnhub_index_exists := (SELECT COUNT(*) FROM information_schema.STATISTICS
  WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'file_info' AND INDEX_NAME = 'idx_file_folder');
SET @learnhub_ddl := IF(@learnhub_index_exists = 0,
  'CREATE INDEX idx_file_folder ON file_info (folder_id, created_at)', 'DO 0');
PREPARE learnhub_stmt FROM @learnhub_ddl;
EXECUTE learnhub_stmt;
DEALLOCATE PREPARE learnhub_stmt;
