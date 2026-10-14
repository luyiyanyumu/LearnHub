import assert from 'node:assert/strict'
import test from 'node:test'
import { Schema } from '@tiptap/pm/model'
import { EditorState, TextSelection } from '@tiptap/pm/state'
import { history, undo, redo } from '@tiptap/pm/history'
import { markdownPasteSlice, handleMarkdownPaste } from './markdownPaste.js'

const schema = new Schema({
  nodes: {
    doc: { content: 'block+' },
    paragraph: { group: 'block', content: 'inline*' },
    codeBlock: { group: 'block', atom: true, isolating: true, attrs: { language: { default: null }, code: { default: '' } } },
    mathBlock: { group: 'block', atom: true, isolating: true, attrs: { tex: { default: '' } } },
    mathInline: { group: 'inline', inline: true, atom: true, attrs: { tex: { default: '' } } },
    table: { group: 'block', content: 'tableRow+', isolating: true },
    tableRow: { content: '(tableCell | tableHeader)+' },
    tableCell: { content: 'block+', isolating: true, attrs: { colspan: { default: 1 }, rowspan: { default: 1 }, colwidth: { default: null }, align: { default: null } } },
    tableHeader: { content: 'block+', isolating: true, attrs: { colspan: { default: 1 }, rowspan: { default: 1 }, colwidth: { default: null }, align: { default: null } } },
    text: { group: 'inline' },
  },
  marks: { bold: {}, code: { code: true, excludes: '_' } },
})
const p = (text = '', marks) => schema.node('paragraph', null, text ? schema.text(text, marks) : null)
const doc = (...nodes) => schema.node('doc', null, nodes)
const clipboard = (plain, html = '') => ({ clipboardData: { getData(type) { return type === 'text/plain' ? plain : type === 'text/html' ? html : '' } } })
const children = node => Array.from({ length: node.childCount }, (_, index) => node.child(index))
function mathNodes(content) {
  const found = []
  content.descendants(node => { if (node.type.name === 'mathBlock' || node.type.name === 'mathInline') found.push(node) })
  return found
}
function harness(content = doc(p()), selection = TextSelection.atStart(content)) {
  let state = EditorState.create({ schema, doc: content, selection, plugins: [history()] })
  const transactions = []
  return {
    editable: true,
    get state() { return state },
    transactions,
    dispatch(transaction) { transaction.doc.check(); transactions.push(transaction); state = state.apply(transaction) },
  }
}

test('shared inline parser recognizes dollar and LaTeX parentheses while preserving surrounding text', () => {
  const slice = markdownPasteSlice(String.raw`方程 $E=mc^2$ 与 \(a^2+b^2=c^2\) 后面。`, schema)
  assert.deepEqual(children(slice.content.firstChild).map(node => node.type.name), ['text', 'mathInline', 'text', 'mathInline', 'text'])
  assert.deepEqual(mathNodes(slice.content).map(node => node.attrs.tex), ['E=mc^2', 'a^2+b^2=c^2'])
  assert.equal(slice.content.firstChild.textContent, '方程  与  后面。')
})

test('pasted parentheses formulas allow whitespace and retain exact source in atoms and undo history', () => {
  const original = doc(p('ABCD'))
  const selection = TextSelection.create(original, 3)
  const view = harness(original, selection)
  const raw = 'Before \\( x + y \\) and \\(\tz^2\t\\)\n\\[ x - y \\]'
  assert.equal(handleMarkdownPaste(view, clipboard(raw)), true)
  assert.deepEqual(mathNodes(view.state.doc).map(node => node.attrs.tex), [' x + y ', '\tz^2\t', ' x - y '])
  assert.deepEqual(mathNodes(view.state.doc).map(node => node.type.name), ['mathInline', 'mathInline', 'mathBlock'])
  assert.equal(view.transactions.length, 1)
  assert.equal(undo(view.state, view.dispatch), true)
  assert.ok(view.state.doc.eq(original))
  assert.ok(view.state.selection.eq(selection))
})

