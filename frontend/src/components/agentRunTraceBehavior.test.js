import test from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import { compileScript, parse } from '@vue/compiler-sfc'
import * as vue from 'vue'
import * as retrievalDisplay from '../utils/retrievalDisplay.js'

const source = readFileSync(new URL('./AgentRunTrace.vue', import.meta.url), 'utf8')
const compiled = compileScript(parse(source).descriptor, { id: 'agent-run-trace-behavior', inlineTemplate: true })
const factory = new Function('modules', 'Date', 'setInterval', 'clearInterval', compiled.content
  .replace(/^import\s+([\s\S]*?)\s+from\s+['"]([^'"]+)['"]\s*;?\r?$/gm,
    (_, names, from) => `const ${names.replace(/\bas\b/g, ':')} = modules[${JSON.stringify(from)}]`)
  .replace('export default', 'return'))

function harness(initialProps = {}) {
  let now = 100_000
  let timerId = 0
  const timers = new Map()
  const openedSources = []
  const inspections = []
  const props = vue.reactive({ entries: [], running: false, startedAt: 0, elapsedMs: 0, failed: false, ...initialProps })
  const component = factory({ vue, '../utils/retrievalDisplay.js': retrievalDisplay }, { now: () => now },
    callback => { const id = ++timerId; timers.set(id, callback); return id },
    id => timers.delete(id))
  const renderer = vue.createRenderer({
    createElement: tag => ({ tag, children: [], props: {}, style: {}, parent: null, text: '' }),
    createText: text => ({ tag: '#text', text, children: [] }),
    createComment: text => ({ tag: '#comment', text: '', children: [] }),
    insert(child, parent, anchor) {
      if (child.parent) child.parent.children.splice(child.parent.children.indexOf(child), 1)
      const index = anchor ? parent.children.indexOf(anchor) : -1
      if (index < 0) parent.children.push(child)
      else parent.children.splice(index, 0, child)
      child.parent = parent
    },
    remove(child) {
      if (child.parent) child.parent.children.splice(child.parent.children.indexOf(child), 1)
      child.parent = null
    },
    setText(node, text) { node.text = text },
    setElementText(node, text) { node.text = text; node.children = [] },
    patchProp(node, key, before, value) { node.props[key] = value },
    parentNode: node => node.parent,
    nextSibling(node) { return node.parent?.children[node.parent.children.indexOf(node) + 1] || null },
  })
  const root = { children: [] }
  const app = renderer.createApp({ setup: () => () => vue.h(component, { ...props, onOpenSource: source => openedSources.push(source), onInspect: () => inspections.push(true) }) })
  app.mount(root)
  const all = (node = root) => [node, ...node.children.flatMap(child => all(child))]
  const text = (node = root) => `${node.text || ''}${node.children.map(child => text(child)).join('')}`
  const find = predicate => all().find(predicate)
  return {
    props, timers, openedSources, inspections, all, text, find,
    button: () => find(node => node.tag === 'button'),
    async flush() { await vue.nextTick(); await vue.nextTick() },
    async tick(ms) { now += ms; timers.forEach(callback => callback()); await this.flush() },
    async toggle() { this.button().props.onClick(); await this.flush() },
    dispose: () => app.unmount(),
  }
}

test('live trace expands, updates the current action and clock, then collapses with a frozen duration', async () => {
  const h = harness({ running: true, startedAt: 100_000 })
  try {
    assert.equal(h.button().props['aria-expanded'], true)
    assert.match(h.text(h.button()), /正在连接/)
    assert.equal(h.timers.size, 1)
    h.props.entries.push({ id: 'read', kind: 'tool', title: '读取示例笔记', status: 'running' })
    await h.flush()
    assert.match(h.text(h.button()), /读取示例笔记.*1 步.*0 秒/)
    await h.tick(4_000)
    assert.match(h.text(h.button()), /4 秒/)
    h.props.entries[0].status = 'completed'
    h.props.elapsedMs = 4_350
    h.props.running = false
    await h.flush()
    assert.equal(h.button().props['aria-expanded'], false)
    assert.match(h.text(h.button()), /处理过程.*1 步.*4 秒/)
    assert.equal(h.timers.size, 0)
    await h.tick(20_000)
    assert.match(h.text(h.button()), /4 秒/)
    await h.toggle()
    assert.equal(h.button().props['aria-expanded'], true)
  } finally { h.dispose() }
})

