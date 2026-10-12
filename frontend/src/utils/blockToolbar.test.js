import assert from 'node:assert/strict'
import test from 'node:test'
import { Schema } from '@tiptap/pm/model'
import { EditorState, NodeSelection, TextSelection } from '@tiptap/pm/state'
import { history, undo } from '@tiptap/pm/history'
import { applyBlockFormat, runBlockTool } from './blockToolbar.js'
import { DEFAULT_FLOWCHART_SOURCE } from './flowchart.js'

const meta = { blockId: { default: null }, dataLine: { default: null }, textAlign: { default: null }, indent: { default: 0 } }
const schema = new Schema({
  nodes: {
    doc: { content: 'block+' },
    paragraph: { group: 'block', content: 'inline*', attrs: meta },
    heading: { group: 'block', content: 'inline*', attrs: { ...meta, level: { default: 2 } } },
    blockquote: { group: 'block', content: 'block+' },
    callout: { group: 'block', content: 'block+', attrs: { type: { default: 'tip' } } },
    details: { group: 'block', content: 'summary block*' },
    summary: { group: 'block', atom: true, attrs: { title: { default: '' } } },
    bulletList: { group: 'block', content: 'listItem+' },
    orderedList: { group: 'block', content: 'listItem+', attrs: { start: { default: 1 } } },
    listItem: { content: 'paragraph block*' },
    taskList: { group: 'block', content: 'taskItem+' },
    taskItem: { content: 'paragraph block*', attrs: { checked: { default: false } } },
    codeBlock: { group: 'block', atom: true, attrs: { code: { default: '' }, language: { default: null } } },
    horizontalRule: { group: 'block', atom: true },
    table: { group: 'block', content: 'tableRow+' },
    tableRow: { content: '(tableHeader|tableCell)+' },
    tableHeader: { content: 'block+' },
    tableCell: { content: 'block+' },
    image: { inline: true, group: 'inline', atom: true, attrs: { src: {}, alt: { default: null } } },
    hardBreak: { inline: true, group: 'inline' },
    text: { group: 'inline' },
  },
  marks: {
    bold: {}, italic: {}, underline: {}, strike: {}, code: { excludes: '_' },
    link: { attrs: { href: {} } }, fontStyle: { attrs: { style: { default: '' } } },
    superscript: {}, subscript: {}, highlight: {},
  },
})
const text = (value, marks = []) => value ? schema.text(value, marks) : null
const p = (value, attrs, marks) => schema.node('paragraph', attrs, text(value, marks))
const doc = (...nodes) => schema.node('doc', null, nodes)
const item = (...nodes) => schema.node('listItem', null, nodes)
function harness(content, from = 1, to = from, nodeSelection = false) {
  let state = EditorState.create({ schema, doc: content, selection: nodeSelection ? NodeSelection.create(content, from) : TextSelection.create(content, from, to), plugins: [history()] })
  const transactions = []
  const editor = {
    get state() { return state }, schema,
    view: { dispatch(tr) { tr.doc.check(); transactions.push(tr); state = state.apply(tr) } },
  }
  return { editor, transactions }
}
function types(node) { return Array.from({ length: node.childCount }, (_, i) => node.child(i).type.name) }
function undoOnce(editor, before) {
  assert.equal(undo(editor.state, tr => editor.view.dispatch(tr)), true)
  assert.equal(editor.state.doc.eq(before), true)
}

test('block insertion at paragraph start, middle and end uses the cursor and keeps later blocks', () => {
  for (const at of [1, 3, 5]) {
    const bold = schema.marks.bold.create()
    const original = doc(p('ABCD', { blockId: 'block-original', indent: 2 }, [bold]), p('Later'))
    const { editor, transactions } = harness(original, at)
    assert.equal(runBlockTool(editor, 'codeBlock', 'javascript'), true)
    assert.equal(transactions.length, 1)
    const nodes = Array.from({ length: editor.state.doc.childCount }, (_, i) => editor.state.doc.child(i))
    const codeIndex = nodes.findIndex(node => node.type.name === 'codeBlock')
    assert.notEqual(codeIndex, -1)
    assert.equal(nodes[codeIndex].attrs.language, 'javascript')
    assert.equal(nodes[codeIndex].attrs.code, '')
    assert.equal(nodes.slice(0, codeIndex).map(node => node.textContent).join(''), 'ABCD'.slice(0, at - 1))
    assert.equal(nodes.slice(codeIndex + 1, -1).map(node => node.textContent).join(''), 'ABCD'.slice(at - 1))
    assert.equal(nodes.at(-1).textContent, 'Later')
    const ids = nodes.filter(node => node.attrs.blockId === 'block-original')
    assert.equal(ids.length, 1)
    for (const node of nodes.filter(node => node.isTextblock && node.textContent !== 'Later')) {
      assert.equal(node.attrs.indent, 2)
      if (node.firstChild) assert.equal(node.firstChild.marks[0].type.name, 'bold')
    }
    assert.equal(editor.state.selection.node.type.name, 'codeBlock')
    undoOnce(editor, original)
  }
})

