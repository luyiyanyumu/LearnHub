import test from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import { compileScript, compileTemplate, parse } from '@vue/compiler-sfc'
import * as vue from 'vue'
import * as communities from '../utils/knowledgeGraphCommunities.js'
import * as galaxyRenderer from '../utils/knowledgeGalaxyRenderer.js'

const descriptor = parse(readFileSync(new URL('./CommunityGalaxy.vue', import.meta.url), 'utf8')).descriptor
const script = compileScript(descriptor, { id: 'community-galaxy-behavior' })
const imports = code => code.replace(/^import\s+([\s\S]*?)\s+from\s+['"]([^'"]+)['"]\s*;?\r?$/gm,
  (_, names, from) => `const ${names.replace(/\bas\b/g, ':')} = modules[${JSON.stringify(from)}]`)
const factory = new Function('modules', imports(script.content).replace('export default', 'return'))
const render = new Function('modules', imports(compileTemplate({ source: descriptor.template.content,
  filename: 'CommunityGalaxy.vue', id: 'community-galaxy-behavior', compilerOptions: { bindingMetadata: script.bindings } }).code)
  .replace('export function render', 'return function render'))
const modules = { vue, '../utils/knowledgeGraphCommunities.js': communities }

function fixture(nodeCount = 5000, communityCount = 257) {
  const nodes = Array.from({ length: nodeCount }, (_, index) => ({ id: `example-${index}`, label: `示例概念 ${index}`, degree: index % 7 }))
  return { nodes, edges: nodes.map((node, index) => ({ source: node.id, target: nodes[(index + 1) % nodes.length].id, origin: 'llm' })),
    communities: Object.fromEntries(nodes.map((node, index) => [node.id, index % communityCount])),
    groups: Array.from({ length: communityCount }, (_, communityId) => ({ communityId, title: `示例社区 ${communityId}` })) }
}

function eventTarget() {
  const listeners = new Map()
  return { listeners,
    addEventListener(name, listener) { if (!listeners.has(name)) listeners.set(name, new Set()); listeners.get(name).add(listener) },
    removeEventListener(name, listener) { listeners.get(name)?.delete(listener); if (!listeners.get(name)?.size) listeners.delete(name) },
    dispatch(name, event = {}) { for (const listener of [...(listeners.get(name) || [])]) listener(event) },
  }
}