test('manual collapse stays collapsed during updates and native button exposes its controlled region', async () => {
  const h = harness({ running: true })
  try {
    assert.equal(h.button().props.type, 'button')
    assert.ok(h.find(node => node.props?.id === h.button().props['aria-controls']))
    await h.toggle()
    h.props.entries.push({ id: 'search', kind: 'tool', title: '检索示例资料', status: 'running' })
    await h.flush()
    assert.equal(h.button().props['aria-expanded'], false)
    assert.match(h.text(h.button()), /检索示例资料/)
    h.props.running = false
    await h.flush()
    h.props.running = true
    await h.flush()
    assert.equal(h.button().props['aria-expanded'], true)
    assert.equal(h.timers.size, 1)
  } finally { h.dispose() }
  assert.equal(h.timers.size, 0)
})

test('restored traces begin collapsed and keep errors, confirmations and interrupted work visible in summary', async () => {
  const h = harness({ elapsedMs: 62_000, entries: [
    { id: 'failed', kind: 'tool', title: '读取示例资料', status: 'error', detail: '示例资料已移除' },
    { id: 'pending', kind: 'tool', title: '更新示例笔记', status: 'waiting' },
    { id: 'stale', kind: 'phase', title: '生成结果', status: 'running' },
  ] })
  try {
    assert.equal(h.button().props['aria-expanded'], false)
    assert.equal(h.timers.size, 0)
    assert.match(h.text(h.button()), /1 项未成功.*1 项待确认.*1 项中断.*1 分 2 秒/)
    assert.doesNotMatch(h.text(h.button()), /全部完成/)
    await h.toggle()
    assert.match(h.text(), /示例资料已移除/)
    assert.match(h.text(), /生成结果已中断/)
  } finally { h.dispose() }
})

test('nested tool details render as plain text and long details do not create HTML nodes', async () => {
  const maliciousDetail = '<img src=x onerror="alert(1)">\n示例工具详情'
  const h = harness({ running: true, entries: [
    { id: 'outer', kind: 'tool', title: '自适应工具', status: 'running' },
    { id: 'inner', kind: 'tool', parentId: 'outer', title: '读取示例笔记', status: 'completed', detail: maliciousDetail, elapsedMs: 2_000 },
  ] })
  try {
    const row = h.find(node => node.tag === 'li' && String(node.props.class).includes('is-nested'))
    assert.ok(row)
    assert.match(h.text(row), /2 秒/)
    assert.ok(h.text(row).includes(maliciousDetail))
    assert.equal(h.find(node => node.tag === 'img'), undefined)
    assert.equal(h.all().some(node => Object.hasOwn(node.props || {}, 'innerHTML')), false)
  } finally { h.dispose() }
})

test('failed runs without a tool error still display an incomplete state', () => {
  const h = harness({ failed: true, entries: [{ id: 'phase', kind: 'phase', status: 'completed', title: '分析请求' }] })
  try {
    assert.match(h.text(h.button()), /处理未完成/)
    assert.ok(h.find(node => String(node.props?.class).includes('trace-mark is-error')))
  } finally { h.dispose() }
})

test('plans render separately from actual execution and pending work cannot claim a completed run', async () => {
  const h = harness({ running: true, entries: [
    { id: 'plan-1', kind: 'plan', title: '核对合成来源', status: 'running' },
    { id: 'plan-2', kind: 'plan', title: '整理合成结论', status: 'pending' },
    { id: 'read', kind: 'tool', title: '读取合成来源', status: 'running' },
  ] })
  try {
    assert.match(h.text(h.button()), /读取合成来源.*1 步.*2 项计划.*1 项待执行/)
    assert.doesNotMatch(h.text(h.button()), /待确认/)
    const plan = h.find(node => node.tag === 'ol' && node.props['aria-label'] === '工作计划')
    const execution = h.find(node => node.tag === 'ol' && node.props['aria-label'] === '执行步骤')
    assert.match(h.text(plan), /核对合成来源进行中.*整理合成结论待执行/)
    assert.doesNotMatch(h.text(plan), /读取合成来源/)
    assert.match(h.text(execution), /读取合成来源/)
    assert.doesNotMatch(h.text(execution), /整理合成结论/)
    h.props.entries[0].status = 'completed'
    h.props.entries[2].status = 'completed'
    h.props.running = false
    await h.flush()
    assert.ok(h.find(node => String(node.props?.class).includes('trace-mark is-pending')))
    assert.equal(h.find(node => String(node.props?.class).includes('trace-mark is-completed')), undefined)
    assert.match(h.text(h.button()), /1 项待执行/)
    assert.doesNotMatch(h.text(h.button()), /待确认|项中断/)
  } finally { h.dispose() }
})

