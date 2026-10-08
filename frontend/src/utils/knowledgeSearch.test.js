import test from 'node:test'
import assert from 'node:assert/strict'
import { createKnowledgeSearchLoader } from './knowledgeSearch.js'

function deferred() {
  let resolve, reject
  const promise = new Promise((ok, fail) => { resolve = ok; reject = fail })
  return { promise, resolve, reject }
}

function harness() {
  const requests = [], states = [], errors = []
  const state = { items: [], keyword: '', lastMode: 'fusion', loading: false, error: '' }
  const load = (mode, query, topK) => {
    const request = { ...deferred(), mode, query, topK }
    requests.push(request)
    return request.promise
  }
  const loader = createKnowledgeSearchLoader({
    loadFusion: (q, topK) => load('fusion', q, topK),
    loadKeyword: q => load('keyword', q),
    onState(update) { Object.assign(state, update); states.push({ ...state }) },
    onError(message, mode) { errors.push({ message, mode }) },
  })
  return { loader, requests, states, errors, state }
}

test('a delayed fusion result cannot overwrite a newer keyword query or its result mode', async () => {
  const h = harness()
  const older = h.loader.search({ query: 'ReAct', mode: 'fusion' })
  const latest = h.loader.search({ query: 'Planning', mode: 'keyword' })
  h.requests[1].resolve({ keyword: 'Planning', items: [{ type: 'note', id: 99, title: '当前词面结果' }] })
  await latest
  const result = structuredClone(h.state)
  h.requests[0].resolve([{ sourceType: 'note', sourceId: 2, title: '过时融合结果', text: 'ReAct', channels: ['graph'] }])
  await older
  assert.deepEqual(h.state, result)
  assert.equal(h.state.lastMode, 'keyword')
  assert.equal(h.state.keyword, 'Planning')
  assert.equal(h.states.length, 3)
})

test('an older failure keeps the newest request loading and never publishes an error notification', async () => {
  const h = harness()
  const older = h.loader.search({ query: 'old' })
  const latest = h.loader.search({ query: 'new' })
  h.requests[0].reject(new Error('旧检索失败'))
  await older
  assert.equal(h.state.loading, true)
  assert.equal(h.state.error, '')
  assert.deepEqual(h.errors, [])
  h.requests[1].resolve([{ sourceType: 'file', sourceId: 3, title: '当前结果', text: 'new' }])
  await latest
  assert.equal(h.state.loading, false)
  assert.equal(h.state.items[0].title, '当前结果')
})

test('query and mode are captured before await, including the newest failure and its server message', async () => {
  const h = harness()
  const options = { query: ' ReAct ', mode: 'fusion' }
  const pending = h.loader.search(options)
  options.query = 'Planning'
  options.mode = 'keyword'
  assert.equal(h.requests[0].query, 'ReAct')
  assert.equal(h.requests[0].topK, 10)
  h.requests[0].reject({ message: 'HTTP 500', response: { data: { msg: '重排服务不可用' } } })
  await pending
  assert.equal(h.state.keyword, 'ReAct')
  assert.equal(h.state.lastMode, 'fusion')
  assert.equal(h.state.loading, false)
  assert.deepEqual(h.state.items, [])
  assert.deepEqual(h.errors, [{ message: '重排服务不可用', mode: 'fusion' }])
})

test('a late failure after the latest success leaves that result and its explanation intact', async () => {
  const h = harness()
  const older = h.loader.search({ query: 'old' })
  const latest = h.loader.search({ query: 'current' })
  h.requests[1].resolve([{ sourceType: 'note', sourceId: 4, title: 'current', text: '正文', seq: 0, channels: ['graph', 'vector'], graphRelations: ['A —用于→ B'] }])
  await latest
  const result = structuredClone(h.state)
  h.requests[0].reject(new Error('late failure'))
  await older
  assert.deepEqual(h.state, result)
  assert.deepEqual(h.errors, [])
})

