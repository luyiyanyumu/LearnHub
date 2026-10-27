<script setup>
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Document, Download, Folder, MoreFilled, Plus, Search } from '@element-plus/icons-vue'
import { categoryApi, tagApi, noteApi, folderApi, saveBlob } from '../api'
import FolderExplorerToolbar from '../components/FolderExplorerToolbar.vue'
import FolderMoveDialog from '../components/FolderMoveDialog.vue'
import StateView from '../components/StateView.vue'
import { folderScopeParams, folderPath } from '../utils/folders'
import { folderExplorerRows, isExplorerFolder, folderExplorerScope, folderExplorerQuery } from '../utils/folderExplorer'
import { newNoteDraftQuery } from '../composables/useNoteTabs.js'
import { stripLeadingDocTitle } from '../utils/mdTitle'
import {
  IMPORT_ACCEPT,
  IMPORT_MAX_BYTES,
  isHtmlFile,
  isMarkdownFile,
  readNoteFile,
  titleFromFileName,
} from '../utils/importNote'

const router = useRouter()
const route = useRoute()
const loading = ref(false)
/** 主数据加载失败原因。失败必须与「空数据」分开表达：只留一条 toast 的话滑走就没痕迹了 */
const loadError = ref('')
const exporting = ref(false)
const importing = ref(false)
/** 隐藏的文件选择框：点「导入」按钮时程序化触发 */
const importInput = ref(null)
const list = ref([])
const total = ref(0)
const query = ref({ page: 1, size: 10, categoryId: undefined, tagId: undefined, kw: '' })
const categories = ref([])
const tags = ref([])
const folders = ref([])
const foldersLoading = ref(false)
const selectedFolder = ref(folderExplorerScope(route.query))
const selectedIds = ref([])
const noteTable = ref(null)
const explorerToolbar = ref(null)
const moveVisible = ref(false)
const moveIds = ref([])
const folderTitle = computed(() => selectedFolder.value === null ? '全部笔记'
  : selectedFolder.value === 0 ? '笔记根目录' : folderPath(folders.value, selectedFolder.value))
const explorerRows = computed(() => folderExplorerRows(folders.value, list.value, {
  scope: selectedFolder.value, keyword: query.value.kw, kind: 'note',
}))
const visibleFolderCount = computed(() => explorerRows.value.filter(isExplorerFolder).length)
let loadSeq = 0
let foldersSeq = 0

async function loadFolders() {
  const seq = ++foldersSeq
  foldersLoading.value = true
  try {
    const data = await folderApi.list('note')
    if (seq !== foldersSeq) return
    folders.value = data || []
    if (selectedFolder.value > 0 && !folders.value.some(f => f.id === selectedFolder.value)) {
      selectFolder(0)
    }
  } finally {
    if (seq === foldersSeq) foldersLoading.value = false
  }
}

function selectFolder(value) {
  if (value === selectedFolder.value) return
  clearDirectorySelection()
  selectedFolder.value = value
  router.replace({ path: route.path, query: folderExplorerQuery(route.query, value), hash: route.hash })
  search()
}

function clearDirectorySelection() {
  noteTable.value?.clearSelection()
  selectedIds.value = []
  moveVisible.value = false
  moveIds.value = []
}

async function foldersChanged() {
  await loadFolders()
  await load()
}

function openMove(row) {
  if (loading.value || isExplorerFolder(row)) return
  moveIds.value = row ? [row.id] : [...selectedIds.value]
  if (moveIds.value.length) moveVisible.value = true
}

async function onMoved() {
  noteTable.value?.clearSelection()
  query.value.page = 1
  await Promise.all([loadFolders(), load()])
}

function noteRoute(path) {
  return { path, query: selectedFolder.value === null ? {} : { folderId: String(selectedFolder.value) } }
}

watch(() => folderExplorerScope(route.query), scope => {
  if (scope === selectedFolder.value) return
  clearDirectorySelection()
  selectedFolder.value = scope
  search()
})

