import test from 'node:test'
import assert from 'node:assert/strict'
import { createGraphHitIndex, createGraphRenderer, graphNodeScreenRadius, parseGraphColor, prepareGraphGeometry } from './knowledgeGraphRenderer.js'

function fixture(count = 5000) {
  const nodes = Array.from({ length: count }, (_, i) => ({ id: `node-${i}`, x: i % 100 * 40 - 2000, y: Math.floor(i / 100) * 40 - 1000,
    r: 8, degree: i % 8, type: 'concept', color: [0.2, 0.6, 0.5, 1] }))
  const edges = nodes.map((node, i) => ({ source: node.id, target: nodes[(i + 1) % count].id,
    origin: i % 3 === 0 ? 'derived' : 'llm', label: '示例关系' }))
  return { nodes, edges }
}

function canvasContext() {
  const calls = []
  const ctx = { calls, globalAlpha: 1 }
  for (const method of ['setTransform', 'clearRect', 'setLineDash', 'beginPath', 'moveTo', 'lineTo', 'closePath', 'stroke', 'fill', 'arc']) {
    ctx[method] = (...args) => { calls.push({ method, args, alpha: ctx.globalAlpha }) }
  }
  return ctx
}

function mockWebGl({ failCompile = false } = {}) {
  const calls = [], gl = { calls, FLOAT: 0x1406, TRIANGLES: 4, ARRAY_BUFFER: 0x8892, STATIC_DRAW: 0x88e4, DYNAMIC_DRAW: 0x88e8,
    VERTEX_SHADER: 0x8b31, FRAGMENT_SHADER: 0x8b30, COMPILE_STATUS: 0x8b81, LINK_STATUS: 0x8b82, COLOR_BUFFER_BIT: 0x4000,
    BLEND: 0xbe2, SRC_ALPHA: 0x302, ONE_MINUS_SRC_ALPHA: 0x303, ONE: 1, DEPTH_TEST: 0xb71 }
  let next = 0, boundBuffer = null
  for (const method of ['createProgram', 'createShader', 'createBuffer']) gl[method] = () => ({ id: ++next, method })
  gl.getShaderParameter = () => !failCompile; gl.getProgramParameter = () => true; gl.isContextLost = () => false
  gl.getAttribLocation = (program, name) => {
    calls.push({ method: 'getAttribLocation', args: [program, name] })
    return ['a_position', 'a_source'].includes(name) ? 0 : ['a_radius', 'a_target'].includes(name) ? 1
      : name === 'a_corner' ? 2 : ['a_color', 'a_style'].includes(name) ? 3 : 4
  }
  gl.getUniformLocation = (program, name) => { calls.push({ method: 'getUniformLocation', args: [program, name] }); return { program, name } }
  gl.bindBuffer = (target, buffer) => { boundBuffer = buffer }
  gl.bufferData = (target, data, usage) => calls.push({ method: 'bufferData', buffer: boundBuffer, data: new Float32Array(data), usage })
  gl.bufferSubData = (target, offset, data) => calls.push({ method: 'bufferSubData', buffer: boundBuffer, offset, data: new Float32Array(data) })
  for (const method of ['shaderSource', 'compileShader', 'attachShader', 'linkProgram', 'deleteProgram', 'deleteShader', 'deleteBuffer',
    'enableVertexAttribArray', 'disableVertexAttribArray', 'vertexAttribPointer', 'uniform2f', 'uniform1f', 'uniform4fv', 'viewport', 'clearColor', 'clear', 'enable', 'blendFuncSeparate', 'disable', 'useProgram', 'drawArrays']) {
    gl[method] = (...args) => calls.push({ method, args })
  }
  return gl
}

function mockCanvas(gl = null, ctx = null, fallbackCtx = null) {
  const listeners = new Map(), inserted = [], contextRequests = []
  const canvas = { width: 300, height: 150, style: {}, listeners, inserted, contextRequests,
    addEventListener(name, callback) { listeners.set(name, callback) },
    removeEventListener(name, callback) { if (listeners.get(name) === callback) listeners.delete(name) },
    getContext(type, options) { contextRequests.push({ type, options }); return type === 'webgl' ? gl : type === '2d' ? ctx : null },
    parentNode: { insertBefore(node) { inserted.push(node) } },
    ownerDocument: { createElement() {
      const fallback = { style: {}, width: 300, height: 150, attributes: {}, removed: false,
        setAttribute(key, value) { this.attributes[key] = value }, getContext() { return fallbackCtx }, remove() { this.removed = true } }
      return fallback
    } },
  }
  return canvas
}

