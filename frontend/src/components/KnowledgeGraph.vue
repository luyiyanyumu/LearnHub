<script setup>
import { computed, onBeforeUnmount, onMounted, reactive, ref, shallowRef, useId, watch } from 'vue'
import { GRAPH_SIMULATION_NODE_LIMIT, createGraphLayout, fitGraphBounds, placeGraphLabels, reconcileGraphLayout, stepGraphLayout } from '../utils/knowledgeGraphLayout.js'
import { createGraphHitIndex, createGraphRenderer, graphNodeScreenRadius, parseGraphColor } from '../utils/knowledgeGraphRenderer.js'

const props = defineProps({
  nodes: { type: Array, default: () => [] },
  edges: { type: Array, default: () => [] },
  activeId: { type: String, default: '' },
  layoutRootId: { type: String, default: '' },
  communities: { type: Object, default: () => ({}) },
})
const emit = defineEmits(['select', 'open'])
const wrapRef = ref(null)
const canvasRef = ref(null)
const largeGraph = computed(() => props.nodes.length > GRAPH_SIMULATION_NODE_LIMIT)
const view = reactive({ w: 900, h: 540, x: 0, y: 0, k: 1 })
const hoverId = ref(''), dragging = ref(''), frame = ref(0), layoutMode = ref('')
const keyboardId = ref(''), rendererType = ref(''), overviewLabels = shallowRef([]), labelsPending = ref(false)
const arrowId = `kg-arrow-${useId().replace(/[^a-zA-Z0-9_-]/g, '')}`
const sim = new Map()
let layout = createGraphLayout(), alpha = 1, raf = 0, ro = null, fitTimer = 0
let userAdjusted = false, needsFit = false, panFrom = null, dragFrom = null, moved = 0
let renderer = null, drawRaf = 0, labelTimer = 0, pointerRaf = 0, pointerEvent = null, themeObserver = null
let graphDirty = true, focusDirty = true, hitDirty = true, paletteDirty = true, palette = null, typeColors = null
let hitIndex = createGraphHitIndex(), mounted = false, nodeMoved = false