test('blank parentheses stay literal during paste, including when a later formula is recognized', () => {
  for (const raw of ['\\(\\)', '\\(   \\)', '\\(\t\n \\)', '\\[   \\]']) {
    assert.equal(markdownPasteSlice(raw, schema), null, raw)
  }
  const slice = markdownPasteSlice('空括号 \\(   \\)，公式 \\( x + y \\)', schema)
  assert.equal(slice.content.firstChild.firstChild.text, '空括号 \\(   \\)，公式 ')
  assert.deepEqual(mathNodes(slice.content).map(node => node.attrs.tex), [' x + y '])
})

test('complete display delimiters preserve exact multiline TeX, whitespace and CRLF normalization', () => {
  const latex = String.raw`\begin{aligned}
  a &= b + c \\
  d &= e
\end{aligned}`
  for (const [open, close] of [['$$', '$$'], ['\\[', '\\]']]) {
    const raw = `${open}\r\n${latex.replaceAll('\n', '\r\n')}  \r\n${close}\r\n`
    const slice = markdownPasteSlice(raw, schema)
    assert.equal(slice.content.childCount, 1)
    assert.equal(slice.content.firstChild.type.name, 'mathBlock')
    assert.equal(slice.content.firstChild.attrs.tex, `\n${latex}  \n`)
  }
  assert.equal(markdownPasteSlice('$$ x + y $$', schema).content.firstChild.attrs.tex, ' x + y ')
})

test('inline math may span lines without losing its original source', () => {
  const raw = 'Before $a +\nb$ After'
  const slice = markdownPasteSlice(raw, schema)
  assert.equal(slice.content.childCount, 1)
  assert.equal(mathNodes(slice.content)[0].attrs.tex, 'a +\nb')
  assert.equal(slice.content.firstChild.textContent, 'Before  After')
  assert.deepEqual(children(slice.content.firstChild).map(node => node.type.name), ['text', 'mathInline', 'text'])
})

test('code fences, nested examples and inline code protect formula syntax', () => {
  for (const raw of [
    '```latex\n$x+y$\n$$a=b$$\n```',
    '````markdown\n```mermaid\nflowchart TD\n```\n$x+y$\n````',
    '    $x+y$\n    $$a=b$$',
    '`$x+y$` and `\\(a+b\\)`',
    '``literal\n$x+y$\ncode``',
  ]) assert.equal(markdownPasteSlice(raw, schema), null, raw)
  const slice = markdownPasteSlice('`$literal$` then $a+b$', schema)
  assert.equal(slice.content.firstChild.firstChild.text, '`$literal$` then ')
  assert.deepEqual(mathNodes(slice.content).map(node => node.attrs.tex), ['a+b'])
})

test('escaped dollars, currency, whitespace and unmatched delimiters remain ordinary text', () => {
  for (const raw of [
    String.raw`价格 \$5 与 $6`,
    '价格 $5 与 $6',
    '$5$', '$1,234.50$', '$5 USD$', '$20 dollars$',
    '$ 两边空格 $', '$x+y $', '$x+y',
    '$$\n未闭合', String.raw`\[未闭合`,
    String.raw`\(未闭合`,
    'Ordinary synthetic prose with no delimiters.',
    'Text $$x+y$$ after', 'Text $$x+y$ after',
  ]) assert.equal(markdownPasteSlice(raw, schema), null, raw)
  const slice = markdownPasteSlice('价格 $5$，公式 $x+1$，明确数字 \\(5\\)', schema)
  assert.equal(slice.content.firstChild.firstChild.text, '价格 $5$，公式 ')
  assert.deepEqual(mathNodes(slice.content).map(node => node.attrs.tex), ['x+1', '5'])
  assert.deepEqual(mathNodes(markdownPasteSlice('$a$$b$', schema).content).map(node => node.attrs.tex), ['a', 'b'])
})

