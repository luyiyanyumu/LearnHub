<script setup>
import { computed, ref, watch } from 'vue'
import { ArrowLeft, ArrowRight, Top, FolderOpened, Plus, MoreFilled } from '@element-plus/icons-vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { folderApi } from '../api/index.js'
import { flattenFolderOptions, normalizeFolderScope } from '../utils/folders.js'
import { folderExplorerBreadcrumbs, parentFolderScope } from '../utils/folderExplorer.js'

const props = defineProps({
  kind: { type: String, required: true, validator: value => ['note', 'file'].includes(value) },
  folders: { type: Array, default: () => [] },
  modelValue: { type: [Number, String], default: 0 },
  loading: { type: Boolean, default: false },
})
const emit = defineEmits(['update:modelValue', 'changed'])
const scope = computed(() => normalizeFolderScope(props.modelValue))
const label = computed(() => props.kind === 'note' ? '笔记' : '资料')
const rootLabel = computed(() => props.kind === 'note' ? '笔记' : '资料库')
const folderMap = computed(() => new Map(flattenFolderOptions(props.folders).map(folder => [folder.id, folder])))
const currentFolder = computed(() => folderMap.value.get(scope.value))
const breadcrumbs = computed(() => folderExplorerBreadcrumbs(props.folders, scope.value))
const busy = ref(false)
const error = ref('')
const disabled = computed(() => props.loading || busy.value)
const history = ref([scope.value])
const historyIndex = ref(0)
let pendingHistoryIndex = null

watch(scope, value => {
  error.value = ''
  if (pendingHistoryIndex !== null && history.value[pendingHistoryIndex] === value) {
    historyIndex.value = pendingHistoryIndex
  } else if (history.value[historyIndex.value] !== value) {
    history.value = [...history.value.slice(0, historyIndex.value + 1), value]
    historyIndex.value = history.value.length - 1
  }
  pendingHistoryIndex = null
}, { flush: 'sync' })

function historyTarget(direction) {
  for (let index = historyIndex.value + direction; index >= 0 && index < history.value.length; index += direction) {
    const value = history.value[index]
    if (value !== scope.value && (value === null || value === 0 || folderMap.value.has(value))) return index
  }
  return -1
}
const previousIndex = computed(() => historyTarget(-1))
const nextIndex = computed(() => historyTarget(1))

function navigate(value) {
  if (disabled.value || value === scope.value) return
  pendingHistoryIndex = null
  emit('update:modelValue', value)
}
function traverse(index) {
  if (disabled.value || index < 0) return
  pendingHistoryIndex = index
  emit('update:modelValue', history.value[index])
}
function validateName(value) {
  const name = value?.trim() || ''
  if (!name || name.length > 100) return '请输入 1 到 100 个字符的名称'
  if (/[\u0000-\u001f\u007f-\u009f/\\]/u.test(name)) return '名称不能包含控制字符、正斜杠或反斜杠'
  return true
}
function cancelled(reason) { return reason === 'cancel' || reason === 'close' }

async function manageFolder(action, folder = currentFolder.value) {
  if (disabled.value || !['create', 'rename', 'delete'].includes(action)) return
  // Row menus carry snapshots. Resolve the latest directory before a mutation.
  const target = folder ? folderMap.value.get(Number(folder.id)) : null
  if (folder && !target) return
  if (action !== 'create' && !target) return
  if (action === 'delete' && target.children.length) return
  const kind = props.kind
  busy.value = true
  error.value = ''
  try {
    if (action === 'create') {
      const { value } = await ElMessageBox.prompt(target ? `在「${target.name}」内创建文件夹：` : '文件夹名称：', '新建文件夹', {
        inputPlaceholder: '例如：课程、论文、项目资料', inputValidator: validateName,
        confirmButtonText: '创建', cancelButtonText: '取消',
      })
      const created = await folderApi.create({ kind, name: value.trim(), parentId: target?.id || 0 })
      emit('changed', { action, folder: created })
      ElMessage.success('文件夹已创建')
    } else if (action === 'rename') {
      const { value } = await ElMessageBox.prompt('新的文件夹名称：', '重命名文件夹', {
        inputValue: target.name, inputValidator: validateName, confirmButtonText: '保存', cancelButtonText: '取消',
      })
      if (value.trim() === target.name) return
      await folderApi.update(target.id, { name: value.trim() })
      emit('changed', { action, folderId: target.id })
      ElMessage.success('文件夹已重命名')
    } else {
      await ElMessageBox.confirm(`删除文件夹「${target.name}」？其中的${label.value}将移到根目录，内容会保留。`, '删除文件夹', {
        type: 'warning', confirmButtonText: '删除文件夹', cancelButtonText: '取消', confirmButtonClass: 'el-button--danger',
      })
      await folderApi.remove(target.id)
      if (scope.value === target.id) emit('update:modelValue', 0)
      emit('changed', { action, folderId: target.id })
      ElMessage.success('文件夹已删除，内容已移到根目录')
    }
  } catch (reason) {
    if (!cancelled(reason)) error.value = reason?.message || '文件夹操作失败，请重试'
  } finally {
    busy.value = false
  }
}
defineExpose({ manageFolder })
</script>

