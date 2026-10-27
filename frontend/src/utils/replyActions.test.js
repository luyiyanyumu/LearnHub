import test from 'node:test'
import assert from 'node:assert/strict'
import { normalizeReplyActions, followUpMessage, pendingNoteId } from './replyActions.js'

test('reply options accept only supported bounded data and leave copy to the permanent fallback', () => {
  assert.deepEqual(normalizeReplyActions(null), [])
  assert.deepEqual(normalizeReplyActions('save_note'), [])
  const valid = { id: 'follow-1', label: '按章节分批补全', kind: 'follow_up', prompt: '继续补全，并逐章确认依据。', noteId: 701 }
  assert.deepEqual(normalizeReplyActions([
    { id: 'run', label: '运行', kind: 'execute', prompt: 'alert(1)' },
    { id: 'blank', label: ' ', kind: 'save_note' },
    { id: 'no-prompt', label: '继续', kind: 'follow_up' },
    { id: 'oversized', label: '继续', kind: 'follow_up', prompt: 'a'.repeat(6001) },
    { id: 'copy', label: '复制任意内容', kind: 'copy' },
    valid, valid,
    { id: 'save', label: '另存为复习笔记', kind: 'save_note', code: 'alert(1)' },
    { id: 'merge', label: '融入这篇笔记', kind: 'merge_note', noteId: '701' },
    { id: 'insert', label: '在光标处补充', kind: 'insert_at_cursor', noteId: 'invalid' },
    { id: 'overflow', label: '更多', kind: 'save_note' },
  ]), [valid, { id: 'save', label: '另存为复习笔记', kind: 'save_note' },
    { id: 'merge', label: '融入这篇笔记', kind: 'merge_note', noteId: '701' },
    { id: 'insert', label: '在光标处补充', kind: 'insert_at_cursor' }])
})

test('follow-ups visibly carry the selected instruction and the answer they refer to', () => {
  const text = followUpMessage({ prompt: '补一组综合练习。' }, '什么是合成示例？', '这是关联的合成回答。')
  assert.match(text, /^补一组综合练习。/)
  assert.match(text, /原问题：\n什么是合成示例？/)
  assert.match(text, /回答材料：\n这是关联的合成回答。/)
})

test('all supported pending note writes expose a target without treating another tool as note editing', () => {
  assert.equal(pendingNoteId({ toolName: 'edit_note', preview: { noteId: 701 } }), 701)
  assert.equal(pendingNoteId({ toolName: 'update_note', noteId: 702 }), 702)
  assert.equal(pendingNoteId({ toolName: 'append_to_note', arguments: { note_id: 703 } }), 703)
  assert.equal(pendingNoteId({ toolName: 'append_to_note', args: { note_id: 704 } }), 704)
  assert.equal(pendingNoteId({ toolName: 'create_note', noteId: 701 }), null)
})
