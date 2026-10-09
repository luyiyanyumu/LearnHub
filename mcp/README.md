# learn-hub MCP 服务

通过 MCP 工具访问 learn-hub 后端，支持笔记、分类、标签、速查卡和资料库操作。服务使用 Node.js 内置模块实现 JSON-RPC 2.0 over stdio，无需安装 npm 依赖，也不直接连接数据库。

## 启动条件

- Node.js 18 或更高版本。
- learn-hub 后端已启动，默认地址为 `http://localhost:18080`。后端配置见[项目 README](../README.md)。
- 保留仓库目录结构：`edit_note` 的参数定义读取自 [note-edit-tool.json](../backend/src/main/resources/note-edit-tool.json)。

从仓库根目录运行服务：

```powershell
node mcp/learn-hub-mcp.mjs
```

服务通过标准输入接收协议消息，通常由 MCP 客户端启动；直接运行后会等待输入。标准输出只传输协议帧，诊断日志写入标准错误。

## 客户端配置

以下是使用 `mcpServers` 格式的客户端配置示例。将 `C:/path/to/learn-hub` 替换为实际仓库目录；配置位置和生效方式以客户端为准。

```json
{
  "mcpServers": {
    "learnhub": {
      "command": "node",
      "args": ["C:/path/to/learn-hub/mcp/learn-hub-mcp.mjs"],
      "env": {
        "LEARNHUB_BASE_URL": "http://localhost:18080"
      }
    }
  }
}
```

对于使用 `@deepseek-ai/dsh-mcp-client` 的 DeepSeek Harness，可在所用 profile 的 `cordis.patch.yml` 中添加插件配置：

```yaml
- insert:
    - id: mcp-learnhub
      name: '@deepseek-ai/dsh-mcp-client'
      config:
        serverName: learnhub
        transport: stdio
        command: node
        args: ['C:/path/to/learn-hub/mcp/learn-hub-mcp.mjs']
        env:
          LEARNHUB_BASE_URL: http://localhost:18080
```

客户端需要能找到 `node`；若出现 `ENOENT`，检查 `PATH` 或把 `command` 改为 Node.js 可执行文件的实际路径。接入后可调用 `stats` 验证连接。

## 工具清单

| 工具 | 用途 | 操作类型 |
| --- | --- | --- |
| `stats` | 笔记、速查卡、分类、标签总览和最近笔记 | 只读 |
| `search_notes` | 按关键词检索笔记，返回不含正文的列表 | 只读 |
| `get_note` | 获取完整正文、`content_hash`、分类和标签 | 只读 |
| `edit_note` | 定向删除、替换、插入、单字格式和目录生成；默认预览，提交时校验版本 | 写入 |
| `list_categories` | 获取带 `depth` 的分类列表 | 只读 |
| `list_tags` | 获取标签及使用次数，支持关键词过滤 | 只读 |
| `list_quick_refs` | 获取含正文的速查卡列表 | 只读 |
| `search_all` | 跨笔记、速查卡和资料库正文检索 | 只读 |
| `list_files` | 获取文件信息、抽取状态和字数，不含正文 | 只读 |
| `get_file_text` | 分页读取资料正文，默认返回 2000 字 | 只读 |
| `create_note` | 新建笔记，`tagNames` 中不存在的标签会自动创建 | 写入 |
| `update_note` | 更新指定字段，保留未提供的字段 | 写入 |
| `create_category` | 新建分类，可指定父分类 | 写入 |
| `create_tag` | 新建标签 | 写入 |
| `create_quick_ref` | 新建速查卡 | 写入 |
| `update_quick_ref` | 更新速查卡 | 写入 |
| `upload_file` | 上传 MCP 进程可读取的文件并抽取正文 | 写入 |
| `upload_file_from_url` | 下载全文直链并上传到资料库 | 写入 |
| `delete_note` | 删除笔记，带 `destructiveHint` 标注 | 删除 |
| `delete_quick_ref` | 删除速查卡，带 `destructiveHint` 标注 | 删除 |

工具参数以 `tools/list` 返回的 `inputSchema` 为准。资料删除未开放为 MCP 工具，可在应用界面或 REST 接口中操作。

## 使用约定与边界

- **先检索，再读取正文。** 笔记和资料列表不返回正文；资料正文使用 `offset` 与 `maxChars` 按需读取，返回总长度与 `hasMore`。
- **定向编辑校验版本。** 先用 `get_note` 取得 `content_hash`，调用 `edit_note` 时传入 `expected_hash`。默认 `dry_run` 为预览，确认后再提交。
- **部分更新保留其他字段。** `update_note` 会先读取现有笔记，再合并传入字段，避免后端全量替换接口清空未提供的内容。
- **上传后核对抽取状态。** 两个上传工具返回 `textStatus` 与 `textChars`；抽取失败或没有正文时附带 `notice`。扫描版 PDF 可能无法提取正文。
- **URL 上传接受全文直链。** 仅支持 HTTP/HTTPS，大小上限为 50 MiB，拒收 HTML 落地页。文件扩展名不明确时会参考 Content-Type；无法识别时需显式提供 `filename`。
- **工具失败返回可读原因。** 业务错误使用带 `isError` 的工具结果，便于客户端修正参数。
- **能力范围。** 提供 tools，不提供 resources 或 prompts；上传不做去重；删除操作没有回收站。
- **访问范围。** 服务没有独立鉴权，需由后端部署环境和 MCP 客户端控制访问权限。

## 环境变量

| 变量 | 默认值 | 用途 |
| --- | --- | --- |
| `LEARNHUB_BASE_URL` | `http://localhost:18080` | 后端地址 |
| `LEARNHUB_TIMEOUT_MS` | `15000` | 普通 HTTP 请求超时，单位毫秒 |
| `LEARNHUB_UPLOAD_TIMEOUT_MS` | `180000` | 上传及后端抽取正文超时，单位毫秒 |
| `LEARNHUB_DOWNLOAD_TIMEOUT_MS` | `180000` | 远端文件下载超时，单位毫秒 |

## 验证与排错

从仓库根目录运行只读冒烟检查：

```powershell
node mcp/smoke-test.mjs
```

检查包括协议握手、工具清单、只读查询、编辑预览、版本保护和错误路径。部分检查需要后端已有至少一篇非空笔记；空库时相关检查会失败。

需要验证写入流程时，可在测试环境运行：

```powershell
node mcp/smoke-test.mjs --write
```

写入检查会创建、更新和删除测试笔记、标签及资料，并尝试清理测试记录。结果由脚本报告，退出码 `0` 表示检查通过。

| 现象 | 检查方向 |
| --- | --- |
| 无法连接后端 | 确认后端运行，并核对 `LEARNHUB_BASE_URL` |
| 客户端未显示工具 | 检查启动命令、仓库路径和客户端日志，先运行只读冒烟检查 |
| `ENOENT` | 检查客户端能否找到 Node.js 可执行文件 |
| 笔记不存在 | 使用 `search_notes` 获取有效 ID |
| 正文版本已变化 | 重新读取笔记，使用最新 `content_hash` 编辑 |
| 上传或下载超时 | 检查文件大小、网络和正文抽取耗时，再调整对应超时变量 |
