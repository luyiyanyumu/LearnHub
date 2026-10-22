import test from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import { compileScript, compileTemplate, parse } from '@vue/compiler-sfc'
import * as vue from 'vue'
import * as layoutUtils from '../utils/knowledgeGraphLayout.js'
import * as rendererUtils from '../utils/knowledgeGraphRenderer.js'

const source = readFileSync(new URL('./KnowledgeGraph.vue', import.meta.url), 'utf8')
const descriptor = parse(source).descriptor
const script = compileScript(descriptor, { id: 'knowledge-graph-behavior' })
const compiled = script.content
const setup = compiled.replace(/^import\s+([\s\S]*?)\s+from\s+['"]([^'"]+)['"]\s*;?\r?$/gm,
  (_, names, from) => `const ${names.replace(/\bas\b/g, ':')} = modules[${JSON.stringify(from)}]`).replace('export default', 'return')
const template = compileTemplate({ source: descriptor.template.content, filename: 'KnowledgeGraph.vue',
  id: 'knowledge-graph-behavior', compilerOptions: { bindingMetadata: script.bindings } }).code
  .replace(/^import\s+([\s\S]*?)\s+from\s+['"]([^'"]+)['"]\s*;?\r?$/gm,
    (_, names, from) => `const ${names.replace(/\bas\b/g, ':')} = modules[${JSON.stringify(from)}]`)
  .replace('export function render', 'return function render')

function fixture(count = 5000) {
  const nodes = Array.from({ length: count }, (_, index) => ({ id: `example-${String(index).padStart(5, '0')}`,
    label: `示例概念 ${index}`, type: 'concept' }))
  const edges = nodes.map((node, index) => ({ source: node.id, target: nodes[(index + 1) % count].id,
    relation: '示例关系', origin: index % 2 ? 'derived' : 'llm' }))
  return { nodes, edges, communities: Object.fromEntries(nodes.map((node, index) => [node.id, Math.floor(index / 20)])) }
}

function eventTarget() {
  const listeners = new Map()
  return { listeners,
    addEventListener(type, fn) { if (!listeners.has(type)) listeners.set(type, new Set()); listeners.get(type).add(fn) },
    removeEventListener(type, fn) { listeners.get(type)?.delete(fn); if (!listeners.get(type)?.size) listeners.delete(type) },
    dispatch(type, event = {}) { for (const fn of [...(listeners.get(type) || [])]) fn(event) },
  }
}

