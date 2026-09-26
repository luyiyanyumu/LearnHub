<script setup>
/**
 * 知识图谱：零依赖的 SVG 力导向图。
 *
 * <h3>为什么自己写而不是引 echarts / d3</h3>
 * 本项目的依赖刻意保持精简（MCP server 都是零依赖手写的）。图只有"几十个节点、
 * 几十条边"的量级，一个斥力 + 弹簧 + 向心力的简化模拟就够用，包体不增、配色也能直接
 * 用项目令牌（深色主题自动跟随）。换成图库反而要额外处理主题、按需引入与包体。
 *
 * <h3>三个容易踩的坑（都已处理）</h3>
 * <ol>
 *   <li><b>刷新时不能重排</b>：版本号一变就重新拉数据，若重建模拟，整张图会跳一下。
 *       所以位置按节点 id 存在 Map 里，老节点沿用旧坐标，只有新节点才随机落点。</li>
 *   <li><b>点击与拖拽要区分</b>：拖完节点会触发 click，于是拖一下就把节点选中了。
 *       这里用位移阈值（<4px 才算点击）区分。</li>
 *   <li><b>模拟要能停</b>：常驻 rAF 会一直占 CPU。用 alpha 衰减，静止后自动停，
 *       拖动或数据变化时重新加能量。</li>
 * </ol>
 */
import { computed, onBeforeUnmount, onMounted, reactive, ref, watch } from 'vue'

const props = defineProps({
  nodes: { type: Array, default: () => [] },
  edges: { type: Array, default: () => [] },
  activeId: { type: String, default: '' },
})
const emit = defineEmits(['select', 'open'])

const wrapRef = ref(null)
const view = reactive({ w: 900, h: 540, x: 0, y: 0, k: 1 })
const hoverId = ref('')
const dragging = ref(null)

/** id → 运行时状态；跨数据刷新保留坐标，避免整图跳动 */
const sim = new Map()
const frame = ref(0) // 每帧自增，用来驱动重渲染

let alpha = 1
let raf = 0
let ro = null

const NODE_R = { category: 13, tag: 9, note: 7, ref: 6, file: 8, concept: 8 }

function nodeRadius(n) {
  const base = NODE_R[n.type] ?? 7
  return base + Math.min(6, (n.degree || 0) * 0.5)
}

/** 用项目令牌派生一套同色系阶梯，深色主题自动跟随（不硬编码颜色） */
function nodeFill(n) {
  switch (n.type) {
    case 'category':
      return 'var(--app-brand)'
    case 'note':
      return 'color-mix(in srgb, var(--app-brand) 62%, var(--app-text-1))'
    case 'ref':
      return 'color-mix(in srgb, var(--app-brand) 34%, var(--app-text-2))'
    case 'file':
      // 资料：更深的青灰，与笔记/速查卡拉开距离（同一色系、不同明度）
      return 'color-mix(in srgb, var(--app-brand-deep) 72%, var(--app-text-2))'
    case 'concept':
      // 概念：实心一点，和文档层的"点"区分开
      return 'color-mix(in srgb, var(--app-brand) 78%, var(--app-text-1))'
    default:
      return 'var(--app-brand-soft)'
  }
}
function nodeStroke(n) {
  if (n.type === 'tag') return 'var(--app-brand)'
  if (n.type === 'category') return 'var(--app-brand-deep)'
  if (n.type === 'concept') return 'var(--app-brand-soft)'
  return 'transparent'
}

const degreeMap = computed(() => {
  const m = new Map()
  for (const e of props.edges) {
    m.set(e.source, (m.get(e.source) || 0) + 1)
    m.set(e.target, (m.get(e.target) || 0) + 1)
  }
  return m
})

/** 悬停高亮：只点亮与它相邻的节点/边，其余降透明度 */
const neighbors = computed(() => {
  const id = hoverId.value || props.activeId
  if (!id) return null
  const set = new Set([id])
  for (const e of props.edges) {
    if (e.source === id) set.add(e.target)
    if (e.target === id) set.add(e.source)
  }
  return set
})

