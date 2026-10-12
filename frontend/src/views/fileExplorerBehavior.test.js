import test from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import { compileScript, parse } from '@vue/compiler-sfc'
import * as vue from 'vue'
import * as explorer from '../utils/folderExplorer.js'
import * as folders from '../utils/folders.js'
import * as kinds from '../utils/documentKind.js'
import * as routeReader from '../utils/sourceRouteReader.js'

const source = readFileSync(new URL('./FileLibrary.vue', import.meta.url), 'utf8')
const compiled = compileScript(parse(source).descriptor, { id: 'file-explorer-behavior' }).content
const imports = /^import\s+([\s\S]*?)\s+from\s+['"]([^'"]+)['"]\s*;?\r?$/gm
const factory = new Function('modules', compiled
  .replace(imports, (_, names, from) => `const ${names.trim().replace(/\bas\b/g, ':')} = modules[${JSON.stringify(from)}]`)
  .replace('export default', 'return'))
const renderer = vue.createRenderer({
  createElement: tag => ({ tag, children: [] }), createText: text => ({ text }), createComment: text => ({ text }),
  insert: (node, parent) => parent.children.push(node), remove() {}, setText() {}, setElementText() {}, patchProp() {}, parentNode: () => null, nextSibling: () => null,
})
async function flush() { for (let index = 0; index < 25; index++) { await Promise.resolve(); await vue.nextTick() } }
function deferred() { let resolve; const promise = new Promise(done => { resolve = done }); return { promise, resolve } }

async function harness(initialQuery = {}) {
  const previousWindow = Object.getOwnPropertyDescriptor(globalThis, 'window')
  Object.defineProperty(globalThis, 'window', { configurable: true, value: Object.assign(new EventTarget(), { location: { search: '' } }) })
  const route = vue.reactive({ path: '/files', query: { ...initialQuery }, hash: '' })
  const pageCalls = [], writes = [], navigation = []
  let directory = [
    { id: 1, name: '研究', parentId: null, itemCount: 4 },
    { id: 7, name: '论文', parentId: 1, itemCount: 2 },
    { id: 8, name: '练习', parentId: null, itemCount: 1 },
  ]
  let result = { list: [{ id: 1, originName: '示例.md', ext: 'md' }], total: 41 }
  let pageLoader = null
  let uploadWait = null
  const modules = {
    vue,
    'vue-router': { useRoute: () => route, useRouter: () => ({ async replace(target) { navigation.push(target); route.query = target.query; route.hash = target.hash } }) },
    'element-plus': { ElMessage: { success() {}, error() {} }, ElMessageBox: { confirm: async () => {}, prompt: async () => ({ value: '合成示例' }) } },
    '../utils/folderExplorer': explorer, '../utils/folders': folders, '../utils/documentKind': kinds, '../utils/sourceRouteReader': routeReader,
    '../api': {
      saveBlob() {}, categoryApi: { tree: async () => [] }, folderApi: { list: async () => directory },
      fileApi: {
        async page(params) { pageCalls.push({ ...params }); return pageLoader ? pageLoader(params) : result },
        async upload(file, categoryId, folderId) { writes.push({ action: 'upload', file, categoryId, folderId }); if (uploadWait) { const wait = uploadWait; uploadWait = null; await wait.promise } },
        async text(id) { writes.push({ action: 'text', id }); return { text: '合成正文', textStatus: 'ok' } },
        async translateCaps() { return {} },
        async remove(id) { writes.push({ action: 'remove', id }) },
        async rename(id) { writes.push({ action: 'rename', id }) },
        async updateSummary(id) { writes.push({ action: 'summary', id }) },
        async updateCategory(id) { writes.push({ action: 'category', id }) },
        async reextract(id) { writes.push({ action: 'reextract', id }); return { textStatus: 'ok' } },
        async download(id) { writes.push({ action: 'download', id }); return { data: null } },
        async detail(id) { return { id, ext: 'md', originName: '路由示例.md' } },
      },
    },
  }
  for (const match of compiled.matchAll(imports)) if (match[2].endsWith('.vue')) modules[match[2]] = {}
  const component = factory(modules)
  component.render = () => null
  const app = renderer.createApp(component)
  app.mount({ children: [] })
  await flush()
  const state = app._instance.setupState
  return {
    state, route, pageCalls, writes, navigation,
    setFolders(next) { directory = next }, setResult(next) { result = next },
    setPageLoader(loader) { pageLoader = loader },
    holdUpload() { uploadWait = deferred(); return uploadWait },
    async close() { app.unmount(); await flush(); if (previousWindow) Object.defineProperty(globalThis, 'window', previousWindow); else delete globalThis.window },
  }
}