function onSelectionChange(rows) {
  selectedIds.value = rows.filter(row => !isExplorerFolder(row)).map(row => row.id)
}

function openRow(row) {
  if (isExplorerFolder(row)) selectFolder(row.id)
  else router.push(noteRoute(`/notes/${row.id}`))
}

function onRowDoubleClick(row, _column, event) {
  if (event?.target?.closest?.('button, a, input, [role="button"], .el-checkbox, .el-dropdown')) return
  openRow(row)
}

function folderSummary(row) {
  return [row.childrenCount ? `${row.childrenCount} 个子文件夹` : '', `${row.itemCount || 0} 篇笔记`].filter(Boolean).join(' · ')
}

function manageFolder(action, row) {
  explorerToolbar.value?.manageFolder(action, row)
}

function onNoteCommand(action, row) {
  if (action === 'move') openMove(row)
  else if (action === 'delete') return onDelete(row)
  else if (action === 'md' || action === 'html') return exportNote(row, action)
}

async function loadCategories() {
  categories.value = flatten(await categoryApi.tree())
}
function flatten(nodes, depth = 0, out = []) {
  for (const n of nodes || []) {
    out.push({ ...n, depth })
    if (n.children?.length) flatten(n.children, depth + 1, out)
  }
  return out
}

async function loadTags() {
  tags.value = await tagApi.list()
}

async function load() {
  const seq = ++loadSeq
  loading.value = true
  loadError.value = ''
  noteTable.value?.clearSelection()
  selectedIds.value = []
  try {
    const params = { ...query.value, ...folderScopeParams(selectedFolder.value) }
    if (!params.categoryId) delete params.categoryId
    if (!params.tagId) delete params.tagId
    if (!params.kw) delete params.kw
    const data = await noteApi.page(params)
    if (seq !== loadSeq) return
    noteTable.value?.clearSelection()
    selectedIds.value = []
    list.value = data.list
    total.value = data.total
    loadError.value = ''
  } catch (e) {
    // 主数据请求失败：留下可重试的持久状态。
    // 此前只有拦截器的一条 toast，失败后表格退化成「这里还没有内容…」，
    // 等于把故障说成了「没有数据」，用户会以为笔记丢了。
    // 与 load() 同一套序号判断，避免慢响应覆盖新一次加载的结果。
    if (seq !== loadSeq) return
    loadError.value = e?.message || '请求失败'
  } finally {
    if (seq === loadSeq) loading.value = false
  }
}

function search() {
  query.value.page = 1
  load()
}

function resetFilter() {
  query.value = { page: 1, size: 10, categoryId: undefined, tagId: undefined, kw: '' }
  load()
}

async function onDelete(row) {
  if (!row || isExplorerFolder(row)) return
  await ElMessageBox.confirm(`确定删除笔记「${row.title}」吗？`, '提示', { type: 'warning' })
  await noteApi.remove(row.id)
  ElMessage.success('已删除')
  if (list.value.length === 1 && query.value.page > 1) query.value.page--
  await Promise.all([load(), loadFolders()])
}

function openNew() {
  const target = noteRoute('/notes/new')
  router.push({ ...target, query: newNoteDraftQuery(target.query) })
}

/** 点「导入」= 打开隐藏的文件选择框（accept 在模板上，与导入工具同一份常量） */
function pickImport() {
  importInput.value?.click()
}