function dimmed(id) {
  return neighbors.value ? !neighbors.value.has(id) : false
}
function edgeDimmed(e) {
  if (!neighbors.value) return false
  const id = hoverId.value || props.activeId
  return e.source !== id && e.target !== id
}

const renderNodes = computed(() => {
  frame.value // 建立依赖：每帧重算
  return props.nodes.map((n) => {
    const s = sim.get(n.id)
    return { ...n, x: s?.x ?? 0, y: s?.y ?? 0, r: nodeRadius(n) }
  })
})

const renderEdges = computed(() => {
  frame.value
  const byId = new Map(renderNodes.value.map((n) => [n.id, n]))
  return props.edges.map((e, i) => {
    const a = byId.get(e.source)
    const b = byId.get(e.target)
    if (!a || !b) return null
    return {
      key: e.source + '>' + e.target + '#' + i,
      x1: a.x, y1: a.y, x2: b.x, y2: b.y,
      semantic: e.kind === 'semantic',
      // 概念层：derived = 按本体规则推出来的隐含事实，用虚线画出来，
      // 这样"看到的"和"推出来的"在图上就是一眼可分的两回事
      derived: e.origin === 'derived',
      // 概念层标记：这里"边"就是主角（它表达的是关系本身），
      // 文档层那套浅灰细线在这层太淡了，几乎看不见（实测 stroke #E8EAED / 1.2px）
      concept: e.origin === 'llm' || e.origin === 'derived',
      relation: e.relation,
      relationLabel: e.label,
      reason: e.reason,
      weight: e.weight,
    }
  }).filter(Boolean)
})

// ------------------------------------------------------------------
// 力导向模拟：斥力 + 弹簧 + 向心力
// ------------------------------------------------------------------
function ensureState() {
  const cx = view.w / 2
  const cy = view.h / 2
  for (const n of props.nodes) {
    if (!sim.has(n.id)) {
      const a = Math.random() * Math.PI * 2
      const r = 60 + Math.random() * 120
      sim.set(n.id, { x: cx + Math.cos(a) * r, y: cy + Math.sin(a) * r, vx: 0, vy: 0 })
    }
  }
  // 清掉已删除节点的残留坐标
  const ids = new Set(props.nodes.map((n) => n.id))
  for (const id of [...sim.keys()]) {
    if (!ids.has(id)) sim.delete(id)
  }
}

function tick() {
  const ns = props.nodes
  const cx = view.w / 2
  const cy = view.h / 2
  const REPULSE = 5200
  const SPRING = 0.045
  const SPRING_LEN = 96
  const CENTER = 0.006
  const DAMP = 0.86

  for (const a of ns) {
    const sa = sim.get(a.id)
    if (!sa) continue
    // 斥力：O(n²)，几十个节点无压力；上千节点再换空间划分
    for (const b of ns) {
      if (a.id === b.id) continue
      const sb = sim.get(b.id)
      if (!sb) continue
      let dx = sa.x - sb.x
      let dy = sa.y - sb.y
      let d2 = dx * dx + dy * dy
      if (d2 < 1) {
        dx = Math.random() - 0.5
        dy = Math.random() - 0.5
        d2 = 1
      }
      const f = (REPULSE / d2) * alpha
      const d = Math.sqrt(d2)
      sa.vx += (dx / d) * f
      sa.vy += (dy / d) * f
    }
    // 向心力：防孤岛飘走
    sa.vx += (cx - sa.x) * CENTER * alpha
    sa.vy += (cy - sa.y) * CENTER * alpha
  }

  for (const e of props.edges) {
    const a = sim.get(e.source)
    const b = sim.get(e.target)
    if (!a || !b) continue
    const dx = b.x - a.x
    const dy = b.y - a.y
    const d = Math.max(1, Math.hypot(dx, dy))
    const target = e.kind === 'semantic' ? SPRING_LEN * 1.5 : SPRING_LEN
    const f = (d - target) * SPRING * alpha
    const ux = (dx / d) * f
    const uy = (dy / d) * f
    a.vx += ux
    a.vy += uy
    b.vx -= ux
    b.vy -= uy
  }

  for (const n of ns) {
    const s = sim.get(n.id)
    if (!s) continue
    if (dragging.value === n.id) {
      s.vx = 0
      s.vy = 0
      continue
    }
    s.vx *= DAMP
    s.vy *= DAMP
    s.x += Math.max(-14, Math.min(14, s.vx))
    s.y += Math.max(-14, Math.min(14, s.vy))

    // 软边界：靠近边缘就回推，越过就直接夹住。
    // 边距必须按**节点自己的半径**算：分类节点半径 19，标签画在圆心下方 r+11 处，
    // 再加下降部与呼吸空间。用固定值 34 时分类标签的下缘正好压在画布边上（实测只差 1px），
    // 看起来就是"底部被切掉了"。
    const margin = nodeRadius(n) + 28
    const minX = margin
    const maxX = view.w - margin
    const minY = margin
    const maxY = view.h - margin
    if (s.x < minX) s.vx += (minX - s.x) * 0.25
    else if (s.x > maxX) s.vx -= (s.x - maxX) * 0.25
    if (s.y < minY) s.vy += (minY - s.y) * 0.25
    else if (s.y > maxY) s.vy -= (s.y - maxY) * 0.25
    s.x = Math.min(maxX, Math.max(minX, s.x))
    s.y = Math.min(maxY, Math.max(minY, s.y))
  }
  frame.value++
}

