import test from 'node:test'
import assert from 'node:assert/strict'
import domino from '@mixmark-io/domino'
import { extractedAssetUrl, extractedBlockAssetUrl, extractedImageDimensions, renderExtractedMarkdown, verifiedSourcePage } from './extractedMarkdown.js'

const hash = 'b'.repeat(64), revision = 'a'.repeat(64)
const artifact = { fileId: 41, sourceSha256: revision, assets: [{ name: `${hash}.png`, mediaType: 'image/png', sha256: hash, bytes: 512 }] }
function render(source, changes = {}) {
  const document = domino.createWindow().document
  const result = renderExtractedMarkdown(source, { ...artifact, ...changes }, document)
  const root = document.createElement('div')
  root.innerHTML = result.html
  return { ...result, root }
}

test('structured Markdown preserves headings, lists, tables, code and validated LaTeX', () => {
  const output = render('# 合成标题\n\n- 第一项\n- 第二项\n\n|公式|含义|\n|---|---|\n|$QK^\\top$|相关性|\n\n$$\n\\frac{a}{b}\n$$\n\n```js\nconst price = "$5"\n```')
  assert.equal(output.root.querySelector('h1').textContent, '合成标题')
  assert.equal(output.root.querySelectorAll('li').length, 2)
  assert.ok(output.root.querySelector('table math'))
  assert.ok(output.root.querySelector('mfrac'))
  assert.match(output.root.querySelector('pre code').textContent, /\$5/)
  assert.deepEqual(output.errors, [])
})

test('only current-file manifest PNGs receive version-bound request URLs', () => {
  const output = render(`![原图](assets/${hash}.png)\n\n![远程](https://example.invalid/track.png) ![本机](file:///private.png) ![任意相对](other.png) ![穿越](assets/../${hash}.png)`)
  assert.equal(output.root.querySelectorAll('img').length, 1)
  const image = output.root.querySelector('img')
  assert.equal(image.getAttribute('src'), `/api/files/41/extracted-assets/${hash}.png?sourceRevision=${revision}`)
  assert.equal(image.getAttribute('alt'), '原图')
  assert.ok(output.blockedImages >= 2)
  for (const changes of [{ sourceSha256: '' }, { fileId: '../42' }, { assets: [] }, { assets: null }, { assets: [{ ...artifact.assets[0], sha256: 'c'.repeat(64) }] }]) {
    assert.equal(extractedAssetUrl(`assets/${hash}.png`, { ...artifact, ...changes }), '')
  }
  assert.equal(extractedBlockAssetUrl({ src: `assets/${hash}.png` }, artifact), image.getAttribute('src'))
  assert.equal(extractedBlockAssetUrl({ markdown: `![原图](assets/${hash}.png)` }, artifact), image.getAttribute('src'))
})

test('raw HTML is text, unsafe links and TeX never become active resources', () => {
  const output = render('<script>alert(1)</script><img src="https://example.invalid/x" onerror="alert(1)">\n\n[相对](/private) [凭据](https://name:pass@example.invalid) [安全](https://example.invalid/page)\n\n$\\href{javascript:alert(1)}{x}$')
  assert.equal(output.root.querySelectorAll('script,img,iframe,svg').length, 0)
  assert.match(output.root.textContent, /<script>/)
  assert.equal(output.root.querySelectorAll('a').length, 1)
  assert.equal(output.root.querySelector('a').getAttribute('rel'), 'noopener noreferrer')
  assert.ok(output.errors.length)
  assert.equal(output.root.querySelectorAll('[onclick],[onerror],math a').length, 0)
})

test('merged HTML tables retain only bounded spans and validate formulas and manifest images inside cells', () => {
  const output = render(`<table style="background:url(https://example.invalid)" onclick="alert(1)"><tbody><tr><th colspan="2">合成注意力</th></tr><tr><td rowspan="2" data-secret="x">$QK^\\top$</td><td>![区域](assets/${hash}.png)</td></tr><tr><td colspan="0"><img src="https://example.invalid/x" onerror="alert(1)"><script>alert(1)</script>$$\\frac{a}{b}$$</td></tr></tbody></table>`)
  assert.equal(output.root.querySelector('th').getAttribute('colspan'), '2')
  assert.equal(output.root.querySelector('td').getAttribute('rowspan'), '2')
  assert.equal(output.root.querySelectorAll('[style],[onclick],[onerror],[data-secret],[colspan="0"],script').length, 0)
  assert.equal(output.root.querySelectorAll('img').length, 1)
  assert.equal(output.root.querySelectorAll('math').length, 2)
  assert.ok(output.root.querySelector('mfrac'))
  assert.ok(output.blockedImages > 0)
  assert.deepEqual(output.errors, [])
})

