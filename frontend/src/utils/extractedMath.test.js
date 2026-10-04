import test from 'node:test'
import assert from 'node:assert/strict'
import domino from '@mixmark-io/domino'
import {
  exportExtractedText, extractedFormulaLatex, MAX_EXTRACTED_LATEX_CHARS,
  normalizeExtractedLatex, renderExtractedMath, renderExtractedText,
} from './extractedMath.js'

function parse(html) {
  const document = domino.createWindow().document
  const root = document.createElement('div')
  root.innerHTML = html
  return root
}

test('restored fractions, indexed roots, sub/superscripts and matrices produce MathML', () => {
  for (const latex of [String.raw`\frac{a+b}{\sqrt{x}}`, String.raw`\sqrt[3]{x_1^2}`, String.raw`\sum_{i=1}^{n} x_i`, String.raw`\begin{pmatrix}a&b\\c&d\end{pmatrix}`]) {
    const rendered = renderExtractedMath(latex)
    assert.equal(rendered.ok, true, latex)
    const root = parse(rendered.html)
    assert.equal(root.querySelector('annotation').textContent, latex)
    assert.equal(root.querySelector('math').getAttribute('display'), 'block')
  }
  assert.ok(parse(renderExtractedMath(String.raw`\frac{a}{\sqrt{x_1^2}}`).html).querySelector('mfrac msqrt msubsup'))
})

test('inline and display delimiters render while keeping surrounding prose and Markdown emphasis', () => {
  const source = String.raw`Use **energy** $E=mc^2$, \(x_1\), and $$\frac{1}{\sqrt{n}}$$.`
  const result = renderExtractedText(source)
  const root = parse(result.html)
  assert.equal(root.querySelector('strong').textContent, 'energy')
  assert.equal(root.querySelectorAll('math').length, 3)
  assert.equal(root.querySelectorAll('math[display="block"]').length, 1)
  assert.ok(root.textContent.startsWith('Use energy'))
  assert.ok(root.textContent.trim().endsWith('.'))
  assert.deepEqual(result.errors, [])
})

test('multiline display formulas support aligned environments without eating adjacent paragraphs', () => {
  const result = renderExtractedText(String.raw`Before.

$$
\begin{aligned}
y &= x^2 \\
z &= \sqrt{x}
\end{aligned}
$$

After.`)
  const root = parse(result.html)
  assert.equal(root.querySelectorAll('math').length, 1)
  assert.equal(root.querySelectorAll('p').length, 2)
  assert.ok(root.textContent.includes('Before.'))
  assert.ok(root.textContent.includes('After.'))
})

test('unclosed and invalid formulas preserve adjacent prose and code as readable text', () => {
  const source = 'Before $unfinished\n\nAfter paragraph.\n\n$$\n\\frac{x}{\n\nLast paragraph.\n\n```js\nconst formula = "$x^2$"\n```'
  const root = parse(renderExtractedText(source).html)
  assert.equal(root.querySelectorAll('math').length, 0)
  assert.ok(root.textContent.includes('Before $unfinished'))
  assert.ok(root.textContent.includes('After paragraph.'))
  assert.ok(root.textContent.includes('Last paragraph.'))
  assert.equal(root.querySelector('pre').textContent.trim(), 'const formula = "$x^2$"')
  const invalid = renderExtractedText(String.raw`Before $\frac{a}{$ after.`)
  assert.equal(parse(invalid.html).querySelectorAll('math').length, 0)
  assert.equal(parse(invalid.html).querySelector('.extracted-math-error').textContent, String.raw`\frac{a}{`)
  assert.equal(invalid.errors.length, 1)
})

test('dollar amounts, escaped dollars, fenced/indented and inline code never become formulas', () => {
  const source = 'Prices are $5 and $10, or $100.00 each, also $5元$ and $20USD$; literal \\$x$ stays literal.\n\n`$x^2$`\n\n```latex\n$$\\frac{x}{y}$$\n```\n\n    $y_1$'
  const root = parse(renderExtractedText(source).html)
  assert.equal(root.querySelectorAll('math').length, 0)
  assert.ok(root.textContent.includes('Prices are $5 and $10, or $100.00 each'))
  assert.ok(root.textContent.includes('literal $x$ stays literal'))
  assert.equal(root.querySelectorAll('pre').length, 2)
  assert.ok(root.querySelector('code').textContent.includes('$x^2$'))
})

