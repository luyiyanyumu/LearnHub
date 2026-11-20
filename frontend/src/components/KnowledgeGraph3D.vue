<script setup>
import { computed, onBeforeUnmount, onMounted, ref, shallowRef, toRaw, useId, watch } from 'vue'
import { layoutGraph3d } from '../utils/knowledgeGraph3dLayout.js'
import { createGraph3dRenderer } from '../utils/knowledgeGraph3dRenderer.js'

const props = defineProps({
  mode: { type: String, default: 'concept' },
  active: { type: Boolean, default: true },
  nodes: { type: Array, default: () => [] },
  edges: { type: Array, default: () => [] },
  communities: { type: [Object, Map], default: () => ({}) },
  activeId: { type: String, default: '' },
  layoutRootId: { type: String, default: '' },
})
const emit = defineEmits(['expand', 'select', 'open'])
const canvasRef = ref(null)
const sceneRef = ref(null)
const available = ref(true)
const visible = ref(true)
const autoRotate = defineModel('autoRotate', { type: Boolean, default: true })
const reducedMotion = ref(false)
const motion = ref('idle')
const metrics = shallowRef(null)
const hoverId = ref('')
const keyboardId = ref('')
const instructionsId = useId()
const overview = computed(() => props.mode === 'overview')
const nodeById = computed(() => new Map(props.nodes.map(node => [String(node.id), node])))
const indexById = computed(() => new Map(props.nodes.map((node, index) => [String(node.id), index])))
const byCommunity = computed(() => new Map(props.nodes.map(node => [String(node.communityId), node])))
const layout = computed(() => layoutGraph3d(toRaw(props.nodes), toRaw(props.edges), {
  mode: props.mode, communities: toRaw(props.communities), rootId: props.layoutRootId,
}))
const keyboardTarget = computed(() => keyboardId.value || props.activeId)
const selectedId = computed(() => overview.value ? hoverId.value || keyboardId.value : keyboardTarget.value)
const selectedNode = computed(() => nodeById.value.get(selectedId.value))
const keyboardNode = computed(() => nodeById.value.get(keyboardTarget.value))
const rotationEnabled = computed(() => autoRotate.value && !reducedMotion.value)
const unit = computed(() => overview.value ? '社区' : '概念')
const motionText = computed(() => motion.value === 'rotating' ? '旋转中' : rotationEnabled.value ? '空闲后旋转' : '已暂停')
const densityText = computed(() => {
  const m = metrics.value
  if (!m || m.totalNodes !== props.nodes.length || m.paintedNodes >= m.totalNodes) return ''
  return `当前绘制 ${m.paintedNodes.toLocaleString()} / ${m.totalNodes.toLocaleString()} 个${unit.value}`
})
let renderer, observer, motionQuery, queuedFocus = '', graphDirty = true, drawing = false
let metricTimer = 0, pendingMetrics = null

function nodeTitle(node) { return node?.label || node?.title || node?.name || node?.id || '' }
function refreshSelection() { if (drawing) renderer.setSelected(selectedId.value) }
function syncScene() {
  if (!renderer) return
  const nextActive = props.active && visible.value
  if (nextActive && graphDirty) {
    renderer.setGraph(layout.value)
    graphDirty = false
  }
  if (nextActive !== drawing) {
    drawing = nextActive
    renderer.setActive(drawing)
  }
  if (!drawing) return
  refreshSelection()
  if (queuedFocus && renderer.focusNode(queuedFocus)) queuedFocus = ''
}
function syncMotion() {
  renderer?.setReducedMotion(reducedMotion.value)
  renderer?.setAutoRotate(rotationEnabled.value)
}
function syncMotionPreference() { reducedMotion.value = Boolean(motionQuery?.matches) }

