import test from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import { compileScript, compileTemplate, parse } from '@vue/compiler-sfc'
import * as vue from 'vue'
import * as folders from '../utils/folders.js'
import * as explorer from '../utils/folderExplorer.js'
import * as tabs from '../composables/useNoteTabs.js'
import * as importNote from '../utils/importNote.js'
import * as mdTitle from '../utils/mdTitle.js'

const source = readFileSync(new URL('./NoteList.vue', import.meta.url), 'utf8')
const descriptor = parse(source).descriptor
const compiled = compileScript(descriptor, { id: 'note-explorer-behavior' })
const factory = new Function('modules', compiled.content
  .replace(/^import\s+([\s\S]*?)\s+from\s+['"]([^'"]+)['"]\s*;?\r?$/gm,
    (_, names, from) => `const ${names.replace(/\bas\b/g, ':')} = modules[${JSON.stringify(from)}]`)
  .replace('export default', 'return'))

async function flush() { for (let i = 0; i < 10; i++) { await Promise.resolve(); await vue.nextTick() } }

async function harness(initialQuery = {}, options = {}) {
  const previousWindow = globalThis.window
  globalThis.window = { addEventListener() {}, removeEventListener() {} }
  const route = vue.reactive({ path: '/notes', query: initialQuery, hash: options.hash || '' })
  const calls = [], navigation = [], detailCalls = [], removeCalls = [], confirmations = []
  let pendingPage = null
  const folderData = options.folders || [
    { id: 1, name: '示例课程', parentId: null, itemCount: 12 },
    { id: 2, name: '小节', parentId: 1, itemCount: 1 },
    { id: 3, name: '实验记录', parentId: null, itemCount: 0 },
  ]
  const noteData = options.notes || [
    { id: 1, title: '根目录笔记', summary: '合成示例', folderId: null, tags: [] },
    ...Array.from({ length: 12 }, (_, i) => ({ id: i + 10, title: `课程 ${i + 1}`, folderId: 1, tags: [] })),
    { id: 30, title: '子目录笔记', folderId: 2, tags: [] },
  ]
  const router = {
    async replace(location) { navigation.push(['replace', location]); route.query = location.query; await vue.nextTick() },
    async push(location) { navigation.push(['push', location]); await vue.nextTick() },
  }
  const modules = {
    vue, 'vue-router': { useRoute: () => route, useRouter: () => router },
    'element-plus': { ElMessage: { success() {}, error() {} }, ElMessageBox: { async confirm(...args) { confirmations.push(args) } } },
    '@element-plus/icons-vue': { ArrowDown: {}, Folder: {}, MoreFilled: {} },
    '../api': {
      categoryApi: { async tree() { return [] } }, tagApi: { async list() { return [] } },
      folderApi: { async list() { return folderData } },
      noteApi: {
        async page(params) {
          calls.push({ ...params })
          if (pendingPage) await pendingPage
          const matching = noteData.filter(note => params.folderId == null || (note.folderId || 0) === params.folderId)
          return { list: matching.slice((params.page - 1) * params.size, params.page * params.size), total: matching.length }
        },
        async detail(id) { detailCalls.push(id); return noteData.find(note => note.id === id) },
        async remove(id) { removeCalls.push(id) },
      },
    },
    '../components/FolderExplorerToolbar.vue': {}, '../components/FolderMoveDialog.vue': {},
    '../utils/folders': folders, '../utils/folderExplorer': explorer,
    '../composables/useNoteTabs.js': tabs, '../utils/importNote': importNote, '../utils/mdTitle': mdTitle,
  }
  const component = factory(modules)
  component.render = () => null
  const renderer = vue.createRenderer({
    createElement: tag => ({ tag, children: [] }), createText: text => ({ text }), createComment: text => ({ text }),
    insert: (child, parent) => parent.children.push(child), remove() {}, setText() {}, setElementText() {},
    patchProp() {}, parentNode: () => null, nextSibling: () => null,
  })
  const app = renderer.createApp(component)
  app.mount({ children: [] })
  await flush()
  return {
    state: app._instance.setupState, route, calls, navigation, detailCalls, removeCalls, confirmations,
    holdPage() { let resume; pendingPage = new Promise(resolve => { resume = resolve }); return resume },
    dispose() { app.unmount(); globalThis.window = previousWindow },
  }
}

test('default note location combines top-level folders with only root notes and uses distinct row identities', async () => {
  const h = await harness()
  try {
    assert.equal(h.state.selectedFolder, 0)
    assert.equal(h.calls.at(-1).folderId, 0)
    assert.equal(h.state.total, 1)
    assert.equal(h.state.folderTitle, '笔记根目录')
    assert.deepEqual(h.state.explorerRows.map(row => row.__explorerKey), ['folder:1', 'folder:3', 'note:1'])
    assert.equal(h.state.explorerRows[0].childrenCount, 1)
  } finally { h.dispose() }
})

