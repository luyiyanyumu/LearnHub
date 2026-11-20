/** Keep the exploration filters and hop expansion consistent with the visible graph. */
export function graphCommunityOptions(nodes, groups) {
  const byId = new Map(nodes.map(node => [node.id, node]))
  return groups.map(group => ({ ...group,
    title: (group.nodeIds || []).map(id => byId.get(id)).filter(Boolean)
      .sort((a, b) => (b.degree || 0) - (a.degree || 0)).slice(0, 2)
      .map(node => node.label || node.name || node.id).join(' / ') || `社区 #${group.communityId}`,
  }))
}

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

export function createGraphSearchIndex(nodes) {
  const entries = nodes.map(node => ({
    node,
    terms: [node.label || node.name || '', ...(node.aliases || [])].map(term => String(term).toLocaleLowerCase()),
  })).sort((a, b) => (b.node.degree || 0) - (a.node.degree || 0) || String(a.node.id).localeCompare(String(b.node.id)))

  return (query, limit = 12) => {
    const q = String(query || '').trim().toLocaleLowerCase()
    if (!q) return entries.slice(0, limit).map(entry => entry.node)

    const exact = []
    const prefix = []
    const partial = []
    for (const { node, terms } of entries) {
      if (terms.includes(q)) exact.push(node)
      else if (terms.some(term => term.startsWith(q))) prefix.push(node)
      else if (terms.some(term => term.includes(q))) partial.push(node)
    }
    return exact.concat(prefix, partial).slice(0, limit)
  }
}

export function findGraphNodes(nodes, query, limit = 12) {
  return createGraphSearchIndex(nodes)(query, limit)
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
