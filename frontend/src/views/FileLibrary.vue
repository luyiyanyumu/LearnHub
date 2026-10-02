<script setup>
import { computed, nextTick, onBeforeUnmount, onMounted, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { categoryApi, fileApi, saveBlob } from '../api'
import PdfReader from '../components/PdfReader.vue'
import DocTextView from '../components/DocTextView.vue'

const loading = ref(false)
const uploading = ref(false)
const list = ref([])
const categories = ref([])
const query = ref({ categoryId: undefined, kw: '' })
const uploadCategoryId = ref(undefined)
const uploadInput = ref(null)

function fmtSize(bytes) {
  if (bytes == null) return '-'
  if (bytes < 1024) return bytes + ' B'
  if (bytes < 1024 * 1024) return (bytes / 1024).toFixed(1) + ' KB'
  if (bytes < 1024 * 1024 * 1024) return (bytes / 1024 / 1024).toFixed(1) + ' MB'
  return (bytes / 1024 / 1024 / 1024).toFixed(2) + ' GB'
}

function time(v) {
  return (v || '').replace('T', ' ').slice(0, 16)
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

async function load() {
  loading.value = true
  try {
    const params = { ...query.value }
    if (!params.categoryId) delete params.categoryId
    if (!params.kw) delete params.kw
    list.value = await fileApi.list(params)
  } finally {
    loading.value = false
  }
}

function search() {
  load()
}

function resetFilter() {
  query.value = { categoryId: undefined, kw: '' }
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
    load()
  } finally {
    uploading.value = false
  }
}

/**
 * 资料阅读器。
 *
 * 两条路各司其职：
 *   · **原文** —— 走 /api/files/{id}/raw（Content-Type 给对 + inline），
 *     PDF 交给浏览器自带的阅读器渲染，图片/音视频直接显示，不用自己做解析；
 *   · **抽取正文** —— 走 /api/files/{id}/text，是检索层实际用的那份文本，
 *     抽不出时把原因显示出来（而不是给一片空白）。
 */
const reader = ref(null)
const readerTab = ref('doc')
const pdfReader = ref(null)
const readerText = ref('')
const readerStatus = ref('')
const readerError = ref('')
const readerLoading = ref(false)
/**
 * 排版还原后的正文（{ pages:[{page,columns,blocks}] }）。
 *
 * 与 readerText 的关系：readerText 是**检索层那份纯文本**（PDF 两栏会逐行交错），
 * 只有排版还原不了（非 PDF、扫描件、失败）时才拿它兜底显示。
 * 懒加载：默认打开的是「原文」，切到「抽取正文」才去拉，PDF 解析那 1 秒不该让开卷就等。
 */
const readerLayout = ref(null)
const layoutLoading = ref(false)

/** 有没有可用的排版还原结果（决定「抽取正文」是文档视图还是分段兜底视图） */
const layoutUsable = computed(() =>
  readerLayout.value?.status === 'ok' && (readerLayout.value.pages?.length || 0) > 0)

async function ensureLayout() {
  const id = reader.value?.id
  if (!id || readerLayout.value || layoutLoading.value) return
  layoutLoading.value = true
  try {
    readerLayout.value = await fileApi.layout(id)
  } catch (e) {
    /* 拦截器已提示；这里把状态降级，让下面的分段正文继续可用 */
    readerLayout.value = { status: 'failed', error: e?.message || '', pages: [], chars: 0, pageCount: 0 }
  } finally {
    layoutLoading.value = false
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

/** 能交给浏览器原生渲染的类型（iframe/img/video） */
const docKind = computed(() => {
  const ext = (reader.value?.ext || '').toLowerCase()
  if (ext === 'pdf') return 'pdf'
  if (['png', 'jpg', 'jpeg', 'gif', 'webp', 'svg', 'bmp'].includes(ext)) return 'image'
  if (['mp4', 'webm', 'ogg'].includes(ext)) return 'video'
  if (['mp3', 'wav', 'flac', 'm4a'].includes(ext)) return 'audio'
  return 'other'
})

/** 译文缓存：key = 段落序号，翻过的不再重复翻（翻译要花模型调用，重复翻纯浪费） */
const translations = ref({})
const translatingKey = ref('')
const targetLang = ref('简体中文')
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

function tooLong(p) {
  return p.length > (transCaps.value.maxChars || 4000)
}

async function translatePara(p) {
  if (translations.value[p.key] || translatingKey.value) return
  translatingKey.value = p.key
  try {
    const r = await fileApi.translate(reader.value.id, p.text, targetLang.value)
    translations.value = {
      ...translations.value,
      [p.key]: { text: r.translation, model: r.model, profile: r.profile, ms: r.ms },
    }
  } catch (e) {
    ElMessage.error('翻译失败：' + (e?.message || e))
  } finally {
    translatingKey.value = ''
  }
}

async function translateFirst(n) {
  const list = paragraphs.value.slice(0, n)
  for (const p of list) {
    await translatePara(p)
  }
}
async function openReader(row) {
  reader.value = row
  readerTab.value = 'doc'
  readerText.value = ''
  readerStatus.value = ''
  readerError.value = ''
  readerLayout.value = null
  readerLoading.value = true
  try {
    fileApi.translateCaps().then((c) => { transCaps.value = c || {} }).catch(() => {})
    translations.value = {}
    const d = await fileApi.text(row.id)
    readerText.value = d.text || ''
    readerStatus.value = d.textStatus || ''
    readerError.value = d.textError || ''
  } catch (e) {
    /* 拦截器已提示 */
  } finally {
    readerLoading.value = false
  }
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
      return `已抽 ${row.textChars} 字`
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
  load()
})

onBeforeUnmount(() => {
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
            <span class="fname">{{ row.originName }}</span>
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
              <el-option v-for="c in categories" :key="c.id" :label="'　'.repeat(c.depth) + c.name" :value="c.id" />
            </el-select>
          </template>
        </el-table-column>
        <el-table-column label="大小" width="100">
          <template #default="{ row }">{{ fmtSize(row.size) }}</template>
        </el-table-column>
        <el-table-column label="上传时间" width="165">
          <template #default="{ row }">{{ time(row.createdAt) }}</template>
        </el-table-column>
        <el-table-column label="操作" width="220" fixed="right">
          <template #default="{ row }">
            <el-button link type="primary" @click="openReader(row)">阅读</el-button>
          <el-button link type="primary" @click="onDownload(row)">下载</el-button>
            <el-button link type="primary" @click="onEditSummary(row)">说明</el-button>
            <el-button
              v-if="row.textStatus !== 'ok'"
              link
              type="primary"
              @click="onReextract(row)"
            >重抽</el-button>
            <el-button link type="danger" @click="onDelete(row)">删除</el-button>
          </template>
        </el-table-column>
        <template #empty>
          <el-empty description="资料库还是空的，点右上角上传第一份资料" :image-size="80" />
        </template>
      </el-table>
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
      @close="reader = null"
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
          <template v-if="readerTab === 'text'">
            <span class="reader-sep" />
            <select v-model="targetLang" class="reader-select" title="翻译目标语言">
              <option v-for="l in ['简体中文', 'English', '日本語', '한국어']" :key="l" :value="l">{{ l }}</option>
            </select>
            <!-- 文档视图自带逐段翻译控件，这里的批量按钮只服务分段兜底视图 -->
            <button
              v-if="!layoutUsable"
              type="button"
              class="reader-btn"
              :disabled="!paragraphs.length"
              @click="translateFirst(5)"
            >
              翻译前 5 段
            </button>
          </template>
          <span class="reader-spacer" />
          <button type="button" class="reader-btn" @click="onDownload(reader)">下载</button>
          <a class="reader-link" :href="fileApi.rawUrl(reader.id)" target="_blank" rel="noreferrer">
            <button type="button" class="reader-btn">新标签打开</button>
          </a>
        </div>

        <!-- 原文：PDF 走浏览器自带阅读器（FitH = 按宽度自适应，避免横向滚动条）；
             图片/音视频直接显示 -->
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
            class="reader-frame"
          />
          <img v-else-if="docKind === 'image'" :src="fileApi.rawUrl(reader.id)" class="reader-img" />
          <video v-else-if="docKind === 'video'" :src="fileApi.rawUrl(reader.id)" controls class="reader-media"></video>
          <audio v-else-if="docKind === 'audio'" :src="fileApi.rawUrl(reader.id)" controls></audio>
          <div v-else class="reader-fallback">
            <p class="hint">这种格式浏览器不能直接渲染，请用「下载」或「新标签打开」。</p>
          </div>
        </div>

        <!--
          抽取正文：**优先**用后端排版还原出来的结构（标题/章节/段落/列表，按页回传），
          照原文档排版；还原不了（非 PDF / 扫描件 / 失败）时才退回"检索层纯文本分段"，
          并保留下面的逐段翻译 —— 两条路都不会让这一页空着。
        -->
        <div v-show="readerTab === 'text'" v-loading="layoutLoading || readerLoading" class="reader-body reader-text-wrap">
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
            :target-lang="targetLang"
            :max-chars="transCaps.maxChars"
            @open-original="openOriginalPage"
          />

          <!-- 兜底：检索层那份纯文本，按段落读、逐段翻译 -->
          <template v-else>
            <div class="reader-text-head">
              <span class="hint">按段落读：点每段右上「译」即可逐段翻译</span>
              <span class="spacer" />
              <el-button size="small" text :disabled="!readerText" @click="copyReaderText">复制正文</el-button>
            </div>
            <p v-if="transCaps.profile" class="hint trans-cap">
              翻译用「{{ transCaps.profile }} / {{ transCaps.model }}」（可在「模型参数」里换档案；单段上限 {{ transCaps.maxChars }} 字）
            </p>
            <!-- 按段落翻译：整篇翻会撞输出上限（实测两万字就被截断），读到哪里翻到哪里更稳 -->
            <div class="reader-paras">
              <div v-for="p in paragraphs" :key="p.key" class="para">
                <div class="para-head">
                  <span class="para-no">¶{{ p.key + 1 }}</span>
                  <el-button
                    size="small"
                    text
                    :loading="translatingKey === p.key"
                    :disabled="tooLong(p) || translatingKey !== ''"
                    @click="translatePara(p)"
                  >
                    {{ translations[p.key] ? '重译' : '译' }}
                  </el-button>
                  <span v-if="tooLong(p)" class="hint">超单段上限，请选中更小的一段</span>
                </div>
                <p class="para-text">{{ p.text }}</p>
                <div v-if="translations[p.key]" class="para-trans">
                  <span class="trans-tag">译 · {{ translations[p.key].profile || translations[p.key].model }}</span>
                  <p class="trans-text">{{ translations[p.key].text }}</p>
                </div>
              </div>
              <p v-if="!paragraphs.length" class="hint">（无正文）</p>
            </div>
          </template>
        </div>
      </div>
    </el-dialog>
</template>
/* ============ 全屏阅读器 ============ */
/* 弹窗本体铺满视口，并把内边距归零：阅读需要"整块视野" */
.reader-dialog :deep(.el-dialog__body) {
  height: calc(100vh - 56px);
  padding: 0 20px 16px;
  overflow: hidden;
}
.reader {
  display: flex;
  flex-direction: column;
  height: 100%;
  gap: 8px;
}
/* 阅读器工具条：外观全部来自全局 .reader-toolbar（与 PDF 工具条同一套），这里只留位置 */
.reader-meta {
  flex: 0 0 auto;
  margin-bottom: 8px;
}
.reader-body {
  flex: 1 1 auto;
  min-height: 0;
}
/* PDF：填满剩余高度；#view=FitH 让它按宽度自适应，不再出现横向滚动条 */
.reader-frame {
  width: 100%;
  height: 100%;
  border: 0;
  border-radius: 8px;
  background: var(--app-bg);
}
.reader-img {
  max-width: 100%;
  max-height: 100%;
  display: block;
  margin: 0 auto;
  border-radius: 8px;
}
.reader-media {
  width: 100%;
  max-height: 100%;
}
.reader-fallback {
  padding: 24px 0;
}
/* 文本模式：居中窄栏 + 大字号高行距，接近纸质阅读 */
.reader-text-wrap {
  display: flex;
  flex-direction: column;
  min-height: 0;
}
.reader-text-head {
  display: flex;
  align-items: center;
  gap: 8px;
}
.reader-paras {
  flex: 1 1 auto;
  min-height: 0;
  overflow: auto;
  margin-top: 6px;
  padding: 24px 0 40px;
  background: var(--app-bg);
  border: 1px solid var(--app-border-weak);
  border-radius: 10px;
}
/* 每一段自己也是"阅读栏"，宽度上限让行长可控（一屏 70~80 字最舒服） */
.para {
  max-width: 900px;
  margin: 0 auto;
  padding: 0 24px;
}
.para + .para {
  margin-top: 18px;
  padding-top: 18px;
  border-top: 1px dashed var(--app-border-weak);
}
.para-head {
  display: flex;
  align-items: center;
  gap: 8px;
  opacity: 0.65;
}
.para:hover .para-head {
  opacity: 1;
}
.para-no {
  font-size: 11px;
  color: var(--app-text-3);
}
.para-text {
  margin: 6px 0 0;
  font-size: 15px;
  line-height: 1.95;
  color: var(--app-text-1);
  white-space: pre-wrap;
  word-break: break-word;
}
.para-trans {
  margin-top: 10px;
  padding: 10px 14px;
  border-left: 3px solid color-mix(in srgb, var(--app-brand) 55%, transparent);
  background: var(--app-brand-soft);
  border-radius: 0 8px 8px 0;
}
.trans-tag {
  font-size: 11px;
  color: var(--app-brand-deep);
}
.trans-text {
  margin: 4px 0 0;
  font-size: 15px;
  line-height: 1.9;
  color: var(--app-text-1);
  white-space: pre-wrap;
  word-break: break-word;
}
.reader-text {
  flex: 1 1 auto;
  min-height: 0;
  overflow: auto;
  margin: 6px 0 0;
  padding: 12px;
  background: var(--app-bg);
  border: 1px solid var(--app-border-weak);
  border-radius: 8px;
  font-size: 13px;
  line-height: 1.75;
  white-space: pre-wrap;
  word-break: break-word;
  color: var(--app-text-1);
}
.spacer {
  flex: 1 1 auto;
}
.warn-line {
  margin: 0 0 4px;
  font-size: 12.5px;
  color: #b45309;
}
<style scoped>
.tip {
  font-size: 12px;
  color: var(--app-text-3);
}

.fname {
  color: var(--app-text-1);
  word-break: break-all;
}

/* 手填说明：抽不出正文的资料靠它参与检索，值得在列表里露出来 */
.fsummary {
  margin-top: 4px;
  font-size: 12px;
  line-height: 1.5;
  color: var(--app-text-3);
  display: -webkit-box;
  -webkit-line-clamp: 2;
  -webkit-box-orient: vertical;
  overflow: hidden;
}
/* ============ 全屏阅读器（追加在样式末尾：同优先级下最后一条生效，避免被旧规则盖掉）============ */
/* 注意 :deep 必须写在**后代位置**：`.reader-dialog :deep(.el-dialog__body)`
   会编译成 `.reader-dialog[data-v] .el-dialog__body` ✓ 匹配 EP 内部元素；
   写成 `:deep(.reader-dialog) .el-dialog__body` 会变成 `[data-v] .reader-dialog .el-dialog__body` ✗ 不匹配（实测弹窗高度算不出来，PDF 只剩 154px 高） */
.reader-dialog :deep(.el-dialog__body) {
  height: calc(100vh - 56px);
  padding: 0 20px 16px;
  overflow: hidden;
}
/* 直接给视口高度，不依赖父容器能不能被撑开：
   实测 el-dialog 的 fullscreen 没有把 body 拉高（body 只有 185px，PDF 被压成 154px 高的细条），
   而这层父级高度受 EP 内部 flex 影响、不好控制 —— 与其跟它较劲，不如让 iframe 自己拿到高度。 */
.reader-frame {
  /* PDF 走 PdfReader 组件（自带画布与滚动区），这里只给一个实在的高度 */
  height: calc(100vh - 150px);
  min-height: 480px;
  display: block;
}
.reader-body {
  height: calc(100vh - 150px);
}
.reader-body {
  flex: 1 1 auto;
  min-height: 0;
}
.reader-paras {
  padding: 24px 0 40px !important;
}
.para {
  max-width: 900px !important;
  margin: 0 auto !important;
  padding: 0 24px !important;
}
.para-text {
  font-size: 15px !important;
  line-height: 1.95 !important;
}
.trans-text {
  font-size: 15px !important;
  line-height: 1.9 !important;
}
</style>
