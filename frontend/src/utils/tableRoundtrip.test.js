import assert from 'node:assert/strict'
import test from 'node:test'
import domino from '@mixmark-io/domino'
import MarkdownIt from 'markdown-it'
import { getSchema } from '@tiptap/core'
import StarterKit from '@tiptap/starter-kit'
import { TableKit } from '@tiptap/extension-table'
import { DOMParser, DOMSerializer } from '@tiptap/pm/model'
import { previewHtmlToMd } from './htmlToMd.js'
import { MathInline, MathBlock } from './mathNodes.js'
import mdMath from './mdMath.js'

const schema = getSchema([StarterKit, TableKit, MathInline, MathBlock])
const shellMd = new MarkdownIt({ html: true, breaks: true }).use(mdMath, { renderKatex: false })
const previewMd = new MarkdownIt({ html: true, breaks: true }).use(mdMath)
function withDocument(operation) {
  const previous = globalThis.document
  const document = domino.createWindow().document
  // Domino's HTMLCollection predates the browser iterable DOM API.
  const collection = Object.getPrototypeOf(document.createElement('div').children)
  if (!collection[Symbol.iterator]) Object.defineProperty(collection, Symbol.iterator, { value: Array.prototype[Symbol.iterator] })
  const createElement = document.createElement.bind(document)
  Object.defineProperty(document, 'createElement', { value: tag => {
    const element = createElement(tag)
    const queryAll = element.querySelectorAll.bind(element)
    Object.defineProperty(element, 'querySelectorAll', { value: selector => Array.from(queryAll(selector)) })
    return element
  } })
  globalThis.document = document
  try { return operation(document) } finally { globalThis.document = previous }
}
function parse(html, document) {
  const box = document.createElement('div')
  box.innerHTML = html
  return DOMParser.fromSchema(schema).parse(box)
}
const table = content => `<table><tbody><tr><th><p>标题</p></th><th><p>说明</p></th></tr>${content}</tbody></table>`

test('cell line breaks, blank lines, marks and neighboring cells survive repeated saves', () => withDocument(document => {
  const original = table('<tr><td><p><strong>第一行</strong><br><br><a href="https://example.com">第三行</a><br></p></td><td><p>相邻不变</p></td></tr>')
  const before = parse(original, document)
  let html = original
  for (let i = 0; i < 3; i++) {
    const markdown = previewHtmlToMd(html)
    assert.match(markdown, /<table>/)
    html = new MarkdownIt({ html: true, breaks: true }).render(markdown)
    const after = parse(html, document)
    after.check()
    assert.ok(after.eq(before))
    assert.equal((html.match(/<br\s*\/?>/g) || []).length, 3)
  }
}))

test('multiple paragraphs and merged cells preserve their structure on reopen', () => withDocument(document => {
  const original = '<table><tbody><tr><th colspan="2"><p>合并标题<br>第二行</p></th></tr><tr><td rowspan="2"><p>第一段</p><p></p><p>第三段</p></td><td><p>右一</p></td></tr><tr><td><p>右二</p></td></tr></tbody></table>'
  const markdown = previewHtmlToMd(original)
  assert.match(markdown, /colspan="2"/)
  assert.match(markdown, /rowspan="2"/)
  const after = parse(new MarkdownIt({ html: true, breaks: true }).render(markdown), document)
  assert.ok(after.eq(parse(original, document)))
}))

test('ordinary single-line tables still use Markdown pipe syntax', () => withDocument(document => {
  const original = table('<tr><td>甲</td><td>乙</td></tr>')
  const markdown = previewHtmlToMd(original)
  assert.ok(!markdown.includes('<table'))
  assert.match(markdown, /\|.*标题.*\|.*说明.*\|/)
  const after = parse(new MarkdownIt({ html: true, breaks: true }).render(markdown), document)
  assert.ok(after.eq(parse(original, document)))
}))

