# learn-hub MCP server

把 learn-hub 的 REST 接口暴露成 **MCP 工具**，让 DeepSeek Harness（或任何 MCP 客户端）能直接读写你的知识库——
查笔记、建笔记、打标签、管分类、记速查卡、**把找到的论文一键存进资料库**，都不用手动复制粘贴。

- **零 npm 依赖**：一个 `.mjs` 文件，手写 JSON-RPC 2.0 over stdio，不 import 任何包
  （Node 18+ 自带 `fetch` / `FormData` / `Blob`，文件读写用 `node:fs/promises`）
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
node smoke-test.mjs            # 只读检查：握手 / 工具清单 / 笔记检索 / 资料库读取 / 错误路径（19 项）
node smoke-test.mjs --write    # 额外跑「建笔记→改标题验正文不丢→删」+「本机文件入库→抽正文→能检索→删」
                               # 两个写入闭环（31 项，全部自己清理）
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

## 4. 工具清单（19 个）

| 工具 | 作用 | 类型 |
| --- | --- | --- |
| `stats` | 工作台总览：笔记/速查卡/分类/标签数 + 最近笔记 | 只读 |
| `search_notes` | 关键词全文检索笔记，返回精简列表（**不含正文**） | 只读 |
| `get_note` | 取一篇笔记的完整 Markdown 正文、分类与标签 | 只读 |
| `list_categories` | 分类树（拉平成带 `depth` 的列表） | 只读 |
| `list_tags` | 标签及其使用次数，可按关键词过滤 | 只读 |
| `list_quick_refs` | 速查卡列表（含正文） | 只读 |
| `search_all` | 跨资源统一检索（笔记 + 速查卡 + **资料库正文**） | 只读 |
| `list_files` | 资料库列表：文件名/类型/大小/**抽取状态与字数**（不含正文） | 只读 |
| `get_file_text` | 取资料抽出来的正文片段（默认 2000 字，`offset` 翻页） | 只读 |
| `create_note` | 新建笔记，标签**可直接给中文名**（不存在自动创建） | 写入 |
| `update_note` | 改笔记，**只传要改的字段** | 写入 |
| `create_category` | 新建分类（可指定父分类） | 写入 |
| `create_tag` | 单独新建标签 | 写入 |
| `create_quick_ref` | 新建速查卡 | 写入 |
| `update_quick_ref` | 改速查卡 | 写入 |
| `upload_file` | **把本机文件加入资料库**（后端立刻抽正文 → 可被检索） | 写入 |
| `upload_file_from_url` | **从 URL 下载全文并加入资料库**（"找到论文→入库"一步到位） | 写入 |
| `delete_note` | 删除笔记（**不可恢复**，带 `destructiveHint` 标注） | 危险 |
| `delete_quick_ref` | 删除速查卡（**不可恢复**） | 危险 |

**典型用法：找论文 → 入库 → 检索。** 让模型查一篇论文，拿到 arXiv/出版商的 PDF 直链后调
`upload_file_from_url`，再 `list_files` 确认 `textStatus=ok`，之后这篇论文的正文就进了统一检索
（`search_all`）与知识库问答的召回范围。资料库的删除没有开放成 MCP 工具（不做回收站，误删代价太大），
要删请用界面或 REST。

> 应用内那个悬浮智能体也有一套等价工具（`add_file_from_url` / `list_files`，写操作走"待确认卡片"），
> 所以"帮我把这篇论文放进资料库"在应用里与在 DSH 里都能做。两者共用后端同一条落盘/抽文链路。

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

**⑦ 资料列表同样不返回正文，而且正文要分页给。** 资料库里的 PDF 抽出来常有十几万字
（实测一份论文 88,764 字），`get_file_text` 默认只给 2000 字 + 总长度 + `hasMore`，模型按需翻页。
这和 ① 是同一条原则：**列表与默认返回都要按"上下文预算"设计**。

**⑧ URL 入库要按 Content-Type 认类型，认不出就拒收。** 实测踩过：arXiv 的 PDF 直链是
`https://arxiv.org/pdf/1706.03762`，**没有 `.pdf` 后缀**，按 URL 猜出来的扩展名是 `03762`，
后端于是把它判成 `unsupported`——文件进去了、正文没抽出来，界面上有记录、检索里却没有它，
是最难发现的那种脏数据。现在：后缀不认识就用 Content-Type 补（`application/pdf` → `.pdf`），
补不出来的直接报错，让调用方显式给带扩展名的 `filename`。

**⑨ 入库后必须能一眼看出"抽没抽出正文"。** 两个上传工具都会回 `textStatus` / `textChars`，
抽不出时额外给一句 `notice`（例如"扫描版 PDF 抽不出字，只能按文件名与说明检索"）——
避免模型把"上传成功"误读成"已经能检索了"。

## 6. 已知边界

- **只桥接工具**：MCP 的 resources / prompts 不支持（与 DSH 的 MCP 桥接一致）。
- **删除是真的删**：没有回收站。`delete_*` 已加 `destructiveHint` 标注，但**真正的防线在权限侧**——
  这个 MCP server 跑在本机、能读写你的知识库，请按「给本机受信任程序授权」的尺度使用。
  （资料库的删除**没有**开放成工具，就是为了少一个误删入口。）
- **上传大文件会等**：`upload_file` / `upload_file_from_url` 会在后端同步抽正文，几十 MB 的 PDF
  可能要几十秒；超时另有两档配置（见第 8 节），默认 180s。
- **URL 入库只认全文直链**：返回 `text/html` 会被拒收（那是落地页不是论文），
  且大小上限 50MB（与后端 `multipart.max-file-size` 一致）。它**不做**任何绕过付费墙的事。
- **不做去重**：同一篇论文传两次就是两条记录（arXiv 版与会议版本来就该分开存）。
- **不做鉴权**：它假设后端只在本机（`localhost:18080`）。若后端暴露到网络，任何能起这个
  MCP server 的程序都能读写数据。
- **无分页续页处理**：工具清单一次返回，未使用 `cursor`（数量远小于需要分页的量级）。

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
| `LEARNHUB_UPLOAD_TIMEOUT_MS` | `180000` | 上传资料（含后端抽正文）的超时 |
| `LEARNHUB_DOWNLOAD_TIMEOUT_MS` | `180000` | `upload_file_from_url` 下载远端的超时 |
