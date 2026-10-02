-- ============================================================================
-- rag_eval 人工标注题：30 条（关系 10 / 跨资料综合 10 / 库内缺口 10）
-- 起草、落库、核对：2026-10-02
--
-- 这份文件是**评测题集的权威副本**，与数据库里 enabled=0 的 30 行一一对应。
-- 为什么要落成文件：题目此前只存在于数据库里，仓库无从复核，重装环境就丢了。
--
-- 【30 条一律 enabled=0（草稿）】
--   RagEvalService.cases() 只取 enabled=1，所以它们**不进 99 题回归基线**。
--   审改通过后再逐条启用：
--     UPDATE rag_eval SET enabled=1 WHERE id = <...>;
--   启用后跑：POST /api/kb/eval/run?label=加30题后&topK=5&mode=fused
--
-- 【缺口题不会拖低召回率】
--   expect_refs='none' 是"库内缺口/无答案题"，RagEvalService.isGapCase() 会把它们
--   从 recall/MRR/词面/语义四个分母里剔除 —— 否则每加一条缺口题就白扣一分召回。
--   它们考的是"该说不知道时有没有说"，由答案级评测的 no_answer_score 判：
--     POST /api/kb/eval/answer/run?label=&topK=5&limit=10
--
-- 【expect_words 为什么有 NULL】
--   缺口题没有"应出现的关键事实"，给了会把拒答措辞算进正确性覆盖率、污染指标；
--   keywordCoverage 对没标注的题返回 -1（不参与均值）。
--
-- 【导入方式】不会自动执行（spring.sql.init 只跑 schema.sql / data.sql）：
--   mysql -h127.0.0.1 -P3307 -uroot -p learn_hub < backend/src/main/resources/rag-eval-labeled.sql
--
-- 【血泪教训：别再用 INSERT IGNORE 灌长文本】
--   note 列原本是 VARCHAR(255)，而这里的 note 最长 399 字（"考什么 + 依据 + 原文摘录"）。
--   INSERT IGNORE 会把 "Data too long" 这个**错误降级成 warning 并静默截断** ——
--   本轮就有 5 条（id 140/141/142/147/148）的证据摘录被拦腰砍掉，而导入还报成功。
--   note 已通过幂等迁移加宽到 VARCHAR(1000)（schema.sql 第 14 条）。
--   灌完这类数据务必复核长度：SELECT MAX(CHAR_LENGTH(note)) FROM rag_eval;
-- ============================================================================

