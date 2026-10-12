<script setup>
import { computed, onBeforeUnmount, ref, useId, watch } from 'vue'
import { noteApi } from '../api/index.js'
import { createNoteLink, extractNoteHeadings, validateLinkHref } from '../utils/noteLinks.js'

const props = defineProps({
  modelValue: { type: Boolean, default: false },
  currentNoteId: { type: [Number, String], default: null },
  currentTitle: { type: String, default: '' },
  currentContent: { type: String, default: '' },
  initialText: { type: String, default: '' },
})
const emit = defineEmits(['update:modelValue', 'insert'])
const id = `note-link-${useId()}`
const type = ref('note')
const text = ref('')
const textEdited = ref(false)
const address = ref('')
const keyword = ref('')
const notes = ref([])
const total = ref(0)
const selectedNoteId = ref(null)
const headingKey = ref('')
const detail = ref(null)
const listLoading = ref(false)
const detailLoading = ref(false)
const listError = ref('')
const detailError = ref('')
let listRequest = 0, detailRequest = 0

function noteId(value) {
  const number = Number(value)
  return Number.isSafeInteger(number) && number > 0 ? number : null
}
const currentId = computed(() => noteId(props.currentNoteId))
const isCurrent = computed(() => selectedNoteId.value !== null && selectedNoteId.value === currentId.value)
const noteOptions = computed(() => {
  const list = notes.value.filter(note => noteId(note.id) !== currentId.value)
  return currentId.value
    ? [{ id: currentId.value, title: props.currentTitle || '当前笔记', current: true }, ...list]
    : list
})
const selectedTitle = computed(() => isCurrent.value
  ? props.currentTitle || '当前笔记'
  : detail.value?.title || noteOptions.value.find(note => noteId(note.id) === selectedNoteId.value)?.title || '')
const headings = computed(() => extractNoteHeadings(isCurrent.value ? props.currentContent : detail.value?.content || ''))
const selectedHeading = computed(() => headings.value.find(heading => heading.key === headingKey.value) || null)
const ready = computed(() => Boolean(selectedNoteId.value) && !detailLoading.value && !detailError.value
  && (isCurrent.value || noteId(detail.value?.id) === selectedNoteId.value))
const target = computed(() => type.value === 'external'
  ? address.value.trim()
  : createNoteLink(selectedNoteId.value, selectedHeading.value))
const defaultText = computed(() => type.value === 'external'
  ? address.value.trim()
  : selectedHeading.value?.text || selectedTitle.value)
const canInsert = computed(() => type.value === 'external'
  ? validateLinkHref(target.value)
  : ready.value && (!headingKey.value || Boolean(selectedHeading.value)))

watch(defaultText, value => { if (!textEdited.value) text.value = value })

function editText(value) {
  textEdited.value = true
  text.value = value
}

async function loadNotes(value = keyword.value) {
  keyword.value = value
  const request = ++listRequest
  listLoading.value = true
  listError.value = ''
  try {
    const page = await noteApi.page({ kw: value.trim(), page: 1, size: 50 })
    if (request !== listRequest || !props.modelValue) return
    notes.value = Array.isArray(page?.list) ? page.list.filter(note => noteId(note?.id)) : []
    total.value = Number(page?.total) || notes.value.length
  } catch (reason) {
    if (request !== listRequest || !props.modelValue) return
    notes.value = []
    listError.value = reason?.message || '笔记加载失败，请重试'
  } finally {
    if (request === listRequest) listLoading.value = false
  }
}

async function loadSelected() {
  const request = ++detailRequest
  const selected = selectedNoteId.value
  headingKey.value = ''
  detail.value = null
  detailError.value = ''
  detailLoading.value = false
  if (!selected || isCurrent.value || !props.modelValue) return
  detailLoading.value = true
  try {
    const note = await noteApi.detail(selected)
    if (request !== detailRequest || !props.modelValue || selected !== selectedNoteId.value) return
    if (noteId(note?.id) !== selected) throw new Error('笔记信息不一致，请重新选择')
    detail.value = note
  } catch (reason) {
    if (request !== detailRequest || !props.modelValue || selected !== selectedNoteId.value) return
    detailError.value = reason?.message || '章节加载失败，请重试'
  } finally {
    if (request === detailRequest) detailLoading.value = false
  }
}

