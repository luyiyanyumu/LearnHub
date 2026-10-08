<script setup>
import { computed, onBeforeUnmount, onMounted, reactive, ref, useId, watch } from 'vue'
import { createGraphLayout, fitGraphBounds, placeGraphLabels, reconcileGraphLayout, stepGraphLayout } from '../utils/knowledgeGraphLayout.js'

const props = defineProps({
  nodes: { type: Array, default: () => [] },
  edges: { type: Array, default: () => [] },
  activeId: { type: String, default: '' },
  layoutRootId: { type: String, default: '' },
  communities: { type: Object, default: () => ({}) },
})
const emit = defineEmits(['select', 'open'])
const wrapRef = ref(null)
const view = reactive({ w: 900, h: 540, x: 0, y: 0, k: 1 })
const hoverId = ref(''), dragging = ref(''), frame = ref(0)
const arrowId = `kg-arrow-${useId().replace(/[^a-zA-Z0-9_-]/g, '')}`
const sim = new Map()
let layout = createGraphLayout(), alpha = 1, raf = 0, ro = null, fitTimer = 0
let userAdjusted = false, needsFit = false, panFrom = null, dragFrom = null, moved = 0

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
  const id = hoverId.value || props.activeId
  return nodeById.value.has(id) ? id : ''
})
const neighbors = computed(() => {
  if (!focusId.value) return null
  const ids = new Set([focusId.value])
  for (const e of props.edges) {
    if (e.source === focusId.value) ids.add(e.target)
    if (e.target === focusId.value) ids.add(e.source)
  }
  return ids
})
const renderNodes = computed(() => {
  frame.value
  return physicsNodes.value.map((n) => ({ ...n, x: sim.get(n.id)?.x || 0, y: sim.get(n.id)?.y || 0,
    r: Math.max(n.r, (3.3 + Math.min(2.5, Math.sqrt(n.degree) * 0.5)) / view.k) }))
})
const renderEdges = computed(() => {
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
const renderLabels = computed(() => placeGraphLabels(renderNodes.value, view, {
  focusId: focusId.value, neighborIds: neighbors.value || new Set(),
  maxLabels: view.k < 0.75 ? 18 : view.k < 1.5 ? 28 : 48,
}))
const focusNodeLabel = computed(() => nodeById.value.get(focusId.value)?.label || '')
const relationCaption = computed(() => {
  if (!focusId.value) return ''
  const edges = renderEdges.value.filter((e) => e.focused)
  const labels = [...new Set(edges.map((e) => e.label || e.relation).filter(Boolean))]
  return `${edges.length} 条关系${labels.length ? ` · ${labels.slice(0, 3).join(' / ')}${labels.length > 3 ? ' …' : ''}` : ''}`
})

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
  if (raf) cancelAnimationFrame(raf)
  raf = 0
  clearTimeout(fitTimer)
  needsFit = false
  userAdjusted = false
  hoverId.value = ''
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
  const nodes = physicsNodes.value.map(n => ({ ...n, ...sim.get(n.id) }))
  Object.assign(view, fitGraphBounds(nodes, view.w, view.h))
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
  dragging.value = node.id; dragFrom = svgPoint(evt); moved = 0
  listenPointer()
}
function onBackgroundDown(evt) {
  if (evt.button !== 0 || evt.target.closest('.kg-node, .kg-controls, .kg-focus-card')) return
  panFrom = { sx: evt.clientX, sy: evt.clientY }; moved = 0
  listenPointer()
}
function onWindowMove(evt) {
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
  reheat(0.4)
}
function onWindowUp() {
  const id = dragging.value, wasPan = !!panFrom
  removePointer(); dragging.value = ''; panFrom = null
  if (moved <= 4 && (id || wasPan)) emit('select', id ? nodeById.value.get(id) || null : null)
}
function onWindowCancel() { removePointer(); dragging.value = ''; panFrom = null }
function clampView() {
  const nodes = renderNodes.value
  if (!nodes.length) return
  const left = view.x + Math.min(...nodes.map((n) => n.x)) * view.k
  const right = view.x + Math.max(...nodes.map((n) => n.x)) * view.k
  const top = view.y + Math.min(...nodes.map((n) => n.y)) * view.k
  const bottom = view.y + Math.max(...nodes.map((n) => n.y)) * view.k
  if (right < view.w * 0.2) view.x += view.w * 0.2 - right
  if (left > view.w * 0.8) view.x -= left - view.w * 0.8
  if (bottom < view.h * 0.2) view.y += view.h * 0.2 - bottom
  if (top > view.h * 0.8) view.y -= top - view.h * 0.8
}
function applyZoom(factor, x = view.w / 2, y = view.h / 2) {
  userAdjusted = true
  const scale = Math.max(0.08, Math.min(4, view.k * factor))
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
defineExpose({ resetView, zoomBy, reheat, fitView, focusNode })
onMounted(() => {
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
})
onBeforeUnmount(() => {
  if (raf) cancelAnimationFrame(raf)
  clearTimeout(fitTimer); ro?.disconnect(); removePointer()
})
</script>

<template>
  <div ref="wrapRef" class="kg-wrap" @pointerdown="onBackgroundDown" @wheel="onWheel">
    <svg class="kg-svg" :width="view.w" :height="view.h" aria-label="知识关联图谱，可拖拽、滚轮缩放，Tab 聚焦节点，Enter 选择">
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
    <div v-if="hoverId && focusNodeLabel && hoverId !== activeId" class="kg-focus-card" aria-live="polite">
      <strong>{{ focusNodeLabel }}</strong><span>{{ relationCaption }}</span>
    </div>
    <div class="kg-controls" aria-label="图谱视图控制" @pointerdown.stop>
      <button type="button" title="缩小" aria-label="缩小图谱" @click="zoomBy(0.83)">−</button>
      <span class="kg-scale">{{ Math.round(view.k * 100) }}%</span>
      <button type="button" title="放大" aria-label="放大图谱" @click="zoomBy(1.2)">+</button>
      <span class="kg-control-divider" />
      <button type="button" class="kg-fit" title="显示完整图谱" @click="resetView">适应视图</button>
    </div>
    <div class="kg-hint">拖拽移动 · 滚轮缩放 · 选中查看关系</div>
  </div>
</template>

<style scoped>
.kg-wrap { position: relative; width: 100%; height: 100%; min-height: 320px; overflow: hidden; border-radius: var(--radius); background: var(--app-card); cursor: grab; touch-action: none; }
.kg-wrap:active { cursor: grabbing; }
.kg-svg { display: block; }
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