function mountGraph(props = fixture(), { rendererType = 'webgl' } = {}) {
  const globalNames = ['window', 'document', 'requestAnimationFrame', 'cancelAnimationFrame', 'ResizeObserver',
    'MutationObserver', 'getComputedStyle', 'setTimeout', 'clearTimeout']
  const previous = new Map(globalNames.map(name => [name, Object.getOwnPropertyDescriptor(globalThis, name)]))
  const frames = new Map(), timers = new Map(), renderers = [], events = [], observers = [], hitIndexes = []
  let nextId = 0
  const requestFrame = callback => { const id = ++nextId; frames.set(id, callback); return id }
  const cancelFrame = id => frames.delete(id)
  const window = { ...eventTarget(), devicePixelRatio: 1, requestAnimationFrame: requestFrame, cancelAnimationFrame: cancelFrame }
  const rect = () => ({ left: 20, top: 30, width: 1000, height: 650 })
  const elements = []
  let canvas = null
  const root = { classList: { contains: () => false } }
  const style = { getPropertyValue(name) {
    return ({ '--app-brand': '#0a9f90', '--app-brand-deep': '#07897d', '--app-brand-soft': '#ddf5ef',
      '--app-text-1': '#203438', '--app-text-2': '#4b6267', '--app-text-3': '#74868a',
      '--app-border': '#d9e2e4', '--app-card': '#ffffff' })[name] || ''
  } }
  const globals = { window, document: { documentElement: root, body: root }, requestAnimationFrame: requestFrame,
    cancelAnimationFrame: cancelFrame, getComputedStyle: () => style,
    setTimeout(callback, delay) { const id = ++nextId; timers.set(id, { callback, delay }); return id },
    clearTimeout: id => timers.delete(id),
    ResizeObserver: class { constructor(callback) { this.callback = callback; observers.push(this) } observe() {} disconnect() { this.disconnected = true } },
    MutationObserver: class { constructor(callback) { this.callback = callback; observers.push(this) } observe() {} disconnect() { this.disconnected = true } },
  }
  for (const [name, value] of Object.entries(globals)) Object.defineProperty(globalThis, name, { configurable: true, writable: true, value })
  const modules = { vue, '../utils/knowledgeGraphLayout.js': layoutUtils,
    '../utils/knowledgeGraphRenderer.js': { ...rendererUtils,
      createGraphHitIndex(nodes, options) {
        hitIndexes.push(nodes || [])
        return rendererUtils.createGraphHitIndex(nodes, options)
      }, createGraphRenderer(_canvas, options = {}) {
      const calls = { graphs: [], focuses: [], draws: [], moves: [], destroyed: false }
      const renderer = { type: rendererType, calls,
        setGraph(nodes, edges) { calls.graphs.push({ nodes, edges }) },
        setFocus(id, ids) { calls.focuses.push({ id, ids: new Set(ids || []) }) },
        updateNodePosition(id, x, y) { calls.moves.push({ id, x, y }); return true },
        render(view, palette) { calls.draws.push({ view: { ...view }, palette }) },
        destroy() { calls.destroyed = true },
      }
      renderers.push(renderer); options.onTypeChange?.(rendererType)
      return renderer
    } },
  }
  const component = new Function('modules', setup)(modules)
  component.render = new Function('modules', template)(modules)
  const renderer = vue.createRenderer({ createElement(tag) {
    const element = { ...eventTarget(), tag, props: {}, children: [], style: {}, clientWidth: 1000, clientHeight: 650,
      getBoundingClientRect: rect, closest: () => null, contains: () => true,
      focus() {}, setPointerCapture() {}, releasePointerCapture() {} }
    elements.push(element)
    if (tag === 'canvas') canvas = element
    return element
  }, createText: text => ({ text }), createComment: text => ({ comment: text }),
  insert(child, parent, anchor) {
    if (child.parent) { const old = child.parent.children.indexOf(child); if (old >= 0) child.parent.children.splice(old, 1) }
    const index = anchor ? parent.children.indexOf(anchor) : -1
    parent.children.splice(index < 0 ? parent.children.length : index, 0, child); child.parent = parent
  }, remove(child) {
    const index = child.parent?.children.indexOf(child)
    if (index >= 0) child.parent.children.splice(index, 1)
    child.parent = null
  }, setText: (node, text) => { node.text = text }, setElementText: (node, text) => { node.text = text; node.children = [] },
  patchProp: (node, name, _previous, value) => { node.props[name] = value }, parentNode: node => node.parent,
  nextSibling(node) { const index = node.parent?.children.indexOf(node); return node.parent?.children[index + 1] || null } })
  const app = renderer.createApp(component, { ...props, onSelect: node => events.push(['select', node]), onOpen: node => events.push(['open', node]) })
  const host = { children: [] }
  app.mount(host)
  const state = app._instance.setupState
  const descendants = () => {
    const result = [], queue = [...host.children]
    for (let index = 0; index < queue.length; index++) { const node = queue[index]; result.push(node); queue.push(...(node.children || [])) }
    return result
  }
  return { state, props: app._instance.props, get canvas() { return canvas }, window, events, frames, timers, renderers, observers, hitIndexes,
    elements: descendants,
    async tick() { await vue.nextTick() },
    async frame() {
      await vue.nextTick()
      const callbacks = [...frames.values()]; frames.clear()
      for (const callback of callbacks) callback(0)
      await vue.nextTick()
    },
    async settleLabels() {
      await vue.nextTick()
      const callbacks = [...timers.values()]; timers.clear()
      for (const { callback } of callbacks) callback()
      await vue.nextTick()
    },
    point(node = state.positionNodes[0], offsetX = 0, offsetY = 0) {
      const bounds = rect()
      return { button: 0, clientX: bounds.left + state.view.x + node.x * state.view.k + offsetX,
        clientY: bounds.top + state.view.y + node.y * state.view.k + offsetY, target: canvas, currentTarget: canvas,
        preventDefault() {}, stopPropagation() {} }
    },
    cleanup() {
      app.unmount()
      for (const [name, descriptor] of previous) {
        if (descriptor) Object.defineProperty(globalThis, name, descriptor)
        else delete globalThis[name]
      }
    },
  }
}

