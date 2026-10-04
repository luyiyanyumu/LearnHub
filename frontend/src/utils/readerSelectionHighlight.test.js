import test from 'node:test'
import assert from 'node:assert/strict'
import domino from '@mixmark-io/domino'
import { createReaderSelectionHighlight, READER_HIGHLIGHT_NAME } from './readerSelectionHighlight.js'

// Domino supplies real document nodes and selectors but no DOM Range. This
// adapter models text offsets and cloning; browser QA covers native painting.
function textNodes(root) {
  if (root.nodeType === 3) return [root]
  return Array.from(root.childNodes).flatMap(textNodes)
}
function beforeNode(node) {
  let offset = 0
  for (let current = node; current.parentNode; current = current.parentNode) {
    for (let sibling = current.previousSibling; sibling; sibling = sibling.previousSibling) offset += sibling.textContent.length
  }
  return offset
}
function boundaryOffset(node, offset) {
  return beforeNode(node) + (node.nodeType === 3
    ? offset
    : Array.from(node.childNodes).slice(0, offset).reduce((sum, child) => sum + child.textContent.length, 0))
}
class TextRange {
  constructor(document) { this.document = document }
  setStart(node, offset) { this.startContainer = node; this.startOffset = offset }
  setEnd(node, offset) { this.endContainer = node; this.endOffset = offset }
  get start() { return boundaryOffset(this.startContainer, this.startOffset) }
  get end() { return boundaryOffset(this.endContainer, this.endOffset) }
  get collapsed() { return this.start === this.end }
  getClientRects() {
    if (this.startContainer.testRects) return this.startContainer.testRects()
    const left = 10 + this.startOffset * 5
    return [{ left, top: 10, right: left + (this.endOffset - this.startOffset) * 5, bottom: 30 }]
  }
  get commonAncestorContainer() {
    const parents = new Set()
    for (let node = this.startContainer; node; node = node.parentNode) parents.add(node)
    for (let node = this.endContainer; node; node = node.parentNode) if (parents.has(node)) return node
  }
  intersectsNode(node) {
    const start = beforeNode(node)
    return this.start < start + node.textContent.length && this.end > start
  }
  toString() {
    return textNodes(this.commonAncestorContainer).map(node => {
      const start = beforeNode(node)
      return node.textContent.slice(Math.max(0, this.start - start), Math.max(0, Math.min(node.length, this.end - start)))
    }).join('')
  }
  cloneContents() {
    const fragment = this.document.createDocumentFragment()
    const cloneSelected = (node) => {
      if (!this.intersectsNode(node)) return null
      if (node.nodeType === 3) {
        const start = beforeNode(node)
        return this.document.createTextNode(node.textContent.slice(Math.max(0, this.start - start), Math.min(node.length, this.end - start)))
      }
      const clone = node.cloneNode(false)
      for (const child of node.childNodes) {
        const selected = cloneSelected(child)
        if (selected) clone.appendChild(selected)
      }
      return clone
    }
    const ancestor = this.commonAncestorContainer
    for (const node of ancestor.nodeType === 3 ? [ancestor] : ancestor.childNodes) {
      const selected = cloneSelected(node)
      if (selected) fragment.appendChild(selected)
    }
    return fragment
  }
}
function fixture(html) {
  const document = domino.createDocument(html)
  document.createRange = () => new TextRange(document)
  const observers = []
  const resizeObservers = []
  const frames = new Map()
  const listeners = new Map()
  let nextFrame = 1
  const bounds = { left: 0, top: 0, right: 400, bottom: 200, width: 400, height: 200 }
  for (const element of document.querySelectorAll('*')) element.getBoundingClientRect = () => bounds
  const view = {
    CSS: { highlights: new Map() },
    Highlight: class extends Set {},
    MutationObserver: class {
      constructor(callback) { this.callback = callback; observers.push(this) }
      observe(root, options) { this.root = root; this.options = options; this.connected = true }
      disconnect() { this.connected = false }
    },
    ResizeObserver: class {
      constructor(callback) { this.callback = callback; resizeObservers.push(this) }
      observe(root) { this.root = root; this.connected = true }
      disconnect() { this.connected = false }
    },
    getComputedStyle: element => ({ overflowX: element.style.overflowX || 'visible', overflowY: element.style.overflowY || 'visible' }),
    requestAnimationFrame: callback => { const id = nextFrame++; frames.set(id, callback); return id },
    cancelAnimationFrame: id => frames.delete(id),
    addEventListener: (type, callback) => {
      if (!listeners.has(type)) listeners.set(type, new Set())
      listeners.get(type).add(callback)
    },
    removeEventListener: (type, callback) => listeners.get(type)?.delete(callback),
  }
  const range = (start, startOffset, end = start, endOffset = start.length) => {
    const value = document.createRange()
    value.setStart(start, startOffset)
    value.setEnd(end, endOffset)
    return value
  }
  const select = (...ranges) => ({
    isCollapsed: ranges.every(range => range.collapsed),
    rangeCount: ranges.length,
    getRangeAt: index => ranges[index],
    toString: () => ranges.map(String).join(''),
  })
  return {
    document, view, range, select, observers, resizeObservers,
    root: document.body,
    registered: () => view.CSS.highlights.get(READER_HIGHLIGHT_NAME),
    flushMutations: () => observers.filter(observer => observer.connected).forEach(observer => observer.callback([])),
    flushFrames: () => {
      const queued = Array.from(frames.values())
      frames.clear()
      queued.forEach(callback => callback())
    },
    pendingFrames: () => frames.size,
    resize: () => listeners.get('resize')?.forEach(callback => callback()),
    scroll: () => {
      const event = document.createEvent('Event')
      event.initEvent('scroll', false, false)
      document.dispatchEvent(event)
    },
  }
}
const markedText = highlight => Array.from(highlight || []).map(String)

