import test from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import { compileScript, parse } from '@vue/compiler-sfc'
import * as vue from 'vue'
import { Close, Document, Menu, Plus } from '@element-plus/icons-vue'
import * as tabUtils from './useNoteTabs.js'

function compileComponent(name, modules) {
  const source = readFileSync(new URL(`../components/${name}.vue`, import.meta.url), 'utf8')
  const compiled = compileScript(parse(source).descriptor, { id: `workspace-${name}`, inlineTemplate: true }).content
  const body = compiled
    .replace(/^import\s+([\s\S]*?)\s+from\s+['"]([^'"]+)['"]\s*;?\r?$/gm, (_, names, from) => {
      const binding = names.trim().replace(/\bas\b/g, ':')
      return `const ${binding} = modules[${JSON.stringify(from)}]`
    })
    .replace("import('../views/NoteEdit.vue')", 'Promise.resolve(modules.NoteEdit)')
    .replace('export default', 'return')
  return new Function('modules', body)(modules)
}

function hostElement(tag, text = '') {
  return { tag, text, children: [], props: {}, style: {}, parent: null, focus() { this.focused = true } }
}

const renderer = vue.createRenderer({
  createElement: hostElement, createText: text => hostElement('#text', text), createComment: text => hostElement('#comment', text),
  insert(child, parent, anchor = null) {
    if (child.parent) this.remove(child)
    child.parent = parent
    const index = anchor ? parent.children.indexOf(anchor) : -1
    parent.children.splice(index < 0 ? parent.children.length : index, 0, child)
  },
  remove(child) { const index = child.parent?.children.indexOf(child); if (index >= 0) child.parent.children.splice(index, 1); child.parent = null },
  setText: (node, text) => { node.text = text }, setElementText: (node, text) => { node.text = text; node.children = [] },
  patchProp: (node, key, _previous, value) => { node.props[key] = value },
  parentNode: node => node.parent,
  nextSibling: node => node.parent?.children[node.parent.children.indexOf(node) + 1] || null,
})

async function flush() { for (let index = 0; index < 8; index++) { await Promise.resolve(); await vue.nextTick() } }
function locate(root, predicate) { if (predicate(root)) return root; for (const child of root.children || []) { const hit = locate(child, predicate); if (hit) return hit } return null }

async function workspaceHarness() {
  const manager = tabUtils.createNoteTabsManager()
  const route = vue.reactive({ path: '/notes/1', fullPath: '/notes/1', params: { id: '1' }, query: {}, hash: '' })
  const navigation = [], instances = new Map(), mountCounts = new Map(), closeChecks = []
  let allowClose = true
  function changeRoute(location) {
    const path = typeof location === 'string' ? location : location.path
    const query = typeof location === 'string' ? {} : location.query || {}
    const hash = typeof location === 'string' ? '' : location.hash || ''
    Object.assign(route, { path, query, hash, params: /^\/notes\/\d+$/.test(path) ? { id: path.split('/')[2] } : {}, fullPath: path + JSON.stringify(query) + hash })
  }
  const router = {
    async push(location) { navigation.push(['push', location]); changeRoute(location); await vue.nextTick() },
    async replace(location) { navigation.push(['replace', location]); changeRoute(location); await vue.nextTick() },
  }
  const NoteEdit = {
    props: ['noteId', 'noteQuery', 'tabKey', 'active'], emits: ['tab-state'],
    setup(props, { emit, expose }) {
      const content = vue.ref(`initial ${props.noteId}`)
      const handle = { content, props }
      expose({ async prepareTabClose() { closeChecks.push(props.tabKey); return allowClose } })
      vue.onMounted(() => {
        mountCounts.set(props.tabKey, (mountCounts.get(props.tabKey) || 0) + 1)
        instances.set(props.tabKey, handle)
        emit('tab-state', { title: `笔记 ${props.noteId}`, dirty: false })
      })
      vue.onUnmounted(() => instances.delete(props.tabKey))
      return () => vue.h('div', { 'data-note': props.noteId, 'data-active': props.active }, content.value)
    },
  }
  const modules = {
    vue, 'vue-router': { useRoute: () => route, useRouter: () => router }, NoteEdit,
    '@element-plus/icons-vue': { Close, Document, Menu, Plus },
    '../composables/useNoteTabs.js': { ...tabUtils, ...manager, findNoteRouteTab: manager.findRouteTab },
  }
  modules['./NoteTabs.vue'] = compileComponent('NoteTabs', modules)
  const component = compileComponent('NoteWorkspace', modules)
  const app = renderer.createApp(component), root = hostElement('root')
  app.mount(root)
  await flush()
  return { app, root, manager, route, navigation, instances, mountCounts, closeChecks, changeRoute, setAllowClose(value) { allowClose = value } }
}

