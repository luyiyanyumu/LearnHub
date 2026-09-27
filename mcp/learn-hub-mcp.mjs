#!/usr/bin/env node
/**
 * learn-hub MCP server —— 把 learn-hub 的 REST 接口暴露成 MCP 工具，
 * 让 DeepSeek Harness（或任何 MCP 客户端）能直接读写笔记库**与资料库**。
 *
 * 设计取舍：
 * 1. **零 npm 依赖、手写协议**。MCP 的 stdio 传输就是「换行分隔的 JSON-RPC 2.0」，
 *    只需要 initialize / tools/list / tools/call 三个方法，不值得为此引入官方 SDK 包
 *    （与本项目后端手写 DeepSeek 客户端、无 SDK 依赖的做法一致）。
 *    Node 18+ 自带 fetch / FormData / Blob，所以除了 node: 内置模块外不 import 任何包。
 * 2. **stdout 只走协议帧，日志一律 stderr**。这是 MCP stdio 的硬要求：
 *    往 stdout 打一行日志就会破坏帧解析（DSH 自己的 SDK 服务端也有同样约束）。
 * 3. **不改后端**。全部通过已有的 REST 接口访问，所以后端不需要重启、不需要新依赖。
 * 4. 工具粒度贴着「人怎么用这个知识库」设计，而不是贴着表结构。
 *
 * 环境变量：
 *   LEARNHUB_BASE_URL   后端地址，默认 http://localhost:18080
 *   LEARNHUB_TIMEOUT_MS 单次 HTTP 超时，默认 15000（上传/下载另有更长的超时）
 *
 * 本地自测（不依赖任何客户端）：
 *   echo '{"jsonrpc":"2.0","id":1,"method":"tools/list"}' | node learn-hub-mcp.mjs
 */

import { readFile, stat } from 'node:fs/promises'
import { basename, extname } from 'node:path'

const BASE = (process.env.LEARNHUB_BASE_URL || 'http://localhost:18080').replace(/\/+$/, '')
const TIMEOUT_MS = Number(process.env.LEARNHUB_TIMEOUT_MS || 15000)
/** 上传：后端 50MB 上限 + 抽取正文要时间，给的余量更宽 */
const UPLOAD_TIMEOUT_MS = Number(process.env.LEARNHUB_UPLOAD_TIMEOUT_MS || 180000)
/** 下载远端论文 PDF：只用来入库，所以跟上传同一档超时 */
const DOWNLOAD_TIMEOUT_MS = Number(process.env.LEARNHUB_DOWNLOAD_TIMEOUT_MS || 180000)
/** 与后端 spring.servlet.multipart.max-file-size 对齐，超了直接在产品侧拦下 */
const MAX_UPLOAD_BYTES = 50 * 1024 * 1024

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

// ---------------------------------------------------------------- 资料库（文件）辅助

/** 精简资料项：**不含正文**（正文动辄十几万字，列表里带上就等于把上下文塞爆） */
function briefFile(f) {
  return {
    id: f.id,
    originName: f.originName,
    ext: f.ext || '',
    size: f.size,
    textStatus: f.textStatus || '',
    textChars: f.textChars ?? 0,
    categoryId: f.categoryId ?? null,
    categoryName: f.categoryName || null,
    summary: f.summary || '',
    createdAt: f.createdAt || '',
  }
}

/** 上传字节流到资料库：走后端 multipart 接口，落盘后后端会立刻抽正文 */
async function uploadBytes(bytes, filename, categoryId) {
  const fd = new FormData()
  // Buffer 是合法 BlobPart；带上文件名后端才认得出扩展名（决定怎么抽正文）
  fd.append('file', new Blob([bytes]), filename)
  const url = new URL(BASE + '/api/files/upload')
  if (categoryId) url.searchParams.set('categoryId', String(categoryId))
  let resp
  try {
    resp = await fetch(url, { method: 'POST', body: fd, signal: AbortSignal.timeout(UPLOAD_TIMEOUT_MS) })
  } catch (e) {
    throw new Error(`上传失败（${BASE}）：${e?.message || e}`)
  }
  let payload = null
  try {
    payload = await resp.json()
  } catch {
    throw new Error(`上传返回了非 JSON 内容（HTTP ${resp.status}）`)
  }
  if (!resp.ok || payload.code !== 200) {
    throw new Error(payload?.msg || `上传失败（HTTP ${resp.status}）`)
  }
  return payload.data
}

