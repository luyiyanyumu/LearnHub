# learn-hub 知识库改进：接续任务书

> 用途：把这份文档整段发给一个新会话，让它接着做。上一轮会话的上下文已耗尽，
> 但**代码、测试、数据、文档都已落盘并推送到 GitHub**，新会话可以完全从仓库状态接手。

---

## 0. 一句话现状

> **2026-10-02 本轮更新：交接书里剩下的两件事都已完成。**
> —— **30 条人工标注题**（关系 10 / 跨资料 10 / 缺口 10，全部 `enabled=0` 草稿）
> 与 **答案级评分维度**（答案正确性 / 引用支持 / 引用完整性 / 无答案处理 / 耗时与 token）。
> 实施记录、验收数据、踩到的两个坑见
> `docs/knowledge-utility-implementation-2026-10-02.md` **第七节**。
> **下面第 3 节已不是待办**，保留原文只是为了说明当时的口径与要求；
> 真正还剩的事见实施文档第四节「剩余工作」表（主要是：30 题尚未正式启用、
> 6 个语义型漏检、跨资料题的证据注入深度）。

按 `docs/knowledge-utility-audit-2026-10-02.md` 的改进清单：
**P0 四项全部完成并运行时验收；P1-1 完成；P1-2 完成；P1-3 完成（含规则重设）；
P1-4 完成（30 条人工标注题 + 答案级评分维度）。**

---

## 1. 环境与常用命令（都已实测可用）

| 项 | 值 |
| --- | --- |
| 仓库 | `C:\Users\dyh\WorkBuddy\2026-09-07-15-46-17\learn-hub`（分支 `master`，远端 `luyiyanyumu/LearnHub`）|
| 后端 | Spring Boot 3.5.16 + Maven，跑在 `http://127.0.0.1:18080`（API 前缀 `/api`）|
| JDK | `C:\Users\dyh\dev\tools\jdk-21.0.12.1+1\bin\java.exe`（系统默认是 Java 26，**必须用这个 JDK21 跑**）|
| 前端 | Vue 3 + Vite，dev server `http://127.0.0.1:5174`；构建 `cd frontend; npm run build` |
| 数据库 | MySQL `127.0.0.1:3307`，库 `learn_hub`，`root/root123456` |
| mysql 客户端 | `C:\Program Files\MySQL\MySQL Server 9.4\bin\mysql.exe` |

**编译 / 打包**：`mvn -o -q compile`（无输出=成功）；`mvn -o -q package -DskipTests`

⚠️ **重建后端必须"先停服务，再 package"**：运行中的进程会锁住 `target/*.jar`，
直接打包会在 repackage 阶段报 `Unable to rename ... .jar to .jar.original`，
并留下一个 1MB 的**瘦包（不可运行）**。正确顺序：
```powershell
# 1) 停：按端口找进程
$c = Get-NetTCPConnection -LocalPort 18080 -State Listen | Select-Object -First 1
Stop-Process -Id $c.OwningProcess -Force; Start-Sleep -Seconds 3
# 2) 打包
mvn -o -q package -DskipTests
# 3) 启动
Start-Process -FilePath <JDK21>\bin\java.exe -ArgumentList '-jar','target\learn-hub-backend-0.0.1-SNAPSHOT.jar' `
  -WorkingDirectory <backend> -RedirectStandardOutput <backend>\backend-run.log `
  -RedirectStandardError <backend>\backend-run.err.log -PassThru -WindowStyle Hidden
# 4) 等就绪（轮询 18080 端口，一般 10~15 秒）
```

**PowerShell 踩坑**：`Invoke-RestMethod` 会把 UTF-8 当 Latin-1 解码导致中文乱码，
读 JSON 用：
```powershell
$r = Invoke-WebRequest -Uri $url -UseBasicParsing
$j = [System.Text.Encoding]::UTF8.GetString($r.RawContentStream.ToArray()) | ConvertFrom-Json
```
写含中文的文本文件用 `[System.IO.File]::WriteAllText($p,$s,(New-Object System.Text.UTF8Encoding($false)))`
（`Set-Content -Encoding UTF8` 会加 BOM，喂给 `javac` 会报 `非法字符 '\ufeff'`）。

---

## 2. 已完成的部分（提交都已推送）

