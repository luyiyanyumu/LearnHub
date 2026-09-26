# 知识图谱的实现逻辑（按五层方法论落地）

这份文档回答一个问题：**learn-hub 的知识图谱是按什么思路做的，哪些地方刻意没按"标准做法"来，为什么。**

核心立场和原始方法论一致：知识图谱的价值不是"存一堆三元组"，而是**把非结构化信息转成机器可计算的结构化关系**，
并且能回答"没有直接写出来、但一定成立"的事实。所以判断一个图谱做得好不好，只看三件事：
抽取出来的关系对不对、同一个概念有没有被拆成多个点、能不能推出隐含事实。

---

## 0. 起点：原来的"图谱"其实不是知识图谱

改造前实测（`kg_edge` 全表）：

```
note  2 → ref  3   related       Spring Boot 注解与请求映射
note  5 → ref  4   prerequisite  Java基础含==与equals
note  5 → ref  1   prerequisite  Java基础含字符串类区别
ref   4 → ref  1   contrast      都涉及String常量池与比较
```

8 条边，**全是"文档 ↔ 文档"**（笔记↔速查卡），一条概念三元组都没有；
25 个 wiki 实体页存在但不在图里；没有节点表、没有实体 ID、没有推理、检索也完全不用图。

那是一个**相似度图**（"这两篇记录相关"），和知识图谱是两种东西。下面是按五层补上的部分。

---

## 1. 第一层：数据获取与预处理

| 子任务 | 状态 | 在哪 |
| --- | --- | --- |
| 结构化 / 半结构化 / 非结构化入库 | ✅ 已有 | 资料库：PDFBox / POI 抽正文；笔记、速查卡本身就是结构化字段 |
| 分块 | ✅ 已有 | `TextChunker`（800 字 / 120 重叠）——向量索引与图谱共用同一批源 |
| 分句 | ⚠️ 简化 | 抽取器按**段落块**（`TripleExtractor.split`）送素材，要求模型抄**整句原文**当证据；不单独做分句器 |
| 分词 | ➖ 不需要 | 中文没有空格分词需求；实体识别走"名字/别名匹配"，不依赖分词 |
| 指代消解 | ❌ 刻意不做 | 见下 |

**为什么不做指代消解**：标准做法要把"他/它/该注解"还原成具体实体，需要专门模型或 coref 工具链。
这里的替代方案是**证据约束**：要求模型输出的 `evidence` 必须是素材里的原话（`containsLoose` 校验，
对不上就把证据置空）。**不还原指代，而是拒绝没有明确主语的句子** —— 效果是少抽几条，
但不会把"它"错认成另一个实体。对个人知识库来说，"少而准"比"多而错"重要。

---

## 2. 第二层：信息抽取（图谱的编译器）

这是整件事的核心，也是投入最多的一层。三个子任务**一次做完**（联合抽取，不是先 NER 再 RE）：

| 子任务 | 实现 | 说明 |
| --- | --- | --- |
| NER | `TripleExtractor` 一次调用里同时产出 head/tail | 目标是技术概念（工具/语言/命令/术语），不是人名地名 |
| RE | 同一次调用产出 `relation` | 关系**限定在封闭词表**里（见第三层本体） |
| 实体链接与消歧 | `EntityLinker` + `KgGraphService.upsertEntity` | 归一化 → 别名 → 规范 id |

**为什么联合抽取**：分两步做，NER 认错的名字会直接把 RE 带偏（误差传播），而且两次调用各付一次 token。

### 三道校验（每条不过就丢弃）

1. **关系必须在本体里**：`KgOntology.canonical` 认不出来就丢，**绝不兜底成 `related_to`** ——
   兜底的后果是图里全是零信息的"相关"虚线，既不能推理也不能按关系过滤。
2. **头尾必须是像实体的短名词**：长度 ≤ 40、至少含一个汉字或字母、不是纯数字、
   **不含句读**（`plausible`）。最后一条是实测加的：模型会给"堆、栈、方法区"这种整句答案。
3. **必须带原文证据**：没有证据的三元组不进图。这是防幻觉最有效的一道 ——
   模型知道要抄原文，就不会凭"常识"编关系。

### 实测结果（2026-09-21，素材 12 条）

```
素材 12 条 → 抽取 53 条三元组 → 实体 86 个 → 推理出 11 条隐含事实 → 向量化 79/86
耗时 32 秒，模型 deepseek-flash（可在设置里改成本地）
```

