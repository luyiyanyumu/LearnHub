import assert from 'node:assert/strict'
import test from 'node:test'
import { Schema } from '@tiptap/pm/model'
import { EditorState, TextSelection } from '@tiptap/pm/state'
import { history, undo } from '@tiptap/pm/history'
import {
  addActionBlockBelow, canIndentActionBlock, convertActionBlock, deleteActionBlock,
  findActionBlock, getBlockActionSupport, indentActionBlock,
} from './blockActions.js'

const meta = { blockId: { default: null }, dataLine: { default: null } }
const schema = new Schema({
  nodes: {
    doc: { content: 'block+' },
    paragraph: { group: 'block', content: 'inline*', attrs: { ...meta, indent: { default: 0 } } },
    heading: { group: 'block', content: 'inline*', attrs: { ...meta, indent: { default: 0 }, level: { default: 2 } } },
    blockquote: { group: 'block', content: 'block+', attrs: meta },
    callout: { group: 'block', content: 'block+', attrs: { ...meta, type: { default: 'tip' } } },
    details: { group: 'block', content: 'summary block*', attrs: meta },
    summary: { group: 'block', atom: true, attrs: { title: { default: '' } } },
    bulletList: { group: 'block', content: 'listItem+', attrs: meta },
    orderedList: { group: 'block', content: 'listItem+', attrs: { ...meta, start: { default: 1 } } },
    listItem: { content: 'paragraph block*', attrs: meta },
    codeBlock: { group: 'block', atom: true, attrs: { ...meta, code: { default: '' }, language: { default: null } } },
    table: { group: 'block', content: 'tableRow+', attrs: meta },
    tableRow: { content: '(tableHeader|tableCell)+' },
    tableHeader: { content: 'block+' },
    tableCell: { content: 'block+' },
    horizontalRule: { group: 'block', atom: true, attrs: meta },
    hardBreak: { inline: true, group: 'inline' },
    text: { group: 'inline' },
  },
  marks: { bold: {}, italic: {}, link: { attrs: { href: {} } } },
})
const p = text => schema.node('paragraph', null, text ? schema.text(text) : null)
const item = (...nodes) => schema.node('listItem', null, nodes)
const doc = (...nodes) => schema.node('doc', null, nodes)
const table = () => schema.node('table', null, [schema.node('tableRow', null, [schema.node('tableCell', null, p('Cell'))])])
function harness(content, selection = null) {
  let state = EditorState.create({ schema, doc: content, selection: selection || TextSelection.atStart(content), plugins: [history()] })
  const transactions = []
  const editor = {
    schema,
    get state() { return state },
    view: { dispatch(tr) { tr.doc.check(); transactions.push(tr); state = state.apply(tr) } },
  }
  return { editor, transactions }
}
function undoOnce(editor) {
  assert.equal(undo(editor.state, tr => editor.view.dispatch(tr)), true)
}

test('heading conversion preserves inline formatting, identity and indentation in one undo step', () => {
  const bold = schema.marks.bold.create()
  const link = schema.marks.link.create({ href: 'https://example.com' })
  const heading = schema.node('heading', { level: 3, blockId: 'block-keep', indent: 2 }, [schema.text('Title', [bold, link])])
  const before = doc(heading, p('After'))
  const { editor, transactions } = harness(before)
  assert.equal(convertActionBlock(editor, 0, 'paragraph'), true)
  assert.equal(transactions.length, 1)
  assert.equal(editor.state.doc.firstChild.type.name, 'paragraph')
  assert.equal(editor.state.doc.firstChild.attrs.blockId, 'block-keep')
  assert.equal(editor.state.doc.firstChild.attrs.indent, 2)
  assert.deepEqual(editor.state.doc.firstChild.firstChild.marks, heading.firstChild.marks)
  assert.equal(editor.state.selection.from, 1)
  undoOnce(editor)
  assert.equal(editor.state.doc.eq(before), true)
})

test('converting a middle ordered-list item splits siblings and retains numbering and nested body', () => {
  const nested = schema.node('bulletList', null, [item(p('Nested'))])
  const first = item(p('First'))
  const selected = item(p('Selected'), nested)
  const before = doc(schema.node('orderedList', { start: 4, blockId: 'block-list' }, [first, selected, item(p('Last'))]))
  const { editor, transactions } = harness(before)
  assert.equal(convertActionBlock(editor, 1 + first.nodeSize, 'h2'), true)
  assert.equal(transactions.length, 1)
  const result = editor.state.doc
  assert.deepEqual(Array.from({ length: result.childCount }, (_, i) => result.child(i).type.name), ['orderedList', 'heading', 'bulletList', 'orderedList'])
  assert.equal(result.child(0).attrs.start, 4)
  assert.equal(result.child(3).attrs.start, 6)
  assert.equal(result.child(3).attrs.blockId, null)
  assert.equal(result.child(2).eq(nested), true)
  assert.equal(result.textContent, before.textContent)
  undoOnce(editor)
  assert.equal(editor.state.doc.eq(before), true)
})

