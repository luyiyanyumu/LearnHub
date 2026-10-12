// 覆盖 views/QuickRefs.vue 的 P0 行为：把「库为空」与「筛选后没有结果」分成两句话，
// 且失败是第三种说法 —— 不再把三者混成一句"没有数据"。
//
// 真挂载：StateView 用**真实组件**（断言最终文案与动作按钮），分类/列表接口用桩
// 控制成功、失败、空列表三条路径，筛选条件通过 el-input 的 v-model 回调真实改写。
import test from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import { compileScript, parse } from '@vue/compiler-sfc'
import * as vue from 'vue'
import * as htmlQuotes from '../utils/htmlQuotes.js'
import * as sourceRouteReader from '../utils/sourceRouteReader.js'

const QUICK_REFS_FILE = new URL('./QuickRefs.vue', import.meta.url)
const STATE_VIEW_FILE = new URL('../components/StateView.vue', import.meta.url)

globalThis.window = new EventTarget()
globalThis.window.getSelection = () => ({ toString: () => '' })

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

/** 子组件探针桩；vmodel 为真时按 modelValue 决定是否渲染（弹窗关着就不渲染内容） */
const stub = (name, { vmodel = false } = {}) => ({
  name,
  inheritAttrs: false,
  setup(_, { slots, attrs }) {
    return () => {
      if (vmodel && !attrs.modelValue) return null
      const scope = { row: { id: 1, title: '', categoryName: '', updatedAt: '' }, $index: 0 }
      return vue.h('div', { ...attrs, 'data-stub': name },
        [...(slots.default?.(scope) || []), ...(slots.empty?.() || []), ...(slots.append?.() || [])])
    }
  },
})

const StateView = compile(STATE_VIEW_FILE, 'quick-refs-behavior-state-view')({ vue })
const EL_STUBS = [
  'el-button', 'el-card', 'el-dropdown', 'el-dropdown-menu', 'el-dropdown-item',
  'el-input', 'el-option', 'el-select', 'el-tag',
]

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
  for (const name of EL_STUBS) app.component(name, stub(name))
  app.component('el-dialog', stub('el-dialog', { vmodel: true }))
  app.mount(root)

  const all = (node = root) => [node, ...(node.children || []).flatMap(all)]
  const text = (node = root) => `${node.text || ''}${(node.children || []).map(text).join('')}`
  const subtree = node => [node, ...(node.children || []).flatMap(subtree)]
  const allStubs = name => all().filter(node => node.props?.['data-stub'] === name)
  const byClass = name => all().find(node => String(node.props?.class || '').split(/\s+/).includes(name))
  /** 空/失败态那张卡片的动作按钮（在 sv-actions 里，不是工具栏那些按钮） */
  const actionButton = () => {
    const actions = byClass('sv-actions')
    return actions ? subtree(actions).find(node => node.props?.['data-stub'] === 'el-button') : undefined
  }
  return {
    app, root, all, text, subtree, allStubs, byClass, actionButton,
    title: () => byClass('sv-title')?.text,
    detail: () => byClass('sv-detail')?.text,
    actionLabel: () => {
      const button = actionButton()
      return button ? text(button) : undefined
    },
    /** 通过工具栏搜索框的 v-model 回调真实改写筛选条件 */
    setKeyword: async value => { allStubs('el-input')[0].props['onUpdate:modelValue'](value) },
    flush,
  }
}

async function flush() { for (let i = 0; i < 8; i++) { await Promise.resolve(); await vue.nextTick() } }

/** 造一个 QuickRefs 实例：列表/分类接口行为由用例注入 */
async function renderQuickRefs({ list = async () => [], tree = async () => [] } = {}) {
  const calls = { list: 0, tree: 0 }
  const component = compile(QUICK_REFS_FILE, 'quick-refs-behavior')({
    vue,
    'element-plus': { ElMessage: { warning() {}, success() {}, error() {} }, ElMessageBox: { async confirm() { return 'confirm' } } },
    'vue-router': { useRoute: () => ({ query: {}, path: '/refs' }) },
    '../components/BlockPreview.vue': stub('block-preview'),
    '../components/StateView.vue': StateView,
    '../api': {
      categoryApi: { tree: async () => { calls.tree += 1; return tree() } },
      quickRefApi: { list: async () => { calls.list += 1; return list() } },
    },
    '../utils/htmlQuotes': htmlQuotes,
    '../utils/sourceRouteReader': sourceRouteReader,
  })
  const harness = mount(component)
  await flush()
  return { ...harness, calls }
}

