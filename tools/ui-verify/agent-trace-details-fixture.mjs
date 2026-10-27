// Actual AgentRunTrace with synthetic operation records; no model or database access.
// Run: node tools/ui-verify/agent-trace-details-fixture.mjs
// Open: http://127.0.0.1:5193/__agent_trace_details.html?theme=light
import { createServer, loadConfigFromFile } from '../../frontend/node_modules/vite/dist/node/index.js'
import { dirname, resolve } from 'node:path'
import { fileURLToPath } from 'node:url'

const root = resolve(dirname(fileURLToPath(import.meta.url)), '../../frontend')
const port = Number(process.env.AGENT_TRACE_DETAILS_FIXTURE_PORT || 5193)
const loaded = await loadConfigFromFile({ command: 'serve', mode: 'development' }, resolve(root, 'vite.config.js'))
if (!loaded) throw new Error('Vite config not found')
const html = '<!doctype html><html lang="zh-CN"><head><meta charset="UTF-8"><meta name="viewport" content="width=device-width,initial-scale=1.0"><title>合成过程明细核验</title></head><body><div id="app"></div><script type="module" src="/__agent_trace_details_entry.js"></script></body></html>'
const entry = `
import 'element-plus/theme-chalk/base.css'
import 'element-plus/theme-chalk/dark/css-vars.css'
import { createApp, h, ref } from 'vue'
import '/src/style.css'
import AgentRunTrace from '/src/components/AgentRunTrace.vue'
import { retrievalSourcePath } from '/src/utils/retrievalDisplay.js'
import { initTheme, setThemeMode } from '/src/composables/useTheme.js'
initTheme()
setThemeMode(new URL(location.href).searchParams.get('theme') === 'dark' ? 'dark' : 'light')
const note = { type: 'note', id: 9001, title: '合成笔记 · 检索入门', snippet: '<p>先获取候选片段，再阅读原始记录核对。</p>', seq: 1, channels: ['vector', 'keyword'] }
const file = { type: 'file', id: 9002, title: '合成资料 · 来源检查指南', snippet: '核对页面标题、访问地址及相关片段。', seq: 0, channels: ['tool'] }
const Host = { setup() {
  const running = ref(true), opened = ref(''), inspecting = ref(0), dark = ref(new URL(location.href).searchParams.get('theme') === 'dark')
  const entries = ref([
    { id: 'prepare', kind: 'phase', title: '准备对话', detail: '会话上下文已就绪', status: 'completed', elapsedMs: 2000 },
    { id: 'retrieve', kind: 'phase', title: '检索相关知识', detail: '已找到 2 条相关来源', status: 'completed', elapsedMs: 14000, sources: [note, file] },
    { id: 'select', kind: 'phase', title: '分析请求', detail: '已选择 2 项工具操作', status: 'completed', elapsedMs: 6000, tools: [
      { name: 'web_search', title: '搜索网络资料', arguments: { queries: ['合成资料 召回流程'], maxResults: 3 } },
      { name: 'get_note', title: '读取笔记原文', arguments: { id: 9001 } },
    ] },
    { id: 'web', kind: 'tool', title: '搜索网络资料', detail: '已找到 2 个相关网页', status: 'completed', elapsedMs: 17000,
      tools: [{ name: 'web_search', title: '搜索网络资料', arguments: { queries: ['合成资料 召回流程'] } }],
      websites: [{ title: '合成网页 · 研究概览', url: 'https://example.com/synthetic/overview', snippet: '网页搜索返回的合成摘要。' }, { title: '合成网页 · 方法说明', url: 'https://example.org/synthetic/method' }],
      result: '合成搜索结果：返回 2 条网页标题、地址与摘要。' },
    { id: 'failed', kind: 'tool', title: '读取网页', status: 'error', detail: '本次调用未完成，将根据返回结果调整处理', elapsedMs: 6000,
      tools: [{ name: 'web_fetch', title: '读取网页', arguments: { url: 'https://example.com/synthetic/missing' } }],
      websites: [{ title: '合成网页 · 已移除的页面', url: 'https://example.com/synthetic/missing' }], error: '合成错误：网页返回 HTTP 404，未取得正文。' },
    { id: 'live', kind: 'tool', title: '读取网页', status: 'running', toolName: 'web_fetch',
      tools: [{ name: 'web_fetch', title: '读取网页', arguments: { url: 'https://example.org/synthetic/method' } }],
      websites: [{ title: '合成网页 · 方法说明', url: 'https://example.org/synthetic/method' }] },
    { id: 'legacy', kind: 'phase', title: '整理工具结果', status: 'completed', detail: '旧记录仅保留概要，未保存操作明细' },
  ])
  function complete() {
    entries.value = entries.value.map(item => item.id === 'live' ? { ...item, status: 'completed', elapsedMs: 1200, detail: '处理完成', sources: [file], result: '合成网页正文：核对来源与原文后整理回答。' } : item)
    running.value = false
  }
  function theme() { dark.value = !dark.value; setThemeMode(dark.value ? 'dark' : 'light') }
  return () => h('main', { class: 'fixture-host' }, [
    h('header', { class: 'fixture-toolbar' }, [h('span', '合成核验 · 可展开每一步的实际操作记录'), h('button', { onClick: theme }, dark.value ? '切换浅色' : '切换深色'), h('button', { onClick: complete }, '模拟步骤完成')]),
    h('section', { class: 'fixture-card' }, [h(AgentRunTrace, { entries: entries.value, running: running.value, elapsedMs: 47000, onOpenSource: source => { opened.value = '已打开合成来源：' + source.title + ' · ' + retrievalSourcePath(source) }, onInspect: () => { inspecting.value++ } })]),
    h('p', { role: 'status', class: 'fixture-status' }, opened.value || '原文入口会记录合成跳转目标，不会访问真实笔记。'),
    h('p', { class: 'fixture-status' }, '已检查明细：' + inspecting.value + ' 次'),
  ])
} }
const style = document.createElement('style')
style.textContent = 'html,body,#app{min-height:100%;margin:0}.fixture-host{max-width:960px;margin:auto;padding:24px;box-sizing:border-box;color:var(--app-text-1)}.fixture-toolbar{display:flex;align-items:center;gap:8px;flex-wrap:wrap;margin-bottom:16px;font-size:12px}.fixture-toolbar>span{flex:1;min-width:160px}.fixture-toolbar button{font:inherit;color:var(--app-brand-deep);background:var(--app-card);border:1px solid var(--app-border);border-radius:8px;min-height:32px;padding:6px 10px;cursor:pointer}.fixture-toolbar button:focus-visible{outline:2px solid var(--app-brand);outline-offset:2px}.fixture-card{min-width:0;background:var(--app-card);border:1px solid var(--app-border);border-radius:12px;padding:16px}.fixture-status{font-size:12px;color:var(--app-text-2);overflow-wrap:anywhere}@media(max-width:520px){.fixture-host{padding:12px}.fixture-card{padding:8px}}'
document.head.appendChild(style)
createApp(Host).mount('#app')
`
const fixture = {
  name: 'synthetic-agent-trace-details-fixture', enforce: 'pre',
  resolveId(id) { if (id === '/__agent_trace_details_entry.js') return '\0synthetic-agent-trace-details-entry' },
  load(id) { if (id === '\0synthetic-agent-trace-details-entry') return entry },
  configureServer(server) { server.middlewares.use(async (req, res, next) => {
    const url = new URL(req.url, `http://127.0.0.1:${port}`)
    if (url.pathname === '/__agent_trace_details.html') { res.setHeader('Content-Type', 'text/html; charset=utf-8'); res.end(await server.transformIndexHtml(url.pathname, html)); return }
    if (!url.pathname.startsWith('/api')) { next(); return }
    res.statusCode = 404; res.setHeader('Content-Type', 'application/json; charset=utf-8'); res.end(JSON.stringify({ code: 404, msg: '合成核验不会连接真实服务' }))
  }) },
}
const server = await createServer({ ...loaded.config, configFile: false, root, cacheDir: 'node_modules/.vite-agent-trace-details-fixture', plugins: [...loaded.config.plugins, fixture], server: { ...loaded.config.server, host: '127.0.0.1', port, strictPort: true, proxy: undefined } })
await server.listen()
console.log(`SYNTHETIC_AGENT_TRACE_DETAILS_READY http://127.0.0.1:${port}/__agent_trace_details.html`)
async function stop() { await server.close(); process.exit(0) }
process.on('SIGINT', stop); process.on('SIGTERM', stop)
