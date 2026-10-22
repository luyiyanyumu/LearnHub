<script setup>
import { computed, nextTick, onBeforeUnmount, onMounted, reactive, ref, useId, watch } from 'vue'
import { buildCommunityGalaxyMap, layoutCommunityGalaxyMap, createCommunityGalaxyIndex } from '../utils/knowledgeGraphCommunities.js'
import { createKnowledgeGalaxyRenderer, galaxyFitCamera, galaxyZoomAt } from '../utils/knowledgeGalaxyRenderer.js'

const props = defineProps({
  nodes: { type: Array, default: () => [] }, edges: { type: Array, default: () => [] },
  communities: { type: Object, default: () => ({}) }, groups: { type: Array, default: () => [] },
  overviewData: { type: Object, default: null },
  active: { type: Boolean, default: true },
})
const emit = defineEmits(['expand'])
const sceneRef = ref(null), canvasRef = ref(null), flowRef = ref(null), twinkleARef = ref(null), twinkleBRef = ref(null)
const instructionsId = `galaxy-${useId().replace(/[^a-zA-Z0-9_-]/g, '')}`
const overview = computed(() => props.overviewData || buildCommunityGalaxyMap(props.nodes, props.edges, props.communities, props.groups))
const layout = computed(() => layoutCommunityGalaxyMap(overview.value.nodes))
const index = computed(() => createCommunityGalaxyIndex(layout.value.nodes))
const byCommunityId = computed(() => new Map(layout.value.nodes.map(node => [String(node.communityId), node])))
const adjacency = computed(() => {
  const result = new Map()
  for (const edge of overview.value.edges) for (const id of [edge.source, edge.target]) {
    const key = String(id)
    if (!result.has(key)) result.set(key, [])
    result.get(key).push(edge)
  }
  return result
})
const view = reactive({ width: 1000, height: 520, dpr: 1 })
const camera = reactive({ x: 0, y: 0, k: 1 })
const metrics = reactive({ visibleCount: 0, paintedCount: 0, labelCount: 0, aggregated: false, relationsLimited: false })
const hoverId = ref(''), keyboardId = ref(''), interacting = ref(false)
const focusedId = computed(() => hoverId.value || keyboardId.value)
const focusedStar = computed(() => index.value.byId.get(String(focusedId.value)))
const inViewport = ref(false), pageVisible = ref(true), reducedMotion = ref(false)
const motionActive = computed(() => props.active && inViewport.value && pageVisible.value && !reducedMotion.value)
const zoomPercent = computed(() => Math.round(camera.k / galaxyFitCamera(layout.value, view.width, view.height).k * 100))
let renderer = null, resizeObserver = null, visibilityObserver = null, motionPreference = null
let frame = 0, idleTimer = 0, drag = null, pendingHover = null, fitted = true, destroyed = false