test('switching notes and visiting the list keep mounted editor content and active flags independent', async () => {
  const h = await workspaceHarness()
  try {
    assert.equal(h.mountCounts.get('note:1'), 1)
    h.instances.get('note:1').content.value = 'unsaved chapter one'
    h.manager.updateNoteTab('note:1', { dirty: true })
    h.changeRoute({ path: '/notes/2' })
    await flush()
    assert.equal(h.instances.get('note:1').props.active, false)
    assert.equal(h.instances.get('note:2').props.active, true)
    h.instances.get('note:2').content.value = 'unsaved chapter two'
    h.changeRoute({ path: '/notes', query: { folderId: '3' } })
    await flush()
    assert.equal(h.instances.get('note:1').props.active, false)
    assert.equal(h.instances.get('note:2').props.active, false)
    const tabOne = locate(h.root, node => node.props?.role === 'tab' && node.props?.title?.startsWith('笔记 1'))
    tabOne.props.onClick()
    await flush()
    assert.equal(h.instances.get('note:1').content.value, 'unsaved chapter one')
    assert.equal(h.instances.get('note:2').content.value, 'unsaved chapter two')
    assert.equal(h.mountCounts.get('note:1'), 1)
    assert.equal(h.mountCounts.get('note:2'), 1)
    assert.equal(h.manager.noteTabs.value[0].dirty, true)
  } finally { h.app.unmount() }
})

test('closing a background dirty tab confirms, cancellation preserves it, and the last active close returns to the list', async () => {
  const h = await workspaceHarness()
  try {
    h.changeRoute({ path: '/notes/2' })
    await flush()
    const closeOne = () => locate(h.root, node => node.props?.['aria-label'] === '关闭 笔记 1').props.onClick()
    h.setAllowClose(false)
    closeOne()
    await flush()
    assert.equal(h.manager.noteTabs.value.length, 2)
    assert.equal(h.route.path, '/notes/2')
    h.setAllowClose(true)
    closeOne()
    await flush()
    assert.equal(h.manager.noteTabs.value.length, 1)
    assert.equal(h.route.path, '/notes/2')
    assert.deepEqual(h.closeChecks, ['note:1', 'note:1'])
    locate(h.root, node => node.props?.['aria-label'] === '关闭 笔记 2').props.onClick()
    await flush()
    assert.equal(h.route.path, '/notes')
    assert.equal(h.manager.noteTabs.value.length, 0)
    assert.equal(h.instances.size, 0)
  } finally { h.app.unmount() }
})

test('left/right tab navigation selects and focuses the adjacent tab without remounting either editor', async () => {
  const h = await workspaceHarness()
  try {
    h.changeRoute({ path: '/notes/2' })
    await flush()
    const tabTwo = locate(h.root, node => node.props?.role === 'tab' && node.props?.title === '笔记 2')
    let prevented = false
    tabTwo.props.onKeydown({ key: 'ArrowLeft', preventDefault() { prevented = true } })
    await flush()
    const tabOne = locate(h.root, node => node.props?.role === 'tab' && node.props?.title === '笔记 1')
    assert.equal(prevented, true)
    assert.equal(h.route.path, '/notes/1')
    assert.equal(tabOne.focused, true)
    assert.equal(h.mountCounts.get('note:1'), 1)
    assert.equal(h.mountCounts.get('note:2'), 1)
  } finally { h.app.unmount() }
})

