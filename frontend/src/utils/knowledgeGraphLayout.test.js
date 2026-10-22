import assert from 'node:assert/strict'
import test from 'node:test'
import { GRAPH_SIMULATION_NODE_LIMIT, createGraphLayout, fitGraphBounds, placeGraphLabels, reconcileGraphLayout, stepGraphLayout } from './knowledgeGraphLayout.js'

const nodes = ['agent', 'rag', 'graph', 'java', 'jvm', 'island'].map((id) => ({ id, label: id, type: 'concept', r: 9 }))
const edges = [{ source: 'agent', target: 'rag' }, { source: 'rag', target: 'graph' }, { source: 'java', target: 'jvm' }]

test('reloading or reordering the same graph preserves deterministic cluster positions', () => {
  const communities = { agent: 1, rag: 1, graph: 2, java: 3, jvm: 3 }
  const first = createGraphLayout(nodes, edges, communities)
  const second = createGraphLayout([...nodes].reverse(), [...edges].reverse(), communities)
  assert.deepEqual([...first.positions], [...second.positions])
  assert.deepEqual([...first.groupOf], [...second.groupOf])
})

test('disconnected islands have separate anchors and do not get dragged into the central component', () => {
  const layout = createGraphLayout(nodes, edges)
  assert.equal(layout.groups.length, 3)
  assert.equal(layout.groupOf.get('agent'), layout.groupOf.get('graph'))
  assert.notEqual(layout.groupOf.get('agent'), layout.groupOf.get('java'))
  const states = layout.positions
  for (let tick = 0; tick < 150; tick++) stepGraphLayout(nodes, edges, states, layout, { alpha: 0.97 ** tick })
  const center = ['agent', 'rag', 'graph'].map((id) => states.get(id)).reduce((sum, p) => ({ x: sum.x + p.x / 3, y: sum.y + p.y / 3 }), { x: 0, y: 0 })
  assert.ok(Math.hypot(states.get('island').x - center.x, states.get('island').y - center.y) > 100)
  for (const state of states.values()) assert.ok(Number.isFinite(state.x) && Number.isFinite(state.y))
})

test('world coordinates can exceed a small viewport while fitting keeps all nodes visible', () => {
  const largeNodes = Array.from({ length: 90 }, (_, index) => ({ id: `n${index}`, r: 12 }))
  const layout = createGraphLayout(largeNodes)
  for (let tick = 0; tick < 90; tick++) stepGraphLayout(largeNodes, [], layout.positions, layout, { alpha: 0.97 ** tick })
  const rendered = largeNodes.map((n) => ({ ...n, ...layout.positions.get(n.id) }))
  assert.ok(rendered.some((n) => Math.abs(n.x) > 240 || Math.abs(n.y) > 320))
  const view = { w: 240, h: 320, ...fitGraphBounds(rendered, 240, 320, { padding: 24 }) }
  for (const n of rendered) {
    assert.ok(view.x + (n.x - n.r) * view.k >= 23)
    assert.ok(view.x + (n.x + n.r) * view.k <= 217)
    assert.ok(view.y + (n.y - n.r) * view.k >= 23)
    assert.ok(view.y + (n.y + n.r) * view.k <= 297)
  }
})

test('coincident nodes separate deterministically, dangling edges are ignored, and dragged nodes stay pinned', () => {
  const layout = createGraphLayout(nodes, edges)
  const states = new Map(nodes.map((n) => [n.id, { x: 0, y: 0, vx: 0, vy: 0 }]))
  for (let tick = 0; tick < 40; tick++) stepGraphLayout(nodes, [...edges, { source: 'agent', target: 'missing' }], states, layout, { pinnedId: 'agent' })
  assert.deepEqual(states.get('agent'), { x: 0, y: 0, vx: 0, vy: 0 })
  assert.ok(Math.hypot(states.get('rag').x, states.get('rag').y) > 10)
  for (const state of states.values()) assert.ok(Number.isFinite(state.x) && Number.isFinite(state.y))
})

