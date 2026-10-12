import test from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import { compileScript, parse } from '@vue/compiler-sfc'
import * as vue from 'vue'
import MarkdownIt from 'markdown-it'
import { EditorState } from '@codemirror/state'
import { history, undo } from '@codemirror/commands'
import * as tabUtils from './useNoteTabs.js'

const source = readFileSync(new URL('../views/NoteEdit.vue', import.meta.url), 'utf8')
const compiled = compileScript(parse(source).descriptor, { id: 'note-edit-tab-script-test' }).content
const importPattern = /^import\s+([\s\S]*?)\s+from\s+['"]([^'"]+)['"]\s*;?\r?$/gm
const withoutSideEffects = compiled.replace(/^import\s+['"][^'"]+['"]\s*;?\r?$/gm, '')
const helperModules = {}
for (const match of withoutSideEffects.matchAll(importPattern)) {
  const from = match[2]
  if (from.startsWith('../utils/')) helperModules[from] = await import(new URL(`${from.endsWith('.js') ? from : `${from}.js`}`, new URL('../views/NoteEdit.vue', import.meta.url)))
}
const factory = new Function('modules', withoutSideEffects
  .replace(importPattern, (_, names, from) => `const ${names.trim().replace(/\bas\b/g, ':')} = modules[${JSON.stringify(from)}]`)
  .replace('export default', 'return'))

function defer() { let resolve; const promise = new Promise(done => { resolve = done }); return { promise, resolve } }
async function flush() { for (let index = 0; index < 35; index++) { await Promise.resolve(); await vue.nextTick() } }

function installBrowserMocks() {
  const keys = ['window', 'document', 'localStorage', 'requestAnimationFrame', 'cancelAnimationFrame', 'ResizeObserver', 'MutationObserver', 'CustomEvent']
  const previous = new Map(keys.map(key => [key, Object.getOwnPropertyDescriptor(globalThis, key)]))
  const windowMock = Object.assign(new EventTarget(), { innerWidth: 1400, getSelection: () => null, location: { origin: 'http://localhost' } })
  const documentMock = Object.assign(new EventTarget(), {
    activeElement: null, querySelector: () => null,
    createElement: () => ({ innerHTML: '', textContent: '', querySelectorAll: () => [], classList: { add() {}, remove() {} } }),
    body: { classList: { add() {}, remove() {} } },
  })
  let frame = 0
  const observedResizeNodes = [], unobservedResizeNodes = []
  const mocks = {
    window: windowMock, document: documentMock,
    localStorage: { getItem: () => null, setItem() {}, removeItem() {} },
    requestAnimationFrame: callback => { queueMicrotask(() => callback(0)); return ++frame }, cancelAnimationFrame() {},
    ResizeObserver: class { observe(node) { observedResizeNodes.push(node) } unobserve(node) { unobservedResizeNodes.push(node) } disconnect() {} },
    MutationObserver: class { observe() {} disconnect() {} },
    CustomEvent: class extends Event { constructor(type, options = {}) { super(type); this.detail = options.detail } },
  }
  for (const [key, value] of Object.entries(mocks)) Object.defineProperty(globalThis, key, { value, writable: true, configurable: true })
  return { windowMock, observedResizeNodes, unobservedResizeNodes, frameCount: () => frame,
    restore() { for (const [key, descriptor] of previous) { if (descriptor) Object.defineProperty(globalThis, key, descriptor); else delete globalThis[key] } } }
}

const renderer = vue.createRenderer({
  createElement: tag => ({ tag, children: [] }), createText: text => ({ text }), createComment: text => ({ text }),
  insert: (node, parent) => parent.children.push(node), remove() {}, setText() {}, setElementText() {}, patchProp() {}, parentNode: () => null, nextSibling: () => null,
})

