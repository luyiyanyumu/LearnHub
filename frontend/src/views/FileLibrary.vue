<script setup>
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { useRoute, useRouter } from 'vue-router'
import { categoryApi, fileApi, folderApi, saveBlob } from '../api'
import FolderExplorerToolbar from '../components/FolderExplorerToolbar.vue'
import FolderMoveDialog from '../components/FolderMoveDialog.vue'
import PdfReader from '../components/PdfReader.vue'
import DocTextView from '../components/DocTextView.vue'
import OriginalDocumentView from '../components/OriginalDocumentView.vue'
import ReaderTranslation from '../components/ReaderTranslation.vue'
import { documentKind } from '../utils/documentKind'
import { createSourceRouteReader } from '../utils/sourceRouteReader'
import { flattenFolderOptions } from '../utils/folders'
import { folderExplorerQuery, folderExplorerRows, folderExplorerScope, isExplorerFolder } from '../utils/folderExplorer'
import { confidenceLabel, extractionBlocks, extractionStatus, extractionQualityLabel, extractionBlockTypeLabel, extractionMediaLabel, extractionLimitHints, hasExtractionLimit, isManuallyReviewedFormula } from '../utils/documentExtraction.js'
import { normalizeSourceLocation, resolveSourceLocation, sourceLocationQuery, sourceLocatorFromRoute, sourceRegionStyle } from '../utils/sourceLocation.js'
import { extractedBlockAssetUrl, renderExtractedMarkdown, verifiedSourcePage } from '../utils/extractedMarkdown.js'
import { MAX_EXTRACTED_LATEX_CHARS, renderExtractedMath } from '../utils/extractedMath.js'
import { createExtractionTaskMonitor, extractionPageProgress, extractionTaskFinished, extractionTaskLabel, extractionTasksFromRoute, pendingScanCoverage, readExtractionTasks, rememberExtractionTask } from '../utils/extractionTasks.js'

const loading = ref(false)
const route = useRoute()
const router = useRouter()
const uploading = ref(false)
const list = ref([])
/** 分页状态。列表一次只渲染一页（而不是全量）——原因见 fileApi.page 的注释。 */
const pager = ref({ page: 1, size: 20, total: 0 })
const categories = ref([])
const query = ref({ categoryId: undefined, kw: '' })
const uploadCategoryId = ref(undefined)
const uploadInput = ref(null)
const folders = ref([])
const foldersLoading = ref(false)
const selectedFolder = ref(folderExplorerScope(route.query))
const folderToolbar = ref(null)
const table = ref(null)
const selectedRows = ref([])
const moveVisible = ref(false)
const moveIds = ref([])
const moveLabel = ref('')
let listSeq = 0
let foldersSeq = 0
const folderPaths = computed(() => new Map(flattenFolderOptions(folders.value).map(folder => [folder.id, folder.path])))
const explorerRows = computed(() => folderExplorerRows(folders.value, list.value, {
  scope: selectedFolder.value, keyword: query.value.kw, kind: 'file',
}))
const visibleFolderCount = computed(() => explorerRows.value.filter(isExplorerFolder).length)

function folderPath(id) {
  return id ? folderPaths.value.get(Number(id)) || '文件夹' : '未收纳'
}

const currentFolderTitle = computed(() => selectedFolder.value == null
  ? '全部资料'
  : selectedFolder.value === 0 ? '资料库' : folderPath(selectedFolder.value))
const hasFilter = computed(() => !!(query.value.categoryId || query.value.kw))
const emptyMessage = computed(() => hasFilter.value
  ? '当前文件夹没有匹配的资料，试试清除筛选条件'
  : selectedFolder.value == null ? '资料库还是空的，上传第一份资料开始收纳' : '这个文件夹还没有资料，可上传或从其他文件夹移入')

async function loadFolders() {
  const seq = ++foldersSeq
  foldersLoading.value = true
  try {
    const data = await folderApi.list('file')
    if (seq !== foldersSeq) return
    folders.value = Array.isArray(data) ? data : data?.list || []
    if (selectedFolder.value > 0 && !folders.value.some(folder => Number(folder.id) === Number(selectedFolder.value))) {
      selectedFolder.value = 0
    }
  } finally {
    if (seq === foldersSeq) foldersLoading.value = false
  }
}

async function refreshCollection() {
  await Promise.all([loadFolders(), load()])
}

function openMove(rows = selectedRows.value) {
  const files = rows.filter(row => !isExplorerFolder(row))
  if (!files.length) return
  moveIds.value = files.map(row => row.id)
  moveLabel.value = '资料'
  moveVisible.value = true
}

async function onMoved() {
  selectedRows.value = []
  table.value?.clearSelection()
  await refreshCollection()
}

watch(() => route.query, value => {
  selectedFolder.value = folderExplorerScope(value)
})

watch(selectedFolder, scope => {
  if (folderExplorerScope(route.query) !== scope) {
    router.replace({ path: route.path, query: folderExplorerQuery(route.query, scope), hash: route.hash })
  }
  pager.value.page = 1
  load()
})

function openExplorerRow(row, _column, event) {
  const control = event?.target?.closest?.('button, a, input, textarea, [role="button"], .el-select')
  if (control && !control.classList?.contains('folder-name')) return
  if (isExplorerFolder(row)) selectedFolder.value = Number(row.id)
  else openReader(row)
}

function onSelectionChange(rows) {
  const visible = new Set(explorerRows.value.filter(row => !isExplorerFolder(row)).map(row => row.__explorerKey))
  selectedRows.value = loading.value ? [] : rows.filter(row => !isExplorerFolder(row) && visible.has(row.__explorerKey))
}

function selectableFile(row) {
  return !isExplorerFolder(row)
}

function manageExplorerFolder(action, row) {
  if (isExplorerFolder(row)) folderToolbar.value?.manageFolder(action, row)
}

/**
 * 数字格式化统一走 Intl：手拼字符串会把 1234567 显示成 `1234567`（该是 `1,234,567`），
 * 而且不同语言环境下分隔符本来就不同。列表里的数字都参与"一眼比大小"，所以要给它们正确的格式。
 */
const NUM = new Intl.NumberFormat('zh-CN')

function fmtSize(bytes) {
  if (bytes == null) return '—'
  if (bytes < 1024) return NUM.format(bytes) + ' B'
  if (bytes < 1024 * 1024) return (bytes / 1024).toFixed(1) + ' KB'
  if (bytes < 1024 * 1024 * 1024) return (bytes / 1024 / 1024).toFixed(1) + ' MB'
  return (bytes / 1024 / 1024 / 1024).toFixed(2) + ' GB'
}

/**
 * 上传时间。
 *
 * <p>空值给 `—` 而不是空字符串：同一列里其他行都有时间，空字符串看起来像"漏渲染了"。
 * 这里与 `fmtSize` 的约定保持一致（原来只有 fmtSize 有兜底，time 没有）。
 */
function time(v) {
  if (!v) return '—'
  return String(v).replace('T', ' ').slice(0, 16)
}

async function loadCats() {
  categories.value = flatten(await categoryApi.tree())
}
function flatten(nodes, depth = 0, out = []) {
  for (const n of nodes || []) {
    out.push({ ...n, depth })
    if (n.children?.length) flatten(n.children, depth + 1, out)
  }
  return out
}

/**
 * 分类下拉的 label：全角空格缩进，**封顶 3 层**。
 *
 * <p>为什么封顶：分类树深度没有上限，而「分类」列只有 150px（扣 padding ≈126px）。
 * 深度 5 就是 5 个全角空格（约 70px），分类名本身只剩几十像素 —— 界面上一眼看过去全是缩进。
 * 真要表达深层级，应该靠搜索或树形选择器，而不是把缩进堆在下拉里。
 */
function catLabel(c) {
  return '　'.repeat(Math.min(c.depth || 0, 3)) + c.name
}

/**
 * break-ui：最坏数据集开关（**dev-only**）。
 *
 * 只在 URL 带 `?data=` 时才生效 —— 生产里没有这个参数，就完全不激活、连 fixture 都不会被下载
 * （fixture 是动态 import，单独成 chunk）。切换条也只在带参数时渲染，所以正式界面看不到它。
 *
 * 造它的原因：这个列表是照着"友好数据"设计出来的（文件名都不长、说明都空着、分类都选了），
 * 而真实数据里会出现 255 字的长名、不能断行的相机文件名、六个抽取状态混排、1,000 行不分页。
 */
const DEV_FIXTURES = [
  { key: 'worst', label: 'Worst case' },
  { key: 'demo', label: 'Demo data' },
  { key: 'empty', label: 'Empty' },
  { key: 'one', label: 'One' },
  { key: 'huge', label: '1,000 rows' },
]
const devData = ref(new URLSearchParams(window.location.search).get('data') || '')

async function setDevData(key) {
  devData.value = key
  await router.replace({ path: route.path, query: { ...route.query, data: key }, hash: route.hash })
  await load()
}

async function load() {
  const seq = ++listSeq
  loading.value = true
  selectedRows.value = []
  table.value?.clearSelection()
  try {
    if (devData.value && devData.value !== 'demo') {
      const m = await import('../dev/worstCaseFiles')
      if (seq !== listSeq) return
      const fixture = m.pickFixture(devData.value) || []
      const all = selectedFolder.value == null ? fixture : fixture.filter(row => Number(row.folderId || 0) === selectedFolder.value)
      // 最坏数据集也走同一套分页逻辑 —— 这样 `1,000 rows` 这个状态看到的就是修复后的行为
      pager.value.total = all.length
      const start = (pager.value.page - 1) * pager.value.size
      list.value = all.slice(start, start + pager.value.size)
      return
    }
    const params = { ...query.value, page: pager.value.page, size: pager.value.size }
    if (selectedFolder.value != null) params.folderId = selectedFolder.value
    if (!params.categoryId) delete params.categoryId
    if (!params.kw) delete params.kw
    const r = await fileApi.page(params)
    if (seq !== listSeq) return
    list.value = r.list || []
    pager.value.total = r.total || 0
    // A bulk move/delete can remove several pages; clamp directly instead of requesting each empty page.
    const lastPage = Math.max(1, Math.ceil(pager.value.total / pager.value.size))
    if (pager.value.page > lastPage) {
      pager.value.page = lastPage
      if (pager.value.total > 0) await load()
    }
  } finally {
    if (seq === listSeq) loading.value = false
  }
}

function search() {
  // 换筛选条件必须回到第 1 页：停在第 3 页搜一个只有 2 条结果的关键词会看到空表
  pager.value.page = 1
  load()
}

function resetFilter() {
  query.value = { categoryId: undefined, kw: '' }
  pager.value.page = 1
  load()
}

function onPageChange(p) {
  pager.value.page = p
  load()
}

function onPageSizeChange(s) {
  pager.value.size = s
  pager.value.page = 1
  load()
}

function pickUpload() {
  uploadInput.value?.click()
}

async function onFileChosen(e) {
  const files = Array.from(e.target.files || [])
  if (!files.length) return
  // A batch keeps its original destination even when the user opens another folder mid-upload.
  const destination = selectedFolder.value
  const categoryId = uploadCategoryId.value || null
  let uploaded = 0
  uploading.value = true
  try {
    for (const f of files) {
      await fileApi.upload(f, categoryId, destination > 0 ? destination : null)
      uploaded += 1
      ElMessage.success(`已上传：${f.name}`)
    }
  } finally {
    e.target.value = ''
    uploading.value = false
    if (uploaded) {
      // Keep a newly chosen view's pagination; only the upload destination returns to page one.
      if (selectedFolder.value === destination) pager.value.page = 1
      await refreshCollection()
    }
  }
}

/**
 * 资料阅读器。
 *
 *   · **原文** —— PDF 由 pdf.js 渲染；Word / Markdown 从上传的原文件生成阅读视图；
 *     三种文档共用选区翻译，图片/音视频直接显示；
 *   · **抽取正文** —— PDF / Word / Markdown 走 text-layout，保留段落与公式；
 *     其他格式使用检索文本兜底，抽不出时显示原因。
 */
const reader = ref(null)
const readerTab = ref('doc')
const pdfReader = ref(null)
const originalSourceRoot = ref(null)
const textSourceRoot = ref(null)
const textTranslator = ref(null)
const blockReader = ref(null)
const readerText = ref('')
const readerStatus = ref('')
const readerError = ref('')
const readerLoading = ref(false)
const readerMarkdown = ref(null)
const markdownLoading = ref(false)
const markdownError = ref('')
const markdownMode = ref('preview')
const markdownVersion = ref('latest')
const markdownDownloading = ref(false)
const publishingScan = ref(false)
const publicationError = ref('')
let markdownRequest = null
let markdownLoadSeq = 0
const markdownMissing = computed(() => readerMarkdown.value && (readerMarkdown.value.status === 'not_processed' || readerMarkdown.value.format !== 'markdown'))
const markdownArtifact = computed(() => ({ ...readerMarkdown.value, fileId: reader.value?.id, pages: readerMarkdown.value?.pages || [] }))
const markdownIsCandidate = computed(() => readerMarkdown.value?.selectedVersion === 'candidate')
const markdownVersionLabel = computed(() => markdownIsCandidate.value ? '最新扫描 · 待审核' : '已发布检索版本')
const markdownReview = computed(() => readerMarkdown.value?.reviewSummary || {})
const retrievalReview = computed(() => readerMarkdown.value?.retrievalReview || {})
const canPublishSelectedScan = computed(() => markdownIsCandidate.value && retrievalReview.value.canPublish
  && !markdownLoading.value && !publishingScan.value && !formulaReviewSaving.value
  && !isReextracting(reader.value) && !isStructuring(reader.value) && !isProcessingMedia(reader.value))