test('block insertion replaces a selected range and preserves marked prefixes and suffixes', () => {
  const bold = schema.marks.bold.create()
  const original = doc(p('ABCDEF', null, [bold]), p('Later'))
  const { editor, transactions } = harness(original, 3, 5)
  assert.equal(runBlockTool(editor, 'codeBlock', 'python'), true)
  assert.deepEqual(types(editor.state.doc), ['paragraph', 'codeBlock', 'paragraph', 'paragraph'])
  assert.equal(editor.state.doc.child(0).textContent, 'AB')
  assert.equal(editor.state.doc.child(1).attrs.code, 'CD')
  assert.equal(editor.state.doc.child(2).textContent, 'EF')
  assert.equal(editor.state.doc.child(2).firstChild.marks[0].type.name, 'bold')
  assert.equal(transactions.length, 1)
  undoOnce(editor, original)
})

test('flowchart insertion uses the current caret, preserves marked text and is undone in one step', () => {
  for (const at of [1, 3, 5]) {
    const bold = schema.marks.bold.create()
    const original = doc(p('ABCD', { blockId: 'flowchart-paragraph' }, [bold]), p('Later'))
    const { editor, transactions } = harness(original, at)
    assert.equal(runBlockTool(editor, 'flowchart'), true)
    assert.equal(transactions.length, 1)
    const nodes = Array.from({ length: editor.state.doc.childCount }, (_, i) => editor.state.doc.child(i))
    const index = nodes.findIndex(node => node.type.name === 'codeBlock')
    assert.equal(nodes[index].attrs.language, 'mermaid')
    assert.equal(nodes[index].attrs.code, DEFAULT_FLOWCHART_SOURCE)
    assert.equal(nodes.slice(0, index).map(node => node.textContent).join(''), 'ABCD'.slice(0, at - 1))
    assert.equal(nodes.slice(index + 1, -1).map(node => node.textContent).join(''), 'ABCD'.slice(at - 1))
    assert.equal(nodes.at(-1).textContent, 'Later')
    for (const node of nodes.filter(node => node.isTextblock && node.textContent !== 'Later')) {
      if (node.firstChild) assert.equal(node.firstChild.marks[0].type.name, 'bold')
    }
    assert.equal(editor.state.selection.node.attrs.language, 'mermaid')
    undoOnce(editor, original)
    assert.equal(editor.state.selection.from, at)
    assert.equal(editor.state.selection.to, at)
    assert.equal(undo(editor.state, tr => editor.view.dispatch(tr)), false)
  }
})

test('flowcharts replace only the current selection and keep another editor draft intact', () => {
  const original = doc(p('ABCDEF'), p('Later'))
  const { editor, transactions } = harness(original, 3, 5)
  const other = harness(doc(p('Independent draft'))).editor
  const otherBefore = other.state
  assert.equal(runBlockTool(editor, 'flowchart'), true)
  assert.deepEqual(types(editor.state.doc), ['paragraph', 'codeBlock', 'paragraph', 'paragraph'])
  assert.equal(editor.state.doc.child(0).textContent, 'AB')
  assert.equal(editor.state.doc.child(1).attrs.code, DEFAULT_FLOWCHART_SOURCE)
  assert.equal(editor.state.doc.child(2).textContent, 'EF')
  assert.equal(editor.state.doc.lastChild.textContent, 'Later')
  assert.equal(other.state, otherBefore)
  assert.equal(transactions.length, 1)
  undoOnce(editor, original)
  assert.equal(editor.state.selection.from, 3)
  assert.equal(editor.state.selection.to, 5)
})