async function harness(options = {}) {
  const browser = installBrowserMocks(), manager = tabUtils.createNoteTabsManager()
  const route = vue.reactive({ path: '/notes/1', params: { id: '1' }, query: {}, hash: '' })
  const reads = [], writes = [], navigation = [], confirmations = [], mounts = []
  let creation = null, allowClose = true
  const modules = {
    ...helperModules, vue, 'markdown-it': MarkdownIt,
    'vue-router': { useRoute: () => route, useRouter: () => ({
      replace: location => { navigation.push(location); return Promise.resolve() },
      push: location => { navigation.push(location); return Promise.resolve() },
      resolve: ({ params }) => ({ href: `/notes/${params.id}` }),
    }), onBeforeRouteLeave() {} },
    'element-plus': { ElMessage: { info() {}, warning() {}, error() {}, success() {} }, ElMessageBox: {
      async confirm(text) { confirmations.push(text); if (!allowClose) throw new Error('cancel') },
    } },
    'md-editor-v3': {}, '../composables/useTheme': { isDark: vue.ref(false) }, '../composables/useViewMode': { focusMode: vue.ref(false) },
    '../composables/useNoteTabs.js': { ...tabUtils, ...manager },
    '../api': {
      categoryApi: { tree: options.categoryTree || (async () => []) },
      tagApi: { list: options.tagList || (async () => []) },
      folderApi: { list: options.folderList || (async () => [{ id: 3, name: '测试资料夹' }]) },
      noteApi: {
        async detail(id) { reads.push(id); return options.detail ? options.detail(id) : { id, title: `笔记 ${id}`, content: `body ${id}`, folderId: 3, tags: [] } },
        async update(id, payload) { writes.push({ id, payload }) },
        add(payload) { writes.push({ create: true, payload }); return creation?.promise || Promise.resolve({ id: 55 }) },
      },
    },
  }
  for (const match of withoutSideEffects.matchAll(importPattern)) if (match[2].endsWith('.vue')) modules[match[2]] = {}
  const component = factory(modules)
  component.render = () => null
  async function mount(props) {
    const incoming = vue.reactive(props)
    const app = renderer.createApp({ setup: () => () => vue.h(component, { ...incoming, onTabState: state => manager.updateNoteTab(incoming.tabKey, state) }) })
    app.mount({ children: [] })
    await flush()
    const state = app._instance.subTree.component.setupState
    const exposed = app._instance.subTree.component.exposed
    const instance = { app, state, exposed, props: incoming }
    mounts.push(instance)
    return instance
  }
  return { ...browser, manager, route, reads, writes, navigation, confirmations, mount,
    setCreation(value) { creation = value }, setAllowClose(value) { allowClose = value },
    async cleanup() { for (const entry of mounts) entry.app.unmount(); await flush(); browser.restore() },
  }
}

test('actual NoteEdit scripts keep A/B IDs and body drafts independent, ignore inactive Ctrl+S and do not reload on route changes', async () => {
  const h = await harness()
  try {
    const a = await h.mount({ noteId: 1, noteQuery: {}, tabKey: 'note:1', active: true })
    const b = await h.mount({ noteId: 2, noteQuery: {}, tabKey: 'note:2', active: false })
    a.state.form.content = 'draft A'
    b.state.form.content = 'draft B'
    a.props.active = false
    b.props.active = true
    Object.assign(h.route, { path: '/notes/2', params: { id: '2' } })
    await flush()
    let inactivePrevented = false
    a.state.onGlobalKeydown({ key: 's', ctrlKey: true, preventDefault() { inactivePrevented = true } })
    await flush()
    assert.equal(inactivePrevented, false)
    assert.equal(h.writes.length, 0)
    b.state.onGlobalKeydown({ key: 's', ctrlKey: true, preventDefault() {} })
    await flush()
    assert.equal(h.writes.length, 1)
    assert.equal(h.writes[0].id, 2)
    assert.equal(h.writes[0].payload.content, 'draft B')
    assert.equal(a.state.id, 1)
    assert.equal(b.state.id, 2)
    assert.equal(a.state.form.content, 'draft A')
    assert.deepEqual(h.reads, [1, 2])
  } finally { await h.cleanup() }
})