const markdownView = computed(() => renderExtractedMarkdown(readerMarkdown.value?.markdown || '', markdownArtifact.value))
const mediaStartingId = ref('')
const mediaStarting = computed(() => !!mediaStartingId.value)
const mediaJob = ref(null)
const mediaTaskError = ref('')
const migrationStarting = ref(false)
const migrationJob = ref(null)
const migrationTaskError = ref('')
const structureStartingId = ref('')
const structureJob = ref(null)
const structureTaskError = ref(null)
const extractionStartingId = ref('')
const extractionRefreshingId = ref('')
const extractionJob = ref(null)
const extractionTaskError = ref('')
const mediaBusy = computed(() => mediaStarting.value || !!mediaJob.value && !extractionTaskFinished(mediaJob.value))
const migrationBusy = computed(() => migrationStarting.value || !!migrationJob.value && !extractionTaskFinished(migrationJob.value))
const structureBusy = computed(() => !!structureStartingId.value || !!structureJob.value && !extractionTaskFinished(structureJob.value))
const extractionBusy = computed(() => !!extractionStartingId.value || !!extractionRefreshingId.value || !!extractionJob.value && !extractionTaskFinished(extractionJob.value))
const readerStructureJob = computed(() => String(structureJob.value?.fileId) === String(reader.value?.id) ? structureJob.value : null)
const readerStructureError = computed(() => structureTaskError.value?.fileId === String(reader.value?.id) ? structureTaskError.value.message : '')
const readerPageJob = computed(() => {
  const body = reader.value && String(extractionJob.value?.fileId) === String(reader.value.id) ? extractionJob.value : null
  const structure = readerStructureJob.value
  return [body, structure].find(job => job && !extractionTaskFinished(job))
    || ((structure?.updatedAt || 0) > (body?.updatedAt || 0) ? structure : body || structure)
})
const readerPageProgress = computed(() => extractionPageProgress(readerPageJob.value))
const readerPageTaskKind = computed(() => readerPageJob.value === readerStructureJob.value ? 'structure' : 'extraction')
const readerPageTaskError = computed(() => readerPageTaskKind.value === 'structure' ? readerStructureError.value : extractionTaskError.value)
const documentJobs = computed(() => [
  { kind: 'media', title: '公式与图片处理', job: mediaJob.value, error: mediaTaskError.value },
  { kind: 'migration', title: '已有资料 Markdown 更新', job: migrationJob.value, error: migrationTaskError.value },
  {
    kind: 'structure', title: '高精度扫描', job: structureJob.value,
    progress: extractionPageProgress(structureJob.value),
    error: structureTaskError.value?.fileId === String(structureJob.value?.fileId) ? structureTaskError.value.message : '',
  },
  { kind: 'extraction', title: extractionJob.value?.scanMode ? '高精度扫描' : '正文抽取', job: extractionJob.value, progress: extractionPageProgress(extractionJob.value), error: extractionTaskError.value },
].filter(item => item.job))
const readerExtraction = ref(null)
const extractionReportMode = ref('published')
const reportSnapshot = ref(null)
const scanCandidate = computed(() => readerExtraction.value?.pendingUpgrade ? readerExtraction.value.scanCandidate : null)
const reportExtraction = computed(() => reportSnapshot.value || (extractionReportMode.value === 'candidate' && scanCandidate.value ? scanCandidate.value : readerExtraction.value))
const viewingScanCandidate = computed(() => reportSnapshot.value ? reportSnapshot.value.selectedVersion === 'candidate' : reportExtraction.value === scanCandidate.value && !!scanCandidate.value)
const candidateMarkdownView = computed(() => renderExtractedMarkdown(reportExtraction.value?.markdown || '', {
  ...reportExtraction.value,
  fileId: reader.value?.id, sourceSha256: reportExtraction.value?.sourceSha256,
  assets: reportExtraction.value?.assets || [], pages: reportExtraction.value?.pages || [], renderScope: 'scan-candidate',
}))
watch(extractionReportMode, () => { reportSnapshot.value = null; formulaReview.value = null; blockPage.value = 1 }, { flush: 'sync' })
watch(readerExtraction, value => { if (value && !value.pendingUpgrade) extractionReportMode.value = 'published' })
const readerCoverage = computed(() => {
  const report = readerExtraction.value?.report
  const source = report ? readerExtraction.value : reader.value?.extraction
  const stored = reader.value?.extractionCoverage
  return source?.report ? {
    ...source.report,
    completedPages: source.completedPages ?? source.report.completedPages,
    limitReasons: source.limitReasons ?? source.report.limitReasons ?? stored?.limitReasons,
  } : stored
})
// A newer report is authoritative even when it no longer contains a pending scan.
const readerPendingScan = computed(() => pendingScanCoverage(readerExtraction.value ?? reader.value))
const readerActionCoverage = computed(() => readerPendingScan.value || readerCoverage.value)
const readerCanContinue = computed(() => canContinueExtraction(reader.value, readerActionCoverage.value))
const readerBodyLimited = computed(() => extractionBodyLimited(reader.value, readerActionCoverage.value))
const readerPendingScanProgress = computed(() => extractionPageProgress({
  status: 'partial', pendingUpgrade: !!readerPendingScan.value,
  scanProgress: readerExtraction.value?.scanProgress ?? reader.value?.scanProgress,
}))
const reportCoverage = computed(() => reportSnapshot.value?.report ? { ...reportSnapshot.value.report,
  completedPages: reportSnapshot.value.completedPages, limitReasons: reportSnapshot.value.limitReasons,
} : viewingScanCandidate.value ? readerPendingScan.value : readerCoverage.value)
const reportLimitHints = computed(() => extractionLimitHints(reportCoverage.value))
const readerLimitHints = computed(() => extractionLimitHints(readerCoverage.value))
const readerExtractionGuidance = computed(() => extractionGuidance(reader.value, readerCoverage.value))
const formulaReview = ref(null)
const formulaReviewSaving = ref(false)
const formulaReviewError = ref('')
const extractionLoading = ref(false)
const extractionError = ref('')
const extractionCaps = ref(null)
const capsError = ref('')
const blockFilter = ref('indexed')
const blockPage = ref(1)
const readerLocation = ref(null)
const requestedLocation = ref(null)
const locationMessage = ref('')
const imageRegion = ref(null)
const imageOriginalRoot = ref(null)
const imageLoadedSource = ref('')
const imageLoadError = ref('')
let extractionRequest = null
const extractionState = computed(() => extractionStatus(reportExtraction.value))
const reportOcrLabel = computed(() => ({
  'document-ingestion-v5-scan-hq2': '高精度扫描',
  'document-ingestion-v5-scan-hq': '高精度扫描',
  'document-ingestion-v4-structure': '结构化 OCR',
}[reportExtraction.value?.pipelineVersion] || 'OCR'))
const extractionRows = computed(() => extractionBlocks(reportExtraction.value))
const reviewedFormulaBlock = computed(() => {
  const review = formulaReview.value
  if (!review || review.fileId !== String(reader.value?.id) || review.sourceRevision !== reportExtraction.value?.sourceSha256
      || review.artifactRevision && review.artifactRevision !== reportExtraction.value?.artifactRevision
      || review.pendingScan !== viewingScanCandidate.value) return null
  return extractionRows.value.find(block => block.key === review.blockKey && block.latexStatus === 'needs_review'
    && (block.latex || '') === review.expectedLatex) || null
})
const formulaReviewPreview = computed(() => renderExtractedMath(formulaReview.value?.latex))
const formulaReviewImage = computed(() => {
  const source = reportExtraction.value
  const artifact = { fileId: reader.value?.id, sourceSha256: source?.sourceSha256,
    assets: source?.assets || (markdownIsCandidate.value === !!formulaReview.value?.pendingScan ? readerMarkdown.value?.assets : []) || [] }
  return artifact.sourceSha256 === formulaReview.value?.sourceRevision
    ? extractedBlockAssetUrl(reviewedFormulaBlock.value, artifact) : ''
})
const matchingExtractionRows = computed(() => extractionRows.value.filter(block => blockFilter.value === 'all' || (blockFilter.value === 'rejected' ? block.rejected : !block.rejected)))
const visibleExtractionRows = computed(() => matchingExtractionRows.value.slice((blockPage.value - 1) * 20, blockPage.value * 20))
watch([blockFilter, reportExtraction], () => { blockPage.value = 1 })
const rejectedBlockCount = computed(() => extractionRows.value.filter(block => block.rejected).length)
const formulaReviewCount = computed(() => extractionRows.value.filter(block => block.latexStatus === 'needs_review').length)
const mediaPreviouslyProcessed = computed(() => extractionBlocks(readerExtraction.value).some(block => ['recognized', 'restored', 'described'].includes(block.latexStatus) || ['recognized', 'vision_described', 'image_described'].includes(block.quality)))
const hasVisionContent = computed(() => extractionBlocks(readerExtraction.value).some(block => !isManuallyReviewedFormula(block)
  && (block.quality === 'recognized' || block.latexStatus === 'recognized')))
const imageRegionStyle = computed(() => sourceRegionStyle(readerLocation.value))
const ocrCapabilityLabel = computed(() => {
  if (capsError.value) return 'OCR 能力状态暂不可用'
  if (!extractionCaps.value) return '正在检查 OCR 能力…'
  if (!extractionCaps.value.configured) return '基础文字 OCR 未启用'
  return extractionCaps.value.available ? '基础文字 OCR 可用' : '基础文字 OCR 当前不可用'
})
const structureFormats = new Set(['pdf', 'png', 'jpg', 'jpeg', 'tif', 'tiff', 'bmp', 'gif', 'webp'])
const structureSupported = computed(() => supportsPagedExtraction(reader.value))
const structureCapability = computed(() => extractionCaps.value?.structure)
const structureCapabilityLabel = computed(() => {
  if (capsError.value) return '状态暂不可用'
  if (!extractionCaps.value) return '正在检查…'
  if (!structureCapability.value?.configured) return '未启用'
  return structureCapability.value.available ? '可用' : '暂不可用'
})
/**
 * 排版还原后的正文（{ pages:[{page,columns,blocks}] }）。
 *
 * 与 readerText 的关系：readerText 是**检索层那份纯文本**（PDF 两栏会逐行交错），
 * 只有排版还原不了（旧版 Word、其他格式、扫描件或解析失败）时才拿它兜底显示。
 * 懒加载：默认打开的是「原文」，切到「抽取正文」才去拉，PDF 解析那 1 秒不该让开卷就等。
 */
const readerLayout = ref(null)
const layoutLoading = ref(false)
let layoutRequest = null
let readerSeq = 0

async function ensureMarkdown(force = false) {
  const id = reader.value?.id
  if (!id || !force && readerMarkdown.value) return readerMarkdown.value
  if (!force && markdownLoading.value) return markdownRequest
  const seq = readerSeq
  const requestSeq = ++markdownLoadSeq
  const version = markdownVersion.value
  const current = () => seq === readerSeq && requestSeq === markdownLoadSeq && reader.value?.id === id && markdownVersion.value === version
  markdownLoading.value = true
  markdownError.value = ''
  markdownRequest = (async () => {
    try {
      const result = await fileApi.markdown(id, version)
      if (!current()) return null
      readerMarkdown.value = result
      if (Array.isArray(result?.pages)) readerLayout.value = {
        status: 'ok', chars: result.markdown?.length || 0, pageCount: result.report?.totalPages || result.pages.length,
        pages: result.pages.map(page => ({ ...page, blocks: (page.blocks || []).map(block => ({ ...block, text: block.cleanText || block.rawText || '' })) })),
      }
      if (result.selectedVersion) extractionReportMode.value = result.selectedVersion
      return readerMarkdown.value
    } catch (error) {
      if (current()) markdownError.value = error?.message || '结构化 Markdown 加载失败，请重试。'
      return null
    } finally { if (current()) markdownLoading.value = false }
  })()
  return markdownRequest
}

async function selectMarkdownVersion(version) {
  if (!['latest', 'published', 'candidate'].includes(version)) return
  markdownVersion.value = version
  ++markdownLoadSeq
  readerMarkdown.value = null
  readerLayout.value = null
  layoutLoading.value = false
  markdownLoading.value = false
  formulaReview.value = null
  reportSnapshot.value = null
  publicationError.value = ''
  return ensureMarkdown(true)
}

async function openMarkdownReview(blockId = '', page = 0) {
  const artifact = readerMarkdown.value
  if (!artifact || markdownLoading.value) return
  const id = reader.value?.id, seq = readerSeq
  await ensureExtraction(true)
  if (seq !== readerSeq || reader.value?.id !== id || artifact !== readerMarkdown.value) return
  extractionReportMode.value = artifact.selectedVersion === 'candidate' ? 'candidate' : 'published'
  reportSnapshot.value = artifact
  readerTab.value = 'extraction'
  blockFilter.value = 'all'
  await nextTick()
  if (seq !== readerSeq || artifact !== readerMarkdown.value) return
  const index = matchingExtractionRows.value.findIndex(block => block.id === blockId && Number(block.page) === Number(page))
  blockPage.value = index < 0 ? 1 : Math.floor(index / 20) + 1
  await nextTick()
  const target = globalThis.document?.querySelector('.extraction-panel .extraction-blocks')
  target?.scrollIntoView({ block: 'start' })
}

async function publishReviewedScan() {
  const artifact = readerMarkdown.value, id = reader.value?.id, seq = readerSeq
  const review = artifact?.retrievalReview
  if (!id || !canPublishSelectedScan.value) return
  try {
    const excluded = Number(review.excludedOtherCount) || 0
    await ElMessageBox.confirm(`将当前扫描结果中的合格内容发布到检索库。${excluded ? `其中 ${excluded} 处待核对内容会被排除，仍在阅读视图保留标记。` : ''}原件和历史记录继续保留。`, '审核并发布到检索库', { confirmButtonText: '确认发布', cancelButtonText: '继续核对', type: 'warning' })
  } catch { return }
  if (seq !== readerSeq || artifact !== readerMarkdown.value || !canPublishSelectedScan.value) return
  publishingScan.value = true
  publicationError.value = ''
  try {
    await fileApi.publishScan(id, { sourceRevision: review.candidateSourceRevision, artifactRevision: review.candidateArtifactRevision })
    if (seq !== readerSeq || reader.value?.id !== id) return
    ElMessage.success('已审核发布，检索索引由后台维护更新')
    await Promise.allSettled([refreshReaderArtifacts(), load()])
  } catch (error) {
    if (seq === readerSeq) publicationError.value = error?.message || '发布未完成，请刷新结果后重试。'
  } finally { publishingScan.value = false }
}

async function copyMarkdown() {
  if (!readerMarkdown.value?.markdown || markdownMissing.value || markdownLoading.value) return
  try { await navigator.clipboard.writeText(readerMarkdown.value.markdown); ElMessage.success('已复制保存的 Markdown') }
  catch { markdownMode.value = 'source'; ElMessage.error('复制失败，可在源码中手动选择。') }
}

async function downloadMarkdown(bundle = false) {
  if (!reader.value || !readerMarkdown.value || markdownMissing.value || markdownDownloading.value || markdownLoading.value) return
  const row = reader.value
  const artifact = readerMarkdown.value, seq = readerSeq
  markdownDownloading.value = true
  try {
    const response = await fileApi.downloadMarkdown(row.id, bundle, { version: artifact.selectedVersion || markdownVersion.value,
      sourceRevision: artifact.sourceSha256, artifactRevision: artifact.artifactRevision })
    if (seq !== readerSeq || artifact !== readerMarkdown.value) return
    const base = String(row.originName || 'document').replace(/\.[^.]+$/, '')
    saveBlob(response.data, `${base}${bundle ? '-markdown.zip' : '.md'}`)
  } finally { markdownDownloading.value = false }
}

async function refreshReaderArtifacts({ clearLocation = false } = {}) {
  const id = reader.value?.id
  if (!id) return
  const seq = ++readerSeq
  if (clearLocation) { readerLocation.value = null; requestedLocation.value = null; locationMessage.value = '' }
  readerExtraction.value = null
  reportSnapshot.value = null
  readerMarkdown.value = null
  readerLayout.value = null
  extractionLoading.value = false
  markdownLoading.value = false
  layoutLoading.value = false
  const results = await Promise.allSettled([ensureExtraction(true), ensureMarkdown(true), ensureLayout(), fileApi.text(id)])
  if (seq !== readerSeq || String(reader.value?.id) !== String(id)) return
  const text = results[3]
  if (text.status === 'fulfilled') {
    readerText.value = text.value.text || ''
    readerStatus.value = text.value.textStatus || ''
    readerError.value = text.value.textError || ''
  }
}

async function onFormulaPersisted(event) {
  if (String(event?.fileId) !== String(reader.value?.id)) return
  await refreshReaderArtifacts()
  await load()
}

async function loadExtractionCaps() {
  capsError.value = ''
  try { extractionCaps.value = await fileApi.extractionCaps() }
  catch (error) { capsError.value = error?.message || '无法读取 OCR 能力，请稍后重试。' }
}

async function applySourceLocation(location) {
  requestedLocation.value = location
  const result = resolveSourceLocation(location, readerExtraction.value)
  readerLocation.value = result.location
  locationMessage.value = result.message
  if (result.location?.blockId && !result.location.rect && !result.message) {
    const id = reader.value?.id, seq = readerSeq
    readerTab.value = 'text'
    markdownMode.value = 'blocks'
    if (markdownVersion.value !== 'published') await selectMarkdownVersion('published')
    await Promise.all([ensureLayout(), ensureMarkdown()])
    await nextTick()
    if (seq !== readerSeq || reader.value?.id !== id) return
    const current = resolveSourceLocation(requestedLocation.value, readerExtraction.value)
    if (current.location?.blockId && !current.message) blockReader.value?.focusBlock?.(current.location.blockId, current.location.page)
  }
}

async function ensureExtraction(force = false) {
  const id = reader.value?.id
  if (!id || !force && readerExtraction.value) return readerExtraction.value
  if (!force && extractionLoading.value) return extractionRequest
  const seq = readerSeq
  extractionLoading.value = true
  extractionError.value = ''
  extractionRequest = (async () => {
    try {
      const result = await fileApi.extraction(id)
      if (seq !== readerSeq || reader.value?.id !== id) return null
      readerExtraction.value = result
      if (requestedLocation.value) await applySourceLocation(requestedLocation.value)
      return result
    } catch (error) {
      if (seq === readerSeq) {
        extractionError.value = error?.message || '报告加载失败，请重试。'
        if (requestedLocation.value?.rect) locationMessage.value = '来源报告加载失败，已定位原文页且未高亮。重新加载报告后可核验区域。'
      }
      return null
    } finally { if (seq === readerSeq) extractionLoading.value = false }
  })()
  return extractionRequest
}

/** 有没有可用的排版还原结果（决定「抽取正文」是文档视图还是分段兜底视图） */
const layoutUsable = computed(() =>
  readerLayout.value?.status === 'ok' && (readerLayout.value.pages?.length || 0) > 0)