function formatCount(value) { return Number(value || 0).toLocaleString('zh-CN') }
function canDraw() { return props.active && inViewport.value && pageVisible.value && !destroyed }
function cancelFrame() { if (frame) cancelAnimationFrame(frame); frame = 0 }
function releaseDrag() {
  if (drag) { try { canvasRef.value?.releasePointerCapture?.(drag.id) } catch { /* Capture can already be cancelled by the browser. */ } }
  drag = null
}
function hit(point, touch = false) {
  return index.value.nearest((point.x - camera.x) / camera.k, (point.y - camera.y) / camera.k, (touch ? 24 : 14) / camera.k)
}
function render() {
  frame = 0
  if (!renderer || !canDraw()) return
  if (pendingHover && !drag) { hoverId.value = hit(pendingHover)?.id || ''; pendingHover = null }
  Object.assign(metrics, renderer.draw(layout.value, index.value, camera, view,
    { focusId: focusedId.value, adjacency: adjacency.value, byId: index.value.byId, interactive: interacting.value }))
}
function requestRender() { if (renderer && canDraw() && !frame) frame = requestAnimationFrame(render) }
function gesture() {
  interacting.value = true
  clearTimeout(idleTimer)
  idleTimer = setTimeout(() => { interacting.value = false; requestRender() }, 150)
}
function resetView() {
  fitted = true; hoverId.value = ''; keyboardId.value = ''; pendingHover = null
  Object.assign(camera, galaxyFitCamera(layout.value, view.width, view.height))
  requestRender()
}
function bringIntoView(star) {
  if (!star) return
  const x = star.x * camera.k + camera.x, y = star.y * camera.k + camera.y
  if (x < 30 || x > view.width - 30 || y < 30 || y > view.height - 42) {
    camera.x = view.width / 2 - star.x * camera.k; camera.y = view.height / 2 - star.y * camera.k
    fitted = false
  }
}
function selectStar(star, pan = false) {
  hoverId.value = ''; keyboardId.value = star?.id || ''; pendingHover = null
  if (pan) bringIntoView(star)
  requestRender()
}
function focusCommunity(communityId) {
  const star = byCommunityId.value.get(String(communityId)) || layout.value.nodes[0]
  selectStar(star, true)
  canvasRef.value?.focus({ preventScroll: true })
}
function expand(star = focusedStar.value) { if (star) emit('expand', star.communityId) }
function zoom(factor, point = { x: view.width / 2, y: view.height / 2 }) {
  const fit = galaxyFitCamera(layout.value, view.width, view.height)
  Object.assign(camera, galaxyZoomAt(camera, point, factor, fit.k * .6, 8))
  fitted = false; pendingHover = null; hoverId.value = ''; gesture(); requestRender()
}
function localPoint(event) {
  const rect = canvasRef.value?.getBoundingClientRect() || { left: 0, top: 0 }
  return { x: event.clientX - rect.left, y: event.clientY - rect.top }
}
function onWheel(event) {
  event.preventDefault()
  zoom(Math.exp(-Math.max(-200, Math.min(200, event.deltaY)) * .002), localPoint(event))
}
function onPointerDown(event) {
  if (event.button != null && event.button !== 0 || drag) return
  event.preventDefault?.()
  const point = localPoint(event)
  drag = { id: event.pointerId, start: point, x: camera.x, y: camera.y, moved: false, touch: event.pointerType === 'touch' }
  canvasRef.value?.setPointerCapture?.(event.pointerId)
  canvasRef.value?.focus({ preventScroll: true })
}
function onPointerMove(event) {
  const point = localPoint(event)
  if (drag) {
    if (event.pointerId !== drag.id) return
    const dx = point.x - drag.start.x, dy = point.y - drag.start.y
    if (Math.hypot(dx, dy) > 5) drag.moved = true
    if (drag.moved) {
      fitted = false; hoverId.value = ''; keyboardId.value = ''; pendingHover = null
      camera.x = drag.x + dx; camera.y = drag.y + dy; gesture(); requestRender()
    }
  } else { pendingHover = point; requestRender() }
}
function finishPointer(event, cancelled = false) {
  if (!drag || event.pointerId !== drag.id) return
  const current = drag; drag = null
  canvasRef.value?.releasePointerCapture?.(event.pointerId)
  if (!cancelled && !current.moved) {
    const star = hit(localPoint(event), current.touch)
    selectStar(star)
    if (star) expand(star)
  }
  requestRender()
}
function leavePointer() { if (!drag) { hoverId.value = ''; pendingHover = null; requestRender() } }
function onCanvasFocus() { if (!focusedId.value) selectStar(layout.value.nodes[0]) }
function onKeyDown(event) {
  const nodes = layout.value.nodes
  if (['Enter', ' '].includes(event.key)) { event.preventDefault(); if (!event.repeat) expand(); return }
  if (event.key === 'Escape') { event.preventDefault(); selectStar(null); return }
  if (event.key === '+' || event.key === '=') { event.preventDefault(); zoom(1.3); return }
  if (event.key === '-') { event.preventDefault(); zoom(1 / 1.3); return }
  if (event.key === '0') { event.preventDefault(); resetView(); return }
  if (!['Home', 'End', 'ArrowLeft', 'ArrowRight', 'ArrowUp', 'ArrowDown'].includes(event.key) || !nodes.length) return
  event.preventDefault()
  let position = nodes.findIndex(node => node.id === focusedId.value)
  if (event.key === 'Home') position = 0
  else if (event.key === 'End') position = nodes.length - 1
  else position = Math.max(0, Math.min(nodes.length - 1, position + (['ArrowLeft', 'ArrowUp'].includes(event.key) ? -1 : 1)))
  selectStar(nodes[position], true)
}
function syncPageVisibility() { pageVisible.value = typeof document === 'undefined' || !document.hidden }
function syncMotionPreference() { reducedMotion.value = motionPreference?.matches || false }
watch(layout, () => {
  if (!index.value.byId.has(String(keyboardId.value))) keyboardId.value = ''
  hoverId.value = ''; fitted = true
  Object.assign(camera, galaxyFitCamera(layout.value, view.width, view.height)); requestRender()
}, { flush: 'post' })
watch(() => [camera.x, camera.y, camera.k, view.width, view.height, view.dpr], requestRender)
watch(() => [props.active, inViewport.value, pageVisible.value, reducedMotion.value], () => {
  if (canDraw()) requestRender()
  else { cancelFrame(); pendingHover = null; releaseDrag(); clearTimeout(idleTimer); interacting.value = false }
})
onMounted(() => {
  renderer = createKnowledgeGalaxyRenderer({ canvas: canvasRef.value, flowCanvas: flowRef.value,
    twinkleCanvases: [twinkleARef.value, twinkleBRef.value] })
  const resize = () => {
    const element = sceneRef.value
    if (!element?.clientWidth || !element.clientHeight) return
    const oldWidth = view.width, oldHeight = view.height
    view.width = element.clientWidth; view.height = Math.max(320, element.clientHeight)
    view.dpr = typeof devicePixelRatio === 'number' ? Math.min(2, devicePixelRatio) : 1
    if (fitted) Object.assign(camera, galaxyFitCamera(layout.value, view.width, view.height))
    else { camera.x += (view.width - oldWidth) / 2; camera.y += (view.height - oldHeight) / 2 }
    requestRender()
  }
  resize(); resizeObserver = new ResizeObserver(resize); resizeObserver.observe(sceneRef.value)
  syncPageVisibility()
  if (typeof document !== 'undefined') document.addEventListener('visibilitychange', syncPageVisibility)
  if (typeof window !== 'undefined' && window.matchMedia) {
    motionPreference = window.matchMedia('(prefers-reduced-motion: reduce)'); syncMotionPreference()
    motionPreference.addEventListener?.('change', syncMotionPreference)
  }
  if (typeof IntersectionObserver !== 'undefined') {
    visibilityObserver = new IntersectionObserver(entries => {
      inViewport.value = entries.some(entry => entry.target === sceneRef.value && entry.isIntersecting)
    }); visibilityObserver.observe(sceneRef.value)
  } else inViewport.value = true
  nextTick(requestRender)
})
onBeforeUnmount(() => {
  destroyed = true; cancelFrame(); releaseDrag(); clearTimeout(idleTimer); renderer?.destroy()
  resizeObserver?.disconnect(); visibilityObserver?.disconnect()
  if (typeof document !== 'undefined') document.removeEventListener('visibilitychange', syncPageVisibility)
  motionPreference?.removeEventListener?.('change', syncMotionPreference)
})
defineExpose({ resetView, focusCommunity })
</script>

