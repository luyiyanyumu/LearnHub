-- learn_hub 库表结构（幂等：可重复执行）

CREATE TABLE IF NOT EXISTS category (
    id          BIGINT AUTO_INCREMENT PRIMARY KEY,
    name        VARCHAR(50)  NOT NULL COMMENT '分类名称',
    parent_id   BIGINT       NOT NULL DEFAULT 0 COMMENT '父分类id，0为根',
    sort_order  INT          NOT NULL DEFAULT 0 COMMENT '排序号，越小越靠前',
    created_at  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY uk_category_name (name)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT ='分类';

CREATE TABLE IF NOT EXISTS tag (
    id          BIGINT AUTO_INCREMENT PRIMARY KEY,
    name        VARCHAR(50) NOT NULL COMMENT '标签名',
    created_at  DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE KEY uk_tag_name (name)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT ='标签';

CREATE TABLE IF NOT EXISTS note (
    id          BIGINT AUTO_INCREMENT PRIMARY KEY,
    title       VARCHAR(200) NOT NULL COMMENT '笔记标题',
    content     LONGTEXT     NULL COMMENT 'Markdown 正文',
    category_id BIGINT       NULL COMMENT '所属分类id',
    created_at  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    KEY idx_note_category (category_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT ='笔记';

CREATE TABLE IF NOT EXISTS note_tag (
    note_id BIGINT NOT NULL,
    tag_id  BIGINT NOT NULL,
    PRIMARY KEY (note_id, tag_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT ='笔记-标签关联';

CREATE TABLE IF NOT EXISTS quick_ref (
    id          BIGINT AUTO_INCREMENT PRIMARY KEY,
    title       VARCHAR(200) NOT NULL COMMENT '速查项标题',
    content     TEXT         NULL COMMENT '速查内容(简短Markdown)',
    category_id BIGINT       NULL COMMENT '所属分类id',
    created_at  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    KEY idx_ref_category (category_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT ='速查卡';

CREATE TABLE IF NOT EXISTS file_info (
    id          BIGINT AUTO_INCREMENT PRIMARY KEY,
    origin_name VARCHAR(255) NOT NULL COMMENT '原始文件名',
    store_name  VARCHAR(128) NOT NULL COMMENT '存储文件名(uuid)',
    size        BIGINT       NOT NULL DEFAULT 0 COMMENT '文件大小(字节)',
    ext         VARCHAR(20)  NULL COMMENT '扩展名',
    category_id BIGINT       NULL COMMENT '所属分类id',
    created_at  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    KEY idx_file_category (category_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT ='资料库文件';

-- 键值对设置表：模型名 / 接口地址 / 温度 / 思考模式 / 润色提示词 / 格式提示词等运行时可改配置（空值行=使用默认）
CREATE TABLE IF NOT EXISTS app_setting (
    setting_key   VARCHAR(64)  NOT NULL PRIMARY KEY COMMENT '设置键',
    setting_value MEDIUMTEXT   NULL COMMENT '设置值(覆盖默认)',
    updated_at    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT ='应用设置';

-- ============================================================================
-- 增量迁移（幂等）
-- 上面全是 CREATE TABLE IF NOT EXISTS —— 对「已存在的表」它什么都不做，
-- 所以后来新增的列 / 索引必须单独写在这里，否则老库永远缺这一列。
-- MySQL 的 DDL 不支持 IF NOT EXISTS，用 information_schema 查一下再动态执行。
-- 每次启动都会跑，命中已存在分支时执行的是无害的 SELECT 1。
-- ============================================================================

-- 1) note.summary：列表页只需摘要，不该把整段 LONGTEXT 正文拉回 Java 再截取。
--    写入时算好存库，查询时只 SELECT 这一列（见 NoteService#toVO）。
SET @col_summary := (SELECT COUNT(*) FROM information_schema.COLUMNS
                     WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'note' AND COLUMN_NAME = 'summary');
SET @ddl := IF(@col_summary = 0,
    'ALTER TABLE note ADD COLUMN summary VARCHAR(255) NULL COMMENT ''纯文本摘要（写入时由正文算出）'' AFTER content',
    'SELECT 1');
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- 2) note_tag 的 (tag_id, note_id) 索引。主键是 (note_id, tag_id)，最左前缀是 note_id，
--    而「按标签筛笔记」（NoteMapper#selectNoteIdsByTag：WHERE tag_id = ?）用不上它，只能全表扫。
SET @idx_tag := (SELECT COUNT(*) FROM information_schema.STATISTICS
                 WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'note_tag' AND INDEX_NAME = 'idx_note_tag_tag');
SET @ddl := IF(@idx_tag = 0,
    'CREATE INDEX idx_note_tag_tag ON note_tag (tag_id, note_id)',
    'SELECT 1');
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;