const nodeTypes = { category: '分类', tag: '标签', note: '笔记', ref: '速查卡', file: '资料', concept: '概念' }
const nodeById = computed(() => new Map(props.nodes.map((node) => [node.id, node])))
const degreeMap = computed(() => {
  const degrees = new Map()
  for (const e of props.edges) {
    degrees.set(e.source, (degrees.get(e.source) || 0) + 1)
    degrees.set(e.target, (degrees.get(e.target) || 0) + 1)
  }
  return degrees
})
const physicsNodes = computed(() => props.nodes.map((n) => {
  const degree = degreeMap.value.get(n.id) || 0
  const base = { category: 13, tag: 8, note: 7, ref: 6, file: 8, concept: 8 }[n.type] || 7
  return { ...n, degree, r: base + Math.min(5, Math.sqrt(degree) * 1.4) }
}))
const focusId = computed(() => {
  const id = hoverId.value || keyboardId.value || props.activeId
  return nodeById.value.has(id) ? id : ''
})
const incidentEdges = computed(() => {
  const index = new Map()
  for (const edge of props.edges) {
    for (const id of new Set([edge.source, edge.target])) {
      if (!index.has(id)) index.set(id, [])
      index.get(id).push(edge)
    }
  }
  return index
})
const neighbors = computed(() => {
  if (!focusId.value) return null
  const ids = new Set([focusId.value])
  for (const e of incidentEdges.value.get(focusId.value) || []) {
    if (e.source === focusId.value) ids.add(e.target)
    if (e.target === focusId.value) ids.add(e.source)
  }
  return ids
})
// World geometry depends on positions, never on the camera. The large renderer
// retains its GPU buffers throughout a pan/zoom gesture.
const positionNodes = computed(() => {
  frame.value
  return physicsNodes.value.map((n) => ({ ...n, x: sim.get(n.id)?.x || 0, y: sim.get(n.id)?.y || 0 }))
})
const graphBounds = computed(() => positionNodes.value.reduce((bounds, n) => ({
  left: Math.min(bounds.left, n.x), right: Math.max(bounds.right, n.x),
  top: Math.min(bounds.top, n.y), bottom: Math.max(bounds.bottom, n.y),
}), { left: Infinity, right: -Infinity, top: Infinity, bottom: -Infinity }))
const renderNodes = computed(() => largeGraph.value ? [] : positionNodes.value.map(n => ({ ...n, r: graphNodeScreenRadius(n, view.k) / view.k })))
const renderEdges = computed(() => {
  if (largeGraph.value) return []
  const byId = new Map(renderNodes.value.map((n) => [n.id, n]))
  return props.edges.map((e, index) => {
    const a = byId.get(e.source), b = byId.get(e.target)
    if (!a || !b || a.id === b.id) return null
    const dx = b.x - a.x, dy = b.y - a.y, distance = Math.max(1, Math.hypot(dx, dy))
    const directed = e.kind === 'semantic' || e.origin === 'llm' || e.origin === 'derived'
    const ux = dx / distance, uy = dy / distance
    return { ...e, key: `${e.source}>${e.target}#${index}`,
      x1: a.x + ux * (a.r + 2), y1: a.y + uy * (a.r + 2),
      x2: b.x - ux * (b.r + (directed ? 6 : 2)), y2: b.y - uy * (b.r + (directed ? 6 : 2)),
      semantic: e.kind === 'semantic', concept: e.origin === 'llm' || e.origin === 'derived',
      directed, derived: e.origin === 'derived',
      focused: !!focusId.value && (e.source === focusId.value || e.target === focusId.value) }
  }).filter(Boolean)
})
const renderLabels = computed(() => largeGraph.value ? labelsPending.value ? [] : overviewLabels.value : placeGraphLabels(renderNodes.value, view, {
  focusId: focusId.value, neighborIds: neighbors.value || new Set(),
  maxLabels: view.k < 0.75 ? 18 : view.k < 1.5 ? 28 : 48,
}))
const focusNodeLabel = computed(() => nodeById.value.get(focusId.value)?.label || '')
const relationCaption = computed(() => {
  if (!focusId.value) return ''
  const edges = incidentEdges.value.get(focusId.value) || []
  const labels = [...new Set(edges.map((e) => e.label || e.relation).filter(Boolean))]
  return `${edges.length} 条关系${labels.length ? ` · ${labels.slice(0, 3).join(' / ')}${labels.length > 3 ? ' …' : ''}` : ''}`
})
const keyboardCaption = computed(() => {
  const n = nodeById.value.get(keyboardId.value)
  return n ? `${n.id === props.activeId ? '已选中，' : ''}${n.label}，${nodeTypes[n.type] || n.type}，${degreeMap.value.get(n.id) || 0} 条关联。Enter 选择，Shift+Enter 打开，Escape 清除。` : ''
})

