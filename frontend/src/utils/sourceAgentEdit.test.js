import assert from 'node:assert/strict'
import test from 'node:test'
import { EditorState } from '@codemirror/state'
import { history, undo, redo, undoDepth } from '@codemirror/commands'
import { insertSourceContent, replaceSourceContent } from './sourceAgentEdit.js'

function harness(doc = '原始笔记', selection = { anchor: 2 }) {
  const transactions = []
  const view = { composing: false, state: EditorState.create({ doc, selection, extensions: [history()] }),
    dispatch(spec) { const transaction = spec.state ? spec : view.state.update(spec); transactions.push(transaction); view.state = transaction.state },
  }
  return { view, transactions }
}

test('source replacements and insertions reject an unfinished composition without changing selection or history', () => {
  for (const operation of [replaceSourceContent, insertSourceContent]) {
    const h = harness('未完成拼音', { anchor: 4, head: 2 })
    h.view.composing = true
    const original = h.view.state
    assert.equal(operation(h.view, 'AI 合成内容'), false)
    assert.equal(h.view.state, original)
    assert.equal(h.transactions.length, 0)
    assert.equal(undoDepth(h.view.state), 0)
    h.view.composing = false
    assert.equal(operation(h.view, 'AI 合成内容'), true)
    assert.equal(h.transactions.length, 1)
  }
})

test('a source AI batch and typing on either side remain separate undo steps', () => {
  const h = harness('原文', { anchor: 2 })
  h.view.dispatch({ changes: { from: 2, insert: '手写' } })
  assert.equal(replaceSourceContent(h.view, '原文手写\n\n第一处 AI\n\n第二处 AI'), true)
  const generated = h.view.state.doc.toString()
  h.view.dispatch({ changes: { from: h.view.state.doc.length, insert: '继续输入' } })
  assert.equal(undoDepth(h.view.state), 3)
  assert.equal(undo(h.view), true)
  assert.equal(h.view.state.doc.toString(), generated)
  assert.equal(undo(h.view), true)
  assert.equal(h.view.state.doc.toString(), '原文手写')
  assert.equal(redo(h.view), true)
  assert.equal(h.view.state.doc.toString(), generated)
})

test('source insertion replaces the saved reverse selection and restores it when undone', () => {
  const h = harness('abcdef', { anchor: 4, head: 2 })
  assert.equal(insertSourceContent(h.view, '$$\nx = y + 1\n$$'), true)
  assert.equal(h.view.state.doc.toString(), 'ab$$\nx = y + 1\n$$ef')
  assert.equal(h.view.state.selection.main.anchor, 17)
  assert.equal(undo(h.view), true)
  assert.equal(h.view.state.doc.toString(), 'abcdef')
  assert.equal(h.view.state.selection.main.anchor, 4)
  assert.equal(h.view.state.selection.main.head, 2)
})

test('AI blocks at ATX heading carets follow the full heading including closing markers', () => {
  const original = '## 合成标题 ##\n\n保留正文'
  const titleEnd = original.indexOf('\n')
  for (const caret of [0, 5, titleEnd]) {
    const h = harness(original, { anchor: caret })
    assert.equal(insertSourceContent(h.view, '新增正文', { block: true }), true)
    assert.equal(h.view.state.doc.toString(), '## 合成标题 ##\n\n新增正文\n\n保留正文')
    assert.equal(h.view.state.selection.main.anchor, titleEnd + '\n\n新增正文'.length)
    assert.equal(undoDepth(h.view.state), 1)
    assert.equal(undo(h.view), true)
    assert.equal(h.view.state.doc.toString(), original)
    assert.equal(h.view.state.selection.main.anchor, caret)
    assert.equal(redo(h.view), true)
    assert.match(h.view.state.doc.toString(), /##\n\n新增正文\n\n保留正文$/)
  }
})

test('AI blocks after Setext heading carets keep all title lines and underline together', () => {
  const original = '合成标题\n续行标题\n--------\n保留正文'
  for (const caret of [2, 7, original.indexOf('----') + 2]) {
    const h = harness(original, { anchor: caret })
    assert.equal(insertSourceContent(h.view, '第一段\n\n第二段', { block: true }), true)
    assert.equal(h.view.state.doc.toString(), '合成标题\n续行标题\n--------\n\n第一段\n\n第二段\n\n保留正文')
    assert.equal(undo(h.view), true)
    assert.equal(h.view.state.doc.toString(), original)
  }
})

test('heading AI blocks preserve supplied blank lines and code indentation', () => {
  const h = harness('## 程序示例\n\n保留正文', { anchor: 5 })
  const content = '\n\n    def example():\n        return 1\n\n'
  assert.equal(insertSourceContent(h.view, content, { block: true }), true)
  assert.equal(h.view.state.doc.toString(), '## 程序示例' + content + '\n\n保留正文')
  assert.equal(h.view.state.selection.main.anchor, '## 程序示例'.length + content.length)
  const atEnd = harness('## 程序示例', { anchor: 3 })
  assert.equal(insertSourceContent(atEnd.view, '    return 1', { block: true }), true)
  assert.equal(atEnd.view.state.doc.toString(), '## 程序示例\n\n    return 1')
})

test('source word edits and explicit selections in headings still replace exact text', () => {
  const original = '## abcd\n\n正文'
  const exact = harness(original, { anchor: 5 })
  assert.equal(insertSourceContent(exact.view, 'X'), true)
  assert.equal(exact.view.state.doc.toString(), '## abXcd\n\n正文')
  const selected = harness(original, { anchor: 6, head: 4 })
  assert.equal(insertSourceContent(selected.view, 'X', { block: true }), true)
  assert.equal(selected.view.state.doc.toString(), '## aXd\n\n正文')
  assert.equal(undo(selected.view), true)
  assert.equal(selected.view.state.doc.toString(), original)
  assert.equal(selected.view.state.selection.main.anchor, 6)
  assert.equal(selected.view.state.selection.main.head, 4)
})

test('heading-like lines inside code or formulas keep precise caret insertion', () => {
  for (const original of ['```markdown\n## 合成标题\n```', '$$\n## 合成标题\n$$', '    ## 合成标题']) {
    const caret = original.indexOf('标题')
    const h = harness(original, { anchor: caret })
    assert.equal(insertSourceContent(h.view, 'X', { block: true }), true)
    assert.equal(h.view.state.doc.toString(), original.slice(0, caret) + 'X' + original.slice(caret))
  }
})

test('unchanged source replacement creates no undo event and invalid views are rejected', () => {
  const h = harness()
  assert.equal(replaceSourceContent(h.view, '原始笔记'), true)
  assert.equal(h.transactions.length, 0)
  for (const value of [null, {}, { state: h.view.state, dispatch: true }]) {
    assert.equal(replaceSourceContent(value, '拒绝'), false)
    assert.equal(insertSourceContent(value, '拒绝'), false)
  }
})