/**
 * 后端**抽得出正文**的扩展名（镜像 `DocumentTextService` 的 PDF/Office/文本三组白名单）。
 * 为什么在这里也要维护一份：往资料库塞一份抽不出正文的文件，界面上有记录、检索里却没有它 ——
 * 是"看起来成功、实际查不到"的隐形脏数据，宁可在入口处就拦下来（实测踩过：arxiv.org/pdf/1706.03762
 * 没有 .pdf 后缀，按 URL 猜出来的是 "03762"，后端直接判 unsupported，整份论文白传）。
 */
const EXTRACTABLE_EXTS = new Set([
  'pdf',
  'doc', 'docx', 'docm', 'xls', 'xlsx', 'xlsm', 'ppt', 'pptx', 'pptm',
  'md', 'markdown', 'txt', 'text', 'log', 'csv', 'tsv', 'json', 'jsonc', 'yml', 'yaml',
  'xml', 'html', 'htm', 'css', 'scss', 'less', 'sql', 'properties', 'ini', 'conf', 'env',
  'toml', 'java', 'js', 'mjs', 'cjs', 'ts', 'vue', 'jsx', 'tsx', 'py', 'go', 'rs', 'rb',
  'php', 'c', 'h', 'cpp', 'hpp', 'cs', 'kt', 'swift', 'sh', 'bash', 'zsh', 'ps1', 'bat',
  'cmd', 'gradle', 'groovy', 'lua', 'r', 'm', 'pl', 'scala', 'dart', 'tex',
])

/** Content-Type → 扩展名（只在 URL 后缀不可信时用） */
function extFromContentType(contentType) {
  const t = (contentType || '').toLowerCase()
  if (t.includes('pdf')) return '.pdf'
  if (t.includes('wordprocessingml')) return '.docx'
  if (t.includes('spreadsheetml')) return '.xlsx'
  if (t.includes('presentationml')) return '.pptx'
  if (t.includes('msword')) return '.doc'
  if (t.includes('text/markdown')) return '.md'
  if (t.includes('text/plain')) return '.txt'
  if (t.includes('text/csv')) return '.csv'
  if (t.includes('json')) return '.json'
  return ''
}

/**
 * 由 URL / Content-Type 猜一个**带可抽取扩展名**的文件名（后端用它决定怎么抽正文）。
 * 判不出可抽取类型时**直接抛错**，而不是入库一份查不到的资料。
 */
