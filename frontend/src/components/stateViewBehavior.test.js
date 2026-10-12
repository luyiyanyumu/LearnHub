// 覆盖 components/StateView.vue 的模板/道具契约（P0 新增的三态唯一出口）。
//
// 沿用本仓既有模式（见 agentRunTraceBehavior.test.js）：用 @vue/compiler-sfc 的
// parse + compileScript 编译后，用一个记账用的自定义 renderer 真挂载，
// 断言渲染出的节点/属性/事件，而不是对源码做字符串匹配。
import test from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import { compileScript, parse } from '@vue/compiler-sfc'
import * as vue from 'vue'

const source = readFileSync(new URL('./StateView.vue', import.meta.url), 'utf8')
const compiled = compileScript(parse(source).descriptor, { id: 'state-view-behavior', inlineTemplate: true })
const factory = new Function('modules', 'emitHook', compiled.content
  .replace(/^import\s+([\s\S]*?)\s+from\s+['"]([^'"]+)['"]\s*;?\r?$/gm,
    (_, names, from) => `const ${names.replace(/\bas\b/g, ':')} = modules[${JSON.stringify(from)}]`)
  .replace('export default', 'return'))

function harness(props = {}, slots = {}) {
  const component = factory({ vue })
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
  const app = renderer.createApp({ setup: () => () => vue.h(component, props, slots) })
  app.mount(root)
  const all = (node = root) => [node, ...(node.children || []).flatMap(child => all(child))]
  const text = (node = root) => `${node.text || ''}${(node.children || []).map(child => text(child)).join('')}`
  const find = predicate => all().find(predicate)
  const byClass = name => find(node => String(node.props?.class || '').split(/\s+/).includes(name))
  return {
    all, text, find, byClass,
    root: () => find(node => String(node.props?.class || '').includes('state-view')),
    actionButton: () => find(node => node.tag === 'el-button'),
    dispose: () => app.unmount(),
  }
}

test('error 态：root 用 role="alert"，渲染 title/detail，动作按钮是默认样式', () => {
  const h = harness({ state: 'error', title: '笔记列表加载失败', detail: '服务暂时不可用', actionLabel: '重试' })
  try {
    const root = h.root()
    assert.equal(root.props.role, 'alert')
    assert.equal(root.props.class, 'state-view is-error is-block')
    assert.equal(h.byClass('sv-title').text, '笔记列表加载失败')
    assert.equal(h.byClass('sv-detail').text, '服务暂时不可用')
    assert.equal(h.actionButton().props.type, 'default')
  } finally { h.dispose() }
})

test('loading 态：root 用 role="status" 且带 aria-busy / aria-live，渲染 aria-hidden 骨架', () => {
  const h = harness({ state: 'loading' })
  try {
    const root = h.root()
    assert.equal(root.props.role, 'status')
    assert.equal(root.props['aria-busy'], true)
    assert.equal(root.props['aria-live'], 'polite')
    const skeleton = h.byClass('sv-skeleton')
    assert.equal(skeleton.props['aria-hidden'], 'true')
    assert.equal(skeleton.children.filter(node => String(node.props?.class).includes('sv-line')).length, 3)
  } finally { h.dispose() }
})

test('empty 态：root 用 role="status" 且不带 aria-busy，文案与 error 态不同', () => {
  const empty = harness({ state: 'empty', title: '这里还没有内容', detail: '新建一篇试试', actionLabel: '新建' })
  const error = harness({ state: 'error', title: '笔记列表加载失败', detail: '服务暂时不可用', actionLabel: '重试' })
  try {
    assert.equal(empty.root().props.role, 'status')
    assert.ok(!empty.root().props['aria-busy'], 'empty 态不应被认为是 busy')
    assert.equal(empty.root().props.class, 'state-view is-empty is-block')
    // 三个态的 class 两两不同，页面/测试可以据此区分
    assert.notEqual(empty.root().props.class, error.root().props.class)
    // empty 与 error 的可见文案必须不同：这正是 P0 要修的「失败被说成没有内容」
    assert.notEqual(empty.text(), error.text())
    assert.notEqual(empty.byClass('sv-title').text, error.byClass('sv-title').text)
    assert.equal(empty.actionButton().props.type, 'primary')
  } finally { empty.dispose(); error.dispose() }
})

test('loading 态不渲染任何文案，「三个态各有各的文案」这条在当前实现下不成立（如实固定现状）', () => {
  // 任务预期是「三个态渲染出的文案互不相同」。实测：loading 走的是 <template v-else> 之外的
  // 骨架分支，title / detail 两个道具在 loading 态**被完全忽略**，可见文本是空串。
  // 所以「三态各有各的文案」不成立——如实断言现状，不写"应然"断言，也不改源码去迁就测试。
  // 若将来给 loading 态补了文案，这条会红，提示同步更新预期。
  const h = harness({ state: 'loading', title: '正在加载笔记列表', detail: '请稍候', actionLabel: '重试' })
  try {
    assert.equal(h.text().replace(/\s/g, ''), '', 'loading 态当前不渲染任何可见文本')
    assert.equal(h.byClass('sv-title'), undefined, 'loading 态不渲染 .sv-title')
    assert.equal(h.byClass('sv-detail'), undefined, 'loading 态不渲染 .sv-detail')
    // 同一分支下 actionLabel 也被忽略：加载中不给动作按钮
    assert.equal(h.actionButton(), undefined, 'loading 态当前不渲染动作按钮')
  } finally { h.dispose() }
})

test('actionLabel 存在时渲染动作按钮，点击发出一次 action 事件', () => {
  let fired = 0
  const h = harness({ state: 'error', title: 'T', actionLabel: '重试', onAction: () => { fired++ } })
  try {
    const button = h.actionButton()
    assert.ok(button, '有 actionLabel 时应渲染按钮')
    assert.equal(h.text(button), '重试')
    assert.equal(typeof button.props.onClick, 'function')
    button.props.onClick()
    assert.equal(fired, 1)
  } finally { h.dispose() }
})

test('没有 actionLabel 也没有 action 插槽时不渲染动作区', () => {
  const h = harness({ state: 'empty', title: '这里还没有内容', detail: '新建一篇试试' })
  try {
    assert.equal(h.byClass('sv-actions'), undefined)
    assert.equal(h.find(node => node.tag === 'el-button'), undefined)
  } finally { h.dispose() }
})

test('提供 action 插槽时仍渲染动作区并显示插槽内容', () => {
  const h = harness({ state: 'empty', title: 'T' }, { action: () => vue.h('span', { class: 'x-slot' }, '自定义动作') })
  try {
    assert.ok(h.byClass('sv-actions'), '有 action 插槽时应渲染动作区')
    assert.equal(h.find(node => node.props?.class === 'x-slot').text, '自定义动作')
  } finally { h.dispose() }
})

test('detail 为空时不渲染次文案行，避免出现空段落', () => {
  const h = harness({ state: 'error', title: '仅标题' })
  try {
    assert.equal(h.byClass('sv-detail'), undefined)
    assert.equal(h.byClass('sv-title').text, '仅标题')
  } finally { h.dispose() }
})

test('size 道具映射成 class：默认整块 block，页内小提示用 inline', () => {
  const block = harness({ state: 'loading' })
  const inline = harness({ state: 'error', size: 'inline', title: 'T' })
  try {
    assert.equal(block.root().props.class, 'state-view is-loading is-block')
    assert.equal(inline.root().props.class, 'state-view is-error is-inline')
  } finally { block.dispose(); inline.dispose() }
})