function mountGalaxy(props = fixture(), { width = 1000, height = 520, hidden = false, reducedMotion = false, inView = true } = {}) {
  const previous = new Map(['ResizeObserver', 'IntersectionObserver', 'window', 'document', 'requestAnimationFrame', 'cancelAnimationFrame', 'setTimeout', 'clearTimeout']
    .map(name => [name, Object.getOwnPropertyDescriptor(globalThis, name)]))
  const observers = [], intersections = [], events = [], sizes = { width, height }
  const frames = new Map(), timers = new Map(), contexts = [], draws = [], renderers = []
  let nextFrameId = 0, time = 0
  const requestFrame = callback => { const id = ++nextFrameId; frames.set(id, callback); return id }
  const cancelFrame = id => frames.delete(id)
  const context = () => {
    const calls = [], ctx = { calls, globalAlpha: 1, measureText: value => ({ width: String(value).length * 7 }) }
    for (const method of ['setTransform', 'resetTransform', 'clearRect', 'fillRect', 'save', 'restore', 'translate', 'scale', 'rotate',
      'beginPath', 'moveTo', 'lineTo', 'quadraticCurveTo', 'bezierCurveTo', 'closePath', 'stroke', 'fill', 'arc', 'ellipse',
      'fillText', 'strokeText', 'setLineDash', 'drawImage']) {
      ctx[method] = (...args) => calls.push({ method, args, alpha: ctx.globalAlpha })
    }
    for (const method of ['createRadialGradient', 'createLinearGradient']) ctx[method] = () => ({ addColorStop() {} })
    contexts.push(ctx)
    return ctx
  }
  const canvas = () => { const ctx = context(); return { width: 0, height: 0, getContext: type => type === '2d' ? ctx : null } }
  const document = { ...eventTarget(), hidden, visibilityState: hidden ? 'hidden' : 'visible', createElement: () => canvas() }
  const media = { ...eventTarget(), matches: reducedMotion }
  const globals = {
    document,
    window: { ...eventTarget(), devicePixelRatio: 1, requestAnimationFrame: requestFrame, cancelAnimationFrame: cancelFrame,
      matchMedia(query) { media.query = query; return media } },
    requestAnimationFrame: requestFrame, cancelAnimationFrame: cancelFrame,
    setTimeout(callback, delay) { const id = ++nextFrameId; timers.set(id, { callback, delay }); return id },
    clearTimeout: id => timers.delete(id),
    ResizeObserver: class { constructor(callback) { this.callback = callback; observers.push(this) }
      observe(element) { this.element = element }
      disconnect() { this.disconnected = true }
    },
    IntersectionObserver: class { constructor(callback) { this.callback = callback; intersections.push(this) }
      observe(element) { this.element = element }
      disconnect() { this.disconnected = true }
    },
  }
  for (const [name, value] of Object.entries(globals)) Object.defineProperty(globalThis, name, { configurable: true, writable: true, value })
  const component = factory({ ...modules, '../utils/knowledgeGalaxyRenderer.js': { ...galaxyRenderer,
    createKnowledgeGalaxyRenderer(options) {
      const renderer = galaxyRenderer.createKnowledgeGalaxyRenderer(options), calls = { destroyed: false }
      renderers.push(calls)
      return { draw(layout, index, camera, view, drawOptions) {
        draws.push({ layout, index, camera: { ...camera }, view: { ...view }, options: { ...drawOptions } })
        return renderer.draw(layout, index, camera, view, drawOptions)
      }, destroy() { calls.destroyed = true; renderer.destroy() } }
    },
  } })
  component.render = render(modules)
  const renderer = vue.createRenderer({
    createElement: tag => ({ ...eventTarget(), ...(tag === 'canvas' ? canvas() : {}), tag, props: {}, children: [],
      get clientWidth() { return sizes.width }, get clientHeight() { return sizes.height },
      getBoundingClientRect() { return { left: 20, top: 30, width: sizes.width, height: sizes.height } },
      focus() { this.focused = true },
      setPointerCapture(id) { this.capturedPointer = id }, releasePointerCapture() { this.capturedPointer = null },
    }),
    createText: text => ({ text }), createComment: comment => ({ comment }),
    insert(child, parent, anchor) {
      if (child.parent) { const old = child.parent.children.indexOf(child); if (old >= 0) child.parent.children.splice(old, 1) }
      const index = anchor ? parent.children.indexOf(anchor) : -1
      parent.children.splice(index < 0 ? parent.children.length : index, 0, child); child.parent = parent
    },
    remove(child) { const index = child.parent?.children.indexOf(child); if (index >= 0) child.parent.children.splice(index, 1); child.parent = null },
    setText: (node, text) => { node.text = text }, setElementText: (node, text) => { node.text = text; node.children = [] },
    patchProp: (node, key, _old, value) => { node.props[key] = value }, parentNode: node => node.parent,
    nextSibling(node) { const index = node.parent?.children.indexOf(node); return node.parent?.children[index + 1] || null },
  })
  const app = renderer.createApp(component, { ...props, onExpand: id => events.push(id) })
  const host = { children: [] }
  app.mount(host)
  for (const observer of intersections) observer.callback([{ target: observer.element, isIntersecting: inView, intersectionRatio: inView ? 1 : 0 }])
  const descendants = () => {
    const queue = [...host.children], result = []
    for (let index = 0; index < queue.length; index++) { result.push(queue[index]); queue.push(...(queue[index].children || [])) }
    return result
  }
  const hasClass = (node, name) => typeof node.props?.class === 'string' && node.props.class.split(/\s+/).includes(name)
  const state = app._instance.setupState
  return { state, props: app._instance.props, exposed: app._instance.exposed, observers, intersections, events, document, media, frames, timers, contexts, draws, renderers,
    elements: descendants,
    motion: () => descendants().find(node => node.tag === 'section').props['data-motion'],
    get canvas() { return descendants().find(node => node.tag === 'canvas' && hasClass(node, 'galaxy-canvas')) },
    get scene() { return descendants().find(node => hasClass(node, 'galaxy-scene')) },
    button: label => descendants().find(node => node.tag === 'button' && (node.props['aria-label'] === label || node.text === label)),
    async tick() { await vue.nextTick() },
    async frame(delta = 16) {
      await vue.nextTick()
      time += delta
      const callbacks = [...frames.values()]; frames.clear()
      for (const callback of callbacks) callback(time)
      await vue.nextTick()
    },
    async settle() {
      const callbacks = [...timers.values()]; timers.clear()
      for (const { callback } of callbacks) callback()
      await vue.nextTick()
    },
    point(star = state.layout.nodes[0], overrides = {}) {
      return { button: 0, pointerId: 1, pointerType: 'mouse', clientX: 20 + star.x * state.camera.k + state.camera.x,
        clientY: 30 + star.y * state.camera.k + state.camera.y, preventDefault() {}, ...overrides }
    },
    async resize(nextWidth, nextHeight = height) {
      sizes.width = nextWidth; sizes.height = nextHeight
      for (const observer of observers) observer.callback()
      await vue.nextTick()
    },
    async setInView(value) {
      for (const observer of intersections) observer.callback([{ target: observer.element, isIntersecting: value, intersectionRatio: value ? 1 : 0 }])
      await vue.nextTick()
    },
    async setHidden(value) { document.hidden = value; document.visibilityState = value ? 'hidden' : 'visible'; document.dispatch('visibilitychange'); await vue.nextTick() },
    async setReducedMotion(value) { media.matches = value; media.dispatch('change', { matches: value }); await vue.nextTick() },
    cleanup() {
      app.unmount()
      for (const [name, descriptor] of previous) {
        if (descriptor) Object.defineProperty(globalThis, name, descriptor)
        else delete globalThis[name]
      }
    },
  }
}

