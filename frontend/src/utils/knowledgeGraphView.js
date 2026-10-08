/** Keep the exploration filters and hop expansion consistent with the visible graph. */
export function filterConceptGraph(nodes, edges, { relations = null, origin = 'all', community = '', communities = {}, focus = null, showIsolated = false } = {}) {
  const allowed = new Set(nodes.filter(n => community === '' || String(communities[n.id]) === String(community)).map(n => n.id))
  const shownEdges = edges.filter(e => allowed.has(e.source) && allowed.has(e.target)
    && (relations == null || relations.includes(e.relation))
    && (origin === 'all' || (origin === 'derived' ? e.origin === 'derived' : e.origin !== 'derived'))
    && (!focus || (focus.has(e.source) && focus.has(e.target))))
  const connected = new Set(shownEdges.flatMap(e => [e.source, e.target]))
  return {
    nodes: nodes.filter(n => allowed.has(n.id) && (!focus || focus.has(n.id)) && (showIsolated || connected.has(n.id) || focus?.has(n.id))),
    edges: shownEdges,
  }
}

export function expandGraphIds(id, edges, hops = 1) {
  const result = new Set([id])
  let frontier = new Set([id])
  for (let h = 0; h < Math.max(0, Math.min(3, hops)); h++) {
    const next = new Set()
    for (const e of edges) {
      if (frontier.has(e.source) && !result.has(e.target)) next.add(e.target)
      if (frontier.has(e.target) && !result.has(e.source)) next.add(e.source)
    }
    for (const nextId of next) result.add(nextId)
    frontier = next
  }
  return result
}

export function findGraphNodes(nodes, query, limit = 12) {
  const q = String(query || '').trim().toLocaleLowerCase()
  const score = n => {
    const name = String(n.label || n.name || '').toLocaleLowerCase()
    const aliases = (n.aliases || []).map(a => String(a).toLocaleLowerCase())
    if (!q) return 1
    if (name === q || aliases.includes(q)) return 4
    if (name.startsWith(q) || aliases.some(a => a.startsWith(q))) return 3
    return name.includes(q) || aliases.some(a => a.includes(q)) ? 2 : 0
  }
  return nodes.map(n => ({ n, score: score(n) })).filter(x => x.score > 0)
    .sort((a, b) => b.score - a.score || (b.n.degree || 0) - (a.n.degree || 0) || String(a.n.id).localeCompare(String(b.n.id)))
    .slice(0, limit).map(x => x.n)
}

export function graphSource(source) {
  const raw = typeof source === 'object' && source ? source.ref || `${source.type}:${source.id}` : String(source || '')
  const match = /^(note|ref|quick_ref|file|笔记|速查卡|资料)[-:#](\d+)$/.exec(raw)
  if (!match) return null
  const type = { quick_ref: 'ref', 笔记: 'note', 速查卡: 'ref', 资料: 'file' }[match[1]] || match[1]
  const id = Number(match[2])
  return { type, id, label: `${{ note: '笔记', ref: '速查卡', file: '资料' }[type]} #${id}`,
    path: type === 'note' ? `/notes/${id}` : type === 'file' ? `/files?read=${id}` : `/refs?read=${id}` }
}
