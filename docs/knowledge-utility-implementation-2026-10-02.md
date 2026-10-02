# 知识库实用性改进：实施记录与验收证据

对应评估报告：[knowledge-utility-audit-2026-10-02.md](knowledge-utility-audit-2026-10-02.md)

实施日期：2026-10-02。所有"实测"均来自运行中的后端（`http://127.0.0.1:18080`），
每项都给出可复现的核对命令。

---

## 一、P0 四项：已完成，并有运行时验收

### P0-① 统一 `note / quick_ref / file` 来源编号 —— 提交 `aaca1f2`

**根因**：来源类型在代码里有**两套命名** —— `KbChunk` / `EntityCompileService` 用
`quick_ref`，而 `KgService` / `WikiService` 用 `ref`。`WikiQuality.normalizeKind`
把「速查卡」归一成 `ref`，拿 `ref-3` 去比 `idsOf()` 产出的 `quick_ref-3`，**永远不匹配**
→ 真实引用被判"不存在"、方括号被剥掉降级成纯文本（@GetMapping、dsh、git 等页）。

**修法**：`WikiQuality` 改为两种写法都认（`normalizeKind` 用规范写法 `quick_ref`，
新增 `altRef()` 做 `ref-N ↔ quick_ref-N` 互换，`kindLabel` 同时接受两者）。

**历史数据**：已按报告建议"用原始生成文本重新校验"修复 —— 先备份
（`backend/wiki-page-backup-20261002.tsv`），再 `REGEXP_REPLACE` **只重挂真实存在**的编号
`(2|3|7|8)`，命中 8 行；引 `速查卡#5`（不存在）的 2 页**未动**，无效 ID 仍被正确识别。

**验收**：
```powershell
$p = (Invoke-RestMethod http://127.0.0.1:18080/api/wiki/pages/entity-51a81e9bc1).data
$p.quality            # ok（修复前 warn）
$p.contentMd -match '\[速查卡#3\]'   # True（修复前为无方括号的 速查卡#3）
```
实体页 `quality=warn` 数量：**12 → 4**（剩余 4 页是真问题：幻觉编号 / 无有效引用）。

### P0-② 索引收录库里全部实体页 —— 提交 `ca12603`

**根因**：实体页只增不删（设计如此），库里累积；而 `upsertIndexPage(chosen)` 的
`chosen` 被 `MAX_ENTITY_PAGES=10` 截断 → 实测 32 个实体页只列 10 个，页脚还承诺
"所以不会漏项"。

**修法**：改为先查 `wiki_page` 里全部 `topic_type='entity'` 的页；本批重编的按 kind 分组列出
（保留 brief），本次未重编的历史页单列「## 其他实体页」一节；首行 / `itemCount` /
`sourceHash` 一律用库内总数；页脚不再作绝对承诺；`chosen` 为空的提前返回分支也刷新索引。

**验收**：
```powershell
$t = (Invoke-RestMethod http://127.0.0.1:18080/api/wiki/topics).data
$idx = (Invoke-RestMethod http://127.0.0.1:18080/api/wiki/pages/index).data
"实体页=" + ($t | ? topicType -eq entity).Count + "  索引 itemCount=" + $idx.itemCount
# 修复前：32 / 10；修复后：36 / 35（35 那次编译后实体页又增 1，索引随即标 stale，见 P1-2）
```

### P0-③ Wiki 主题匹配与缺口语义 —— 提交 `0c641c6`

**根因**：问题切词后的通用 2-gram（"知识"）同时命中《知识索引》《知识自检》的标题，
与明确主题词（MQTT）落进**同一个布尔 `titleHit` 档**；档内按 raw score 排，
再加上 `RAG_MAX_PAGES=2`，MQTT 页被截掉。另：`## 待补充` 被注入层一律说成
"素材里明确没记"，其实多半是**采样截断**造成的。

**修法**：标题命中从布尔改成**具体度** `titleScore`（ASCII 主题词 +100，中文 2 字 +1 / ≥3 字 +10）；
注入侧与探针侧共用同一套排序；缺口拆成「该页未展开」与「采样未覆盖」两类；
删掉注入前言里"你的知识库里没有记这一点"那句。

**验收**：
```powershell
$q = [uri]::EscapeDataString('关于 MQTT，我的知识库里还缺哪些关键点？')
(Invoke-RestMethod "http://127.0.0.1:18080/api/wiki/probe?q=$q").data.block
```
修复前：注入《知识索引》《知识自检》；修复后：**注入《MQTT协议》《MQTT》**，
且缺口标注为「待补充（该页未展开，原文里可能有）：QoS…；Broker…；Topic…」。

### P0-④ 图谱未确认边与错误方向 —— 提交 `1aab030` `cf424a3` `4118b70`

