# 固定知识库评测工具

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

输出包括原文、标题、问题、回答、服务地址、配置和运行统计，字段脱敏只处理密钥，不能使这些文件成为公开资料。请放在 Git 已忽略的 `output/` 中；公开文档采用合成示例。测试仅使用合成语料和临时本地模拟 API，不连接真实数据库或模型。
