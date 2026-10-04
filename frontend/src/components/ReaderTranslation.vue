<script setup>
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import { fileApi } from '../api'
import { createReaderTranslation, selectedReaderText, translationLimit } from '../utils/readerTranslation'
import { createReaderSelectionHighlight } from '../utils/readerSelectionHighlight'

const props = defineProps({
  fileId: { type: [Number, String], required: true },
  /** An element containing only original text, never the toolbar or this panel. */
  sourceRoot: { type: Object, default: null },
  maxChars: { type: Number, default: undefined },
  capabilities: { type: Object, default: () => ({}) },
})
const targetLang = ref('简体中文')
const automatic = ref(true)
const locked = ref(false)
const collapsed = ref(false)
const width = ref(340)
const frame = ref(null)
const originalFrame = ref(null)
const selectionRects = ref([])
const narrow = ref(false)
const state = ref({ text: '', translation: '', model: '', loading: false, error: '', cached: false })
const maxChars = computed(() => translationLimit(props.maxChars ?? props.capabilities?.maxChars))
const sourceChars = computed(() => state.value.text.trim().length)
const languages = computed(() => props.capabilities?.langs?.length ? props.capabilities.langs : ['简体中文', 'English', '日本語', '한국어'])
const ready = computed(() => sourceChars.value > 0 && sourceChars.value <= maxChars.value)
const selectionHighlight = createReaderSelectionHighlight(window, (rects) => {
  const bounds = originalFrame.value?.getBoundingClientRect()
  selectionRects.value = bounds ? rects.map((rect) => ({
    left: `${rect.left - bounds.left}px`, top: `${rect.top - bounds.top}px`,
    width: `${rect.width}px`, height: `${rect.height}px`,
  })) : []
})
const session = createReaderTranslation({
  fileId: props.fileId, targetLang: targetLang.value, maxChars: maxChars.value,
  translate: (id, text, language) => fileApi.translate(id, text, language),
  onChange: (next) => { state.value = next },
})

function translateText(text, { force = false } = {}) {
  if (locked.value) {
    ElMessage.info('当前译文已锁定，请先解锁再更换原文')
    return Promise.resolve(null)
  }
  clearSelectionHighlight()
  session.selectText(text)
  collapsed.value = false
  return session.request({ force })
}
function captureSelection(event) {
  const root = props.sourceRoot?.$el || props.sourceRoot
  if (!root || !root.getClientRects().length) return
  if (!root.contains(event.target)) {
    // Shift selection often dispatches its key event to <body>, rather than
    // the non-focusable paragraph. The range itself still has to belong here.
    if (event.type !== 'keyup' || event.target?.closest?.('input, textarea, select, button, .translation-panel')) return
  }
  const selection = window.getSelection?.()
  const text = selectedReaderText(selection, root)
  if (!text || locked.value) return
  selectionHighlight.set(selection, root)
  session.selectText(text)
  collapsed.value = false
  if (automatic.value && ready.value && !state.value.loading && !state.value.translation) session.request()
}
function selectionKey(event) {
  if (event.key === 'Shift' || (event.shiftKey && event.key.startsWith('Arrow'))) captureSelection(event)
}
function clearSelectionHighlight() {
  selectionHighlight.clear()
  const root = props.sourceRoot?.$el || props.sourceRoot
  const selection = window.getSelection?.()
  if (root && selection?.rangeCount && root.contains(selection.anchorNode) && root.contains(selection.focusNode)) selection.removeAllRanges()
}
function editSource(event) {
  clearSelectionHighlight()
  session.selectText(event.target.value, { trim: false })
}
function onAutomaticChange() {
  if (automatic.value && !locked.value && ready.value && !state.value.translation && !state.value.loading) session.request()
}
function requestTranslation() {
  if (!locked.value) return session.request({ force: true })
}
function clear() { clearSelectionHighlight(); session.clear(); locked.value = false }
async function copy(text) {
  if (!text) return
  try { await navigator.clipboard.writeText(text); ElMessage.success('已复制') }
  catch { ElMessage.warning('复制失败，请手动选择并复制') }
}
let stopResize = null
function startResize(event) {
  if (event.button !== 0) return
  event.preventDefault()
  const startX = event.clientX
  const startWidth = width.value
  const resize = (next) => {
    const max = Math.max(240, Math.min(560, (frame.value?.clientWidth || 900) * 0.55))
    width.value = Math.min(max, Math.max(240, startWidth + startX - next.clientX))
  }
  const stop = () => {
    window.removeEventListener('pointermove', resize)
    window.removeEventListener('pointerup', stop)
    window.removeEventListener('pointercancel', stop)
    stopResize = null
  }
  stopResize?.()
  stopResize = stop
  window.addEventListener('pointermove', resize)
  window.addEventListener('pointerup', stop)
  window.addEventListener('pointercancel', stop)
}
function resizeKey(event) {
  if (!['ArrowLeft', 'ArrowRight'].includes(event.key)) return
  event.preventDefault()
  width.value = Math.min(560, Math.max(240, width.value + (event.key === 'ArrowLeft' ? 20 : -20)))
}
watch(() => [props.fileId, targetLang.value, maxChars.value], ([fileId, language, limit], previous) => {
  const changedFile = previous && String(previous[0]) !== String(fileId)
  session.setContext({ fileId, targetLang: language, maxChars: limit })
  if (changedFile) { selectionHighlight.clear(); locked.value = false }
  if (!changedFile && automatic.value && !locked.value && ready.value && frame.value?.getClientRects().length) session.request()
})
watch(() => props.sourceRoot, () => selectionHighlight.clear(), { flush: 'post' })
let layoutObserver = null
onMounted(() => {
  layoutObserver = new ResizeObserver(() => {
    const available = frame.value?.clientWidth || 0
    if (available) narrow.value = available <= 800
  })
  if (frame.value) layoutObserver.observe(frame.value)
  document.addEventListener('mouseup', captureSelection)
  document.addEventListener('touchend', captureSelection)
  document.addEventListener('keyup', selectionKey)
})
onBeforeUnmount(() => {
  selectionHighlight.clear()
  session.clear()
  stopResize?.()
  layoutObserver?.disconnect()
  document.removeEventListener('mouseup', captureSelection)
  document.removeEventListener('touchend', captureSelection)
  document.removeEventListener('keyup', selectionKey)
})
defineExpose({ translateText, clear })
</script>