function updateMetrics(next) {
  pendingMetrics = next
  if (!metricTimer) metricTimer = setTimeout(() => {
    metricTimer = 0
    const previous = metrics.value
    if (!previous || Object.keys(pendingMetrics).some(key => pendingMetrics[key] !== previous[key])) {
      metrics.value = pendingMetrics
    }
  }, 120)
}

function resetView() {
  hoverId.value = ''
  keyboardId.value = ''
  queuedFocus = ''
  renderer?.resetView()
}
function zoomBy(factor) { renderer?.zoomBy(factor) }
function focusNode(id) {
  const key = String(id)
  if (!nodeById.value.has(key)) return
  hoverId.value = ''
  keyboardId.value = key
  queuedFocus = key
  syncScene()
}
function focusCommunity(communityId) {
  const node = byCommunity.value.get(String(communityId)) || props.nodes[0]
  if (!node) return
  focusNode(node.id)
  canvasRef.value?.focus({ preventScroll: true })
}
function select(id) {
  const node = id == null ? null : nodeById.value.get(String(id)) || null
  keyboardId.value = node ? String(node.id) : ''
  if (overview.value) {
    if (node) emit('expand', node.communityId)
  } else emit('select', node)
}
function open(id) {
  const node = nodeById.value.get(String(id))
  if (node && !overview.value) emit('open', node)
}
function onCanvasFocus(event) {
  if (!event.target.matches(':focus-visible') || keyboardTarget.value) return
  if (props.nodes.length) focusNode(props.nodes[0].id)
}
function onKeyDown(event) {
  const { key } = event
  if (['ArrowLeft', 'ArrowRight', 'ArrowUp', 'ArrowDown', 'Home', 'End'].includes(key)) {
    if (!props.nodes.length) return
    event.preventDefault()
    const index = indexById.value.get(keyboardTarget.value)
    const step = key === 'ArrowLeft' || key === 'ArrowUp' ? -1 : 1
    const next = key === 'Home' ? 0 : key === 'End' ? props.nodes.length - 1 : index == null ? 0
      : (index + step + props.nodes.length) % props.nodes.length
    focusNode(props.nodes[next].id)
  } else if (key === 'Enter' || key === ' ') {
    event.preventDefault()
    if (event.repeat) return
    const id = keyboardTarget.value || (overview.value ? hoverId.value : '')
    if (!id) return
    if (event.shiftKey && key === 'Enter' && !overview.value) open(id)
    else select(id)
  } else if (key === 'Escape') {
    event.preventDefault()
    hoverId.value = ''
    select(null)
  } else if (key === '0') {
    event.preventDefault()
    resetView()
  } else if (key === '+' || key === '=' || key === '-') {
    event.preventDefault()
    zoomBy(key === '-' ? 1 / 1.2 : 1.2)
  }
}

watch(() => [props.nodes, props.edges, props.communities, props.mode, props.layoutRootId], () => {
  graphDirty = true
  hoverId.value = ''
  if (keyboardId.value && !nodeById.value.has(keyboardId.value)) keyboardId.value = ''
  if (queuedFocus && !nodeById.value.has(queuedFocus)) queuedFocus = ''
  syncScene()
}, { flush: 'post' })
watch([() => props.active, visible], syncScene, { flush: 'post' })
watch([reducedMotion, rotationEnabled], syncMotion)
watch(() => props.activeId, () => { keyboardId.value = ''; hoverId.value = '' })
watch(selectedId, refreshSelection)

onMounted(() => {
  try {
    renderer = createGraph3dRenderer(canvasRef.value, {
      onSelect: select, onOpen: open,
      onHover: id => { hoverId.value = id || '' },
      onMotionChange: value => { motion.value = value },
      onMetrics: updateMetrics,
    })
  } catch {
    renderer = null
  }
  if (!renderer) {
    available.value = false
    return
  }
  renderer.setActive(false)
  motionQuery = window.matchMedia?.('(prefers-reduced-motion: reduce)')
  motionQuery?.addEventListener?.('change', syncMotionPreference)
  syncMotionPreference()
  syncMotion()
  if (typeof IntersectionObserver !== 'undefined') {
    observer = new IntersectionObserver(([entry]) => { visible.value = entry.isIntersecting })
    observer.observe(sceneRef.value)
  }
  syncScene()
})
onBeforeUnmount(() => {
  observer?.disconnect()
  motionQuery?.removeEventListener?.('change', syncMotionPreference)
  clearTimeout(metricTimer)
  renderer?.destroy()
})
defineExpose({ resetView, focusNode, focusCommunity })
</script>

