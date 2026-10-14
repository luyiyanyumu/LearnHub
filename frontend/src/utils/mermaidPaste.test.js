import assert from 'node:assert/strict'
import test from 'node:test'
import { Schema } from '@tiptap/pm/model'
import { EditorState, NodeSelection, TextSelection } from '@tiptap/pm/state'
import { history, undo, redo } from '@tiptap/pm/history'
import { handleMermaidPaste, mermaidPasteSlice } from './mermaidPaste.js'

const schema = new Schema({
  nodes: {
    doc: { content: 'block+' },
    paragraph: { group: 'block', content: 'inline*' },
    codeBlock: { group: 'block', atom: true, isolating: true, attrs: { language: { default: null }, code: { default: '' } } },
    text: { group: 'inline' },
  },
  marks: { bold: {}, italic: {} },
})
const p = (text = '', marks) => schema.node('paragraph', null, text ? schema.text(text, marks) : null)
const doc = (...nodes) => schema.node('doc', null, nodes)
const diagram = '```mermaid\nflowchart TD\n  A["合成数据"] --> B["示例"]  \n```'
const code = 'flowchart TD\n  A["合成数据"] --> B["示例"]  \n'
const clipboard = (plain, html = '') => ({ clipboardData: { getData(type) { return type === 'text/plain' ? plain : type === 'text/html' ? html : '' } } })

function harness(content = doc(p()), selection = TextSelection.atStart(content), editable = true) {
  let state = EditorState.create({ schema, doc: content, selection, plugins: [history()] })
  const transactions = []
  return {
    editable,
    get state() { return state },
    transactions,
    dispatch(transaction) {
      transaction.doc.check()
      transactions.push(transaction)
      state = state.apply(transaction)
    },
  }
}

const children = node => Array.from({ length: node.childCount }, (_, index) => node.child(index))
const diagrams = node => children(node).filter(child => child.type.name === 'codeBlock')

test('complete fenced Markdown creates an atom with source and Mermaid language', () => {
  const slice = mermaidPasteSlice(diagram, schema)
  assert.equal(slice.openStart, 0)
  assert.equal(slice.openEnd, 0)
  assert.equal(slice.content.childCount, 1)
  assert.equal(slice.content.firstChild.isAtom, true)
  assert.equal(slice.content.firstChild.attrs.language, 'mermaid')
  assert.equal(slice.content.firstChild.attrs.code, code)
})

test('CRLF, uppercase, tilde fences, longer closers and source whitespace are retained', () => {
  const source = '~~~~MeRmAiD\r\nflowchart TD  \r\n\tA --> B\t\r\n  \r\n~~~~~ \t\r\n'
  const slice = mermaidPasteSlice(source, schema)
  assert.equal(slice.content.childCount, 1)
  assert.equal(slice.content.firstChild.attrs.code, 'flowchart TD  \n\tA --> B\t\n  \n')
  assert.equal(mermaidPasteSlice('`````mermaid\nflowchart TD\n``````', schema).content.firstChild.attrs.code, 'flowchart TD\n')
})

test('outer code examples, other languages, inline and unfinished fences do not convert', () => {
  for (const source of [
    '````markdown\n' + diagram + '\n````',
    '~~~~markdown\n' + diagram + '\n~~~~',
    '```javascript\n' + diagram + '\n```',
    diagram.replace('mermaid', 'mermaid-example'),
    diagram.replace('mermaid', 'mermaid extra-info'),
    'Here is a literal ` ```mermaid ` label',
    '```mermaid\nflowchart TD\nA --> B',
    '~~~~mermaid\nflowchart TD\n~~~',
    '```mermaid\nflowchart TD\n~~~~',
    diagram.split('\n').map(line => `    ${line}`).join('\n'),
    diagram.split('\n').map(line => `> ${line}`).join('\n'),
    '- Example\n' + diagram.split('\n').map(line => `  ${line}`).join('\n'),
  ]) assert.equal(mermaidPasteSlice(source, schema), null, source)
})