抽出来的事实抽样（都带来源）：

```
Spring Boot -是一种-> Spring 项目        资料#2
DI          -是一种-> IoC 的实现方式      资料#2
Spring MVC  -属于->   Spring 体系         资料#2
Core Container / Web 模块 / Data Access/Integration / Test 模块 -属于-> Spring 架构   资料#2
JVM -属于-> JRE                          笔记#5
JRE -属于-> JDK                          笔记#5
JVM -易混-> JRE   JRE -易混-> JDK         笔记#5
GC  -用于-> 自动释放内存                   笔记#5
Tools / Memory / Planning -属于-> Agent   笔记#44
```

其中 `JVM→JRE→JDK` 这组来自 PDF 的架构图与小节，`@SpringBootConfiguration -相关-> @SpringBootApplication`、
`git checkout -易混-> git switch`、`StringBuffer -易混-> StringBuilder` 来自速查卡。

### 实体消歧：踩过的坑

归一化必须**先抽括注、再删标点**。第一版写反了：NFKC 会把 `（` 折成 `(`，
随后的"去两端标点"把收尾的 `)` 一起吃掉 → `DeepSeek Harness（dsh）` 的括注结构没了、别名一个都抽不出来，
同一概念会因写法不同长出三四个节点。

另外**括注不参与身份判定**（单测 `parenthesisIsNotPartOfIdentity` 锁住）：
`DeepSeek Harness（dsh）` 与 `DeepSeek Harness` 必须是同一个点，`dsh` 进别名表。
`git` / `Git` / `Ｇit` / `" Git "` 同理归一到 `git`（实测：86 个节点归一化后**零重复**）。

**不做自动模糊合并**：合并是**不可逆**的破坏性操作。相似度只用来**提示**，合并要人点：
```
StringBuilder ≈ StringBuffer      (0.77)
Spring 架构  ≈ Spring 体系 ≈ Spring 项目 (0.75)
ReAct        ≈ ReAct 文本解析      (0.6)
```
注意第一行：`StringBuilder` 和 `StringBuffer` 是**两个不同的类**，但字面编辑距离很近。
这种只有语义才能分辨的情况，正是"只提示、不自动合并"的理由。

**节点粒度 ≠ 页面粒度**：wiki 实体页有"同一工具的子命令不要各建一页"的规则，
但图谱**需要** `git checkout`、`git switch` 各成一个节点 —— 否则 `checkout 易混 switch` 这条关系就无处可挂。

---

## 3. 第三层：知识融合与存储

### 本体（Ontology）

`KgOntology` 定义一张**刻意做小的**封闭关系词表，每条关系带代数属性：

| id | 标签 | 传递 | 对称 | 判据 |
| --- | --- | --- | --- | --- |
| `is_a` | 是一种 | ✅ | ❌ | A 是 B 的一个种类/实现 |
| `part_of` | 属于 | ✅ | ❌ | A 是 B 的组成部分或环节 |
| `prerequisite` | 前置知识 | ✅ | ❌ | 要理解 A 必须先掌握 B |
| `used_for` | 用于 | ❌ | ❌ | A 是用来做 B 的手段 |
| `contrast_with` | 易混 | ❌ | ✅ | 容易混为一谈，需对比着记 |
| `related_to` | 相关 | ❌ | ✅ | 兜底（能归到具体关系时不要用） |

关系的自由文本写法（`instance_of` / `属于` / `包含于` / `A 与 B 易混…`）统一由 `canonical` 归一。
**关系词表越小，抽取越准、越好维护** —— 这是刻意的取舍。

### 存储：属性图，但**不上图数据库**

```
kg_node(id, name, norm, type, aliases, brief, wiki_key, source_count, embedding)
         ↑ id = e-<sha256(归一化名) 前 10 位>， unique(norm)
kg_relation(id, head_id, relation, tail_id, evidence, sources, weight, origin, derived_from, model)
             ↑ unique(head_id, relation, tail_id) —— 重复抽取自然合并，不会越积越多
```

**为什么不用 Neo4j / RDF(Virtuoso/Jena)**：这个库的规模是**数十个实体、数十条三元组**。
整图载入内存 + BFS 遍历（`KgGraphService.neighbors/path`）在这个量级上是微秒级；
引入图数据库要多一个服务、多一套备份、多一层故障面，换不来任何可感收益。
**什么时候该换**：实体上千、或需要亿级路径查询时。这个判断标准写在这里，免得以后凭感觉争论。

