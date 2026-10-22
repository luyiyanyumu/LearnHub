// Large overviews retain the complete topology without an O(N²) animation on the
// UI thread. A selected neighborhood already has its own settled ring layout.
export const GRAPH_SIMULATION_NODE_LIMIT = 400

/** Pack large overviews in one pass instead of testing thousands of spiral candidates per group. */
function packOverviewGroups(groups, aspectRatio) {
  const gap = 24
  const ratio = Math.max(0.5, Math.min(3, aspectRatio))
  const area = groups.reduce((sum, group) => sum + (group.radius * 2 + gap) ** 2, 0)
  const widest = groups.reduce((width, group) => Math.max(width, group.radius * 2), 0)
  const rowWidth = Math.max(widest, Math.sqrt(area * ratio))
  let x = 0, y = 0, rowHeight = 0, width = 0
  const packed = []
  for (const group of groups) {
    const diameter = group.radius * 2
    if (x && x + diameter > rowWidth) { x = 0; y += rowHeight + gap; rowHeight = 0 }
    packed.push({ ...group, x: x + group.radius, y: y + group.radius })
    width = Math.max(width, x + diameter)
    x += diameter + gap
    rowHeight = Math.max(rowHeight, diameter)
  }
  const height = y + rowHeight
  for (const group of packed) { group.x -= width / 2; group.y -= height / 2 }
  return packed
}

/** Deterministic world coordinates: the viewport fits the graph, never clamps its nodes. */
export function createGraphLayout(nodes = [], edges = [], communities = {}, { aspectRatio = 1.8, rootId = '' } = {}) {
  const sorted = [...nodes].sort((a, b) => String(a.id).localeCompare(String(b.id)))
  const byId = new Map(sorted.map((n) => [n.id, n]))
  const adjacency = new Map(sorted.map((n) => [n.id, []]))
  for (const e of edges) {
    if (!byId.has(e.source) || !byId.has(e.target) || e.source === e.target) continue
    adjacency.get(e.source).push(e.target)
    adjacency.get(e.target).push(e.source)
  }
  if (byId.has(rootId)) return createNeighborhoodLayout(sorted, adjacency, communities, rootId, aspectRatio)
  const componentOf = new Map()
  for (const n of sorted) {
    if (componentOf.has(n.id)) continue
    const queue = [n.id]
    componentOf.set(n.id, n.id)
    for (let i = 0; i < queue.length; i++) {
      for (const id of adjacency.get(queue[i])) {
        if (componentOf.has(id)) continue
        componentOf.set(id, n.id)
        queue.push(id)
      }
    }
  }
  const grouped = new Map()
  for (const n of sorted) {
    const community = communities[n.id]
    const key = n.type === 'concept' && community != null
      ? `community:${community}` : `component:${componentOf.get(n.id)}`
    if (!grouped.has(key)) grouped.set(key, [])
    grouped.get(key).push(n)
  }
  const groups = [...grouped].map(([id, members]) => ({
    id, members, radius: members.length === 1 ? 20 : Math.sqrt(members.length) * 26 + 12,
  })).sort((a, b) => b.members.length - a.members.length || a.id.localeCompare(b.id))
  const staticOverview = sorted.length > GRAPH_SIMULATION_NODE_LIMIT
  const packed = staticOverview ? packOverviewGroups(groups, aspectRatio) : []
  const goldenAngle = Math.PI * (3 - Math.sqrt(5))
  const stretch = Math.sqrt(Math.max(0.5, Math.min(3, aspectRatio)))
  const anchors = new Map(), positions = new Map(), groupOf = new Map()
  for (const group of staticOverview ? packed : groups) {
    let x = staticOverview ? group.x : 0, y = staticOverview ? group.y : 0
    // A compact spiral packs small disconnected groups without creating border rows.
    if (!staticOverview && packed.length) {
      for (let candidate = 1; candidate <= 12000; candidate++) {
        const distance = Math.sqrt(candidate) * 18
        x = Math.cos(candidate * goldenAngle) * distance * stretch
        y = Math.sin(candidate * goldenAngle) * distance / stretch
        if (packed.every((p) => Math.hypot(x - p.x, y - p.y) >= group.radius + p.radius + 18)) break
      }
    }
    if (!staticOverview) packed.push({ ...group, x, y })
    group.members.forEach((n, index) => {
      const distance = group.members.length === 1 ? 0 : Math.sqrt(index + 0.5) * 23
      const angle = index * goldenAngle
      anchors.set(n.id, { x, y })
      groupOf.set(n.id, group.id)
      positions.set(n.id, { x: x + Math.cos(angle) * distance, y: y + Math.sin(angle) * distance, vx: 0, vy: 0 })
    })
  }
  return { positions, anchors, groupOf, groups: packed, adjacency,
    fixed: staticOverview, mode: staticOverview ? 'overview' : 'simulation' }
}