<template>
  <section class="kg3d" :data-graph-mode="mode" :data-motion="motion" :aria-label="`三维${unit}图谱`">
    <div ref="sceneRef" class="kg3d-scene" :data-painted-count="metrics?.paintedNodes" :data-node-count="nodes.length">
      <canvas ref="canvasRef" class="kg3d-canvas" :class="{ 'has-hit': hoverId }" role="group" tabindex="0"
        :aria-label="`三维${unit}图谱，${nodes.length} 个${unit}，${edges.length} 条关系`" :aria-describedby="instructionsId"
        @focus="onCanvasFocus" @keydown="onKeyDown" />
      <span class="kg3d-badge" aria-hidden="true">3D <i /> 透视</span>
      <button type="button" class="kg3d-rotate" aria-label="自动旋转" :aria-pressed="rotationEnabled"
        :disabled="!available || reducedMotion" :title="reducedMotion ? '已遵循系统减少动态效果设置' : '切换空闲自动旋转'"
        @click="autoRotate = !autoRotate">
        <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.7" aria-hidden="true">
          <ellipse cx="12" cy="12" rx="10" ry="4.5" transform="rotate(-30 12 12)" /><circle cx="12" cy="12" r="1.5" />
        </svg>
        <span>自动旋转</span><span class="kg3d-motion-dot" />
      </button>
      <p v-if="!available" class="kg3d-unavailable" role="status">当前浏览器暂无法绘制三维图谱。可继续使用右侧搜索和筛选。</p>
      <div class="kg3d-zoom" aria-label="图谱视角控制">
        <button type="button" aria-label="放大图谱" title="放大" :disabled="!available" @click="zoomBy(1.2)">+</button>
        <button type="button" aria-label="缩小图谱" title="缩小" :disabled="!available" @click="zoomBy(1 / 1.2)">−</button>
        <button type="button" aria-label="查看全图" title="查看全图" :disabled="!available" @click="resetView">
          <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.7" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true">
            <path d="M4 10a8 8 0 1 1 2 8M4 5v5h5" />
          </svg>
        </button>
      </div>
    </div>
    <footer class="kg3d-footer">
      <div class="kg3d-readout">
        <span v-if="overview && selectedNode" class="kg3d-current" :title="nodeTitle(selectedNode)">{{ nodeTitle(selectedNode) }} · {{ selectedNode.count }} 个概念</span>
        <span v-else class="kg3d-hint">拖动旋转 · 滚轮或双指缩放 · {{ overview ? '点击展开社区' : '点击查看关系' }}</span>
        <button v-if="overview && selectedNode" type="button" class="kg3d-expand" @click="select(selectedId)">展开社区</button>
      </div>
      <span class="kg3d-density">{{ densityText || motionText }}</span>
    </footer>
    <span :id="instructionsId" class="kg3d-sr-only">方向键逐个定位，Home 和 End 跳到首尾，Enter 或空格{{ overview ? '展开社区' : '选择概念，Shift+Enter 打开' }}。加减键缩放，0 查看全图，Escape 清除选择。</span>
    <span class="kg3d-sr-only" role="status">{{ keyboardNode ? nodeTitle(keyboardNode) : '' }}</span>
  </section>
</template>

