import { Fragment } from '@tiptap/pm/model'
import { NodeSelection, Selection, TextSelection } from '@tiptap/pm/state'
import { lift, toggleMark, wrapIn } from '@tiptap/pm/commands'
import { wrapInList } from '@tiptap/pm/schema-list'
import { closeHistory, redo, undo } from '@tiptap/pm/history'

const inlineNames = { bold: 'bold', italic: 'italic', strike: 'strike', underline: 'underline', inlineCode: 'code' }

function ready(editor) {
  return Boolean(editor && !editor.isDestroyed && editor.state?.doc && editor.view?.dispatch)
}

function dispatch(editor, transaction) {
  if (!transaction.docChanged && !transaction.storedMarksSet && transaction.selection.eq(editor.state.selection)) return false
  transaction.doc.check()
  editor.view.dispatch(closeHistory(transaction).scrollIntoView())
  return true
}

function command(editor, operation) {
  return Boolean(operation(editor.state, transaction => editor.view.dispatch(closeHistory(transaction).scrollIntoView()), editor.view))
}

function inlineSelection(editor) {
  const { selection } = editor.state
  // A code block is an atom containing a separate CodeMirror editor. Formatting
  // its outer NodeSelection would replace the complete code block with prose.
  return !(selection instanceof NodeSelection && selection.node.isBlock)
    && selection.$from.parent.inlineContent && selection.$to.parent.inlineContent
}

function selectedText(state) {
  if (state.selection instanceof NodeSelection && state.selection.node.isBlock) return ''
  return state.doc.textBetween(state.selection.from, state.selection.to, '\n', '\n')
}

function selectedInline(state, fallback) {
  const { selection, schema } = state
  if (!selection.empty && selection.$from.sameParent(selection.$to) && selection.$from.parent.inlineContent) {
    return state.doc.slice(selection.from, selection.to).content
  }
  const text = selectedText(state) || fallback || ''
  const nodes = []
  text.split('\n').forEach((line, index) => {
    if (index && schema.nodes.hardBreak) nodes.push(schema.nodes.hardBreak.create())
    if (line) nodes.push(schema.text(line))
  })
  return Fragment.fromArray(nodes)
}

function blockFor(state, name, arg) {
  const { schema } = state
  const paragraph = content => schema.nodes.paragraph?.create(null, content)
  switch (name) {
    case 'codeBlock': {
      const type = schema.nodes.codeBlock
      if (!type) return null
      const code = selectedText(state)
      const language = typeof arg === 'string' ? arg : null
      return type.isLeaf ? type.create({ code, language }) : type.create({ language }, code ? schema.text(code) : null)
    }
    case 'hr': return schema.nodes.horizontalRule?.create() || null
    case 'table': {
      const { table, tableRow, tableHeader, tableCell } = schema.nodes
      if (!table || !tableRow || !tableHeader || !tableCell || !schema.nodes.paragraph) return null
      const row = (cell, labels) => tableRow.create(null, labels.map(text => cell.create(null, paragraph(text ? schema.text(text) : null))))
      return table.create(null, [row(tableHeader, ['列A', '列B']), row(tableCell, ['', ''])])
    }
    case 'todo': {
      const { taskList, taskItem } = schema.nodes
      if (!taskList || !taskItem) return null
      return taskList.create(null, taskItem.create({ checked: false }, paragraph(selectedInline(state, '任务'))))
    }
    case 'details': {
      const { details, summary } = schema.nodes
      if (!details || !summary) return null
      return details.create(null, [summary.create({ title: '点击展开' }), paragraph(selectedInline(state, '折叠内容'))])
    }
    case 'callout': return schema.nodes.callout?.create({ type: 'tip' }, paragraph(selectedInline(state, '提示内容'))) || null
    default: return null
  }
}

/** ProseMirror may copy paragraph attrs when fitting a block into its middle. */
function clearSplitIdentities(transaction) {
  const seen = new Set()
  const duplicates = []
  transaction.doc.descendants((node, pos) => {
    if (!node.isTextblock || !node.attrs.blockId) return
    if (seen.has(node.attrs.blockId)) duplicates.push({ node, pos })
    else seen.add(node.attrs.blockId)
  })
  for (const { node, pos } of duplicates) {
    transaction.setNodeMarkup(pos, undefined, { ...node.attrs, blockId: null, dataLine: null })
  }
}

function insertedPosition(transaction, node, from, to) {
  const start = transaction.mapping.map(from, -1)
  const ranges = []
  transaction.mapping.maps.forEach((map, index) => {
    const later = transaction.mapping.slice(index + 1)
    map.forEach((_oldFrom, _oldTo, newFrom, newTo) => {
      ranges.push({ from: later.map(newFrom, -1), to: later.map(newTo, 1) })
    })
  })
  const matches = []
  transaction.doc.descendants((candidate, pos) => {
    if (ranges.some(range => pos >= range.from && pos < range.to) && candidate.eq(node)) matches.push(pos)
  })
  return matches.length ? matches.reduce((best, pos) => Math.abs(pos - start) < Math.abs(best - start) ? pos : best) : null
}