test('a plan alone is identified as a plan and does not invent an executed step', () => {
  const h = harness({ running: true, entries: [{ id: 'plan-1', kind: 'plan', title: '整理合成资料', status: 'running' }] })
  try {
    assert.match(h.text(h.button()), /计划：整理合成资料.*1 项计划/)
    assert.doesNotMatch(h.text(h.button()), /1 步/)
    assert.match(h.text(), /尚无实际执行记录/)
    assert.equal(h.find(node => node.tag === 'ol' && node.props['aria-label'] === '执行步骤'), undefined)
  } finally { h.dispose() }
})

test('adjusted plan rows stay reviewable without being counted as failures, interruptions or remaining work', () => {
  const h = harness({ entries: [
    { id: 'plan-1', kind: 'plan', title: '核对合成来源', status: 'completed' },
    { id: 'plan-2', kind: 'plan', title: '合成旧计划', status: 'cancelled', detail: '计划已调整，此步骤不再需要' },
    { id: 'result', kind: 'phase', title: '本轮结果', status: 'completed' },
  ] })
  try {
    assert.match(h.text(h.button()), /1 步.*1 项计划.*1 项已调整/)
    assert.doesNotMatch(h.text(h.button()), /待执行|待确认|中断|未成功/)
    assert.match(h.text(), /工作计划1 \/ 1.*合成旧计划已调整/)
    assert.ok(h.find(node => String(node.props?.class).includes('is-plan is-cancelled')))
  } finally { h.dispose() }
})

test('failed and waiting outcomes remain distinct while pending plans are present', () => {
  for (const state of ['error', 'waiting']) {
    const h = harness({ entries: [
      { id: 'plan-1', kind: 'plan', title: '完成合成编辑', status: 'pending' },
      { id: 'result', kind: 'phase', title: '本轮结果', status: state },
    ] })
    try {
      assert.ok(h.find(node => String(node.props?.class).includes(`trace-mark is-${state}`)))
      assert.match(h.text(h.button()), /1 项待执行/)
      assert.match(h.text(h.button()), state === 'error' ? /1 项未成功/ : /1 项待确认/)
    } finally { h.dispose() }
  }
})

test('step disclosure shows the actual tool arguments, linked sources and safe websites only after opening', async () => {
  const source = { type: 'note', id: 9001, title: '合成笔记 · 检索方法', snippet: '<b>先检索，再核对原文。</b>', seq: 2, channels: ['vector'] }
  const h = harness({ running: true, entries: [{ id: 'lookup', kind: 'tool', title: '检索相关知识', status: 'completed',
    tools: [{ name: 'search_knowledge', title: '检索知识库', arguments: { query: '合成关键词', limit: 3 } }],
    sources: [source], websites: [{ title: '合成示例站点', url: 'https://example.com/synthetic?q=demo', snippet: '<b>合成网页摘要</b>' }],
    result: '找到与问题相关的合成来源。',
  }] })
  try {
    const button = h.find(node => node.tag === 'button' && node.props['aria-controls']?.includes('-step-'))
    assert.equal(button.props.type, 'button')
    assert.equal(button.props['aria-expanded'], false)
    assert.match(h.text(button), /1 项工具 · 1 条资料 · 1 个网站 · 查看详情/)
    assert.equal(h.find(node => String(node.props?.class).includes('entry-records')), undefined)
    button.props.onClick()
    await h.flush()
    assert.equal(button.props['aria-expanded'], true)
    assert.ok(h.find(node => node.props?.id === button.props['aria-controls']))
    assert.match(h.text(), /检索知识库.*search_knowledge.*调用参数.*合成关键词/s)
    assert.equal(h.inspections.length, 1)
    assert.match(h.text(), /第 3 段 · 语义.*先检索，再核对原文。/)
    const sourceButton = h.find(node => node.tag === 'button' && node.props.class === 'record-source-link')
    sourceButton.props.onClick()
    assert.deepEqual(h.openedSources, [source])
    const link = h.find(node => node.tag === 'a')
    assert.equal(link.props.href, 'https://example.com/synthetic?q=demo')
    assert.equal(link.props.target, '_blank')
    assert.equal(link.props.rel, 'noopener noreferrer')
    assert.match(h.text(), /合成网页摘要.*返回结果预览.*相关的合成来源/)
  } finally { h.dispose() }
})

