export const UNASSIGNED_COMMUNITY_ID = '__unassigned__'

const compare = (a, b) => String(a) < String(b) ? -1 : String(a) > String(b) ? 1 : 0
const membership = (communityOf, id) => communityOf instanceof Map
  ? communityOf.get(id) : Object.hasOwn(communityOf || {}, id) ? communityOf[id] : undefined
const communityKey = value => value == null || value === '' ? UNASSIGNED_COMMUNITY_ID : String(value)
const starId = key => `community:${encodeURIComponent(key)}`
const nodeTitle = node => String(node.label || node.name || node.id)
const rankMembers = (a, b) => (Number(b.degree) || 0) - (Number(a.degree) || 0) || compare(a.id, b.id)

/**
 * Aggregate only the supplied (already filtered) graph. Pagination limits the
 * stars being drawn, never the set of communities available to exploration.
 * Edge counting is one pass and only visible pairs allocate link objects.
 */
export function buildCommunityOverview(nodes = [], edges = [], communityOf = {}, groups = [], { maxCommunities = 36, page = 0, allCommunities = false } = {}) {
  const metadata = new Map(groups.map(group => [communityKey(group.communityId), group]))
  const byNode = new Map(), buckets = new Map()
  for (const node of nodes) {
    if (node?.id == null || byNode.has(node.id)) continue
    const raw = membership(communityOf, node.id)
    const key = communityKey(raw)
    if (!buckets.has(key)) buckets.set(key, {
      id: starId(key), communityId: key === UNASSIGNED_COMMUNITY_ID ? UNASSIGNED_COMMUNITY_ID : raw,
      unassigned: key === UNASSIGNED_COMMUNITY_ID, nodeIds: [], count: 0, size: 0,
      internalEdgeCount: 0, crossEdgeCount: 0, directEdgeCount: 0, derivedEdgeCount: 0,
      internalDirectEdgeCount: 0, internalDerivedEdgeCount: 0,
      crossDirectEdgeCount: 0, crossDerivedEdgeCount: 0, _leaders: [],
    })
    const bucket = buckets.get(key)
    // Numeric and string forms address the same backend community. Prefer the
    // numeric representation when mixed callers supply both forms.
    if (typeof raw === 'number') bucket.communityId = raw
    bucket.nodeIds.push(node.id)
    bucket.count++; bucket.size++
    bucket._leaders.push(node)
    bucket._leaders.sort(rankMembers)
    if (bucket._leaders.length > 2) bucket._leaders.length = 2
    byNode.set(node.id, bucket)
  }
  const communities = [...buckets.values()].map(bucket => {
    const group = metadata.get(communityKey(bucket.communityId))
    const title = bucket.unassigned ? '未分组概念'
      : group?.title || bucket._leaders.map(nodeTitle).join(' / ') || `社区 #${bucket.communityId}`
    delete bucket._leaders
    bucket.nodeIds.sort(compare)
    return { ...bucket, label: title, title }
  }).sort((a, b) => b.count - a.count || compare(a.id, b.id))
  // Use the final public objects for the edge counters as well.
  const publicById = new Map(communities.map(community => [community.id, community]))
  for (const [id, community] of byNode) byNode.set(id, publicById.get(community.id))
  const limit = allCommunities ? Math.max(1, communities.length)
    : Math.max(1, Math.min(96, Math.floor(Number(maxCommunities)) || 36))
  const pageCount = Math.ceil(communities.length / limit)
  const activePage = Math.max(0, Math.min(Math.max(0, pageCount - 1), Math.floor(Number(page)) || 0))
  const visible = communities.slice(activePage * limit, (activePage + 1) * limit)
  const visibleIds = new Set(visible.map(community => community.id))
  const links = new Map()
  let totalInternalEdges = 0, totalCrossEdges = 0, ignoredEdges = 0
  for (const edge of edges) {
    const source = byNode.get(edge.source), target = byNode.get(edge.target)
    if (!source || !target) { ignoredEdges++; continue }
    const derived = edge.origin === 'derived'
    const countKey = derived ? 'derivedEdgeCount' : 'directEdgeCount'
    if (source.id === target.id) {
      source.internalEdgeCount++; source[countKey]++
      source[derived ? 'internalDerivedEdgeCount' : 'internalDirectEdgeCount']++
      totalInternalEdges++
      continue
    }
    totalCrossEdges++
    for (const community of [source, target]) {
      community.crossEdgeCount++; community[countKey]++
      community[derived ? 'crossDerivedEdgeCount' : 'crossDirectEdgeCount']++
    }
    if (!visibleIds.has(source.id) || !visibleIds.has(target.id)) continue
    const forward = compare(source.id, target.id) < 0
    const from = forward ? source.id : target.id, to = forward ? target.id : source.id
    const key = JSON.stringify([from, to])
    if (!links.has(key)) links.set(key, { id: `link:${key}`, source: from, target: to,
      count: 0, weight: 0, directCount: 0, derivedCount: 0, forwardCount: 0, backwardCount: 0 })
    const link = links.get(key)
    link.count++; link.weight++
    link[derived ? 'derivedCount' : 'directCount']++
    link[forward ? 'forwardCount' : 'backwardCount']++
  }
  const visibleEdges = [...links.values()].sort((a, b) => b.weight - a.weight || compare(a.id, b.id))
  return { communities, nodes: visible, edges: visibleEdges, page: activePage, pageCount,
    totalCommunities: communities.length, totalNodes: byNode.size,
    totalEdges: totalInternalEdges + totalCrossEdges, totalInternalEdges, totalCrossEdges,
    ignoredEdges, hiddenCommunityCount: communities.length - visible.length,
    hiddenCrossEdges: totalCrossEdges - visibleEdges.reduce((sum, edge) => sum + edge.count, 0) }
}

