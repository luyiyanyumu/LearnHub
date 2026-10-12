import test from 'node:test'
import assert from 'node:assert/strict'
import { codeEvidenceWindow, codeSearchStatus, codeSearchStepLabel, createCodeRequestGate, createCodeSearchLoader, normalizeCodeAgenticResponse } from './codeAgenticSearch.js'

function deferred() {
  let resolve, reject
  const promise = new Promise((ok, fail) => { resolve = ok; reject = fail })
  return { promise, resolve, reject }
}

function harness() {
  const requests = []
  const state = { rows: [], agentic: null, loading: false, error: '' }
  const states = []
  function load(kind, params, signal) {
    const request = { ...deferred(), kind, params, signal }
    requests.push(request)
    return request.promise
  }
  const loader = createCodeSearchLoader({
    agenticSearch: (body, signal) => load('agentic', body, signal),
    search: (q, params, signal) => load('search', { q, ...params }, signal),
    list: (params, signal) => load('list', params, signal),
    onState(update) { Object.assign(state, update); states.push(structuredClone(state)) },
  })
  return { loader, requests, state, states }
}

test('multi-round answers retain tool steps, result reasons and exact source quotes', async () => {
  const h = harness()
  const pending = h.loader.search({ query: '  失败后怎样重试？ ', lang: 'java', repoId: 17 })
  assert.deepEqual(h.requests[0].params, { question: '失败后怎样重试？', lang: 'java', repoId: 17, limit: 8 })
  const evidence = { startLine: 2, endLine: 3, quote: '  retry();\n  return result;' }
  const steps = [{ action: 'search', query: 'retry', count: 2 }, { action: 'read', snippetId: 71, title: '合成重试代码', startLine: 1, endLine: 4 }]
  h.requests[0].resolve({ status: 'completed', model: 'synthetic-model', rounds: 2, answer: '失败后再次执行。', steps,
    results: [{ id: 71, title: '合成重试代码', reason: '包含重试调用', evidence: [evidence] }] })
  await pending
  assert.equal(h.state.loading, false)
  assert.equal(h.state.agentic.answer, '失败后再次执行。')
  assert.equal(h.state.agentic.rounds, 2)
  assert.equal(h.state.agentic.model, 'synthetic-model')
  assert.deepEqual(h.state.agentic.steps, steps)
  assert.equal(h.state.rows[0].reason, '包含重试调用')
  assert.deepEqual(h.state.rows[0].evidence, [evidence])
  assert.equal(codeSearchStepLabel(steps[0]), '检索候选')
  assert.equal(codeSearchStepLabel(steps[1]), '阅读原文')
})

test('blank natural-language input browses ordinary snippets without invoking a model', async () => {
  const h = harness()
  const pending = h.loader.search({ query: '  ', lang: 'python', unassigned: true })
  assert.equal(h.requests[0].kind, 'list')
  assert.deepEqual(h.requests[0].params, { lang: 'python', unassigned: true, mode: 'all', limit: 500 })
  h.requests[0].resolve([{ id: 1, title: '合成片段', lineCount: 3 }])
  await pending
  assert.equal(h.state.rows[0].title, '合成片段')
  assert.equal(h.state.agentic, null)
})

test('all legacy search modes retain their scope and never use agentic endpoint', async () => {
  for (const mode of ['all', 'symbol', 'keyword']) {
    const h = harness()
    const pending = h.loader.search({ query: ' retry ', mode, repoId: 23 })
    assert.equal(h.requests[0].kind, 'search')
    assert.deepEqual(h.requests[0].params, { q: 'retry', repoId: 23, mode, limit: 500 })
    h.requests[0].resolve([{ id: 8, hitSymbol: 'retry', hitLine: 2 }])
    await pending
    assert.equal(h.state.rows[0].hitLine, 2)
    assert.equal(h.state.agentic, null)
  }
})

test('a new query aborts its predecessor and ignores late results even if abort was ignored', async () => {
  const h = harness()
  const older = h.loader.search({ query: '旧问题' })
  const latest = h.loader.search({ query: '新问题' })
  assert.equal(h.requests[0].signal.aborted, true)
  h.requests[1].resolve({ status: 'limited', answer: '当前归纳', rounds: 3, results: [{ id: 2 }] })
  await latest
  const current = structuredClone(h.state)
  h.requests[0].resolve({ status: 'completed', answer: '过时归纳', results: [{ id: 1 }] })
  await older
  assert.deepEqual(h.state, current)
  assert.equal(h.state.agentic.status, 'limited')
})

test('changing context invalidates pending failures and clears displayed old search evidence', async () => {
  const h = harness()
  const old = h.loader.search({ query: '旧问题' })
  h.loader.invalidate()
  assert.equal(h.requests[0].signal.aborted, true)
  h.requests[0].reject(new Error('不应显示的过期错误'))
  await old
  assert.deepEqual(h.state, { loading: false, error: '', rows: [], agentic: null })
  const current = h.loader.search({ query: '新项目问题', repoId: 99 })
  h.requests[1].reject({ message: 'HTTP 503', response: { data: { msg: '当前服务暂时不可用' } } })
  await current
  assert.equal(h.state.error, '当前服务暂时不可用')
})