function selectInserted(transaction, node, pos) {
  let textPosition = null
  if (node.isTextblock) textPosition = pos + 1
  else node.descendants((child, offset) => {
    if (textPosition != null) return false
    if (child.isTextblock) { textPosition = pos + 1 + offset + 1; return false }
    return true
  })
  if (textPosition != null) transaction.setSelection(TextSelection.create(transaction.doc, textPosition))
  else if (NodeSelection.isSelectable(node)) transaction.setSelection(NodeSelection.create(transaction.doc, pos))
  else transaction.setSelection(Selection.near(transaction.doc.resolve(pos + node.nodeSize), 1))
}

function insertBlock(editor, name, arg) {
  const node = blockFor(editor.state, name, arg)
  if (!node) return false
  node.check()
  const { selection } = editor.state
  const transaction = editor.state.tr
  let from = selection.from
  let to = selection.to
  // A selected atom has no text cursor inside its model. Its exact boundary is
  // the next insertion point; keep the original atom instead of deleting it.
  if (selection instanceof NodeSelection && selection.node.isBlock) {
    from = to = selection.to
    transaction.setSelection(Selection.near(transaction.doc.resolve(to), 1))
    transaction.insert(to, node)
  } else {
    transaction.replaceSelectionWith(node, false)
  }
  if (!transaction.docChanged) return false
  const pos = insertedPosition(transaction, node, from, to)
  // No fallback to document end: an unfittable insertion leaves content intact.
  if (pos == null) return false
  clearSplitIdentities(transaction)
  selectInserted(transaction, transaction.doc.nodeAt(pos), pos)
  return dispatch(editor, transaction)
}

function nativeToggle(editor, method) {
  if (typeof editor.chain !== 'function') return null
  const chain = editor.chain()
  if (typeof chain[method] !== 'function') return null
  return chain.command(({ tr }) => { closeHistory(tr); return true })[method]().run()
}

