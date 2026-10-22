const INKS = ['#647d96', '#85786e', '#678989', '#827c95']
const clamp = (value, min, max) => Math.max(min, Math.min(max, value))
const finite = (value, fallback = 0) => Number.isFinite(Number(value)) ? Number(value) : fallback

export function galaxyFitCamera(layout, width, height) {
  const b = layout.bounds || { left: 0, right: layout.width || 1, top: 0, bottom: layout.height || 1 }
  const padding = Math.min(48, Math.max(20, width * .055))
  const k = Math.max(.0001, Math.min((width - padding * 2) / Math.max(1, b.right - b.left),
    (height - padding * 2) / Math.max(1, b.bottom - b.top), 2))
  return { x: width / 2 - (b.left + b.right) / 2 * k, y: height / 2 - (b.top + b.bottom) / 2 * k, k }
}

export function galaxyViewBounds(camera, view, padding = 0) {
  const k = Math.max(.000001, finite(camera.k, 1))
  return { left: (-camera.x - padding) / k, right: (view.width - camera.x + padding) / k,
    top: (-camera.y - padding) / k, bottom: (view.height - camera.y + padding) / k }
}

export function galaxyZoomAt(camera, point, factor, min = .001, max = 8) {
  const k = clamp(camera.k * factor, min, max), ratio = k / camera.k
  return { x: point.x - (point.x - camera.x) * ratio, y: point.y - (point.y - camera.y) * ratio, k }
}

export function selectGalaxyDetail(visibleNodes, camera, view, focusId = '', { interactive = false } = {}) {
  let painted = visibleNodes, aggregated = false
  if (visibleNodes.length > 3000) {
    const cell = Math.max(8, Math.sqrt(view.width * view.height / 1600)), buckets = new Map()
    for (const node of visibleNodes) {
      if (String(node.id) === String(focusId)) continue
      const x = node.x * camera.k + camera.x, y = node.y * camera.k + camera.y
      const key = `${Math.floor(x / cell)}:${Math.floor(y / cell)}`
      const bucket = buckets.get(key)
      if (bucket) { bucket.clusterCount++; if (node.count > bucket.count) Object.assign(bucket, { ...node, clusterCount: bucket.clusterCount }) }
      else buckets.set(key, { ...node, clusterCount: 1 })
    }
    painted = [...buckets.values()]
    const focus = visibleNodes.find(node => String(node.id) === String(focusId))
    if (focus) painted.push(focus)
    aggregated = true
  }
  const focus = visibleNodes.find(node => String(node.id) === String(focusId))
  const budget = interactive ? (focus ? 1 : 0) : camera.k < .8 ? 5 : camera.k < 1.8 ? 12 : 24
  const candidates = interactive ? focus ? [focus] : [] : [...visibleNodes].sort((a, b) =>
    Number(String(b.id) === String(focusId)) - Number(String(a.id) === String(focusId)) || b.count - a.count).slice(0, 160)
  const labelNodes = [], boxes = [], labelWidth = Math.min(128, view.width - 24)
  for (const node of candidates) {
    if (labelNodes.length >= budget) break
    const x = node.x * camera.k + camera.x, y = node.y * camera.k + camera.y
    if (x < 0 || x > view.width || y < 0 || y > view.height - 20) continue
    const left = clamp(x - labelWidth / 2, 8, Math.max(8, view.width - labelWidth - 8))
    const box = { left, right: left + labelWidth, top: y + 12, bottom: y + 31 }
    if (boxes.some(other => box.left < other.right + 8 && box.right > other.left - 8 && box.top < other.bottom + 4 && box.bottom > other.top - 4)) continue
    boxes.push(box); labelNodes.push(node)
  }
  return { painted, labelNodes, visibleCount: visibleNodes.length, aggregated }
}

function noise(index, salt) {
  const value = Math.sin((index + 1) * 127.1 + (salt + 1) * 311.7) * 43758.5453
  return value - Math.floor(value)
}