test('table conversions reject lossy formats and allow containers while retaining every cell', () => {
  const before = doc(table())
  const { editor, transactions } = harness(before)
  for (const type of ['paragraph', 'h1', 'bulletList', 'orderedList', 'codeBlock']) {
    assert.equal(getBlockActionSupport(editor, 0, type), false)
    assert.equal(convertActionBlock(editor, 0, type), false)
  }
  assert.equal(transactions.length, 0)
  assert.equal(convertActionBlock(editor, 0, 'callout'), true)
  assert.equal(editor.state.doc.firstChild.firstChild.eq(before.firstChild), true)
  assert.equal(getBlockActionSupport(editor, 0, 'h2'), false)
  undoOnce(editor)
  assert.equal(editor.state.doc.eq(before), true)
})

test('custom atom code blocks keep source text through code and paragraph conversions', () => {
  const original = schema.node('paragraph', null, [schema.text('a'), schema.node('hardBreak'), schema.text('b')])
  const { editor } = harness(doc(original))
  assert.equal(convertActionBlock(editor, 0, 'codeBlock'), true)
  assert.equal(editor.state.doc.firstChild.attrs.code, 'a\nb')
  assert.equal(editor.state.doc.firstChild.content.size, 0)
  assert.equal(editor.state.selection.node.type.name, 'codeBlock')
  assert.equal(convertActionBlock(editor, 0, 'paragraph'), true)
  assert.equal(editor.state.doc.firstChild.eq(original), true)
})

test('successive menu actions remain separate undo operations', () => {
  const { editor } = harness(doc(p('Text')))
  assert.equal(convertActionBlock(editor, 0, 'h2'), true)
  assert.equal(convertActionBlock(editor, 0, 'h3'), true)
  undoOnce(editor)
  assert.equal(editor.state.doc.firstChild.type.name, 'heading')
  assert.equal(editor.state.doc.firstChild.attrs.level, 2)
  undoOnce(editor)
  assert.equal(editor.state.doc.firstChild.type.name, 'paragraph')
})

test('details conversion and unwrapping retain the title and body blocks', () => {
  const folding = schema.node('details', null, [schema.node('summary', { title: 'Title' }), p('Body'), schema.node('bulletList', null, [item(p('Nested'))])])
  const { editor } = harness(doc(folding))
  assert.equal(convertActionBlock(editor, 0, 'paragraph'), true)
  assert.equal(editor.state.doc.child(0).textContent, 'Title')
  assert.equal(editor.state.doc.child(1).textContent, 'Body')
  assert.equal(editor.state.doc.child(2).type.name, 'bulletList')
})

test('choosing the existing container or list type is a no-op and preserves its attributes', () => {
  for (const node of [
    schema.node('callout', { type: 'warning', dataLine: '12' }, p('Warning')),
    schema.node('details', null, [schema.node('summary', { title: 'Original title' }), p('Body')]),
    schema.node('orderedList', { start: 5 }, [item(p('Item'))]),
  ]) {
    const before = doc(node)
    const { editor, transactions } = harness(before)
    assert.equal(getBlockActionSupport(editor, 0, node.type.name), false)
    assert.equal(convertActionBlock(editor, 0, node.type.name), false)
    if (node.type.name === 'orderedList') assert.equal(convertActionBlock(editor, 1, 'orderedList'), false)
    assert.equal(transactions.length, 0)
    assert.equal(editor.state.doc.eq(before), true)
  }
})

test('deleting the only block leaves a valid editable document and is undoable', () => {
  const before = doc(p('Only'))
  const { editor, transactions } = harness(before)
  assert.equal(deleteActionBlock(editor, 0), true)
  assert.equal(transactions.length, 1)
  assert.equal(editor.state.doc.childCount, 1)
  assert.equal(editor.state.doc.firstChild.type.name, 'paragraph')
  assert.equal(editor.state.doc.firstChild.textContent, '')
  assert.equal(editor.state.selection.from, 1)
  undoOnce(editor)
  assert.equal(editor.state.doc.eq(before), true)
})

test('deleting the only list item removes its list wrapper in one transaction', () => {
  const before = doc(schema.node('bulletList', null, [item(p('Only'))]), p('After'))
  const { editor, transactions } = harness(before)
  assert.equal(deleteActionBlock(editor, 1), true)
  assert.equal(transactions.length, 1)
  assert.equal(editor.state.doc.childCount, 1)
  assert.equal(editor.state.doc.firstChild.textContent, 'After')
  undoOnce(editor)
  assert.equal(editor.state.doc.eq(before), true)
})

