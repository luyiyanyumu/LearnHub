<script setup>
import { computed, ref, useId, watch } from 'vue'
import { ArrowDown, ArrowRight, Document, Files, Folder, FolderOpened, MoreFilled, Plus } from '@element-plus/icons-vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { folderApi } from '../api/index.js'
import { buildFolderTree, folderName, folderPath, normalizeFolderScope } from '../utils/folders.js'

const props = defineProps({
  kind: { type: String, required: true, validator: value => ['note', 'file'].includes(value) },
  folders: { type: Array, default: () => [] },
  modelValue: { type: [Number, String], default: null },
  loading: { type: Boolean, default: false },
  totalCount: { type: Number, default: null },
  unfiledCount: { type: Number, default: null },
})
const emit = defineEmits(['update:modelValue', 'changed'])
const id = `folder-sidebar-${useId()}`
const open = ref(true)
const collapsed = ref(new Set())
const busy = ref(false)
const error = ref('')
const scope = computed(() => normalizeFolderScope(props.modelValue))
const tree = computed(() => buildFolderTree(props.folders))
const label = computed(() => props.kind === 'note' ? '笔记' : '资料')
const disabled = computed(() => props.loading || busy.value)
const rows = computed(() => {
  const visible = []
  const stack = tree.value.map(node => ({ node, depth: 0 })).reverse()
  while (stack.length) {
    const entry = stack.pop()
    visible.push(entry)
    if (!collapsed.value.has(entry.node.id)) {
      for (let index = entry.node.children.length - 1; index >= 0; index--) {
        stack.push({ node: entry.node.children[index], depth: entry.depth + 1 })
      }
    }
  }
  return visible
})

// A restored route or a move can select a folder whose ancestors were collapsed.
watch([() => props.modelValue, () => props.folders], () => {
  const map = new Map(props.folders.filter(folder => folder && Number(folder.id) > 0).map(folder => [Number(folder.id), folder]))
  const visited = new Set()
  let current = map.get(scope.value)
  const next = new Set(collapsed.value)
  while (current && !visited.has(Number(current.id))) {
    visited.add(Number(current.id))
    next.delete(Number(current.parentId))
    current = map.get(Number(current.parentId))
  }
  collapsed.value = next
}, { immediate: true })

function select(value) {
  if (!disabled.value) emit('update:modelValue', value)
}

function toggleFolder(folder) {
  const next = new Set(collapsed.value)
  if (next.has(folder.id)) next.delete(folder.id)
  else next.add(folder.id)
  collapsed.value = next
}

function cancelled(reason) {
  return reason === 'cancel' || reason === 'close'
}

function validateName(value) {
  const name = value?.trim() || ''
  if (!name || name.length > 100) return '请输入 1 到 100 个字符的名称'
  if (/[\u0000-\u001f\u007f-\u009f/\\]/u.test(name)) return '名称不能包含控制字符、正斜杠或反斜杠'
  return true
}

async function create(parent = null) {
  if (disabled.value) return
  busy.value = true
  error.value = ''
  try {
    const { value } = await ElMessageBox.prompt(parent ? `在「${parent.name}」内创建文件夹：` : '文件夹名称：', '新建文件夹', {
      inputPlaceholder: '例如：课程、论文、项目资料', inputValidator: validateName,
      confirmButtonText: '创建', cancelButtonText: '取消',
    })
    const created = await folderApi.create({ kind: props.kind, name: value.trim(), parentId: parent?.id || 0 })
    if (parent) {
      const next = new Set(collapsed.value)
      next.delete(parent.id)
      collapsed.value = next
    }
    emit('changed', { action: 'create', folder: created })
    ElMessage.success('文件夹已创建')
  } catch (reason) {
    if (!cancelled(reason)) error.value = reason?.message || '创建文件夹失败，请重试'
  } finally {
    busy.value = false
  }
}

async function rename(folder) {
  if (disabled.value) return
  busy.value = true
  error.value = ''
  try {
    const { value } = await ElMessageBox.prompt('新的文件夹名称：', '重命名文件夹', {
      inputValue: folder.name, inputValidator: validateName, confirmButtonText: '保存', cancelButtonText: '取消',
    })
    if (value.trim() === folder.name) return
    await folderApi.update(folder.id, { name: value.trim() })
    emit('changed', { action: 'rename', folderId: folder.id })
    ElMessage.success('文件夹已重命名')
  } catch (reason) {
    if (!cancelled(reason)) error.value = reason?.message || '重命名失败，请重试'
  } finally {
    busy.value = false
  }
}