test('5,000 node geometry retains every node and parallel relation without mutating source', () => {
  const { nodes, edges } = fixture()
  edges.push({ ...edges[0], label: '另一种示例关系' }, { source: 'missing', target: nodes[0].id }, { source: nodes[0].id, target: nodes[0].id })
  const snapshot = JSON.stringify({ nodes, edges }), graph = prepareGraphGeometry(nodes, edges)
  assert.equal(graph.nodes.length, 5000); assert.equal(graph.edges.length, 5001)
  assert.equal(graph.nodeData.length, 5000 * 6 * 11); assert.equal(graph.edgeData.length, 5001 * 6 * 13)
  assert.ok(graph.nodeData.every(Number.isFinite)); assert.ok(graph.edgeData.every(Number.isFinite))
  assert.equal(JSON.stringify({ nodes, edges }), snapshot)
  nodes[0].x = 123456; nodes[0].color[0] = 1
  assert.equal(graph.nodes[0].x, -2000); assert.equal(graph.nodes[0].color[0], 0.2)
  assert.equal(graph.edges.at(-1).label, '另一种示例关系')
})

test('screen radii and indexed picking agree at overview, zoom and negative coordinates', () => {
  const small = { id: 'small', x: -500, y: -250, r: 8, degree: 100 }
  const other = { id: 'other', x: 400, y: 300, r: 16, degree: 1 }
  const index = createGraphHitIndex([small, other])
  for (const k of [0.001, 0.07, 1, 4]) {
    const view = { x: 600, y: 400, w: 1000, h: 650, k }, radius = graphNodeScreenRadius(small, k)
    const x = small.x * k + view.x, y = small.y * k + view.y
    assert.equal(index.hitTest(x, y, view, { padding: 0 }), small)
    assert.equal(index.hitTest(x - radius + 0.01, y, view, { padding: 0 }), small)
    assert.notEqual(index.hitTest(x - radius - 0.01, y, view, { padding: 0 }), small)
  }
  assert.equal(graphNodeScreenRadius(small, 0.001), 5.8)
  assert.equal(graphNodeScreenRadius(other, 4), 64)
})

test('spatial picking preserves original nodes, chooses nearest and resolves exact overlap by paint order', () => {
  const first = { id: 'first', x: 0, y: 0, r: 20 }, last = { id: 'last', x: 0, y: 0, r: 20 }, near = { id: 'near', x: 10, y: 0, r: 20 }
  const index = createGraphHitIndex([first, last, near, { id: 'invalid', x: NaN, y: 0 }])
  assert.equal(index.size, 3)
  assert.equal(index.hitTest(0, 0, { k: 1 }), last)
  assert.equal(index.hitTest(10, 0, { k: 1 }), near)
  assert.equal(index.hitTest(100, 100, { k: 1 }), null)
  assert.equal(index.hitTest(NaN, 0, { k: 1 }), null)
  assert.equal(createGraphHitIndex().hitTest(0, 0, { k: 1 }), null)
})

test('large spatial index finds every target without a view rebuild and snapshots indexed positions', () => {
  const { nodes } = fixture(), index = createGraphHitIndex(nodes)
  for (const k of [0.08, 0.8, 3]) {
    const view = { x: 400, y: 300, k, w: 800, h: 600 }
    for (const i of [0, 42, 2561, 4999]) assert.equal(index.hitTest(nodes[i].x * k + view.x, nodes[i].y * k + view.y, view, { padding: 0 }), nodes[i])
  }
  const x = nodes[42].x, y = nodes[42].y
  nodes[42].x += 99999
  assert.equal(index.hitTest(x, y, { k: 1 }, { padding: 0 }), nodes[42])
})

