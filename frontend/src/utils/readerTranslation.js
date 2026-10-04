/** Java's String.length and browser strings both count UTF-16 code units. */
export const DEFAULT_TRANSLATION_LIMIT = 4000
export const READER_SELECTION_IGNORE = '[data-translation-ignore], .reader-toolbar, .reader-text-head, .para-head, .doc-trans, .doc-page-sep, .pdf-page-no, .original-warnings, .original-state, .warn-line, input, textarea, button'
const BLOCK_TAGS = new Set(['P', 'DIV', 'ARTICLE', 'SECTION', 'LI', 'H1', 'H2', 'H3', 'H4', 'H5', 'H6', 'TR', 'BLOCKQUOTE', 'PRE'])

function fragmentText(node) {
  if (node.nodeType === 3) return node.textContent || ''
  if (node.nodeName === 'BR') return '\n'
  const text = Array.from(node.childNodes || []).map(fragmentText).join('')
  if (['TD', 'TH'].includes(node.nodeName)) return text + '\t'
  return text + (BLOCK_TAGS.has(node.nodeName) ? '\n' : '')
}

export function selectedReaderText(selection, root) {
  if (!selection || !root?.contains || selection.isCollapsed || !selection.rangeCount) return ''
  const ranges = []
  for (let i = 0; i < selection.rangeCount; i++) {
    const range = selection.getRangeAt(i)
    if (!root.contains(range.startContainer) || !root.contains(range.endContainer)) return ''
    for (const node of [range.startContainer, range.endContainer]) {
      const element = node.nodeType === 1 ? node : node.parentElement
      if (element?.closest?.(READER_SELECTION_IGNORE)) return ''
    }
    ranges.push(range)
  }
  // A multi-paragraph selection can cross hidden paragraph buttons, page labels,
  // or an existing inline translation even when both endpoints are original text.
  const ignored = Array.from(root.querySelectorAll?.(READER_SELECTION_IGNORE) || [])
  if (ranges.some((range) => range.intersectsNode && ignored.some((node) => range.intersectsNode(node)))) {
    return ranges.map((range) => {
      const fragment = range.cloneContents()
      fragment.querySelectorAll(READER_SELECTION_IGNORE).forEach((node) => node.remove())
      return fragmentText(fragment)
    }).join('\n').trim()
  }
  return String(selection).trim()
}

export function translationLimit(value) {
  const number = Number(value)
  return Number.isFinite(number) && number > 0 ? Math.floor(number) : DEFAULT_TRANSLATION_LIMIT
}

/** A reader-local session. No late response may replace another file, language, or selection. */
export function createReaderTranslation({ translate, onChange = () => {}, fileId, targetLang = '简体中文', maxChars } = {}) {
  let context = { fileId, targetLang, maxChars: translationLimit(maxChars) }
  let state = { text: '', translation: '', model: '', loading: false, error: '', cached: false }
  let generation = 0
  const cache = new Map()
  let pending = null
  const emit = (patch) => { state = { ...state, ...patch }; onChange({ ...state }); return state }
  const clearResult = () => ({ translation: '', model: '', loading: false, error: '', cached: false })
  const keyOf = (text) => JSON.stringify([String(context.fileId), context.targetLang, text])
  const validationError = (text) => {
    const source = text.trim()
    if (!source) return '请先在原文中选择文字，或输入要翻译的内容'
    if (source.length > context.maxChars) return `已选择 ${source.length} 字，超过单次上限 ${context.maxChars} 字，请缩小选区后再翻译`
    if (context.fileId === null || context.fileId === undefined || context.fileId === '') return '请先打开一份资料'
    return ''
  }
  return {
    get state() { return { ...state } },
    get limit() { return context.maxChars },
    setContext(next) {
      const updated = { ...context, ...next, maxChars: translationLimit(next.maxChars ?? context.maxChars) }
      const fileChanged = String(updated.fileId) !== String(context.fileId)
      const changed = fileChanged || updated.targetLang !== context.targetLang || updated.maxChars !== context.maxChars
      context = updated
      if (!changed) return
      generation++
      pending = null
      if (fileChanged) cache.clear()
      emit({ ...clearResult(), ...(fileChanged ? { text: '' } : {}), error: fileChanged || !state.text ? '' : validationError(state.text) })
    },
    selectText(value, { trim = true } = {}) {
      const original = String(value ?? '')
      const text = trim ? original.trim() : original
      if (text === state.text) return { ...state }
      generation++
      pending = null
      return emit({ text, ...clearResult(), error: text ? validationError(text) : '' })
    },
    clear() {
      generation++
      pending = null
      emit({ text: '', ...clearResult() })
    },
    async request({ force = false } = {}) {
      const text = state.text.trim()
      const error = validationError(text)
      if (error) { emit({ loading: false, error }); return null }
      const key = keyOf(text)
      if (!force && cache.has(key)) {
        const result = cache.get(key)
        emit({ ...result, loading: false, error: '', cached: true })
        return result
      }
      if (pending?.key === key) return pending.promise
      const current = ++generation
      const requestContext = { ...context }
      emit({ ...clearResult(), loading: true })
      const promise = Promise.resolve().then(() => translate(requestContext.fileId, text, requestContext.targetLang))
        .then((response) => {
          const translation = String(response?.translation ?? '').trim()
          if (!translation) throw new Error('模型没有返回译文，请重试')
          const result = { translation, model: [response?.profile, response?.model].filter(Boolean).join(' / ') }
          // Switching files also isolates caches; a response from the old file must not refill it.
          if (String(context.fileId) === String(requestContext.fileId)) {
            cache.set(key, result)
            if (cache.size > 32) cache.delete(cache.keys().next().value)
          }
          if (current === generation) emit({ ...result, loading: false, error: '', cached: false })
          return result
        })
        .catch((failure) => {
          if (current === generation) emit({ loading: false, error: failure?.message || String(failure) })
          return null
        })
        .finally(() => { if (pending?.promise === promise) pending = null })
      pending = { key, promise }
      return promise
    },
  }
}