async function remove(folder) {
  if (disabled.value || folder.children.length) return
  busy.value = true
  error.value = ''
  try {
    await ElMessageBox.confirm(`删除文件夹「${folder.name}」？其中的${label.value}将移到「未收纳」，内容会保留。`, '删除文件夹', {
      type: 'warning', confirmButtonText: '删除文件夹', cancelButtonText: '取消', confirmButtonClass: 'el-button--danger',
    })
    await folderApi.remove(folder.id)
    if (scope.value === folder.id) emit('update:modelValue', 0)
    emit('changed', { action: 'delete', folderId: folder.id })
    ElMessage.success('文件夹已删除，内容已移到未收纳')
  } catch (reason) {
    if (!cancelled(reason)) error.value = reason?.message || '删除文件夹失败，请重试'
  } finally {
    busy.value = false
  }
}

function command(action, folder) {
  if (action === 'create') create(folder)
  else if (action === 'rename') rename(folder)
  else if (action === 'delete') remove(folder)
}
</script>

<template>
  <nav class="folder-sidebar" :aria-label="`${label}文件夹`" :aria-busy="disabled">
    <div class="folder-header">
      <h3>文件夹</h3>
      <button class="folder-mobile-toggle icon-button" type="button" :aria-expanded="open" :aria-controls="`${id}-content`" :aria-label="open ? '收起文件夹' : '展开文件夹'" @click="open = !open"><ArrowDown :class="{ rotated: !open }" aria-hidden="true" /></button>
      <button class="icon-button create-root" type="button" title="新建文件夹" aria-label="新建顶级文件夹" :disabled="disabled" @click="create()"><Plus aria-hidden="true" /></button>
    </div>
    <div :id="`${id}-content`" class="folder-content" :class="{ 'mobile-collapsed': !open }">
      <div class="folder-shortcuts">
        <button type="button" class="folder-choice" :class="{ active: scope === null }" :aria-current="scope === null ? 'page' : undefined" :disabled="disabled" @click="select(null)">
          <component :is="kind === 'note' ? Document : Files" aria-hidden="true" /><span>全部{{ label }}</span><span v-if="totalCount !== null" class="folder-count">{{ totalCount }}</span>
        </button>
        <button type="button" class="folder-choice" :class="{ active: scope === 0 }" :aria-current="scope === 0 ? 'page' : undefined" :disabled="disabled" @click="select(0)">
          <FolderOpened aria-hidden="true" /><span>未收纳</span><span v-if="unfiledCount !== null" class="folder-count">{{ unfiledCount }}</span>
        </button>
      </div>
      <p v-if="loading && !folders.length" class="folder-message" role="status">正在加载文件夹…</p>
      <ul class="folder-tree">
        <li v-for="entry in rows" :key="entry.node.id" class="folder-row" :class="{ active: scope === entry.node.id }" :style="{ '--folder-indent': `${Math.min(entry.depth, 3) * 12}px` }">
          <button v-if="entry.node.children.length" type="button" class="folder-expand icon-button" :aria-expanded="!collapsed.has(entry.node.id)" :aria-label="`${collapsed.has(entry.node.id) ? '展开' : '收起'}「${entry.node.name}」`" :disabled="disabled" @click.stop="toggleFolder(entry.node)"><ArrowRight :class="{ expanded: !collapsed.has(entry.node.id) }" aria-hidden="true" /></button>
          <span v-else class="folder-expand-space" aria-hidden="true" />
          <button type="button" class="folder-open" :title="folderPath(folders, entry.node.id)" :aria-current="scope === entry.node.id ? 'page' : undefined" :disabled="disabled" @click="select(entry.node.id)">
            <Folder aria-hidden="true" /><span class="folder-name">{{ entry.node.name }}</span><span class="folder-count" :title="`${entry.node.itemCount} 项直接收纳的${label}`">{{ entry.node.itemCount }}</span>
          </button>
          <el-dropdown trigger="click" :disabled="disabled" @command="command($event, entry.node)">
            <button type="button" class="folder-menu icon-button" :disabled="disabled" aria-haspopup="menu" :aria-label="`管理「${entry.node.name}」`"><MoreFilled aria-hidden="true" /></button>
            <template #dropdown><el-dropdown-menu>
              <el-dropdown-item command="create">新建子文件夹</el-dropdown-item>
              <el-dropdown-item command="rename">重命名</el-dropdown-item>
              <el-dropdown-item command="delete" divided :disabled="!!entry.node.children.length" :title="entry.node.children.length ? '请先删除或移走子文件夹' : undefined">{{ entry.node.children.length ? '含子文件夹，暂不可删除' : '删除文件夹' }}</el-dropdown-item>
            </el-dropdown-menu></template>
          </el-dropdown>
        </li>
      </ul>
      <p v-if="!loading && !folders.length" class="folder-message">新建文件夹，按主题收纳{{ label }}。</p>
      <p v-if="error" class="folder-error" role="alert">{{ error }}</p>
      <p class="folder-hint">当前：{{ folderName(folders, scope) }}<br />文件夹不影响知识库检索。</p>
    </div>
  </nav>
