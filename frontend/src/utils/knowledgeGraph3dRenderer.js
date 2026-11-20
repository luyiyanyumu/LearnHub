import { parseGraphColor } from './knowledgeGraphRenderer.js'

const TAU = Math.PI * 2
const IDLE_DELAY = 3000
const ROTATION_FRAME_MS = 1000 / 30
const MAX_EDGES = 1150
const MAX_FOCUS_EDGES = 300
const MAX_COLORS = 18
const INITIAL = { yaw: -.32, pitch: -.25, zoom: 1, x: 0, y: 0, z: 0, panX: 0, panY: 0 }
const CAMERA_KEYS = Object.keys(INITIAL)
const FONT = 'system-ui, "Microsoft YaHei", "PingFang SC", sans-serif'
const STRONG_FONT = '650 13px ' + FONT
const NORMAL_FONT = '500 11px ' + FONT
const EDGE = [111, 124, 144]
const SELECTED_INK = [68, 83, 132]
const DEPTH_ALPHA = [.55, .71, .85, 1]
const SPRITE_SIZE = 64
const SPRITE_RADIUS = 22
const clamp = (value, min, max) => Math.min(max, Math.max(min, value))
const rgba = (rgb, alpha) => 'rgba(' + rgb.join(',') + ',' + alpha + ')'
const mix = (rgb, other, weight) => rgb.map((value, i) => Math.round(value * (1 - weight) + other[i] * weight))
const number = (value, fallback) => Number.isFinite(value) ? value : fallback
const DASH = [3, 4]
const SOLID = []
const AXIS_RADII = [145, 230]
const CIRCLE = Array.from({ length: 49 }, (_, i) => [Math.cos(i * TAU / 48), Math.sin(i * TAU / 48)])

function colorPalette(nodes, doc, sprites) {
  const colors = [], byColor = new Map()
  const byNode = nodes.map(node => {
    const key = node.color || '#7a879e'
    if (byColor.has(key)) return byColor.get(key)
    const rgb = parseGraphColor(key, [.48, .53, .62, 1]).slice(0, 3).map(value => Math.round(value * 255))
    let index = colors.length
    if (index < MAX_COLORS) {
      const colorKey = rgb.join(',')
      let image = sprites.get(colorKey)
      if (!image) {
        image = drawSphereSprite(rgb, doc)
        if (sprites.size >= MAX_COLORS * 2) sprites.delete(sprites.keys().next().value)
        sprites.set(colorKey, image)
      }
      colors.push({ rgb, image, dot: rgba(mix(rgb, [248, 250, 252], .16), 1) })
    } else {
      // Large overviews can expose thousands of nominal community colors.
      let nearest = Infinity
      for (let i = 0; i < colors.length; i++) {
        const [r, g, b] = colors[i].rgb
        const distance = (rgb[0] - r) ** 2 + (rgb[1] - g) ** 2 + (rgb[2] - b) ** 2
        if (distance < nearest) { nearest = distance; index = i }
      }
    }
    byColor.set(key, index)
    return index
  })
  return { colors, byNode }
}

function drawSphereSprite(rgb, doc) {
  const image = doc.createElement('canvas')
  image.width = image.height = SPRITE_SIZE
  const brush = image.getContext('2d')
  const light = brush.createRadialGradient(25, 23, 2.5, 33, 35, 25)
  light.addColorStop(0, '#ffffff')
  light.addColorStop(.55, rgba(mix(rgb, [245, 246, 249], .83), 1))
  light.addColorStop(1, rgba(mix(rgb, [225, 229, 236], .64), 1))
  brush.beginPath()
  brush.arc(32, 32, SPRITE_RADIUS, 0, TAU)
  brush.fillStyle = light
  brush.shadowColor = 'rgba(55,66,91,.20)'
  brush.shadowBlur = 8
  brush.shadowOffsetY = 4
  brush.fill()
  brush.shadowColor = 'transparent'
  brush.strokeStyle = rgba(rgb, .93)
  brush.lineWidth = 1.7
  brush.stroke()
  return image
}

