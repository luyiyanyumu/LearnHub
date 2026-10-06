import test from 'node:test'
import assert from 'node:assert/strict'
import MarkdownIt from 'markdown-it'
import { mdMath, renderMathToHtml } from './mdMath.js'

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