test('unmount invalidates pending successes and failures and prevents new requests', async () => {
  const h = harness()
  const older = h.loader.search({ query: 'old' })
  const latest = h.loader.search({ query: 'current' })
  h.loader.dispose()
  h.requests[0].resolve([{ sourceType: 'note', sourceId: 1, title: 'ignored' }])
  h.requests[1].reject(new Error('ignored failure'))
  await Promise.all([older, latest])
  await h.loader.search({ query: 'after unmount' })
  assert.equal(h.states.length, 2)
  assert.equal(h.requests.length, 2)
  assert.deepEqual(h.errors, [])
})

test('empty fusion queries use the legacy recent-knowledge response without dropping its metadata', async () => {
  const h = harness()
  const query = h.loader.search({ query: '  ', mode: 'fusion' })
  assert.equal(h.requests[0].mode, 'keyword')
  assert.equal(h.requests[0].query, '  ')
  const items = [{ type: 'file', id: 6, title: '资料', ext: 'pdf', updatedAt: '2026-10-07', categoryName: '论文' }]
  h.requests[0].resolve({ keyword: '', items })
  await query
  assert.equal(h.state.lastMode, 'keyword')
  assert.equal(h.state.items, items)
  assert.equal(h.state.keyword, '')
})

test('latest fusion results preserve ranking, evidence-window identity and actual retrieval channels', async () => {
  const h = harness()
  const pending = h.loader.search({ query: '  ReAct  ' })
  h.requests[0].resolve([
    { sourceType: 'note', sourceId: 7, title: '证据窗口', text: '<b>当前正文</b>', seq: -1, passageKey: 'note:7:window', channels: ['graph', 'keyword'], graphRelations: ['A —用于→ B', ' A —用于→ B '], category: 'Agent', score: .031 },
    { sourceType: 'note', sourceId: 7, title: '另一个段落', text: '另段', seq: 2, channels: ['vector'], score: NaN },
  ])
  await pending
  assert.equal(h.state.keyword, 'ReAct')
  assert.equal(h.state.lastMode, 'fusion')
  assert.deepEqual(h.state.items.map(x => x.title), ['证据窗口', '另一个段落'])
  assert.equal(h.state.items[0].passageKey, 'note:7:window')
  assert.equal(h.state.items[0].snippet, '当前正文')
  assert.deepEqual(h.state.items[0].channels, ['graph', 'keyword'])
  assert.deepEqual(h.state.items[0].graphRelations, ['A —用于→ B'])
  assert.equal(h.state.items[0].categoryName, 'Agent')
  assert.equal(h.state.items[1].score, null)
  assert.notEqual(h.state.items[0].passageKey, h.state.items[1].passageKey)
})

test('a current failed request can be retried successfully and clears the old error at launch', async () => {
  const h = harness()
  const failed = h.loader.search({ query: 'retry' })
  h.requests[0].reject(new Error('暂时离线'))
  await failed
  assert.equal(h.state.error, '暂时离线')
  const retry = h.loader.search({ query: 'retry' })
  assert.equal(h.state.error, '')
  assert.equal(h.state.loading, true)
  h.requests[1].resolve([])
  await retry
  assert.equal(h.state.error, '')
  assert.equal(h.state.loading, false)
  assert.equal(h.errors.length, 1)
})

test('Wiki results retain their page, section and guide metadata for navigation and evidence labeling', async () => {
  const h = harness()
  const pending = h.loader.search({ query: 'ReAct' })
  h.requests[0].resolve([{ type: 'wiki', id: 7, title: 'ReAct', text: '生成的小节内容', topicKey: 'entity-7',
    sectionKey: 'section-2', heading: 'ReAct / 循环机制', generatedGuide: true, sourceRefs: ['note:3'], channels: ['wiki'] }])
  await pending
  const [item] = h.state.items
  assert.equal(item.type, 'wiki')
  assert.equal(item.id, 7)
  assert.equal(item.topicKey, 'entity-7')
  assert.equal(item.sectionKey, 'section-2')
  assert.equal(item.heading, 'ReAct / 循环机制')
  assert.equal(item.generatedGuide, true)
  assert.deepEqual(item.sourceRefs, ['note:3'])
})