test('entering a neighborhood discards collinear full-graph coordinates and velocities, independent of navigation history', () => {
  const neighbors = Array.from({ length: 14 }, (_, i) => ({ id: `n${i}`, label: `概念 ${i}`, type: 'concept', degree: 1, r: 9 }))
  const star = [{ id: 'root', label: 'ReAct', type: 'concept', degree: 14, r: 13 }, ...neighbors]
  const links = neighbors.map(n => ({ source: 'root', target: n.id }))
  const states = new Map(), full = reconcileGraphLayout(star, links, {}, states)
  for (const [i, p] of [...states.values()].entries()) Object.assign(p, { x: 1500 + i, y: 900 + i * 80, vx: 9, vy: -7 })
  const options = { rootId: 'root', aspectRatio: 1.8 }
  const focused = reconcileGraphLayout(star, links, {}, states, full, options)
  const direct = createGraphLayout(star, links, {}, options)
  assert.deepEqual(states, direct.positions)
  const before = structuredClone(states)
  for (let tick = 0; tick < 150; tick++) stepGraphLayout(star, links, states, focused, { alpha: 0.97 ** tick })
  assert.deepEqual(states, before)
  const rendered = star.map(n => ({ ...n, ...states.get(n.id) }))
  const view = { w: 800, h: 500, ...fitGraphBounds(rendered, 800, 500) }
  const width = Math.max(...rendered.map(n => n.x)) - Math.min(...rendered.map(n => n.x))
  const height = Math.max(...rendered.map(n => n.y)) - Math.min(...rendered.map(n => n.y))
  assert.ok(width / height > 1.5 && width / height < 2.1, 'uses the horizontal space instead of a vertical strip')
  const labels = placeGraphLabels(rendered, view, { focusId: 'root', neighborIds: new Set(star.map(n => n.id)) })
  assert.equal(labels.length, star.length, 'all 15 names fit in the neighborhood')
  const overview = reconcileGraphLayout(star, links, {}, states, focused)
  reconcileGraphLayout(star, links, {}, states, overview, options)
  assert.deepEqual(states, before, 're-entering produces the same settled geometry')
})

test('identical refreshes preserve dragged nodes, but changing the center or topology reinitializes the layout', () => {
  const states = new Map(), options = { rootId: 'agent' }
  const layout = reconcileGraphLayout(nodes, edges, {}, states, null, options)
  Object.assign(states.get('rag'), { x: 400, y: 230 })
  const same = reconcileGraphLayout([...nodes].reverse().map(n => ({ ...n, label: `${n.label} updated` })), [...edges].reverse(), {}, states, layout, options)
  assert.equal(same, layout)
  assert.equal(states.get('rag').x, 400)
  const moved = reconcileGraphLayout(nodes, edges, {}, states, same, { rootId: 'rag' })
  assert.deepEqual(states.get('rag'), { x: 0, y: 0, vx: 0, vy: 0 })
  const filtered = reconcileGraphLayout(nodes.slice(0, 3), edges.slice(0, 1), {}, states, moved, { rootId: 'rag' })
  assert.notEqual(filtered, moved)
  assert.equal(states.size, 3)
})

