import test from 'node:test'
import assert from 'node:assert/strict'
import {
  galaxyFitCamera, galaxyViewBounds, galaxyZoomAt, selectGalaxyDetail, createKnowledgeGalaxyRenderer,
} from './knowledgeGalaxyRenderer.js'

function close(actual, expected, message) {
  assert.ok(Math.abs(actual - expected) < 1e-8, `${message}: ${actual} ≈ ${expected}`)
}

function fixture(count = 20000) {
  return Array.from({ length: count }, (_, index) => ({
    id: `community:${index}`, communityId: index, title: `合成社区 ${index}`, label: `合成社区 ${index}`,
    count: 1 + index % 31, size: 1 + index % 31, degree: index % 9,
    x: 16 + index % 200 * 4.8, y: 16 + Math.floor(index / 200) * 5.8,
    r: 3 + index % 4, arm: index % 3, rank: index,
    internalEdgeCount: index % 13, crossEdgeCount: index % 7,
  }))
}

function mockCanvas() {
  const calls = [], attributes = {}, ctx = { calls, globalAlpha: 1 }
  for (const method of ['setTransform', 'clearRect', 'fillRect', 'save', 'restore', 'translate', 'scale', 'rotate',
    'setLineDash', 'beginPath', 'moveTo', 'lineTo', 'closePath', 'stroke', 'fill', 'arc', 'ellipse',
    'quadraticCurveTo', 'bezierCurveTo', 'fillText', 'strokeText', 'drawImage', 'roundRect', 'rect']) {
    ctx[method] = (...args) => { calls.push({ method, args, alpha: ctx.globalAlpha }) }
  }
  for (const method of ['createRadialGradient', 'createLinearGradient']) {
    ctx[method] = (...args) => { calls.push({ method, args }); return { addColorStop() {} } }
  }
  ctx.measureText = text => ({ width: String(text).length * 7 })
  const canvas = { width: 300, height: 150, style: {}, attributes, ctx,
    getContext: type => type === '2d' ? ctx : null,
    setAttribute(name, value) { attributes[name] = value },
    removeAttribute(name) { delete attributes[name] },
    ownerDocument: { createElement: () => mockCanvas() },
  }
  ctx.canvas = canvas
  return canvas
}

test('camera zoom preserves the world point under the pointer and clamps scale without mutating input', () => {
  const camera = Object.freeze({ x: 87, y: -41, k: .7 })
  const point = Object.freeze({ x: 233, y: 179 })
  const anchor = { x: (point.x - camera.x) / camera.k, y: (point.y - camera.y) / camera.k }
  for (const [factor, expectedScale] of [[1.4, .98], [.001, .2], [100, 4]]) {
    const next = galaxyZoomAt(camera, point, factor, .2, 4)
    close(next.k, expectedScale, 'scale respects its limits')
    close((point.x - next.x) / next.k, anchor.x, 'horizontal pointer anchor remains stable')
    close((point.y - next.y) / next.k, anchor.y, 'vertical pointer anchor remains stable')
    assert.notEqual(next, camera)
  }
  assert.deepEqual(camera, { x: 87, y: -41, k: .7 })
})

test('fit camera keeps the full world bounds visible in desktop and narrow viewports', () => {
  const layout = { nodes: [{ id: 'left', x: -2200, y: -900, r: 20 }, { id: 'right', x: 1900, y: 1300, r: 20 }],
    bounds: { left: -2220, right: 1920, top: -920, bottom: 1320, width: 4140, height: 2240 } }
  for (const [width, height] of [[1000, 640], [217, 420]]) {
    const camera = galaxyFitCamera(layout, width, height)
    assert.ok(Number.isFinite(camera.x) && Number.isFinite(camera.y) && camera.k > 0)
    for (const x of [layout.bounds.left, layout.bounds.right]) {
      assert.ok(camera.x + x * camera.k >= 0 && camera.x + x * camera.k <= width)
    }
    for (const y of [layout.bounds.top, layout.bounds.bottom]) {
      assert.ok(camera.y + y * camera.k >= 0 && camera.y + y * camera.k <= height)
    }
  }
})