**融合规则**：
- 重复三元组合并（证据保留第一条、来源取并集、权重取最大）；
- 别名取并集，`norm` 唯一键挡住同义重复；
- 冲突（同一头尾不同关系）**全部保留**，不做仲裁 —— 小库上"两个关系都列出来让人看"比自动挑选更安全。

---

## 4. 第四层：推理与查询

### 符号推理（`KgReasoner`）

按本体的代数属性做闭包，规则**从属性声明推导**，不是硬编码关系名：

- **传递**：`Maven → part_of → 构建工具`、`构建工具 → part_of → 工程化` ⇒ `Maven → part_of → 工程化`
- **对称**：`A contrast_with B` ⇒ `B contrast_with A`
- 链长 ≤ 3 跳，权重每跳 × 0.7 衰减，低于 0.25 不再外推（避免推出一串 0.1 的废话）
- 推导边带 `origin=derived` 与 `derived_from`（依据的两条边 id）—— **推导结果必须可溯源**，
  界面上推导边画成**虚线**，和直接抽取的事实一眼可分

实测推出的隐含事实（这些**没有直接写在任何记录里**）：

```
JVM     -属于-> JDK        依据 JVM→JRE、JRE→JDK     w=0.63
Tools   -属于-> LLM        依据 Tools→Agent、Agent→LLM
JRE     -易混-> JVM        对称补齐（原文只写了 JVM 易混 JRE）
JDK     -易混-> JRE        对称补齐
git fetch -相关-> git pull  对称补齐
```

### 向量推理：**刻意不做** TransE / RotatE

那类知识图谱嵌入需要**成千上万条**三元组才能训出有意义的向量空间。
这个库只有几十条，训出来的是噪声。**同一件事换一种做法**：
用已有的 `bge-m3` 把**实体描述**（名字 + 说明 + 对应 wiki 页开头）向量化，
用于"换一种说法也能找到概念"——这是 Graph RAG 里"向量"那一半，实测 79/86 个实体已向量化。

### 查询

| 能力 | 接口 |
| --- | --- |
| 邻居展开（1~4 跳） | `GET /api/kg/concept/neighbors?id=&hops=` |
| 两点最短路径 | `GET /api/kg/concept/path?from=&to=` |
| 只跑推理（免费） | `POST /api/kg/concept/reason` |
| 疑似重复实体 | `GET /api/kg/concept/duplicates` |
| 人工合并 | `POST /api/kg/concept/merge?from=&to=` |
| 删除概念 / 三元组 | `DELETE /api/kg/concept/nodes/{id}` / `relations/{id}` |
| 实体识别探针 | `GET /api/kg/concept/recognize?q=` |

**方向语义**：对称关系两个方向都跟，非对称只跟正向 ——
否则"栈 属于 JVM"会被反向读成"JVM 属于栈"（单测 `traversalDirection` 锁住）。

---

## 5. 第五层：与 RAG 结合（Graph RAG）

这是这套东西真正的用处。检索现在是**三路证据源**并行注入对话：

| 证据源 | 回答的问题 | 实现 |
| --- | --- | --- |
| 词面检索 | 哪几条记录和你问的**用词**一致 | 2~3-gram 关键词 |
| 语义检索 | 哪几条记录**换一种说法**也相关 | bge-m3 向量（chunk 级） |
| **概念图谱** | 相关概念之间**是什么关系**、还能顺出什么 | `KgGraphService.retrievalBlock` |

图谱这一块的流程（对应方法论的"查询期"）：

```
问题 → ① 实体识别（名字/别名，长串优先）
     → ② 命中不足时向量兜底（阈值 0.62）
     → ③ 取命中实体的 1 跳三元组（含证据句与来源）
     → ④ 命中 ≥2 个实体时算它们之间的最短路径
     → ⑤ 拼成紧凑上下文注入（最多 24 条）
```

实测（`GET /api/kg/concept/recognize`）：

问题 `JVM 和 JDK 是什么关系？` → 精确识别 `[JVM、JDK]`，注入：

```
1. JVM -属于→ JDK（推导）        ← 这条不在任何文档里
2. JVM -属于→ JRE   证据：JRE（Java 运行环境）：包含 JVM 与运行 Java 程序所需的核心类库　来源：笔记#5
3. JVM -用于→ 跨平台 证据：JVM（Java 虚拟机）：运行 Java 字节码文件的核心，是跨平台的基础。　来源：笔记#5
4. JVM -易混→ JRE   5. JRE -易混→ JVM（推导）   6. JRE -易混→ JDK   7. JDK -易混→ JRE（推导）
```