**根因**：`TripleExtractor` 证据对不上时只清空证据**仍接受**三元组并统一给 0.9；
`KgPipelineService` 入库处又硬编码 0.9（改了抽取层也不生效）；推导与注入都不筛"无证据边"；
本体层缺"依赖 vs 组成"的判据 → 证据「Spring 是基于 Java 的，自然依赖于 JVM」被抽成
`Spring-属于→JVM`，再经传递闭包放大成 `Spring-属于→JRE`。

**修法**：
- D1：未核对的三元组**保留但降权**（0.9 → 0.4），让"有没有原文支持"在数据上可区分；
- D2：入库使用 `t.weight()`，不再硬编码 0.9；
- D3：注入层跳过 `origin=llm` 且无证据的边；
- D4：`reason()` 只从**有原文证据**的直接边出发建传递闭包；
- D5：`KgOntology` 新增"依赖/组成"判据，证据里**有依赖词、无组成词**时把
  `part_of/is_a` 改判为 `prerequisite`（词面启发式，已在注释写明局限）。

**存量数据清理**（已执行）：
```
DELETE /api/kg/concept/relations/67     # Spring -属于→ JVM
DELETE /api/kg/concept/relations/169    # Spring -属于→ Java
POST   /api/kg/concept/reason           # 先 clearDerived 再重推
```
**验收**：
```powershell
$q = [uri]::EscapeDataString('Spring 和 JVM 是什么关系')
$b = (Invoke-RestMethod "http://127.0.0.1:18080/api/kg/concept/recognize?q=$q").data.block
$b -match 'Spring -属于→ JVM'      # False（修复前 True）
$b -match 'Spring -前置知识→ JVM'  # True
```
边数 194 → 174；推导边 62 → 44（未核对边不再参与推导）。

### 附带：前端标注 —— 提交 `1c49ec6`

`Knowledge.vue` 工具条原先把 `topics.length` 写成「N 页编译产物」，把**未生成页的主题**
也算进去。改为「共 N 个主题，已生成 M 页」（用后端已有的 `generated` 字段）。实测 48 / 38。

---

## 二、P1：已有实质进展与可核对数据

### P1-1 知识库主搜索改用融合检索 —— 提交 `8b11b6f` `d95edfb`

**新增端点** `GET /api/kb/search?q=&topK=`（复用 `VectorIndexService.search`，与智能体同一条融合链路）。
**前端**：`Knowledge.vue` 检索区默认「融合」、可切「词面」；每条结果显示来源类型标签、
标题、120 字命中片段、相关度；点击跳原文;常驻状态行说明"本次结果来自哪种检索"。

**验收**：
| 查询 | 词面 | 融合 |
| --- | --- | --- |
| 大量字符串拼接用哪个类性能更好 | **0 条** | 10 条，目标速查卡**第 4** |
| StringBuilder | 0 条 | 10 条，目标速查卡**第 2** |

另修：空/空白 `q` 原先 500，现在返回空列表（`q=` → HTTP 200）。

### P1-2 来源指纹与标脏 —— 提交 `06730c9`（索引页部分）

**根因**：实体/索引/自检页的 `stale` **写死 false**，编译页永远不提示"待更新"；
索引页的 `sourceHash` 是 `"index-" + 页数`（弱指纹）。

**修法**：`EntityCompileService.indexFingerprint()` 对**实体页清单**（topicKey + 标题，
不含生成时间）做 SHA-256；`WikiService.topics()` 对索引页用它重算比对。

**验收**（含一次真实的联动）：
```powershell
$idx = (Invoke-RestMethod http://127.0.0.1:18080/api/wiki/topics).data | ? topicType -eq index
$idx.stale     # True —— 实体页数从 35 变 36 后，索引立刻正确标脏
```
指纹算法独立验证：同集合不同顺序**相等**；多一页 / 改名**不相等**。

**未做**：实体页自身的来源指纹与依赖映射（需要把"这一页用了哪些来源"落库）。

### P1-3 概念身份、别名与 Wiki 链接 —— 提交 `d88b731` `e9b89c0` `3ca940b` `a77816e`

**三个问题、三处修**：
1. 关联逻辑只在**图谱重建**时跑 → 新增 `POST /api/kg/concept/link-wiki`
   （免费、不调模型），可随时重关联（实测 11 → 13）。
2. `aliases` **只在抽取阶段用、没有落库** → 实体页正文首行写入
   `<!-- entity-aliases: a, b -->`（HTML 注释，渲染不显示；不改表结构，避开
   `sql.init=always` 无 ALTER 的启动风险）；`linkWikiPages` 解析它并纳入匹配
   （正式标题优先，别名不覆盖）。实测：编译后 6 页带上别名，重关联 13 → **14**。
