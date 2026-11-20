# LearnHub 项目面试：60 个问题、参考回答与连续追问

> 整理日期：2026-10-30。依据当前工作区源码，面向 Java 后端、AI 应用开发与全栈岗位。
>
> 回答中的“我”是练习口径，只能用于你确实理解、参与过的工作。项目代码能证明功能存在，不能证明你的个人贡献、线上规模或实际收益。本文不虚构用户量、QPS、准确率和性能提升，也不引用本机资料与私有评测数据。

## 怎么练这份题

- **P0：必须讲清楚。** 先练项目介绍、检索链路、更新一致性、Agent 预算与写入确认。
- **P1：容易拉开差距。** 重点解释失败窗口、实现取舍、测试方法与扩展路径。
- **P2：按岗位准备。** 前端编辑器、部署发布等可以根据面试方向深入。
- 每题先用自己的话回答，再接连续追问。回答顺序尽量是：具体问题 → 当前实现 → 为什么这样做 → 代价与边界。
- 文末有源码入口和模拟面试路线。源码路径相对仓库，链接可直接跳到相关文件。

## 一、项目定位与架构（01—06）

### 01.【P0】用一分钟介绍项目，别从技术栈开始背。

**参考回答：**

LearnHub 是一个可自托管的个人学习与知识工作台，解决的是“资料收了很多，但查不到、看不懂关系，也很难整理回自己的笔记”的问题。用户可以导入资料、写笔记，再用关键词、向量、图谱和 Wiki 导览检索原文，通过智能体整理内容，最后确认写回笔记。

我会重点展开三条链路：多通道检索怎样回到原文证据，来源变化后怎样维护派生知识，以及智能体怎样在预算和确认机制内修改笔记。技术上是 Vue 3 与 Spring Boot 的前后端分离应用，MySQL 保存主要状态，Milvus 等服务按需启用。

**连续追问：** 用户操作一遍要经过哪些模块？没有配置模型还能用什么？你最值得展开的一个模块是什么？

**回答边界：** 没有模型时仍可用笔记、资料管理和关键词检索；不要把每个功能都说成 AI 功能。

### 02.【P0】这不就是把笔记软件和大模型 API 拼在一起吗？

**参考回答：**

如果只是聊天入口，确实很容易被替代。我更看重围绕自己的知识持续工作的闭环：原文抽取和结构化分块、多通道召回、来源核对、派生 Wiki 与图谱的失效维护、笔记定向编辑和人工确认。

其中有不少确定性工程问题，调用模型本身解决不了。例如旧来源失效后不能继续引用，模型生成的目标段落必须准确定位，用户继续输入时不能错误地显示已保存。项目价值需要用这些具体场景和对照评测证明，不能只凭功能多就证明有效。

**连续追问：** 去掉图谱和 Wiki，效果会差多少？哪一个功能改善了真实任务？

**回答边界：** 没有固定数据集的消融实验，就回答“目前还没有足够证据给出增益比例”，再说明如何验证。

### 03.【P0】为什么用单体，为什么不用微服务、Redis、MQ？

**参考回答：**

当前主要面向个人自托管，先采用单体内按服务职责拆分，可以减少部署组件和跨服务排错。笔记、资料、检索、Wiki、图谱、会话都有独立的服务入口，主要数据留在 MySQL；浏览器抓取、代码运行等需要特殊运行环境的能力可以独立部署。

代价是部分任务调度、互斥和缓存仍属于单进程，不能直接增加后端副本就得到正确的水平扩展。引入 MQ 或 Redis 应服务于明确问题，例如任务租约、跨实例互斥或吞吐瓶颈；先给出问题和一致性要求，再选择组件。

**连续追问：** 如果起两个后端，哪里最先出问题？你准备拆哪个模块？

**答题重点：** 优先谈后台维护和 Agent 会话互斥的跨实例边界，再谈服务拆分。

### 04.【P1】为什么自己写 Agent 和 RAG 编排，没有直接套框架？

**参考回答：**

当前实现直接组织模型协议、工具调用、检索和状态保存。这样可以明确控制笔记作用域、待确认操作、来源证据和执行预算，也容易让前端展示实际执行阶段。项目中的定向笔记编辑、会话恢复和结构保护都有比较具体的业务要求。

代价是我要自己维护循环状态、协议兼容、重试、工具结果压缩和异常处理，也容易出现大型服务类。是否引入框架应看它能否减少维护成本，并保留这些业务约束，不能把“不用框架”本身当作技术优势。

**连续追问：** 哪部分是业务逻辑，哪部分应该抽成可复用基础能力？如何避免 `AgentService` 越来越大？

### 05.【P0】从上传一份 PDF，到 Agent 引用它回答，完整走一遍。

**参考回答：**

上传后先保存文件和元数据，再用文档抽取服务提取正文并记录成功、空内容、不支持或失败等状态。来源变化触发后续维护，正文按结构分块，需要向量时调用嵌入服务并保存分块与索引状态；Wiki 和图谱按维护流程更新。

查询时从关键词、向量、局部图谱证据与 Wiki 导览召回候选，统一去重、RRF 融合并按配置重排，再在预算内组装原文上下文。Agent 根据证据回答；进一步修改笔记时进入预览与确认流程。上传成功不等于所有索引和派生知识已同步完成。

**连续追问：** 扫描 PDF 没有文字怎么办？嵌入失败怎么办？上传成功但检索不到，先查哪一步？

**源码入口：** [文件存储][file-storage]、[正文抽取][document-text]、[检索编排][retrieval]、[后台维护][maintenance]。

### 06.【P0】你觉得最难的问题是什么？说一个能画时序图的。

**参考回答示例：**

我会选“AI 修改笔记时如何不覆盖用户的新输入”。它跨越前端草稿、保存请求、模型生成、预览确认和数据库写入。前端要确认当前活动笔记、刷新编辑器内容并保存；后端定向编辑要用内容哈希确认基准，再在行锁和事务里校验并写入；前端接收更新时还要处理本地已经变脏的情况。

这条链路解决了指定编辑路径中的一部分覆盖风险，但普通保存还没有统一版本条件，所以不能宣称所有并发编辑都已解决。如果继续完善，我会把版本校验覆盖到所有正文写入口，并增加两个客户端交错保存的测试。

**连续追问：** 按 A 读旧版本 → B 写新版本 → A 保存的顺序，你现在会发生什么？

**使用要求：** 只有确实参与并理解这条链路才用这个案例；不要把示例改写成虚构的线上事故。

## 二、RAG 检索与评测（07—18）

### 07.【P0】为什么不直接每 800 个字符切一块？

**参考回答：**

固定长度切割容易把定义与解释、表格、代码和多行公式拆开。当前优先识别标题、段落及完整结构，再按目标长度聚合；默认目标 800 字符、重叠 120、普通文本最大 1200，这些是可配置的字符预算，不是精确 token 数。

代码围栏、显示公式和表格按原子结构保护，因此允许超过普通文本上限。代价是块大小不完全均匀，下游仍要处理嵌入输入和上下文预算。项目为较大的原子块把 `kb_chunk.chunk_text` 迁移到可容纳更长文本的类型。

**连续追问：** 单个代码块很长怎么办？保留整块是否意味着模型一定能看到整块？

**回答边界：** 保存结构完整与模型输入完整是两件事；嵌入输入仍受独立上限约束。

**源码入口：** [结构分块][chunking]、[分块设置][kb-settings]、[V7 迁移][migration-v7]。

### 08.【P1】你说语义切割，具体用的什么信号？所有内容都这么切吗？

**参考回答：**

长资料可以启用语义切割，用相邻文本单元的嵌入差异寻找语义边界，再结合长度和结构约束聚合。边界阈值按差异分布的百分位设置，默认百分位是 85，值越高，通常选出的边界越少。

它主要用于达到长度门槛的资料库文件，默认门槛为 4000 字符；笔记和速查卡仍按结构处理。预计算边界会增加嵌入请求和索引成本，所以并不是所有来源、每次查询都实时做语义切割。

**连续追问：** 如何证明语义切割优于结构切割？换嵌入模型后旧边界还能直接用吗？

**答题重点：** 比较固定语料下的召回、上下文完整性、成本和耗时；不要只展示分块数量变化。

**源码入口：** [语义切割][semantic-chunker]、[结构分块][chunking]、[分块设置][kb-settings]。

### 09.【P0】为什么把分块持久化？每次问的时候重新切不行吗？

**参考回答：**

持久化使嵌入、检索、证据定位能够复用一致的分块身份，也避免对没有变化的长文反复切割和嵌入。分块需要关联来源、序号、小节等信息，索引状态需要知道原文、分块配置和嵌入空间是否仍然适配。

成本是引入了派生数据的一致性问题：来源更新、删除或配置变化后，旧块可能过期。因此查询不能只看到数据库里“有向量”就认为可用，还要校验来源与索引状态；后台负责增量维护，必要时重建。

**连续追问：** 只改文件显示名要不要重算向量？为什么不同配置不能复用同一批分块？

