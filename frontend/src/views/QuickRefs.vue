<script setup>
import { onBeforeUnmount, onMounted, reactive, ref } from 'vue'
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
      <div v-else class="ref-grid">
        <el-card v-for="r in list" :key="r.id" shadow="hover" class="ref-card">
          <div class="ref-head">
            <span class="ref-title">{{ r.title }}</span>
            <el-dropdown trigger="click">
              <el-button link class="more">⋯</el-button>
              <template #dropdown>
                <el-dropdown-menu>
                  <el-dropdown-item @click="openEdit(r)">编辑</el-dropdown-item>
                  <el-dropdown-item divided @click="onDelete(r)">删除</el-dropdown-item>
                </el-dropdown-menu>
              </template>
            </el-dropdown>
          </div>
          <div class="ref-body md-mini">
            <MdPreview :modelValue="fixHtmlQuotes(r.content || '')" :theme="isDark ? 'dark' : 'light'" previewTheme="github" />
          </div>
          <div class="ref-foot">
            <el-tag v-if="r.categoryName" size="small" type="info">{{ r.categoryName }}</el-tag>
            <span class="ref-time">{{ (r.updatedAt || '').replace('T', ' ').slice(0, 16) }}</span>
          </div>
        </el-card>
      </div>
    </div>

    <el-dialog
      v-model="dialogVisible"
      :title="editingId ? '编辑速查卡' : '新建速查卡'"
      width="640px"
      top="8vh"
      destroy-on-close
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
}

.ref-title {
  font-weight: 600;
  color: var(--app-text-1);
}

.more {
  font-size: 16px;
  letter-spacing: 2px;
  color: var(--app-text-3);
}

.ref-body {
  max-height: 190px;
  overflow: auto;
  border-top: 1px dashed var(--app-border);
  padding-top: 8px;
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