/** Neighborhoods use stable hop rings, not the inherited shape of their communities. */
function createNeighborhoodLayout(nodes, adjacency, communities, rootId, aspectRatio) {
  const depth = new Map([[rootId, 0]]), queue = [rootId]
  for (let i = 0; i < queue.length; i++) {
    for (const id of adjacency.get(queue[i])) {
      if (depth.has(id)) continue
      depth.set(id, depth.get(queue[i]) + 1)
      queue.push(id)
    }
  }
  const disconnectedDepth = Math.max(...depth.values()) + 1
  const rings = new Map(), positions = new Map([[rootId, { x: 0, y: 0, vx: 0, vy: 0 }]])
  for (const node of nodes) {
    if (node.id === rootId) continue
    // Relationship filters can leave former neighbors disconnected. Keep them visible
    // on an outer ring without inventing an edge or a hop distance to the root.
    const ring = depth.get(node.id) ?? disconnectedDepth
    if (!rings.has(ring)) rings.set(ring, [])
    rings.get(ring).push(node)
  }
  const stretch = Math.sqrt(Math.max(0.65, Math.min(2.2, aspectRatio)))
  let radius = 0
  for (const [hop, members] of [...rings].sort(([a], [b]) => a - b)) {
    members.sort((a, b) => String(communities[a.id] ?? '').localeCompare(String(communities[b.id] ?? '')) || String(a.id).localeCompare(String(b.id)))
    radius = Math.max(radius + 160, members.length * 92 / (2 * Math.PI))
    members.forEach((node, index) => {
      const angle = 2 * Math.PI * index / members.length + (members.length <= 2 ? 0 : -Math.PI / 2 + Math.PI / members.length + (hop - 1) * 0.12)
      positions.set(node.id, { x: Math.cos(angle) * radius * stretch, y: Math.sin(angle) * radius / stretch, vx: 0, vy: 0 })
    })
  }
  return { positions, adjacency, fixed: true, mode: 'neighborhood', rootId }
}

/** Reset on navigation/topology changes; identical refreshes preserve manual placement. */
export function reconcileGraphLayout(nodes, edges, communities, states, previous, options = {}) {
  const { rootId = '', aspectRatio = 1.8 } = options
  const key = JSON.stringify([
    rootId, rootId ? Math.round(aspectRatio * 100) : null,
    nodes.map(n => [n.id, n.type, communities[n.id] ?? null]).sort((a, b) => String(a[0]).localeCompare(String(b[0]))),
    edges.map(e => JSON.stringify([e.source, e.target, e.kind])).sort(),
  ])
  if (previous?.key === key) return previous
  const layout = createGraphLayout(nodes, edges, communities, options)
  layout.key = key
  states.clear()
  for (const [id, position] of layout.positions) states.set(id, { ...position })
  return layout
}