test('the source editor is mounted only on first use, binds scrolling then, and retains caret/history across mode and tab switches', async () => {
  const h = await harness()
  try {
    const a = await h.mount({ noteId: 1, noteQuery: {}, tabKey: 'note:1', active: true })
    assert.equal(a.state.sourceEditorMounted, false)
    assert.equal(a.state.showEditorPane, false)
    assert.equal(h.frameCount(), 0)
    const listeners = new Map(), source = a.state.form.content
    let focused = 0
    const scroller = {
      addEventListener(type, handler) { listeners.set(type, handler) }, removeEventListener(type) { listeners.delete(type) },
      querySelector: () => null, clientHeight: 200, scrollHeight: 200,
    }
    const view = vue.markRaw({
      scrollDOM: scroller,
      state: EditorState.create({ doc: source, selection: { anchor: 3 }, extensions: [history()] }),
      dispatch(spec) { this.state = spec.state || this.state.update(spec).state; a.state.form.content = this.state.doc.toString() },
      focus() { focused++ },
    })
    const editor = vue.markRaw({ getEditorView: () => view })
    a.state.editorRef = editor
    await flush()
    assert.equal(a.state.sourceEditorMounted, false)
    assert.equal(listeners.has('scroll'), false)
    a.state.readingMode = false
    await flush()
    assert.equal(a.state.sourceEditorMounted, true)
    assert.equal(a.state.showEditorPane, true)
    assert.equal(listeners.has('scroll'), true)
    assert.equal(h.observedResizeNodes.filter(node => node === scroller).length, 1)

    // Execute the actual source toolbar at its caret with real CodeMirror state/history.
    assert.equal(a.state.runSourceTool('inlineCode'), true)
    assert.equal(view.state.doc.toString(), 'bod`代码`y 1')
    assert.equal(focused, 1)
    const editedState = view.state, caret = view.state.selection.main
    a.state.readingMode = true
    a.props.active = false
    await flush()
    assert.equal(a.state.sourceEditorMounted, true)
    assert.equal(a.state.editorRef, editor)
    a.props.active = true
    a.state.readingMode = false
    await flush()
    assert.equal(a.state.editorRef.getEditorView(), view)
    assert.equal(view.state, editedState)
    assert.equal(view.state.selection.main, caret)
    assert.equal(h.observedResizeNodes.filter(node => node === scroller).length, 1)
    assert.equal(undo({ state: view.state, dispatch: transaction => view.dispatch(transaction) }), true)
    assert.equal(view.state.doc.toString(), source)
    assert.equal(a.state.sourceEditorMounted, true)
    a.app.unmount()
    await flush()
    assert.equal(listeners.has('scroll'), false)
  } finally { await h.cleanup() }
})

test('existing note details start immediately and render without waiting for any optional metadata', async () => {
  const category = defer(), tags = defer(), folders = defer(), detail = defer()
  const h = await harness({
    categoryTree: () => category.promise, tagList: () => tags.promise, folderList: () => folders.promise,
    detail: () => detail.promise,
  })
  try {
    const a = await h.mount({ noteId: 1, noteQuery: {}, tabKey: 'note:1', active: true })
    assert.deepEqual(h.reads, [1])
    assert.equal(a.state.loading, true)
    assert.equal(a.state.noteReady, false)
    detail.resolve({ id: 1, title: '合成正文', content: '# 合成章节\n\n正文已加载', folderId: 3, tags: [] })
    await flush()
    assert.equal(a.state.form.content, '# 合成章节\n\n正文已加载')
    assert.equal(a.state.noteReady, true)
    assert.equal(a.state.loading, false)
    a.state.form.content = '# 合成章节\n\n等待元数据时已输入的草稿'
    category.resolve([{ id: 1, name: '合成分类' }])
    tags.resolve([{ id: 2, name: '合成标签' }])
    folders.resolve([{ id: 3, parentId: 0, name: '合成目录' }])
    await flush()
    assert.equal(a.state.categories[0].name, '合成分类')
    assert.equal(a.state.tags[0].name, '合成标签')
    assert.equal(a.state.folders[0].name, '合成目录')
    assert.equal(a.state.form.content, '# 合成章节\n\n等待元数据时已输入的草稿')
    assert.equal(a.state.dirty, true)
  } finally { await h.cleanup() }
})

