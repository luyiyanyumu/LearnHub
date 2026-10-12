<script setup>
import { computed, ref, useId, watch } from 'vue'
import { ElMessage } from 'element-plus'
import { folderApi } from '../api/index.js'
import { flattenFolderOptions, validItemIds } from '../utils/folders.js'

const props = defineProps({
  modelValue: { type: Boolean, default: false },
  folders: { type: Array, default: () => [] },
  kind: { type: String, required: true, validator: value => ['note', 'file'].includes(value) },
  ids: { type: Array, default: () => [] },
  label: { type: String, default: '' },
})
const emit = defineEmits(['update:modelValue', 'moved'])
const id = `folder-move-${useId()}`
const target = ref(0)
const targetSelect = ref(null)
const busy = ref(false)
const error = ref('')
const options = computed(() => flattenFolderOptions(props.folders))
const itemIds = computed(() => validItemIds(props.ids))
const noun = computed(() => props.label || (props.kind === 'note' ? '笔记' : '资料'))
const validTarget = computed(() => target.value === 0 || options.value.some(folder => folder.id === target.value))
const targetPath = computed(() => target.value === 0 ? '未收纳' : options.value.find(folder => folder.id === target.value)?.path || '目标文件夹已不存在')
const canMove = computed(() => !busy.value && itemIds.value.length > 0 && validTarget.value)

watch(() => props.modelValue, visible => {
  if (visible) {
    target.value = 0
    error.value = ''
  }
})

function close() {
  if (!busy.value) emit('update:modelValue', false)
}

function focusTarget() {
  targetSelect.value?.focus()
}

async function move() {
  if (!canMove.value) return
  // Snapshot the user's selection so concurrent list refreshes cannot change the write.
  const ids = [...itemIds.value]
  const folderId = target.value === 0 ? null : target.value
  busy.value = true
  error.value = ''
  try {
    await folderApi.moveItems({ kind: props.kind, ids, folderId })
    emit('moved', { folderId, ids })
    emit('update:modelValue', false)
    ElMessage.success(`${ids.length} 项${noun.value}已移动`)
  } catch (reason) {
    error.value = reason?.message || '移动失败，请重试'
  } finally {
    busy.value = false
  }
}
</script>

<template>
  <el-dialog :model-value="modelValue" title="移动到文件夹" width="440px" class="folder-move-dialog" append-to-body :close-on-click-modal="!busy" :close-on-press-escape="!busy" :show-close="!busy" @update:model-value="close" @opened="focusTarget">
    <form class="folder-move-form" @submit.prevent="move">
      <p class="move-summary">已选择 <strong>{{ itemIds.length }}</strong> 项{{ noun }}</p>
      <label :for="`${id}-target`">目标文件夹</label>
      <el-select
        :id="`${id}-target`" ref="targetSelect" v-model="target" class="move-target-select"
        :disabled="busy" :title="targetPath" aria-label="目标文件夹" filterable default-first-option
        :reserve-keyword="false" :show-arrow="false" popper-class="folder-move-options"
        placeholder="搜索或选择文件夹" no-match-text="没有匹配的文件夹"
      >
        <template #prefix>
          <svg class="move-folder-icon" aria-hidden="true" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.6" stroke-linecap="round" stroke-linejoin="round">
            <path d="M3 7a2 2 0 0 1 2-2h5l2 2h7a2 2 0 0 1 2 2v10a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2Z" />
          </svg>
        </template>
        <el-option :value="0" label="未收纳">
          <span class="move-folder-option">未收纳</span>
        </el-option>
        <el-option v-for="folder in options" :key="folder.id" :value="folder.id" :label="folder.path">
          <span class="move-folder-option" :title="folder.path">{{ folder.path }}</span>
        </el-option>
      </el-select>
      <p class="move-hint">正文、原文件和知识库索引会保留。</p>
      <p v-if="!itemIds.length" class="move-error" role="alert">请先选择要移动的{{ noun }}。</p>
      <p v-else-if="!validTarget" class="move-error" role="alert">目标文件夹已不存在，请重新选择。</p>
      <p v-if="error" class="move-error" role="alert">{{ error }}</p>
      <div class="move-actions">
        <el-button :disabled="busy" @click="close">取消</el-button>
        <el-button type="primary" native-type="submit" :disabled="!canMove" :loading="busy">移动</el-button>
      </div>
    </form>
  </el-dialog>
</template>

<style scoped>
.folder-move-form { color: var(--app-text-1); }
.move-summary { margin: 0 0 20px; color: var(--app-text-2); font-size: 13px; line-height: 1.6; }
.move-summary strong { color: var(--app-text-1); font-weight: 600; }
.folder-move-form label { display: block; margin-bottom: 8px; font-size: 13px; color: var(--app-text-2); }
.move-target-select { width: 100%; --el-component-size: var(--control-h); }
.move-target-select :deep(.el-select__wrapper) { min-height: var(--control-h); background: var(--app-card); color: var(--app-text-1); border-radius: var(--radius); }
.move-folder-icon { width: 16px; height: 16px; color: var(--app-text-3); }
.move-folder-option { display: block; padding: 2px 0; white-space: normal; overflow-wrap: anywhere; font-size: 13px; line-height: 1.6; }
.move-hint { margin: 10px 0 0; color: var(--app-text-3); font-size: 12px; line-height: 1.6; }
.move-error { margin: 12px 0 0; color: var(--el-color-danger); font-size: 13px; line-height: 1.7; }
.move-actions { display: flex; justify-content: flex-end; gap: 8px; margin-top: 24px; }
.move-actions :deep(.el-button + .el-button) { margin-left: 0; }
:global(.folder-move-dialog) { max-width: calc(100vw - 32px); }
:global(.folder-move-dialog .el-dialog__title) { color: var(--app-text-1); font-size: 18px; font-weight: 600; }
:global(.folder-move-options.el-popper) { max-width: calc(100vw - 48px); background: var(--app-card); border-color: var(--app-border); border-radius: var(--radius); box-shadow: var(--shadow-md); }
:global(.folder-move-options .el-select-dropdown__list) { padding: 6px; }
:global(.folder-move-options .el-select-dropdown__item) { height: auto; min-height: 36px; padding: 8px 10px; margin: 2px 0; border-radius: var(--radius-sm); color: var(--app-text-1); line-height: 1.6; }
:global(.folder-move-options .el-select-dropdown__item.is-hovering) { background: var(--app-brand-soft); }
:global(.folder-move-options .el-select-dropdown__item.is-selected) { background: var(--app-brand-soft); color: var(--app-brand-deep); font-weight: 600; }
</style>