async function ensureLayout() {
  const id = reader.value?.id
  if (!id || readerLayout.value) return readerLayout.value
  if (layoutLoading.value) return layoutRequest
  const seq = readerSeq
  const artifact = await ensureMarkdown()
  if (seq !== readerSeq || reader.value?.id !== id || !artifact || artifact !== readerMarkdown.value) return null
  if (readerLayout.value) return readerLayout.value
  // Versioned artifacts own their layout; a missing page list must never borrow published blocks.
  if (artifact?.selectedVersion) return null
  if (!['pdf', 'word', 'markdown', 'image'].includes(docKind.value)) {
    readerLayout.value = { status: 'text', pages: [], error: '当前页显示用于检索的抽取文字' }
    return
  }
  const requestVersion = markdownLoadSeq
  layoutLoading.value = true
  layoutRequest = (async () => {
    try {
      const layout = await fileApi.layout(id)
      if (seq === readerSeq && requestVersion === markdownLoadSeq && reader.value?.id === id) readerLayout.value = layout
    } catch (e) {
      if (seq === readerSeq && requestVersion === markdownLoadSeq && reader.value?.id === id) {
        readerLayout.value = { status: 'failed', error: e?.message || '', pages: [], chars: 0, pageCount: 0 }
      }
    } finally { if (seq === readerSeq && requestVersion === markdownLoadSeq) layoutLoading.value = false }
  })()
  return layoutRequest
}

/** 切换原文 / 抽取正文：切到正文时顺带把排版还原拉回来 */
function switchReaderTab(tab) {
  readerTab.value = tab
  if (tab === 'text') { ensureLayout(); ensureMarkdown() }
  if (tab === 'extraction') ensureExtraction()
}

async function openOriginalPage(page) {
  requestedLocation.value = null
  readerLocation.value = { page: Number(page), rect: null }
  locationMessage.value = ''
  readerTab.value = 'doc'
  await nextTick()
  pdfReader.value?.jump(page)
}

async function openExtractionBlock(block) {
  const source = readerTab.value === 'text' ? markdownArtifact.value : reportExtraction.value
  const location = normalizeSourceLocation({ ...block, blockId: block.id, sourceRevision: source?.sourceSha256, pipelineVersion: source?.pipelineVersion })
  if (!location) return
  if (source?.selectedVersion === 'candidate' || readerTab.value !== 'text' && viewingScanCandidate.value) {
    const resolved = resolveSourceLocation(location, source)
    requestedLocation.value = null
    readerLocation.value = resolved.location
    locationMessage.value = resolved.message
  } else await applySourceLocation(location)
  if (!location.rect && location.blockId) return
  readerTab.value = 'doc'
  await nextTick()
  if (docKind.value === 'pdf') pdfReader.value?.focusSource?.()
  else if (docKind.value === 'image') {
    const target = imageRegion.value || imageOriginalRoot.value
    target?.scrollIntoView({ block: 'center', inline: 'nearest' })
    target?.focus({ preventScroll: true })
  }
}

async function reviewExtractionBlock(block) {
  const id = reader.value?.id, seq = readerSeq
  const version = viewingScanCandidate.value ? 'candidate' : 'published'
  readerTab.value = 'text'
  markdownMode.value = 'blocks'
  if (readerMarkdown.value?.selectedVersion !== version) await selectMarkdownVersion(version)
  await Promise.all([ensureLayout(), ensureMarkdown()])
  await nextTick()
  if (seq !== readerSeq || reader.value?.id !== id) return
  blockReader.value?.focusBlock?.(block.id, block.page)
}

function beginFormulaReview(block) {
  const sourceRevision = reportExtraction.value?.sourceSha256
  if (!reader.value || !block.id || block.latexStatus !== 'needs_review' || !/^[a-f\d]{64}$/i.test(sourceRevision || '') || formulaReviewSaving.value) return
  if (reviewedFormulaBlock.value?.key === block.key) return
  formulaReview.value = {
    blockKey: block.key, fileId: String(reader.value.id), sourceRevision, pendingScan: viewingScanCandidate.value,
    artifactRevision: reportExtraction.value?.artifactRevision,
    expectedLatex: block.latex || '', latex: block.latex || '',
  }
  formulaReviewError.value = ''
  if (!viewingScanCandidate.value) ensureMarkdown()
}

async function confirmFormulaReview() {
  const review = formulaReview.value
  const block = reviewedFormulaBlock.value
  const preview = formulaReviewPreview.value
  if (!review || !block || !preview.ok || formulaReviewSaving.value || isStructuring(reader.value) || isReextracting(reader.value)) return
  formulaReviewSaving.value = true
  formulaReviewError.value = ''
  try {
    await fileApi.confirmStructureFormula(review.fileId, {
      blockId: block.id, sourceRevision: review.sourceRevision,
      expectedLatex: review.expectedLatex, latex: preview.latex, pendingScan: review.pendingScan,
      ...(review.artifactRevision ? { artifactRevision: review.artifactRevision } : {}),
    })
    if (formulaReview.value === review) formulaReview.value = null
    ElMessage.success('公式已确认')
    await Promise.allSettled([review.fileId === String(reader.value?.id) ? refreshReaderArtifacts() : null, load()])
  } catch (error) {
    if (formulaReview.value === review) formulaReviewError.value = error?.message || '公式确认失败，请重新加载报告后重试。'
    else ElMessage.error(error?.message || '公式确认失败。')
  } finally { formulaReviewSaving.value = false }
}

const docKind = computed(() => documentKind(reader.value))
const imageOriginalSource = computed(() => {
  if (!reader.value) return ''
  const extraction = reportExtraction.value
  const validRevision = normalizeSourceLocation({ page: 1, sourceRevision: extraction?.sourceSha256 })?.sourceRevision
  if (validRevision && extraction.pages?.length) {
    const page = Number(readerLocation.value?.page) || 1
    return fileApi.extractionPageImageUrl(reader.value.id, extraction.pages.some(item => Number(item.page) === page) ? page : 1, validRevision)
  }
  return fileApi.rawUrl(reader.value.id)
})
const readerSourceHref = computed(() => !reader.value ? '' : ['pdf', 'word', 'markdown', 'image'].includes(docKind.value)
  ? `/files?read=${encodeURIComponent(reader.value.id)}${sourceLocationQuery({ sourceLocations: [requestedLocation.value] })}`
  : fileApi.rawUrl(reader.value.id))
watch(imageOriginalSource, () => { imageLoadedSource.value = ''; imageLoadError.value = '' }, { flush: 'sync' })
async function onImageLoad(event) {
  const source = event.target.getAttribute('src')
  if (new URL(source, window.location.href).href === event.target.currentSrc) {
    imageLoadedSource.value = source
    imageLoadError.value = ''
    await nextTick()
    if (document.activeElement === imageOriginalRoot.value) imageRegion.value?.scrollIntoView({ block: 'center', inline: 'nearest' })
  }
}
const transCaps = ref({ maxChars: 4000, profile: '', model: '' })

/**
 * 把正文切成"可翻译的小段"。
 *
 * 两级策略，因为正文来源不同：
 *   ① Markdown/文本类有空行 → 直接按空行切，语义上就是自然段；
 *   ② **PDF 抽出来的正文没有空行**（PDFBox 每行一个 \n），按空行切会得到一整段几万字，
 *      超过单段上限后译按钮直接不可点（实测：8.8 万字的论文变成"1 段"，点了没反应）。
 *      这种情况按行累积到约 1500 字切一段 —— 只是**物理切分**，不是真实段落划分，
 *      但对"逐段翻译"来说够用。
 */
function splitParas(text) {
  const t = text || ''
  if (!t.trim()) return []
  const byBlank = t.split(/\n\s*\n/).map((x) => x.trim()).filter((x) => x.length > 0)
  if (byBlank.length > 1) return byBlank
  const out = []
  let buf = ''
  for (const line of t.split('\n')) {
    if (buf && (buf.length + line.length) > 1500) {
      out.push(buf.trim())
      buf = ''
    }
    buf += line + '\n'
  }
  if (buf.trim()) out.push(buf.trim())
  return out.filter((x) => x.length > 0)
}

const paragraphs = computed(() =>
  splitParas(readerMarkdown.value?.markdown ?? readerText.value).map((p, i) => ({ key: i, text: p })))

async function openReader(row, location = null) {
  if (isExplorerFolder(row)) return
  readRoute.cancel()
  const seq = ++readerSeq
  reader.value = row
  extractionReportMode.value = 'published'
  reportSnapshot.value = null
  formulaReview.value = null
  formulaReviewError.value = ''
  readerTab.value = 'doc'
  readerText.value = ''
  readerMarkdown.value = null
  markdownVersion.value = location ? 'published' : 'latest'
  ++markdownLoadSeq
  publicationError.value = ''
  markdownLoading.value = false
  markdownRequest = null
  markdownError.value = ''
  markdownMode.value = 'preview'
  readerStatus.value = ''
  readerError.value = ''
  readerLayout.value = null
  layoutLoading.value = false
  layoutRequest = null
  transCaps.value = { maxChars: 4000, profile: '', model: '' }
  readerLoading.value = true
  readerExtraction.value = null
  extractionLoading.value = false
  extractionRequest = null
  extractionError.value = ''
  blockFilter.value = 'indexed'
  readerLocation.value = location ? { ...location, rect: null } : null
  requestedLocation.value = location
  locationMessage.value = ''
  const linkedMedia = extractionTasksFromRoute(route.query).media
  if (linkedMedia && String(linkedMedia.fileId) === String(row.id)) switchReaderTab('text')
  loadExtractionCaps()
  ensureExtraction()
  try {
    fileApi.translateCaps().then((c) => {
      if (seq === readerSeq) transCaps.value = c || {}
    }).catch(() => {})
    const d = await fileApi.text(row.id)
    if (seq !== readerSeq) return
    readerText.value = d.text || ''
    readerStatus.value = d.textStatus || ''
    readerError.value = d.textError || ''
  } catch (e) {
    /* 拦截器已提示 */
  } finally {
    if (seq === readerSeq) readerLoading.value = false
  }
}

function closeReader() {
  readRoute.cancel()
  ++readerSeq
  reader.value = null
  formulaReview.value = null
  formulaReviewError.value = ''
}

function openExtractionReport(row) {
  openReader(row)
  switchReaderTab('extraction')
}

async function copyReaderText() {
  try {
    await navigator.clipboard.writeText(readerMarkdown.value?.markdown ?? readerText.value ?? '')
    ElMessage.success('已复制正文')
  } catch (e) {
    ElMessage.error('复制失败，请手动选择')
  }
}

const skippedTextHint = '此前因文件大小限制跳过，请在「⋯」菜单中点击「重抽正文」。'

function skippedHint(error) {
  return error && error !== '文件超过 20MB，未抽取正文' ? error : skippedTextHint
}

/** 抽不出正文时的人话解释（不同状态的应对方式不同） */
function statusHint() {
  const s = readerStatus.value
  if (s === 'ok') return ''
  if (s === 'empty') return '这份文件抽取出来是空的 —— 扫描版 PDF / 纯图片类常见这种，需要 OCR 或手工补「说明」。'
  if (s === 'unsupported') return '这个格式不支持抽正文 —— 仍可点「原文」阅读，检索靠手填「说明」。'
  if (s === 'skipped') return skippedHint(readerError.value)
  if (s === 'failed') return '抽取失败：' + (readerError.value || '未知原因')
  if (s === 'pending') return '还在抽取中，稍后重新打开即可。'
  return '未抽取正文。'
}
async function onDownload(row) {
  if (isExplorerFolder(row)) return
  const resp = await fileApi.download(row.id)
  saveBlob(resp.data, row.originName)
  ElMessage.success('已开始下载')
}

async function onDelete(row) {
  if (isExplorerFolder(row)) return
  await ElMessageBox.confirm(`确定删除资料「${row.originName}」吗？删除后不可恢复。`, '提示', { type: 'warning' })
  await fileApi.remove(row.id)
  ElMessage.success('已删除')
  await refreshCollection()
}

function coveredPages(coverage) {
  return coverage?.completedPages
}

function hasMissingExtractionPages(coverage) {
  const done = coveredPages(coverage)
  return Number.isInteger(done) && done >= 0 && Number.isInteger(coverage?.totalPages) && done < coverage.totalPages
}

function supportsPagedExtraction(row) {
  return structureFormats.has(String(row?.ext || '').replace(/^\./, '').toLowerCase())
}

function isPdfExtraction(row) {
  return String(row?.ext || '').replace(/^\./, '').toLowerCase() === 'pdf'
}

function extractionBodyLimited(row, coverage) {
  return hasExtractionLimit(coverage, 'document_capacity')
    || hasExtractionLimit(coverage, 'page_pixels') && !isPdfExtraction(row) && coverage.totalPages === 1
}

function canContinueExtraction(row, coverage = pendingScanCoverage(row) || row?.extractionCoverage) {
  return supportsPagedExtraction(row) && hasMissingExtractionPages(coverage) && !extractionBodyLimited(row, coverage)
}

function extractionActionLabel(row, coverage = pendingScanCoverage(row) || row?.extractionCoverage) {
  const scan = supportsPagedExtraction(row) && (structureCapability.value?.configured || row?.pendingUpgrade)
  return canContinueExtraction(row, coverage)
    ? (scan ? '继续扫描' : '继续抽取正文')
    : (scan ? '重新扫描' : '重新抽取正文')
}

function isPartialExtraction(coverage) {
  return coverage?.partial === true || coverage?.truncated === true || hasMissingExtractionPages(coverage)
}

function extractionGuidance(row, coverage) {
  if (hasExtractionLimit(coverage, 'document_capacity')) return '可按章节拆分资料后再抽取。'
  if (hasExtractionLimit(coverage, 'page_pixels') && !isPdfExtraction(row)) {
    return canContinueExtraction(row, coverage)
      ? '可继续抽取其他页，超限页可缩小或分割原图后再抽取。'
      : '可缩小或分割原图后再抽取。'
  }
  if (canContinueExtraction(row, coverage)) {
    return hasExtractionLimit(coverage, 'page_pixels')
      ? '可继续抽取，系统会按页尺寸调整渲染。'
      : '可继续抽取剩余页。'
  }
  if (hasExtractionLimit(coverage, 'media_limit')) return '可继续处理公式与图片。'
  return ''
}

function textHint(row) {
  const progress = rowPageProgress(row)
  if (progress) return [progress.pages, progress.detail].filter(Boolean).join(' · ')
  if (row.textStatus === 'skipped') return skippedHint(row.textError)
  const coverage = row.extractionCoverage
  const scan = pendingScanCoverage(row)
  if (scan) {
    const candidate = extractionPageProgress({ status: 'partial', pendingUpgrade: true, scanProgress: row.scanProgress })
    const published = Number.isInteger(coverage?.completedPages) && coverage.totalPages > 0
      ? `原有正文可用 ${NUM.format(coverage.completedPages)} / ${NUM.format(coverage.totalPages)} 页。` : '原有可用正文仍保留。'
    return [candidate.pages, published, canContinueExtraction(row) ? '可继续扫描，新结果通过检查后才替换。' : '新扫描结果待核对，尚未替换原有正文。'].filter(Boolean).join(' ')
  }
  if (row.textStatus !== 'ok' || coverage && !isPartialExtraction(coverage)) return row.textError
  const count = `已收录 ${NUM.format(row.textChars || 0)} 字。`
  const pages = hasMissingExtractionPages(coverage)
    ? `可用 ${NUM.format(coveredPages(coverage))} / ${NUM.format(coverage.totalPages)} 页。`
    : ''
  const [primary] = extractionLimitHints(coverage)
  const detail = primary ? `${primary.text}。` : coverage ? '部分内容待核对。' : '覆盖范围待核对。'
  const next = extractionGuidance(row, coverage) || '请查看抽取报告。'
  return count + pages + detail + (row.textError || '') + next
}

/** 正文抽取状态 → 徽标文案（这几档含义不同，不能只显示"成功/失败"） */
function textLabel(row) {
  if (!row) return ''
  if (isReextracting(row) || isStructuring(row)) {
    const progress = rowPageProgress(row)
    return progress?.pages || '正在抽取正文'
  }
  const scan = pendingScanCoverage(row)
  if (scan) return hasMissingExtractionPages(scan)
    ? `扫描待继续 ${NUM.format(scan.completedPages)}/${NUM.format(scan.totalPages)} 页`
    : '扫描待核对'
  switch (row.textStatus) {
    case 'ok': {
      const coverage = row.extractionCoverage
      if (hasMissingExtractionPages(coverage)) return `部分抽取 ${NUM.format(coveredPages(coverage))}/${NUM.format(coverage.totalPages)} 页`
      if (isPartialExtraction(coverage)) return '部分抽取'
      return `${coverage ? '已抽' : '已收录'} ${NUM.format(row.textChars || 0)} 字`
    }
    case 'empty':
      return '无文字'
    case 'unsupported':
      return '不支持'
    case 'skipped':
      return skippedHint(row.textError) === skippedTextHint ? '待重抽' : '已跳过'
    case 'failed':
      return '抽取失败'
    default:
      return '待抽取'
  }
}

