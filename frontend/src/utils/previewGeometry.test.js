import test from 'node:test'
import assert from 'node:assert/strict'
import { createPreviewGeometryCache, activeHeadingIndex, mapSparseScroll } from './previewGeometry.js'

test('a thousand-block note is measured once across repeated scrolling, then remeasured on layout change', () => {
  let reads = 0
  const host = { clientWidth: 800, clientTop: 0, scrollTop: 0,
    getBoundingClientRect: () => ({ top: 0 }),
    querySelectorAll: selector => selector.includes('data-line') ? nodes : headings,
  }
  const nodes = Array.from({ length: 1000 }, (_, line) => ({ dataset: { line: String(line) },
    getBoundingClientRect: () => { reads++; return { top: line * 40 - host.scrollTop, height: 30 } },
  }))
  const headings = nodes.filter((_, i) => i % 10 === 0)
  const cache = createPreviewGeometryCache()
  for (let i = 0; i < 60; i++) {
    host.scrollTop = i * 100
    const layout = cache.read(host)
    assert.equal(activeHeadingIndex(layout.headings, host.scrollTop + 40), Math.floor((host.scrollTop + 40) / 400))
  }
  assert.equal(reads, 1000)
  cache.invalidate(); cache.read(host)
  assert.equal(reads, 2000)
  host.clientWidth = 500; cache.read(host)
  assert.equal(reads, 3000)
});

test('sparse synchronization measures logarithmically and keeps pixel interpolation and bounds', () => {
  const anchors = Array.from({ length: 4096 }, (_, i) => ({ line: i, preview: (i + 1) * 80 }))
  let reads = 0
  const source = point => { reads++; return (point.line + 1) * 40 }
  assert.equal(mapSparseScroll(anchors, 100020, 'source', source, 200000, 400000), 200040)
  assert.ok(reads <= 15, `read ${reads} source anchors`)
  reads = 0
  assert.equal(mapSparseScroll(anchors, 200040, 'preview', source, 200000, 400000), 100020)
  assert.ok(reads <= 2)
  assert.equal(mapSparseScroll(anchors, 200000, 'source', source, 200000, 400000), 400000)
  assert.equal(mapSparseScroll([], 0, 'source', source, 0, 0), null)
});

test('collapsed blocks and duplicate line metadata cannot break the layout map', () => {
  const nodes = [
    { dataset: { line: '0' }, getBoundingClientRect: () => ({ top: 20, height: 30 }) },
    { dataset: { line: '0' }, getBoundingClientRect: () => ({ top: 25, height: 30 }) },
    { dataset: { line: '2' }, getBoundingClientRect: () => ({ top: 0, height: 0 }) },
    { dataset: { line: '4' }, getBoundingClientRect: () => ({ top: 100, height: 30 }) },
  ]
  const host = { clientWidth: 600, clientTop: 0, scrollTop: 0,
    getBoundingClientRect: () => ({ top: 0 }), querySelectorAll: () => nodes,
  }
  assert.deepEqual(createPreviewGeometryCache().read(host).anchors, [{ line: 0, preview: 20 }, { line: 4, preview: 100 }])
});

test('different pane bottoms retain the preceding valid anchor', () => {
  const anchors = [{ line: 0, preview: 1000 }, { line: 1, preview: 2000 }]
  const source = p => [500, 1500][p.line]
  assert.equal(mapSparseScroll(anchors, 2500, 'preview', source, 1000, 3000), 875)
});

test('a shortened source never reads obsolete preview line anchors', () => {
  const anchors = Array.from({ length: 4096 }, (_, i) => ({ line: i, preview: (i + 1) * 80 }))
  let reads = 0
  const source = point => {
    assert.ok(point.line < 12, 'out-of-range source line')
    reads++
    return (point.line + 1) * 40
  }
  assert.equal(mapSparseScroll(anchors, 220, 'source', source, 1000, 2000, 12), 440)
  assert.ok(reads <= 6)
  assert.equal(mapSparseScroll(anchors, 900, 'preview', source, 1000, 2000, 0), 450)
});
