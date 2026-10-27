-- Code fences, display formulas and tables are preserved as atomic knowledge chunks.
-- Such a block can exceed TEXT's 65,535-byte limit even when prose chunks stay bounded.
-- Widen only smaller text columns; preserve all existing bodies, vectors and index state.
-- A current schema.sql installation already uses MEDIUMTEXT, and a wider LONGTEXT is retained.

SET @learnhub_chunk_needs_mediumtext := (SELECT COUNT(*) FROM information_schema.COLUMNS
  WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'kb_chunk' AND COLUMN_NAME = 'chunk_text'
    AND DATA_TYPE IN ('tinytext', 'text'));
SET @learnhub_ddl := IF(@learnhub_chunk_needs_mediumtext > 0,
  'ALTER TABLE kb_chunk MODIFY COLUMN chunk_text MEDIUMTEXT NOT NULL COMMENT ''块正文''', 'DO 0');
PREPARE learnhub_stmt FROM @learnhub_ddl;
EXECUTE learnhub_stmt;
DEALLOCATE PREPARE learnhub_stmt;