test('resolved CSS and numeric palette colors cover light, dark and community HSL', () => {
  assert.deepEqual(parseGraphColor('#abc'), [170 / 255, 187 / 255, 204 / 255, 1])
  assert.deepEqual(parseGraphColor('#aabbcc80'), [170 / 255, 187 / 255, 204 / 255, 128 / 255])
  assert.deepEqual(parseGraphColor('rgb(20 40 60 / 50%)'), [20 / 255, 40 / 255, 60 / 255, 0.5])
  assert.deepEqual(parseGraphColor('rgba(100%, 0%, 50%, .25)'), [1, 0, 0.5, 0.25])
  assert.deepEqual(parseGraphColor('color(srgb 0.1 0.8 0.65 / 80%)'), [0.1, 0.8, 0.65, 0.8])
  assert.deepEqual(parseGraphColor('hsl(120 100% 50%)'), [0, 1, 0, 1])
  assert.deepEqual(parseGraphColor('hsla(.5turn 100% 50% / .4)'), [0, 1, 1, 0.4])
  assert.deepEqual(parseGraphColor(new Float32Array([0, 0.5, 1, 1])), [0, 0.5, 1, 1])
  assert.deepEqual(parseGraphColor('unresolved', [0.1, 0.2, 0.3, 1]), [0.1, 0.2, 0.3, 1])
  assert.deepEqual(parseGraphColor('transparent'), [0, 0, 0, 0])
})

test('WebGL camera moves do not re-upload geometry and GPU draws preserve full topology', () => {
  const gl = mockWebGl(), canvas = mockCanvas(gl), renderer = createGraphRenderer(canvas), { nodes, edges } = fixture()
  renderer.setGraph(nodes, edges); renderer.render({ w: 1000, h: 650, x: 500, y: 325, k: 0.1, dpr: 4 })
  assert.equal(renderer.type, 'webgl'); assert.equal(canvas.width, 2000); assert.equal(canvas.height, 1300)
  const uploads = gl.calls.filter((call) => call.method === 'bufferData')
  assert.equal(uploads.length, 4)
  const lookups = gl.calls.filter((call) => ['getAttribLocation', 'getUniformLocation'].includes(call.method)).length
  const draws = gl.calls.filter((call) => call.method === 'drawArrays')
  assert.deepEqual(draws.map((call) => call.args[2]), [30000, 30000])
  for (let i = 0; i < 120; i++) renderer.render({ w: 1000, h: 650, x: 500 + i, y: 325 - i, k: 0.1 + i * 0.001 })
  assert.equal(gl.calls.filter((call) => call.method === 'bufferData').length, uploads.length)
  assert.equal(gl.calls.filter((call) => ['getAttribLocation', 'getUniformLocation'].includes(call.method)).length, lookups)
  renderer.setFocus(nodes[42].id, new Set([nodes[41].id, nodes[43].id]))
  renderer.render({ w: 1000, h: 650, k: 0.2 })
  const focusedUploads = gl.calls.filter((call) => call.method === 'bufferData').slice(4)
  assert.equal(focusedUploads.length, 2); assert.ok(focusedUploads.every((call) => call.usage === gl.DYNAMIC_DRAW))
  const nodeFlags = focusedUploads.find((call) => call.data.length === 5000 * 12).data
  assert.equal(nodeFlags[42 * 12 + 1], 1); assert.equal(nodeFlags[41 * 12], 1); assert.equal(nodeFlags[0], 0)
  renderer.setFocus(nodes[42].id, new Set([nodes[41].id, nodes[43].id])); renderer.render({ k: 1 })
  assert.equal(gl.calls.filter((call) => call.method === 'bufferData').length, 6)
  renderer.destroy()
  assert.equal(gl.calls.filter((call) => call.method === 'deleteBuffer').length, 4)
  assert.equal(gl.calls.filter((call) => call.method === 'deleteProgram').length, 2)
  assert.equal(canvas.listeners.size, 0)
  const count = gl.calls.length
  renderer.destroy(); renderer.render({ k: 1 }); renderer.setGraph(nodes, edges)
  assert.equal(gl.calls.length, count)
})