test('same-id streamed replacements preserve an opened step while new operation details arrive', async () => {
  const h = harness({ running: true, entries: [{ id: 'fetch', title: '读取网页', status: 'running', toolName: 'web_fetch' }] })
  try {
    let button = h.find(node => node.tag === 'button' && node.props['aria-controls']?.includes('-step-'))
    button.props.onClick()
    await h.flush()
    const regionId = button.props['aria-controls']
    h.props.entries = [{ id: 'fetch', title: '读取网页', status: 'error', tools: [{ name: 'web_fetch', title: '读取网页', arguments: { url: 'https://example.org/synthetic' } }],
      websites: [{ url: 'https://example.org/synthetic' }], error: '合成错误：网页返回 404。', elapsedMs: 6000 }]
    await h.flush()
    button = h.find(node => node.tag === 'button' && node.props['aria-controls'] === regionId)
    assert.equal(button.props['aria-expanded'], true)
    assert.match(h.text(), /失败原因合成错误：网页返回 404。/)
    h.props.running = false
    await h.flush()
    assert.equal(h.button().props['aria-expanded'], true, 'completion must not hide a step being inspected')
    assert.equal(h.all().filter(node => node.tag === 'li' && String(node.props.class).includes('trace-entry')).length, 1)
    button.props.onClick()
    await h.flush()
    assert.equal(button.props['aria-expanded'], false)
    assert.equal(h.find(node => node.props?.id === regionId), undefined)
  } finally { h.dispose() }
})

test('untrusted previews remain plain text and bounded; unsafe URLs never become links', async () => {
  const malicious = '<img src=x onerror="alert(1)">'
  const h = harness({ running: true, entries: [{ id: 'safe', title: '合成工具', status: 'error',
    tools: [{ name: 'synthetic_tool', arguments: { content: malicious + 'x'.repeat(5000) } }],
    websites: [{ url: 'javascript:alert(1)' }, { url: 'data:text/html,unsafe' }, { url: 'file:///private' }, { url: 'https://name:password@example.com/private' }],
    sources: [{ type: 'note', id: '../invalid', title: malicious, snippet: '合成无效来源' }], result: malicious + 'z'.repeat(8000), error: malicious,
  }] })
  try {
    h.find(node => node.tag === 'button' && node.props['aria-controls']?.includes('-step-')).props.onClick()
    await h.flush()
    assert.equal(h.find(node => node.tag === 'a'), undefined)
    assert.equal(h.find(node => node.props?.class === 'record-source-link'), undefined)
    assert.match(h.text(), /此资料未提供可打开的来源位置/)
    assert.equal(h.find(node => node.tag === 'img'), undefined)
    assert.equal(h.all().some(node => Object.hasOwn(node.props || {}, 'innerHTML')), false)
    const previews = h.all().filter(node => node.tag === 'pre')
    assert.equal(previews.length, 3)
    assert.ok(previews.every(node => h.text(node).length <= 4020))
    assert.match(h.text(), /预览已截断/)
  } finally { h.dispose() }
})

test('legacy records show only their recorded tool and never invent websites or source links', async () => {
  const h = harness({ running: true, entries: [
    { id: 'legacy', title: '读取笔记', status: 'completed', toolName: 'get_note' },
    { id: 'missing', title: '分析请求', detail: '已选择 2 项工具操作', status: 'completed' },
  ] })
  try {
    const buttons = h.all().filter(node => node.tag === 'button' && node.props['aria-controls']?.includes('-step-'))
    assert.equal(buttons.length, 1)
    buttons[0].props.onClick()
    await h.flush()
    assert.match(h.text(), /get_note.*未记录调用参数/)
    assert.match(h.text(), /此步骤未记录工具、网站或资料明细/)
    assert.equal(h.find(node => node.tag === 'a'), undefined)
    assert.doesNotMatch(h.text(), /网站与网页|检索资料/)
  } finally { h.dispose() }
})