export function stepGraphLayout(nodes, edges, states, layout, { alpha = 1, pinnedId = '' } = {}) {
  // A ring is already settled; springs would collapse it and make results history-dependent.
  if (layout.fixed) return
  for (let i = 0; i < nodes.length; i++) {
    const a = states.get(nodes[i].id)
    if (!a) continue
    for (let j = i + 1; j < nodes.length; j++) {
      const b = states.get(nodes[j].id)
      if (!b) continue
      let dx = a.x - b.x, dy = a.y - b.y
      if (Math.abs(dx) + Math.abs(dy) < 0.01) { dx = Math.cos((i + j) * 2.4); dy = Math.sin((i + j) * 2.4) }
      const distance = Math.max(1, Math.hypot(dx, dy))
      const separation = (nodes[i].r || 8) + (nodes[j].r || 8) + 18
      const force = (Math.min(9, 2200 / (distance * distance)) + Math.max(0, separation - distance) * 0.15) * alpha
      const fx = dx / distance * force, fy = dy / distance * force
      a.vx += fx; a.vy += fy
      b.vx -= fx; b.vy -= fy
    }
    const anchor = layout.anchors.get(nodes[i].id)
    if (anchor) {
      a.vx += (anchor.x - a.x) * 0.012 * alpha
      a.vy += (anchor.y - a.y) * 0.012 * alpha
    }
  }
  for (const edge of edges) {
    const a = states.get(edge.source), b = states.get(edge.target)
    if (!a || !b || edge.source === edge.target) continue
    const dx = b.x - a.x, dy = b.y - a.y
    const distance = Math.max(1, Math.hypot(dx, dy))
    const sameGroup = layout.groupOf.get(edge.source) === layout.groupOf.get(edge.target)
    const sourceAnchor = layout.anchors.get(edge.source), targetAnchor = layout.anchors.get(edge.target)
    const target = sameGroup ? (edge.kind === 'semantic' ? 115 : 85)
      : Math.max(150, Math.hypot(targetAnchor.x - sourceAnchor.x, targetAnchor.y - sourceAnchor.y))
    const force = (distance - target) * (sameGroup ? 0.025 : 0.0015) * alpha
    const fx = dx / distance * force, fy = dy / distance * force
    a.vx += fx; a.vy += fy
    b.vx -= fx; b.vy -= fy
  }
  for (const node of nodes) {
    const state = states.get(node.id)
    if (!state) continue
    if (node.id === pinnedId) { state.vx = 0; state.vy = 0; continue }
    state.vx *= 0.76; state.vy *= 0.76
    state.x += Math.max(-10, Math.min(10, state.vx))
    state.y += Math.max(-10, Math.min(10, state.vy))
  }
}

export function fitGraphBounds(nodes, width, height, { padding = 64, maxScale = 1.5 } = {}) {
  if (!nodes.length || width <= 0 || height <= 0) return { x: 0, y: 0, k: 1 }
  const minX = Math.min(...nodes.map((n) => n.x - (n.r || 8)))
  const maxX = Math.max(...nodes.map((n) => n.x + (n.r || 8)))
  const minY = Math.min(...nodes.map((n) => n.y - (n.r || 8)))
  const maxY = Math.max(...nodes.map((n) => n.y + (n.r || 8)))
  const k = Math.max(0.001, Math.min(maxScale, Math.max(1, width - padding * 2) / Math.max(1, maxX - minX), Math.max(1, height - padding * 2) / Math.max(1, maxY - minY)))
  return { k, x: width / 2 - (minX + maxX) / 2 * k, y: height / 2 - (minY + maxY) / 2 * k }
}

function overlaps(a, b) {
  return a.left < b.right + 4 && a.right + 4 > b.left && a.top < b.bottom + 3 && a.bottom + 3 > b.top
}

/** Screen-space buckets let label candidates test nearby circles instead of the entire graph. */
function indexLabelObstacles(nodes, scale) {
  const cellSize = 64, cells = new Map()
  for (const node of nodes) {
    const radius = (node.r || 8) * scale + 2
    const obstacle = { id: node.id, left: node.sx - radius, right: node.sx + radius,
      top: node.sy - radius, bottom: node.sy + radius }
    for (let x = Math.floor(obstacle.left / cellSize); x <= Math.floor(obstacle.right / cellSize); x++) {
      for (let y = Math.floor(obstacle.top / cellSize); y <= Math.floor(obstacle.bottom / cellSize); y++) {
        const key = `${x}:${y}`
        if (!cells.has(key)) cells.set(key, [])
        cells.get(key).push(obstacle)
      }
    }
  }
  return (candidate, ownId) => {
    const visited = new Set()
    // overlaps() includes a four/three pixel safety margin around each box.
    for (let x = Math.floor((candidate.left - 4) / cellSize); x <= Math.floor((candidate.right + 4) / cellSize); x++) {
      for (let y = Math.floor((candidate.top - 3) / cellSize); y <= Math.floor((candidate.bottom + 3) / cellSize); y++) {
        for (const obstacle of cells.get(`${x}:${y}`) || []) {
          if (obstacle.id === ownId || visited.has(obstacle)) continue
          visited.add(obstacle)
          if (overlaps(candidate, obstacle)) return true
        }
      }
    }
    return false
  }
}

