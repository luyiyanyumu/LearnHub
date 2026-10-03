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
    id           BIGINT AUTO_INCREMENT PRIMARY KEY,
    origin_name  VARCHAR(255) NOT NULL COMMENT '原始文件名',
    store_name   VARCHAR(128) NOT NULL COMMENT '存储文件名(uuid)',
    size         BIGINT       NOT NULL DEFAULT 0 COMMENT '文件大小(字节)',
    ext          VARCHAR(20)  NULL COMMENT '扩展名',
    category_id  BIGINT       NULL COMMENT '所属分类id',
    -- 文本层：资料要"融进知识库"（检索 / wiki / 图谱 / 智能体召回）就必须先有正文。
    -- 抽不出正文的类型（图片、压缩包）靠 summary 手填说明参与检索，仍然是有用的知识入口。
    summary      VARCHAR(500) NULL COMMENT '用户手填说明（抽不出正文的文件靠它参与检索）',
    text_content MEDIUMTEXT   NULL COMMENT '抽取出的正文（文本/PDF/Office）',
    text_status  VARCHAR(16)  NOT NULL DEFAULT 'pending' COMMENT 'pending/ok/empty/unsupported/skipped/failed',
    text_chars   INT          NOT NULL DEFAULT 0 COMMENT '正文字数（列表展示，避免查 MEDIUMTEXT）',
    text_error   VARCHAR(255) NULL COMMENT '抽取失败/跳过的原因',
    extracted_at DATETIME     NULL COMMENT '抽取时间',
    created_at   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    KEY idx_file_category (category_id),
    KEY idx_file_cat_created (category_id, created_at)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT ='资料库文件';

