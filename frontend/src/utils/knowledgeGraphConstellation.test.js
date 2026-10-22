import test from 'node:test'
import assert from 'node:assert/strict'
import { layoutCommunityConstellation } from './knowledgeGraphCommunities.js'

const stars = count => Array.from({ length: count }, (_, index) => ({
  id: `community:${index}`, count: count - index, title: `示例社区 ${index}`,
}))

function verifyHitAreas(layout, originalCount) {
  assert.equal(layout.nodes.length, originalCount)
  assert.equal(new Set(layout.nodes.map(node => node.id)).size, originalCount)
  for (const node of layout.nodes) {
    assert.ok([node.x, node.y, node.r, node.progress, node.angle, node.labelWidth, node.labelHeight].every(Number.isFinite))
    assert.ok(node.r > 0)
    assert.ok(node.progress >= 0 && node.progress <= 1)
    assert.equal(node.labelWidth, layout.labelWidth)
    assert.equal(node.labelHeight, layout.labelHeight)
    assert.ok(node.x - node.labelWidth / 2 >= -1e-7)
    assert.ok(node.y - node.labelHeight / 2 >= -1e-7)
    assert.ok(node.x + node.labelWidth / 2 <= layout.width + 1e-7)
    assert.ok(node.y + node.labelHeight / 2 <= layout.height + 1e-7)
  }
  for (let a = 0; a < layout.nodes.length; a++) for (let b = a + 1; b < layout.nodes.length; b++) {
    const first = layout.nodes[a], second = layout.nodes[b]
    assert.ok(Math.abs(first.x - second.x) >= 56 * layout.scale - 1e-6
      || Math.abs(first.y - second.y) >= 56 * layout.scale - 1e-6,
    `48px hit areas and their focus clearance must not overlap: ${first.id}, ${second.id}`)
  }
}

test('responsive constellation pages keep complete 48px buttons and the largest community at the common center', () => {
  for (const [width, height, count] of [[1000, 520, 18], [667, 540, 12], [288, 660, 6], [218, 660, 4]]) {
    const layout = layoutCommunityConstellation(stars(count), { width, height, padding: width < 600 ? 10 : 24 })
    verifyHitAreas(layout, count)
    assert.equal(layout.scale, 1)
    assert.equal(layout.labelWidth, 48)
    assert.equal(layout.labelHeight, 48)
    assert.deepEqual(layout.center, { x: width / 2, y: height / 2 })
    assert.equal(layout.nodes[0].x, layout.center.x)
    assert.equal(layout.nodes[0].y, layout.center.y)
    assert.equal(layout.nodes[0].arm, -1)
    assert.ok(layout.nodes.slice(1).every(node => node.r < layout.nodes[0].r))
  }
})

test('nuclei remain exactly on continuous arms after collision separation, with increasing distance and winding', () => {
  for (const [width, height, count] of [[1000, 520, 18], [667, 540, 12], [288, 660, 6], [218, 660, 4]]) {
    const layout = layoutCommunityConstellation(stars(count), { width, height, padding: width < 600 ? 10 : 24 })
    const spanX = layout.spiralArms[0].points.at(-1).progress === 1
      ? (width - 2 * (width < 600 ? 10 : 24) - layout.labelWidth) / 2 : NaN
    const spanY = (height - 2 * (width < 600 ? 10 : 24) - layout.labelHeight) / 2
    for (const node of layout.nodes.slice(1)) {
      const expectedAngle = layout.spiralRotation + node.arm * Math.PI * 2 / 3 + layout.spiralTurn * node.progress
      assert.ok(Math.abs(node.angle - expectedAngle) < 1e-12)
      assert.ok(Math.abs(node.x - (layout.center.x + Math.cos(expectedAngle) * node.progress * spanX)) < 1e-8)
      assert.ok(Math.abs(node.y - (layout.center.y + Math.sin(expectedAngle) * node.progress * spanY)) < 1e-8)
    }
    for (let arm = 0; arm < 3; arm++) {
      const members = layout.nodes.filter(node => node.arm === arm).sort((a, b) => a.rank - b.rank)
      for (let index = 1; index < members.length; index++) {
        assert.ok(members[index].progress > members[index - 1].progress)
        assert.ok(members[index].angle > members[index - 1].angle)
      }
    }
  }
})