**源码入口：** [向量索引][vector-index]、[分块实体][kb-chunk]、[索引状态实体][index-state]。

### 10.【P0】两个 embedding 模型都是 1024 维，向量为什么不能混用？

**参考回答：**

维度相同只说明数组长度相同，不说明坐标空间含义相同。项目的嵌入快照根据规范化后的协议、端点与模型标识计算空间指纹，检索和索引需要匹配当前空间；历史上没有空间标识的向量也不能直接当作兼容。

API Key 不参与空间指纹，因此同服务同模型的密钥轮换不需要重建；更换服务地址或模型则需要检查并重建。局限是供应商可能在相同模型名和端点后替换权重，当前配置指纹无法自动识别，需要模型版本约束或人工重建。

**连续追问：** 向量维度怎么校验？模型别名背后的权重更新怎么发现？重建期间是否允许继续搜？

**源码入口：** [嵌入客户端][embedding]、[向量索引][vector-index]。

### 11.【P0】关键词搜索是不是 BM25？分词和倒排索引在哪里？

**参考回答：**

当前这条正式融合检索里的关键词通道，是读取来源分块后在 Java 中做词面匹配和启发式评分，不能称为 BM25，也没有因为用了 MySQL 就自动拥有搜索引擎的倒排检索能力。笔记列表里的 `LIKE` 查询与这条融合链路也不是同一个实现。

它适合当前自托管规模，能覆盖术语、名称和精确表达，但候选扫描和中文匹配都有局限。规模上来后，我会引入合适的全文索引或搜索服务，并复用后续融合协议，用固定评测比较新旧通道。

**连续追问：** 中英文混排怎么办？短词为什么容易误召回？百万分块还能这样扫吗？

**源码入口：** [检索编排][retrieval]、[普通笔记查询][note-service]。

### 12.【P0】为什么选 RRF？公式写出来，分数能解释成置信度吗？

**参考回答：**

不同通道的原始分数不可直接比较，例如词面分、余弦相似度和图谱证据分的尺度不同。RRF 使用各通道内部名次融合，当前各通道等权：

$$
\operatorname{RRF}(d)=\sum_{c\in C_d}\frac{1}{k+r_c(d)}.
$$

其中，\(r_c(d)\) 是候选在通道中的名次，\(k\) 是平滑参数，默认 60、可配置范围 1 到 200。候选按来源类型、来源 ID 和片段序号去重，同一通道对同一片段只贡献一次。多个片段属于同一文档，并不会因此全部合成一个候选。

RRF 分数是排序信号，不是答案正确的概率。当前也没有可配置的通道权重，不能声称已经做了学习排序或概率校准。

**连续追问：** 两个通道高度相关会不会重复加分？只用排名损失了什么信息？怎样调平滑参数？

**源码入口：** [检索编排][retrieval]、[检索设置][kb-settings]。

### 13.【P0】默认 MySQL 怎么做向量检索？复杂度是多少？Milvus 不可用呢？

**参考回答：**

MySQL 后端保存向量，由应用侧对候选向量进行余弦评分和选取，属于全扫描方案，不能描述成原生 ANN 索引。若扫描 \(N\) 个 \(D\) 维向量，单就相似度计算而言，成本约为 \(O(ND)\)，还有读取、解码和排序等开销。实现有缓存，但缓存不能改变查询的基本扫描性质。

项目通过统一的向量存储接口支持可选 Milvus，并检查空间适配、同步状态与可用性，必要时回退 MySQL。回退保留可用性，也可能突然增加延迟；不能承诺 Milvus 下线后性能不变。

**连续追问：** 为什么一开始不用 Milvus？ANN 召回如何评测？回退会不会把后端压垮？

**源码入口：** [向量存储接口][vector-store]、[MySQL 实现][mysql-vector]、[Milvus 实现][milvus-vector]。

### 14.【P1】Query Rewrite、补检索会不会把用户问题改偏？

**参考回答：**

改写和补充查询用于弥补措辞差异或初次召回不足，不能让生成的查询文本取代原始问题成为事实。项目把这些步骤纳入统一检索流程与设置，保留原问题，并对补充候选继续执行来源检查、融合和预算控制。

风险包括概念漂移、额外模型耗时、重复候选和虚构术语。排查时应分别看原查询与补充查询带来了什么候选，而不是只看最终回答是否流畅。评测应包含改写开关对照，并统计增量召回、噪声和延迟。

**连续追问：** 一直补检索会不会无上限？补出来的内容和原问题冲突时怎么办？

**源码入口：** [检索编排][retrieval]、[检索设置][kb-settings]。

### 15.【P0】已经融合了为什么还要 rerank？LLM 排序结果能完全相信吗？

**参考回答：**

召回和 RRF 主要解决“尽量找到相关候选”，重排再判断“候选与这次问题的匹配程度”。项目支持 LLM 列表重排和 Cross-Encoder 服务，并用统一结果描述成功、跳过或失败等情况。

模型返回的候选标识和顺序需要校验，不能接受凭空出现的候选，也不能把格式错误当成合法排序。重排失败时应该保留可用的原排序和失败信息。代价是额外调用、截取片段的信息损失，以及列表重排的位置偏差，是否开启需要看端到端收益。

**连续追问：** 重排只看截断文本，会不会漏掉答案？怎么区分“失败后降级”和“正常完成但排序没变”？

**源码入口：** [重排编排][rerank]、[LLM 重排][llm-rerank]、[Cross-Encoder][cross-rerank]。

### 16.【P0】只拿命中块，定义在上一段怎么办？直接把全文塞进去吗？

**参考回答：**

项目会在预算内补齐相邻证据，但先重新读取当前来源，核对命中片段的身份和正文，避免用过期分块继续扩展。普通邻居限制在同一来源、同一标题小节、序号相邻的片段；无标题时还要求与问题相关，图谱证据片段不走这种邻居扩展。

默认每个种子最多补两个额外片段；整份上下文至多补一个经核对的资料父节开头，并占用所属种子的扩展名额，不能理解成每个种子都能额外拿“两个邻居加一个父节”。

这能减少“只有结论，没有定义”的断裂，但也会消耗上下文，不能无限扩展。当前预算主要采用字符等近似量控制，不能宣传为精确 tokenizer 预算。上下文需要兼顾候选覆盖和结构完整，不能只按总长度硬截断。

**连续追问：** 为什么不能跨小节补？多个高分块来自同一文档，会不会挤掉其他来源？

**源码入口：** [上下文组装][retrieval-context]、[结构化摘录][structure-excerpt]。

### 17.【P0】嵌入、图谱、Wiki、重排全是可选依赖，失败时怎么回答？

**参考回答：**

我把检索能力与增强能力区分开。没有配置嵌入时仍能走词面检索；Milvus 不可用可以回退 MySQL；重排失败保留已有候选；过期或无法核对的 Wiki、图谱证据不能当成有效原文继续注入。

降级必须可观察：实际走了哪些通道、有没有异常、剩余证据是否足够。零候选不代表用户库里一定没有相关内容，也可能是配置或索引问题；最终回答应明确证据不足，不能让模型把缺失的内容补成确定结论。

**连续追问：** 返回空数组和依赖超时怎么区分？降级后正确性有保证吗？

**回答边界：** 降级提高可用性，不会自动保证答案正确。

### 18.【P0】你说检索效果好，用什么指标？你代码里的 Recall 口径一致吗？

**参考回答：**

先固定语料、问题、期望来源以及 \(K\)，再评价是否命中和排序。常见的期望来源覆盖率与 MRR 可以写成：

$$
\operatorname{Recall@K}(q)=\frac{|R_q\cap T_q^K|}{|R_q|},
\qquad
\operatorname{MRR}=\frac{1}{|Q|}\sum_{q\in Q}\frac{1}{\operatorname{rank}_q}.
$$

未找到相关结果时，倒数排名项记为 0。必须声明这里按来源还是按片段评价，不能混用。

当前项目两个入口存在命名口径差异：`RagEvalService` 的 `recallAtK` 偏向“任一期望来源命中”的案例命中率；`KnowledgeDiagnosticService` 计算的是期望来源覆盖率。只有一个期望来源时可能一致，有多个时不等价。面试时要主动说明，并计划统一指标名与报告口径。

**连续追问：** 找到原文是否等于答对？没有标注的当前资料能不能显示准确率？

**回答边界：** 未标注数据不能虚构准确率；答案质量与检索质量需要分别评估。

**源码入口：** [检索评测][rag-eval]、[检索诊断][diagnostic]、[答案评测][answer-eval]。

## 三、GraphRAG、Wiki 与知识维护（19—25）

### 19.【P0】GraphRAG 究竟怎样参与检索？你做的是多跳推理吗？

**参考回答：**

正式融合链路的局部图谱通道先匹配概念，再取直接相邻关系。关系必须有合法类型、来源和非空证据，规则推导关系不直接作为这条原文证据通道的输入。之后重新读取当前原文，找到承载关系证据的片段，再参与融合。

所以这里更准确的描述是“图谱辅助发现原文证据”，主要使用直接邻域，不能说已经完成任意多跳路径推理。图谱展示接口可以显示明确标记的推导边，但展示能力与正式证据召回的准入规则需要分开讲。

