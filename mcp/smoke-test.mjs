#!/usr/bin/env node
/**
 * learn-hub MCP server 冒烟自测（零依赖）。
 *
 * 为什么要它：MCP 的 stdio 协议是手写的，改完服务端需要一条命令确认「帧格式没坏、工具还都在」。
 * 这里**自己手写一份最小客户端**（initialize → tools/list → 调几个只读工具），
 * 不依赖任何 SDK，所以换台机器也能直接跑。
 *
 * 用法：
 *   node smoke-test.mjs            # 只读检查（默认，不会改动知识库）
 *   node smoke-test.mjs --write    # 额外跑一遍「建 → 改 → 删」写入闭环（会自己清理）
 *
 * 前置：learn-hub 后端在运行（默认 http://localhost:18080）。
 */

import { spawn } from 'node:child_process'
import { mkdtemp, rm, writeFile } from 'node:fs/promises'
import { tmpdir } from 'node:os'
import path from 'node:path'
import { fileURLToPath } from 'node:url'

const HERE = path.dirname(fileURLToPath(import.meta.url))
const SERVER = path.join(HERE, 'learn-hub-mcp.mjs')
const WITH_WRITE = process.argv.includes('--write')
const BASE = process.env.LEARNHUB_BASE_URL || 'http://localhost:18080'

let pass = 0
let fail = 0
function check(name, ok, extra = '') {
  if (ok) {
    pass++
    console.log(`  ✓ ${name}${extra ? '  ' + extra : ''}`)
  } else {
    fail++
    console.log(`  ✗ ${name}${extra ? '  ' + extra : ''}`)
  }
}

// ---------------------------------------------------------------- 最小 MCP 客户端

const child = spawn(process.execPath, [SERVER], {
  cwd: HERE,
  env: { ...process.env, LEARNHUB_BASE_URL: BASE },
  stdio: ['pipe', 'pipe', 'inherit'], // stderr 直接透传，方便看服务端日志
})

let buf = ''
const pending = new Map()
let nextId = 1

child.stdout.setEncoding('utf8')
child.stdout.on('data', (chunk) => {
  buf += chunk
  let i
  while ((i = buf.indexOf('\n')) >= 0) {
    const line = buf.slice(0, i).trim()
    buf = buf.slice(i + 1)
    if (!line) continue
    const msg = JSON.parse(line)
    const waiter = pending.get(msg.id)
    if (waiter) {
      pending.delete(msg.id)
      waiter(msg)
    }
  }
})

function request(method, params) {
  const id = nextId++
  return new Promise((resolve, reject) => {
    // 定时器必须在收到响应时清掉：否则这些未清的 timer 会一直吊着事件循环，
    // 最后只能靠 process.exit() 强退 —— 那会在 Windows 上撞出 libuv 断言。
    const timer = setTimeout(() => {
      if (pending.delete(id)) reject(new Error(`${method} 超时`))
    }, 20000)
    pending.set(id, (msg) => {
      clearTimeout(timer)
      msg.error ? reject(new Error(msg.error.message)) : resolve(msg.result)
    })
    child.stdin.write(JSON.stringify({ jsonrpc: '2.0', id, method, params }) + '\n')
  })
}
const notify = (method, params) => child.stdin.write(JSON.stringify({ jsonrpc: '2.0', method, params }) + '\n')

/** 调工具并把结果文本解析回对象 */
async function call(name, args = {}) {
  const r = await request('tools/call', { name, arguments: args })
  const text = (r.content || []).map((c) => c.text || '').join('\n')
  return { isError: !!r.isError, text, data: r.isError ? null : safeJson(text) }
}
const safeJson = (s) => {
  try {
    return JSON.parse(s)
  } catch {
    return null
  }
}

// ---------------------------------------------------------------- 跑

