// Isolated UI fixture for the actual agent components. All content is synthetic.
// Run from the repository root: node tools/ui-verify/agent-chat-fixture.mjs
// Open http://127.0.0.1:5186/__agent_chat.html (optional ?theme=dark&mode=drawer).
// All /api requests terminate here. This process never reads app settings or calls a model/database.
import { createServer, loadConfigFromFile } from '../../frontend/node_modules/vite/dist/node/index.js'
import { dirname, resolve } from 'node:path'
import { fileURLToPath } from 'node:url'

const root = resolve(dirname(fileURLToPath(import.meta.url)), '../../frontend')
const port = Number(process.env.AGENT_CHAT_FIXTURE_PORT || 5186)
const configFile = resolve(root, 'vite.config.js')
const loaded = await loadConfigFromFile({ command: 'serve', mode: 'development' }, configFile)
if (!loaded) throw new Error('Vite config not found')

const stamp = '2026-01-15 09:00:00'
let nextSession = 3
let nextAction = 9100
const sessions = new Map([
  ['synthetic-session-a', { id: 'synthetic-session-a', title: '合成示例 · 检索任务', modelProfileId: '', updatedAt: stamp, messages: [], pendingActions: [] }],
  ['synthetic-session-b', { id: 'synthetic-session-b', title: '合成示例 · 历史会话', modelProfileId: '', updatedAt: stamp, messages: [
    { role: 'user', content: '解释合成资料中的召回流程。' },
    { role: 'assistant', content: '先获取候选，再根据来源核对。这是用于界面检查的合成回答。', events: ['已读取合成笔记'], toolUsed: true, status: 'completed', progress: [
      { id: 'history-read', kind: 'tool', toolName: 'get_note', title: '读取笔记', detail: '合成笔记 · 检索入门', status: 'completed', elapsedMs: 820 },
      { id: 'history-answer', kind: 'phase', title: '整理回答', detail: '已依据合成资料整理结果', status: 'completed', elapsedMs: 1320 },
    ] },
  ], pendingActions: [] }],
])
const profiles = [{ id: 'synthetic-model', name: '合成模型', model: 'fixture-model', provider: 'custom', purpose: 'chat', configured: true }]
const ok = data => ({ code: 200, data })
const sleep = ms => new Promise(resolve => setTimeout(resolve, ms))

function json(res, data, status = 200) {
  res.statusCode = status
  res.setHeader('Content-Type', 'application/json; charset=utf-8')
  res.end(JSON.stringify(data))
}

async function bodyOf(req) {
  let text = ''
  for await (const chunk of req) {
    text += chunk.toString()
    if (text.length > 1_000_000) throw new Error('合成请求过大')
  }
  return text ? JSON.parse(text) : {}
}

function sessionRow(session) {
  return { id: session.id, title: session.title, modelProfileId: session.modelProfileId, updatedAt: session.updatedAt, createdAt: stamp, events: session.messages.length }
}

function newSession(body) {
  const id = `synthetic-session-${nextSession++}`
  const session = { id, title: String(body.title || '合成新对话'), modelProfileId: body.modelProfileId || '', updatedAt: stamp, messages: [], pendingActions: [] }
  sessions.set(id, session)
  return session
}

function fakePending(session) {
  const action = { id: nextAction++, toolName: 'edit_note', summary: '为合成笔记添加目录', preview: {
    noteId: 9001, title: '合成笔记 · 检索入门', changed: true, changes: [{ action: 'insert', label: '在正文前添加目录', count: 1, before: '', after: '## 目录\n\n- 检索流程\n- 结果核对' }],
  } }
  session.pendingActions.push(action)
  return action
}