const ROWS = [{ id: 1, title: '合成速查卡', content: 'kubectl get pods', categoryName: '', updatedAt: '2026-01-01T00:00' }]

test('库为空（无筛选）：说「还没有速查卡」，动作是「新建第一张」', async () => {
  const h = await renderQuickRefs()
  try {
    assert.equal(h.title(), '还没有速查卡')
    assert.equal(h.detail(), '速查卡用来存常用命令和配置片段，方便随时复制')
    assert.equal(h.actionLabel(), '新建第一张')
  } finally { h.app.unmount() }
})

test('筛选后没结果：换成「没有匹配的速查卡」，并提示换条件 —— 与「库为空」文案完全不同', async () => {
  const h = await renderQuickRefs()
  try {
    const emptyLibrary = { title: h.title(), detail: h.detail(), action: h.actionLabel() }
    await h.setKeyword('nginx')
    await h.flush()
    const noMatch = { title: h.title(), detail: h.detail(), action: h.actionLabel() }
    assert.equal(noMatch.title, '没有匹配的速查卡')
    assert.equal(noMatch.detail, '换个关键词再试，或清空筛选条件')
    assert.equal(noMatch.action, '重置筛选')
    // 三处（标题/说明/动作）都要不一样，只改一处等于没区分
    assert.notEqual(noMatch.title, emptyLibrary.title)
    assert.notEqual(noMatch.detail, emptyLibrary.detail)
    assert.notEqual(noMatch.action, emptyLibrary.action)
  } finally { h.app.unmount() }
})

test('加载失败：是第三种说法（给出原因+重试），既不等于「库为空」也不等于「筛选无结果」', async () => {
  const h = await renderQuickRefs({ list: async () => { throw new Error('速查卡接口 502') } })
  try {
    assert.equal(h.title(), '速查卡加载失败')
    assert.equal(h.detail(), '速查卡接口 502')
    assert.equal(h.byClass('state-view').props.role, 'alert')
    assert.equal(h.actionLabel(), '重试')
    assert.notEqual(h.title(), '还没有速查卡')
    assert.notEqual(h.title(), '没有匹配的速查卡')
  } finally { h.app.unmount() }
})

test('筛选无结果时点动作 = 重置筛选，回到「还没有速查卡」', async () => {
  const h = await renderQuickRefs()
  try {
    await h.setKeyword('nginx')
    await h.flush()
    assert.equal(h.actionLabel(), '重置筛选')
    const before = h.calls.list
    h.actionButton().props.onClick()
    await h.flush()
    assert.equal(h.calls.list > before, true, '重置筛选会重新拉列表')
    assert.equal(h.title(), '还没有速查卡')
    assert.equal(h.actionLabel(), '新建第一张')
  } finally { h.app.unmount() }
})

test('库为空时点动作 = 新建第一张（打开新建弹窗，而不是"重置筛选"）', async () => {
  const h = await renderQuickRefs()
  try {
    assert.equal(h.allStubs('el-dialog').length, 0, '初始不应有打开的弹窗')
    assert.equal(h.actionLabel(), '新建第一张')
    h.actionButton().props.onClick()
    await h.flush()
    assert.equal(h.allStubs('el-dialog').length, 1, '点「新建第一张」应打开弹窗')
  } finally { h.app.unmount() }
})

test('分类接口失败不连累主列表（旁路降级）：列表照常渲染，不显示失败态', async () => {
  const h = await renderQuickRefs({
    list: async () => ROWS,
    tree: async () => { throw new Error('分类接口 500') },
  })
  try {
    assert.equal(h.calls.tree, 1)
    assert.equal(h.calls.list, 1, '分类失败后主列表仍要发请求')
    assert.notEqual(h.byClass('ref-grid'), undefined, '主列表应照常渲染')
    assert.equal(h.byClass('ref-card').props.class.includes('ref-card'), true)
    assert.equal(h.byClass('sv-title'), undefined, '不该出现失败态')
    assert.equal(h.allStubs('el-option').length, 0, '分类下拉为空，但列表没受影响')
  } finally { h.app.unmount() }
})