test('marks only the selected characters and keeps the original DOM unchanged', () => {
  const f = fixture('<p>prefix structure suffix</p>')
  const source = f.root.querySelector('p').firstChild
  const original = f.root.innerHTML
  const marker = createReaderSelectionHighlight(f.view)
  marker.set(f.select(f.range(source, 7, source, 16)), f.root)
  assert.deepEqual(markedText(f.registered()), ['structure'])
  const [marked] = f.registered()
  assert.equal(marked.startOffset, 7)
  assert.equal(marked.endOffset, 16)
  assert.equal(marked.startContainer, source)
  assert.equal(f.root.innerHTML, original)
  marker.clear()
  assert.equal(f.registered(), undefined)
  assert.equal(f.observers[0].connected, false)
})

test('preserves exact boundaries across formatted inline text nodes', () => {
  const f = fixture('<p>One <b>bold</b> and <span>colored</span> ending</p>')
  const nodes = textNodes(f.root.querySelector('p'))
  const original = f.root.innerHTML
  const marker = createReaderSelectionHighlight(f.view)
  marker.set(f.select(f.range(nodes[0], 1, nodes[3], 3)), f.root)
  assert.deepEqual(markedText(f.registered()), ['ne ', 'bold', ' and ', 'col'])
  assert.equal(f.root.innerHTML, original)
})

test('a multi-paragraph selection excludes buttons, page labels, and inline translations', () => {
  const f = fixture('<p>First <button>Translate</button> tail</p><span class="pdf-page-no">1 / 3</span><div class="doc-trans">Existing translation</div><p>Second <em>text</em></p>')
  const first = f.root.querySelector('p').firstChild
  const last = f.root.querySelector('em').firstChild
  const original = f.root.innerHTML
  const marker = createReaderSelectionHighlight(f.view)
  marker.set(f.select(f.range(first, 0, last, 2)), f.root)
  assert.deepEqual(markedText(f.registered()), ['First ', ' tail', 'Second ', 'te'])
  assert.equal(f.root.innerHTML, original)
})

