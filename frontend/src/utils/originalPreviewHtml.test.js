import test from 'node:test'
import assert from 'node:assert/strict'
import domino from '@mixmark-io/domino'
import { createOriginalPreviewLoader, renderOriginalPreview, sanitizeOriginalHtml } from './originalPreviewHtml.js'

const document = () => domino.createWindow().document
function parse(html) {
  const element = document().createElement('div')
  element.innerHTML = html
  return element
}
const png = 'data:image/png;base64,iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+jT1sAAAAASUVORK5CYII='

test('Word preview retains headings, tables, embedded raster images, safe run styles and notes', () => {
  const result = renderOriginalPreview({
    format: 'word', html: `<h1>原始文档</h1><p style="text-align:center;"><strong>Introduction</strong> <span style="font-size: 12pt; color: #0b7f72">text</span></p><table><tbody><tr><td colspan="2">Merged</td></tr></tbody></table><img src="${png}" alt="图片"><div class="word-equation">x²+y²</div><div class="word-notes">脚注</div>`,
    warnings: ['复杂分页以流式排版展示'],
  }, document())
  const root = parse(result.html)
  assert.equal(root.querySelector('h1').textContent, '原始文档')
  assert.equal(root.querySelector('td').getAttribute('colspan'), '2')
  assert.equal(root.querySelector('span').getAttribute('style'), 'font-size: 12pt; color: #0b7f72')
  assert.equal(root.querySelector('img').getAttribute('src'), png)
  assert.ok(root.querySelector('.word-equation'))
  assert.ok(root.querySelector('.word-notes'))
  assert.deepEqual(result.warnings, ['复杂分页以流式排版展示'])
})

test('uploaded HTML cannot execute scripts, load frames, post forms or apply resource-bearing CSS', () => {
  const result = sanitizeOriginalHtml(`<script>alert(1)</script><iframe src="https://evil.test/frame">hidden</iframe><form action="/api/delete"><input name="id"><button>submit</button></form><p onclick="alert(1)" id="safe" style="color: red; background-image:url(https://evil.test/pixel); position:fixed; z-index:99999">Text <span style="font-family:Arial; color:expression(alert(1))">safe</span></p><svg><script>alert(1)</script></svg><math><annotation-xml encoding="text/html"><img src=x onerror=alert(1)></annotation-xml></math>`, document())
  const root = parse(result.html)
  assert.equal(root.querySelectorAll('script, iframe, form, input, button, svg, annotation-xml').length, 0)
  assert.equal(root.querySelector('p').getAttribute('onclick'), null)
  assert.equal(root.querySelector('p').getAttribute('style'), 'color: red')
  assert.equal(root.querySelector('span').getAttribute('style'), 'font-family: Arial')
  assert.equal(root.querySelector('p').id, 'original-safe')
  assert.ok(result.warnings.some(w => w.includes('不安全')))
})

test('external, protocol-relative, local, relative and MIME-spoofed images never retain a request URL', () => {
  const sources = ['https://tracker.test/pixel', '//tracker.test/x', '/api/files/1/raw', '../image.png', 'file:///C:/secret.png', 'data:image/svg+xml;base64,PHN2Zz4=', 'data:image/png;base64,' + btoa('<svg onload="alert(1)"></svg>')]
  const result = sanitizeOriginalHtml(sources.map(src => `<img src="${src}" onerror="alert(1)" alt="safe">`).join(''), document())
  assert.equal(parse(result.html).querySelectorAll('img').length, 0)
  assert.equal(parse(result.html).querySelectorAll('.original-image-blocked').length, sources.length)
  assert.ok(result.warnings[0].includes(`${sources.length} 张图片`))
})

test('links are limited to explicit HTTP(S) and scoped document anchors', () => {
  const result = sanitizeOriginalHtml('<h2 id="标题">标题</h2><a href="#%E6%A0%87%E9%A2%98">目录</a><a href="https://example.com/document">来源</a><a href="javascript:alert(1)">bad</a><a href="&#x6a;avascript:alert(1)">encoded bad</a><a href="file:///C:/note.docx">local</a><a href="//tracker.test/x">relative</a><a href="https://name:password@evil.test/">credentials</a>', document())
  const root = parse(result.html)
  const links = Array.from(root.querySelectorAll('a'))
  assert.equal(links[0].getAttribute('href'), '#original-标题')
  assert.equal(root.querySelector('h2').id, 'original-标题')
  assert.equal(links[1].getAttribute('rel'), 'noopener noreferrer')
  assert.equal(links[1].getAttribute('target'), '_blank')
  assert.ok(links.slice(2).every(link => !link.hasAttribute('href')))
})