test('inline source PNGs retain bounded dimensions and position without accepting resource-bearing attributes', () => {
  const output = render(`前文 <img src="assets/${hash}.png" width="16" height="12" alt="小图" style="width:100vw" onerror="bad()"> 后文。\n\n<img src="assets/${hash}.png" width="360" height="180" alt="大图">\n\n![自然尺寸](assets/${hash}.png)`)
  const images = output.root.querySelectorAll('img')
  assert.equal(images.length, 3)
  assert.equal(images[0].getAttribute('width'), '16')
  assert.equal(images[0].getAttribute('height'), '12')
  assert.equal(images[0].parentNode.textContent, '前文  后文。')
  assert.equal(images[1].getAttribute('width'), '360')
  assert.equal(images[2].hasAttribute('width'), false)
  assert.equal(output.root.querySelectorAll('[style],[onerror]').length, 0)
  assert.deepEqual(extractedImageDimensions({ widthPx: 22.666666, heightPx: 12 }), { width: 22.667, height: 12 })
  for (const width of ['-1', '100%', '4097', 'NaN', 0, Infinity]) assert.equal(extractedImageDimensions({ width }).width, null)
})

test('soft lines stay soft while Markdown hard breaks and strict br/sup/sub work in text and cells', () => {
  const output = render('软换行\n仍在同段  \n硬换行<br>明确换行，x<sup onclick="bad()">2</sup>和a<sub>1</sub>。\n\n<table><tr><td>单元格\n软换行<br>硬换行<sup>3</sup></td></tr></table>')
  assert.equal(output.root.querySelectorAll('br').length, 3)
  assert.equal(output.root.querySelectorAll('sup').length, 2)
  assert.equal(output.root.querySelector('sub').textContent, '1')
  assert.equal(output.root.querySelectorAll('[onclick]').length, 0)
  assert.match(output.root.querySelector('p').textContent, /软换行\n仍在同段/)
  const escaped = render('&lt;img src="assets/' + hash + '.png"&gt;')
  assert.equal(escaped.root.querySelectorAll('img').length, 0)
})

test('mature footnotes retain labels, repeated-reference back links and body/table reading order', () => {
  const output = render('<table><tr><td>表格先引用[^fn-a]</td></tr></table>\n\n正文引用[^en-b]，再次引用[^fn-a]。\n\n[^fn-a]: 脚注第一段。\n\n    第二段含 $x_1$。\n\n[^en-b]: 尾注与[安全链接](https://example.invalid/)。')
  const notes = output.root.querySelectorAll('.footnote-item')
  assert.equal(notes.length, 2)
  assert.match(notes[0].textContent, /脚注第一段/)
  assert.match(notes[0].textContent, /第二段含/)
  assert.ok(notes[0].querySelector('math'))
  assert.equal(output.root.querySelectorAll('.footnote-ref').length, 3)
  for (const link of Array.from(output.root.querySelectorAll('.footnote-ref a,.footnote-backref'))) {
    const id = link.getAttribute('href').slice(1)
    assert.ok(/^lh-fn(?:ref)?-/.test(id))
    assert.ok(output.root.querySelector(`[id="${id}"]`), id)
  }
  assert.equal(output.root.querySelector('table .footnote-ref').textContent, '[1]')
})

test('only restricted bookmarks survive and physical-page actions require a matching source revision and actual page', () => {
  const source = '<a id="word-bookmark-Chapter_1" onclick="bad()"></a>\n\n[跳转](#word-bookmark-Chapter_1)\n\n<a id="source-page-2"></a>\n\n###### 印刷页码 iv · 原件第2页\n\n<a id="arbitrary-dom-id"></a>'
  const pages = [{ page: 2, coordinateUnit: 'pt', label: 'iv' }]
  const output = render(source, { pages, report: { totalPages: 3 } })
  assert.ok(output.root.querySelector('#word-bookmark-Chapter_1'))
  assert.ok(!output.root.querySelector('#arbitrary-dom-id'))
  assert.equal(output.root.querySelector('[data-source-page]').getAttribute('data-source-page'), '2')
  assert.match(output.root.querySelector('.structured-page-marker').textContent, /印刷页码 iv/)
  for (const changed of [{ sourceSha256: '' }, { pages: [] }, { pages: [{ page: 2, coordinateUnit: 'none' }] }, { pages, report: { totalPages: 1 } }]) {
    assert.equal(render(source, changed).root.querySelectorAll('[data-source-page]').length, 0)
  }
  assert.equal(verifiedSourcePage(2, { sourceSha256: revision, pages }), true)
})