test('a replacement selection removes the previous highlight', () => {
  const f = fixture('<p>first second</p>')
  const source = f.root.querySelector('p').firstChild
  const marker = createReaderSelectionHighlight(f.view)
  marker.set(f.select(f.range(source, 0, source, 5)), f.root)
  marker.set(f.select(f.range(source, 6, source, 12)), f.root)
  assert.deepEqual(markedText(f.registered()), ['second'])
  assert.equal(f.observers[0].connected, false)
})

test('clearing one reader preserves another reader and supports reuse after all readers clear', () => {
  const f = fixture('<article id="one">First reader</article><article id="two">Second reader</article>')
  const oneRoot = f.root.querySelector('#one')
  const twoRoot = f.root.querySelector('#two')
  const one = createReaderSelectionHighlight(f.view)
  const two = createReaderSelectionHighlight(f.view)
  one.set(f.select(f.range(oneRoot.firstChild, 0)), oneRoot)
  two.set(f.select(f.range(twoRoot.firstChild, 0)), twoRoot)
  assert.deepEqual(markedText(f.registered()), ['First reader', 'Second reader'])
  one.clear()
  one.clear()
  assert.deepEqual(markedText(f.registered()), ['Second reader'])
  two.clear()
  assert.equal(f.registered(), undefined)
  one.set(f.select(f.range(oneRoot.firstChild, 0, oneRoot.firstChild, 5)), oneRoot)
  assert.deepEqual(markedText(f.registered()), ['First'])
})

test('PDF text-layer replacement clears its mark without clearing another reader', () => {
  const f = fixture('<div class="textLayer"><span>PDF word</span></div><article>Word content</article>')
  const pdfRoot = f.root.querySelector('.textLayer')
  const wordRoot = f.root.querySelector('article')
  const pdf = createReaderSelectionHighlight(f.view)
  const word = createReaderSelectionHighlight(f.view)
  pdf.set(f.select(f.range(pdfRoot.firstChild.firstChild, 0)), pdfRoot)
  word.set(f.select(f.range(wordRoot.firstChild, 0)), wordRoot)
  pdfRoot.innerHTML = '<span>New PDF page</span>'
  f.flushMutations()
  assert.deepEqual(markedText(f.registered()), ['Word content'])
  assert.equal(f.observers[0].connected, false)
  assert.equal(f.observers[1].connected, true)
})

test('unrelated document changes retain the mark, but selected text edits clear it', () => {
  const f = fixture('<p>Original sentence</p>')
  const source = f.root.querySelector('p').firstChild
  const marker = createReaderSelectionHighlight(f.view)
  marker.set(f.select(f.range(source, 0, source, 8)), f.root)
  f.root.appendChild(f.document.createElement('p'))
  f.flushMutations()
  assert.deepEqual(markedText(f.registered()), ['Original'])
  source.textContent = 'Replaced sentence'
  f.flushMutations()
  assert.equal(f.registered(), undefined)
})

test('rejects source-external endpoints, control selections, and collapsed selections', () => {
  const f = fixture('<article><p>Original</p><button>Translate</button></article><aside>Outside</aside>')
  const root = f.root.querySelector('article')
  const source = root.querySelector('p').firstChild
  const outside = f.root.querySelector('aside').firstChild
  const button = root.querySelector('button').firstChild
  const marker = createReaderSelectionHighlight(f.view)
  for (const selected of [f.range(source, 0, outside, 7), f.range(button, 0), f.range(source, 2, source, 2)]) {
    marker.set(f.select(selected), root)
    assert.equal(f.registered(), undefined)
  }
})