3. **单页重编写错 key**：`WikiService.savePage` 的 key 推导只有
   "tag / 其余 → cat-<id>" 两个分支，对 `entity`（id 恒为 0）拼出 **`cat-0`** ——
   重编实体页时结果写进历史垃圾键，页面不更新还多出同名入口。已修为
   编译产物页直接用**自己的 topicKey**。实测：目标页 `generatedAt`
   09-28 20:55 → **10-02 15:16**，且 `cat-0` 未重建。

**顺带整理**：删除历史垃圾页 `cat-0`（标题 `@GetMapping`、`topic_type=entity`，
重复入口）。备份 `backend/wiki-cat0-backup.tsv`。

**未做**：把别名铺到全部实体页（只对**重新编译**过的页生效，需要多跑几轮编译）；
剩余近重复主题的整理。

### P1-4 答案级评测 —— 提交 `8f2370c`（一项可量化优化）

**先建立可复现基线**（回归集 97 → **99** 题，新增"StringBuilder""Spring 和 JVM 是什么关系"）：

| 指标 | 值 |
| --- | --- |
| recall@5（fused，含精排） | 0.929 |
| MRR | 0.860 |
| 词面 / 语义 recall | 0.687 / 0.838 |

三条路径的相对关系**复现**了报告的历史结论（词面 ≪ 语义 ≪ 融合）。

**然后做了一项有量化收益的优化**：检索链路**没有来源级去重**（代码里的
`key()/keysOf()` 是全项目从未调用的死代码），一篇长文档靠块数占满前 K
（实测 `file#19` 占 4 行、`note#51` 占 2 行），把对症的短资料挤出榜单。
改为**来源优先**（每个来源先取最高分那块，不足 topK 再补）：

| 指标 | 去重前 | 去重后 |
| --- | --- | --- |
| recall@5 | 0.929 | **0.939** |
| MRR | 0.860 | **0.869** |
| 语义 recall | 0.838 | **0.869** |
| 词面 recall | 0.687 | 0.687（不受影响，符合预期） |

评测历史落库，`GET /api/kb/eval/history` 可查（标签「修复后基线」「来源去重后」）。
复跑：`POST /api/kb/eval/run?label=xxx&topK=5&mode=fused`（99 题约 146 秒）。

**未做**（报告明确要求）：30 条人工标注题（关系 10 / 跨资料综合 10 / 库内缺口 10）；
**答案级评分维度**（现在只评"来源命中"，不评答案正确性、引用支持、无答案处理、成本）。

**基线暴露的漏检**（top-5 未命中期望来源，多为"问痛点不问方案名"的语义型）：
```
物联网设备上报数据一般怎么做            期望 note:3
多个设备同时上报数据，怎么保证不丢       期望 note:3
每次连接数据库都要重新建连接太慢了怎么办  期望 note:5
怎么把一次会话的上下文存下来下次接着聊    期望 note:44
消融实验主要验证了哪两个设计的作用？     期望 file:6
系统里负责判断这次执行是不是跑偏了的模块叫什么？ 期望 file:5
```

---

## 三、运维注意（踩过的坑）

1. **重建后端的正确顺序是"先停服务，再 `mvn package`"**。运行中的进程会锁住
   `target/*.jar`，直接打包会在 `repackage` 阶段报
   `Unable to rename ... .jar to .jar.original`，并留下一个 1MB 的**瘦包**（不可运行）。
   发现后必须先停服务、重新打包。旧 jar 备份在 `target/*.jar.bak`。
2. **`GET /api/wiki/pages/{topicKey}` 对不存在的页返回 200 + `data:null`**，
   用 HTTP 状态码判断"页是否存在"会**误判**（本次就误报过一次）；
   权威判断请查 DB 或看 `data` 是否为空。建议后续让不存在时返回 404。
3. `schema.sql` 是 `spring.sql.init.mode=always` 且**没有任何 ALTER**，
   MySQL 也没有 `ADD COLUMN IF NOT EXISTS` → 直接加列会在第二次启动时报
   duplicate column 并**导致启动失败**。要加列得照 `ModelProfileMigration`
   的 `ApplicationReadyEvent` + 幂等检查模式，或复用现有可空列。
4. 本会话所有数据变更都已备份：
   `backend/wiki-page-backup-20261002.tsv`（10 个实体页）、`backend/wiki-cat0-backup.tsv`。

## 四、剩余工作

| 优先级 | 事项 |
| --- | --- |
| P1-4 | 30 条人工标注题；答案级评分（正确性 / 引用支持 / 无答案处理 / 耗时与成本）；剩余 6 个语义型漏检 |
| P1-2 | 实体页自身的来源指纹与依赖映射（把"这一页用了哪些来源"落库，才能精确标脏） |
| P1-3 | 多跑几轮实体编译把别名铺开；近重复主题整理（`GET /concept/duplicates` + `POST /concept/merge`） |
| 小修 | `/pages/{key}` 不存在时返回 404；`/refs`、`/files` 支持定位到单条（搜索结果点击目前只到列表页） |
