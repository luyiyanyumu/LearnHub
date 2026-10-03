import test from 'node:test'
import assert from 'node:assert/strict'
import { matchesOpenNote, parseActionResult } from './agentNoteEdit.js'

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