/** The complete observed community graph, with no drawing or pagination cap. */
export function buildCommunityGalaxyMap(nodes = [], edges = [], communityOf = {}, groups = []) {
  return buildCommunityOverview(nodes, edges, communityOf, groups, { allCommunities: true })
}

export const buildFullCommunityOverview = buildCommunityGalaxyMap

/** Reveal a community plus its one-hop boundary without inventing neighbor-neighbor links. */
export function expandCommunityGraph(nodes = [], edges = [], communityOf = {}, communityId, { includeBoundary = true } = {}) {
  const wanted = communityKey(communityId)
  const byId = new Map(nodes.filter(node => node?.id != null).map(node => [node.id, node]))
  const memberIds = new Set(nodes.filter(node => node?.id != null
    && communityKey(membership(communityOf, node.id)) === wanted).map(node => node.id))
  const boundaryIds = new Set(), selectedEdges = []
  for (const edge of edges) {
    if (!byId.has(edge.source) || !byId.has(edge.target)) continue
    const sourceInside = memberIds.has(edge.source), targetInside = memberIds.has(edge.target)
    if (!sourceInside && !targetInside) continue
    if (!includeBoundary && (!sourceInside || !targetInside)) continue
    selectedEdges.push(edge)
    if (!sourceInside) boundaryIds.add(edge.source)
    if (!targetInside) boundaryIds.add(edge.target)
  }
  return { nodes: nodes.filter(node => memberIds.has(node.id) || boundaryIds.has(node.id)),
    edges: selectedEdges, memberIds, boundaryIds }
}

function hash(value) {
  let result = 2166136261
  for (const character of String(value)) result = Math.imul(result ^ character.charCodeAt(0), 16777619)
  return result >>> 0
}

const clamp = (value, low, high) => Math.max(low, Math.min(high, value))

function spiralPoint(arm, progress, spanX, spanY, rotation, centerX, centerY) {
  const angle = rotation + arm * Math.PI * 2 / 3 + 2.1 * progress ** 0.78
  return { x: centerX + Math.cos(angle) * progress * spanX,
    y: centerY + Math.sin(angle) * progress * spanY, angle }
}

function hasLabelCollisions(points, width, height) {
  for (let a = 0; a < points.length; a++) for (let b = a + 1; b < points.length; b++) {
    if (Math.abs(points[a].x - points[b].x) < width - 1e-7
      && Math.abs(points[a].y - points[b].y) < height - 1e-7) return true
  }
  return false
}

function alignmentScore(points) {
  let largestBand = 0, repeated = 0
  for (const axis of ['x', 'y']) for (const point of points) {
    const count = points.reduce((sum, other) => sum + (Math.abs(point[axis] - other[axis]) <= 6 ? 1 : 0), 0)
    largestBand = Math.max(largestBand, count)
    repeated += count - 1
  }
  return { largestBand, score: largestBand * 100 + repeated }
}