test('hop rings include disconnected filtered nodes, ignore duplicate/dangling edges and fit narrow viewports', () => {
  const links = [...edges, ...edges, { source: 'missing', target: 'agent' }, { source: 'agent', target: 'agent' }]
  for (const [width, height] of [[320, 500], [800, 500]]) {
    const options = { rootId: 'agent', aspectRatio: width / height }
    const layout = createGraphLayout(nodes, links, {}, options)
    assert.deepEqual(layout.positions, createGraphLayout([...nodes].reverse(), [...links].reverse(), {}, options).positions)
    const distance = id => Math.hypot(layout.positions.get(id).x, layout.positions.get(id).y)
    assert.ok(distance('graph') > distance('rag'))
    assert.ok(distance('island') > distance('graph'))
    const rendered = nodes.map(n => ({ ...n, ...layout.positions.get(n.id) }))
    const view = fitGraphBounds(rendered, width, height)
    for (const n of rendered) {
      assert.ok(view.x + (n.x - n.r) * view.k >= 63)
      assert.ok(view.x + (n.x + n.r) * view.k <= width - 63)
      assert.ok(view.y + (n.y - n.r) * view.k >= 63)
      assert.ok(view.y + (n.y + n.r) * view.k <= height - 63)
    }
  }
  assert.equal(createGraphLayout([{ id: 'alone' }], [], {}, { rootId: 'alone' }).positions.size, 1)
  assert.equal(createGraphLayout([], [], {}, { rootId: 'deleted' }).positions.size, 0)
})

test('labels stay readable at overview zoom and avoid each other and the controls area', () => {
  const view = { w: 640, h: 480, x: 150, y: 100, k: 0.45 }
  const ranked = Array.from({ length: 20 }, (_, index) => ({ id: `n${index}`, label: `知识概念 ${index}`, type: 'concept', degree: 3,
    r: 8, x: index % 5 * 170, y: Math.floor(index / 5) * 160 }))
  const labels = placeGraphLabels(ranked, view)
  assert.ok(labels.length >= 8)
  for (const label of labels) {
    assert.equal(label.fontSize * view.k, 12)
    assert.ok(label.screenBox.bottom < view.h - 54)
    for (const other of labels) if (label.id !== other.id) {
      const a = label.screenBox, b = other.screenBox
      assert.ok(a.right <= b.left || b.right <= a.left || a.bottom <= b.top || b.bottom <= a.top)
    }
  }
})

test('selection labels prioritize the selected node and its neighbors instead of unrelated hubs', () => {
  const view = { w: 800, h: 500, x: 300, y: 200, k: 1 }
  const selected = [
    { id: 'selected', label: 'Selected complete concept', degree: 1, x: 0, y: 0, r: 8 },
    { id: 'neighbor', label: 'Neighbor', degree: 2, x: 150, y: 50, r: 8 },
    { id: 'unrelated', label: 'Unrelated hub', degree: 100, x: -100, y: 130, r: 8 },
  ]
  const labels = placeGraphLabels(selected, view, { focusId: 'selected', neighborIds: new Set(['selected', 'neighbor']) })
  assert.deepEqual(labels.map((label) => label.id), ['selected', 'neighbor'])
  assert.equal(labels[0].text, 'Selected complete concept')
})

function syntheticOverview(count = 5000) {
  const nodes = Array.from({ length: count }, (_, index) => ({ id: `synthetic-${index}`, label: `合成概念 ${index}`,
    type: 'concept', r: 9, degree: 4 }))
  const edges = nodes.slice(1).map((node, index) => ({ source: nodes[index].id, target: node.id }))
  const communities = Object.fromEntries(nodes.map((node, index) => [node.id, Math.floor(index / 30)]))
  return { nodes, edges, communities }
}

test('a complete 5000-node overview settles without simulation, remains deterministic, and preserves every relationship', () => {
  const { nodes, edges, communities } = syntheticOverview()
  const layout = createGraphLayout(nodes, edges, communities)
  assert.equal(layout.fixed, true)
  assert.equal(layout.mode, 'overview')
  assert.equal(layout.positions.size, nodes.length)
  assert.equal([...layout.adjacency.values()].reduce((sum, neighbors) => sum + neighbors.length, 0), edges.length * 2)
  for (const state of layout.positions.values()) assert.ok(Object.values(state).every(Number.isFinite))
  const reversed = createGraphLayout([...nodes].reverse(), [...edges].reverse(), communities)
  assert.deepEqual(layout.positions, reversed.positions)
  assert.deepEqual(layout.groups, reversed.groups)
  const original = structuredClone(layout.positions)
  for (let tick = 0; tick < 150; tick++) stepGraphLayout(nodes, edges, layout.positions, layout, { alpha: 0.97 ** tick })
  assert.deepEqual(layout.positions, original, 'a static overview never runs all-pairs repulsion or edge springs')
  for (let i = 0; i < layout.groups.length; i++) for (let j = i + 1; j < layout.groups.length; j++) {
    const a = layout.groups[i], b = layout.groups[j]
    assert.ok(Math.hypot(a.x - b.x, a.y - b.y) >= a.radius + b.radius + 23, 'community bounds do not overlap')
  }
})

