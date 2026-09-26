<script setup>
import { nextTick, onBeforeUnmount, onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { MdPreview } from 'md-editor-v3'
// 编辑器全局初始化 + 样式（原来在 main.js，为了不占首屏挪到这里）
import '../utils/mdEditorSetup'
import { categoryApi, quickRefApi } from '../api'
import { fixHtmlQuotes } from '../utils/htmlQuotes'
import { isDark } from '../composables/useTheme'

const loading = ref(false)
const list = ref([])
const categories = ref([])
const query = ref({ categoryId: undefined, kw: '' })

const dialogVisible = ref(false)
const saving = ref(false)
const editingId = ref(null)
const form = reactive({ title: '', content: '', categoryId: undefined })

// ---------- 放大预览 ----------
/**
 * 卡片里的正文只够"扫一眼"：列宽 280px，内容按需裁切（不嵌滚动条）。
 * 想看全就点卡片放大 —— 用一个宽对话框按笔记页的舒适排版展示全文。
 */
const viewVisible = ref(false)
const viewRow = ref(null)

function openView(row) {
  viewRow.value = row
  viewVisible.value = true
}

/**
 * 点卡片放大。
 * 但要是用户正在卡片里**选字**（速查卡常被用来选中复制命令），就别弹窗打断他。
 */
function onCardClick(row) {
  const sel = window.getSelection?.()?.toString()
  if (sel && sel.trim()) return
  openView(row)
}

function editFromView() {
  const row = viewRow.value
  viewVisible.value = false
  if (row) openEdit(row)
}

async function copyContent() {
  const text = viewRow.value?.content || ''
  if (!text.trim()) {
    ElMessage.warning('内容为空')
    return
  }
  try {
    await navigator.clipboard.writeText(text)
    ElMessage.success('已复制内容')
  } catch (e) {
    ElMessage.error('复制失败，请手动选择文本')
  }
}

function fmtTime(t) {
  return (t || '').replace('T', ' ').slice(0, 16)
}

// ---------- 卡片内容溢出标记 ----------
/** 卡片正文是裁切的（不嵌滚动条），所以溢出时补一层渐隐，提示"下面还有、可点开放大" */
const gridRef = ref(null)
const clipped = ref({})

async function measureClipped() {
  await nextTick()
  const map = {}
  for (const el of gridRef.value?.querySelectorAll('.ref-body') || []) {
    map[el.dataset.rid] = el.scrollHeight - el.clientHeight > 6
  }
  clipped.value = map
}

async function loadCats() {
  const tree = await categoryApi.tree()
  const flat = []
  ;(function walk(nodes, depth = 0) {
    for (const n of nodes || []) {
      flat.push({ ...n, depth })
      if (n.children?.length) walk(n.children, depth + 1)
    }
  })(tree)
  categories.value = flat
}

async function load() {
  loading.value = true
  try {
    const params = { ...query.value }
    if (!params.categoryId) delete params.categoryId
    if (!params.kw) delete params.kw
    list.value = await quickRefApi.list(params)
    measureClipped()
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

function openAdd() {
  editingId.value = null
  form.title = ''
  form.content = ''
  form.categoryId = undefined
  dialogVisible.value = true
}

function openEdit(row) {
  editingId.value = row.id
  form.title = row.title
  form.content = row.content || ''
  form.categoryId = row.categoryId ?? undefined
  dialogVisible.value = true
}

async function save() {
  if (!form.title.trim()) {
    ElMessage.warning('标题不能为空')
    return
  }
  saving.value = true
  try {
    const payload = { title: form.title.trim(), content: form.content, categoryId: form.categoryId || null }
    if (editingId.value) {
      await quickRefApi.update(editingId.value, payload)
      ElMessage.success('已更新')
    } else {
      await quickRefApi.add(payload)
      ElMessage.success('已添加')
    }
    dialogVisible.value = false
    load()
  } finally {
    saving.value = false
  }
}

async function onDelete(row) {
  await ElMessageBox.confirm(`确定删除速查卡「${row.title}」吗？`, '提示', { type: 'warning' })
  await quickRefApi.remove(row.id)
  ElMessage.success('已删除')
  load()
}

onMounted(async () => {
  window.addEventListener('lh-meta-changed', loadCats)
  window.addEventListener('resize', measureClipped)
  await loadCats()
  load()
})

onBeforeUnmount(() => {
  window.removeEventListener('lh-meta-changed', loadCats)
  window.removeEventListener('resize', measureClipped)
})
</script>

<template>
  <div class="page">
    <div class="toolbar">
      <h2 class="page-h2">速查卡</h2>
      <span class="tip">短平快的命令 / API 签名 / 易错点随手记</span>
      <div class="spacer"></div>
      <el-input
        v-model="query.kw"
        placeholder="搜索速查内容"
        clearable
        style="width: 200px"
        @keyup.enter="search"
        @clear="search"
      >
        <template #append>
          <el-button @click="search">搜索</el-button>
        </template>
      </el-input>
      <el-select v-model="query.categoryId" placeholder="全部分类" clearable style="width: 130px" @change="search">
        <el-option v-for="c in categories" :key="c.id" :label="'　'.repeat(c.depth) + c.name" :value="c.id" />
      </el-select>
      <el-button v-if="query.categoryId || query.kw" @click="resetFilter">重置</el-button>
      <el-button type="primary" @click="openAdd">＋ 新建速查卡</el-button>
    </div>

    <div v-loading="loading">
      <el-empty v-if="!list.length" description="还没有速查卡，点右上角添加一张" />
      <div v-else ref="gridRef" class="ref-grid">
        <el-card
          v-for="r in list"
          :key="r.id"
          shadow="hover"
          class="ref-card"
          role="button"
          tabindex="0"
          :aria-label="'放大查看「' + r.title + '」'"
          @click="onCardClick(r)"
          @keydown.enter.prevent="openView(r)"
          @keydown.space.prevent="openView(r)"
        >
          <div class="ref-head">
            <span class="ref-title">{{ r.title }}</span>
            <!-- 卡片整体可点；这一列工具按钮要拦住冒泡，别把"删除"也变成"打开" -->
            <span class="ref-acts" @click.stop>
              <button type="button" class="ref-icon" title="放大查看" aria-label="放大查看" @click="openView(r)">
                <svg viewBox="0 0 24 24" width="14" height="14" fill="none" stroke="currentColor" stroke-width="2"
                     stroke-linecap="round" stroke-linejoin="round">
                  <path d="M15 3h6v6M9 21H3v-6M21 3l-7 7M3 21l7-7" />
                </svg>
              </button>
              <el-dropdown trigger="click">
                <el-button link class="more" aria-label="更多操作">⋯</el-button>
                <template #dropdown>
                  <el-dropdown-menu>
                    <el-dropdown-item @click="openView(r)">放大查看</el-dropdown-item>
                    <el-dropdown-item @click="openEdit(r)">编辑</el-dropdown-item>
                    <el-dropdown-item divided @click="onDelete(r)">删除</el-dropdown-item>
                  </el-dropdown-menu>
                </template>
              </el-dropdown>
            </span>
          </div>
          <div class="ref-body md-mini" :class="{ 'is-clipped': clipped[r.id] }" :data-rid="r.id">
            <MdPreview :modelValue="fixHtmlQuotes(r.content || '')" :theme="isDark ? 'dark' : 'light'" previewTheme="github" />
          </div>
          <div class="ref-foot">
            <el-tag v-if="r.categoryName" size="small" type="info">{{ r.categoryName }}</el-tag>
            <span class="ref-time">{{ fmtTime(r.updatedAt) }}</span>
          </div>
        </el-card>
      </div>
    </div>

    <!-- 放大预览：按笔记页的排版读全文，卡片里的 280px 只是索引 -->
    <el-dialog
      v-model="viewVisible"
      :title="viewRow?.title || '速查卡'"
      width="min(880px, 92vw)"
      top="6vh"
      destroy-on-close
      append-to-body
      class="ref-view-dialog"
    >
      <div class="view-meta">
        <el-tag v-if="viewRow?.categoryName" size="small" type="info">{{ viewRow.categoryName }}</el-tag>
        <span class="ref-time">{{ fmtTime(viewRow?.updatedAt) }}</span>
      </div>
      <div class="view-body">
        <MdPreview
          :modelValue="fixHtmlQuotes(viewRow?.content || '*暂无内容*')"
          :theme="isDark ? 'dark' : 'light'"
          previewTheme="github"
        />
      </div>
      <template #footer>
        <el-button @click="copyContent">复制内容</el-button>
        <el-button @click="editFromView">编辑</el-button>
        <el-button type="primary" @click="viewVisible = false">关闭</el-button>
      </template>
    </el-dialog>

    <el-dialog
      v-model="dialogVisible"
      :title="editingId ? '编辑速查卡' : '新建速查卡'"
      width="640px"
      top="8vh"
      destroy-on-close
      append-to-body
    >
      <div class="dlg-form">
        <div class="dlg-row">
          <el-input v-model="form.title" placeholder="标题，如：Docker 常用命令" maxlength="200" />
          <el-select v-model="form.categoryId" placeholder="选择分类（可选）" clearable style="width: 150px">
            <el-option v-for="c in categories" :key="c.id" :label="c.name" :value="c.id" />
          </el-select>
        </div>
        <MdPreview class="dlg-preview" :modelValue="fixHtmlQuotes(form.content || '*暂无内容*')" :theme="isDark ? 'dark' : 'light'" previewTheme="github" />
        <el-input
          v-model="form.content"
          type="textarea"
          :rows="6"
          placeholder="速查内容，支持 Markdown：表格、代码块、要点列表…"
        />
      </div>
      <template #footer>
        <el-button @click="dialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="save">保存</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped>
.tip {
  font-size: 12px;
  color: var(--app-text-3);
}

.ref-grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(280px, 1fr));
  gap: 14px;
}

.ref-card {
  cursor: pointer;
  transition: box-shadow var(--dur-fast) var(--ease), transform var(--dur-fast) var(--ease);
}

.ref-card:hover {
  transform: translateY(-1px);
}

.ref-card:focus-visible {
  outline: 2px solid color-mix(in srgb, var(--app-brand) 55%, transparent);
  outline-offset: 2px;
}

.ref-card :deep(.el-card__body) {
  padding: 14px 16px;
  display: flex;
  flex-direction: column;
  gap: 10px;
}

.ref-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
}