/** Resolve rectangles continuously along their separation vector, without snapping either axis. */
function settleSpiralLabels(targets, width, height, gap, bounds) {
  const points = targets.map(point => ({ ...point }))
  const minX = bounds.left + width / 2, maxX = bounds.right - width / 2
  const minY = bounds.top + height / 2, maxY = bounds.bottom - height / 2
  const constrain = point => { point.x = clamp(point.x, minX, maxX); point.y = clamp(point.y, minY, maxY) }
  points.forEach(constrain)
  for (let iteration = 0; iteration < 220; iteration++) {
    // The attraction disappears before the final collision passes. It keeps
    // the arms recognizable without continually recreating resolved overlaps.
    const attraction = iteration < 90 ? 0.025 * (1 - iteration / 90) : 0
    if (attraction) for (let i = 1; i < points.length; i++) {
      points[i].x += (targets[i].x - points[i].x) * attraction
      points[i].y += (targets[i].y - points[i].y) * attraction
    }
    for (let a = 0; a < points.length; a++) for (let b = a + 1; b < points.length; b++) {
      const first = points[a], second = points[b]
      let dx = second.x - first.x, dy = second.y - first.y
      if (Math.abs(dx) >= width + gap || Math.abs(dy) >= height + gap) continue
      if (Math.abs(dx) + Math.abs(dy) < 1e-5) {
        const angle = (a * 1.93 + b * 2.41) % (Math.PI * 2)
        dx = Math.cos(angle) * 0.01; dy = Math.sin(angle) * 0.01
      }
      const factor = Math.min((width + gap) / Math.max(Math.abs(dx), 1e-8),
        (height + gap) / Math.max(Math.abs(dy), 1e-8)) - 1
      const pushX = dx * factor * 1.002, pushY = dy * factor * 1.002
      // The largest community stays at the galactic core. Other stars share
      // the displacement, so the result does not depend on a drawing history.
      if (a === 0) { second.x += pushX; second.y += pushY }
      else {
        first.x -= pushX / 2; first.y -= pushY / 2
        second.x += pushX / 2; second.y += pushY / 2
      }
      constrain(first); constrain(second)
    }
    if (iteration >= 90 && !hasLabelCollisions(points, width + gap - 0.05, height + gap - 0.05)) return points
  }
  return hasLabelCollisions(points, width + gap - 0.05, height + gap - 0.05) ? null : points
}

function spiralGuides(spanX, spanY, rotation, centerX, centerY) {
  return Array.from({ length: 3 }, (_, id) => {
    const points = Array.from({ length: 37 }, (_, index) => {
      const progress = index / 36
      const point = spiralPoint(id, progress, spanX, spanY, rotation, centerX, centerY)
      // Buttons are 104px tall, with their visual nucleus 34px above center.
      return { x: point.x, y: point.y - 34, progress, angle: point.angle }
    })
    const path = points.map((point, index) => `${index ? 'L' : 'M'}${point.x.toFixed(2)} ${point.y.toFixed(2)}`).join(' ')
    return { id, path, points }
  })
}

/**
 * A continuous, settled three-arm spiral. Local rectangular collision passes
 * preserve readable labels and keep the core fixed; there is no lattice or
 * running physics animation. Only an overcrowded viewport scales its labels.
 */