function prepareGraph(nodes, edges, doc, sprites) {
  const ids = new Map(), rows = []
  for (const node of nodes) {
    if (node?.id == null || ![node.x, node.y, node.z].every(Number.isFinite)) continue
    const id = String(node.id)
    if (ids.has(id)) continue
    ids.set(id, rows.length)
    rows.push(node)
  }
  const { colors, byNode } = colorPalette(rows, doc, sprites)
  const count = rows.length
  const positions = new Float32Array(count * 3)
  const radii = new Float32Array(count), ranks = new Uint8Array(count)
  const nodeIds = [], labels = []
  rows.forEach((node, i) => {
    const rank = clamp(number(node.rank, 1), 1, 3)
    const radius = rank === 3 ? 12.3 : rank === 2 ? 7.4 : 2.55
    positions[i * 3] = node.x
    positions[i * 3 + 1] = node.y
    positions[i * 3 + 2] = node.z
    ranks[i] = rank
    radii[i] = clamp(number(node.size, radius), 1.8, 18)
    nodeIds.push(String(node.id))
    labels.push(String(node.label || '').slice(0, 120))
  })
  const labelOrder = Array.from({ length: count }, (_, i) => i)
    .sort((a, b) => ranks[b] - ranks[a] || radii[b] - radii[a] || a - b)
  const links = [], neighbors = Array.from({ length: count }, () => [])
  for (const edge of edges) {
    const a = ids.get(String(edge?.source)), b = ids.get(String(edge?.target))
    if (a === undefined || b === undefined || a === b) continue
    const derived = edge.origin === 'derived' || (edge.directCount === 0 && edge.derivedCount > 0)
    const weight = number(edge.weight, number(edge.count, 1))
    links.push({ a, b, derived, weight })
  }
  links.sort((a, b) => b.weight - a.weight || Number(a.derived) - Number(b.derived))
  for (const { a, b } of links) {
    neighbors[a].push(b)
    neighbors[b].push(a)
  }
  return {
    ids, nodeIds, labels, positions, radii, ranks, colors, byNode, labelOrder, links, neighbors,
    x: new Float32Array(count), y: new Float32Array(count), depth: new Float32Array(count),
    r: new Float32Array(count), priority: new Float32Array(count), painted: new Uint8Array(count),
    related: new Uint8Array(count), normalLabels: [], strongLabels: [],
  }
}