test('a flowchart inserted from an existing code node keeps that code and its following paragraph', () => {
  const original = doc(schema.node('codeBlock', { language: 'js', code: 'keepSynthetic()' }), p('Later'))
  const { editor } = harness(original, 0, 0, true)
  assert.equal(runBlockTool(editor, 'flowchart'), true)
  assert.deepEqual(types(editor.state.doc), ['codeBlock', 'codeBlock', 'paragraph'])
  assert.equal(editor.state.doc.firstChild.attrs.code, 'keepSynthetic()')
  assert.equal(editor.state.doc.child(1).attrs.code, DEFAULT_FLOWCHART_SOURCE)
  assert.equal(editor.state.doc.child(1).attrs.language, 'mermaid')
  assert.equal(editor.state.doc.lastChild.textContent, 'Later')
  undoOnce(editor, original)
})

test('details, callout and todo keep selected inline formatting in their editable body', () => {
  for (const name of ['details', 'callout', 'todo']) {
    const italic = schema.marks.italic.create()
    const original = doc(p('ABCDEF', null, [italic]))
    const { editor } = harness(original, 3, 5)
    assert.equal(runBlockTool(editor, name), true)
    const inserted = editor.state.doc.child(1)
    const body = name === 'details' ? inserted.child(1) : name === 'todo' ? inserted.firstChild.firstChild : inserted.firstChild
    assert.equal(body.textContent, 'CD')
    assert.equal(body.firstChild.marks[0].type.name, 'italic')
    assert.equal(editor.state.selection.$from.parent.type.name, 'paragraph')
    if (name === 'details') assert.equal(inserted.firstChild.attrs.title, '点击展开')
    if (name === 'todo') assert.equal(inserted.firstChild.attrs.checked, false)
    undoOnce(editor, original)
  }
})

test('a table inserted in a paragraph is a 2x2 model block with its cursor in the first cell', () => {
  const original = doc(p('ABCD'), p('Later'))
  const { editor } = harness(original, 3)
  assert.equal(runBlockTool(editor, 'table'), true)
  assert.deepEqual(types(editor.state.doc), ['paragraph', 'table', 'paragraph', 'paragraph'])
  const table = editor.state.doc.child(1)
  assert.equal(table.childCount, 2)
  assert.equal(table.firstChild.childCount, 2)
  assert.equal(table.firstChild.firstChild.type.name, 'tableHeader')
  assert.equal(table.firstChild.firstChild.textContent, '列A')
  assert.equal(editor.state.selection.$from.node(1).type.name, 'table')
  undoOnce(editor, original)
})

test('inserting into a list or table cell retains the container and its adjacent content', () => {
  const list = schema.node('bulletList', null, [item(p('ABCD')), item(p('Next item'))])
  const cell = schema.node('tableCell', null, p('ABCD'))
  const table = schema.node('table', null, schema.node('tableRow', null, [cell, schema.node('tableCell', null, p('Other cell'))]))
  for (const original of [doc(list, p('Later')), doc(table, p('Later'))]) {
    let cursor
    original.descendants((node, pos) => { if (node.isTextblock && node.textContent === 'ABCD') cursor = pos + 3 })
    const { editor } = harness(original, cursor)
    assert.equal(runBlockTool(editor, 'hr'), true)
    assert.equal(editor.state.doc.firstChild.type.name, original.firstChild.type.name)
    assert.equal(editor.state.doc.textContent, original.textContent)
    assert.equal(editor.state.doc.lastChild.textContent, 'Later')
    let rules = 0
    editor.state.doc.descendants(node => { if (node.type.name === 'horizontalRule') rules++ })
    assert.equal(rules, 1)
    undoOnce(editor, original)
  }
})

test('a selected code atom is retained when another block is inserted at its boundary', () => {
  const code = schema.node('codeBlock', { code: 'keep()', language: 'python' })
  const original = doc(code, p('Later'))
  const { editor } = harness(original, 0, 0, true)
  assert.equal(runBlockTool(editor, 'table'), true)
  assert.deepEqual(types(editor.state.doc), ['codeBlock', 'table', 'paragraph'])
  assert.equal(editor.state.doc.firstChild.eq(code), true)
  undoOnce(editor, original)
  for (const name of ['inlineCode', 'link', 'image', 'bold']) assert.equal(runBlockTool(editor, name, 'https://example.com'), false)
  assert.equal(applyBlockFormat(editor, { kind: 'color', value: '#f00' }), false)
  assert.equal(editor.state.doc.eq(original), true)
})

