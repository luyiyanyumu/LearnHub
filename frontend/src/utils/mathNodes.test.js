import assert from 'node:assert/strict'
import test from 'node:test'
import domino from '@mixmark-io/domino'
import MarkdownIt from 'markdown-it'
import { getSchema } from '@tiptap/core'
import StarterKit from '@tiptap/starter-kit'
import { DOMParser, DOMSerializer } from '@tiptap/pm/model'
import { MathInline, MathBlock } from './mathNodes.js'
import mdMath from './mdMath.js'
import { previewHtmlToMd } from './htmlToMd.js'

const schema = getSchema([StarterKit, MathInline, MathBlock])
const shellMd = new MarkdownIt({ html: true, breaks: true }).use(mdMath, { renderKatex: false })
const fullMd = new MarkdownIt({ html: true, breaks: true }).use(mdMath)

function withDocument(operation) {
  const previous = globalThis.document
  const document = domino.createWindow().document
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

test('source shells and full KaTeX HTML parse into the same formula nodes', () => withDocument(document => {
  const source = '## 合成公式\n\n行内 $x < y$ 与 \\(E_q(q)\\) 保留上下文。\n\n$$\n\\begin{aligned}\ns(q,d)&=\\langle E_q(q),E_d(d)\\rangle\\\\\nc_{t+1}&=U(c_t,u_t,o_{t+1})\n\\end{aligned}\n$$\n\n\\[ \\text{"A"} + 1 \\]'
  const shells = shellMd.render(source)
  const before = parse(shells, document)
  assert.ok(before.eq(parse(fullMd.render(source), document)))
  const formulas = []
  before.descendants(node => { if (node.type.name.startsWith('math')) formulas.push(node) })
  assert.deepEqual(formulas.map(node => node.type.name), ['mathInline', 'mathInline', 'mathBlock', 'mathBlock'])
  assert.equal(formulas[0].attrs.tex, 'x < y')
  assert.equal(formulas[3].attrs.tex, '\\text{"A"} + 1')
  assert.match(formulas[2].attrs.tex, /\\begin\{aligned\}\ns\(q,d\)&=\\langle/)
  assert.ok(!shells.includes('katex'))
  for (let pass = 0; pass < 3; pass++) {
    const serialized = document.createElement('div')
    serialized.appendChild(DOMSerializer.fromSchema(schema).serializeFragment(before.content, { document }))
    const markdown = previewHtmlToMd(serialized.innerHTML)
    assert.ok(parse(shellMd.render(markdown), document).eq(before), 'TeX and surrounding blocks survive Markdown serialization')
    assert.match(fullMd.render(markdown), /class="katex"/)
  }
}))

test('inline and block node views render once and retain DOM until TeX changes', () => withDocument(() => {
  for (const [extension, type, display] of [[MathInline, 'mathInline', false], [MathBlock, 'mathBlock', true]]) {
    const original = schema.node(type, { tex: 'E=mc^2' })
    const view = extension.config.addNodeView()({ node: original })
    const katex = view.dom.querySelector('.katex')
    assert.ok(katex)
    assert.equal(Boolean(view.dom.querySelector('.katex-display')), display)
    assert.equal(view.dom.getAttribute('contenteditable'), 'false')
    assert.equal(view.update(schema.node(type, { tex: 'E=mc^2' })), true)
    assert.equal(view.dom.querySelector('.katex'), katex, 'unchanged TeX keeps its existing rendered DOM')
    assert.equal(view.update(schema.node(type, { tex: '\\frac{1}{x+1}' })), true)
    const changed = view.dom.querySelector('.katex')
    assert.notEqual(changed, katex)
    assert.equal(view.dom.querySelector('annotation').textContent, '\\frac{1}{x+1}')
    assert.equal(view.dom.getAttribute('data-tex'), '\\frac{1}{x+1}')
    assert.equal(view.update(schema.node(type, { tex: '\\frac{1}{x+1}' })), true)
    assert.equal(view.dom.querySelector('.katex'), changed)
    assert.equal(view.update(schema.node(type === 'mathInline' ? 'mathBlock' : 'mathInline', { tex: 'x' })), false)
    assert.equal(view.dom.querySelector('.katex'), changed, 'a different node type is rejected without altering the view')
  }
}))

test('unrelated node-attribute updates keep the rendered formula and its selection target', () => withDocument(() => {
  const FormulaWithMetadata = MathBlock.extend({
    addAttributes() { return { ...this.parent(), sourceVersion: { default: 0 } } },
  })
  const metadataSchema = getSchema([StarterKit, FormulaWithMetadata])
  const view = FormulaWithMetadata.config.addNodeView()({ node: metadataSchema.node('mathBlock', { tex: 'x^2', sourceVersion: 0 }) })
  const initial = view.dom.firstChild
  assert.equal(view.update(metadataSchema.node('mathBlock', { tex: 'x^2', sourceVersion: 1 })), true)
  assert.equal(view.dom.firstChild, initial)
  assert.equal(view.dom.getAttribute('data-tex'), 'x^2')
}))
