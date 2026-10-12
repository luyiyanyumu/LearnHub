# 智能体自适应工具

遇到具体能力缺口时，智能体可以读取项目实现，编写、测试并注册工具，在同一次对话中调用它继续完成任务。工具定义采用 JSON 工作流，支持原生工具调用、固定数据转换，以及真正执行 Python 源码的 `code` 步骤。生成的代码存入版本化工具注册表，每次在独立临时容器内执行；不热替换生产 JVM 中的 Java 类。

## 完成一次能力补齐

1. `inspect_capabilities` 根据目标返回原生工具、相关源码片段及其 `path` / `sha256`、已注册工具、精确工作流契约及 `recentReviews` 中的近期审查记录。智能体先判断原生工具能否直接完成任务。
2. 对相关能力候选做审查，说明具体缺口。已有能力足够时直接复用；需要扩展时提供 `definition`、源码引用和正常 / 边界合成测试。先调用 `test_adaptive_tool` 试运行，依据返回的 `actual`、`expected`、代码错误与输出日志修正，再提交 `evolve_tool`。
3. 服务端检查工具白名单、输入 schema、步骤引用、写操作位置、源码哈希、重复性及测试结果。`evolve_tool` 会独立再次执行测试。单个原生调用的直接别名会被归为复用；结构指纹识别改名或调整步骤配置字段顺序后的等价工作流（保留代码可观察的输入键顺序及数字类型），Python AST 指纹识别仅改注释、空白或排版的重复代码。代码工具必须填写稳定的 `capability_key`，相同标识要求补齐同名工具。相近描述会返回候选，确有不同用途时需用 `distinct_reason` 说明差异。相似度与有限测试不能证明任意两段程序语义等价，智能体仍需结合接口和源码审查。
4. 通过审查后更新工具目录，智能体调用新工具；失败时返回原因，待确认操作返回 `staged: true` 并立即停止后续步骤，不能宣称已写入。

`extends_tool` 可指向被扩展的原生或自适应工具。扩展已有自适应工具必须沿用它的 `name`，并传当前 `expected_version`，由服务端升级同一工具；新工具使用 `expected_version: 0`。工作流步骤只能调用原生工具，不能嵌套调用自适应工具或进化控制工具。

## 源码、测试与运行约束

源码检查优先读取项目检出目录；独立 JAR 可读取构建时放入 classpath 的 `agent/project-source/` 源码资源，也支持源码目录快照。源码输出限定范围、过滤非源码资料并处理敏感字面值。使用构建内置源码时，项目实现变化后须重新构建；提交定义时会重新检查引用的路径与哈希。

每个定义提供 2～6 项合成测试。`tool_results` 中的模拟返回必须严格匹配实际调用的 `tool` 和解析后的完整 `arguments`，缺少匹配即失败；测试不调用真实服务。`expected` 对比工作流 `output`；原生调用失败或待确认而提前终止时，对比返回的状态对象。纯转换流程可使用空 `tool_results`。代码工具的每个用例都必须实际完成全部代码步骤，不能靠前置模拟调用提前返回来跳过代码测试。

一次用户请求最多检查源码 2 次、试运行 2 次（含启用与回退复测）、提交方案 2 次，所有自适应工具合计最多执行 12 次内部原生调用或代码步骤；仍受原对话时间预算限制。每个工作流最多 8 步，其中最多 2 个代码步骤，源码合计最多 24,000 字符。单次方案审查与测试共用 60 秒预算。注册表最多 12 个工具，包含已停用的工具。每个定义的 `parameters` 序列化后最多 6,000 字符，注册表所有工具的当前 `parameters` 合计最多 24,000 字符，包含已停用工具；可执行计划最多 32,000 字符。

`inspect_capabilities` 返回的完整工具定义共用 48,000 字符预算，在目标中写出准确工具名称会优先返回该工具。超过预算的工具仅返回简介，并标记 `definitionOmitted: true`；需要完整定义时，用该工具名称再次检查。转换支持 `pick`、`filter`、`sort`、`unique`、`join`、`replace`、`template`、`split`、`length`；引用使用 `{"$ref":"input.items"}` 或 `{"$ref":"steps.id.field"}`，步骤引用只能指向前面的结果。输入和结果还有大小、层级与数组数量上限。

写操作沿用原有确认卡片，且只能放在最后一步。Word 文档生成（`create_word_document`）与笔记写入一样先返回待确认状态，批准后才执行。笔记定向编辑仍需先 `get_note`，把返回的 `content_hash` 原样传为 `edit_note.expected_hash`；当前笔记有未保存草稿时拒绝生成编辑预览，确认时正文版本发生变化也拒绝写入。自适应工具不会绕过这些检查。

`manage_adaptive_tool` 支持 `disable`、`enable`、`rollback`，均需当前 `expected_version`。保留最近 3 个历史定义，回退后版本号继续递增，避免旧请求误覆盖。启用或回退会重新核验并执行测试，失败时保留当前定义。连续 3 次实现故障会自动停用，成功运行清零计数；输入参数错误、原生工具业务失败、执行服务不可用或繁忙不计入实现故障。

## 启用代码执行环境

在仓库根目录运行（需要 Docker Engine）：

