import { isGeneratedGuide } from './retrievalDisplay.js'

/** Do not treat skipped, malformed, or contradictory checks as verified answers. */
export function groundingDisplay(result, retrieved) {
  if (result == null) return null
  const unchecked = note => ({ status: 'unchecked', label: '未完整核验', note, unsupported: [] })
  const malformed = () => unchecked('核验结果格式不完整，请结合原文核对。')
  if (typeof result !== 'object' || Array.isArray(result)) return malformed()
  const note = typeof result.note === 'string' ? result.note.trim() : ''
  if (result.checked === false) return unchecked(note || '尚未获得完整的依据核验结果。')
  // Older responses omit checked but still contain a valid grounded/unsupported result.
  if (Object.hasOwn(result, 'checked') && result.checked !== true) return malformed()
  if (typeof result.grounded !== 'boolean' || !Array.isArray(result.unsupported)
    || result.unsupported.some(value => typeof value !== 'string' || !value.trim())
    || (result.note != null && typeof result.note !== 'string')) return malformed()
  const unsupported = [...new Set(result.unsupported.map(value => value.trim()))]
  const verified = result.grounded && unsupported.length === 0
  if (verified && Array.isArray(retrieved) && retrieved.some(isGeneratedGuide)
    && !retrieved.some(hit => ['note', 'quick_ref', 'ref', 'file'].includes(hit?.sourceType ?? hit?.type) && hit?.generatedGuide !== true)) {
    return unchecked('本轮使用了生成导览，尚缺少可核对的原文证据。')
  }
  return { status: verified ? 'verified' : 'unsupported',
    label: verified ? '依据已核对' : '部分结论缺少依据', note, unsupported }
}
