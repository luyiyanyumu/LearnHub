import assert from 'node:assert/strict'
import test from 'node:test'
import { Schema } from '@tiptap/pm/model'
import { EditorState, TextSelection } from '@tiptap/pm/state'
import { dataLineUpdates } from './dataLineRefresh.js'

const schema = new Schema({
  nodes: {
    doc: { content: 'block+' },
    text: { group: 'inline' },
    paragraph: { group: 'block', content: 'inline*', attrs: { dataLine: { default: null } } },
    heading: { group: 'block', content: 'inline*', attrs: { dataLine: { default: null }, level: { default: 2 } } },
  },
})
const block = (type, text, line) => schema.node(type, { dataLine: line }, schema.text(text))
const doc = (...nodes) => schema.node('doc', null, nodes)

test('updates shifted anchors while preserving content and selection', () => {
  const current = doc(block('heading', 'Start', '0'), block('paragraph', 'New paragraph', null), block('heading', 'Later', '2'))
  const rendered = doc(block('heading', 'Start', '0'), block('paragraph', 'New paragraph', '2'), block('heading', 'Later', '4'))
  const state = EditorState.create({ doc: current, selection: TextSelection.create(current, 9, 14) })
  const tr = state.tr
  for (const { pos, node, line } of dataLineUpdates(current, rendered)) {
    tr.setNodeMarkup(pos, undefined, { ...node.attrs, dataLine: line })
  }
  const next = state.apply(tr)
  assert.equal(next.doc.textContent, current.textContent)
  assert.equal(next.selection.from, state.selection.from)
  assert.equal(next.selection.to, state.selection.to)
  assert.equal(next.doc.child(0).attrs.dataLine, '0')
  assert.equal(next.doc.child(1).attrs.dataLine, '2')
  assert.equal(next.doc.child(2).attrs.dataLine, '4')
})

test('clears ambiguous anchors if serialization merges repeated blocks', () => {
  const current = doc(block('paragraph', 'Repeated', '0'), block('paragraph', 'Repeated', '2'), block('heading', 'Later', '4'))
  const rendered = doc(block('paragraph', 'Repeated', '0'), block('heading', 'Later', '2'))
  assert.deepEqual(dataLineUpdates(current, rendered).map(({ line }) => line), [null, null, '2'])
})

test('keeps repeated anchors mapped by order when the structure is unchanged', () => {
  const current = doc(block('paragraph', 'Repeated', '0'), block('paragraph', 'Repeated', '2'))
  const rendered = doc(block('paragraph', 'Repeated', '3'), block('paragraph', 'Repeated', '5'))
  assert.deepEqual(dataLineUpdates(current, rendered).map(({ line }) => line), ['3', '5'])
})
