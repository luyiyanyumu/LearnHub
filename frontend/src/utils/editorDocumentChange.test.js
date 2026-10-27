import assert from 'node:assert/strict'
import test from 'node:test'
import { getSchema } from '@tiptap/core'
import StarterKit from '@tiptap/starter-kit'
import { EditorState, NodeSelection, TextSelection } from '@tiptap/pm/state'
import { history, undo, redo, undoDepth } from '@tiptap/pm/history'
import { MathInline, MathBlock } from './mathNodes.js'
import { DataLineAttr } from './dataLineAttr.js'
import { insertEditorDocument, replaceEditorDocument } from './editorDocumentChange.js'

const schema = getSchema([StarterKit, MathInline, MathBlock, DataLineAttr])
const p = (text, dataLine = null) => schema.node('paragraph', { dataLine }, text ? schema.text(text) : null)
const doc = (...nodes) => schema.node('doc', null, nodes)
function harness(content, selection = TextSelection.create(content, 1)) {
  let state = EditorState.create({ schema, doc: content, selection, plugins: [history()] })
  const transactions = []
  return { get state() { return state }, isDestroyed: false, transactions,
    view: { dispatch(transaction) { transaction.doc.check(); transactions.push(transaction); state = state.apply(transaction) } },
  }
}

test('a batch and adjacent typing each undo separately while preserving earlier manual edits', () => {
  const h = harness(doc(p('原文')))
  h.view.dispatch(h.state.tr.insertText('手动', 3))
  const savedDraft = h.state.doc
  const edited = doc(p('原文手动'), p('第一处 AI 补充'), p('第二处 AI 补充'))
  assert.equal(replaceEditorDocument(h, edited), true)
  assert.equal(undoDepth(h.state), 2)
  h.view.dispatch(h.state.tr.insertText('之后输入', 2))
  assert.equal(undoDepth(h.state), 3)
  assert.equal(undo(h.state, h.view.dispatch), true)
  assert.ok(h.state.doc.eq(edited))
  assert.equal(undo(h.state, h.view.dispatch), true)
  assert.ok(h.state.doc.eq(savedDraft))
  assert.equal(redo(h.state, h.view.dispatch), true)
  assert.ok(h.state.doc.eq(edited))
  assert.equal(h.transactions.filter(transaction => transaction.docChanged).length, 6)
})

test('the smallest changed range maps a reverse selection in an untouched paragraph', () => {
  const original = doc(p('甲'), p('右侧光标'))
  const h = harness(original, TextSelection.create(original, 8, 5))
  const edited = doc(p('甲新增'), p('右侧光标'))
  assert.equal(replaceEditorDocument(h, edited), true)
  assert.equal(h.state.selection.anchor, 10)
  assert.equal(h.state.selection.head, 7)
  assert.equal(h.transactions.filter(transaction => transaction.docChanged).length, 1)
  undo(h.state, h.view.dispatch)
  assert.ok(h.state.doc.eq(original))
  assert.equal(h.state.selection.anchor, 8)
  assert.equal(h.state.selection.head, 5)
})

test('source-line movements do not replace untouched paragraphs or lose the formula selection', () => {
  const formula = schema.node('mathBlock', { tex: '\\begin{aligned}\na &= b + c\\\\\nd &= e\n\\end{aligned}' })
  const original = doc(p('前言', '0'), formula, p('保留正文', '7'))
  const h = harness(original, NodeSelection.create(original, 4))
  const edited = doc(p('前言补充', '0'), formula, p('保留正文', '9'))
  assert.equal(replaceEditorDocument(h, edited), true)
  assert.ok(h.state.selection instanceof NodeSelection)
  assert.equal(h.state.selection.node.attrs.tex, formula.attrs.tex)
  assert.equal(h.state.selection.from, 6)
  assert.equal(h.state.doc.lastChild.textContent, '保留正文')
  undo(h.state, h.view.dispatch)
  assert.ok(h.state.doc.eq(original))
})

