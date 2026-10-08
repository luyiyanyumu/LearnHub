import test from 'node:test'
import assert from 'node:assert/strict'
import { expandGraphIds, filterConceptGraph, findGraphNodes, graphSource } from './knowledgeGraphView.js'

const nodes = [{ id: 'a', label: 'ReAct', aliases: ['推理行动'], degree: 2 }, { id: 'b', label: 'Agent', degree: 10 }, { id: 'c', label: 'LLM' }, { id: 'd', label: '孤立点' }]
const edges = [{ source: 'a', target: 'b', relation: 'is_a', origin: 'llm' }, { source: 'b', target: 'c', relation: 'used_for', origin: 'derived' }]
test('filters intersect community, origin, relation and focus without dropping an isolated focus seed', () => {
  assert.deepEqual(filterConceptGraph(nodes, edges, { origin: 'direct' }).nodes.map(n => n.id), ['a', 'b'])
  assert.equal(filterConceptGraph(nodes, edges, { relations: [] }).edges.length, 0)
  assert.deepEqual(filterConceptGraph(nodes, edges, { focus: new Set(['d']) }).nodes.map(n => n.id), ['d'])
  const selected = filterConceptGraph(nodes, edges, { community: 0, communities: { a: 0, b: 0, c: 1 }, showIsolated: true })
  assert.deepEqual(selected.nodes.map(n => n.id), ['a', 'b'])
  assert.equal(selected.edges.length, 1)
})
test('hop expansion follows incident edges once, including incoming connections', () => {
  assert.deepEqual([...expandGraphIds('c', edges, 1)], ['c', 'b'])
  assert.deepEqual([...expandGraphIds('a', edges, 2)], ['a', 'b', 'c'])
})
test('concept lookup uses aliases, ranks exact names first and keeps deterministic results', () => {
  assert.equal(findGraphNodes(nodes, '推理行动')[0].id, 'a')
  assert.deepEqual(findGraphNodes(nodes, 'agent').map(n => n.id), ['b'])
  assert.equal(findGraphNodes(nodes, '')[0].id, 'b')
})
test('only supported source references become internal navigation links', () => {
  assert.equal(graphSource('note-3').path, '/notes/3')
  assert.equal(graphSource('quick_ref:2').type, 'ref')
  assert.equal(graphSource('file-10').path, '/files?read=10')
  assert.equal(graphSource('笔记#5').path, '/notes/5')
  assert.equal(graphSource({ ref: '资料#4', title: '论文' }).type, 'file')
  assert.equal(graphSource('https://example.com'), null)
})