test('Canvas2D fallback preserves directed dashed relations, focus dimming and viewport clipping', () => {
  const ctx = canvasContext(), canvas = mockCanvas(null, ctx), renderer = createGraphRenderer(canvas)
  const nodes = [{ id: 'a', x: 40, y: 50, r: 8 }, { id: 'b', x: 160, y: 50, r: 8 }, { id: 'c', x: 40, y: 100, r: 8 }, { id: 'far', x: 10000, y: 10000, r: 8 }]
  renderer.setGraph(nodes, [{ source: 'a', target: 'b', origin: 'derived' }, { source: 'b', target: 'c' }])
  renderer.setFocus('a', ['b']); renderer.render({ w: 240, h: 160, k: 1, dpr: 1 })
  assert.equal(renderer.type, 'canvas2d')
  assert.ok(ctx.calls.some((call) => call.method === 'setLineDash' && call.args[0].join() === '5,4'))
  assert.ok(ctx.calls.some((call) => call.method === 'closePath'))
  assert.equal(ctx.calls.filter((call) => call.method === 'arc').length, 3)
  assert.equal(ctx.calls.find((call) => call.method === 'arc' && call.args[1] === 100)?.alpha, 0.16)
  assert.ok(ctx.calls.some((call) => call.method === 'stroke' && call.alpha === 0.07))
  renderer.destroy()
})

test('dragging one of 5,000 nodes updates only its node and incident edge buffers', () => {
  const gl = mockWebGl(), canvas = mockCanvas(gl), renderer = createGraphRenderer(canvas), { nodes, edges } = fixture()
  renderer.setGraph(nodes, edges); renderer.render({ w: 1000, h: 650, k: 0.1 })
  const fullUploads = gl.calls.filter((call) => call.method === 'bufferData').length
  assert.equal(renderer.updateNodePosition('missing', 10, 20), false)
  assert.equal(renderer.updateNodePosition('node-42', NaN, 20), false)
  assert.equal(renderer.updateNodePosition('node-42', nodes[42].x, nodes[42].y), false)
  assert.equal(renderer.updateNodePosition('node-42', 10, 20), true)
  assert.equal(renderer.updateNodePosition('node-42', 15, 25), true)
  assert.equal(renderer.updateNodePosition('node-42', 15, 25), false)
  renderer.render({ w: 1000, h: 650, k: 0.1 })
  assert.equal(gl.calls.filter((call) => call.method === 'bufferData').length, fullUploads)
  const partials = gl.calls.filter((call) => call.method === 'bufferSubData')
  assert.equal(partials.length, 3)
  const nodeUpload = partials.find((call) => call.data.length === 66)
  assert.equal(nodeUpload.offset, 42 * 66 * 4); assert.deepEqual([...nodeUpload.data.slice(0, 2)], [15, 25])
  const edgeUploads = partials.filter((call) => call.data.length === 78)
  assert.deepEqual(edgeUploads.map((call) => call.offset).sort((a, b) => a - b), [41 * 78 * 4, 42 * 78 * 4])
  assert.deepEqual([...edgeUploads.find((call) => call.offset === 41 * 78 * 4).data.slice(4, 6)], [15, 25])
  assert.deepEqual([...edgeUploads.find((call) => call.offset === 42 * 78 * 4).data.slice(0, 2)], [15, 25])
  // No new pointer input means no new transfers. Replacing a graph cancels any
  // queued slices, so a pending drag cannot write into its replacement buffers.
  renderer.render({ w: 1000, h: 650, k: 0.2 })
  assert.equal(gl.calls.filter((call) => call.method === 'bufferSubData').length, 3)
  renderer.updateNodePosition('node-42', 100, 200); renderer.setGraph(nodes.slice(0, 10), [])
  renderer.render({ w: 1000, h: 650, k: 0.1 })
  assert.equal(gl.calls.filter((call) => call.method === 'bufferSubData').length, 3)
  assert.equal(gl.calls.filter((call) => call.method === 'drawArrays').at(-1).args[2], 10 * 6)
  renderer.destroy(); assert.equal(renderer.updateNodePosition('node-1', 5, 6), false)
})

test('Canvas fallback uses the same updated node and incident edge positions during drag', () => {
  const ctx = canvasContext(), renderer = createGraphRenderer(mockCanvas(null, ctx))
  renderer.setGraph([{ id: 'a', x: 20, y: 40, r: 8 }, { id: 'b', x: 160, y: 40, r: 8 }], [{ source: 'a', target: 'b' }])
  renderer.updateNodePosition('a', 60, 80); renderer.render({ w: 240, h: 160, k: 1 })
  assert.ok(ctx.calls.some((call) => call.method === 'arc' && call.args[0] === 60 && call.args[1] === 80))
  const lineStart = ctx.calls.find((call) => call.method === 'moveTo')
  assert.ok(lineStart.args[0] > 60 && lineStart.args[0] < 72)
  assert.ok(lineStart.args[1] < 80 && lineStart.args[1] > 70)
  renderer.destroy()
})

