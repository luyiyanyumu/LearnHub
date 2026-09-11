<script setup>
import { onBeforeUnmount, onMounted, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { categoryApi, fileApi, saveBlob } from '../api'

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
      <span class="tip">存放学习资料：PDF / 代码 / 文档 / 压缩包…（单文件 ≤ 50MB）</span>
      <div class="spacer"></div>
      <el-input
        v-model="query.kw"
        placeholder="搜索文件名"
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
        <el-table-column label="大小" width="100">
          <template #default="{ row }">{{ fmtSize(row.size) }}</template>
        </el-table-column>
        <el-table-column label="上传时间" width="165">
          <template #default="{ row }">{{ time(row.createdAt) }}</template>
        </el-table-column>
        <el-table-column label="操作" width="130" fixed="right">
          <template #default="{ row }">
            <el-button link type="primary" @click="onDownload(row)">下载</el-button>
            <el-button link type="danger" @click="onDelete(row)">删除</el-button>
          </template>
        </el-table-column>
        <template #empty>
          <el-empty description="资料库还是空的，点右上角上传第一份资料" :image-size="80" />
        </template>
      </el-table>
    </el-card>
  </div>
</template>

<style scoped>
.tip {
  font-size: 12px;
  color: var(--app-text-3);
}

.fname {
  color: var(--app-text-1);
  word-break: break-all;
}
</style>