test('viewport bounds invert the camera transform and include a screen-space margin', () => {
  const bounds = galaxyViewBounds({ x: 20, y: -10, k: 2 }, { width: 1000, height: 600 }, 12)
  close(bounds.left, -16, 'left margin')
  close(bounds.top, -1, 'top margin')
  close(bounds.right, 496, 'right margin')
  close(bounds.bottom, 311, 'bottom margin')
})

test('dense overview bounds painted detail and labels while retaining all 20,000 visible communities and focus', () => {
  const nodes = fixture(), camera = { x: 0, y: 0, k: 1 }, view = { width: 1000, height: 640, dpr: 1 }
  const focusId = nodes[12345].id
  const detail = selectGalaxyDetail(nodes, camera, view, focusId)
  assert.equal(detail.visibleCount, nodes.length)
  assert.equal(detail.aggregated, true)
  assert.ok(detail.painted.length > 0 && detail.painted.length <= 4000)
  assert.ok(detail.labelNodes.length <= 24)
  assert.ok(detail.painted.some(node => node.id === focusId), 'LOD keeps the focused community as a real painted node')
  assert.ok(detail.labelNodes.some(node => node.id === focusId), 'LOD keeps the focused name reachable')
  assert.equal(nodes.length, 20000)
})

test('settled detail retains individual communities and active gestures reserve names for the focused star', () => {
  const nodes = fixture(80), camera = { x: 0, y: 0, k: 1 }, view = { width: 1000, height: 640, dpr: 1 }
  const settled = selectGalaxyDetail(nodes, camera, view)
  assert.equal(settled.visibleCount, nodes.length)
  assert.equal(settled.aggregated, false)
  assert.deepEqual(settled.painted.map(node => node.id), nodes.map(node => node.id))
  assert.ok(settled.labelNodes.length > 0 && settled.labelNodes.length <= 24)
  const unfocused = selectGalaxyDetail(nodes, camera, view, '', { interactive: true })
  assert.equal(unfocused.labelNodes.length, 0)
  const focused = selectGalaxyDetail(nodes, camera, view, nodes[9].id, { interactive: true })
  assert.deepEqual(focused.labelNodes.map(node => node.id), [nodes[9].id])
  assert.equal(focused.visibleCount, nodes.length)
})

test('dense proxies account for every visible community exactly once without changing input objects', () => {
  const nodes = fixture().map(node => Object.freeze(node))
  const focusId = nodes[999].id
  const detail = selectGalaxyDetail(nodes, { x: 0, y: 0, k: 1 }, { width: 1000, height: 640 }, focusId)
  assert.equal(detail.painted.reduce((sum, node) => sum + (node.clusterCount || 1), 0), nodes.length)
  assert.equal(detail.painted.filter(node => node.id === focusId).length, 1)
  assert.ok(nodes.every(node => !Object.hasOwn(node, 'clusterCount')), 'proxy aggregation leaves actual graph records untouched')
})