test('transparent GPU canvas preserves the intended dim alpha when composited on either theme', () => {
  const gl = mockWebGl(), canvas = mockCanvas(gl), renderer = createGraphRenderer(canvas)
  renderer.setGraph([{ id: 'focus', x: 40, y: 50, r: 8 }, { id: 'dim', x: 80, y: 50, r: 8 }], [])
  renderer.setFocus('focus', []); renderer.render({ w: 200, h: 120, k: 1 })
  assert.equal(canvas.contextRequests.find((request) => request.type === 'webgl').options.premultipliedAlpha, true)
  const blend = gl.calls.find((call) => call.method === 'blendFuncSeparate')
  assert.deepEqual(blend.args, [gl.SRC_ALPHA, gl.ONE_MINUS_SRC_ALPHA, gl.ONE, gl.ONE_MINUS_SRC_ALPHA])
  assert.deepEqual(gl.calls.find((call) => call.method === 'clearColor').args, [0, 0, 0, 0])
  // The transparent framebuffer must store alpha .16, with RGB premultiplied
  // once. Compositing it over light/dark then has exactly 16% node coverage.
  const opacity = 0.16, fill = [0.1, 0.65, 0.55]
  const sourceRgbFactor = blend.args[0] === gl.SRC_ALPHA ? opacity : 1
  const sourceAlphaFactor = blend.args[2] === gl.ONE ? 1 : opacity
  const storedRgb = fill.map((channel) => channel * sourceRgbFactor), storedAlpha = opacity * sourceAlphaFactor
  assert.equal(storedAlpha, opacity)
  for (const background of [[1, 1, 1], [0.06, 0.08, 0.1]]) {
    const composite = storedRgb.map((channel, i) => channel + background[i] * (1 - storedAlpha))
    const expected = fill.map((channel, i) => channel * opacity + background[i] * (1 - opacity))
    assert.deepEqual(composite, expected)
  }
  renderer.destroy()
})

test('lost WebGL context displays safe fallback and restored context re-uploads latest cached data', () => {
  const gl = mockWebGl(), ctx = canvasContext(), canvas = mockCanvas(gl, null, ctx), changes = []
  const renderer = createGraphRenderer(canvas, { onTypeChange: (type) => changes.push(type) })
  const { nodes, edges } = fixture(50)
  renderer.setGraph(nodes, edges); renderer.render({ w: 800, h: 600, k: 0.1 })
  let prevented = false
  canvas.listeners.get('webglcontextlost')({ preventDefault() { prevented = true } })
  assert.equal(prevented, true); assert.equal(renderer.type, 'canvas2d')
  const fallback = canvas.inserted[0]
  assert.equal(fallback.attributes['aria-hidden'], 'true'); assert.equal(fallback.style.pointerEvents, 'none')
  renderer.setGraph(nodes.slice(0, 20), edges); renderer.render({ w: 500, h: 400, k: 0.3 })
  canvas.listeners.get('webglcontextrestored')()
  assert.equal(renderer.type, 'webgl'); assert.equal(fallback.removed, true)
  assert.equal(gl.calls.filter((call) => call.method === 'drawArrays').at(-1).args[2], 20 * 6)
  assert.deepEqual(changes, ['webgl', 'canvas2d', 'webgl'])
  renderer.destroy()
})

test('driver refusing shaders falls back and releases partial resources', () => {
  const gl = mockWebGl({ failCompile: true }), ctx = canvasContext(), canvas = mockCanvas(gl, null, ctx)
  const renderer = createGraphRenderer(canvas)
  assert.equal(renderer.type, 'canvas2d')
  assert.equal(gl.calls.filter((call) => call.method === 'deleteProgram').length, 1)
  assert.equal(gl.calls.filter((call) => call.method === 'deleteShader').length, 1)
  renderer.destroy(); assert.equal(canvas.inserted[0].removed, true)
})
