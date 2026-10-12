<script setup>
import { computed, nextTick, onBeforeUnmount, onMounted, reactive, ref, watch } from 'vue'
import { isDark } from '../composables/useTheme.js'
import { createMermaidPreviewState, mermaidFrameDocument, mermaidSvgSize, readMermaidTheme } from '../utils/mermaidPreview.js'

const props = defineProps({
  source: { type: String, default: '' },
  active: { type: Boolean, default: true },
})
const emit = defineEmits(['rendered', 'error'])
const host = ref(null)
const width = ref(640)
const state = reactive({ loading: false, svg: '', error: '' })
const size = computed(() => mermaidSvgSize(state.svg))
const frameHeight = computed(() => Math.min(2000, Math.max(100, Math.ceil((Math.min(Math.max(1, width.value - 16), size.value.width) / size.value.width) * size.value.height) + 24)))
const frameDocument = computed(() => state.svg ? mermaidFrameDocument(state.svg, readMermaidTheme(isDark.value)) : '')
let mounted = false
let resizeObserver
const preview = createMermaidPreviewState({
  onState(next) {
    Object.assign(state, next)
    if (next.svg) emit('rendered')
    if (next.error) emit('error', next.error)
  },
})

function refresh() {
  if (!mounted) return
  if (!props.active) { preview.invalidate(); return }
  preview.update(props.source, readMermaidTheme(isDark.value))
}
watch([() => props.source, () => props.active], refresh, { flush: 'sync' })
watch(isDark, () => {
  // useTheme changes the reactive flag before toggling html.dark. Invalidate now,
  // but read CSS tokens after that synchronous theme application has finished.
  preview.invalidate()
  nextTick(refresh)
}, { flush: 'sync' })
onMounted(() => {
  mounted = true
  if (typeof ResizeObserver !== 'undefined') {
    resizeObserver = new ResizeObserver(entries => { width.value = Math.max(1, entries[0]?.contentRect.width || 640) })
    resizeObserver.observe(host.value)
  }
  refresh()
})
onBeforeUnmount(() => { mounted = false; preview.dispose(); resizeObserver?.disconnect() })
</script>

<template>
  <div ref="host" class="mermaid-preview" contenteditable="false" :aria-busy="state.loading">
    <p v-if="state.loading" class="mermaid-status" role="status">正在绘制流程图…</p>
    <div v-else-if="state.error" class="mermaid-error" role="status">
      <strong>请检查流程图源码</strong>
      <p>{{ state.error }}</p>
    </div>
    <iframe v-else-if="state.svg" class="mermaid-frame" title="Mermaid 流程图预览" role="img" aria-label="流程图预览，可切换到源码查看详细内容" sandbox="" referrerpolicy="no-referrer"
      :srcdoc="frameDocument" :style="{ height: `${frameHeight}px` }" />
  </div>
</template>

<style scoped>
.mermaid-preview { min-width: 0; width: 100%; background: var(--app-card); }
.mermaid-frame { display: block; width: 100%; border: 0; background: transparent; }
.mermaid-status { margin: 0; padding: 24px 16px; color: var(--app-text-2); font: 14px/1.7 var(--font-sans, system-ui, sans-serif); }
.mermaid-error { padding: 16px; color: var(--app-text-2); font: 14px/1.7 var(--font-sans, system-ui, sans-serif); }
.mermaid-error strong { color: var(--app-text-1); }
.mermaid-error p { margin: 8px 0 0; white-space: pre-wrap; overflow-wrap: anywhere; }
</style>
