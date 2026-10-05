<script setup>
import { nextTick, onBeforeUnmount, onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
// 正文渲染不再走 md-editor（老版预览），改用笔记页那套块编辑器（tiptap）的只读模式：
// 代码块、提示块、折叠块、表格、任务列表…与笔记里看到的是同一套渲染与同一套样式。
import BlockPreview from '../components/BlockPreview.vue'
import { categoryApi, quickRefApi } from '../api'
import { fixHtmlQuotes } from '../utils/htmlQuotes'

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
function onCardClick(row, ev) {
  // 代码块（复制 / 折叠 / 选中命令）是它自己的交互区，点它别顺带放大大图
  if (ev?.target?.closest?.('.code-block-cm')) return
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
          @click="onCardClick(r, $event)"
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
          <div class="ref-body" :class="{ 'is-clipped': clipped[r.id] }" :data-rid="r.id">
            <BlockPreview :content="fixHtmlQuotes(r.content || '')" readonly compact />
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
        <BlockPreview :content="fixHtmlQuotes(viewRow?.content || '*暂无内容*')" readonly />
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
        <!-- 实时预览：与卡片/笔记同一套只读渲染器，看到的排版就是要存的排版 -->
        <div class="dlg-preview">
          <BlockPreview :content="fixHtmlQuotes(form.content || '*暂无内容*')" readonly compact />
        </div>
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
   溢出时底部渐隐，点卡片看全文。
   min-height 与 max-height 同为 172px：内容多的卡被截到 172，内容少的卡也撑到 172 —— 否则
   "只有两行"的卡会矮一大截，而 CSS Grid **每行高度独立**，两行之间就会参差不齐（实测现象）。
   flex:1 让正文吃掉卡片内的剩余空间，配合 grid 项默认 stretch，同一行的卡等高。 */
.ref-body {
  position: relative;
  flex: 1;
  min-height: 172px;
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

/* 正文渲染已换成笔记页那套块编辑器（BlockPreview 只读模式）：
   卡片的紧凑排版不再写在这里 —— 它是渲染器自己的一个档位（<BlockPreview compact>），
   与放大预览的 A4 排版共用同一份源码，避免"同一段正文两套迷你样式"再次各改各的。 */

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
