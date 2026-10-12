import test from 'node:test'
import assert from 'node:assert/strict'
import { postChatStream } from './chatStream.js'

const encoder = new TextEncoder()

function deferred() {
  let resolve, reject
  const promise = new Promise((yes, no) => { resolve = yes; reject = no })
  return { promise, resolve, reject }
}

function stream(chunks = [], overrides = {}) {
  const queue = chunks.map(value => typeof value === 'string' ? encoder.encode(value) : value)
  const calls = { reads: 0, cancels: 0, releases: 0 }
  const reader = {
    async read() {
      calls.reads++
      if (queue.length) return { done: false, value: queue.shift() }
      return { done: true }
    },
    async cancel() { calls.cancels++ },
    releaseLock() { calls.releases++ },
    ...overrides,
  }
  const response = { ok: true, headers: { get: () => 'text/event-stream; charset=UTF-8' }, body: { getReader: () => reader } }
  return { calls, reader, response, fetcher: async () => response }
}

async function flush() { for (let i = 0; i < 8; i++) await Promise.resolve() }

test('decodes split UTF-8 and CRLF boundaries and joins multiline data without corrupting Chinese or emoji', async () => {
  const encoded = encoder.encode(': heartbeat\r\nid: synthetic-event\r\nretry: 1000\r\nevent: progress\r\ndata: {\r\ndata: "id":"step-1",\r\ndata: "title":"读取示例笔记 📖"\r\ndata: }\r\n\r\nevent: result\r\ndata: {"answer":"示例结果"}\r\n\r\n')
  const s = stream(Array.from(encoded, byte => Uint8Array.of(byte)))
  const events = []
  const result = await postChatStream('/synthetic/chat', { message: '示例问题' }, (...event) => events.push(event), undefined, s.fetcher)
  assert.deepEqual(events, [['progress', { id: 'step-1', title: '读取示例笔记 📖' }], ['result', { answer: '示例结果' }]])
  assert.deepEqual(result, { answer: '示例结果' })
  assert.equal(s.calls.cancels, 1)
  assert.equal(s.calls.releases, 1)
})

test('dispatches progress immediately and returns a result without waiting for the server to close its stream', async () => {
  const secondChunk = deferred()
  const events = []
  let reads = 0
  const s = stream([], {
    read() {
      reads++
      if (reads === 1) return Promise.resolve({ done: false, value: encoder.encode('event: progress\ndata: {"title":"检索示例资料"}\n\n') })
      if (reads === 2) return secondChunk.promise
      assert.fail('a terminal result must not request another chunk')
    },
  })
  let finished = false
  const request = postChatStream('/synthetic/chat', {}, (...event) => events.push(event), undefined, s.fetcher).then(result => { finished = true; return result })
  await flush()
  assert.deepEqual(events, [['progress', { title: '检索示例资料' }]])
  assert.equal(finished, false)
  secondChunk.resolve({ done: false, value: encoder.encode('event: result\ndata: {"answer":"已完成"}\n\nevent: progress\ndata: {"title":"忽略结束后的事件"}\n\n') })
  assert.deepEqual(await request, { answer: '已完成' })
  assert.equal(events.length, 2)
  assert.equal(reads, 2)
  assert.equal(s.calls.cancels, 1)
  assert.equal(s.calls.releases, 1)
})

test('posts JSON with the supplied AbortSignal and requested SSE content type', async () => {
  const controller = new AbortController()
  const s = stream(['event: result\ndata: {"ok":true}\n\n'])
  let request
  await postChatStream('/synthetic/chat', { message: '示例', sessionId: 'synthetic-session' }, null, controller.signal,
    async (path, options) => { request = { path, options }; return s.response })
  assert.equal(request.path, '/synthetic/chat')
  assert.equal(request.options.method, 'POST')
  assert.equal(request.options.signal, controller.signal)
  assert.equal(request.options.headers.Accept, 'text/event-stream')
  assert.equal(request.options.headers['Content-Type'], 'application/json')
  assert.deepEqual(JSON.parse(request.options.body), { message: '示例', sessionId: 'synthetic-session' })
})

test('reports HTTP errors with status for callers to decide explicit compatibility fallback', async () => {
  for (const [body, message] of [[{ msg: '示例接口不存在' }, '示例接口不存在'], [{ message: '示例接口未开放' }, '示例接口未开放']]) {
    await assert.rejects(postChatStream('/synthetic/chat', {}, null, undefined, async () => ({ ok: false, status: 404, json: async () => body })),
      error => error.message === message && error.status === 404)
  }
  await assert.rejects(postChatStream('/synthetic/chat', {}, null, undefined, async () => ({
    ok: false, status: 502, json: async () => { throw new SyntaxError('proxy returned HTML') },
  })), error => error.status === 502 && /HTTP 502/.test(error.message))
})