**连续追问：** 命中了关系却找不到原文怎么办？原文能定位是否足以证明关系正确？

**源码入口：** [图谱原文证据][graph-evidence]、[GraphRAG][graphrag]。

### 20.【P1】模型抽错了实体关系，只要配上来源，你就当真了吗？

**参考回答：**

不会。来源定位、抽取准确性和推理正确性是不同层次。当前会对关系类型、来源和证据做约束，并在检索时核对证据是否仍存在于当前原文。这样可以挡住失效证据或没有来源的关系，但不能证明模型没有把主客体、否定或条件抽反。

进一步应准备有标注的关系样本，分别检查实体匹配、关系类型、方向、否定和条件；对冲突关系保留来源，允许用户纠正。不能把“模型抽出来”“原文出现过这些词”直接等价为知识事实。

**连续追问：** “A 不一定导致 B”会不会被抽成“A 导致 B”？同名概念如何消歧？

**回答边界：** 不宣称已实现完整语义证明、冲突消解或跨领域实体消歧。

**源码入口：** [关系抽取][triple-extractor]、[图谱原文证据][graph-evidence]。

### 21.【P1】知识社区怎么划分？为什么要有社区摘要？

**参考回答：**

项目使用自实现的 Leiden 风格算法，基于带权关系做局部移动、细化与聚合，并固定随机种子帮助重现结果。图内容指纹与社区划分在同一事务落库，内容未变时可以避免重算。

社区摘要用于“整个知识库有哪些主题”这类整体问题，读取有效缓存，作为独立的生成概览。摘要有效性涉及成员、概念、关系证据、来源内容和模型身份，生成结束前还要检查输入是否变化。它与原文片段不是同一级证据。

**连续追问：** 固定种子就一定稳定吗？图里多一条边会怎样？为什么不用连通分量？

**回答边界：** 固定种子不保证全局最优，也不能据此宣称完整复现论文的全部性质。

**源码入口：** [社区服务][community]、[社区算法][leiden]、[社区摘要][community-summary]。

### 22.【P0】Wiki 已经是 LLM 生成内容，再拿它做 RAG，不是幻觉循环吗？

**参考回答：**

所以 Wiki 的定位是导览。它帮助找到主题、小节和依赖来源，随后回查当前原文，再用原文候选参加检索。上下文中也把 Wiki 标成生成导览，不能冒充用户原始资料。

答案核验使用原文证据台账，Wiki 不升级成已经核实的原文证据。这个分层能减少把生成结论反复当事实的风险，但不能消除 Wiki 的错误导向，因此还需要检查引用、来源依赖和检索增益。

**连续追问：** Wiki 写错了，是否仍可能把原文检索带偏？为什么不完全禁用 Wiki？

**源码入口：** [Wiki 检索][wiki-retrieval]、[上下文组装][retrieval-context]、[证据台账][tool-evidence]。

### 23.【P0】原文改了一个没被 Wiki 引用的段落，Wiki 要不要失效？

**参考回答：**

当前是保守失效。依赖记录不只保存被抽样片段，还保存来源全文及页面等快照哈希。来源删除、正文或标题变化、页面变化，以及依赖不完整，都可能导致页面过期或状态未知；检索只使用通过 freshness 检查的导览。

即使修改的是没有被抽样的段落，也可能改变结论适用条件，因此全文哈希变化会让 Wiki 失效。代价是多一些重生成成本。更细粒度的依赖可以减少重算，但必须证明不会漏掉影响结论的上游变动。

**连续追问：** freshness 通过能证明内容真实吗？为什么不能只看更新时间？

**答题重点：** freshness 证明版本对应，不证明语义正确；时间戳也不能替代完整内容身份。

**源码入口：** [Wiki 依赖][wiki-dependency]、[Wiki 检索][wiki-retrieval]。

### 24.【P0】用户连续改十次笔记，后台会跑十轮全量维护吗？失败后呢？

**参考回答：**

维护流程在事务提交后接收变化，通过约两秒防抖合并连续变更，再按索引、Wiki、图谱、文档关联、社区、摘要和检查等阶段处理；部分阶段有依赖约束。阶段水位、失败次数、重试时间和状态持久化，失败按阶段退避重试，运行中发生的新变更也需要继续核查。

重启后读取持久状态，之前运行中的阶段转为待核查，再通过扫描与指纹决定后续工作。当前调度和互斥仍在单个 JVM 内，没有多实例任务租约，不能宣称严格一次执行。

**连续追问：** 暂停自动维护是否等于取消当前阶段？两个后端会不会同时维护？某阶段失败能否执行下游？

**源码入口：** [维护调度][maintenance]、[维护阶段操作][maintenance-operations]。

### 25.【P0】重建索引一半失败会清空旧索引吗？MySQL 与 Milvus 怎么一致？

**参考回答：**

重建先在事务外准备来源分块和嵌入并校验，准备期间保留旧索引；准备成功后，在 MySQL 事务中替换分块与索引状态，并检查配置是否改变。MySQL 提交后再同步 Milvus，失败时撤销 ANN 就绪状态，回退 MySQL。

这不是跨 MySQL 和 Milvus 的分布式事务。当前通过“主存储成功 + 外部索引可降级”维持可用性。重建期间来源仍可能变化，查询还要核对当前来源与片段，宁可过滤旧语义结果，也不把旧正文当作当前证据。

**连续追问：** 为什么不把模型嵌入放进数据库事务？准备期间改模型怎么办？Milvus 同步成功标记重启后还在吗？

**回答边界：** ANN 完整就绪标记有进程内状态，不能假设重启后自然保持；长模型请求放在事务里还会扩大持锁时间。

**源码入口：** [向量索引的 `rebuild`、`publishPrepared`、`assertCurrent`][vector-index]。

## 四、Agent 执行、安全与写入（26—39）

### 26.【P0】你的 Agent 与“模型调用几个 function”有什么区别？

**参考回答：**

工具调用是协议能力，Agent 还要管理任务状态和执行过程。当前循环维护目标、模型轮次、工具次数、重复调用签名和进展状态；工具结果返回模型后继续推理，直到完成、待确认、达到预算或无法继续。

服务端决定工具是否允许执行，模型只是提出调用。修改笔记等写操作进入待确认流程，计划更新或工具方案提出都不代表任务已经成功。前端展示实际阶段和最终状态，避免用一段流畅回答掩盖没有完成的操作。

**连续追问：** 一个任务“完成”的判断来自模型还是应用？待确认能不能显示成功？

**源码入口：** [Agent 主流程][agent]、[循环状态][agent-loop-state]、[会话状态][agent-session]。

### 27.【P0】模型一直调工具怎么办？预算到底怎么限制？

**参考回答：**

一轮聊天共享 900000 毫秒的执行期限，最多 16 轮模型推理、40 次工具调用，每轮最多 8 次工具。连续 3 轮没有实际进展会停止。剩余时间不足时不再启动新的大步骤，并给收尾保留时间；重试和长文子任务共享本轮期限。

同一工具与参数签名最多实际执行两次，已暂存的改动或已成功写入的结果可以复用，单纯更新计划不算真实进展。它们是时间、次数和字符等预算，当前不是按精确 token 或金额限制的成本系统。

**连续追问：** 每次超时重试是否重新获得 15 分钟？排队时间算进去吗？重复读相同资料怎样判断有无进展？

**回答边界：** 执行预算从任务实际开始计算，排队还会增加用户等待；工具局部超时是否完全服从剩余期限需要逐条核查。

**源码入口：** [Agent 期限控制][agent]、[循环计数与去重][agent-loop-state]。

### 28.【P0】工具 JSON 看起来合法，就执行吗？模型自己写了引用，就算有证据吗？

**参考回答：**

都不能只看表面格式。模型报告输出截断时，即使部分工具参数看起来可解析，也会拦截这批不完整调用。调用还要经过工具白名单、参数和作用域校验。

证据由应用独立记录实际注入和成功读取的笔记、速查卡及文件材料，不只依赖模型输出的引用编号。Wiki 只保留导览身份；若本轮材料超出核验预算或包含未覆盖工具材料，就明确不能完成全部核验，不能只验证前缀后把整篇标成可信。

**连续追问：** 有来源就没有幻觉吗？模型核验模型是否可靠？一个工具失败，后面的工具还应该执行吗？

**回答边界：** 核验标签是辅助信号，既不是事实证明，也不是所有生成内容都经过完整验证。

**源码入口：** [工具调度][agent]、[证据台账][tool-evidence]、[答案核验][grounding]。

### 29.【P0】SSE 断开，任务为什么还能跑？它是逐 token 流式吗？

**参考回答：**

执行任务提交给独立工作线程，SSE 是通知通道。连接断开后停止发送与心跳，本轮任务仍在原预算内执行并保存结果。当前聊天流主要发送会话、进度和最终结果事件，不能宣传为模型 token 的逐个实时转发。