test('large graph keeps all 5000 nodes and relationships while camera gestures reuse geometry', async () => {
  const data = fixture(), h = mountGraph(data)
  try {
    await h.frame()
    const calls = h.renderers[0].calls, geometry = h.state.positionNodes, initialGraphs = calls.graphs.length, initialIndexes = h.hitIndexes.length
    assert.equal(h.state.largeGraph, true)
    assert.equal(calls.graphs.at(-1).nodes.length, data.nodes.length)
    assert.equal(calls.graphs.at(-1).edges.length, data.edges.length)
    assert.deepEqual(new Set(calls.graphs.at(-1).nodes.map(n => n.id)), new Set(data.nodes.map(n => n.id)))
    assert.equal(h.state.renderNodes.length, 0, 'large graphs do not create thousands of SVG node bindings')
    assert.equal(h.state.renderEdges.length, 0)
    assert.equal(h.elements().filter(node => node.tag === 'canvas').length, 1)
    assert.equal(h.elements().filter(node => node.props?.role === 'button').length, 0)
    assert.equal(h.canvas.props['data-node-count'], 5000)
    assert.equal(h.canvas.props['data-edge-count'], 5000)
    const initialDraws = calls.draws.length
    for (let tick = 0; tick < 12; tick++) h.state.zoomBy(1.01)
    await h.tick()
    assert.equal(h.frames.size, 1, 'a burst of camera updates schedules one drawing frame')
    assert.equal(h.state.positionNodes, geometry, 'camera updates keep the world geometry cache')
    await h.frame()
    assert.equal(calls.draws.length, initialDraws + 1)
    assert.equal(calls.graphs.length, initialGraphs, 'camera movement never uploads the graph again')
    assert.equal(h.hitIndexes.length, initialIndexes, 'camera movement reuses the world-space hit index')
    assert.equal(calls.graphs.at(-1).edges, data.edges)
  } finally { h.cleanup() }
})

test('overview labels are deferred until a camera gesture settles, then remain bounded and readable', async () => {
  const h = mountGraph()
  try {
    await h.frame(); await h.settleLabels()
    assert.equal(h.state.labelsPending, false)
    assert.ok(h.state.renderLabels.length > 0 && h.state.renderLabels.length <= 18)
    h.state.zoomBy(1.1)
    await h.tick()
    assert.equal(h.state.labelsPending, true)
    assert.equal(h.state.renderLabels.length, 0, 'old labels are hidden while their screen positions are stale')
    await h.frame(); await h.settleLabels()
    assert.equal(h.state.labelsPending, false)
    for (const label of h.state.renderLabels) assert.ok(Math.abs(label.fontSize * h.state.view.k - 12) < 0.00001)
  } finally { h.cleanup() }
})

