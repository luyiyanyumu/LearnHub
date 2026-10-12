// 覆盖 layout/AppLayout.vue 的 P0 行为：全局降级横幅的出现 / 可关闭 / 恢复后消失。
//
// 关键点：横幅绑定的必须是 **utils/connectionState.js 那份真实状态**，所以这里不桩它——
// 用例直接调用真实的 markBackendDown / markBackendUp 来驱动，挂载的是真实模板。
// 其余依赖（路由、图标、子组件）用桩隔离。
import test from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import { compileScript, parse } from '@vue/compiler-sfc'
import * as vue from 'vue'
import * as connectionState from '../utils/connectionState.js'

const LAYOUT_FILE = new URL('./AppLayout.vue', import.meta.url)
const ICON_NAMES = [
  'ArrowLeft', 'ArrowRight', 'ChatDotRound', 'Collection', 'Cpu', 'Document',
  'FolderOpened', 'House', 'Moon', 'Notebook', 'Setting', 'Sunny',
]

globalThis.window = new EventTarget()
globalThis.window.matchMedia = () => ({ matches: false, addEventListener() {}, removeEventListener() {} })
const stored = new Map()
globalThis.localStorage = {
  getItem: key => stored.get(key) ?? null,
  setItem: (key, value) => stored.set(key, String(value)),
  removeItem: key => stored.delete(key),
}

/** 记账用的 DOM 节点：v-show 会写 el.style.display，所以必须带 style 对象 */
const makeNode = tag => ({
  tag, children: [], props: {}, style: {}, text: '', dataset: {}, parent: null,
  querySelectorAll: () => [], addEventListener() {}, removeEventListener() {},
})

function compile(fileUrl, id) {
  const { descriptor } = parse(readFileSync(fileUrl, 'utf8'))
  const compiled = compileScript(descriptor, { id, inlineTemplate: true, templateOptions: { hoistStatic: false } })
  const factory = new Function('modules', compiled.content
    .replace(/^import\s+([\s\S]*?)\s+from\s+['"]([^'"]+)['"]\s*;?\r?$/gm,
      (_, names, from) => `const ${names.replace(/\bas\b/g, ':')} = modules[${JSON.stringify(from)}]`)
    .replace('export default', 'return'))
  return modules => factory(modules)
}

const stub = name => ({
  name,
  inheritAttrs: false,
  setup(_, { slots, attrs }) {
    return () => vue.h('div', { ...attrs, 'data-stub': name }, slots.default?.() || [])
  },
})

function mount(component) {
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
  const app = renderer.createApp({ setup: () => () => vue.h(component) })
  app.config.warnHandler = () => {}
  app.component('router-link', stub('router-link'))
  app.component('router-view', stub('router-view'))
  for (const name of ICON_NAMES) app.component(name, stub(`icon-${name}`))
  app.mount(root)

  const all = (node = root) => [node, ...(node.children || []).flatMap(all)]
  const text = (node = root) => `${node.text || ''}${(node.children || []).map(text).join('')}`
  const byClass = name => all().find(node => String(node.props?.class || '').split(/\s+/).includes(name))
  return {
    app, root, all, text, byClass,
    banner: () => byClass('offline-bar'),
    closeButton: () => byClass('ob-close'),
    retryButton: () => byClass('ob-btn'),
  }
}

async function flush() { for (let i = 0; i < 8; i++) { await Promise.resolve(); await vue.nextTick() } }

const deferred = () => {
  let resolve, reject
  const promise = new Promise((ok, fail) => { resolve = ok; reject = fail })
  return { promise, resolve, reject }
}

/** 每次挂载前先把链路状态复位，避免用例之间互相污染（模块级 ref 是单例） */
async function renderLayout({ tree } = {}) {
  connectionState.markBackendUp()
  const calls = { tree: 0 }
  const component = compile(LAYOUT_FILE, 'app-layout-banner-behavior')({
    vue: { ...vue, defineAsyncComponent: () => stub('async-component') },
    'vue-router': { useRoute: () => ({ path: '/', fullPath: '/' }) },
    '@element-plus/icons-vue': Object.fromEntries(ICON_NAMES.map(name => [name, stub(`icon-${name}`)])),
    '../composables/useTheme': { isDark: vue.ref(false), toggleTheme() {} },
    '../composables/useViewMode': { focusMode: vue.ref(false) },
    '../components/AgentPanel.vue': stub('agent-panel'),
    '../composables/useNoteTabs.js': { noteTabs: vue.ref([]), isNoteEditorRoute: () => false, openNoteRoute() {} },
    '../utils/connectionState.js': connectionState,
    '../api': { categoryApi: { tree: async () => { calls.tree += 1; return tree ? tree() : [] } } },
  })
  const harness = mount(component)
  await flush()
  return { ...harness, calls, flush }
}

test('链路正常时没有横幅', async () => {
  const h = await renderLayout()
  try {
    assert.equal(connectionState.backendDown.value, false)
    assert.equal(h.banner(), undefined, '正常时不会渲染降级横幅')
    assert.equal(h.byClass('ob-close'), undefined)
  } finally { h.app.unmount(); connectionState.markBackendUp() }
})

