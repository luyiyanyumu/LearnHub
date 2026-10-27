import test from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import { compileScript, parse } from '@vue/compiler-sfc'
import * as vue from 'vue'
import MarkdownIt from 'markdown-it'
import { EditorState } from '@codemirror/state'
import { history, undo } from '@codemirror/commands'
import * as tabUtils from './useNoteTabs.js'
import { prepareAgentNote, insertAgentNote } from '../utils/agentNoteEdit.js'

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
      aiApi: options.aiApi || {},
      categoryApi: { tree: options.categoryTree || (async () => []) },
      tagApi: { list: options.tagList || (async () => []) },
      folderApi: { list: options.folderList || (async () => [{ id: 3, name: '测试资料夹' }]) },
      noteApi: {
        async detail(id) { reads.push(id); return options.detail ? options.detail(id) : { id, title: `笔记 ${id}`, content: `body ${id}`, folderId: 3, tags: [] } },
        async update(id, payload) { writes.push({ id, payload }); return options.update?.(id, payload) },
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

test('agent preparation saves only the active matching editor and preserves background drafts', async () => {
  const h = await harness()
  try {
    const a = await h.mount({ noteId: 1, tabKey: 'note:1', active: true })
    const b = await h.mount({ noteId: 2, tabKey: 'note:2', active: false })
    a.state.form.content = '需要自动保存的草稿'
    b.state.form.content = '后台未保存草稿'
    const context = await prepareAgentNote({ noteId: 1, editorKey: 'note:1' })
    assert.equal(context.dirty, false)
    assert.equal(context.noteId, 1)
    assert.equal(context.editorKey, 'note:1')
    assert.equal(context.context, '需要自动保存的草稿')
    assert.deepEqual(h.writes.map(write => write.id), [1])
    assert.equal(b.state.form.content, '后台未保存草稿')
    assert.equal(b.state.dirty, true)
    await assert.rejects(prepareAgentNote({ noteId: 2, editorKey: 'note:2' }), /已切换|尚未就绪/)
    assert.equal(h.writes.length, 1)
  } finally { await h.cleanup() }
})

test('agent preparation awaits an in-flight save and then saves typing added during that request', async () => {
  const pending = defer()
  let writes = 0
  const h = await harness({ update: () => ++writes === 1 ? pending.promise : undefined })
  try {
    const a = await h.mount({ noteId: 1, tabKey: 'note:1', active: true })
    a.state.form.content = '首次草稿'
    const saving = a.state.save()
    a.state.form.content = '保存期间继续输入'
    const preparing = prepareAgentNote({ noteId: 1, editorKey: 'note:1' })
    pending.resolve()
    await saving
    const result = await preparing
    assert.deepEqual(h.writes.map(write => write.payload.content), ['首次草稿', '保存期间继续输入'])
    assert.equal(result.dirty, false)
    assert.equal(a.state.form.content, '保存期间继续输入')
  } finally { await h.cleanup() }
})

test('failed automatic saves retain the draft and reject preparation without pretending it is saved', async () => {
  const h = await harness({ update: () => { throw new Error('合成保存失败') } })
  try {
    const a = await h.mount({ noteId: 1, tabKey: 'note:1', active: true })
    a.state.form.content = '必须保留的草稿'
    await assert.rejects(prepareAgentNote({ noteId: 1, editorKey: 'note:1' }), /合成保存失败/)
    assert.equal(a.state.form.content, '必须保留的草稿')
    assert.equal(a.state.dirty, true)
    assert.equal(a.state.saveError, '合成保存失败')
  } finally { await h.cleanup() }
})

test('switching notes while automatic save runs cancels the following edit and never saves the new tab', async () => {
  const pending = defer()
  const h = await harness({ update: () => pending.promise })
  try {
    const a = await h.mount({ noteId: 1, tabKey: 'note:1', active: true })
    const b = await h.mount({ noteId: 2, tabKey: 'note:2', active: false })
    a.state.form.content = 'A 草稿'
    b.state.form.content = 'B 草稿'
    const preparing = prepareAgentNote({ noteId: 1, editorKey: 'note:1' })
    const rejected = assert.rejects(preparing, /已切换/)
    a.props.active = false
    b.props.active = true
    await flush()
    pending.resolve()
    await rejected
    assert.deepEqual(h.writes.map(write => write.id), [1])
    assert.equal(b.state.form.content, 'B 草稿')
  } finally { await h.cleanup() }
})

test('a new draft is created once and preparation returns its promoted ID with the same editor key', async () => {
  const h = await harness()
  try {
    const a = await h.mount({ noteId: null, tabKey: 'draft:demo', active: true })
    a.state.form.title = '合成新笔记'
    a.state.form.content = '新笔记正文'
    const context = await prepareAgentNote({ noteId: null, editorKey: 'draft:demo', allowNew: true })
    assert.equal(context.noteId, 55)
    assert.equal(context.editorKey, 'draft:demo')
    assert.equal(context.isNew, false)
    assert.equal(context.dirty, false)
    assert.equal(h.writes.filter(write => write.create).length, 1)
  } finally { await h.cleanup() }
})

test('multi-section merge generates one batch preview and applies all changes after saving the original draft', async () => {
  const calls = []
  const h = await harness({ aiApi: {
    async status() { return { mergeMaxChars: 1 } },
    async mergeLocate() { return { action: 'merge', targets: [{ action: 'merge', index: 1 }, { action: 'merge', index: 2 }] } },
    async mergeSectionStream(request) { calls.push(request); return { content: request.section + '\n\n新增内容' } },
  } })
  try {
    const a = await h.mount({ noteId: 1, tabKey: 'note:1', active: true })
    const original = '## A\n\n原文甲\n\n## B\n\n原文乙\n\n## C\n\n保持原样\n'
    a.state.form.content = original
    await a.state.onAgentMerge({ detail: { noteId: 1, editorKey: 'note:1', requestId: 'merge-demo', question: '分别补充两节', answer: '合成回答' } })
    assert.equal(calls.length, 2)
    assert.equal(a.state.mergePatch.kind, 'batch-sections')
    assert.equal(a.state.form.content, original)
    assert.equal(h.writes[0].payload.content, original)
    await a.state.aiApply()
    assert.ok(a.state.form.content.includes('原文甲\n\n新增内容'))
    assert.ok(a.state.form.content.includes('原文乙\n\n新增内容'))
    assert.ok(a.state.form.content.includes('## C\n\n保持原样\n'))
    assert.equal(a.state.dirty, true)
    assert.equal(a.state.aiDialog, false)
  } finally { await h.cleanup() }
})

test('the focused source editor receives cursor insertion after automatic save, with a separate undo step', async () => {
  const h = await harness()
  try {
    const a = await h.mount({ noteId: 1, tabKey: 'note:1', active: true })
    const view = vue.markRaw({ state: EditorState.create({ doc: '合成原文', selection: { anchor: 2 }, extensions: [history()] }),
      dispatch(spec) { this.state = this.state.update(spec).state; a.state.form.content = this.state.doc.toString() } })
    a.state.editorRef = vue.markRaw({ getEditorView: () => view })
    a.state.form.content = '合成原文'
    a.state.onSourceFocusIn()
    const result = await insertAgentNote({ noteId: 1, editorKey: 'note:1', answer: '新增' })
    assert.equal(result.ok, true)
    assert.equal(h.writes[0].payload.content, '合成原文')
    assert.equal(view.state.doc.toString(), '合成新增原文')
    assert.equal(undo({ state: view.state, dispatch: transaction => { view.state = transaction.state } }), true)
    assert.equal(view.state.doc.toString(), '合成原文')
  } finally { await h.cleanup() }
})

test('a newly created draft can finish merge generation with its promoted note ID', async () => {
  const h = await harness({ aiApi: {
    async status() { return { mergeMaxChars: 10000 } },
    async mergeNoteStream(request) { assert.equal(request.noteId, 55); return '已经融入的合成正文' },
  } })
  try {
    const a = await h.mount({ noteId: null, tabKey: 'draft:merge', active: true })
    a.state.form.title = '新建融入示例'
    a.state.form.content = '原始合成正文'
    await a.state.onAgentMerge({ detail: { editorKey: 'draft:merge', answer: '补充内容' } })
    assert.equal(a.state.aiResult, '已经融入的合成正文')
    assert.equal(a.state.aiDialog, true)
    assert.equal(h.writes.filter(write => write.create).length, 1)
  } finally { await h.cleanup() }
})

test('a cancelled section-location response cannot start jobs or clear a newer merge run', async () => {
  const oldLocate = defer(), nextLocate = defer()
  let locations = 0, generations = 0
  const h = await harness({ aiApi: {
    async status() { return { mergeMaxChars: 1 } },
    mergeLocate() { return ++locations === 1 ? oldLocate.promise : nextLocate.promise },
    async mergeSectionStream() { generations++; return { content: '新一轮正确正文' } },
  } })
  try {
    const a = await h.mount({ noteId: 1, tabKey: 'note:1', active: true })
    a.state.form.content = '## A\n\n合成原文'
    const old = a.state.onAgentMerge({ detail: { noteId: 1, editorKey: 'note:1', answer: '旧任务' } })
    await flush()
    assert.equal(locations, 1)
    a.state.cancelAiProcess()
    const current = a.state.onAgentMerge({ detail: { noteId: 1, editorKey: 'note:1', answer: '新任务' } })
    await flush()
    assert.equal(locations, 2)
    oldLocate.resolve({ action: 'merge', index: 1 })
    await old
    assert.equal(generations, 0)
    assert.equal(a.state.aiBusy, true)
    nextLocate.resolve({ action: 'merge', index: 1 })
    await current
    assert.equal(generations, 1)
    assert.equal(a.state.mergePatch.after, '新一轮正确正文')
    assert.equal(a.state.aiBusy, false)
  } finally { await h.cleanup() }
})

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

function measureEditorWidth(state, width) {
  state.editorWrapRef = {
    clientWidth: width, addEventListener() {}, removeEventListener() {},
    querySelector: () => null,
    getBoundingClientRect: () => ({ left: 0, width }),
  }
  state.measureLayout()
}

test('the outline docks by actual editor width, including a 743px editor inside a wider window', async () => {
  const h = await harness()
  try {
    const a = await h.mount({ noteId: 1, tabKey: 'note:1', active: true })
    assert.equal(h.windowMock.innerWidth, 1400)
    measureEditorWidth(a.state, 743)
    await flush()
    assert.equal(a.state.layoutWidth, 743)
    assert.equal(a.state.layoutMode, 'tab', 'source tabs and outline docking use independent width requirements')
    assert.equal(a.state.outlineOpen, true)
    assert.equal(a.state.outlineDrawer, false)
    assert.equal(a.state.outlineInlineVisible, true)
    assert.equal(a.state.outlineShown, true)
    assert.equal(a.state.outlineRailWidth, 180)
  } finally { await h.cleanup() }
})

test('resizing preserves the chosen outline state and transfers an open drawer back to the dock', async () => {
  const h = await harness()
  try {
    const a = await h.mount({ noteId: 1, tabKey: 'note:1', active: true })
    measureEditorWidth(a.state, 743)
    measureEditorWidth(a.state, 500)
    await flush()
    assert.equal(a.state.outlineOpen, true, 'narrowing does not overwrite the dock preference')
    assert.equal(a.state.outlineShown, false)
    a.state.toggleOutline()
    await flush()
    assert.equal(a.state.outlineDrawer, true)
    assert.equal(a.state.outlineShown, true)
    measureEditorWidth(a.state, 743)
    await flush()
    assert.equal(a.state.outlineInlineVisible, true)
    assert.equal(a.state.outlineDrawer, false, 'the dock and drawer cannot remain open together')
    a.state.toggleOutline()
    assert.equal(a.state.outlineOpen, false)
    measureEditorWidth(a.state, 500)
    measureEditorWidth(a.state, 1400)
    await flush()
    assert.equal(a.state.outlineOpen, false, 'widening does not undo an explicit close')
    assert.equal(a.state.outlineShown, false)
    a.state.toggleOutline()
    a.state.toggleFocus()
    assert.equal(a.state.outlineOpen, true, 'focus mode only hides the chosen outline temporarily')
    assert.equal(a.state.outlineShown, false)
    a.state.toggleFocus()
    assert.equal(a.state.outlineShown, true)
  } finally { await h.cleanup() }
})

test('opening the outline from focus mode measures restored navigation before choosing a drawer', async () => {
  const h = await harness()
  let stopResize
  try {
    const a = await h.mount({ noteId: 1, tabKey: 'note:1', active: true })
    const draft = '# 合成焦点标题\n\n焦点切换不丢失当前草稿'
    a.state.form.content = draft
    measureEditorWidth(a.state, 743)
    a.state.toggleFocus()
    assert.equal(a.state.focusMode, true)
    assert.equal(a.state.canDockOutline, true)
    assert.equal(a.state.outlineShown, false)
    // Restoring the navigation consumes width before the next layout measurement.
    stopResize = vue.watch(() => a.state.focusMode, focused => {
      if (!focused) a.state.editorWrapRef.clientWidth = 680
    }, { flush: 'sync' })
    await a.state.toggleOutline()
    await flush()
    assert.equal(a.state.layoutWidth, 680)
    assert.equal(a.state.focusMode, false)
    assert.equal(a.state.outlineDrawer, true)
    assert.equal(a.state.outlineShown, true)
    assert.equal(a.state.outlineInlineVisible, false)
    assert.equal(a.state.outlineOpen, true)
    assert.equal(a.state.form.content, draft)
    assert.equal(a.state.dirty, true)
    a.props.active = false
    await flush()
    await a.state.toggleOutline()
    assert.equal(a.state.outlineDrawer, false, 'an inactive editor cannot reopen an outline overlay')
    assert.equal(a.state.form.content, draft)
    assert.equal(h.writes.length, 0)
  } finally { stopResize?.(); await h.cleanup() }
})

test('source widths and divider drags reserve the real outline rail and a usable preview', async () => {
  const h = await harness()
  try {
    const a = await h.mount({ noteId: 1, tabKey: 'note:1', active: true })
    a.state.readingMode = false
    for (const [width, rail] of [[860, 189], [1000, 220], [1280, 232]]) {
      measureEditorWidth(a.state, width)
      assert.equal(a.state.outlineInlineVisible, true)
      assert.equal(a.state.outlineRailWidth, rail)
      const assertUsablePanes = () => {
        const sourceWidth = Number.parseFloat(a.state.editorStyle.flex.split(' ')[2])
        assert.ok(sourceWidth >= 300, `source remains at least 300px at editor width ${width}`)
        assert.ok(width - rail - 9 - sourceWidth >= 320 - 0.001, `preview remains at least 320px at editor width ${width}`)
      }
      for (const preference of [24, 90]) { a.state.editorPct = preference; assertUsablePanes() }
      a.state.startDrag({ preventDefault() {} })
      for (const clientX of [0, width]) {
        h.windowMock.dispatchEvent(Object.assign(new Event('mousemove'), { clientX }))
        assertUsablePanes()
      }
      h.windowMock.dispatchEvent(new Event('mouseup'))
    }
  } finally { await h.cleanup() }
})

test('reading scroll measures only visible outline headings and catches up immediately when reopened', async () => {
  const h = await harness()
  try {
    const a = await h.mount({ noteId: 1, noteQuery: {}, tabKey: 'note:1', active: true })
    const queries = [], scroller = previewScroller()
    let measured = 0
    scroller.clientWidth = 800
    const headings = [0, 600, 1200].map(top => ({ getBoundingClientRect() {
      measured++
      return { top: 100 + top - scroller.scrollTop, height: 28 }
    } }))
    scroller.querySelectorAll = selector => { queries.push(selector); return selector.includes('[data-line]') ? [] : headings }
    a.state.pvScrollRef = scroller
    a.state.outlineOpen = false
    await flush()
    const before = h.frameCount()
    for (let frame = 0; frame < 60; frame++) { scroller.scrollTop = frame * 10; a.state.onPreviewScroll(); await flush() }
    a.state.onPreviewLayoutChange()
    await flush()
    assert.equal(h.frameCount(), before, 'hidden outline schedules no reading-scroll frame')
    assert.deepEqual(queries, [])
    assert.equal(measured, 0)

    a.state.outlineOpen = true
    await flush()
    assert.equal(a.state.activeIdx, 1)
    assert.equal(queries.length, 1)
    assert.ok(queries.every(selector => !selector.includes('[data-line]')), 'reading never builds source-sync anchors')
    assert.equal(measured, 3)
    scroller.scrollTop = 1210
    a.state.onPreviewScroll()
    await flush()
    assert.equal(a.state.activeIdx, 2)
    assert.equal(measured, 3, 'scrolling reuses content coordinates')

    a.state.onPreviewLayoutChange()
    a.state.outlineOpen = false
    await flush()
    assert.equal(measured, 3, 'a queued frame cannot measure an outline hidden before it runs')
    measureEditorWidth(a.state, 500)
    await flush()
    a.state.openOutlineDrawer()
    await flush()
    assert.equal(a.state.activeIdx, 2)
    assert.equal(measured, 6, 'opening the narrow-screen drawer rebuilds invalidated headings')
    measureEditorWidth(a.state, 1400)
    await flush()
    scroller.scrollTop = 610
    a.state.onPreviewScroll()
    await flush()
    assert.equal(a.state.outlineOpen, true)
    assert.equal(a.state.outlineDrawer, false)
    assert.equal(a.state.activeIdx, 1, 'an open drawer transferred to the dock still tracks scrolling')
    a.props.active = false
    await flush()
    a.state.onPreviewScroll()
    a.state.onPreviewLayoutChange()
    await flush()
    assert.equal(measured, 6, 'background drafts do no scroll geometry work')
  } finally { await h.cleanup() }
})

test('hiding the outline keeps preview-to-source scrolling synchronized in two-pane editing', async () => {
  const h = await harness()
  try {
    const a = await h.mount({ noteId: 1, noteQuery: {}, tabKey: 'note:1', active: true })
    const pv = previewScroller(), ed = previewScroller()
    pv.clientWidth = ed.clientWidth = 600
    pv.scrollHeight = 2500
    ed.scrollHeight = 2000
    const view = vue.markRaw({
      scrollDOM: ed, contentDOM: { querySelectorAll: () => [] }, documentTop: 100,
      state: EditorState.create({ doc: a.state.form.content }),
      lineBlockAt: () => ({ top: 0 }),
    })
    a.state.editorRef = vue.markRaw({ getEditorView: () => view })
    a.state.pvScrollRef = pv
    a.state.readingMode = false
    a.state.outlineOpen = false
    await flush()
    pv.scrollTop = 500
    a.state.onScrollIntent('preview')
    a.state.onPreviewScroll()
    await flush()
    assert.equal(ed.scrollTop, 375)
    assert.equal(a.state.outlineShown, false)
    assert.equal(a.state.activeIdx, -1)
  } finally { await h.cleanup() }
})

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
    measureEditorWidth(a.state, 500)
    a.state.editorTab = 'edit'
    a.state.form.content = '# 最新合成标题\n\n合成正文'
    const headings = [], calls = [], scroller = previewScroller(headings)
    scroller.scrollTo = options => { calls.push(options); scroller.scrollTop = options.top }
    a.state.pvScrollRef = scroller
    const previewEditor = vue.markRaw({ flushContent(content) {
      assert.equal(a.state.showPreviewPane, true)
      assert.equal(content, '# 最新合成标题\n\n合成正文')
      headings.splice(0, headings.length, { getBoundingClientRect: () => ({ top: 500 - scroller.scrollTop }) })
    } })
    a.state.blockPreviewRef = previewEditor
    await flush()
    assert.equal(a.state.showPreviewPane, false)
    a.state.outlineDrawer = true
    a.state.jumpFromDrawer(0)
    await flush()
    assert.equal(a.state.editorTab, 'preview')
    assert.equal(scroller.scrollTop, 376)
    assert.equal(calls.length, 1)
    assert.equal(a.state.outlineDrawer, false)
    assert.equal(a.state.form.content, '# 最新合成标题\n\n合成正文')
    assert.equal(a.state.dirty, true)
    assert.equal(a.state.blockPreviewRef, previewEditor, 'an outline jump retains the current editor instance')
    // A late jump must not act on a different note after this tab deactivates.
    a.props.active = false
    await flush()
    a.state.outlineDrawer = true
    a.state.jumpFromDrawer(0)
    await flush()
    assert.equal(calls.length, 1)
    assert.equal(a.state.form.content, '# 最新合成标题\n\n合成正文')
    assert.equal(a.state.dirty, true)
  } finally { await h.cleanup() }
})