<template>
  <div ref="frame" class="reader-translation-layout" :class="{ collapsed, narrow }" :style="{ '--translation-width': width + 'px' }">
    <div ref="originalFrame" class="reader-original" :data-selection-highlight="selectionHighlight.mode" :data-highlight-count="selectionHighlight.size">
      <slot />
      <div v-if="selectionRects.length" class="reader-selection-overlay" aria-hidden="true" data-translation-ignore>
        <span v-for="(rect, index) in selectionRects" :key="index" :style="rect" />
      </div>
    </div>
    <button v-if="collapsed" type="button" class="translation-reopen reader-btn" @click="collapsed = false" title="打开翻译侧栏">译文 ›</button>
    <template v-else>
      <div class="translation-resize" role="separator" tabindex="0" aria-label="调整译文栏宽度" aria-orientation="vertical"
           :aria-valuenow="width" :aria-valuemin="240" :aria-valuemax="560" @pointerdown="startResize" @keydown="resizeKey" />
      <aside class="translation-panel" aria-label="阅读翻译">
        <header class="translation-header">
          <strong>阅读翻译</strong>
          <button type="button" class="translation-close" aria-label="收起译文栏" title="收起译文栏" @click="collapsed = true">›</button>
        </header>
        <div class="translation-controls">
          <select v-model="targetLang" class="reader-select" aria-label="翻译目标语言" :disabled="locked">
            <option v-for="language in languages" :key="language" :value="language">{{ language }}</option>
          </select>
          <label class="translation-checkbox"><input v-model="automatic" type="checkbox" @change="onAutomaticChange" /> 划词自动翻译</label>
          <label class="translation-checkbox" :class="{ locked }"><input v-model="locked" type="checkbox" /> 锁定译文</label>
        </div>
        <div class="translation-scroll">
          <p class="translation-guide">{{ locked ? '译文已锁定，解锁后可选择新的文字。' : automatic ? '在左侧选择单词或段落，译文会显示在这里。' : '选择原文后，点击「翻译」查看译文。' }}</p>
          <div class="translation-section-head">
            <b>原文</b>
            <span class="translation-count" :class="{ exceeded: sourceChars > maxChars }">{{ sourceChars }} / {{ maxChars }}</span>
            <button type="button" class="translation-text-button" :disabled="!state.text" @click="copy(state.text)">复制</button>
          </div>
          <textarea :value="state.text" class="translation-source" rows="6" aria-label="待翻译原文，可修改后重新翻译" placeholder="选中文字，也可以在这里修改原文后翻译"
                    :readonly="locked" @input="editSource" />
          <div class="translation-actions">
            <button type="button" class="reader-btn primary-translate" :disabled="!ready || state.loading || locked" @click="requestTranslation">{{ state.loading ? '翻译中…' : state.translation ? '重新翻译' : '翻译' }}</button>
            <button type="button" class="reader-btn" :disabled="!state.text || locked" @click="clear">清空</button>
          </div>
          <p v-if="state.error" class="translation-error" role="alert">{{ state.error }}</p>
          <div class="translation-section-head translation-result-head">
            <b>译文</b>
            <button type="button" class="translation-text-button" :disabled="!state.translation" @click="copy(state.translation)">复制</button>
          </div>
          <div class="translation-result" aria-live="polite" :aria-busy="state.loading">
            <p v-if="state.loading" class="translation-empty">正在翻译所选内容…</p>
            <p v-else-if="state.translation" class="translation-text">{{ state.translation }}</p>
            <p v-else class="translation-empty">{{ state.error ? '请调整原文后重试。' : '选中即译，原文与译文对照阅读。' }}</p>
          </div>
          <p v-if="state.model || capabilities.profile" class="translation-model">{{ state.model || [capabilities.profile, capabilities.model].filter(Boolean).join(' / ') }}<span v-if="state.cached"> · 已缓存</span></p>
        </div>
      </aside>
    </template>
  </div>