前端通过 `fetch POST + ReadableStream` 解析事件流，处理 UTF-8 跨块和事件拆包。异常断流后不会自动重新发起聊天，因为后端可能已经执行工具；恢复时先读会话或操作状态。它不是基于 `Last-Event-ID` 的事件流断点续传。

**连续追问：** 页面关了和后端重启有何区别？超时重发会不会重复写？

**源码入口：** [聊天控制器][ai-controller]、[流解析][chat-stream]、[前端会话面板][agent-panel]。

### 30.【P0】同一会话同时发两条消息会怎样？后端重启能接着跑吗？

**参考回答：**

当前在 JVM 内记录活动会话，同一会话未结束时拒绝另一轮。聊天工作池有 4 个工作线程、24 个排队位置，队列满会明确提示本次没有开始。不同会话可以并发，但这不是用户级限流或完整的公平调度。

断开连接只影响通知，进程重启则会丢失工作线程和请求内循环状态。数据库中的会话与结果能用于查看历史，不等于任务能从中断点继续执行。两个后端实例也不会共享 JVM 会话互斥；要扩展，需要持久化任务、租约、故障接管和幂等设计。

**连续追问：** 一个会话占满时间怎么办？多实例只做粘性路由够不够？部署重启时的执行中动作怎样处理？

**源码入口：** [有界工作池][ai-controller]、[活动会话集合][agent]、[会话持久化][agent-session]。

### 31.【P0】你的“长期记忆”存了什么？为什么不把所有工具结果都回放？

**参考回答：**

会话记录与模型上下文是两份视图。常规投影取最近 8 轮用户与最终回答文本，更早内容通过带覆盖水位的摘要衔接；进度、历史思考和工具审计不直接全部回放。应用还注入操作状态，让模型知道 pending、executing 不代表成功。

单轮工具上下文有软硬字符阈值，压缩时分段摘要再合并，保留来源、哈希、游标和未完成事项；只有摘要全部成功才替换原上下文，失败则保留。它是有损的会话与任务记忆，精确编辑仍需重读原文，不能说模型永久、无损记住了全部资料。

**连续追问：** 摘要漏掉一条约束怎么办？后端重启后能按全部工具轨迹精确恢复吗？

**源码入口：** [会话投影][agent-session]、[工具上下文][agent-loop-context]、[上下文压缩][agent-context-memory]。

### 32.【P0】用户要求“改掉第三节”，为什么不让模型直接写库？

**参考回答：**

自然语言目标、模型方案和数据库副作用需要分层。模型先读正文并生成具体编辑操作，应用确定笔记作用域、验证锚点与版本并提供预览；待确认操作保存的是实际参数。用户确认时执行这些参数，不再让模型临时重新生成另一套内容。

这样可以让用户审阅修改范围，并减少操作目标漂移。当前笔记仍是未保存草稿时，不能拿数据库里的旧正文假装它就是当前编辑内容。确认机制也不能替代后端鉴权，它只是当前应用的写入流程控制。

**连续追问：** 预览和真正执行的参数一致吗？用户只说了“可以”，会不会确认另一个操作？

**源码入口：** [Agent 待确认处理][agent]、[待确认操作存储][agent-session]、[笔记编辑器][note-content-editor]。

### 33.【P0】连续点击两次确认会重复写吗？你保证 exactly-once 吗？

**参考回答：**

确认时先通过数据库条件更新，把 `pending` 变成 `executing`，只有领取成功的一方执行副作用。取消也只能从允许的状态迁移，终态不能被随意覆盖，这能应对并发确认和重复点击。

但领取、工具执行和最终状态更新没有被一个覆盖整个过程的事务包起来。进程可能在领取后、执行前崩溃，也可能在写入完成后、记成功前崩溃，留下 `executing`。所以不能宣称严格 exactly-once。应按业务加入幂等键和可查询执行记录，再决定恢复动作，不能盲目重放。

**连续追问：** 画出这两个崩溃窗口。收到网络超时后，前端怎样判断成功？为什么不能把所有工具放一个数据库事务里？

**源码入口：** [操作 `claimAction` 与 `resolveAction`][agent-session]、[确认后的工具分发][agent]。

### 34.【P0】定向修改怎么定位？有重复句子怎么办？版本检查有没有竞态？

**参考回答：**

服务端在原始 Markdown 字符串上执行确定性补丁，模型提供锚点和操作，避免重新序列化整篇。重复文本必须通过出现次数、紧邻前后文或明确全部匹配来消歧，不能默认改第一处；批量修改先全部验证，通过后再写入，并避免切断 Unicode 代理对。

定向确认在事务中 `SELECT ... FOR UPDATE`，再验证 SHA-256 `expected_hash`、计算修改并更新正文与摘要。行锁把校验和写入放进同一保护区。不过普通保存没有同样的版本条件，旧客户端随后仍可能覆盖结果，完整解决要统一所有写入口。

**连续追问：** 为什么只有 hash 校验还不够？标题重复时怎么定位？版本变了能否自动重定位？

**回答边界：** 当前版本冲突应重新读取并预览，不承诺自动无损合并。

**源码入口：** [确定性编辑][note-content-editor]、[事务写入][note-service]、[行锁查询][note-mapper]。

### 35.【P1】长文为什么用子智能体？并发会不会把章节顺序和公式弄乱？

**参考回答：**

新建长文时，优先按完整大纲一次生成，只有模型明确返回 `finish_reason=length`，才启动分节降级，不靠猜测“文字好像不够长”。多处补写可以按统一规划直接使用子任务，与新建长文的截断降级是不同入口。子任务最多 3 个并发，共享总期限；进度按实际完成顺序展示，正文按规划顺序合并，最终形成统一确认预览。

输入分块保护完整多行公式，生成提示词要求数学定义和推导使用 LaTeX；输出截断或模型失误仍可能产生不完整公式，需要标记并核对。失败的小节明确标记，不把部分完成说成整篇成功。并发生成还可能出现术语或叙述重复，需要合并检查。当前每个任务有自己的子任务池，并非全局最多 3 个模型请求。

**连续追问：** 为什么不能一开始就并行？其中一节失败怎么办？四个聊天同时分节，模型并发是多少？

**源码入口：** [长文流程][agent]、[长文子任务][long-note-agents]、[数学输出约定][note-math-prompt]。

### 36.【P1】自适应工具是不是让模型生成代码后随便执行？

**参考回答：**

工具方案要经过服务端 schema、底层能力和源码引用检查，并提供合成正常与边界测试；注册时重新检查和重跑测试，更新使用预期版本，连续实现错误可以停用，支持回滚。注册成功也不等于用户任务已经完成，还要实际调用验证。

生成 Python 需要独立 runner：后端发送有界 JSON，由 broker 创建一次性容器，限制网络、挂载、权限、CPU、内存、进程和时间。默认需单独部署配置。broker 持有 Docker 控制能力，是高权限受信任组件，不能说容器隔离能消灭宿主风险；生成代码和测试通过也不等于形式化正确。

**连续追问：** 模型自己写测试证明自己对，有什么盲区？工具注册在多实例下安全吗？runner 的 Docker socket 暴露给谁？

**源码入口：** [工具演化][tool-evolution]、[代码执行客户端][code-execution]、[runner broker][runner-broker]。

### 37.【P1】联网抓取怎样防 SSRF 和提示词注入？分页读取怎么保证同一版网页？

**参考回答：**

HTTP 路径只接受 HTTP/HTTPS，拒绝 URL 凭据，校验 DNS 返回地址并固定连接到已验证 IP，HTTPS 仍按原主机名做证书校验，重定向重新检查。这比仅用字符串检查 `localhost` 更完整。

长网页续读使用同一会话的不可变快照、页面 ID 和合法偏移，不能翻到第二页时重新抓一次网页再拼接；快照有 TTL 和容量限制，过期明确重新开始。网页内容作为数据处理，不能提升为工具权限或写入授权。提示词约束只是缓解注入，硬边界仍来自工具校验和写入确认。

**连续追问：** DNS 校验后地址变了怎么办？可选浏览器抓取和普通 HTTP 的安全边界完全一致吗？重启后快照还在吗？

**回答边界：** 快照属于进程内状态；HTTP 路径的防护不能自动证明其他抓取路径安全。

**源码入口：** [网页服务][web-service]、[固定 IP 的 HTTP 客户端][pinned-http]、[网页快照][web-page]。

### 38.【P1】代码库的 Agentic Search 与普通 RAG 有什么区别？

**参考回答：**

普通检索通常一次召回候选并组装上下文；代码 Agentic Search 允许模型先搜索，再根据结果选择片段继续读取，用实际读到的代码和行号支持结论。服务端固定代码范围，限制最多 6 轮模型、12 次工具和 90 秒，并核对输出证据是否来自已读取窗口。它适合“函数在哪里被调用”“某行为经过哪条链路”这类需要逐步缩小范围的问题。

多轮搜索的代价是模型调用、延迟和遗漏路径的风险。必须保留读取范围和依据，限定步数与预算。找到某段代码不代表运行时一定走这条路径；若要判断实际行为，还需结合入口、配置与测试。

