import { retrievalKey } from './retrievalDisplay.js'

export const KNOWLEDGE_SOURCE_LABELS = {
  note: '笔记', quick_ref: '速查卡', file: '资料', wiki: 'Wiki 导览', other: '其他',
}

export function knowledgeSourceType(item) {
  const type = item?.sourceType ?? item?.type
  if (type === 'ref') return 'quick_ref'
  return Object.hasOwn(KNOWLEDGE_SOURCE_LABELS, type) ? type : 'other'
}

/** A source filter changes visibility, never the server's order or original rank. */
export function knowledgeResultRows(items, filter = 'all') {
  if (!Array.isArray(items)) return []
  return items.map((item, index) => ({ item, rank: index + 1, key: `${retrievalKey(item)}:${index}` }))
    .filter(row => filter === 'all' || knowledgeSourceType(row.item) === filter)
}

export function knowledgeSourceFilters(items) {
  const rows = knowledgeResultRows(items)
  const counts = Object.fromEntries(Object.keys(KNOWLEDGE_SOURCE_LABELS).map(type => [type, 0]))
  for (const row of rows) counts[knowledgeSourceType(row.item)]++
  return [{ key: 'all', label: '全部', count: rows.length },
    ...Object.entries(KNOWLEDGE_SOURCE_LABELS)
      .filter(([type]) => counts[type] > 0)
      .map(([key, label]) => ({ key, label, count: counts[key] }))]
}

function escapeHtml(value) {
  return String(value).replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;')
    .replace(/"/g, '&quot;').replace(/'/g, '&#39;')
}

/** Match the original plain text; escaped entities must never become a match surface. */
export function highlightKnowledgeText(text, keyword = '') {
  const input = String(text ?? '')
  const needle = String(keyword ?? '').trim()
  if (!needle) return escapeHtml(input)
  const matcher = new RegExp(needle.replace(/[.*+?^${}()|[\]\\]/g, '\\$&'), 'gi')
  let output = '', cursor = 0
  for (const match of input.matchAll(matcher)) {
    output += escapeHtml(input.slice(cursor, match.index)) + `<mark>${escapeHtml(match[0])}</mark>`
    cursor = match.index + match[0].length
  }
  return output + escapeHtml(input.slice(cursor))
}