async function streamChat(req, res, body) {
  const session = sessions.get(body.sessionId) || newSession({ title: body.message || '合成新对话', modelProfileId: body.modelProfileId })
  const message = String(body.message || '正常')
  const slow = /慢|slow/i.test(message)
  const fail = /失败|error/i.test(message)
  const interrupted = /断流|断开|disconnect/i.test(message)
  const wantsWrite = /目录|编辑|确认|write/i.test(message)
  const wantsPlan = /计划|plan/i.test(message)
  const begin = Date.now()
  const assistant = { role: 'assistant', content: '', events: [], progress: [], toolUsed: true, status: 'running' }
  session.messages.push({ role: 'user', content: message }, assistant)
  let disconnected = false
  res.statusCode = 200
  res.setHeader('Content-Type', 'text/event-stream; charset=utf-8')
  res.setHeader('Cache-Control', 'no-cache')
  res.setHeader('X-Accel-Buffering', 'no')
  res.flushHeaders()
  res.on('close', () => {
    disconnected = true
  })
  function send(event, payload) {
    if (disconnected || res.destroyed || res.writableEnded) return false
    res.write(`event: ${event}\r\ndata: ${JSON.stringify(payload)}\r\n\r\n`)
    return true
  }
  function step(id, title, status, extra = {}) {
    const progress = { id, kind: 'tool', title, status, elapsedMs: Date.now() - begin, ...extra }
    const index = assistant.progress.findIndex(item => item.id === id)
    if (index < 0) assistant.progress.push(progress)
    else assistant.progress[index] = { ...assistant.progress[index], ...progress }
    send('progress', progress)
  }
  async function wait() {
    await sleep(slow ? 4500 : 1500)
  }
  send('session', { sessionId: session.id })
  if (wantsPlan) {
    step('plan-1', '读取并核对合成资料', 'running', { kind: 'plan' })
    step('plan-2', '整理有来源的回答', 'pending', { kind: 'plan' })
    step('plan-3', '额外检索旧方案', 'cancelled', { kind: 'plan', detail: '计划已调整，此步骤不再需要' })
  }
  step('context', '准备对话上下文', 'running', { kind: 'phase', detail: '读取当前会话与合成笔记背景' })
  await wait()
  step('context', '准备对话上下文', 'completed', { kind: 'phase', detail: '已载入合成上下文' })
  step('search', '检索知识库', 'running', { toolName: 'search_knowledge', detail: '查找合成示例中的相关资料' })
  await wait()
  if (fail) {
    step('search', '检索知识库', 'error', { toolName: 'search_knowledge', detail: '合成错误：检索服务暂时不可用' })
    assistant.status = 'failed'
    assistant.content = '合成错误：检索服务暂时不可用。'
    assistant.elapsedMs = Date.now() - begin
    send('error', { message: assistant.content })
    res.end()
    return
  }
  // Disconnect only stops delivery. Work completes in the in-memory history, as on the backend.
  if (interrupted) res.end()
  step('search', '检索知识库', 'completed', { toolName: 'search_knowledge', detail: '找到 2 条合成来源' })
  step('adaptive', '执行自适应工具', 'running', { toolName: 'adaptive_synthetic_outline', detail: '读取来源并整理章节' })
  step('read-note', '读取笔记', 'running', { toolName: 'get_note', parentId: 'adaptive', detail: '合成笔记 · 检索入门' })
  await wait()
  step('read-note', '读取笔记', 'completed', { toolName: 'get_note', parentId: 'adaptive', detail: '已读取 2 个合成章节' })
  step('adaptive', '执行自适应工具', 'completed', { toolName: 'adaptive_synthetic_outline', detail: '已整理合成来源中的章节' })
  const pendingActions = wantsWrite ? [fakePending(session)] : []
  if (wantsWrite) step('edit', '准备笔记修改', 'waiting', { toolName: 'edit_note', actionId: pendingActions[0].id, detail: '目录预览已准备，等待确认' })
  step('answer', '整理回答', 'running', { kind: 'phase', detail: '核对来源并组织回答' })
  await wait()
  step('answer', '整理回答', 'completed', { kind: 'phase', detail: '合成回答已完成' })
  if (wantsPlan) {
    step('plan-1', '读取并核对合成资料', 'completed', { kind: 'plan' })
    step('plan-2', '整理有来源的回答', wantsWrite ? 'pending' : 'completed', { kind: 'plan' })
  }
  assistant.content = wantsWrite
    ? '已为合成笔记准备目录，请在下方预览并确认。\n\n这次操作读取了章节，并复用了已有编辑工具。'
    : '已阅读合成来源，整理结果如下：\n\n1. 先检索相关片段，再阅读来源。\n2. 根据实际结果整理回答。\n\n这是用于验证实时过程、会话切换和恢复的合成内容。'
  assistant.status = 'completed'
  assistant.elapsedMs = Date.now() - begin
  assistant.events = ['找到 2 条合成来源', wantsWrite ? '已准备目录修改预览' : '已完成合成检索任务']
  const result = { sessionId: session.id, reply: assistant.content, events: assistant.events, progress: assistant.progress, elapsedMs: assistant.elapsedMs, pendingActions, toolUsed: true, retrieved: [], reasoning: '' }
  send('result', result)
  if (!res.writableEnded) res.end()
}