test('large-graph clicks select the hit node and dragging flushes its final move without emitting a click', async () => {
  const data = fixture(500), h = mountGraph(data)
  try {
    await h.frame()
    const node = h.state.positionNodes[0], original = { x: node.x, y: node.y }
    h.state.onCanvasDown(h.point(node))
    h.window.dispatch('pointerup')
    assert.deepEqual(h.events.map(([type, selected]) => [type, selected?.id]), [['select', node.id]])
    assert.equal(h.events[0][1], data.nodes[0], 'selection preserves the original node payload')
    assert.equal(h.window.listeners.size, 0)

    const graphUploads = h.renderers[0].calls.graphs.length
    h.state.onCanvasDown(h.point(node))
    h.window.dispatch('pointermove', h.point(node, 10, 5))
    h.window.dispatch('pointermove', h.point(node, 30, 15))
    assert.equal(h.state.positionNodes[0].x, original.x, 'pointer events are coalesced until a frame or release')
    await h.frame(); await h.frame()
    assert.equal(h.renderers[0].calls.graphs.length, graphUploads, 'drag frames only patch the moved node and incident edges')
    assert.equal(h.renderers[0].calls.moves.length, 1, 'the pointer burst applies only its newest position')
    assert.equal(h.renderers[0].calls.moves[0].id, node.id)
    h.window.dispatch('pointermove', h.point(node, 40, 20))
    h.window.dispatch('pointerup')
    assert.equal(h.events.length, 1, 'a drag does not select or clear the node')
    assert.ok(Math.abs(h.state.positionNodes[0].x - original.x - 40 / h.state.view.k) < 0.00001)
    assert.ok(Math.abs(h.state.positionNodes[0].y - original.y - 20 / h.state.view.k) < 0.00001)
    assert.equal(h.renderers[0].calls.moves.length, 2, 'release flushes a move that has not reached an animation frame')
    assert.equal(h.window.listeners.size, 0)
    await h.frame()
    assert.equal(h.renderers[0].calls.graphs.length, graphUploads + 1, 'changed positions upload once after the drag')
  } finally { h.cleanup() }
})

test('pointer cancellation discards the queued move and background panning reuses bounds and GPU geometry', async () => {
  const h = mountGraph(fixture(500))
  try {
    await h.frame()
    const node = h.state.positionNodes[0], x = node.x, geometry = h.state.positionNodes
    h.state.onCanvasDown(h.point(node))
    h.window.dispatch('pointermove', h.point(node, 30, 30))
    h.window.dispatch('pointercancel')
    await h.frame()
    assert.equal(h.state.positionNodes[0].x, x)
    assert.equal(h.events.length, 0)
    assert.equal(h.window.listeners.size, 0)

    const initialUploads = h.renderers[0].calls.graphs.length, bounds = h.state.graphBounds
    const start = { ...h.point(node), clientX: 22, clientY: 32 }
    const initialViewX = h.state.view.x, initialViewY = h.state.view.y
    h.state.onCanvasDown(start)
    for (let offset = 1; offset <= 20; offset++) h.window.dispatch('pointermove', { ...start, clientX: start.clientX + offset, clientY: start.clientY + offset })
    await h.frame()
    assert.equal(h.state.view.x, initialViewX + 20)
    assert.equal(h.state.view.y, initialViewY + 20)
    h.window.dispatch('pointerup')
    await h.frame()
    assert.equal(h.state.positionNodes, geometry)
    assert.equal(h.state.graphBounds, bounds)
    assert.equal(h.renderers[0].calls.graphs.length, initialUploads)
    assert.equal(h.events.length, 0)

    h.state.onCanvasDown(start)
    h.window.dispatch('pointerup')
    assert.deepEqual(h.events, [['select', null]], 'a background click clears selection')
  } finally { h.cleanup() }
})

test('keyboard navigation reaches every node and preserves selection, open, zoom and Escape actions', async () => {
  const data = fixture(500), h = mountGraph(data)
  const key = (value, extra = {}) => ({ key: value, preventDefault() {}, ...extra })
  try {
    await h.frame()
    h.state.onCanvasFocus({ target: { matches: selector => selector === ':focus-visible' } })
    assert.equal(h.state.keyboardId, data.nodes[0].id)
    assert.match(h.state.keyboardCaption, /示例概念 0，概念，2 条关联/)
    h.canvas.props.onKeydown(key('ArrowRight'))
    h.canvas.props.onKeydown(key('Enter'))
    h.canvas.props.onKeydown(key('End'))
    h.canvas.props.onKeydown(key(' '))
    h.canvas.props.onKeydown(key('Enter', { shiftKey: true }))
    assert.deepEqual(h.events.map(([type, node]) => [type, node.id]), [
      ['select', data.nodes[1].id], ['select', data.nodes.at(-1).id], ['open', data.nodes.at(-1).id],
    ])
    const uploads = h.renderers[0].calls.graphs.length, scale = h.state.view.k
    h.canvas.props.onKeydown(key('+'))
    assert.ok(h.state.view.k > scale)
    h.canvas.props.onKeydown(key('-'))
    h.canvas.props.onKeydown(key('Escape'))
    assert.equal(h.state.keyboardId, '')
    assert.equal(h.state.focusId, '')
    assert.deepEqual(h.events.at(-1), ['select', null])
    await h.frame()
    assert.equal(h.renderers[0].calls.graphs.length, uploads)
  } finally { h.cleanup() }
})

