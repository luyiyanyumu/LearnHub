<script setup>
import { onBeforeUnmount, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { ArrowDown } from '@element-plus/icons-vue'
import { categoryApi, tagApi, noteApi, saveBlob } from '../api'

const router = useRouter()
const loading = ref(false)
const exporting = ref(false)
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

/** 导出笔记：md 或自包含 html（html 渲染器懒加载，不拖首屏） */
async function exportNote(row, fmt = 'md') {
  exporting.value = true
  try {
    const n = await noteApi.detail(row.id)
    const safe = n.title.replace(/[\\/:*?"<>|]/g, '_')
    if (fmt === 'html') {
      const { renderNoteHtml } = await import('../utils/mdToHtml')
      const meta = `${[n.categoryName, ...(n.tags || []).map((t) => `#${t.name}`)].filter(Boolean).join(' · ') || '未分类'}　·　更新于 ${time(n.updatedAt)}`
      const html = renderNoteHtml({ title: n.title, content: n.content || '', meta })
      const blob = new Blob([html], { type: 'text/html;charset=utf-8' })
      saveBlob(blob, `${safe}.html`)
      ElMessage.success('已导出为 HTML 网页文件')
    } else {
      const content = `# ${n.title}\n\n${n.content || ''}\n`
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
        placeholder="搜索标题 / 内容关键词"
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
      <el-button v-if="query.categoryId || query.tagId || query.kw" @click="resetFilter">重置</el-button>
    </div>

    <el-card shadow="never" v-loading="loading || exporting">
      <el-table :data="list">
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

.pager {
  display: flex;
  justify-content: flex-end;
  margin-top: 12px;
}
</style>