| 项 | 状态 | 提交 | 关键验收证据 |
| --- | --- | --- | --- |
| **P0-①** 统一 `note/quick_ref/file` 来源编号、修复真实引用被剥除 | ✅ | `aaca1f2` | `git`→`[速查卡#7]`、`Docker`→`[速查卡#2]`、`dsh`→`[速查卡#8]`，三页 `q=ok`；无效编号仍被拦；warn 12→4 |
| **P0-②** 索引收录库里全部实体页 | ✅ | `ca12603` | 索引 itemCount 10 → 36+，与实体页数一致 |
| **P0-③** Wiki 主题匹配 + 缺口语义 | ✅ | `0c641c6` | MQTT 问句由注入《知识索引/自检》→ **注入《MQTT协议》《MQTT》** |
| **P0-④** 图谱未确认边隔离 + 错误方向 | ✅ | `1aab030` `cf424a3` `4118b70` | `Spring-属于→JVM/JRE` 全部消失，改为 `前置关系`；推导边 62→44 |
| **P1-1** 知识库主搜索改融合检索 | ✅ | `8b11b6f` `d95edfb` | 词面 **0 条** → 融合 10 条、目标速查卡**第 4**；空查询 500→200；前端默认融合可切词面 |
| **P1-2** 索引页内容指纹 | ✅ | `06730c9` | 实体页数变化 → 索引自动 `stale=true`（含真实联动验证）|
| **P1-2** 实体页来源指纹与依赖映射 | ✅ | `dd2e22e` | 36 页中恰好 **3 页** 翻 `stale=true`（`Spring Boot自动装配`/`Docker`/`A2A`），与实现方预测**完全一致** |
| **P1-3** 概念↔Wiki 关联可单独触发 | ✅ | `d88b731` | `POST /api/kg/concept/link-wiki`，11→13 |
| **P1-3** 别名持久化 | ✅ | `e9b89c0` `3ca940b` | 别名写进实体页首行 `<!-- entity-aliases: -->`；实测 13→14 |
| **P1-3** 别名铺不开的根因修复 | ✅ | `252f98f` | 编译批次改为优先收"还没有别名"的页，形成自排空轮转；别名覆盖 6→10 |
| **P1-3** 图谱节点准入闸门 | ✅ | `4fecfb1` `90c2107` | 24 用例验证：**零误杀**（`Flow-GRPO`/`spring boot`/`AutoConfigurationImportSelector`/`IoC 的实现方式` 全保留），命令行/选项/句子碎片全拦 |
| **P1-3** 存量垃圾节点清理 | ✅ | `3e2b38e` `44b803e` | 135 → **104** 节点（先备份再删，全部用官方端点）|
| **P1-4** 回归集 + 基线 + 检索优化 | 🟡 | `8f2370c` | 97→**99 题**；来源去重使 **recall@5 0.929→0.939、MRR 0.860→0.869** |
| 修掉的真实 bug | ✅ | `a77816e` | 单页重编实体页把结果写到历史垃圾键 `cat-0`（已修 + 清理）|
| 回归测试 | ✅ | `ff47a63` `3b4a3cc` `dd2e22e` | `WikiQualityTest` 4 + `KnowledgePureHelpersTest` 6 + `EntityStaleTest` 12 = **22 条全过** |

**详细实施记录**（根因 / 修法 / 可复现验收命令 / 剩余配方 / 运维踩坑）：
👉 `docs/knowledge-utility-implementation-2026-10-02.md`

---

## 3. 你要做的剩余工作

> ⚠️ **本节已全部完成（2026-10-02）** —— 见第 0 节的说明与实施文档第七节。
> 下面内容保留为**当时的要求与口径**，不要照着重做。

### 3.1 补齐 24 条人工标注题（主要任务） ✅ 已完成

评估报告要求 30 条：**关系题 10 + 跨资料综合题 10 + 库内缺口/无答案题 10**。
上一轮已落 **6 条**（`rag_eval` 表，`enabled=0` 草稿，id **126–131**，见文档 5.1），
**还差 24 条**（关系 7 / 跨资料 10 / 缺口 7）。

**素材清单（真实 id，已实测存在）**：
```
速查卡: quick_ref:1 String/StringBuilder/StringBuffer 区别   quick_ref:2 Docker 常用命令
        quick_ref:3 @GetMapping/@PostMapping 速记          quick_ref:4 Java == 与 equals 区别
        quick_ref:7 Git 常用指令速查                        quick_ref:8 DeepSeek Harness(dsh) 常用命令
笔记:   note:51 AI Agent 讲义        note:52 Milvus 入门笔记     note:50 code agent 项目分析
        note:44 AI Agent 学习笔记     note:2  Spring Boot 启动流程  note:47 RAG 两篇奠基论文
        note:3  MQTT 三句话入门       note:5  Java学习笔记        note:1  Maven 坐标三要素
资料:   file 表名请先 SHOW TABLES 确认（**没有** `file` 这张表），已知 file:2 = Spring boot.pdf
```