const key = (value, extra = {}) => ({ key: value, preventDefault() {}, ...extra })

function expectGeometryUnchanged(h, layout, index) {
  assert.equal(h.state.layout, layout, 'camera and UI changes reuse the settled world coordinates')
  assert.equal(h.state.index, index, 'camera and UI changes reuse the world-space spatial index')
}

test('all 1257 communities share one canvas galaxy without pagination or thousands of focus targets', async () => {
  const data = fixture(5000, 1257), h = mountGalaxy(data)
  try {
    await h.frame()
    assert.equal(h.state.overview.totalNodes, 5000)
    assert.equal(h.state.overview.totalEdges, 5000)
    assert.equal(h.state.overview.totalCommunities, 1257)
    assert.equal(h.state.overview.nodes.length, 1257)
    assert.equal(h.state.layout.nodes.length, 1257)
    assert.equal(h.state.index.size, 1257)
    assert.equal(new Set(h.state.layout.nodes.map(node => node.communityId)).size, 1257)
    assert.equal(h.elements().find(node => node.tag === 'section').props['data-community-count'], 1257)
    assert.equal(h.elements().filter(node => node.tag === 'canvas').length, 4, 'one main drawing and three fixed decoration surfaces')
    assert.equal(h.elements().filter(node => node.props?.tabindex === '0').length, 1, 'one keyboard entry reaches every community')
    assert.equal(h.elements().filter(node => node.tag === 'button').length, 3, 'only fixed camera controls are mounted before selection')
    assert.equal(h.button('上一组社区'), undefined)
    assert.equal(h.button('下一组社区'), undefined)
    assert.equal(h.canvas.props.role, 'application')
    assert.ok(h.canvas.props['aria-label'].includes('方向键选择社区'))
    assert.ok(h.canvas.props['aria-describedby'])
    assert.equal(h.scene.props['data-visible-count'], 1257)
    assert.ok(h.scene.props['data-label-count'] <= 24)
    assert.equal(data.nodes.length, 5000)
  } finally { h.cleanup() }
})