function loop() {
  tick()
  alpha *= 0.985
  // 冷却到静止时，如果期间数据变过就自动缩放一次（兜底；主触发是下面按时间的定时器）
  if (alpha <= 0.02 && needsFit) {
    needsFit = false
    fitView()
  }
  if (alpha > 0.02 || dragging.value) {
    raf = requestAnimationFrame(loop)
  } else {
    raf = 0
  }
}

function reheat(a = 0.9) {
  alpha = a
  if (!raf) {
    raf = requestAnimationFrame(loop)
  }
}

/**
 * 把整张图缩放到画布内（留 6% 边距），并居中。
 * <p>
 * 不只是"防止超出"：画布比图大时它会把图**放大**填满（上限 1.6 倍），
 * 否则一张小图缩在宽画布中间，四周全是空白，看起来就像"图的下半部分没画出来"。
 */
function fitView() {
  const pts = [...sim.values()]
  if (!pts.length) return
  let minX = Infinity
  let minY = Infinity
  let maxX = -Infinity
  let maxY = -Infinity
  for (const p of pts) {
    minX = Math.min(minX, p.x)
    minY = Math.min(minY, p.y)
    maxX = Math.max(maxX, p.x)
    maxY = Math.max(maxY, p.y)
  }
  const pad = 36 // 节点半径 + 标签的余量
  const w = Math.max(1, maxX - minX + pad * 2)
  const h = Math.max(1, maxY - minY + pad * 2)
  const k = Math.max(0.35, Math.min(1.6, Math.min(view.w / w, view.h / h) * 0.94))
  view.k = k
  view.x = (view.w - w * k) / 2 - (minX - pad) * k
  view.y = (view.h - h * k) / 2 - (minY - pad) * k
}

let needsFit = false
/** 用户自己缩放/平移过之后就不再自动抢视图（实时刷新每几秒一次，抢视图会很烦） */
let userAdjusted = false
let fitTimer = 0

/**
 * 触发一次"适应视图"。
 * <p>
 * 刻意用**定时器**而不是等模拟冷却：alpha 是按帧数衰减的，
 * 浏览器后台标签页 / 无头环境会把 rAF 节流，几十秒都到不了阈值（实测就这样漏掉了）。
 * 按时间触发与帧率无关，行为可预期。
 */
function scheduleFit(delay = 2200) {
  clearTimeout(fitTimer)
  fitTimer = setTimeout(() => {
    if (needsFit && !userAdjusted) {
      needsFit = false
      fitView()
    }
  }, delay)
}