**跨资料题的天然候选组合**（需要读正文确认"各提供什么"）：
`note:2` + `file:2`（Spring Boot 启动流程）、`note:47` + `note:52`（RAG 论文 + Milvus）、
`note:44`/`note:51` + `note:50`（AI Agent 讲义 + code agent 项目分析）。

**缺口题做法**：先 `GET /api/knowledge/search?kw=<关键词>` 确认**命中 0 条**，再写。

**硬性要求**：
- `expect_refs` 里的 id **必须真实存在**，写完逐条核对；跨资料题写**全部**必要来源，用 `|` 分隔；
- `note` 字段写清"考什么 + 依据来自哪份材料的哪一段"；
- 问题要口语化（像现有 99 题），不要关键词堆砌；
- **草稿一律 `enabled=0`**（`RagEvalService.cases()` 只取 `enabled=1`，不影响现有基线）。

**表结构与落库**：
```sql
-- rag_eval: id(自增) / question(唯一) / expect_refs / expect_words / note / enabled / created_at
INSERT IGNORE INTO rag_eval (question, expect_refs, expect_words, note, enabled) VALUES (...);
```
用临时 `.sql` 文件 + `Get-Content file -Raw | & $mysql ...` 执行（命令行内联中文容易踩引号坑）。

### 3.2 增加答案级评分维度（第二大任务） ✅ 已完成

`RagEvalService` 目前**只评"来源命中"**（recall@K / MRR / keyword / vector），
**不评价生成答案**。报告要求补上：
- 答案**正确性**、**引用支持与完整性**、**无答案时的处理**（该说不知道时是否说了）、
  **耗时与 token 成本**。

参考端点：`POST /api/kb/eval/run?label=&topK=5&mode=fused`（99 题约 146 秒）、
`GET /api/kb/eval/cases`、`GET /api/kb/eval/history`。

### 3.3 可选（报告里明确不建议投入更多的地方）

- **图谱关联率**：已查明天花板是**实体页覆盖率**（104 个节点 vs 36 个实体页，
  绝大多数节点根本没有页面可连），**不是匹配算法**。不要再跑编译试图提升。
  ⚠️ 详情见实施文档第六节的实测数据与三个可选方向及各自代价。
- 小修：`GET /api/wiki/pages/{key}` 对**不存在的页返回 200 + `data:null`**
  （用 HTTP 状态码判断"页是否存在"会误判，建议改成 404）。

---

## 4. 必须知道的边界与约定

1. **不要给表加列**：`schema.sql` 是 `spring.sql.init.mode=always` 且**没有任何 ALTER**，
   直接加列会在**第二次启动**时报 duplicate column 并导致启动失败。
   要加列得照 `ModelProfileMigration` 的 `ApplicationReadyEvent` + 幂等检查模式。
   上一轮的 P1-2 就是靠**复用 `wiki_page.source_hash`** 绕开这个限制的。
2. **来源类型有两套命名**：`KbChunk`/`EntityCompileService` 用 `quick_ref`，
   `KgService`/`WikiService` 用 `ref`。`WikiQuality` 必须**两套都认**（有回归测试锁着）。
3. **图谱节点准入规则**（`KgGraphService.admissibleConceptName`）：
   宁可漏收垃圾，**绝不误杀真实概念**。动词表只列名动不同形的动词
   （配置/设置/运行/安装是名动同形词，已移除）。
4. 工作区现在是**干净的**（所有改动都已提交推送），可以正常 `git add` 指定文件。
5. 代码注释与文档一律**中文**。
6. 数据库破坏性操作前**先备份**（上一轮的做法：导出节点与相关边到 TSV，
   用官方端点 `DELETE /api/kg/concept/nodes/{id}` 删，最后用 DB 权威数据复核 —— 
   **不要用接口的 HTTP 状态码判断成败**）。

---

## 5. 相关文档

| 文档 | 内容 |
| --- | --- |
| `docs/knowledge-utility-audit-2026-10-02.md` | **改进清单来源**（P0/P1 优先级表、代码证据）|
| `docs/knowledge-utility-implementation-2026-10-02.md` | **实施记录**：每项根因/修法/验收命令 + 评测题配方 + 运维踩坑 |
| `docs/knowledge-upgrade-plan-2026-10-02.md` | 更早的计划文档 |
