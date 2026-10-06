<script setup>
import { onBeforeUnmount, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { ArrowDown } from '@element-plus/icons-vue'
import { categoryApi, tagApi, noteApi, saveBlob } from '../api'
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
const loading = ref(false)
const exporting = ref(false)
const importing = ref(false)
/** 隐藏的文件选择框：点「导入」按钮时程序化触发 */
const importInput = ref(null)
const list = ref([])
const total = ref(0)
const query = ref({ page: 1, size: 10, categoryId: undefined, tagId: undefined, kw: '' })
const categories = ref([])
const tags = ref([])

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
  loading.value = true
  try {
    const params = { ...query.value }
    if (!params.categoryId) delete params.categoryId
    if (!params.tagId) delete params.tagId
    if (!params.kw) delete params.kw
    const data = await noteApi.page(params)
    list.value = data.list
    total.value = data.total
  } finally {
    loading.value = false
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
  await ElMessageBox.confirm(`确定删除笔记「${row.title}」吗？`, '提示', { type: 'warning' })
  await noteApi.remove(row.id)
  ElMessage.success('已删除')
  if (list.value.length === 1 && query.value.page > 1) query.value.page--
  load()
}

function openNew() {
  router.push('/notes/new')
}

/** 点「导入」= 打开隐藏的文件选择框（accept 在模板上，与导入工具同一份常量） */
function pickImport() {
  importInput.value?.click()
}

/** 导出笔记：md 或自包含 html（html 渲染器懒加载，不拖首屏） */
async function exportNote(row, fmt = 'md') {
  exporting.value = true
  try {
    const n = await noteApi.detail(row.id)
    const safe = n.title.replace(/[\\/:*?"<>|]/g, '_')
    // 标题只出现一次：正文首行若还是同一个 `# 标题`（历史笔记），先剥掉再拼
    const body = stripLeadingDocTitle(n.content, n.title)
    if (fmt === 'html') {
      const { renderNoteHtml } = await import('../utils/mdToHtml')
      const meta = `${[n.categoryName, ...(n.tags || []).map((t) => `#${t.name}`)].filter(Boolean).join(' · ') || '未分类'}　·　更新于 ${time(n.updatedAt)}`
      const html = renderNoteHtml({ title: n.title, content: body, meta })
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
          categoryId: query.value.categoryId || null,
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
  await load()
  if (ok.length) ElMessage.success(`已导入 ${ok.length} 篇：${ok.join('、')}`)
  for (const msg of bad) ElMessage.error(msg)
}

function time(v) {
  return (v || '').replace('T', ' ').slice(0, 16)
}

onMounted(async () => {
  window.addEventListener('lh-meta-changed', reloadMeta)
  await Promise.all([loadCategories(), loadTags()])
  load()
})

onBeforeUnmount(() => {
  window.removeEventListener('lh-meta-changed', reloadMeta)
})

/** 设置里改了分类/标签后刷新筛选下拉（不影响当前列表） */
async function reloadMeta() {
  await Promise.all([loadCategories(), loadTags()])
}
</script>

<template>
  <div class="page">
    <div class="toolbar">
      <h2 class="page-h2">笔记</h2>
      <div class="spacer"></div>
      <el-input
        v-model="query.kw"
        placeholder="搜索标题 / 内容"
        clearable
        style="width: 220px"
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
      <el-select v-model="query.tagId" placeholder="全部标签" clearable style="width: 130px" @change="search">
        <el-option v-for="t in tags" :key="t.id" :label="t.name" :value="t.id" />
      </el-select>
      <el-button type="primary" @click="openNew">＋ 新建笔记</el-button>
      <!-- 导入：md / markdown / txt 按原文入库，html 走 Turndown 转 Markdown；可多选。
           图标沿用全局 .btn-ico 内联 SVG（与资料库「上传资料」同一套笔画）：
           这里是那枚上传图标的镜像 —— 托盘 + 向下的箭头，语义就是"把文件收进来"。 -->
      <el-button :loading="importing" title="导入 Markdown / HTML 文件" @click="pickImport">
        <span class="btn-ico">
          <svg viewBox="0 0 24 24"><path d="M12 4.5V16M6.5 10.5 12 16 17.5 10.5M4.5 19.5h15" /></svg>
        </span>导入
      </el-button>
      <input
        ref="importInput"
        type="file"
        multiple
        hidden
        :accept="IMPORT_ACCEPT"
        @change="onImportChosen"
      />
      <el-button v-if="query.categoryId || query.tagId || query.kw" @click="resetFilter">重置</el-button>
    </div>

    <el-card shadow="never" v-loading="loading || exporting || importing">
      <el-table :data="list" class="note-table">
        <el-table-column label="标题" min-width="220">
          <template #default="{ row }">
            <a class="title-link" @click="router.push(`/notes/${row.id}`)">{{ row.title }}</a>
            <div class="summary">{{ row.summary }}</div>
          </template>
        </el-table-column>
        <el-table-column label="分类" width="110">
          <template #default="{ row }">
            <el-tag v-if="row.categoryName" size="small" type="info">{{ row.categoryName }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="标签" width="150">
          <template #default="{ row }">
            <el-tag v-for="t in row.tags" :key="t.id" size="small" class="tag-item" effect="plain">{{ t.name }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="更新时间" width="160">
          <template #default="{ row }">{{ time(row.updatedAt) }}</template>
        </el-table-column>
        <el-table-column label="操作" width="200" fixed="right">
          <template #default="{ row }">
            <el-button link type="primary" @click="router.push(`/notes/${row.id}`)">编辑</el-button>
            <el-dropdown trigger="click" @command="(cmd) => exportNote(row, cmd)">
              <el-button link type="success">
                导出<el-icon style="margin-left: 2px"><ArrowDown /></el-icon>
              </el-button>
              <template #dropdown>
                <el-dropdown-menu>
                  <el-dropdown-item command="md">Markdown (.md)</el-dropdown-item>
                  <el-dropdown-item command="html">网页 HTML (.html)</el-dropdown-item>
                </el-dropdown-menu>
              </template>
            </el-dropdown>
            <el-button link type="danger" @click="onDelete(row)">删除</el-button>
          </template>
        </el-table-column>
        <template #empty>
          <el-empty description="还没有笔记，点右上角新建一篇吧" :image-size="80" />
        </template>
      </el-table>
      <div class="pager">
        <el-pagination
          v-model:current-page="query.page"
          :page-size="query.size"
          :total="total"
          layout="total, prev, pager, next"
          @current-change="load"
        />
      </div>
    </el-card>
  </div>
</template>

<style scoped>
.title-link {
  color: var(--app-text-1);
  font-weight: 500;
  cursor: pointer;
  text-decoration: none;
}

.title-link:hover {
  color: var(--app-brand-deep);
}

.summary {
  font-size: 12px;
  color: var(--app-text-3);
  margin-top: 2px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  max-width: 480px;
}

.tag-item {
  margin-right: 4px;
}

/* 操作列「编辑/导出/删除」基线对齐：el-dropdown 是 inline-flex，
   基线比两侧 inline-block 的 link 按钮偏上 2px，强制 middle 对齐 */
.note-table :deep(.el-dropdown) {
  vertical-align: middle;
}

.pager {
  display: flex;
  justify-content: flex-end;
  margin-top: 12px;
}
</style>
