import test from 'node:test'
import assert from 'node:assert/strict'
import {
  AGENT_NOTE_PREPARE_EVENT, AGENT_NOTE_INSERT_EVENT,
  matchesOpenNote, parseActionResult, prepareAgentNote, insertAgentNote,
} from './agentNoteEdit.js'

function eventHarness() {
  const keys = ['window', 'CustomEvent']
  const previous = new Map(keys.map(key => [key, Object.getOwnPropertyDescriptor(globalThis, key)]))
  const target = new EventTarget()
  Object.defineProperty(globalThis, 'window', { value: target, configurable: true, writable: true })
  Object.defineProperty(globalThis, 'CustomEvent', { configurable: true, writable: true,
    value: class extends Event { constructor(type, options) { super(type); this.detail = options?.detail } },
  })
  return { target, cleanup() {
    for (const [key, descriptor] of previous) {
      if (descriptor) Object.defineProperty(globalThis, key, descriptor)
      else delete globalThis[key]
    }
  } }
}

function deferred() { let resolve; const promise = new Promise(done => { resolve = done }); return { promise, resolve } }

test('a result from note A cannot overwrite note B or an unsaved new note', () => {
  assert.equal(matchesOpenNote('12', 12), true)
  assert.equal(matchesOpenNote(13, 12), false)
  assert.equal(matchesOpenNote(undefined, 12), false)
  assert.equal(matchesOpenNote(null, null), false)
})

test('failed approvals retain the concrete error and cannot be mistaken for success', () => {
  assert.equal(parseActionResult({ result: '{"ok":false,"error":"正文已变化"}' }).error, '正文已变化')
  assert.deepEqual(parseActionResult({ result: 'invalid JSON' }), {})
  assert.deepEqual(parseActionResult(undefined), {})
  assert.equal(parseActionResult({ result: { noteEdit: { noteId: 12 } } }).noteEdit.noteId, 12)
})

test('timed-out insert expires the editor request so a late save cannot insert or duplicate content', async () => {
  const h = eventHarness(), saved = deferred(), caller = new AbortController()
  let request, insertions = 0
  h.target.addEventListener(AGENT_NOTE_INSERT_EVENT, event => {
    request = event.detail
    request.respond((async () => {
      await saved.promise
      if (!request.signal.aborted) insertions++
      return { ok: true }
    })())
  })
  try {
    await assert.rejects(insertAgentNote({ noteId: 12, editorKey: 'note:12', answer: '合成插入内容', signal: caller.signal, timeoutMs: 5 }), /自动保存尚未完成/)
    assert.equal(request.signal.aborted, true)
    assert.equal(caller.signal.aborted, false, 'expiration belongs only to this request')
    saved.resolve()
    await Promise.resolve()
    await Promise.resolve()
    assert.equal(insertions, 0)
  } finally { saved.resolve(); h.cleanup() }
})

test('external cancellation aborts the editor-owned signal and already-cancelled requests never dispatch', async () => {
  const h = eventHarness(), controller = new AbortController()
  let request, dispatches = 0
  h.target.addEventListener(AGENT_NOTE_PREPARE_EVENT, event => {
    dispatches++
    request = event.detail
    request.respond(new Promise(() => {}))
  })
  try {
    const preparing = prepareAgentNote({ noteId: 12, signal: controller.signal })
    const rejected = assert.rejects(preparing, /操作已取消/)
    controller.abort()
    await rejected
    assert.equal(request.signal.aborted, true)
    await assert.rejects(prepareAgentNote({ noteId: 12, signal: controller.signal }), /操作已取消/)
    assert.equal(dispatches, 1)
  } finally { h.cleanup() }
})

test('completed preparation unlinks cancellation and clears its timeout', async () => {
  const h = eventHarness(), controller = new AbortController()
  let request
  h.target.addEventListener(AGENT_NOTE_PREPARE_EVENT, event => {
    request = event.detail
    request.respond({ noteId: 12, editorKey: 'note:12', dirty: false })
  })
  try {
    const result = await prepareAgentNote({ noteId: 12, signal: controller.signal, timeoutMs: 5 })
    assert.equal(result.noteId, 12)
    controller.abort()
    await new Promise(resolve => setTimeout(resolve, 10))
    assert.equal(request.signal.aborted, false)
  } finally { h.cleanup() }
})

test('an unclaimed request fails promptly and marks its signal expired', async () => {
  const h = eventHarness()
  let request
  h.target.addEventListener(AGENT_NOTE_PREPARE_EVENT, event => { request = event.detail })
  try {
    await assert.rejects(prepareAgentNote({ noteId: 12 }), /当前笔记已切换/)
    assert.equal(request.signal.aborted, true)
  } finally { h.cleanup() }
})