test('file explorer defaults to root, keeps folders above the file page and separates colliding IDs', async () => {
  const h = await harness()
  try {
    assert.equal(h.state.selectedFolder, 0)
    assert.equal(h.pageCalls.at(-1).folderId, 0)
    assert.deepEqual(h.state.explorerRows.map(row => row.__explorerKey), ['folder:1', 'folder:8', 'file:1'])
    assert.equal(h.state.pager.total, 41)
    assert.equal(h.state.visibleFolderCount, 2)
    assert.equal(h.state.selectableFile(h.state.explorerRows[0]), false)
    assert.equal(h.state.selectableFile(h.state.explorerRows[2]), true)
    h.state.onPageChange(2)
    await flush()
    assert.equal(h.pageCalls.at(-1).page, 2)
    assert.equal(h.state.pager.total, 41)
    assert.equal(h.state.explorerRows.filter(explorer.isExplorerFolder).length, 2)
  } finally { await h.close() }
})

test('folder navigation preserves unrelated route options, supports aggregate view and recovers deleted folders', async () => {
  const h = await harness({ folderId: '1', read: '19', data: 'demo' })
  try {
    h.state.openExplorerRow(h.state.explorerRows[0])
    await flush()
    assert.equal(h.state.selectedFolder, 7)
    assert.deepEqual(h.route.query, { folderId: '7', read: '19', data: 'demo' })
    assert.equal(h.pageCalls.at(-1).folderId, 7)
    h.route.query = { ...h.route.query, view: 'all' }
    await flush()
    assert.equal(h.state.selectedFolder, null)
    assert.equal(h.state.explorerRows.some(explorer.isExplorerFolder), false)
    assert.equal('folderId' in h.pageCalls.at(-1), false)
    h.state.selectedFolder = 7
    await flush()
    h.setFolders([{ id: 1, name: '研究', parentId: null }])
    await h.state.refreshCollection()
    await flush()
    assert.equal(h.state.selectedFolder, 0)
    assert.deepEqual(h.route.query, { read: '19', data: 'demo' })
    assert.equal(h.pageCalls.at(-1).folderId, 0)
  } finally { await h.close() }
})

test('folder rows cannot enter any file mutation, reader or bulk move', async () => {
  const h = await harness()
  try {
    const folder = h.state.explorerRows[0], file = h.state.explorerRows.at(-1)
    h.state.onSelectionChange([folder, file])
    assert.deepEqual(h.state.selectedRows.map(row => row.__explorerKey), ['file:1'])
    h.state.openMove([folder, file])
    assert.deepEqual(h.state.moveIds, [1])
    await Promise.all([
      h.state.openReader(folder), h.state.onDownload(folder), h.state.onDelete(folder),
      h.state.onEditSummary(folder), h.state.onRename(folder), h.state.onReextract(folder), h.state.onChangeCategory(folder, 12),
    ])
    assert.deepEqual(h.writes, [])
    assert.equal(h.state.reader, null)
    h.state.openExplorerRow(file, null, { target: { closest: () => ({ classList: { contains: () => false } }) } })
    assert.equal(h.state.reader, null)
    await h.state.openReader(file)
    assert.equal(h.state.reader.id, 1)
    assert.deepEqual(h.writes, [{ action: 'text', id: 1 }])
  } finally { await h.close() }
})

test('rapid navigation ignores old responses and selection events while a new folder loads', async () => {
  const h = await harness()
  try {
    const oldRow = h.state.explorerRows.at(-1)
    h.state.onSelectionChange([oldRow])
    assert.equal(h.state.selectedRows.length, 1)
    const first = deferred(), latest = deferred()
    h.setPageLoader(params => params.folderId === 7 ? first.promise : latest.promise)
    h.state.selectedFolder = 7
    await flush()
    h.state.onSelectionChange([oldRow])
    assert.equal(h.state.selectedRows.length, 0)
    h.state.selectedFolder = 8
    await flush()
    latest.resolve({ list: [{ id: 82, originName: '最新示例.md' }], total: 5 })
    await flush()
    first.resolve({ list: [{ id: 71, originName: '旧示例.md' }], total: 33 })
    await flush()
    assert.equal(h.state.selectedFolder, 8)
    assert.deepEqual(h.state.list.map(row => row.id), [82])
    assert.equal(h.state.pager.total, 5)
    assert.equal(h.state.loading, false)
    h.state.onSelectionChange([oldRow])
    assert.equal(h.state.selectedRows.length, 0)
  } finally { await h.close() }
})

test('an upload batch retains its original folder while navigation changes the current page', async () => {
  const h = await harness({ folderId: '7' })
  try {
    const wait = h.holdUpload()
    const target = { files: [{ name: '合成甲.md' }, { name: '合成乙.md' }], value: 'selected' }
    const uploading = h.state.onFileChosen({ target })
    await flush()
    h.state.selectedFolder = 8
    await flush()
    h.state.pager.page = 2
    wait.resolve()
    await uploading
    await flush()
    assert.deepEqual(h.writes.map(call => call.folderId), [7, 7])
    assert.equal(h.state.selectedFolder, 8)
    assert.equal(h.state.pager.page, 2)
    assert.equal(h.pageCalls.at(-1).folderId, 8)
    assert.equal(target.value, '')
    assert.equal(h.state.uploading, false)
  } finally { await h.close() }
})