test('rejects successful non-stream responses and unavailable bodies instead of treating them as a completed answer', async () => {
  await assert.rejects(postChatStream('/synthetic/chat', {}, null, undefined, async () => ({ ok: true, headers: { get: () => 'application/json' } })), /服务未返回对话进度/)
  await assert.rejects(postChatStream('/synthetic/chat', {}, null, undefined, async () => ({ ok: true, headers: { get: () => 'text/event-stream' }, body: null })), /无法读取流式响应/)
})

test('EOF without a terminal result reports uncertainty and does not retry the operation', async () => {
  let attempts = 0
  const events = []
  const s = stream(['event: progress\ndata: {"title":"已收到写入请求"}\n\n'])
  await assert.rejects(postChatStream('/synthetic/chat', {}, (...event) => events.push(event), undefined,
    async () => { attempts++; return s.response }), /连接已中断.*后台可能仍在处理/)
  assert.equal(attempts, 1)
  assert.equal(events.length, 1)
  assert.equal(s.calls.cancels, 1)
  assert.equal(s.calls.releases, 1)
})

test('malformed event JSON and truncated JSON at EOF fail visibly and release the reader', async () => {
  for (const wire of ['event: progress\ndata: not-json\n\n', 'event: result\ndata: {"answer":"truncated']) {
    const s = stream([wire])
    const events = []
    await assert.rejects(postChatStream('/synthetic/chat', {}, (...event) => events.push(event), undefined, s.fetcher), /对话进度格式错误/)
    assert.deepEqual(events, [])
    assert.equal(s.calls.cancels, 1)
    assert.equal(s.calls.releases, 1)
  }
})

test('server error events stop processing and preserve the provided failure message', async () => {
  for (const payload of [{ message: '示例检索失败' }, { error: '示例检索失败' }]) {
    const s = stream([`event: error\ndata: ${JSON.stringify(payload)}\n\nevent: result\ndata: {"answer":"must not appear"}\n\n`])
    const events = []
    await assert.rejects(postChatStream('/synthetic/chat', {}, (...event) => events.push(event), undefined, s.fetcher), /示例检索失败/)
    assert.deepEqual(events, [])
    assert.equal(s.calls.cancels, 1)
    assert.equal(s.calls.releases, 1)
  }
})

test('an already aborted signal cancels the opened reader without consuming events', async () => {
  const controller = new AbortController()
  controller.abort()
  const s = stream(['event: result\ndata: {"answer":"must not appear"}\n\n'])
  await assert.rejects(postChatStream('/synthetic/chat', {}, null, controller.signal, s.fetcher), { name: 'AbortError' })
  assert.equal(s.calls.reads, 0)
  assert.equal(s.calls.cancels, 1)
  assert.equal(s.calls.releases, 1)
})

test('abort during a fetch-backed pending read propagates AbortError and always releases its lock', async () => {
  const controller = new AbortController()
  const waitingRead = deferred()
  controller.signal.addEventListener('abort', () => waitingRead.reject(new DOMException('stopped', 'AbortError')), { once: true })
  const s = stream([], { read: () => waitingRead.promise })
  const request = postChatStream('/synthetic/chat', {}, null, controller.signal, s.fetcher)
  await flush()
  controller.abort()
  await assert.rejects(request, { name: 'AbortError' })
  assert.equal(s.calls.cancels, 1)
  assert.equal(s.calls.releases, 1)
})

test('reader cleanup survives a failed cancel and a callback exception', async () => {
  const s = stream(['event: progress\ndata: {"title":"示例步骤"}\n\n'], {
    cancel: async () => { throw new Error('already disconnected') },
  })
  const callbackError = new Error('synthetic view callback failed')
  await assert.rejects(postChatStream('/synthetic/chat', {}, () => { throw callbackError }, undefined, s.fetcher), error => error === callbackError)
  assert.equal(s.calls.releases, 1)
})

test('network read failures are propagated once and still cancel and release the reader', async () => {
  const networkError = new TypeError('synthetic network disconnected')
  const s = stream([], { read: async () => { throw networkError } })
  await assert.rejects(postChatStream('/synthetic/chat', {}, null, undefined, s.fetcher), error => error === networkError)
  assert.equal(s.calls.cancels, 1)
  assert.equal(s.calls.releases, 1)
})
