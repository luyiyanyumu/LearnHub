import assert from 'node:assert/strict'
import test from 'node:test'
import { Schema } from '@tiptap/pm/model'
import { EditorState, NodeSelection, TextSelection } from '@tiptap/pm/state'
import { history, undo } from '@tiptap/pm/history'
import { applyBlockFormat, runBlockTool } from './blockToolbar.js'

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