.ref-title {
  font-weight: 600;
  color: var(--app-text-1);
  min-width: 0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.ref-acts {
  display: flex;
  align-items: center;
  gap: 2px;
  flex: none;
}

/* 放大按钮平时淡出，悬停卡片才显形（不抢标题的视线） */
.ref-icon {
  display: grid;
  place-items: center;
  width: 24px;
  height: 24px;
  padding: 0;
  border: 0;
  border-radius: 6px;
  background: transparent;
  color: var(--app-text-3);
  cursor: pointer;
  opacity: 0;
  transition: opacity var(--dur-fast) ease, color var(--dur-fast) ease, background var(--dur-fast) ease;
}

.ref-card:hover .ref-icon,
.ref-icon:focus-visible {
  opacity: 1;
}

.ref-icon:hover {
  color: var(--app-brand-deep);
  background: var(--app-brand-soft);
}

.more {
  font-size: 16px;
  letter-spacing: 2px;
  color: var(--app-text-3);
}

/* 卡片正文：按需裁切，**不嵌滚动条**（嵌套滚动条是"乱"的主要来源），
   溢出时底部渐隐，点卡片看全文 */
.ref-body {
  position: relative;
  max-height: 172px;
  overflow: hidden;
  border-top: 1px dashed var(--app-border);
  padding-top: 8px;
}

.ref-body.is-clipped::after {
  content: '';
  position: absolute;
  inset-inline: 0;
  bottom: 0;
  height: 32px;
  background: linear-gradient(to bottom, transparent, var(--app-card) 78%);
  pointer-events: none;
}

/* ---- 迷你排版：全局那套是给笔记页宽栏调的（15px/1.8、表格大内距），
   放进 280px 卡片就又挤又乱，这里整体降一档并收紧 ---- */
.ref-body.md-mini :deep(.md-editor-preview.md-editor-preview) {
  font-size: 12.5px;
  line-height: 1.7;
  padding: 0;
  word-break: normal;
  overflow-wrap: anywhere;
}

.ref-body.md-mini :deep(.md-editor-preview.md-editor-preview p) {
  margin: 0.3em 0;
}

.ref-body.md-mini :deep(.md-editor-preview.md-editor-preview h1),
.ref-body.md-mini :deep(.md-editor-preview.md-editor-preview h2),
.ref-body.md-mini :deep(.md-editor-preview.md-editor-preview h3),
.ref-body.md-mini :deep(.md-editor-preview.md-editor-preview h4),
.ref-body.md-mini :deep(.md-editor-preview.md-editor-preview h5),
.ref-body.md-mini :deep(.md-editor-preview.md-editor-preview h6) {
  font-size: 13px;
  margin: 0.5em 0 0.25em;
  padding-bottom: 0;
  border-bottom: 0;
  word-break: normal;
  overflow-wrap: anywhere;
}

.ref-body.md-mini :deep(.md-editor-preview.md-editor-preview ul),
.ref-body.md-mini :deep(.md-editor-preview.md-editor-preview ol) {
  margin: 0.25em 0;
  padding-left: 1.05em;
}

.ref-body.md-mini :deep(.md-editor-preview.md-editor-preview li) {
  margin: 0.1em 0;
}

.ref-body.md-mini :deep(.md-editor-preview.md-editor-preview blockquote) {
  margin: 0.3em 0;
  padding: 0.05em 0.65em;
}

.ref-body.md-mini :deep(.md-editor-preview.md-editor-preview hr) {
  margin: 0.6em 0;
}

.ref-body.md-mini :deep(.md-editor-preview.md-editor-preview code) {
  font-size: 0.94em;
}

.ref-body.md-mini :deep(.md-editor-preview.md-editor-preview code:not(.md-editor-code-block)) {
  padding: 0.08em 0.3em;
}

/* 表格：不在卡里横竖滚动，按内容撑开被卡片裁掉即可（放大后看全） */
.ref-body.md-mini :deep(.md-editor-preview.md-editor-preview table) {
  width: max-content;
  min-width: 100%;
  margin: 0.3em 0;
  overflow: hidden;
  font-size: 0.97em;
}

.ref-body.md-mini :deep(.md-editor-preview.md-editor-preview th),
.ref-body.md-mini :deep(.md-editor-preview.md-editor-preview td) {
  padding: 0.2em 0.5em;
  white-space: nowrap;
}

.ref-body.md-mini :deep(.md-editor-preview.md-editor-preview pre) {
  margin: 0.3em 0;
  overflow: hidden;
}

/* 代码块在卡片里去掉编辑器配件（语言条/复制按钮/行号栏），只留代码本身；
   这些在放大预览里都还在 */
.ref-body.md-mini :deep(.md-editor-code-head) {
  display: none;
}

.ref-body.md-mini :deep([rn-wrapper]) {
  display: none;
}

.ref-body.md-mini :deep(.md-editor-code) {
  margin: 0.3em 0;
  overflow: hidden;
}

/* 主题给代码正文的包装 span 设了 overflow:auto（宽度 100% 却仍会溢出），
   只压 pre / .md-editor-code 不够 —— 滚动条会画在这个 span 上。
   卡片里统一裁切，长命令横向滚动留给放大预览。 */
.ref-body.md-mini :deep(.md-editor-preview.md-editor-preview .md-editor-code pre code .md-editor-code-block) {
  overflow: hidden;
}

.ref-foot {
  display: flex;
  align-items: center;
  justify-content: space-between;
}

.ref-time {
  font-size: 11px;
  color: var(--app-text-3);
}

/* ---- 放大预览 ---- */
.view-meta {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-bottom: 10px;
}

.view-body {
  max-height: 66vh;
  overflow: auto;
  padding: 4px 2px;
}

.dlg-form {
  display: flex;
  flex-direction: column;
  gap: 10px;
}

.dlg-row {
  display: flex;
  gap: 10px;
}

.dlg-preview {
  border: 1px solid var(--app-border);
  border-radius: 6px;
  padding: 6px 10px;
  max-height: 200px;
  overflow: auto;
  background: var(--app-bg);
}
</style>