另外给智能体加了两个工具（模型自己可以沿图多跳查）：
`graph_neighbors(entity, hops)`、`graph_query(entity|relation|from|to)`。

### 两个实测调过的参数

1. **向量兜底阈值 0.5 → 0.62**。0.5 太松：问"容器编排该用什么"会拉进"打印合成后的配置树"这种弱相关概念，
   而注入的每一条都在挤占上下文。**宁缺毋滥。**
2. **兜底命中的实体必须至少有 1 条关系**，否则它对"概念关联"这一块毫无贡献，直接过滤掉。
   另外：命中实体但一条边都取不到时 `retrievalBlock` 返回空串（不注入空块，免得模型对着空块瞎猜）。

### 增量更新

- **不重建整图**：`kg_relation` 的唯一键让重复抽取自然合并；
- 素材变了 → 点「重建概念图谱」：按来源标注回填 `sources`，旧的三元组若这次没抽到仍在（只增不删）；
- 推倒是**整批重算**的（先清 `origin=derived` 再重推），保证可重复、不会叠着长。

---

## 6. 关键难点与现状（诚实清单）

| 难点 | 现状 | 说明 |
| --- | --- | --- |
| 抽取质量 / 幻觉 | ⚠️ 已用三重校验压低 | 证据句必须能在原文里找到；关系必须在词表里；形状必须像实体。但没有二次一致性校验（让另一个模型复核）—— 那会翻倍花 token |
| 实体消歧 | ⚠️ 归一化可靠，语义合并靠人 | 归一化后零重复；`StringBuilder ≈ StringBuffer` 这类只能提示，不能自动合并 |
| Schema 设计 | ✅ 6 个关系 | 刻意做小；关系多了抽取准确率会掉 |
| 增量更新 | ⚠️ 只增不删 | 换来的是"不会因为抽取波动丢事实"，代价是旧错误要靠人删 |
| 指代消解 | ❌ 不做 | 用"拒绝无明确主语的句子"替代 |
| 图数据库 | ❌ 刻意不用 | 数十个实体的规模，MySQL + 内存 BFS 足够 |
| TransE/RotatE | ❌ 刻意不做 | 几十条三元组训不出有意义的向量空间，改用实体文本向量 |

### 已知会产生噪声的地方

- 模型会把**上下位关系判得太宽**：实测出现 `IoC -是一种-> 面向对象的思想`（IoC 是设计原则，不是"一种思想"）。
  这类错误只能靠人删（图谱支持删节点/删边）。
- 抽取**不确定**：同一份素材两次运行选出的概念集合会有差异，所以编译是"只增"的，
  图会缓慢变全，也会缓慢积累噪声 —— 定期看 `duplicates` 与"规则推理"的结果是好习惯。

---

## 7. 代码地图

| 文件 | 职责 |
| --- | --- |
| `service/KgOntology.java` | 本体：封闭关系词表 + 传递/对称属性 + 同义归一 |
| `service/EntityLinker.java` | 归一化、规范 id、别名抽取、合法性判定、相似度提示 |
| `service/TripleExtractor.java` | 联合抽取（NER+RE）+ 三道校验 + 证据核对 |
| `service/KgReasoner.java` | 传递闭包 / 对称推导（纯函数，带单测） |
| `service/KgGraphService.java` | 存储读写、遍历查询、实体识别、Graph RAG 上下文块、删除与合并 |
| `service/KgPipelineService.java` | 五层流水线编排 + 任务进度 |
| `controller/KgController.java` | 概念层接口（另含原有的文档层接口） |
| `ai/ModelRouting.java` | 新增 `triple` 任务：抽取走云端（可在设置里切本地） |
| `ai/AgentService.java` | 注入图谱上下文 + `graph_neighbors` / `graph_query` 两个工具 |
| `frontend/src/views/Knowledge.vue` | 图谱页：文档层 / 概念层切换、关系过滤、多跳聚焦、删除 |
| `frontend/src/components/KnowledgeGraph.vue` | 概念节点样式 + 推导边虚线 |
| `test/.../KgTest.java` | 14 个单测：本体归一、消歧、闭包、方向语义 |