test('链路 down 时横幅出现：role="alert" + 失败原因，且原因是拦截器登记的那条', async () => {
  const h = await renderLayout()
  try {
    connectionState.markBackendDown(new Error('connect ECONNREFUSED 127.0.0.1:8080'))
    await flush()
    const banner = h.banner()
    assert.notEqual(banner, undefined, 'down 时应出现横幅')
    assert.equal(banner.props.role, 'alert')
    const rendered = h.text(banner)
    assert.equal(rendered.includes('后端服务未连接'), true)
    assert.equal(rendered.includes('connect ECONNREFUSED 127.0.0.1:8080'), true, '横幅要说明「为什么」')
    assert.equal(rendered.includes('页面上的内容可能不是最新的'), true)
  } finally { h.app.unmount(); connectionState.markBackendUp() }
})

test('横幅可关闭：点关闭按钮后不再显示，但链路状态本身没被改掉', async () => {
  const h = await renderLayout()
  try {
    connectionState.markBackendDown(new Error('boom'))
    await flush()
    const close = h.closeButton()
    assert.notEqual(close, undefined, '应有关闭按钮')
    assert.equal(close.props['aria-label'], '关闭提示')
    assert.equal(close.props.type, 'button')
    close.props.onClick()
    await flush()
    assert.equal(h.banner(), undefined, '关闭后横幅消失')
    // 关的是横幅，不是"假装链路好了"——否则后续页面会以为服务可用
    assert.equal(connectionState.backendDown.value, true, '关闭只影响横幅，不改链路状态')
  } finally { h.app.unmount(); connectionState.markBackendUp() }
})

test('恢复后消失，且再次故障时横幅会回来（关闭只在「本次故障内」有效）', async () => {
  const h = await renderLayout()
  try {
    connectionState.markBackendDown(new Error('第一次失败'))
    await flush()
    assert.notEqual(h.banner(), undefined)

    // 任一请求成功 → 拦截器调 markBackendUp() → 横幅自动消失
    connectionState.markBackendUp()
    await flush()
    assert.equal(h.banner(), undefined, '恢复后横幅应消失')

    // 用户此前关过横幅：新一次故障必须还能看到提示，否则比不显示更危险
    h.closeButton()
    connectionState.markBackendDown(new Error('第二次失败'))
    await flush()
    const banner = h.banner()
    assert.notEqual(banner, undefined, '新一次故障应重新出现横幅')
    assert.equal(h.text(banner).includes('第二次失败'), true)
  } finally { h.app.unmount(); connectionState.markBackendUp() }
})

test('关闭后再故障的完整序列：关 → 恢复 → 又失败 → 关 → 又失败，每次都能看到', async () => {
  const h = await renderLayout()
  try {
    for (const reason of ['故障甲', '故障乙']) {
      connectionState.markBackendDown(new Error(reason))
      await flush()
      assert.notEqual(h.banner(), undefined, `${reason}：应出现横幅`)
      h.closeButton().props.onClick()
      await flush()
      assert.equal(h.banner(), undefined, `${reason}：关掉后应消失`)
      connectionState.markBackendUp()
      await flush()
    }
  } finally { h.app.unmount(); connectionState.markBackendUp() }
})

test('重试按钮：探测期间禁用并改文案，点击会调用探活接口，结束后复原', async () => {
  const pending = deferred()
  const h = await renderLayout({ tree: () => pending.promise })
  try {
    connectionState.markBackendDown(new Error('boom'))
    await flush()
    const retry = h.retryButton()
    assert.notEqual(retry, undefined, '应有重试按钮')
    assert.equal(h.text(retry), '重试连接')
    assert.equal(retry.props.disabled, false)
    assert.equal(h.calls.tree, 0, '没点之前不探测')

    retry.props.onClick()
    await flush()
    assert.equal(h.calls.tree, 1)
    assert.equal(h.text(h.retryButton()), '正在重试…', '探测中应给出反馈')
    assert.equal(h.retryButton().props.disabled, true, '探测中不应重复点击')

    pending.resolve([])
    await flush()
    assert.equal(h.text(h.retryButton()), '重试连接')
    assert.equal(h.retryButton().props.disabled, false)
  } finally { h.app.unmount(); connectionState.markBackendUp() }
})

test('模板耦合（静态核对）：横幅的显隐绑定真实 connectionState，重试探测用既有轻接口', () => {
  const source = readFileSync(LAYOUT_FILE, 'utf8')
  const lines = source.split(/\r?\n/)
  const at = content => lines.findIndex(line => line.includes(content))
  // 必须是导入的那份全局状态，不能是布局内部的本地 ref（否则横幅和页面状态会各说各话）
  assert.notEqual(at("import { backendDown, backendDownReason } from '../utils/connectionState.js'"), -1)
  assert.notEqual(at('v-if="backendDown && !bannerDismissed"'), -1, '横幅显隐应由 backendDown 与本地关闭位共同决定')
  assert.notEqual(at('{{ backendDownReason }}'), -1, '横幅原因来自共享状态')
  assert.equal(at(':disabled="probing"') !== -1, true)
  assert.equal(at('@click="bannerDismissed = true"') !== -1, true)
  // 关闭位只在 backendDown 变化时重置——这正是「关闭只在本次故障内有效」的实现
  const watchIndex = at('watch(backendDown, () => {')
  assert.notEqual(watchIndex, -1)
  assert.equal(lines.slice(watchIndex, watchIndex + 3).some(line => line.includes('bannerDismissed.value = false')), true)
  // 探活用最轻的既有接口（分类树），不新增探测端点
  assert.equal(source.includes('await categoryApi.tree()'), true)
})
