import test from 'node:test'
import assert from 'node:assert/strict'
import { createReaderTranslation, selectedReaderText, translationLimit } from './readerTranslation.js'

const deferred = () => {
  let resolve
  let reject
  const promise = new Promise((yes, no) => { resolve = yes; reject = no })
  return { promise, resolve, reject }
}
const nextTurn = () => new Promise((resolve) => setImmediate(resolve))

test('selection must start and end inside the source, excluding controls and translation output', () => {
  const left = { nodeType: 3, parentElement: { closest: () => null } }
  const right = { nodeType: 3, parentElement: { closest: () => null } }
  const outside = { nodeType: 3, parentElement: { closest: () => null } }
  const button = { nodeType: 1, closest: () => ({}) }
  const root = { contains: (node) => [left, right, button].includes(node) }
  const selection = (start, end) => ({ rangeCount: 1, isCollapsed: false, getRangeAt: () => ({ startContainer: start, endContainer: end }), toString: () => '  original text  ' })
  assert.equal(selectedReaderText(selection(left, right), root), 'original text')
  assert.equal(selectedReaderText(selection(left, outside), root), '')
  assert.equal(selectedReaderText(selection(outside, right), root), '')
  assert.equal(selectedReaderText(selection(button, right), root), '')
  assert.equal(selectedReaderText(null, root), '')
  assert.equal(selectedReaderText({ ...selection(left, right), isCollapsed: true }, root), '')
})

test('a later selection wins even if the first translation returns last', async () => {
  const requests = new Map()
  const session = createReaderTranslation({ fileId: 1, translate: (_, text) => {
    const request = deferred(); requests.set(text, request); return request.promise
  } })
  session.selectText('first paragraph')
  const first = session.request()
  await nextTurn()
  session.selectText('second paragraph')
  const second = session.request()
  await nextTurn()
  requests.get('second paragraph').resolve({ translation: '第二段', model: 'mock' })
  await second
  requests.get('first paragraph').resolve({ translation: '第一段' })
  await first
  assert.equal(session.state.text, 'second paragraph')
  assert.equal(session.state.translation, '第二段')
  assert.equal(session.state.loading, false)
})

test('changing file clears source, invalidates in-flight responses, and isolates its cache', async () => {
  const old = deferred()
  let calls = 0
  const session = createReaderTranslation({ fileId: 1, translate: (id) => {
    calls++; return id === 1 ? old.promise : Promise.resolve({ translation: '文件二' })
  } })
  session.selectText('shared source')
  const first = session.request()
  await nextTurn()
  session.setContext({ fileId: 2 })
  assert.equal(session.state.text, '')
  session.selectText('shared source')
  await session.request()
  old.resolve({ translation: '文件一' })
  await first
  assert.equal(session.state.translation, '文件二')
  assert.equal(calls, 2)
})

test('language changes cannot display a result from the previous language', async () => {
  const old = deferred()
  const calls = []
  const session = createReaderTranslation({ fileId: 1, translate: (_, text, language) => {
    calls.push([text, language]); return language === '简体中文' ? old.promise : Promise.resolve({ translation: 'English translation' })
  } })
  session.selectText('source text')
  const first = session.request()
  await nextTurn()
  session.setContext({ targetLang: 'English' })
  assert.equal(session.state.translation, '')
  await session.request()
  old.resolve({ translation: '中文' })
  await first
  assert.equal(session.state.translation, 'English translation')
  session.setContext({ targetLang: '简体中文' })
  await session.request()
  assert.equal(session.state.translation, '中文')
  assert.equal(session.state.cached, true)
  assert.equal(calls.length, 2)
})

test('enforces the backend UTF-16 length limit without silently truncating', async () => {
  const received = []
  const session = createReaderTranslation({ fileId: 1, maxChars: 4000, translate: (_, text) => {
    received.push(text); return Promise.resolve({ translation: 'ok' })
  } })
  const exact = '🙂'.repeat(2000)
  session.selectText(exact)
  await session.request()
  assert.equal(received[0], exact)
  const excessive = exact + 'a'
  session.selectText(excessive)
  assert.equal(await session.request(), null)
  assert.equal(session.state.text, excessive)
  assert.match(session.state.error, /4001.*4000/)
  assert.equal(received.length, 1)
  assert.equal(translationLimit(NaN), 4000)
})

test('cache reuses exact context; explicit retranslation bypasses it; simultaneous requests share work', async () => {
  let calls = 0
  const gate = deferred()
  const session = createReaderTranslation({ fileId: 1, translate: () => { calls++; return calls === 1 ? gate.promise : Promise.resolve({ translation: '重译' }) } })
  session.selectText('same source')
  const one = session.request()
  const two = session.request()
  await nextTurn()
  assert.equal(calls, 1)
  gate.resolve({ translation: '初译' })
  await Promise.all([one, two])
  await session.request()
  assert.equal(calls, 1)
  assert.equal(session.state.cached, true)
  await session.request({ force: true })
  assert.equal(calls, 2)
  assert.equal(session.state.translation, '重译')
})

test('editing source preserves typed spaces, sends trimmed text, and old failures stay isolated', async () => {
  const old = deferred()
  const texts = []
  const session = createReaderTranslation({ fileId: 1, translate: (_, text) => {
    texts.push(text); return text === 'before' ? old.promise : Promise.resolve({ translation: '修改后' })
  } })
  session.selectText('before')
  const first = session.request()
  await nextTurn()
  session.selectText('after ', { trim: false })
  assert.equal(session.state.text, 'after ')
  await session.request()
  old.reject(new Error('old failure'))
  await first
  assert.equal(session.state.translation, '修改后')
  assert.equal(session.state.error, '')
  assert.deepEqual(texts, ['before', 'after'])
})

test('empty model output is a retryable failure, not a successful translation', async () => {
  const session = createReaderTranslation({ fileId: 1, translate: () => Promise.resolve({ translation: '   ' }) })
  session.selectText('source')
  assert.equal(await session.request(), null)
  assert.match(session.state.error, /没有返回译文/)
  assert.equal(session.state.loading, false)
})