test('zoom bursts schedule one draw, preserve geometry and cull offscreen communities', async () => {
  const h = mountGalaxy(fixture(5000, 1257))
  try {
    await h.frame()
    const layout = h.state.layout, index = h.state.index, initialDraws = h.draws.length
    const originalK = h.state.camera.k
    for (let step = 0; step < 16; step++) h.canvas.props.onWheel({ ...h.point(), clientX: 520, clientY: 290, deltaY: -100 })
    await h.tick()
    assert.equal(h.frames.size, 1, 'camera changes within a frame are batched')
    expectGeometryUnchanged(h, layout, index)
    assert.ok(h.state.camera.k > originalK)
    await h.frame()
    assert.equal(h.draws.length, initialDraws + 1)
    assert.ok(h.state.metrics.visibleCount > 0)
    assert.ok(h.state.metrics.visibleCount < 1257 / 2, 'the renderer queries the viewport rather than painting the whole galaxy when zoomed in')
    assert.ok(h.state.metrics.labelCount <= 1, 'gesture frames keep only the selected label')
    assert.equal(h.state.overview.totalCommunities, 1257, 'culling never truncates the underlying community map')
    await h.settle(); await h.frame()
    assert.equal(h.state.interacting, false)
    assert.ok(h.state.metrics.labelCount <= 24)
    assert.equal(h.draws.at(-1).layout, layout)
    assert.equal(h.draws.at(-1).index, index)
    h.button('查看全图').props.onClick(); await h.frame()
    assert.equal(h.state.metrics.visibleCount, 1257)
    assert.equal(h.state.camera.k, originalK)
  } finally { h.cleanup() }
})

test('pointer drag pans the camera without expanding a community and a normal tap opens its real ID', async () => {
  const h = mountGalaxy(fixture(500, 101))
  try {
    await h.frame()
    const layout = h.state.layout, index = h.state.index, initial = { ...h.state.camera }
    const start = h.point(), moved = { ...start, clientX: start.clientX + 86, clientY: start.clientY + 43 }
    h.canvas.props.onPointerdown(start)
    assert.equal(h.canvas.capturedPointer, 1)
    h.canvas.props.onPointermove({ ...moved, pointerId: 2 })
    assert.equal(h.state.camera.x, initial.x, 'unrelated pointers do not move the active gesture')
    h.canvas.props.onPointermove(moved)
    h.canvas.props.onPointerup(moved)
    await h.frame()
    assert.equal(h.state.camera.x, initial.x + 86)
    assert.equal(h.state.camera.y, initial.y + 43)
    assert.deepEqual(h.events, [], 'finishing a drag does not accidentally enter a community')
    assert.equal(h.canvas.capturedPointer, null)
    expectGeometryUnchanged(h, layout, index)
    const star = layout.nodes.find(node => node.communityId === 0), tap = h.point(star)
    h.canvas.props.onPointerdown(tap); h.canvas.props.onPointerup(tap)
    assert.deepEqual(h.events, [0])
    h.canvas.props.onPointerdown(tap); h.canvas.props.onPointercancel(tap)
    assert.deepEqual(h.events, [0], 'cancelled gestures never open a community')
  } finally { h.cleanup() }
})

test('touch picking keeps a 48px target even when visible nuclei are small', async () => {
  const h = mountGalaxy(fixture(100, 3), { width: 274, height: 420 })
  try {
    await h.frame()
    const star = h.state.layout.nodes[0]
    const point = h.point(star, { pointerType: 'touch' })
    point.clientX += 20
    h.canvas.props.onPointerdown(point); h.canvas.props.onPointerup(point)
    assert.deepEqual(h.events, [star.communityId])
  } finally { h.cleanup() }
})