test('Markdown retains code, tables, heading anchors and rendered inline/block math without executing HTML', () => {
  const source = '# Reading\n\n[目录](#reading)\n\n| A | B |\n|---|---|\n| one | two |\n\nFormula $x^2 + \\frac{1}{2}$ and \\(a+b\\).\n\n$$\nE=mc^2\n$$\n\n\\[\n\\sum_{i=1}^{n}i\n\\]\n\n```javascript\nconst price = "$x$";\n```\n\n`$inline code$` costs $5 and $10.\n\n<script>alert(1)</script>\n\n![remote](https://tracker.test/pixel)'
  const result = renderOriginalPreview({ format: 'markdown', source }, document())
  const root = parse(result.html)
  assert.equal(root.querySelector('h1').id, 'original-reading')
  assert.equal(root.querySelector('a').getAttribute('href'), '#original-reading')
  assert.equal(root.querySelectorAll('td').length, 2)
  assert.equal(root.querySelectorAll('math').length, 4)
  assert.equal(root.querySelector('annotation').textContent, 'x^2 + \\frac{1}{2}')
  assert.ok(root.querySelector('pre').textContent.includes('"$x$"'))
  assert.ok(root.textContent.includes('$inline code$'))
  assert.ok(root.textContent.includes('costs $5 and $10'))
  assert.equal(root.querySelectorAll('script, img').length, 0)
})

test('invalid and untrusted math retain readable source without remote resources', () => {
  const result = renderOriginalPreview({ format: 'markdown', source: '$\\invalidcommand{x}$\n\n$$\\includegraphics{https://evil.test/pixel}$$\n\n$\\href{javascript:alert(1)}{go}$' }, document())
  const root = parse(result.html)
  assert.ok(root.querySelector('.original-math-error'))
  assert.equal(root.querySelectorAll('img, a[href], script').length, 0)
  assert.ok(root.textContent.includes('invalidcommand'))
})

test('unsupported and empty originals display an explicit state instead of an empty document', () => {
  assert.throws(() => renderOriginalPreview({ format: 'zip', html: 'ignored' }, document()), /不支持原文预览/)
  assert.equal(renderOriginalPreview({ format: 'word', html: '<p><br></p>' }, document()).hasContent, false)
  assert.equal(renderOriginalPreview({ format: 'markdown', source: '  ' }, document()).hasContent, false)
  assert.equal(renderOriginalPreview({ format: 'word', html: `<img src="${png}">` }, document()).hasContent, true)
})

function deferred() {
  let resolve, reject
  const promise = new Promise((ok, fail) => { resolve = ok; reject = fail })
  return { promise, resolve, reject }
}

test('switching files ignores both stale success and stale failure after the newest preview loads', async () => {
  const requests = [deferred(), deferred(), deferred()]
  const states = []
  const loader = createOriginalPreviewLoader({ load: id => requests[id].promise, render: payload => renderOriginalPreview(payload, document()), onState: state => states.push(state) })
  const olderSuccess = loader.open(0)
  const olderFailure = loader.open(1)
  const latest = loader.open(2)
  requests[2].resolve({ format: 'markdown', source: '# Latest' })
  await latest
  requests[0].resolve({ format: 'markdown', source: '# Obsolete' })
  requests[1].reject(new Error('old failure'))
  await Promise.all([olderSuccess, olderFailure])
  assert.equal(states.length, 4)
  assert.ok(states.at(-1).html.includes('Latest'))
  assert.equal(states.at(-1).error, '')
})

test('unmount invalidates a pending request and current errors allow a fresh retry', async () => {
  const request = deferred()
  const states = []
  let fail = true
  const loader = createOriginalPreviewLoader({ load: () => fail ? Promise.reject(new Error('Failed')) : request.promise, render: p => renderOriginalPreview(p, document()), onState: s => states.push(s) })
  await loader.open(1)
  assert.equal(states.at(-1).error, 'Failed')
  fail = false
  const retry = loader.open(1)
  assert.equal(states.at(-1).html, '')
  loader.dispose()
  request.resolve({ format: 'markdown', source: '# Finished' })
  await retry
  assert.equal(states.length, 3)
})
