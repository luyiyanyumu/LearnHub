-- Representative pre-Flyway database: older tables plus user data, no migration history.
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
    created_at   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    KEY idx_file_category (category_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT ='资料库文件';

CREATE TABLE IF NOT EXISTS app_setting (
    setting_key   VARCHAR(64)  NOT NULL PRIMARY KEY COMMENT '设置键',
    setting_value MEDIUMTEXT   NULL COMMENT '设置值(覆盖默认)',
    updated_at    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT ='应用设置';

CREATE TABLE IF NOT EXISTS agent_session (
    id          VARCHAR(36)  NOT NULL PRIMARY KEY COMMENT '会话id(UUID)',
    title       VARCHAR(120) NULL COMMENT '标题：取首条用户消息的前若干字',
    note_id     BIGINT       NULL COMMENT '发起时所在的笔记id（可为空）',
    created_at  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    KEY idx_agent_session_updated (updated_at)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT ='智能体会话';

CREATE TABLE IF NOT EXISTS agent_event (
    id            BIGINT AUTO_INCREMENT PRIMARY KEY,
    session_id    VARCHAR(36) NOT NULL COMMENT '所属会话id',
    role          VARCHAR(16) NOT NULL COMMENT 'user/assistant/tool/summary',
    content       MEDIUMTEXT  NULL COMMENT '文本内容（assistant 存最终回复，summary 存压缩摘要）',
    tool_name     VARCHAR(64) NULL COMMENT '工具名（role=tool 时）',
    tool_call_id  VARCHAR(64) NULL COMMENT '对应模型返回的 tool_call id',
    tokens        INT         NULL COMMENT '该轮 token 用量（含思考；接口返回 usage 时记录）',
    created_at    DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    KEY idx_agent_event_session (session_id, id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT ='智能体会话事件';

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

CREATE TABLE IF NOT EXISTS kb_chunk (
    id          BIGINT AUTO_INCREMENT PRIMARY KEY,
    source_type VARCHAR(16)  NOT NULL COMMENT 'note / quick_ref / file',
    source_id   BIGINT       NOT NULL,
    seq         INT          NOT NULL COMMENT '该来源的第几块（从 0 开始）',
    title       VARCHAR(255) NULL COMMENT '来源标题（冗余，检索展示用）',
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

CREATE TABLE IF NOT EXISTS rag_eval_answer_run (
  id BIGINT NOT NULL AUTO_INCREMENT,
  label VARCHAR(64) NOT NULL COMMENT '本次运行的标签',
  cases INT NOT NULL DEFAULT 0 COMMENT '实际评测了多少条用例',
  top_k INT NOT NULL DEFAULT 5,
  mode VARCHAR(16) NOT NULL DEFAULT 'fused' COMMENT '检索模式：fused/keyword/vector',
  corpus_hash VARCHAR(64) NULL COMMENT '语料指纹（各来源内容哈希聚合，语料变了分数不可比）',
  model VARCHAR(120) NULL COMMENT '生成答案所用的模型名',
  prompt_version VARCHAR(32) NULL COMMENT '生成与评分 prompt 的版本号',
  answer_accuracy DOUBLE NOT NULL DEFAULT 0 COMMENT '答案正确性：expect_words 覆盖率（只统计有标注的题）',
  citation_support DOUBLE NOT NULL DEFAULT 0 COMMENT '引用支持：答案被证据支撑的占比（grounding）',
  citation_coverage DOUBLE NOT NULL DEFAULT 0 COMMENT '引用完整性：必要来源**全部**召回的占比（ALL 而非 ANY）',
  no_answer_score DOUBLE NOT NULL DEFAULT 0 COMMENT '无答案处理：缺口题正确拒答率（该说不知道时说了的比例）；无缺口题时为 0 表示未测',
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

INSERT INTO category (id, name) VALUES (9001, '保留的分类');
INSERT INTO note (id, title, content, category_id, created_at, updated_at) VALUES (9001, '保留的笔记', '# 用户自己的正文', 9001, '2026-01-10 12:00:00', '2026-10-01 13:00:00');
INSERT INTO quick_ref (id, title, content) VALUES (9001, '保留的卡片', '用户卡片正文');
INSERT INTO file_info (id, origin_name, store_name, size) VALUES (9001, '原资料.pdf', 'keep.pdf', 512);
INSERT INTO app_setting (setting_key, setting_value) VALUES ('custom.setting', 'keep-me');
INSERT INTO model_profile (id, name, base_url, model, api_key) VALUES ('keep-profile', '保留的档案', 'http://example.invalid', 'keep-model', 'synthetic-test-key');
INSERT INTO wiki_page (id, topic_key, title, content_md) VALUES (9001, 'cat-9001', '旧 Wiki', '用户 Wiki 正文');
INSERT INTO rag_eval (question, expect_refs, note) VALUES ('旧用例', 'note:9001', '保留注记');