test('nested tables, incomplete HTML and code fences preserve adjacent document content', () => {
  const nested = render('<table><tr><td>外层<table><tr><td>$x_1$</td></tr></table></td></tr></table>\n\n## 后续章节')
  assert.equal(nested.root.querySelectorAll('table').length, 2)
  assert.equal(nested.root.querySelector('h2').textContent, '后续章节')
  const incomplete = render('<table><tr><td>未闭合\n\n## 保留章节')
  assert.equal(incomplete.root.querySelectorAll('table').length, 0)
  assert.equal(incomplete.root.querySelector('h2').textContent, '保留章节')
  const code = render('```html\n<table><tr><td>$x$</td></tr></table>\n```')
  assert.equal(code.root.querySelectorAll('table,math').length, 0)
  assert.match(code.root.querySelector('code').textContent, /<table>/)
})

function reviewBlock(source, text, changes = {}) {
  return { id: 'review-1', page: 1, type: 'text', quality: 'low_confidence', indexed: false,
    markdown: text, markdownStart: source.indexOf(text), markdownEnd: source.indexOf(text) + text.length, ...changes }
}

test('review marks remain adjacent to exact selected-artifact blocks without splitting tables or math', () => {
  const table = '|合成值|说明|\n|---|---|\n|$x_1$|待核对文字|'
  const formula = `![公式原图](assets/${hash}.png)\n\n[公式待核对]`
  const source = `## 合成资料\n\n普通正文。\n\n${table}\n\n${formula}\n\n可靠结尾。`
  const output = render(source, { pages: [{ page: 1, blocks: [
    reviewBlock(source, table, { id: 'table-1', type: 'table' }),
    reviewBlock(source, formula, { id: 'formula-1', type: 'formula', quality: 'uncertain_formula', latexStatus: 'needs_review' }),
    reviewBlock(source, '可靠结尾。', { id: 'accepted', quality: 'accepted', indexed: true }),
  ] }] })
  assert.equal(output.reviewCount, 2)
  assert.deepEqual(output.reviewItems.map(item => item.blockId), ['table-1', 'formula-1'])
  assert.equal(output.root.querySelectorAll('table').length, 1)
  assert.ok(output.root.querySelector('table math'))
  assert.equal(output.root.querySelectorAll('img').length, 1)
  const buttons = output.root.querySelectorAll('.structured-review-button')
  assert.equal(buttons[0].getAttribute('data-review-block'), 'table-1')
  assert.equal(buttons[0].getAttribute('data-source-revision'), revision)
  assert.match(buttons[1].textContent, /待核对：公式转写/)
  assert.equal(buttons[0].parentNode.nextElementSibling.tagName, 'TABLE')
  assert.match(output.root.textContent, /可靠结尾/)
})

test('review marks reject stale ranges and unbound artifacts and do not label intentional exclusions', () => {
  const source = '合成前文。\n\n可靠正文。'
  for (const changes of [{ markdown: '不同版本' }, { markdownStart: -1 }, { markdownEnd: 999 },
    { quality: 'page_furniture' }, { quality: 'non_text' }, { quality: 'superseded_by_formula' }]) {
    assert.equal(render(source, { pages: [{ page: 1, blocks: [reviewBlock(source, '可靠正文。', changes)] }] }).reviewCount, 0)
  }
  assert.equal(render(source, { sourceSha256: '', pages: [{ page: 1, blocks: [reviewBlock(source, '可靠正文。')] }] }).reviewCount, 0)
})

test('hidden image scan review is marked at the source image and attributes stay escaped', () => {
  const source = `合成图片：![样例](assets/${hash}.png)`
  const imageBlock = reviewBlock(source, source, { id: 'image-1', type: 'image', quality: 'non_text', src: `assets/${hash}.png` })
  const marker = { id: 'scan-" onclick="bad()', page: 1, type: 'text', quality: 'local_scan_review',
    src: `assets/${hash}.png`, markdown: '', markdownStart: -1, markdownEnd: -1 }
  const output = render(source, { pages: [{ page: 1, blocks: [imageBlock, marker] }] })
  assert.equal(output.reviewCount, 1)
  assert.equal(output.root.querySelector('.structured-review-button').getAttribute('data-review-block'), marker.id)
  assert.equal(output.root.querySelectorAll('[onclick]').length, 0)
  assert.equal(output.root.querySelectorAll('img').length, 1)
  assert.equal(render(source, { pages: [{ page: 2, blocks: [{ ...marker, page: 2 }, imageBlock] }] }).reviewCount, 0)
})