test('image and link insertion replace exactly the selected text or the empty cursor', () => {
  for (const [from, to] of [[3, 3], [3, 5]]) {
    const original = doc(p('ABCDEF'), p('Later'))
    const { editor } = harness(original, from, to)
    assert.equal(runBlockTool(editor, 'image', 'https://example.com/image.png'), true)
    assert.equal(editor.state.doc.firstChild.child(0).text, 'AB')
    assert.equal(editor.state.doc.firstChild.child(1).type.name, 'image')
    assert.equal(editor.state.doc.firstChild.child(2).text, to === from ? 'CDEF' : 'EF')
    undoOnce(editor, original)
    assert.equal(runBlockTool(editor, 'link', 'https://example.com'), true)
    const linked = editor.state.doc.firstChild.child(1)
    assert.equal(linked.text, to === from ? 'https://example.com' : 'CD')
    assert.equal(linked.marks[0].attrs.href, 'https://example.com')
    undoOnce(editor, original)
  }
})

test('note links retain mixed inline formatting when the label matches selected words', () => {
  const bold = schema.marks.bold.create()
  const italic = schema.marks.italic.create()
  const font = schema.marks.fontStyle.create({ style: 'color: #f00; font-size: 18px' })
  const original = doc(schema.node('paragraph', { blockId: 'block-stable' }, [
    text('前'), text('目', [bold]), text('标', [italic, font]), text('后'),
  ]), p('Later'))
  const { editor, transactions } = harness(original, 2, 4)
  const href = '/notes/12#section=%E7%AB%A0%E8%8A%82&occurrence=2'
  assert.equal(runBlockTool(editor, 'noteLink', { href, text: '目标' }), true)
  assert.equal(transactions.length, 1)
  const paragraph = editor.state.doc.firstChild
  assert.equal(paragraph.textContent, '前目标后')
  assert.equal(paragraph.attrs.blockId, 'block-stable')
  assert.equal(schema.marks.bold.isInSet(paragraph.child(1).marks), bold)
  assert.equal(schema.marks.italic.isInSet(paragraph.child(2).marks), italic)
  assert.equal(schema.marks.fontStyle.isInSet(paragraph.child(2).marks), font)
  for (const index of [1, 2]) assert.equal(schema.marks.link.isInSet(paragraph.child(index).marks).attrs.href, href)
  for (const index of [0, 3]) assert.equal(schema.marks.link.isInSet(paragraph.child(index).marks), undefined)
  assert.equal(editor.state.doc.lastChild.textContent, 'Later')
  undoOnce(editor, original)
})

test('note links insert literal labels at the caret or replace a selected range and undo once', () => {
  const label = '<章节>[1] *重点* \\ “中文”'
  const href = '/notes/99#section=A%26B&occurrence=1'
  for (const [from, to] of [[3, 3], [3, 5]]) {
    const bold = schema.marks.bold.create()
    const original = doc(p('ABCDEF', null, [bold]), p('Later'))
    const { editor, transactions } = harness(original, from, to)
    assert.equal(runBlockTool(editor, 'noteLink', { href, text: label }), true)
    assert.equal(transactions.length, 1)
    const paragraph = editor.state.doc.firstChild
    assert.equal(paragraph.textContent, 'AB' + label + (from === to ? 'CDEF' : 'EF'))
    const inserted = paragraph.child(1)
    assert.equal(inserted.isText, true)
    assert.equal(inserted.text, label)
    assert.equal(schema.marks.link.isInSet(inserted.marks).attrs.href, href)
    assert.equal(schema.marks.bold.isInSet(inserted.marks), bold)
    assert.equal(editor.state.selection.from, from + label.length)
    assert.equal(editor.state.selection.empty, true)
    undoOnce(editor, original)
    assert.equal(editor.state.selection.from, from)
    assert.equal(editor.state.selection.to, to)
  }
})

test('note links use selected words or their href as fallback and reject unsafe targets without editing', () => {
  const original = doc(p('ABCD'))
  for (const [from, to, expected] of [[2, 4, 'BC'], [3, 3, '/notes/12']]) {
    const { editor } = harness(original, from, to)
    assert.equal(runBlockTool(editor, 'noteLink', { href: '/notes/12', text: '' }), true)
    const linked = editor.state.doc.firstChild.child(1)
    assert.equal(linked.text, expected)
    assert.equal(schema.marks.link.isInSet(linked.marks).attrs.href, '/notes/12')
    undoOnce(editor, original)
  }
  const { editor, transactions } = harness(original, 2, 4)
  for (const href of ['', 'javascript:alert(1)', 'data:text/html,<b>x</b>', '//example.com', '/notes/\n12', '/notes\\12']) {
    assert.equal(runBlockTool(editor, 'noteLink', { href, text: '目标' }), false, href)
  }
  const atom = harness(doc(schema.node('codeBlock', { code: 'keep()' }), p('Later')), 0, 0, true)
  assert.equal(runBlockTool(atom.editor, 'noteLink', { href: '/notes/12', text: '目标' }), false)
  assert.equal(transactions.length, 0)
  assert.equal(editor.state.doc.eq(original), true)
  assert.equal(atom.transactions.length, 0)
})