test('inserting at a stored reverse selection preserves surrounding text and undoes once', () => {
  const original = doc(p('abcd'), p('另一段'))
  const h = harness(original, TextSelection.create(original, 4, 2))
  const formula = schema.node('mathInline', { tex: 'P(A \\mid B) = \\frac{P(A \\cap B)}{P(B)}' })
  const insertion = doc(schema.node('paragraph', null, [schema.text('插入'), formula]))
  assert.equal(insertEditorDocument(h, insertion), true)
  assert.equal(h.state.doc.firstChild.textContent, 'a插入d')
  assert.ok(h.state.doc.firstChild.child(1).eq(formula))
  assert.equal(h.state.doc.lastChild.textContent, '另一段')
  assert.equal(undoDepth(h.state), 1)
  assert.equal(undo(h.state, h.view.dispatch), true)
  assert.ok(h.state.doc.eq(original))
})

test('an AI block at any heading caret follows the complete heading and undoes once', () => {
  const heading = schema.node('heading', { level: 2 }, schema.text('合成标题'))
  const original = doc(heading, p('保留正文'))
  const insertion = doc(p('新增正文'), p('第二段'))
  for (const caret of [1, 3, heading.nodeSize - 1]) {
    const h = harness(original, TextSelection.create(original, caret))
    assert.equal(insertEditorDocument(h, insertion, { block: true }), true)
    assert.ok(h.state.doc.eq(doc(heading, p('新增正文'), p('第二段'), p('保留正文'))))
    assert.equal(h.state.selection.$from.parent.textContent, '第二段')
    assert.equal(h.state.selection.$from.parentOffset, '第二段'.length)
    assert.equal(undoDepth(h.state), 1)
    assert.equal(undo(h.state, h.view.dispatch), true)
    assert.ok(h.state.doc.eq(original))
    assert.equal(h.state.selection.from, caret)
    assert.equal(redo(h.state, h.view.dispatch), true)
    assert.ok(h.state.doc.child(1).eq(p('新增正文')))
  }
})

test('heading word insertion and explicit reverse selections retain exact replacement', () => {
  const heading = schema.node('heading', { level: 2 }, schema.text('abcd'))
  const original = doc(heading)
  const word = doc(p('X'))
  const caret = harness(original, TextSelection.create(original, 3))
  assert.equal(insertEditorDocument(caret, word), true)
  assert.equal(caret.state.doc.firstChild.type.name, 'heading')
  assert.equal(caret.state.doc.firstChild.textContent, 'abXcd')
  const selection = harness(original, TextSelection.create(original, 4, 2))
  assert.equal(insertEditorDocument(selection, word, { block: true }), true)
  assert.equal(selection.state.doc.firstChild.textContent, 'aXd')
  assert.equal(undo(selection.state, selection.view.dispatch), true)
  assert.ok(selection.state.doc.eq(original))
  assert.equal(selection.state.selection.anchor, 4)
  assert.equal(selection.state.selection.head, 2)
})

test('AI heading insertion preserves code indentation and formula nodes as sibling blocks', () => {
  const heading = schema.node('heading', { level: 2 }, schema.text('程序示例'))
  const code = schema.node('codeBlock', { language: 'python' }, schema.text('def example():\n    return 1'))
  const formula = schema.node('mathBlock', { tex: 'x + y = z' })
  const original = doc(heading)
  const h = harness(original, TextSelection.create(original, 2))
  assert.equal(insertEditorDocument(h, doc(code, formula), { block: true }), true)
  assert.ok(h.state.doc.eq(doc(heading, code, formula)))
  assert.equal(undoDepth(h.state), 1)
  assert.equal(undo(h.state, h.view.dispatch), true)
  assert.ok(h.state.doc.eq(original))
})

test('no-op content and metadata-only changes create no history or dispatch', () => {
  const h = harness(doc(p('一样', '0')))
  assert.equal(replaceEditorDocument(h, doc(p('一样', '10'))), true)
  assert.equal(h.transactions.length, 0)
  assert.equal(undoDepth(h.state), 0)
  assert.equal(replaceEditorDocument({ ...h, isDestroyed: true }, doc(p('拒绝'))), false)
  assert.equal(h.transactions.length, 0)
})