<style scoped>
.kg3d {
  --scene-ink: #40516e;
  --scene-muted: #617087;
  --scene-line: #e1e6ed;
  display: flex;
  flex: 1 1 auto;
  flex-direction: column;
  width: 100%;
  height: 100%;
  min-width: 0;
  min-height: 290px;
  background: #fff;
  color: var(--scene-ink);
  color-scheme: light;
}
.kg3d-scene { position: relative; flex: 1 1 auto; min-height: 235px; overflow: hidden; isolation: isolate; }
.kg3d-canvas { position: absolute; inset: 0; display: block; width: 100%; height: 100%; touch-action: none; cursor: grab; }
.kg3d-canvas.has-hit { cursor: pointer; }
.kg3d-canvas:active { cursor: grabbing; }
.kg3d-canvas:focus-visible { outline-offset: -3px; }
.kg3d-badge, .kg3d-rotate, .kg3d-zoom { position: absolute; z-index: 1; }
.kg3d-badge { top: 16px; left: 16px; display: inline-flex; align-items: center; gap: 6px; color: var(--scene-muted); font-size: 11px; letter-spacing: .08em; pointer-events: none; }
.kg3d-badge i { width: 3px; height: 3px; border-radius: 50%; background: #9aa8bf; }
.kg3d button { font-family: inherit; cursor: pointer; }
.kg3d button:disabled { opacity: .55; cursor: default; }
.kg3d button:focus-visible, .kg3d-canvas:focus-visible { outline: 2px solid #5771ad; }
.kg3d-rotate { top: 10px; right: 12px; display: flex; align-items: center; gap: 6px; height: 35px; padding: 0 9px; border: 1px solid #dbe2ec; border-radius: 7px; background: #fff; color: var(--scene-ink); font-size: 12px; }
.kg3d-rotate[aria-pressed="true"] { background: #f4f6fb; border-color: #d0d9eb; }
.kg3d-rotate svg, .kg3d-zoom svg { width: 16px; height: 16px; }
.kg3d-motion-dot { width: 5px; height: 5px; border-radius: 50%; background: #8692a7; }
.kg3d[data-motion="rotating"] .kg3d-motion-dot { background: #526eb8; }
.kg3d-zoom { right: 12px; bottom: 10px; display: flex; border: 1px solid #dbe2ec; border-radius: 7px; background: #fffffff2; }
.kg3d-zoom button { display: grid; place-items: center; width: 35px; height: 35px; padding: 0; border: 0; background: transparent; color: var(--scene-ink); font-size: 17px; }
.kg3d-zoom button + button { border-left: 1px solid var(--scene-line); }
.kg3d-zoom button:hover, .kg3d-rotate:hover { background: #eef2f9; }
.kg3d-footer { display: flex; justify-content: space-between; align-items: center; gap: 4px 12px; min-height: 39px; padding: 3px 12px; border-top: 1px solid var(--scene-line); color: var(--scene-muted); font-size: 11px; }
.kg3d-readout { display: flex; align-items: center; gap: 8px; min-width: 0; }
.kg3d-current, .kg3d-hint { overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.kg3d-expand { flex: none; padding: 3px 5px; border: 0; border-radius: 4px; background: #f1f4fa; color: var(--scene-ink); font-size: inherit; }
.kg3d-density { flex: none; font-variant-numeric: tabular-nums; white-space: nowrap; }
.kg3d-unavailable { position: absolute; inset: 0; display: grid; place-items: center; margin: 0; padding: 15px; text-align: center; color: var(--scene-muted); }
.kg3d-sr-only { position: absolute; width: 1px; height: 1px; padding: 0; overflow: hidden; clip-path: inset(50%); white-space: nowrap; }
@media (max-width: 640px) {
  .kg3d-badge { top: 15px; left: 10px; }
  .kg3d-rotate, .kg3d-zoom { right: 8px; }
  .kg3d-footer { min-height: 51px; flex-wrap: wrap; align-content: center; padding-inline: 9px; }
  .kg3d-readout { flex: 1 1 100%; }
}
</style>