function makeDust(layout) {
  if (!layout.nodes.length) return []
  const dust = [], band = layout.bandWidth || layout.width * .025
  for (const arm of layout.spiralArms || []) arm.points.slice(1).forEach((point, index) => {
    const a = arm.points[index], b = arm.points[Math.min(index + 2, arm.points.length - 1)]
    const length = Math.hypot(b.x - a.x, b.y - a.y) || 1, tx = (b.x - a.x) / length, ty = (b.y - a.y) / length
    for (let lane = 0; lane < 9; lane++) {
      const across = (noise(index * 9 + lane, arm.id * 2) - .5) * band * 1.6
      const along = (noise(index * 9 + lane, arm.id * 2 + 1) - .5) * band * .25
      dust.push({ x: point.x - ty * across + tx * along, y: point.y + tx * across + ty * along, tone: (index + lane) % 4,
        size: .45 + lane % 3 * .19 })
    }
  })
  const center = layout.center || { x: layout.width / 2, y: layout.height / 2 }
  const spread = layout.width * .035
  for (let i = 0; i < 600; i++) {
    const a = noise(i, 19) * Math.PI * 2, r = Math.sqrt(-Math.log(Math.max(.0001, noise(i, 23)))) * spread
    dust.push({ x: center.x + Math.cos(a) * r, y: center.y + Math.sin(a) * r * .68, tone: i % 4, size: .5 + i % 3 * .17 })
  }
  return dust
}

function contextFor(canvas, view, decoration = false) {
  if (!canvas?.getContext) return null
  const dpr = decoration ? 1 : clamp(view.dpr || 1, 1, 2)
  const width = Math.max(1, Math.round(view.width * dpr)), height = Math.max(1, Math.round(view.height * dpr))
  if (canvas.width !== width) canvas.width = width
  if (canvas.height !== height) canvas.height = height
  const ctx = canvas.getContext('2d')
  if (!ctx) return null
  ctx.setTransform(dpr, 0, 0, dpr, 0, 0)
  ctx.clearRect(0, 0, view.width, view.height)
  return ctx
}

function point(ctx, x, y, r) { ctx.moveTo(x + r, y); ctx.arc(x, y, r, 0, Math.PI * 2) }
function ink(node) { const id = Number(node.communityId); return INKS[Number.isFinite(id) ? Math.abs(Math.round(id)) % 4 : 0] }
function screen(node, camera) { return { x: node.x * camera.k + camera.x, y: node.y * camera.k + camera.y } }