test('external active-key changes reveal only the current tab without focusing it or selecting a note', async () => {
  const activeKey = vue.ref('note:1')
  const tabs = [1, 2, 3].map(id => ({ key: `note:${id}`, title: `合成笔记 ${id}`, dirty: false }))
  const scrolls = [], focuses = [], selections = []
  const component = compileComponent('NoteTabs', { vue, '@element-plus/icons-vue': { Close, Document, Menu, Plus } })
  const parent = {
    setup: () => () => vue.h(component, {
      tabs, activeKey: activeKey.value,
      onSelect(key) { selections.push(key); activeKey.value = key },
    }),
  }
  const app = renderer.createApp(parent), root = hostElement('root')
  app.mount(root)
  const buttons = new Map(tabs.map(tab => [tab.key, locate(root, node => node.props?.id === `note-tab-${encodeURIComponent(tab.key)}`)]))
  for (const [key, button] of buttons) {
    button.scrollIntoView = options => scrolls.push([key, options])
    button.focus = () => focuses.push(key)
  }
  try {
    await flush()
    assert.deepEqual(scrolls, [['note:1', { block: 'nearest', inline: 'nearest' }]])
    scrolls.length = 0
    activeKey.value = 'note:2'
    // Multiple external route changes before rendering reveal the final key.
    activeKey.value = 'note:3'
    await flush()
    assert.deepEqual(scrolls, [['note:3', { block: 'nearest', inline: 'nearest' }]])
    assert.deepEqual(focuses, [])
    assert.deepEqual(selections, [])

    scrolls.length = 0
    activeKey.value = ''
    await flush()
    assert.deepEqual(scrolls, [])
    assert.equal(locate(root, node => node.props?.['aria-label'] === '返回笔记列表').props['aria-current'], 'page')
    assert.deepEqual(focuses, [])
    assert.deepEqual(selections, [])

    // Manual selection relies on the same watcher rather than scrolling twice.
    buttons.get('note:1').props.onClick()
    await flush()
    assert.deepEqual(selections, ['note:1'])
    assert.deepEqual(scrolls, [['note:1', { block: 'nearest', inline: 'nearest' }]])
    assert.deepEqual(focuses, [])
  } finally { app.unmount() }
})

test('saving a draft changes its note ID while keeping the same mounted editor and content', async () => {
  const h = await workspaceHarness()
  try {
    h.changeRoute({ path: '/notes/new', query: { draft: 'a', folderId: '3' } })
    await flush()
    const draft = h.instances.get('draft:a')
    draft.content.value = 'new draft body'
    h.manager.promoteNoteTab('draft:a', 55)
    h.changeRoute({ path: '/notes/55', query: { folderId: '3' } })
    await flush()
    assert.equal(h.instances.get('draft:a'), draft)
    assert.equal(draft.props.noteId, 55)
    assert.equal(draft.props.active, true)
    assert.equal(draft.content.value, 'new draft body')
    assert.equal(h.mountCounts.get('draft:a'), 1)
    assert.equal(h.manager.noteTabs.value.filter(tab => tab.id === 55).length, 1)
  } finally { h.app.unmount() }
})

test('a close confirmation that finishes after switching tabs cannot navigate away from the newly selected note', async () => {
  const h = await workspaceHarness()
  try {
    let finish
    h.setAllowClose(new Promise(resolve => { finish = resolve }))
    locate(h.root, node => node.props?.['aria-label'] === '关闭 笔记 1').props.onClick()
    await flush()
    h.changeRoute({ path: '/notes/2' })
    await flush()
    finish(true)
    await flush()
    assert.equal(h.route.path, '/notes/2')
    assert.equal(h.manager.noteTabs.value.length, 1)
    assert.equal(h.manager.noteTabs.value[0].id, 2)
    assert.deepEqual(h.navigation, [])
  } finally { h.app.unmount() }
})