function readPalette() {
  if (!paletteDirty && palette) return palette
  const style = getComputedStyle(wrapRef.value)
  const token = name => parseGraphColor(style.getPropertyValue(name).trim())
  const brand = token('--app-brand'), text = token('--app-text-1'), muted = token('--app-text-2')
  const mix = (a, b, amount) => a.map((v, i) => v * amount + b[i] * (1 - amount))
  palette = { brand, text, border: token('--app-border'), card: token('--app-card'), semantic: mix(brand, token('--app-text-3'), 0.55) }
  typeColors = { category: brand, tag: token('--app-brand-soft'), note: mix(brand, text, 0.62),
    ref: mix(brand, muted, 0.34), file: mix(token('--app-brand-deep'), muted, 0.72), concept: mix(brand, text, 0.78) }
  paletteDirty = false
  return palette
}
function drawCanvas() {
  drawRaf = 0
  if (!mounted || !renderer || !largeGraph.value) return
  readPalette()
  if (graphDirty) {
    const colors = new Map()
    renderer.setGraph(positionNodes.value.map(n => {
      const community = props.communities[n.id]
      let color = typeColors[n.type] || palette.brand
      if (n.type === 'concept' && community != null) {
        if (!colors.has(community)) colors.set(community, parseGraphColor(nodeFill(n)))
        color = colors.get(community)
      }
      return { ...n, color }
    }), props.edges)
    graphDirty = false; focusDirty = true
  }
  if (hitDirty) { hitIndex = createGraphHitIndex(positionNodes.value); hitDirty = false }
  if (focusDirty) { renderer.setFocus(focusId.value, neighbors.value); focusDirty = false }
  renderer.render(view, palette)
}
function scheduleCanvasDraw() {
  if (mounted && largeGraph.value && !drawRaf) drawRaf = requestAnimationFrame(drawCanvas)
}
function scheduleLabels() {
  if (!mounted || !largeGraph.value) return
  labelsPending.value = true
  clearTimeout(labelTimer)
  // Keep gestures on the GPU path. Collision placement runs once after the
  // gesture settles, rather than sorting/indexing every node on each wheel tick.
  labelTimer = setTimeout(() => {
    const nodes = positionNodes.value.map(n => ({ ...n, r: graphNodeScreenRadius(n, view.k) / view.k }))
    overviewLabels.value = placeGraphLabels(nodes, view, { focusId: focusId.value, neighborIds: neighbors.value || new Set(), maxLabels: view.k < 0.75 ? 18 : view.k < 1.5 ? 28 : 48 })
    labelsPending.value = false
  }, 140)
}
watch(positionNodes, () => { graphDirty = true; hitDirty = true; scheduleCanvasDraw(); scheduleLabels() })
watch(focusId, () => { focusDirty = true; scheduleCanvasDraw(); scheduleLabels() })
watch(() => [view.x, view.y, view.k, view.w, view.h], () => { scheduleCanvasDraw(); scheduleLabels() })
watch(canvasRef, canvas => {
  renderer?.destroy(); renderer = null
  if (!canvas) return
  renderer = createGraphRenderer(canvas, { onTypeChange: type => { rendererType.value = type; scheduleCanvasDraw() } })
  rendererType.value = renderer.type
  graphDirty = true; focusDirty = true; hitDirty = true
  scheduleCanvasDraw(); scheduleLabels()
}, { flush: 'post' })

function nodeFill(n) {
  const community = props.communities[n.id]
  if (n.type === 'concept' && community != null) return `hsl(${Math.round(Number(community) * 137.508 % 360)} 46% 48%)`
  const fills = { category: 'var(--app-brand)', tag: 'var(--app-brand-soft)',
    note: 'color-mix(in srgb, var(--app-brand) 62%, var(--app-text-1))',
    ref: 'color-mix(in srgb, var(--app-brand) 34%, var(--app-text-2))',
    file: 'color-mix(in srgb, var(--app-brand-deep) 72%, var(--app-text-2))',
    concept: 'color-mix(in srgb, var(--app-brand) 78%, var(--app-text-1))' }
  return fills[n.type] || 'var(--app-brand)'
}
function edgeTitle(e) {
  const source = nodeById.value.get(e.source)?.label || e.source
  const target = nodeById.value.get(e.target)?.label || e.target
  return `${source} → ${e.label || e.relation || '关联'} → ${target}${e.derived ? '（规则推导）' : ''}${e.reason ? `：${e.reason}` : ''}`
}
function rebuildState() {
  const next = reconcileGraphLayout(physicsNodes.value, props.edges, props.communities, sim, layout,
    { aspectRatio: view.w / view.h, rootId: props.layoutRootId })
  if (next === layout) return false
  layout = next
  layoutMode.value = layout.mode || ''
  if (raf) cancelAnimationFrame(raf)
  raf = 0
  clearTimeout(fitTimer)
  needsFit = false
  userAdjusted = false
  hoverId.value = ''; keyboardId.value = ''
  onWindowCancel()
  frame.value++
  return true
}
function loop() {
  stepGraphLayout(physicsNodes.value, props.edges, sim, layout, { alpha, pinnedId: dragging.value })
  frame.value++
  alpha *= 0.97
  if (alpha > 0.02 || dragging.value) raf = requestAnimationFrame(loop)
  else {
    raf = 0
    if (needsFit && !userAdjusted) { needsFit = false; fitView() }
  }
}
function reheat(value = 0.7) {
  if (layout.fixed) { frame.value++; return }
  alpha = value
  if (!raf) raf = requestAnimationFrame(loop)
}
function fitView() {
  // Camera fitting must not depend on the previous zoom's minimum screen-size circles.
  Object.assign(view, fitGraphBounds(positionNodes.value, view.w, view.h))
}
function scheduleFit() {
  clearTimeout(fitTimer)
  if (layout.fixed) { needsFit = false; if (!userAdjusted) fitView(); return }
  needsFit = true
  fitTimer = setTimeout(() => { if (!userAdjusted) fitView() }, 400)
}
function resetView() { userAdjusted = false; fitView() }
function focusNode(id) {
  const state = sim.get(id)
  if (!state) return
  // Search already narrows the props to the selected neighborhood. Fit that entire
  // neighborhood so its neighbors remain reachable instead of forcing 125% zoom.
  resetView()
  scheduleFit()
}
watch(() => [props.nodes, props.edges, props.communities, props.layoutRootId], () => {
  if (rebuildState()) { reheat(); scheduleFit() }
})

