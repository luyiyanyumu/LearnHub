const TABS = new Set(['search', 'graph', 'wiki'])

/** Invalid or repeated query values never choose a workspace. */
export function knowledgeWorkspaceTab(query) {
  return TABS.has(query?.tab) ? query.tab : 'search'
}

/** Explicit navigation clears an old Wiki location; unrelated query values survive. */
export function knowledgeWorkspaceQuery(query, tab, topic = '') {
  const next = { ...query, tab: TABS.has(tab) ? tab : 'search' }
  delete next.topic
  delete next.section
  delete next.heading
  if (next.tab === 'wiki' && typeof topic === 'string' && /^[\w-]{1,200}$/.test(topic)) next.topic = topic
  return next
}
