# 固定知识库评测工具

## 智能体实时过程界面核验

运行 `node tools/ui-verify/agent-chat-fixture.mjs`，打开终端给出的本地地址。输入“正常”“目录”“失败”“断流”或“慢速”，可检查实时步骤、待确认操作、错误恢复与会话切换；页面也提供深浅色切换。数据全部为内存合成样例，所有 API 请求在该进程内处理，代理关闭，不访问真实笔记、配置、数据库或模型。

浏览器回归脚本为 `tools/ui-verify/check-agent-chat.mjs`，通过 `LEARNHUB_PLAYWRIGHT_PATH` 指定已安装的 Playwright 包位置。截图与报告保存在 Git 忽略的 `output/chat-progress-ui/` 中。

## 固定题集

`register-fixed-kb-eval.mjs` 将本机题集登记为隔离的停用草稿；同题已属于其他评测集时会拒绝复用，不修改已有题。`run-fixed-kb-eval.mjs` 通过 LearnHub API 校验冻结语料、记录检索和答案、比较前后配置与语料状态，并支持从同一输出目录恢复。

先准备本机 `suite.json` 和 `corpus.json`。以下是合成格式示例，运行前需替换成目标知识库中实际存在的原文，并用其 UTF-8 正文的 SHA-256 填写 `sha256`。

```json
{
  "schemaVersion": 1,
  "id": "example-suite",
  "version": "v1",
  "cases": [{
    "id": "example-01",
    "question": "示例笔记如何描述规划？",
    "expectedBehavior": "answer",
    "evidenceGroups": [{
      "id": "planning",
      "alternatives": [{"ref": "note:1", "quotes": ["规划负责拆解任务"]}]
    }],
    "answerFacts": [{"id": "planning", "description": "规划拆解任务", "patterns": ["拆解任务"]}]
  }]
}
```

语料文件的结构：

```json
{
  "sources": [{
    "ref": "note:1",
    "title": "示例笔记",
    "content": "规划负责拆解任务",
    "sha256": "<正文 SHA-256>"
  }]
}
```

`ref` 支持 `note:<id>`、`file:<id>` 和 `quick_ref:<id>`。文件来源还需填写与文件说明一致的 `summary`。必要证据组之间是 AND，同一组中的原文备选是 OR；`expectedBehavior` 支持 `answer`、`abstain`、`clarify`。字符串/正则覆盖只作诊断，答案正确性需按冻结事实复核。

在仓库根目录运行：

```powershell
node tools/register-fixed-kb-eval.mjs --suite output/fixed-kb-eval/suite.json --container learn-hub-mysql --database learn_hub
node tools/run-fixed-kb-eval.mjs --suite output/fixed-kb-eval/suite.json --corpus output/fixed-kb-eval/corpus.json --out output/fixed-kb-eval/run --base http://localhost:18080
node --test tools/register-fixed-kb-eval.test.mjs tools/test/run-fixed-kb-eval.test.mjs
```

容器名、数据库名和 API 地址可以按部署修改；API 地址也可由 `LEARNHUB_BASE_URL` 指定。登记脚本从容器已有环境取得数据库密码，不需要将密码写进命令。`--retrieval-only` 跳过答案生成且不要求登记题目；正常运行会通过后端调用当前配置的模型并保存评测记录。

同一输出目录只允许恢复题集、语料、配置、选项和源码哈希一致的运行，已完成题目不会再次生成答案。新输出目录会重新运行；`--retry-errors` 显式重试失败题，调用中断时优先查询后端历史，不能恢复的请求不会自动重复提交。

## 合成候选的重排 A/B

`run-rerank-ab.mjs` 使用冻结的合成题集，直接调用生产 `LlmListwiseReranker` 和 `CrossEncoderReranker`。两组候选、顺序和 120 字符摘录相同；题目标签不传给模型。每题两种排列先取平均，再按问题做配对 bootstrap。此评测只比较重排，不测召回或最终答案。

