import test from 'node:test'
import assert from 'node:assert/strict'
import { buildCommunityOverview, expandCommunityGraph, layoutCommunityGalaxy, UNASSIGNED_COMMUNITY_ID } from './knowledgeGraphCommunities.js'

const nodes = [
  { id: 'a', label: '示例代理', degree: 3 }, { id: 'b', label: '示例规划', degree: 5 },
  { id: 'c', label: '示例检索' }, { id: 'd', label: '示例证据' }, { id: 'e', label: '待归类概念' },
]
const communityOf = { a: 0, b: 0, c: 1, d: 2 }
const edges = [
  { source: 'a', target: 'b', origin: 'llm' },
  { source: 'a', target: 'c', origin: 'llm' },
  { source: 'c', target: 'b', origin: 'derived' },
  { source: 'c', target: 'd', origin: 'llm' },
  { source: 'e', target: 'a', origin: 'llm' },
  { source: 'missing', target: 'a' },
]

test('aggregation accounts for every real node and valid edge, including community zero and unassigned concepts', () => {
  const result = buildCommunityOverview(nodes, edges, communityOf)
  assert.equal(result.totalNodes, 5)
  assert.equal(result.totalCommunities, 4)
  assert.equal(result.totalEdges, 5)
  assert.equal(result.totalInternalEdges, 1)
  assert.equal(result.totalCrossEdges, 4)
  assert.equal(result.ignoredEdges, 1)
  const zero = result.communities.find(community => community.communityId === 0)
  assert.equal(zero.title, '示例规划 / 示例代理')
  assert.deepEqual(zero.nodeIds, ['a', 'b'])
  assert.equal(zero.internalEdgeCount, 1)
  assert.equal(zero.crossEdgeCount, 3)
  assert.equal(zero.directEdgeCount, 3)
  assert.equal(zero.derivedEdgeCount, 1)
  assert.equal(result.communities.find(community => community.unassigned).communityId, UNASSIGNED_COMMUNITY_ID)
  assert.equal(result.communities.find(community => community.unassigned).title, '未分组概念')
  assert.equal(result.edges.reduce((sum, edge) => sum + edge.weight, 0), 4)
  assert.equal(result.hiddenCrossEdges, 0)
})

test('one weighted undirected link preserves direct, derived and original direction counts', () => {
  const result = buildCommunityOverview(nodes, edges, communityOf)
  const link = result.edges.find(edge => edge.source === 'community:0' && edge.target === 'community:1')
  assert.equal(link.count, 2)
  assert.equal(link.weight, 2)
  assert.equal(link.directCount, 1)
  assert.equal(link.derivedCount, 1)
  assert.equal(link.forwardCount, 1)
  assert.equal(link.backwardCount, 1)
})

test('metadata can name communities but cannot resurrect stale members or empty communities', () => {
  const result = buildCommunityOverview(nodes.slice(0, 3), edges, communityOf, [
    { communityId: 0, title: '示例学习社区', nodeIds: ['a', 'b', 'missing'], size: 99 },
    { communityId: 20, title: '过期社区', nodeIds: ['missing'], size: 1 },
  ])
  assert.equal(result.communities.length, 2)
  assert.equal(result.communities[0].title, '示例学习社区')
  assert.equal(result.communities[0].count, 2)
  assert.deepEqual(result.communities[0].nodeIds, ['a', 'b'])
  assert.equal(result.totalEdges, 3)
})

test('stable page order, titles and coordinates do not depend on node, edge or metadata order', () => {
  const groups = [{ communityId: 1, title: '示例检索社区' }]
  const first = buildCommunityOverview(nodes, edges, communityOf, groups, { maxCommunities: 2 })
  const reversed = buildCommunityOverview([...nodes].reverse(), [...edges].reverse(), communityOf, [...groups].reverse(), { maxCommunities: 2 })
  assert.deepEqual(first, reversed)
  assert.deepEqual(layoutCommunityGalaxy(first.nodes), layoutCommunityGalaxy([...first.nodes].reverse()))
  assert.equal(nodes[0].id, 'a')
  assert.equal(nodes[0].x, undefined)
})