/** Single white 3D canvas. Topology and sphere sprites are prepared only when the view data changes. */
export function createGraph3dRenderer(canvas, { onSelect, onOpen, onHover, onMotionChange, onMetrics } = {}) {
  const doc = canvas.ownerDocument, win = doc.defaultView
  const ctx = canvas.getContext('2d')
  if (!ctx) return null

  const sprites = new Map()
  let graph = prepareGraph([], [], doc, sprites)
  let width = 0, height = 0, dpr = 1, scale = 1
  let selected = -1, hovered = -1, selectedId = null
  const camera = { ...INITIAL }, target = { ...INITIAL }
  const pointers = new Map(), cleanup = []
  let pinch = 0, pendingHover = null
  let destroyed = false, active = true, inViewport = true, autoRotate = true, reducedMotion = false
  let frame = 0, wake = 0, lastFrame = 0, lastPaint = 0, lastMetrics = -Infinity, metricKey = ''
  let dirty = true, mode = '', idleAt = win.performance.now() + IDLE_DELAY
  let bins = new Int32Array(0), cols = 1, rows = 1, cell = 1
  let paintBudget = 620, visibleCount = 0
  const paintIndices = [], labelRects = []
  const depthBuckets = DEPTH_ALPHA.map(() => ({ dots: [], hubs: [] }))

  function listen(target, event, handler, options) {
    target.addEventListener(event, handler, options)
    cleanup.push(() => target.removeEventListener(event, handler, options))
  }

  function resize() {
    const rect = canvas.getBoundingClientRect()
    const nextWidth = Math.floor(rect.width), nextHeight = Math.floor(rect.height)
    if (!nextWidth || !nextHeight) return
    const nextDpr = clamp(win.devicePixelRatio || 1, 1, 1.75)
    if (width === nextWidth && height === nextHeight && dpr === nextDpr) return
    width = nextWidth
    height = nextHeight
    dpr = nextDpr
    canvas.width = Math.round(width * dpr)
    canvas.height = Math.round(height * dpr)
    scale = Math.min(width / 505, height / 450)
    paintBudget = width < 550 ? 380 : 620
    cell = Math.max(13, Math.sqrt(width * height / (paintBudget * .84)))
    cols = Math.ceil(width / cell)
    rows = Math.ceil(height / cell)
    bins = new Int32Array(cols * rows)
    graph.normalLabels.length = graph.strongLabels.length = 0
    dirty = true
    requestFrame()
  }

  function canDraw() {
    return active && inViewport && !doc.hidden && !destroyed && width > 0 && height > 0
  }

  function requestFrame() {
    if (!frame && canDraw()) frame = win.requestAnimationFrame(tick)
  }

  function stopFrame() {
    if (frame) win.cancelAnimationFrame(frame)
    frame = 0
    lastFrame = 0
    clearWake()
  }

  function clearWake() {
    if (wake) win.clearTimeout(wake)
    wake = 0
  }

  function activity() {
    idleAt = win.performance.now() + IDLE_DELAY
    clearWake()
    requestFrame()
  }

  function updateMode(next) {
    if (mode === next) return
    mode = next
    onMotionChange?.(mode)
  }

  function visibilityChanged() {
    if (!canDraw()) {
      stopFrame()
      clearPointers()
      updateMode('paused')
    } else {
      resize()
      activity()
    }
  }

  function makeProjection() {
    const a = Math.cos(camera.yaw), b = Math.sin(camera.yaw)
    const c = Math.cos(camera.pitch), d = Math.sin(camera.pitch)
    const centerX = width * .5 + camera.panX
    const centerY = height * (width >= 720 ? .435 : .47) + camera.panY
    const zoomScale = scale * camera.zoom
    const { x: shiftX, y: shiftY, z: shiftZ } = camera
    const point = { x: 0, y: 0, depth: 0, perspective: 1 }
    return (x, y, z) => {
      x -= shiftX
      y -= shiftY
      z -= shiftZ
      const horizontal = x * a - z * b
      const yawDepth = x * b + z * a
      const depth = y * d + yawDepth * c
      const perspective = 780 / (780 - clamp(depth, -620, 570))
      const lens = perspective * zoomScale
      point.x = centerX + horizontal * lens
      point.y = centerY + (y * c - yawDepth * d) * lens
      point.depth = depth
      point.perspective = perspective
      return point
    }
  }

  function drawEnvironment(project) {
    ctx.lineWidth = .9
    ctx.setLineDash(SOLID)
    for (const radius of AXIS_RADII) {
      ctx.beginPath()
      CIRCLE.forEach(([x, z], i) => {
        const point = project(radius * x, 145, radius * z)
        if (i === 0) ctx.moveTo(point.x, point.y)
        else ctx.lineTo(point.x, point.y)
      })
      ctx.strokeStyle = radius === 230 ? '#e2e7ee' : '#edf0f4'
      ctx.stroke()
    }
    ctx.beginPath()
    for (let i = 0; i < 12; i++) {
      const [x, z] = CIRCLE[i * 4]
      const from = project(224 * x, 145, 224 * z)
      ctx.moveTo(from.x, from.y)
      const to = project(239 * x, 145, 239 * z)
      ctx.lineTo(to.x, to.y)
    }
    ctx.strokeStyle = '#e3e8ef'
    ctx.stroke()
  }

  function markPainted(i) {
    if (i < 0 || graph.painted[i] || !Number.isFinite(graph.priority[i])) return
    graph.painted[i] = 1
    paintIndices.push(i)
  }

  function projectGraph(project) {
    const { positions, x, y, depth, r, radii, ranks, related, painted, priority } = graph
    const sizeScale = clamp(scale * camera.zoom, .63, 1.65)
    const dense = x.length > paintBudget
    painted.fill(0)
    if (dense) bins.fill(-1)
    visibleCount = 0
    paintIndices.length = 0
    for (let i = 0, j = 0; i < x.length; i++, j += 3) {
      const point = project(positions[j], positions[j + 1], positions[j + 2])
      const prominent = i === selected || i === hovered
      const radius = Math.max(prominent ? 4.8 : 1.6, radii[i] * point.perspective * sizeScale)
      x[i] = point.x
      y[i] = point.y
      depth[i] = point.depth
      r[i] = radius
      if (point.x < -radius || point.x > width + radius || point.y < -radius || point.y > height + radius) {
        priority[i] = -Infinity
        continue
      }
      visibleCount++
      priority[i] = (prominent ? 1000 : related[i] ? 120 : 0) + ranks[i] * 10 + radius + point.depth * .008
      if (!dense) markPainted(i)
      else {
        const column = clamp(Math.floor(point.x / cell), 0, cols - 1)
        const row = clamp(Math.floor(point.y / cell), 0, rows - 1)
        const bin = row * cols + column
        const previous = bins[bin]
        if (previous < 0 || priority[i] > priority[previous]) bins[bin] = i
      }
    }
    if (dense) {
      for (const i of bins) markPainted(i)
      // A dense bin may contain both the selected and pointer-hovered nodes.
      markPainted(selected)
      markPainted(hovered)
      paintRelationalDetail()
    }
  }

  function paintRelationalDetail() {
    // One point per bin can hide both ends of almost every link. Reserve some
    // detail for actual connected pairs, while keeping the entire scene indexed.
    const { links, priority, painted, neighbors } = graph
    const limit = Math.min(paintBudget, paintIndices.length + Math.floor(paintBudget * .42))
    if (selected >= 0 && painted[selected]) {
      for (const i of neighbors[selected]) {
        if (paintIndices.length >= limit) return
        markPainted(i)
      }
    }
    for (const { a, b } of links) {
      if (paintIndices.length >= limit) break
      if (!Number.isFinite(priority[a]) || !Number.isFinite(priority[b])) continue
      if (paintIndices.length + (1 - painted[a]) + (1 - painted[b]) > limit) continue
      markPainted(a)
      markPainted(b)
    }
  }

  function drawEdges() {
    const { links, painted, x, y } = graph
    let total = 0
    for (let focused = 0; focused < 2; focused++) {
      for (let derived = 0; derived < 2; derived++) {
        let count = 0
        const limit = focused ? MAX_FOCUS_EDGES / 2 : MAX_EDGES / 2
        ctx.beginPath()
        for (const link of links) {
          const { a, b } = link
          if (!painted[a] || !painted[b] || link.derived !== Boolean(derived)
            || (a === selected || b === selected) !== Boolean(focused)) continue
          ctx.moveTo(x[a], y[a])
          ctx.lineTo(x[b], y[b])
          if (++count >= limit) break
        }
        if (!count) continue
        ctx.setLineDash(derived ? DASH : SOLID)
        ctx.strokeStyle = focused ? rgba(SELECTED_INK, derived ? .59 : .73) : rgba(EDGE, derived ? .23 : .32)
        ctx.lineWidth = focused ? 1.2 : .78
        ctx.stroke()
        total += count
      }
    }
    ctx.setLineDash(SOLID)
    return total
  }

  function drawNodes() {
    const { colors, byNode, x, y, depth, r, ranks } = graph
    for (const bucket of depthBuckets) {
      bucket.hubs.length = 0
      bucket.dots.forEach(dots => { dots.length = 0 })
    }
    for (const i of paintIndices) {
      const bucket = depthBuckets[clamp(Math.floor((depth[i] + 260) / 130), 0, depthBuckets.length - 1)]
      if (ranks[i] >= 2 || i === selected || i === hovered) bucket.hubs.push(i)
      else (bucket.dots[byNode[i]] ??= []).push(i)
    }
    depthBuckets.forEach(({ dots, hubs }, band) => {
      ctx.globalAlpha = DEPTH_ALPHA[band]
      dots.forEach((indices, color) => {
        if (!indices.length) return
        ctx.beginPath()
        for (const i of indices) {
          ctx.moveTo(x[i] + r[i], y[i])
          ctx.arc(x[i], y[i], r[i], 0, TAU)
        }
        ctx.fillStyle = colors[color].dot
        ctx.fill()
      })
      for (const i of hubs) {
        const size = r[i] * SPRITE_SIZE / SPRITE_RADIUS
        ctx.drawImage(colors[byNode[i]].image, x[i] - size / 2, y[i] - size / 2, size, size)
      }
    })
    ctx.globalAlpha = 1
    for (const i of hovered === selected ? [selected] : [selected, hovered]) {
      if (i < 0 || !graph.painted[i]) continue
      ctx.beginPath()
      ctx.arc(x[i], y[i], r[i] + (i === selected ? 4.5 : 3), 0, TAU)
      ctx.strokeStyle = rgba(SELECTED_INK, i === selected ? .72 : .46)
      ctx.lineWidth = 1
      ctx.stroke()
    }
  }

  function drawLabel(i, strong) {
    if (i < 0 || !graph.painted[i] || !graph.labels[i]) return
    const { x, y, r, labels, normalLabels, strongLabels } = graph
    const nodeX = x[i], nodeY = y[i]
    const font = strong ? STRONG_FONT : NORMAL_FONT
    const padding = strong ? 10 : 6, boxHeight = strong ? 27 : 20
    ctx.font = font
    const cache = strong ? strongLabels : normalLabels
    if (!cache[i]) {
      const maxWidth = Math.max(32, Math.min(180, width * .55)) - padding * 2
      let label = labels[i], measured = ctx.measureText(label).width
      if (measured > maxWidth) {
        const characters = Array.from(label)
        let low = 0, high = characters.length - 1
        while (low < high) {
          const mid = Math.ceil((low + high) / 2)
          if (ctx.measureText(characters.slice(0, mid).join('') + '…').width <= maxWidth) low = mid
          else high = mid - 1
        }
        label = characters.slice(0, low).join('') + '…'
        measured = ctx.measureText(label).width
      }
      cache[i] = { label, width: measured + padding * 2 }
    }
    const { label, width: boxWidth } = cache[i]
    const gap = r[i] + (strong ? 10 : 5)
    const places = [[nodeX + gap, nodeY - boxHeight - 5], [nodeX - gap - boxWidth, nodeY - boxHeight - 5],
      [nodeX + gap, nodeY + 5], [nodeX - gap - boxWidth, nodeY + 5]]
    let place = places.find(([left, top]) => left >= 8 && top >= 8 && left + boxWidth <= width - 8 && top + boxHeight <= height - 8
      && !labelRects.some(([x1, y1, x2, y2]) => left < x2 + 3 && left + boxWidth > x1 - 3 && top < y2 + 3 && top + boxHeight > y1 - 3))
    if (!place && !strong) return
    if (!place) place = [clamp(nodeX + gap, 8, width - boxWidth - 8), clamp(nodeY - boxHeight - 5, 8, height - boxHeight - 8)]
    const [left, top] = place
    labelRects.push([left, top, left + boxWidth, top + boxHeight])
    ctx.beginPath()
    ctx.roundRect(left, top, boxWidth, boxHeight, strong ? 6 : 4)
    ctx.fillStyle = '#ffffff'
    ctx.fill()
    ctx.strokeStyle = strong ? '#9caac7' : '#dbe3ed'
    ctx.lineWidth = 1
    ctx.stroke()
    ctx.textBaseline = 'middle'
    ctx.fillStyle = strong ? '#2d3c61' : '#45546d'
    ctx.fillText(label, left + padding, top + boxHeight / 2 + .4)
  }

  function drawLabels() {
    labelRects.length = 0
    drawLabel(selected, true)
    if (hovered !== selected) drawLabel(hovered, true)
    const limit = pointers.size ? 4 : width < 600 ? 7 : 12
    let examined = 0
    for (const i of graph.labelOrder) {
      if (labelRects.length >= limit || ++examined > 75) break
      if (i !== selected && i !== hovered && (graph.ranks[i] >= 2 || labelRects.length < 3)) drawLabel(i, false)
    }
  }

  function draw(now, settled) {
    ctx.setTransform(dpr, 0, 0, dpr, 0, 0)
    ctx.clearRect(0, 0, width, height)
    const project = makeProjection()
    drawEnvironment(project)
    projectGraph(project)
    const paintedEdges = drawEdges()
    drawNodes()
    drawLabels()
    const zoomPercent = Math.round(camera.zoom * 100)
    const nextKey = [graph.x.length, visibleCount, paintIndices.length, graph.links.length, paintedEdges, zoomPercent].join('/')
    if (onMetrics && nextKey !== metricKey && (settled || now - lastMetrics >= 240)) {
      metricKey = nextKey
      lastMetrics = now
      onMetrics({ totalNodes: graph.x.length, visibleNodes: visibleCount, paintedNodes: paintIndices.length,
        totalEdges: graph.links.length, paintedEdges, zoomPercent })
    }
  }

  function tick(now) {
    frame = 0
    if (!canDraw()) return
    const elapsed = lastFrame ? clamp(now - lastFrame, 0, 45) : 16.67
    lastFrame = now
    const rotating = autoRotate && !reducedMotion && !pointers.size && now >= idleAt
    updateMode(rotating ? 'rotating' : autoRotate && !reducedMotion ? 'idle' : 'paused')
    if (rotating) target.yaw += elapsed * .00019
    const ease = reducedMotion ? 1 : 1 - .76 ** (elapsed / 16.67)
    let moved = false, easing = false
    for (const key of CAMERA_KEYS) {
      const delta = target[key] - camera[key]
      if (!delta) continue
      camera[key] += Math.abs(delta) <= .002 ? delta : delta * ease
      moved = true
      easing ||= Math.abs(target[key] - camera[key]) > .002
    }
    if (pendingHover && !pointers.size) {
      setHover(pick(pendingHover.x, pendingHover.y))
      pendingHover = null
    }
    if ((dirty || moved) && (dirty || !rotating || now - lastPaint >= ROTATION_FRAME_MS - 1)) {
      draw(now, !rotating && !easing && !pointers.size)
      lastPaint = now
      dirty = false
    }
    if (rotating || easing || dirty) requestFrame()
    else if (autoRotate && !reducedMotion && !pointers.size && !wake) {
      lastFrame = 0
      wake = win.setTimeout(() => { wake = 0; requestFrame() }, Math.max(1, idleAt - now))
    } else lastFrame = 0
  }

  function setHover(index) {
    if (index === hovered) return
    hovered = index
    dirty = true
    canvas.style.cursor = index >= 0 ? 'pointer' : ''
    onHover?.(index >= 0 ? graph.nodeIds[index] : null)
  }

  function pick(clientX, clientY) {
    const rect = canvas.getBoundingClientRect()
    const x = clientX - rect.left, y = clientY - rect.top
    let result = -1, score = Infinity
    for (const i of paintIndices) {
      const dx = x - graph.x[i], dy = y - graph.y[i]
      const distance = dx * dx + dy * dy
      const hit = Math.max(8, graph.r[i] + 4)
      if (distance > hit * hit) continue
      const next = distance / (hit * hit) - graph.depth[i] * .00065
      if (next < score) { score = next; result = i }
    }
    return result
  }

  function clearPointers() {
    const ids = [...pointers.keys()]
    pointers.clear()
    for (const id of ids) if (canvas.hasPointerCapture?.(id)) canvas.releasePointerCapture(id)
    pendingHover = null
    canvas.style.cursor = ''
  }

  function pointerDistance() {
    const [a, b] = pointers.values()
    return Math.hypot(a.x - b.x, a.y - b.y)
  }

  function zoomBy(factor) {
    target.zoom = clamp(target.zoom * number(factor, 1), .55, 4)
    activity()
  }

  listen(canvas, 'pointerdown', event => {
    if (event.pointerType === 'mouse' && event.button !== 0 || pointers.size >= 2) return
    activity()
    setHover(-1)
    pointers.set(event.pointerId, { x: event.clientX, y: event.clientY, moved: 0, pan: event.shiftKey })
    canvas.setPointerCapture?.(event.pointerId)
    canvas.style.cursor = 'grabbing'
    if (pointers.size === 2) {
      pinch = pointerDistance()
      for (const pointer of pointers.values()) pointer.moved = 100
    }
  })
  listen(canvas, 'pointermove', event => {
    activity()
    const pointer = pointers.get(event.pointerId)
    if (!pointer) {
      if (!pointers.size && event.pointerType !== 'touch') pendingHover = { x: event.clientX, y: event.clientY }
      return
    }
    const dx = event.clientX - pointer.x, dy = event.clientY - pointer.y
    pointer.x = event.clientX
    pointer.y = event.clientY
    pointer.moved += Math.abs(dx) + Math.abs(dy)
    if (pointers.size === 2) {
      const next = pointerDistance()
      if (pinch > 0) zoomBy(next / pinch)
      pinch = next
    } else if (pointer.pan) {
      target.panX += dx
      target.panY += dy
    } else {
      target.yaw -= dx * .006
      target.pitch = clamp(target.pitch + dy * .006, -1.24, 1.24)
    }
  })
  function release(event) {
    const pointer = pointers.get(event.pointerId)
    if (!pointer) return
    pointers.delete(event.pointerId)
    if (!pointers.size) canvas.style.cursor = ''
    dirty = true
    activity()
    if (event.type !== 'pointerup' || pointers.size || pointer.moved >= 5) return
    const i = pick(event.clientX, event.clientY)
    onSelect?.(i >= 0 ? graph.nodeIds[i] : null)
  }
  for (const event of ['pointerup', 'pointercancel', 'lostpointercapture']) listen(canvas, event, release)
  listen(canvas, 'pointerleave', () => { pendingHover = null; if (!pointers.size) { setHover(-1); requestFrame() } })
  listen(canvas, 'dblclick', event => {
    const i = pick(event.clientX, event.clientY)
    if (i >= 0) onOpen?.(graph.nodeIds[i])
  })
  listen(canvas, 'wheel', event => {
    event.preventDefault()
    const delta = clamp(event.deltaY, -260, 260)
    zoomBy(Math.exp(-delta * (event.deltaMode === 1 ? .019 : .00165)))
  }, { passive: false })
  listen(doc, 'visibilitychange', visibilityChanged)
  const resizeObserver = typeof win.ResizeObserver === 'function' ? new win.ResizeObserver(resize) : null
  if (resizeObserver) resizeObserver.observe(canvas)
  else listen(win, 'resize', resize)
  const visibilityObserver = typeof win.IntersectionObserver === 'function' ? new win.IntersectionObserver(entries => {
    inViewport = entries.some(entry => entry.isIntersecting)
    visibilityChanged()
  }) : null
  visibilityObserver?.observe(canvas)

  function updateSelection() {
    selected = graph.ids.get(selectedId) ?? -1
    graph.related.fill(0)
    if (selected >= 0) for (const i of graph.neighbors[selected]) graph.related[i] = 1
  }

  resize()
  return {
    setGraph({ nodes = [], edges = [] } = {}) {
      graph = prepareGraph(nodes, edges, doc, sprites)
      setHover(-1)
      updateSelection()
      paintIndices.length = 0
      metricKey = ''
      lastMetrics = -Infinity
      dirty = true
      activity()
    },
    setSelected(id) {
      const next = id == null || id === '' ? null : String(id)
      if (next === selectedId) return
      selectedId = next
      updateSelection()
      dirty = true
      activity()
    },
    setActive(value) {
      if (active === Boolean(value)) return
      active = Boolean(value)
      visibilityChanged()
    },
    setAutoRotate(value) { autoRotate = Boolean(value); activity() },
    setReducedMotion(value) { reducedMotion = Boolean(value); activity() },
    resetView() { Object.assign(target, INITIAL); activity() },
    focusNode(id) {
      const i = graph.ids.get(String(id))
      if (i === undefined) return false
      const j = i * 3
      Object.assign(target, { x: graph.positions[j], y: graph.positions[j + 1], z: graph.positions[j + 2],
        zoom: Math.max(target.zoom, 1.36), panX: 0, panY: 0 })
      activity()
      return true
    },
    zoomBy,
    destroy() {
      destroyed = true
      stopFrame()
      for (const remove of cleanup) remove()
      resizeObserver?.disconnect()
      visibilityObserver?.disconnect()
      clearPointers()
    },
  }
}