```sh
docker compose -f deploy/docker-compose.yml -f deploy/docker-compose.code-runner.yml build code-runner code-sandbox
docker compose -f deploy/docker-compose.yml -f deploy/docker-compose.code-runner.yml up -d code-runner backend
```

代码执行覆盖配置为后端设置 `LEARNHUB_CODE_RUNNER_URL`。如果使用发布版 Compose，将第一项文件换成 `deploy/docker-compose.release.yml`；两个执行镜像仍需从源码构建。后续更新继续带上该覆盖文件。`code-sandbox` 仅作为构建目标，不需要作为常驻服务启动。

调用 `inspect_capabilities` 可查看 `codeRuntime.available`。未配置或暂不可用时，不发布代码工具供模型调用，已有原生与纯组合工具继续使用。直接启动 JAR 的部署可以配置同名环境变量指向受信任的内部 runner；不应将 runner 端口暴露到公网。

受信任的 broker 是唯一挂载 Docker Engine socket 的组件，只接受固定的代码执行和语法分析请求，不接受容器配置、镜像名、命令行或主机路径参数。它与后端通过内部网络通信。每次生成代码执行都创建非 root、断网、只读根目录的临时容器，只传入明确的 JSON 输入；不挂载源码、上传资料、数据库或应用密钥。容器限制内存、CPU、进程数、临时目录、日志和运行时间，结束后删除，异常清理会重试。实际沙箱入口和测试见 [执行环境](../tools/code-runner/README.md)。

当前支持 **Python 3 标准库**，入口为 `run(input)`，返回 JSON 可序列化结果。原文读取、联网和写回由现有 `call` 工具完成；代码步骤负责解析、计算与转换。修改笔记依然通过最后一步的原生编辑工具提交预览，保留未保存草稿与正文版本校验。

## Python 工具合成示例

以下为步骤和测试片段；完整定义仍需 `name`、`description`、`gap`、`parameters`、真实 `source_refs` 及稳定的 `capability_key`。此例实现现有固定转换不支持的加权计算，输入仅为合成数据。

```json
{
  "capability_key": "weighted_score",
  "steps": [{
    "id": "score",
    "op": "code",
    "language": "python",
    "source": "def run(input):\n    items = input['items']\n    weight = sum(x['weight'] for x in items)\n    return {'score': sum(x['value'] * x['weight'] for x in items) / weight if weight else None}\n",
    "input": {"$ref": "input"}
  }],
  "output": {"$ref": "steps.score"},
  "tests": [
    {"name": "正常加权", "kind": "normal", "input": {"items": [{"value": 2, "weight": 1}, {"value": 4, "weight": 1}]}, "expected": {"score": 3.0}},
    {"name": "空集合", "kind": "boundary", "input": {"items": []}, "expected": {"score": null}}
  ]
}
```

`test_adaptive_tool` 不注册、不修改业务资料，调用原生工具的测试必须提供精确匹配的 `tool_results` 模拟数据。测试失败返回诊断，模型可修正同一份源码。注册成功后与其他自适应工具一样由同一实例内所有会话复用，过程界面会显示实际 Python 执行步骤。

## 合成定义示例

下面的定义对字符串数组按首次出现顺序去重，再用顿号连接。作为 `evolve_tool` 的 `definition` 提交前，先调用 `inspect_capabilities` 检查相关实现，把 `source_refs` 替换为本次返回的实际路径与哈希。示例中的 64 位零哈希是合成占位值，不能直接通过源码审查；测试数据不来自真实笔记。

```json
{
  "name": "adaptive_unique_line",
  "description": "对给定字符串数组按首次出现顺序去重，再用顿号连接为一行文本。",
  "gap": "需要复用字符串数组去重并连接的组合行为，避免每次重新生成相同转换步骤。",
  "expected_version": 0,
  "parameters": {
    "type": "object",
    "properties": {
      "items": {
        "type": "array",
        "items": { "type": "string", "maxLength": 120 },
        "minItems": 0,
        "maxItems": 100
      }
    },
    "required": ["items"],
    "additionalProperties": false
  },
  "steps": [
    {
      "id": "distinct",
      "op": "transform",
      "kind": "unique",
      "value": { "$ref": "input.items" }
    },
    {
      "id": "joined",
      "op": "transform",
      "kind": "join",
      "value": { "$ref": "steps.distinct" },
      "separator": "、"
    }
  ],
  "output": { "$ref": "steps.joined" },
  "source_refs": [
    {
      "path": "backend/src/main/java/org/dyh/learnhub/ai/AdaptiveToolWorkflow.java",
      "sha256": "0000000000000000000000000000000000000000000000000000000000000000"
    }
  ],
  "tests": [
    {
      "name": "正常输入保留首次出现顺序",
      "input": { "items": ["合成甲", "合成乙", "合成甲"] },
      "tool_results": [],
      "expected": "合成甲、合成乙"
    },
    {
      "name": "空数组边界",
      "input": { "items": [] },
      "tool_results": [],
      "expected": ""
    }
  ]
}
```

注册成功后调用 `adaptive_unique_line`，参数为 `{"items":["合成甲","合成乙","合成甲"]}`。普通成功返回包含 `ok`、`output` 和各步骤结果；注册表记录工具版本、运行结果与审查事件，便于复用和修正。