</template>

<style scoped>
.folder-sidebar { min-width: 0; padding: 12px 8px; color: var(--app-text-1); background: var(--app-card); border: 1px solid var(--app-border); border-radius: var(--radius-lg); }
.folder-header { display: flex; align-items: center; gap: 4px; padding: 0 4px 8px 8px; }
.folder-header h3 { flex: 1; margin: 0; font-size: 14px; font-weight: 650; }
.icon-button { display: inline-flex; align-items: center; justify-content: center; flex: 0 0 auto; width: 28px; height: 32px; padding: 0; border: 0; border-radius: 6px; color: var(--app-text-2); background: transparent; cursor: pointer; }
.icon-button svg { width: 15px; height: 15px; }
.icon-button:hover:not(:disabled) { background: var(--app-bg); color: var(--app-brand-deep); }
.icon-button:disabled { cursor: default; opacity: .5; }
.folder-mobile-toggle { display: none; }
.folder-shortcuts { padding-bottom: 8px; margin-bottom: 8px; border-bottom: 1px solid var(--app-border-weak); }
.folder-choice { display: flex; align-items: center; gap: 8px; width: 100%; min-height: 36px; padding: 6px 10px; border: 0; border-radius: 6px; font: inherit; font-size: 13px; text-align: left; color: var(--app-text-2); background: transparent; cursor: pointer; }
.folder-choice svg { flex: 0 0 16px; width: 16px; height: 16px; }
.folder-choice:hover:not(:disabled), .folder-row:hover, .folder-row:focus-within { background: var(--app-bg); }
.folder-choice.active, .folder-row.active { color: var(--app-brand-deep); background: var(--app-brand-soft); }
.folder-choice.active { font-weight: 600; }
.folder-choice:disabled, .folder-open:disabled { cursor: default; }
.folder-tree { list-style: none; margin: 0; padding: 0; max-height: calc(100vh - 340px); min-height: 0; overflow-y: auto; overscroll-behavior: contain; }
.folder-row { display: flex; align-items: center; min-width: 0; min-height: 36px; padding-left: var(--folder-indent); border-radius: 6px; color: var(--app-text-2); }
.folder-expand { width: 22px; }
.folder-expand svg { width: 12px; height: 12px; transition: transform .12s ease; }
.folder-expand svg.expanded { transform: rotate(90deg); }
.folder-expand-space { flex: 0 0 22px; }
.folder-open { display: flex; align-items: center; gap: 6px; min-width: 0; flex: 1; min-height: 36px; padding: 6px 0; border: 0; text-align: left; font: inherit; font-size: 13px; color: inherit; background: transparent; cursor: pointer; }
.folder-open svg { flex: 0 0 15px; width: 15px; height: 15px; }
.folder-name { flex: 1; min-width: 0; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.folder-count { margin-left: auto; flex: 0 0 auto; padding-left: 4px; color: var(--app-text-3); font-size: 12px; font-variant-numeric: tabular-nums; }
.folder-menu { opacity: 0; width: 26px; }
.folder-menu svg { width: 14px; height: 14px; }
.folder-row:hover .folder-menu, .folder-row:focus-within .folder-menu, .folder-row.active .folder-menu { opacity: 1; }
.folder-message, .folder-hint { padding: 8px; margin: 0; font-size: 12px; color: var(--app-text-3); line-height: 1.7; }
.folder-hint { border-top: 1px solid var(--app-border-weak); margin-top: 8px; overflow-wrap: anywhere; }
.folder-error { margin: 8px; font-size: 12px; color: var(--el-color-danger); line-height: 1.7; overflow-wrap: anywhere; }
@media (max-width: 760px) {
  .folder-mobile-toggle { display: inline-flex; }
  .folder-mobile-toggle svg.rotated { transform: rotate(-90deg); }
  .folder-content.mobile-collapsed { display: none; }
  .folder-header { padding-bottom: 0; }
  .folder-content { margin-top: 8px; }
  .folder-tree { max-height: 260px; }
  .folder-menu { opacity: 1; }
}
@media (hover: none) { .folder-menu { opacity: 1; } }
@media (prefers-reduced-motion: reduce) { .folder-expand svg { transition: none; } }
</style>