export function layoutCommunityGalaxy(communities = [], { width = 1000, height = 650, padding = 28 } = {}) {
  const screenWidth = Math.max(1, Number(width) || 1000), screenHeight = Math.max(1, Number(height) || 650)
  const inset = Math.max(0, Math.min(Number(padding) || 0, screenWidth / 5, screenHeight / 5))
  const availableWidth = screenWidth - inset * 2, availableHeight = screenHeight - inset * 2
  const sorted = [...communities].sort((a, b) => (Number(b.count ?? b.size) || 0) - (Number(a.count ?? a.size) || 0) || compare(a.id, b.id))
  if (!sorted.length) return { nodes: [], width: screenWidth, height: screenHeight, scale: 1, spiralArms: [],
    labelWidth: screenWidth < 600 ? 112 : 128, labelHeight: 104,
    bounds: { left: screenWidth / 2, right: screenWidth / 2, top: screenHeight / 2, bottom: screenHeight / 2, width: 0, height: 0 } }
  const preferredWidth = screenWidth < 600 ? 112 : 128, preferredHeight = 104
  const centerX = screenWidth / 2, centerY = screenHeight / 2
  const maxRank = Math.max(1, Math.ceil((sorted.length - 1) / 3))
  let scale = Math.min(1, availableWidth / preferredWidth, availableHeight / preferredHeight,
    Math.sqrt(availableWidth * availableHeight / (sorted.length * preferredWidth * preferredHeight)) * 0.85)
  let positions, spanX, spanY, rotation, labelWidth, labelHeight
  // Each attempt changes a continuous phase, never snaps to candidate cells.
  // The bound is independent of the full graph size (only this page is used).
  for (let density = 0; density < 8 && !positions; density++) {
    labelWidth = preferredWidth * scale; labelHeight = preferredHeight * scale
    spanX = Math.max(0, (availableWidth - labelWidth) / 2)
    spanY = Math.max(0, (availableHeight - labelHeight) / 2)
    let best = null, bestScore = Infinity, bestRotation
    for (let phase = 0; phase < 6; phase++) {
      rotation = -Math.PI / 2 + phase * 0.19
      const targets = sorted.map((community, index) => {
        const arm = index === 0 ? -1 : (index - 1) % 3
        const rank = index === 0 ? 0 : Math.floor((index - 1) / 3) + 1
        const seed = hash(community.id)
        const radialJitter = ((seed % 101) / 100 - 0.5) * 0.04
        const angularJitter = (((seed >>> 9) % 101) / 100 - 0.5) * 0.13
        const progress = index === 0 ? 0 : clamp(0.18 + 0.79 * (rank / maxRank) ** 0.78 + radialJitter, 0, 1)
        const point = spiralPoint(arm, progress, spanX, spanY, rotation + angularJitter, centerX, centerY)
        return { x: point.x, y: point.y, arm, rank, progress, angle: point.angle }
      })
      const candidate = settleSpiralLabels(targets, labelWidth, labelHeight, 14 * scale,
        { left: inset, right: screenWidth - inset, top: inset, bottom: screenHeight - inset })
      if (!candidate) continue
      const alignment = alignmentScore(candidate)
      if (alignment.score < bestScore) { best = candidate; bestScore = alignment.score; bestRotation = rotation }
      if (alignment.largestBand <= 3) break
    }
    positions = best
    if (best) rotation = bestRotation
    if (!positions) scale *= 0.92
  }
  // Extremely crowded callers retain every star at a provably separated scale
  // on the same spiral. This is a bounded pair-distance calculation, not an
  // alternative arrangement or an unbounded retry loop.
  if (!positions) {
    rotation = -Math.PI / 2
    const normalized = sorted.map((community, index) => {
      const arm = index === 0 ? -1 : (index - 1) % 3, rank = index === 0 ? 0 : Math.floor((index - 1) / 3) + 1
      const progress = index === 0 ? 0 : 0.18 + 0.79 * (rank / maxRank) ** 0.78
      return { ...spiralPoint(arm, progress, availableWidth / 2, availableHeight / 2, rotation, centerX, centerY), arm, rank, progress }
    })
    let separation = Infinity
    for (let a = 0; a < normalized.length; a++) for (let b = a + 1; b < normalized.length; b++) {
      separation = Math.min(separation, Math.max(Math.abs(normalized[a].x - normalized[b].x) / (preferredWidth + 14),
        Math.abs(normalized[a].y - normalized[b].y) / (preferredHeight + 14)))
    }
    scale = Math.min(scale, availableWidth / preferredWidth * 0.1, availableHeight / preferredHeight * 0.1, separation * 0.45)
    labelWidth = preferredWidth * scale; labelHeight = preferredHeight * scale
    spanX = Math.max(0, (availableWidth - labelWidth) / 2); spanY = Math.max(0, (availableHeight - labelHeight) / 2)
    positions = normalized.map(point => ({ ...point,
      ...spiralPoint(point.arm, point.progress, spanX, spanY, rotation, centerX, centerY) }))
  }
  const maxSize = Math.max(1, Number(sorted[0].count ?? sorted[0].size) || 1)
  const placed = sorted.map((community, index) => {
    const point = positions[index]
    const size = Math.max(0, Number(community.count ?? community.size) || 0)
    const r = ((screenWidth < 600 ? 20 : 22) + (screenWidth < 600 ? 10 : 16) * Math.sqrt(size / maxSize)) * scale
    return { ...community, ...point, r, labelWidth, labelHeight }
  })
  const bounds = { left: Math.min(...placed.map(node => node.x - labelWidth / 2)),
    right: Math.max(...placed.map(node => node.x + labelWidth / 2)),
    top: Math.min(...placed.map(node => node.y - labelHeight / 2)),
    bottom: Math.max(...placed.map(node => node.y + labelHeight / 2)) }
  bounds.width = bounds.right - bounds.left; bounds.height = bounds.bottom - bounds.top
  return { nodes: placed, width: screenWidth, height: screenHeight, scale, labelWidth, labelHeight, bounds,
    spiralArms: spiralGuides(spanX, spanY, rotation, centerX, centerY) }
}

