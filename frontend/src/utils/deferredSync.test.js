import assert from 'node:assert/strict'
import test from 'node:test'
import { createDeferredSync } from './deferredSync.js'

function clock() {
  let now = 0, id = 0
  const timers = new Map()
  return {
    setTimer(fn, delay) { const key = ++id; timers.set(key, { fn, at: now + delay }); return key },
    clearTimer(key) { timers.delete(key) },
    advance(ms) { now += ms; for (const [key, task] of [...timers]) if (task.at <= now) { timers.delete(key); task.fn() } },
    get size() { return timers.size },
  }
}

test('a burst of input renders immediately but serializes only its latest value after the pause', () => {
  const timer = clock(), saved = []
  let content = ''
  const sync = createDeferredSync(() => saved.push(content), timer)
  for (const value of ['a', 'ab', 'abc']) { content = value; sync.schedule(); timer.advance(100) }
  assert.deepEqual(saved, [])
  assert.equal(timer.size, 1)
  timer.advance(150)
  assert.deepEqual(saved, ['abc'])
  assert.equal(sync.hasPending(), false)
})

test('save/export flush captures the final keystroke immediately with no later duplicate', () => {
  const timer = clock(), saved = []
  let content = '最后一个字'
  const sync = createDeferredSync(() => saved.push(content), timer)
  sync.schedule()
  sync.flush()
  assert.deepEqual(saved, [content])
  assert.equal(timer.size, 0)
  timer.advance(1000)
  assert.equal(saved.length, 1)
  content += '保存中继续输入'
  sync.schedule()
  assert.equal(sync.hasPending(), true)
  sync.flush()
  assert.deepEqual(saved, ['最后一个字', content])
})

test('IME composition pauses conversion until text has been committed', () => {
  const timer = clock(), saved = []
  let content = 'p'
  const sync = createDeferredSync(() => saved.push(content), timer)
  sync.schedule()
  sync.pause()
  for (const value of ['pin', '拼']) { content = value; sync.schedule(); timer.advance(1000) }
  assert.deepEqual(saved, [])
  sync.resume()
  timer.advance(249)
  assert.deepEqual(saved, [])
  timer.advance(1)
  assert.deepEqual(saved, ['拼'])
})

test('external content replacement/unmount cancels an older draft and failed conversion stays pending', () => {
  const timer = clock()
  let calls = 0, fail = true
  const sync = createDeferredSync(() => { calls++; if (fail) throw new Error('convert failed') }, timer)
  sync.schedule()
  sync.cancel()
  timer.advance(1000)
  assert.equal(calls, 0)
  sync.schedule()
  assert.throws(() => sync.flush(), /convert failed/)
  assert.equal(sync.hasPending(), true)
  fail = false
  sync.flush()
  assert.equal(sync.hasPending(), false)
  assert.equal(calls, 2)
})