test('entering a folder changes the exact backend scope without adding folders to note pagination or batch IDs', async () => {
  const h = await harness()
  try {
    h.state.query.page = 2
    h.state.selectFolder(1)
    await flush()
    assert.equal(h.state.query.page, 1)
    assert.deepEqual(h.navigation.at(-1), ['replace', { path: '/notes', query: { folderId: '1' }, hash: '' }])
    assert.equal(h.calls.at(-1).folderId, 1)
    assert.equal(h.state.total, 12)
    assert.equal(h.state.list.length, 10)
    assert.equal(h.state.explorerRows.length, 11)
    assert.equal(h.state.explorerRows[0].id, 2)
    h.state.onSelectionChange([h.state.explorerRows[0], h.state.explorerRows[1]])
    h.state.openMove()
    assert.deepEqual(h.state.moveIds, [10])
    h.state.moveVisible = false
    h.state.openMove(h.state.explorerRows[0])
    assert.equal(h.state.moveVisible, false)
  } finally { h.dispose() }
})

test('explicit all-notes view omits folder rows and browser back restores root scope', async () => {
  const h = await harness({ folderId: '1', keep: 'example' })
  try {
    h.state.selectFolder(null)
    await flush()
    assert.deepEqual(h.navigation.at(-1), ['replace', { path: '/notes', query: { view: 'all', keep: 'example' }, hash: '' }])
    assert.equal(Object.hasOwn(h.calls.at(-1), 'folderId'), false)
    assert.equal(h.state.total, 14)
    assert.equal(h.state.explorerRows.some(explorer.isExplorerFolder), false)
    h.route.query = { keep: 'example' }
    await flush()
    assert.equal(h.state.selectedFolder, 0)
    assert.equal(h.calls.at(-1).folderId, 0)
    assert.equal(h.state.explorerRows.filter(explorer.isExplorerFolder).length, 2)
  } finally { h.dispose() }
})

test('folder row double click opens its directory while an interactive note button does not trigger a second route', async () => {
  const h = await harness()
  try {
    const folder = h.state.explorerRows[0]
    h.state.onRowDoubleClick(folder, null, { target: { closest() { return null } } })
    await flush()
    assert.equal(h.state.selectedFolder, 1)
    const before = h.navigation.length
    h.state.onRowDoubleClick(h.state.explorerRows[1], null, { target: { closest() { return {} } } })
    await flush()
    assert.equal(h.navigation.length, before)
    h.state.onRowDoubleClick(h.state.explorerRows[1], null, { target: { closest() { return null } } })
    await flush()
    assert.equal(h.navigation.at(-1)[1].path, '/notes/10')
  } finally { h.dispose() }
})

test('new notes in the current folder still receive independent draft tabs', async () => {
  const h = await harness({ folderId: '2' })
  try {
    h.state.openNew()
    h.state.openNew()
    const locations = h.navigation.filter(([method]) => method === 'push').map(([, location]) => location)
    assert.equal(locations[0].path, '/notes/new')
    assert.equal(locations[0].query.folderId, '2')
    assert.notEqual(locations[0].query.draft, locations[1].query.draft)
  } finally { h.dispose() }
})

test('directory navigation clears batch state before a delayed response and keeps the route hash', async () => {
  const h = await harness({}, { hash: '#example-location' })
  const resume = h.holdPage()
  try {
    let clears = 0
    h.state.noteTable = { clearSelection() { clears++ } }
    h.state.selectedIds = [1]
    h.state.moveVisible = true
    h.state.moveIds = [1]
    h.state.selectFolder(1)
    assert.equal(h.state.loading, true)
    assert.deepEqual(h.state.selectedIds, [])
    assert.deepEqual(h.state.moveIds, [])
    assert.equal(h.state.moveVisible, false)
    assert.ok(clears > 0)
    assert.equal(h.navigation.at(-1)[1].hash, '#example-location')
    h.state.openMove(h.state.list[0])
    assert.equal(h.state.moveVisible, false)
    resume()
    await flush()
    assert.equal(h.state.loading, false)
  } finally { resume(); h.dispose() }
})

test('folder rows cannot enter note deletion or export APIs and child folders disable deletion visibly', async () => {
  const h = await harness()
  try {
    const folder = h.state.explorerRows[0]
    await h.state.onDelete(folder)
    await h.state.exportNote(folder)
    assert.deepEqual(h.confirmations, [])
    assert.deepEqual(h.removeCalls, [])
    assert.deepEqual(h.detailCalls, [])
    assert.match(source, /command="delete" :disabled="!!row.childrenCount"/)
    assert.match(source, /含子文件夹，暂不可删除/)
  } finally { h.dispose() }
})

test('explorer template compiles with themed toolbar and non-selectable folder rows', () => {
  const result = compileTemplate({ source: descriptor.template.content, filename: 'NoteList.vue', id: 'note-explorer-behavior', compilerOptions: { bindingMetadata: compiled.bindings } })
  assert.deepEqual(result.errors, [])
  assert.equal(source.includes('FolderSidebar'), false)
  assert.match(source, /:selectable="row => !isExplorerFolder\(row\)"/)
  assert.match(source, /row-key="__explorerKey"/)
})
