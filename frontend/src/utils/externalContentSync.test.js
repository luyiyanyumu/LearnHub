import assert from 'node:assert/strict'
import test from 'node:test'
import { Schema } from '@tiptap/pm/model'
import { EditorState } from '@tiptap/pm/state'
import { history, undo } from '@tiptap/pm/history'
import { createExternalContentSync } from './externalContentSync.js'
import { createDeferredSync } from './deferredSync.js'

function clock() {
  let now = 0, next = 0
  const tasks = new Map()
  return {
    setTimer(fn, delay) { const id = ++next; tasks.set(id, { fn, at: now + delay }); return id },
    clearTimer(id) { tasks.delete(id) },
    advance(ms) { now += ms; for (const [id, task] of [...tasks]) if (task.at <= now) { tasks.delete(id); task.fn() } },
    get size() { return tasks.size },
  }
}

function fixture({ available = true, ...options } = {}) {
  const timer = clock(), applied = []
  let current = '初始正文'
  const sync = createExternalContentSync({
    current: () => current,
    apply: value => { applied.push(value); current = value },
    available, ...timer, ...options,
  })
  return { timer, applied, sync, current: () => current }
}

test('source keystrokes coalesce into the latest full preview only after the pause', () => {
  const { timer, applied, sync } = fixture()
  for (const value of ['新的', '新的正文', '新的正文末尾']) { sync.receive(value); timer.advance(50) }
  assert.deepEqual(applied, [])
  assert.equal(timer.size, 1)
  timer.advance(100)
  assert.deepEqual(applied, ['新的正文末尾'])
  assert.equal(sync.hasPending(), false)
})

test('hidden updates do not render; restoring visibility applies the newest source once', () => {
  const { timer, applied, sync } = fixture({ available: false })
  sync.receive('隐藏时第一稿')
  timer.advance(500)
  sync.receive('隐藏时最终稿')
  timer.advance(500)
  assert.deepEqual(applied, [])
  assert.equal(timer.size, 0)
  assert.equal(sync.flush(), '隐藏时最终稿')
  assert.equal(sync.hasPending(), true)
  sync.setAvailable(true)
  sync.setAvailable(true)
  timer.advance(500)
  assert.deepEqual(applied, ['隐藏时最终稿'])
})

test('deactivation cancels a scheduled render without dropping its latest source', () => {
  const { timer, applied, sync } = fixture()
  sync.receive('待更新')
  timer.advance(100)
  sync.setAvailable(false)
  timer.advance(500)
  assert.deepEqual(applied, [])
  sync.receive('非活动时更新')
  sync.setAvailable(true)
  assert.deepEqual(applied, ['非活动时更新'])
})

test('save/tool flush uses a same-tick parent update and leaves no stale timer', () => {
  const { timer, applied, sync } = fixture()
  sync.receive('早一稿')
  sync.receive('父组件刚插入的最新稿')
  assert.equal(sync.flush(), '父组件刚插入的最新稿')
  assert.deepEqual(applied, ['父组件刚插入的最新稿'])
  timer.advance(1000)
  assert.equal(applied.length, 1)
})

test('rolling source back to the displayed value cancels an obsolete replacement', () => {
  const { timer, applied, sync, current } = fixture()
  sync.receive('随后撤回的修改')
  sync.receive(current())
  timer.advance(500)
  assert.deepEqual(applied, [])
  assert.equal(sync.hasPending(), false)
})

test('external authority cancels an older internal serialization even while hidden', () => {
  const timer = clock(), emitted = []
  const internal = createDeferredSync(() => emitted.push('旧预览草稿'), timer)
  const sync = createExternalContentSync({ current: () => '旧正文', apply: () => {},
    onExternal: internal.cancel, available: false, ...timer })
  internal.schedule()
  sync.receive('新源码')
  timer.advance(1000)
  assert.deepEqual(emitted, [])
  assert.equal(sync.flush(), '新源码')
})