test('failed optional metadata cannot prevent note reading or discard independently successful options', async () => {
  const h = await harness({
    categoryTree: async () => { throw new Error('合成分类失败') },
    tagList: async () => [{ id: 2, name: '可用标签' }],
    folderList: async () => { throw new Error('合成目录失败') },
  })
  try {
    const a = await h.mount({ noteId: 1, noteQuery: {}, tabKey: 'note:1', active: true })
    assert.deepEqual(h.reads, [1])
    assert.equal(a.state.noteReady, true)
    assert.equal(a.state.loading, false)
    assert.equal(a.state.loadError, '')
    assert.equal(a.state.form.content, 'body 1')
    assert.deepEqual(a.state.tags, [{ id: 2, name: '可用标签' }])
    assert.deepEqual(a.state.categories, [])
    assert.deepEqual(a.state.folders, [])
  } finally { await h.cleanup() }
})

test('new drafts wait only for destination validation, preserving valid folders despite slow or failed other metadata', async () => {
  const category = defer(), folders = defer()
  const h = await harness({
    categoryTree: () => category.promise,
    tagList: async () => { throw new Error('合成标签失败') },
    folderList: () => folders.promise,
  })
  try {
    const a = await h.mount({ noteId: null, noteQuery: { folderId: '3' }, tabKey: 'draft:synthetic', active: true })
    assert.equal(a.state.loading, true)
    assert.equal(a.state.noteReady, false)
    assert.deepEqual(h.reads, [])
    folders.resolve([{ id: 3, parentId: 0, name: '有效合成目录' }])
    await flush()
    assert.equal(a.state.form.folderId, 3)
    assert.equal(a.state.savedFolderId, 3)
    assert.equal(a.state.noteReady, true)
    assert.equal(a.state.loading, false)
    assert.equal(a.state.dirty, false)
    const template = a.state.form.content
    assert.ok(template.length > 0)
    a.state.form.content = '元数据返回前输入的合成草稿'
    category.resolve([{ id: 1, name: '迟到分类' }])
    await flush()
    assert.equal(a.state.form.content, '元数据返回前输入的合成草稿')
    assert.equal(a.state.form.folderId, 3)
    assert.deepEqual(h.reads, [])
  } finally { await h.cleanup() }
})

test('new drafts reject missing destination folders and still initialize when folder metadata fails', async () => {
  for (const folderList of [async () => [], async () => { throw new Error('合成目录失败') }]) {
    const h = await harness({ categoryTree: async () => { throw new Error('合成分类失败') }, folderList })
    try {
      const a = await h.mount({ noteId: null, noteQuery: { folderId: '99' }, tabKey: 'draft:synthetic', active: true })
      assert.equal(a.state.form.folderId, 0)
      assert.equal(a.state.savedFolderId, 0)
      assert.equal(a.state.noteReady, true)
      assert.equal(a.state.loading, false)
      assert.equal(a.state.dirty, false)
      assert.deepEqual(h.reads, [])
    } finally { await h.cleanup() }
  }
})

test('late metadata never mutates disposed editors, and independent note-detail failure remains retryable', async () => {
  const folders = defer()
  const h = await harness({ folderList: () => folders.promise })
  try {
    const a = await h.mount({ noteId: null, noteQuery: { folderId: '3' }, tabKey: 'draft:synthetic', active: true })
    a.app.unmount()
    folders.resolve([{ id: 3, name: '迟到目录' }])
    await flush()
    assert.equal(a.state.noteReady, false)
    assert.deepEqual(a.state.folders, [])
    assert.deepEqual(h.reads, [])
  } finally { await h.cleanup() }
  let fail = true
  const failed = await harness({ detail: async id => {
    if (fail) throw new Error('合成正文加载失败')
    return { id, title: '恢复笔记', content: '重试成功的合成正文', tags: [] }
  } })
  try {
    const a = await failed.mount({ noteId: 1, noteQuery: {}, tabKey: 'note:1', active: true })
    assert.equal(a.state.loadError, '合成正文加载失败')
    assert.equal(a.state.noteReady, false)
    assert.equal(a.state.loading, false)
    fail = false
    await a.state.loadNote()
    assert.equal(a.state.loadError, '')
    assert.equal(a.state.noteReady, true)
    assert.equal(a.state.form.content, '重试成功的合成正文')
  } finally { await failed.cleanup() }
})