test('canvas drawing clips with world bounds, limits density, and reuses decoration geometry on focus-only redraws', () => {
  const nodes = [...fixture(), { id: 'outside', communityId: 20000, title: '合成离屏社区', count: 10, x: 6000, y: 6000, r: 8 }]
  const queryCalls = [], byId = new Map(nodes.map(node => [node.id, node]))
  const index = { byId, query(bounds) {
    queryCalls.push(bounds)
    return nodes.filter(node => node.x >= bounds.left && node.x <= bounds.right && node.y >= bounds.top && node.y <= bounds.bottom)
  } }
  let armReads = 0
  const arms = Array.from({ length: 3 }, (_, id) => ({ id, points: Array.from({ length: 37 }, (_, i) => ({
    x: 500 + Math.cos(i / 36 * 3.2 + id * Math.PI * 2 / 3) * i * 10,
    y: 320 + Math.sin(i / 36 * 3.2 + id * Math.PI * 2 / 3) * i * 6,
  })) }))
  const layout = { nodes, width: 1000, height: 640, center: { x: 500, y: 320 },
    bounds: { left: 0, right: 1000, top: 0, bottom: 640 }, get spiralArms() { armReads++; return arms } }
  const canvas = mockCanvas(), flowCanvas = mockCanvas(), twinkleCanvases = [mockCanvas(), mockCanvas()]
  const renderer = createKnowledgeGalaxyRenderer({ canvas, flowCanvas, twinkleCanvases })
  const camera = { x: 0, y: 0, k: 1 }, view = { width: 1000, height: 640, dpr: 3 }
  const first = renderer.draw(layout, index, camera, view)
  assert.equal(first.visibleCount, 20000, 'offscreen communities remain in data but are excluded from drawing')
  assert.ok(first.paintedCount > 0 && first.paintedCount <= 4000)
  assert.ok(first.labelCount <= 24)
  assert.equal(first.aggregated, true)
  assert.ok(first.decorationCount > 0 && first.decorationCount <= 4000)
  assert.deepEqual(queryCalls[0], galaxyViewBounds(camera, view, 32))
  assert.equal(canvas.width, 2000, 'main canvas caps device pixel ratio at two')
  assert.equal(canvas.height, 1280)
  for (const layer of [flowCanvas, ...twinkleCanvases]) {
    assert.equal(layer.width, 1000, 'moving surfaces use one physical pixel per CSS pixel')
    assert.equal(layer.height, 640)
  }
  const overlayCounts = [flowCanvas, ...twinkleCanvases].map(layer => layer.ctx.calls.length)
  const mainCount = canvas.ctx.calls.length, initialArmReads = armReads
  const focused = renderer.draw(layout, index, camera, view, { focusId: nodes[12345].id, byId })
  assert.equal(focused.visibleCount, 20000)
  assert.equal(armReads, initialArmReads, 'focus changes do not regenerate static arm geometry')
  assert.ok(canvas.ctx.calls.length > mainCount, 'the interactive scene still redraws its selection')
  assert.deepEqual([flowCanvas, ...twinkleCanvases].map(layer => layer.ctx.calls.length), overlayCounts,
    'unchanged camera and layout reuse all three decoration surfaces')
  renderer.draw(layout, index, { ...camera, x: -750 }, view, { interactive: true })
  assert.ok(queryCalls.at(-1).left > queryCalls[0].left)
  assert.ok(flowCanvas.ctx.calls.length > overlayCounts[0], 'camera changes reproject decoration surfaces')
  assert.equal(armReads, initialArmReads, 'camera motion reuses the world-space dust geometry')
  renderer.destroy()
  for (const layer of [canvas, flowCanvas, ...twinkleCanvases]) assert.equal(layer.width * layer.height, 1)
  const callsAtDestroy = canvas.ctx.calls.length
  assert.equal(renderer.draw(layout, index, camera, view).paintedCount, 0)
  assert.equal(canvas.ctx.calls.length, callsAtDestroy, 'destroyed renderers cannot schedule more paint work')
})

