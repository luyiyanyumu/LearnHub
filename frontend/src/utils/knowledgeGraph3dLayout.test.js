import test from 'node:test'
import assert from 'node:assert/strict'
import { graph3dCommunityColor, layoutGraph3d } from './knowledgeGraph3dLayout.js'

test('3D neighborhood prioritizes the focused concept and preserves evidence edge styling', () => {
  const nodes = [{ id: 'tail' }, { id: 'root', title: '中心' }, { id: 'other' }]
  const edges = [
    { source: 'root', target: 'tail', origin: 'derived' },
    { source: 'tail', target: 'other', origin: 'llm' },
    { source: 'other', target: 'absent' },
  ]
  const options = { rootId: 'root', communities: { root: 0, tail: 0, other: 1 } }
  const first = layoutGraph3d(nodes, edges, options)
  assert.equal(first.nodes.length, 3)
  assert.deepEqual(first.nodes[0], {
    id: 'root', label: '中心', color: graph3dCommunityColor(0),
    x: 0, y: 0, z: 0, rank: 3, size: 11,
  })
  assert.notEqual(graph3dCommunityColor(0), graph3dCommunityColor(undefined))
  assert.deepEqual(first.edges.map(edge => edge.origin), ['derived', 'llm'])
  assert.deepEqual(layoutGraph3d([...nodes].reverse(), edges, options).nodes, first.nodes)
})

test('large community overview retains every id and separates the largest visible hubs', () => {
  const count = 3237
  const nodes = Array.from({ length: count }, (_, i) => ({
    id: `community:${i}`, communityId: i, title: `社区 ${i}`, count: count - i,
  }))
  const edges = [{ source: nodes[0].id, target: nodes[1].id, weight: 3, directCount: 2, derivedCount: 1 }]
  const layout = layoutGraph3d(nodes, edges, { mode: 'overview' })
  assert.equal(layout.nodes.length, count)
  assert.equal(new Set(layout.nodes.map(node => node.id)).size, count)
  assert.equal(layout.nodes[0].rank, 3)
  assert.ok(Math.hypot(layout.nodes[1].x, layout.nodes[1].y) > 25)
  assert.ok(layout.nodes.every(node => Number.isFinite(node.z)))
  assert.deepEqual(layout.edges[0], {
    source: nodes[0].id, target: nodes[1].id, weight: 3, origin: undefined, directCount: 2, derivedCount: 1,
  })
})