test('the flowchart toolbar targets only the active note and releases its captured editor selection', async () => {
  const h = await harness()
  try {
    const a = await h.mount({ noteId: 1, noteQuery: {}, tabKey: 'note:1', active: true })
    const b = await h.mount({ noteId: 2, noteQuery: {}, tabKey: 'note:2', active: false })
    a.state.form.content = 'Synthetic draft A'
    b.state.form.content = 'Synthetic draft B'
    const calls = []
    let released = 0
    a.state.blockPreviewRef = { captureToolbarTarget: () => ({
      runTool(name) { calls.push({ note: 1, name }); return true }, release() { released++ },
    }), flushContent() {} }
    b.state.blockPreviewRef = { captureToolbarTarget: () => ({
      runTool(name) { calls.push({ note: 2, name }); return true }, release() { released++ },
    }), flushContent() {} }
    b.state.onFlowchartClick()
    assert.deepEqual(calls, [])
    a.state.onFlowchartClick()
    assert.deepEqual(calls, [{ note: 1, name: 'flowchart' }])
    assert.equal(released, 1)
    a.props.active = false
    b.props.active = true
    await flush()
    a.state.onFlowchartClick()
    b.state.onFlowchartClick()
    assert.deepEqual(calls, [{ note: 1, name: 'flowchart' }, { note: 2, name: 'flowchart' }])
    assert.equal(released, 2)
    assert.equal(a.state.form.content, 'Synthetic draft A')
    assert.equal(b.state.form.content, 'Synthetic draft B')
  } finally { await h.cleanup() }
})

test('a background draft creation retains edits made during the request and cannot grab the active route', async () => {
  const h = await harness()
  try {
    const tab = h.manager.openNoteRoute({ path: '/notes/new', query: { draft: 'a', folderId: '3' } })
    const a = await h.mount({ noteId: null, noteQuery: tab.query, tabKey: tab.key, active: true })
    a.state.form.title = '新草稿'
    a.state.form.content = 'submitted body'
    const pending = defer()
    h.setCreation(pending)
    const save = a.state.save()
    a.state.form.content = 'typed while saving'
    a.props.active = false
    Object.assign(h.route, { path: '/notes/2', params: { id: '2' } })
    await flush()
    pending.resolve({ id: 55 })
    await save
    a.props.noteId = h.manager.noteTabs.value[0].id
    await flush()
    assert.equal(a.state.id, 55)
    assert.equal(a.state.form.content, 'typed while saving')
    assert.equal(a.state.dirty, true)
    assert.equal(h.manager.noteTabs.value[0].dirty, true)
    assert.deepEqual(h.navigation, [])
    assert.deepEqual(h.reads, [])
  } finally { await h.cleanup() }
})

test('actual dirty-close handling blocks saving tabs, preserves on cancel and requires explicit discard confirmation', async () => {
  const h = await harness()
  try {
    const a = await h.mount({ noteId: 1, noteQuery: {}, tabKey: 'note:1', active: true })
    assert.equal(await a.exposed.prepareTabClose(), true)
    a.state.form.content = 'unsaved body'
    h.setAllowClose(false)
    assert.equal(await a.exposed.prepareTabClose(), false)
    assert.equal(a.state.form.content, 'unsaved body')
    h.setAllowClose(true)
    assert.equal(await a.exposed.prepareTabClose(), true)
    assert.match(h.confirmations[0], /未保存.*丢弃/)
    a.state.saving = true
    assert.equal(await a.exposed.prepareTabClose(), false)
    assert.equal(h.confirmations.length, 2)
  } finally { await h.cleanup() }
})

