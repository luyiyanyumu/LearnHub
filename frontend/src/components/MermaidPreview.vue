<script setup>
import { computed, nextTick, onBeforeUnmount, onMounted, reactive, ref, watch } from 'vue'
import { FullScreen, Refresh, Share, Warning, ZoomIn, ZoomOut } from '@element-plus/icons-vue'
import { isDark } from '../composables/useTheme.js'
import { createMermaidPreviewState, mermaidFrameDocument, mermaidSvgSize, readMermaidTheme } from '../utils/mermaidPreview.js'
import { mermaidReadableSize, mermaidViewportSize } from '../utils/mermaidViewport.js'

const props = defineProps({
  source: { type: String, default: '' },
  active: { type: Boolean, default: true },
})
const emit = defineEmits(['rendered', 'error'])
const host = ref(null)
const width = ref(640)
const zoom = ref(1)
const inlineOverview = ref(false)
const viewerOpen = ref(false)
const viewerHost = ref(null)
const viewerWidth = ref(960)
const viewerZoom = ref(1)
const viewerNatural = ref(true)
const viewerMaxHeight = ref(560)
const state = reactive({ loading: false, svg: '', error: '' })
const size = computed(() => mermaidSvgSize(state.svg))
const frameSize = computed(() => (inlineOverview.value ? mermaidViewportSize : mermaidReadableSize)(size.value, Math.max(1, width.value - 48), zoom.value))
const frameHeight = computed(() => frameSize.value.height)
const needsScroll = computed(() => frameSize.value.height > 440 || frameSize.value.width > width.value - 48)
const viewerSize = computed(() => mermaidViewportSize(size.value, viewerNatural.value ? size.value.width : Math.max(1, viewerWidth.value - 48), viewerZoom.value, { maxHeight: viewerNatural.value ? size.value.height : viewerMaxHeight.value }))
const frameDocument = computed(() => state.svg ? mermaidFrameDocument(state.svg, { ...readMermaidTheme(isDark.value), padding: 0, fit: true }) : '')
let mounted = false
let resizeObserver
let viewerResizeObserver
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
function zoomBy(amount, enlarged = false) {
  const value = enlarged ? viewerZoom : zoom
  value.value = Math.max(0.5, Math.min(2, Math.round((value.value + amount) * 100) / 100))
}
function fit(enlarged = false) {
  (enlarged ? viewerZoom : zoom).value = 1
  if (enlarged) viewerNatural.value = false
  else inlineOverview.value = true
}
function naturalSize() { viewerNatural.value = true; viewerZoom.value = 1 }
function openViewer() {
  if (!props.active || !state.svg) return
  viewerZoom.value = 1
  viewerNatural.value = true
  viewerOpen.value = true
}
function closeViewer() { viewerOpen.value = false }
function measureViewer() {
  if (!viewerHost.value) return
  viewerWidth.value = viewerHost.value.clientWidth || 960
  viewerMaxHeight.value = typeof window === 'undefined' ? 560 : Math.max(160, Math.min(560, window.innerHeight - 240))
}
function observeViewer() {
  measureViewer()
  if (typeof ResizeObserver !== 'undefined' && viewerHost.value) {
    viewerResizeObserver?.disconnect()
    viewerResizeObserver = new ResizeObserver(measureViewer)
    viewerResizeObserver.observe(viewerHost.value)
  }
  window.addEventListener('resize', measureViewer)
}
function stopObservingViewer() {
  viewerResizeObserver?.disconnect()
  if (typeof window !== 'undefined') window.removeEventListener('resize', measureViewer)
}
function onCanvasKeydown(event, enlarged = false) {
  if (event.ctrlKey || event.metaKey || event.altKey || !state.svg) return
  const canvas = event.currentTarget
  const navigation = {
    ArrowDown: [0, 40], ArrowUp: [0, -40], ArrowRight: [40, 0], ArrowLeft: [-40, 0],
    PageDown: [0, canvas?.clientHeight || 400], PageUp: [0, -(canvas?.clientHeight || 400)],
  }
  if (navigation[event.key] && canvas?.scrollBy) canvas.scrollBy(...navigation[event.key])
  else if (event.key === 'Home' && canvas?.scrollTo) canvas.scrollTo(0, 0)
  else if (event.key === 'End' && canvas?.scrollTo) canvas.scrollTo(canvas.scrollWidth, canvas.scrollHeight)
  else if (event.key === '+' || event.key === '=') zoomBy(0.25, enlarged)
  else if (event.key === '-') zoomBy(-0.25, enlarged)
  else if (event.key === '0') fit(enlarged)
  else if (event.key === 'Enter' && !enlarged) openViewer()
  else return
  event.preventDefault()
  event.stopPropagation()
}
watch([() => props.source, () => props.active], refresh, { flush: 'sync' })
watch(() => props.source, () => { zoom.value = 1; viewerZoom.value = 1; inlineOverview.value = false })
watch(() => props.active, active => { if (!active) closeViewer() })
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
onBeforeUnmount(() => { mounted = false; preview.dispose(); resizeObserver?.disconnect(); stopObservingViewer() })
defineExpose({ openViewer, closeViewer })
</script>

