import assert from 'node:assert/strict'
import test from 'node:test'
import domino from '@mixmark-io/domino'
import MarkdownIt from 'markdown-it'
import { previewHtmlToMd } from './htmlToMd.js'
import { mdMath, renderMathToHtml } from './mdMath.js'

const md = new MarkdownIt({ html: true, breaks: true }).use(mdMath)
const biEncoder = String.raw`s(q,d)\;=\;\big\langle E_q(q),\;E_d(d)\big\rangle`
const crossEncoder = String.raw`s(q,d)\;=\;\text{Transformer}\big([\text{CLS}]\; q\; [\text{SEP}]\; d\; [\text{SEP}]\big)_{\text{[CLS]}}`
const aligned = String.raw`\begin{aligned}
a_{1} &= \frac{x}{2} \\


b_{2} &= \left\langle u,v\right\rangle \\
c &= \begin{pmatrix} 1 & 2 \\ 3 & 4 \end{pmatrix}
\end{aligned}`

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

function mathShell(document, tex, display = false) {
  const node = document.createElement(display ? 'div' : 'span')
  node.className = display ? 'math-block' : 'math-inline'
  node.setAttribute('data-tex', tex)
  return node.outerHTML
}

function formulas(document, html) {
  const box = document.createElement('div')
  box.innerHTML = html
  return box.querySelectorAll('.math-block[data-tex],.math-inline[data-tex]')
    .map(node => node.getAttribute('data-tex'))
}

function assertRenderedMathCount(document, html, count) {
  const box = document.createElement('div')
  box.innerHTML = html
  assert.equal(box.querySelectorAll('.katex').length, count)
  assert.equal(box.querySelectorAll('annotation[encoding="application/x-tex"]').length, count)
  assert.equal(box.querySelectorAll('.katex .katex,.math-inline .math-inline,.math-block .math-block').length, 0)
}

function assertNoPlaceholders(source) {
  assert.ok(!source.includes('data-lh-math-token'), 'temporary math DOM must never reach saved Markdown')
  assert.ok(!/LEARNHUBMATHTOKEN\d+END/.test(source), 'temporary math text must never reach saved Markdown')
}

test('empty data-tex shells serialize inline and display TeX without escaping commands or subscripts', () => withDocument(document => {
  const html = `<p>Similarity ${mathShell(document, biEncoder)}.</p>${mathShell(document, crossEncoder, true)}`
  const markdown = previewHtmlToMd(html)
  assert.equal(markdown, `Similarity $${biEncoder}$.\n\n$$\n${crossEncoder}\n$$`)
  const rendered = md.render(markdown)
  assert.deepEqual(formulas(document, rendered), [biEncoder, crossEncoder])
  assert.ok(!rendered.includes('katex-error'))
  assertNoPlaceholders(markdown)
}))

test('editing and saving rendered math repeatedly never increases escapes or duplicates formulas', () => withDocument(document => {
  const original = `Bi: $${biEncoder}$\n\n$$\n${crossEncoder}\n$$`
  let markdown = original
  for (let save = 0; save < 5; save++) {
    markdown = previewHtmlToMd(md.render(markdown))
    assert.equal(markdown, original)
    assert.deepEqual(formulas(document, md.render(markdown)), [biEncoder, crossEncoder])
    assertNoPlaceholders(markdown)
  }
}))

test('aligned equations retain real row separators, matrices, blank lines and whitespace byte for byte', () => withDocument(document => {
  const original = `$$\n${aligned}\n$$`
  let markdown = original
  for (let save = 0; save < 4; save++) {
    markdown = previewHtmlToMd(md.render(markdown))
    assert.equal(markdown, original)
    const rendered = md.render(markdown)
    assert.deepEqual(formulas(document, rendered), [aligned])
    assert.ok(!rendered.includes('katex-error'))
  }
}))

test('inline TeX preserves literal escapes, braces, ampersands, quotes and internal newlines', () => withDocument(document => {
  const tex = String.raw`\text{a & "b"}\; x\_y + \{z\} + \$` + '\n  q+\tq'
  const markdown = previewHtmlToMd(`<p>${mathShell(document, tex)}</p>`)
  assert.equal(markdown, `$${tex}$`)
  assert.deepEqual(formulas(document, md.render(markdown)), [tex])
}))

test('KaTeX clipboard HTML extracts annotation once for inline and display formulas', () => withDocument(document => {
  const html = `<p>${renderMathToHtml(biEncoder)}</p>${renderMathToHtml(aligned, { display: true })}`
  const markdown = previewHtmlToMd(html)
  assert.equal(markdown, `$${biEncoder}$\n\n$$\n${aligned}\n$$`)
  assert.deepEqual(formulas(document, md.render(markdown)), [biEncoder, aligned])
  assertNoPlaceholders(markdown)
}))