function internalLinkClick(href, overrides = {}) {
  const anchor = { getAttribute: name => name === 'href' ? href : null }
  const state = { prevented: false, stopped: false }
  const event = {
    button: 0, detail: 1, target: { closest: selector => selector === 'a[href]' ? anchor : null },
    preventDefault() { state.prevented = true }, stopPropagation() { state.stopped = true }, ...overrides,
  }
  return { anchor, event, state }
}

function previewScroller(headings = []) {
  return {
    scrollTop: 0, clientTop: 0, clientHeight: 500, scrollHeight: 1600,
    contains: () => true, querySelectorAll: () => headings, querySelector: () => null,
    getBoundingClientRect: () => ({ top: 100 }), addEventListener() {}, removeEventListener() {},
  }
}

test('a normal internal-link click navigates even when an inserted link label remains selected', async () => {
  const h = await harness()
  try {
    const a = await h.mount({ noteId: 1, noteQuery: {}, tabKey: 'note:1', active: true })
    a.state.pvScrollRef = previewScroller()
    h.windowMock.getSelection = () => ({ isCollapsed: false, rangeCount: 1 })
    a.state.onNoteLinkPointerDown({ clientX: 50, clientY: 80 })
    const click = internalLinkClick('/notes/2#section=Retrieval&occurrence=1')
    a.state.onNoteLinkClick(click.event)
    await flush()
    assert.deepEqual(h.navigation, [{ path: '/notes/2', hash: '#section=Retrieval&occurrence=1' }])
    assert.equal(click.state.prevented, true)
    assert.equal(click.state.stopped, true)
  } finally { await h.cleanup() }
})

test('drag-selecting across a link suppresses navigation, while a fresh click or keyboard activation still works', async () => {
  const h = await harness()
  try {
    const a = await h.mount({ noteId: 1, noteQuery: {}, tabKey: 'note:1', active: true })
    a.state.pvScrollRef = previewScroller()
    a.state.onNoteLinkPointerDown({ clientX: 50, clientY: 80 })
    a.state.onNoteLinkPointerMove({ clientX: 75, clientY: 82 })
    const dragClick = internalLinkClick('/notes/2')
    a.state.onNoteLinkClick(dragClick.event)
    await flush()
    assert.deepEqual(h.navigation, [])
    assert.equal(dragClick.state.prevented, true)
    // Keyboard activation must not inherit the previous pointer drag suppression.
    a.state.onNoteLinkClick(internalLinkClick('/notes/2', { detail: 0 }).event)
    await flush()
    assert.equal(h.navigation.length, 1)
    a.state.onNoteLinkPointerDown({ clientX: 80, clientY: 90 })
    a.state.onNoteLinkClick(internalLinkClick('/notes/3').event)
    await flush()
    assert.equal(h.navigation.length, 2)
    assert.equal(h.navigation[1].path, '/notes/3')
  } finally { await h.cleanup() }
})