<template>
  <div ref="host" class="mermaid-preview" contenteditable="false" :aria-busy="state.loading" @mousedown.stop @click.stop>
    <div v-if="state.loading" class="mermaid-status" role="status"><el-icon class="mermaid-loading-icon"><Share /></el-icon><span>正在绘制流程图…</span></div>
    <div v-else-if="state.error" class="mermaid-error" role="status">
      <el-icon class="mermaid-error-icon"><Warning /></el-icon>
      <strong>暂时无法预览这张图</strong>
      <p>源码已保留，切换到「源码」检查后即可重新预览。</p>
      <details><summary>查看错误详情</summary><p>{{ state.error }}</p></details>
    </div>
    <template v-else-if="state.svg">
      <div class="mermaid-canvas" role="region" aria-label="流程图画布" tabindex="0" @keydown="onCanvasKeydown($event)">
        <div class="mermaid-canvas-inner" :style="{ minWidth: `${frameSize.width + 48}px` }">
          <iframe class="mermaid-frame" title="Mermaid 流程图预览" role="img" aria-label="流程图预览，可切换到源码查看详细内容" sandbox="" referrerpolicy="no-referrer" :srcdoc="frameDocument" :style="{ width: `${frameSize.width}px`, height: `${frameHeight}px` }" />
        </div>
      </div>
      <div class="mermaid-toolbar" role="toolbar" aria-label="流程图视图控制">
        <span class="mermaid-hint">{{ needsScroll ? '滚动查看完整流程图' : '完整预览' }} <span class="mermaid-key-hint">· 按 + / − 缩放</span></span>
        <div class="mermaid-zoom-controls">
          <el-button class="mermaid-tool" text :icon="ZoomOut" aria-label="缩小流程图" title="缩小" :disabled="zoom <= 0.5" @click="zoomBy(-0.25)" />
          <output class="mermaid-scale" aria-label="流程图缩放比例">{{ Math.round(zoom * 100) }}%</output>
          <el-button class="mermaid-tool" text :icon="ZoomIn" aria-label="放大流程图" title="放大" :disabled="zoom >= 2" @click="zoomBy(0.25)" />
          <el-button class="mermaid-tool mermaid-fit" text :icon="Refresh" @click="fit()">适应画布</el-button>
        </div>
      </div>
    </template>
    <el-dialog v-model="viewerOpen" title="流程图大图" width="min(1100px, calc(100vw - 32px))" top="6vh" append-to-body destroy-on-close class="mermaid-viewer-dialog" @opened="observeViewer" @closed="stopObservingViewer">
      <template #header><div class="mermaid-viewer-heading"><el-icon><FullScreen /></el-icon><strong>流程图大图</strong><span>按 + / − 缩放，0 适应画布</span></div></template>
      <div ref="viewerHost" class="mermaid-viewer">
        <div class="mermaid-canvas mermaid-viewer-canvas" role="region" aria-label="流程图大图画布" tabindex="0" :style="{ maxHeight: `${viewerMaxHeight + 48}px` }" @keydown="onCanvasKeydown($event, true)">
          <div class="mermaid-canvas-inner" :style="{ minWidth: `${viewerSize.width + 48}px` }">
            <iframe v-if="state.svg" class="mermaid-frame" title="Mermaid 流程图大图" role="img" aria-label="流程图放大预览" sandbox="" referrerpolicy="no-referrer" :srcdoc="frameDocument" :style="{ width: `${viewerSize.width}px`, height: `${viewerSize.height}px` }" />
          </div>
        </div>
        <div class="mermaid-toolbar mermaid-viewer-toolbar" role="toolbar" aria-label="流程图大图视图控制">
          <span class="mermaid-hint">滚动查看完整流程图</span>
          <div class="mermaid-zoom-controls">
            <el-button class="mermaid-tool" text :icon="ZoomOut" aria-label="缩小大图" :disabled="viewerZoom <= 0.5" @click="zoomBy(-0.25, true)" />
            <output class="mermaid-scale" aria-label="大图缩放比例">{{ Math.round(viewerZoom * 100) }}%</output>
            <el-button class="mermaid-tool" text :icon="ZoomIn" aria-label="放大大图" :disabled="viewerZoom >= 2" @click="zoomBy(0.25, true)" />
            <el-button class="mermaid-tool mermaid-fit" text @click="naturalSize()">原始大小</el-button>
            <el-button class="mermaid-tool mermaid-fit" text :icon="Refresh" @click="fit(true)">适应大图</el-button>
          </div>
        </div>
      </div>
    </el-dialog>
  </div>