**连续追问：** 如何防止模型编造文件或行号？为什么不把整个仓库一次塞进去？工具读到了实现就能证明功能正确吗？

**源码入口：** [代码 Agentic Search][code-agentic-search]、[代码索引][code-index]。

### 39.【P1】你还有“本地知识模型”，和 RAG 是一回事吗？

**参考回答：**

不是。RAG 在查询时检索外部资料；参数知识训练希望把资料影响写进模型参数，两者的更新成本、可追溯性和遗忘方式不同。项目里的本地知识模型是默认关闭、独立部署的实验能力，不能拿它代替已经可核对原文的检索链路，也不能宣称已经得到可靠的领域模型。

后端为符合条件的抽取正文记录带内容修订的 outbox，提交后异步投递，轮询可以处理已提交但未发送的记录。这个机制服务于实验训练投递，不代表整个项目的所有后台任务都采用同样的 outbox。

**连续追问：** 上传成功但训练服务宕机怎么办？删除资料能保证模型忘掉吗？闭卷评测与携带原文评测如何分开？

**源码入口：** [本地知识模型服务][knowledge-model]、[实验能力说明][knowledge-model-doc]。

## 五、前端编辑器与交互状态（40—45）

### 40.【P1】切换笔记不丢草稿，是把所有正文存在 localStorage 里吗？

**参考回答：**

不是。工作区给访问过的笔记稳定 key，通过 `v-for` 保持组件实例、`v-show` 切换显示，因此组件里的正文、编辑器和撤销历史继续存活。新草稿保存得到数据库 ID 后也保留原 key，避免被当成另一个实例重建。

`sessionStorage` 只记录已保存笔记的标签元数据，不保存未保存正文。因此保证的是同一页面生命周期内切换保留，刷新不能恢复未保存草稿。关闭标签和退出提醒能降低误操作风险，但不能当成持久化方案。

**连续追问：** 一直开 100 篇大笔记，内存怎么办？LRU 淘汰怎么保留脏草稿和撤销历史？

**源码入口：** [笔记工作区][note-workspace]、[标签状态][note-tabs]、[笔记编辑页][note-edit]。

### 41.【P1】隐藏的笔记实例还活着，Ctrl+S 和 AI 怎么避免同时作用于多篇？

**参考回答：**

组件存活不等于拥有操作权。快捷键先判断活动状态；AI 请求还核对活动编辑器、编辑器实例 `editorKey`、笔记 ID 和准备状态。异步保存或准备结束后再次确认归属，如果用户已经切换标签，就取消后续修改。请求超时和 AbortSignal 也避免迟到结果继续写入。

AI 操作前先刷新块编辑内容、检查输入法组合输入并等待保存，最多尝试三轮确认当前内容已稳定。它解决的是单页面的操作作用域，不能替代后端权限隔离或多人协作协议。

**连续追问：** 等待保存时切换标签会怎样？中文输入法还没选词时能否立即启动 AI 修改？

**源码入口：** [编辑页的活动状态与准备流程][note-edit]、[Agent 编辑请求][agent-note-edit]。

### 42.【P1】保存请求发出后继续输入，响应回来会不会错误清掉“未保存”？

**参考回答：**

保存时捕获这次提交的表单指纹，返回后把已保存快照更新成这次提交的内容。如果请求期间又有输入，当前表单与快照不同，仍保持 dirty；重复保存共享正在运行的保存任务，减少交错请求。

AI 写回通知到达时，如果本地已经有未保存修改，会保留草稿并进入冲突态，避免直接覆盖。要强调这是前端保护：普通后端保存仍没有版本条件，其他浏览器的旧内容保存不能仅靠当前页面的 dirty 解决。

**连续追问：** 标题和标签变化算不算 dirty？保存失败时快照怎么处理？两个浏览器怎样复现覆盖？

**源码入口：** [笔记保存、指纹与冲突处理][note-edit]、[普通更新入口][note-service]。

### 43.【P1】AI 修改直接 `setContent` 不就行了？你为什么还处理文档 diff？

**参考回答：**

整体替换容易干扰选区和撤销体验。AI 显式修改通过 ProseMirror 文档差异确定替换范围，用 transaction 更新局部，并映射选区；比较时忽略 Markdown 行号之类的辅助属性，避免前面插入一段就把后面所有节点判断成变化。`closeHistory` 把 AI 操作与手工输入拆成独立撤销事件。

项目不是完全不用 `setContent`：首次加载和部分源码同步仍会使用，并控制更新事件和历史标记。源码模式和块模式也不是一个统一撤销栈；不能说任意模式切换都能无损保留同一套历史。

**连续追问：** 表格和公式怎样保留结构？在标题中间插入块怎样处理？如何验证 Ctrl+Z 只撤回这一轮 AI 修改？

**源码入口：** [文档局部修改][editor-document-change]、[块编辑预览][block-preview]。

### 44.【P1】支持 HTML、公式、Mermaid 和外部资料，XSS 怎么防？

**参考回答：**

不同入口有不同处理：原文预览重建允许的标签与属性；Mermaid 使用 strict 配置、SVG 清理及 sandbox iframe，限制脚本、外链等能力；笔记外链也限制协议并使用安全打开方式。剪贴板清理 Office 噪音和大图，不等于完整的安全过滤。

当前不能声称所有 HTML 都统一经过 DOMPurify。部分 Markdown 渲染和 HTML 导出允许 HTML，导出入口没有看到通用 sanitizer，所以还需要统一安全策略、覆盖各渲染入口并测试危险协议、SVG、事件属性和导出文件。不能因某一处用了安全库就宣布整个富文本链路安全。

**连续追问：** DOMPurify 用在哪里？导出的 HTML 离开应用后靠什么限制？允许样式是否也有风险？

**源码入口：** [原文 HTML 处理][original-preview]、[Mermaid 预览][mermaid-preview]、[HTML 导出][md-to-html]。

### 45.【P2】编辑器这么重，响应式和性能怎么保证？不要只说用了 Vue。

**参考回答：**

页面按路由懒加载，Element Plus 按需引入，列表分页减少一次渲染量。编辑器布局会根据容器实际宽度和 ResizeObserver 调整，空间足够时停靠大纲，较窄时用抽屉；标签还有 tab 语义、活动状态和键盘导航。

代价是为了保留草稿而长期保留编辑器实例，内存会随打开笔记增长。实际性能应测首次可交互、长文输入延迟、表格操作和多标签内存，不能用“构建成功”或注释中的历史数据证明体验良好。真实输入法、剪贴板与屏幕阅读器仍需要浏览器和人工验收。

**连续追问：** 你会怎么测输入卡顿？为什么只看窗口宽度不够？深色模式是否覆盖弹窗和浮层？

**源码入口：** [前端构建配置][vite-config]、[笔记布局][note-edit]、[标签键盘交互][note-tabs-view]。

## 六、数据库、并发与 API（46—53）

### 46.【P0】数据库怎么分？哪些是原始数据，哪些可以重建？

**参考回答：**

原始内容主要是笔记、速查卡、上传文件及元数据，配套分类、标签和文件夹；会话事件与待确认操作保存交互和执行状态。分块、向量、图谱、社区和 Wiki 属于从来源产生的知识结构，重建能力与来源依赖需要单独维护。

“可以从来源重新生成”不代表应该随意删除：重建有成本，生成内容也未必逐字一致。备份至少要把数据库、上传文件、技能和配置作为对应版本的一组。仅备份数据库而丢失上传文件，资料记录还在，原文读取依然会失败。

**连续追问：** Wiki 有人工修改时还能当作随时可丢的缓存吗？向量丢了和原文件丢了，恢复成本有何不同？

**源码入口：** [持久化实体目录][entities]、[迁移目录][migrations]、[启动器备份][cli-manager]。

### 47.【P1】MyBatis-Plus 帮你封装了什么？项目踩过哪些真实的 ORM 问题？

**参考回答：**

它封装基本 CRUD 和分页，复杂关系仍需要自己设计查询。列表查询笔记时不读取大段正文，直接用预存摘要；分类批量加载、标签一次查询后按笔记分组，避免逐条查询形成 N+1。分页有每页上限。

另一个细节是 null 语义。默认 `updateById` 会忽略 null，想清空分类、密钥或模型参数时，需要显式 `set`。标签的 null 表示本次不涉及，空数组表示明确清空；混淆两者会导致 AI 局部更新误删标签，或用户清空后刷新又恢复旧值。

**连续追问：** null、字段省略、空数组各是什么意思？全文 `LIKE '%词%'` 能不能靠普通 B-Tree 高效查？

**源码入口：** [笔记列表与更新][note-service]、[模型档案更新][model-profile]、[分页配置][mybatis-config]。

### 48.【P0】方法加 `@Transactional`，上传文件和数据库就一起原子提交了吗？

**参考回答：**

不会，数据库事务不能自动回滚普通磁盘写入。当前上传先保存文件、写元数据并抽取正文，抽取失败用状态与原因体现；如果文件写入后数据库失败，仍可能有孤儿文件。删除时把文件移入回收站是恢复友好的处理，但移动也不属于 MySQL 事务，异常窗口仍然存在。