test('three dust guides share the exact nucleus center, direction and 4.35rad sweep, with 97 continuous samples each', () => {
  const layout = layoutCommunityConstellation(stars(18), { width: 1000, height: 520, padding: 24 })
  assert.equal(layout.spiralArms.length, 3)
  assert.equal(layout.spiralTurn, 4.35)
  for (const arm of layout.spiralArms) {
    assert.equal(arm.points.length, 97)
    assert.equal(arm.points[0].x, layout.nodes[0].x)
    assert.equal(arm.points[0].y, layout.nodes[0].y)
    assert.equal(arm.points[0].progress, 0)
    assert.equal(arm.points.at(-1).progress, 1)
    assert.ok(Math.abs(arm.points.at(-1).angle - arm.points[0].angle - 4.35) < 1e-12)
    assert.match(arm.path, /^M[\d.]+ [\d.]+ L/)
    for (let index = 1; index < arm.points.length; index++) {
      const previous = arm.points[index - 1], current = arm.points[index]
      assert.ok(current.progress > previous.progress)
      assert.ok(current.angle > previous.angle)
      assert.ok(Math.hypot(current.x - previous.x, current.y - previous.y) < 24)
      assert.ok(current.x >= 0 && current.x <= layout.width)
      assert.ok(current.y >= 0 && current.y <= layout.height)
    }
  }
  for (let index = 1; index < 97; index++) {
    const first = layout.spiralArms[0].points[index], second = layout.spiralArms[1].points[index]
    const third = layout.spiralArms[2].points[index]
    assert.ok(Math.abs(second.angle - first.angle - Math.PI * 2 / 3) < 1e-12)
    assert.ok(Math.abs(third.angle - second.angle - Math.PI * 2 / 3) < 1e-12)
  }
})

test('label length cannot move a nucleus and reversed input cannot change a constellation', () => {
  const original = stars(18)
  const layout = layoutCommunityConstellation(original)
  assert.deepEqual(layout, layoutCommunityConstellation([...original].reverse()))
  const longTitles = layoutCommunityConstellation(original.map(node => ({ ...node, title: '示例标题'.repeat(80) })))
  assert.deepEqual(layout.nodes.map(node => [node.id, node.x, node.y, node.progress]),
    longTitles.nodes.map(node => [node.id, node.x, node.y, node.progress]))
  assert.equal(original[0].x, undefined)
})

test('different community identities retain full-size buttons at all real responsive limits', () => {
  for (let seed = 0; seed < 16; seed++) {
    for (const [width, height, count] of [[1000, 520, 18], [667, 540, 12], [288, 660, 6], [218, 660, 4]]) {
      const varied = Array.from({ length: count }, (_, index) => ({
        id: `community:${(seed * 947 + index * 757) % 9001}`, count: count - index,
      }))
      const layout = layoutCommunityConstellation(varied, { width, height, padding: width < 600 ? 10 : 24 })
      verifyHitAreas(layout, count)
      assert.equal(layout.scale, 1)
    }
  }
})

test('empty, isolated and truly crowded constellations keep finite geometry and complete membership', () => {
  const empty = layoutCommunityConstellation([], { width: 218, height: 660 })
  assert.equal(empty.nodes.length, 0)
  assert.equal(empty.bounds.width, 0)
  const single = layoutCommunityConstellation(stars(1))
  verifyHitAreas(single, 1)
  assert.equal(single.scale, 1)
  for (const [width, height, count] of [[1, 1, 8], [180, 220, 72], [320, 360, 120]]) {
    const layout = layoutCommunityConstellation(stars(count), { width, height, padding: 10 })
    verifyHitAreas(layout, count)
    assert.ok(layout.scale > 0 && layout.scale < 1)
    assert.deepEqual(layout, layoutCommunityConstellation([...stars(count)].reverse(), { width, height, padding: 10 }))
  }
})