test('external URL links open safely despite a retained selection; dragging and executable schemes cannot open a window', async () => {
  const h = await harness()
  try {
    const a = await h.mount({ noteId: 1, noteQuery: {}, tabKey: 'note:1', active: true })
    const opened = []
    a.state.pvScrollRef = previewScroller()
    h.windowMock.open = (...args) => { opened.push(args); return null }
    h.windowMock.getSelection = () => ({ isCollapsed: false, rangeCount: 1 })
    for (const href of ['https://example.org/chapter', 'http://example.org/reference', 'mailto:notes@example.org']) {
      a.state.onNoteLinkPointerDown({ clientX: 30, clientY: 40 })
      const click = internalLinkClick(href)
      a.state.onNoteLinkClick(click.event)
      assert.equal(click.state.prevented, true)
      assert.equal(click.state.stopped, true)
    }
    assert.deepEqual(opened, [
      ['https://example.org/chapter', '_blank', 'noopener,noreferrer'],
      ['http://example.org/reference', '_blank', 'noopener,noreferrer'],
      ['mailto:notes@example.org', '_blank', 'noopener,noreferrer'],
    ])
    a.state.onNoteLinkPointerDown({ clientX: 30, clientY: 40 })
    a.state.onNoteLinkPointerMove({ clientX: 50, clientY: 41 })
    a.state.onNoteLinkClick(internalLinkClick('https://example.org/dragged').event)
    assert.equal(opened.length, 3)
    a.state.onNoteLinkPointerDown({ clientX: 60, clientY: 40 })
    for (const href of ['javascript:alert(1)', 'data:text/html,<script>alert(1)</script>', 'file:///private.txt']) {
      a.state.onNoteLinkClick(internalLinkClick(href).event)
    }
    await flush()
    assert.equal(opened.length, 3)
    assert.deepEqual(h.navigation, [])
  } finally { await h.cleanup() }
})

test('the navigation event reveals a cached narrow-screen source tab and scrolls again to its already visited chapter hash', async () => {
  const h = await harness()
  try {
    const a = await h.mount({ noteId: 2, noteQuery: {}, tabKey: 'note:2', active: false })
    let animations = 0
    const headings = [], scroller = previewScroller(headings)
    headings.push({
      tagName: 'H2', textContent: 'Retrieval', parentElement: scroller,
      getBoundingClientRect: () => ({ top: 500 - scroller.scrollTop }), animate() { animations++ },
    })
    a.state.pvScrollRef = scroller
    Object.assign(h.route, { path: '/notes/2', params: { id: '2' }, hash: '#section=Retrieval&occurrence=1' })
    a.props.active = true
    await flush()
    assert.equal(scroller.scrollTop, 376)
    assert.equal(animations, 1)
    // The cached instance subsequently moves to source-only mode and away from the section.
    a.state.readingMode = false
    a.state.layoutMode = 'tab'
    a.state.editorTab = 'edit'
    scroller.scrollTop = 0
    await flush()
    assert.equal(a.state.showPreviewPane, false)
    h.windowMock.dispatchEvent(new CustomEvent('lh-note-link-navigate', {
      detail: { noteId: 2, hash: '#section=Retrieval&occurrence=1' },
    }))
    await flush()
    assert.equal(a.state.editorTab, 'preview')
    assert.equal(a.state.showPreviewPane, true)
    assert.equal(scroller.scrollTop, 376)
    assert.equal(animations, 2)
    assert.deepEqual(h.reads, [2])
  } finally { await h.cleanup() }
})

test('a narrow source outline jump displays and flushes the latest preview before resolving its heading', async () => {
  const h = await harness()
  try {
    const a = await h.mount({ noteId: 1, noteQuery: {}, tabKey: 'note:1', active: true })
    a.state.readingMode = false
    a.state.layoutMode = 'tab'
    a.state.editorTab = 'edit'
    a.state.form.content = '# 最新合成标题\n\n合成正文'
    const headings = [], calls = [], scroller = previewScroller(headings)
    scroller.scrollTo = options => { calls.push(options); scroller.scrollTop = options.top }
    a.state.pvScrollRef = scroller
    a.state.blockPreviewRef = { flushContent(content) {
      assert.equal(a.state.showPreviewPane, true)
      assert.equal(content, '# 最新合成标题\n\n合成正文')
      headings.splice(0, headings.length, { getBoundingClientRect: () => ({ top: 500 - scroller.scrollTop }) })
    } }
    await flush()
    assert.equal(a.state.showPreviewPane, false)
    await a.state.scrollToHeading(0)
    assert.equal(a.state.editorTab, 'preview')
    assert.equal(scroller.scrollTop, 376)
    assert.equal(calls.length, 1)
    // A late jump must not act on a different note after this tab deactivates.
    a.props.active = false
    await flush()
    await a.state.scrollToHeading(0)
    assert.equal(calls.length, 1)
  } finally { await h.cleanup() }
})
