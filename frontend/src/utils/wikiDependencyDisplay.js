import { retrievalSourcePath } from './retrievalDisplay.js'

const SOURCE_NAMES = { note: '笔记', quick_ref: '速查卡', file: '资料' }
const DEPENDENCY_STATES = {
  current: { label: '与生成时一致', tone: 'current' },
  source_changed: { label: '来源内容已变更', tone: 'changed' },
  source_missing: { label: '来源缺失', tone: 'missing' },
  page_changed: { label: '知识页已变化', tone: 'changed' },
  chunk_changed: { label: '片段已变更', tone: 'changed' },
  unknown: { label: '尚无法确认', tone: 'unknown' },
}

const REASON_LABELS = {
  legacy_no_dependencies: '旧页尚未记录生成时的原文片段，重新生成后可追踪来源变化。',
  page_missing: '知识页已删除或无法读取。',
  dependency_check_failed: '本次未能完成来源检查，可重试读取。',
  dependency_record_invalid: '生成依赖记录不完整，重新生成后可追踪来源变化。',
  dependency_record_missing: '来源依赖记录缺失，重新生成后可恢复。',
  source_changed: '来源内容与生成时不同。',
  source_missing: '来源已删除或无法读取。',
  page_changed: '当前知识页与这次生成的依赖记录不匹配。',
  chunk_changed: '当前片段与生成时不同。',
  unknown: '尚无法确认来源片段是否变化。',
}

function nonNegativeInteger(value) {
  return Number.isInteger(value) && value >= 0 ? value : null
}

function hashDescription(dependency) {
  return [
    ['来源指纹（生成时）', dependency.fullContentHash],
    ['来源指纹（当前）', dependency.currentFullContentHash],
    ['片段指纹（生成时）', dependency.chunkHash],
    ['片段指纹（当前）', dependency.currentChunkHash],
  ].filter(([, value]) => typeof value === 'string' && value.trim())
    .map(([label, value]) => `${label}：${value}`).join('\n')
}

/** Dependency hashes show freshness of generation material, never semantic verification of claims. */
export function wikiDependencyView(payload) {
  const data = payload && typeof payload === 'object' ? payload : {}
  const rows = (Array.isArray(data.dependencies) ? data.dependencies : [])
    .filter(item => item && typeof item === 'object').map((item, index) => {
      const state = DEPENDENCY_STATES[item.status] ?? DEPENDENCY_STATES.unknown
      const seq = nonNegativeInteger(item.seq)
      const sourceName = SOURCE_NAMES[item.sourceType] ?? '来源'
      return {
        key: JSON.stringify([item.sourceType, item.sourceId, seq, index]),
        sourceLabel: `${sourceName} #${item.sourceId ?? '?'}`,
        title: typeof item.title === 'string' ? item.title : '',
        position: seq == null ? '' : `第 ${seq + 1} 段`,
        heading: typeof item.heading === 'string' ? item.heading : '',
        path: item.status === 'source_missing' ? null : retrievalSourcePath(item),
        status: state.label,
        tone: state.tone,
        hashDescription: hashDescription(item),
        chunkText: typeof item.chunkText === 'string' ? item.chunkText : '',
      }
    })
  const status = data.status === 'stale' ? 'stale'
    : data.status === 'current' && data.sourceUnchanged === true && rows.length > 0
      && rows.every(row => row.tone === 'current') ? 'current' : 'unknown'
  const states = { current: '已记录片段未变化', stale: '来源依赖已变，待更新', unknown: '依赖状态待确认' }
  if (data.hasDependencies === false && data.reasons?.includes?.('legacy_no_dependencies')) {
    states.unknown = '尚未记录生成片段'
  }
  const counts = { changed: 0, missing: 0, unknown: 0 }
  rows.forEach(row => { if (row.tone in counts) counts[row.tone]++ })
  const summary = [`${rows.length} 个原文片段`, counts.changed ? `${counts.changed} 处变更` : '',
    counts.missing ? `${counts.missing} 个缺失` : '', counts.unknown ? `${counts.unknown} 个待确认` : '']
    .filter(Boolean).join(' · ')
  const coverage = data.coverage && typeof data.coverage === 'object' ? data.coverage : {}
  const selected = nonNegativeInteger(coverage.chunkCount)
  const total = nonNegativeInteger(coverage.totalChunks)
  const sources = nonNegativeInteger(coverage.sourceCount)
  const chars = nonNegativeInteger(coverage.selectedChars)
  const material = rows.length ? [sources != null ? `${sources} 个来源` : '',
    selected != null && total != null ? `读取 ${selected} / ${total} 个片段` : '',
    chars != null ? `生成素材 ${chars.toLocaleString('zh-CN')} 字` : ''].filter(Boolean).join(' · ') : ''
  const sourceCoverage = rows.length && Array.isArray(coverage.sources) ? coverage.sources
    .filter(source => source && typeof source === 'object').map((source, index) => {
      const sent = nonNegativeInteger(source.sentChunkCount), full = nonNegativeInteger(source.totalChunkCount)
      const used = nonNegativeInteger(source.usedChars), original = nonNegativeInteger(source.sourceChars)
      return {
        key: JSON.stringify([source.sourceType, source.sourceId, index]),
        label: `${SOURCE_NAMES[source.sourceType] ?? '来源'} #${source.sourceId ?? '?'}${source.title ? ` · ${source.title}` : ''}`,
        material: [sent != null && full != null ? `读取 ${sent} / ${full} 个片段` : '',
          used != null ? `生成素材 ${used.toLocaleString('zh-CN')} 字` : '',
          original != null ? `当时原文 ${original.toLocaleString('zh-CN')} 字` : ''].filter(Boolean).join(' · '),
      }
    }) : []
  const reasons = (Array.isArray(data.reasons) ? data.reasons : [])
    .filter(reason => typeof reason === 'string').map(reason => REASON_LABELS[reason]).filter(Boolean)
  if (!rows.length && !reasons.length) reasons.push('尚无可展示的原文片段依赖。')
  return { status, statusLabel: states[status], rows, summary, material, sourceCoverage, reasons: [...new Set(reasons)],
    partialMaterial: rows.length > 0 && coverage.complete === false, factVerified: false }
}

/** A slow request from a previous page must never publish dependency state for the current page. */
export function createWikiDependencyLoader({ load, onState }) {
  let generation = 0
  let disposed = false
  return {
    async read(topicKey) {
      if (disposed || !topicKey) return
      const current = ++generation
      onState({ topicKey, loading: true, error: '', data: null })
      try {
        const data = await load(topicKey)
        if (current === generation && !disposed) onState({ topicKey, loading: false, error: '', data })
      } catch (error) {
        if (current === generation && !disposed) onState({ topicKey, loading: false,
          error: error?.response?.data?.msg || error?.message || '来源依赖读取失败', data: null })
      }
    },
    clear() { generation++; if (!disposed) onState({ topicKey: '', loading: false, error: '', data: null }) },
    dispose() { disposed = true; generation++ },
  }
}