SET NAMES utf8mb4;
INSERT IGNORE INTO rag_eval (question, expect_refs, expect_words, note, enabled) VALUES ('String、StringBuilder 和 StringBuffer 到底有什么区别，我该用哪个？', 'quick_ref:1', 'StringBuilder;StringBuffer;线程;性能', '【关系/对比题·草稿】依据：速查卡《String / StringBuilder / StringBuffer 区别》，标题本身即该对比', 0);
INSERT IGNORE INTO rag_eval (question, expect_refs, expect_words, note, enabled) VALUES ('Java 里 == 和 equals 有什么区别？', 'quick_ref:4', 'equals;引用;地址;内容', '【关系/对比题·草稿】依据：速查卡《Java == 与 equals 区别》，标题本身即该对比', 0);
INSERT IGNORE INTO rag_eval (question, expect_refs, expect_words, note, enabled) VALUES ('@GetMapping 和 @PostMapping 有什么区别？', 'quick_ref:3', 'GetMapping;PostMapping;GET;POST', '【关系/对比题·草稿】依据：速查卡《@GetMapping / @PostMapping 速记》', 0);
INSERT IGNORE INTO rag_eval (question, expect_refs, expect_words, note, enabled) VALUES ('Kubernetes 的 Operator 模式怎么用？', 'none', '没有记录;未找到;不包含', '【缺口题·草稿】已核对：词面检索「Kubernetes Operator」命中 0 条，库里确实没有该内容', 0);
INSERT IGNORE INTO rag_eval (question, expect_refs, expect_words, note, enabled) VALUES ('Rust 的所有权机制怎么理解？', 'none', '没有记录;未找到;不包含', '【缺口题·草稿】已核对：词面检索「Rust 所有权」命中 0 条', 0);
INSERT IGNORE INTO rag_eval (question, expect_refs, expect_words, note, enabled) VALUES ('Go 的 goroutine 调度是怎么实现的？', 'none', '没有记录;未找到;不包含', '【缺口题·草稿】已核对：词面检索「goroutine 调度」命中 0 条', 0);
INSERT IGNORE INTO rag_eval (question, expect_refs, expect_words, note, enabled) VALUES ('Spring 里的控制反转和依赖注入，是一个东西的两种叫法吗？', 'note:5', '设计思想;实现方式;IoC;DI', '考什么：IoC 与 DI 是思想与实现方式的关系，不是同义反复。依据 note:5《四、企业必备技术 →（一）Spring → 1. Spring Core》第 1813 行原文「前者是设计思想，即把对象的创建权和依赖管理权交给 Spring 容器；后者是实现方式」。', 0);
INSERT IGNORE INTO rag_eval (question, expect_refs, expect_words, note, enabled) VALUES ('FAISS 和 Milvus 都是干向量检索的，到底差在哪？我这种量级该用哪个？', 'note:52', '算法库;服务;持久化;过滤;扩容', '考什么：算法库与分布式向量数据库的边界（能力清单差在哪），以及可操作的选型判据。依据 note:52《与相邻方案的差异》第 50 行原文「FAISS 给你算法，Milvus 给你服务」；同文《一个判断标准》第 75 行给出判据「检索时是否需要复杂的标量过滤」。', 0);
INSERT IGNORE INTO rag_eval (question, expect_refs, expect_words, note, enabled) VALUES ('MCP 和 Function Calling 是二选一的关系吗？还是两个得一起用？', 'file:19', '模型侧;工具侧;互补;FC;tools', '考什么：MCP 与 FC 是工具侧集成与模型侧表达的互补关系，不是替代关系。依据 file:19《04 工具调用（Tool / Function Calling）→ 3.3 MCP vs Function Calling 的区别》第 2596 行原文「FC 是模型侧表达」；该节结论是 Host 常把 MCP 工具列表映射成 FC 的 tools，二者一起用。', 0);
INSERT IGNORE INTO rag_eval (question, expect_refs, expect_words, note, enabled) VALUES ('Reflexion 跟「让模型自己再检查一遍」有什么不一样，值不值得为它多花一轮？', 'file:19', '一次性;显式化;策略记忆;跨尝试', '考什么：Reflexion 与一次性自检的区别——反思是否结构化、是否能跨尝试复用。依据 file:19《3. Reflexion 框架 → 3.3 面试问题（Q）+ 标准答案（A）Q5》（第 904-906 行）原文「Reflexion 把评估与反思 显式化、结构化」；该问答的对比是一次性自检 vs 可累积的策略记忆。', 0);
INSERT IGNORE INTO rag_eval (question, expect_refs, expect_words, note, enabled) VALUES ('检索里 Bi-Encoder 和 Cross-Encoder 有什么不同？为什么召回完还要再重排一遍？', 'note:51', 'Bi-Encoder;Cross-Encoder;独立编码;拼在一起;Top-K', '考什么：双塔编码器与交叉编码器在编码方式 / 精度 / 速度上的取舍，以及重排为什么放在 Top-K 之后。依据 note:51《5.4 重排序（Reranking）》第 383 行原文（已去掉 Markdown 加粗标记）「（双塔），为速度对 query 与 doc」「打分，精度更高但慢，所以放在 Top-K 之后做小范围重排」。', 0);
INSERT IGNORE INTO rag_eval (question, expect_refs, expect_words, note, enabled) VALUES ('Spring Boot 的自动配置和我自己写在配置文件里的东西，到底谁说了算？', 'file:2', '没有自定义配置;jar 包依赖;外部配置;加载顺序', '考什么：自动配置的触发条件（无自定义时才按依赖推断）与外部配置的覆盖顺序。依据 file:2《1.6 Spring Boot自动配置与外部配置》第 1104 行原文「尝试根据现有的 jar 包依赖对程序进行配置」；第 1120 行原文「为了将外部配置和自动配置配合使用，Spring Boot 为这些配置的加载指定了一个顺序」，其后列出 (1)~(17) 共 17 级。', 0);
INSERT IGNORE INTO rag_eval (question, expect_refs, expect_words, note, enabled) VALUES ('code agent 里单段 60 轮和总共 1200 轮这两个上限，哪个才是真的会停下来？', 'note:50', '检查点;硬顶;1200;续跑;熔断', '考什么：区分段边界（检查点，不是停止）与硬顶 / 熔断（真停）两类判据。依据 note:50《一、停止边界》第 20 行原文「为什么 60 轮不算停止」，并把它称为检查点；同段原文「60 × 40 = 2400 > 硬顶 1200」，真停的是 1200 轮硬顶与 5 次同指纹熔断。', 0);
INSERT IGNORE INTO rag_eval (question, expect_refs, expect_words, note, enabled) VALUES ('我想在 Spring Boot 启动过程里插一段自己的逻辑，比如环境刚准备好就去读点东西——启动流程走到哪一步了？该挂哪个事件、代码写在哪儿？', 'note:2|file:2', '环境就绪;ApplicationEnvironmentPreparedEvent;addListeners;SpringApplication', '考什么：笔记给启动流程骨架、PDF 给可挂的事件钩子与注册代码，缺一侧答不全。依据：note:2《分步拆解》第 9 行原文「创建 SpringApplication → 推断应用类型 → 加载启动类上的注解」；file:2《2.6.2 内置事件》第 2457 行原文「ApplicationEnvironmentPreparedEvent：应用环境就绪事件」，第 2489 行给出注册写法「application.addListeners(new」。', 0);
INSERT IGNORE INTO rag_eval (question, expect_refs, expect_words, note, enabled) VALUES ('启动类放在 com.example 下、Controller 也放里面就没事，可我把 Controller 挪到和启动类同级的包外面就扫不到了，这是为什么？怎么补救？', 'note:5|file:2', 'root package;子包;ComponentScan;扫描范围', '考什么：@ComponentScan 的扫描范围（注解含义在笔记里）× PDF 里 root package 的坑与两种修法。依据：note:5《1. Spring Core》第 1823 行原文「@ComponentScan（"包名"）告诉 Spring 去哪些包中扫描组件」；file:2《1.4.2 推荐的工程结构》第 383 行原文「root package与应用主类的位置是整个结构的关键」，第 404 行原文「（1）使用@ComponentScan 注解指定具体的加载包，比如：」，另一种补救是 @Bean。', 0);
INSERT IGNORE INTO rag_eval (question, expect_refs, expect_words, note, enabled) VALUES ('RAG 原论文里的 RAG-Sequence 和 RAG-Token 到底差在哪？它那套检索器和生成器分别用的什么模型？', 'note:47|file:16', 'RAG-Sequence;RAG-Token;同一篇;BART;DPR', '考什么：中文笔记给论文定位与组件（DPR + BART、端到端联合微调），原论文给两种范式的确切差别（每个 token 是否可换文档）；笔记把这条恰好留在「待补」清单里。依据：note:47《① RAG（Lewis 2020）》第 89 行原文「检索器用 DPR 的稠密向量取回 top-k 段落」；file:16 第 112-114 行原文（跨行拼接）「the model uses the same document to predict each target token」/「can predict each target token based on a different document」，第 148 行原文「We use BART-large [32], a pre-trained seq2seq transformer [58] with 400M parameters.」。', 0);
INSERT IGNORE INTO rag_eval (question, expect_refs, expect_words, note, enabled) VALUES ('都说 DPR 只用了很少的标注数据就干翻了 BM25，这个「很少」具体是多少？它凭什么能赢关键词检索？', 'note:47|file:17', '1000 条;dual-encoder;BM25;同义词', '考什么：笔记只给结论（少量标注数据即大幅超过 BM25），具体数量与机制在原论文里（1000 条样本、双塔点积、同义改写仍能命中）。依据：note:47《② DPR（Karpukhin 2020）》第 95 行原文「仅用少量标注数据（Natural Questions）训练即大幅超过 BM25」；file:17 正文原文「using 1,000 examples already outperforms BM25」，摘要里还有 bad guy 与 villain 的例子「Who is the bad guy in lord of the rings?」。', 0);
INSERT IGNORE INTO rag_eval (question, expect_refs, expect_words, note, enabled) VALUES ('我想搭一套 RAG，向量规模可能涨到几千万条，还能像小项目那样把 FAISS 塞在进程里用吗？什么时候非得上专门的向量库？', 'note:47|note:52', 'FAISS;Milvus;百万级;过滤下推;ANN', '考什么：RAG 侧的检索链路（query 向量与 chunk 向量做 top-k 召回）× Milvus 笔记的规模选型阈值，两边合起来才能判断。依据：note:47 第 44 行原文「和每个 chunk 的向量算 cosine similarity 排序，取 top 10 当上下文」；note:52《维度一：数据量》第 304 行表格原文「100 万 ~ 1 亿」，第 307 行给出内存粗算「向量数 × 维度 × 4 字节 × 索引倍数」。', 0);
INSERT IGNORE INTO rag_eval (question, expect_refs, expect_words, note, enabled) VALUES ('笔记里说 RAG 是两篇论文打的地基，那现在常听到的 Agentic RAG、Self-RAG 跟这两篇是什么关系？', 'note:47|file:19', 'Agentic RAG;Self-RAG;反思 token;多步决策;延伸', '考什么：奠基论文（RAG / DPR）与后续 RAG 变体的继承关系——笔记给 Self-RAG 的出处与定位，面试资料给各变体的定义与边界。依据：note:47《延伸三篇（可选）》第 104 行原文「模型自己决定要不要检索、结果可不可信，与 Agent 方向直接衔接」；file:19《8.2 Agentic RAG》第 1822 行原文「由 Agent 决定何时检索、检索什么、是否再检索」、《8.3 Self-RAG》第 1827 行原文「反思 token：是否需要检索」。', 0);
INSERT IGNORE INTO rag_eval (question, expect_refs, expect_words, note, enabled) VALUES ('按「Agent = Model + Harness」的说法，harness 得补上模型补不了的能力。那「任务跑起来停不下来」这件事，在真实项目里是靠什么补的？', 'note:44|note:50', 'Harness;安全钩子;检查点;硬顶;熔断', '考什么：笔记把「安全钩子」列为 harness 组件之一，真实 code agent 则把它落成一套分层阈值；两边合起来才是完整答案。依据：note:44《2.Harness 装了什么》第 28 行原文「执行前后拦截；到轮数上限不硬退，先判断任务做完没」；note:50 第 20 行原文「为什么 60 轮不算停止」、「60 × 40 = 2400 > 硬顶 1200」，第 39 行原文「机器阈值比人的自律更可靠」，另有 5 次同指纹熔断。', 0);
INSERT IGNORE INTO rag_eval (question, expect_refs, expect_words, note, enabled) VALUES ('长任务跑到几十轮，上下文越堆越长，讲义里讲的压缩原则跟这个 code agent 实际的做法对得上吗？', 'note:51|note:50', '摘要压缩;误差累积;60 轮;重新锚定', '考什么：讲义给的上下文压缩通用原则 × 该项目在段边界的具体动作（重新锚定 + 压一次上下文 + 要求复用已查到的结论）。依据：note:51《7.3 短期记忆与摘要压缩》第 605 行原文「把早期对话压成摘要，解决成本与注意力问题，但」；note:50 第 20 行原文「每到 60 轮，从持久化轨迹重新推导一遍、压一次上下文」。', 0);
INSERT IGNORE INTO rag_eval (question, expect_refs, expect_words, note, enabled) VALUES ('AgentFlow 那种计划员、操作工、质检员、出报告的流水线，按我讲义里的说法算多智能体吗？它到底训练了哪一部分？', 'note:51|file:5', '多 Agent;计划员;planner;on-policy;Flow-GRPO', '考什么：讲义的多 Agent 定义与形态给出归类，论文给出实际模块与只在线训练 planner 的事实，缺一侧答不完整。依据：note:51《8.1 单 Agent 的瓶颈 vs 多 Agent 的优势》第 728 行原文「就是典型的多 Agent 分工」，角色是「计划员 + 操作工 + 质检员 + 出报告的人」；file:5 摘要原文「a trainable, in-the-flow agentic framework that coordinates four modules」与「directly optimizes its planner inside the multi-turn loop」。', 0);
INSERT IGNORE INTO rag_eval (question, expect_refs, expect_words, note, enabled) VALUES ('讲义说长任务部署要配可恢复状态。真到论文里，光把对话上下文存下来够不够？还得一并还原什么？', 'note:51|file:6', 'checkpoint;上下文;环境状态;对齐;rewind memory', '考什么：部署侧「可恢复状态」的通用说法 × 论文里上下文与环境状态对齐恢复（外加 rewind memory）的具体要求，还要看消融结论。依据：note:51《13.5 版本管理与发布策略》第 1433 行原文「任务队列与 Worker + 可恢复状态（checkpoint）+ K8s 优雅停机」；file:6 正文原文「a system that can return both」「the agent context and the environment state to a selected」，消融结论原文「rewind causes the largest degradation」。', 0);
INSERT IGNORE INTO rag_eval (question, expect_refs, expect_words, note, enabled) VALUES ('Kafka 的消费者组 rebalance 一般是什么原因触发的？', 'none', NULL, '考什么：知识库里没有 Kafka 主题内容时，模型应明确说"没有收录"，而不是照着通用知识编一套 rebalance 触发条件。依据：语料全文扫描 rebalance 0 次、消费者组 0 次；GET /api/knowledge/search?kw=Kafka 消费者组 rebalance 是怎么触发的 返回 total=0。', 0);
INSERT IGNORE INTO rag_eval (question, expect_refs, expect_words, note, enabled) VALUES ('Nginx 反向代理要怎么配？', 'none', NULL, '考什么：库里没有 Nginx 相关材料时的"该说不知道"。依据：语料全文扫描 nginx 0 次、反向代理 0 次（注："负载均衡"在语料中出现 3 次，但都不属于 Nginx 配置语境）；GET /api/knowledge/search?kw=Nginx 反向代理怎么配置 返回 total=0。', 0);
INSERT IGNORE INTO rag_eval (question, expect_refs, expect_words, note, enabled) VALUES ('为什么 TCP 建立连接要三次握手，两次不行吗？', 'none', NULL, '考什么：网络协议属于库外知识，模型不该假装是"你笔记里记过的"。依据：语料全文扫描 三次握手 0 次（tcp 一词出现 10 次，但均在无关语境）；GET /api/knowledge/search?kw=TCP 为什么三次握手不是两次 返回 total=0。', 0);
INSERT IGNORE INTO rag_eval (question, expect_refs, expect_words, note, enabled) VALUES ('Vue3 的响应式原理是怎么实现的？', 'none', NULL, '考什么：前端框架内容库里完全没有，考会不会老实承认。依据：语料全文扫描 vue 0 次、响应式 0 次；GET /api/knowledge/search?kw=Vue3 响应式原理是怎么实现的 返回 total=0。库里"响应式"这个词一次都没出现过，所以这不是"说法不同"，是真的没有。', 0);
INSERT IGNORE INTO rag_eval (question, expect_refs, expect_words, note, enabled) VALUES ('Linux 里 chmod 755 这种权限位怎么算？', 'none', NULL, '考什么：系统运维基础属于库外内容。依据：语料全文扫描 chmod 0 次；GET /api/knowledge/search?kw=Linux chmod 权限位怎么算 返回 total=0。注意这条容易被模型的先验知识蒙对（755 是常识），所以它真正考的是"有没有把它说成是知识库里的记录"。', 0);
INSERT IGNORE INTO rag_eval (question, expect_refs, expect_words, note, enabled) VALUES ('用 Redis 做分布式锁，怎么防止死锁？', 'none', NULL, '考什么：库里提到过 Redis（40 次），但没有任何分布式锁的实现内容 —— 这正是"半熟主题"的缺口题：模型看到 Redis 有印象就更容易编。依据：语料全文扫描 分布式锁 0 次；GET /api/knowledge/search?kw=Redis 分布式锁怎么实现 返回 total=0。', 0);
INSERT IGNORE INTO rag_eval (question, expect_refs, expect_words, note, enabled) VALUES ('MySQL 数据量太大了，分库分表一般怎么分？', 'none', NULL, '考什么：MySQL 在语料里出现 24 次（多在 JDBC / 连接语境），但没有分库分表方案 —— 同属"半熟主题"。依据：语料全文扫描 分库分表 0 次；GET /api/knowledge/search?kw=MySQL 分库分表怎么分 返回 total=0。', 0);