test('other pasted content stays literal, including HTML, Markdown headings and code samples', () => {
  const before = '# Literal heading\n<script>alert("example")</script>\n'
  const after = '\n**Literal emphasis**\n```javascript\nconsole.log("synthetic")\n```'
  const slice = mermaidPasteSlice(before + diagram + after, schema)
  const nodes = children(slice.content)
  assert.deepEqual(nodes.map(node => node.type.name), ['paragraph', 'paragraph', 'codeBlock', 'paragraph', 'paragraph', 'paragraph', 'paragraph'])
  assert.equal(nodes[0].textContent, '# Literal heading')
  assert.equal(nodes[1].textContent, '<script>alert("example")</script>')
  assert.equal(nodes[3].textContent, '**Literal emphasis**')
  assert.equal(nodes[4].textContent, '```javascript')
  assert.equal(nodes[5].textContent, 'console.log("synthetic")')
  assert.equal(nodes[6].textContent, '```')
  assert.ok(nodes.every(node => !node.marks.length))
})

test('pasting at each caret position splits only the receiving paragraph and undoes once', () => {
  for (const offset of [0, 4, 8]) {
    const original = doc(p('LeftRght'), p('Untouched'))
    const selection = TextSelection.create(original, offset + 1)
    const view = harness(original, selection)
    assert.equal(handleMermaidPaste(view, clipboard(diagram)), true)
    assert.equal(view.transactions.length, 1)
    const transaction = view.transactions[0]
    assert.equal(transaction.getMeta('uiEvent'), 'paste')
    assert.equal(transaction.getMeta('paste'), true)
    const result = children(view.state.doc)
    assert.equal(diagrams(view.state.doc).length, 1)
    assert.equal(result.at(-1).eq(original.lastChild), true)
    assert.equal(result.filter(node => node.type.name === 'paragraph').map(node => node.textContent).join(''), 'LeftRghtUntouched')
    assert.equal(diagrams(view.state.doc)[0].attrs.code, code)
    assert.equal(undo(view.state, view.dispatch), true)
    assert.ok(view.state.doc.eq(original))
    assert.ok(view.state.selection.eq(selection))
    assert.equal(undo(view.state, view.dispatch), false)
    assert.equal(redo(view.state, view.dispatch), true)
    assert.equal(diagrams(view.state.doc)[0].attrs.code, code)
  }
})

test('forward and reverse selections replace only selected text and restore the selection', () => {
  for (const reverse of [false, true]) {
    const original = doc(p('Before selected After'))
    const selection = TextSelection.create(original, reverse ? 16 : 8, reverse ? 8 : 16)
    const view = harness(original, selection)
    assert.equal(handleMermaidPaste(view, clipboard(diagram)), true)
    assert.deepEqual(children(view.state.doc).map(node => node.type.name), ['paragraph', 'codeBlock', 'paragraph'])
    assert.equal(view.state.doc.firstChild.textContent, 'Before ')
    assert.equal(view.state.doc.lastChild.textContent, ' After')
    assert.equal(undo(view.state, view.dispatch), true)
    assert.ok(view.state.doc.eq(original))
    assert.ok(view.state.selection.eq(selection))
  }
})

test('selections across paragraphs and node selections use current ProseMirror positions', () => {
  const original = doc(p('Before target'), p('target After'), p('Untouched'))
  const selection = TextSelection.create(original, 8, original.firstChild.nodeSize + 7)
  const view = harness(original, selection)
  assert.equal(handleMermaidPaste(view, clipboard(diagram)), true)
  assert.equal(view.state.doc.firstChild.textContent, 'Before ')
  assert.equal(view.state.doc.child(2).textContent, ' After')
  assert.equal(view.state.doc.lastChild.textContent, 'Untouched')
  assert.equal(undo(view.state, view.dispatch), true)
  assert.ok(view.state.doc.eq(original))

  const withAtom = doc(p('Before'), schema.node('codeBlock', { language: 'js', code: 'synthetic();' }), p('After'))
  const atomView = harness(withAtom, NodeSelection.create(withAtom, withAtom.firstChild.nodeSize))
  assert.equal(handleMermaidPaste(atomView, clipboard(diagram)), true)
  assert.equal(atomView.state.doc.childCount, 3)
  assert.equal(atomView.state.doc.child(1).attrs.code, code)
  assert.equal(undo(atomView.state, atomView.dispatch), true)
  assert.ok(atomView.state.doc.eq(withAtom))
})

