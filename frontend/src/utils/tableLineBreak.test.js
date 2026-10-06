import assert from 'node:assert/strict'
import test from 'node:test'
import { getSchema } from '@tiptap/core'
import StarterKit from '@tiptap/starter-kit'
import { TableKit } from '@tiptap/extension-table'
import { EditorState, TextSelection } from '@tiptap/pm/state'
import { CellSelection } from '@tiptap/pm/tables'
import { history, undo } from '@tiptap/pm/history'
import { insertTableLineBreak } from './tableLineBreak.js'

const schema = getSchema([StarterKit, TableKit])
const p = (text, marks) => schema.node('paragraph', null, text ? schema.text(text, marks) : null)
const cell = (text, header = false, attrs, marks) => schema.node(header ? 'tableHeader' : 'tableCell', attrs, p(text, marks))
const row = (...cells) => schema.node('tableRow', null, cells)
const doc = (...rows) => schema.node('doc', null, [schema.node('table', null, rows), p('外部')])
function textAt(content, value) {
  let result
  content.descendants((node, pos) => { if (node.isText && node.text === value) result = pos })
  return result
}
function harness(content, selection) {
  let state = EditorState.create({ schema, doc: content, selection, plugins: [history()] })
  const transactions = []
  return { get state() { return state }, dispatch(tr) { tr.doc.check(); transactions.push(tr); state = state.apply(tr) }, transactions }
}
function lines(node) { return [...Array(node.childCount)].map((_, i) => node.child(i).type.name === 'hardBreak' ? '\n' : node.child(i).textContent).join('') }

test('line breaks at the beginning, middle and end stay in the cell and undo in one step', () => {
  for (const offset of [0, 2, 4]) {
    const original = doc(row(cell('ABCD'), cell('相邻')), row(cell('下行'), cell('末格')))
    const h = harness(original, TextSelection.create(original, textAt(original, 'ABCD') + offset))
    assert.equal(insertTableLineBreak(h.state, h.dispatch), true)
    const table = h.state.doc.firstChild
    assert.equal(lines(table.firstChild.firstChild.firstChild), 'ABCD'.slice(0, offset) + '\n' + 'ABCD'.slice(offset))
    assert.ok(table.firstChild.child(1).eq(original.firstChild.firstChild.child(1)))
    assert.ok(table.child(1).eq(original.firstChild.child(1)))
    assert.equal(h.transactions.length, 1)
    assert.equal(undo(h.state, h.dispatch), true)
    assert.ok(h.state.doc.eq(original))
  }
})

test('a reverse text range in a merged header is replaced without losing marks or cell spans', () => {
  const bold = schema.marks.bold.create()
  const original = doc(row(cell('ABCD', true, { colspan: 2 }, [bold])), row(cell('下行'), cell('末格')))
  const pos = textAt(original, 'ABCD')
  const h = harness(original, TextSelection.create(original, pos + 3, pos + 1))
  assert.equal(insertTableLineBreak(h.state), true)
  assert.equal(h.transactions.length, 0)
  assert.equal(insertTableLineBreak(h.state, h.dispatch), true)
  h.dispatch(h.state.tr.insertText('新'))
  const header = h.state.doc.firstChild.firstChild.firstChild
  assert.equal(header.attrs.colspan, 2)
  assert.equal(header.type.name, 'tableHeader')
  assert.equal(lines(header.firstChild), 'A\n新D')
  assert.ok(header.firstChild.child(2).marks.some(mark => mark.type.name === 'bold'))
})

test('cell selections, cross-cell text ranges and outside text are rejected without mutation', () => {
  const original = doc(row(cell('ABCD'), cell('相邻')), row(cell('下行'), cell('末格')))
  const cellPositions = []
  original.descendants((node, pos) => { if (node.type.name === 'tableCell') cellPositions.push(pos) })
  const selections = [
    CellSelection.create(original, cellPositions[0]),
    CellSelection.create(original, cellPositions[0], cellPositions[3]),
    TextSelection.create(original, textAt(original, 'ABCD') + 1, textAt(original, '相邻') + 1),
    TextSelection.create(original, textAt(original, '外部')),
  ]
  for (const selection of selections) {
    const h = harness(original, selection)
    assert.equal(insertTableLineBreak(h.state, h.dispatch), false)
    assert.equal(h.transactions.length, 0)
    assert.ok(h.state.doc.eq(original))
  }
})
