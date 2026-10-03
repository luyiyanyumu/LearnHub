import test from 'node:test'
import assert from 'node:assert/strict'
import MarkdownIt from 'markdown-it'
import mdAnchor from './mdAnchor.js'

test('TOC anchors remain unique when an original heading already includes the duplicate suffix', () => {
  const md = new MarkdownIt().use(mdAnchor)
  const headings = md.parse('# foo\n\n## foo-1\n\n## foo\n\n## foo', {}).filter(t => t.type === 'heading_open')
  assert.deepEqual(headings.map(t => t.attrGet('id')), ['foo', 'foo-1', 'foo-2', 'foo-3'])
  assert.equal(md.parse('# foo', {})[0].attrGet('id'), 'foo')
})

test('formatting a single English letter renders properly and keeps visible text', () => {
  const html = new MarkdownIt({ html: true }).render('Py<strong>t</strong>hon\n\n`&`')
  assert.ok(html.includes('Py<strong>t</strong>hon'))
  assert.ok(html.includes('<code>&amp;</code>'))
})