test('hover and active selection update focused neighbors without rebuilding graph geometry', async () => {
  const data = fixture(500), h = mountGraph(data)
  try {
    await h.frame()
    const calls = h.renderers[0].calls, uploads = calls.graphs.length
    h.state.onCanvasMove(h.point(h.state.positionNodes[4]))
    await h.frame()
    assert.equal(h.state.focusId, data.nodes[4].id)
    assert.deepEqual(calls.focuses.at(-1).ids, new Set([data.nodes[4].id, data.nodes[3].id, data.nodes[5].id]))
    assert.match(h.state.relationCaption, /^2 条关系 · 示例关系$/)
    h.state.hoverId = ''
    h.props.activeId = data.nodes[10].id
    await h.frame()
    assert.equal(calls.focuses.at(-1).id, data.nodes[10].id)
    assert.equal(calls.graphs.length, uploads)
  } finally { h.cleanup() }
})

test('a filtered small neighborhood restores SVG nodes and releases the overview renderer', async () => {
  const h = mountGraph(fixture(500))
  try {
    await h.frame()
    const originalRenderer = h.renderers[0]
    const small = fixture(6)
    h.props.nodes = small.nodes; h.props.edges = small.edges; h.props.communities = small.communities
    h.props.layoutRootId = small.nodes[0].id
    await h.tick()
    assert.equal(h.state.largeGraph, false)
    assert.equal(h.elements().filter(node => node.tag === 'canvas').length, 0)
    assert.equal(h.elements().filter(node => node.props?.role === 'button').length, 6)
    assert.equal(originalRenderer.calls.destroyed, true)
    assert.equal(h.state.renderNodes.length, 6)
    assert.equal(h.state.renderEdges.length, 6)
    assert.equal(h.state.layoutMode, 'neighborhood')
  } finally { h.cleanup() }
})

test('Canvas2D fallback retains the full graph and drawing unavailability remains visible with keyboard access', async () => {
  for (const rendererType of ['canvas2d', 'unavailable']) {
    const data = fixture(500), h = mountGraph(data, { rendererType })
    try {
      await h.frame()
      assert.equal(h.state.rendererType, rendererType)
      assert.equal(h.renderers[0].calls.graphs.at(-1).nodes.length, data.nodes.length)
      assert.equal(h.canvas.props['data-renderer'], rendererType)
      const notice = h.elements().find(node => node.props?.class === 'kg-render-error')
      assert.equal(Boolean(notice), rendererType === 'unavailable')
      if (notice) assert.match(notice.text, /仍可通过右侧搜索查看概念和关系/)
      h.state.onCanvasKey({ key: 'End', preventDefault() {} })
      h.state.onCanvasKey({ key: 'Enter', preventDefault() {} })
      assert.equal(h.events.at(-1)[1].id, data.nodes.at(-1).id)
    } finally { h.cleanup() }
  }
})

test('unmount releases renderer, observers, pointer listeners, deferred labels and queued frames', async () => {
  const h = mountGraph(fixture(500))
  let cleaned = false
  try {
    await h.frame()
    h.state.onCanvasDown(h.point())
    h.window.dispatch('pointermove', h.point(undefined, 10, 10))
    h.state.zoomBy(1.1)
    await h.tick()
    assert.ok(h.frames.size > 0)
    assert.ok(h.timers.size > 0)
    h.cleanup(); cleaned = true
    assert.equal(h.renderers[0].calls.destroyed, true)
    assert.equal(h.frames.size, 0)
    assert.equal(h.timers.size, 0)
    assert.equal(h.window.listeners.size, 0)
    assert.ok(h.observers.every(observer => observer.disconnected))
  } finally { if (!cleaned) h.cleanup() }
})