watch(() => [props.nodes, props.edges], () => {
  ensureState()
  needsFit = true
  reheat(0.7)
  scheduleFit()
  frame.value++
})

// ------------------------------------------------------------------
// 交互：拖拽 / 缩放 / 平移
// ------------------------------------------------------------------
let panFrom = null
let dragFrom = null
let moved = 0

function svgPoint(evt) {
  const rect = wrapRef.value?.getBoundingClientRect()
  if (!rect) return { x: 0, y: 0 }
  // 屏幕坐标 → 画布坐标（去掉缩放与平移）
  return {
    x: (evt.clientX - rect.left - view.x) / view.k,
    y: (evt.clientY - rect.top - view.y) / view.k,
  }
}

function onNodeDown(evt, n) {
  evt.stopPropagation()
  dragging.value = n.id
  dragFrom = svgPoint(evt)
  moved = 0
  reheat(1)
  window.addEventListener('pointermove', onWindowMove)
  window.addEventListener('pointerup', onWindowUp)
}

/**
 * 约束平移范围：内容矩形必须与画布保持至少三分之一的重叠。
 * <p>
 * 允许自由平移，但**不允许把整张图拖出视野**。起因是用户两次反馈"下面不见了"——
 * 背景拖拽很容易一下子把图拖没，而界面上唯一的恢复手段（右下角 ⟳）不够显眼。
 */
function clampView() {
  const pts = [...sim.values()]
  if (!pts.length || !view.w || !view.h) return
  let minX = Infinity
  let minY = Infinity
  let maxX = -Infinity
  let maxY = -Infinity
  for (const p of pts) {
    minX = Math.min(minX, p.x)
    minY = Math.min(minY, p.y)
    maxX = Math.max(maxX, p.x)
    maxY = Math.max(maxY, p.y)
  }
  const left = view.x + minX * view.k
  const right = view.x + maxX * view.k
  const top = view.y + minY * view.k
  const bottom = view.y + maxY * view.k
  const need = 0.34
  if (right < view.w * need) view.x += view.w * need - right
  if (left > view.w * (1 - need)) view.x -= left - view.w * (1 - need)
  if (bottom < view.h * need) view.y += view.h * need - bottom
  if (top > view.h * (1 - need)) view.y -= top - view.h * (1 - need)
}

function onWindowMove(evt) {
  if (panFrom) {
    view.x += evt.clientX - panFrom.sx
    view.y += evt.clientY - panFrom.sy
    panFrom.sx = evt.clientX
    panFrom.sy = evt.clientY
    clampView()
    return
  }
  if (!dragging.value) return
  const p = svgPoint(evt)
  const s = sim.get(dragging.value)
  if (!s) return
  moved += Math.hypot(p.x - dragFrom.x, p.y - dragFrom.y)
  s.x = p.x
  s.y = p.y
  dragFrom = p
  reheat(0.55)
}

function onWindowUp(evt) {
  window.removeEventListener('pointermove', onWindowMove)
  window.removeEventListener('pointerup', onWindowUp)
  const wasDrag = moved > 4
  const id = dragging.value
  dragging.value = null
  panFrom = null
  if (id && !wasDrag) {
    const node = props.nodes.find((n) => n.id === id)
    if (node) emit('select', node)
  } else if (!id) {
    // 空白处点击：清除选中
    emit('select', null)
  }
}

function onBackgroundDown(evt) {
  if (evt.target.closest('.kg-node')) return
  userAdjusted = true
  panFrom = { sx: evt.clientX, sy: evt.clientY }
  window.addEventListener('pointermove', onWindowMove)
  window.addEventListener('pointerup', onWindowUp)
}

function onWheel(evt) {
  evt.preventDefault()
  userAdjusted = true
  const rect = wrapRef.value?.getBoundingClientRect()
  const mx = evt.clientX - rect.left
  const my = evt.clientY - rect.top
  const k2 = Math.max(0.3, Math.min(2.6, view.k * (evt.deltaY < 0 ? 1.12 : 0.89)))
  // 以光标为锚点缩放
  view.x = mx - ((mx - view.x) / view.k) * k2
  view.y = my - ((my - view.y) / view.k) * k2
  view.k = k2
}

