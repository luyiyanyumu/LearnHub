# learn-hub MCP server

把 learn-hub 的 REST 接口暴露成 **MCP 工具**，让 DeepSeek Harness（或任何 MCP 客户端）能直接读写你的笔记库——
查笔记、建笔记、打标签、管分类、记速查卡，都不用手动复制粘贴。

- **零依赖**：一个 `.mjs` 文件，手写 JSON-RPC 2.0 over stdio，不 import 任何包（Node 18+ 自带 `fetch`）
- **不改后端**：全部走已有的 REST 接口，后端不用重启、不用加依赖
- **不带数据库连接**：它只是后端的客户端，所以后端停了它才有感知（首次调用时给出可读提示）

---

## 1. 前置：后端在运行

```powershell
# 后端（端口 18080）
cd backend
java -jar target/learn-hub-backend-0.0.1-SNAPSHOT.jar
```

## 2. 自测（不用装任何东西）

```powershell
cd mcp
node smoke-test.mjs            # 只读检查：握手 / 工具清单 / 检索 / 错误路径（14 项）
node smoke-test.mjs --write    # 额外跑「建 → 改标题验正文不丢 → 删」闭环（19 项，会自己清理）
```

期望输出 `结果：N 通过 / 0 失败`，退出码 0。

## 3. 接进 DeepSeek Harness

编辑 **`C:\Users\dyh\.dsh\profiles\web\cordis.patch.yml`**（你的 web profile，当前是 `[]`），
在数组里加一条 `insert`：

```yaml
- insert:
    - id: mcp-learnhub
      name: '@deepseek-ai/dsh-mcp-client'
      config:
        serverName: learnhub
        transport: stdio
        command: node
        args: ['C:/Users/dyh/WorkBuddy/2026-09-07-15-46-17/learn-hub/mcp/learn-hub-mcp.mjs']
        env:
          LEARNHUB_BASE_URL: http://localhost:18080
```

几个关键点：

| 项 | 说明 |
| --- | --- |
| `serverName` | 决定工具在模型侧的名字前缀：`mcp__learnhub__*`。要求 `[A-Za-z0-9_-]{1,32}` |
| `id` | 一个 MCP server 对应**一个插件实例**，id 必须唯一（你以后再加别的 server 就再插一条，换个 id 和 serverName） |
| 生效方式 | 你的 web profile 是 `"patchReload": "live"` —— 保存即热生效，**不用重启 dsh** |
| `command: node` | 若报 `ENOENT`，换成绝对路径 `'C:/Program Files/nodejs/node.exe'` |
| `env` | `dsh-mcp-client` 会**清洗掉环境变量**，所以要显式传 `LEARNHUB_BASE_URL`（不传则用默认值 `http://localhost:18080`） |
| 可选调优 | `toolCallTimeoutMs`（默认 60s）、`failOnStartupError: true`（连不上就拒绝启动，便于排错） |

**验证**：保存后再问 DSH 一句「用 learnhub 工具看看我知识库里有什么」，或直接让它调 `stats`。
工具没出现就看 dsh 的日志——默认 `failOnStartupError: false`，服务端起不来时 harness 照常启动、只记一条错误。

## 4. 工具清单（15 个）

| 工具 | 作用 | 类型 |
| --- | --- | --- |
| `stats` | 工作台总览：笔记/速查卡/分类/标签数 + 最近笔记 | 只读 |
| `search_notes` | 关键词全文检索笔记，返回精简列表（**不含正文**） | 只读 |
| `get_note` | 取一篇笔记的完整 Markdown 正文、分类与标签 | 只读 |
| `list_categories` | 分类树（拉平成带 `depth` 的列表） | 只读 |
| `list_tags` | 标签及其使用次数，可按关键词过滤 | 只读 |
| `list_quick_refs` | 速查卡列表（含正文） | 只读 |
| `search_all` | 跨资源统一检索（笔记 + 速查卡） | 只读 |
| `create_note` | 新建笔记，标签**可直接给中文名**（不存在自动创建） | 写入 |
| `update_note` | 改笔记，**只传要改的字段** | 写入 |
| `create_category` | 新建分类（可指定父分类） | 写入 |
| `create_tag` | 单独新建标签 | 写入 |
| `create_quick_ref` | 新建速查卡 | 写入 |
| `update_quick_ref` | 改速查卡 | 写入 |
| `delete_note` | 删除笔记（**不可恢复**，带 `destructiveHint` 标注） | 危险 |
| `delete_quick_ref` | 删除速查卡（**不可恢复**） | 危险 |