test('fallback keeps ordinary candidates visible and limited remains visibly distinct', async () => {
  const h = harness()
  const pending = h.loader.search({ query: '合成问题' })
  h.requests[0].resolve({ status: 'fallback', rounds: 0, model: '', answer: '', results: [{ id: 4, title: '普通候选' }] })
  await pending
  assert.equal(h.state.rows[0].title, '普通候选')
  assert.equal(codeSearchStatus(h.state.agentic.status).label, '普通候选')
  assert.match(codeSearchStatus('fallback').description, /模型当前不可用/)
  assert.equal(codeSearchStatus('limited').label, '检索受限')
  assert.equal(codeSearchStatus('limited').description, '本次检索未完整完成，请结合下方提示与原文判断。')
})

test('dispose aborts active requests, suppresses late failures and prevents further calls', async () => {
  const h = harness()
  const pending = h.loader.search({ query: '合成问题' })
  h.loader.dispose()
  assert.equal(h.requests[0].signal.aborted, true)
  h.requests[0].reject(new Error('卸载后失败'))
  await pending
  await h.loader.search({ query: '卸载后问题' })
  assert.equal(h.requests.length, 1)
  assert.equal(h.states.length, 1)
})

test('detail generation gate invalidates a closed or superseded detail request', () => {
  const gate = createCodeRequestGate()
  const first = gate.begin()
  const second = gate.begin()
  assert.equal(first.signal.aborted, true)
  assert.equal(first.isCurrent(), false)
  assert.equal(second.isCurrent(), true)
  gate.invalidate()
  assert.equal(second.signal.aborted, true)
  assert.equal(second.isCurrent(), false)
  gate.dispose()
  assert.equal(gate.begin(), null)
})

test('unusable evidence is excluded without promoting unknown status to completed', () => {
  const response = normalizeCodeAgenticResponse({ status: 'mystery', results: [{ id: 1, evidence: [
    { startLine: 0, endLine: 1, quote: 'zero' }, { startLine: 3, endLine: 2, quote: 'reversed' },
    { startLine: 1, endLine: 2, quote: '' }, { startLine: 1, endLine: 2, quote: 'const n = 1;' },
  ] }, null, {}] })
  assert.equal(response.status, 'limited')
  assert.equal(response.results.length, 1)
  assert.deepEqual(response.results[0].evidence, [{ startLine: 1, endLine: 2, quote: 'const n = 1;' }])
})

test('step presentation retains only tool inputs and output ranges and never hidden reasoning', () => {
  const response = normalizeCodeAgenticResponse({ steps: [{ action: 'list_files', query: 'src/', count: 2,
    reasoning_content: 'hidden internal chain', detail: 'provider internals', summary: 'not a supported field' }] })
  assert.deepEqual(response.steps, [{ action: 'list_files', query: 'src/', count: 2 }])
  assert.equal(codeSearchStepLabel(response.steps[0]), '查看文件线索')
  assert.equal(codeSearchStepLabel({ action: 'fallback' }), '回退普通检索')
  assert.equal(codeSearchStepLabel({ action: 'limit' }), '达到检索上限')
  assert.equal(codeSearchStepLabel({ action: 'repeat_skipped' }), '跳过重复检索')
})

test('source highlighting preserves exact full code and handles CRLF, final line and stale ranges', () => {
  const code = 'first\r\n  retry();\r\n  return result;\r\nlast'
  const window = codeEvidenceWindow(code, { startLine: 2, endLine: 3 })
  assert.equal(window.highlight, '  retry();\r\n  return result;')
  assert.equal(window.before + window.highlight + window.after, code)
  assert.equal(codeEvidenceWindow(code, { startLine: 4, endLine: 4 }).highlight, 'last')
  const stale = codeEvidenceWindow(code, { startLine: 4, endLine: 5 })
  assert.equal(stale.before, code)
  assert.equal(stale.startLine, null)
})

test('source ranges count blank lines and CR, CRLF or LF consistently without changing source text', () => {
  for (const separator of ['\r', '\r\n', '\n']) {
    const code = ['first', '', 'third', ''].join(separator)
    const window = codeEvidenceWindow(code, { startLine: 2, endLine: 3, quote: '\nthird' })
    assert.equal(window.startLine, 2)
    assert.equal(window.highlight, separator + 'third')
    assert.equal(window.before + window.highlight + window.after, code)
    assert.equal(window.changed, false)
  }
})

test('detail edited after search cannot highlight an old evidence range, while symbol navigation remains available', () => {
  const edited = 'first\nchanged();\nlast'
  const stale = codeEvidenceWindow(edited, { startLine: 2, endLine: 2, quote: 'original();' })
  assert.equal(stale.changed, true)
  assert.equal(stale.startLine, null)
  assert.equal(stale.before, edited)
  const moved = codeEvidenceWindow(edited, { startLine: 4, endLine: 4, quote: 'original();' })
  assert.equal(moved.changed, true)
  assert.equal(codeEvidenceWindow(edited, { startLine: 2, endLine: 2 }).highlight, 'changed();')
})
