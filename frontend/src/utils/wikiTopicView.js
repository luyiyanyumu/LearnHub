export const WIKI_TOPIC_TYPES = [
  { value: 'category', label: '分类' },
  { value: 'tag', label: '标签' },
  { value: 'entity', label: '实体' },
  { value: 'index', label: '索引' },
  { value: 'lint', label: '自检' },
]

export const WIKI_TOPIC_STATES = [
  { value: 'stale', label: '待更新' },
  { value: 'missing', label: '未生成' },
  { value: 'generated', label: '已生成' },
]

/** Generation state is separate from verification of the page's source evidence. */
export function wikiTopicState(topic) {
  if (topic?.stale === true) return 'stale'
  return topic?.generated === true ? 'generated' : 'missing'
}

export function wikiTopicType(topic) {
  return typeof topic?.topicType === 'string' && topic.topicType ? topic.topicType : 'category'
}

/** The backend's itemCount has a different meaning for each compiled page type. */
export function wikiTopicCountLabel(topic) {
  const count = topic?.itemCount
  if (!Number.isInteger(count) || count < 0) return ''
  const n = count.toLocaleString('zh-CN')
  const type = wikiTopicType(topic)
  if (type === 'entity') return count > 0 ? `${n} 个关联来源` : '跨页编译生成'
  if (type === 'index') return `收录 ${n} 个页面`
  if (type === 'lint') return `${n} 条问题`
  if (type === 'category' || type === 'tag') return `${n} 条素材`
  return `${n} 项关联内容`
}

/** Filters affect navigation only; input order and original topic objects remain intact. */
export function wikiTopicView(topics, { query = '', type = 'all', state = 'all' } = {}) {
  const all = Array.isArray(topics)
    ? topics.filter(topic => topic && typeof topic.topicKey === 'string' && topic.topicKey)
    : []
  const needle = String(query ?? '').trim().toLocaleLowerCase()
  const counts = { stale: 0, missing: 0, generated: 0 }
  let generatedCount = 0
  for (const topic of all) {
    counts[wikiTopicState(topic)]++
    if (topic.generated === true) generatedCount++
  }
  const visible = all.filter(topic => {
    if (type !== 'all' && wikiTopicType(topic) !== type) return false
    if (state !== 'all' && wikiTopicState(topic) !== state) return false
    const aliases = Array.isArray(topic.aliases) ? topic.aliases.filter(alias => typeof alias === 'string') : []
    return !needle || [topic.title, topic.topicKey, ...aliases].some(value =>
      typeof value === 'string' && value.toLocaleLowerCase().includes(needle))
  })
  const knownTypes = new Set(WIKI_TOPIC_TYPES.map(item => item.value))
  const groups = WIKI_TOPIC_TYPES.map(item => ({
    type: item.value,
    label: item.label,
    items: visible.filter(topic => wikiTopicType(topic) === item.value),
  })).filter(group => group.items.length)
  const rest = visible.filter(topic => !knownTypes.has(wikiTopicType(topic)))
  if (rest.length) groups.push({ type: 'other', label: '其他', items: rest })
  return { groups, totalCount: all.length, visibleCount: visible.length, generatedCount, counts,
    hasFilter: !!needle || type !== 'all' || state !== 'all' }
}