改进可以使用暂存文件、明确状态、提交后转正与补偿扫描，保证重试幂等；删除则用墓碑或持久化待清理任务。应先定义“哪些状态算用户可见成功”，不能仅靠注解承诺跨资源原子性。

**连续追问：** 数据库回滚但文件已移动怎么办？进程在落盘后崩溃怎么清理？为什么抽取失败不一定要让上传失败？

**源码入口：** [文件上传、抽取与删除][file-storage]、[抽取状态][document-text]。

### 49.【P1】目录移动怎样防环？文件夹和内容混排后怎么分页？

**参考回答：**

后端修改目录时在事务内锁定同类目录，沿目标父链检查自己、后代和重复访问，防止出现环；同位置同名还要靠数据库唯一约束兜底。批量移动先校验全部 ID、锁定目标和内容，再统一修改，避免部分成功。

前端目录与内容是两类行：当前子目录显示在内容前，不占内容分页名额，也不参与内容批量选择。根目录显示顶级目录和未收纳内容，“全部”是独立入口。现在锁同类目录的粒度较粗，个人规模容易推理，目录量和并发增大后需要测锁竞争并细化设计。

**连续追问：** A 移到 B、B 同时移到 A 会怎样？删除目录会删掉笔记吗？锁定顺序是否可能死锁？

**回答边界：** 当前有子目录时拒绝直接删除；删除叶子目录把内容移回未收纳，不等于删除内容。不承诺任何锁组合都绝不会死锁。

**源码入口：** [目录事务][folder-service]、[前端目录语义][folder-explorer]、[迁移并发用例][migration-test]。

### 50.【P0】为什么用 Flyway？旧库怎么接管？升级失败能不能换回旧镜像？

**参考回答：**

数据库结构由版本迁移管理，当前仓库包含 V1 到 V7。旧的非空数据库没有迁移历史时，先检查 LearnHub 核心表签名，只允许以版本 0 接管，避免跳过 V1 的兼容升级；无关数据库不能直接接管。已发布的历史迁移保持不变，用新版本迁移继续演进。

启动会校验迁移历史，错误或未来版本会阻断运行。MySQL DDL 可能隐式提交，不能把整次迁移视为可整体回滚的事务。如果升级已修改数据库，单独换回旧镜像未必兼容，需要恢复匹配版本的备份。

**连续追问：** 为什么不是 baseline 1？迁移做了一半怎么办？`repair` 是不是万能修复？怎样在不碰用户数据库的前提下测试？

**源码入口：** [迁移配置][migration-config]、[版本脚本][migrations]、[Testcontainers 迁移测试][migration-test]。

### 51.【P0】现在能直接变成多用户在线产品吗？权限隔离在哪里？

**参考回答：**

当前主要是个人自托管应用，没有看到完整的用户认证、租户隔离和资源级授权体系，不能直接宣传成成熟的多租户 SaaS。发布部署默认绑定回环地址，有助于缩小默认暴露面，但这不是用户权限系统。

如果做多人产品，需要先定义用户、空间和资源归属，把鉴权覆盖到正文、原文件下载、检索、图谱、会话、待确认操作、设置和后台任务。向量与缓存也要带隔离范围。不能只加登录页，或者只在前端隐藏按钮。

**连续追问：** 换个 noteId 能不能读别人的笔记？检索结果和 Agent 工具是否也需要权限过滤？模型配置应该谁能看？

**源码入口：** [后端依赖][backend-pom]、[控制器目录][controllers]、[发布部署模板][release-compose]。

### 52.【P0】API Key 不回显就安全了吗？你认真检查过所有设置入口吗？

**参考回答：**

不回显只是一个环节。模型档案视图有密钥提示掩码，更新用 `__KEEP__` 表达保留原值，SQL 标准输出也有脱敏；但当前存储方式不能说成数据库加密或 KMS 托管。

更具体的缺口是：旧设置快照仍有 `wikiApiKey` 的生效值；`/settings/effective` 根据请求键返回配置来源与生效值，没有对敏感键做完备过滤。因此不能笼统说“所有密钥永不回传”。我会先统一敏感字段分类，对读取入口默认拒绝明文输出，再处理日志、备份权限与静态加密。

**连续追问：** 掩码在哪层做？诊断接口会不会绕过？API Key 是不是可能被发送到用户配置的任意服务地址？

**回答边界：** 这是当前源码可见的改进项，本文没有修改这些入口，也没有读取任何实际密钥。

**源码入口：** [档案视图][model-profile]、[设置快照][settings-service]、[设置诊断接口][settings-controller]、[SQL 日志处理][mybatis-config]。

### 53.【P1】异常和文件限制怎么设计？业务 code=400 就等于 HTTP 400 吗？

**参考回答：**

不一定。当前异常处理会返回统一 `Result`，部分参数错误只设置响应体的业务码，没有对应设置 HTTP 400；通用异常则设置 HTTP 500。客户端、代理和监控如果只看 HTTP 状态，可能得到与业务码不同的判断，后续应统一协议。

文件链路还要区分接收大小、格式识别、正文抽取长度和页数。当前抽取有正文与 PDF 页数上限，扫描 PDF 也不能仅靠文本提取得到正文。状态与失败原因要告诉用户；向外返回异常信息时还应避免暴露内部路径或敏感配置。

**连续追问：** HTTP 200 里的失败会影响重试吗？50MB 文件是否代表解析内存最多 50MB？截断正文怎样提示？

**源码入口：** [异常处理][exception-handler]、[正文抽取限制][document-text]、[代理请求大小配置][nginx-config]。

## 七、部署、质量与项目可信度（54—60）

### 54.【P1】开发环境正常，部署后刷新 404、PDF 空白、Agent 超时，分别查哪？

**参考回答：**

刷新子路由 404 先查 history 路由回退；PDF worker 空白查 `.mjs` 的 MIME 类型和资源请求；长 Agent 请求超时查浏览器、nginx、后端和模型的各层期限，SSE 还要检查缓冲与心跳。项目 nginx 对这些部署细节有相应配置。

后端容器重建后地址可能变化，反向代理使用 Docker DNS 和请求时解析，避免依赖启动时解析出的旧地址。静态哈希资源长缓存，HTML 入口重新校验，减少升级后入口仍引用旧 chunk 的问题。每种症状要用网络请求和服务日志验证，不靠反复重启猜测。

**连续追问：** 为什么 `index.html` 不跟带 hash 的 JS 一样缓存？前端容器能访问 `localhost:18080` 吗？

**源码入口：** [nginx 配置][nginx-config]、[Vite 代理][vite-config]、[部署模板][release-compose]。

### 55.【P1】npx 更新就是 pull 镜像再重启吗？中途断电怎么办？

**参考回答：**

启动器把实例状态与版本保存在独立目录，`start` 启动已安装版本，`update` 才切目标版本。升级先拉镜像，再停止前后端写入，备份数据库、上传文件、技能和配置，启动目标服务并等待健康检查，通过后才提交新版本状态。

过程有操作锁和持久化 pending 阶段，失败保留备份和进度，允许按同一目标版本重试；不会自动把数据库降回去。它采用有停机窗口的更新，不能说是零停机蓝绿发布。备份一致性的前提也包括没有绕过应用直接写数据库。

**连续追问：** 拉镜像失败是否影响旧版本？健康检查失败会不会把已安装版本写成新版本？半份备份会不会覆盖好备份？

**源码入口：** [启动器 `updateInternal` 与 `backupFiles`][cli-manager]、[启动器故障测试][cli-manager-test]。

### 56.【P0】你写了哪些有价值的测试？前端测试是不是只搜字符串？

**参考回答：**

后端已有检索融合、索引空间与新鲜度、邻居补齐、Wiki 依赖、图谱证据、循环预算、确认状态和笔记编辑等测试。迁移测试用独立 MySQL Testcontainers，覆盖新旧库升级、历史校验、未来版本拒绝，以及目录并发，需显式启用。

前端使用 Node 测试运行器，部分行为测试会编译实际 Vue 组件并用自定义 renderer 验证多标签实例保留和草稿行为，还有编辑事务、SSE 拆包等测试。它们不能替代真实浏览器 E2E，更不能证明真实输入法、剪贴板或屏幕阅读器都已通过。测试的价值是验证失败边界，而不只是增加数量。

**连续追问：** 不启动真实模型能测什么？mock 会不会掩盖协议差异？普通 `mvn verify` 是否默认运行迁移测试？

**回答边界：** 普通验证默认不启用迁移集成测试；本文只核对源码和已有用例，没有执行整套测试或真实模型评测。

**源码入口：** [后端测试目录][backend-tests]、[标签行为测试][workspace-behavior-test]、[编辑行为测试][editor-tabs-test]、[CI][release-workflow]。

### 57.【P0】如果给你一天，怎样证明图谱、Wiki 和 rerank 确实有用？

**参考回答：**

