import test from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import { compileScript, parse } from '@vue/compiler-sfc'
import { Schema } from '@tiptap/pm/model'
import { EditorState } from '@tiptap/pm/state'
import * as vue from 'vue'
import * as blockTarget from '../utils/blockTarget.js'

const source = readFileSync(new URL('./BlockActionMenu.vue', import.meta.url), 'utf8')
const compiled = compileScript(parse(source).descriptor, { id: 'block-action-scroll-test' }).content
const factory = new Function('modules', compiled
  .replace(/^import\s+([\s\S]*?)\s+from\s+['"]([^'"]+)['"]\s*;?\r?$/gm,
    (_, names, from) => `const ${names.trim().replace(/\bas\b/g, ':')} = modules[${JSON.stringify(from)}]`)
  .replace('export default', 'return'))

const schema = new Schema({ nodes: {
  doc: { content: 'paragraph+' }, paragraph: { content: 'text*', group: 'block' }, text: { group: 'inline' },
} })
const paragraph = text => schema.nodes.paragraph.create(null, schema.text(text))

const renderer = vue.createRenderer({
  createElement: tag => ({ tag, children: [] }), createText: text => ({ text }), createComment: text => ({ text }),
  insert: (node, parent) => parent.children.push(node), remove() {}, setText() {}, setElementText() {},
  patchProp() {}, parentNode: () => null, nextSibling: () => null,
})

function eventSurface() {
  const listeners = new Map()
  return {
    addEventListener(type, listener) {
      if (!listeners.has(type)) listeners.set(type, new Set())
      listeners.get(type).add(listener)
    },
    removeEventListener(type, listener) { listeners.get(type)?.delete(listener) },
    fire(type) { for (const listener of [...(listeners.get(type) || [])]) listener({ type }) },
    count(type) { return listeners.get(type)?.size || 0 },
  }
}

function harness() {
  const keys = ['window', 'document', 'requestAnimationFrame', 'cancelAnimationFrame', 'ResizeObserver']
  const previous = new Map(keys.map(key => [key, Object.getOwnPropertyDescriptor(globalThis, key)]))
  const windowMock = Object.assign(eventSurface(), { innerWidth: 1200, innerHeight: 800 })
  const documentMock = eventSurface(), scroller = eventSurface()
  const frames = new Map(), observers = [], measurements = [], chosen = []
  let frameId = 0, scrollTop = 0, disposed = false
  const host = vue.markRaw({
    closest: () => scroller,
    getBoundingClientRect() { measurements.push('host'); return { top: 100 - scrollTop, left: 20 } },
  })
  function blockDom(name, top = 160) {
    return vue.markRaw({ name, nodeType: 1, closest: () => null,
      getBoundingClientRect() { measurements.push(name); return { top: top - scrollTop, left: 40, width: 600, height: 50 } },
    })
  }
  let currentDom = blockDom('first DOM')
  const handlers = new Map()
  const editor = vue.markRaw({
    isDestroyed: false,
    state: EditorState.create({ schema, doc: schema.nodes.doc.create(null, paragraph('合成段落')) }),
    view: { nodeDOM: () => currentDom, domAtPos: () => ({ node: currentDom }) },
    on(type, handler) { if (!handlers.has(type)) handlers.set(type, new Set()); handlers.get(type).add(handler) },
    off(type, handler) { handlers.get(type)?.delete(handler) },
    emit(type, value) { for (const handler of [...(handlers.get(type) || [])]) handler(value) },
  })
  const mocks = {
    window: windowMock, document: documentMock,
    requestAnimationFrame: callback => { frames.set(++frameId, callback); return frameId },
    cancelAnimationFrame: id => frames.delete(id),
    ResizeObserver: class {
      constructor(callback) { this.callback = callback; this.nodes = []; this.disconnected = false; observers.push(this) }
      observe(node) { this.nodes.push(node) }
      disconnect() { this.disconnected = true; this.nodes = [] }
      fire() { if (!this.disconnected) this.callback([]) }
    },
  }
  for (const [key, value] of Object.entries(mocks)) Object.defineProperty(globalThis, key, { value, writable: true, configurable: true })
  const component = factory({
    vue, 'element-plus': { ElMessage: { info() {}, warning() {}, success() {} } },
    '../utils/blockActions': { findActionBlock: (_view, dom) => ({ pos: 0, dom }) },
    '../utils/blockMeta': {}, '../utils/htmlToMd': {},
    '../utils/blockTarget': { ...blockTarget, createBlockTarget(doc, pos) {
      const target = blockTarget.createBlockTarget(doc, pos)
      chosen.push(target)
      return target
    } },
  })
  component.render = () => null
  const app = renderer.createApp(component, { editor, host })
  app.mount({ children: [] })
  const state = app._instance.setupState
  return {
    state, editor, host, scroller, windowMock, documentMock, observers, measurements, chosen, frames, handlers,
    hover() { state.hover({ target: currentDom }) },
    scroll(value) { scrollTop = value; scroller.fire('scroll') },
    newDom() { currentDom = blockDom('replacement DOM', 190); return currentDom },
    changeDoc() {
      const transaction = editor.state.tr.insertText('修改', 1)
      editor.state = editor.state.apply(transaction)
      editor.emit('transaction', { transaction })
      return transaction
    },
    async frame() {
      const callbacks = [...frames.values()]
      frames.clear()
      for (const callback of callbacks) callback(0)
      await vue.nextTick()
    },
    unmount() { if (!disposed) { app.unmount(); disposed = true } },
    cleanup() {
      this.unmount()
      for (const [key, descriptor] of previous) {
        if (descriptor) Object.defineProperty(globalThis, key, descriptor)
        else delete globalThis[key]
      }
    },
  }
}

