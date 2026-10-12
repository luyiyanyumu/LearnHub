const SEARCH_STATUSES = new Set(['completed', 'limited', 'fallback'])

export function codeSearchStatus(status) {
  return {
    completed: { label: '检索完成', type: 'success', description: '' },
    limited: { label: '检索受限', type: 'warning', description: '本次检索未完整完成，请结合下方提示与原文判断。' },
    fallback: { label: '普通候选', type: 'info', description: '模型当前不可用，下面显示普通检索候选，可直接打开原文。' },
  }[status] || { label: '检索受限', type: 'warning', description: '检索未完整完成，请结合原文判断。' }
}

function lineNumber(value) {
  if (!['number', 'string'].includes(typeof value)) return null
  const n = Number(value)
  return Number.isSafeInteger(n) && n > 0 ? n : null
}

/** Preserve server-verified quotes verbatim. Reject unusable navigation ranges. */
export function codeEvidence(items) {
  return (Array.isArray(items) ? items : []).flatMap(item => {
    if (!item || typeof item !== 'object') return []
    const startLine = lineNumber(item.startLine)
    const endLine = lineNumber(item.endLine)
    if (!startLine || !endLine || endLine < startLine || typeof item.quote !== 'string' || !item.quote.trim()) return []
    return [{ startLine, endLine, quote: item.quote }]
  })
}

export function normalizeCodeAgenticResponse(response) {
  const body = response && typeof response === 'object' ? response : {}
  return {
    answer: typeof body.answer === 'string' ? body.answer : '',
    status: SEARCH_STATUSES.has(body.status) ? body.status : 'limited',
    model: typeof body.model === 'string' ? body.model : '',
    rounds: Number.isSafeInteger(body.rounds) && body.rounds >= 0 ? body.rounds : 0,
    steps: (Array.isArray(body.steps) ? body.steps : []).filter(step => step && typeof step === 'object').map(step =>
      Object.fromEntries(['action', 'query', 'snippetId', 'title', 'count', 'startLine', 'endLine']
        .filter(key => step[key] != null).map(key => [key, step[key]]))),
    results: (Array.isArray(body.results) ? body.results : []).filter(row => row && row.id != null).map(row => ({
      ...row,
      reason: typeof row.reason === 'string' ? row.reason : '',
      evidence: codeEvidence(row.evidence),
    })),
  }
}

export function codeSearchStepLabel(step) {
  const names = { search: '检索候选', search_code: '检索候选', list_files: '查看文件线索', read: '阅读原文', read_code: '阅读原文', read_snippet: '阅读原文',
    limit: '达到检索上限', fallback: '回退普通检索', repeat_skipped: '跳过重复检索', tool_blocked: '工具未获支持', finish: '完成归纳' }
  return Object.hasOwn(names, step.action) ? names[step.action] : '检索步骤'
}

/** A generation check still protects state if the transport ignores abort. */
export function createCodeRequestGate() {
  let generation = 0
  let controller = null
  let disposed = false
  return {
    begin() {
      if (disposed) return null
      controller?.abort()
      controller = new AbortController()
      const current = ++generation
      const signal = controller.signal
      return { signal, isCurrent: () => !disposed && current === generation && !signal.aborted }
    },
    invalidate() { generation++; controller?.abort(); controller = null },
    dispose() { disposed = true; this.invalidate() },
  }
}

/** One lifecycle for natural-language, legacy, and empty-query browsing. */
export function createCodeSearchLoader({ agenticSearch, search, list, onState }) {
  const gate = createCodeRequestGate()
  let disposed = false
  return {
    async search({ query = '', mode = 'agentic', lang = '', repoId = null, unassigned = false } = {}) {
      const request = gate.begin()
      if (!request) return
      const question = String(query ?? '').trim()
      const scope = { ...(lang ? { lang } : {}), ...(repoId != null ? { repoId } : {}), ...(unassigned ? { unassigned: true } : {}) }
      onState({ loading: true, error: '', rows: [], agentic: null })
      try {
        if (mode === 'agentic' && question) {
          const response = await agenticSearch({ question, ...scope, limit: 8 }, request.signal)
          if (!request.isCurrent()) return
          const agentic = normalizeCodeAgenticResponse(response)
          onState({ loading: false, error: '', rows: agentic.results, agentic })
        } else {
          const params = { ...scope, mode: mode === 'agentic' ? 'all' : mode, limit: 500 }
          const rows = await (question ? search(question, params, request.signal) : list(params, request.signal))
          if (!request.isCurrent()) return
          onState({ loading: false, error: '', rows: Array.isArray(rows) ? rows : [], agentic: null })
        }
      } catch (error) {
        if (!request.isCurrent()) return
        onState({ loading: false, rows: [], agentic: null,
          error: error?.response?.data?.msg || error?.message || '检索失败，请稍后重试' })
      }
    },
    invalidate() {
      gate.invalidate()
      if (!disposed) onState({ loading: false, error: '', rows: [], agentic: null })
    },
    dispose() { disposed = true; gate.dispose() },
  }
}

/** Render three text nodes instead of a DOM node per source line, even for large files. */
export function codeEvidenceWindow(code, evidence) {
  const text = typeof code === 'string' ? code : ''
  const starts = [0]
  const ends = []
  for (const separator of text.matchAll(/\r\n|\r|\n/g)) {
    ends.push(separator.index)
    starts.push(separator.index + separator[0].length)
  }
  ends.push(text.length)
  const startLine = lineNumber(evidence?.startLine)
  const endLine = lineNumber(evidence?.endLine)
  const hasQuote = typeof evidence?.quote === 'string'
  const unhighlighted = changed => ({ before: text, highlight: '', after: '', startLine: null, endLine: null, changed })
  if (!startLine || !endLine || endLine < startLine || endLine > starts.length) return unhighlighted(hasQuote)
  const from = starts[startLine - 1]
  const to = ends[endLine - 1]
  const highlight = text.slice(from, to)
  const normalizeNewlines = value => value.replace(/\r\n|\r/g, '\n')
  if (hasQuote && normalizeNewlines(evidence.quote) !== normalizeNewlines(highlight)) return unhighlighted(true)
  return { before: text.slice(0, from), highlight, after: text.slice(to), startLine, endLine, changed: false }
}