</template>

<style scoped>
.mermaid-preview { min-width: 0; width: 100%; background: var(--app-card); }
.mermaid-frame { display: block; flex: none; border: 0; background: transparent; }
.mermaid-canvas { max-height: 488px; overflow: auto; overscroll-behavior: contain; scrollbar-gutter: stable; }
.mermaid-canvas:focus-visible { outline: 2px solid var(--app-brand); outline-offset: -3px; border-radius: 4px; }
.mermaid-canvas-inner { display: flex; align-items: center; justify-content: center; box-sizing: border-box; width: 100%; min-height: 200px; padding: 24px; }
.mermaid-toolbar { display: flex; align-items: center; justify-content: space-between; gap: 8px; min-height: 48px; padding: 6px 16px; box-sizing: border-box; border-top: 1px solid var(--app-border-weak); background: color-mix(in srgb, var(--app-bg) 50%, var(--app-card)); }
.mermaid-hint { color: var(--app-text-2); font: 12px/1.5 var(--font-sans, system-ui, sans-serif); }
.mermaid-zoom-controls { display: flex; align-items: center; gap: 4px; flex: none; }
.mermaid-scale { min-width: 40px; color: var(--app-text-2); text-align: center; font: 12px/1.5 ui-monospace, monospace; }
.mermaid-tool.el-button { height: 32px; min-width: 32px; margin: 0; padding: 8px; color: var(--app-text-2); font-size: 14px; }
.mermaid-fit.el-button { font-size: 12px; }
.mermaid-tool.el-button:not(:disabled):hover { color: var(--app-brand-deep); background: var(--app-brand-soft); }
.mermaid-tool.el-button:focus-visible { outline: 2px solid var(--app-brand); outline-offset: 2px; }
.mermaid-status { min-height: 220px; display: flex; flex-direction: column; justify-content: center; align-items: center; gap: 12px; color: var(--app-text-2); font: 14px/1.7 var(--font-sans, system-ui, sans-serif); }
.mermaid-loading-icon, .mermaid-error-icon { width: 24px; height: 24px; padding: 12px; border-radius: 12px; background: var(--app-brand-soft); color: var(--app-brand-deep); font-size: 24px; }
.mermaid-error { display: flex; flex-direction: column; align-items: center; padding: 24px; color: var(--app-text-2); font: 14px/1.7 var(--font-sans, system-ui, sans-serif); text-align: center; }
.mermaid-error strong { margin-top: 12px; color: var(--app-text-1); }
.mermaid-error p { margin: 8px 0 0; white-space: pre-wrap; overflow-wrap: anywhere; }
.mermaid-error details { margin-top: 16px; max-width: 100%; text-align: left; font-size: 12px; }
.mermaid-error summary { cursor: pointer; color: var(--app-brand-deep); }
.mermaid-viewer-heading { display: flex; align-items: center; gap: 8px; color: var(--app-text-1); font: 14px/1.5 var(--font-sans, system-ui, sans-serif); }
.mermaid-viewer-heading > .el-icon { color: var(--app-brand-deep); }
.mermaid-viewer-heading > span { margin-left: 8px; color: var(--app-text-2); font-size: 12px; font-weight: 400; }
.mermaid-viewer { overflow: hidden; border: 1px solid var(--app-border); border-radius: var(--radius, 8px); background: var(--app-card); }
.mermaid-viewer-canvas .mermaid-canvas-inner { min-height: 240px; }
@media (max-width: 600px) {
  .mermaid-toolbar { padding: 6px 10px; }
  .mermaid-key-hint, .mermaid-viewer-heading > span, .mermaid-viewer-toolbar > .mermaid-hint { display: none; }
  .mermaid-viewer-toolbar { justify-content: center; }
  .mermaid-viewer-toolbar .mermaid-zoom-controls { flex: 1; min-width: 0; flex-wrap: wrap; justify-content: center; }
}
@media (max-width: 420px) {
  .mermaid-toolbar > .mermaid-hint { display: none; }
  .mermaid-toolbar { justify-content: center; }
}
</style>