test('pagination reports omitted stars and omitted cross-community links without losing their accounting', () => {
  const first = buildCommunityOverview(nodes, edges, communityOf, [], { maxCommunities: 2, page: 0 })
  const second = buildCommunityOverview(nodes, edges, communityOf, [], { maxCommunities: 2, page: 1 })
  assert.equal(first.pageCount, 2)
  assert.equal(first.hiddenCommunityCount, 2)
  assert.equal(first.hiddenCrossEdges, 2)
  assert.equal(second.hiddenCrossEdges, 4)
  assert.deepEqual(new Set([...first.nodes, ...second.nodes].flatMap(community => community.nodeIds)), new Set(nodes.map(node => node.id)))
  assert.equal(buildCommunityOverview(nodes, edges, communityOf, [], { maxCommunities: 2, page: 999 }).page, 1)
  assert.equal(buildCommunityOverview(nodes, edges, communityOf, [], { maxCommunities: 2, page: -5 }).page, 0)
})

test('5,000 concepts and thousands of singleton communities keep complete membership behind a bounded overview', () => {
  let reads = 0
  const manyNodes = Array.from({ length: 5000 }, (_, index) => ({
    get id() { reads++; return `concept-${index}` }, label: `示例概念 ${index}`,
  }))
  const manyEdges = Array.from({ length: 4999 }, (_, index) => ({ source: `concept-${index}`, target: `concept-${index + 1}`, origin: index % 2 ? 'derived' : 'llm' }))
  const membershipMap = Object.fromEntries(Array.from({ length: 5000 }, (_, index) => [`concept-${index}`, index]))
  const result = buildCommunityOverview(manyNodes, manyEdges, membershipMap, [], { maxCommunities: 36 })
  assert.equal(result.nodes.length, 36)
  assert.equal(result.totalCommunities, 5000)
  assert.equal(result.totalNodes, 5000)
  assert.equal(result.totalEdges, 4999)
  assert.equal(result.totalCrossEdges, 4999)
  assert.equal(result.totalInternalEdges, 0)
  assert.equal(result.hiddenCommunityCount, 4964)
  assert.equal(result.communities.reduce((sum, community) => sum + community.count, 0), 5000)
  assert.equal(new Set(result.communities.flatMap(community => community.nodeIds)).size, 5000)
  assert.ok(reads < 5000 * 12, 'aggregation must index nodes instead of rescanning them for each community')
  assert.equal(result.hiddenCrossEdges + result.edges.reduce((sum, edge) => sum + edge.count, 0), 4999)
})

test('empty graphs, isolated concepts, Map membership and prototype-like IDs stay valid', () => {
  const empty = buildCommunityOverview([], [], {})
  assert.equal(empty.pageCount, 0)
  assert.equal(empty.page, 0)
  assert.deepEqual(empty.nodes, [])
  const isolated = buildCommunityOverview([{ id: 0 }, { id: '__proto__', label: '示例概念' }], [], new Map([[0, 0], ['__proto__', 'proto']]))
  assert.equal(isolated.totalCommunities, 2)
  assert.equal(isolated.totalNodes, 2)
  assert.ok(isolated.communities.some(community => community.communityId === 0))
  assert.equal(isolated.totalEdges, 0)
})

test('community expansion retains members and only their incident boundary relationships', () => {
  const additional = [...edges, { source: 'c', target: 'e', origin: 'derived' }]
  const expanded = expandCommunityGraph(nodes, additional, communityOf, 0)
  assert.deepEqual(expanded.memberIds, new Set(['a', 'b']))
  assert.deepEqual(expanded.boundaryIds, new Set(['c', 'e']))
  assert.deepEqual(expanded.nodes.map(node => node.id), ['a', 'b', 'c', 'e'])
  assert.equal(expanded.edges.length, 4)
  assert.ok(!expanded.edges.some(edge => edge.source === 'c' && edge.target === 'e'))
  assert.ok(!expanded.edges.some(edge => edge.source === 'missing'))
})

test('internal-only expansion and cross-only singleton expansion never drop actual members', () => {
  const internal = expandCommunityGraph(nodes, edges, communityOf, '0', { includeBoundary: false })
  assert.deepEqual(internal.nodes.map(node => node.id), ['a', 'b'])
  assert.equal(internal.edges.length, 1)
  const singleton = expandCommunityGraph(nodes, edges, communityOf, 2)
  assert.deepEqual(singleton.nodes.map(node => node.id), ['c', 'd'])
  assert.equal(singleton.memberIds.size, 1)
  assert.equal(singleton.boundaryIds.size, 1)
  assert.equal(singleton.edges.length, 1)
  const isolated = expandCommunityGraph([{ id: 'only' }], [], { only: 0 }, 0)
  assert.deepEqual(isolated.nodes.map(node => node.id), ['only'])
  assert.equal(isolated.edges.length, 0)
  assert.equal(expandCommunityGraph(nodes, edges, communityOf, 'unknown').nodes.length, 0)
})