test('marks and font settings target only the current selection and retain other font styles', () => {
  const font = schema.marks.fontStyle.create({ style: 'font-size: 18px; background-color: #fff' })
  const original = doc(p('ABCDEF', null, [font]))
  const { editor } = harness(original, 3, 5)
  assert.equal(runBlockTool(editor, 'bold'), true)
  assert.equal(editor.state.doc.firstChild.child(1).text, 'CD')
  assert.equal(applyBlockFormat(editor, { kind: 'color', value: '#f00' }), true)
  const marks = editor.state.doc.firstChild.child(1).marks
  assert.equal(schema.marks.bold.isInSet(marks).type.name, 'bold')
  assert.equal(schema.marks.fontStyle.isInSet(marks).attrs.style, 'font-size: 18px; background-color: #fff; color: #f00')
  assert.equal(applyBlockFormat(editor, { kind: 'clear' }), true)
  assert.equal(editor.state.doc.firstChild.child(1).marks.length, 0)
  assert.deepEqual(editor.state.doc.firstChild.child(0).marks, [font])
})

test('empty caret font settings use stored marks and upper/lower scripts exclude each other', () => {
  const original = doc(p('ABCD'))
  const { editor } = harness(original, 3)
  assert.equal(applyBlockFormat(editor, { kind: 'size', value: 20 }), true)
  assert.equal(editor.state.doc.eq(original), true)
  assert.equal(editor.state.storedMarks[0].attrs.style, 'font-size: 20px')
  assert.equal(applyBlockFormat(editor, { kind: 'sup' }), true)
  assert.equal(applyBlockFormat(editor, { kind: 'sub' }), true)
  assert.equal(Boolean(schema.marks.superscript.isInSet(editor.state.storedMarks)), false)
  assert.equal(Boolean(schema.marks.subscript.isInSet(editor.state.storedMarks)), true)
})

test('paragraph formats and alignment preserve identity and do not affect adjacent blocks', () => {
  const original = doc(p('ABCD', { blockId: 'block-stable' }), p('Later'))
  const { editor } = harness(original, 3)
  assert.equal(applyBlockFormat(editor, { kind: 'align', value: 'center' }), true)
  assert.equal(editor.state.doc.firstChild.attrs.textAlign, 'center')
  assert.equal(editor.state.doc.child(1).attrs.textAlign, null)
  assert.equal(runBlockTool(editor, 'h3'), true)
  assert.equal(editor.state.doc.firstChild.type.name, 'heading')
  assert.equal(editor.state.doc.firstChild.attrs.level, 3)
  // Schema-level block changes should preserve the paragraph's permanent link.
  assert.equal(editor.state.doc.firstChild.attrs.blockId, 'block-stable')
  assert.equal(applyBlockFormat(editor, { kind: 'align', value: 'left' }), true)
  assert.equal(editor.state.doc.firstChild.attrs.textAlign, null)
})

test('unknown tools and absent schema nodes reject rather than falling back to document end', () => {
  const original = doc(p('ABCD'), p('Later'))
  const { editor, transactions } = harness(original, 3)
  assert.equal(runBlockTool(editor, 'unknown'), false)
  assert.equal(runBlockTool(editor, 'link', 'javascript:alert(1)'), false)
  assert.equal(runBlockTool(editor, 'image', ''), false)
  assert.equal(applyBlockFormat(editor, { kind: 'size', value: NaN }), false)
  assert.equal(transactions.length, 0)
  assert.equal(editor.state.doc.eq(original), true)
})

test('successive toolbar insertions are separate undo actions', () => {
  const original = doc(p('ABCD'))
  const { editor } = harness(original, 3)
  assert.equal(runBlockTool(editor, 'table'), true)
  const afterTable = editor.state.doc
  assert.equal(runBlockTool(editor, 'codeBlock', 'java'), true)
  undoOnce(editor, afterTable)
  undoOnce(editor, original)
})