function zoomBy(f) {
  userAdjusted = true
  const cx = view.w / 2
  const cy = view.h / 2
  const k2 = Math.max(0.3, Math.min(2.6, view.k * f))
  view.x = cx - ((cx - view.x) / view.k) * k2
  view.y = cy - ((cy - view.y) / view.k) * k2
  view.k = k2
}

function resetView() {
  userAdjusted = false
  fitView()
}

defineExpose({ resetView, zoomBy, reheat, fitView })

onMounted(() => {
  const el = wrapRef.value
  if (!el) return
  const apply = () => {
    view.w = Math.max(480, el.clientWidth)
    view.h = Math.max(320, el.clientHeight)
    ensureState()
    // 这里必须自己排一次"适应视图"：父组件用的是 v-if="nodes.length"，
    // 本组件是在**数据到位之后**才创建的 —— props 从第一帧起就是最终值，
    // 那个 watch 永远不会触发（实测漏掉过：自动缩放一次都没跑，手动点按钮却正常）。
    needsFit = true
    reheat(0.8)
    scheduleFit()
  }
  apply()
  ro = new ResizeObserver(apply)
  ro.observe(el)
})

onBeforeUnmount(() => {
  if (raf) cancelAnimationFrame(raf)
  clearTimeout(fitTimer)
  ro?.disconnect()
  window.removeEventListener('pointermove', onWindowMove)
  window.removeEventListener('pointerup', onWindowUp)
})

function label(n) {
  const t = n.label || ''
  return t.length > 12 ? t.slice(0, 12) + '…' : t
}

/**
 * 标签纵坐标：默认画在节点下方；**贴近画布下沿时翻到上方**。
 * <p>
 * 软边界管的是"节点圆心"，而标签还占 ~15px；用户把图放大/平移之后，
 * 节点仍可能来到下沿附近，此时标签会被画布裁掉（实测就是"下面不见了"的观感）。
 * 翻转比"再多留白"更彻底：无论怎么拖拽缩放，标签都不会被切。
 */
function labelY(n) {
  const below = n.y + n.r + 11
  return n.y + n.r + 16 > view.h ? n.y - n.r - 6 : below
}
</script>

<template>
  <div ref="wrapRef" class="kg-wrap" @pointerdown="onBackgroundDown" @wheel="onWheel">
    <svg class="kg-svg" :width="view.w" :height="view.h">
      <defs>
        <!-- 语义边用箭头区分方向（前置/易混是有向的） -->
        <marker id="kg-arrow" viewBox="0 0 10 10" refX="9" refY="5" markerWidth="6" markerHeight="6" orient="auto-start-reverse">
          <path d="M0,0 L10,5 L0,10 z" fill="var(--app-brand)" />
        </marker>
      </defs>
      <g :transform="`translate(${view.x},${view.y}) scale(${view.k})`">
        <line
          v-for="e in renderEdges"
          :key="e.key"
          :x1="e.x1" :y1="e.y1" :x2="e.x2" :y2="e.y2"
          class="kg-edge"
          :class="{ 'kg-edge-semantic': e.semantic, 'kg-edge-concept': e.concept, 'kg-edge-derived': e.derived, 'kg-edge-dim': edgeDimmed(e) }"
          :marker-end="e.semantic ? 'url(#kg-arrow)' : ''"
        >
          <title v-if="e.derived">{{ e.relationLabel || e.relation }}（规则推导）</title>
          <title v-else-if="e.relationLabel">{{ e.relationLabel }}（{{ e.relation }}）{{ e.reason ? '：' + e.reason : '' }}</title>
          <title v-else-if="e.reason">{{ e.reason }}（{{ e.relation }}）</title>
        </line>

        <g
          v-for="n in renderNodes"
          :key="n.id"
          class="kg-node"
          :class="{ 'kg-node-dim': dimmed(n.id), 'kg-node-active': n.id === activeId || n.id === hoverId }"
          @pointerdown="onNodeDown($event, n)"
          @mouseenter="hoverId = n.id"
          @mouseleave="hoverId = ''"
        >
          <circle
            :cx="n.x" :cy="n.y" :r="n.r"
            :fill="nodeFill(n)"
            :stroke="nodeStroke(n)"
            stroke-width="1.5"
          />
          <text
            :x="n.x" :y="labelY(n)"
            class="kg-label"
            :class="{
              'kg-label-strong': n.type === 'category',
              'kg-label-tag': n.type === 'tag',
            }"
            text-anchor="middle"
          >{{ label(n) }}</text>
          <title>{{ n.label }}（{{ n.type }}，{{ n.degree || 0 }} 条关联）</title>
        </g>
      </g>
    </svg>

    <div class="kg-zoom">
      <button type="button" title="放大" @click="zoomBy(1.2)">＋</button>
      <button type="button" title="缩小" @click="zoomBy(0.83)">－</button>
      <button type="button" title="适应视图" @click="resetView">⟳</button>
    </div>
  </div>