/** Place labels in screen space so zooming out never turns them into tiny overlapping text. */
export function placeGraphLabels(nodes, view, { focusId = '', neighborIds = new Set(), maxLabels = 28 } = {}) {
  const visible = nodes.map((n) => ({ ...n, sx: view.x + n.x * view.k, sy: view.y + n.y * view.k }))
    .filter((n) => n.sx > 10 && n.sy > 10 && n.sx < view.w - 10 && n.sy < view.h - 10)
  const priority = (n) => n.id === focusId ? 3 : neighborIds.has(n.id) ? 2 : n.type === 'category' ? 1 : 0
  const ranked = [...visible].sort((a, b) => {
    return priority(b) - priority(a) || (b.degree || 0) - (a.degree || 0) || String(a.id).localeCompare(String(b.id))
  })
  const hitsNode = indexLabelObstacles(visible, view.k)
  const occupied = [], labels = []
  for (const n of ranked) {
    if (labels.length >= maxLabels) break
    if (nodes.length > 30 && !focusId && n.type !== 'category' && !(n.degree > 1)) continue
    if (focusId && n.id !== focusId && !neighborIds.has(n.id)) continue
    const raw = String(n.label || n.id)
    const limit = n.id === focusId ? 36 : neighborIds.has(n.id) ? 22 : 16
    const text = raw.length > limit ? `${raw.slice(0, limit)}…` : raw
    const width = Math.min(view.w - 24, [...text].reduce((sum, char) => sum + (/[^\u0000-\u00ff]/.test(char) ? 12 : 6.7), 14))
    const radius = (n.r || 8) * view.k
    const candidate = (left, top, callout = false) => ({ left, top, right: left + width, bottom: top + 20, callout })
    const candidates = [
      candidate(n.sx - width / 2, n.sy + radius + 6),
      candidate(n.sx - width / 2, n.sy - radius - 26),
      candidate(n.sx + radius + 7, n.sy - 10),
      candidate(n.sx - radius - width - 7, n.sy - 10),
      // Dense hubs need an external caption; a leader line keeps the name tied to
      // its node without covering nearby circles or dropping the important label.
      ...((n.degree >= 4 || n.id === focusId || neighborIds.has(n.id)) ? [32, 56, 88].flatMap(offset => [
        candidate(n.sx - width / 2, n.sy + radius + offset, true),
        candidate(n.sx - width / 2, n.sy - radius - 20 - offset, true),
        candidate(n.sx + radius + offset, n.sy - 10, true),
        candidate(n.sx - radius - width - offset, n.sy - 10, true),
      ]) : []),
    ]
    const inBounds = (box) => box.left > 6 && box.right < view.w - 6 && box.top > 6 && box.bottom < view.h - 54
    let box = candidates.find((candidate) => inBounds(candidate) && occupied.every((p) => !overlaps(candidate, p)) && !hitsNode(candidate, n.id))
    if (!box && n.id === focusId) box = candidates.find(inBounds)
    if (!box) continue
    occupied.push(box)
    labels.push({ id: n.id, text, strong: n.id === focusId || n.type === 'category',
      leader: box.callout ? { x1: n.x, y1: n.y,
        x2: (Math.max(box.left, Math.min(box.right, n.sx)) - view.x) / view.k,
        y2: (Math.max(box.top, Math.min(box.bottom, n.sy)) - view.y) / view.k } : null,
      x: (box.left - view.x) / view.k, y: (box.top - view.y) / view.k, width: width / view.k, height: 20 / view.k, fontSize: 12 / view.k,
      screenBox: box })
  }
  return labels
}
