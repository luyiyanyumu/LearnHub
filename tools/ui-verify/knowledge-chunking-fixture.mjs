// Actual KnowledgeSettingsPanel, synthetic settings, and an in-memory API only.
// Run: node tools/ui-verify/knowledge-chunking-fixture.mjs
// Open: http://127.0.0.1:5192/__knowledge_chunking.html?theme=light
// The fixture rejects every unimplemented API path and has no backend proxy.
import { createServer, loadConfigFromFile } from '../../frontend/node_modules/vite/dist/node/index.js'
import { dirname, resolve } from 'node:path'
import { fileURLToPath } from 'node:url'

const root = resolve(dirname(fileURLToPath(import.meta.url)), '../../frontend')
const port = Number(process.env.KNOWLEDGE_CHUNKING_FIXTURE_PORT || 5192)
const loaded = await loadConfigFromFile({ command: 'serve', mode: 'development' }, resolve(root, 'vite.config.js'))
if (!loaded) throw new Error('Vite config not found')
const field = (key, label, type, defaultValue, extra = {}) => ({ key, label, type, default: defaultValue, group: 'index', requiresRebuild: true, ...extra })
const fields = [
  field('chunkStrategy', '切割方式', 'select', 'structure', { help: '仅长篇资料库文件可选语义切割；笔记和速查卡保持结构切割。保存后需重建索引。', options: [{ label: '结构切割', value: 'structure' }, { label: '长资料语义切割', value: 'semantic_long' }] }),
  field('chunkTarget', '目标片段长度', 'number', 800, { min: 100, max: 8000, step: 10, help: '按标题和段落聚合到目标字符数。' }),
  field('chunkOverlap', '片段重叠长度', 'number', 120, { min: 0, max: 4000, step: 10, help: '须小于目标长度，保留相邻片段的上下文。' }),
  field('chunkMax', '片段最大长度', 'number', 1200, { min: 100, max: 16000, step: 10, help: '普通文字的最大长度；代码、公式与表格可完整保留并超出此限制。' }),
  field('semanticMinChars', '语义切割起始长度', 'number', 4000, { min: 2000, max: 100000, step: 100, help: '达到此字符数的资料库文件才预计算语义边界；保存后需重建索引。' }),
  field('semanticBreakpointPercentile', '语义边界百分位', 'number', 85, { min: 50, max: 99, step: 1, help: '越高越少切分；仍保护代码、公式与表格。保存后需重建索引。' }),
  field('embeddingInputChars', '嵌入文本上限', 'number', 6000, { min: 128, max: 32000, step: 100, help: '含标题和小节。' }),
  field('contextualEmbed', '嵌入包含标题与小节', 'boolean', true, { help: '为片段前置来源标题和所属小节。' }),
  field('autoIndex', '自动维护索引', 'boolean', true, { requiresRebuild: false, help: '由后台自动维护总开关统一调度。' }),
  field('vectorEnabled', '启用向量检索', 'boolean', true, { group: 'retrieval', requiresRebuild: false, help: '合成的向量检索开关。' }),
  field('candidates', '每路候选上限', 'number', 24, { min: 4, max: 120, step: 1, group: 'retrieval', requiresRebuild: false }),
  field('rerankEnabled', '启用候选重排', 'boolean', false, { group: 'rerank', requiresRebuild: false }),
  field('maxPassages', '最多注入原文数', 'number', 8, { min: 1, max: 24, step: 1, group: 'context', requiresRebuild: false }),
  field('totalChars', '总上下文预算', 'number', 7000, { min: 1000, max: 64000, step: 100, group: 'context', requiresRebuild: false }),
]
const defaults = Object.fromEntries(fields.map(item => [item.key, item.default]))
let values = { ...defaults }, saved = false, writes = []
const ok = data => ({ code: 200, data })
const snapshot = () => ({ fields, defaults, values, needsRebuild: saved, rebuildFields: saved ? ['chunkStrategy', 'semanticMinChars', 'semanticBreakpointPercentile'] : [] })
function json(res, data, status = 200) { res.statusCode = status; res.setHeader('Content-Type', 'application/json; charset=utf-8'); res.end(JSON.stringify(data)) }
async function bodyOf(req) { let text = ''; for await (const chunk of req) { text += chunk; if (text.length > 100000) throw new Error('Synthetic request too large') } return text ? JSON.parse(text) : {} }

