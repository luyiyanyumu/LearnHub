import test from 'node:test'
import assert from 'node:assert/strict'
import { buildCommunityOverview, buildCommunityGalaxyMap, buildFullCommunityOverview,
  layoutCommunityGalaxyMap, createCommunityGalaxyIndex, UNASSIGNED_COMMUNITY_ID } from './knowledgeGraphCommunities.js'

const communities = count => Array.from({ length: count }, (_, index) => ({
  id: `community:${index}`, communityId: index, title: `合成社区 ${index}`, count: 1 + index % 43,
}))

test('the galaxy aggregates every community and observed link without changing the paginated API', () => {
  const nodes = Array.from({ length: 1200 }, (_, index) => ({ id: `concept-${index}`, label: `合成概念 ${index}` }))
  const membership = Object.fromEntries(nodes.map((node, index) => [node.id, index]))
  const edges = nodes.slice(1).map((node, index) => ({ source: nodes[index].id, target: node.id,
    origin: index % 2 ? 'derived' : 'llm' }))
  edges.push({ source: 'concept-1', target: 'concept-0', origin: 'derived' },
    { source: 'missing', target: 'concept-0' })
  const result = buildCommunityGalaxyMap(nodes, edges, membership)
  assert.equal(buildFullCommunityOverview, buildCommunityGalaxyMap)
  assert.equal(result.nodes.length, 1200)
  assert.equal(result.totalCommunities, 1200)
  assert.equal(result.totalNodes, 1200)
  assert.equal(result.totalEdges, 1200)
  assert.equal(result.totalCrossEdges, 1200)
  assert.equal(result.totalInternalEdges, 0)
  assert.equal(result.ignoredEdges, 1)
  assert.equal(result.page, 0)
  assert.equal(result.pageCount, 1)
  assert.equal(result.hiddenCommunityCount, 0)
  assert.equal(result.hiddenCrossEdges, 0)
  assert.equal(result.edges.length, 1199, 'only observed community pairs allocate links')
  assert.equal(result.edges.reduce((sum, edge) => sum + edge.count, 0), 1200)
  const pair = result.edges.find(edge => edge.count === 2)
  assert.equal(pair.directCount, 1)
  assert.equal(pair.derivedCount, 1)
  assert.equal(pair.forwardCount, 1)
  assert.equal(pair.backwardCount, 1)
  assert.equal(buildCommunityOverview(nodes, edges, membership, [], { maxCommunities: 5000 }).nodes.length, 96)
})

test('full aggregation keeps mixed community zero, internal edges, metadata and unassigned concepts truthful', () => {
  const nodes = [{ id: 'a' }, { id: 'b' }, { id: 'c' }, { id: 'd' }]
  const result = buildCommunityGalaxyMap(nodes, [
    { source: 'a', target: 'b', origin: 'derived' }, { source: 'a', target: 'c', origin: 'llm' },
  ], new Map([['a', 0], ['b', '0'], ['c', 5]]), [
    { communityId: 0, title: '合成零号社区' }, { communityId: 99, title: '过期资料' },
  ])
  assert.equal(result.nodes.length, 3)
  const zero = result.nodes.find(node => node.communityId === 0)
  assert.equal(zero.title, '合成零号社区')
  assert.equal(zero.count, 2)
  assert.equal(zero.internalEdgeCount, 1)
  assert.equal(zero.internalDerivedEdgeCount, 1)
  assert.ok(result.nodes.some(node => node.communityId === UNASSIGNED_COMMUNITY_ID))
  assert.equal(result.edges.length, 1)
  assert.equal(result.totalInternalEdges, 1)
  assert.equal(result.totalCrossEdges, 1)
  assert.equal(buildCommunityGalaxyMap().pageCount, 0)
})

test('a full galaxy remains deterministic and independent of viewport dimensions or input ordering', () => {
  const input = communities(125)
  const original = structuredClone(input)
  const first = layoutCommunityGalaxyMap(input)
  const reversed = layoutCommunityGalaxyMap([...input].reverse(), { width: 270, height: 700 })
  assert.deepEqual(first, reversed)
  assert.deepEqual(input, original, 'layout never annotates caller data')
  assert.equal(first.nodes.length, 125)
  assert.equal(first.spiralArms.length, 3)
  for (const arm of first.spiralArms) assert.equal(arm.points.length, 97)
  const largest = first.nodes[0]
  assert.equal(largest.arm, -1)
  assert.equal(largest.x, first.center.x)
  assert.equal(largest.y, first.center.y)
  assert.equal(largest.r, 12)
})