function filenameFrom(url, contentType, given) {
  const fromUrl = decodeURIComponent(new URL(url).pathname.split('/').filter(Boolean).pop() || '')
  const raw = (given || '').trim() || fromUrl
  const name = basename(raw).replace(/[\\/:*?"<>|]/g, '_').slice(0, 120)
  const ext = extname(name).slice(1).toLowerCase()
  if (EXTRACTABLE_EXTS.has(ext)) return name
  const fromType = extFromContentType(contentType)
  if (fromType) {
    // 后缀不可信（如 arxiv.org/pdf/1706.03762 的 ".03762"）：整名保留，再补一个真后缀
    return (name || 'download') + fromType
  }
  throw new Error(
    `这份文件的类型后端抽不出正文（文件名 "${name}"，Content-Type "${contentType || '未知'}"），` +
      `入库后会是一条检索不到的空壳，所以拒收。` +
      `如果它确实是论文全文，请传一个带扩展名的 filename（如 "paper.pdf"）；` +
      `如果只是网页/压缩包，先换成全文文件，或用 upload_file 从本机路径自行入库（那时会明确标 unsupported）。`,
  )
}

/** 抽不出正文时给模型一句明确提示（别让"入库成功"被误读成"能检索了"） */
function extractionNotice(uploaded) {
  const status = uploaded?.textStatus
  if (status === 'ok') return null
  const why = {
    empty: '文件里没有可提取的文字（扫描版 PDF / 纯图片）',
    unsupported: '这个格式不支持抽正文（图片、压缩包等）',
    skipped: '文件太大，后端跳过了抽取',
    failed: '后端抽取失败',
    pending: '后端还在抽取',
  }[status] || `抽取状态：${status || '未知'}`
  return `${why} —— 这份资料只能按文件名与手写「说明」参与检索`
}


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
  // ---------- 读：笔记 ----------
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

  // ---------- 读：资料库（上传的 PDF/Office/文本，正文由后端抽好） ----------

  async list_files({ kw, categoryId } = {}) {
    const list = await api('GET', '/api/files', { query: { kw, categoryId } })
    return { count: list.length, files: list.map(briefFile) }
  },

  /**
   * 取资料正文的**一段**。
   *
   * 为什么必须分页：抽出来的正文常见十几万字，一次全给等于把上下文烧光。
   * 默认给 2000 字 + 总长度 + hasMore，模型按需翻页（offset）。
   * `textStatus` 一并返回：empty/unsupported/failed 时要能解释"为什么没正文"。
   */
  async get_file_text({ id, offset = 0, maxChars = 2000 }) {
    const d = await api('GET', `/api/files/${id}/text`)
    const text = d.text || ''
    const start = Math.max(0, Number(offset) || 0)
    const size = Math.max(200, Math.min(20000, Number(maxChars) || 2000))
    const slice = text.slice(start, start + size)
    return {
      id: d.id,
      originName: d.originName,
      ext: d.ext || '',
      chars: d.chars ?? text.length,
      textStatus: d.textStatus || '',
      textError: d.textError || '',
      offset: start,
      returned: slice.length,
      hasMore: start + slice.length < text.length,
      text: slice,
    }
  },

  // ---------- 写：往资料库加文件（找到的论文可以一键入库） ----------

  /**
   * 把一个**本机文件**加入资料库。
   *
   * 后端会立刻抽正文（PDF 走 PDFBox），所以返回的 textStatus/textChars 就是"能不能被检索"的答案；
   * 抽不出（扫描件/图片/压缩包）也会如实返回状态，而不是静默入库一个空壳。
   */
  async upload_file({ path, categoryId }) {
    if (!path) throw new Error('缺少 path（本机文件的绝对路径或相对当前工作目录的路径）')
    let info
    try {
      info = await stat(path)
    } catch {
      throw new Error(`文件不存在或读不到：${path}`)
    }
    if (!info.isFile()) throw new Error(`不是文件：${path}`)
    if (info.size > MAX_UPLOAD_BYTES) {
      throw new Error(`文件 ${(info.size / 1048576).toFixed(1)}MB 超过后端 50MB 上限，未上传`)
    }
    const bytes = await readFile(path)
    const created = await uploadBytes(bytes, basename(path), categoryId)
    return { ok: true, uploaded: briefFile(created), source: 'local', path, notice: extractionNotice(created) }
  },

  /**
   * 从 URL 下载并直接加入资料库（"找到论文 → 入库"的一步到位入口）。
   *
   * 两个刻意的守卫：
   *  ① 只接受 http/https，且限制在 50MB 内（与后端一致）；
   *  ② **返回 text/html 就拒收** —— 论文页面的落地页很常见，而把它当"全文"入库
   *     会得到一份没有正文的资料，看起来成功、实际检索不到，是最难发现的那种脏数据。
   *     真需要网页正文时，先抓页面把 PDF 直链找出来再传这个工具。
   * 注意：这里不做任何"绕付费墙"的事，也不该被用来越权下载；地址必须来自用户给出或检索到的公开全文。
   */
  async upload_file_from_url({ url, filename, categoryId }) {
    if (!url) throw new Error('缺少 url')
    let target
    try {
      target = new URL(url)
    } catch {
      throw new Error(`不是合法 URL：${url}`)
    }
    if (!['http:', 'https:'].includes(target.protocol)) {
      throw new Error(`只支持 http/https，收到：${target.protocol}`)
    }
    let resp
    try {
      resp = await fetch(target, {
        redirect: 'follow',
        headers: { 'User-Agent': 'learn-hub-mcp/1.0 (+local library import)' },
        signal: AbortSignal.timeout(DOWNLOAD_TIMEOUT_MS),
      })
    } catch (e) {
      throw new Error(`下载失败：${e?.message || e}`)
    }
    if (!resp.ok) throw new Error(`下载失败：HTTP ${resp.status}（${target.href}）`)
    const ctype = (resp.headers.get('content-type') || '').toLowerCase()
    const declared = Number(resp.headers.get('content-length') || 0)
    if (declared > MAX_UPLOAD_BYTES) {
      throw new Error(`远端文件约 ${(declared / 1048576).toFixed(1)}MB，超过 50MB 上限，未下载`)
    }
    const bytes = Buffer.from(await resp.arrayBuffer())
    if (bytes.length > MAX_UPLOAD_BYTES) {
      throw new Error(`下载了 ${(bytes.length / 1048576).toFixed(1)}MB，超过 50MB 上限，未入库`)
    }
    if (ctype.includes('text/html') || bytes.subarray(0, 200).toString('utf8').toLowerCase().includes('<!doctype html')) {
      throw new Error(
        `这个地址返回的是网页（${ctype || 'html'}）而不是全文文件：${target.href}。` +
          `请抓开该页面找到 PDF 直链（例如 arXiv 的 https://arxiv.org/pdf/<id>）再入库。`,
      )
    }
    const created = await uploadBytes(bytes, filenameFrom(target.href, ctype, filename), categoryId)
    return {
      ok: true,
      uploaded: briefFile(created),
      source: target.href,
      contentType: ctype || '',
      bytes: bytes.length,
      notice: extractionNotice(created),
    }
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

  // ---------- 资料库 ----------
  {
    name: 'list_files',
    description: '列出资料库（上传的 PDF / Office / 文本）里的资料，含抽取状态与字数。不返回正文。',
    inputSchema: {
      type: 'object',
      properties: {
        kw: { type: 'string', description: '按文件名过滤（可选）' },
        categoryId: { type: 'number', description: '只看某个分类（可选）' },
      },
      additionalProperties: false,
    },
    annotations: { readOnlyHint: true },
  },
  {
    name: 'get_file_text',
    description:
      '取某份资料抽出来的正文片段（默认前 2000 字，可用 offset 翻页）。' +
      'PDF 等文档上传后由后端自动抽正文，这份文本同时用于知识库检索。',
    inputSchema: {
      type: 'object',
      properties: {
        id: { type: 'number', description: '资料 id（用 list_files / search_all 拿）' },
        offset: { type: 'number', description: '从第几个字符开始（默认 0）' },
        maxChars: { type: 'number', description: '本次最多返回多少字（200~20000，默认 2000）' },
      },
      required: ['id'],
      additionalProperties: false,
    },
    annotations: { readOnlyHint: true },
  },
  {
    name: 'upload_file',
    description:
      '把一个本机文件加入资料库（后端会立刻抽正文，随后可被知识库检索）。' +
      '适合：终端里已经下载好的论文 PDF。',
    inputSchema: {
      type: 'object',
      properties: {
        path: { type: 'string', description: '本机文件路径（绝对路径，或相对 MCP 进程工作目录）' },
        categoryId: { type: 'number', description: '归到某个分类（可选）' },
      },
      required: ['path'],
      additionalProperties: false,
    },
    annotations: { readOnlyHint: false },
  },
  {
    name: 'upload_file_from_url',
    description:
      '从 URL 下载一份**全文文件**（如 arXiv/出版商/机构的 PDF 直链）并直接加入资料库。"找到论文→入库"用这个。' +
      '只接受 http/https、≤50MB；返回网页（text/html）会被拒收（那说明给的是落地页不是全文）。' +
      '地址必须来自用户给出或检索到的公开全文，不得用于绕过付费墙。',
    inputSchema: {
      type: 'object',
      properties: {
        url: { type: 'string', description: '全文文件直链（http/https）' },
        filename: { type: 'string', description: '入库文件名（可选；不给就从 URL 推断，PDF 会补 .pdf）' },
        categoryId: { type: 'number', description: '归到某个分类（可选）' },
      },
      required: ['url'],
      additionalProperties: false,
    },
    annotations: { readOnlyHint: false },
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
          '这个服务连接的是本地 learn-hub 知识库（Markdown 笔记 + 速查卡 + 分类/标签）与资料库（上传的文档，' +
          'PDF 会被自动抽成正文并参与检索）。不确定有什么内容时先调 stats / search_all / list_files。' +
          '找到论文想留档时用 upload_file_from_url 直接入库（只传公开全文直链，别传落地页）。',
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