function textTagType(row) {
  if (isReextracting(row) || isStructuring(row)) return 'info'
  if (pendingScanCoverage(row)) return 'warning'
  if (row.textStatus === 'ok') {
    if (isPartialExtraction(row.extractionCoverage)) return 'warning'
    return row.extractionCoverage ? 'success' : 'info'
  }
  if (row.textStatus === 'failed') return 'danger'
  if (row.textStatus === 'empty' || row.textStatus === 'unsupported' || row.textStatus === 'skipped') return 'info'
  return 'warning'
}

/** 手填说明：抽不出正文的资料（扫描件/图片/压缩包）就靠它参与检索 */
async function onEditSummary(row) {
  if (isExplorerFolder(row)) return
  const { value } = await ElMessageBox.prompt(
    '写几句说明。抽不出正文的资料（扫描版 PDF / 图片 / 压缩包）就靠它进知识库检索。',
    '资料说明',
    {
      inputValue: row.summary || '',
      inputType: 'textarea',
      inputPlaceholder: '这份资料讲了什么、要点是什么…',
      confirmButtonText: '保存',
    },
  )
  await fileApi.updateSummary(row.id, value || '')
  ElMessage.success('已保存说明')
  load()
}

/** 改文件名：只改基名（扩展名不许改 —— 抽正文按扩展名选解析器，改了会解析错） */
async function onRename(row) {
  if (isExplorerFolder(row)) return
  const ext = row.ext ? `.${row.ext}` : ''
  const base = ext && String(row.originName || '').toLowerCase().endsWith(ext.toLowerCase())
    ? row.originName.slice(0, -ext.length)
    : (row.originName || '')
  const { value } = await ElMessageBox.prompt(
    `改个名字（扩展名 ${ext || '（无）'} 不能改：抽正文是按扩展名选解析器的）。`,
    '重命名资料',
    {
      inputValue: base,
      inputPlaceholder: '新的文件名（不含扩展名）',
      confirmButtonText: '保存',
      inputValidator: (v) => (String(v || '').trim() ? true : '文件名不能为空'),
    },
  )
  const next = `${String(value).trim()}${ext}`
  if (next === row.originName) return
  await fileApi.rename(row.id, next)
  ElMessage.success(`已改名为「${next}」`)
  load()
}

function isReextracting(row) {
  if (!row || isExplorerFolder(row)) return false
  const id = String(row.id)
  return id === extractionStartingId.value || id === extractionRefreshingId.value
    || !!extractionJob.value && String(extractionJob.value.fileId) === id && !extractionTaskFinished(extractionJob.value)
}

function isStructuring(row) {
  if (!row || isExplorerFolder(row)) return false
  const id = String(row.id)
  return id === structureStartingId.value || !!structureJob.value && id === String(structureJob.value.fileId) && !extractionTaskFinished(structureJob.value)
}

function isProcessingMedia(row) {
  if (!row || isExplorerFolder(row)) return false
  const id = String(row.id)
  return id === mediaStartingId.value || !!mediaJob.value && String(mediaJob.value.fileId) === id && !extractionTaskFinished(mediaJob.value)
}

function rowPageProgress(row) {
  if (isReextracting(row)) return extractionPageProgress(extractionJob.value)
  if (isStructuring(row)) return extractionPageProgress(structureJob.value)
  return null
}

function canStartExtraction(row) {
  return !extractionBusy.value && !isStructuring(row) && !isProcessingMedia(row)
    && !(formulaReviewSaving.value && String(reader.value?.id) === String(row?.id))
}

async function onReextract(row) {
  if (!row || isExplorerFolder(row) || !canStartExtraction(row)) return
  const id = String(row.id)
  const coverage = String(reader.value?.id) === id ? readerActionCoverage.value : pendingScanCoverage(row) || row.extractionCoverage
  if (extractionBodyLimited(row, coverage)) return
  const resume = canContinueExtraction(row, coverage)
  extractionStartingId.value = id
  extractionJob.value = null
  extractionTaskError.value = ''
  try {
    const job = { resume, ...await fileApi.startExtraction(row.id, { resume }), fileId: id }
    rememberExtractionTask('extraction', job)
    extractionMonitor.watch(job)
  } catch (error) { ElMessage.error(error?.message || '正文抽取任务未能启动，请重试。') }
  finally { extractionStartingId.value = '' }
}

/** 改分类：资料按分类进知识图谱，所以"归类"就是把资料接进图的那一步 */
async function onChangeCategory(row, categoryId) {
  if (isExplorerFolder(row)) return
  await fileApi.updateCategory(row.id, categoryId || null)
  ElMessage.success(categoryId ? '已归类' : '已取消分类')
  load()
}

const mediaMonitor = createExtractionTaskMonitor({
  load: id => fileApi.mediaJob(id),
  onState(job) { mediaJob.value = job; rememberExtractionTask('media', job) },
  async onFinished(job) {
    if (job.status === 'failed') return
    try {
      if (String(reader.value?.id) === String(job.fileId)) await refreshReaderArtifacts()
      await load()
    } catch (error) { mediaTaskError.value = `任务已结束，结果刷新失败：${error?.message || '请重试。'}` }
  },
  onError(error) { mediaTaskError.value = `状态查询暂停：${error?.message || '请重试。'} 后台任务仍会继续。` },
})
const migrationMonitor = createExtractionTaskMonitor({
  load: id => fileApi.markdownMigration(id),
  onState(job) { migrationJob.value = job; rememberExtractionTask('migration', job) },
  async onFinished(job) {
    if (job.status === 'failed') return
    try { if (reader.value) await refreshReaderArtifacts(); await load() }
    catch (error) { migrationTaskError.value = `任务已结束，结果刷新失败：${error?.message || '请重试。'}` }
  },
  onError(error) { migrationTaskError.value = `状态查询暂停：${error?.message || '请重试。'} 后台任务仍会继续。` },
})
const structureMonitor = createExtractionTaskMonitor({
  load: id => fileApi.structureJob(id),
  onState(job) { structureJob.value = job; rememberExtractionTask('structure', job) },
  async onFinished(job) {
    if (job.status === 'failed') return
    try {
      if (String(reader.value?.id) === String(job.fileId)) await refreshReaderArtifacts({ clearLocation: !job.resume })
      await load()
    } catch (error) {
      if (structureJob.value?.id === job.id) structureTaskError.value = { fileId: String(job.fileId), message: `任务已结束，结果刷新失败：${error?.message || '请重试。'}` }
    }
  },
  onError(error, job) {
    structureTaskError.value = { fileId: String(job.fileId), message: `状态查询暂停：${error?.message || '请重试。'} 后台任务仍会继续。` }
  },
})

const extractionMonitor = createExtractionTaskMonitor({
  load: id => fileApi.extractionJob(id),
  onState(job) { extractionJob.value = job; rememberExtractionTask('extraction', job) },
  async onFinished(job) {
    if (job.status === 'failed') return
    const id = String(job.fileId)
    extractionRefreshingId.value = id
    try {
      await Promise.all([
        load(),
        String(reader.value?.id) === id ? refreshReaderArtifacts({ clearLocation: !job.resume }) : null,
      ])
    } catch (error) { extractionTaskError.value = `任务已结束，结果刷新失败：${error?.message || '请重试。'}` }
    finally { extractionRefreshingId.value = '' }
  },
  onError(error) { extractionTaskError.value = `状态查询暂停：${error?.message || '请重试。'} 后台任务仍会继续。` },
})

async function processReaderMedia() {
  if (readerTab.value === 'text' && markdownIsCandidate.value || readerTab.value === 'extraction' && viewingScanCandidate.value) {
    ElMessage.error('新扫描公式请在待核对标记中确认；图片处理仅适用于已发布检索版本。')
    return
  }
  if (!reader.value || mediaBusy.value || isReextracting(reader.value) || isStructuring(reader.value)) return
  const id = reader.value.id
  mediaStartingId.value = String(id)
  mediaTaskError.value = ''
  try {
    const job = await fileApi.processMedia(id, false)
    rememberExtractionTask('media', { ...job, fileId: String(id) })
    mediaMonitor.watch({ ...job, fileId: String(id) })
  } catch (error) { mediaTaskError.value = error?.message || '媒体任务未能启动，请重试。' }
  finally { mediaStartingId.value = '' }
}

async function startMarkdownMigration() {
  if (migrationBusy.value) return
  migrationStarting.value = true
  migrationTaskError.value = ''
  try {
    const job = await fileApi.migrateMarkdown()
    rememberExtractionTask('migration', job)
    migrationMonitor.watch(job)
  }
  catch (error) { migrationTaskError.value = error?.message || '转换任务未能启动，请重试。' }
  finally { migrationStarting.value = false }
}

async function processReaderStructure() {
  if (!reader.value || !structureSupported.value || capsError.value || !structureCapability.value?.available
      || readerBodyLimited.value || structureBusy.value || formulaReviewSaving.value || isReextracting(reader.value) || isProcessingMedia(reader.value)) return
  const id = String(reader.value.id)
  const resume = readerCanContinue.value
  structureStartingId.value = id
  structureJob.value = null
  structureTaskError.value = null
  try {
    const job = { resume, ...await fileApi.processStructure(id, { resume }), fileId: id }
    rememberExtractionTask('structure', job)
    structureMonitor.watch(job)
  } catch (error) {
    structureTaskError.value = { fileId: id, message: error?.message || '扫描任务未能启动，请重试。' }
  } finally { structureStartingId.value = '' }
}

function resumeDocumentTask(kind) {
  if (kind === 'media' && mediaJob.value) { mediaTaskError.value = ''; mediaMonitor.watch(mediaJob.value) }
  if (kind === 'migration' && migrationJob.value) { migrationTaskError.value = ''; migrationMonitor.watch(migrationJob.value) }
  if (kind === 'structure' && structureJob.value) { structureTaskError.value = null; structureMonitor.watch(structureJob.value) }
  if (kind === 'extraction' && extractionJob.value) { extractionTaskError.value = ''; extractionMonitor.watch(extractionJob.value) }
}

function onMarkdownClick(event) {
  const article = event.target.closest('.structured-markdown')
  const artifact = article?.getAttribute('aria-label') === '新扫描候选正文' ? { ...reportExtraction.value, selectedVersion: 'candidate' } : markdownArtifact.value
  const reviewButton = event.target.closest('button[data-review-block]')
  if (reviewButton && article) {
    if (reviewButton.dataset.sourceRevision !== artifact.sourceSha256) return
    if (artifact.selectedVersion === 'candidate' && article.getAttribute('aria-label') === '新扫描候选正文') {
      blockFilter.value = 'all'
      const index = matchingExtractionRows.value.findIndex(block => block.id === reviewButton.dataset.reviewBlock && block.page === Number(reviewButton.dataset.reviewPage))
      blockPage.value = index < 0 ? 1 : Math.floor(index / 20) + 1
    } else openMarkdownReview(reviewButton.dataset.reviewBlock, reviewButton.dataset.reviewPage)
    return
  }
  const pageButton = event.target.closest('button[data-source-page]')
  if (pageButton && article) {
    const page = Number(pageButton.dataset.sourcePage)
    if (pageButton.dataset.sourceRevision === artifact.sourceSha256 && verifiedSourcePage(page, artifact)) openOriginalPage(page)
    return
  }
  const link = event.target.closest('a[href^="#"]')
  if (!link || !article) return
  event.preventDefault()
  let id
  try { id = decodeURIComponent(link.getAttribute('href').slice(1)) } catch { return }
  const target = Array.from(article.querySelectorAll('[id]')).find(node => node.id === id)
  target?.scrollIntoView({ block: 'start' })
  target?.focus?.({ preventScroll: true })
}

const readRoute = createSourceRouteReader({
  load: id => fileApi.detail(id),
  onOpen: openReader,
  // API errors already use the request interceptor's readable notification.
})
watch(() => [route.query.read, route.query.page, route.query.rect, route.query.sourceRevision, route.query.blockId, route.query.sourcePipeline],
  () => readRoute.open(route.query.read, sourceLocatorFromRoute(route.query)), { immediate: true, flush: 'sync' })

const routeTaskKeys = { media: '', migration: '' }
watch(() => [route.query.read, route.query.mediaJob, route.query.migrationJob], () => {
  const tasks = extractionTasksFromRoute(route.query)
  for (const kind of ['media', 'migration']) {
    const task = tasks[kind]
    const key = task ? `${task.id}:${task.fileId || ''}` : ''
    if (routeTaskKeys[kind] === key) continue
    const previous = routeTaskKeys[kind]
    routeTaskKeys[kind] = key
    const monitor = kind === 'media' ? mediaMonitor : migrationMonitor
    if (task) {
      if (kind === 'media') mediaTaskError.value = ''
      else migrationTaskError.value = ''
      monitor.watch(task)
    } else if (previous) {
      monitor.stop()
      if (kind === 'media') { mediaJob.value = null; mediaTaskError.value = '' }
      else { migrationJob.value = null; migrationTaskError.value = '' }
    }
  }
}, { immediate: true, flush: 'sync' })

onMounted(async () => {
  window.addEventListener('lh-meta-changed', loadCats)
  const tasks = readExtractionTasks()
  const linked = extractionTasksFromRoute(route.query)
  if (tasks.media && !linked.media) mediaMonitor.watch(tasks.media)
  if (tasks.migration && !linked.migration) migrationMonitor.watch(tasks.migration)
  if (tasks.structure) structureMonitor.watch(tasks.structure)
  if (tasks.extraction) extractionMonitor.watch(tasks.extraction)
  await Promise.all([loadCats(), loadFolders()])
  await load()
})

onBeforeUnmount(() => {
  readRoute.dispose()
  mediaMonitor.dispose()
  migrationMonitor.dispose()
  structureMonitor.dispose()
  extractionMonitor.dispose()
  ++readerSeq
  ++listSeq
  ++foldersSeq
  window.removeEventListener('lh-meta-changed', loadCats)
})
</script>