</template>

<style scoped>
.kg-wrap {
  position: relative;
  width: 100%;
  height: 100%;
  min-height: 320px;
  overflow: hidden;
  border-radius: var(--radius);
  background: var(--app-bg);
  cursor: grab;
  touch-action: none;
}
.kg-wrap:active {
  cursor: grabbing;
}
.kg-svg {
  display: block;
}
.kg-edge {
  stroke: var(--app-border);
  stroke-width: 1.2;
  transition: opacity var(--dur-fast) ease;
}
.kg-edge-semantic {
  stroke: color-mix(in srgb, var(--app-brand) 55%, transparent);
  stroke-width: 1.6;
  stroke-dasharray: 5 4;
}
/* 推导边：虚线 + 更淡。它是"按规则推出来的"，不是哪条记录里直接写着的 ——
   画成和直接事实一样的实线，会让人把推断当成原文。 */
/* 概念层的边：加深、加粗。文档层靠"点"表达内容、边只是辅助；
   概念层反过来 —— 关系本身就是内容，浅灰细线等于没画。 */
.kg-edge-concept {
  stroke: color-mix(in srgb, var(--app-brand) 42%, var(--app-text-3));
  stroke-width: 1.4;
  opacity: 0.85;
}
.kg-edge-concept.kg-edge-dim {
  opacity: 0.12;
}
.kg-edge-derived {
  stroke-dasharray: 4 4;
  opacity: 0.75;
}
.kg-edge-dim {
  opacity: 0.15;
}
.kg-node {
  cursor: pointer;
}
.kg-node circle {
  transition: opacity var(--dur-fast) ease;
}
.kg-node-dim {
  opacity: 0.22;
}
.kg-node-active circle {
  stroke: var(--app-brand-deep);
  stroke-width: 2.5;
}
.kg-label {
  font-size: 10.5px;
  fill: var(--app-text-2);
  pointer-events: none;
  user-select: none;
}
.kg-label-strong {
  font-size: 11.5px;
  font-weight: 600;
  fill: var(--app-text-1);
}
/* 标签节点：空心小环 + 小一号淡字。
   之前刻意不画标签文字，结果图上是几个"空白圆圈"，看不出是什么（用户直接来问）。
   小一号 + 更淡是为了保持层次，不是省略。 */
.kg-label-tag {
  font-size: 10px;
  font-weight: 500;
  fill: var(--app-text-3);
}
.kg-zoom {
  position: absolute;
  right: 10px;
  bottom: 10px;
  display: flex;
  flex-direction: column;
  gap: 4px;
}
.kg-zoom button {
  width: 26px;
  height: 26px;
  border: 1px solid var(--app-border);
  border-radius: 7px;
  background: var(--app-card);
  color: var(--app-text-2);
  font-size: 13px;
  line-height: 1;
  cursor: pointer;
  transition: all var(--dur-fast) ease;
}
.kg-zoom button:hover {
  color: var(--app-brand-deep);
  border-color: var(--app-brand);
  background: var(--app-brand-soft);
}
</style>