test('one keyboard surface reaches all 1207 communities including the final item and community zero', async () => {
  const h = mountGalaxy(fixture(5000, 1207))
  try {
    await h.frame()
    const nodes = h.state.layout.nodes, reached = new Set()
    h.canvas.props.onKeydown(key('Home'))
    for (let step = 0; step < nodes.length; step++) {
      reached.add(h.state.focusedStar.communityId)
      h.canvas.props.onKeydown(key('ArrowRight'))
    }
    assert.equal(reached.size, 1207, 'navigation covers the complete map rather than just visible points')
    assert.equal(h.state.focusedStar.id, nodes.at(-1).id)
    h.canvas.props.onKeydown(key('Enter'))
    assert.deepEqual(h.events, [nodes.at(-1).communityId])
    h.canvas.props.onKeydown(key('Home'))
    h.canvas.props.onKeydown(key(' '))
    h.canvas.props.onKeydown(key('Enter', { repeat: true }))
    assert.deepEqual(h.events, [nodes.at(-1).communityId, nodes[0].communityId])
    h.exposed.focusCommunity(0)
    assert.equal(h.state.focusedStar.communityId, 0)
    h.canvas.props.onKeydown(key('End'))
    assert.equal(h.state.focusedStar.id, nodes.at(-1).id)
    h.canvas.props.onKeydown(key('ArrowLeft'))
    assert.equal(h.state.focusedStar.id, nodes.at(-2).id)
    h.canvas.props.onKeydown(key('Escape'))
    assert.equal(h.state.focusedId, '')
  } finally { h.cleanup() }
})

test('restoring a visible community retains the camera and restoring an offscreen community brings it into view', async () => {
  const h = mountGalaxy(fixture(5000, 1257))
  try {
    await h.frame()
    const layout = h.state.layout, index = h.state.index, original = { ...h.state.camera }
    h.exposed.focusCommunity(0)
    assert.deepEqual({ ...h.state.camera }, original, 'returning to a visible community preserves the explored camera')
    assert.equal(h.canvas.focused, true)
    h.state.camera.k = 4; h.state.camera.x = 0; h.state.camera.y = 0
    const last = layout.nodes.at(-1)
    h.exposed.focusCommunity(String(last.communityId))
    assert.equal(h.state.focusedStar, last)
    assert.equal(h.state.camera.k, 4, 'focus restores do not reset the zoom level')
    assert.equal(last.x * h.state.camera.k + h.state.camera.x, h.state.view.width / 2)
    assert.equal(last.y * h.state.camera.k + h.state.camera.y, h.state.view.height / 2)
    expectGeometryUnchanged(h, layout, index)
    h.canvas.props.onKeydown(key('0')); await h.frame()
    assert.deepEqual({ ...h.state.camera }, original)
    assert.equal(h.state.focusedId, '')
  } finally { h.cleanup() }
})

test('hover is frame-batched, reveals truthful selected counts and temporarily overrides keyboard focus', async () => {
  const h = mountGalaxy(fixture(500, 101))
  try {
    await h.frame()
    const nodes = h.state.layout.nodes
    h.exposed.focusCommunity(nodes[0].communityId); await h.frame()
    const decorationCommands = h.contexts.slice(1).map(context => context.calls.length)
    const keyboard = h.state.keyboardId, hovered = nodes[1]
    h.canvas.props.onPointermove(h.point(hovered))
    assert.equal(h.state.focusedId, keyboard, 'pointer events defer spatial picking to the scheduled frame')
    await h.frame()
    assert.equal(h.state.focusedStar.id, hovered.id)
    assert.equal(h.state.keyboardId, keyboard)
    assert.deepEqual(h.contexts.slice(1).map(context => context.calls.length), decorationCommands,
      'hovering reuses the existing decorative canvas surfaces')
    const incident = h.state.overview.edges.filter(edge => edge.source === hovered.id || edge.target === hovered.id)
    assert.deepEqual(h.state.adjacency.get(hovered.id), incident)
    assert.ok(h.elements().some(node => node.tag === 'strong' && node.text === hovered.title))
    assert.ok(h.elements().some(node => typeof node.text === 'string' && node.text.includes(`${hovered.count} 个概念`)))
    const context = h.contexts[0]
    assert.ok(context.calls.some(call => call.method === 'quadraticCurveTo'), 'focusing shows real incident community relationships')
    h.canvas.props.onPointerleave(); await h.frame()
    assert.equal(h.state.focusedId, keyboard)
    assert.equal(h.state.overview.totalNodes, 500)
    assert.equal(h.state.overview.totalEdges, 500)
  } finally { h.cleanup() }
})