先建立固定的合成语料、问题和来源标注，覆盖精确术语、语义改写、多来源综合、长段落边界、版本失效和无答案问题。把用于调参的问题与保留评测问题分开，再做关键词、向量、融合、重排、图谱和 Wiki 的开关对照。

统一候选数量与上下文预算，记录来源命中、排序、证据覆盖、答案支持、错误引用、拒答、延迟及调用成本。无答案问题单独看误召回，生成答案需要人工抽查。只有固定输入、配置和指标后，才能报告增益；少量内置示例不能代表所有领域。

**连续追问：** 检索指标涨了，答案反而变差怎么办？为什么调参集不能兼做最终结果？如何区分更好检索与更大上下文的收益？

**源码入口：** [请求内合成诊断][diagnostic]、[检索评测][rag-eval]、[答案评测][answer-eval]。

### 58.【P0】资料量放大 100 倍，你先优化哪里？别先报组件名字。

**参考回答：**

先分别测来源读取、分块复用校验、关键词扫描、向量扫描、重排和上下文组装的耗时与内存。当前普通检索读取全部来源、默认向量全扫描，都是明确的扩展瓶颈；后台生成和多个聊天的子任务也会放大外部模型并发。

我会优先把候选定位从全量来源读取改成索引驱动，再评估全文索引与 ANN；同时建立全局模型并发与优先级控制。之后再把后台任务的领取和状态改成支持租约与幂等的持久机制。多副本之前要先解决会话互斥、任务重复执行和缓存隔离，不能只扩容容器。

**连续追问：** 为什么先优化来源读取而不是 Java GC？切到 Milvus 后哪些瓶颈仍在？怎么定义允许的降级延迟？

**回答边界：** 这是扩展方案，不是已完成的百万级容量验证；没有压测就不给 QPS 承诺。

### 59.【P0】用户说“资料上传了，但 Agent 瞎答”，你怎么定位？

**参考回答：**

先拿一个可复现的合成案例，按链路检查：资料是否成功抽正文、内容是否被截断、分块和嵌入空间是否有效、查询实际启用了什么通道、候选是否命中正确来源、融合与重排是否丢掉证据、上下文预算是否挤掉关键段落，最后看模型有没有按证据回答。

如果源内容都没有进入上下文，优先修抽取、索引或检索；如果正确证据已完整进入，才看提示、模型与答案核验。保存请求与实际上下文、阶段耗时和状态，比只保存最终回答更有诊断价值。抓取日志和案例时继续使用合成数据并脱敏。

**连续追问：** 命中来源但答错可能是什么原因？检验区为什么不直接显示一个“准确率”？如何判断是模型错还是证据不足？

**源码入口：** [检索诊断][diagnostic]、[上下文组装][retrieval-context]、[执行进度][agent]。

### 60.【P0】代码这么多，你亲自做了什么？AI 写的部分你怎么证明自己掌握？

**参考回答模板：**

“我主要负责【实际模块】，亲自做的决策是【一个具体取舍】，落实在【具体入口和关键方法】。我使用 AI 辅助【真实工作范围】，由我核对【约束、实现与验证】。例如【你确实经历的一个问题】，我先用【复现方法】定位到【原因】，再通过【修改与回归】验证。当前还留有【真实边界】，下一步我会【具体改进】。”

不能把“仓库有这个功能”当成“这个模块我独立设计并实现”。最有说服力的是当场走读一条调用链，解释一次失败路径，修改一个小需求并设计验证。遇到不熟悉的模块，说明自己的参与范围，再说明如何定位，而不是背整份文档。

**连续追问：** 现在打开源码，指出入口。删掉这层校验会怎样？现场补一个并发回归用例，你怎么写？

## 八、最值得练的五组连续追问

### 路线 A：检索有没有真做明白

**面试官：** 为什么不用固定长度切块？→ 保护块过长怎么嵌入？→ 关键词是 BM25 吗？→ RRF 分数能当概率吗？→ 来源变更后旧向量如何过滤？→ 你说效果好，标注和指标在哪里？

**回答落点：** 07、09—13、16、18。能讲清实现，也能承认字符预算、全量扫描和指标口径的局限。

### 路线 B：写入一致性与幂等

**面试官：** AI 预览后我手工改了正文呢？→ hash 检查与更新之间怎么办？→ 确认点两次呢？→ 副作用完成但记成功之前宕机呢？→ 旧浏览器普通保存还能覆盖吗？

**回答落点：** 32—34、42、48。用时序说明行锁、条件状态迁移，以及没有被覆盖的失败窗口。

### 路线 C：异步任务是否可靠

**面试官：** 关页面为什么继续？→ 杀后端进程还能继续吗？→ 再部署一个实例呢？→ 队列满了怎么办？→ 重试是否重新获得预算？

**回答落点：** 24、27、29—30。明确通知通道、执行线程、持久化状态、任务恢复是不同能力。

### 路线 D：是不是只会调用模型

**面试官：** Wiki 是幻觉循环吗？→ 图谱的关系错了怎么办？→ 工具结果怎么核验？→ 自动生成的代码在哪跑？→ 网页命令模型泄露资料怎么办？

**回答落点：** 19—23、28、36—37。用原文证据、工具校验、确认流程与运行隔离回答，不靠提示词包办安全。

### 路线 E：能不能真正交付

**面试官：** 升级会丢资料吗？→ MySQL DDL 能回滚吗？→ 只换旧镜像行不行？→ 测试用的是我的数据库吗？→ 密钥诊断接口会不会泄露？

**回答落点：** 46、48、50、52、54—56。讲具体状态、备份与边界，不用“Docker 化了所以可靠”作答。

## 九、面试里不要说满的十句话

| 不宜直接说 | 更准确的说法 |
| --- | --- |
| “关键词用的是 BM25。” | 当前融合链路是应用内启发式词面评分，后续可评估全文索引。 |
| “MySQL 已经支持高性能 ANN。” | 默认是应用侧向量全扫描，Milvus 是可选后端。 |
| “RRF 0.8 表示八成正确。” | RRF 是名次融合分数，需要另外评价答案支持度。 |
| “所有编辑都有乐观锁。” | 定向编辑有 hash 与行锁，普通保存尚未统一版本条件。 |
| “任务断线、重启都能断点续跑。” | 断开通知连接不取消本轮；进程重启不能保证恢复执行点。 |
| “确认操作保证 exactly-once。” | 条件领取防重复确认，副作用与状态落库间仍有崩溃窗口。 |
| “多开几个容器就能水平扩容。” | 会话互斥和后台维护仍有单进程边界，需先补分布式任务语义。 |
| “未保存草稿刷新也能找回。” | 当前只保证页面生命周期内切换保留，标签元数据不等于草稿备份。 |
| “密钥和所有 HTML 都已统一安全处理。” | 部分入口有掩码和隔离，设置读取与 HTML 导出仍有具体缺口。 |
| “准确率提升了很多，系统支持高并发。” | 只报告确实测过、注明语料配置与指标口径的结果；未测的不报数字。 |

## 十、源码复习索引与验证方式

以下是定位入口，方法名和实现以当前源码为准。面试前至少亲自读通其中两条完整链路。

| 方向 | 优先打开的文件 |
| --- | --- |
| 检索全链路 | [KnowledgeRetrievalService][retrieval]：`searchPipeline`、`currentPassages`、`keyword`、`fuse`。 |
| 分块与嵌入 | [TextChunker][text-chunker]、[KnowledgeChunkingService][chunking]、[SemanticChunker][semantic-chunker]、[EmbeddingClient][embedding]。 |
| 索引一致性 | [VectorIndexService][vector-index]：`persistedChunks`、`rebuild`、`publishPrepared`、`assertCurrent`。 |
| 上下文与原文 | [RetrievalContextService][retrieval-context]、[GraphEvidenceService][graph-evidence]、[WikiDependencyService][wiki-dependency]。 |
| 维护与社区 | [KnowledgeMaintenanceService][maintenance]、[KgCommunityService][community]、[KgCommunitySummaryService][community-summary]。 |
| Agent 循环 | [AgentService][agent]、[AgentLoopState][agent-loop-state]、[AgentContextMemory][agent-context-memory]。 |
| 写确认与版本 | [AgentSessionService][agent-session]：`claimAction`、`resolveAction`；[NoteService][note-service]：`editContent`、`update`；[NoteContentEditor][note-content-editor]。 |
| 编辑器 | [NoteWorkspace][note-workspace]、[useNoteTabs][note-tabs]、[NoteEdit][note-edit]、[editorDocumentChange][editor-document-change]。 |
| 发布与恢复 | [manager.mjs][cli-manager]、[迁移配置][migration-config]、[迁移测试][migration-test]、[CI][release-workflow]。 |

如果需要重新验证代码，可以在独立的合成环境运行以下命令。它们是复习和验证入口，**不表示本次编写文档时已经执行通过**。

```powershell
# 后端常规测试与打包；迁移集成测试默认不包含在内
mvn -B -f backend/pom.xml verify

# 前端依赖、行为测试与构建
npm --prefix frontend ci
Push-Location frontend
try {
    node --test src/*/*.test.js
    npm run build
} finally {
    Pop-Location
}

# 启动器测试
npm --prefix cli test
```