## 5. 几个刻意的设计决定

**① 列表不返回正文。** `search_notes` 只给 `id / title / summary / 分类 / 标签 / 时间`——
否则一次检索就把几万字塞进上下文。要正文就 `get_note`。这是 MCP 工具设计里最容易翻车的地方。

**② `update_note` 内部先取回再合并。** 后端 `PUT /api/notes/{id}` 是**全量替换**，
模型如果只想改标题却只传 `{title}`，正文会被清空。服务端替它把没传的字段补上，
所以模型只需要说「改哪一项」。这条是 `smoke-test --write` 里的关键断言。

**③ 标签用名字，不用 id。** `create_note` 接受 `tagNames: ["Java基础"]`，不存在的自动创建。
否则模型每次都得先 `list_tags` 再映射 id，既啰嗦又容易搞错。

**④ 工具级失败用 `isError` 结果，而不是 JSON-RPC 错误。** 这样模型能看到「笔记不存在: 99999999」
并自行改正；JSON-RPC 错误会让调用直接失败、模型看不到原因。

**⑤ stdout 只走协议帧，日志一律 stderr。** MCP stdio 的硬要求：往 stdout 打一行日志就会破坏帧解析。
反过来，客户端断开时不要 `process.exit()`——句柄仍在 flush 时强退会在 Windows 上撞 libuv 断言（实测踩过）。

**⑥ 声明并回声协议版本。** 客户端（DSH 用的官方 SDK）会先发自己的版本，规范要求服务端支持时**原样回同一个**；
这里对得上就回声，对不上退回 `2025-06-18`。同时必须声明 `capabilities.tools`，否则客户端直接报
`Server does not support tools`。

## 6. 已知边界

- **只桥接工具**：MCP 的 resources / prompts 不支持（与 DSH 的 MCP 桥接一致）。
- **删除是真的删**：没有回收站。`delete_*` 已加 `destructiveHint` 标注，但**真正的防线在权限侧**——
  这个 MCP server 跑在本机、能读写你的知识库，请按「给本机受信任程序授权」的尺度使用。
- **不做鉴权**：它假设后端只在本机（`localhost:18080`）。若后端暴露到网络，任何能起这个
  MCP server 的程序都能读写数据。
- **无分页续页处理**：15 个工具一次返回，未使用 `cursor`（数量远小于需要分页的量级）。

## 7. 排错

| 现象 | 原因 / 处理 |
| --- | --- |
| 工具在 DSH 里不出现 | 先 `node smoke-test.mjs` 确认服务端本身没问题；再看 dsh 日志；必要时给配置加 `failOnStartupError: true` |
| `连不上 learn-hub 后端` | 后端没跑，或端口不对 → 起后端 / 改 `LEARNHUB_BASE_URL` |
| `笔记不存在: xxx` | 只是 id 不对，让模型先 `search_notes` 拿 id |
| 改了服务端要重启吗 | stdio 模式下 DSH 每次会话按需拉起进程；改完直接重跑 `smoke-test.mjs` 验证即可 |
| `ENOENT` 找不到 node | 把 `command` 换成 `'C:/Program Files/nodejs/node.exe'` |

## 8. 环境变量

| 变量 | 默认 | 说明 |
| --- | --- | --- |
| `LEARNHUB_BASE_URL` | `http://localhost:18080` | 后端地址 |
| `LEARNHUB_TIMEOUT_MS` | `15000` | 单次 HTTP 超时 |
