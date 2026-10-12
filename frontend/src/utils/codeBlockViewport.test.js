import assert from 'node:assert/strict'
import test from 'node:test'
import { EditorState, Transaction } from '@codemirror/state'
import { history, undo } from '@codemirror/commands'
import { codeBlockChange, codeBlockDocument } from './codeBlockViewState.js'
import { createCodeBlockViewport, setCodeBlockViewportActivity, clearCodeBlockViewportActivity } from './codeBlockViewport.js'

function harness({ observer = true, active = true } = {}) {
  const editor = { isDestroyed: false }
  const root = { name: 'preview scroll container' }
  let visible = true, nextFrame = 0, activations = 0
  const frames = new Map(), activity = []
  const element = { isConnected: true, closest: selector => selector === '.pv-scroll' ? root : null, getClientRects: () => visible ? [{}] : [] }
  let io
  class Observer {
    constructor(callback, options) { this.callback = callback; this.options = options; this.observed = []; this.disconnects = 0; io = this }
    observe(target) { this.observed.push(target) }
    disconnect() { this.disconnects++ }
  }
  setCodeBlockViewportActivity(editor, active)
  let onActivate = () => { activations++ }
  const viewport = createCodeBlockViewport({ editor, element, Observer: observer ? Observer : undefined,
    activate: () => onActivate(), onActivity: value => activity.push(value),
    schedule(callback) { frames.set(++nextFrame, callback); return nextFrame },
    cancel: handle => frames.delete(handle),
  })
  return { editor, element, root, viewport, frames, activity,
    get observer() { return io }, get activations() { return activations },
    setVisible(value) { visible = value }, setActivate(operation) { onActivate = operation },
    intersect(value) { io.callback([{ target: element, isIntersecting: value }]) },
    flush() { const pending = [...frames.values()]; frames.clear(); for (const callback of pending) callback() },
    destroy() { viewport.dispose(); clearCodeBlockViewportActivity(editor) },
  }
}

test('offscreen blocks defer work, observe the preview root and coalesce near-viewport frames', () => {
  const h = harness()
  try {
    assert.equal(h.activations, 0)
    assert.equal(h.observer.options.root, h.root)
    assert.equal(h.observer.options.rootMargin, '120px 0px')
    h.intersect(false); h.flush()
    assert.equal(h.activations, 0)
    h.intersect(true); h.intersect(true)
    assert.equal(h.frames.size, 1)
    h.flush()
    assert.equal(h.activations, 1)
    h.intersect(true); h.intersect(false); h.flush()
    assert.equal(h.activations, 1, 'leaving the viewport cancels work not started yet')
  } finally { h.destroy() }
})

test('switching to a hidden note cancels pending work and ignores queued observer events', () => {
  const h = harness()
  try {
    h.intersect(true)
    assert.equal(h.frames.size, 1)
    setCodeBlockViewportActivity(h.editor, false)
    assert.equal(h.frames.size, 0)
    assert.equal(h.observer.disconnects, 1)
    h.intersect(true); h.flush()
    assert.equal(h.activations, 0)
    assert.equal(h.viewport.request(), false)
    setCodeBlockViewportActivity(h.editor, true)
    h.flush()
    assert.equal(h.activations, 1)
    assert.deepEqual(h.activity, [true, false, true])
  } finally { h.destroy() }
})

test('an initially hidden draft neither observes nor initializes until made active', () => {
  const h = harness({ active: false })
  try {
    assert.equal(h.observer.observed.length, 0)
    assert.equal(h.frames.size, 0)
    setCodeBlockViewportActivity(h.editor, true)
    assert.equal(h.observer.observed.length, 1)
    h.intersect(true); h.flush()
    assert.equal(h.activations, 1)
  } finally { h.destroy() }
})

test('click or keyboard focus activates immediately; detached, hidden or destroyed nodes cannot activate', () => {
  const h = harness()
  try {
    assert.equal(h.viewport.request(), true)
    assert.equal(h.activations, 1)
    h.setVisible(false)
    assert.equal(h.viewport.request(), false)
    h.setVisible(true); h.element.isConnected = false
    assert.equal(h.viewport.request(), false)
    h.element.isConnected = true; h.editor.isDestroyed = true
    assert.equal(h.viewport.request(), false)
  } finally { h.destroy() }
})

test('without IntersectionObserver active blocks initialize normally and hidden drafts still wait', () => {
  const h = harness({ observer: false, active: false })
  try {
    assert.equal(h.frames.size, 0)
    setCodeBlockViewportActivity(h.editor, true)
    assert.equal(h.frames.size, 1)
    h.flush()
    assert.equal(h.activations, 1)
    setCodeBlockViewportActivity(h.editor, false)
    assert.equal(h.viewport.request(), false)
  } finally { h.destroy() }
})

test('disposing a node cancels frames and subscriptions so restoring its tab cannot revive it', () => {
  const h = harness()
  h.intersect(true)
  h.viewport.dispose()
  assert.equal(h.frames.size, 0)
  setCodeBlockViewportActivity(h.editor, false)
  setCodeBlockViewportActivity(h.editor, true)
  h.intersect(true); h.flush()
  assert.equal(h.activations, 0)
  assert.equal(h.viewport.request(), false)
  clearCodeBlockViewportActivity(h.editor)
})

test('lazy code initialization reads current attrs; later visibility changes retain selection and CodeMirror undo', () => {
  const h = harness()
  let code = 'stale source', state = null, creations = 0
  h.setActivate(() => {
    if (state) return
    creations++
    state = EditorState.create({ doc: codeBlockDocument(code), extensions: [history()] })
  })
  try {
    code = 'alpha\r\n\r\nbeta\r\n'
    h.intersect(true); h.flush()
    assert.equal(state.doc.toString(), 'alpha\n\nbeta\n')
    state = state.update({ changes: { from: 5, insert: '-draft' }, selection: { anchor: 11 } }).state
    const selected = state.selection.main.anchor
    setCodeBlockViewportActivity(h.editor, false)
    setCodeBlockViewportActivity(h.editor, true)
    h.flush()
    assert.equal(creations, 1)
    assert.equal(state.selection.main.anchor, selected)
    assert.equal(state.doc.toString(), 'alpha-draft\n\nbeta\n')
    const next = 'alpha-draft\n\nbeta updated\n'
    state = state.update({ changes: codeBlockChange(state.doc.toString(), next), annotations: Transaction.addToHistory.of(false) }).state
    assert.equal(state.selection.main.anchor, selected)
    assert.equal(undo({ state, dispatch: transaction => { state = transaction.state } }), true)
    assert.equal(state.doc.toString(), 'alpha\n\nbeta updated\n', 'external source refresh does not add an undo step or discard local undo')
  } finally { h.destroy() }
})