test('missing highlight APIs use clipped overlay rectangles without changing document text', () => {
  const f = fixture('<section style="overflow-y: auto"><p>Original document</p></section>')
  delete f.view.CSS.highlights
  delete f.view.Highlight
  const source = f.root.querySelector('p').firstChild
  source.testRects = () => [
    { left: -10, top: 10, right: 100, bottom: 40 },
    { left: 15, top: 60, right: 180, bottom: 100 },
    { left: 15, top: 120, right: 180, bottom: 130 },
    { left: 10, top: 25, right: 10, bottom: 30 },
  ]
  f.root.querySelector('section').getBoundingClientRect = () => ({ left: 0, top: 20, right: 400, bottom: 80 })
  const selection = f.select(f.range(source, 0))
  const original = f.root.innerHTML
  const painted = []
  const marker = createReaderSelectionHighlight(f.view, rectangles => painted.push(rectangles))
  marker.set(selection, f.root)
  assert.deepEqual(painted.at(-1), [
    { left: 0, top: 20, width: 100, height: 20 },
    { left: 15, top: 60, width: 165, height: 20 },
  ])
  assert.equal(f.root.innerHTML, original)
  marker.clear()
  assert.deepEqual(painted.at(-1), [])
})

test('overlay fallback follows captured scroll and resize with one scheduled repaint, and clears pending work', () => {
  const f = fixture('<p>Original document</p>')
  delete f.view.Highlight
  const source = f.root.querySelector('p').firstChild
  let currentRect = { left: 20, top: 30, right: 50, bottom: 50 }
  source.testRects = () => [currentRect]
  const painted = []
  const marker = createReaderSelectionHighlight(f.view, rectangles => painted.push(rectangles))
  marker.set(f.select(f.range(source, 0)), f.root)
  assert.deepEqual(painted.at(-1), [{ left: 20, top: 30, width: 30, height: 20 }])
  currentRect = { left: 20, top: 10, right: 50, bottom: 30 }
  f.scroll()
  f.resize()
  f.resizeObservers[0].callback()
  assert.equal(f.pendingFrames(), 1)
  f.flushFrames()
  assert.deepEqual(painted.at(-1), [{ left: 20, top: 10, width: 30, height: 20 }])
  f.scroll()
  assert.equal(f.pendingFrames(), 1)
  marker.clear()
  assert.equal(f.pendingFrames(), 0)
  assert.equal(f.resizeObservers[0].connected, false)
  const paintCount = painted.length
  f.scroll()
  f.resize()
  f.flushFrames()
  assert.equal(painted.length, paintCount)
  assert.deepEqual(painted.at(-1), [])
})

test('overlay fallback clears rectangles when a PDF text layer replaces its selected nodes', () => {
  const f = fixture('<div class="textLayer"><span>PDF original</span></div>')
  delete f.view.Highlight
  const painted = []
  const marker = createReaderSelectionHighlight(f.view, rectangles => painted.push(rectangles))
  marker.set(f.select(f.range(f.root.querySelector('span').firstChild, 0)), f.root)
  assert.equal(painted.at(-1).length, 1)
  f.root.querySelector('.textLayer').innerHTML = '<span>New page</span>'
  f.flushMutations()
  assert.deepEqual(painted.at(-1), [])
  assert.equal(f.resizeObservers[0].connected, false)
})

test('valid document mutations repaint an overlay when the selected text shifts position', () => {
  const f = fixture('<p>Original document</p>')
  delete f.view.Highlight
  const source = f.root.querySelector('p').firstChild
  let currentRect = { left: 10, top: 10, right: 80, bottom: 30 }
  source.testRects = () => [currentRect]
  const painted = []
  const marker = createReaderSelectionHighlight(f.view, rectangles => painted.push(rectangles))
  marker.set(f.select(f.range(source, 0)), f.root)
  assert.deepEqual(painted.at(-1), [{ left: 10, top: 10, width: 70, height: 20 }])
  f.root.insertBefore(f.document.createElement('p'), f.root.firstChild)
  currentRect = { left: 10, top: 50, right: 80, bottom: 70 }
  f.flushMutations()
  assert.equal(f.pendingFrames(), 1)
  f.flushFrames()
  assert.deepEqual(painted.at(-1), [{ left: 10, top: 50, width: 70, height: 20 }])
  assert.equal(marker.size, 1)
  assert.equal(f.observers[0].connected, true)
})