const html = '<!doctype html><html lang="zh-CN"><head><meta charset="UTF-8"><meta name="viewport" content="width=device-width,initial-scale=1.0"><title>合成知识库切割设置核验</title></head><body><div id="app"></div><script type="module" src="/__knowledge_chunking_entry.js"></script></body></html>'
const entry = `
import 'element-plus/theme-chalk/base.css'
import 'element-plus/theme-chalk/dark/css-vars.css'
import 'element-plus/es/components/message/style/css'
import 'element-plus/es/components/button/style/css'
import { createApp, h, ref } from 'vue'
import { ElButton, ElConfigProvider } from 'element-plus'
import zhCn from 'element-plus/es/locale/lang/zh-cn'
import '/src/style.css'
import KnowledgeSettingsPanel from '/src/components/KnowledgeSettingsPanel.vue'
import { initTheme, setThemeMode } from '/src/composables/useTheme.js'
initTheme()
setThemeMode(new URL(location.href).searchParams.get('theme') === 'dark' ? 'dark' : 'light')
const Host = { setup() {
  const panel = ref(null), state = ref({ loaded: false, dirty: false, saving: false })
  return () => h(ElConfigProvider, { locale: zhCn }, () => h('main', { class: 'fixture-host' }, [
    h('p', { class: 'fixture-label' }, '合成核验 · 仅保存到本进程内存'),
    h('section', { class: 'fixture-panel' }, [
      h(KnowledgeSettingsPanel, { ref: panel, active: true, onState: value => { state.value = value } }),
      h('footer', { class: 'fixture-footer' }, [
        h('span', { role: 'status' }, state.value.dirty ? '修改尚未保存' : '当前参数已保存'),
        h(ElButton, { type: 'primary', loading: state.value.saving, disabled: !state.value.loaded || !state.value.dirty, onClick: () => panel.value.save() }, () => '保存知识库设置'),
      ]),
    ]),
  ]))
} }
const style = document.createElement('style')
style.textContent = 'html,body,#app{min-height:100%;margin:0}.fixture-host{max-width:1120px;margin:auto;padding:24px;box-sizing:border-box}.fixture-label{font-size:12px;color:var(--app-text-2);margin:0 0 12px}.fixture-panel{background:var(--app-card);border:1px solid var(--app-border);border-radius:var(--radius);padding:24px}.fixture-footer{display:flex;align-items:center;justify-content:space-between;gap:12px;border-top:1px solid var(--app-border);margin-top:24px;padding-top:16px;font-size:12px;color:var(--app-text-2)}@media(max-width:760px){.fixture-host{padding:12px}.fixture-panel{padding:14px}.fixture-footer{align-items:flex-start;flex-direction:column}}'
document.head.appendChild(style)
createApp(Host).mount('#app')
`
const fixture = {
  name: 'synthetic-knowledge-chunking-fixture', enforce: 'pre',
  resolveId(id) { if (id === '/__knowledge_chunking_entry.js') return '\0synthetic-knowledge-chunking-entry' },
  load(id) { if (id === '\0synthetic-knowledge-chunking-entry') return entry },
  configureServer(server) { server.middlewares.use(async (req, res, next) => {
    const url = new URL(req.url, `http://127.0.0.1:${port}`)
    if (url.pathname === '/__knowledge_chunking.html') { res.setHeader('Content-Type', 'text/html; charset=utf-8'); res.end(await server.transformIndexHtml(url.pathname, html)); return }
    if (url.pathname === '/__fixture/reset' && req.method === 'POST') { values = { ...defaults }; saved = false; writes = []; json(res, ok({ reset: true })); return }
    if (url.pathname === '/__fixture/audit') { json(res, ok({ values, writes })); return }
    if (!url.pathname.startsWith('/api')) { next(); return }
    try {
      if (url.pathname === '/api/kb/settings' && req.method === 'GET') { json(res, ok(snapshot())); return }
      if (url.pathname === '/api/kb/settings' && req.method === 'PUT') {
        const body = await bodyOf(req)
        writes.push(body.values); values = { ...values, ...body.values }; saved = true
        json(res, ok(snapshot())); return
      }
      if (url.pathname === '/api/kb/status' && req.method === 'GET') { json(res, ok({ configured: true, enabled: true, chunks: 96, compatibleChunks: 96, model: 'synthetic-embedding-model', vectorBackend: 'mysql', stale: saved })); return }
      if (url.pathname === '/api/knowledge/maintenance' && req.method === 'GET') { json(res, ok({ enabled: false, running: false, pending: saved, message: '合成自动维护已关闭，保存后需重建索引。' })); return }
      json(res, { code: 404, msg: '合成核验拒绝此接口；不会连接真实服务' }, 404)
    } catch (error) { json(res, { code: 500, msg: String(error.message || error) }, 500) }
  }) },
}
const server = await createServer({ ...loaded.config, configFile: false, root, cacheDir: 'node_modules/.vite-knowledge-chunking-fixture', plugins: [...loaded.config.plugins, fixture], server: { ...loaded.config.server, host: '127.0.0.1', port, strictPort: true, proxy: undefined } })
await server.listen()
console.log(`SYNTHETIC_KNOWLEDGE_CHUNKING_READY http://127.0.0.1:${port}/__knowledge_chunking.html`)
async function stop() { await server.close(); process.exit(0) }
process.on('SIGINT', stop); process.on('SIGTERM', stop)