迁移测试按 [数据库迁移说明](database-migrations.md) 显式启用 Testcontainers，使用一次性 MySQL 容器；不要用个人数据库作为复习测试数据。

建议面试前准备三样证据：一份合成数据演示、一张你亲自画出的关键时序图，以及一组可复现的测试或评测结果。回答时可以明确说“已实现”“当前边界”“计划改进”，但不要把三者混在一起。

[file-storage]: ../backend/src/main/java/org/dyh/learnhub/service/FileStorageService.java
[document-text]: ../backend/src/main/java/org/dyh/learnhub/service/DocumentTextService.java
[retrieval]: ../backend/src/main/java/org/dyh/learnhub/service/KnowledgeRetrievalService.java
[maintenance]: ../backend/src/main/java/org/dyh/learnhub/service/KnowledgeMaintenanceService.java
[maintenance-operations]: ../backend/src/main/java/org/dyh/learnhub/service/KnowledgeMaintenanceOperations.java
[chunking]: ../backend/src/main/java/org/dyh/learnhub/service/KnowledgeChunkingService.java
[text-chunker]: ../backend/src/main/java/org/dyh/learnhub/service/TextChunker.java
[semantic-chunker]: ../backend/src/main/java/org/dyh/learnhub/service/SemanticChunker.java
[kb-settings]: ../backend/src/main/java/org/dyh/learnhub/service/KnowledgeBaseSettings.java
[migration-v7]: ../backend/src/main/resources/db/migration/V7__preserve_atomic_knowledge_chunks.sql
[vector-index]: ../backend/src/main/java/org/dyh/learnhub/service/VectorIndexService.java
[kb-chunk]: ../backend/src/main/java/org/dyh/learnhub/entity/KbChunk.java
[index-state]: ../backend/src/main/java/org/dyh/learnhub/entity/KbIndexState.java
[embedding]: ../backend/src/main/java/org/dyh/learnhub/ai/EmbeddingClient.java
[note-service]: ../backend/src/main/java/org/dyh/learnhub/service/NoteService.java
[vector-store]: ../backend/src/main/java/org/dyh/learnhub/service/vector/VectorStore.java
[mysql-vector]: ../backend/src/main/java/org/dyh/learnhub/service/vector/MysqlVectorStore.java
[milvus-vector]: ../backend/src/main/java/org/dyh/learnhub/service/vector/MilvusVectorStore.java
[rerank]: ../backend/src/main/java/org/dyh/learnhub/service/RerankService.java
[llm-rerank]: ../backend/src/main/java/org/dyh/learnhub/service/rerank/LlmListwiseReranker.java
[cross-rerank]: ../backend/src/main/java/org/dyh/learnhub/service/rerank/CrossEncoderReranker.java
[retrieval-context]: ../backend/src/main/java/org/dyh/learnhub/service/RetrievalContextService.java
[structure-excerpt]: ../backend/src/main/java/org/dyh/learnhub/service/StructureAwareExcerpt.java
[rag-eval]: ../backend/src/main/java/org/dyh/learnhub/service/RagEvalService.java
[diagnostic]: ../backend/src/main/java/org/dyh/learnhub/service/KnowledgeDiagnosticService.java
[answer-eval]: ../backend/src/main/java/org/dyh/learnhub/service/RagAnswerEvalService.java
[graph-evidence]: ../backend/src/main/java/org/dyh/learnhub/service/GraphEvidenceService.java
[graphrag]: ../backend/src/main/java/org/dyh/learnhub/service/GraphRagService.java
[triple-extractor]: ../backend/src/main/java/org/dyh/learnhub/service/TripleExtractor.java
[community]: ../backend/src/main/java/org/dyh/learnhub/service/KgCommunityService.java
[leiden]: ../backend/src/main/java/org/dyh/learnhub/service/LeidenCommunity.java
[community-summary]: ../backend/src/main/java/org/dyh/learnhub/service/KgCommunitySummaryService.java
[wiki-retrieval]: ../backend/src/main/java/org/dyh/learnhub/service/WikiRetrievalService.java
[wiki-dependency]: ../backend/src/main/java/org/dyh/learnhub/service/WikiDependencyService.java
[tool-evidence]: ../backend/src/main/java/org/dyh/learnhub/ai/AgentToolEvidence.java
[agent]: ../backend/src/main/java/org/dyh/learnhub/ai/AgentService.java
[agent-loop-state]: ../backend/src/main/java/org/dyh/learnhub/ai/AgentLoopState.java
[agent-session]: ../backend/src/main/java/org/dyh/learnhub/service/AgentSessionService.java
[grounding]: ../backend/src/main/java/org/dyh/learnhub/service/GroundingService.java
[ai-controller]: ../backend/src/main/java/org/dyh/learnhub/controller/AiController.java
[chat-stream]: ../frontend/src/utils/chatStream.js
[agent-panel]: ../frontend/src/components/AgentPanel.vue
[agent-loop-context]: ../backend/src/main/java/org/dyh/learnhub/ai/AgentLoopContext.java
[agent-context-memory]: ../backend/src/main/java/org/dyh/learnhub/ai/AgentContextMemory.java
[note-content-editor]: ../backend/src/main/java/org/dyh/learnhub/service/NoteContentEditor.java
[note-mapper]: ../backend/src/main/java/org/dyh/learnhub/mapper/NoteMapper.java
[long-note-agents]: ../backend/src/main/java/org/dyh/learnhub/ai/LongNoteSubAgents.java
[note-math-prompt]: ../backend/src/main/java/org/dyh/learnhub/ai/NoteMathPrompt.java
[tool-evolution]: ../backend/src/main/java/org/dyh/learnhub/ai/ToolEvolutionService.java
[code-execution]: ../backend/src/main/java/org/dyh/learnhub/ai/CodeExecutionService.java
[runner-broker]: ../tools/code-runner/broker.py
[web-service]: ../backend/src/main/java/org/dyh/learnhub/service/WebService.java
[pinned-http]: ../backend/src/main/java/org/dyh/learnhub/service/PinnedHttpClient.java
[web-page]: ../backend/src/main/java/org/dyh/learnhub/service/WebPageService.java
[code-agentic-search]: ../backend/src/main/java/org/dyh/learnhub/service/CodeAgenticSearchService.java
[code-index]: ../backend/src/main/java/org/dyh/learnhub/service/CodeIndexService.java
[knowledge-model]: ../backend/src/main/java/org/dyh/learnhub/service/KnowledgeModelService.java
[knowledge-model-doc]: knowledge-model.md
[note-workspace]: ../frontend/src/components/NoteWorkspace.vue
[note-tabs]: ../frontend/src/composables/useNoteTabs.js
[note-edit]: ../frontend/src/views/NoteEdit.vue
[agent-note-edit]: ../frontend/src/utils/agentNoteEdit.js
[editor-document-change]: ../frontend/src/utils/editorDocumentChange.js
[block-preview]: ../frontend/src/components/BlockPreview.vue
[original-preview]: ../frontend/src/utils/originalPreviewHtml.js
[mermaid-preview]: ../frontend/src/utils/mermaidPreview.js
[md-to-html]: ../frontend/src/utils/mdToHtml.js
[vite-config]: ../frontend/vite.config.js
[note-tabs-view]: ../frontend/src/components/NoteTabs.vue
[entities]: ../backend/src/main/java/org/dyh/learnhub/entity/
[migrations]: ../backend/src/main/resources/db/migration/
[cli-manager]: ../cli/src/manager.mjs
[model-profile]: ../backend/src/main/java/org/dyh/learnhub/service/ModelProfileService.java
[mybatis-config]: ../backend/src/main/java/org/dyh/learnhub/config/MybatisPlusConfig.java
[folder-service]: ../backend/src/main/java/org/dyh/learnhub/service/FolderService.java
[folder-explorer]: ../frontend/src/utils/folderExplorer.js
[migration-test]: ../backend/src/test/java/org/dyh/learnhub/config/DatabaseMigrationTest.java
[migration-config]: ../backend/src/main/java/org/dyh/learnhub/config/DatabaseMigrationConfiguration.java
[backend-pom]: ../backend/pom.xml
[controllers]: ../backend/src/main/java/org/dyh/learnhub/controller/
[release-compose]: ../deploy/docker-compose.release.yml
[settings-service]: ../backend/src/main/java/org/dyh/learnhub/service/SettingsService.java
[settings-controller]: ../backend/src/main/java/org/dyh/learnhub/controller/SettingsController.java
[exception-handler]: ../backend/src/main/java/org/dyh/learnhub/common/GlobalExceptionHandler.java
[nginx-config]: ../deploy/nginx.conf
[cli-manager-test]: ../cli/test/manager.test.mjs
[backend-tests]: ../backend/src/test/
[workspace-behavior-test]: ../frontend/src/composables/noteWorkspaceBehavior.test.js
[editor-tabs-test]: ../frontend/src/composables/noteEditorTabsBehavior.test.js
[release-workflow]: ../.github/workflows/release.yml
