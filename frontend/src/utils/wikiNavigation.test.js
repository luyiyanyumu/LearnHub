import test from 'node:test'
import assert from 'node:assert/strict'
import MarkdownIt from 'markdown-it'
import mdAnchor from './mdAnchor.js'
import { retrievalSourcePath } from './retrievalDisplay.js'
import { findWikiHeading, wikiHeadingId, wikiRouteTarget } from './wikiNavigation.js'

function heading(textContent, sectionKey) {
  return { textContent, getAttribute: key => key === 'data-wiki-section' ? sectionKey : null }
}

test('retrieved Wiki links resolve the exact topic and section after a route round trip', () => {
  const path = retrievalSourcePath({ type: 'wiki', topicKey: 'entity-12ab', sectionKey: 'section-4', heading: '机制 / 工具反馈' })
  const query = Object.fromEntries(new URL(path, 'https://learnhub.local').searchParams)
  assert.deepEqual(wikiRouteTarget(query), { topicKey: 'entity-12ab', sectionKey: 'section-4', heading: '机制 / 工具反馈' })
  assert.deepEqual(wikiRouteTarget({ tab: 'wiki', topic: 'cat-2' }), { topicKey: 'cat-2', sectionKey: '', heading: '' })
  for (const query of [{}, { tab: 'graph', topic: 'cat-2' }, { tab: 'wiki', topic: ['cat-2', 'cat-3'] },
    { tab: 'wiki', topic: '../notes/3' }, { tab: 'wiki', topic: 'cat-2?read=4' }]) assert.equal(wikiRouteTarget(query), null)
})

test('same-name headings navigate by their parser section index, with a readable path fallback', () => {
  const first = heading('说明', 'section-2'), second = heading('说明', 'section-4')
  const headings = [heading('概念 A', 'section-1'), first, heading('概念 B', 'section-3'), second]
  assert.equal(findWikiHeading(headings, { sectionKey: 'section-4', heading: '概念 B / 说明' }), second)
  assert.equal(findWikiHeading(headings, { sectionKey: 'section-3', heading: '概念 B' }), headings[2])
  assert.equal(findWikiHeading(headings, { heading: '概念 A > 说明' }), first)
  assert.equal(findWikiHeading(headings, { heading: '概念 A › 说明' }), first)
  assert.equal(findWikiHeading(headings, { heading: '不存在' }), null)
  assert.equal(findWikiHeading(headings, { sectionKey: 'section-0', heading: '' }), null)
})

test('changed heading order falls back to the named heading instead of locating a wrong current section', () => {
  const headings = [heading('新插入的小节', 'section-1'), heading('循环机制', 'section-2')]
  assert.equal(findWikiHeading(headings, { sectionKey: 'section-1', heading: 'ReAct / 循环机制' }), headings[1])
})

test('section metadata follows Markdown headings, excludes code and keeps existing duplicate anchors', () => {
  const md = new MarkdownIt().use(mdAnchor)
  let index = 0
  const renderHeading = md.renderer.rules.heading_open
  md.renderer.rules.heading_open = (tokens, i, options, env, renderer) => {
    const token = tokens[i]
    token.attrSet('id', wikiHeadingId({ index: ++index, currentToken: token, text: tokens[i + 1].content }))
    return renderHeading ? renderHeading(tokens, i, options, env, renderer) : renderer.renderToken(tokens, i, options)
  }
  const html = md.render('# 概念\n\n说明\n----\n\n```markdown\n## 代码中的标题\n```\n\n## 说明\n')
  assert.match(html, /id="概念" data-wiki-section="section-1"/)
  assert.match(html, /id="说明" data-wiki-section="section-2"/)
  assert.match(html, /id="说明-1" data-wiki-section="section-3"/)
  assert.equal(index, 3)
})
