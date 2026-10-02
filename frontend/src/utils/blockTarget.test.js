import assert from 'node:assert/strict'
import test from 'node:test'
import { Schema } from '@tiptap/pm/model'
import { EditorState } from '@tiptap/pm/state'
import { createBlockTarget, mapBlockTarget } from './blockTarget.js'

const schema = new Schema({
  nodes: {
    doc: { content: 'block+' },
    paragraph: { group: 'block', content: 'text*', attrs: { dataLine: { default: null } } },
    codeBlock: { group: 'block', atom: true, attrs: { code: { default: '' }, dataLine: { default: null } } },
    text: {},
  },
})
const p = text => schema.node('paragraph', null, text ? schema.text(text) : null)
const state = (...nodes) => EditorState.create({ doc: schema.node('doc', null, nodes) })

test('markup-only transactions preserve paragraph and atom block targets', () => {
  for (const node of [p('Text'), schema.node('codeBlock', { code: 'print(1)' })]) {
    const current = state(node, p('Following'))
    const target = createBlockTarget(current.doc, 0)
    const tr = current.tr.setNodeMarkup(0, undefined, { ...node.attrs, dataLine: '12' })
    assert.equal(tr.mapping.mapResult(0, 1).deleted, true)
    assert.equal(mapBlockTarget(target, tr), target)
    assert.deepEqual({ pos: target.pos, end: target.end, deleted: target.deleted }, { pos: 0, end: node.nodeSize, deleted: false })
    assert.equal(target.node, node)
  }
})

test('deleting a block invalidates the target even when an identical sibling occupies its position', () => {
  const repeated = p('Same')
  const current = state(repeated, repeated)
  const target = createBlockTarget(current.doc, 0)
  const tr = current.tr.delete(0, repeated.nodeSize)
  assert.equal(tr.mapping.mapResult(0, 1).deletedAcross, false)
  assert.equal(tr.doc.nodeAt(0).eq(target.node), true)
  mapBlockTarget(target, tr)
  assert.equal(target.deleted, true)
  assert.equal(target.end, target.pos)
  const nextTr = current.apply(tr).tr.insert(0, p('New'))
  mapBlockTarget(target, nextTr)
  assert.equal(target.deleted, true)
  assert.equal(target.pos, 0)
})

test('inserting neighboring blocks moves the target without including either sibling', () => {
  let current = state(p('Target'))
  const target = createBlockTarget(current.doc, 0)
  const leading = p('Before')
  const before = current.tr.insert(0, leading)
  mapBlockTarget(target, before)
  current = current.apply(before)
  assert.equal(target.pos, leading.nodeSize)
  assert.equal(target.end, leading.nodeSize + target.node.nodeSize)
  assert.equal(current.doc.nodeAt(target.pos).eq(target.node), true)
  const oldEnd = target.end
  const after = current.tr.insert(oldEnd, p('After'))
  mapBlockTarget(target, after)
  assert.equal(target.end, oldEnd)
  assert.equal(target.deleted, false)
})

test('editing target content updates its range while preserving the original async snapshot', () => {
  let current = state(p('Target'), p('Following'))
  const target = createBlockTarget(current.doc, 0)
  const initial = target.node
  const edit = current.tr.insertText(' new', 4)
  mapBlockTarget(target, edit)
  current = current.apply(edit)
  assert.equal(target.end, initial.nodeSize + 4)
  assert.equal(target.node, initial)
  assert.equal(current.doc.nodeAt(target.pos).eq(initial), false)
  assert.equal(target.deleted, false)
  const clearText = current.tr.delete(1, target.end - 1)
  mapBlockTarget(target, clearText)
  assert.equal(target.end - target.pos, 2)
  assert.equal(target.deleted, false)
})