test('display blocks with trailing text are retained literally without dropping any source', () => {
  const raw = '$$x+y$$ trailing synthetic text\n后面 $z^2$'
  const slice = markdownPasteSlice(raw, schema)
  assert.equal(slice.content.firstChild.textContent, '$$x+y$$ trailing synthetic text')
  assert.deepEqual(mathNodes(slice.content).map(node => node.attrs.tex), ['z^2'])
  assert.equal(markdownPasteSlice('$$\nx+y\n$$ trailing text', schema), null)
  assert.equal(markdownPasteSlice('$$   $$', schema), null)
})

test('Mermaid, inline formulas and display formulas share one selection transaction and undo', () => {
  const original = doc(p('ABCD'), p('Untouched'))
  const selection = TextSelection.create(original, 3)
  const view = harness(original, selection)
  const raw = 'Before $a+b$\n```mermaid\nflowchart TD\nA --> B  \n```\n$$\nx^2 + y^2\n$$\nAfter \\(c=d\\)'
  assert.equal(handleMarkdownPaste(view, clipboard(raw)), true)
  assert.equal(view.transactions.length, 1)
  assert.equal(view.transactions[0].getMeta('uiEvent'), 'paste')
  assert.equal(view.transactions[0].getMeta('paste'), true)
  assert.deepEqual(children(view.state.doc).map(node => node.type.name), ['paragraph', 'codeBlock', 'mathBlock', 'paragraph', 'paragraph'])
  assert.equal(view.state.doc.firstChild.textContent, 'ABBefore ')
  assert.equal(view.state.doc.child(1).attrs.code, 'flowchart TD\nA --> B  \n')
  assert.equal(view.state.doc.child(3).textContent, 'After CD')
  assert.deepEqual(mathNodes(view.state.doc).map(node => node.attrs.tex), ['a+b', '\nx^2 + y^2\n', 'c=d'])
  assert.equal(undo(view.state, view.dispatch), true)
  assert.ok(view.state.doc.eq(original))
  assert.ok(view.state.selection.eq(selection))
  assert.equal(undo(view.state, view.dispatch), false)
  assert.equal(redo(view.state, view.dispatch), true)
  assert.equal(mathNodes(view.state.doc).length, 3)
})

test('inline caret insertion and forward/reverse selection replacements preserve marks and undo selection', () => {
  const bold = schema.marks.bold.create()
  for (const [from, to] of [[1, 1], [3, 3], [5, 5], [2, 4], [4, 2]]) {
    const original = doc(p('ABCD', [bold]))
    const selection = TextSelection.create(original, from, to)
    const view = harness(original, selection)
    assert.equal(handleMarkdownPaste(view, clipboard('$x^2$')), true)
    const formula = mathNodes(view.state.doc)[0]
    assert.equal(formula.attrs.tex, 'x^2')
    assert.equal(formula.marks[0].type.name, 'bold')
    assert.equal(view.state.doc.textContent, 'ABCD'.slice(0, Math.min(from, to) - 1) + 'ABCD'.slice(Math.max(from, to) - 1))
    assert.equal(view.transactions.length, 1)
    assert.equal(undo(view.state, view.dispatch), true)
    assert.ok(view.state.doc.eq(original))
    assert.ok(view.state.selection.eq(selection))
  }
})

test('formula paste has an independent undo group and cannot touch another note draft', () => {
  const original = doc(p('Before'))
  const view = harness(original, TextSelection.create(original, 7))
  const other = harness(doc(p('Other draft')))
  const otherState = other.state
  view.dispatch(view.state.tr.insertText(' typed'))
  const typed = view.state.doc
  assert.equal(handleMarkdownPaste(view, clipboard('$$a=b$$')), true)
  assert.equal(other.state, otherState)
  assert.equal(other.transactions.length, 0)
  assert.equal(undo(other.state, other.dispatch), false)
  assert.equal(undo(view.state, view.dispatch), true)
  assert.ok(view.state.doc.eq(typed))
  assert.equal(undo(view.state, view.dispatch), true)
  assert.ok(view.state.doc.eq(original))
})