test('nuclei form three genuinely wide spiral bands with enough room to select neighbors', () => {
  const result = layoutCommunityGalaxyMap(communities(1200))
  assert.equal(result.minSeparation, 28)
  for (const node of result.nodes) {
    assert.ok(Number.isFinite(node.x) && Number.isFinite(node.y))
    assert.ok(node.r >= 6 && node.r <= 12)
    assert.ok(node.x - node.r >= 0 && node.x + node.r <= result.width)
    assert.ok(node.y - node.r >= 0 && node.y + node.r <= result.height)
    if (node.arm < 0) continue
    const angle = result.spiralRotation + node.arm * Math.PI * 2 / 3 + result.spiralTurn * node.progress
    const onArm = { x: result.center.x + Math.cos(angle) * result.spanX * node.progress,
      y: result.center.y + Math.sin(angle) * result.spanY * node.progress }
    assert.ok(Math.abs(Math.hypot(node.x - onArm.x, node.y - onArm.y) - Math.abs(node.armOffset)) < 1e-7)
    assert.ok(Math.abs(node.armOffset) <= result.bandWidth / 2)
  }
  for (let arm = 0; arm < 3; arm++) {
    const members = result.nodes.filter(node => node.arm === arm)
    assert.ok(members.length >= 399)
    assert.ok(members.some(node => node.armOffset < -10))
    assert.ok(members.some(node => node.armOffset > 10))
    assert.ok(Math.max(...members.map(node => node.progress)) > 0.95)
    assert.ok(Math.min(...members.map(node => node.progress)) < 0.2)
  }
  // Independent geometry check, rather than trusting the same buckets that
  // accepted these points during construction.
  for (let a = 0; a < result.nodes.length; a++) for (let b = a + 1; b < result.nodes.length; b++) {
    const first = result.nodes[a], second = result.nodes[b]
    assert.ok((first.x - second.x) ** 2 + (first.y - second.y) ** 2 >= 28 ** 2 - 1e-7)
  }
})

test('20,000 communities stay complete in one world and culling agrees with actual positions', () => {
  const input = communities(20000)
  const result = layoutCommunityGalaxyMap(input)
  assert.equal(result.nodes.length, input.length)
  assert.equal(new Set(result.nodes.map(node => node.id)).size, input.length)
  assert.ok(result.width > layoutCommunityGalaxyMap(communities(1200)).width)
  const index = createCommunityGalaxyIndex(result.nodes)
  assert.equal(index.size, input.length)
  assert.equal(index.query(result.bounds).length, input.length, 'overview queries cannot truncate members')
  for (let sample = 0; sample < 12; sample++) {
    const node = result.nodes[sample * 1499]
    assert.ok(node.x - node.r >= 0 && node.x + node.r <= result.width)
    assert.ok(node.y - node.r >= 0 && node.y + node.r <= result.height)
    assert.equal(index.nearest(node.x, node.y, 13), node)
    const bounds = { left: node.x - 150, right: node.x + 150, top: node.y - 80, bottom: node.y + 80 }
    const actual = index.query(bounds).map(point => point.id).sort()
    const expected = result.nodes.filter(point => point.x >= bounds.left && point.x <= bounds.right
      && point.y >= bounds.top && point.y <= bounds.bottom).map(point => point.id).sort()
    assert.deepEqual(actual, expected)
  }
})

test('spatial queries support negative positions, inclusive boundaries and deterministic nearest ties', () => {
  const left = { id: 0, x: -12, y: 0 }, right = { id: 'b', x: 12, y: 0 }
  const index = createCommunityGalaxyIndex([right, left, { id: 'invalid', x: NaN, y: 0 }], { cellSize: 10 })
  assert.equal(index.byId.get('0'), left)
  assert.equal(index.byId.get('b'), right)
  assert.deepEqual(new Set(index.query({ left: -12, right: 12, top: 0, bottom: 0 })), new Set([left, right]))
  assert.equal(index.nearest(0, 0, 12), left)
  assert.equal(index.nearest(0, 0, 11), null)
  assert.equal(index.nearest(-12, 0, 0), left)
  assert.deepEqual(index.query({ left: 10, right: -10, top: 0, bottom: 0 }), [])
  assert.deepEqual(index.query({ left: -Infinity, right: Infinity, top: 0, bottom: 0 }), [])
  assert.equal(index.nearest(0, 0, Infinity), null)
})

test('empty and small maps remain finite and clamp invalid geometry inputs', () => {
  for (const count of [0, 1, 2, 3]) {
    const result = layoutCommunityGalaxyMap(communities(count), {
      minSeparation: Infinity, aspect: NaN, rotation: Infinity, padding: -100,
    })
    assert.equal(result.nodes.length, count)
    assert.ok(Number.isFinite(result.width) && result.width > 0)
    assert.ok(Number.isFinite(result.height) && result.height > 0)
    assert.equal(result.minSeparation, 28)
    assert.ok(result.nodes.every(node => Number.isFinite(node.x) && Number.isFinite(node.y)))
  }
})