const html = '<!doctype html><html lang="zh-CN"><head><meta charset="UTF-8"><meta name="viewport" content="width=device-width,initial-scale=1.0"><title>合成智能体过程核验</title></head><body><div id="app"></div><script type="module" src="/__agent_chat_entry.js"></script></body></html>'
const entry = `
import 'element-plus/theme-chalk/base.css'
import 'element-plus/theme-chalk/dark/css-vars.css'
import 'element-plus/es/components/message/style/css'
import 'element-plus/es/components/message-box/style/css'
import { createApp, h, ref } from 'vue'
import { createRouter, createWebHashHistory } from 'vue-router'
import { ElConfigProvider } from 'element-plus'
import zhCn from 'element-plus/es/locale/lang/zh-cn'
import '/src/style.css'
import AgentSessions from '/src/views/AgentSessions.vue'
import AgentPanel from '/src/components/AgentPanel.vue'
import { initTheme, setThemeMode } from '/src/composables/useTheme.js'
const options = new URL(location.href).searchParams
const saved = localStorage.getItem('lh-agent-session')
if (options.has('session') || !saved?.startsWith('synthetic-session-')) {
  localStorage.setItem('lh-agent-session', options.get('session') || 'synthetic-session-a')
}
initTheme()
setThemeMode(options.get('theme') === 'dark' ? 'dark' : 'light')
const router = createRouter({ history: createWebHashHistory(), routes: [{ path: '/:pathMatch(.*)*', component: { render: () => null } }] })
const Host = { setup() {
  const dark = ref(options.get('theme') === 'dark')
  const toggle = () => { dark.value = !dark.value; setThemeMode(dark.value ? 'dark' : 'light') }
  return () => h(ElConfigProvider, { locale: zhCn }, () => h('main', { class: 'fixture-host' }, [
    h('header', { class: 'fixture-toolbar' }, [
      h('span', '合成 UI 核验 · 输入“正常”“目录”“失败”“断流”或“慢速”'),
      h('button', { onClick: toggle }, dark.value ? '切换浅色' : '切换深色'),
      h('a', { href: '?mode=' + (options.get('mode') === 'drawer' ? 'embedded' : 'drawer') }, options.get('mode') === 'drawer' ? '嵌入模式' : '抽屉模式'),
    ]),
    options.get('mode') === 'drawer' ? h(AgentPanel) : h(AgentSessions),
  ]))
} }
const style = document.createElement('style')
style.textContent = 'html,body,#app{height:100%;margin:0}.fixture-host{height:100%;display:flex;flex-direction:column;background:var(--app-bg);color:var(--app-text-1)}.fixture-toolbar{display:flex;align-items:center;gap:12px;padding:10px 16px;flex:none;flex-wrap:wrap;font-size:12px;border-bottom:1px solid var(--app-border)}.fixture-toolbar button,.fixture-toolbar a{font:inherit;color:var(--app-brand-deep);border:1px solid var(--app-border);border-radius:7px;background:var(--app-card);padding:5px 9px;cursor:pointer}.fixture-host>.agent-page{flex:1;min-height:0;height:auto!important;padding:18px;box-sizing:border-box}'
document.head.appendChild(style)
createApp(Host).use(router).mount('#app')
`

