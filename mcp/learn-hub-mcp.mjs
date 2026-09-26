#!/usr/bin/env node
/**
 * learn-hub MCP server —— 把 learn-hub 的 REST 接口暴露成 MCP 工具，
 * 让 DeepSeek Harness（或任何 MCP 客户端）能直接读写笔记库。
 *
 * 设计取舍：
 * 1. **零依赖、手写协议**。MCP 的 stdio 传输就是「换行分隔的 JSON-RPC 2.0」，
 *    只需要 initialize / tools/list / tools/call 三个方法，不值得为此引入官方 SDK 包
 *    （与本项目后端手写 DeepSeek 客户端、无 SDK 依赖的做法一致）。
 *    Node 18+ 自带 fetch，所以整个文件不 import 任何东西。
 * 2. **stdout 只走协议帧，日志一律 stderr**。这是 MCP stdio 的硬要求：
 *    往 stdout 打一行日志就会破坏帧解析（DSH 自己的 SDK 服务端也有同样约束）。
 * 3. **不改后端**。全部通过已有的 REST 接口访问，所以后端不需要重启、不需要新依赖。
 * 4. 工具粒度贴着「人怎么用这个知识库」设计，而不是贴着表结构。
 *
 * 环境变量：
 *   LEARNHUB_BASE_URL   后端地址，默认 http://localhost:18080
 *   LEARNHUB_TIMEOUT_MS 单次 HTTP 超时，默认 15000
 *
 * 本地自测（不依赖任何客户端）：
 *   echo '{"jsonrpc":"2.0","id":1,"method":"tools/list"}' | node learn-hub-mcp.mjs
 */

const BASE = (process.env.LEARNHUB_BASE_URL || 'http://localhost:18080').replace(/\/+$/, '')
const TIMEOUT_MS = Number(process.env.LEARNHUB_TIMEOUT_MS || 15000)

/**
 * 我们声明支持的协议版本。客户端（DSH 用的官方 SDK）会先发它自己的版本，
 * 规范要求：服务端支持该版本时**必须原样回同一个**，否则回一个自己支持的。
 * 所以这里做成「能对上就回声，对不上就退回 2025-06-18」—— 客户端那边只校验
 * 返回的版本是否在它的支持列表里，回声必然通过。
 */
const KNOWN_PROTOCOL_VERSIONS = ['2025-11-25', '2025-06-18', '2025-03-26', '2024-11-05', '2024-10-07']
const FALLBACK_PROTOCOL_VERSION = '2025-06-18'

/** 日志只写 stderr —— stdout 是协议通道 */
function log(...args) {
  process.stderr.write('[learn-hub-mcp] ' + args.join(' ') + '\n')
}

// ---------------------------------------------------------------- HTTP 客户端

/**
 * 调 learn-hub 接口并拆掉统一返回体 {code, msg, data}。
 * 失败时抛出带可读信息的 Error，由 tools/call 转成 isError 结果。
 */
async function api(method, path, { body, query } = {}) {
  const url = new URL(BASE + path)
  for (const [k, v] of Object.entries(query || {})) {
    if (v !== undefined && v !== null && v !== '') url.searchParams.set(k, String(v))
  }
  let resp
  try {
    resp = await fetch(url, {
      method,
      headers: body ? { 'Content-Type': 'application/json' } : undefined,
      body: body ? JSON.stringify(body) : undefined,
      signal: AbortSignal.timeout(TIMEOUT_MS),
    })
  } catch (e) {
    throw new Error(
      `连不上 learn-hub 后端（${BASE}）：${e?.message || e}。` +
        `请确认后端在运行（backend 里 java -jar target/learn-hub-backend-*.jar），` +
        `或设置 LEARNHUB_BASE_URL 指向正确地址。`,
    )
  }
  let payload = null
  try {
    payload = await resp.json()
  } catch {
    throw new Error(`后端返回了非 JSON 内容（HTTP ${resp.status}）`)
  }
  if (!resp.ok || payload.code !== 200) {
    throw new Error(payload?.msg || `请求失败（HTTP ${resp.status}）`)
  }
  return payload.data
}

// ---------------------------------------------------------------- 工具实现