watch(selectedNoteId, loadSelected)
watch(() => props.modelValue, visible => {
  listRequest++
  detailRequest++
  listLoading.value = false
  detailLoading.value = false
  if (!visible) return
  type.value = 'note'
  text.value = props.initialText
  textEdited.value = Boolean(props.initialText)
  address.value = ''
  keyword.value = ''
  notes.value = []
  total.value = 0
  listError.value = ''
  detailError.value = ''
  detail.value = null
  headingKey.value = ''
  selectedNoteId.value = currentId.value
  // Current note uses the unsaved editor document, never an older server copy.
  if (!textEdited.value) text.value = currentId.value ? props.currentTitle || '当前笔记' : ''
  loadNotes('')
}, { immediate: true })

function close() {
  listRequest++
  detailRequest++
  emit('update:modelValue', false)
}

function insert() {
  if (!canInsert.value) return
  emit('insert', { href: target.value, text: text.value.trim() || defaultText.value || target.value })
  close()
}

onBeforeUnmount(() => { listRequest++; detailRequest++ })
</script>

<template>
  <el-dialog :model-value="modelValue" title="插入超链接" width="480px" class="note-link-dialog" append-to-body @update:model-value="close">
    <form class="note-link-form" @submit.prevent="insert">
      <el-radio-group v-model="type" size="small" class="link-type" aria-label="链接类型">
        <el-radio-button value="note">笔记与章节</el-radio-button>
        <el-radio-button value="external">网页与其他链接</el-radio-button>
      </el-radio-group>
      <template v-if="type === 'note'">
        <label :for="`${id}-note`">目标笔记</label>
        <el-select
          :id="`${id}-note`" v-model="selectedNoteId" filterable remote :remote-method="loadNotes"
          :loading="listLoading" :reserve-keyword="false" class="link-select"
          placeholder="输入标题搜索笔记" no-data-text="没有找到笔记" no-match-text="没有找到笔记"
          popper-class="note-link-options" aria-label="目标笔记"
        >
          <el-option v-for="note in noteOptions" :key="note.id" :value="noteId(note.id)" :label="note.title || '无标题笔记'">
            <span class="link-option-title" :title="note.title">{{ note.title || '无标题笔记' }}</span>
            <span v-if="note.current" class="link-current-tag">当前笔记</span>
          </el-option>
        </el-select>
        <p v-if="listError" class="link-error" role="alert">{{ listError }} <el-button link type="primary" @click="loadNotes()">重试</el-button></p>
        <p v-else-if="total > 50" class="link-hint">显示前 50 篇笔记，输入标题可继续查找。</p>
        <label :for="`${id}-heading`">跳转位置</label>
        <el-select
          :id="`${id}-heading`" v-model="headingKey" :empty-values="[null, undefined]" filterable :loading="detailLoading" :disabled="!ready"
          class="link-select" placeholder="选择章节" no-match-text="没有匹配的章节" popper-class="note-link-options"
          aria-label="目标章节"
        >
          <el-option value="" label="笔记开头" />
          <el-option v-for="heading in headings" :key="heading.key" :value="heading.key"
            :label="`${heading.text}${heading.occurrence > 1 ? `（第 ${heading.occurrence} 处）` : ''}`">
            <span class="link-heading-option" :style="{ paddingLeft: `${Math.min(heading.level - 1, 4) * 10}px` }" :title="heading.text">
              <span class="link-heading-level">H{{ heading.level }}</span>
              {{ heading.text }}<span v-if="heading.occurrence > 1" class="link-duplicate"> · 第 {{ heading.occurrence }} 处</span>
            </span>
          </el-option>
        </el-select>
        <p v-if="detailError" class="link-error" role="alert">{{ detailError }} <el-button link type="primary" @click="loadSelected">重试</el-button></p>
        <p v-else-if="ready && !headings.length" class="link-hint">这篇笔记暂无标题，将跳转到笔记开头。</p>
        <p v-else-if="isCurrent" class="link-hint">当前笔记的章节按编辑器中最新的正文识别。</p>
        <p v-if="!currentId" class="link-hint">保存当前笔记后，可创建指向它的链接。</p>
      </template>
      <template v-else>
        <label :for="`${id}-address`">链接地址</label>
        <el-input :id="`${id}-address`" v-model="address" placeholder="https://example.com 或站内路径" clearable aria-label="链接地址" />
        <p v-if="address && !validateLinkHref(address)" class="link-error" role="alert">请输入有效的网页、邮箱或站内链接。</p>
      </template>
      <label :for="`${id}-text`">显示文字</label>
      <el-input :id="`${id}-text`" :model-value="text" :placeholder="defaultText || '链接显示的文字'" @update:model-value="editText" aria-label="显示文字" />
      <div v-if="canInsert" class="link-destination" :title="target">{{ target }}</div>
      <div class="link-actions">
        <el-button @click="close">取消</el-button>
        <el-button type="primary" native-type="submit" :disabled="!canInsert">插入链接</el-button>
      </div>
    </form>
  </el-dialog>