test('unassigned community expansion handles absent, null and empty membership without matching community zero', () => {
  const mixed = { ...communityOf, b: null, c: '' }
  const expanded = expandCommunityGraph(nodes, edges, mixed, UNASSIGNED_COMMUNITY_ID)
  assert.deepEqual(expanded.memberIds, new Set(['b', 'c', 'e']))
  assert.ok(!expanded.memberIds.has('a'))
  assert.equal(expanded.nodes.length, 5)
})

function assertReadableLayout(communities, width, height, padding) {
  const layout = layoutCommunityGalaxy(communities, { width, height, padding })
  assert.equal(layout.nodes.length, communities.length)
  assert.equal(new Set(layout.nodes.map(node => node.id)).size, communities.length)
  for (const node of layout.nodes) {
    assert.ok([node.x, node.y, node.r, node.labelWidth, node.labelHeight].every(Number.isFinite))
    assert.ok(node.r > 0)
    assert.ok(node.x - node.labelWidth / 2 >= -1e-7)
    assert.ok(node.y - node.labelHeight / 2 >= -1e-7)
    assert.ok(node.x + node.labelWidth / 2 <= width + 1e-7)
    assert.ok(node.y + node.labelHeight / 2 <= height + 1e-7)
  }
  for (let a = 0; a < layout.nodes.length; a++) for (let b = a + 1; b < layout.nodes.length; b++) {
    const first = layout.nodes[a], second = layout.nodes[b]
    assert.ok(Math.abs(first.x - second.x) >= (first.labelWidth + second.labelWidth) / 2
      || Math.abs(first.y - second.y) >= (first.labelHeight + second.labelHeight) / 2,
    `the complete label boxes for ${first.id} and ${second.id} must not overlap`)
    assert.ok(Math.hypot(first.x - second.x, first.y - second.y) > first.r + second.r)
  }
  return layout
}

const sampleStars = count => Array.from({ length: count }, (_, index) => ({ id: `community:${index}`, count: count - index, title: `示例社区 ${index}` }))

test('galaxy overview fits 24 desktop community stars with separate readable label boxes', () => {
  const layout = assertReadableLayout(sampleStars(24), 1000, 640, 24)
  assert.equal(layout.labelWidth, 128)
  assert.equal(layout.labelHeight, 104)
  assert.equal(layout.scale, 1)
  assert.equal(layout.nodes[0].arm, -1)
  assert.ok(Math.abs(layout.nodes[0].x - 500) < 80)
  assert.ok(Math.abs(layout.nodes[0].y - 320) < 65)
  assert.deepEqual(new Set(layout.nodes.slice(1).map(node => node.arm)), new Set([0, 1, 2]))
})

test('narrow galaxy retains eight stars and 112px labels without horizontal overflow or overlap', () => {
  const layout = assertReadableLayout(sampleStars(8), 274, 660, 10)
  assert.equal(layout.labelWidth, 112)
  assert.equal(layout.labelHeight, 104)
  assert.equal(layout.scale, 1)
  assert.ok(layout.nodes[0].r >= layout.nodes.at(-1).r)
  assertReadableLayout(sampleStars(8), 375, 620, 10)
})

test('current responsive page sizes retain full text and complete keyboard focus clearance', () => {
  for (const [width, height, count] of [[1000, 520, 18], [667, 553, 12], [274, 660, 6], [217, 660, 4]]) {
    const layout = assertReadableLayout(sampleStars(count), width, height, width < 600 ? 10 : 24)
    assert.equal(layout.scale, 1)
    assert.equal(layout.labelWidth, width < 600 ? 112 : 128)
    assert.equal(layout.labelHeight, 104)
    assert.equal(layout.nodes[0].x, width / 2)
    assert.equal(layout.nodes[0].y, height / 2)
    for (let a = 0; a < layout.nodes.length; a++) for (let b = a + 1; b < layout.nodes.length; b++) {
      const first = layout.nodes[a], second = layout.nodes[b]
      assert.ok(Math.abs(first.x - second.x) >= layout.labelWidth + 13.9
        || Math.abs(first.y - second.y) >= layout.labelHeight + 13.9,
      `focus outlines must have space between ${first.id} and ${second.id} at ${width}px`)
    }
  }
})