/** 导出笔记：md 或自包含 html（html 渲染器懒加载，不拖首屏） */
async function exportNote(row, fmt = 'md') {
  if (!row || isExplorerFolder(row)) return
  exporting.value = true
  try {
    const n = await noteApi.detail(row.id)
    const safe = n.title.replace(/[\\/:*?"<>|]/g, '_')
    // 标题只出现一次：正文首行若还是同一个 `# 标题`（历史笔记），先剥掉再拼
    const body = stripLeadingDocTitle(n.content, n.title)
    if (fmt === 'html') {
      const { renderNoteHtml } = await import('../utils/mdToHtml')
      const meta = `${[n.categoryName, ...(n.tags || []).map((t) => `#${t.name}`)].filter(Boolean).join(' · ') || '未分类'}　·　更新于 ${time(n.updatedAt)}`
      const html = await renderNoteHtml({ title: n.title, content: body, meta })
      const blob = new Blob([html], { type: 'text/html;charset=utf-8' })
      saveBlob(blob, `${safe}.html`)
      ElMessage.success('已导出为 HTML 网页文件')
    } else {
      const content = `# ${n.title}\n\n${body}\n`
      const blob = new Blob([content], { type: 'text/markdown;charset=utf-8' })
      saveBlob(blob, `${safe}.md`)
      ElMessage.success('已导出为 Markdown 文件')
    }
  } catch (e) {
    /* 拦截器已提示 */
  } finally {
    exporting.value = false
  }
}

/**
 * 导入 Markdown / HTML 文件成新笔记。
 *
 * 规则与"导出"对称：文件首行的 `# 标题` 提升为笔记标题（导出写的就是 `# 标题\n\n正文`），
 * HTML 则取文档里的 h1（其次 <title>）当标题、正文由 Turndown 转回 Markdown。
 * 解析细节在 `utils/importNote.js`（那边是纯函数，有单测）。
 *
 * 多选时逐个导入并汇总结果：**一个失败不影响其余**——批量导 20 个文件时，
 * 因为其中一个坏了就整批中止，是最让人恼火的行为。导入到当前筛选的分类下（若选了分类）。
 */
async function onImportChosen(e) {
  const files = Array.from(e.target?.files || [])
  // 同一个文件连选两次也要能再次触发 change
  if (e.target) e.target.value = ''
  if (!files.length) return
  importing.value = true
  const importFolderId = selectedFolder.value > 0 ? selectedFolder.value : null
  const importCategoryId = query.value.categoryId || null
  const ok = []
  const bad = []
  try {
    for (const f of files) {
      try {
        if (f.size > IMPORT_MAX_BYTES) throw new Error(`文件超过 ${Math.round(IMPORT_MAX_BYTES / 1024 / 1024)}MB`)
        if (!isMarkdownFile(f.name) && !isHtmlFile(f.name)) throw new Error('只支持 .md / .markdown / .txt / .html / .htm')
        const { title, content } = await readNoteFile(f)
        if (!content.trim()) throw new Error('文件里没有可导入的正文')
        const created = await noteApi.add({
          title: title || titleFromFileName(f.name),
          content,
          categoryId: importCategoryId,
          folderId: importFolderId,
          tagIds: [],
        })
        ok.push(created?.title || title)
      } catch (err) {
        bad.push(`${f.name}：${err?.message || '导入失败'}`)
      }
    }
  } finally {
    importing.value = false
  }
  await Promise.all([load(), loadFolders()])
  if (ok.length) ElMessage.success(`已导入 ${ok.length} 篇：${ok.join('、')}`)
  for (const msg of bad) ElMessage.error(msg)
}

function time(v) {
  return (v || '').replace('T', ' ').slice(0, 16)
}

onMounted(async () => {
  window.addEventListener('lh-meta-changed', reloadMeta)
  await Promise.all([loadCategories(), loadTags(), loadFolders()])
  load()
})

onBeforeUnmount(() => {
  loadSeq++
  foldersSeq++
  window.removeEventListener('lh-meta-changed', reloadMeta)
})

/** 设置里改了分类/标签后刷新筛选下拉（不影响当前列表） */
async function reloadMeta() {
  await Promise.all([loadCategories(), loadTags()])
}
</script>

<template>
  <div class="page notes-page">
    <header class="notes-heading">
      <div class="notes-heading-copy">
        <h2 class="page-h2">笔记</h2>
        <span class="notes-description">整理与继续编辑</span>
      </div>
      <div class="notes-heading-actions">
        <el-button :icon="Download" :loading="importing" title="导入 Markdown / HTML 文件" @click="pickImport">导入</el-button>
        <el-button type="primary" :icon="Plus" @click="openNew">新建笔记</el-button>
      </div>
      <input ref="importInput" type="file" multiple hidden :accept="IMPORT_ACCEPT" @change="onImportChosen" />
    </header>

    <el-card class="note-workspace note-content" shadow="never" v-loading="loading || exporting || importing">
      <FolderExplorerToolbar
        ref="explorerToolbar"
        kind="note"
        :folders="folders"
        :model-value="selectedFolder"
        :loading="foldersLoading"
        @update:model-value="selectFolder"
        @changed="foldersChanged"
      />
    <div class="toolbar note-toolbar" role="search" aria-label="筛选笔记">
      <el-input
        v-model="query.kw"
        placeholder="搜索标题 / 内容"
        aria-label="搜索笔记标题或内容"
        class="note-search"
        :prefix-icon="Search"
        clearable
        @keyup.enter="search"
        @clear="search"
      >
        <template #append>
          <el-button @click="search">搜索</el-button>
        </template>
      </el-input>
      <div class="note-filters">
      <el-select v-model="query.categoryId" placeholder="全部分类" aria-label="按分类筛选" clearable class="note-filter" @change="search">
        <el-option v-for="c in categories" :key="c.id" :label="'　'.repeat(c.depth) + c.name" :value="c.id" />
      </el-select>
      <el-select v-model="query.tagId" placeholder="全部标签" aria-label="按标签筛选" clearable class="note-filter" @change="search">
        <el-option v-for="t in tags" :key="t.id" :label="t.name" :value="t.id" />
      </el-select>
      </div>
      <el-button v-if="query.categoryId || query.tagId || query.kw" text class="reset-filter" @click="resetFilter">重置筛选</el-button>
    </div>

        <div class="folder-context">
          <div class="folder-context-title">
            <span class="directory-count"><template v-if="selectedFolder !== null"><b>{{ visibleFolderCount }}</b> 个文件夹 <span aria-hidden="true">·</span> </template><b>{{ total }}</b> 篇笔记</span>
          </div>
          <div v-if="selectedIds.length" class="selection-actions">
            <span class="selection-count">已选 {{ selectedIds.length }} 篇</span>
            <el-button size="small" @click="openMove()">移动到…</el-button>
            <el-button size="small" text @click="noteTable?.clearSelection()">取消选择</el-button>
          </div>
        </div>
      <StateView
        v-if="loadError && !loading"
        state="error"
        size="inline"
        title="笔记列表加载失败"
        :detail="loadError"
        action-label="重试"
        @action="load"
      />
      <el-table
        ref="noteTable"
        :data="explorerRows"
        row-key="__explorerKey"
        class="note-table"
        :row-class-name="({ row }) => isExplorerFolder(row) ? 'explorer-folder-row' : ''"
        @selection-change="onSelectionChange"
        @row-dblclick="onRowDoubleClick"
      >
        <el-table-column type="selection" width="44" :selectable="row => !isExplorerFolder(row)" />
        <el-table-column label="名称" min-width="300">
          <template #default="{ row }">
            <div v-if="isExplorerFolder(row)" class="explorer-folder-name">
              <span class="entry-icon folder-entry-icon"><el-icon><Folder /></el-icon></span>
              <div class="explorer-folder-label">
                <button type="button" class="title-link folder-link" :title="`打开文件夹 ${row.name}`" @click.stop="openRow(row)">{{ row.name }}</button>
                <div class="summary">{{ folderSummary(row) }}</div>
              </div>
            </div>
            <div v-else class="note-entry-name">
              <span class="entry-icon document-entry-icon"><el-icon><Document /></el-icon></span>
              <div class="note-entry-label">
                <button type="button" class="title-link" :title="row.title" @click.stop="openRow(row)">{{ row.title }}</button>
                <div class="summary" :class="{ 'empty-summary': !row.summary }" :title="row.summary || undefined">{{ row.summary || '暂无摘要' }}</div>
              </div>
            </div>
          </template>
        </el-table-column>
        <el-table-column v-if="selectedFolder === null" label="文件夹" min-width="145" show-overflow-tooltip>
          <template #default="{ row }">{{ folderPath(folders, row.folderId ?? 0) }}</template>
        </el-table-column>
        <el-table-column label="分类" width="110">
          <template #default="{ row }">
            <span v-if="isExplorerFolder(row)" class="folder-kind">文件夹</span>
            <el-tag v-else-if="row.categoryName" size="small" type="info">{{ row.categoryName }}</el-tag>
            <span v-else class="empty-meta">未分类</span>
          </template>
        </el-table-column>
        <el-table-column label="标签" width="150">
          <template #default="{ row }">
            <template v-if="!isExplorerFolder(row)">
              <div v-if="row.tags?.length" class="note-tags" :title="row.tags.map(tag => tag.name).join('、')">
                <el-tag v-for="t in row.tags.slice(0, 2)" :key="t.id" size="small" class="tag-item" effect="plain"><span>{{ t.name }}</span></el-tag>
                <span v-if="row.tags.length > 2" class="extra-tags" :aria-label="`另有 ${row.tags.length - 2} 个标签`">+{{ row.tags.length - 2 }}</span>
              </div>
              <span v-if="!row.tags?.length" class="empty-meta" aria-label="无标签">—</span>
            </template>
          </template>
        </el-table-column>
        <el-table-column label="更新时间" width="160">
          <template #default="{ row }"><span class="updated-time">{{ time(row.updatedAt) || '—' }}</span></template>
        </el-table-column>
        <el-table-column label="操作" width="116" fixed="right">
          <template #default="{ row }">
            <div class="row-actions">
            <template v-if="isExplorerFolder(row)">
              <el-button link type="primary" @click.stop="openRow(row)">打开</el-button>
              <el-dropdown trigger="click" @command="action => manageFolder(action, row)">
                <el-button text class="row-more" :aria-label="`${row.name} 的文件夹操作`" @click.stop><el-icon><MoreFilled /></el-icon></el-button>
                <template #dropdown>
                  <el-dropdown-menu>
                    <el-dropdown-item command="create">新建子文件夹</el-dropdown-item>
                    <el-dropdown-item command="rename">重命名</el-dropdown-item>
                    <el-dropdown-item command="delete" :disabled="!!row.childrenCount">{{ row.childrenCount ? '含子文件夹，暂不可删除' : '删除文件夹' }}</el-dropdown-item>
                  </el-dropdown-menu>
                </template>
              </el-dropdown>
            </template>
            <template v-else>
            <el-button link type="primary" @click.stop="openRow(row)">编辑</el-button>
            <el-dropdown trigger="click" @command="action => onNoteCommand(action, row)">
              <el-button text class="row-more" :aria-label="`${row.title} 的笔记操作`" @click.stop><el-icon><MoreFilled /></el-icon></el-button>
              <template #dropdown>
                <el-dropdown-menu>
                  <el-dropdown-item command="move">移动到文件夹…</el-dropdown-item>
                  <el-dropdown-item command="md" divided>导出 Markdown (.md)</el-dropdown-item>
                  <el-dropdown-item command="html">导出网页 HTML (.html)</el-dropdown-item>
                  <el-dropdown-item command="delete" divided class="delete-menu-item">删除笔记</el-dropdown-item>
                </el-dropdown-menu>
              </template>
            </el-dropdown>
            </template>
            </div>
          </template>
        </el-table-column>
        <template #empty>
          <!-- 失败时不给空态文案：这块区域交给上面的 StateView 解释，避免把故障说成「没有内容」 -->
          <el-empty v-if="!loadError" :description="query.kw || query.categoryId || query.tagId ? '没有匹配的内容，试试调整筛选条件' : '这里还没有内容，可以新建文件夹、笔记或导入已有笔记'" :image-size="80" />
        </template>
      </el-table>
      <footer class="notes-footer">
        <span>双击打开 · 按 Enter 打开所选名称</span>
      <div v-if="total > query.size" class="pager">
        <el-pagination
          v-model:current-page="query.page"
          :page-size="query.size"
          :total="total"
          layout="total, prev, pager, next"
          @current-change="load"
        />
      </div>
      </footer>
    </el-card>
    <FolderMoveDialog v-model="moveVisible" kind="note" :folders="folders" :ids="moveIds" label="笔记" @moved="onMoved" />
  </div>
</template>

<style scoped>
.notes-page { max-width: 1760px; width: 100%; box-sizing: border-box; margin: 0 auto; padding: 24px 28px 96px; }
.notes-heading { display: flex; justify-content: space-between; align-items: center; flex-wrap: wrap; gap: 16px 24px; margin-bottom: 16px; }
.notes-heading-copy { display: flex; align-items: baseline; flex-wrap: wrap; gap: 8px 16px; min-width: 0; }
.notes-heading .page-h2 { font-size: var(--font-title); line-height: var(--lh-title); letter-spacing: -.04em; }
.notes-description { color: var(--app-text-3); font-size: var(--font-caption); line-height: var(--lh-caption); }
.notes-heading-actions { display: flex; align-items: center; flex-wrap: wrap; gap: 10px; max-width: 100%; }
.notes-heading-actions :deep(.el-button) { height: 40px; padding: 0 18px; border-radius: var(--radius); margin: 0; }
.note-workspace.el-card { min-width: 0; container: note-collection / inline-size; border-radius: var(--radius-xl); box-shadow: var(--shadow-sm); }
.note-content :deep(.el-card__body) { padding: 0; }
.note-toolbar { gap: 12px; margin: 0; padding: 16px 20px 8px; }
.note-toolbar :deep(.el-input), .note-toolbar :deep(.el-select), .note-toolbar :deep(.el-button) { --el-component-size: 40px; }
.note-toolbar :deep(.el-select__wrapper) { min-height: var(--control-h); }
.note-toolbar .note-search { flex: 1 1 260px; width: auto; min-width: 0; max-width: min(100%, 420px); }
.note-search :deep(.el-input-group__append) { background: var(--app-card); color: var(--app-text-2); }
.note-filters { display: flex; flex-wrap: wrap; gap: 10px; margin-left: auto; min-width: 0; max-width: 100%; }
.note-filter { width: 148px; max-width: 100%; }
.note-toolbar > .el-button + .el-button { margin-left: 0; }
.reset-filter { padding: 0 8px; }
.note-content :deep(.folder-explorer) { margin: 0; padding: 12px 20px; border-bottom: 1px solid var(--app-border-weak); }
.note-content :deep(.explorer-toolbar) { padding: 0; border: 0; background: transparent; gap: 16px; }
.note-content :deep(.explorer-path) { background: var(--app-bg); }
.note-content :deep(.explorer-actions .el-button), .note-content :deep(.explorer-history .el-button) { height: var(--control-h-inline); }
.folder-context, .folder-context-title, .selection-actions {
  display: flex;
  align-items: center;
  gap: 12px;
}
.folder-context { justify-content: space-between; flex-wrap: wrap; gap: 10px; min-height: 36px; padding: 4px 20px 10px; }
.folder-context-title { min-width: 0; gap: 12px; flex-wrap: wrap; }
.directory-count { color: var(--app-text-3); font-size: var(--font-caption); white-space: nowrap; }
.directory-count span { margin: 0 4px; }
.directory-count b { font-weight: 550; color: var(--app-text-2); font-variant-numeric: tabular-nums; }
.selection-actions { min-height: 32px; flex-wrap: wrap; gap: 8px; max-width: 100%; }
.selection-actions :deep(.el-button + .el-button) { margin-left: 0; }
.selection-count { color: var(--app-brand-deep); font-size: var(--font-caption); white-space: nowrap; }
.note-table.el-table { width: calc(100% - 24px); margin: 0 12px; --el-table-header-bg-color: var(--app-bg); --el-table-border-color: var(--app-border-weak); --el-table-row-hover-bg-color: color-mix(in srgb, var(--app-brand-soft) 55%, var(--app-card)); }
.note-table :deep(.el-table__header th) { padding: 10px 0; font-weight: 500; }
.note-table :deep(.el-table__body td) { padding: 12px 0; }
.note-table :deep(.el-table__inner-wrapper::before) { display: none; }
.note-table :deep(.el-checkbox__inner) { border-radius: 4px; border-color: color-mix(in srgb, var(--app-text-3) 75%, var(--app-border)); }
.note-table :deep(.el-checkbox__input.is-checked .el-checkbox__inner),
.note-table :deep(.el-checkbox__input.is-indeterminate .el-checkbox__inner) { border-color: var(--app-brand); background-color: var(--app-brand); }
.note-table :deep(.el-table__body tr:focus-within > td) { background-color: var(--el-table-row-hover-bg-color); }
.title-link {
  display: block;
  max-width: 100%;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  text-align: left;
  padding: 0;
  border: 0;
  background: transparent;
  font: inherit;
  color: var(--app-text-1);
  font-weight: 550;
  cursor: pointer;
  text-decoration: none;
}

.title-link:hover {
  color: var(--app-brand-deep);
}
.title-link:focus-visible {
  outline: 2px solid var(--app-brand);
  outline-offset: 3px;
  border-radius: 3px;
}
.explorer-folder-name, .note-entry-name { display: flex; align-items: center; gap: 12px; min-width: 0; }
.entry-icon { display: flex; align-items: center; justify-content: center; flex: 0 0 32px; width: 32px; height: 36px; box-sizing: border-box; border-radius: var(--radius); font-size: 20px; }
.folder-entry-icon { background: var(--app-brand-soft); color: var(--app-brand-deep); }
.document-entry-icon { background: var(--app-bg); color: var(--app-text-2); border: 1px solid var(--app-border-weak); font-size: 18px; }
.explorer-folder-label, .note-entry-label { flex: 1; min-width: 0; line-height: 1.5; }
.folder-kind, .empty-meta { font-size: var(--font-caption); color: var(--app-text-3); }
.updated-time { font-size: var(--font-caption); color: var(--app-text-2); font-variant-numeric: tabular-nums; }
.note-table :deep(.explorer-folder-row .el-checkbox) { visibility: hidden; }
.note-table :deep(.explorer-folder-row) { cursor: default; }

.summary {
  font-size: 12px;
  color: var(--app-text-3);
  margin-top: 4px;
  line-height: 1.5;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  max-width: 100%;
}

.note-tags { display: flex; align-items: center; gap: 4px; min-width: 0; }
.note-tags .tag-item { min-width: 0; max-width: 96px; flex: 0 1 auto; }
.note-tags .tag-item:first-child { flex-shrink: 0; max-width: 64px; }
.note-tags .tag-item :deep(.el-tag__content), .note-tags .tag-item span { display: block; min-width: 0; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.extra-tags { flex: 0 0 auto; color: var(--app-text-3); font-size: var(--font-caption); font-variant-numeric: tabular-nums; }

.row-actions { display: flex; align-items: center; gap: 10px; }
.row-actions :deep(.el-button) { font-size: 12px; }
.row-more.el-button { width: 32px; height: 32px; padding: 0; margin: 0; color: var(--app-text-3); }
.row-more.el-button:hover, .row-more.el-button:focus-visible { color: var(--app-brand-deep); background: var(--app-brand-soft); }
.row-more.el-button:focus-visible { outline: 2px solid var(--app-brand); outline-offset: -2px; }
.delete-menu-item { color: var(--el-color-danger); }
.notes-footer { display: flex; align-items: center; justify-content: space-between; flex-wrap: wrap; gap: 12px; padding: 12px 20px; margin-top: 4px; border-top: 1px solid var(--app-border-weak); }
.notes-footer > span { font-size: var(--font-caption); color: var(--app-text-3); }
.pager { display: flex; justify-content: flex-start; width: max-content; min-width: 0; max-width: 100%; overflow-x: auto; margin-left: auto; }
.pager :deep(.el-pagination) { flex: 0 0 auto; }
@media (max-width: 1100px) {
  .notes-page { padding: 20px 24px 96px; }
  .notes-heading { gap: 16px; }
  .note-filter { width: 132px; }
}
@media (max-width: 760px) {
  .notes-page { padding: 16px 16px 96px; }
  .notes-heading { align-items: stretch; flex-direction: column; gap: 12px; margin-bottom: 16px; }
  .notes-heading .page-h2 { font-size: 26px; }
  .notes-description { font-size: 12px; }
  .notes-heading-actions { width: 100%; }
  .notes-heading-actions :deep(.el-button--primary) { flex: 1; }
  .note-toolbar { padding: 12px 16px 8px; gap: 10px; }
  .note-toolbar .note-search { flex-basis: 100%; max-width: none; width: 100%; }
  .note-filters { width: 100%; margin-left: 0; }
  .note-filter { flex: 1; min-width: 0; width: 0; }
  .reset-filter { height: 28px; }
  .note-content :deep(.folder-explorer) { padding: 12px 16px; }
  .note-content :deep(.explorer-toolbar) { gap: 6px; }
  .note-content :deep(.explorer-actions) { justify-content: flex-end; }
  .folder-context { padding: 4px 16px 10px; }
  .folder-context-title { gap: 6px 10px; }
  .note-table.el-table { width: calc(100% - 16px); margin: 0 8px; }
  .note-table :deep(.el-table__body td) { padding: 12px 0; }
  .notes-footer { padding: 12px 16px; }
  .notes-footer > span { width: 100%; }
  .pager { max-width: 100%; overflow-x: auto; }
  /* Fixed action cells must join the table's horizontal scroll on narrow screens. */
  .note-table :deep(.el-table__body-wrapper tr td.el-table-fixed-column--right),
  .note-table :deep(.el-table__header-wrapper tr th.el-table-fixed-column--right) { position: static !important; right: auto !important; }
  .note-table :deep(.el-table-fixed-column--right::before),
  .note-table :deep(.el-table-fixed-column--right::after) { display: none; box-shadow: none !important; }
}
@media (max-width: 480px) {
  .note-content :deep(.explorer-history) { gap: 0; }
  .note-content :deep(.explorer-history .el-button) { width: 28px; padding: 4px; }
  .note-content :deep(.explorer-actions .el-button) { padding-right: 8px; padding-left: 8px; }
}
/* The collection can be narrow even on a wide screen with navigation open. */
@container note-collection (max-width: 680px) {
  .note-toolbar .note-search { flex-basis: 100%; max-width: 100%; }
  .note-filters { flex: 1 1 100%; margin-left: 0; }
  .note-filter { flex: 1 1 132px; min-width: 0; width: auto; }
  .note-table :deep(.el-table-fixed-column--right) { position: static !important; right: auto !important; }
  .note-table :deep(.el-table-fixed-column--right::before),
  .note-table :deep(.el-table-fixed-column--right::after) { display: none; box-shadow: none !important; }
}
</style>