<template>
  <div class="page file-library">
    <div class="toolbar library-header">
      <div class="library-heading">
        <h2 class="page-h2">资料库</h2>
        <span class="tip">按文件夹收纳原文，抽取正文参与知识库检索</span>
      </div>
      <div class="library-header-actions">
      <el-button :loading="migrationStarting" :disabled="migrationBusy" @click="startMarkdownMigration">{{ migrationBusy ? 'Markdown 更新进行中' : '更新已有资料的 MD' }}</el-button>
      <el-select v-model="uploadCategoryId" class="upload-category" aria-label="上传分类" placeholder="上传分类（可选）" clearable>
        <el-option v-for="c in categories" :key="c.id" :label="catLabel(c)" :value="c.id" />
      </el-select>
      <el-button type="primary" :loading="uploading" :title="`上传到：${selectedFolder > 0 ? currentFolderTitle : '未收纳'}`" @click="pickUpload">
        <span class="btn-ico"><svg viewBox="0 0 24 24"><path d="M12 16V4.5M6.5 10 12 4.5 17.5 10M4.5 19.5h15" /></svg></span>上传资料
      </el-button>
      <input ref="uploadInput" type="file" multiple hidden @change="onFileChosen" />
      </div>
    </div>

    <p class="migration-scope">启用高精度扫描后，PDF 与图片默认逐页识别。更新已有资料会转换旧版本；后台任务在离开页面后继续。</p>
    <p v-if="migrationTaskError && !migrationJob" class="extraction-feedback" role="alert">{{ migrationTaskError }}</p>
    <p v-if="structureTaskError && structureTaskError.fileId !== String(structureJob?.fileId)" class="extraction-feedback" role="alert">资料 {{ structureTaskError.fileId }} · {{ structureTaskError.message }}</p>
    <section v-if="documentJobs.length" class="document-tasks" aria-label="资料处理任务">
      <article v-for="task in documentJobs" :key="task.kind" class="document-task" :aria-busy="!task.progress && !extractionTaskFinished(task.job)">
        <div class="document-task-heading"><strong>{{ task.title }}<template v-if="task.job.fileId"> · 资料 {{ task.job.fileId }}</template></strong><span role="status">{{ extractionTaskLabel(task.job) }}</span></div>
        <template v-if="task.progress">
          <p v-if="task.progress.pages || task.progress.detail" role="status">{{ [task.progress.pages, task.progress.detail].filter(Boolean).join(' · ') }}</p>
          <el-progress v-if="task.progress.percentage !== null" :percentage="task.progress.percentage" :status="task.job.status === 'completed' ? 'success' : task.job.status === 'failed' ? 'exception' : task.job.status === 'partial' ? 'warning' : undefined" :aria-label="`${task.title}页数进度`" />
        </template>
        <template v-else>
          <p v-if="task.job.total > 0">已处理 {{ task.job.done ?? 0 }} / {{ task.job.total }}<template v-if="task.job.failed"> · 失败 {{ task.job.failed }}</template><template v-if="task.job.partial"> · 待核对 {{ task.job.partial }}</template></p>
          <p v-if="task.job.stage">{{ task.job.stage }}</p>
          <el-progress v-if="task.job.total > 0" :percentage="Math.min(100, Math.round((task.job.done || 0) / task.job.total * 100))" :status="task.job.status === 'completed' ? 'success' : task.job.status === 'failed' ? 'exception' : task.job.status === 'partial' ? 'warning' : undefined" />
          <p v-if="task.job.status === 'partial'" class="extraction-hint">部分资料或媒体仍待处理，请核对下方结果；已有成功结果会保留。</p>
        </template>
        <p v-if="task.job.error || task.error" class="task-error" role="alert">{{ task.error || task.job.error }}</p>
        <p v-if="task.job.index?.configured === false" class="extraction-hint">语义检索索引尚未配置。</p>
        <p v-if="task.job.index?.error || task.job.index?.message" class="extraction-hint">{{ task.job.index.error || task.job.index.message }}</p>
        <el-button v-if="task.error" size="small" @click="resumeDocumentTask(task.kind)">{{ extractionTaskFinished(task.job) ? '刷新结果' : '重新查询状态' }}</el-button>
        <details v-if="task.job.items?.some(item => item.status !== 'completed')"><summary>查看未完成项目</summary><ul><li v-for="item in task.job.items.filter(item => item.status !== 'completed').slice(0, 20)" :key="item.id">资料 {{ item.id }}：{{ extractionTaskLabel(item) }}<template v-if="item.error"> · {{ item.error }}</template></li></ul><p v-if="task.job.items.length > 20">此处最多显示 20 项。</p></details>
      </article>
    </section>

    <el-card class="library-content" shadow="never">
        <FolderExplorerToolbar
          ref="folderToolbar"
          v-model="selectedFolder"
          kind="file"
          :folders="folders"
          :loading="foldersLoading"
          @changed="refreshCollection"
        />
        <div class="collection-head">
          <div class="collection-title">
            <span v-if="visibleFolderCount">{{ visibleFolderCount }} 个文件夹</span>
            <span>{{ NUM.format(pager.total) }} 份资料</span>
          </div>
          <div v-if="selectedRows.length" class="selection-actions">
            <span>已选 {{ selectedRows.length }} 份</span>
            <el-button @click="openMove()">移动到</el-button>
            <el-button text @click="table?.clearSelection()">取消选择</el-button>
          </div>
          <div class="collection-filters">
      <el-input
        v-model="query.kw"
        class="file-search"
        aria-label="搜索资料"
        placeholder="搜索文件名 / 说明 / 正文"
        clearable
        @keyup.enter="search"
        @clear="search"
      >
        <template #append>
          <el-button @click="search">搜索</el-button>
        </template>
      </el-input>
      <el-select v-model="query.categoryId" class="file-category-filter" aria-label="筛选资料分类" placeholder="全部分类" clearable @change="search">
        <el-option v-for="c in categories" :key="c.id" :label="catLabel(c)" :value="c.id" />
      </el-select>
          <el-button v-if="hasFilter" @click="resetFilter">重置</el-button>
          </div>
        </div>
      <el-table
        ref="table"
        class="file-table"
        :class="{ 'with-locations': selectedFolder == null }"
        :data="explorerRows"
        row-key="__explorerKey"
        :row-class-name="({ row }) => isExplorerFolder(row) ? 'explorer-folder-row' : ''"
        v-loading="loading"
        @selection-change="onSelectionChange"
        @row-dblclick="openExplorerRow"
      >
        <el-table-column type="selection" width="44" :selectable="selectableFile" />
        <el-table-column label="名称" min-width="280">
          <template #default="{ row }">
            <button
              v-if="isExplorerFolder(row)"
              type="button"
              class="folder-name"
              :title="`双击或按 Enter 打开：${row.name}`"
              @keydown.enter.stop.prevent="openExplorerRow(row)"
            >
              <svg viewBox="0 0 24 24" aria-hidden="true"><path d="M3 6.5a2 2 0 0 1 2-2h5l2 2h7a2 2 0 0 1 2 2v10a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2z" /></svg>
              <span>{{ row.name }}</span>
            </button>
            <template v-else>
              <span class="fname" :title="row.originName">{{ row.originName }}</span>
              <span v-if="selectedFolder == null" class="folder-location" :title="folderPath(row.folderId)">{{ folderPath(row.folderId) }}</span>
            </template>
          </template>
        </el-table-column>
        <el-table-column label="类型" width="80">
          <template #default="{ row }">
            <span v-if="isExplorerFolder(row)" class="folder-kind">文件夹</span>
            <el-tag v-else size="small" type="info" effect="plain">{{ row.ext || '其他' }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="正文" width="196">
          <template #default="{ row }">
            <span v-if="isExplorerFolder(row)" class="folder-detail">{{ NUM.format(row.itemCount || 0) }} 份资料<span v-if="row.childrenCount"> · {{ row.childrenCount }} 个子文件夹</span></span>
            <template v-else>
              <div class="extraction-row-status">
                <el-tooltip v-if="textHint(row)" :content="textHint(row)" :trigger="['hover', 'focus']" placement="top" popper-class="extraction-tooltip">
                  <el-tag tabindex="0" size="small" :type="textTagType(row)" effect="plain">{{ textLabel(row) }}</el-tag>
                </el-tooltip>
                <el-tag v-else size="small" :type="textTagType(row)" effect="plain">{{ textLabel(row) }}</el-tag>
                <template v-if="isPartialExtraction(row.extractionCoverage)">
                  <el-button v-if="canContinueExtraction(row)" link type="primary" :disabled="!canStartExtraction(row)" @click="onReextract(row)">{{ extractionActionLabel(row) }}</el-button>
                  <el-button v-else link type="primary" @click="openExtractionReport(row)">查看报告</el-button>
                </template>
              </div>
              <div v-if="row.summary" class="fsummary" :title="row.summary">{{ row.summary }}</div>
            </template>
          </template>
        </el-table-column>
        <el-table-column label="分类" width="132">
          <template #default="{ row }">
            <el-select
              v-if="!isExplorerFolder(row)"
              :model-value="row.categoryId || undefined"
              :aria-label="`${row.originName}的分类`"
              placeholder="未分类"
              clearable
              size="small"
              style="width: 100%"
              @change="onChangeCategory(row, $event)"
            >
              <el-option v-for="c in categories" :key="c.id" :label="catLabel(c)" :value="c.id" />
            </el-select>
          </template>
        </el-table-column>
        <el-table-column label="大小" width="88">
          <template #default="{ row }"><span class="num">{{ isExplorerFolder(row) ? '—' : fmtSize(row.size) }}</span></template>
        </el-table-column>
        <el-table-column label="修改 / 上传时间" width="152">
          <template #default="{ row }"><span class="num">{{ time(isExplorerFolder(row) ? row.updatedAt || row.createdAt : row.createdAt) }}</span></template>
        </el-table-column>
        <!--
          操作列：主操作留在行内，次要操作（说明 / 重命名 / 重抽）收进 ⋯。
          原因：这一列是 fixed="right"，宽度就是它在窄屏上**吃掉**的宽度 ——
          五个按钮要 270px，320px 屏上文件名（min-width 240）直接被挤出视野，
          而这一列永远可见。收进 ⋯ 后主操作只剩三个（阅读/下载/删除）。
        -->
        <el-table-column label="操作" width="176" fixed="right">
          <template #default="{ row }">
            <div class="row-actions">
            <el-button v-if="isExplorerFolder(row)" link type="primary" @click="openExplorerRow(row)">打开</el-button>
            <template v-else>
              <el-button link type="primary" @click="openReader(row)">阅读</el-button>
              <el-button link type="primary" @click="onDownload(row)">下载</el-button>
            </template>
            <el-dropdown trigger="click" :disabled="isReextracting(row)" @click.stop @dblclick.stop>
              <el-button link type="primary" :loading="isReextracting(row)" :disabled="isReextracting(row)" :aria-busy="isReextracting(row)" :aria-label="isExplorerFolder(row) ? '文件夹操作' : '资料更多操作'">⋯</el-button>
              <template #dropdown>
                <el-dropdown-menu>
                  <template v-if="isExplorerFolder(row)">
                    <el-dropdown-item @click="manageExplorerFolder('create', row)">新建子文件夹</el-dropdown-item>
                    <el-dropdown-item @click="manageExplorerFolder('rename', row)">重命名</el-dropdown-item>
                    <el-dropdown-item :disabled="!!row.childrenCount" @click="manageExplorerFolder('delete', row)">{{ row.childrenCount ? '含子文件夹，暂不可删除' : '删除文件夹' }}</el-dropdown-item>
                  </template>
                  <template v-else>
                    <el-dropdown-item @click="openMove([row])">移动到文件夹</el-dropdown-item>
                    <el-dropdown-item @click="onEditSummary(row)">说明</el-dropdown-item>
                    <el-dropdown-item @click="onRename(row)">重命名</el-dropdown-item>
                    <el-dropdown-item v-if="canContinueExtraction(row)" :disabled="!canStartExtraction(row)" @click="onReextract(row)">{{ extractionActionLabel(row) }}</el-dropdown-item>
                    <el-dropdown-item v-else-if="isPartialExtraction(row.extractionCoverage)" @click="openExtractionReport(row)">查看抽取报告</el-dropdown-item>
                    <el-dropdown-item v-else-if="row.textStatus !== 'ok'" :disabled="!canStartExtraction(row)" @click="onReextract(row)">{{ isStructuring(row) ? '正在扫描…' : '重抽正文' }}</el-dropdown-item>
                  </template>
                </el-dropdown-menu>
              </template>
            </el-dropdown>
            <el-button v-if="!isExplorerFolder(row)" link type="danger" @click="onDelete(row)">删除</el-button>
            </div>
          </template>
        </el-table-column>
        <template #empty>
          <el-empty :description="emptyMessage" :image-size="80" />
        </template>
      </el-table>

      <!-- 分页：列表一次只渲染一页，而不是把上千行一次性塞进 DOM -->
      <div v-if="pager.total > pager.size" class="fpage">
        <el-pagination
          :current-page="pager.page"
          :page-size="pager.size"
          :page-sizes="[20, 50, 100]"
          :total="pager.total"
          layout="total, sizes, prev, pager, next, jumper"
          background
          @current-change="onPageChange"
          @size-change="onPageSizeChange"
        />
      </div>
    </el-card>
    <FolderMoveDialog
      v-model="moveVisible"
      kind="file"
      :folders="folders"
      :ids="moveIds"
      :label="moveLabel"
      @moved="onMoved"
    />
  </div>

    <!--
      资料阅读器（**全屏**）。
      原来做成 62% 宽的抽屉 —— 实测没法看：PDF 被压在一个 430×280 的小框里，
      里面还有一层滚动条，读论文等于用放大镜。阅读需要的是"整块视野"，所以改成全屏覆盖：
      顶部一行工具（文件名 / 原文·正文切换 / 下载 / 新标签 / 关闭），其余全部给内容。
    -->
    <el-dialog
      :model-value="!!reader"
      fullscreen
      :show-close="true"
      :title="reader ? reader.originName : ''"
      class="reader-dialog"
      destroy-on-close
      @close="closeReader"
    >
      <div v-if="reader" class="reader">
        <!--
          工具条：与下面的 PDF 工具条（PdfReader 的 .reader-toolbar）用**同一套控件样式**。
          之前这里是 el-radio-group + el-button，和自研的 PDF 工具条叠在一起像两个拼盘：
          高度、圆角、字号都不一样；统一之后切页签时控件位置也不跳。
        -->
        <div class="reader-toolbar reader-meta">
          <span class="reader-hint">{{ (reader.ext || '').toUpperCase() }} · {{ fmtSize(reader.size) }}</span>
          <span v-if="readerTab === 'text'" class="reader-hint">
            · {{ markdownLoading ? '正在加载所选版本…' : markdownVersionLabel }}
          </span>
          <span class="reader-sep" />
          <button type="button" class="reader-btn" :class="{ on: readerTab === 'doc' }" :aria-pressed="readerTab === 'doc'" @click="switchReaderTab('doc')">
            原文
          </button>
          <button type="button" class="reader-btn" :class="{ on: readerTab === 'text' }" :aria-pressed="readerTab === 'text'" @click="switchReaderTab('text')">
            结构化 MD
          </button>
          <button type="button" class="reader-btn" :class="{ on: readerTab === 'extraction' }" :aria-pressed="readerTab === 'extraction'" @click="switchReaderTab('extraction')">抽取报告</button>
          <span class="reader-spacer" />
          <button type="button" class="reader-btn" @click="onDownload(reader)">下载</button>
          <a class="reader-btn reader-link" :href="readerSourceHref" target="_blank" rel="noreferrer">新标签打开</a>
        </div>
        <p v-if="locationMessage" class="source-location-message" role="status">{{ locationMessage }}</p>
        <section v-if="readerPageJob" class="reader-extraction-task document-task" aria-label="正文抽取进度">
          <div class="document-task-heading">
            <strong>{{ readerPageTaskKind === 'structure' || readerPageJob.scanMode ? '高精度扫描' : '正文抽取' }}</strong>
            <span role="status">{{ [extractionTaskLabel(readerPageJob), readerPageProgress.pages, readerPageProgress.detail].filter(Boolean).join(' · ') }}</span>
          </div>
          <el-progress v-if="readerPageProgress.percentage !== null" class="reader-extraction-progress" :percentage="readerPageProgress.percentage" :show-text="false" :status="readerPageJob.status === 'completed' ? 'success' : readerPageJob.status === 'failed' ? 'exception' : readerPageJob.status === 'partial' ? 'warning' : undefined" aria-label="正文抽取页数进度" />
          <p v-if="readerPageTaskError || readerPageJob.error" class="task-error" role="alert">{{ readerPageTaskError || readerPageJob.error }}</p>
          <el-button v-if="readerPageTaskError" size="small" @click="resumeDocumentTask(readerPageTaskKind)">{{ extractionTaskFinished(readerPageJob) ? '刷新结果' : '重新查询状态' }}</el-button>
        </section>

        <!-- PDF 保留原版页面；Word / Markdown 从原文件渲染，统一在右侧翻译。 -->
        <div v-show="readerTab === 'doc'" class="reader-body">
          <!-- 自己渲染（pdf.js 画到 canvas）：页码/缩放/翻页/适宽都由我们控制，
               不用浏览器内置查看器 —— 那个工具栏拿不到、样式进不去、一压缩就没法看 -->
          <PdfReader
            ref="pdfReader"
            v-if="docKind === 'pdf'"
            :key="reader.id"
            :file-id="reader.id"
            :src="fileApi.rawUrl(reader.id)"
            :initial="{ readPage: reader.readPage, readScale: reader.readScale, readMode: reader.readMode }"
            :translation-caps="transCaps"
            :source-location="readerLocation"
            class="reader-frame"
          />
          <ReaderTranslation
            v-else-if="docKind === 'word' || docKind === 'markdown'"
            :key="`${reader.id}-original`"
            :file-id="reader.id"
            :source-root="originalSourceRoot"
            :max-chars="transCaps.maxChars"
            :capabilities="transCaps"
            class="reader-frame"
          >
            <div ref="originalSourceRoot" class="reader-original-source">
              <OriginalDocumentView :file-id="reader.id" :filename="reader.originName" />
            </div>
          </ReaderTranslation>
          <div v-else-if="docKind === 'image'" class="reader-image-scroll">
            <div ref="imageOriginalRoot" class="reader-image-frame" tabindex="-1" aria-label="原图页面">
              <img :src="imageOriginalSource" :alt="reader.originName" class="reader-img" @load="onImageLoad" @error="imageLoadError = '原文图像加载失败，可下载原文件查看。'; imageLoadedSource = ''" />
              <div v-if="imageLoadedSource === imageOriginalSource && imageRegionStyle" ref="imageRegion" class="image-source-highlight" :style="imageRegionStyle" tabindex="-1" role="img" aria-label="原图定位区域"></div>
            </div>
            <p v-if="imageLoadError" class="extraction-feedback" role="alert">{{ imageLoadError }} <el-button size="small" @click="onDownload(reader)">下载原文件</el-button></p>
          </div>
          <video v-else-if="docKind === 'video'" :src="fileApi.rawUrl(reader.id)" controls class="reader-media"></video>
          <audio v-else-if="docKind === 'audio'" :src="fileApi.rawUrl(reader.id)" controls></audio>
          <div v-else class="reader-fallback">
            <p class="hint">这种格式浏览器不能直接渲染，请用「下载」或「新标签打开」。</p>
          </div>
        </div>

        <!--
          抽取正文：**优先**用后端排版还原出来的结构（标题/章节/段落/列表，按页回传），
          照原文档排版并渲染 LaTeX 公式；还原不了时退回检索层纯文本分段，
          并保留下面的逐段翻译 —— 两条路都不会让这一页空着。
        -->
        <ReaderTranslation
          v-show="readerTab === 'text'"
          ref="textTranslator"
          :key="`${reader.id}-text`"
          :file-id="reader.id"
          :source-root="textSourceRoot"
          :max-chars="transCaps.maxChars"
          :capabilities="transCaps"
          class="reader-body reader-frame structured-translation"
        >
        <div ref="textSourceRoot" v-loading="markdownLoading || markdownMode === 'blocks' && (layoutLoading || readerLoading)" class="reader-text-wrap">
          <div class="structured-md-toolbar" data-translation-ignore>
            <label class="structured-md-version"><span>阅读版本</span>
              <el-select :model-value="markdownVersion === 'candidate' ? 'latest' : markdownVersion" size="small" aria-label="结构化 MD 阅读版本" :disabled="publishingScan" @change="selectMarkdownVersion">
                <el-option label="最新扫描结果" value="latest" />
                <el-option label="已发布检索版本" value="published" />
              </el-select>
            </label>
            <el-radio-group v-model="markdownMode" size="small" aria-label="结构化 Markdown 视图">
              <el-radio-button value="preview">预览</el-radio-button>
              <el-radio-button value="source">源码</el-radio-button>
              <el-radio-button value="blocks">按块核对</el-radio-button>
            </el-radio-group>
            <div class="structured-md-actions">
              <el-button size="small" :disabled="markdownLoading || !readerMarkdown?.markdown || markdownMissing" @click="copyMarkdown">复制 MD</el-button>
              <el-button size="small" :loading="markdownDownloading" :disabled="markdownLoading || !readerMarkdown || markdownMissing" @click="downloadMarkdown(false)">下载 MD</el-button>
              <el-button size="small" :disabled="markdownLoading || !readerMarkdown || markdownMissing || markdownDownloading" @click="downloadMarkdown(true)">下载图片包</el-button>
              <el-button size="small" :loading="mediaStarting" :title="markdownIsCandidate ? '新扫描公式请点击待核对标记确认；图片处理仅适用于已发布版本' : undefined" :disabled="markdownIsCandidate || markdownLoading || mediaBusy || isReextracting(reader) || isStructuring(reader)" @click="processReaderMedia">{{ mediaPreviouslyProcessed ? '继续处理公式与图片' : '处理公式与图片' }}</el-button>
              <template v-if="!markdownMissing && isPartialExtraction(readerCoverage)">
                <el-button v-if="readerCanContinue" size="small" :disabled="!canStartExtraction(reader)" @click="onReextract(reader)">{{ extractionActionLabel(reader, readerActionCoverage) }}</el-button>
                <el-button v-else size="small" @click="switchReaderTab('extraction')">查看抽取报告</el-button>
              </template>
            </div>
            <p v-if="readerMarkdown && !markdownMissing" class="structured-md-hint">阅读 MD {{ readerMarkdown.markdown?.length || 0 }} 字 · 当前检索 MD {{ readerMarkdown.indexedMarkdown?.length || 0 }} 字</p>
            <p v-if="hasVisionContent" class="structured-md-hint">含模型转写或图像描述，请对照原图核对。</p>
            <p v-if="!markdownIsCandidate && extractionCaps?.vision && (!extractionCaps.vision.enabled || extractionCaps.vision.available === false)" class="structured-md-hint">{{ extractionCaps.vision.reason || '未配置可用的图片模型；仍可提取原图，模型识别内容将标记为待处理。' }}</p>
          </div>
          <section v-if="readerMarkdown && !markdownMissing" class="structured-md-review" aria-label="阅读与检索审核" data-translation-ignore>
            <div class="structured-md-review-heading">
              <el-tag :type="markdownIsCandidate || markdownReview.total ? 'warning' : 'success'" size="small">{{ markdownIsCandidate ? '尚未发布到检索库' : '当前检索版本' }}</el-tag>
              <span v-if="markdownReview.total">{{ markdownReview.total }} 处待核对<template v-if="markdownReview.formulaCount">，其中 {{ markdownReview.formulaCount }} 处公式</template></span>
              <span v-else-if="markdownReview.complete === false">扫描尚未完整覆盖</span>
              <span v-else>阅读结果与检索发布状态分开管理</span>
              <el-button size="small" @click="openMarkdownReview()">{{ markdownReview.total ? '核对标记内容' : '查看质量报告' }}</el-button>
            </div>
            <p v-if="markdownIsCandidate">最新扫描用于阅读；待核对处保留标记，检索入库单独审核。</p>
            <div v-if="retrievalReview.required" class="structured-md-publication">
              <template v-if="markdownIsCandidate">
                <el-button type="primary" size="small" :loading="publishingScan" :disabled="!canPublishSelectedScan" @click="publishReviewedScan">审核并发布到检索库</el-button>
                <span v-if="retrievalReview.excludedOtherCount">发布时排除 {{ retrievalReview.excludedOtherCount }} 处待核对内容，保留阅读标记。</span>
                <ul v-if="retrievalReview.blockers?.length"><li v-for="reason in retrievalReview.blockers" :key="reason">{{ reason }}</li></ul>
              </template>
              <el-button v-else size="small" @click="selectMarkdownVersion('latest')">查看待审核的最新扫描</el-button>
            </div>
            <p v-if="publicationError" class="task-error" role="alert">{{ publicationError }}</p>
          </section>
          <div v-if="markdownError" class="extraction-feedback" role="alert">{{ markdownError }} <el-button size="small" @click="ensureMarkdown(true)">重新加载 MD</el-button></div>
          <div v-else-if="markdownMissing" class="extraction-feedback">这份资料尚未转换为统一 Markdown，原件与旧正文仍保留。<el-button v-if="readerBodyLimited" size="small" @click="switchReaderTab('extraction')">查看抽取报告</el-button><el-button v-else size="small" :loading="isReextracting(reader)" :disabled="!canStartExtraction(reader)" @click="onReextract(reader)">{{ readerCanContinue ? extractionActionLabel(reader, readerActionCoverage) : '生成结构化 MD' }}</el-button></div>
          <p v-if="mediaTaskError && (!mediaJob || String(mediaJob.fileId) === String(reader.id))" class="task-error" role="alert">{{ mediaTaskError }}</p>
          <p v-if="mediaJob && String(mediaJob.fileId) === String(reader.id)" class="structured-md-hint" role="status">媒体任务：{{ extractionTaskLabel(mediaJob) }} · {{ mediaJob.done ?? 0 }} / {{ mediaJob.total ?? 0 }}<template v-if="mediaJob.stage"> · {{ mediaJob.stage }}</template><template v-if="mediaJob.error"> · {{ mediaJob.error }}</template><el-button v-if="mediaTaskError" size="small" @click="resumeDocumentTask('media')">重新查询状态</el-button></p>
          <article v-if="readerTab === 'text' && markdownMode === 'preview' && readerMarkdown && !markdownMissing" class="structured-markdown structured-md-content" aria-label="结构化 Markdown 预览" @click="onMarkdownClick">
            <p v-if="markdownView.errors.length || markdownView.blockedImages" class="structured-md-warning" data-translation-ignore>部分公式或图片需核对，已保留源码和原文入口。<template v-for="error in markdownView.errors" :key="error"> {{ error }}</template></p>
            <div v-html="markdownView.html"></div>
          </article>
          <pre v-if="readerTab === 'text' && markdownMode === 'source' && readerMarkdown && !markdownMissing" class="structured-md-source structured-md-content" tabindex="0" aria-label="Markdown 源码">{{ readerMarkdown.markdown }}</pre>
          <div v-show="markdownMode === 'blocks'" class="structured-block-review">
          <p v-if="!readerMarkdown?.selectedVersion && statusHint()" class="warn-line">{{ statusHint() }}</p>
          <p v-if="readerLayout && readerLayout.status === 'failed'" class="warn-line">
            排版还原失败（{{ readerLayout.error || '未知原因' }}），下面是检索层正文。
          </p>
          <p v-else-if="readerLayout && !layoutUsable && readerLayout.status !== 'ok'" class="hint">
            {{ readerLayout.error || '这份资料没有可还原的版面' }}，下面是检索层正文。
          </p>

          <DocTextView
            ref="blockReader"
            v-if="layoutUsable"
            :key="`${reader.id}:${readerMarkdown?.selectedVersion || 'published'}:${readerMarkdown?.artifactRevision || readerMarkdown?.sourceSha256 || ''}`"
            :file-id="reader.id"
            :pages="readerLayout.pages"
            :chars="readerLayout.chars"
            :page-count="readerLayout.pageCount"
            :max-chars="transCaps.maxChars"
            :artifact="markdownArtifact"
            :allow-region-recognition="docKind === 'pdf' && !markdownIsCandidate"
            external-translation
            @open-original="openOriginalPage"
            @open-region="openExtractionBlock"
            @persisted="onFormulaPersisted"
            @translate="textTranslator?.translateText($event)"
          />

          <!-- 兜底：检索层那份纯文本，按段落读、逐段翻译 -->
          <template v-else>
            <div class="reader-text-head">
              <span class="hint">选中文字或点击「译」，在右侧对照阅读</span>
              <span class="spacer" />
              <el-button size="small" text :disabled="!readerText" @click="copyReaderText">复制正文</el-button>
            </div>
            <!-- 按段落翻译：整篇翻会撞输出上限（实测两万字就被截断），读到哪里翻到哪里更稳 -->
            <div class="reader-paras">
              <div v-for="p in paragraphs" :key="p.key" class="para">
                <div class="para-head">
                  <span class="para-no">¶{{ p.key + 1 }}</span>
                  <el-button
                    size="small"
                    text
                    :disabled="p.text.length > (transCaps.maxChars || 4000)"
                    @click="textTranslator?.translateText(p.text)"
                  >
                    译
                  </el-button>
                  <span v-if="p.text.length > (transCaps.maxChars || 4000)" class="hint">超单段上限，请选中更小的一段</span>
                </div>
                <p class="para-text">{{ p.text }}</p>
              </div>
              <p v-if="!paragraphs.length" class="hint">（无正文）</p>
            </div>
          </template>
          </div>
        </div>
        </ReaderTranslation>

        <section v-if="readerTab === 'extraction'" class="extraction-panel reader-body" aria-label="文档抽取报告" :aria-busy="extractionLoading">
          <div class="extraction-heading">
            <div>
              <h3>抽取与质量</h3>
              <p>检查识别结果，并回到原文核对。未收录的块不会参与知识检索。</p>
            </div>
            <div class="structured-md-actions">
              <el-button :loading="mediaStarting" :disabled="viewingScanCandidate || mediaBusy || isReextracting(reader) || isStructuring(reader)" @click="processReaderMedia">{{ mediaPreviouslyProcessed ? '继续处理公式与图片' : '处理公式与图片' }}</el-button>
              <el-button v-if="!readerBodyLimited" :type="readerCanContinue ? 'primary' : undefined" :loading="isReextracting(reader)" :disabled="!canStartExtraction(reader)" @click="onReextract(reader)">{{ extractionActionLabel(reader, readerActionCoverage) }}</el-button>
            </div>
          </div>
          <el-radio-group v-if="scanCandidate" v-model="extractionReportMode" size="small" aria-label="选择抽取报告版本">
            <el-radio-button value="published">已发布正文</el-radio-button>
            <el-radio-button value="candidate">新扫描候选</el-radio-button>
          </el-radio-group>
          <div class="extraction-status-row">
            <el-tag :type="extractionState.type">{{ viewingScanCandidate ? '扫描候选 · ' : '' }}{{ extractionState.label }}</el-tag>
            <span>{{ ocrCapabilityLabel }}</span>
            <span v-if="extractionCaps?.engine">{{ extractionCaps.engine }}</span>
          </div>
          <p v-if="extractionCaps?.message || capsError" class="extraction-hint">{{ capsError || extractionCaps.message }}</p>
          <div v-if="readerPendingScan" class="document-task" aria-label="待发布的扫描结果">
            <div class="document-task-heading"><strong>新扫描结果待审核发布</strong><span>{{ readerPendingScanProgress.pages }}</span></div>
            <p>结构化 MD 可阅读最新扫描，当前检索继续使用已发布正文。<template v-if="readerCanContinue">可继续扫描剩余页；</template>核对后在阅读区单独审核发布。</p>
            <el-button v-if="scanCandidate && !viewingScanCandidate" size="small" @click="extractionReportMode = 'candidate'">查看新扫描候选</el-button>
            <ul v-if="extractionLimitHints(readerPendingScan).length"><li v-for="limit in extractionLimitHints(readerPendingScan)" :key="limit.code">{{ limit.text }}</li></ul>
          </div>
          <p v-if="extractionCaps?.vision" class="extraction-hint">公式与图片模型：{{ extractionCaps.vision.enabled ? [extractionCaps.vision.profile, extractionCaps.vision.model].filter(Boolean).join(' / ') || '已启用' : extractionCaps.vision.reason || '未配置，原图仍保留' }}</p>
          <p v-if="mediaJob && String(mediaJob.fileId) === String(reader.id)" class="extraction-hint" role="status">媒体任务：{{ extractionTaskLabel(mediaJob) }} · {{ mediaJob.done ?? 0 }} / {{ mediaJob.total ?? 0 }}<template v-if="mediaJob.error"> · {{ mediaJob.error }}</template></p>
          <p v-if="mediaTaskError" class="task-error" role="alert">{{ mediaTaskError }} <el-button v-if="mediaJob" size="small" @click="resumeDocumentTask('media')">重新查询状态</el-button></p>
          <el-button v-if="capsError" size="small" @click="loadExtractionCaps">重新检查 OCR 能力</el-button>
          <div v-if="structureSupported" class="document-task structure-task" :aria-busy="isStructuring(reader)">
            <div class="document-task-heading">
              <strong>高精度扫描</strong>
              <span>{{ structureCapabilityLabel }}</span>
            </div>
            <p v-if="structureCapability?.configured">PDF 与图片默认使用此方式，包括带文字层的 PDF。逐页识别版面、文字、表格与公式；大文件可能需要分批继续。</p>
            <p v-else>启用后，PDF 与图片默认逐页扫描。当前保留基础抽取方式，扫描件需要可用的文字识别服务。</p>
            <p>重新扫描期间保留已发布检索版本，最新结果可直接阅读。公式仍需对照原图核对，检索发布单独审核。</p>
            <p v-if="structureCapability?.message">{{ structureCapability.message }}</p>
            <div v-if="!readerBodyLimited" class="structured-md-actions">
              <el-button plain type="primary" size="small" :loading="structureStartingId === String(reader.id)" :disabled="!!capsError || !structureCapability?.available || structureBusy || formulaReviewSaving || isReextracting(reader) || isProcessingMedia(reader)" @click="processReaderStructure">{{ readerCanContinue ? '继续扫描' : '重新扫描' }}</el-button>
              <el-button v-if="structureCapability?.configured && !structureCapability.available && !capsError" size="small" @click="loadExtractionCaps">重新检查状态</el-button>
            </div>
            <p v-if="structureBusy && !isStructuring(reader)">另一份资料正在扫描，完成后可继续。</p>
            <p v-if="!readerStructureJob && readerStructureError" class="task-error" role="alert">{{ readerStructureError }}</p>
          </div>
          <div v-if="extractionError" class="extraction-feedback" role="alert">
            <p>抽取报告加载失败：{{ extractionError }}</p>
            <el-button size="small" @click="refreshReaderArtifacts()">重新加载报告</el-button>
          </div>
          <p v-else-if="extractionLoading && !readerExtraction" class="extraction-hint" role="status">正在加载抽取报告…</p>
          <template v-else-if="reportExtraction">
            <p v-if="reportExtraction.status === 'not_processed'" class="extraction-hint">这份资料尚无抽取报告。<template v-if="extractionCaps && !extractionCaps.configured && !structureCapability?.configured">扫描件需要先启用文字识别服务。</template>点击「{{ extractionActionLabel(reader, readerActionCoverage) }}」可补充报告和原文位置。</p>
            <p v-if="reportExtraction.error" class="extraction-feedback">{{ reportExtraction.error }}</p>
            <p v-if="reportExtraction.status !== 'not_processed' && reportExtraction.format !== 'markdown'" class="extraction-hint">这是旧版抽取结果，可使用抽取按钮生成统一 Markdown。</p>
            <details v-if="viewingScanCandidate && reportExtraction.markdown" class="scan-candidate-preview">
              <summary>预览新扫描正文（尚未发布）</summary>
              <p class="extraction-hint">此处展示候选正文；图片和公式请通过内容块的原文入口核对。</p>
              <article class="structured-markdown" aria-label="新扫描候选正文" @click="onMarkdownClick" v-html="candidateMarkdownView.html"></article>
            </details>
            <dl v-if="reportExtraction.report" class="extraction-metrics">
              <div><dt>{{ viewingScanCandidate ? '扫描通过页' : '可用页' }}</dt><dd>{{ coveredPages(reportCoverage) == null ? '待核对' : `${NUM.format(coveredPages(reportCoverage))} / ${NUM.format(reportCoverage.totalPages)}` }}</dd></div>
              <div><dt>OCR 页</dt><dd>{{ reportExtraction.report.ocrPages ?? 0 }}</dd></div>
              <div><dt>未通过页</dt><dd>{{ reportExtraction.report.rejectedPages ?? 0 }}</dd></div>
              <div><dt>平均置信度</dt><dd>{{ confidenceLabel(reportExtraction.report.meanConfidence) }}</dd></div>
            </dl>
            <div v-if="isPartialExtraction(reportCoverage) || reportLimitHints.length || reportExtraction.report?.warnings?.length" class="extraction-warnings">
              <strong>质量提示</strong>
              <ul v-if="reportLimitHints.length"><li v-for="limit in reportLimitHints" :key="limit.code">{{ limit.text }}</li></ul>
              <p v-if="extractionGuidance(reader, reportCoverage)">{{ extractionGuidance(reader, reportCoverage) }}<template v-if="readerCanContinue">已有可用正文和原文定位会保留。</template></p>
              <p v-else-if="isPartialExtraction(reportCoverage)">部分内容仍待核对，可查看未通过的内容块、公式和图片。</p>
              <details v-if="reportExtraction.report?.warnings?.length" class="extraction-warning-details">
                <summary>查看识别明细（{{ reportExtraction.report.warnings.length }}）</summary>
                <ul><li v-for="(warning, index) in reportExtraction.report.warnings" :key="index">{{ warning }}</li></ul>
              </details>
            </div>
            <div class="extraction-block-heading">
              <h4>结构化内容块 <span>{{ extractionRows.length }}</span></h4>
              <el-radio-group v-model="blockFilter" size="small" aria-label="筛选抽取块">
                <el-radio-button value="indexed">{{ viewingScanCandidate ? '候选已通过' : '已收录' }} {{ extractionRows.length - rejectedBlockCount }}</el-radio-button>
                <el-radio-button value="rejected">{{ viewingScanCandidate ? '待核对' : '未收录' }} {{ rejectedBlockCount }}</el-radio-button>
                <el-radio-button value="all">全部</el-radio-button>
              </el-radio-group>
            </div>
            <p v-if="formulaReviewCount" class="extraction-hint">有 {{ formulaReviewCount }} 处公式转写待核对。<el-button v-if="blockFilter === 'indexed'" size="small" text @click="blockFilter = 'rejected'">{{ viewingScanCandidate ? '查看待核对内容' : '查看未收录内容' }}</el-button></p>
            <ol v-if="visibleExtractionRows.length" class="extraction-blocks" aria-label="抽取内容块">
              <li v-for="block in visibleExtractionRows" :key="block.key" class="extraction-block">
                <div class="extraction-block-meta">
                  <strong>第 {{ block.page }} 页</strong>
                  <span>{{ block.method === 'ocr' ? reportOcrLabel : block.method === 'vision' ? '视觉识别' : '原生文字' }}</span>
                  <span>{{ extractionBlockTypeLabel(block.type) }}</span>
                  <span v-if="extractionMediaLabel(block)">{{ extractionMediaLabel(block) }}</span>
                  <el-tag size="small" :type="block.rejected ? 'warning' : 'success'">{{ viewingScanCandidate ? (block.rejected ? '待核对' : '候选已通过') : (block.rejected ? '未收录' : '已收录') }}</el-tag>
                  <span>置信度 {{ confidenceLabel(block.confidence) }}</span>
                </div>
                <p v-if="block.rejected" class="extraction-hint">{{ block.latexStatus === 'needs_review' ? '公式转写待核对' : extractionQualityLabel(block.quality) }}，不参与检索。</p>
                <p v-if="block.text || block.latexStatus !== 'needs_review'" class="extraction-block-text">{{ block.text || '（无可展示文字）' }}</p>
                <details v-if="block.rawText && block.cleanText && block.rawText !== block.cleanText"><summary>查看原始识别文字</summary><p class="extraction-block-text">{{ block.rawText }}</p></details>
                <el-button v-if="['pdf', 'image'].includes(docKind)" size="small" plain type="primary" @click="openExtractionBlock(block)">{{ block.rect ? '查看原文区域' : '查看原文页' }}</el-button>
                <el-button v-if="block.latexStatus === 'needs_review'" size="small" :aria-expanded="reviewedFormulaBlock?.key === block.key" :disabled="formulaReviewSaving" @click="beginFormulaReview(block)">核对公式转写</el-button>
                <el-button v-else-if="block.type === 'formula' && !viewingScanCandidate" size="small" @click="reviewExtractionBlock(block)">核对 / 识别公式</el-button>
                <div v-if="reviewedFormulaBlock?.key === block.key" class="formula-review">
                  <p class="extraction-hint">对照原图检查上下标、分式和符号。{{ viewingScanCandidate ? '确认会保存到扫描候选，整份新结果通过检查后才纳入检索。' : '确认后，修正的公式会纳入检索。' }}</p>
                  <img v-if="formulaReviewImage" :src="formulaReviewImage" alt="待核对的公式原图" loading="lazy" decoding="async" />
                  <p v-else class="extraction-hint">可通过上方的原文入口查看公式区域，再返回确认。</p>
                  <label>
                    <span>候选 LaTeX</span>
                    <el-input v-model="formulaReview.latex" type="textarea" :rows="3" :maxlength="MAX_EXTRACTED_LATEX_CHARS" :disabled="formulaReviewSaving" spellcheck="false" aria-label="待核对的公式 LaTeX" />
                  </label>
                  <div v-if="formulaReviewPreview.ok" class="formula-review-preview" aria-label="候选公式预览" v-html="formulaReviewPreview.html"></div>
                  <p v-else class="extraction-hint">{{ formulaReviewPreview.error }}</p>
                  <p v-if="formulaReviewError" class="task-error" role="alert">{{ formulaReviewError }} <el-button size="small" text @click="ensureExtraction(true)">重新加载报告</el-button></p>
                  <div class="structured-md-actions">
                    <el-button size="small" type="primary" :loading="formulaReviewSaving" :disabled="!formulaReviewPreview.ok || isStructuring(reader) || isReextracting(reader)" @click="confirmFormulaReview">核对原图并确认</el-button>
                    <el-button size="small" :disabled="formulaReviewSaving" @click="formulaReview = null">取消</el-button>
                  </div>
                </div>
              </li>
            </ol>
            <p v-else class="extraction-hint">{{ extractionRows.length ? '这个筛选下没有内容块。' : '报告中没有结构化内容块，可重新抽取后查看。' }}</p>
            <el-pagination v-if="matchingExtractionRows.length > 20" v-model:current-page="blockPage" :page-size="20" :total="matchingExtractionRows.length" :pager-count="5" layout="prev, pager, next" small aria-label="抽取块分页" />
          </template>
        </section>
      </div>
    </el-dialog>

  <!-- break-ui 数据集切换条：只在 URL 带 ?data= 时渲染，正式界面看不到它 -->
  <div v-if="devData" class="devdata-bar" role="group" aria-label="break-ui worst-case data">
    <span class="devdata-title">break-ui</span>
    <button
      v-for="f in DEV_FIXTURES"
      :key="f.key"
      type="button"
      class="devdata-seg"
      :class="{ on: devData === f.key }"
      @click="setDevData(f.key)"
    >{{ f.label }}</button>
  </div>
</template>

<style scoped>
/* break-ui 的切换条：刻意做成"中性 chrome"（灰轨道 + 白色滑块、系统字体），
   它不属于被测设计，所以不沿用应用的主题变量与动效。 */
.devdata-bar {
  position: fixed;
  left: 50%;
  bottom: 14px;
  transform: translateX(-50%);
  z-index: 3000;
  display: flex;
  align-items: center;
  gap: 2px;
  padding: 3px;
  border-radius: 999px;
  background: #e8e8ea;
  box-shadow: 0 2px 10px rgb(0 0 0 / 18%);
  font-family: system-ui, -apple-system, 'Segoe UI', sans-serif;
  font-size: 12px;
}
.devdata-title {
  padding: 0 8px 0 6px;
  color: #6b6b70;
  font-size: 11px;
  letter-spacing: .02em;
}
.devdata-seg {
  padding: 5px 11px;
  border: 0;
  border-radius: 999px;
  background: transparent;
  color: #494950;
  font: inherit;
  cursor: pointer;
}
.devdata-seg.on {
  background: #fff;
  color: #18181b;
  box-shadow: 0 1px 3px rgb(0 0 0 / 14%);
}
/* Keep the final table controls scrollable above the floating agent button. */
.page.file-library { min-width: 0; padding-bottom: 96px; }
.library-header { justify-content: space-between; gap: var(--space-md); margin-bottom: var(--space-lg); }
.library-heading { display: flex; align-items: baseline; flex-wrap: wrap; gap: var(--space-sm) var(--space-md); min-width: 0; }
.library-heading .page-h2 { font-size: var(--font-title); line-height: var(--lh-title); }
.library-heading .tip { font-size: var(--font-caption); line-height: var(--lh-caption); color: var(--app-text-3); }
.library-header-actions { display: flex; align-items: center; flex-wrap: wrap; gap: var(--space-sm); max-width: 100%; min-width: 0; }
.upload-category { width: var(--control-w); max-width: 100%; }
.library-header-actions :deep(.el-button), .collection-filters :deep(.el-button) { height: var(--control-h); margin-left: 0; font-size: var(--font-body); }
.library-header-actions > .el-button { width: var(--control-w); max-width: 100%; }
.library-header-actions > .el-button:first-child { width: auto; min-width: min(100%, var(--control-w)); }
.collection-filters > .el-button { width: var(--control-w); max-width: 100%; }
.library-header-actions :deep(.el-select__wrapper), .collection-filters :deep(.el-select__wrapper) { min-height: var(--control-h); font-size: var(--font-body); }
.collection-filters :deep(.el-input) { --el-component-size: var(--control-h); font-size: var(--font-body); }
.library-content { min-width: 0; container: file-collection / inline-size; }
.library-content :deep(.el-card__body) { padding: var(--space-md); }
.library-content :deep(.folder-explorer) { margin-bottom: var(--space-md); }
.library-content :deep(.explorer-toolbar) { gap: var(--space-sm); padding: var(--space-sm); border-radius: var(--radius); }
.collection-head { display: flex; align-items: center; flex-wrap: wrap; gap: var(--space-sm); margin: 0 0 var(--space-md); min-height: var(--control-h); }
.collection-title { display: flex; align-items: center; flex-wrap: wrap; gap: var(--space-sm); min-width: 0; max-width: 100%; }
.collection-title > span { font-size: var(--font-caption); line-height: var(--lh-caption); color: var(--app-text-3); }
.collection-filters { display: flex; align-items: center; flex-wrap: wrap; gap: var(--space-sm); margin-left: auto; min-width: 0; max-width: 100%; }
.file-search { width: var(--control-search-w); max-width: 100%; }
.file-category-filter { width: var(--control-w); max-width: 100%; }
.selection-actions { display: flex; align-items: center; flex-wrap: wrap; gap: var(--space-sm); max-width: 100%; color: var(--app-brand); font-size: var(--font-caption); }
.selection-actions > .el-button:not(.is-text) { width: var(--control-w); max-width: 100%; }
.selection-actions .el-button + .el-button { margin-left: 0; }
.file-table { font-size: var(--font-body); }
.file-table :deep(.el-table__body tr) { height: 72px; }
.file-table.with-locations :deep(.el-table__body tr) { height: 88px; }
.file-table :deep(td.el-table__cell) { padding: 12px 0; }
.file-table :deep(th.el-table__cell) { height: 44px; padding: var(--space-sm) 0; }
.file-table :deep(.cell) { padding: 0 12px; line-height: 20px; }
.file-table :deep(.el-table__header .cell) { font-size: var(--font-caption); font-weight: 600; letter-spacing: 0; }
.file-table :deep(.el-select__wrapper) { min-height: var(--control-h-inline); font-size: var(--font-caption); }
.file-table :deep(.el-tag) { height: 24px; padding: 0 var(--space-sm); font-size: var(--font-caption); line-height: 22px; }
.row-actions { display: flex; align-items: center; gap: var(--space-sm); white-space: nowrap; }
.row-actions :deep(.el-button) { min-height: var(--control-h-inline); margin: 0; padding: 0; font-size: var(--font-body); }
.row-actions :deep(.el-dropdown) { display: inline-flex; align-items: center; }
.row-actions :deep(.el-dropdown .el-button) { min-width: 24px; }
.folder-location { display: block; margin-top: var(--space-xs); max-width: 100%; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; color: var(--app-text-3); font-size: var(--font-caption); line-height: 16px; }
.folder-name { display: inline-flex; align-items: center; gap: var(--space-sm); max-width: 100%; padding: var(--space-xs) 0; border: 0; border-radius: var(--radius); background: transparent; color: var(--app-text-1); font: inherit; font-weight: 500; text-align: left; cursor: pointer; }
.folder-name svg { width: 20px; height: 20px; flex-shrink: 0; fill: var(--app-brand-soft); stroke: var(--app-brand); stroke-width: 1.6; }
.folder-name span { overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.folder-name:focus-visible { outline: 2px solid var(--app-brand); outline-offset: 3px; }
.folder-kind, .folder-detail { color: var(--app-text-3); font-size: var(--font-caption); }
.library-content :deep(.explorer-folder-row) { cursor: pointer; }
.library-content :deep(.explorer-folder-row .el-table-column--selection .el-checkbox) { visibility: hidden; }
/* 文件名：裁 2 行 + 只在必要时断行。
   原来只有 `word-break: break-all` —— 那是"任意两个字符之间都能断"，于是 approved 会被切成
   appro/ved，明明有空格和连字符可断。overflow-wrap: anywhere 只在真的放不下时才断。
   裁切是必须的：origin_name 上限 255 字符，不裁的话一行能长到十几行（见 title 属性给全文）。 */
.fname {
  color: var(--app-text-1);
  font-size: var(--font-body);
  font-weight: 500;
  line-height: 20px;
  overflow-wrap: anywhere;
  display: -webkit-box;
  -webkit-line-clamp: 2;
  -webkit-box-orient: vertical;
  overflow: hidden;
}
/* 参与"一眼比大小"的数字用等宽数字，值变化时列不会左右跳 */
.num { font-size: var(--font-caption); color: var(--app-text-2); font-variant-numeric: tabular-nums; white-space: nowrap; }
/* 分页条：右对齐、给表格一点呼吸 */
.fpage { display: flex; justify-content: safe flex-end; min-width: 0; max-width: 100%; margin-top: var(--space-md); overflow-x: auto; scrollbar-width: thin; }
.fpage :deep(.el-pagination) { flex: 0 0 auto; }
.fsummary { margin-top: var(--space-xs); font-size: var(--font-caption); line-height: 16px; color: var(--app-text-3); white-space: nowrap; text-overflow: ellipsis; overflow: hidden; }
.extraction-row-status { display: flex; align-items: center; flex-wrap: wrap; gap: 4px 8px; }
.extraction-row-status :deep(.el-button) { margin: 0; padding: 0; font-size: var(--font-caption); }
.spacer { flex: 1 1 auto; }
:global(.reader-dialog.el-dialog) { display: flex; flex-direction: column; height: 100dvh; overflow: hidden; }
:global(.reader-dialog .el-dialog__header) { flex: 0 0 auto; }
:global(.reader-dialog .el-dialog__body) { flex: 1 1 auto; min-height: 0; box-sizing: border-box; padding: 0; overflow: hidden; }
.reader { display: flex; flex-direction: column; height: 100%; min-height: 0; gap: 8px; }
.reader-meta { flex: 0 0 auto; flex-wrap: wrap; max-height: min(144px, 30dvh); overflow: auto; }
.reader-body { flex: 1 1 auto; min-width: 0; min-height: 0; }
.reader-frame { width: 100%; height: 100%; min-height: 0; border: 0; border-radius: 8px; background: var(--app-bg); }
.reader-original-source { height: 100%; min-height: 0; }
.reader-image-scroll { height: 100%; overflow: auto; text-align: center; }
.reader-image-frame { position: relative; display: inline-block; max-width: 100%; vertical-align: top; }
.reader-img { display: block; max-width: 100%; height: auto; margin: 0 auto; border-radius: 8px; }
.image-source-highlight { position: absolute; box-sizing: border-box; border: 2px solid #0d9488; background: rgb(20 184 166 / 20%); box-shadow: 0 0 0 2px rgb(255 255 255 / 70%); pointer-events: none; }
.source-location-message { flex: 0 0 auto; max-height: 22dvh; overflow: auto; margin: 0; padding: 6px 10px; background: var(--app-brand-soft); color: var(--app-text-1); font-size: 12px; line-height: 1.6; overflow-wrap: anywhere; }
.extraction-panel { overflow: auto; padding: 16px; color: var(--app-text-1); background: var(--app-bg); border: 1px solid var(--app-border); border-radius: var(--radius); }
.extraction-heading, .extraction-block-heading { display: flex; align-items: flex-start; flex-wrap: wrap; gap: 12px; justify-content: space-between; }
.extraction-heading h3, .extraction-block-heading h4 { margin: 0; font-size: 16px; }
.extraction-heading p, .extraction-hint { margin: 6px 0 12px; font-size: 12.5px; line-height: 1.7; color: var(--app-text-2); overflow-wrap: anywhere; }
.extraction-status-row, .extraction-block-meta { display: flex; flex-wrap: wrap; align-items: center; gap: 8px; font-size: 12px; color: var(--app-text-2); }
.extraction-panel :deep(.el-tag) { color: var(--app-text-1); }
.extraction-panel :deep(.el-button--primary.is-plain) { color: var(--app-brand-deep); }
.extraction-metrics { display: grid; grid-template-columns: repeat(auto-fit, minmax(110px, 1fr)); gap: 12px; margin: 16px 0; }
.extraction-metrics div { padding: 12px; border: 1px solid var(--app-border); border-radius: var(--radius); background: var(--app-card); }
.extraction-metrics dt { color: var(--app-text-2); font-size: 12px; }
.extraction-metrics dd { margin: 4px 0 0; font-size: 20px; font-variant-numeric: tabular-nums; }
.extraction-feedback, .extraction-warnings { padding: 12px; margin: 12px 0; border: 1px solid var(--el-color-warning-light-5); background: var(--el-color-warning-light-9); color: var(--app-text-1); border-radius: var(--radius); font-size: 12.5px; line-height: 1.7; overflow-wrap: anywhere; }
.extraction-warnings p { margin: 6px 0 0; }
.extraction-warnings ul { margin: 6px 0 0; padding-left: 20px; }
.extraction-warning-details { margin-top: 8px; }
.extraction-warning-details summary { cursor: pointer; width: fit-content; }
:global(.extraction-tooltip) { max-width: min(24rem, calc(100vw - 2rem)); white-space: normal; overflow-wrap: anywhere; line-height: 1.5; }
.extraction-block-heading { align-items: center; margin-top: 24px; }
.extraction-block-heading h4 { font-size: 14px; }
.extraction-block-heading h4 span { color: var(--app-text-3); font-weight: 400; }
.extraction-block-heading :deep(.el-radio-group) { display: flex; flex-wrap: wrap; gap: 4px; }
.extraction-block-heading :deep(.el-radio-button__inner) { border: 1px solid var(--el-border-color); border-radius: 4px; box-shadow: none; }
.extraction-blocks { list-style: none; padding: 0; margin: 12px 0 0; }
.extraction-block { padding: 16px 0; border-top: 1px solid var(--app-border); }
.extraction-block-text { white-space: pre-wrap; overflow-wrap: anywhere; font-size: 14px; line-height: 1.8; margin: 10px 0; }
.extraction-block summary { cursor: pointer; color: var(--app-text-2); font-size: 12px; }
.extraction-block details { margin: 8px 0; }
.extraction-panel :is(button, summary):focus-visible { outline: 2px solid var(--app-brand); outline-offset: 3px; }
.reader-media { width: 100%; max-height: 100%; }
.reader-fallback { padding: 24px; color: var(--app-text-2); }
.reader-text-wrap { display: flex; flex-direction: column; height: 100%; min-height: 0; }
.reader-text-wrap :deep(.doc-view) { flex: 1 1 auto; height: auto; min-height: 0; }
.migration-scope { margin: -8px 0 16px; color: var(--app-text-2); font-size: 12px; line-height: 1.7; }
.document-tasks { display: grid; gap: 10px; margin-bottom: 16px; min-width: 0; }
.document-task { min-width: 0; padding: 12px; border: 1px solid var(--app-border); border-radius: var(--radius); background: var(--app-card); font-size: 12px; overflow-wrap: anywhere; }
.document-task p { margin: 6px 0; }
.document-task-heading { display: flex; flex-wrap: wrap; justify-content: space-between; gap: 8px; color: var(--app-text-1); }
.document-task-heading span { color: var(--app-brand-deep); }
.document-task summary { cursor: pointer; color: var(--app-brand-deep); margin-top: 8px; }
.document-task ul { margin: 6px 0 0; padding-left: 18px; }
.reader-extraction-task { flex: 0 0 auto; max-height: min(140px, 25dvh); overflow: auto; padding: 8px 12px; }
.reader-extraction-progress { margin-top: 6px; }
.structure-task { margin: 12px 0; }
.formula-review { display: grid; gap: 8px; padding: 12px; margin-top: 12px; border: 1px solid var(--app-border); border-radius: var(--radius); background: var(--app-card); }
.formula-review img { display: block; max-width: 100%; max-height: 220px; object-fit: contain; justify-self: start; }
.formula-review label { display: grid; gap: 4px; min-width: 0; }
.scan-candidate-preview { margin-block: 12px; }
.scan-candidate-preview > summary { cursor: pointer; color: var(--app-brand-deep); }
.scan-candidate-preview > article { max-height: 50dvh; overflow: auto; padding: 16px; border: 1px solid var(--app-border); border-radius: var(--radius); background: var(--app-card); }
.formula-review-preview { overflow-x: auto; }
.task-error { margin: 6px 10px; color: var(--el-color-danger); font-size: 12px; line-height: 1.7; overflow-wrap: anywhere; }
.structured-md-toolbar { flex: 0 0 auto; display: flex; flex-wrap: wrap; align-items: center; gap: 8px; padding: 8px; max-height: min(196px, 32dvh); overflow: auto; border: 1px solid var(--app-border); border-radius: var(--radius); background: var(--app-card); }
.structured-md-actions { display: flex; align-items: center; flex-wrap: wrap; gap: 6px; }
.structured-md-actions :deep(.el-button) { margin-left: 0; }
.structured-md-toolbar :deep(.el-radio-group) { flex-wrap: wrap; }
.structured-md-version { display: flex; align-items: center; gap: 8px; min-width: 0; font-size: 12px; color: var(--app-text-2); }
.structured-md-version :deep(.el-select) { width: 170px; }
.structured-md-review { flex: 0 0 auto; margin-top: 8px; padding: 10px 12px; border: 1px solid var(--app-border); border-radius: var(--radius); background: var(--app-card); font-size: 12px; color: var(--app-text-2); max-height: min(180px, 24dvh); overflow: auto; }
.structured-md-review-heading, .structured-md-publication { display: flex; flex-wrap: wrap; align-items: center; gap: 8px; }
.structured-md-review-heading { color: var(--app-text-1); }
.structured-md-review p { margin: 6px 0 0; line-height: 1.7; }
.structured-md-publication { margin-top: 8px; }
.structured-md-publication ul { flex-basis: 100%; margin: 0; padding-left: 18px; line-height: 1.7; }
.structured-md-review :deep(.el-button) { margin-left: 0; white-space: normal; height: auto; min-height: 26px; line-height: 1.4; }
.structured-md-hint { flex: 0 0 auto; margin: 0; font-size: 12px; line-height: 1.7; color: var(--app-text-2); overflow-wrap: anywhere; }
.structured-md-toolbar > .structured-md-hint { flex-basis: 100%; }
.structured-md-content { flex: 1 1 auto; min-width: 0; min-height: 0; overflow: auto; margin: 8px 0 0; padding: 24px; border: 1px solid var(--app-border); border-radius: var(--radius); background: var(--app-card); color: var(--app-text-1); }
.structured-md-source { font: 12px/1.8 ui-monospace, Consolas, monospace; white-space: pre-wrap; overflow-wrap: anywhere; }
.structured-markdown { font-size: 15px; line-height: 1.85; overflow-wrap: anywhere; }
.structured-markdown :deep(img) { max-width: 100%; height: auto; }
.structured-markdown :deep(img[width]) { vertical-align: middle; }
.structured-markdown :deep(blockquote) { margin: 1em 0; padding: .2em 1em; border-left: 3px solid var(--app-border); color: var(--app-text-2); }
.structured-markdown :deep(blockquote > :first-child) { margin-top: 0; }
.structured-markdown :deep(blockquote > :last-child) { margin-bottom: 0; }
.structured-markdown :deep(.footnotes) { margin-top: 1.6em; font-size: .88em; color: var(--app-text-2); }
.structured-markdown :deep(.footnote-ref) { font-size: .75em; line-height: 0; }
.structured-markdown :deep(.footnote-backref) { white-space: nowrap; }
.structured-markdown :deep(.footnote-item:focus) { outline: 2px solid var(--app-brand); outline-offset: 3px; }
.structured-markdown :deep(.structured-page-marker) { margin: 2em 0 1em; padding-top: .8em; border-top: 1px solid var(--app-border); font-size: 12px; font-weight: 500; color: var(--app-text-2); }
.structured-markdown :deep(.structured-page-button) { border: 0; padding: 3px 0; background: transparent; color: var(--app-brand-deep); font: inherit; text-align: left; cursor: pointer; }
.structured-markdown :deep(.structured-page-button:focus-visible) { outline: 2px solid var(--app-brand); outline-offset: 3px; }
.structured-markdown :deep(a) { color: var(--app-brand-deep); text-decoration: underline; }
.structured-markdown :deep(pre) { overflow: auto; padding: 12px; background: var(--app-bg); white-space: pre; border-radius: 6px; }
.structured-markdown :deep(code) { font: 0.9em/1.7 ui-monospace, Consolas, monospace; }
.structured-markdown :deep(table) { border-collapse: collapse; max-width: 100%; }
.structured-markdown :deep(th), .structured-markdown :deep(td) { border: 1px solid var(--app-border); padding: 6px 10px; min-width: 60px; }
.structured-markdown :deep(th) { background: var(--app-bg); }
.structured-markdown :deep(.structured-table-scroll) { max-width: 100%; overflow: auto; }
.structured-markdown :deep(.structured-math-block) { display: block; max-width: 100%; overflow: auto; text-align: center; padding: 12px 0; }
.structured-markdown :deep(.structured-math-error), .structured-md-warning { color: var(--app-text-2); background: var(--app-bg); white-space: pre-wrap; }
.structured-markdown :deep(.structured-image-blocked) { color: var(--app-text-2); font-size: 12px; }
.structured-markdown :deep(.structured-review-notice) { margin: 12px 0 6px; padding: 8px 10px; border-left: 3px solid var(--el-color-warning); background: var(--el-color-warning-light-9); color: var(--app-text-1); font-size: 12px; line-height: 1.6; overflow-wrap: anywhere; }
.structured-markdown :deep(.structured-review-button) { display: inline-flex; flex-wrap: wrap; align-items: center; gap: 4px; max-width: 100%; min-height: 28px; padding: 3px 5px; border: 1px solid var(--el-color-warning); border-radius: 4px; color: inherit; background: transparent; font: inherit; text-align: left; white-space: normal; cursor: pointer; }
.structured-markdown :deep(.structured-review-button:hover) { background: var(--el-color-warning-light-8); }
.structured-markdown :deep(.structured-review-button:focus-visible) { outline: 2px solid var(--app-brand); outline-offset: 3px; }
.structured-markdown :deep(.structured-needs-review) { border-inline-start: 2px solid var(--el-color-warning); padding-inline-start: 10px; }
@media (max-width: 480px) {
  .structured-md-version { width: 100%; }
  .structured-md-version :deep(.el-select) { flex: 1; }
  .structured-md-content { padding: 14px; }
}
.structured-block-review { flex: 1 1 auto; min-width: 0; min-height: 0; display: flex; flex-direction: column; overflow: hidden; }
.structured-translation.narrow { overflow: auto; }
.structured-translation.narrow :deep(.reader-original) { flex: 0 0 max(400px, 70dvh); }
.structured-translation.narrow :deep(.translation-panel) { flex: 0 0 280px; }
.structured-md-content:focus-visible { outline: 2px solid var(--app-brand); outline-offset: -3px; }
.reader-text-head { display: flex; align-items: center; flex-wrap: wrap; gap: 8px; padding: 0 8px 8px; }
.reader-paras { flex: 1 1 auto; min-height: 0; overflow: auto; padding: 24px 0 40px; background: var(--app-bg); border: 1px solid var(--app-border-weak); border-radius: 10px; }
.para { max-width: 900px; margin: 0 auto; padding: 0 24px; }
.para + .para { margin-top: 18px; padding-top: 18px; border-top: 1px dashed var(--app-border-weak); }
.para-head { display: flex; align-items: center; gap: 8px; color: var(--app-text-3); }
.para-no { font-size: 11px; }
.para-text { margin: 6px 0 0; font-size: 15px; line-height: 1.95; color: var(--app-text-1); white-space: pre-wrap; overflow-wrap: anywhere; }
.warn-line { margin: 0 8px 8px; font-size: 12.5px; color: #b45309; }
@media (max-width: 800px) {
  .file-library { padding: var(--space-md); }
  .library-heading { flex-direction: column; align-items: flex-start; gap: var(--space-xs); }
  .library-header-actions { margin-left: auto; }
  .collection-title { flex-basis: 100%; }
  .collection-filters { flex: 1 1 100%; flex-wrap: wrap; }
  .library-content :deep(.el-card__body) { padding: var(--space-sm); }
  .fpage { justify-content: flex-start; }
  :global(.reader-dialog.el-dialog) { padding: 10px; }
  .reader-meta { gap: 5px; }
  .para { padding: 0 16px; }
}
@media (max-width: 1280px) {
  .library-content :deep(.el-table-fixed-column--right) { position: static !important; right: auto !important; box-shadow: none !important; }
  .library-content :deep(.el-table-fixed-column--right::before),
  .library-content :deep(.el-table-fixed-column--right::after) { display: none !important; }
}
@media (max-width: 480px) {
  .structured-md-content { padding: 14px; font-size: 14px; }
  .extraction-panel { padding: 10px; }
  .extraction-heading > div { flex: 1 1 100%; }
  .extraction-metrics { gap: 6px; }
  .library-header { gap: var(--space-md); }
  .library-header-actions { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); width: 100%; }
  .library-header-actions > .el-button:first-child { grid-column: 1 / -1; width: 100%; min-width: 0; }
  .upload-category, .library-header-actions > .el-button:not(:first-child) { width: 100%; min-width: 0; }
  .selection-actions { flex-wrap: wrap; }
}
@container file-collection (max-width: 760px) {
  .collection-title { flex-basis: 100%; }
  .collection-filters { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); flex: 1 1 100%; margin-left: 0; }
  .file-search { grid-column: 1 / -1; width: 100%; min-width: 0; max-width: 100%; }
  .file-category-filter, .collection-filters > .el-button { width: 100%; min-width: 0; }
  .file-category-filter:last-child { grid-column: 1 / -1; }
  .file-table :deep(.el-table-fixed-column--right) { position: static !important; right: auto !important; box-shadow: none !important; }
  .file-table :deep(.el-table-fixed-column--right::before),
  .file-table :deep(.el-table-fixed-column--right::after) { display: none !important; }
}
</style>