test('literal HTML and Markdown remain raw text while recognized formulas become safe atoms', () => {
  const raw = '<script>alert("synthetic")</script> $\\text{<img src=x onerror=alert(1)>}$\n# Literal heading'
  const slice = markdownPasteSlice(raw, schema)
  assert.equal(slice.content.firstChild.firstChild.text, '<script>alert("synthetic")</script> ')
  assert.equal(mathNodes(slice.content)[0].attrs.tex, '\\text{<img src=x onerror=alert(1)>}')
  assert.equal(slice.content.lastChild.textContent, '# Literal heading')
  assert.ok(children(slice.content).every(node => node.type.name === 'paragraph'))
})

test('real KaTeX and source-bearing math HTML keep the existing rich paste path', () => {
  for (const html of [
    '<span class="katex">rendered</span>',
    '<span class=katex>rendered</span>',
    '<span class="katex-display"><span class="katex">rendered</span></span>',
    '<span class="math-inline" data-tex="x^2"></span>',
    '<div data-tex="a=b" class="math-block"></div>',
    '<span class="md-editor-katex-inline">x^2</span>',
    '<annotation encoding="application/x-tex">x^2</annotation>',
    '<pre><code class="language-mermaid">flowchart TD</code></pre>',
  ]) {
    const view = harness()
    assert.equal(handleMarkdownPaste(view, clipboard('$x^2$', html)), false, html)
    assert.equal(view.transactions.length, 0)
  }
})

test('HTML wrapping literal formulas, including Markdown code, converts from plain text', () => {
  for (const html of ['<p>$x^2$</p>', '<pre><code class="language-markdown">$x^2$</code></pre>']) {
    const view = harness()
    assert.equal(handleMarkdownPaste(view, clipboard('$x^2$', html)), true)
    assert.equal(mathNodes(view.state.doc)[0].attrs.tex, 'x^2')
  }
})

test('readonly and missing clipboard/schema fall through without mutation', () => {
  const view = harness()
  view.editable = false
  assert.equal(handleMarkdownPaste(view, clipboard('$x^2$')), false)
  view.editable = true
  assert.equal(handleMarkdownPaste(view, clipboard('ordinary text')), false)
  assert.equal(handleMarkdownPaste(view, {}), false)
  assert.equal(handleMarkdownPaste(null, clipboard('$x^2$')), false)
  assert.equal(markdownPasteSlice('$x^2$', { nodes: { paragraph: schema.nodes.paragraph }, text: schema.text.bind(schema) }), null)
  assert.equal(view.transactions.length, 0)
})

test('Chinese adjacent bold is recognized without requiring spaces and retains surrounding text', () => {
  const raw = '共用一个**最多 20 项**的重排池'
  const slice = markdownPasteSlice(raw, schema)
  assert.equal(slice.content.firstChild.textContent, '共用一个最多 20 项的重排池')
  assert.deepEqual(children(slice.content.firstChild).map(node => [node.text, node.marks.map(mark => mark.type.name)]), [
    ['共用一个', []], ['最多 20 项', ['bold']], ['的重排池', []],
  ])
  for (const literal of [String.raw`\**转义**`, '`**代码**`', '***三重***', '****四重****', '** 空格 **', '**未闭合']) {
    assert.equal(markdownPasteSlice(literal, schema), null, literal)
  }
})

test('bold around math and multiple bold pairs preserve atoms, escaped text and unrelated Markdown', () => {
  const raw = String.raw`前**公式 $x + y$ 与 \( z \) 表达式**后，**加粗**；\**转义**，\*原样\*，# 标题 [链接](synthetic)。`
  const slice = markdownPasteSlice(raw, schema)
  assert.deepEqual(mathNodes(slice.content).map(node => [node.attrs.tex, node.marks[0]?.type.name]), [['x + y', 'bold'], [' z ', 'bold']])
  assert.equal(slice.content.firstChild.textContent, String.raw`前公式  与  表达式后，加粗；\**转义**，\*原样\*，# 标题 [链接](synthetic)。`)
})