const CONSTELLATION_TURN = 4.35

function constellationPoint(arm, progress, spanX, spanY, rotation, centerX, centerY) {
  const angle = rotation + arm * Math.PI * 2 / 3 + CONSTELLATION_TURN * progress
  return { x: centerX + Math.cos(angle) * spanX * progress,
    y: centerY + Math.sin(angle) * spanY * progress, angle }
}

function placeConstellation(sorted, armCounts, geometry, scale, rotation) {
  const { centerX, centerY, spanX, spanY } = geometry
  const separation = 56 * scale, lastProgress = [0, 0, 0]
  const placed = []
  const start = clamp(56 * scale / Math.max(1e-9, Math.min(spanX, spanY)) * 0.65, 0.24, 0.5)
  for (let index = 0; index < sorted.length; index++) {
    const arm = index === 0 ? -1 : (index - 1) % 3
    const rank = index === 0 ? 0 : Math.floor((index - 1) / 3) + 1
    if (index === 0) { placed.push({ x: centerX, y: centerY, arm, rank, progress: 0, angle: rotation }); continue }
    const count = armCounts[arm]
    const ideal = count === 1 ? 0.82 : start + (0.96 - start) * (rank - 1) / (count - 1)
    const minimum = Math.max(0.08, lastProgress[arm] + 0.035)
    const maximum = 0.99 - (count - rank) * 0.035
    // Every candidate stays on the very same arm. A small deterministic radial
    // adjustment separates hit areas without making the points form a table.
    const sign = hash(sorted[index].id) % 2 ? 1 : -1
    let chosen
    for (let step = 0; step <= 48; step++) {
      const offset = step === 0 ? 0 : Math.ceil(step / 2) * 0.012 * (step % 2 ? sign : -sign)
      const progress = clamp(ideal + offset, minimum, maximum)
      const point = constellationPoint(arm, progress, spanX, spanY, rotation, centerX, centerY)
      if (placed.every(other => Math.abs(other.x - point.x) >= separation - 1e-7
        || Math.abs(other.y - point.y) >= separation - 1e-7)) {
        chosen = { ...point, arm, rank, progress }; break
      }
    }
    if (!chosen) return null
    lastProgress[arm] = chosen.progress
    placed.push(chosen)
  }
  return placed
}

/**
 * A nucleus-only star disc: all 48px buttons lie exactly on three continuous
 * Archimedean arms. Names are a separate overlay and never displace the nuclei.
 * Placement searches only rotation and distance along an arm, with no physics.
 */
