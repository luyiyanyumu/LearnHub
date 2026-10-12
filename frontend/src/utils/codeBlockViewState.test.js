import assert from 'node:assert/strict'
import test from 'node:test'
import { EditorState, Transaction } from '@codemirror/state'
import { codeBlockChange, codeBlockDocument } from './codeBlockViewState.js'

test('source normalization retains blank and trailing lines across CRLF, CR and LF', () => {
  assert.equal(codeBlockDocument('flowchart TD\r\n\r\n A-->B\r\n\r\n'), 'flowchart TD\n\n A-->B\n\n')
  assert.equal(codeBlockDocument('A\rB\n'), 'A\nB\n')
  assert.equal(codeBlockDocument(''), '')
})

test('external undo/redo replaces the smallest span and keeps a cursor outside that span', () => {
  let state = EditorState.create({ doc: 'flowchart TD\n A-->B\n B-->C\n', selection: { anchor: 5 } })
  const change = codeBlockChange(state.doc.toString(), 'flowchart TD\n A-->D\n B-->C\n')
  assert.deepEqual(change, { from: 18, to: 19, insert: 'D' })
  state = state.update({ changes: change, annotations: Transaction.addToHistory.of(false) }).state
  assert.equal(state.selection.main.anchor, 5)
  assert.equal(state.doc.toString(), 'flowchart TD\n A-->D\n B-->C\n')
  assert.equal(codeBlockChange(state.doc.toString(), state.doc.toString()), null)
})

test('cursor after external insertion follows the original text rather than resetting to document start', () => {
  let state = EditorState.create({ doc: 'A-->B\nB-->C', selection: { anchor: 9 } })
  state = state.update({ changes: codeBlockChange(state.doc.toString(), 'flowchart TD\nA-->B\nB-->C') }).state
  assert.equal(state.selection.main.anchor, 22)
  assert.equal(state.doc.sliceString(19), 'B-->C')
  const cleared = codeBlockChange(state.doc.toString(), '')
  assert.equal(state.update({ changes: cleared }).state.doc.toString(), '')
})