const fixturePlugin = {
  name: 'synthetic-agent-chat-fixture',
  enforce: 'pre',
  resolveId(id) { if (id === '/__agent_chat_entry.js') return '\0synthetic-agent-chat-entry' },
  load(id) { if (id === '\0synthetic-agent-chat-entry') return entry },
  configureServer(server) {
    server.middlewares.use(async (req, res, next) => {
      const url = new URL(req.url, `http://127.0.0.1:${port}`)
      if (url.pathname === '/__agent_chat.html') {
        res.setHeader('Content-Type', 'text/html; charset=utf-8')
        res.end(await server.transformIndexHtml(url.pathname, html))
        return
      }
      if (!url.pathname.startsWith('/api')) { next(); return }
      try {
        const body = ['POST', 'PUT', 'PATCH'].includes(req.method) ? await bodyOf(req) : {}
        const path = url.pathname
        if (path === '/api/ai/status') { json(res, ok({ configured: true, model: 'fixture-model' })); return }
        if (path === '/api/settings') { json(res, ok({ webEnabled: false })); return }
        if (path === '/api/model/profiles') { json(res, ok({ profiles, routing: [] })); return }
        if (path === '/api/categories' || path === '/api/tags') { json(res, ok([])); return }
        if (path === '/api/model/sessions') {
          json(res, ok(req.method === 'POST' ? sessionRow(newSession(body)) : [...sessions.values()].map(sessionRow)))
          return
        }
        const sessionMatch = path.match(/^\/api\/(?:model|ai)\/sessions\/([^/]+)(?:\/(model|title))?$/)
        if (sessionMatch) {
          const session = sessions.get(sessionMatch[1])
          if (!session) { json(res, { code: 404, msg: '合成会话不存在' }, 404); return }
          if (req.method === 'DELETE') sessions.delete(session.id)
          else if (sessionMatch[2] === 'model') session.modelProfileId = body.profileId || ''
          else if (sessionMatch[2] === 'title') session.title = String(body.title || '合成会话')
          json(res, ok(session))
          return
        }
        if (path === '/api/ai/chat-stream') { await streamChat(req, res, body); return }
        const actualActionMatch = path.match(/^\/api\/ai\/actions\/(\d+)\/(approve|reject)$/)
        if (actualActionMatch) {
          const [, id, action] = actualActionMatch
          const session = [...sessions.values()].find(item => item.pendingActions.some(pending => String(pending.id) === id))
          if (!session) { json(res, { code: 404, msg: '合成待确认操作不存在' }, 404); return }
          session.pendingActions = session.pendingActions.filter(pending => String(pending.id) !== id)
          const hint = action === 'approve' ? '已更新合成笔记目录' : '已取消合成操作'
          const last = [...session.messages].reverse().find(item => item.role === 'assistant')
          if (last) {
            last.events.push(hint); last.content += '\n\n' + hint
            for (const row of last.progress || []) {
              if (String(row.actionId) === id) { row.status = 'completed'; row.detail = hint }
            }
          }
          json(res, ok({ ok: true, hint }))
          return
        }
        json(res, { code: 404, msg: '合成核验未实现此接口；不会连接真实服务' }, 404)
      } catch (error) {
        if (!res.headersSent) json(res, { code: 500, msg: String(error.message || error) }, 500)
        else res.end()
      }
    })
  },
}

const server = await createServer({
  ...loaded.config,
  configFile: false,
  root,
  plugins: [...loaded.config.plugins, fixturePlugin],
  server: { ...loaded.config.server, host: '127.0.0.1', port, strictPort: true, proxy: undefined },
})
await server.listen()
console.log(`SYNTHETIC_AGENT_CHAT_READY http://127.0.0.1:${port}/__agent_chat.html`)
async function stop() { await server.close(); process.exit(0) }
process.on('SIGINT', stop)
process.on('SIGTERM', stop)