function svgPoint(evt) {
  const rect = wrapRef.value.getBoundingClientRect()
  return { x: (evt.clientX - rect.left - view.x) / view.k, y: (evt.clientY - rect.top - view.y) / view.k }
}
function listenPointer() {
  window.addEventListener('pointermove', onWindowMove)
  window.addEventListener('pointerup', onWindowUp)
  window.addEventListener('pointercancel', onWindowCancel)
}
function removePointer() {
  window.removeEventListener('pointermove', onWindowMove)
  window.removeEventListener('pointerup', onWindowUp)
  window.removeEventListener('pointercancel', onWindowCancel)
}
function onNodeDown(evt, node) {
  if (evt.button !== 0) return
  evt.stopPropagation()
  dragging.value = node.id; dragFrom = svgPoint(evt); moved = 0; nodeMoved = false
  listenPointer()
}
function onBackgroundDown(evt) {
  if (evt.button !== 0 || evt.target.closest('.kg-node, .kg-controls, .kg-focus-card')) return
  hoverId.value = ''; keyboardId.value = ''
  panFrom = { sx: evt.clientX, sy: evt.clientY }; moved = 0
  listenPointer()
}
function applyPointerMove(evt) {
  if (panFrom) {
    const dx = evt.clientX - panFrom.sx, dy = evt.clientY - panFrom.sy
    moved += Math.hypot(dx, dy)
    view.x += dx; view.y += dy
    panFrom = { sx: evt.clientX, sy: evt.clientY }
    if (moved > 4) userAdjusted = true
    clampView()
    return
  }
  if (!dragging.value) return
  const position = svgPoint(evt), state = sim.get(dragging.value)
  if (!state) return
  moved += Math.hypot(position.x - dragFrom.x, position.y - dragFrom.y) * view.k
  if (moved > 4) userAdjusted = true
  state.x = position.x; state.y = position.y
  dragFrom = position
  nodeMoved = true
  if (largeGraph.value && renderer?.updateNodePosition) {
    renderer.updateNodePosition(dragging.value, state.x, state.y)
    clearTimeout(labelTimer); labelsPending.value = true
    scheduleCanvasDraw()
  } else reheat(0.4)
}
function onWindowMove(evt) {
  pointerEvent = evt
  if (!pointerRaf) pointerRaf = requestAnimationFrame(() => { pointerRaf = 0; const latest = pointerEvent; pointerEvent = null; if (latest) applyPointerMove(latest) })
}
function flushPointerMove() {
  if (pointerRaf) cancelAnimationFrame(pointerRaf)
  pointerRaf = 0
  const latest = pointerEvent; pointerEvent = null
  if (latest) applyPointerMove(latest)
}
function onWindowUp() {
  flushPointerMove()
  const id = dragging.value, wasPan = !!panFrom
  removePointer(); dragging.value = ''; panFrom = null
  if (id && nodeMoved && largeGraph.value) frame.value++
  nodeMoved = false
  if (moved <= 4 && (id || wasPan)) emit('select', id ? nodeById.value.get(id) || null : null)
}
function onWindowCancel() {
  if (pointerRaf) cancelAnimationFrame(pointerRaf)
  pointerRaf = 0; pointerEvent = null
  if (dragging.value && nodeMoved && largeGraph.value) frame.value++
  nodeMoved = false
  removePointer(); dragging.value = ''; panFrom = null
}
function clampView() {
  if (!positionNodes.value.length) return
  const bounds = graphBounds.value
  const left = view.x + bounds.left * view.k
  const right = view.x + bounds.right * view.k
  const top = view.y + bounds.top * view.k
  const bottom = view.y + bounds.bottom * view.k
  if (right < view.w * 0.2) view.x += view.w * 0.2 - right
  if (left > view.w * 0.8) view.x -= left - view.w * 0.8
  if (bottom < view.h * 0.2) view.y += view.h * 0.2 - bottom
  if (top > view.h * 0.8) view.y -= top - view.h * 0.8
}
function applyZoom(factor, x = view.w / 2, y = view.h / 2) {
  userAdjusted = true
  const scale = Math.max(0.001, Math.min(4, view.k * factor))
  view.x = x - (x - view.x) / view.k * scale
  view.y = y - (y - view.y) / view.k * scale
  view.k = scale
}
function onWheel(evt) {
  evt.preventDefault()
  const rect = wrapRef.value.getBoundingClientRect()
  applyZoom(evt.deltaY < 0 ? 1.12 : 0.89, evt.clientX - rect.left, evt.clientY - rect.top)
}
function zoomBy(factor) { applyZoom(factor) }
function onNodeKey(evt, node) {
  if (evt.key === 'Enter' || evt.key === ' ') { evt.preventDefault(); emit('select', node); focusNode(node.id) }
  if (evt.key === 'Escape') { hoverId.value = ''; emit('select', null) }
}
function onNodeFocus(evt, node) {
  hoverId.value = node.id
  if (evt.target.matches(':focus-visible')) focusNode(node.id)
}
function canvasHit(evt) {
  if (hitDirty) { hitIndex = createGraphHitIndex(positionNodes.value); hitDirty = false }
  const rect = wrapRef.value.getBoundingClientRect()
  return hitIndex.hitTest(evt.clientX - rect.left, evt.clientY - rect.top, view)
}
function onCanvasDown(evt) {
  if (evt.button !== 0) return
  keyboardId.value = ''
  // Mouse focus must not change the camera or choose an unrelated first node.
  evt.preventDefault()
  canvasRef.value?.focus({ preventScroll: true })
  const node = canvasHit(evt)
  if (node) { hoverId.value = node.id; onNodeDown(evt, node) }
  else onBackgroundDown(evt)
}
function onCanvasMove(evt) {
  if (panFrom || dragging.value) return
  keyboardId.value = ''
  hoverId.value = canvasHit(evt)?.id || ''
}
function onCanvasOpen(evt) {
  const node = canvasHit(evt)
  if (node) emit('open', nodeById.value.get(node.id) || node)
}
function ensureNodeVisible(id) {
  const p = sim.get(id)
  if (!p) return
  const x = view.x + p.x * view.k, y = view.y + p.y * view.k
  if (x < 32 || x > view.w - 32 || y < 64 || y > view.h - 64) {
    view.x = view.w / 2 - p.x * view.k; view.y = view.h / 2 - p.y * view.k
    userAdjusted = true
  }
}
function onCanvasFocus(evt) {
  if (!evt.target.matches(':focus-visible')) return
  keyboardId.value = focusId.value || props.nodes[0]?.id || ''
  ensureNodeVisible(keyboardId.value)
}
function onCanvasKey(evt) {
  if (['ArrowRight', 'ArrowDown', 'ArrowLeft', 'ArrowUp', 'Home', 'End'].includes(evt.key)) {
    evt.preventDefault()
    const nodes = positionNodes.value
    if (!nodes.length) return
    const current = nodes.findIndex(n => n.id === (keyboardId.value || focusId.value))
    const step = evt.key === 'ArrowLeft' || evt.key === 'ArrowUp' ? -1 : 1
    const index = evt.key === 'Home' ? 0 : evt.key === 'End' ? nodes.length - 1 : current < 0 ? 0 : (current + step + nodes.length) % nodes.length
    hoverId.value = ''; keyboardId.value = nodes[index].id
    ensureNodeVisible(keyboardId.value)
  } else if (evt.key === 'Enter' || evt.key === ' ') {
    evt.preventDefault()
    const node = nodeById.value.get(keyboardId.value || focusId.value)
    if (node) emit(evt.key === 'Enter' && evt.shiftKey ? 'open' : 'select', node)
  } else if (evt.key === 'Escape') {
    evt.preventDefault(); hoverId.value = ''; keyboardId.value = ''; emit('select', null)
  } else if (evt.key === '+' || evt.key === '=') { evt.preventDefault(); zoomBy(1.2) }
  else if (evt.key === '-') { evt.preventDefault(); zoomBy(0.83) }
}
defineExpose({ resetView, zoomBy, reheat, fitView, focusNode })
onMounted(() => {
  mounted = true
  const element = wrapRef.value
  view.w = Math.max(240, element.clientWidth); view.h = Math.max(320, element.clientHeight)
  rebuildState()
  const resize = () => {
    view.w = Math.max(240, element.clientWidth); view.h = Math.max(320, element.clientHeight)
    if (!userAdjusted) {
      if (layout.fixed) rebuildState()
      fitView()
    }
  }
  resize(); reheat(); scheduleFit()
  ro = new ResizeObserver(resize); ro.observe(element)
  themeObserver = new MutationObserver(() => {
    paletteDirty = true; graphDirty = true; scheduleCanvasDraw()
  })
  themeObserver.observe(document.documentElement, { attributes: true, attributeFilter: ['class', 'style', 'data-theme'] })
  scheduleCanvasDraw(); scheduleLabels()
})
onBeforeUnmount(() => {
  mounted = false
  if (raf) cancelAnimationFrame(raf)
  if (drawRaf) cancelAnimationFrame(drawRaf)
  clearTimeout(fitTimer); clearTimeout(labelTimer); ro?.disconnect(); themeObserver?.disconnect(); onWindowCancel()
  renderer?.destroy(); renderer = null
})
</script>