test('simulation is retained for small graphs and large selected neighborhoods retain their stable rings', () => {
  const { nodes, edges, communities } = syntheticOverview(GRAPH_SIMULATION_NODE_LIMIT + 1)
  const small = createGraphLayout(nodes.slice(0, GRAPH_SIMULATION_NODE_LIMIT), edges, communities)
  assert.equal(small.fixed, false)
  assert.equal(small.mode, 'simulation')
  assert.equal(createGraphLayout(nodes, edges, communities).mode, 'overview')
  const neighborhood = createGraphLayout(nodes, edges, communities, { rootId: nodes[0].id })
  assert.equal(neighborhood.fixed, true)
  assert.equal(neighborhood.mode, 'neighborhood')
  assert.equal(neighborhood.rootId, nodes[0].id)
  assert.equal(neighborhood.positions.size, nodes.length)
})

test('large overview fits desktop and narrow screens, with readable labels outside every other node and label', () => {
  const { nodes, edges, communities } = syntheticOverview()
  const layout = createGraphLayout(nodes, edges, communities)
  const world = nodes.map(node => ({ ...node, ...layout.positions.get(node.id) }))
  for (const [w, h] of [[1000, 650], [375, 640]]) {
    const view = { w, h, ...fitGraphBounds(world, w, h) }
    for (const node of world) {
      assert.ok(view.x + (node.x - node.r) * view.k >= 63)
      assert.ok(view.x + (node.x + node.r) * view.k <= w - 63)
      assert.ok(view.y + (node.y - node.r) * view.k >= 63)
      assert.ok(view.y + (node.y + node.r) * view.k <= h - 63)
    }
    const rendered = world.map(node => ({ ...node, r: Math.max(node.r, 4 / view.k) }))
    const labels = placeGraphLabels(rendered, view)
    assert.ok(labels.length > 0 && labels.length <= 28)
    const boxesOverlap = (a, b) => a.left < b.right + 4 && a.right + 4 > b.left && a.top < b.bottom + 3 && a.bottom + 3 > b.top
    for (const label of labels) {
      const box = label.screenBox
      assert.equal(label.fontSize * view.k, 12)
      assert.ok(box.left > 6 && box.right < w - 6 && box.top > 6 && box.bottom < h - 54)
      for (const other of labels) if (other.id !== label.id) assert.equal(boxesOverlap(box, other.screenBox), false)
      for (const other of rendered) if (other.id !== label.id) {
        const radius = other.r * view.k + 2, x = view.x + other.x * view.k, y = view.y + other.y * view.k
        // Only visible nodes are label obstacles, matching the rendered viewport.
        if (x > 10 && y > 10 && x < w - 10 && y < h - 10) {
          assert.equal(boxesOverlap(box, { left: x - radius, right: x + radius, top: y - radius, bottom: y + radius }), false)
        }
      }
    }
  }
})

test('large disconnected overviews pack all islands without candidate searches or duplicate coordinates', () => {
  const nodes = Array.from({ length: 4500 }, (_, index) => ({ id: `island-${index}`, type: 'concept', r: 9 }))
  const layout = createGraphLayout(nodes)
  assert.equal(layout.groups.length, nodes.length)
  assert.equal(layout.positions.size, nodes.length)
  assert.equal(new Set([...layout.positions.values()].map(p => `${p.x}:${p.y}`)).size, nodes.length)
  assert.equal(layout.fixed, true)
})