-- 键值对设置表：模型名 / 接口地址 / 温度 / 思考模式 / 对话提示词等运行时可改配置（空值行=使用默认）
-- 注：润色、整理格式的提示词已改为技能文件 skills/<id>/SKILL.md，不再存这张表（见 SkillService）
CREATE TABLE IF NOT EXISTS app_setting (
    setting_key   VARCHAR(64)  NOT NULL PRIMARY KEY COMMENT '设置键',
    setting_value MEDIUMTEXT   NULL COMMENT '设置值(覆盖默认)',
    updated_at    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT ='应用设置';

-- ============================================================================
-- 智能体会话与事件日志（2026-09 新增）
-- 会话 = 一条 append-only 的事件流；界面和模型看到的都是它的「投影」。
-- 为什么必须落库：原来对话历史只在前端内存里（刷新即丢），后端完全无状态 ——
-- 结果是模型记不住上一轮、用户刷新后看到空面板而模型却"记得"，两边不一致。
-- ============================================================================

CREATE TABLE IF NOT EXISTS agent_session (
    id          VARCHAR(36)  NOT NULL PRIMARY KEY COMMENT '会话id(UUID)',
    title       VARCHAR(120) NULL COMMENT '标题：取首条用户消息的前若干字',
    note_id     BIGINT       NULL COMMENT '发起时所在的笔记id（可为空）',
    created_at  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    KEY idx_agent_session_updated (updated_at)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT ='智能体会话';

-- role: user / assistant / tool / summary
-- 顺序用自增 id 表达（同一会话内单调），不再单设 seq 列 —— 少一次 MAX() 查询，也没有竞态。
-- 注意：role=tool 的事件**只用于审计与界面回看，不参与发给模型的上下文投影**；
-- 一篇长笔记的工具结果就能吃掉几千 token，回放收益远小于成本（见 AgentSessionService#projection）。
CREATE TABLE IF NOT EXISTS agent_event (
    id            BIGINT AUTO_INCREMENT PRIMARY KEY,
    session_id    VARCHAR(36) NOT NULL COMMENT '所属会话id',
    role          VARCHAR(16) NOT NULL COMMENT 'user/assistant/tool/summary',
    content       MEDIUMTEXT  NULL COMMENT '文本内容（assistant 存最终回复，summary 存压缩摘要）',
    tool_name     VARCHAR(64) NULL COMMENT '工具名（role=tool 时）',
    tool_call_id  VARCHAR(64) NULL COMMENT '对应模型返回的 tool_call id',
    tokens        INT         NULL COMMENT '该轮 token 用量（含思考；接口返回 usage 时记录）',
    finish_reason VARCHAR(16) NULL COMMENT 'stop/length（被 max_tokens 截断）/tool_calls；用于事后查哪条回答被截断了',
    created_at    DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    KEY idx_agent_event_session (session_id, id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT ='智能体会话事件';

-- 待确认的写操作（审批队列）。
-- 为什么需要：模型很容易把"记住这件事"当成写指令，实测中它据此直接建了笔记、还顺手新建了分类。
-- 现在写工具不再直接执行，而是先挂到这里，等用户在界面上点确认才落库。
CREATE TABLE IF NOT EXISTS agent_pending_action (
    id          BIGINT AUTO_INCREMENT PRIMARY KEY,
    session_id  VARCHAR(36) NOT NULL COMMENT '所属会话id',
    tool_name   VARCHAR(64) NOT NULL COMMENT '要执行的工具名',
    args_json   MEDIUMTEXT  NULL COMMENT '原样保存的调用参数（确认时按此执行，不受后续对话影响）',
    summary     VARCHAR(255) NULL COMMENT '给用户看的一行摘要，如「新建笔记《xxx》」',
    status      VARCHAR(16) NOT NULL DEFAULT 'pending' COMMENT 'pending/approved/rejected',
    created_at  DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    resolved_at DATETIME    NULL COMMENT '确认或取消的时间',
    KEY idx_agent_action_session (session_id, status)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT ='智能体待确认写操作';

-- ============================================================================
-- LLM Wiki 与知识图谱（2026-09 新增）
-- ============================================================================

-- 每个分类（或标签）一份 wiki 页：由模型把该主题下的笔记/速查卡整理成结构化长文。
-- 为什么落库：生成一次要几秒到几十秒、还要花 token，刷新页面不该重算；
-- source_hash 存「生成时素材的指纹」，与当前素材不一致即说明过期（界面显示「待更新」）。
CREATE TABLE IF NOT EXISTS wiki_page (
    id           BIGINT AUTO_INCREMENT PRIMARY KEY,
    topic_type   VARCHAR(16)  NOT NULL DEFAULT 'category' COMMENT '主题类型：category/tag',
    topic_id     BIGINT       NULL COMMENT '分类或标签 id',
    topic_key    VARCHAR(64)  NOT NULL COMMENT '主题唯一键：cat-3 / tag-2',
    title        VARCHAR(200) NOT NULL COMMENT '主题标题（取分类名）',
    content_md   MEDIUMTEXT   NULL COMMENT '模型生成的结构化 wiki 正文(Markdown)',
    source_hash  VARCHAR(64)  NULL COMMENT '生成时素材的指纹，用于判断过期',
    item_count   INT          NOT NULL DEFAULT 0 COMMENT '生成时的条目数',
    model        VARCHAR(64)  NULL COMMENT '生成用的模型名，便于排查',
    generated_at DATETIME     NULL COMMENT '最近一次生成时间',
    created_at   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY uk_wiki_topic (topic_key)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT ='LLM wiki 页（按主题）';

-- 知识图谱的语义关联边。
-- 结构关系（笔记→分类、笔记→标签）是实时查出来的、不落库；这张表只存模型推断的语义关联。
-- 单独一张表的原因：模型每次重建给出的关联都会变，需要能整批替换（重建时按 origin 清旧行）。
CREATE TABLE IF NOT EXISTS kg_edge (
    id          BIGINT AUTO_INCREMENT PRIMARY KEY,
    source_type VARCHAR(16) NOT NULL COMMENT 'note/ref',
    source_id   BIGINT      NOT NULL,
    target_type VARCHAR(16) NOT NULL COMMENT 'note/ref',
    target_id   BIGINT      NOT NULL,
    relation    VARCHAR(32) NOT NULL DEFAULT 'related' COMMENT 'related（相关）/ contrast（易混）/ prerequisite（前置）',
    reason      VARCHAR(255) NULL COMMENT '模型给出的关联理由，悬浮展示',
    weight      DOUBLE      NOT NULL DEFAULT 1 COMMENT '关联强度 0~1',
    origin      VARCHAR(16) NOT NULL DEFAULT 'llm' COMMENT 'llm/user',
    created_at  DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE KEY uk_kg_edge (source_type, source_id, target_type, target_id, relation),
    KEY idx_kg_source (source_type, source_id),
    KEY idx_kg_target (target_type, target_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT ='知识图谱语义关联';

-- ============================================================================
-- 语义检索索引（2026-09 新增）
-- 起因：词面检索（LIKE / n-gram）存在语言鸿沟 —— 用户问「撤销暂存区」，资料里写的是 git reset，
-- 结果 0 条。要让智能体"问什么都知道"，必须有语义召回。
-- 为什么落 MySQL 而不是引向量库：个人库只有几百块（18 万字 ÷ 800 ≈ 230 块），
-- Java 里暴力余弦就是毫秒级；存 BLOB（1024 维 float32 = 4KB/块）总占用 1MB 量级。
-- ============================================================================
CREATE TABLE IF NOT EXISTS kb_chunk (
    id          BIGINT AUTO_INCREMENT PRIMARY KEY,
    source_type VARCHAR(16)  NOT NULL COMMENT 'note / quick_ref / file',
    source_id   BIGINT       NOT NULL,
    seq         INT          NOT NULL COMMENT '该来源的第几块（从 0 开始）',
    title       VARCHAR(255) NULL COMMENT '来源标题（冗余，检索展示用）',
    heading     VARCHAR(255) NULL COMMENT '该块所属小节标题（上下文化嵌入用：检索时与标题一起拼进嵌入输入）',
    category    VARCHAR(255) NULL COMMENT '来源分类名（冗余）',
    chunk_text  TEXT         NOT NULL COMMENT '块正文',
    char_len    INT          NOT NULL DEFAULT 0,
    vec         BLOB         NULL COMMENT '向量：dim × float32 小端',
    dim         INT          NOT NULL DEFAULT 0,
    model       VARCHAR(64)  NULL COMMENT '嵌入模型名（换模型需重建索引）',
    updated_at  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    created_at  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE KEY uk_kb_chunk (source_type, source_id, seq),
    KEY idx_kb_source (source_type, source_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT ='语义检索分块索引';

-- ============================================================================
-- 给**已经存在**的库补列（自愈式，幂等）
--
-- 为什么必须有这一段：sql.init=always 只会执行 `CREATE TABLE IF NOT EXISTS`，
-- 表一旦建过就再也不会被改动 —— 于是"实体/Mapper 加了字段、建表脚本也加了列"，
-- 但**老库不会拿到这一列**，运行时直接报 `Unknown column 'xxx' in 'field list'`。
-- 实测踩过：新部署的机器语义索引重建整体失败，job 报
-- `Unknown column 'heading' in 'field list'`（kb_chunk 少了 2026-09 加的 heading 列）。
--
-- 写法与文件末尾处理 rag_eval.note 宽度的那段一致：先查 information_schema，
-- 需要才拼 DDL 执行，已经是对的就执行 SELECT 1（幂等，反复启动无副作用）。
-- ============================================================================
SET @c := (SELECT COUNT(*) FROM information_schema.COLUMNS
           WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'kb_chunk' AND COLUMN_NAME = 'heading');
SET @ddl := IF(@c = 0,
    'ALTER TABLE kb_chunk ADD COLUMN heading VARCHAR(255) NULL COMMENT ''该块所属小节标题（上下文化嵌入用：检索时与标题一起拼进嵌入输入）'' AFTER title',
    'SELECT 1');
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;

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

-- 3) file_info 的文本层（2026-09 新增）：资料库融入知识库的前提。
--    检索 / 主题 wiki / 知识图谱 / 智能体召回全都建立在文本上，所以老库必须补这几列。
SET @c := (SELECT COUNT(*) FROM information_schema.COLUMNS
           WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'file_info' AND COLUMN_NAME = 'summary');
SET @ddl := IF(@c = 0, 'ALTER TABLE file_info ADD COLUMN summary VARCHAR(500) NULL COMMENT ''用户手填说明'' AFTER category_id', 'SELECT 1');
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @c := (SELECT COUNT(*) FROM information_schema.COLUMNS
           WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'file_info' AND COLUMN_NAME = 'text_content');
SET @ddl := IF(@c = 0, 'ALTER TABLE file_info ADD COLUMN text_content MEDIUMTEXT NULL COMMENT ''抽取出的正文'' AFTER summary', 'SELECT 1');
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @c := (SELECT COUNT(*) FROM information_schema.COLUMNS
           WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'file_info' AND COLUMN_NAME = 'text_status');
SET @ddl := IF(@c = 0, 'ALTER TABLE file_info ADD COLUMN text_status VARCHAR(16) NOT NULL DEFAULT ''pending'' COMMENT ''penOK/empty/unsupported/skipped/failed'' AFTER text_content', 'SELECT 1');
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @c := (SELECT COUNT(*) FROM information_schema.COLUMNS
           WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'file_info' AND COLUMN_NAME = 'text_chars');
SET @ddl := IF(@c = 0, 'ALTER TABLE file_info ADD COLUMN text_chars INT NOT NULL DEFAULT 0 COMMENT ''正文字数'' AFTER text_status', 'SELECT 1');
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @c := (SELECT COUNT(*) FROM information_schema.COLUMNS
           WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'file_info' AND COLUMN_NAME = 'text_error');
SET @ddl := IF(@c = 0, 'ALTER TABLE file_info ADD COLUMN text_error VARCHAR(255) NULL COMMENT ''抽取失败/跳过原因'' AFTER text_chars', 'SELECT 1');
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @c := (SELECT COUNT(*) FROM information_schema.COLUMNS
           WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'file_info' AND COLUMN_NAME = 'extracted_at');
SET @ddl := IF(@c = 0, 'ALTER TABLE file_info ADD COLUMN extracted_at DATETIME NULL COMMENT ''抽取时间'' AFTER text_error', 'SELECT 1');
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- 4) 图谱版本指纹要覆盖资料库：上传/删除资料会改变图的内容，版本必须跟着变，
--    否则前端轮询不会刷新，wiki 的过期判断也会漏掉资料这一路。
SET @idx_file_catc := (SELECT COUNT(*) FROM information_schema.STATISTICS
                       WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'file_info' AND INDEX_NAME = 'idx_file_cat_created');
SET @ddl := IF(@idx_file_catc = 0, 'CREATE INDEX idx_file_cat_created ON file_info (category_id, created_at)', 'SELECT 1');
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- 5) wiki_page 的质量校验结果（2026-09 新增）。
--    本地小模型生成长文时会有可探测的瑕疵（引用不存在的素材、标记写坏、结构缺失），
--    与其让用户自己发现，不如生成后自动校验一次并把结论落库、在界面上标出来。
SET @c := (SELECT COUNT(*) FROM information_schema.COLUMNS
           WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'wiki_page' AND COLUMN_NAME = 'quality');
SET @ddl := IF(@c = 0, 'ALTER TABLE wiki_page ADD COLUMN quality VARCHAR(16) NULL COMMENT ''ok/warn/failed'' AFTER model', 'SELECT 1');
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @c := (SELECT COUNT(*) FROM information_schema.COLUMNS
           WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'wiki_page' AND COLUMN_NAME = 'quality_note');
SET @ddl := IF(@c = 0, 'ALTER TABLE wiki_page ADD COLUMN quality_note VARCHAR(255) NULL COMMENT ''校验发现的问题（多条用；分隔）'' AFTER quality', 'SELECT 1');
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @c := (SELECT COUNT(*) FROM information_schema.COLUMNS
           WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'wiki_page' AND COLUMN_NAME = 'target_id');
SET @ddl := IF(@c = 0, 'ALTER TABLE wiki_page ADD COLUMN target_id VARCHAR(16) NULL COMMENT ''用哪个模型目标生成的：main/local'' AFTER quality_note', 'SELECT 1');
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- =====================================================================
-- 6) 概念层知识图谱（2026-09 新增）
--    背景：原来的 kg_edge 只连"文档↔文档"（笔记↔速查卡），那是**相似度图**，不是知识图谱。
--    这里补上真正的图谱存储：节点表（实体，带规范名与别名）+ 三元组表（头实体，关系，尾实体）。
--    选型：刻意**没有**上 Neo4j / RDF 库 —— 这个库的规模是几十个实体、几十条三元组，
--    MySQL 的边表 + 应用内 BFS 足够；多一个数据库只增加运维成本。论证见 docs/kg-design.md。
-- =====================================================================
CREATE TABLE IF NOT EXISTS kg_node (
  id VARCHAR(24) NOT NULL COMMENT '规范实体 id：e-<sha256(归一化名) 前 10 位>',
  name VARCHAR(120) NOT NULL COMMENT '显示名（首次出现时的写法）',
  norm VARCHAR(120) NOT NULL COMMENT '归一化名：折叠空白/全角转半角/去括注/小写',
  type VARCHAR(16) NOT NULL DEFAULT 'concept' COMMENT 'concept/tool/language/framework/command/term',
  aliases VARCHAR(500) NULL COMMENT '别名，用 | 分隔（含括注里的写法）',
  brief VARCHAR(500) NULL COMMENT '一句话说明：它是什么',
  wiki_key VARCHAR(64) NULL COMMENT '对应 wiki 实体页的 topic_key',
  source_count INT NOT NULL DEFAULT 0 COMMENT '提到它的素材条数',
  embedding BLOB NULL COMMENT 'bge-m3 向量（float32 小端），实体级相似度/消歧用',
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  UNIQUE KEY uk_kg_node_norm (norm),
  KEY idx_kg_node_name (name)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='知识图谱节点（实体/概念）';

CREATE TABLE IF NOT EXISTS kg_relation (
  id BIGINT NOT NULL AUTO_INCREMENT,
  head_id VARCHAR(24) NOT NULL COMMENT '头实体 id',
  relation VARCHAR(32) NOT NULL COMMENT '本体里的关系 id（封闭词表）',
  tail_id VARCHAR(24) NOT NULL COMMENT '尾实体 id',
  evidence VARCHAR(500) NULL COMMENT '证据句（原文摘录）',
  sources VARCHAR(255) NULL COMMENT '来源，形如 笔记#5|资料#2',
  weight DOUBLE NOT NULL DEFAULT 1 COMMENT '置信度；推导出来的按规则衰减',
  origin VARCHAR(16) NOT NULL DEFAULT 'llm' COMMENT 'llm=模型抽取 / derived=规则推导',
  derived_from VARCHAR(255) NULL COMMENT '推导依据（两条边 id），便于溯源',
  model VARCHAR(64) NULL COMMENT '抽取用的模型',
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  UNIQUE KEY uk_kg_triple (head_id, relation, tail_id),
  KEY idx_kg_rel_head (head_id, relation),
  KEY idx_kg_rel_tail (tail_id, relation)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='知识图谱三元组（头实体-关系-尾实体）';
-- =====================================================================
-- 7) 检索评测集 + 索引状态（2026-09 新增）
--    动机：检索的改动（切块带上下文、融合打分、重排）如果没有指标，
--    就只能靠"感觉变好了"。这张表让每次改动都有 recall@k / MRR 可比。
-- =====================================================================
CREATE TABLE IF NOT EXISTS rag_eval (
  id BIGINT NOT NULL AUTO_INCREMENT,
  question VARCHAR(500) NOT NULL COMMENT '问题（用真实口语问法，不要照抄标题）',
  expect_refs VARCHAR(500) NOT NULL COMMENT '应命中的来源，形如 note:5|file:2（| 分隔，命中任一即算召回）',
  expect_words VARCHAR(500) NULL COMMENT '答案里应出现的关键词，| 分隔（用于人工核对，不参与自动打分）',
  note VARCHAR(255) NULL COMMENT '这条用例想验证什么（例如"换一种说法的语义鸿沟"）',
  enabled TINYINT NOT NULL DEFAULT 1,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  UNIQUE KEY uk_rag_eval_q (question)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='检索评测集';

CREATE TABLE IF NOT EXISTS rag_eval_run (
  id BIGINT NOT NULL AUTO_INCREMENT,
  label VARCHAR(64) NOT NULL COMMENT '本次运行的标签（例如"改前基线" / "加融合打分"）',
  cases INT NOT NULL DEFAULT 0,
  top_k INT NOT NULL DEFAULT 5,
  recall_at_k DOUBLE NOT NULL DEFAULT 0 COMMENT 'top-k 内命中的用例占比',
  mrr DOUBLE NOT NULL DEFAULT 0 COMMENT '首个命中位置倒数的均值',
  keyword_recall DOUBLE NOT NULL DEFAULT 0 COMMENT '仅词面一路的 recall@k（对照组）',
  vector_recall DOUBLE NOT NULL DEFAULT 0 COMMENT '仅语义一路的 recall@k（对照组）',
  detail MEDIUMTEXT NULL COMMENT '逐条结果 JSON（哪个用例没召回、排在第几）',
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  KEY idx_rag_run_label (label, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='检索评测历史（用来对比改动前后）';

-- =====================================================================
-- 答案级评测历史（2026-10-02 新增）
--   检索评测（rag_eval_run）只看"期望来源有没有被召回"，它回答不了：
--   答得对不对？引用有没有依据？库里没有的时候有没有老实说不知道？花了多少时间与 token？
--   这张表补上这一层。语料指纹 / 模型 / prompt 版本必须一起存 ——
--   否则两次分数不可比（换了模型或改了语料，分数变化说明不了任何事）。
-- =====================================================================
CREATE TABLE IF NOT EXISTS rag_eval_answer_run (
  id BIGINT NOT NULL AUTO_INCREMENT,
  label VARCHAR(64) NOT NULL COMMENT '本次运行的标签',
  cases INT NOT NULL DEFAULT 0 COMMENT '实际评测了多少条用例',
  top_k INT NOT NULL DEFAULT 5,
  mode VARCHAR(16) NOT NULL DEFAULT 'fused' COMMENT '检索模式：fused/keyword/vector',
  corpus_hash VARCHAR(64) NULL COMMENT '语料指纹（各来源内容哈希聚合，语料变了分数不可比）',
  model VARCHAR(120) NULL COMMENT '生成答案所用的模型名',
  prompt_version VARCHAR(32) NULL COMMENT '生成与评分 prompt 的版本号',
  wiki_inject TINYINT NOT NULL DEFAULT 0 COMMENT '本轮是否注入 wiki 块（报告要求的四臂对照之一）',
  kg_inject TINYINT NOT NULL DEFAULT 0 COMMENT '本轮是否注入概念图谱块（四臂对照之一）',
  answer_accuracy DOUBLE NOT NULL DEFAULT 0 COMMENT '答案正确性：expect_words 覆盖率（只统计有标注的题）',
  citation_support DOUBLE NOT NULL DEFAULT 0 COMMENT '引用支持：答案被证据支撑的占比（grounding）',
  citation_coverage DOUBLE NOT NULL DEFAULT 0 COMMENT '引用完整性：必要来源**全部**召回的占比（ALL 而非 ANY）',
  no_answer_score DOUBLE NOT NULL DEFAULT 0 COMMENT '无答案处理：缺口题正确拒答率（该说不知道时说了的比例）；无缺口题时为 0 表示未测',
  false_refusals INT NOT NULL DEFAULT 0 COMMENT '有材料却拒答的条数（诊断项，与 no_answer_score 分开记）',
  elapsed_ms BIGINT NOT NULL DEFAULT 0 COMMENT '整轮墙钟耗时',
  avg_latency_ms INT NOT NULL DEFAULT 0 COMMENT '单题平均生成耗时',
  prompt_tokens BIGINT NOT NULL DEFAULT 0,
  completion_tokens BIGINT NOT NULL DEFAULT 0,
  total_tokens BIGINT NOT NULL DEFAULT 0,
  est_cost DOUBLE NOT NULL DEFAULT 0 COMMENT '估算成本；未配置单价时为 0（此时只信 token 数）',
  detail MEDIUMTEXT NULL COMMENT '逐条结果 JSON（答案、引用、各项判定与耗时）',
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  KEY idx_rag_answer_label (label, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='答案级评测历史';

-- 索引状态：记录每个来源已索引内容的指纹，用于**增量索引**
-- （改一条笔记只重编那一条，而不是全量重建 300 多块）
CREATE TABLE IF NOT EXISTS kb_index_state (
  id VARCHAR(48) NOT NULL COMMENT '来源键：note:5 / quick_ref:3 / file:2',
  source_type VARCHAR(16) NOT NULL,
  source_id BIGINT NOT NULL,
  content_hash VARCHAR(64) NOT NULL COMMENT '标题+正文的 sha256（变了才需要重编）',
  chunks INT NOT NULL DEFAULT 0,
  indexed_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  KEY idx_kb_index_type (source_type)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='向量索引的按来源指纹（增量索引用）';
-- =====================================================================
-- 8) 模型配置档案（2026-09 新增）
--    背景：原来模型配置写死成"主模型 + 一个本地/自建目标"（ai.model / ai.wiki_*），
--    只能二选一。实际会同时配好几个（DeepSeek 云端、Kimi、本地 Ollama、LM Studio…），
--    而且不同任务应该用不同档案。这里改成**可无限新增的档案列表**，
--    每个任务（对话/wiki/实体/影响/自检/图谱/改写/抽取/重排/核对）各自指向一个档案。
-- =====================================================================
CREATE TABLE IF NOT EXISTS model_profile (
  id VARCHAR(36) NOT NULL COMMENT '档案 id',
  name VARCHAR(64) NOT NULL COMMENT '显示名，如 DeepSeek 云端 / 本地 Ollama',
  provider VARCHAR(32) NOT NULL DEFAULT 'custom' COMMENT 'deepseek/openai/kimi/ark/ollama/lmstudio/vllm/custom',
  base_url VARCHAR(255) NOT NULL COMMENT 'OpenAI 兼容基址',
  api_key VARCHAR(255) NULL COMMENT '密钥（**接口永不回传**，只回传是否已配置与尾号）',
  model VARCHAR(120) NOT NULL COMMENT '模型名',
  note VARCHAR(255) NULL COMMENT '备注：这档准备用来干什么',
  sort_order INT NOT NULL DEFAULT 0,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  KEY idx_model_profile_order (sort_order, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='模型配置档案（可新增多个，任务各自指向一个）';

-- 会话记住自己用的档案（"新开不同会话"要能各自选模型）
SET @c := (SELECT COUNT(*) FROM information_schema.COLUMNS
           WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'agent_session' AND COLUMN_NAME = 'model_profile_id');
SET @ddl := IF(@c = 0, 'ALTER TABLE agent_session ADD COLUMN model_profile_id VARCHAR(36) NULL COMMENT ''本会话使用的模型档案 id（空=跟随分工表）'' AFTER title', 'SELECT 1');
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;
-- =====================================================================
-- 9) 生成参数**跟着档案走**（2026-09 新增）
--    背景：思考模式/最大输出/温度原来是全局单值（ai.max_tokens 等）。有多个档案之后这是错的 ——
--    本地 qwen3:8b 与云端 deepseek-flash 想要的最优参数并不一样（输出上限、是否思考、温度能否生效）。
--    这四列留空 = 跟随全局默认，所以老行为不会变。
-- =====================================================================
SET @c := (SELECT COUNT(*) FROM information_schema.COLUMNS
           WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'model_profile' AND COLUMN_NAME = 'max_tokens');
SET @ddl := IF(@c = 0, 'ALTER TABLE model_profile ADD COLUMN max_tokens INT NULL COMMENT ''输出上限；空=跟随全局'' AFTER note', 'SELECT 1');
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- =====================================================================
-- 10) agent_event 记录 finish_reason（2026-09-29 新增）
--     背景：回答被 max_tokens 截断时只在后端日志里 warn 一行，日志一滚就无从考证 ——
--     实测被用户问过一次"是不是达到最大字数了"，只能靠事后算 token 数去倒推。
--     落库之后一条 SQL 就能列出来：
--       SELECT id, tokens, finish_reason FROM agent_event WHERE finish_reason = 'length';
-- =====================================================================
SET @c := (SELECT COUNT(*) FROM information_schema.COLUMNS
           WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'agent_event' AND COLUMN_NAME = 'finish_reason');
SET @ddl := IF(@c = 0, 'ALTER TABLE agent_event ADD COLUMN finish_reason VARCHAR(16) NULL COMMENT ''stop/length（被 max_tokens 截断）/tool_calls'' AFTER tokens', 'SELECT 1');
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @c := (SELECT COUNT(*) FROM information_schema.COLUMNS
           WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'model_profile' AND COLUMN_NAME = 'temperature');
SET @ddl := IF(@c = 0, 'ALTER TABLE model_profile ADD COLUMN temperature DECIMAL(3,2) NULL COMMENT ''温度；空=跟随全局'' AFTER max_tokens', 'SELECT 1');
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @c := (SELECT COUNT(*) FROM information_schema.COLUMNS
           WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'model_profile' AND COLUMN_NAME = 'thinking');
SET @ddl := IF(@c = 0, 'ALTER TABLE model_profile ADD COLUMN thinking VARCHAR(16) NULL COMMENT ''auto/enabled/disabled；空=跟随全局'' AFTER temperature', 'SELECT 1');
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @c := (SELECT COUNT(*) FROM information_schema.COLUMNS
           WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'model_profile' AND COLUMN_NAME = 'reasoning_effort');
SET @ddl := IF(@c = 0, 'ALTER TABLE model_profile ADD COLUMN reasoning_effort VARCHAR(24) NULL COMMENT ''思考强度；空=跟随全局'' AFTER thinking', 'SELECT 1');
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;
-- =====================================================================
-- 10) 代码库（2026-09 新增）
--     为什么单开一块、而不是并进知识库的 kb_chunk：
--     代码的信号密度天生低（样板/依赖/测试），整仓进向量库会让 top-k 被无关代码占满，
--     把"我自己的笔记"挤出去（知识检索现在 85 条用例 recall@5=0.976，是在 373 块上测的）。
--     所以代码独立存放 + 独立索引；智能体需要时通过 search_code 工具**显式**去查。
--
--     三个索引层次（递增成本）：
--       ① code_symbol：抽出的类/函数/方法名 + 行号 → 精确定位（"在哪定义"）
--       ② symbols 列：全部标识符 → 关键词/前缀模糊（零模型成本，确定性）
--       ③ code_chunk：可选语义嵌入（需要本地嵌入模型；默认关）
-- =====================================================================

-- 项目容器：可以只挂链接（论文附带的仓库/演示站就是这么用的）
CREATE TABLE IF NOT EXISTS code_repo (
  id BIGINT NOT NULL AUTO_INCREMENT,
  name VARCHAR(120) NOT NULL COMMENT '项目名',
  url VARCHAR(500) NULL COMMENT '仓库地址',
  demo_url VARCHAR(500) NULL COMMENT '演示站地址',
  license VARCHAR(64) NULL COMMENT '许可证（引用别人代码必须留）',
  note VARCHAR(500) NULL COMMENT '一句话说明',
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  KEY idx_code_repo_name (name)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='代码库：项目/仓库容器';

-- 代码片段：真正存代码的实体。explain 才是"知识"（为什么这么写、怎么用、坑在哪）
CREATE TABLE IF NOT EXISTS code_snippet (
  id BIGINT NOT NULL AUTO_INCREMENT,
  repo_id BIGINT NULL COMMENT '所属项目（可空）',
  title VARCHAR(200) NOT NULL,
  lang VARCHAR(32) NULL COMMENT 'java/python/js/ts/go/...',
  code MEDIUMTEXT NOT NULL COMMENT '代码原文',
  explain_text TEXT NULL COMMENT '说明：为什么这么写 / 怎么用 / 踩过什么坑',
  file_path VARCHAR(300) NULL COMMENT '原文件路径（用于定位）',
  source_url VARCHAR(500) NULL COMMENT '出处链接（仓库文件页/文档）',
  symbols TEXT NULL COMMENT '抽取出的标识符（空格分隔），关键词检索用',
  line_count INT NULL COMMENT '行数',
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  KEY idx_code_snippet_repo (repo_id),
  KEY idx_code_snippet_lang (lang)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='代码片段';

-- 符号索引：精确定位（搜 splitWithHeadings 要能直接给出文件与行号）
CREATE TABLE IF NOT EXISTS code_symbol (
  id BIGINT NOT NULL AUTO_INCREMENT,
  snippet_id BIGINT NOT NULL,
  kind VARCHAR(16) NOT NULL COMMENT 'class/interface/enum/function/method/record/const/file',
  name VARCHAR(160) NOT NULL,
  line INT NULL COMMENT '定义所在行',
  PRIMARY KEY (id),
  KEY idx_code_symbol_name (name),
  KEY idx_code_symbol_snippet (snippet_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='代码符号索引';

-- 语义块（可选）：只嵌"标题+说明+代码前若干行"，独立于 kb_chunk
CREATE TABLE IF NOT EXISTS code_chunk (
  id BIGINT NOT NULL AUTO_INCREMENT,
  snippet_id BIGINT NOT NULL,
  idx INT NOT NULL COMMENT '片段内序号（长代码才需要）',
  heading VARCHAR(200) NULL,
  chunk_text MEDIUMTEXT NOT NULL,
  embedding MEDIUMTEXT NULL COMMENT 'JSON 数组（1024 维）',
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  KEY idx_code_chunk_snippet (snippet_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='代码库语义块（可选索引）';
-- =====================================================================
-- 11) 资料阅读位置（2026-09-23 新增）
--     一直读到第 37 页、关掉再打开又从头开始，是长文档阅读最常见的摩擦。
--     这里只存三个数：页码 / 缩放 / 阅读模式 —— 刻意不存滚动像素（窗口大小一变就没意义）。
-- =====================================================================
SET @c := (SELECT COUNT(*) FROM information_schema.COLUMNS
           WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'file_info' AND COLUMN_NAME = 'read_page');
SET @ddl := IF(@c = 0, 'ALTER TABLE file_info ADD COLUMN read_page INT NULL COMMENT ''上次读到的页码'' AFTER text_error', 'SELECT 1');
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @c := (SELECT COUNT(*) FROM information_schema.COLUMNS
           WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'file_info' AND COLUMN_NAME = 'read_scale');
SET @ddl := IF(@c = 0, 'ALTER TABLE file_info ADD COLUMN read_scale DECIMAL(4,2) NULL COMMENT ''上次的缩放倍率'' AFTER read_page', 'SELECT 1');
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @c := (SELECT COUNT(*) FROM information_schema.COLUMNS
           WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'file_info' AND COLUMN_NAME = 'read_mode');
SET @ddl := IF(@c = 0, 'ALTER TABLE file_info ADD COLUMN read_mode VARCHAR(16) NULL COMMENT ''single/double/continuous'' AFTER read_scale', 'SELECT 1');
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- =====================================================================
-- 12) 首页年度学习记录（2026-10 新增）
-- 每个来源每天一条；不关联外键，删除内容后仍保留当天的学习足迹。
-- =====================================================================
CREATE TABLE IF NOT EXISTS learning_activity (
    source_type   VARCHAR(16) NOT NULL COMMENT 'note/quick_ref/file/agent_event',
    source_id     BIGINT      NOT NULL,
    activity_date DATE        NOT NULL,
    created_at    DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (source_type, source_id, activity_date),
    KEY idx_learning_activity_date (activity_date)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='每日学习记录';

-- 老数据只能恢复创建日和最后更新日；INSERT IGNORE 保证重复启动不增加计数。
INSERT IGNORE INTO learning_activity (source_type, source_id, activity_date)
SELECT 'note', id, DATE(created_at) FROM note WHERE created_at IS NOT NULL;
INSERT IGNORE INTO learning_activity (source_type, source_id, activity_date)
SELECT 'note', id, DATE(updated_at) FROM note WHERE updated_at IS NOT NULL;
INSERT IGNORE INTO learning_activity (source_type, source_id, activity_date)
SELECT 'quick_ref', id, DATE(created_at) FROM quick_ref WHERE created_at IS NOT NULL;
INSERT IGNORE INTO learning_activity (source_type, source_id, activity_date)
SELECT 'quick_ref', id, DATE(updated_at) FROM quick_ref WHERE updated_at IS NOT NULL;
INSERT IGNORE INTO learning_activity (source_type, source_id, activity_date)
SELECT 'file', id, DATE(created_at) FROM file_info WHERE created_at IS NOT NULL;
INSERT IGNORE INTO learning_activity (source_type, source_id, activity_date)
SELECT 'agent_event', id, DATE(created_at) FROM agent_event WHERE role = 'user' AND created_at IS NOT NULL;

-- =====================================================================
-- 13) rag_eval_answer_run.false_refusals（2026-10-02 新增）
--     为什么单独一列而不是并进 no_answer_score：实测发现两者是**不同的失败模式** ——
--     "缺口题没老实说不知道"是模型在编；"有材料却拒答"多半是检索把来源召回了、
--     但注入的证据没带上需要的那段细节（实测 id=139 就是这样）。
--     把后者并进分数会让一个 0.75 看起来像"模型 25% 的时间在瞎答"，其实是两种问题各占一半。
--     注意本表是本次新加的，但**已经建过**（先跑过一轮冒烟），所以这里必须走幂等迁移 ——
--     CREATE TABLE IF NOT EXISTS 对已存在的表什么都不做，直接改上面的建表语句不会生效。
-- =====================================================================
SET @c := (SELECT COUNT(*) FROM information_schema.COLUMNS
           WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'rag_eval_answer_run'
             AND COLUMN_NAME = 'false_refusals');
SET @ddl := IF(@c = 0,
    'ALTER TABLE rag_eval_answer_run ADD COLUMN false_refusals INT NOT NULL DEFAULT 0 COMMENT ''有材料却拒答的条数（诊断项）'' AFTER no_answer_score',
    'SELECT 1');
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- =====================================================================
-- 14) rag_eval.note 加宽 255 → 1000（2026-10-02）
--     踩到的坑：note 原本 VARCHAR(255)，而人工标注题的 note 要装
--     "考什么 + 依据来自哪份材料的哪一段 + 原文摘录"，很容易超过 255。
--     致命的是 **INSERT IGNORE 会把 "Data too long" 这个错误降级成 warning 并静默截断** ——
--     于是 5 条（id 140/141/142/147/148）的证据摘录被拦腰砍掉，而导入还报成功。
--     300 条用例里 5 条被截、且没有任何报错，这种损失靠"看导入是否报错"是发现不了的。
--     加宽到 1000 而不是 TEXT：内容仍是短注记，给个明确上限比放到 TEXT 更不容易被滥用。
-- =====================================================================
SET @c := (SELECT IFNULL(MAX(CHARACTER_MAXIMUM_LENGTH), 0) FROM information_schema.COLUMNS
           WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'rag_eval' AND COLUMN_NAME = 'note');
SET @ddl := IF(@c < 1000,
    'ALTER TABLE rag_eval MODIFY COLUMN note VARCHAR(1000) NULL COMMENT ''这条用例想验证什么 + 依据来自哪份材料的哪一段''',
    'SELECT 1');
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;