</template>

<style scoped>
.reader-translation-layout { display: flex; flex: 1 1 auto; min-width: 0; min-height: 0; height: 100%; position: relative; }
.reader-original { flex: 1 1 auto; min-width: 0; min-height: 0; display: flex; flex-direction: column; position: relative; overflow: hidden; }
.reader-selection-overlay { position: absolute; inset: 0; pointer-events: none; z-index: 6; overflow: hidden; }
.reader-selection-overlay span { position: absolute; background: rgb(255 211 78 / 55%); pointer-events: none; mix-blend-mode: multiply; }
.translation-panel { display: flex; flex-direction: column; flex: 0 0 min(var(--translation-width), 55%); min-width: 240px; min-height: 0; overflow: hidden; background: var(--app-card, #fff); border: 1px solid var(--app-border, #dfe5e8); border-radius: 10px; }
.translation-resize { width: 10px; flex-shrink: 0; cursor: col-resize; touch-action: none; position: relative; }
.translation-resize::after { content: ''; position: absolute; width: 3px; height: 42px; border-radius: 4px; background: var(--app-border, #dfe5e8); left: 4px; top: calc(50% - 21px); }
.translation-resize:hover::after, .translation-resize:focus-visible::after { background: var(--app-brand, #46938b); }
.translation-header { display: flex; align-items: center; justify-content: space-between; gap: 10px; padding: 12px 14px 8px; color: var(--app-text-1); font-size: 14px; }
.translation-close { border: 0; background: transparent; color: var(--app-text-3); cursor: pointer; font-size: 24px; line-height: 1; padding: 0 4px; }
.translation-controls { display: flex; align-items: center; flex-wrap: wrap; gap: 9px 12px; padding: 0 14px 12px; border-bottom: 1px solid var(--app-border-weak, #edf0f2); font-size: 12px; }
.translation-checkbox { display: inline-flex; align-items: center; gap: 4px; color: var(--app-text-2); cursor: pointer; white-space: nowrap; }
.translation-checkbox input { accent-color: var(--app-brand); margin: 0; }
.translation-checkbox.locked { color: var(--app-brand-deep); }
.translation-scroll { flex: 1 1 auto; min-height: 0; overflow: auto; padding: 12px 14px 18px; }
.translation-guide { margin: 0 0 18px; font-size: 12px; line-height: 1.65; color: var(--app-text-3); }
.translation-section-head { display: flex; align-items: center; gap: 8px; font-size: 12px; color: var(--app-text-2); margin-bottom: 8px; }
.translation-count { font-size: 11px; margin-left: auto; color: var(--app-text-3); }
.translation-count.exceeded { color: #c65c42; }
.translation-text-button { border: 0; background: transparent; color: var(--app-brand-deep, #43847d); font-size: 12px; cursor: pointer; padding: 0; margin-left: auto; }
.translation-text-button:disabled { opacity: .4; cursor: default; }
.translation-source { box-sizing: border-box; width: 100%; resize: vertical; min-height: 115px; max-height: 38vh; border: 1px solid var(--app-border-weak, #e7ecee); border-radius: 8px; background: var(--app-bg, #f7f9fa); color: var(--app-text-2); padding: 10px; font: inherit; font-size: 12.5px; line-height: 1.75; }
.translation-source:focus { outline: 2px solid var(--app-brand-soft, #d2e7e2); border-color: var(--app-brand); }
.translation-actions { display: flex; gap: 8px; padding-top: 10px; }
.primary-translate { background: var(--app-brand-soft, #e8f4f1); color: var(--app-brand-deep, #35746d); border-color: transparent; }
.translation-error { color: #b34b3a; font-size: 12px; line-height: 1.65; margin: 12px 0 0; }
.translation-result-head { margin-top: 22px; }
.translation-result { color: var(--app-text-1); }
.translation-text { white-space: pre-wrap; line-height: 1.9; font-size: 14px; margin: 0; overflow-wrap: anywhere; }
.translation-empty { margin: 0; color: var(--app-text-3); font-size: 12px; line-height: 1.8; }
.translation-model { color: var(--app-text-3); font-size: 10.5px; line-height: 1.65; overflow-wrap: anywhere; margin: 20px 0 0; }
.translation-reopen { position: absolute; right: 8px; top: 8px; z-index: 20; box-shadow: 0 2px 8px #0001; }
.narrow { flex-direction: column; gap: 8px; }
.narrow .translation-panel { flex: 0 0 min(43%, 360px); min-height: 0; min-width: 0; border-radius: 10px; box-shadow: 0 -3px 16px #0001; }
.narrow .translation-resize { display: none; }
.narrow .translation-header { padding: 8px 12px 4px; }
.narrow .translation-controls { padding: 0 12px 8px; }
.narrow .translation-scroll { padding: 8px 12px 12px; }
.narrow .translation-guide { margin-bottom: 10px; }
.narrow .translation-source { min-height: 72px; height: 85px; }
</style>