test('high-fanout focus caps painted curves while preserving visible relationship counts and source adjacency', () => {
  const focus = { id: 'community:focus', communityId: 'focus', title: '合成枢纽', count: 1400, x: 500, y: 320, r: 12 }
  const neighbors = Array.from({ length: 1300 }, (_, index) => ({
    id: `community:neighbor-${index}`, communityId: index, title: `合成邻居 ${index}`, count: 3,
    x: 25 + index % 50 * 18, y: 25 + Math.floor(index / 50) * 22, r: 4,
  }))
  const margins = [[-16, 100], [1016, 100], [100, -16], [100, 656]].map(([x, y], index) => ({
    id: `community:margin-${index}`, communityId: 1500 + index, count: 1, x, y, r: 3,
  }))
  const outside = [[-17, 100], [1017, 100], [100, -17], [100, 657]].map(([x, y], index) => ({
    id: `community:outside-${index}`, communityId: 1600 + index, count: 1, x, y, r: 3,
  }))
  const unrelated = [{ id: 'community:unrelated-a', communityId: 'other-a', count: 2, x: 10, y: 500, r: 4 },
    { id: 'community:unrelated-b', communityId: 'other-b', count: 2, x: 990, y: 500, r: 4 }]
  const nodes = [focus, ...neighbors, ...margins, ...outside, ...unrelated], byId = new Map(nodes.map(node => [node.id, node]))
  const incident = Object.freeze([...outside, ...neighbors, ...margins].map((node, index) => Object.freeze(index % 2
    ? { id: `relation:${index}`, source: node.id, target: focus.id, count: 1 }
    : { id: `relation:${index}`, source: focus.id, target: node.id, count: 1 })))
  const otherEdges = Object.freeze([Object.freeze({ id: 'unrelated-relation', source: unrelated[0].id, target: unrelated[1].id, count: 1 })])
  const adjacency = new Map([[focus.id, incident], [unrelated[0].id, otherEdges]])
  const originalAdjacency = JSON.stringify([...adjacency])
  const index = { byId, query(bounds) { return nodes.filter(node => node.x >= bounds.left && node.x <= bounds.right
    && node.y >= bounds.top && node.y <= bounds.bottom) } }
  const layout = { nodes, width: 1000, height: 640, center: { x: 500, y: 320 }, spiralArms: [] }
  const canvas = mockCanvas(), renderer = createKnowledgeGalaxyRenderer({ canvas })
  const view = { width: 1000, height: 640, dpr: 1 }, camera = { x: 0, y: 0, k: 1 }
  try {
    const metrics = renderer.draw(layout, index, camera, view, { focusId: focus.id, adjacency, byId })
    const curves = canvas.ctx.calls.filter(call => call.method === 'quadraticCurveTo')
    assert.equal(metrics.visibleRelations, neighbors.length + margins.length,
      'all true incident relations within the inclusive 16px viewport margin remain counted')
    assert.equal(metrics.paintedRelations, 512)
    assert.equal(metrics.relationsLimited, true)
    assert.equal(curves.length, 512, 'curve work is bounded independently of the full hub degree')
    const trueEndpoints = new Set([...neighbors, ...margins].map(node => `${node.x}:${node.y}`))
    for (const curve of curves) assert.ok(trueEndpoints.has(`${curve.args[2]}:${curve.args[3]}`),
      'drawn curves only terminate at real visible neighbors of this focus')
    assert.equal(JSON.stringify([...adjacency]), originalAdjacency, 'painting cannot truncate or reorder the actual relationship data')

    const translated = { x: -900, y: 0, k: 1 }, beforePan = canvas.ctx.calls.length
    const expected = [...neighbors, ...margins, ...outside].filter(node => node.x + translated.x >= -16
      && node.x + translated.x <= view.width + 16 && node.y >= -16 && node.y <= view.height + 16)
    const panned = renderer.draw(layout, index, translated, view, { focusId: focus.id, adjacency, byId })
    const pannedCurves = canvas.ctx.calls.slice(beforePan).filter(call => call.method === 'quadraticCurveTo')
    assert.equal(panned.visibleRelations, expected.length, 'pan updates the actual visible incident-neighbor count')
    assert.equal(panned.paintedRelations, Math.min(512, expected.length))
    assert.equal(panned.relationsLimited, expected.length > 512)
    assert.equal(pannedCurves.length, Math.min(512, expected.length))
    for (const curve of pannedCurves) {
      assert.ok(expected.some(node => node.x + translated.x === curve.args[2] && node.y === curve.args[3]),
        'camera clipping does not introduce unrelated edges or stale world coordinates')
    }
    const beforeClear = canvas.ctx.calls.length
    const cleared = renderer.draw(layout, index, translated, view, { adjacency, byId })
    assert.equal(cleared.visibleRelations, 0)
    assert.equal(cleared.paintedRelations, 0)
    assert.equal(cleared.relationsLimited, false)
    assert.equal(canvas.ctx.calls.slice(beforeClear).filter(call => call.method === 'quadraticCurveTo').length, 0,
      'no focus draws no relationship curves')
    assert.equal(JSON.stringify([...adjacency]), originalAdjacency)
  } finally { renderer.destroy() }
})