test('own Markdown echo keeps the actual ProseMirror caret and undo history intact', () => {
  const schema = new Schema({ nodes: { doc: { content: 'paragraph+' },
    paragraph: { content: 'text*', group: 'block' }, text: { group: 'inline' } } })
  const doc = schema.node('doc', null, [schema.node('paragraph', null, [schema.text('A')])])
  let state = EditorState.create({ schema, doc, plugins: [history()] })
  state = state.apply(state.tr.insertText('B', 2))
  const before = state
  const timer = clock()
  let replacements = 0, cancelledDrafts = 0
  const sync = createExternalContentSync({ current: () => state.doc.textContent,
    apply: () => { replacements++ }, onExternal: () => { cancelledDrafts++ }, available: true, ...timer })
  sync.receive('AB')
  sync.flush()
  timer.advance(500)
  assert.equal(replacements, 0)
  assert.equal(cancelledDrafts, 0)
  assert.equal(state, before)
  assert.equal(undo(state, tr => { state = state.apply(tr) }), true)
  assert.equal(state.doc.textContent, 'A')
})

test('IME holds external rendering until committed and still coalesces intervening updates', () => {
  const { timer, applied, sync } = fixture()
  sync.pause()
  sync.receive('pinyin')
  sync.receive('拼音')
  timer.advance(1000)
  assert.equal(sync.flush(), '拼音')
  assert.deepEqual(applied, [])
  sync.resume()
  timer.advance(149)
  assert.deepEqual(applied, [])
  timer.advance(1)
  assert.deepEqual(applied, ['拼音'])
})

test('tool preparation applies queued source before checking a captured selection', () => {
  const schema = new Schema({ nodes: { doc: { content: 'paragraph+' },
    paragraph: { content: 'text*', group: 'block' }, text: { group: 'inline' } } })
  const document = text => schema.node('doc', null, [schema.node('paragraph', null, schema.text(text))])
  let state = EditorState.create({ schema, doc: document('旧正文') })
  let current = '旧正文', invalid = false, insertions = 0
  const captured = state.selection.getBookmark()
  const timer = clock()
  const sync = createExternalContentSync({ current: () => current, available: true, ...timer,
    apply: value => { invalid = true; current = value; state = EditorState.create({ schema, doc: document(value) }) } })
  sync.receive('刚刚切换的新源码')
  assert.equal(sync.hasPending(), true)
  if (sync.prepare() && !invalid) {
    state = state.apply(state.tr.setSelection(captured.resolve(state.doc)).insertText('工具内容'))
    insertions++
  }
  assert.equal(insertions, 0)
  assert.equal(state.doc.textContent, '刚刚切换的新源码')
  timer.advance(500)
  assert.equal(state.doc.textContent, '刚刚切换的新源码')
})

test('tools cannot mutate a hidden or composing document with pending source', () => {
  const { sync, applied } = fixture({ available: false })
  sync.receive('最新源码')
  assert.equal(sync.prepare(), false)
  assert.deepEqual(applied, [])
  sync.pause()
  sync.setAvailable(true)
  assert.equal(sync.prepare(), false)
  assert.deepEqual(applied, [])
  sync.resume()
  assert.equal(sync.prepare(), true)
  assert.deepEqual(applied, ['最新源码'])
})

test('unmount cancels a queued render and a failed apply remains available for retry', () => {
  const timer = clock(), applied = []
  let fail = true
  const sync = createExternalContentSync({ current: () => 'old', available: true, ...timer,
    apply: value => { if (fail) throw Error('synthetic failure'); applied.push(value) } })
  sync.receive('discarded')
  sync.cancel()
  timer.advance(500)
  assert.deepEqual(applied, [])
  sync.receive('retry')
  assert.throws(() => sync.flush(), /synthetic failure/)
  assert.equal(sync.hasPending(), true)
  fail = false
  sync.flush()
  assert.deepEqual(applied, ['retry'])
})
