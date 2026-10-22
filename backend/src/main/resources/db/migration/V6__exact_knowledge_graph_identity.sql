-- EntityLinker hashes the normalized Java string to determine a node's identity.
-- Accent-insensitive comparison can reject a different identity as an existing node.
-- Keep every stored name, id, relation, vector, and maintenance checkpoint; only change norm comparison.
-- The guard also permits adoption of a database already initialized with the current schema.sql.

SET @learnhub_norm_needs_binary := (SELECT COUNT(*) FROM information_schema.COLUMNS
  WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'kg_node' AND COLUMN_NAME = 'norm'
    AND COLLATION_NAME <> 'utf8mb4_bin');
SET @learnhub_ddl := IF(@learnhub_norm_needs_binary > 0,
  'ALTER TABLE kg_node MODIFY COLUMN norm VARCHAR(120) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL COMMENT ''归一化名：折叠空白/全角转半角/去括注/小写''', 'DO 0');
PREPARE learnhub_stmt FROM @learnhub_ddl;
EXECUTE learnhub_stmt;
DEALLOCATE PREPARE learnhub_stmt;
