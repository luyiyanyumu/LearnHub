import { Fragment, Slice } from '@tiptap/pm/model'
import { closeHistory } from '@tiptap/pm/history'
import { Selection } from '@tiptap/pm/state'

// Source anchors describe Markdown layout, not document content. Excluding them
// from the diff keeps an insertion from replacing every later paragraph merely
// because its source line moved. BlockPreview refreshes anchors separately.
function withoutSourceLines(node) {
  const children = []
  node.forEach(child => children.push(withoutSourceLines(child)))
  if (node.isText) return node
  const attrs = { ...node.attrs }
  if ('dataLine' in attrs) attrs.dataLine = null
  return node.type.create(attrs, Fragment.fromArray(children), node.marks)
}

function dispatchIndependentChange(editor, transaction) {
  editor.view.dispatch(closeHistory(transaction))
  // Typing immediately after an AI edit must have its own undo event too.
  editor.view.dispatch(closeHistory(editor.state.tr).setMeta('addToHistory', false))
  return true
}

/** Replace only the changed range, mapping the existing selection without focus. */
export function replaceEditorDocument(editor, nextDoc) {
  if (!editor || editor.isDestroyed || !nextDoc || editor.state.schema !== nextDoc.type.schema) return false
  const { state } = editor
  const before = withoutSourceLines(state.doc)
  const after = withoutSourceLines(nextDoc)
  const from = before.content.findDiffStart(after.content)
  if (from == null) return true
  const end = before.content.findDiffEnd(after.content)
  const overlap = from - Math.min(end.a, end.b)
  const to = end.a + Math.max(0, overlap)
  const nextTo = end.b + Math.max(0, overlap)
  return dispatchIndependentChange(editor, state.tr.replace(from, to, nextDoc.slice(from, nextTo)))
}

/** Insert parsed Markdown at the stored ProseMirror selection in one undo event. */
export function insertEditorDocument(editor, content, { block = false } = {}) {
  if (!editor || editor.isDestroyed || !content || !content.content.size) return false
  const { state } = editor
  if (editor.view?.composing || state.schema !== content.type.schema) return false
  const { selection } = state
  // An AI answer is a block of prose. At a heading caret, keep the complete
  // heading (including text after a middle caret) and insert sibling blocks.
  // Explicit selections and ordinary word insertions still use exact replacement.
  if (block && selection.empty && selection.$from.parent.type.name === 'heading') {
    const { $from } = selection
    const position = $from.after()
    const index = $from.indexAfter($from.depth - 1)
    if (!$from.node($from.depth - 1).canReplace(index, index, content.content)) return false
    const transaction = state.tr.insert(position, content.content)
    transaction.setSelection(Selection.near(transaction.doc.resolve(position + content.content.size), -1))
    return dispatchIndependentChange(editor, transaction)
  }
  const slice = Slice.maxOpen(content.content, false)
  return dispatchIndependentChange(editor, state.tr.replaceSelection(slice))
}