test('GFM table becomes editable table rows/cells and preserves alignment, empty cells and inline syntax', () => {
  const raw = '| 名称 | 内容 | 空列 |\n| :--- | ---: | :---: |\n| 中文**加粗** | $x+y$ 与 `a\\|b`、c\\|d、\\( z \\) | |'
  const slice = markdownPasteSlice(raw, schema)
  const table = slice.content.firstChild
  assert.equal(table.type.name, 'table')
  assert.equal(table.childCount, 2)
  assert.deepEqual(children(table.firstChild).map(node => node.type.name), ['tableHeader', 'tableHeader', 'tableHeader'])
  assert.deepEqual(children(table.lastChild).map(node => node.attrs.align), ['left', 'right', 'center'])
  assert.equal(table.lastChild.firstChild.textContent, '中文加粗')
  assert.equal(table.lastChild.firstChild.firstChild.lastChild.marks[0].type.name, 'bold')
  const content = table.lastChild.child(1)
  assert.equal(content.textContent, ' 与 a|b、c|d、')
  assert.deepEqual(mathNodes(content).map(node => node.attrs.tex), ['x+y', ' z '])
  assert.equal(children(content.firstChild).find(node => node.text === 'a|b').marks[0].type.name, 'code')
  assert.equal(table.lastChild.lastChild.firstChild.childCount, 0)
  table.check()
})

test('table inline code shields math/bold and supports longer backtick delimiters', () => {
  const slice = markdownPasteSlice('| a | b |\n| --- | --- |\n| `**raw** $x$` | ``a`b\\|c`` |', schema)
  const cells = children(slice.content.firstChild.lastChild)
  assert.equal(cells[0].textContent, '**raw** $x$')
  assert.equal(cells[1].textContent, 'a`b|c')
  assert.equal(cells[0].firstChild.firstChild.marks[0].type.name, 'code')
  assert.equal(mathNodes(slice.content).length, 0)
})

test('isolating table edges keep the existing paragraph prefix/suffix outside cells', () => {
  const tableText = '| a | b |\n| --- | --- |\n| 1 | 2 |'
  for (const raw of [tableText, `前**正文**\n${tableText}`, `${tableText}\n\n后**正文**`]) {
    for (const [from, to] of [[1, 1], [3, 3], [5, 5], [2, 4], [4, 2]]) {
      const original = doc(p('ABCD'))
      const selection = TextSelection.create(original, from, to)
      const view = harness(original, selection)
      assert.equal(handleMarkdownPaste(view, clipboard(raw)), true)
      const table = children(view.state.doc).find(node => node.type.name === 'table')
      assert.equal(table.firstChild.firstChild.textContent, 'a')
      assert.equal(table.lastChild.lastChild.textContent, '2')
      const suffix = (raw.endsWith('正文**') ? '后正文' : '') + 'ABCD'.slice(Math.max(from, to) - 1)
      const prefix = 'ABCD'.slice(0, Math.min(from, to) - 1) + (raw.startsWith('前') ? '前正文' : '')
      if (suffix) {
        assert.equal(view.state.doc.lastChild.type.name, 'paragraph')
        assert.equal(view.state.doc.lastChild.textContent, suffix)
      }
      if (prefix) {
        assert.equal(view.state.doc.firstChild.type.name, 'paragraph')
        assert.equal(view.state.doc.firstChild.textContent, prefix)
      }
      assert.equal(undo(view.state, view.dispatch), true)
      assert.ok(view.state.doc.eq(original))
      assert.ok(view.state.selection.eq(selection))
    }
  }
})

test('fenced tables and malformed extra cells stay literal without dropping source', () => {
  const table = '| a | b |\n| --- | --- |\n| 1 | `x|y` |'
  assert.equal(markdownPasteSlice(table, schema), null)
  assert.equal(markdownPasteSlice(`\`\`\`markdown\n${table}\n\`\`\``, schema), null)
  const raw = `\`\`\`markdown\n| a | b |\n| --- | --- |\n| **raw** | $x$ |\n\`\`\`\n后**识别**`
  const slice = markdownPasteSlice(raw, schema)
  assert.equal(slice.content.childCount, 6)
  assert.equal(slice.content.child(3).textContent, '| **raw** | $x$ |')
  assert.equal(slice.content.lastChild.textContent, '后识别')
  assert.equal(mathNodes(slice.content).length, 0)
  assert.equal(markdownPasteSlice(`${table}\n\n后 $z$`, schema).content.firstChild.textContent, '| a | b |')
})