test('only three noninteractive decorative layers animate and no redraw loop runs while idle', async () => {
  const h = mountGalaxy(fixture(5000, 1257))
  try {
    await h.frame()
    const layers = h.elements().filter(node => typeof node.props?.class === 'string' && node.props.class.includes('galaxy-motion-surface'))
    assert.equal(layers.length, 3)
    for (const layer of layers) {
      assert.equal(layer.props['aria-hidden'], 'true')
      assert.equal(layer.children.filter(node => node.tag === 'canvas').length, 1)
      for (const node of [layer, ...layer.children]) {
        assert.equal(node.props?.tabindex, undefined)
        assert.equal(node.props?.role, undefined)
        assert.equal(node.props?.onClick, undefined)
      }
    }
    assert.equal(h.frames.size, 0, 'CSS motion does not request a continuous graph rendering loop')
    assert.ok(h.state.metrics.decorationCount > 0 && h.state.metrics.decorationCount < 4000)
    const draws = h.draws.length
    await h.frame(1000)
    assert.equal(h.draws.length, draws)
    assert.equal(h.elements().filter(node => node.tag === 'button').length, 3)
  } finally { h.cleanup() }
})

test('inactive, offscreen, hidden and reduced-motion states pause effects without rebuilding world geometry', async () => {
  const h = mountGalaxy({ ...fixture(), active: false }, { reducedMotion: true, inView: false })
  try {
    await h.frame()
    const layout = h.state.layout, index = h.state.index, overview = h.state.overview
    const expectMotion = expected => {
      assert.equal(h.motion(), expected)
      expectGeometryUnchanged(h, layout, index)
      assert.equal(h.state.overview, overview)
      assert.equal(h.events.length, 0)
    }
    assert.equal(h.media.query, '(prefers-reduced-motion: reduce)')
    expectMotion('paused')
    assert.equal(h.draws.length, 0)
    await h.setInView(true); await h.setReducedMotion(false)
    expectMotion('paused')
    h.props.active = true; await h.tick()
    expectMotion('running')
    await h.frame()
    assert.equal(h.draws.length, 1)
    h.canvas.props.onWheel({ ...h.point(), deltaY: -20 }); await h.tick()
    assert.equal(h.frames.size, 1)
    await h.setInView(false)
    expectMotion('paused')
    assert.equal(h.frames.size, 0, 'leaving the viewport cancels pending work')
    assert.equal(h.timers.size, 0, 'a paused view releases the gesture timer')
    await h.setInView(true); expectMotion('running')
    await h.setHidden(true); expectMotion('paused')
    assert.equal(h.frames.size, 0)
    await h.setReducedMotion(true); await h.setHidden(false); expectMotion('paused')
    await h.frame()
    assert.ok(h.draws.length > 1, 'reduced motion still allows a static interactive graph')
    await h.setReducedMotion(false); expectMotion('running')
    h.props.active = false; await h.tick(); expectMotion('paused')
  } finally { h.cleanup() }
})

test('resizing preserves all communities and stable world geometry while ignoring hidden zero sizes', async () => {
  const h = mountGalaxy(fixture(5000, 1257), { width: 217, height: 420 })
  try {
    await h.frame()
    const layout = h.state.layout, index = h.state.index
    assert.equal(h.state.metrics.visibleCount, 1257)
    assert.equal(h.state.view.width, 217)
    assert.ok(h.state.metrics.labelCount <= 24)
    const narrowCamera = { ...h.state.camera }
    await h.resize(0, 0)
    assert.equal(h.state.view.width, 217)
    assert.equal(h.state.view.height, 420)
    assert.deepEqual({ ...h.state.camera }, narrowCamera)
    await h.resize(1000, 520); await h.frame()
    assert.equal(h.state.view.width, 1000)
    assert.equal(h.state.metrics.visibleCount, 1257)
    expectGeometryUnchanged(h, layout, index)
    h.canvas.props.onKeydown(key('+')); await h.frame()
    const before = { ...h.state.camera }
    await h.resize(600, 520)
    assert.equal(h.state.camera.k, before.k, 'a user zoom is retained across resize')
    assert.equal(h.state.camera.x, before.x - 200, 'resizing keeps the world center fixed')
    assert.equal(h.state.layout.nodes.length, 1257)
    expectGeometryUnchanged(h, layout, index)
  } finally { h.cleanup() }
})