test('adding paragraph after an item creates a sibling, adding a heading goes after its list', () => {
  const first = item(p('First'))
  const second = item(p('Second'))
  const before = doc(schema.node('bulletList', null, [first, second]))
  const { editor, transactions } = harness(before)
  assert.equal(addActionBlockBelow(editor, 1, 'paragraph'), true)
  assert.equal(editor.state.doc.firstChild.childCount, 3)
  assert.equal(editor.state.doc.firstChild.child(1).firstChild.textContent, '')
  assert.equal(editor.state.selection.from, 1 + first.nodeSize + 2)
  undoOnce(editor)
  assert.equal(editor.state.doc.eq(before), true)
  assert.equal(addActionBlockBelow(editor, 1, 'h3'), true)
  assert.equal(editor.state.doc.childCount, 2)
  assert.equal(editor.state.doc.child(0).eq(before.firstChild), true)
  assert.equal(editor.state.doc.child(1).type.name, 'heading')
  assert.equal(editor.state.doc.child(1).attrs.level, 3)
  assert.equal(transactions.filter(tr => tr.docChanged && !tr.getMeta('history$')).length >= 2, true)
})

test('adding table and horizontal rule uses real schema blocks', () => {
  const { editor } = harness(doc(p('Start')))
  assert.equal(addActionBlockBelow(editor, 0, 'table'), true)
  const inserted = editor.state.doc.child(1)
  assert.equal(inserted.type.name, 'table')
  assert.equal(inserted.childCount, 2)
  assert.equal(inserted.firstChild.childCount, 2)
  assert.equal(inserted.firstChild.firstChild.type.name, 'tableHeader')
  assert.equal(editor.state.selection.$from.parent.type.name, 'paragraph')
  assert.equal(addActionBlockBelow(editor, 0, 'horizontalRule'), true)
  assert.equal(editor.state.doc.child(1).type.name, 'horizontalRule')
})

test('list indentation targets the menu item rather than the existing editor selection', () => {
  const first = item(p('First'))
  const second = item(p('Second'))
  const before = doc(schema.node('bulletList', null, [first, second]))
  const { editor, transactions } = harness(before)
  assert.equal(canIndentActionBlock(editor, 1, 1), false)
  assert.equal(canIndentActionBlock(editor, 1 + first.nodeSize, 1), true)
  assert.equal(transactions.length, 0)
  assert.equal(indentActionBlock(editor, 1 + first.nodeSize, 1), true)
  assert.equal(transactions.length, 1)
  assert.equal(editor.state.doc.firstChild.childCount, 1)
  assert.equal(editor.state.doc.firstChild.firstChild.lastChild.type.name, 'bulletList')
  const nestedPos = 1 + 1 + first.firstChild.nodeSize + 1
  assert.equal(indentActionBlock(editor, nestedPos, -1), true)
  assert.equal(transactions.length, 2)
  assert.equal(editor.state.doc.eq(before), true)
})

test('paragraph indentation obeys bounds and rejects stale positions without mutation', () => {
  const { editor, transactions } = harness(doc(p('Text')))
  assert.equal(indentActionBlock(editor, 0, -1), false)
  for (let i = 0; i < 6; i++) assert.equal(indentActionBlock(editor, 0, 1), true)
  assert.equal(editor.state.doc.firstChild.attrs.indent, 6)
  assert.equal(indentActionBlock(editor, 0, 1), false)
  assert.equal(convertActionBlock(editor, 999, 'h1'), false)
  assert.equal(deleteActionBlock(editor, 999), false)
  assert.equal(addActionBlockBelow(editor, 999), false)
  assert.equal(transactions.length, 6)
})

test('DOM targeting selects a whole list item and whole table instead of inner paragraphs', () => {
  const content = doc(schema.node('bulletList', null, [item(p('List'))]), table())
  const listText = { nodeType: 1 }
  const cellText = { nodeType: 1 }
  const listDom = { nodeType: 1, contains: target => target === listText }
  const tableDom = { nodeType: 1, contains: target => target === cellText }
  const tablePos = content.firstChild.nodeSize
  const root = { contains: target => target === listText || target === cellText }
  listText.parentElement = root
  cellText.parentElement = root
  const view = {
    dom: root, state: { doc: content },
    posAtDOM: target => target === listText ? 3 : tablePos + 4,
    nodeDOM: pos => pos === 1 ? listDom : pos === tablePos ? tableDom : null,
  }
  assert.deepEqual(findActionBlock(view, listText), { pos: 1, node: content.firstChild.firstChild, dom: listDom })
  assert.deepEqual(findActionBlock(view, cellText), { pos: tablePos, node: content.child(1), dom: tableDom })
  assert.equal(findActionBlock(view, { nodeType: 1 }), null)
})

test('atom node-view decorations resolve to their code block rather than the following paragraph', () => {
  const content = doc(schema.node('codeBlock', { code: 'print(1)' }), p('After'))
  const button = { nodeType: 1 }
  const wrapper = { nodeType: 1, childNodes: [button], contains: target => target === button }
  const following = { nodeType: 1, contains: () => false }
  const root = { contains: target => target === button, childNodes: [wrapper, following] }
  button.parentNode = button.parentElement = wrapper
  wrapper.parentNode = wrapper.parentElement = root
  const view = {
    dom: root, state: { doc: content },
    posAtDOM: element => element === root ? 0 : 1,
    nodeDOM: pos => pos === 0 ? wrapper : pos === 1 ? following : null,
  }
  assert.deepEqual(findActionBlock(view, button), { pos: 0, node: content.firstChild, dom: wrapper })
})
