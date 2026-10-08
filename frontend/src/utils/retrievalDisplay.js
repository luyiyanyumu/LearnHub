const CHANNEL_LABELS = { keyword: '关键词', vector: '语义', graph: '图谱', wiki: 'Wiki 定位', tool: '工具读取' }

const BLOCK_TAGS = new Set('address article aside blockquote br caption center dd details div dl dt fieldset figcaption figure footer form h1 h2 h3 h4 h5 h6 header hr legend li main nav ol p pre section summary table tbody td tfoot th thead tr ul'.split(' '))
const INLINE_TAGS = new Set('a abbr b bdi bdo big cite code col colgroup del em font i img ins kbd label mark q rp rt ruby s samp small span strike strong sub sup time tt u var wbr'.split(' '))
const HTML_ENTITIES = {
  amp: '&', lt: '<', gt: '>', quot: '"', apos: "'", nbsp: ' ', ensp: ' ', emsp: ' ', thinsp: ' ',
  ndash: '–', mdash: '—', hellip: '…', bull: '•', middot: '·', copy: '©', reg: '®', trade: '™',
  lsquo: '‘', rsquo: '’', ldquo: '“', rdquo: '”', laquo: '«', raquo: '»',
  times: '×', divide: '÷', le: '≤', ge: '≥', ne: '≠', larr: '←', rarr: '→', harr: '↔',
}

/** Plain text only: strip known presentation tags before decoding entities so escaped code remains visible. */
export function retrievalSnippet(hit, maxLength = 120) {
  const text = String(hit?.snippet ?? hit?.text ?? '')
    .replace(/<!--[\s\S]*?-->/g, ' ')
    .replace(/<(script|style)\b(?:[^<>"']|"[^"]*"|'[^']*')*>[\s\S]*?<\/\1\s*>/gi, ' ')
    .replace(/<\/?([a-z][a-z0-9]*)(?=[\s/>])(?:[^<>"']|"[^"]*"|'[^']*')*>/gi, (tag, name, _offset, source) => {
      // Java type parameters such as <B>, <P>, and <U extends Serializable> also resemble HTML tag names.
      if (/^[A-Z]$/.test(name) && !new RegExp(`</${name}\\s*>`, 'i').test(source)) return tag
      const lower = name.toLowerCase()
      if (BLOCK_TAGS.has(lower)) return ' '
      return INLINE_TAGS.has(lower) ? '' : tag
    })
    .replace(/&(#x[\da-f]+|#\d+|[a-z][a-z0-9]+);/gi, (entity, name) => {
      if (!name.startsWith('#')) return HTML_ENTITIES[name] ?? HTML_ENTITIES[name.toLowerCase()] ?? entity
      const code = name[1].toLowerCase() === 'x' ? parseInt(name.slice(2), 16) : parseInt(name.slice(1), 10)
      return code > 0 && code <= 0x10ffff && !(code >= 0xd800 && code <= 0xdfff) ? String.fromCodePoint(code) : entity
    })
    .replace(/\s+/g, ' ').trim()
  return text.length > maxLength ? text.slice(0, maxLength) + '…' : text
}

/** Only describe channels reported by retrieval; legacy references have no channel metadata. */
export function retrievalChannels(hit) {
  const channels = Array.isArray(hit?.channels) ? hit.channels : []
  return Object.entries(CHANNEL_LABELS).filter(([key]) => channels.includes(key)).map(([, label]) => label)
}

export function retrievalRelations(hit) {
  return [...new Set((Array.isArray(hit?.graphRelations) ? hit.graphRelations : [])
    .filter(value => typeof value === 'string').map(value => value.trim()).filter(Boolean))]
}

export function isGeneratedGuide(hit) {
  return (hit?.sourceType ?? hit?.type) === 'wiki' || hit?.generatedGuide === true
}

/** Generated pages help navigation; only original records are evidence for factual claims. */
export function retrievalGroups(hits) {
  if (!Array.isArray(hits)) return []
  const source = [], guide = [], context = []
  for (const hit of hits) {
    if (isGeneratedGuide(hit)) guide.push(hit)
    else if (['note', 'quick_ref', 'ref', 'file'].includes(hit?.sourceType ?? hit?.type)) source.push(hit)
    else context.push(hit)
  }
  return [
    { key: 'source', label: '原文证据', items: source },
    { key: 'guide', label: '生成导览', items: guide },
    { key: 'context', label: '关系背景', items: context },
  ].filter(group => group.items.length)
}

export function retrievalTitle(hit) {
  const title = typeof hit?.title === 'string' ? hit.title : ''
  const heading = typeof hit?.heading === 'string' ? hit.heading.trim() : ''
  return isGeneratedGuide(hit) && heading && heading !== title ? `${title} · ${heading}` : title
}

function passageSeq(hit) {
  return Number.isInteger(hit?.seq) && hit.seq >= 0 ? hit.seq : null
}

/** Index passages are zero-based; evidence windows may span chunks and use a negative seq. */
export function retrievalPosition(hit) {
  if (Number.isInteger(hit?.seq) && hit.seq < 0) return '证据片段'
  const seq = passageSeq(hit)
  return seq == null ? '' : `第 ${seq + 1} 段`
}

export function retrievalKey(hit) {
  if (typeof hit?.passageKey === 'string' && hit.passageKey.trim()) return hit.passageKey
  const seq = Number.isInteger(hit?.seq) ? hit.seq : null
  const key = [hit?.sourceType ?? hit?.type ?? '', hit?.sourceId ?? hit?.id ?? '', seq]
  if (isGeneratedGuide(hit)) key.push(hit?.topicKey ?? '', hit?.sectionKey ?? hit?.heading ?? '')
  // Older responses have no passage hash. Preserve full evidence text before truncating the visible snippet.
  if (seq != null && seq < 0) key.push(String(hit?.text ?? hit?.snippet ?? ''))
  return JSON.stringify(key)
}

export function retrievalTooltip(hit) {
  const channels = retrievalChannels(hit)
  const relations = retrievalRelations(hit)
  return [retrievalTitle(hit), retrievalPosition(hit), isGeneratedGuide(hit) ? '模型生成的知识导览，请结合原文核对。' : '', channels.length ? `召回：${channels.join(' / ')}` : '',
    relations.length ? `图谱关系：\n${relations.join('\n')}` : ''].filter(Boolean).join('\n')
}

/** Search hits and agent references share the same source types; open the exact source. */
export function retrievalSourcePath(hit) {
  const type = hit?.sourceType ?? hit?.type
  if (type === 'wiki') {
    if (typeof hit?.topicKey !== 'string' || !/^[\w-]{1,200}$/.test(hit.topicKey)) return null
    const query = new URLSearchParams({ tab: 'wiki', topic: hit.topicKey })
    for (const [key, value] of [['section', hit.sectionKey], ['heading', hit.heading]]) {
      if (typeof value === 'string' && value.trim()) query.set(key, value.trim())
    }
    return `/knowledge?${query}`
  }
  const id = String(hit?.sourceId ?? hit?.id ?? '')
  if (!/^[1-9]\d*$/.test(id)) return null
  if (type === 'note') return `/notes/${id}`
  if (type === 'quick_ref' || type === 'ref') return `/refs?read=${id}`
  return type === 'file' ? `/files?read=${id}` : null
}