test('raw HTML table formulas become atomic editor nodes and survive repeated save and reopen cycles', () => withDocument(document => {
  const source = String.raw`<table><colgroup><col style="width: 240px"><col style="width: 320px"><col style="width: 400px"></colgroup><tbody>
<tr><th colspan="3"><p><strong>合成注意力形状</strong><br>按行检查</p></th></tr>
<tr><td rowspan="2" colwidth="240"><p>$QK^\top$</p><p>查询与键</p></td><td><p>$n\times n$</p></td><td><p>第 $(i,j)$ 项：<strong>相关性分数</strong></p></td></tr>
<tr><td><p>$\operatorname{softmax}(\cdot)$</p></td><td><p>每行和为 1<br>条件 $x &lt; y$ 与 <a href="https://example.com/attention">合成链接</a></p></td></tr>
<tr><td><p>$\times V$</p></td><td><p>$n\times d_v$</p></td><td><p>加权和 $\sum_j a_{ij}v_j$</p></td></tr>
<tr><td colspan="3">$$\begin{aligned}a&amp;=b\\c&amp;=d\end{aligned}$$</td></tr>
</tbody></table>`
  const expectedTex = [
    String.raw`QK^\top`, String.raw`n\times n`, '(i,j)', String.raw`\operatorname{softmax}(\cdot)`,
    'x < y', String.raw`\times V`, String.raw`n\times d_v`, String.raw`\sum_j a_{ij}v_j`,
    String.raw`\begin{aligned}a&=b\\c&=d\end{aligned}`,
  ]
  const formulas = doc => {
    const nodes = []
    doc.descendants(node => { if (node.type.name === 'mathInline' || node.type.name === 'mathBlock') nodes.push(node) })
    return nodes
  }
  const original = parse(shellMd.render(source), document)
  original.check()
  assert.deepEqual(formulas(original).map(node => node.attrs.tex), expectedTex)
  assert.deepEqual(formulas(original).map(node => node.type.name), [...Array(8).fill('mathInline'), 'mathBlock'])
  assert.ok(original.eq(parse(previewMd.render(source), document)), 'preview and editor loaders produce the same math nodes')
  const firstTable = original.firstChild
  assert.equal(firstTable.firstChild.firstChild.attrs.colspan, 3)
  assert.equal(firstTable.child(1).firstChild.attrs.rowspan, 2)
  assert.deepEqual(firstTable.child(1).firstChild.attrs.colwidth, [240])

  let current = original
  for (let pass = 0; pass < 3; pass++) {
    const serialized = document.createElement('div')
    serialized.appendChild(DOMSerializer.fromSchema(schema).serializeFragment(current.content, { document }))
    const markdown = previewHtmlToMd(serialized.innerHTML)
    assert.match(markdown, /<table/)
    assert.match(markdown, /colspan="3"/)
    assert.match(markdown, /rowspan="2"/)
    assert.ok(!markdown.includes('LEARNHUBMATHTOKEN'))
    const preview = previewMd.render(markdown)
    const previewBox = document.createElement('div')
    previewBox.innerHTML = preview
    const previewFormulas = Array.from(previewBox.querySelectorAll('.math-inline[data-tex], .math-block[data-tex]'))
    assert.deepEqual(previewFormulas.map(node => node.getAttribute('data-tex')), expectedTex)
    assert.equal(previewFormulas.length, expectedTex.length)
    assert.equal(previewBox.querySelectorAll('.katex').length, expectedTex.length)
    const reopened = parse(shellMd.render(markdown), document)
    reopened.check()
    assert.ok(reopened.eq(original), `table structure, formatting and TeX survive save cycle ${pass + 1}`)
    assert.ok(parse(preview, document).eq(original))
    current = reopened
  }
}))

test('standalone display formulas in table paragraphs survive saves without added empty paragraphs', () => withDocument(document => {
  const source = '<table><tr><td><p>$$x^2$$</p></td><td><p>相邻内容</p></td></tr></table>'
  const original = parse(shellMd.render(source), document)
  assert.equal(original.firstChild.firstChild.firstChild.childCount, 1)
  assert.equal(original.firstChild.firstChild.firstChild.firstChild.type.name, 'mathBlock')
  let current = original
  for (let pass = 0; pass < 3; pass++) {
    const serialized = document.createElement('div')
    serialized.appendChild(DOMSerializer.fromSchema(schema).serializeFragment(current.content, { document }))
    assert.equal(serialized.querySelectorAll('p:empty').length, 0)
    const markdown = previewHtmlToMd(serialized.innerHTML)
    const reopened = parse(shellMd.render(markdown), document)
    reopened.check()
    assert.ok(reopened.eq(original), `display-only cells do not gain paragraphs on save ${pass + 1}`)
    assert.ok(parse(previewMd.render(markdown), document).eq(original))
    current = reopened
  }
}))
