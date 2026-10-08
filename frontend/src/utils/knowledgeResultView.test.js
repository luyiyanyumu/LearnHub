import test from 'node:test'
import assert from 'node:assert/strict'
import { highlightKnowledgeText, knowledgeResultRows, knowledgeSourceFilters, knowledgeSourceType } from './knowledgeResultView.js'

test('source filters preserve relevance order, original ranks and item identity', () => {
  const items = [{ type: 'wiki', id: 3, score: 0.9 }, { type: 'file', id: 2, score: 0.8 },
    { type: 'note', id: 1, score: 0.7 }, { sourceType: 'file', sourceId: 4, score: 0.6 }]
  const rows = knowledgeResultRows(items, 'file')
  assert.deepEqual(rows.map(row => row.rank), [2, 4])
  assert.equal(rows[0].item, items[1])
  assert.equal(rows[1].item, items[3])
  assert.deepEqual(knowledgeResultRows(items).map(row => row.item), items)
  assert.deepEqual(knowledgeResultRows(items, 'quick_ref'), [])
})

test('source counts normalize legacy references and surface unrecognized sources', () => {
  const items = [{ type: 'ref' }, { sourceType: 'quick_ref' }, { type: 'note' }, { type: 'graph' }]
  assert.deepEqual(knowledgeSourceFilters(items), [
    { key: 'all', label: '全部', count: 4 }, { key: 'note', label: '笔记', count: 1 },
    { key: 'quick_ref', label: '速查卡', count: 2 }, { key: 'other', label: '其他', count: 1 },
  ])
  assert.equal(knowledgeSourceType({ sourceType: 'file', type: 'note' }), 'file')
  assert.deepEqual(knowledgeSourceFilters(null), [{ key: 'all', label: '全部', count: 0 }])
})

test('duplicate passage keys still receive distinct rendering keys', () => {
  const rows = knowledgeResultRows([{ passageKey: 'file:1' }, { passageKey: 'file:1' }])
  assert.notEqual(rows[0].key, rows[1].key)
})

test('highlighting treats markup and query contents as inert text', () => {
  assert.equal(highlightKnowledgeText('<img src=x onerror="alert(1)"> ReAct', 'react'),
    '&lt;img src=x onerror=&quot;alert(1)&quot;&gt; <mark>ReAct</mark>')
  assert.equal(highlightKnowledgeText('<script>alert(1)</script>', '<script>'),
    '<mark>&lt;script&gt;</mark>alert(1)&lt;/script&gt;')
  assert.equal(highlightKnowledgeText('C++ a.b [x] $', 'a.b'), 'C++ <mark>a.b</mark> [x] $')
  assert.equal(highlightKnowledgeText('foo [x] foo', '[x]'), 'foo <mark>[x]</mark> foo')
})

test('highlighting matches original text rather than generated escape entities', () => {
  assert.equal(highlightKnowledgeText('a & b < c', '&'), 'a <mark>&amp;</mark> b &lt; c')
  assert.equal(highlightKnowledgeText('a & b', 'amp'), 'a &amp; b')
  assert.equal(highlightKnowledgeText('ReAct react REACT', ' ReAct '), '<mark>ReAct</mark> <mark>react</mark> <mark>REACT</mark>')
  assert.equal(highlightKnowledgeText(null, ''), '')
})