export function layoutCommunityConstellation(communities = [], { width = 1000, height = 520, padding = 24 } = {}) {
  const screenWidth = Math.max(1, Number(width) || 1000), screenHeight = Math.max(1, Number(height) || 520)
  const inset = Math.max(0, Math.min(Number(padding) || 0, screenWidth / 5, screenHeight / 5))
  const availableWidth = screenWidth - inset * 2, availableHeight = screenHeight - inset * 2
  const centerX = screenWidth / 2, centerY = screenHeight / 2
  const sorted = [...communities].sort((a, b) => (Number(b.count ?? b.size) || 0) - (Number(a.count ?? a.size) || 0) || compare(a.id, b.id))
  const armCounts = [0, 0, 0]
  for (let index = 1; index < sorted.length; index++) armCounts[(index - 1) % 3]++
  let scale = Math.min(1, availableWidth / 48, availableHeight / 48,
    Math.sqrt(availableWidth * availableHeight / (Math.max(1, sorted.length) * 56 ** 2)) * 0.85)
  let positions, rotation = -Math.PI / 2, spanX, spanY
  for (let density = 0; density < 12 && !positions; density++) {
    spanX = Math.max(0, (availableWidth - 48 * scale) / 2)
    spanY = Math.max(0, (availableHeight - 48 * scale) / 2)
    for (let phase = 0; phase < 24 && !positions; phase++) {
      rotation = -Math.PI / 2 + phase * Math.PI / 36
      positions = placeConstellation(sorted, armCounts, { centerX, centerY, spanX, spanY }, scale, rotation)
    }
    if (!positions) scale *= 0.92
  }
  if (!positions) {
    // A crowded caller still receives every star. Compute a separated size on
    // the same curves directly instead of introducing another arrangement.
    rotation = -Math.PI / 2
    const full = sorted.map((community, index) => {
      const arm = index === 0 ? -1 : (index - 1) % 3, rank = index === 0 ? 0 : Math.floor((index - 1) / 3) + 1
      const count = arm === -1 ? 1 : armCounts[arm]
      const progress = index === 0 ? 0 : count === 1 ? 0.82 : 0.24 + 0.72 * (rank - 1) / (count - 1)
      return { ...constellationPoint(arm, progress, availableWidth / 2, availableHeight / 2, rotation, centerX, centerY), arm, rank, progress }
    })
    let separation = Infinity
    for (let a = 0; a < full.length; a++) for (let b = a + 1; b < full.length; b++) {
      separation = Math.min(separation, Math.max(Math.abs(full[a].x - full[b].x), Math.abs(full[a].y - full[b].y)) / 56)
    }
    scale = Math.min(scale, availableWidth / 48 * 0.1, availableHeight / 48 * 0.1, separation * 0.45)
    spanX = Math.max(0, (availableWidth - 48 * scale) / 2); spanY = Math.max(0, (availableHeight - 48 * scale) / 2)
    positions = full.map(point => ({ ...point, ...constellationPoint(point.arm, point.progress, spanX, spanY, rotation, centerX, centerY) }))
  }
  const maxSize = Math.max(1, Number(sorted[0]?.count ?? sorted[0]?.size) || 1)
  const labelWidth = 48 * scale, labelHeight = labelWidth
  const nodes = sorted.map((community, index) => ({ ...community, ...positions[index], labelWidth, labelHeight,
    r: (index === 0 ? 15 : 5 + 7 * Math.sqrt(Math.max(0, Number(community.count ?? community.size) || 0) / maxSize)) * scale }))
  const spiralArms = Array.from({ length: 3 }, (_, id) => {
    const points = Array.from({ length: 97 }, (_, index) => {
      const progress = index / 96
      return { ...constellationPoint(id, progress, spanX, spanY, rotation, centerX, centerY), progress }
    })
    return { id, points, path: points.map((point, index) => `${index ? 'L' : 'M'}${point.x.toFixed(2)} ${point.y.toFixed(2)}`).join(' ') }
  })
  const bounds = nodes.length ? { left: Math.min(...nodes.map(node => node.x - labelWidth / 2)),
    right: Math.max(...nodes.map(node => node.x + labelWidth / 2)),
    top: Math.min(...nodes.map(node => node.y - labelHeight / 2)),
    bottom: Math.max(...nodes.map(node => node.y + labelHeight / 2)) }
    : { left: centerX, right: centerX, top: centerY, bottom: centerY }
  bounds.width = bounds.right - bounds.left; bounds.height = bounds.bottom - bounds.top
  return { nodes, width: screenWidth, height: screenHeight, scale, labelWidth, labelHeight, bounds,
    center: { x: centerX, y: centerY }, spiralTurn: CONSTELLATION_TURN, spiralRotation: rotation, spiralArms }
}