先编译后端，生成依赖类路径并编译适配器；然后在独立端口启动真实 Cross-Encoder 服务：

```powershell
Push-Location backend
mvn -DskipTests compile
mvn dependency:build-classpath '-Dmdep.outputFile=target/rerank-ab-classpath.txt'
Pop-Location
New-Item -ItemType Directory -Force output/rerank-ab/java
$rerankClasspath = "backend/target/classes;" + (Get-Content backend/target/rerank-ab-classpath.txt -Raw).Trim()
javac -encoding UTF-8 -cp $rerankClasspath -d output/rerank-ab/java tools/RerankAbProbe.java
python tools/rerank-server.py --model BAAI/bge-reranker-v2-m3 --host 127.0.0.1 --port 8093
```

服务就绪后在另一个终端运行：

```powershell
node tools/run-rerank-ab.mjs --out output/rerank-ab --cross http://127.0.0.1:8093/rerank
node --test tools/test/run-rerank-ab.test.mjs
```

运行器只读已有 DeepSeek 重排档案，凭据只在进程内存和适配器 stdin 中使用，不打印或保存；不修改线上配置或语料。输出包括冻结题集、摘录哈希、每次排序与诊断、汇总和 `report.md`。相同输出目录可恢复已完成配对；题集、模型、配置或源码变化时须使用新目录。Cross 失败会保存诊断并中止，避免回退到 LLM 污染 B 组；LLM 的生产原序回退、编号修复和输出截断单独统计。脚本当前要求已有 DeepSeek 档案和 120 字符片段预算。

输出包括原文、标题、问题、回答、服务地址、配置和运行统计，字段脱敏只处理密钥，不能使这些文件成为公开资料。请放在 Git 已忽略的 `output/` 中；公开文档采用合成示例。测试仅使用合成语料和临时本地模拟 API，不连接真实数据库或模型。

## 固定候选的 MMR A/B

`run-mmr-ab.mjs` 分三步运行：先冻结真实生产摘录，再取得一份共享的 LLM 排序，最后比较直接取前 8 条与 MMR 选材。新题集包含 24 道合成题，每题 20 条候选和两种排列；旧 40 题只复用已保存的 LLM 排序，两个题集的标签含义不同，分别报告。主参数预先固定为 0.7，其他参数仅供敏感性检查。

使用上节已编译的 Java 适配器，并在独立的 `127.0.0.1:11435` 启动使用已有缓存的 Ollama。Embedding 脚本使用 CPU 单线程，不拉取模型；当前实验冻结了本机已有 bge-m3 的模型摘要，换模型需先修改计划并使用新输出目录。

```powershell
node tools/run-mmr-ab.mjs prepare
python tools/mmr-embed.py --input output/mmr-ab-2026-10-10/embedding-documents.json --output output/mmr-ab-2026-10-10/doc-vectors.json
node tools/run-mmr-ab.mjs rank
node tools/run-mmr-ab.mjs analyze --vectors output/mmr-ab-2026-10-10/doc-vectors.json
node --test tools/test/mmr.test.mjs tools/test/run-mmr-ab.test.mjs
python -B tools/test/mmr-embed.test.py
```

Embedding 与新题排序可在冻结后并行运行；`rank` 只有一个在途请求。`prepare`、`analyze` 不访问模型档案，`rank` 只读当前 DeepSeek 重排档案。模型输入不含评分、信息分组或答案要点标签。题集、计划、源码、字节码、候选与摘录哈希变化时拒绝续跑；Embedding 缓存还核验模型摘要、输入字节与 CPU 选项。输出放在被忽略的 `output/`，不修改线上检索配置或笔记。

主要检查必要信息覆盖、重复核心事实与相关证据保留，按问题做配对 bootstrap。来源数量不代表新增信息，相似度下降也不等于答案变准。此实验固定候选集，不能证明实际召回或端到端回答准确率提升。
