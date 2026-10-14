export function knowledgeValues(snapshot) {
  const values = {}
  for (const field of snapshot.fields || []) {
    const raw = snapshot.values?.[field.key] ?? snapshot.defaults?.[field.key] ?? field.default
    values[field.key] = field.type === 'boolean' ? raw === true || raw === '1' || raw === 'true'
      : field.type === 'number' && raw !== '' && raw != null ? Number(raw) : raw ?? ''
  }
  return values
}

export function knowledgeChanges(values, baseline, fields) {
  return Object.fromEntries(fields.filter(field => values[field.key] !== baseline[field.key])
    .map(field => [field.key, values[field.key] == null ? '' : values[field.key]]))
}

/** Client feedback complements the server's atomic validation; blank numeric values reset defaults. */
export function validateKnowledgeValues(values, defaults, fields) {
  const errors = []
  const effective = { ...values }
  for (const field of fields) {
    let value = values[field.key]
    if (value === '' || value == null) value = defaults[field.key] ?? field.default
    effective[field.key] = value
    if (field.type !== 'number') continue
    const number = Number(value)
    if (!Number.isFinite(number) || (Number(field.step ?? 1) >= 1 && !Number.isInteger(number))) {
      errors.push(`${field.label}需要填写${Number(field.step ?? 1) >= 1 ? '整数' : '有效数字'}`)
    } else if ((field.min != null && number < field.min) || (field.max != null && number > field.max)) {
      errors.push(`${field.label}应在 ${field.min ?? '不限'} 至 ${field.max ?? '不限'} 之间`)
    }
  }
  const has = (...keys) => keys.every(key => effective[key] != null)
  if (has('chunkOverlap', 'chunkTarget', 'chunkMax')
    && !(effective.chunkOverlap < effective.chunkTarget && effective.chunkTarget <= effective.chunkMax)) {
    errors.push('切块重叠必须小于目标长度，目标长度不能大于长块长度')
  }
  if (has('maxPassages', 'candidates') && effective.maxPassages > effective.candidates) errors.push('注入条目数不能大于每通道候选数')
  if (has('fusedWindow', 'candidates') && effective.fusedWindow > effective.candidates) errors.push('融合重排窗口不能大于每通道候选数')
  if (has('wikiWindow', 'fusedWindow') && effective.wikiWindow >= effective.fusedWindow) errors.push('Wiki 候选数必须小于融合重排窗口')
  if (has('passageChars', 'totalChars') && effective.passageChars > effective.totalChars) errors.push('单片段长度不能大于检索上下文总预算')
  if (has('wikiChars', 'globalGraphChars', 'totalChars') && effective.wikiChars + effective.globalGraphChars > effective.totalChars - 256) {
    errors.push('Wiki 与全局图谱预算合计须为原文及来源标签保留至少 256 字符')
  }
  return [...new Set(errors)]
}

export function diagnosticPercent(value) {
  return value == null || !Number.isFinite(Number(value)) ? '—' : `${(Number(value) * 100).toFixed(1)}%`
}

export function diagnosticNumber(value, digits = 3) {
  return value == null || !Number.isFinite(Number(value)) ? '—' : Number(value).toFixed(digits)
}

export function diagnosticHitKey(hit) {
  return hit.key || hit.passageKey || `${hit.sourceType || hit.type}:${hit.sourceId ?? hit.id}:${hit.seq ?? 'source'}`
}

export const KNOWLEDGE_GROUP_LABELS = {
  chunk: '切块与索引', indexing: '切块与索引', index: '切块与索引', embedding: '向量嵌入',
  vector: '向量召回', retrieval: '召回与融合', recall: '召回与融合', fusion: '召回与融合',
  rerank: '重排', context: '回答上下文', grounding: '依据核对', sources: '辅助知识源',
}