test('desktop stars form loose winding arms instead of aligned rows and columns', () => {
  for (const [width, height] of [[1000, 520], [1000, 640], [1200, 640]]) {
    const layout = layoutCommunityGalaxy(sampleStars(18), { width, height, padding: 24 })
    for (const axis of ['x', 'y']) {
      const largestBand = Math.max(...layout.nodes.map(node => layout.nodes.filter(other => Math.abs(other[axis] - node[axis]) <= 6).length))
      assert.ok(largestBand <= 3, `a ${axis} band must not recreate a table-like alignment at ${width}×${height}`)
    }
    const spanX = (width - 48 - layout.labelWidth) / 2, spanY = (height - 48 - layout.labelHeight) / 2
    for (let arm = 0; arm < 3; arm++) {
      const members = layout.nodes.filter(node => node.arm === arm).sort((a, b) => a.rank - b.rank)
      const angles = members.map(node => Math.atan2((node.y - height / 2) / spanY, (node.x - width / 2) / spanX))
      for (let i = 1; i < angles.length; i++) {
        while (angles[i] - angles[i - 1] < -Math.PI) angles[i] += Math.PI * 2
        while (angles[i] - angles[i - 1] > Math.PI) angles[i] -= Math.PI * 2
      }
      assert.ok(angles.at(-1) - angles[0] >= 1.15, 'each arm must visibly wind around the core')
      const reverseLimit = height < 600 ? -0.85 : -0.6
      assert.ok(angles.slice(1).every((angle, index) => angle - angles[index] >= reverseLimit),
        'label separation may bend an arm locally, but must not fold it sharply backward')
      assert.ok(members.at(-1).progress > members[0].progress)
    }
  }
})

test('decorative spiral guides share the nucleus coordinate system and grow smoothly out of the core', () => {
  const layout = layoutCommunityGalaxy(sampleStars(18), { width: 1000, height: 640, padding: 24 })
  assert.equal(layout.spiralArms.length, 3)
  const spanX = (1000 - 48 - layout.labelWidth) / 2, spanY = (640 - 48 - layout.labelHeight) / 2
  for (const arm of layout.spiralArms) {
    assert.equal(arm.points.length, 37)
    assert.equal(arm.points[0].x, layout.nodes[0].x)
    assert.equal(arm.points[0].y, layout.nodes[0].y - 34)
    assert.match(arm.path, /^M[\d.]+ [\d.]+ L/)
    const radii = arm.points.map(point => Math.hypot((point.x - 500) / spanX, (point.y + 34 - 320) / spanY))
    for (let index = 1; index < arm.points.length; index++) {
      const previous = arm.points[index - 1], current = arm.points[index]
      assert.ok(Number.isFinite(current.x) && Number.isFinite(current.y))
      assert.ok(current.angle > previous.angle)
      assert.ok(current.progress > previous.progress)
      assert.ok(radii[index] > radii[index - 1])
      assert.ok(Math.hypot(current.x - previous.x, current.y - previous.y) < 30,
        'guide samples must follow a continuous arc rather than hop between rows')
    }
    assert.ok(arm.points.at(-1).angle - arm.points[0].angle > 2)
  }
})

test('different stable community identities cannot turn collision resolution back into aligned bands', () => {
  for (const seed of [0, 13, 37]) {
    const stars = Array.from({ length: 18 }, (_, index) => ({
      id: `community:${(seed * 947 + index * 757) % 9001}`, count: 18 - index,
    }))
    const layout = assertReadableLayout(stars, 1000, 520, 24)
    assert.equal(layout.scale, 1)
    for (const axis of ['x', 'y']) {
      assert.ok(layout.nodes.every(node => layout.nodes.filter(other => Math.abs(node[axis] - other[axis]) <= 6).length <= 3))
    }
    assert.deepEqual(layout, layoutCommunityGalaxy([...stars].reverse(), { width: 1000, height: 520, padding: 24 }))
  }
})

test('layout remains deterministic and finite on tiny, wide and crowded viewports', () => {
  for (const [width, height, count] of [[1, 1, 8], [180, 300, 24], [2000, 300, 24], [1000, 650, 96]]) {
    const stars = sampleStars(count)
    const first = assertReadableLayout(stars, width, height, 28)
    assert.deepEqual(first, layoutCommunityGalaxy([...stars].reverse(), { width, height, padding: 28 }))
  }
  const empty = layoutCommunityGalaxy([], { width: 375, height: 620 })
  assert.equal(empty.nodes.length, 0)
  assert.equal(empty.bounds.width, 0)
})
