import assert from 'node:assert/strict'
import { test } from 'node:test'
import MarkdownIt from 'markdown-it'
import mdDataLine from './mdDataLine.js'

test('paragraph, rule, heading, fenced code and HTML blocks retain source anchors', () => {
  const md = new MarkdownIt({ html: true }).use(mdDataLine)
  const html = md.render('Paragraph\n\n---\n\n## Heading\n\n```text\ncode\n```\n\n<table><tr><td>Cell</td></tr></table>')
  assert.match(html, /<p data-line="0">/)
  assert.match(html, /<hr data-line="2">/)
  assert.match(html, /<h2 data-line="4">/)
  assert.match(html, /<pre data-line="6">/)
  assert.match(html, /<table data-line="10">/)
})