<template>
  <section class="community-galaxy" :data-motion="motionActive ? 'running' : 'paused'" :data-community-count="overview.totalCommunities" aria-label="知识社区星系总览">
    <header class="galaxy-heading"><p>所有社区位于同一星系，点击星团进入社区</p><span>{{ formatCount(overview.totalCommunities) }} 个社区</span></header>
    <div ref="sceneRef" class="galaxy-scene" :data-visible-count="metrics.visibleCount" :data-painted-count="metrics.paintedCount" :data-label-count="metrics.labelCount">
      <canvas ref="canvasRef" class="galaxy-canvas" :class="{ 'galaxy-canvas-hit': hoverId }" tabindex="0" role="application" aria-label="社区星系，可拖拽移动、滚轮缩放，方向键选择社区，Enter 展开" :aria-describedby="instructionsId"
        @wheel.prevent="onWheel" @pointerdown="onPointerDown" @pointermove="onPointerMove" @pointerup="finishPointer($event)" @pointercancel="finishPointer($event, true)" @pointerleave="leavePointer" @keydown="onKeyDown" @focus="onCanvasFocus" />
      <div class="galaxy-motion-surface galaxy-flow-layer" aria-hidden="true"><canvas ref="flowRef" /></div>
      <div class="galaxy-motion-surface galaxy-twinkle-layer galaxy-twinkle-a" aria-hidden="true"><canvas ref="twinkleARef" /></div>
      <div class="galaxy-motion-surface galaxy-twinkle-layer galaxy-twinkle-b" aria-hidden="true"><canvas ref="twinkleBRef" /></div>
      <div class="galaxy-zoom" aria-label="星系视图控制"><button type="button" aria-label="缩小星系" @click="zoom(1 / 1.3)">−</button><span>{{ zoomPercent }}%</span><button type="button" aria-label="放大星系" @click="zoom(1.3)">+</button><button type="button" @click="resetView">查看全图</button></div>
      <div v-if="!overview.totalCommunities" class="galaxy-empty">当前筛选下没有知识社区</div>
    </div>
    <footer class="galaxy-footer">
      <div v-if="focusedStar" class="galaxy-focus-summary" role="status" aria-live="polite"><strong>{{ focusedStar.title }}</strong><span>{{ formatCount(focusedStar.count) }} 个概念 · {{ formatCount(focusedStar.internalEdgeCount) }} 条内部关系 · {{ formatCount(focusedStar.crossEdgeCount) }} 条跨社区关系</span><button type="button" @click="expand()">展开所选社区 ↗</button></div>
      <p v-else class="galaxy-hint">拖拽移动 · 滚轮或按钮缩放 · 放大查看社区名称</p>
      <span class="galaxy-render-summary">视野内 {{ formatCount(metrics.visibleCount) }} / {{ formatCount(overview.totalCommunities) }} 个社区<span v-if="metrics.relationsLimited"> · 显示主要联系</span></span>
    </footer>
    <span :id="instructionsId" class="galaxy-sr-only">方向键依次选择全部社区，Home 和 End 到达首尾，Enter 或空格展开；加减号缩放，0 查看全图，Escape 清除选择。</span>
  </section>