test('mixed text joins the caret paragraph, keeps marks and inserts multiple diagrams in one transaction', () => {
  const original = doc(p('ABCD', [schema.marks.bold.create()]))
  const view = harness(original, TextSelection.create(original, 3))
  const plain = 'Intro\n' + diagram + '\nMiddle\n' + diagram + '\nEnd'
  assert.equal(handleMermaidPaste(view, clipboard(plain)), true)
  assert.equal(view.transactions.length, 1)
  assert.deepEqual(children(view.state.doc).map(node => node.type.name), ['paragraph', 'codeBlock', 'paragraph', 'codeBlock', 'paragraph'])
  assert.equal(view.state.doc.firstChild.textContent, 'ABIntro')
  assert.equal(view.state.doc.lastChild.textContent, 'EndCD')
  assert.equal(view.state.doc.child(2).firstChild.marks[0].type.name, 'bold')
  assert.ok(diagrams(view.state.doc).every(node => node.marks.length === 0))
  assert.equal(undo(view.state, view.dispatch), true)
  assert.ok(view.state.doc.eq(original))
})

test('paste starts its own undo group after recent typing', () => {
  const original = doc(p('Before'))
  const view = harness(original, TextSelection.create(original, 7))
  view.dispatch(view.state.tr.insertText(' typed'))
  const typed = view.state.doc
  assert.equal(handleMermaidPaste(view, clipboard(diagram)), true)
  assert.equal(undo(view.state, view.dispatch), true)
  assert.ok(view.state.doc.eq(typed))
  assert.equal(undo(view.state, view.dispatch), true)
  assert.ok(view.state.doc.eq(original))
})

test('another mounted draft and its cursor/history remain isolated', () => {
  const contentA = doc(p('Draft A'))
  const contentB = doc(p('Draft B'))
  const a = harness(contentA, TextSelection.create(contentA, 4))
  const b = harness(contentB, TextSelection.create(contentB, 6))
  const bState = b.state
  assert.equal(handleMermaidPaste(a, clipboard(diagram)), true)
  assert.equal(b.state, bState)
  assert.equal(b.transactions.length, 0)
  assert.equal(undo(b.state, b.dispatch), false)
  assert.equal(undo(a.state, a.dispatch), true)
  assert.ok(a.state.doc.eq(contentA))
  assert.ok(b.state.doc.eq(contentB))
})

test('rich HTML code blocks with real languages keep the existing HTML paste path', () => {
  for (const html of [
    '<pre><code class="language-mermaid">flowchart TD\nA --> B</code></pre>',
    '<pre><code class="hljs language-MERMAID">flowchart TD</code></pre>',
    '<pre data-language="mermaid"><code>flowchart TD</code></pre>',
    "<pre><code class='language-javascript'>synthetic();</code></pre>",
    '<pre><code class=language-python>print("synthetic")</code></pre>',
  ]) {
    const view = harness()
    assert.equal(handleMermaidPaste(view, clipboard(diagram, html)), false, html)
    assert.equal(view.transactions.length, 0)
  }
})

test('HTML containing literal fences, including Markdown/plaintext code, still converts', () => {
  for (const html of [
    `<div>${diagram}</div>`,
    `<pre><code>${diagram}</code></pre>`,
    `<pre><code class="language-markdown">${diagram}</code></pre>`,
    `<pre data-language="plaintext"><code>${diagram}</code></pre>`,
  ]) {
    const view = harness()
    assert.equal(handleMermaidPaste(view, clipboard(diagram, html)), true, html)
    assert.equal(diagrams(view.state.doc)[0].attrs.code, code)
  }
})

test('readonly, ordinary paste and missing clipboard/schema do not mutate editor state', () => {
  const view = harness()
  view.editable = false
  assert.equal(handleMermaidPaste(view, clipboard(diagram)), false)
  view.editable = true
  assert.equal(handleMermaidPaste(view, clipboard('ordinary text')), false)
  assert.equal(handleMermaidPaste(view, {}), false)
  assert.equal(handleMermaidPaste(null, clipboard(diagram)), false)
  assert.equal(mermaidPasteSlice(diagram, { nodes: {} }), null)
  assert.equal(mermaidPasteSlice('', schema), null)
  assert.equal(view.transactions.length, 0)
})

test('empty complete fences are atoms while empty unfinished ones stay text', () => {
  assert.equal(mermaidPasteSlice('```mermaid\n```', schema).content.firstChild.attrs.code, '')
  assert.equal(mermaidPasteSlice('```mermaid', schema), null)
})