/** 精简笔记列表项，避免把整篇正文塞进列表结果（正文用 get_note 单独取） */
function briefNote(n) {
  return {
    id: n.id,
    title: n.title,
    summary: n.summary || '',
    categoryId: n.categoryId ?? null,
    categoryName: n.categoryName || null,
    tags: (n.tags || []).map((t) => t.name),
    updatedAt: n.updatedAt,
  }
}

/** 标签名 → id（不存在则新建）。让模型可以直接说「打上 Java基础 标签」而不必先查 id。 */
async function resolveTagIds(names) {
  if (!names || !names.length) return []
  const all = await api('GET', '/api/tags')
  const byName = new Map(all.map((t) => [String(t.name).trim().toLowerCase(), t.id]))
  const ids = []
  for (const raw of names) {
    const name = String(raw).trim()
    if (!name) continue
    const hit = byName.get(name.toLowerCase())
    if (hit) {
      ids.push(hit)
      continue
    }
    const created = await api('POST', '/api/tags', { body: { name } })
    ids.push(created.id)
    log(`新建标签: ${name} (#${created.id})`)
  }
  return ids
}

const tools = {
  // ---------- 读 ----------
  async stats() {
    const s = await api('GET', '/api/stats')
    return {
      noteTotal: s.noteTotal,
      refTotal: s.refTotal,
      categoryTotal: s.categoryTotal,
      tagTotal: s.tagTotal,
      recentNotes: (s.recentNotes || []).map(briefNote),
    }
  },

  async search_notes({ kw, categoryId, tagId, page = 1, size = 10 }) {
    const r = await api('GET', '/api/notes', { query: { kw, categoryId, tagId, page, size } })
    return { total: r.total, page, size, list: (r.list || []).map(briefNote) }
  },

  async get_note({ id }) {
    const n = await api('GET', `/api/notes/${id}`)
    return {
      id: n.id,
      title: n.title,
      content: n.content || '',
      categoryId: n.categoryId ?? null,
      categoryName: n.categoryName || null,
      tags: (n.tags || []).map((t) => ({ id: t.id, name: t.name })),
      updatedAt: n.updatedAt,
    }
  },

  async list_categories() {
    const tree = await api('GET', '/api/categories')
    const flat = []
    const walk = (nodes, depth) => {
      for (const n of nodes || []) {
        flat.push({ id: n.id, name: n.name, parentId: n.parentId ?? 0, depth })
        walk(n.children, depth + 1)
      }
    }
    walk(tree, 0)
    return { count: flat.length, categories: flat }
  },

  async list_tags({ kw } = {}) {
    const all = await api('GET', '/api/tags')
    const list = kw ? all.filter((t) => String(t.name).includes(kw)) : all
    return { count: list.length, tags: list.map((t) => ({ id: t.id, name: t.name, useCount: t.useCount })) }
  },

  async list_quick_refs({ kw, categoryId } = {}) {
    const list = await api('GET', '/api/quick-refs', { query: { kw, categoryId } })
    return {
      count: list.length,
      list: list.map((r) => ({
        id: r.id,
        title: r.title,
        content: r.content || '',
        categoryId: r.categoryId ?? null,
        categoryName: r.categoryName || null,
        updatedAt: r.updatedAt,
      })),
    }
  },

  async search_all({ kw }) {
    const r = await api('GET', '/api/knowledge/search', { query: { kw } })
    return r
  },

  // ---------- 写 ----------
  async create_note({ title, content, categoryId, tagNames, tagIds }) {
    const ids = [...(tagIds || []), ...(await resolveTagIds(tagNames))]
    const n = await api('POST', '/api/notes', {
      body: { title, content: content || '', categoryId: categoryId ?? null, tagIds: ids },
    })
    return { ok: true, id: n.id, title: n.title, tags: (n.tags || []).map((t) => t.name) }
  },

  /**
   * 注意：PUT /api/notes/{id} 是**全量替换**，只传 title 会把正文清空。
   * 所以这里先取回原笔记，把传入的字段合并上去再提交 —— 模型只需要说改哪一项。
   */
  async update_note({ id, title, content, categoryId, tagNames, tagIds }) {
    const cur = await api('GET', `/api/notes/${id}`)
    const merged = {
      title: title !== undefined ? title : cur.title,
      content: content !== undefined ? content : cur.content || '',
      categoryId: categoryId !== undefined ? categoryId : cur.categoryId ?? null,
      tagIds:
        tagIds !== undefined || tagNames !== undefined
          ? [...(tagIds || []), ...(await resolveTagIds(tagNames))]
          : (cur.tags || []).map((t) => t.id),
    }
    const n = await api('PUT', `/api/notes/${id}`, { body: merged })
    return { ok: true, id: n.id, title: n.title, contentLength: (n.content || '').length }
  },

  async delete_note({ id }) {
    const n = await api('GET', `/api/notes/${id}`)
    await api('DELETE', `/api/notes/${id}`)
    return { ok: true, deletedId: id, title: n.title }
  },

  async create_category({ name, parentId = 0 }) {
    await api('POST', '/api/categories', { body: { name, parentId } })
    const tree = await api('GET', '/api/categories')
    const flat = []
    const walk = (nodes) => {
      for (const n of nodes || []) {
        flat.push({ id: n.id, name: n.name, parentId: n.parentId ?? 0 })
        walk(n.children)
      }
    }
    walk(tree)
    return { ok: true, created: name, categories: flat }
  },

  async create_tag({ name }) {
    const t = await api('POST', '/api/tags', { body: { name } })
    return { ok: true, id: t.id, name: t.name }
  },

  async create_quick_ref({ title, content, categoryId }) {
    const r = await api('POST', '/api/quick-refs', {
      body: { title, content: content || '', categoryId: categoryId ?? null },
    })
    return { ok: true, id: r.id, title: r.title }
  },

  /** 同样先取回再合并：PUT 是全量替换 */
  async update_quick_ref({ id, title, content, categoryId }) {
    const cur = await api('GET', `/api/quick-refs/${id}`)
    const r = await api('PUT', `/api/quick-refs/${id}`, {
      body: {
        title: title !== undefined ? title : cur.title,
        content: content !== undefined ? content : cur.content || '',
        categoryId: categoryId !== undefined ? categoryId : cur.categoryId ?? null,
      },
    })
    return { ok: true, id: r.id, title: r.title }
  },

  async delete_quick_ref({ id }) {
    const r = await api('GET', `/api/quick-refs/${id}`)
    await api('DELETE', `/api/quick-refs/${id}`)
    return { ok: true, deletedId: id, title: r.title }
  },
}