/** A reusable point index. Queries visit occupied buckets or nearby cells, whichever is smaller. */
export function createCommunityGalaxyIndex(nodes = [], { cellSize = 96 } = {}) {
  const step = Number.isFinite(Number(cellSize)) ? Math.max(1, Number(cellSize)) : 96
  const cells = new Map(), byId = new Map()
  for (const node of nodes) {
    if (node?.id == null || !Number.isFinite(node.x) || !Number.isFinite(node.y)
      || byId.has(String(node.id))) continue
    byId.set(String(node.id), node)
    const key = `${Math.floor(node.x / step)},${Math.floor(node.y / step)}`
    if (!cells.has(key)) cells.set(key, [])
    cells.get(key).push(node)
  }
  function query(bounds) {
    if (!bounds || ![bounds.left, bounds.right, bounds.top, bounds.bottom].every(Number.isFinite)
      || bounds.right < bounds.left || bounds.bottom < bounds.top) return []
    const { left, right, top, bottom } = bounds, found = []
    const append = bucket => {
      for (const node of bucket || []) if (node.x >= left && node.x <= right
        && node.y >= top && node.y <= bottom) found.push(node)
    }
    const firstX = Math.floor(left / step), lastX = Math.floor(right / step)
    const firstY = Math.floor(top / step), lastY = Math.floor(bottom / step)
    if ((lastX - firstX + 1) * (lastY - firstY + 1) > cells.size) {
      for (const bucket of cells.values()) append(bucket)
    } else for (let x = firstX; x <= lastX; x++) for (let y = firstY; y <= lastY; y++) append(cells.get(`${x},${y}`))
    return found
  }
  function nearest(x, y, radius) {
    if (![x, y, radius].every(Number.isFinite) || radius < 0) return null
    let best = null, distance = radius * radius
    for (const node of query({ left: x - radius, right: x + radius, top: y - radius, bottom: y + radius })) {
      const squared = (node.x - x) ** 2 + (node.y - y) ** 2
      if (squared < distance || squared === distance && (!best || compare(node.id, best.id) < 0)) {
        best = node; distance = squared
      }
    }
    return best
  }
  return { byId, query, nearest, cellSize: step, size: byId.size }
}

function radicalInverse(value, base) {
  let fraction = 1 / base, result = 0
  while (value > 0) {
    result += (value % base) * fraction
    value = Math.floor(value / base); fraction /= base
  }
  return result
}

function galaxyArcTable(arm, aspect, rotation) {
  const points = [{ progress: 0.13, length: 0 }]
  let previous = constellationPoint(arm, 0.13, 1, aspect, rotation, 0, 0), length = 0
  for (let sample = 1; sample <= 512; sample++) {
    const progress = 0.13 + 0.85 * sample / 512
    const point = constellationPoint(arm, progress, 1, aspect, rotation, 0, 0)
    length += Math.hypot(point.x - previous.x, point.y - previous.y)
    points.push({ progress, length }); previous = point
  }
  return fraction => {
    const target = fraction * length
    let low = 0, high = points.length - 1
    while (high - low > 1) {
      const middle = (low + high) >>> 1
      if (points[middle].length < target) low = middle
      else high = middle
    }
    const before = points[low], after = points[high]
    return before.progress + (after.progress - before.progress)
      * (target - before.length) / Math.max(1e-12, after.length - before.length)
  }
}

function placeGalaxyMap(count, radius, aspect, rotation, separation, arcTables, withBands = true) {
  const cells = new Map(), positions = [{ x: 0, y: 0, arm: -1, rank: 0, progress: 0, angle: rotation, armOffset: 0 }]
  cells.set('0,0', [positions[0]])
  const separated = point => {
    const cx = Math.floor(point.x / separation), cy = Math.floor(point.y / separation)
    for (let dx = -1; dx <= 1; dx++) for (let dy = -1; dy <= 1; dy++) {
      for (const other of cells.get(`${cx + dx},${cy + dy}`) || []) {
        if ((point.x - other.x) ** 2 + (point.y - other.y) ** 2 < separation ** 2) return false
      }
    }
    const key = `${cx},${cy}`
    if (!cells.has(key)) cells.set(key, [])
    cells.get(key).push(point)
    return true
  }
  const armCounts = [0, 0, 0]
  for (let index = 1; index < count; index++) armCounts[(index - 1) % 3]++
  for (let arm = 0; arm < 3; arm++) {
    let accepted = 0
    // Fixed candidate budget per community bounds work even on pathological input.
    for (let candidate = 1; candidate <= armCounts[arm] * 32 && accepted < armCounts[arm]; candidate++) {
      const fraction = withBands ? radicalInverse(candidate, 2) : (candidate - 0.5) / armCounts[arm]
      const progress = arcTables[arm](fraction)
      const point = constellationPoint(arm, progress, radius, radius * aspect, rotation, 0, 0)
      const dx = Math.cos(point.angle) - CONSTELLATION_TURN * progress * Math.sin(point.angle)
      const dy = aspect * (Math.sin(point.angle) + CONSTELLATION_TURN * progress * Math.cos(point.angle))
      const normalLength = Math.hypot(dx, dy)
      const armOffset = withBands ? (radicalInverse(candidate, 3) * 2 - 1) * radius * 0.065 * (0.45 + 0.55 * progress) : 0
      const placed = { ...point, x: point.x - dy / normalLength * armOffset,
        y: point.y + dx / normalLength * armOffset, arm, rank: accepted + 1, progress, armOffset }
      if (!separated(placed)) continue
      positions[1 + accepted * 3 + arm] = placed
      accepted++
    }
    if (accepted < armCounts[arm]) return null
  }
  return positions
}

