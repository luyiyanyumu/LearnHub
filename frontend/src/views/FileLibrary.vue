<script setup>
import { computed, nextTick, onBeforeUnmount, onMounted, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { useRoute } from 'vue-router'
import { categoryApi, fileApi, saveBlob } from '../api'
import PdfReader from '../components/PdfReader.vue'
import DocTextView from '../components/DocTextView.vue'
import OriginalDocumentView from '../components/OriginalDocumentView.vue'
import ReaderTranslation from '../components/ReaderTranslation.vue'
import { documentKind } from '../utils/documentKind'

const loading = ref(false)
const route = useRoute()
const uploading = ref(false)
const list = ref([])
/** 分页状态。列表一次只渲染一页（而不是全量）——原因见 fileApi.page 的注释。 */
const pager = ref({ page: 1, size: 20, total: 0 })
const categories = ref([])
const query = ref({ categoryId: undefined, kw: '' })
const uploadCategoryId = ref(undefined)
const uploadInput = ref(null)

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
  const url = new URL(window.location.href)
  url.searchParams.set('data', key)
  window.history.replaceState({}, '', url)
  await load()
}

async function load() {
  loading.value = true
  try {
    if (devData.value && devData.value !== 'demo') {
      const m = await import('../dev/worstCaseFiles')
      const all = m.pickFixture(devData.value) || []
      // 最坏数据集也走同一套分页逻辑 —— 这样 `1,000 rows` 这个状态看到的就是修复后的行为
      pager.value.total = all.length
      const start = (pager.value.page - 1) * pager.value.size
      list.value = all.slice(start, start + pager.value.size)
      return
    }
    const params = { ...query.value, page: pager.value.page, size: pager.value.size }
    if (!params.categoryId) delete params.categoryId
    if (!params.kw) delete params.kw
    const r = await fileApi.page(params)
    list.value = r.list || []
    pager.value.total = r.total || 0
    // 删掉最后一页的最后一条后会停在一个空页上（第 3 页，但只剩 2 页数据）→ 自动退回上一页
    if (!list.value.length && pager.value.page > 1 && pager.value.total > 0) {
      pager.value.page -= 1
      await load()
    }
  } finally {
    loading.value = false
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
  const files = e.target.files
  if (!files || !files.length) return
  uploading.value = true
  try {
    for (const f of files) {
      await fileApi.upload(f, uploadCategoryId.value || null)
      ElMessage.success(`已上传：${f.name}`)
    }
    e.target.value = ''
    // 新资料按 createdAt 倒序排在最前 → 回第 1 页才看得到刚上传的那份
    pager.value.page = 1
    load()
  } finally {
    uploading.value = false
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
const readerText = ref('')
const readerStatus = ref('')
const readerError = ref('')
const readerLoading = ref(false)
/**
 * 排版还原后的正文（{ pages:[{page,columns,blocks}] }）。
 *
 * 与 readerText 的关系：readerText 是**检索层那份纯文本**（PDF 两栏会逐行交错），
 * 只有排版还原不了（旧版 Word、其他格式、扫描件或解析失败）时才拿它兜底显示。
 * 懒加载：默认打开的是「原文」，切到「抽取正文」才去拉，PDF 解析那 1 秒不该让开卷就等。
 */
const readerLayout = ref(null)
const layoutLoading = ref(false)
let readerSeq = 0

/** 有没有可用的排版还原结果（决定「抽取正文」是文档视图还是分段兜底视图） */
const layoutUsable = computed(() =>
  readerLayout.value?.status === 'ok' && (readerLayout.value.pages?.length || 0) > 0)

async function ensureLayout() {
  const id = reader.value?.id
  if (!id || readerLayout.value || layoutLoading.value) return
  if (!['pdf', 'docx', 'docm', 'md', 'markdown'].includes(String(reader.value?.ext || '').toLowerCase())) {
    readerLayout.value = { status: 'text', pages: [], error: '当前页显示用于检索的抽取文字' }
    return
  }
  const seq = readerSeq
  layoutLoading.value = true
  try {
    const layout = await fileApi.layout(id)
    if (seq === readerSeq && reader.value?.id === id) readerLayout.value = layout
  } catch (e) {
    if (seq === readerSeq && reader.value?.id === id) {
      readerLayout.value = { status: 'failed', error: e?.message || '', pages: [], chars: 0, pageCount: 0 }
    }
  } finally {
    if (seq === readerSeq) layoutLoading.value = false
  }
}

/** 切换原文 / 抽取正文：切到正文时顺带把排版还原拉回来 */
function switchReaderTab(tab) {
  readerTab.value = tab
  if (tab === 'text') ensureLayout()
}

async function openOriginalPage(page) {
  readerTab.value = 'doc'
  await nextTick()
  pdfReader.value?.jump(page)
}

const docKind = computed(() => documentKind(reader.value))
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
  splitParas(readerText.value).map((p, i) => ({ key: i, text: p })))

async function openReader(row) {
  const seq = ++readerSeq
  reader.value = row
  readerTab.value = 'doc'
  readerText.value = ''
  readerStatus.value = ''
  readerError.value = ''
  readerLayout.value = null
  layoutLoading.value = false
  transCaps.value = { maxChars: 4000, profile: '', model: '' }
  readerLoading.value = true
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
  ++readerSeq
  reader.value = null
}

async function copyReaderText() {
  try {
    await navigator.clipboard.writeText(readerText.value || '')
    ElMessage.success('已复制正文')
  } catch (e) {
    ElMessage.error('复制失败，请手动选择')
  }
}

/** 抽不出正文时的人话解释（不同状态的应对方式不同） */
function statusHint() {
  const s = readerStatus.value
  if (s === 'ok') return ''
  if (s === 'empty') return '这份文件抽取出来是空的 —— 扫描版 PDF / 纯图片类常见这种，需要 OCR 或手工补「说明」。'
  if (s === 'unsupported') return '这个格式不支持抽正文 —— 仍可点「原文」阅读，检索靠手填「说明」。'
  if (s === 'failed') return '抽取失败：' + (readerError.value || '未知原因')
  if (s === 'pending') return '还在抽取中，稍后重新打开即可。'
  return '未抽取正文。'
}
async function onDownload(row) {
  const resp = await fileApi.download(row.id)
  saveBlob(resp.data, row.originName)
  ElMessage.success('已开始下载')
}

async function onDelete(row) {
  await ElMessageBox.confirm(`确定删除资料「${row.originName}」吗？删除后不可恢复。`, '提示', { type: 'warning' })
  await fileApi.remove(row.id)
  ElMessage.success('已删除')
  load()
}

/** 正文抽取状态 → 徽标文案（这几档含义不同，不能只显示"成功/失败"） */
function textLabel(row) {
  if (!row) return ''
  switch (row.textStatus) {
    case 'ok':
      // 字数用 Intl 千位分隔：`已抽 1234567 字` 读不出量级
      return `已抽 ${NUM.format(row.textChars || 0)} 字`
    case 'empty':
      return '无文字'
    case 'unsupported':
      return '不支持'
    case 'skipped':
      return '太大未抽'
    case 'failed':
      return '抽取失败'
    default:
      return '待抽取'
  }
}

function textTagType(status) {
  if (status === 'ok') return 'success'
  if (status === 'failed') return 'danger'
  if (status === 'empty' || status === 'unsupported' || status === 'skipped') return 'info'
  return 'warning'
}

/** 手填说明：抽不出正文的资料（扫描件/图片/压缩包）就靠它参与检索 */
async function onEditSummary(row) {
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

async function onReextract(row) {
  const r = await fileApi.reextract(row.id)
  ElMessage.success(`已重新抽取：${textLabel(r)}`)
  load()
}

/** 改分类：资料按分类进知识图谱，所以"归类"就是把资料接进图的那一步 */
async function onChangeCategory(row, categoryId) {
  await fileApi.updateCategory(row.id, categoryId || null)
  ElMessage.success(categoryId ? '已归类' : '已取消分类')
  load()
}

onMounted(async () => {
  window.addEventListener('lh-meta-changed', loadCats)
  await loadCats()
  await load()
  const requestedFile = route.query.read
  if (typeof requestedFile === 'string' && /^\d+$/.test(requestedFile)) {
    try { await openReader(await fileApi.detail(requestedFile)) } catch { /* 请求拦截器显示加载失败 */ }
  }
})

onBeforeUnmount(() => {
  ++readerSeq
  window.removeEventListener('lh-meta-changed', loadCats)
})
</script>

<template>
  <div class="page">
    <div class="toolbar">
      <h2 class="page-h2">资料库</h2>
      <span class="tip">PDF / Word / Excel / PPT / 代码 / 文本 上传即**抽正文**，正文会进入知识库检索、主题 wiki 与知识图谱</span>
      <div class="spacer"></div>
      <el-input
        v-model="query.kw"
        placeholder="搜索文件名 / 说明 / 正文"
        clearable
        style="width: 200px"
        @keyup.enter="search"
        @clear="search"
      >
        <template #append>
          <el-button @click="search">搜索</el-button>
        </template>
      </el-input>
      <el-select v-model="query.categoryId" placeholder="全部分类" clearable style="width: 140px" @change="search">
        <el-option v-for="c in categories" :key="c.id" :label="'　'.repeat(c.depth) + c.name" :value="c.id" />
      </el-select>
      <el-button v-if="query.categoryId || query.kw" @click="resetFilter">重置</el-button>
      <el-select v-model="uploadCategoryId" placeholder="上传到分类（可选）" clearable style="width: 160px">
        <el-option v-for="c in categories" :key="c.id" :label="'　'.repeat(c.depth) + c.name" :value="c.id" />
      </el-select>
      <el-button type="primary" :loading="uploading" @click="pickUpload">
        <span class="btn-ico"><svg viewBox="0 0 24 24"><path d="M12 16V4.5M6.5 10 12 4.5 17.5 10M4.5 19.5h15" /></svg></span>上传资料
      </el-button>
      <input ref="uploadInput" type="file" multiple hidden @change="onFileChosen" />
    </div>

    <el-card shadow="never" v-loading="loading">
      <el-table :data="list">
        <el-table-column label="文件名" min-width="240">
          <template #default="{ row }">
            <!--
              裁到 2 行 + title 悬停看全名：origin_name 的列上限是 255 字符，
              不裁的话一行能长到十几行（一行吃掉大半屏）；裁了就必须给 title，
              否则"截断了却没处读全文"。
            -->
            <span class="fname" :title="row.originName">{{ row.originName }}</span>
          </template>
        </el-table-column>
        <el-table-column label="类型" width="90">
          <template #default="{ row }">
            <el-tag size="small" type="info" effect="plain">{{ row.ext || '其他' }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="正文" min-width="180">
          <template #default="{ row }">
            <el-tooltip v-if="row.textError" :content="row.textError" placement="top">
              <el-tag size="small" :type="textTagType(row.textStatus)" effect="plain">{{ textLabel(row) }}</el-tag>
            </el-tooltip>
            <el-tag v-else size="small" :type="textTagType(row.textStatus)" effect="plain">{{ textLabel(row) }}</el-tag>
            <div v-if="row.summary" class="fsummary">{{ row.summary }}</div>
          </template>
        </el-table-column>
        <el-table-column label="分类" width="150">
          <template #default="{ row }">
            <el-select
              :model-value="row.categoryId || undefined"
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
        <el-table-column label="大小" width="100">
          <template #default="{ row }"><span class="num">{{ fmtSize(row.size) }}</span></template>
        </el-table-column>
        <el-table-column label="上传时间" width="165">
          <template #default="{ row }"><span class="num">{{ time(row.createdAt) }}</span></template>
        </el-table-column>
        <!--
          操作列：主操作留在行内，次要操作（说明 / 重命名 / 重抽）收进 ⋯。
          原因：这一列是 fixed="right"，宽度就是它在窄屏上**吃掉**的宽度 ——
          五个按钮要 270px，320px 屏上文件名（min-width 240）直接被挤出视野，
          而这一列永远可见。收进 ⋯ 后主操作只剩三个（阅读/下载/删除）。
        -->
        <el-table-column label="操作" width="196" fixed="right">
          <template #default="{ row }">
            <el-button link type="primary" @click="openReader(row)">阅读</el-button>
            <el-button link type="primary" @click="onDownload(row)">下载</el-button>
            <el-dropdown trigger="click">
              <el-button link type="primary" aria-label="更多操作">⋯</el-button>
              <template #dropdown>
                <el-dropdown-menu>
                  <el-dropdown-item @click="onEditSummary(row)">说明</el-dropdown-item>
                  <el-dropdown-item @click="onRename(row)">重命名</el-dropdown-item>
                  <el-dropdown-item v-if="row.textStatus !== 'ok'" @click="onReextract(row)">重抽正文</el-dropdown-item>
                </el-dropdown-menu>
              </template>
            </el-dropdown>
            <el-button link type="danger" @click="onDelete(row)">删除</el-button>
          </template>
        </el-table-column>
        <template #empty>
          <el-empty description="资料库还是空的，点右上角上传第一份资料" :image-size="80" />
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
            · 检索层正文 {{ readerText.length }} 字 / {{ paragraphs.length }} 段
          </span>
          <span class="reader-sep" />
          <button type="button" class="reader-btn" :class="{ on: readerTab === 'doc' }" @click="switchReaderTab('doc')">
            原文
          </button>
          <button type="button" class="reader-btn" :class="{ on: readerTab === 'text' }" @click="switchReaderTab('text')">
            抽取正文
          </button>
          <span class="reader-spacer" />
          <button type="button" class="reader-btn" @click="onDownload(reader)">下载</button>
          <a class="reader-link" :href="['pdf', 'word', 'markdown'].includes(docKind) ? `/files?read=${encodeURIComponent(reader.id)}` : fileApi.rawUrl(reader.id)" target="_blank" rel="noreferrer">
            <button type="button" class="reader-btn">新标签打开</button>
          </a>
        </div>

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
          <img v-else-if="docKind === 'image'" :src="fileApi.rawUrl(reader.id)" class="reader-img" />
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
          class="reader-body reader-frame"
        >
        <div ref="textSourceRoot" v-loading="layoutLoading || readerLoading" class="reader-text-wrap">
          <p v-if="statusHint()" class="warn-line">{{ statusHint() }}</p>
          <p v-if="readerLayout && readerLayout.status === 'failed'" class="warn-line">
            排版还原失败（{{ readerLayout.error || '未知原因' }}），下面是检索层正文。
          </p>
          <p v-else-if="readerLayout && !layoutUsable && readerLayout.status !== 'ok'" class="hint">
            {{ readerLayout.error || '这份资料没有可还原的版面' }}，下面是检索层正文。
          </p>

          <DocTextView
            v-if="layoutUsable"
            :file-id="reader.id"
            :pages="readerLayout.pages"
            :chars="readerLayout.chars"
            :page-count="readerLayout.pageCount"
            :max-chars="transCaps.maxChars"
            external-translation
            @open-original="openOriginalPage"
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
        </ReaderTranslation>
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
.tip { font-size: 12px; color: var(--app-text-3); }
/* 文件名：裁 2 行 + 只在必要时断行。
   原来只有 `word-break: break-all` —— 那是"任意两个字符之间都能断"，于是 approved 会被切成
   appro/ved，明明有空格和连字符可断。overflow-wrap: anywhere 只在真的放不下时才断。
   裁切是必须的：origin_name 上限 255 字符，不裁的话一行能长到十几行（见 title 属性给全文）。 */
.fname {
  color: var(--app-text-1);
  overflow-wrap: anywhere;
  display: -webkit-box;
  -webkit-line-clamp: 2;
  -webkit-box-orient: vertical;
  overflow: hidden;
}
/* 参与"一眼比大小"的数字用等宽数字，值变化时列不会左右跳 */
.num { font-variant-numeric: tabular-nums; }
/* 分页条：右对齐、给表格一点呼吸 */
.fpage { display: flex; justify-content: flex-end; margin-top: 12px; }
.fsummary { margin-top: 4px; font-size: 12px; line-height: 1.5; color: var(--app-text-3); display: -webkit-box; -webkit-line-clamp: 2; -webkit-box-orient: vertical; overflow: hidden; }
.spacer { flex: 1 1 auto; }
:global(.reader-dialog.el-dialog) { display: flex; flex-direction: column; height: 100dvh; overflow: hidden; }
:global(.reader-dialog .el-dialog__header) { flex: 0 0 auto; }
:global(.reader-dialog .el-dialog__body) { flex: 1 1 auto; min-height: 0; box-sizing: border-box; padding: 0; overflow: hidden; }
.reader { display: flex; flex-direction: column; height: 100%; min-height: 0; gap: 8px; }
.reader-meta { flex: 0 0 auto; flex-wrap: wrap; }
.reader-body { flex: 1 1 auto; min-width: 0; min-height: 0; }
.reader-frame { width: 100%; height: 100%; min-height: 0; border: 0; border-radius: 8px; background: var(--app-bg); }
.reader-original-source { height: 100%; min-height: 0; }
.reader-img { display: block; max-width: 100%; max-height: 100%; margin: 0 auto; border-radius: 8px; }
.reader-media { width: 100%; max-height: 100%; }
.reader-fallback { padding: 24px; color: var(--app-text-2); }
.reader-text-wrap { display: flex; flex-direction: column; height: 100%; min-height: 0; }
.reader-text-wrap :deep(.doc-view) { flex: 1 1 auto; height: auto; min-height: 0; }
.reader-text-head { display: flex; align-items: center; gap: 8px; padding: 0 8px 8px; }
.reader-paras { flex: 1 1 auto; min-height: 0; overflow: auto; padding: 24px 0 40px; background: var(--app-bg); border: 1px solid var(--app-border-weak); border-radius: 10px; }
.para { max-width: 900px; margin: 0 auto; padding: 0 24px; }
.para + .para { margin-top: 18px; padding-top: 18px; border-top: 1px dashed var(--app-border-weak); }
.para-head { display: flex; align-items: center; gap: 8px; color: var(--app-text-3); }
.para-no { font-size: 11px; }
.para-text { margin: 6px 0 0; font-size: 15px; line-height: 1.95; color: var(--app-text-1); white-space: pre-wrap; overflow-wrap: anywhere; }
.warn-line { margin: 0 8px 8px; font-size: 12.5px; color: #b45309; }
@media (max-width: 800px) {
  :global(.reader-dialog.el-dialog) { padding: 10px; }
  .reader-meta { gap: 5px; }
  .para { padding: 0 16px; }
}
</style>