/** 工具的对外声明：名字、说明、入参 JSON Schema */
const TOOL_DEFS = [
  {
    name: 'stats',
    description: '工作台总览：笔记数、速查卡数、分类数、标签数与最近更新的笔记。不确定库里有什么时先调这个。',
    inputSchema: { type: 'object', properties: {}, additionalProperties: false },
    annotations: { readOnlyHint: true },
  },
  {
    name: 'search_notes',
    description: '按关键词全文检索笔记，返回精简列表（不含正文）。拿到 id 后用 get_note 取全文。',
    inputSchema: {
      type: 'object',
      properties: {
        kw: { type: 'string', description: '关键词，可留空表示不过滤' },
        categoryId: { type: 'number', description: '限定分类 id（见 list_categories）' },
        tagId: { type: 'number', description: '限定标签 id（见 list_tags）' },
        page: { type: 'number', description: '页码，从 1 开始，默认 1' },
        size: { type: 'number', description: '每页条数，默认 10' },
      },
      additionalProperties: false,
    },
    annotations: { readOnlyHint: true },
  },
  {
    name: 'get_note',
    description: '按 id 取一篇笔记的完整 Markdown 正文、分类与标签。',
    inputSchema: {
      type: 'object',
      properties: { id: { type: 'number', description: '笔记 id' } },
      required: ['id'],
      additionalProperties: false,
    },
    annotations: { readOnlyHint: true },
  },
  {
    name: 'list_categories',
    description: '列出全部分类（多层树会被拉平成带 depth 的列表）。新建笔记前用它确定 categoryId。',
    inputSchema: { type: 'object', properties: {}, additionalProperties: false },
    annotations: { readOnlyHint: true },
  },
  {
    name: 'list_tags',
    description: '列出全部标签及其使用次数，可选按关键词过滤。',
    inputSchema: {
      type: 'object',
      properties: { kw: { type: 'string', description: '标签名关键词，可留空' } },
      additionalProperties: false,
    },
    annotations: { readOnlyHint: true },
  },
  {
    name: 'list_quick_refs',
    description: '列出速查卡（命令/API 速记），支持关键词过滤。列表已含正文，通常不必再单取。',
    inputSchema: {
      type: 'object',
      properties: {
        kw: { type: 'string', description: '关键词，可留空' },
        categoryId: { type: 'number', description: '限定分类 id' },
      },
      additionalProperties: false,
    },
    annotations: { readOnlyHint: true },
  },
  {
    name: 'search_all',
    description: '跨资源统一检索：一次同时搜笔记与速查卡，返回带摘要的命中项。适合「我记过 X 吗」这类问题。',
    inputSchema: {
      type: 'object',
      properties: { kw: { type: 'string', description: '关键词' } },
      required: ['kw'],
      additionalProperties: false,
    },
    annotations: { readOnlyHint: true },
  },
  {
    name: 'create_note',
    description: '新建一篇 Markdown 笔记。标签可以直接给中文名（不存在会自动创建），无需先查 id。',
    inputSchema: {
      type: 'object',
      properties: {
        title: { type: 'string', description: '标题，不能为空' },
        content: { type: 'string', description: 'Markdown 正文' },
        categoryId: { type: 'number', description: '分类 id，可省略' },
        tagNames: {
          type: 'array',
          items: { type: 'string' },
          description: '标签名数组，如 ["Java基础","并发"]，不存在的会自动创建',
        },
        tagIds: { type: 'array', items: { type: 'number' }, description: '已知标签 id 数组（与 tagNames 可混用）' },
      },
      required: ['title'],
      additionalProperties: false,
    },
  },
  {
    name: 'update_note',
    description:
      '修改笔记。**只需传要改的字段**，其余自动保留（服务端内部会先取回原文再合并提交，避免全量替换把正文清空）。',
    inputSchema: {
      type: 'object',
      properties: {
        id: { type: 'number', description: '笔记 id' },
        title: { type: 'string', description: '新标题，不改就别传' },
        content: { type: 'string', description: '新的完整 Markdown 正文，不改就别传' },
        categoryId: { type: 'number', description: '新分类 id' },
        tagNames: { type: 'array', items: { type: 'string' }, description: '用这些标签**整体替换**原标签' },
        tagIds: { type: 'array', items: { type: 'number' }, description: '用这些标签 id 整体替换' },
      },
      required: ['id'],
      additionalProperties: false,
    },
  },
  {
    name: 'delete_note',
    description: '删除一篇笔记（不可恢复）。会先取回标题再删，返回被删的是什么。',
    inputSchema: {
      type: 'object',
      properties: { id: { type: 'number', description: '笔记 id' } },
      required: ['id'],
      additionalProperties: false,
    },
    annotations: { readOnlyHint: false, destructiveHint: true },
  },
  {
    name: 'create_category',
    description: '新建分类，可指定父分类 id（顶层用 0 或省略）。返回新建后的完整分类列表。',
    inputSchema: {
      type: 'object',
      properties: {
        name: { type: 'string', description: '分类名' },
        parentId: { type: 'number', description: '父分类 id，顶层留空或传 0' },
      },
      required: ['name'],
      additionalProperties: false,
    },
  },
  {
    name: 'create_tag',
    description: '单独新建一个标签（多数情况下用 create_note 的 tagNames 就够了）。',
    inputSchema: {
      type: 'object',
      properties: { name: { type: 'string', description: '标签名' } },
      required: ['name'],
      additionalProperties: false,
    },
  },
  {
    name: 'create_quick_ref',
    description: '新建一张速查卡（命令 / API 速记），正文通常是短小的命令或代码片段。',
    inputSchema: {
      type: 'object',
      properties: {
        title: { type: 'string', description: '速查卡标题，如「git 回滚到某次提交」' },
        content: { type: 'string', description: '内容，Markdown 或代码块' },
        categoryId: { type: 'number', description: '分类 id，可省略' },
      },
      required: ['title'],
      additionalProperties: false,
    },
  },
  {
    name: 'update_quick_ref',
    description: '修改速查卡，只传要改的字段（同 update_note，内部先取回再合并）。',
    inputSchema: {
      type: 'object',
      properties: {
        id: { type: 'number', description: '速查卡 id' },
        title: { type: 'string', description: '新标题' },
        content: { type: 'string', description: '新内容' },
        categoryId: { type: 'number', description: '新分类 id' },
      },
      required: ['id'],
      additionalProperties: false,
    },
  },
  {
    name: 'delete_quick_ref',
    description: '删除一张速查卡（不可恢复）。',
    inputSchema: {
      type: 'object',
      properties: { id: { type: 'number', description: '速查卡 id' } },
      required: ['id'],
      additionalProperties: false,
    },
    annotations: { readOnlyHint: false, destructiveHint: true },
  },
]