/**
 * One complete, deterministic galaxy in virtual world coordinates. Community
 * nuclei occupy narrow spiral bands, with a spatial hash enforcing distance.
 * No label rectangles, pairwise physics, viewport sizing or pagination enter
 * this layout. Camera changes therefore never move the underlying communities.
 */
export function layoutCommunityGalaxyMap(communities = [], { minSeparation = 28, aspect = 0.68, rotation = -Math.PI / 2, padding = 32 } = {}) {
  const separation = Number.isFinite(Number(minSeparation)) ? Math.max(28, Number(minSeparation)) : 28
  const flattening = Number.isFinite(Number(aspect)) ? clamp(Number(aspect), 0.55, 0.85) : 0.68
  const phase = Number.isFinite(Number(rotation)) ? Number(rotation) : -Math.PI / 2
  const inset = Number.isFinite(Number(padding)) ? Math.max(24, Number(padding)) : 32
  const sorted = [...communities].sort((a, b) => (Number(b.count ?? b.size) || 0) - (Number(a.count ?? a.size) || 0) || compare(a.id, b.id))
  const arcTables = Array.from({ length: 3 }, (_, arm) => galaxyArcTable(arm, flattening, phase))
  let radius = Math.max(320, separation * 2.1 * Math.sqrt(Math.max(1, sorted.length))), positions
  if (sorted.length) for (let attempt = 0; attempt < 6 && !positions; attempt++) {
    positions = placeGalaxyMap(sorted.length, radius, flattening, phase, separation, arcTables)
    if (!positions) radius *= 1.3
  }
  // The bounded final guard uses generous curve spacing, never drops members,
  // and still performs only local bucket checks rather than all-pairs work.
  if (sorted.length && !positions) {
    radius = Math.max(radius, sorted.length * separation * 2)
    positions = placeGalaxyMap(sorted.length, radius, flattening, phase, separation, arcTables, false)
  }
  const margin = inset + radius * 0.075
  const center = { x: radius + margin, y: radius * flattening + margin }
  const maxSize = Math.max(1, Number(sorted[0]?.count ?? sorted[0]?.size) || 1)
  const nodes = sorted.map((community, index) => ({ ...community, ...positions[index],
    x: positions[index].x + center.x, y: positions[index].y + center.y,
    r: 6 + 6 * Math.sqrt(Math.max(0, Number(community.count ?? community.size) || 0) / maxSize) }))
  const width = (radius + margin) * 2, height = (radius * flattening + margin) * 2
  const bounds = { left: 0, right: width, top: 0, bottom: height, width, height }
  const spiralArms = Array.from({ length: 3 }, (_, id) => {
    const points = Array.from({ length: 97 }, (_, index) => {
      const progress = index / 96
      return { ...constellationPoint(id, progress, radius, radius * flattening, phase, center.x, center.y), progress }
    })
    return { id, points, path: points.map((point, index) => `${index ? 'L' : 'M'}${point.x.toFixed(2)} ${point.y.toFixed(2)}`).join(' ') }
  })
  return { nodes, width, height, bounds, center, scale: 1, minSeparation: separation,
    bandWidth: radius * 0.13, spanX: radius, spanY: radius * flattening,
    spiralTurn: CONSTELLATION_TURN, spiralRotation: phase, spiralArms }
}