test('md-editor source and rendered wrappers retain TeX and display mode', () => withDocument(document => {
  const plainInline = document.createElement('span')
  plainInline.className = 'md-editor-katex-inline'
  plainInline.textContent = biEncoder
  const plainBlock = document.createElement('div')
  plainBlock.className = 'md-editor-katex-block'
  plainBlock.textContent = aligned
  const markdown = previewHtmlToMd(`<p>${plainInline.outerHTML}</p>${plainBlock.outerHTML}`)
  assert.equal(markdown, `$${biEncoder}$\n\n$$\n${aligned}\n$$`)
  const renderedWrapper = `<div class="md-editor-katex-block">${renderMathToHtml(crossEncoder, { display: true })}</div>`
  assert.equal(previewHtmlToMd(renderedWrapper), `$$\n${crossEncoder}\n$$`)
}))

test('raw metadata paragraphs retain real rendered math HTML through repeated saves', () => withDocument(document => {
  const tex = biEncoder + '\n\n  + q'
  let html = `<p id="block-formula" data-block-indent="1" style="margin-left: 2em">Score ${renderMathToHtml(tex)}.</p>`
  for (let save = 0; save < 4; save++) {
    const markdown = previewHtmlToMd(html)
    assert.match(markdown, /^<p id="block-formula"/)
    assert.ok(markdown.includes('class="math-inline"'))
    assert.ok(markdown.includes('class="katex"'), 'raw HTML retains the rendered formula for HTML readers')
    assert.ok(!markdown.includes(`$${tex}$`), 'math delimiters are not reparsed inside raw HTML paragraphs')
    assertNoPlaceholders(markdown)
    html = md.render(markdown)
    assert.deepEqual(formulas(document, html), [tex])
    assertRenderedMathCount(document, html, 1)
  }
}))

test('sized and merged tables preserve inline and display math as real HTML without marker leaks', () => withDocument(document => {
  let html = `<table><colgroup><col style="width: 180px"><col></colgroup><tbody><tr><th colspan="2"><p>${renderMathToHtml(biEncoder)}</p></th></tr><tr><td rowspan="2">Left</td><td>${renderMathToHtml(aligned, { display: true })}</td></tr><tr><td>Right</td></tr></tbody></table>`
  for (let save = 0; save < 3; save++) {
    const markdown = previewHtmlToMd(html)
    assert.match(markdown, /^<table>/)
    assert.ok(markdown.includes('colspan="2"'))
    assert.ok(markdown.includes('rowspan="2"'))
    assertNoPlaceholders(markdown)
    html = md.render(markdown)
    assert.deepEqual(formulas(document, html), [biEncoder, aligned])
    assertRenderedMathCount(document, html, 2)
  }
}))

test('even ordinary tables keep math HTML when TeX contains pipe characters', () => withDocument(document => {
  const tex = String.raw`\left| x_{1} \right| + \|v\|`
  const html = `<table><thead><tr><th>Formula</th></tr></thead><tbody><tr><td>${mathShell(document, tex)}</td></tr></tbody></table>`
  const markdown = previewHtmlToMd(html)
  assert.match(markdown, /^<table>/)
  assert.deepEqual(formulas(document, md.render(markdown)), [tex])
  assertNoPlaceholders(markdown)
}))

test('kept inline formatting wrappers restore canonical math HTML rather than literal dollars', () => withDocument(document => {
  const html = `<p><span style="color: red">Score ${mathShell(document, biEncoder)}</span></p>`
  const markdown = previewHtmlToMd(html)
  assert.ok(markdown.includes('style="color: red"'))
  assert.ok(markdown.includes('class="math-inline"'))
  assert.deepEqual(formulas(document, md.render(markdown)), [biEncoder])
  assertNoPlaceholders(markdown)
}))

test('placeholder-like user text and formula text remain untouched', () => withDocument(document => {
  const literal = 'LEARNHUBMATHTOKEN0END LEARNHUBMATHTOKENX0END'
  const tex = String.raw`\text{LEARNHUBMATHTOKENXX0END}`
  assert.equal(previewHtmlToMd(`<p>${literal} ${mathShell(document, tex)}</p>`), `${literal} $${tex}$`)
}))

test('normal text escaping and code contents still follow their existing Markdown rules', () => withDocument(() => {
  assert.equal(previewHtmlToMd('<p>x_y \\alpha [literal]</p>'), String.raw`x\_y \\alpha \[literal\]`)
  const code = String.raw`$$\begin{matrix} a \\ b \end{matrix}$$`
  assert.equal(previewHtmlToMd(`<pre><code class="language-tex">${code}</code></pre>`), `\`\`\`tex\n${code}\n\`\`\``)
}))