<template>
  <div ref="wrapRef" class="kg-wrap" @pointerdown="onBackgroundDown" @wheel="onWheel">
    <canvas v-if="largeGraph" ref="canvasRef" class="kg-canvas" role="group" tabindex="0"
      :aria-label="`知识关联图谱，${nodes.length} 个节点，${edges.length} 条关系`" :aria-describedby="`${arrowId}-instructions ${arrowId}-keyboard`"
      :data-renderer="rendererType" :data-node-count="nodes.length" :data-edge-count="edges.length"
      @pointerdown.stop="onCanvasDown" @pointermove="onCanvasMove" @pointerleave="hoverId = ''"
      @dblclick="onCanvasOpen" @focus="onCanvasFocus" @blur="keyboardId = ''" @keydown="onCanvasKey" />
    <span v-if="largeGraph" :id="`${arrowId}-instructions`" class="kg-sr-only">方向键切换节点，Home 或 End 跳到首尾，Enter 或空格选择，Shift+Enter 打开，Escape 清除。也可在图谱搜索框查找任意节点。</span>
    <span v-if="largeGraph" :id="`${arrowId}-keyboard`" class="kg-sr-only" role="status" aria-live="polite">{{ keyboardCaption }}</span>
    <svg class="kg-svg" :class="{ 'kg-overlay': largeGraph }" :width="view.w" :height="view.h" :aria-hidden="largeGraph ? true : undefined" :aria-label="largeGraph ? undefined : '知识关联图谱，可拖拽、滚轮缩放，Tab 聚焦节点，Enter 选择'">
      <defs>
        <marker :id="arrowId" viewBox="0 0 10 10" refX="9" refY="5" markerUnits="userSpaceOnUse" :markerWidth="7 / view.k" :markerHeight="7 / view.k" orient="auto">
          <path d="M0,0 L10,5 L0,10 z" fill="var(--app-brand)" />
        </marker>
      </defs>
      <g :transform="`translate(${view.x},${view.y}) scale(${view.k})`">
        <line v-for="edge in renderEdges" :key="edge.key"
          :x1="edge.x1" :y1="edge.y1" :x2="edge.x2" :y2="edge.y2"
          class="kg-edge" :class="{ 'kg-edge-semantic': edge.semantic, 'kg-edge-concept': edge.concept,
            'kg-edge-derived': edge.derived, 'kg-edge-focus': edge.focused, 'kg-edge-dim': focusId && !edge.focused }"
          vector-effect="non-scaling-stroke"
          :marker-end="edge.directed && (edge.focused || view.k >= 0.8) ? `url(#${arrowId})` : undefined">
          <title>{{ edgeTitle(edge) }}</title>
        </line>
        <g v-for="node in renderNodes" :key="node.id" class="kg-node" role="button" tabindex="0"
          :aria-label="`${node.label}，${nodeTypes[node.type] || node.type}，${node.degree} 条关联`"
          :aria-pressed="node.id === activeId"
          :class="{ 'kg-node-dim': neighbors && !neighbors.has(node.id), 'kg-node-active': node.id === focusId }"
          @pointerdown="onNodeDown($event, node)" @dblclick="emit('open', node)"
          @mouseenter="hoverId = node.id" @mouseleave="hoverId = ''"
          @focus="onNodeFocus($event, node)" @blur="hoverId = ''" @keydown="onNodeKey($event, node)">
          <circle :cx="node.x" :cy="node.y" :r="node.r" :fill="nodeFill(node)"
            :stroke="node.type === 'tag' ? 'var(--app-brand)' : 'var(--app-card)'" stroke-width="1.8" vector-effect="non-scaling-stroke" />
          <title>{{ node.label }}（{{ nodeTypes[node.type] || node.type }}，{{ node.degree }} 条关联）</title>
        </g>
        <g v-for="label in renderLabels" :key="`label:${label.id}`" class="kg-label" :class="{ 'kg-label-strong': label.strong }" aria-hidden="true">
          <line v-if="label.leader" v-bind="label.leader" class="kg-label-leader" vector-effect="non-scaling-stroke" />
          <rect :x="label.x" :y="label.y" :width="label.width" :height="label.height" :rx="4 / view.k" />
          <text :x="label.x + 7 / view.k" :y="label.y + 14 / view.k" :font-size="label.fontSize">{{ label.text }}</text>
        </g>
      </g>
    </svg>
    <div v-if="(hoverId || keyboardId) && focusNodeLabel && (focusId !== activeId || keyboardId)" class="kg-focus-card">
      <strong>{{ focusNodeLabel }}</strong><span>{{ relationCaption }}</span>
    </div>
    <div class="kg-controls" aria-label="图谱视图控制" @pointerdown.stop>
      <button type="button" title="缩小" aria-label="缩小图谱" @click="zoomBy(0.83)">−</button>
      <span class="kg-scale">{{ Math.round(view.k * 100) }}%</span>
      <button type="button" title="放大" aria-label="放大图谱" @click="zoomBy(1.2)">+</button>
      <span class="kg-control-divider" />
      <button type="button" class="kg-fit" title="显示完整图谱" @click="resetView">适应视图</button>
    </div>
    <div class="kg-hint">{{ layoutMode === 'overview' ? '大图总览 · ' : '' }}拖拽移动 · 滚轮缩放 · 选中查看关系</div>
    <div v-if="largeGraph && rendererType === 'unavailable'" class="kg-render-error" role="status">图形绘制暂不可用，仍可通过右侧搜索查看概念和关系。</div>
  </div>