test('pipe escaping uses slash parity and excess columns retain the entire raw table', () => {
  for (const count of [0, 2, 4]) {
    const raw = `| a | b |\n| --- | --- |\n| 1 | x${'\\'.repeat(count)}|y |`
    assert.equal(markdownPasteSlice(raw, schema), null, `slashes=${count}`)
    const mixed = markdownPasteSlice(`${raw}\n\n后**合成**`, schema)
    assert.equal(mixed.content.child(2).textContent, raw.split('\n')[2])
  }
  for (const count of [1, 3]) {
    const raw = `| a | b |\n| --- | --- |\n| 1 | x${'\\'.repeat(count)}|y |`
    assert.equal(markdownPasteSlice(raw, schema).content.firstChild.type.name, 'table', `slashes=${count}`)
  }
})

test('mixed table, math, Mermaid and bold replace only current selection in a single undo group', () => {
  const raw = '前**合成** $x$\n| a | b |\n| --- | --- |\n| **值** | \\( y \\) |\n\n```mermaid\nflowchart TD\nA --> B  \n```\n$$\n z^2 \n$$\n后文'
  for (const [from, to] of [[3, 3], [2, 4], [4, 2]]) {
    const original = doc(p('ABCD'), p('保留'))
    const selection = TextSelection.create(original, from, to)
    const view = harness(original, selection)
    const other = harness(doc(p('其他草稿')))
    const otherState = other.state
    view.dispatch(view.state.tr.insertText('先输入'))
    const typedDoc = view.state.doc
    const typedSelection = view.state.selection
    const before = view.transactions.length
    assert.equal(handleMarkdownPaste(view, clipboard(raw)), true)
    assert.equal(view.transactions.length, before + 1)
    assert.ok(children(view.state.doc).some(node => node.type.name === 'table'))
    assert.equal(view.state.doc.lastChild.textContent, '保留')
    assert.deepEqual(mathNodes(view.state.doc).map(node => node.attrs.tex), ['x', ' y ', '\n z^2 \n'])
    assert.equal(other.state, otherState)
    assert.equal(other.transactions.length, 0)
    const pasted = view.state.doc
    assert.equal(undo(view.state, view.dispatch), true)
    assert.ok(view.state.doc.eq(typedDoc))
    assert.ok(view.state.selection.eq(typedSelection))
    assert.equal(redo(view.state, view.dispatch), true)
    assert.ok(view.state.doc.eq(pasted))
  }
})

test('rich formatting/table HTML wins while plain Chinese wrappers still recognize Markdown', () => {
  const raw = '共用一个**最多 20 项**的重排池'
  for (const html of ['<p><strong>真实加粗</strong></p>', '<p><em>真实斜体</em></p>', '<table><tr><td>真实表格</td></tr></table>', '<span style="font-weight:700">真实样式</span>', '<span style="color:teal">真实颜色</span>', '<code>真实代码</code>']) {
    const view = harness()
    assert.equal(handleMarkdownPaste(view, clipboard(raw, html)), false, html)
    assert.equal(view.transactions.length, 0)
  }
  for (const html of [`<span>${raw}</span>`, `<meta charset="utf-8"><div><p><span style="font-weight:normal">${raw}</span></p></div>`]) {
    const view = harness()
    assert.equal(handleMarkdownPaste(view, clipboard(raw, html)), true, html)
    assert.equal(view.state.doc.textContent, '共用一个最多 20 项的重排池')
  }
})

test('pasting Markdown inside a code mark retains the normal literal code paste path', () => {
  const original = doc(p('AB', [schema.marks.code.create()]))
  const view = harness(original, TextSelection.create(original, 2))
  assert.equal(handleMarkdownPaste(view, clipboard('**原样** $x$')), false)
  assert.ok(view.state.doc.eq(original))
  assert.equal(view.transactions.length, 0)
})