test('same-block pointer movement keeps its mapped target and does not schedule repeated geometry', async () => {
  const h = harness()
  try {
    h.hover()
    await h.frame()
    const target = h.state.active
    assert.deepEqual(h.measurements, ['first DOM', 'host'])
    for (let i = 0; i < 100; i++) h.hover()
    assert.equal(h.state.active, target)
    assert.equal(h.frames.size, 0)
    assert.deepEqual(h.measurements, ['first DOM', 'host'])
  } finally { h.cleanup() }
})

test('scrolling closes the menu while content coordinates follow the host without extra layout reads', async () => {
  const h = harness()
  try {
    h.hover()
    await h.frame()
    const box = { ...h.state.box }
    h.state.open = true
    h.state.submenu = 'convert'
    for (let i = 0; i < 60; i++) h.scroll(i * 100)
    await h.frame()
    assert.equal(h.state.open, false)
    assert.equal(h.state.submenu, '')
    assert.deepEqual({ ...h.state.box }, box)
    assert.deepEqual(h.measurements, ['first DOM', 'host'])
    assert.equal(h.frames.size, 0)
  } finally { h.cleanup() }
})

test('window resize and host layout changes remeasure once per frame', async () => {
  const h = harness()
  try {
    h.hover()
    await h.frame()
    assert.deepEqual(h.observers[0].nodes, [h.host])
    h.windowMock.fire('resize')
    h.observers[0].fire()
    h.observers[0].fire()
    assert.equal(h.frames.size, 1)
    await h.frame()
    assert.deepEqual(h.measurements, ['first DOM', 'host', 'first DOM', 'host'])
  } finally { h.cleanup() }
})

test('document edits keep their old async snapshot until hover selects the current node, and replacement DOM is located', async () => {
  const h = harness()
  try {
    h.hover()
    await h.frame()
    const initialTarget = h.state.active, initialNode = vue.toRaw(initialTarget.node)
    const replacement = h.newDom()
    h.changeDoc()
    assert.equal(vue.toRaw(h.state.active.node), initialNode)
    assert.equal(h.frames.size, 1)
    await h.frame()
    assert.equal(h.state.active.dom, replacement)
    assert.deepEqual(h.measurements.slice(-2), ['replacement DOM', 'host'])
    h.hover()
    assert.notEqual(h.state.active, initialTarget)
    assert.equal(vue.toRaw(h.state.active.node), h.editor.state.doc.nodeAt(0))
    assert.notEqual(vue.toRaw(h.state.active.node), initialNode)
    await h.frame()
    const afterEdit = h.state.active
    h.newDom()
    h.hover()
    assert.notEqual(h.state.active, afterEdit)
    assert.equal(h.frames.size, 1)
    await h.frame()
  } finally { h.cleanup() }
})

test('unmount disconnects all handlers and cancels queued measurements', async () => {
  const h = harness()
  try {
    h.hover()
    assert.equal(h.frames.size, 1)
    assert.equal(h.scroller.count('scroll'), 1)
    assert.equal(h.windowMock.count('resize'), 1)
    assert.equal(h.documentMock.count('pointerdown'), 1)
    h.unmount()
    assert.equal(h.frames.size, 0)
    assert.equal(h.scroller.count('scroll'), 0)
    assert.equal(h.windowMock.count('resize'), 0)
    assert.equal(h.documentMock.count('pointerdown'), 0)
    assert.ok(h.observers[0].disconnected)
    for (const listeners of h.handlers.values()) assert.equal(listeners.size, 0)
    h.scroller.fire('scroll')
    h.windowMock.fire('resize')
    h.observers[0].fire()
    await h.frame()
    assert.deepEqual(h.measurements, [])
  } finally { h.cleanup() }
})