test('table and heading inline rendering preserves cell whitespace and mathematical semantics', () => {
  const source = 'Cell A\tCell $x_1$\nrow 2\t\\(\\sqrt{x}\\)'
  const root = parse(renderExtractedText(source, { inline: true }).html)
  assert.equal(root.querySelectorAll('math').length, 2)
  assert.ok(root.textContent.includes('Cell A\tCell'))
  assert.ok(root.textContent.includes('row 2\t'))
  assert.equal(root.querySelectorAll('p, pre, table').length, 0)
})

test('uploaded HTML, image references, JavaScript links and trusted TeX commands cannot execute', () => {
  const source = String.raw`<img src=x onerror="alert(1)"><script>alert(1)</script>

![tracker](https://evil.test/pixel)

[click](javascript:alert(1))

$\href{javascript:alert(1)}{click}$

$\htmlStyle{background:url(https://evil.test/pixel)}{x}$`
  const result = renderExtractedText(source)
  const root = parse(result.html)
  assert.equal(root.querySelectorAll('img, script, iframe, a[href], [style]').length, 0)
  assert.ok(root.textContent.includes('<script>alert(1)</script>'))
  assert.equal(result.errors.length, 1)
  for (const latex of [String.raw`\href{https://evil.test}{x}`, String.raw`\includegraphics{https://evil.test/pixel}`, String.raw`\htmlClass{fake}{x}`, String.raw`\htmlData{onclick=alert(1)}{x}`]) {
    const rendered = renderExtractedMath(latex)
    assert.equal(rendered.ok, false)
    assert.equal(rendered.html, '')
  }
})

test('oversized, recursively expanding and unsupported formulas safely fall back', () => {
  assert.equal(renderExtractedMath('x'.repeat(MAX_EXTRACTED_LATEX_CHARS + 1)).ok, false)
  assert.equal(renderExtractedMath(String.raw`\def\foo{\foo}\foo`).ok, false)
  assert.equal(renderExtractedMath(String.raw`\unknowncommand{x}`).ok, false)
  const result = renderExtractedText(Array.from({ length: 105 }, () => '$x^2$').join(' '))
  assert.equal(parse(result.html).querySelectorAll('math').length, 100)
  assert.equal(parse(result.html).querySelectorAll('.extracted-math-error').length, 5)
})

test('formula delimiters normalize and export uses corrected LaTeX with inline/code sources intact', () => {
  assert.equal(normalizeExtractedLatex(String.raw`$$ \frac{x}{y} $$`), String.raw`\frac{x}{y}`)
  assert.equal(normalizeExtractedLatex(String.raw`\[x^2\]`), 'x^2')
  assert.equal(normalizeExtractedLatex(String.raw`\(x_1\)`), 'x_1')
  assert.equal(extractedFormulaLatex({ latex: 'x^2' }, ''), '')
  const pages = [{ page: 1, blocks: [
    { type: 'para', text: 'Inline $x_1$ formula.' },
    { type: 'formula', text: 'x 2 y 1', latex: String.raw`\frac{x^2}{y_1}`, latexStatus: 'restored' },
    { type: 'formula', text: 'unavailable fragments', latexStatus: 'unavailable' },
    { type: 'code', text: 'const raw = "$x$"' },
  ] }]
  const exported = exportExtractedText(pages, { '1:1': String.raw`\sqrt{x}` })
  assert.equal(exported, 'Inline $x_1$ formula.\n$$\n\\sqrt{x}\n$$\nunavailable fragments\nconst raw = "$x$"')
  assert.ok(!exported.includes('x 2 y 1'))
  assert.ok(exportExtractedText(pages).includes('$$\n\\frac{x^2}{y_1}\n$$'))
})
