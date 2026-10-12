// 覆盖 views/Dashboard.vue 的 P0 行为：总览接口失败时不再把故障渲染成 0。
//
// 做法是**真挂载**（@vue/compiler-sfc 编译 + 记账 renderer）：总览/热力图接口用桩控制
// 成功与失败两条路径；子组件里 ActivityHeatmap 用探针桩、StateView 用**真实组件**，
// 这样断言到的是最终渲染出来的文案与数字，而不是"我以为它会传什么"。
import test from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import { compileScript, parse } from '@vue/compiler-sfc'
import * as vue from 'vue'

const DASHBOARD_FILE = new URL('./Dashboard.vue', import.meta.url)
const STATE_VIEW_FILE = new URL('../components/StateView.vue', import.meta.url)

globalThis.window = new EventTarget()

/** 记账用的 DOM 节点：够渲染用，并给模板 ref 的 querySelectorAll 一个空实现 */
const makeNode = tag => ({
  tag, children: [], props: {}, style: {}, text: '', dataset: {}, parent: null,
  querySelectorAll: () => [], addEventListener() {}, removeEventListener() {},
})

/** 编译 SFC 并立即实例化：import 说明符 → 解构重命名（`as` → `:`），模块由调用方注入 */
function compile(fileUrl, id) {
  const { descriptor } = parse(readFileSync(fileUrl, 'utf8'))
  const compiled = compileScript(descriptor, { id, inlineTemplate: true, templateOptions: { hoistStatic: false } })
  const factory = new Function('modules', compiled.content
    .replace(/^import\s+([\s\S]*?)\s+from\s+['"]([^'"]+)['"]\s*;?\r?$/gm,
      (_, names, from) => `const ${names.replace(/\bas\b/g, ':')} = modules[${JSON.stringify(from)}]`)
    .replace('export default', 'return'))
  return modules => factory(modules)
}

/** 子组件探针桩：把透传属性与默认/空槽渲染出来，并打上 data-stub 便于定位 */
const stub = name => ({
  name,
  inheritAttrs: false,
  setup(_, { slots, attrs }) {
    return () => vue.h('div', { ...attrs, 'data-stub': name },
      [...(slots.default?.({ row: { id: 1, title: '', categoryName: '', updatedAt: '' } }) || []), ...(slots.empty?.() || [])])
  },
})

const StateView = compile(STATE_VIEW_FILE, 'dashboard-behavior-state-view')({ vue })

function mount(component, props = {}) {
  const renderer = vue.createRenderer({
    createElement: makeNode,
    createText: text => ({ tag: '#text', text, children: [], props: {} }),
    createComment: () => ({ tag: '#comment', text: '', children: [], props: {} }),
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
    insertStaticContent(content, parent, anchor) {
      const node = makeNode('#static')
      node.text = content
      this.insert(node, parent, anchor)
      return [node, node]
    },
  })
  const root = makeNode('#root')
  const app = renderer.createApp({ setup: () => () => vue.h(component, props) })
  app.config.warnHandler = () => {}
  for (const name of ['el-card', 'el-table', 'el-table-column', 'el-empty', 'el-link', 'el-tag']) app.component(name, stub(name))
  app.config.globalProperties.$ELEMENT = {}
  app.mount(root)

  const all = (node = root) => [node, ...(node.children || []).flatMap(all)]
  const text = (node = root) => `${node.text || ''}${(node.children || []).map(text).join('')}`
  const byClass = name => all().find(node => String(node.props?.class || '').split(/\s+/).includes(name))
  const byClassAll = name => all().filter(node => String(node.props?.class || '').split(/\s+/).includes(name))
  const subtree = node => [node, ...(node.children || []).flatMap(subtree)]
  const actionButton = () => {
    const actions = byClass('sv-actions')
    return actions ? subtree(actions).find(node => node.tag === 'el-button') : undefined
  }
  return { app, root, all, text, byClass, byClassAll, subtree, actionButton, find: predicate => all().find(predicate) }
}

const flush = async () => { for (let i = 0; i < 8; i++) { await Promise.resolve(); await vue.nextTick() } }

/** 造一个 Dashboard 实例：dashboard()/activity() 的行为由用例决定 */
async function renderDashboard({ dashboard, activity = async () => ({ year: 2026, total: 0, activeDays: 0, max: 0, days: [] }) }) {
  const calls = { dashboard: 0, activity: 0 }
  const component = compile(DASHBOARD_FILE, 'dashboard-behavior')({
    vue,
    'vue-router': { useRouter: () => ({ push() {} }) },
    '../api': {
      statsApi: {
        dashboard: async () => { calls.dashboard += 1; return dashboard() },
        activity: async () => { calls.activity += 1; return activity() },
      },
    },
    '../components/ActivityHeatmap.vue': stub('activity-heatmap'),
    '../components/StateView.vue': StateView,
  })
  const harness = mount(component)
  await flush()
  return { ...harness, calls }
}

