import test from 'node:test'
import assert from 'node:assert/strict'
import MarkdownIt from 'markdown-it'
import { createMarkdownLink, createNoteLink, extractNoteHeadings, findLinkedHeadingIndex, parseNoteLink, validateLinkHref } from './noteLinks.js'

test('chapter choices use visible Markdown and HTML heading text, six levels and duplicate ordinals', () => {
  const source = '# **简介** &amp; `代码`\n\n## 小结\n\n```md\n# 代码内伪标题\n```\n\n标题二\n------\n\n<h4 id="block-example" title="x > y">小结 <em>细节</em> &amp; 补充</h4>\n\n###### 小结\n\n<!-- <h2>注释内伪标题</h2> -->\n\n<pre><h1>HTML代码内伪标题</h1></pre>'
  const headings = extractNoteHeadings(source)
  assert.deepEqual(headings.map(({ text, level, occurrence }) => ({ text, level, occurrence })), [
    { text: '简介 & 代码', level: 1, occurrence: 1 },
    { text: '小结', level: 2, occurrence: 1 },
    { text: '标题二', level: 2, occurrence: 1 },
    { text: '小结 细节 & 补充', level: 4, occurrence: 1 },
    { text: '小结', level: 6, occurrence: 2 },
  ])
  assert.equal(headings[3].anchorId, 'block-example')
  assert.equal(headings[3].line, 11)
  const link = createNoteLink(17, headings.at(-1))
  assert.equal(findLinkedHeadingIndex(headings, parseNoteLink(link)), 4)
})

test('unrelated heading insertions preserve a section link and renamed/missing titles never choose another chapter', () => {
  const original = extractNoteHeadings('## First\n\n## 小结\n\n## Third\n\n## 小结')
  const oldLink = createNoteLink(22, original[3])
  const updated = extractNoteHeadings('# Newly inserted\n\n## First\n\n## 小结\n\n## Third\n\n## 小结')
  assert.equal(oldLink, createNoteLink(22, updated[4]))
  assert.equal(findLinkedHeadingIndex(updated, parseNoteLink(oldLink)), 4)
  assert.equal(findLinkedHeadingIndex(extractNoteHeadings('# Newly inserted\n\n## 小结\n\n## 新小结'), parseNoteLink(oldLink)), -1)
  assert.equal(findLinkedHeadingIndex(extractNoteHeadings('## first'), parseNoteLink(createNoteLink(22, 'First'))), -1)
})

test('chapter text with punctuation, percent, plus and whitespace round-trips without selectors', () => {
  const heading = { text: ' A [b] 100% + "C" / D? #E & F ', occurrence: 2 }
  const parsed = parseNoteLink(createNoteLink('19', heading))
  assert.equal(parsed.noteId, 19)
  assert.equal(parsed.heading, 'A [b] 100% + "C" / D? #E & F')
  assert.equal(parsed.occurrence, 2)
  assert.equal(findLinkedHeadingIndex([{ textContent: parsed.heading }, { textContent: parsed.heading }], parsed), 1)
  assert.equal(createNoteLink(0, heading), '')
  assert.equal(createNoteLink('19other', heading), '')
  assert.equal(createNoteLink(19, { text: 'A', occurrence: 0 }), '')
})

test('legacy heading/block anchors work and only same-origin note URLs are interpreted internally', () => {
  assert.deepEqual(parseNoteLink('/notes/19?folderId=2#block-example'), {
    noteId: 19, heading: null, occurrence: 1, hash: '#block-example', anchorId: 'block-example',
  })
  assert.equal(findLinkedHeadingIndex([{ id: 'other' }, { getAttribute: name => name === 'id' ? 'block-example' : null }], parseNoteLink('/notes/19#block-example')), 1)
  assert.equal(parseNoteLink('#block-example'), null)
  assert.equal(parseNoteLink('https://external.test/notes/19', 'http://localhost:8890'), null)
  assert.equal(parseNoteLink('http://localhost:8890/notes/19', 'http://localhost:8890').noteId, 19)
  for (const invalid of ['/notes/0', '/notes/19/edit', '/notes/9007199254740992', '/notes/19#section=', '/notes/19#section=A&occurrence=0', '/notes/19#section=A&section=B', '/notes/19#section=A&occurrence=1&occurrence=2', '/notes/19#section=%XX', '/notes/19#section=%E0%A4', '/notes/19#%XX']) {
    assert.equal(parseNoteLink(invalid), null, invalid)
  }
})

test('link validation allows useful links and denies executable/control/backslash targets', () => {
  for (const href of ['https://example.com/a?b=1&c=2#part', 'http://localhost:8890/notes/2', 'mailto:name@example.com?subject=Hi', '/notes/2#section=%E5%B0%8F%E7%BB%93&occurrence=1', '/knowledge?tab=wiki', './notes/2', '#block-example']) {
    assert.equal(validateLinkHref(href), true, href)
  }
  for (const href of ['', 'javascript:alert(1)', 'JaVaScRiPt:alert(1)', 'data:text/html,test', 'vbscript:foo', 'file:///etc/passwd', '//external.test/path', '\\external.test', '/\\external.test', 'java\nscript:alert(1)', 'https://exa mple.com', '\u0000https://example.com', 'mailto:']) {
    assert.equal(validateLinkHref(href), false, JSON.stringify(href))
  }
})

test('Markdown insertion escapes user-visible text while preserving literal URL parentheses', () => {
  const source = createMarkdownLink('[click] <img src=x onerror=alert(1)> **bold**', 'https://example.com/a_(b)?x=1&y=2')
  const html = new MarkdownIt({ html: true }).render(source)
  assert.doesNotMatch(html, /<img\b|<strong\b|onerror="/)
  assert.match(html, /&lt;img src=x onerror=alert\(1\)&gt;/)
  assert.match(html, /href="https:\/\/example.com\/a_\(b\)\?x=1&amp;y=2"/)
  assert.equal(createMarkdownLink('click', 'javascript:alert(1)'), '')
})