</template>

<style scoped>
.community-galaxy { --galaxy-muted: #67758b; display: flex; flex-direction: column; width: 100%; height: 100%; min-width: 0; min-height: 640px; overflow: hidden; background: #fff; color: #49566a; color-scheme: light; }
.galaxy-heading { display: flex; align-items: center; justify-content: space-between; flex-wrap: wrap; gap: 8px 20px; padding: 18px 24px 0; font-size: 11px; line-height: 1.6; color: var(--galaxy-muted); }
.galaxy-heading p { margin: 0; }
.galaxy-scene { position: relative; flex: 1; min-width: 0; min-height: 500px; isolation: isolate; overflow: hidden; background: #fff; }
.galaxy-canvas { position: absolute; inset: 0; width: 100%; height: 100%; display: block; touch-action: none; cursor: grab; }
.galaxy-canvas-hit { cursor: pointer; }
.galaxy-canvas:active { cursor: grabbing; }
.galaxy-canvas:focus-visible { outline: 2px solid #6f84b9; outline-offset: -3px; }
.galaxy-motion-surface { position: absolute; inset: 0; pointer-events: none; overflow: hidden; contain: layout paint; animation-play-state: paused; }
.galaxy-motion-surface canvas { display: block; width: 100%; height: 100%; }
.galaxy-flow-layer { transform-origin: 50% 50%; animation: galaxy-arm-flow 42s ease-in-out infinite alternate both; animation-play-state: paused; }
.galaxy-twinkle-layer { animation: galaxy-twinkle 5.6s ease-in-out -1.8s infinite alternate both; animation-play-state: paused; }
.galaxy-twinkle-b { animation-duration: 7.8s; animation-delay: -3.4s; }
.community-galaxy[data-motion="running"] .galaxy-motion-surface { animation-play-state: running; will-change: opacity; }
.community-galaxy[data-motion="running"] .galaxy-flow-layer { will-change: transform, opacity; }
@keyframes galaxy-arm-flow { from { transform: rotate(-.7deg) scale(.995); opacity: .28; } to { transform: rotate(.7deg) scale(1.012); opacity: .62; } }
@keyframes galaxy-twinkle { 0%,100% { opacity: .24; } 45% { opacity: .9; } 72% { opacity: .38; } }
.galaxy-zoom { position: absolute; right: 16px; bottom: 14px; z-index: 1; display: flex; align-items: center; padding: 4px; gap: 3px; border: 1px solid #e6e9ef; border-radius: 8px; background: #fffffff0; }
.galaxy-zoom span { min-width: 42px; text-align: center; font-size: 11px; font-variant-numeric: tabular-nums; color: #67758b; }
.galaxy-zoom button, .galaxy-focus-summary button { min-height: 32px; border: 0; border-radius: 5px; padding: 4px 9px; background: transparent; color: #536b89; font: inherit; font-size: 12px; cursor: pointer; }
.galaxy-zoom button:hover, .galaxy-focus-summary button:hover { background: #f1f5fa; }
.galaxy-zoom button:focus-visible, .galaxy-focus-summary button:focus-visible { outline: 2px solid #6f84b9; outline-offset: 1px; }
.galaxy-footer { display: flex; align-items: center; justify-content: space-between; flex-wrap: wrap; gap: 8px 20px; height: 96px; min-height: 96px; flex-shrink: 0; overflow: auto; padding: 12px 24px; border-top: 1px solid #e6e9ef; font-size: 11px; line-height: 1.6; }
.galaxy-focus-summary { flex: 1; display: flex; align-items: center; flex-wrap: wrap; gap: 4px 12px; min-width: 160px; color: #67758b; }
.galaxy-focus-summary strong { flex-basis: 100%; color: #384a67; font-weight: 500; overflow-wrap: anywhere; }
.galaxy-hint { margin: 0; color: #67758b; }
.galaxy-render-summary { color: #67758b; white-space: nowrap; font-variant-numeric: tabular-nums; }
.galaxy-empty { position: absolute; inset: 0; display: grid; place-items: center; pointer-events: none; color: #67758b; font-size: 13px; }
.galaxy-sr-only { position: absolute; width: 1px; height: 1px; padding: 0; margin: -1px; overflow: hidden; clip-path: inset(50%); white-space: nowrap; }
@media (max-width: 640px) { .community-galaxy { min-height: 560px; } .galaxy-heading { padding: 16px 16px 0; gap: 4px 16px; } .galaxy-scene { min-height: 420px; } .galaxy-footer { height: 144px; min-height: 144px; padding: 12px 16px; gap: 6px; } .galaxy-render-summary { width: 100%; } .galaxy-zoom { right: 10px; bottom: 10px; } .galaxy-zoom button { min-width: 36px; min-height: 36px; } }
@media (prefers-reduced-motion: reduce) { .community-galaxy[data-motion] .galaxy-motion-surface { animation: none; will-change: auto; } }
</style>