export function createKnowledgeGalaxyRenderer({ canvas, flowCanvas, twinkleCanvases = [] }) {
  let lastLayout = null, dust = [], overlayKey = '', destroyed = false
  let queryKey = '', lastIndex = null, candidatesCache = []
  return {
    draw(layout, index, camera, view, { focusId = '', adjacency = new Map(), byId = index.byId, interactive = false } = {}) {
      if (destroyed) return { visibleCount: 0, paintedCount: 0, labelCount: 0, aggregated: false }
      if (layout !== lastLayout) { dust = makeDust(layout); lastLayout = layout; overlayKey = ''; queryKey = '' }
      const key = `${camera.x}:${camera.y}:${camera.k}:${view.width}:${view.height}`
      const bounds = galaxyViewBounds(camera, view, 32)
      if (queryKey !== key || lastIndex !== index) {
        candidatesCache = index.query(bounds); queryKey = key; lastIndex = index
      }
      const candidates = candidatesCache
      const detail = selectGalaxyDetail(candidates, camera, view, focusId, { interactive })
      const ctx = contextFor(canvas, view)
      let visibleRelations = 0, paintedRelations = 0
      if (ctx) {
        ctx.fillStyle = '#fff'; ctx.fillRect(0, 0, view.width, view.height)
        const tones = ['#c4ccd5', '#aebbc8', '#8e9faf', '#c0b29e']
        for (let tone = 0; tone < 4; tone++) {
          ctx.beginPath(); ctx.fillStyle = tones[tone]
          for (const dot of dust) {
            if (dot.tone !== tone || dot.x < bounds.left || dot.x > bounds.right || dot.y < bounds.top || dot.y > bounds.bottom) continue
            const p = screen(dot, camera); point(ctx, p.x, p.y, dot.size)
          }
          ctx.fill()
        }
        const incident = adjacency.get(String(focusId)) || []
        const focus = byId.get(String(focusId))
        if (focus) {
          const a = screen(focus, camera)
          ctx.strokeStyle = '#a2aebc'; ctx.lineWidth = .7; ctx.beginPath()
          for (const edge of incident) {
            const target = byId.get(String(edge.source === focus.id ? edge.target : edge.source))
            if (!target) continue
            const b = screen(target, camera)
            // Connection details follow the viewport; full counts remain in the selected community summary.
            if (b.x < -16 || b.x > view.width + 16 || b.y < -16 || b.y > view.height + 16) continue
            visibleRelations++
            if (paintedRelations >= 512) continue
            paintedRelations++
            ctx.moveTo(a.x, a.y); ctx.quadraticCurveTo((a.x + b.x) / 2 * .85 + view.width * .075,
              (a.y + b.y) / 2 * .85 + view.height * .075, b.x, b.y)
          }
          ctx.stroke()
        }
        // Batch points by tone. At low zoom dense cells are represented by one mark.
        for (const color of INKS) {
          ctx.beginPath(); ctx.fillStyle = color
          for (const node of detail.painted) {
            if (ink(node) !== color) continue
            const p = screen(node, camera), r = clamp((node.r || 8) * camera.k, 1.4, 6)
            point(ctx, p.x, p.y, Math.min(6, r + Math.log2(node.clusterCount || 1) * .25))
          }
          ctx.fill()
        }
        if (focus) {
          const p = screen(focus, camera)
          ctx.beginPath(); ctx.strokeStyle = '#677f9d'; ctx.lineWidth = 1.5; ctx.arc(p.x, p.y, 13, 0, Math.PI * 2); ctx.stroke()
        }
        ctx.font = '11px system-ui, "Microsoft YaHei", sans-serif'; ctx.textAlign = 'center'; ctx.textBaseline = 'top'
        for (const node of detail.labelNodes) {
          const p = screen(node, camera), width = Math.min(128, view.width - 24)
          let title = String(node.title || node.label || node.communityId).split(' / ')[0]
          while (title.length > 1 && ctx.measureText(title).width > width - 8) title = title.slice(0, -2) + '…'
          const x = clamp(p.x, width / 2 + 8, view.width - width / 2 - 8), y = p.y + 13
          ctx.fillStyle = '#ffffffdb'; ctx.fillRect(x - width / 2, y - 1, width, 18)
          ctx.fillStyle = '#596b80'; ctx.fillText(title, x, y)
        }
      }
      if (key !== overlayKey) {
        overlayKey = key
        const flow = contextFor(flowCanvas, view, true)
        if (flow) {
          flow.beginPath(); flow.fillStyle = '#7e92a8'
          dust.forEach((dot, i) => { if (i % 17 || dot.x < bounds.left || dot.x > bounds.right || dot.y < bounds.top || dot.y > bounds.bottom) return
            const p = screen(dot, camera); point(flow, p.x, p.y, dot.size + .2) })
          flow.fill()
        }
        twinkleCanvases.forEach((layer, layerIndex) => {
          const glow = contextFor(layer, view, true)
          if (!glow) return
          glow.beginPath(); glow.fillStyle = '#70869e'
          const step = Math.max(1, Math.ceil(detail.painted.length / 28))
          detail.painted.forEach((node, i) => { if (i % step || Math.floor(i / step) % 2 !== layerIndex) return
            const p = screen(node, camera); point(glow, p.x + 4, p.y - 3, 1.3) })
          glow.fill()
        })
      }
      return { visibleCount: detail.visibleCount, paintedCount: detail.painted.length, labelCount: detail.labelNodes.length,
        aggregated: detail.aggregated, decorationCount: dust.length, visibleRelations, paintedRelations,
        relationsLimited: visibleRelations > paintedRelations }
    },
    destroy() {
      destroyed = true; dust = []; candidatesCache = []; lastIndex = null; lastLayout = null
      for (const layer of [canvas, flowCanvas, ...twinkleCanvases]) if (layer) { layer.width = 1; layer.height = 1 }
    },
  }
}
