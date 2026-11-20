const PALETTE = ['#526eb8', '#9466a9', '#b78240', '#7a879e']
const GOLDEN_ANGLE = Math.PI * (3 - Math.sqrt(5))
const compareId = (a, b) => a < b ? -1 : a > b ? 1 : 0

function hash(value) {
  let result = 2166136261
  for (let i = 0; i < value.length; i++) result = Math.imul(result ^ value.charCodeAt(i), 16777619)
  return result >>> 0
}

export function graph3dCommunityColor(id) {
  if (id == null || id === '' || id === '__unassigned__') return PALETTE[3]
  const numeric = Number(id)
  const index = Number.isSafeInteger(numeric) ? Math.abs(numeric) : hash(String(id))
  return PALETTE[index % PALETTE.length]
}

// Low-discrepancy placement spreads points in three dimensions without a running simulation.
function spread(index, count, key, xRadius, yRadius, zRadius) {
  if (!index) return { x: 0, y: 0, z: 0 }
  const radius = Math.sqrt(index / Math.max(1, count - 1))
  const angle = index * GOLDEN_ANGLE
  const depth = (hash(key) % 2048) / 1023.5 - 1
  return {
    x: Math.cos(angle) * radius * xRadius,
    y: Math.sin(angle) * radius * yRadius,
    z: depth * zRadius * (.55 + radius * .45),
  }
}

function communityOf(communities, id) {
  if (communities instanceof Map) return communities.get(id)
  return communities && Object.hasOwn(communities, id) ? communities[id] : undefined
}

export function layoutGraph3d(nodes = [], edges = [], { mode = 'concept', communities = {}, rootId = '' } = {}) {
  const overview = mode === 'overview'
  const byId = new Map()
  for (const node of nodes) {
    if (node?.id == null || byId.has(String(node.id))) continue
    const id = String(node.id)
    const community = overview ? node.communityId : communityOf(communities, node.id)
    byId.set(id, {
      id,
      label: String(node.label || node.title || node.name || node.id),
      community,
      color: graph3dCommunityColor(community),
      weight: overview ? Math.max(0, Number(node.count ?? node.size) || 0) : 0,
    })
  }
  const links = []
  for (const edge of edges) {
    if (edge?.source == null || edge.target == null) continue
    const source = byId.get(String(edge.source)), target = byId.get(String(edge.target))
    if (!source || !target || source === target) continue
    if (!overview) { source.weight++; target.weight++ }
    links.push({
      source: source.id, target: target.id,
      weight: Number(edge.weight ?? edge.count) || 1,
      origin: edge.origin, directCount: edge.directCount, derivedCount: edge.derivedCount,
    })
  }
  const root = String(rootId ?? '')
  const ordered = [...byId.values()].sort((a, b) => Number(b.id === root) - Number(a.id === root)
    || b.weight - a.weight || compareId(a.id, b.id))

  if (overview) {
    return {
      nodes: ordered.map((node, index) => ({
        id: node.id, label: node.label, color: node.color,
        ...spread(index, index < 8 ? Math.min(19, ordered.length) : ordered.length, node.id, 212, 139, 129),
        rank: index === 0 ? 3 : index < 8 ? 2 : 1,
        size: index === 0 ? 11 : index < 8 ? 6.5 : Math.min(5.4, 2.5 + .52 * Math.log2(node.weight + 1)),
      })),
      edges: links,
    }
  }

  const groups = new Map()
  for (const node of ordered) {
    const key = String(node.community ?? '')
    if (!groups.has(key)) groups.set(key, [])
    groups.get(key).push(node)
  }
  const positioned = []
  for (const [groupIndex, [key, members]] of [...groups].entries()) {
    const center = spread(groupIndex, groups.size, key, 142, 90, 84)
    const range = groups.size === 1 ? 176 : 42 + 94 * Math.sqrt(members.length / ordered.length)
    for (let i = 0; i < members.length; i++) {
      const node = members[i]
      const local = spread(i, members.length, node.id, range, range * .75, range * .71)
      const isRoot = node.id === root
      const rank = isRoot ? 3 : i === 0 && (members.length > 2 || groupIndex === 0) ? 2 : 1
      positioned.push({
        id: node.id, label: node.label, color: node.color,
        x: center.x + local.x, y: center.y + local.y, z: center.z + local.z,
        rank, size: rank >= 3 ? 11 : rank === 2 ? 7 : Math.min(4.8, 2.65 + Math.sqrt(node.weight) * .28),
      })
    }
  }
  return { nodes: positioned, edges: links }
}