const STATS_OK = {
  noteTotal: 12,
  refTotal: 3,
  categoryTotal: 2,
  tagTotal: 5,
  categoryStats: [{ id: 1, name: '运维', noteCount: 2, refCount: 1 }],
  recentNotes: [],
}

test('成功：统计卡渲染真实数字，且不出现失败提示（给失败态断言当对照）', async () => {
  const h = await renderDashboard({ dashboard: async () => STATS_OK })
  try {
    assert.equal(h.calls.dashboard, 1)
    assert.equal(h.byClass('stat-grid') !== undefined, true, '成功时统计区块应在')
    const numbers = h.byClassAll('stat-num').map(node => h.text(node))
    assert.deepEqual(numbers, ['12', '3', '2', '5'])
    assert.equal(h.byClass('sv-title'), undefined, '成功时不应出现失败提示')
  } finally { h.app.unmount() }
})

test('失败：出现失败提示，且统计区块与下方区块整块不渲染 —— 不再把故障显示成 0', async () => {
  const h = await renderDashboard({ dashboard: async () => { throw new Error('后端 502') } })
  try {
    // 失败提示：真实 StateView 渲染出的标题与原因
    assert.equal(h.byClass('sv-title').text, '总览数据加载失败')
    assert.equal(h.byClass('sv-detail').text, '后端 502')
    assert.equal(h.byClass('state-view').props.role, 'alert')

    // 关键：统计数字与标签一个都不渲染 —— 原来这里会显示 4 个 0
    assert.equal(h.byClass('stat-grid'), undefined, '失败时统计区块应被排除')
    assert.equal(h.byClassAll('stat-num').length, 0, '不应渲染任何统计数字')
    assert.equal(h.byClassAll('stat-label').length, 0, '不应渲染统计标签')
    // 下方「最近更新的笔记 / 分类分布」来自同一个请求，也一起收敛
    assert.equal(h.byClass('lower'), undefined)
    const rendered = h.text()
    assert.equal(rendered.includes('最近更新的笔记'), false)
    assert.equal(rendered.includes('分类分布'), false)
  } finally { h.app.unmount() }
})

test('失败：热力图是另一个请求，保留可见（局部降级而不是整页白掉）', async () => {
  const h = await renderDashboard({ dashboard: async () => { throw new Error('后端 502') } })
  try {
    assert.equal(h.find(node => node.props?.['data-stub'] === 'activity-heatmap') !== undefined, true)
    assert.equal(h.calls.activity, 1)
  } finally { h.app.unmount() }
})

test('失败：点提示里的「重试」会重新请求总览（@action 接到 load）', async () => {
  let fail = true
  const h = await renderDashboard({
    dashboard: async () => {
      if (fail) throw new Error('后端 502')
      return STATS_OK
    },
  })
  try {
    assert.equal(h.calls.dashboard, 1)
    const retry = h.actionButton()
    assert.equal(retry !== undefined, true, '失败提示里应有动作按钮')
    assert.equal(h.text(retry), '重试')
    fail = false
    retry.props.onClick()
    await flush()
    assert.equal(h.calls.dashboard, 2)
    assert.equal(h.byClass('sv-title'), undefined, '重试成功后失败提示应消失')
    assert.deepEqual(h.byClassAll('stat-num').map(node => h.text(node)), ['12', '3', '2', '5'])
  } finally { h.app.unmount() }
})

test('模板耦合（静态核对）：统计区块与下方区块共用 statsFailed 这一个开关，失败时同时收敛', () => {
  // 真挂载已经证明运行时行为；这条读源码，防的是「有人只把其中一处改回 v-if="true"」
  // 这类不改变 setup、却让两处状态各说各话的改动。
  const source = readFileSync(DASHBOARD_FILE, 'utf8')
  const lines = source.split(/\r?\n/)
  const at = content => lines.findIndex(line => line.includes(content))
  // 注意：任务里写的「:161/:199 附近」与实际不符——161 行是 .stat-glow、199 行是 </el-card>。
  // 实际的守卫在下面这两行（按内容定位，不按行号写死）。
  assert.notEqual(at('<div v-if="!statsFailed" class="stat-grid">'), -1, '统计区块应由 !statsFailed 守卫')
  assert.notEqual(at('<div v-if="!statsFailed" class="lower">'), -1, '下方区块应由 !statsFailed 守卫')
  // 失败提示：<StateView> 开标签之后紧跟 v-if="statsFailed"（之间允许有空白行）
  const viewStart = at('<StateView')
  assert.notEqual(viewStart, -1, '应有 StateView 失败提示')
  assert.equal(lines.slice(viewStart, viewStart + 3).some(line => line.includes('v-if="statsFailed"')), true,
    '失败提示应由 statsFailed 守卫')
  const guards = lines.filter(line => line.includes('v-if="!statsFailed"'))
  assert.equal(guards.length, 2, '恰好两处依赖 !statsFailed，没有漏网的第三处')
  // 统计数字只有一个来源，且只在成功路径上渲染
  assert.equal(at('{{ stats[c.key] }}') !== -1, true)
})
