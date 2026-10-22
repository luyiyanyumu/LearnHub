import test from 'node:test'
import assert from 'node:assert/strict'
import MarkdownIt from 'markdown-it'
import katex from 'katex'
import domino from '@mixmark-io/domino'
import { mdMath, renderMathInHtml, renderMathToHtml } from './mdMath.js'
import mdDataLine from './mdDataLine.js'
import mdCallout from './mdCallout.js'

const md = new MarkdownIt({ html: true, linkify: true, breaks: true }).use(mdMath)
const html = (src) => md.render(src)

test('inline $…$ renders KaTeX', () => {
  const out = html('质能方程 $E=mc^2$ 就这么短。')
  assert.match(out, /class="math-inline"/)
  assert.match(out, /class="katex"/)
  assert.match(out, /data-tex="E=mc\^2"/)
  assert.ok(!out.includes('$E=mc^2$'), '定界符不该留在输出里')
})

test('block $$…$$ renders display math and may span lines', () => {
  const out = html('$$\nx = \\frac{-b \\pm \\sqrt{b^2-4ac}}{2a}\n$$')
  assert.match(out, /class="math-block"/)
  assert.match(out, /katex-display/)
  assert.match(out, /data-tex="x = \\frac\{-b \\pm \\sqrt\{b\^2-4ac\}\}\{2a\}"/)
})

test('same-line $$ … $$ also works', () => {
  assert.match(html('$$a+b$$'), /class="math-block"/)
})

test('LaTeX delimiters \\(…\\) and \\[…\\] are supported', () => {
  const inline = html('看这个 \\(a^2+b^2=c^2\\) 公式')
  assert.match(inline, /class="math-inline"/)
  assert.ok(!inline.includes('\\('), '定界符应被消费')
  const block = html('\\[ \\int_0^1 x\\,dx \\]')
  assert.match(block, /class="math-block"/)
})

test('parentheses delimiters allow surrounding TeX whitespace and preserve it in rendered source', () => {
  for (const tex of [' x + y ', '\tx + y\t', '\n x + y \n']) {
    const out = html(`前文 \\(${tex}\\) 后文`)
    assert.match(out, /class="math-inline"/)
    assert.match(out, /class="katex"/)
    assert.ok(out.includes(`data-tex="${tex}"`))
    assert.ok(!out.includes('\\('))
  }
})

test('blank parentheses remain literal while dollar whitespace and currency protections stay active', () => {
  for (const source of ['\\(\\)', '\\(   \\)', '\\(\t\n \\)', '$ x + y$', '$x + y $', '$ x + y $', '价格 $5 与 $6']) {
    const out = html(source)
    assert.ok(!out.includes('math-inline'), source)
    assert.ok(!out.includes('class="katex"'), source)
  }
})

test('escaped \\$ stays a literal dollar', () => {
  const out = html('价格是 \\$5 不是公式')
  assert.match(out, /\$5/)
  assert.ok(!out.includes('math-inline'))
})

test('currency and spaced dollars are not math', () => {
  assert.ok(!html('这本书 $5 那本 $6').includes('math-inline'))
  assert.ok(!html('$ 两边有空格 $').includes('math-inline'))
  assert.ok(!html('空公式 $$ 跳过').includes('math-block'))
})

test('dollars inside code are untouched', () => {
  const fenced = html('```\n价格 $5 与 $6\n```')
  assert.match(fenced, /\$5 与 \$6/)
  assert.ok(!fenced.includes('math-inline'))
  const inlineCode = html('写 `$a+b$` 这样')
  assert.match(inlineCode, /<code>\$a\+b\$<\/code>/)
})

test('an unterminated $$ falls back to plain text', () => {
  const out = html('$$\n没有闭合')
  assert.ok(!out.includes('math-block'))
})

test('multirow block math stops at the closing delimiter', () => {
  const out = html('$$\na = 1\n$$\n后面这段是正文。')
  assert.match(out, /class="math-block"/)
  assert.match(out, /后面这段是正文。/)
})