test('filtering clears obsolete focus and empty data keeps fixed controls and an understandable empty state', async () => {
  const h = mountGalaxy(fixture(500, 101))
  try {
    await h.frame()
    h.exposed.focusCommunity(100)
    assert.equal(h.state.focusedStar.communityId, 100)
    h.props.nodes = h.props.nodes.slice(0, 2); h.props.edges = []
    await h.frame()
    assert.equal(h.state.layout.nodes.length, 2)
    assert.equal(h.state.focusedId, '')
    h.props.communities = {}; await h.frame()
    assert.equal(h.state.layout.nodes.length, 1)
    h.exposed.focusCommunity('__unassigned__')
    assert.equal(h.state.focusedStar.communityId, '__unassigned__')
    assert.ok([h.state.camera.x, h.state.camera.y, h.state.camera.k].every(Number.isFinite))
    h.props.nodes = []; await h.frame()
    assert.equal(h.state.layout.nodes.length, 0)
    assert.equal(h.state.metrics.visibleCount, 0)
    assert.equal(h.state.focusedId, '')
    assert.equal(h.button('上一组社区'), undefined)
    assert.equal(h.button('下一组社区'), undefined)
    assert.ok(h.elements().some(node => node.text === '当前筛选下没有知识社区'))
    assert.ok(h.button('查看全图'))
    assert.ok(h.canvas.props['aria-label'])
  } finally { h.cleanup() }
})

test('very large overviews aggregate only painting detail while retaining every community and accessible focus', async () => {
  const h = mountGalaxy(fixture(6000, 5001))
  try {
    await h.frame()
    assert.equal(h.state.layout.nodes.length, 5001)
    assert.equal(h.state.metrics.visibleCount, 5001)
    assert.equal(h.state.metrics.aggregated, true)
    assert.ok(h.state.metrics.paintedCount < 3000, 'screen-density aggregation bounds painting at overview zoom')
    assert.ok(h.state.metrics.labelCount <= 24)
    const last = h.state.layout.nodes.at(-1)
    h.exposed.focusCommunity(last.communityId); await h.frame()
    assert.equal(h.state.focusedStar, last)
    h.canvas.props.onKeydown(key('Enter'))
    assert.deepEqual(h.events, [last.communityId], 'a density-merged point remains independently selectable')
    assert.equal(h.state.index.size, 5001)
    assert.ok(h.elements().length < 50, 'a larger map does not create one DOM node per community')
  } finally { h.cleanup() }
})

test('unmount cancels frames and timers, removes listeners and releases canvas backing stores', async () => {
  const h = mountGalaxy(fixture(500, 101))
  await h.frame()
  const canvases = h.elements().filter(node => node.tag === 'canvas')
  h.canvas.props.onWheel({ ...h.point(), deltaY: -20 }); await h.tick()
  assert.equal(h.frames.size, 1)
  assert.equal(h.timers.size, 1)
  assert.equal(h.document.listeners.get('visibilitychange').size, 1)
  assert.equal(h.media.listeners.get('change').size, 1)
  h.cleanup()
  assert.equal(h.observers[0].disconnected, true)
  assert.equal(h.intersections[0].disconnected, true)
  assert.equal(h.document.listeners.size, 0)
  assert.equal(h.media.listeners.size, 0)
  assert.equal(h.frames.size, 0)
  assert.equal(h.timers.size, 0)
  assert.equal(h.renderers[0].destroyed, true)
  assert.ok(canvases.every(canvas => canvas.width === 1 && canvas.height === 1))
})
