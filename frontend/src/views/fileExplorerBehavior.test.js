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
  let reextractLoader = null
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
        async reextract(id) { writes.push({ action: 'reextract', id }); return reextractLoader ? reextractLoader(id) : { textStatus: 'ok' } },
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
    setReextractLoader(loader) { reextractLoader = loader },
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

test('legacy skipped documents explain how to retry without retaining the obsolete size restriction', async () => {
  const h = await harness()
  try {
    const row = { id: 21, originName: '合成大文件.pdf', textStatus: 'skipped', textError: '文件超过 20MB，未抽取正文' }
    assert.equal(h.state.textLabel(row), '待重抽')
    assert.match(h.state.textHint(row), /此前.*重抽正文/)
    assert.doesNotMatch(h.state.textHint(row), /20MB/)
    h.state.readerStatus = 'skipped'
    h.state.readerError = row.textError
    assert.equal(h.state.statusHint(), h.state.textHint(row))
    await h.state.onReextract(row)
    assert.deepEqual(h.writes, [{ action: 'reextract', id: 21 }])
  } finally { await h.close() }
})

test('re-extraction ignores duplicate clicks for the same file and remains busy until the list refreshes', async () => {
  const h = await harness()
  try {
    const row = { id: 21, textStatus: 'skipped' }
    const extraction = deferred(), refresh = deferred()
    h.setReextractLoader(() => extraction.promise)
    h.setPageLoader(() => refresh.promise)
    const pending = h.state.onReextract(row)
    assert.equal(h.state.isReextracting(row), true)
    assert.equal(h.state.isReextracting({ id: 21, __folder: true }), false)
    await h.state.onReextract({ ...row, id: '21' })
    assert.deepEqual(h.writes, [{ action: 'reextract', id: 21 }])
    extraction.resolve({ textStatus: 'ok', textChars: 123 })
    await flush()
    assert.equal(h.state.isReextracting(row), true)
    refresh.resolve({ list: [{ ...row, textStatus: 'ok', textChars: 123 }], total: 1 })
    await pending
    assert.equal(h.state.isReextracting(row), false)
    assert.equal(h.state.list[0].textStatus, 'ok')
  } finally { await h.close() }
})

test('re-extraction failures release only the failed file so it can be retried', async () => {
  const h = await harness()
  try {
    const first = { id: 21 }, second = { id: 22 }
    const wait = deferred()
    h.setReextractLoader(id => id === 21 ? Promise.reject(new Error('合成抽取失败')) : wait.promise)
    const pending = h.state.onReextract(second)
    await assert.rejects(h.state.onReextract(first), /合成抽取失败/)
    assert.equal(h.state.isReextracting(first), false)
    assert.equal(h.state.isReextracting(second), true)
    h.setReextractLoader(async () => ({ textStatus: 'ok' }))
    await h.state.onReextract(first)
    assert.equal(h.writes.filter(call => call.id === 21).length, 2)
    wait.resolve({ textStatus: 'ok' })
    await pending
    assert.equal(h.state.isReextracting(second), false)
  } finally { await h.close() }
})

test('re-extraction requests wait for large document processing instead of the default 15 second timeout', async () => {
  const apiSource = readFileSync(new URL('../api/index.js', import.meta.url), 'utf8')
  const block = apiSource.match(/export const fileApi = (\{[\s\S]*?\n\})\r?\n/)[1]
  const calls = []
  const api = new Function('request', `return (${block})`)({ post(...args) { calls.push(args) } })
  api.reextract(21)
  assert.deepEqual(calls, [['/files/21/reextract', {}, { timeout: 0 }]])
})
