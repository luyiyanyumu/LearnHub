import assert from 'node:assert/strict'
import test from 'node:test'
import { hasRichHtml, sanitizePastedHtml } from './pasteHtml.js'

/** 用例形态取自"从 Word / 网页复制"的真实剪贴板 HTML（都是实测见过的写法） */

test('sanitizePastedHtml：去掉 mso-* 声明，保留真正有意义的样式', () => {
  const html = '<span style="mso-bidi-font-size:10.5pt; color: rgb(51,51,51); mso-spacerun:yes">文字</span>'
  const { html: out, droppedMso } = sanitizePastedHtml(html)
  assert.equal(out, '<span style="color: rgb(51,51,51)">文字</span>')
  assert.equal(droppedMso, 2)
})

test('sanitizePastedHtml：style 全是 mso-* 时连属性一起去掉（不留空 style）', () => {
  const { html: out } = sanitizePastedHtml('<p style="mso-line-height-rule:exactly">x</p>')
  assert.equal(out, '<p>x</p>')
})

test('sanitizePastedHtml：删掉 Office 私有命名空间标签', () => {
  const html = '<p class=MsoNormal>第一段<o:p></o:p></p><w:sectPr><v:shape/></w:sectPr>'
  const { html: out } = sanitizePastedHtml(html)
  assert.equal(out, '<p class=MsoNormal>第一段</p>')
})

test('sanitizePastedHtml：超大内嵌图片按阈值丢弃并计数（不静默）', () => {
  const big = 'data:image/png;base64,' + 'A'.repeat(300_000)
  const small = 'data:image/png;base64,' + 'B'.repeat(100)
  const html = `<p>插图</p><img src="${big}"><img src="${small}">`
  const { html: out, droppedImages } = sanitizePastedHtml(html)
  assert.equal(droppedImages, 1)
  assert.ok(!out.includes('A'.repeat(1000)), '超大图片应当被整段删掉')
  assert.ok(out.includes(small), '小图应当保留（阈值内）')
})

test('sanitizePastedHtml：外链图片与普通标签一字不动', () => {
  const html = '<p>看这张</p><img src="https://example.com/a.png" alt="图"><table><tr><td>1</td></tr></table>'
  const { html: out, droppedImages, droppedMso } = sanitizePastedHtml(html)
  assert.equal(out, html)
  assert.equal(droppedImages, 0)
  assert.equal(droppedMso, 0)
})

test('sanitizePastedHtml：null / 空串不炸', () => {
  assert.equal(sanitizePastedHtml(null).html, '')
  assert.equal(sanitizePastedHtml(undefined).html, '')
  assert.deepEqual(sanitizePastedHtml(null).droppedImages, 0)
})

test('hasRichHtml：纯文本与"只包了一层壳"的 HTML 判为不是富文本', () => {
  assert.equal(hasRichHtml(''), false)
  assert.equal(hasRichHtml('就是一句话'), false)
  assert.equal(hasRichHtml('<meta charset="utf-8"><span>一行字</span>'), false)
  assert.equal(hasRichHtml('<html><head><meta charset="utf-8"></head><body><p>段落</p></body></html>'), false)
})

test('hasRichHtml：带表格/列表/标题/代码/链接的判为富文本', () => {
  assert.equal(hasRichHtml('<table><tr><td>1</td></tr></table>'), true)
  assert.equal(hasRichHtml('<h2>标题</h2>'), true)
  assert.equal(hasRichHtml('<ul><li>项</li></ul>'), true)
  assert.equal(hasRichHtml('<pre><code>x=1</code></pre>'), true)
  assert.equal(hasRichHtml('<p>看 <a href="https://example.com">链接</a></p>'), true)
  assert.equal(hasRichHtml('<p><strong>加粗</strong>与<em>斜体</em></p>'), true)
})