try {
  console.log(`服务端: ${SERVER}`)
  console.log(`后端  : ${BASE}\n`)

  const init = await request('initialize', {
    protocolVersion: '2025-06-18',
    capabilities: {},
    clientInfo: { name: 'learn-hub-smoke-test', version: '1.0.0' },
  })
  check('initialize 返回协议版本', !!init.protocolVersion, init.protocolVersion)
  check('声明了 tools 能力', !!init.capabilities?.tools)
  check('serverInfo 正确', init.serverInfo?.name === 'learn-hub')
  notify('notifications/initialized')

  const { tools } = await request('tools/list')
  check('tools/list 返回工具', tools.length > 0, `${tools.length} 个`)
  check('每个工具都有 object 型 inputSchema', tools.every((t) => t.inputSchema?.type === 'object'))
  check('工具名唯一', new Set(tools.map((t) => t.name)).size === tools.length)

  console.log('\n--- 只读工具 ---')
  const stats = await call('stats')
  check('stats', !stats.isError && typeof stats.data?.noteTotal === 'number',
    `笔记 ${stats.data?.noteTotal} / 速查卡 ${stats.data?.refTotal} / 分类 ${stats.data?.categoryTotal} / 标签 ${stats.data?.tagTotal}`)

  const cats = await call('list_categories')
  check('list_categories', !cats.isError && cats.data?.count >= 0, `${cats.data?.count} 个分类`)

  const tags = await call('list_tags')
  check('list_tags', !tags.isError && tags.data?.count >= 0, `${tags.data?.count} 个标签`)

  const notes = await call('search_notes', { size: 3 })
  check('search_notes', !notes.isError && Array.isArray(notes.data?.list), `共 ${notes.data?.total} 篇`)
  check('列表不含正文（不塞爆上下文）', !('content' in (notes.data?.list?.[0] || {})))

  if (notes.data?.list?.length) {
    const id = notes.data.list[0].id
    const n = await call('get_note', { id })
    check('get_note 取到正文', !n.isError && n.data.content.length > 0, `「${n.data.title}」${n.data.content.length} 字`)
  } else {
    check('get_note 取到正文', false, '库里没有笔记，跳过（先写一篇再跑）')
  }

  const refs = await call('list_quick_refs')
  check('list_quick_refs', !refs.isError && refs.data?.count >= 0, `${refs.data?.count} 张`)

  console.log('\n--- 资料库（只读）---')
  const files = await call('list_files')
  check('list_files', !files.isError && Array.isArray(files.data?.files), `${files.data?.count} 份`)
  check('资料列表不含正文（不塞爆上下文）', !('text' in (files.data?.files?.[0] || {})))
  const firstPdf = (files.data?.files || []).find((f) => f.ext === 'pdf') || files.data?.files?.[0]
  if (firstPdf) {
    const txt = await call('get_file_text', { id: firstPdf.id, maxChars: 500 })
    check('get_file_text 分页取正文', !txt.isError && typeof txt.data?.chars === 'number',
      `「${txt.data?.originName}」${txt.data?.chars} 字，本次 ${txt.data?.returned} 字，hasMore=${txt.data?.hasMore}`)
    check('get_file_text 不超过 maxChars', (txt.data?.returned ?? 0) <= 500)
    if (firstPdf.textStatus === 'ok') {
      check('★ 正文确实抽出来了（入库即可检索）', (txt.data?.text || '').length > 0)
    }
  } else {
    check('get_file_text 分页取正文', true, '资料库为空，跳过')
  }

  console.log('\n--- 错误路径 ---')
  const bad = await call('get_note', { id: 99999999 })
  check('不存在的 id → isError + 可读原因', bad.isError && bad.text.length > 0, '→ ' + bad.text.slice(0, 50))

  if (WITH_WRITE) {
    console.log('\n--- 写入闭环（自建自删）---')
    const created = await call('create_note', {
      title: '[MCP 冒烟] 临时笔记（可删）',
      content: '## 正文\n\n用于验证 update_note 不会清空正文。\n',
      tagNames: ['MCP冒烟'],
    })
    check('create_note', !created.isError && created.data?.id > 0, `id=${created.data?.id}`)
    if (created.data?.id) {
      await call('update_note', { id: created.data.id, title: '[MCP 冒烟] 改过标题' })
      const after = await call('get_note', { id: created.data.id })
      check('update_note 只改标题', after.data?.title === '[MCP 冒烟] 改过标题')
      check('★ 正文未被全量替换清空', !!after.data?.content?.includes('不会清空正文'))
      const del = await call('delete_note', { id: created.data.id })
      check('delete_note', !del.isError && del.data?.deletedId === created.data.id)
    }
    const tagsNow = await call('list_tags')
    const t = (tagsNow.data?.tags || []).find((x) => x.name === 'MCP冒烟')
    if (t) {
      const r = await fetch(`${BASE}/api/tags/${t.id}`, { method: 'DELETE' })
      check('清理自测标签', r.ok, `#${t.id}`)
    }

    console.log('\n--- 资料库写入闭环（自建自删）---')
    // 用临时目录里的一个小 Markdown：不联网、不进版本库、跑完就删
    const dir = await mkdtemp(path.join(tmpdir(), 'learnhub-smoke-'))
    const sample = path.join(dir, 'mcp-smoke.md')
    const marker = 'MCP冒烟标记：入库后应当能被抽出来'
    await writeFile(sample, `# MCP 冒烟文档\n\n${marker}\n`, 'utf8')
    let uploadedId = null
    try {
      const up = await call('upload_file', { path: sample })
      uploadedId = up.data?.uploaded?.id || null
      check('upload_file 入库', !up.isError && uploadedId > 0, `id=${uploadedId}`)
      check('★ 入库即抽正文（textStatus=ok）', up.data?.uploaded?.textStatus === 'ok',
        `status=${up.data?.uploaded?.textStatus} ${up.data?.uploaded?.textChars} 字`)
      check('抽不出正文时会给提示（这里应为 null）', up.data?.notice === null, `notice=${up.data?.notice}`)

      if (uploadedId) {
        const got = await call('get_file_text', { id: uploadedId, maxChars: 1000 })
        check('★ 抽出来的正文里能找到标记', (got.data?.text || '').includes('MCP冒烟标记'))
        const hit = await call('search_all', { kw: marker })
        check('★ 统一检索能命中这份新资料',
          (hit.data?.items || []).some((i) => i.type === 'file' && i.id === uploadedId))
      }

      const missing = await call('upload_file', { path: path.join(dir, '不存在.pdf') })
      check('不存在的路径 → isError + 可读原因', missing.isError, '→ ' + missing.text.slice(0, 40))
    } finally {
      await rm(dir, { recursive: true, force: true })
      if (uploadedId) {
        // MCP 侧没有删除工具（知识库不做回收站），自测用 REST 收拾干净
        const r = await fetch(`${BASE}/api/files/${uploadedId}`, { method: 'DELETE' })
        check('清理自测资料', r.ok, `#${uploadedId}`)
      }
    }
  } else {
    console.log('\n（省略写入闭环；加 --write 可完整验证建/改/删 + 资料入库）')
  }
} catch (e) {
  fail++
  console.log('\n✗ 异常终止: ' + (e?.message || e))
} finally {
  console.log(`\n结果：${pass} 通过 / ${fail} 失败`)
  // 不用 process.exit()：那会在句柄仍在关闭时触发 libuv 断言。
  // 关掉 stdin 让服务端自行退出，等它退出后父进程自然结束（定时器已在上面的 request 里清干净）。
  process.exitCode = fail ? 1 : 0
  child.stdin.end()
}