</template>

<style scoped>
.kg-wrap { position: relative; width: 100%; height: 100%; min-height: 320px; overflow: hidden; border-radius: var(--radius); background: var(--app-card); cursor: grab; touch-action: none; }
.kg-wrap:active { cursor: grabbing; }
.kg-svg { display: block; }
.kg-canvas { display: block; position: absolute; inset: 0; width: 100%; height: 100%; outline: none; }
.kg-canvas:focus-visible { outline: 2px solid var(--app-brand); outline-offset: -3px; }
.kg-overlay { position: absolute; inset: 0; pointer-events: none; }
.kg-sr-only { position: absolute; width: 1px; height: 1px; padding: 0; margin: -1px; overflow: hidden; clip-path: inset(50%); white-space: nowrap; border: 0; }
.kg-render-error { position: absolute; left: 16px; right: 16px; top: 50%; padding: 16px; text-align: center; background: var(--app-card); color: var(--app-text-2); font-size: 13px; }
.kg-edge { stroke: var(--app-border); stroke-width: 1.2; opacity: .7; transition: opacity var(--dur-fast) ease; }
.kg-edge-concept, .kg-edge-semantic { stroke: color-mix(in srgb, var(--app-brand) 55%, var(--app-text-3)); opacity: .45; }
.kg-edge-derived, .kg-edge-semantic { stroke-dasharray: 5 4; }
.kg-edge-focus { stroke: var(--app-brand); stroke-width: 2.3; opacity: .95; }
.kg-edge-dim { opacity: .07; }
.kg-node { cursor: pointer; outline: none; }
.kg-node circle { transition: opacity var(--dur-fast) ease; }
.kg-node-dim { opacity: .16; }
.kg-node-active circle, .kg-node:focus-visible circle { stroke: var(--app-text-1); stroke-width: 2.5; }
.kg-node:focus-visible circle { stroke-dasharray: 3 2; }
.kg-label { pointer-events: none; user-select: none; }
.kg-label rect { fill: var(--app-card); fill-opacity: .92; }
.kg-label-leader { stroke: var(--app-text-3); stroke-width: .8; opacity: .6; }
.kg-label text { fill: var(--app-text-2); }
.kg-label-strong text { fill: var(--app-text-1); font-weight: 650; }
.kg-focus-card { position: absolute; left: 16px; top: 16px; display: flex; flex-direction: column; gap: 4px; max-width: min(420px, calc(100% - 32px)); padding: 10px 14px; border: 1px solid var(--app-border); border-radius: 10px; background: var(--app-card); box-shadow: 0 5px 18px #00000008; pointer-events: none; }
.kg-focus-card strong { overflow: hidden; text-overflow: ellipsis; white-space: nowrap; font-size: 13px; color: var(--app-text-1); }
.kg-focus-card span { font-size: 12px; color: var(--app-text-3); }
.kg-controls { position: absolute; left: 16px; bottom: 16px; display: flex; align-items: center; gap: 4px; padding: 4px; border: 1px solid var(--app-border); border-radius: 10px; background: var(--app-card); box-shadow: 0 4px 14px #00000008; cursor: default; }
.kg-controls button { width: 30px; height: 30px; border: 0; border-radius: 6px; color: var(--app-text-2); background: transparent; font-size: 19px; cursor: pointer; }
.kg-controls button:hover, .kg-controls button:focus-visible { color: var(--app-brand-deep); background: var(--app-brand-soft); outline: 2px solid var(--app-brand); outline-offset: 1px; }
.kg-controls .kg-fit { width: auto; padding: 0 10px; font-size: 12px; }
.kg-scale { min-width: 38px; text-align: center; font-size: 11px; color: var(--app-text-3); font-variant-numeric: tabular-nums; }
.kg-control-divider { height: 18px; width: 1px; background: var(--app-border); margin: 0 3px; }
.kg-hint { position: absolute; left: 250px; bottom: 29px; color: var(--app-text-3); font-size: 11px; pointer-events: none; }
@media (max-width: 640px) { .kg-hint { display: none; } .kg-controls { left: 10px; bottom: 10px; } }
</style>