// ---------------------------------------------------------------- JSON-RPC 处理

function reply(id, result) {
  send({ jsonrpc: '2.0', id, result })
}
function replyError(id, code, message) {
  send({ jsonrpc: '2.0', id, error: { code, message } })
}
function send(msg) {
  // 一行一个 JSON-RPC 帧；JSON.stringify 会把换行转义，所以帧内不会出现裸换行
  process.stdout.write(JSON.stringify(msg) + '\n')
}

async function handle(msg) {
  const { id, method, params } = msg
  const isNotification = id === undefined || id === null

  switch (method) {
    case 'initialize': {
      const asked = params?.protocolVersion
      const version = KNOWN_PROTOCOL_VERSIONS.includes(asked) ? asked : FALLBACK_PROTOCOL_VERSION
      log(`initialize: client=${params?.clientInfo?.name || '?'} 协商协议=${version}`)
      reply(id, {
        protocolVersion: version,
        // 必须声明 tools 能力：MCP 客户端会据此校验，缺了会直接报 "Server does not support tools"
        capabilities: { tools: {} },
        serverInfo: { name: 'learn-hub', version: '1.0.0' },
        instructions:
          '这个服务连接的是本地 learn-hub 知识库（Markdown 笔记 + 速查卡 + 分类/标签）。' +
          '不确定有什么内容时先调 stats 或 search_all。',
      })
      return
    }
    case 'notifications/initialized':
      log('客户端已初始化')
      return
    case 'ping':
      if (!isNotification) reply(id, {})
      return
    case 'tools/list':
      reply(id, { tools: TOOL_DEFS })
      return
    case 'tools/call': {
      const name = params?.name
      const args = params?.arguments || {}
      const fn = tools[name]
      if (!fn) {
        replyError(id, -32602, `未知工具: ${name}`)
        return
      }
      try {
        const out = await fn(args)
        // 结果以文本返回：DSH 的 MCP 桥接按块顺序以普通文本呈现给模型
        reply(id, { content: [{ type: 'text', text: JSON.stringify(out, null, 2) }] })
      } catch (e) {
        // 工具级失败用 isError 结果，而不是 JSON-RPC 错误 —— 这样模型能看到原因并自行修正
        log(`工具 ${name} 失败: ${e?.message || e}`)
        reply(id, { content: [{ type: 'text', text: `调用失败：${e?.message || e}` }], isError: true })
      }
      return
    }
    default:
      if (!isNotification) replyError(id, -32601, `不支持的方法: ${method}`)
  }
}

// ---------------------------------------------------------------- stdin 主循环

let buf = ''
process.stdin.setEncoding('utf8')
process.stdin.on('data', (chunk) => {
  buf += chunk
  let idx
  while ((idx = buf.indexOf('\n')) >= 0) {
    const line = buf.slice(0, idx).trim()
    buf = buf.slice(idx + 1)
    if (!line) continue
    let msg
    try {
      msg = JSON.parse(line)
    } catch {
      log('收到非 JSON 行，已忽略')
      continue
    }
    // 串行处理：await 保证不会出现响应乱序（工具调用本身是并发的，但这里保持简单可预测）
    handle(msg).catch((e) => log('处理异常: ' + (e?.message || e)))
  }
})
process.stdin.on('end', () => {
  log('stdin 关闭，退出')
  // 这里**不要**用 process.exit(0)：stdout/stderr 可能还在 flush，
  // 句柄仍在关闭时强退会撞出 libuv 断言（Windows 上实测踩到）。
  // stdin 关闭后本进程已无存活句柄，事件循环自然结束即可。
  process.exitCode = 0
})

log(`启动完成，后端地址 ${BASE}`)
