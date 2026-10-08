import test from 'node:test'
import assert from 'node:assert/strict'
import { nextTick, reactive, watch } from 'vue'
import { createSourceRouteReader } from './sourceRouteReader.js'

function deferred() {
  let resolve, reject
  const promise = new Promise((ok, fail) => { resolve = ok; reject = fail })
  return { promise, resolve, reject }
}

function harness(read = undefined) {
  const route = reactive({ query: { read } })
  const requests = [], jobs = [], opened = [], missing = [], errors = []
  const reader = createSourceRouteReader({
    load(id) { const request = { id, ...deferred() }; requests.push(request); return request.promise },
    onOpen(row) { opened.push(row) },
    onMissing(id) { missing.push(id) },
    onError(error) { errors.push(error.message) },
  })
  const stop = watch(() => route.query.read, value => { jobs.push(reader.open(value)) }, { immediate: true, flush: 'sync' })
  return { route, reader, requests, jobs, opened, missing, errors, stop }
}

test('an initial read link fetches its exact source without depending on the visible filtered list', async () => {
  const h = harness('5')
  assert.equal(h.requests[0].id, '5')
  const row = { id: 5, title: '筛选外的速查卡', content: '完整正文', categoryId: 3 }
  h.requests[0].resolve(row)
  await h.jobs[0]
  assert.deepEqual(h.opened, [row])
  assert.deepEqual(h.missing, [])
  h.stop()
})

test('changing read on the same mounted route opens the latest source, never a slower older one', async () => {
  const h = harness('1')
  h.route.query.read = '2'
  await nextTick()
  assert.deepEqual(h.requests.map(r => r.id), ['1', '2'])
  h.requests[1].resolve({ id: 2, title: '当前资料' })
  await h.jobs[1]
  h.requests[0].resolve({ id: 1, title: '旧资料' })
  await h.jobs[0]
  assert.deepEqual(h.opened.map(r => r.id), [2])
  h.stop()
})

test('a route change invalidates an already resolved old request before its pending callback runs', async () => {
  const h = harness('1')
  h.requests[0].resolve({ id: 1 })
  h.route.query.read = '2'
  await h.jobs[0]
  assert.deepEqual(h.opened, [])
  h.requests[1].resolve({ id: 2 })
  await h.jobs[1]
  assert.deepEqual(h.opened.map(row => row.id), [2])
  h.stop()
})

test('removing or invalidating read cancels pending links without opening an arbitrary item', async () => {
  for (const read of [undefined, null, '', '0', '-1', '2?read=3', ['2', '3']]) {
    const h = harness('1')
    h.route.query.read = read
    await nextTick()
    h.requests[0].resolve({ id: 1 })
    await Promise.all(h.jobs)
    assert.equal(h.requests.length, 1)
    assert.deepEqual(h.opened, [])
    h.stop()
  }
})

test('manual reading, closing or editing cancels pending route loads and permits later navigation', async () => {
  const h = harness('1')
  h.reader.cancel()
  h.requests[0].resolve({ id: 1 })
  await h.jobs[0]
  assert.deepEqual(h.opened, [])
  h.route.query.read = '3'
  await nextTick()
  h.requests[1].resolve({ id: 3, content: '新导航正文' })
  await h.jobs[1]
  assert.deepEqual(h.opened.map(r => r.id), [3])
  h.stop()
})

test('older missing items or errors do not open an item or invoke route callbacks after a newer source opens', async () => {
  const h = harness('1')
  h.route.query.read = '2'
  await nextTick()
  h.route.query.read = '3'
  await nextTick()
  h.requests[2].resolve({ id: 3 })
  await h.jobs[2]
  h.requests[0].resolve(null)
  h.requests[1].reject(new Error('旧来源加载失败'))
  await Promise.all(h.jobs)
  assert.deepEqual(h.opened.map(r => r.id), [3])
  assert.deepEqual(h.missing, [])
  assert.deepEqual(h.errors, [])
  h.stop()
})

test('current missing or failed sources remain retryable and mismatched detail ids never open', async () => {
  const h = harness('1')
  h.requests[0].resolve({ id: 99 })
  await h.jobs[0]
  assert.deepEqual(h.missing, ['1'])
  assert.deepEqual(h.opened, [])
  const failure = h.reader.open('1')
  h.requests[1].reject(new Error('当前来源加载失败'))
  await failure
  assert.deepEqual(h.errors, ['当前来源加载失败'])
  const retry = h.reader.open('1')
  h.requests[2].resolve({ id: 1 })
  await retry
  assert.deepEqual(h.opened.map(r => r.id), [1])
  h.stop()
})

test('unmount invalidates in-flight source loads and prevents new requests', async () => {
  const h = harness('1')
  h.reader.dispose()
  h.stop()
  h.requests[0].resolve({ id: 1 })
  await h.jobs[0]
  await h.reader.open('2')
  assert.deepEqual(h.opened, [])
  assert.equal(h.requests.length, 1)
})