test('renderMathToHtml never throws on broken TeX', () => {
  // throwOnError:false —— katex 把错误渲染成红色 katex-error，原文保留（用户能看出错在哪），不抛异常
  const out = renderMathToHtml('\\frac{1}{')
  assert.match(out, /katex-error/)
  assert.match(out, /\\frac\{1\}\{/)
  const bad = renderMathToHtml('\\unknowncommand{x}')
  assert.ok(bad.includes('katex') || bad.includes('math-error'))
})

test('display math keeps the TeX annotation so htmlToMd can round-trip', () => {
  const out = renderMathToHtml('E=mc^2', { display: true })
  assert.match(out, /annotation encoding="application\/x-tex"/)
  assert.match(out, /E=mc\^2/)
})

test('editor source shells keep TeX and delimiters without calling KaTeX', () => {
  const previous = katex.renderToString
  let renders = 0
  katex.renderToString = (...args) => { renders++; return previous(...args) }
  try {
    const editorMd = new MarkdownIt().use(mdMath, { renderKatex: false })
    const output = editorMd.render('先看 $x < y$ 与 \\(z^2\\)\n\n$$\n\\begin{aligned} a&=b\\\\ c&=d\\end{aligned}\n$$\n\n\\[ \\text{"A"} \\]')
    assert.equal(renders, 0)
    assert.match(output, /<span class="math-inline" data-tex="x &lt; y"><\/span>/)
    assert.match(output, /<span class="math-inline" data-tex="z\^2"><\/span>/)
    assert.match(output, /<div class="math-block" data-tex="\\begin\{aligned\} a&amp;=b\\\\ c&amp;=d\\end\{aligned\}"><\/div>/)
    assert.match(output, /data-tex="\\text\{&quot;A&quot;\}"/)
    assert.ok(!output.includes('katex'))
    // A separate default instance still provides fully rendered preview/export HTML.
    assert.match(new MarkdownIt().use(mdMath).render('$E=mc^2$'), /class="katex"/)
    assert.equal(renders, 1)
  } finally { katex.renderToString = previous }
})

test('source shells preserve normal source-line mapping, code and literal dollar rules', () => {
  const editorMd = new MarkdownIt({ html: true }).use(mdMath, { renderKatex: false }).use(mdDataLine)
  const output = editorMd.render('## 合成标题\n\n正文 $x^2$\n\n```text\n$a+b$\n```\n\n$$\ny = x^2\n$$\n\n价格 \\$5 与 $6\n\n<p>原生块</p>')
  assert.match(output, /<h2 data-line="0">/)
  assert.match(output, /<p data-line="2">/)
  assert.match(output, /<pre data-line="4"><code class="language-text">\$a\+b\$/)
  assert.match(output, /<p data-line="12">价格 \$5 与 \$6<\/p>/)
  assert.match(output, /<p data-line="14">原生块<\/p>/)
  assert.match(output, /<div class="math-block" data-tex="y = x\^2"><\/div>/)
  assert.equal((output.match(/class="math-inline"/g) || []).length, 1)
})

const attentionTable = String.raw`<table style="width: 960px"><colgroup><col style="width: 280px"><col style="width: 240px"><col style="width: 440px"></colgroup><tbody>
<tr><th>子式</th><th>形状</th><th>含义</th></tr>
<tr><td>$QK^\top$</td><td>$n\times n$</td><td>第 $(i,j)$ 项是位置 $i$ 的查询与位置 $j$ 的键的点积，<strong>未归一化的相关性分数</strong></td></tr>
<tr><td>$\operatorname{softmax}(\cdot)$</td><td>$n\times n$</td><td><strong>按行归一化</strong><br>每行和为 1，参见 <a href="https://example.com/attention?x=1&amp;y=2">合成说明</a></td></tr>
<tr><td>$\times V$</td><td>$n\times d_v$</td><td>第 $i$ 行是 $\sum_j a_{ij}v_j$，即 $V$ 各行按权重的加权和</td></tr>
<tr><td colspan="3">尺寸与换行需要保留</td></tr>
</tbody></table>`
const attentionTex = [
  String.raw`QK^\top`, String.raw`n\times n`, '(i,j)', 'i', 'j',
  String.raw`\operatorname{softmax}(\cdot)`, String.raw`n\times n`,
  String.raw`\times V`, String.raw`n\times d_v`, 'i', String.raw`\sum_j a_{ij}v_j`, 'V',
]
const parseHtml = source => domino.createWindow(source).document
const formulaTex = document => Array.from(document.querySelectorAll('.math-inline[data-tex], .math-block[data-tex]'), node => node.getAttribute('data-tex'))
function withHtmlDocument(operation) {
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

test('raw HTML attention tables render formulas while preserving widths, merged cells and inline HTML', () => withHtmlDocument(() => {
  const output = html(attentionTable)
  const document = parseHtml(output)
  assert.deepEqual(formulaTex(document), attentionTex)
  assert.equal(document.querySelectorAll('.math-inline > .katex').length, attentionTex.length)
  assert.equal(document.querySelector('table').getAttribute('style'), 'width: 960px')
  assert.equal(document.querySelector('col').getAttribute('style'), 'width: 280px')
  assert.equal(document.querySelector('td[colspan]').getAttribute('colspan'), '3')
  assert.equal(document.querySelectorAll('br').length, 1)
  assert.equal(document.querySelector('strong').textContent, '未归一化的相关性分数')
  assert.equal(document.querySelector('a').getAttribute('href'), 'https://example.com/attention?x=1&y=2')
  assert.ok(!document.querySelector('table').textContent.includes('$'))
}))

test('raw HTML table formulas use empty source shells when KaTeX rendering is disabled', () => withHtmlDocument(() => {
  const editorMd = new MarkdownIt({ html: true }).use(mdMath, { renderKatex: false })
  const document = parseHtml(editorMd.render(attentionTable))
  assert.deepEqual(formulaTex(document), attentionTex)
  assert.equal(document.querySelectorAll('.katex').length, 0)
  for (const node of Array.from(document.querySelectorAll('.math-inline'))) assert.equal(node.innerHTML, '')
}))

test('HTML text-node math supports all delimiters and decodes entities without changing neighboring text', () => withHtmlDocument(() => {
  const source = String.raw`<table><tr><td>条件 &lt; 界限 &amp; 比较：$x &lt; y$，\(z^2\)，<em>保留强调</em></td></tr><tr><td>$$\begin{aligned}a&amp;=b\\c&amp;=d\end{aligned}$$</td><td>\[ p &gt; 0 \]</td></tr></table>`
  const document = parseHtml(html(source))
  assert.deepEqual(formulaTex(document), [
    'x < y', 'z^2', String.raw`\begin{aligned}a&=b\\c&=d\end{aligned}`, 'p > 0',
  ])
  assert.equal(document.querySelectorAll('.math-inline').length, 2)
  assert.equal(document.querySelectorAll('.math-block').length, 2)
  assert.ok(document.querySelector('td').textContent.startsWith('条件 < 界限 & 比较：'))
  assert.equal(document.querySelector('em').textContent, '保留强调')
}))

test('HTML formulas leave code, escaped dollars, currency, attributes and existing math wrappers untouched', () => withHtmlDocument(() => {
  const inlineShell = '<span class="math-inline" data-tex="$kept$">$kept$</span>'
  const blockShell = '<div class="math-block" data-tex="kept">$alsoKept$</div>'
  const katexShell = '<span class="katex"><span>$rendered$</span></span>'
  const source = String.raw`<table><tr><td title="$attribute$"><code>$code$</code><pre>$pre$</pre><!-- $comment$ -->价格 $5 与 $6，转义 \$literal\$；${inlineShell}${blockShell}${katexShell} 正文 $x^2$</td></tr></table>`
  const output = html(source)
  const document = parseHtml(output)
  assert.ok(output.includes(inlineShell))
  assert.ok(output.includes(blockShell))
  assert.ok(output.includes(katexShell))
  assert.ok(output.includes('<!-- $comment$ -->'))
  assert.equal(document.querySelector('td').getAttribute('title'), '$attribute$')
  assert.equal(document.querySelector('code').textContent, '$code$')
  assert.equal(document.querySelector('pre').textContent, '$pre$')
  assert.match(document.querySelector('td').textContent, /价格 \$5 与 \$6/)
  assert.match(document.querySelector('td').textContent, /转义 \\?\$literal\\?\$/)
  assert.deepEqual(formulaTex(document), ['$kept$', 'kept', 'x^2'])
  assert.equal(document.querySelectorAll('.math-inline .math-inline, .math-block .math-inline, .katex .math-inline').length, 0)
}))

test('preview hydrates existing source shells once and keeps complete KaTeX wrappers intact', () => withHtmlDocument(() => {
  const source = '<table><tr><td><span class="math-inline" data-tex="x^2"></span></td><td><div class="math-block" data-tex="y = 1"></div></td></tr></table>'
  const first = html(source)
  const document = parseHtml(first)
  assert.deepEqual(formulaTex(document), ['x^2', 'y = 1'])
  assert.equal(document.querySelectorAll('.katex').length, 2)
  assert.equal(document.querySelectorAll('.katex-display').length, 1)
  const second = html(first)
  assert.equal(second, first, 'rendering a complete preview again does not nest shells or redraw math')
}))

test('display formulas in HTML paragraphs produce valid sibling blocks without empty paragraphs', () => withHtmlDocument(() => {
  const editorMd = new MarkdownIt({ html: true }).use(mdMath, { renderKatex: false })
  const standalone = parseHtml(editorMd.render('<table><tr><td><p>$$x^2$$</p></td></tr></table>'))
  assert.deepEqual(Array.from(standalone.querySelector('td').children, node => node.nodeName), ['DIV'])
  assert.deepEqual(formulaTex(standalone), ['x^2'])

  const source = String.raw`<table><tr><td><p><strong>前文</strong> \[ x^2 \] <em>后文</em></p></td></tr></table>`
  const output = editorMd.render(source)
  const document = parseHtml(output)
  assert.deepEqual(Array.from(document.querySelector('td').children, node => node.nodeName), ['P', 'DIV', 'P'])
  assert.equal(document.querySelector('strong').textContent, '前文')
  assert.equal(document.querySelector('em').textContent, '后文')
  assert.equal(document.querySelectorAll('p .math-block').length, 0)
  assert.equal(document.querySelectorAll('p:empty').length, 0)
  assert.deepEqual(formulaTex(document), ['x^2'])
  assert.equal(editorMd.render(output), output, 'reopening does not add browser-generated empty paragraphs')
}))

test('splitting a display formula inside nested marks preserves formatting on both sides', () => withHtmlDocument(() => {
  const editorMd = new MarkdownIt({ html: true }).use(mdMath, { renderKatex: false })
  const source = '<table><tr><td><p id="synthetic-block"><strong>前<em>左 $$x^2$$ 右</em>后</strong></p></td></tr></table>'
  const output = editorMd.render(source)
  const document = parseHtml(output)
  const [before, formula, after] = Array.from(document.querySelector('td').children)
  assert.deepEqual([before.nodeName, formula.nodeName, after.nodeName], ['P', 'DIV', 'P'])
  assert.equal(before.querySelector('strong').textContent, '前左 ')
  assert.equal(before.querySelector('strong em').textContent, '左 ')
  assert.equal(after.querySelector('strong').textContent, ' 右后')
  assert.equal(after.querySelector('strong em').textContent, ' 右')
  assert.equal(formula.getAttribute('data-tex'), 'x^2')
  assert.equal(document.querySelectorAll('#synthetic-block').length, 1)
  assert.equal(editorMd.render(output), output)
}))

test('blank lines in raw HTML tables retain both formula rows and source-line mapping', () => withHtmlDocument(() => {
  const editorMd = new MarkdownIt({ html: true }).use(mdMath, { renderKatex: false }).use(mdDataLine)
  const lines = [
    '合成前文', '', '<table>', '<tbody>', '<tr><td>$x^2$</td><td>第一行</td></tr>', '',
    '<tr><td>$y^2$</td><td>第二行</td></tr>', '', '</tbody>', '</table>', '', '合成后文',
  ]
  const source = lines.join('\n')
  const tableTokens = editorMd.parse(source, {}).filter(token => token.type === 'html_block')
  assert.equal(tableTokens.length, 1)
  assert.deepEqual(tableTokens[0].map, [2, 10])
  assert.equal(tableTokens[0].content, `${lines.slice(2, 10).join('\n')}\n`)
  const document = parseHtml(editorMd.render(source))
  assert.equal(document.querySelectorAll('table').length, 1)
  assert.equal(document.querySelectorAll('table tr').length, 2)
  assert.deepEqual(formulaTex(document), ['x^2', 'y^2'])
  assert.equal(document.querySelector('table').getAttribute('data-line'), '2')
  assert.equal(document.querySelector('p[data-line="0"]').textContent, '合成前文')
  assert.equal(document.querySelector('p[data-line="11"]').textContent, '合成后文')
}))

test('incomplete raw HTML containers and detached table rows are left intact', () => withHtmlDocument(() => {
  for (const fragment of [
    '<table><tbody><tr><td>$x^2$</td></tr>\n',
    '<section><p>$x^2$</p>\n',
    '<tr><td>$x^2$</td><td>相邻单元格</td></tr>\n',
    '<tbody><tr><td>$x^2$</td></tr></tbody>\n',
    '</table>\n<p>$x^2$</p>\n',
  ]) {
    assert.equal(renderMathInHtml(fragment), fragment)
    assert.equal(renderMathInHtml(fragment, { renderKatex: false }), fragment)
  }
}))

test('an unclosed HTML table does not crash or consume a following callout', () => withHtmlDocument(() => {
  const editorMd = new MarkdownIt({ html: true }).use(mdCallout).use(mdMath, { renderKatex: false })
  const fragment = '<table>\n<tr><td>$x^2$</td></tr>\n'
  const source = `${fragment}\n:::info\n合成提示 $y^2$。\n:::\n\n后续正文`
  const output = editorMd.render(source)
  assert.ok(output.startsWith(fragment), 'the incomplete table fragment stays intact')
  const document = parseHtml(output)
  assert.equal(document.querySelectorAll('.md-callout-info').length, 1)
  assert.match(document.querySelector('.md-callout-info').textContent, /合成提示/)
  assert.deepEqual(formulaTex(document), ['y^2'])
  assert.match(document.body.textContent, /后续正文/)
}))

test('blank lines around nested tables keep subsequent outer rows until the outer table closes', () => withHtmlDocument(() => {
  const editorMd = new MarkdownIt({ html: true }).use(mdMath, { renderKatex: false })
  const source = [
    '<table id="synthetic-outer"><tbody>', '<tr><td>$a^2$</td></tr>', '',
    '<tr><td><table id="synthetic-inner"><tbody><tr><td>$b^2$</td></tr></tbody></table></td></tr>', '',
    '<tr><td>$c^2$</td></tr>', '</tbody></table>',
  ].join('\n')
  const tokens = editorMd.parse(source, {}).filter(token => token.type === 'html_block')
  assert.equal(tokens.length, 1)
  assert.deepEqual(tokens[0].map, [0, 7])
  const document = parseHtml(editorMd.render(source))
  assert.equal(document.querySelectorAll('table').length, 2)
  const outer = document.querySelector('#synthetic-outer')
  const inner = document.querySelector('#synthetic-inner')
  assert.ok(outer.contains(inner))
  assert.equal(outer.firstElementChild.children.length, 3)
  assert.equal(inner.querySelectorAll('tr').length, 1)
  assert.deepEqual(formulaTex(document), ['a^2', 'b^2', 'c^2'])
  assert.equal(outer.lastElementChild.lastElementChild.querySelector('.math-inline').getAttribute('data-tex'), 'c^2')
}))

test('escaped double dollars in HTML stay literal alongside genuine math', () => withHtmlDocument(() => {
  const document = parseHtml(html(String.raw`<table><tr><td>\$\$literal\$\$，$x^2$</td></tr></table>`))
  assert.deepEqual(formulaTex(document), ['x^2'])
  assert.match(document.querySelector('td').textContent, /\\?\$\\?\$literal\\?\$\\?\$/)
}))

test('ordinary Markdown pipe tables keep rendering formulas exactly once', () => {
  const output = html(String.raw`| 子式 | 形状 |
| --- | --- |
| $QK^\top$ | $n\times n$ |
| $\times V$ | $n\times d_v$ |`)
  const document = parseHtml(output)
  assert.deepEqual(formulaTex(document), [String.raw`QK^\top`, String.raw`n\times n`, String.raw`\times V`, String.raw`n\times d_v`])
  assert.equal(document.querySelectorAll('tbody tr').length, 2)
  assert.equal(document.querySelectorAll('.katex').length, 4)
  assert.equal(document.querySelectorAll('.math-inline .math-inline').length, 0)
})
