import test from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import { compileScript, parse } from '@vue/compiler-sfc'
import * as vue from 'vue'
import * as explorer from '../utils/folderExplorer.js'
import * as folders from '../utils/folders.js'
import * as kinds from '../utils/documentKind.js'
import * as routeReader from '../utils/sourceRouteReader.js'
import * as extractionView from '../utils/documentExtraction.js'
import * as sourceLocation from '../utils/sourceLocation.js'
import * as extractedMarkdown from '../utils/extractedMarkdown.js'
import * as extractedMath from '../utils/extractedMath.js'
import * as extractionTasks from '../utils/extractionTasks.js'

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

async function harness(initialQuery = {}, options = {}) {
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
  let extractionStartLoader = null
  let extractionLoader = null
  let markdownLoader = null
  let layoutLoader = null
  let mediaLoader = options.mediaLoader || null
  let migrationLoader = options.migrationLoader || null
  const extractionCalls = []
  const markdownCalls = []
  const markdownVersionCalls = [], prompts = [], layoutCalls = []
  const downloadCalls = []
  const mediaJobCalls = [], migrationJobCalls = [], structureJobCalls = [], extractionJobCalls = []
  const messages = []
  let uploadWait = null
  const modules = {
    vue,
    'vue-router': { useRoute: () => route, useRouter: () => ({ async replace(target) { navigation.push(target); route.query = target.query; route.hash = target.hash } }) },
    'element-plus': { ElMessage: { success() {}, error(message) { messages.push(message) } }, ElMessageBox: { confirm: async (...args) => { prompts.push(args); return options.confirm?.(...args) }, prompt: async () => ({ value: '合成示例' }) } },
    '../utils/folderExplorer': explorer, '../utils/folders': folders, '../utils/documentKind': kinds, '../utils/sourceRouteReader': routeReader,
    '../utils/documentExtraction.js': extractionView, '../utils/sourceLocation.js': sourceLocation,
    '../utils/extractedMarkdown.js': extractedMarkdown, '../utils/extractionTasks.js': extractionTasks,
    '../utils/extractedMath.js': extractedMath,
    '../api': {
      saveBlob(blob, name) { downloadCalls.push({ blob, name }) }, categoryApi: { tree: async () => [] }, folderApi: { list: async () => directory },
      fileApi: {
        rawUrl: id => `/api/files/${id}/raw`,
        extractionPageImageUrl: (id, page, revision) => `/api/files/${id}/extraction-page-image?page=${page}&sourceRevision=${revision}`,
        async page(params) { pageCalls.push({ ...params }); return pageLoader ? pageLoader(params) : result },
        async upload(file, categoryId, folderId) { writes.push({ action: 'upload', file, categoryId, folderId }); if (uploadWait) { const wait = uploadWait; uploadWait = null; await wait.promise } },
        async text(id) { writes.push({ action: 'text', id }); return { text: '合成正文', textStatus: 'ok' } },
        async translateCaps() { return {} },
        async extraction(id) { extractionCalls.push(id); return extractionLoader ? extractionLoader(id) : { status: 'not_processed', pages: [], report: null } },
        async extractionCaps() { return { configured: false, available: false, message: '合成环境未启用 OCR', structure: options.structureCapability } },
        async layout(id) { layoutCalls.push(id); return layoutLoader ? layoutLoader(id) : { status: 'ok', pages: [] } },
        async markdown(id, version) { markdownCalls.push(id); markdownVersionCalls.push({ id, version }); return markdownLoader ? markdownLoader(id, version) : { status: 'ok', format: 'markdown', markdown: '# 合成保存正文', indexedMarkdown: '# 合成保存正文', sourceSha256: 'a'.repeat(64), assets: [] } },
        async downloadMarkdown(id, bundle, selection) { writes.push({ action: 'downloadMarkdown', id, bundle, selection }); return options.downloadMarkdown ? options.downloadMarkdown(id, bundle, selection) : { data: new Blob(['synthetic markdown']) } },
        async publishScan(id, data) { writes.push({ action: 'publishScan', id, data }); return options.publishScan?.(id, data) },
        async processMedia(id, force) { writes.push({ action: 'processMedia', id, force }); return { id: 'media-job-1', status: 'queued' } },
        async mediaJob(id) { mediaJobCalls.push(id); return mediaLoader ? mediaLoader(id) : { id, status: 'partial', done: 1, total: 1, failed: 0 } },
        async processStructure(id, { resume }) { writes.push({ action: 'processStructure', id, resume }); return { id: 'structure-job-1', status: 'queued' } },
        async structureJob(id) { structureJobCalls.push(id); return options.structureLoader ? options.structureLoader(id) : { id, status: 'completed' } },
        async confirmStructureFormula(id, data) { writes.push({ action: 'confirmStructureFormula', id, data }); return options.confirmStructureFormula?.(id, data) },
        async migrateMarkdown() { writes.push({ action: 'migrateMarkdown' }); return { id: 'migration-job-1', status: 'queued' } },
        async markdownMigration(id) { migrationJobCalls.push(id); return migrationLoader ? migrationLoader(id) : { id, status: 'completed', done: 1, total: 1, failed: 0 } },
        async remove(id) { writes.push({ action: 'remove', id }) },
        async rename(id) { writes.push({ action: 'rename', id }) },
        async updateSummary(id) { writes.push({ action: 'summary', id }) },
        async updateCategory(id) { writes.push({ action: 'category', id }) },
        async startExtraction(id, { resume }) { writes.push({ action: 'startExtraction', id, resume }); return extractionStartLoader ? extractionStartLoader(id) : { id: 'extraction-job-1', status: 'queued' } },
        async extractionJob(id) { extractionJobCalls.push(id); return options.extractionJobLoader ? options.extractionJobLoader(id) : { id, status: 'completed' } },
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
    state, route, pageCalls, writes, navigation, extractionCalls, markdownCalls, markdownVersionCalls, prompts, layoutCalls, downloadCalls, mediaJobCalls, migrationJobCalls, structureJobCalls, extractionJobCalls, messages,
    setFolders(next) { directory = next }, setResult(next) { result = next },
    setPageLoader(loader) { pageLoader = loader },
    setExtractionStartLoader(loader) { extractionStartLoader = loader },
    setExtractionLoader(loader) { extractionLoader = loader },
    setMarkdownLoader(loader) { markdownLoader = loader },
    setLayoutLoader(loader) { layoutLoader = loader },
    setMediaLoader(loader) { mediaLoader = loader },
    setMigrationLoader(loader) { migrationLoader = loader },
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

test('正文徽标区分部分抽取、完整抽取和覆盖范围未知', async () => {
  const h = await harness()
  try {
    const row = { id: 21, ext: 'pdf', textStatus: 'ok', textChars: 5967 }
    const partial = {
      ...row,
      extractionCoverage: {
        totalPages: 431, processedPages: 431, completedPages: 9, partial: true, truncated: true,
        limitReasons: [{ code: 'batch_time', amount: 120 }],
      },
      textError: '已保留上次正文和定位。',
    }
    assert.equal(h.state.textLabel(partial), '部分抽取 9/431 页')
    assert.equal(h.state.textTagType(partial), 'warning')
    assert.equal(h.state.textHint(partial), '已收录 5,967 字。可用 9 / 431 页。本批达到 120 秒时限。已保留上次正文和定位。可继续抽取剩余页。')
    assert.equal(h.state.canContinueExtraction(partial), true)

    const allPages = { totalPages: 9, processedPages: 9, completedPages: 9 }
    for (const flag of ['partial', 'truncated']) {
      const partialContent = { ...row, extractionCoverage: { ...allPages, [flag]: true } }
      assert.equal(h.state.textLabel(partialContent), '部分抽取')
      assert.equal(h.state.textTagType(partialContent), 'warning')
    }
    const complete = { ...row, extractionCoverage: { ...allPages, partial: false, truncated: false } }
    assert.equal(h.state.textLabel(complete), '已抽 5,967 字')
    assert.equal(h.state.textTagType(complete), 'success')
    assert.equal(h.state.textLabel(row), '已收录 5,967 字')
    assert.equal(h.state.textTagType(row), 'info')
    assert.match(h.state.textHint(row), /覆盖范围待核对/)
    const unknown = { ...row, extractionCoverage: { ...allPages, completedPages: null, truncated: true } }
    assert.equal(h.state.textLabel(unknown), '部分抽取')
    assert.match(h.state.textHint(unknown), /部分内容待核对。请查看抽取报告/)
    assert.doesNotMatch(h.state.textHint(unknown), /可用|上限/)
    assert.equal(h.state.canContinueExtraction(unknown), false)
  } finally { await h.close() }
})

test('extraction guidance distinguishes recoverable PDF size, persistent limits and media', async () => {
  const h = await harness()
  try {
    const row = { id: 21, ext: 'pdf', textStatus: 'ok', textChars: 123, extractionCoverage: {
      totalPages: 262, processedPages: 262, completedPages: 34, partial: true, truncated: true,
      limitReasons: [{ code: 'page_pixels', amount: 12000000 }],
    } }
    assert.equal(h.state.textLabel(row), '部分抽取 34/262 页')
    assert.match(h.state.textHint(row), /部分页面超过单页 1,200 万像素上限。可继续抽取，系统会按页尺寸调整渲染/)
    assert.equal(h.state.canContinueExtraction(row), true)
    const image = { ...row, ext: 'png', extractionCoverage: {
      ...row.extractionCoverage, totalPages: 1, processedPages: 1, completedPages: 0,
    } }
    assert.equal(h.state.canContinueExtraction(image), false)
    assert.match(h.state.textHint(image), /可缩小或分割原图后再抽取/)
    const tiff = { ...row, ext: 'tiff' }
    assert.equal(h.state.canContinueExtraction(tiff), true)
    assert.match(h.state.textHint(tiff), /可继续抽取其他页/)

    const capacity = { ...row, extractionCoverage: { ...row.extractionCoverage, limitReasons: [
      { code: 'batch_time', amount: 120 }, { code: 'document_capacity' },
    ] } }
    assert.equal(h.state.canContinueExtraction(capacity), false)
    assert.match(h.state.textHint(capacity), /累计容量上限。可按章节拆分资料后再抽取/)
    await h.state.onReextract(capacity)
    assert.deepEqual(h.writes, [])

    const media = { ...row, extractionCoverage: { ...row.extractionCoverage, completedPages: 262,
      limitReasons: [{ code: 'media_limit' }],
    } }
    assert.equal(h.state.canContinueExtraction(media), false)
    assert.match(h.state.textHint(media), /本批公式与图片处理达到预算。可继续处理公式与图片/)
    await h.state.openReader(media)
    h.setExtractionLoader(() => ({ status: 'ok', completedPages: 34,
      report: { totalPages: 262, processedPages: 262, partial: true },
      limitReasons: [{ code: 'page_pixels', amount: 12000000 }],
    }))
    await h.state.ensureExtraction(true)
    assert.equal(h.state.readerCanContinue, true)
    assert.match(h.state.readerLimitHints[0].text, /1,200 万像素/)
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
    const current = { textStatus: 'skipped', textError: '源文件超过 200 MiB 正文抽取上限，原件已保留，可拆分后抽取。' }
    assert.equal(h.state.textLabel(current), '已跳过')
    assert.equal(h.state.textHint(current), current.textError)
    await h.state.onReextract(row)
    assert.deepEqual(h.writes, [{ action: 'startExtraction', id: 21, resume: false }])
  } finally { await h.close() }
})

test('re-extraction ignores duplicate clicks for the same file and remains busy until the list refreshes', async () => {
  const extraction = deferred(), refresh = deferred()
  const h = await harness({}, { extractionJobLoader: () => extraction.promise })
  try {
    const row = { id: 21, textStatus: 'skipped' }
    h.setPageLoader(() => refresh.promise)
    await h.state.onReextract(row)
    assert.equal(h.state.isReextracting(row), true)
    assert.equal(h.state.isReextracting({ id: 21, __folder: true }), false)
    await h.state.onReextract({ ...row, id: '21' })
    assert.deepEqual(h.writes, [{ action: 'startExtraction', id: 21, resume: false }])
    extraction.resolve({ id: 'extraction-job-1', status: 'completed' })
    await flush()
    assert.equal(h.state.isReextracting(row), true)
    refresh.resolve({ list: [{ ...row, textStatus: 'ok', textChars: 123 }], total: 1 })
    await flush()
    assert.equal(h.state.isReextracting(row), false)
    assert.equal(h.state.list[0].textStatus, 'ok')
  } finally { await h.close() }
})

test('starting a failed extraction can be retried once the error is visible', async () => {
  const h = await harness()
  try {
    const first = { id: 21 }
    h.setExtractionStartLoader(() => Promise.reject(new Error('合成抽取失败')))
    await h.state.onReextract(first)
    assert.deepEqual(h.messages, ['合成抽取失败'])
    assert.equal(h.state.isReextracting(first), false)
    h.setExtractionStartLoader(null)
    await h.state.onReextract(first)
    assert.equal(h.writes.filter(call => call.id === 21).length, 2)
    assert.deepEqual(h.extractionJobCalls, ['extraction-job-1'])
  } finally { await h.close() }
})

test('a partial PDF resumes with actual page counts, then refreshes the saved report without clearing its location', async () => {
  let job = { status: 'running', pageProgress: { stage: 'extracting', currentPage: 10, completedPages: 9, totalPages: 431 } }
  const h = await harness({}, { extractionJobLoader: async () => job })
  try {
    const row = { id: 21, ext: 'pdf', textStatus: 'ok', extractionCoverage: { completedPages: 9, processedPages: 431, totalPages: 431, partial: true } }
    assert.equal(h.state.textLabel(row), '部分抽取 9/431 页')
    assert.equal(h.state.canContinueExtraction(row), true)
    await h.state.openReader(row)
    h.state.readerLocation = { page: 2 }
    await h.state.onReextract(row)
    await flush()
    assert.deepEqual(h.writes.filter(call => call.action === 'startExtraction'), [{ action: 'startExtraction', id: 21, resume: true }])
    assert.equal(h.state.readerPageProgress.pages, '可用 9 / 431 页')
    assert.equal(h.state.readerPageProgress.percentage, 2)
    assert.match(h.state.readerPageProgress.detail, /原文第 10 页/)
    await h.state.processReaderMedia()
    assert.equal(h.writes.some(call => call.action === 'processMedia'), false)
    h.setExtractionLoader(async () => ({ status: 'ok', completedPages: 17, report: { totalPages: 431, processedPages: 431, partial: true } }))
    job = { status: 'partial', pageProgress: { stage: 'saving', currentPage: 0, completedPages: 17, totalPages: 431 } }
    h.state.resumeDocumentTask('extraction')
    await flush()
    assert.equal(h.state.readerPageProgress.pages, '可用 17 / 431 页')
    assert.equal(h.state.readerExtraction.completedPages, 17)
    assert.equal(h.state.readerLocation.page, 2)
    assert.equal(h.state.canStartExtraction(row), true)
    assert.equal(h.state.canContinueExtraction({ ...row, ext: 'docx' }), false)
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

test('a staged scan resumes its missing pages while published coverage remains complete', async () => {
  const summary = { completedPages: 20, report: { totalPages: 431, partial: true } }
  const row = { id: 21, ext: 'pdf', textStatus: 'ok', textChars: 123,
    extractionCoverage: { completedPages: 431, totalPages: 431, partial: false }, pendingUpgrade: true, scanProgress: summary }
  const h = await harness({}, { structureCapability: { configured: true, available: true } })
  try {
    let report = { status: 'ok', completedPages: 431, report: { totalPages: 431 }, pendingUpgrade: true, scanProgress: summary }
    h.setExtractionLoader(async () => report)
    await h.state.openReader(row)
    await flush()
    assert.equal(h.state.readerCoverage.completedPages, 431)
    assert.equal(h.state.readerPendingScan.completedPages, 20)
    assert.equal(h.state.readerCanContinue, true)
    assert.equal(h.state.textLabel(row), '扫描待继续 20/431 页')
    assert.match(h.state.textHint(row), /原有正文可用 431 \/ 431 页/)
    assert.equal(h.state.extractionActionLabel(row), '继续扫描')
    assert.deepEqual(h.writes.filter(call => call.action !== 'text'), [])
    await h.state.onReextract(row)
    await flush()
    assert.deepEqual(h.writes.filter(call => call.action === 'startExtraction'), [{ action: 'startExtraction', id: 21, resume: true }])
    report = { status: 'ok', completedPages: 431, report: { totalPages: 431 }, pipelineVersion: 'document-ingestion-v5-scan-hq2' }
    await h.state.ensureExtraction(true)
    assert.equal(h.state.readerPendingScan, null)
    assert.equal(h.state.readerCanContinue, false)
  } finally { await h.close() }
})

const sourceRevision = 'a'.repeat(64)
function syntheticExtraction(revision = sourceRevision) {
  return { status: 'ok', sourceSha256: revision, report: { totalPages: 2, processedPages: 2, ocrPages: 1, rejectedPages: 1, partial: true },
    pages: [{ page: 2, width: 800, height: 600, coordinateUnit: 'pt', method: 'ocr', blocks: [
      { id: 'accepted-1', page: 2, rect: [80, 60, 240, 120], cleanText: '合成检索正文', rawText: '合成 检索正文', confidence: .96, quality: 'accepted', indexed: true },
      { id: 'rejected-1', page: 2, rect: [80, 180, 400, 240], rawText: '合成低质量候选', confidence: .21, quality: 'rejected', indexed: false },
    ] }] }
}

test('reader verifies linked coordinates against its extraction revision and handles same-file locator changes', async () => {
  const h = await harness()
  try {
    h.setExtractionLoader(async () => syntheticExtraction())
    h.route.query = { read: '41', page: '2', rect: '80,60,240,120', sourceRevision }
    await flush()
    assert.equal(String(h.state.reader.id), '41')
    assert.deepEqual(h.state.readerLocation.rect, [80, 60, 240, 120])
    assert.equal(h.state.readerLocation.width, 800)
    assert.equal(h.state.locationMessage, '')
    h.route.query = { ...h.route.query, rect: '80,180,400,240', sourceRevision: 'b'.repeat(64) }
    await flush()
    assert.equal(h.state.readerLocation.page, 2)
    assert.equal(h.state.readerLocation.rect, null)
    assert.match(h.state.locationMessage, /版本已变化/)
  } finally { await h.close() }
})

test('updated Word structure rejects old block links and focuses a matching pipeline only after layout is ready', async () => {
  const h = await harness()
  try {
    const pipelineVersion = 'document-ingestion-v3-layout', focused = []
    const artifact = { status: 'ok', sourceSha256: sourceRevision, pipelineVersion, pages: [{ page: 1, coordinateUnit: 'none', blocks: [{ id: 'p1-b2', type: 'para', text: '合成新版块' }] }] }
    h.setExtractionLoader(async () => artifact)
    h.state.blockReader = { focusBlock: (...args) => focused.push(args) }
    for (const sourcePipeline of [undefined, 'document-ingestion-v2']) {
      h.route.query = { read: '41', page: '1', blockId: 'p1-b2', sourceRevision, ...(sourcePipeline ? { sourcePipeline } : {}) }
      await flush()
      assert.equal(h.state.readerLocation.blockId, '')
      assert.match(h.state.locationMessage, /抽取结构已更新，请重新检索定位/)
      assert.equal(h.state.readerTab, 'doc')
      assert.deepEqual(focused, [])
    }
    const pendingLayout = deferred()
    h.setLayoutLoader(() => pendingLayout.promise)
    h.route.query = { ...h.route.query, sourcePipeline: pipelineVersion }
    await flush()
    assert.equal(h.state.readerTab, 'text')
    assert.equal(h.state.markdownMode, 'blocks')
    assert.deepEqual(focused, [])
    pendingLayout.resolve(artifact)
    await flush()
    assert.equal(h.state.locationMessage, '')
    assert.deepEqual(focused, [['p1-b2', 1]])
    assert.ok(h.state.readerSourceHref.includes(`sourcePipeline=${pipelineVersion}`))
  } finally { await h.close() }
})

test('a slower extraction report cannot replace a newer file or draw its rectangle after close', async () => {
  const h = await harness()
  try {
    const old = deferred()
    h.setExtractionLoader(id => id === 41 ? old.promise : Promise.resolve(syntheticExtraction('b'.repeat(64))))
    await h.state.openReader({ id: 41, ext: 'pdf' }, { page: 2, rect: [80, 60, 240, 120], sourceRevision })
    await h.state.openReader({ id: 42, ext: 'pdf' })
    old.resolve(syntheticExtraction())
    await flush()
    assert.equal(h.state.reader.id, 42)
    assert.equal(h.state.readerExtraction.sourceSha256, 'b'.repeat(64))
    assert.equal(h.state.readerLocation, null)
    const late = deferred()
    h.setExtractionLoader(() => late.promise)
    await h.state.openReader({ id: 43, ext: 'pdf' })
    h.state.closeReader()
    late.resolve(syntheticExtraction())
    await flush()
    assert.equal(h.state.reader, null)
    assert.equal(h.state.readerExtraction, null)
  } finally { await h.close() }
})

test('report filters retain rejected candidates, bound rendered blocks, and locate a selected block', async () => {
  const h = await harness()
  try {
    h.setExtractionLoader(async () => syntheticExtraction())
    await h.state.openReader({ id: 41, ext: 'pdf' })
    await flush()
    assert.match(h.state.ocrCapabilityLabel, /未启用/)
    assert.equal(h.state.visibleExtractionRows.length, 1)
    h.state.blockFilter = 'rejected'
    await flush()
    assert.equal(h.state.visibleExtractionRows[0].id, 'rejected-1')
    const block = h.state.visibleExtractionRows[0]
    await h.state.openExtractionBlock(block)
    assert.equal(h.state.readerTab, 'doc')
    assert.deepEqual(h.state.readerLocation.rect, [80, 180, 400, 240])
    assert.equal(h.state.readerLocation.height, 600)
    h.state.readerExtraction = { ...syntheticExtraction(), pages: [{ page: 1, blocks: Array.from({ length: 53 }, (_, id) => ({ id: String(id), cleanText: '合成块', indexed: true })) }] }
    h.state.blockFilter = 'indexed'
    await flush()
    assert.equal(h.state.visibleExtractionRows.length, 20)
    h.state.blockPage = 3
    assert.equal(h.state.visibleExtractionRows.length, 13)
    h.state.blockFilter = 'rejected'
    await flush()
    assert.equal(h.state.blockPage, 1)
    assert.equal(h.state.visibleExtractionRows.length, 0)
  } finally { await h.close() }
})

test('re-extracting an open source refreshes its report and clears coordinates from the old revision', async () => {
  const h = await harness()
  try {
    h.setExtractionLoader(async () => syntheticExtraction())
    await h.state.openReader({ id: 41, ext: 'pdf' }, { page: 2, rect: [80, 60, 240, 120], sourceRevision })
    await flush()
    h.state.switchReaderTab('extraction')
    h.setExtractionLoader(async () => syntheticExtraction('b'.repeat(64)))
    await h.state.onReextract(h.state.reader)
    await flush()
    assert.equal(h.state.readerExtraction.sourceSha256, 'b'.repeat(64))
    assert.equal(h.state.readerLocation, null)
    assert.equal(h.state.readerTab, 'extraction')
    assert.equal(h.state.isReextracting(h.state.reader), false)
    assert.equal(h.extractionCalls.length, 2)
  } finally { await h.close() }
})

test('a failed report leaves the original readable and retry verifies a pending source rectangle', async () => {
  const h = await harness()
  try {
    h.setExtractionLoader(() => Promise.reject(new Error('合成报告暂不可用')))
    await h.state.openReader({ id: 41, ext: 'pdf' }, { page: 2, rect: [80, 60, 240, 120], sourceRevision })
    await flush()
    assert.equal(h.state.readerTab, 'doc')
    assert.equal(h.state.readerLocation.rect, null)
    assert.match(h.state.extractionError, /合成报告暂不可用/)
    assert.match(h.state.locationMessage, /未高亮/)
    h.setExtractionLoader(async () => syntheticExtraction())
    await h.state.ensureExtraction(true)
    assert.deepEqual(h.state.readerLocation.rect, [80, 60, 240, 120])
    assert.equal(h.state.locationMessage, '')
    assert.equal(h.state.extractionError, '')
  } finally { await h.close() }
})

test('image readers select the report-bound TIFF frame, with raw fallback for legacy metadata', async () => {
  const h = await harness()
  try {
    h.setExtractionLoader(async () => syntheticExtraction())
    await h.state.openReader({ id: 41, ext: 'tiff' }, { page: 2, rect: [80, 60, 240, 120], sourceRevision })
    await flush()
    assert.equal(h.state.docKind, 'image')
    assert.equal(h.state.imageOriginalSource, `/api/files/41/extraction-page-image?page=2&sourceRevision=${sourceRevision}`)
    const link = new URL(h.state.readerSourceHref, 'http://synthetic.local')
    assert.equal(link.searchParams.get('page'), '2')
    assert.equal(link.searchParams.get('sourceRevision'), sourceRevision)
    h.state.readerExtraction = { status: 'not_processed', pages: [], report: null }
    assert.equal(h.state.imageOriginalSource, '/api/files/41/raw')
  } finally { await h.close() }
})

test('structured Markdown loads lazily, exports the saved version and offers conversion for old artifacts', async () => {
  const h = await harness()
  const previousNavigator = Object.getOwnPropertyDescriptor(globalThis, 'navigator')
  const copied = []
  Object.defineProperty(globalThis, 'navigator', { configurable: true, value: { clipboard: { async writeText(text) { copied.push(text) } } } })
  try {
    await h.state.openReader({ id: 41, ext: 'pdf', originName: '合成论文.pdf' })
    assert.equal(h.markdownCalls.length, 0)
    h.state.switchReaderTab('text')
    await flush()
    assert.equal(h.state.markdownMissing, false)
    assert.match(h.state.readerMarkdown.markdown, /合成保存正文/)
    await h.state.copyMarkdown()
    assert.deepEqual(copied, ['# 合成保存正文'])
    await h.state.downloadMarkdown(false)
    await h.state.downloadMarkdown(true)
    assert.deepEqual(h.downloadCalls.map(call => call.name), ['合成论文.md', '合成论文-markdown.zip'])
    h.state.readerMarkdown = { status: 'ok', format: null, markdown: '旧正文' }
    assert.equal(h.state.markdownMissing, true)
    await h.state.copyMarkdown()
    assert.equal(copied.length, 1)
  } finally {
    await h.close()
    if (previousNavigator) Object.defineProperty(globalThis, 'navigator', previousNavigator)
    else delete globalThis.navigator
  }
})

function versionedMarkdown(version = 'candidate', overrides = {}) {
  const candidate = version === 'candidate'
  const markdown = candidate ? 'Synthetic latest text\n\n$$x+2$$' : 'Synthetic published text'
  const block = candidate
    ? { id: 'same-id', type: 'formula', latex: 'x+2', latexStatus: 'needs_review', quality: 'uncertain_formula', indexed: false,
      markdown: '$$x+2$$', markdownStart: markdown.indexOf('$$'), markdownEnd: markdown.length, rect: [10, 20, 60, 40] }
    : { id: 'same-id', type: 'para', cleanText: markdown, quality: 'accepted', indexed: true, markdown, markdownStart: 0, markdownEnd: markdown.length }
  return { status: 'ok', format: 'markdown', sourceSha256: 'a'.repeat(64), pipelineVersion: 'document-ingestion-v5-scan-hq2',
    selectedVersion: version, artifactRevision: (candidate ? 'b' : 'c').repeat(64), candidateAvailable: true, publishedAvailable: true,
    markdown, indexedMarkdown: 'Synthetic published retrieval', assets: [],
    pages: [{ page: candidate ? 2 : 1, width: 600, height: 800, coordinateUnit: 'pt', method: 'ocr', blocks: [block] }],
    report: { totalPages: 2, processedPages: 2, partial: candidate },
    reviewSummary: { formulaCount: candidate ? 1 : 0, otherCount: 0, total: candidate ? 1 : 0, complete: true },
    retrievalReview: { required: true, canPublish: false, blockers: ['请先核对公式'], candidateArtifactRevision: 'b'.repeat(64), candidateSourceRevision: 'a'.repeat(64) },
    ...overrides }
}

test('latest scan owns preview, layout, review metadata and both pinned exports without publishing', async () => {
  const h = await harness()
  const candidate = versionedMarkdown()
  const published = versionedMarkdown('published')
  try {
    h.setMarkdownLoader(async (_, version) => version === 'published' ? published : candidate)
    h.setExtractionLoader(async () => ({ ...published, pendingUpgrade: true, scanCandidate: candidate }))
    await h.state.openReader({ id: 41, ext: 'pdf' })
    h.state.switchReaderTab('text')
    await flush()
    assert.deepEqual(h.markdownVersionCalls, [{ id: 41, version: 'latest' }])
    assert.equal(h.state.markdownIsCandidate, true)
    assert.equal(h.state.markdownArtifact.pages[0].page, 2)
    assert.equal(h.state.readerLayout.pages[0].blocks[0].latex, 'x+2')
    assert.equal(h.state.markdownView.reviewCount, 1)
    assert.match(h.state.markdownView.html, /待核对：公式转写/)
    assert.equal(h.layoutCalls.length, 0)
    await h.state.processReaderMedia()
    await h.state.downloadMarkdown(false)
    await h.state.downloadMarkdown(true)
    const exports = h.writes.filter(call => call.action === 'downloadMarkdown')
    assert.equal(exports.length, 2)
    assert.ok(exports.every(call => call.selection.version === 'candidate' && call.selection.sourceRevision === candidate.sourceSha256 && call.selection.artifactRevision === candidate.artifactRevision))
    assert.equal(h.writes.some(call => ['processMedia', 'publishScan', 'confirmStructureFormula'].includes(call.action)), false)
  } finally { await h.close() }
})

test('rapid version switches discard old Markdown and layout even for an identical source revision', async () => {
  const h = await harness()
  const old = deferred()
  try {
    h.setMarkdownLoader((_, version) => version === 'latest' ? old.promise : Promise.resolve(versionedMarkdown('published')))
    await h.state.openReader({ id: 41, ext: 'pdf' })
    const pending = h.state.ensureMarkdown()
    await h.state.selectMarkdownVersion('published')
    old.resolve(versionedMarkdown())
    await pending
    assert.equal(h.state.readerMarkdown.selectedVersion, 'published')
    assert.equal(h.state.readerLayout.pages[0].page, 1)
    assert.equal(h.state.markdownLoading, false)
    assert.equal(h.state.markdownView.reviewCount, 0)
    await h.state.openReader({ id: 42, ext: 'pdf' })
    assert.equal(h.state.markdownVersion, 'latest')
    assert.equal(h.state.readerLayout, null)
  } finally { await h.close() }
})

test('an obsolete pending Markdown cannot start a published layout request under the latest scan generation', async () => {
  const h = await harness()
  const published = deferred(), candidate = deferred(), legacyLayout = deferred()
  try {
    h.setMarkdownLoader((_, version) => version === 'published' ? published.promise : candidate.promise)
    h.setLayoutLoader(() => legacyLayout.promise)
    await h.state.openReader({ id: 41, ext: 'pdf' })
    h.state.markdownVersion = 'published'
    const oldLayout = h.state.ensureLayout()
    const latest = h.state.selectMarkdownVersion('latest')
    published.resolve({ status: 'ok', format: 'markdown', markdown: 'Synthetic legacy published Markdown', assets: [] })
    await flush()
    const layoutRequestsBeforeCandidate = h.layoutCalls.length
    candidate.resolve(versionedMarkdown())
    await latest
    legacyLayout.resolve({ status: 'ok', pages: [{ page: 1, blocks: [{ id: 'old-published', text: 'Synthetic old layout' }] }] })
    await oldLayout
    assert.equal(layoutRequestsBeforeCandidate, 0, 'the expired Markdown must not borrow the newer request generation')
    assert.equal(h.state.readerMarkdown.selectedVersion, 'candidate')
    assert.equal(h.state.readerLayout.pages[0].page, 2)
    assert.equal(h.state.readerLayout.pages[0].blocks[0].latex, 'x+2')
    assert.equal(h.state.layoutLoading, false)
  } finally { await h.close() }
})

test('main Markdown review uses its exact candidate snapshot and formula target, not a newer report', async () => {
  const h = await harness()
  const candidate = versionedMarkdown()
  const newer = versionedMarkdown('candidate', { pages: [{ page: 2, blocks: [{ id: 'same-id', type: 'formula', latex: 'x+99', latexStatus: 'needs_review' }] }] })
  try {
    h.setMarkdownLoader(async () => candidate)
    h.setExtractionLoader(async () => ({ ...versionedMarkdown('published'), pendingUpgrade: true, scanCandidate: newer }))
    await h.state.openReader({ id: 41, ext: 'pdf' })
    await h.state.ensureMarkdown()
    await h.state.openMarkdownReview('same-id', 2)
    assert.equal(h.state.readerTab, 'extraction')
    assert.equal(h.state.extractionRows[0].latex, 'x+2')
    h.state.beginFormulaReview(h.state.extractionRows[0])
    assert.equal(h.state.formulaReview.pendingScan, true)
    assert.equal(h.state.formulaReview.expectedLatex, 'x+2')
    assert.equal(h.state.formulaReview.artifactRevision, candidate.artifactRevision)
    await h.state.confirmFormulaReview()
    assert.equal(h.writes.find(call => call.action === 'confirmStructureFormula').data.artifactRevision, candidate.artifactRevision)
    await h.state.selectMarkdownVersion('published')
    assert.equal(h.state.formulaReview, null)
    assert.equal(h.writes.some(call => call.action === 'publishScan'), false)
  } finally { await h.close() }
})

test('retrieval publication requires its separate confirmation and rejects version changes during review', async () => {
  const confirm = deferred()
  const h = await harness({ }, { confirm: () => confirm.promise })
  const ready = versionedMarkdown('candidate', { retrievalReview: { required: true, canPublish: true, excludedOtherCount: 3,
    blockers: [], candidateSourceRevision: 'a'.repeat(64), candidateArtifactRevision: 'b'.repeat(64) } })
  try {
    h.setMarkdownLoader(async (_, version) => version === 'published' ? versionedMarkdown('published') : ready)
    await h.state.openReader({ id: 41, ext: 'pdf' })
    await h.state.ensureMarkdown()
    assert.equal(h.writes.some(call => call.action === 'publishScan'), false)
    const stale = h.state.publishReviewedScan()
    await flush()
    assert.match(h.prompts[0][0], /3 处待核对内容会被排除/)
    await h.state.selectMarkdownVersion('published')
    confirm.resolve()
    await stale
    assert.equal(h.writes.some(call => call.action === 'publishScan'), false)
    await h.state.selectMarkdownVersion('latest')
    await h.state.publishReviewedScan()
    assert.deepEqual(h.writes.filter(call => call.action === 'publishScan'), [{ action: 'publishScan', id: 41,
      data: { sourceRevision: 'a'.repeat(64), artifactRevision: 'b'.repeat(64) } }])
  } finally { await h.close() }
})

test('old Markdown responses cannot replace a newer reader file', async () => {
  const h = await harness()
  try {
    const old = deferred()
    h.setMarkdownLoader(id => id === 41 ? old.promise : Promise.resolve({ status: 'ok', format: 'markdown', markdown: '# 当前文件', assets: [] }))
    await h.state.openReader({ id: 41, ext: 'pdf' })
    const pending = h.state.ensureMarkdown()
    await h.state.openReader({ id: 42, ext: 'pdf' })
    await h.state.ensureMarkdown()
    old.resolve({ status: 'ok', format: 'markdown', markdown: '# 旧文件' })
    await pending
    assert.equal(h.state.reader.id, 42)
    assert.equal(h.state.readerMarkdown.markdown, '# 当前文件')
  } finally { await h.close() }
})

test('explicit media jobs reject duplicate clicks, retain partial status and refresh all saved views', async () => {
  const h = await harness()
  try {
    const pending = deferred()
    h.setMediaLoader(() => pending.promise)
    await h.state.openReader({ id: 41, ext: 'pdf' })
    await h.state.processReaderMedia()
    await h.state.processReaderMedia()
    assert.equal(h.state.mediaBusy, true)
    assert.equal(h.writes.filter(call => call.action === 'processMedia').length, 1)
    h.setMarkdownLoader(async () => ({ status: 'ok', format: 'markdown', markdown: '# 媒体处理后的保存版本', assets: [] }))
    pending.resolve({ id: 'media-job-1', status: 'partial', done: 1, total: 1, failed: 0 })
    await flush()
    assert.equal(h.state.mediaJob.status, 'partial')
    assert.equal(h.state.mediaBusy, false)
    assert.match(h.state.readerMarkdown.markdown, /媒体处理后/)
    assert.equal(h.extractionCalls.length, 2)
    const count = h.markdownCalls.length
    await h.state.onFormulaPersisted({ fileId: 99 })
    assert.equal(h.markdownCalls.length, count)
    await h.state.onFormulaPersisted({ fileId: 41 })
    assert.equal(h.markdownCalls.length, count + 1)
  } finally { await h.close() }
})

test('structured OCR starts only by request on supported files and refreshes the matching saved report', async () => {
  const pending = deferred()
  const h = await harness({}, { structureCapability: { configured: true, available: true }, structureLoader: () => pending.promise })
  try {
    await h.state.openReader({ id: 40, ext: 'svg' })
    await h.state.processReaderStructure()
    assert.equal(h.state.structureSupported, false)
    await h.state.openReader({ id: 41, ext: 'pdf' })
    assert.equal(h.state.structureSupported, true)
    assert.equal(h.state.structureCapabilityLabel, '可用')
    assert.deepEqual(h.writes.filter(call => call.action === 'processStructure'), [])
    await Promise.all([h.state.processReaderStructure(), h.state.processReaderStructure()])
    await h.state.onReextract({ id: 41 })
    assert.deepEqual(h.writes.filter(call => ['processStructure', 'startExtraction'].includes(call.action)), [{ action: 'processStructure', id: '41', resume: false }])
    assert.equal(h.state.isStructuring({ id: 41 }), true)
    assert.equal(h.state.isStructuring({ id: 40 }), false)
    assert.deepEqual(h.structureJobCalls, ['structure-job-1'])
    h.setMarkdownLoader(async () => ({ status: 'ok', format: 'markdown', markdown: '# 结构化扫描正文', assets: [] }))
    pending.resolve({ id: 'structure-job-1', status: 'partial', done: 1, total: 2 })
    await flush()
    assert.equal(h.state.structureBusy, false)
    assert.equal(h.state.readerStructureJob.status, 'partial')
    assert.match(h.state.readerMarkdown.markdown, /结构化扫描正文/)
    assert.deepEqual(h.extractionCalls, [40, 41, 41])
  } finally { await h.close() }
})

test('formula confirmation submits only current validated candidates after an explicit review', async () => {
  const revision = 'a'.repeat(64)
  const candidate = { id: 'pending-formula', type: 'formula', latexStatus: 'needs_review', indexed: false, latex: 'x+1' }
  const report = () => ({ ...syntheticExtraction(revision), pages: [{ page: 1, blocks: [candidate] }] })
  const h = await harness()
  try {
    h.setExtractionLoader(async () => report())
    await h.state.openReader({ id: 41, ext: 'pdf' })
    await h.state.confirmFormulaReview()
    assert.equal(h.state.formulaReviewCount, 1)
    h.state.beginFormulaReview(h.state.extractionRows[0])
    assert.equal(h.state.formulaReview.latex, 'x+1')
    h.state.formulaReview.latex = '{'
    await h.state.confirmFormulaReview()
    h.state.formulaReview.latex = 'x+2'
    candidate.latex = 'x+3'
    await h.state.ensureExtraction(true)
    await h.state.confirmFormulaReview()
    assert.deepEqual(h.writes.filter(call => call.action === 'confirmStructureFormula'), [])
    h.state.beginFormulaReview(h.state.extractionRows[0])
    h.state.formulaReview.latex = ' x+2 '
    await h.state.confirmFormulaReview()
    assert.deepEqual(h.writes.filter(call => call.action === 'confirmStructureFormula'), [{
      action: 'confirmStructureFormula', id: '41', data: { blockId: 'pending-formula', sourceRevision: revision, expectedLatex: 'x+3', latex: 'x+2', pendingScan: false },
    }])
    assert.equal(h.state.formulaReview, null)
    assert.equal(h.extractionCalls.length, 3)
  } finally { await h.close() }
})

test('pending scan reports and formula review stay separate from published evidence', async () => {
  const revision = 'a'.repeat(64)
  const candidate = { status: 'partial', sourceSha256: revision, pipelineVersion: 'document-ingestion-v5-scan-hq2',
    markdown: '# 合成扫描候选', report: { totalPages: 2, partial: true },
    pages: [{ page: 2, width: 600, height: 800, coordinateUnit: 'pt', method: 'ocr', blocks: [
      { id: 'shared-formula', type: 'formula', rect: [20, 40, 100, 70], indexed: false, latexStatus: 'needs_review', latex: 'x+2' },
    ] }] }
  const published = { ...syntheticExtraction(revision), pendingUpgrade: true, scanCandidate: candidate,
    scanProgress: { completedPages: 1, report: { totalPages: 2, partial: true } },
    pages: [{ page: 1, blocks: [{ id: 'shared-formula', indexed: true, latexStatus: 'recognized', latex: 'x+1', text: '合成已发布正文' }] }] }
  const h = await harness()
  try {
    h.setExtractionLoader(async () => published)
    await h.state.openReader({ id: 41, ext: 'pdf' })
    await flush()
    assert.equal(h.state.viewingScanCandidate, false)
    assert.equal(h.state.extractionRows[0].text, '合成已发布正文')
    assert.deepEqual(h.writes.filter(call => call.action !== 'text'), [])
    h.state.extractionReportMode = 'candidate'
    await flush()
    assert.equal(h.state.viewingScanCandidate, true)
    assert.match(h.state.candidateMarkdownView.html, /合成扫描候选/)
    assert.equal(h.state.formulaReviewCount, 1)
    const block = h.state.extractionRows[0]
    await h.state.openExtractionBlock(block)
    assert.deepEqual(h.state.readerLocation.rect, [20, 40, 100, 70])
    assert.equal(h.state.readerLocation.page, 2)
    h.state.beginFormulaReview(block)
    assert.equal(h.state.formulaReview.pendingScan, true)
    assert.equal(h.state.formulaReview.expectedLatex, 'x+2')
    h.state.extractionReportMode = 'published'
    await flush()
    await h.state.confirmFormulaReview()
    assert.deepEqual(h.writes.filter(call => call.action !== 'text'), [])
    h.state.extractionReportMode = 'candidate'
    await flush()
    h.state.beginFormulaReview(h.state.extractionRows[0])
    h.state.formulaReview.latex = 'x+3'
    await h.state.confirmFormulaReview()
    assert.deepEqual(h.writes.filter(call => call.action === 'confirmStructureFormula'), [{
      action: 'confirmStructureFormula', id: '41', data: { blockId: 'shared-formula', sourceRevision: revision, expectedLatex: 'x+2', latex: 'x+3', pendingScan: true },
    }])
    assert.equal(h.state.readerExtraction.pages[0].blocks[0].text, '合成已发布正文')
  } finally { await h.close() }
})

test('migration starts only on demand and a second click cannot create a duplicate background task', async () => {
  const h = await harness()
  try {
    assert.equal(h.writes.some(call => call.action === 'migrateMarkdown'), false)
    const pending = deferred()
    h.setMigrationLoader(() => pending.promise)
    await h.state.startMarkdownMigration()
    await h.state.startMarkdownMigration()
    assert.equal(h.writes.filter(call => call.action === 'migrateMarkdown').length, 1)
    assert.equal(h.state.migrationBusy, true)
    pending.resolve({ id: 'migration-job-1', status: 'completed', done: 2, total: 2, failed: 0 })
    await flush()
    assert.equal(h.state.migrationJob.status, 'completed')
    assert.equal(h.state.migrationBusy, false)
  } finally { await h.close() }
})

test('continuing media processing never forces already reliable regions to be recognized again', async () => {
  const h = await harness()
  try {
    h.setExtractionLoader(async () => ({ ...syntheticExtraction(), pages: [{ page: 1, blocks: [
      { id: 'reliable-formula', type: 'formula', quality: 'recognized', latexStatus: 'recognized', indexed: true, latex: 'x_1' },
      { id: 'pending-region-13', type: 'figure', quality: 'pending', indexed: false },
    ] }] }))
    await h.state.openReader({ id: 41, ext: 'pdf' })
    await flush()
    assert.equal(h.state.mediaPreviouslyProcessed, true)
    await h.state.processReaderMedia()
    assert.deepEqual(h.writes.filter(call => call.action === 'processMedia'), [{ action: 'processMedia', id: 41, force: false }])
  } finally { await h.close() }
})

const linkedMediaId = '11111111-1111-4111-8111-111111111111'
const linkedMigrationId = '22222222-2222-4222-8222-222222222222'

test('existing media and migration deep links only GET their jobs and never create processing tasks', async () => {
  const h = await harness({ read: '41', mediaJob: linkedMediaId, migrationJob: linkedMigrationId })
  try {
    assert.deepEqual(h.mediaJobCalls, [linkedMediaId])
    assert.deepEqual(h.migrationJobCalls, [linkedMigrationId])
    assert.equal(h.state.mediaJob.fileId, '41')
    assert.equal(h.state.readerTab, 'text')
    assert.equal(h.writes.some(call => ['processMedia', 'migrateMarkdown'].includes(call.action)), false)
  } finally { await h.close() }
  const migrationOnly = await harness({ migrationJob: linkedMigrationId })
  try {
    assert.deepEqual(migrationOnly.mediaJobCalls, [])
    assert.deepEqual(migrationOnly.migrationJobCalls, [linkedMigrationId])
    assert.equal(migrationOnly.state.reader, null)
  } finally { await migrationOnly.close() }
})

test('invalid job links are ignored without GET or POST processing requests', async () => {
  for (const query of [{ read: '41', mediaJob: 'bad', migrationJob: 'bad' }, { read: '0', mediaJob: linkedMediaId },
    { mediaJob: linkedMediaId }, { read: ['41'], mediaJob: linkedMediaId }, { read: '41', mediaJob: [linkedMediaId], migrationJob: [linkedMigrationId] }]) {
    const h = await harness(query)
    try {
      assert.deepEqual(h.mediaJobCalls, [])
      assert.deepEqual(h.migrationJobCalls, [])
      assert.equal(h.writes.some(call => ['processMedia', 'migrateMarkdown'].includes(call.action)), false)
    } finally { await h.close() }
  }
})

test('route jobs take precedence per kind over local storage without duplicate polling', async () => {
  const previous = Object.getOwnPropertyDescriptor(globalThis, 'localStorage')
  const values = new Map(), storage = { getItem: key => values.get(key), setItem: (key, value) => values.set(key, value) }
  Object.defineProperty(globalThis, 'localStorage', { configurable: true, value: storage })
  extractionTasks.rememberExtractionTask('media', { id: 'stored-media', fileId: '7', status: 'queued' }, storage)
  extractionTasks.rememberExtractionTask('migration', { id: 'stored-migration', status: 'queued' }, storage)
  extractionTasks.rememberExtractionTask('structure', { id: 'stored-structure', fileId: '41', status: 'queued' }, storage)
  const h = await harness({ read: '41', mediaJob: linkedMediaId })
  try {
    assert.deepEqual(h.mediaJobCalls, [linkedMediaId])
    assert.deepEqual(h.migrationJobCalls, ['stored-migration'])
    assert.deepEqual(h.structureJobCalls, ['stored-structure'])
    assert.equal(h.writes.some(call => call.action === 'processStructure'), false)
  } finally {
    await h.close()
    if (previous) Object.defineProperty(globalThis, 'localStorage', previous)
    else delete globalThis.localStorage
  }
})

test('changing or removing linked job queries invalidates older status results before any refresh', async () => {
  const old = deferred(), removed = deferred()
  const h = await harness({ read: '41', mediaJob: linkedMediaId }, {
    mediaLoader: id => id === linkedMediaId ? old.promise : id === linkedMigrationId ? removed.promise : Promise.resolve({ id, status: 'completed' }),
  })
  try {
    const latestId = '33333333-3333-4333-8333-333333333333'
    h.route.query = { read: '42', mediaJob: latestId }
    await flush()
    assert.equal(h.state.mediaJob.id, latestId)
    assert.equal(h.state.mediaJob.fileId, '42')
    const count = h.markdownCalls.length
    old.resolve({ id: linkedMediaId, status: 'completed' })
    await flush()
    assert.equal(h.state.mediaJob.id, latestId)
    assert.equal(h.markdownCalls.length, count)
    h.route.query = { read: '42', mediaJob: linkedMigrationId }
    await flush()
    h.route.query = { read: '42', mediaJob: 'invalid' }
    await flush()
    const afterRemoval = h.markdownCalls.length
    removed.resolve({ id: linkedMigrationId, status: 'completed' })
    await flush()
    assert.equal(h.state.mediaJob, null)
    assert.equal(h.markdownCalls.length, afterRemoval)
    assert.equal(h.writes.some(call => ['processMedia', 'migrateMarkdown'].includes(call.action)), false)
  } finally { await h.close() }
})