<template>
  <div class="folder-explorer" :aria-busy="disabled">
    <nav class="explorer-toolbar" :aria-label="`${label}目录导航`">
      <div class="explorer-history">
        <el-button text :icon="ArrowLeft" aria-label="后退" title="后退" :disabled="disabled || previousIndex < 0" @click="traverse(previousIndex)" />
        <el-button text :icon="ArrowRight" aria-label="前进" title="前进" :disabled="disabled || nextIndex < 0" @click="traverse(nextIndex)" />
        <el-button text :icon="Top" aria-label="上一级" title="上一级" :disabled="disabled || scope === 0" @click="navigate(parentFolderScope(folders, scope))" />
      </div>
      <div class="explorer-path" aria-label="当前位置">
        <FolderOpened class="path-icon" aria-hidden="true" />
        <button type="button" class="path-segment" :aria-current="scope === 0 ? 'page' : undefined" :disabled="disabled" @click="navigate(0)">{{ rootLabel }}</button>
        <template v-for="crumb in breadcrumbs" :key="crumb.id">
          <ArrowRight class="path-separator" aria-hidden="true" />
          <button type="button" class="path-segment" :title="crumb.name" :aria-current="scope === crumb.id ? 'page' : undefined" :disabled="disabled" @click="navigate(crumb.id)">{{ crumb.name }}</button>
        </template>
        <template v-if="scope === null"><ArrowRight class="path-separator" aria-hidden="true" /><span class="path-segment current" aria-current="page">全部{{ label }}</span></template>
      </div>
      <div class="explorer-actions">
        <el-button size="small" :disabled="disabled || scope === null" @click="navigate(null)">全部{{ label }}</el-button>
        <el-button size="small" type="primary" plain :icon="Plus" :disabled="disabled" @click="manageFolder('create')">新建文件夹</el-button>
        <el-dropdown v-if="currentFolder" trigger="click" :disabled="disabled" @command="manageFolder($event)">
          <el-button size="small" text :icon="MoreFilled" aria-label="管理当前文件夹" title="管理当前文件夹" :disabled="disabled" />
          <template #dropdown><el-dropdown-menu>
            <el-dropdown-item command="rename">重命名文件夹</el-dropdown-item>
            <el-dropdown-item command="delete" divided :disabled="!!currentFolder.children.length">{{ currentFolder.children.length ? '含子文件夹，暂不可删除' : '删除文件夹' }}</el-dropdown-item>
          </el-dropdown-menu></template>
        </el-dropdown>
      </div>
    </nav>
    <p v-if="error" class="explorer-error" role="alert">{{ error }}</p>
  </div>
</template>

<style scoped>
.folder-explorer { min-width: 0; container: folder-navigation / inline-size; margin-bottom: 12px; }
.explorer-toolbar { display: flex; align-items: center; flex-wrap: wrap; gap: 12px; min-width: 0; padding: 8px 10px; background: var(--app-card); border: 1px solid var(--app-border); border-radius: var(--radius-lg); }
.explorer-history, .explorer-actions { display: flex; align-items: center; gap: 4px; flex: 0 1 auto; max-width: 100%; }
.explorer-history { flex-shrink: 0; }
.explorer-actions { flex-wrap: wrap; min-width: 0; }
.explorer-history :deep(.el-button + .el-button), .explorer-actions :deep(.el-button + .el-button) { margin-left: 0; }
.explorer-history :deep(.el-button) { padding: 6px; width: 30px; height: 30px; }
.explorer-path { flex: 1 1 160px; min-width: 0; display: flex; align-items: center; gap: 4px; padding: 2px 6px; min-height: 32px; overflow-x: auto; background: var(--app-bg); border-radius: 6px; scrollbar-width: thin; }
.path-icon { flex: 0 0 17px; width: 17px; height: 17px; margin: 0 4px; color: var(--app-brand-deep); }
.path-separator { flex: 0 0 12px; width: 12px; height: 12px; color: var(--app-text-3); }
.path-segment { flex: 0 0 auto; max-width: 220px; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; border: 0; border-radius: 4px; padding: 5px 8px; background: transparent; color: var(--app-text-2); font: inherit; font-size: 13px; cursor: pointer; }
.path-segment[aria-current="page"] { color: var(--app-text-1); font-weight: 600; }
button.path-segment:hover:not(:disabled) { color: var(--app-brand-deep); background: var(--app-brand-soft); }
.path-segment:disabled { cursor: default; opacity: .65; }
.path-segment:focus-visible { outline: 2px solid var(--app-brand); outline-offset: -2px; }
.current { cursor: default; }
.explorer-error { margin: 6px 10px 0; color: var(--el-color-danger); font-size: 13px; overflow-wrap: anywhere; }
@container folder-navigation (max-width: 680px) {
  .explorer-toolbar { gap: 6px; flex-wrap: wrap; padding: 8px; }
  .explorer-path { flex-basis: 120px; }
  .explorer-actions { flex-basis: 100%; justify-content: flex-end; }
  .path-segment { max-width: 180px; }
}
</style>