function link(editor, value) {
  if (!inlineSelection(editor)) return false
  const href = String(value || '').trim()
  if (!href || /[<>\s]/.test(href) || !/^(https?:\/\/|mailto:|tel:|\/|#)/i.test(href)) return false
  const mark = editor.state.schema.marks.link
  if (!mark) return false
  const { selection } = editor.state
  const transaction = editor.state.tr
  if (selection.empty) transaction.replaceSelectionWith(editor.state.schema.text(href, [mark.create({ href })]), false)
  else transaction.addMark(selection.from, selection.to, mark.create({ href }))
  return dispatch(editor, transaction)
}

function image(editor, value) {
  if (!inlineSelection(editor)) return false
  const src = String(value || '').trim()
  const type = editor.state.schema.nodes.image
  if (!type?.isInline || !src || /[<>\s]/.test(src) || !/^(https?:\/\/|data:image\/|blob:|\/)/i.test(src)) return false
  return dispatch(editor, editor.state.tr.replaceSelectionWith(type.create({ src, alt: '图片描述' }), false))
}

/** Toolbar commands always use the current model selection, never DOM insertion. */
export function runBlockTool(editor, name, arg) {
  if (!ready(editor)) return false
  try {
    if (name === 'undo' || name === 'redo') return (name === 'undo' ? undo : redo)(editor.state, tr => editor.view.dispatch(tr))
    if (inlineNames[name]) {
      const mark = editor.state.schema.marks[inlineNames[name]]
      return Boolean(mark && inlineSelection(editor) && command(editor, toggleMark(mark)))
    }
    if (name === 'link') return link(editor, arg)
    if (name === 'image') return image(editor, arg)
    if (['codeBlock', 'table', 'hr', 'todo', 'details', 'callout'].includes(name)) return insertBlock(editor, name, arg)
    if (name === 'para' || /^h[1-6]$/.test(name)) {
      const type = editor.state.schema.nodes[name === 'para' ? 'paragraph' : 'heading']
      if (!type) return false
      const transaction = editor.state.tr
      for (const { $from, $to } of editor.state.selection.ranges) {
        transaction.setBlockType($from.pos, $to.pos, type, node => ({ ...node.attrs, ...(name === 'para' ? {} : { level: Number(name.slice(1)) }) }))
      }
      return dispatch(editor, transaction)
    }
    if (name === 'quote') {
      const native = nativeToggle(editor, 'toggleBlockquote')
      if (native !== null) return native
      const type = editor.state.schema.nodes.blockquote
      if (!type) return false
      const { $from } = editor.state.selection
      for (let depth = $from.depth; depth > 0; depth--) if ($from.node(depth).type === type) return command(editor, lift)
      return command(editor, wrapIn(type))
    }
    if (name === 'ul' || name === 'ol') {
      const native = nativeToggle(editor, name === 'ul' ? 'toggleBulletList' : 'toggleOrderedList')
      if (native !== null) return native
      const type = editor.state.schema.nodes[name === 'ul' ? 'bulletList' : 'orderedList']
      return Boolean(type && command(editor, wrapInList(type)))
    }
    return false
  } catch { return false }
}

function stylesOf(value) {
  const styles = new Map()
  for (const declaration of String(value || '').split(';')) {
    const colon = declaration.indexOf(':')
    if (colon > 0) styles.set(declaration.slice(0, colon).trim().toLowerCase(), declaration.slice(colon + 1).trim())
  }
  return styles
}

function styledMark(type, marks, property, value) {
  const styles = stylesOf(type.isInSet(marks)?.attrs.style)
  styles.set(property, value)
  return type.create({ style: [...styles].map(([key, val]) => `${key}: ${val}`).join('; ') })
}

function applyFont(editor, kind, value) {
  if (!inlineSelection(editor)) return false
  const { state } = editor
  const type = state.schema.marks.fontStyle
  if (!type) return false
  const property = { color: 'color', bg: 'background-color', size: 'font-size' }[kind]
  const cssValue = kind === 'size' ? `${Number(value)}px` : String(value || '').trim()
  if (!cssValue || /[;{}<>]/.test(cssValue) || (kind === 'size' && (!Number.isFinite(Number(value)) || Number(value) <= 0 || Number(value) > 256))) return false
  const transaction = state.tr
  if (state.selection.empty) {
    const marks = state.storedMarks || state.selection.$from.marks()
    transaction.addStoredMark(styledMark(type, marks, property, cssValue))
  } else {
    state.doc.nodesBetween(state.selection.from, state.selection.to, (node, pos) => {
      if (!node.isInline) return
      const from = Math.max(pos, state.selection.from)
      const to = Math.min(pos + node.nodeSize, state.selection.to)
      if (from < to) transaction.addMark(from, to, styledMark(type, node.marks, property, cssValue))
    })
  }
  return dispatch(editor, transaction)
}

function align(editor, value) {
  if (!['left', 'center', 'right', 'justify'].includes(value)) return false
  const transaction = editor.state.tr
  const { from, to, $from } = editor.state.selection
  const update = (node, pos) => {
    if (!['paragraph', 'heading'].includes(node.type.name) || !('textAlign' in node.attrs)) return
    const textAlign = value === 'left' ? null : value
    if (node.attrs.textAlign !== textAlign) transaction.setNodeMarkup(pos, undefined, { ...node.attrs, textAlign })
  }
  if (from === to && $from.parent.isTextblock) update($from.parent, $from.before())
  else editor.state.doc.nodesBetween(from, to, update)
  return dispatch(editor, transaction)
}

export function applyBlockFormat(editor, { kind, value } = {}) {
  if (!ready(editor)) return false
  try {
    if (['color', 'bg', 'size'].includes(kind)) return applyFont(editor, kind, value)
    if (kind === 'align' || kind === 'center') return align(editor, kind === 'center' ? 'center' : value)
    if (kind === 'details' || kind === 'callout') return runBlockTool(editor, kind)
    if (kind === 'underline' || kind === 'strike') return runBlockTool(editor, kind)
    if (!inlineSelection(editor)) return false
    if (kind === 'clear') {
      const { from, to, empty } = editor.state.selection
      return dispatch(editor, empty ? editor.state.tr.setStoredMarks([]) : editor.state.tr.removeMark(from, to))
    }
    const type = editor.state.schema.marks[{ mark: 'highlight', sup: 'superscript', sub: 'subscript' }[kind]]
    if (!type) return false
    if (kind === 'mark') return command(editor, toggleMark(type))
    const opposite = editor.state.schema.marks[kind === 'sup' ? 'subscript' : 'superscript']
    const { from, to, empty, $from } = editor.state.selection
    const transaction = editor.state.tr
    const active = empty ? type.isInSet(editor.state.storedMarks || $from.marks()) : editor.state.doc.rangeHasMark(from, to, type)
    if (empty) {
      if (opposite) transaction.removeStoredMark(opposite)
      active ? transaction.removeStoredMark(type) : transaction.addStoredMark(type.create())
    } else {
      if (opposite) transaction.removeMark(from, to, opposite)
      active ? transaction.removeMark(from, to, type) : transaction.addMark(from, to, type.create())
    }
    return dispatch(editor, transaction)
  } catch { return false }
}