</template>

<style scoped>
.note-link-form { color: var(--app-text-1); }
.link-type { margin-bottom: 6px; }
.note-link-form label { display: block; margin: 16px 0 8px; color: var(--app-text-2); font-size: 13px; }
.link-select { width: 100%; }
.link-hint { margin: 8px 0 0; color: var(--app-text-3); font-size: 12px; line-height: 1.6; }
.link-error { margin: 8px 0 0; color: var(--el-color-danger); font-size: 12px; line-height: 1.6; }
.link-error :deep(.el-button) { font-size: 12px; vertical-align: baseline; }
.link-destination { margin-top: 12px; padding: 8px 10px; border-radius: var(--radius-sm); background: var(--app-bg); color: var(--app-text-3); font-size: 11px; white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }
.link-actions { display: flex; justify-content: flex-end; gap: 8px; margin-top: 20px; }
.link-actions :deep(.el-button + .el-button) { margin-left: 0; }
.link-option-title { display: inline-block; max-width: calc(100% - 70px); overflow: hidden; text-overflow: ellipsis; vertical-align: middle; }
.link-current-tag { margin-left: 8px; color: var(--app-text-3); font-size: 11px; }
.link-heading-option { display: block; overflow: hidden; text-overflow: ellipsis; }
.link-heading-level { margin-right: 7px; color: var(--app-text-3); font-size: 10px; }
.link-duplicate { color: var(--app-text-3); font-size: 11px; }
:global(.note-link-dialog) { max-width: calc(100vw - 32px); }
:global(.note-link-dialog .el-dialog__title) { color: var(--app-text-1); font-size: 18px; font-weight: 600; }
:global(.note-link-options.el-popper) { max-width: calc(100vw - 48px); background: var(--app-card); border-color: var(--app-border); border-radius: var(--radius); }
:global(.note-link-options .el-select-dropdown__list) { padding: 6px; }
:global(.note-link-options .el-select-dropdown__item) { padding: 0 10px; margin: 2px 0; border-radius: var(--radius-sm); color: var(--app-text-1); font-size: 13px; }
:global(.note-link-options .el-select-dropdown__item.is-hovering) { background: var(--app-brand-soft); }
:global(.note-link-options .el-select-dropdown__item.is-selected) { background: var(--app-brand-soft); color: var(--app-brand-deep); font-weight: 600; }
</style>
