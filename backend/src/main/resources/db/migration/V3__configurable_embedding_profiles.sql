-- Keep all existing profiles, source content and vector bytes.
-- Vectors without an embedding-space identity are retained but require rebuilding.
-- Idempotent column additions also support adoption of a schema.sql-created database.

SET @learnhub_column_exists := (SELECT COUNT(*) FROM information_schema.COLUMNS
  WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'model_profile' AND COLUMN_NAME = 'purpose');
SET @learnhub_ddl := IF(@learnhub_column_exists = 0,
  'ALTER TABLE model_profile ADD COLUMN purpose VARCHAR(16) NOT NULL DEFAULT ''chat'' COMMENT ''chat / embedding'' AFTER provider', 'DO 0');
PREPARE learnhub_stmt FROM @learnhub_ddl;
EXECUTE learnhub_stmt;
DEALLOCATE PREPARE learnhub_stmt;

SET @learnhub_column_exists := (SELECT COUNT(*) FROM information_schema.COLUMNS
  WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'kb_chunk' AND COLUMN_NAME = 'embedding_space');
SET @learnhub_ddl := IF(@learnhub_column_exists = 0,
  'ALTER TABLE kb_chunk ADD COLUMN embedding_space VARCHAR(128) NULL COMMENT ''嵌入协议、服务地址与模型的指纹；NULL 为待重建旧索引'' AFTER model', 'DO 0');
PREPARE learnhub_stmt FROM @learnhub_ddl;
EXECUTE learnhub_stmt;
DEALLOCATE PREPARE learnhub_stmt;

SET @learnhub_column_exists := (SELECT COUNT(*) FROM information_schema.COLUMNS
  WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'kb_index_state' AND COLUMN_NAME = 'embedding_space');
SET @learnhub_ddl := IF(@learnhub_column_exists = 0,
  'ALTER TABLE kb_index_state ADD COLUMN embedding_space VARCHAR(128) NULL COMMENT ''索引采用的嵌入空间指纹'' AFTER content_hash', 'DO 0');
PREPARE learnhub_stmt FROM @learnhub_ddl;
EXECUTE learnhub_stmt;
DEALLOCATE PREPARE learnhub_stmt;

SET @learnhub_column_exists := (SELECT COUNT(*) FROM information_schema.COLUMNS
  WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'kg_node' AND COLUMN_NAME = 'embedding_space');
SET @learnhub_ddl := IF(@learnhub_column_exists = 0,
  'ALTER TABLE kg_node ADD COLUMN embedding_space VARCHAR(128) NULL COMMENT ''实体向量采用的嵌入空间指纹'' AFTER embedding', 'DO 0');
PREPARE learnhub_stmt FROM @learnhub_ddl;
EXECUTE learnhub_stmt;
DEALLOCATE PREPARE learnhub_stmt;
