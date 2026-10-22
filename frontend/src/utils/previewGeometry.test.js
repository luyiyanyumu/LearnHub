import test from 'node:test'
import assert from 'node:assert/strict'
import { createPreviewGeometryCache, activeHeadingIndex, mapSparseScroll } from './previewGeometry.js'

function paneFixture() {
  const queries = [], reads = [0, 0, 0, 0]
  const host = { clientWidth: 800, clientTop: 3, scrollTop: 120, viewportTop: 80, contentOffset: 0,
    getBoundingClientRect: () => ({ top: host.viewportTop }),
    querySelectorAll: selector => {
      queries.push(selector)
      return selector.includes('data-line') ? nodes : [nodes[1], nodes[3]]
    },
  }
  const nodes = [0, 120, 360, 720].map((top, index) => ({ dataset: { line: String(index * 4) },
    getBoundingClientRect: () => {
      reads[index]++
      return { top: host.viewportTop + host.clientTop + top + host.contentOffset - host.scrollTop, height: 30 }
    },
  }))
  return { host, queries, reads }
}

test('heading-only scrolling never queries or measures line anchors', () => {
  const { host, queries, reads } = paneFixture()
  const cache = createPreviewGeometryCache()
  const headings = cache.readHeadings(host)
  assert.deepEqual(headings, [{ index: 0, top: 120 }, { index: 1, top: 720 }])
  for (let i = 0; i < 60; i++) {
    host.scrollTop = i * 20
    assert.strictEqual(cache.readHeadings(host), headings)
    assert.equal(activeHeadingIndex(headings, host.scrollTop), host.scrollTop >= 720 ? 1 : host.scrollTop >= 120 ? 0 : -1)
  }
  assert.equal(queries.length, 1)
  assert.ok(!queries[0].includes('data-line'))
  assert.deepEqual(reads, [0, 1, 0, 1])
  assert.deepEqual(cache.readHeadings(null), [])
})

test('adding anchors after scrolling reuses heading content coordinates without measuring them twice', () => {
  const { host, queries, reads } = paneFixture()
  const cache = createPreviewGeometryCache()
  const headings = cache.readHeadings(host)
  host.scrollTop = 600
  host.viewportTop = 45
  const geometry = cache.read(host)
  assert.strictEqual(geometry.headings, headings)
  assert.deepEqual(geometry.anchors, [
    { line: 0, preview: 0 }, { line: 4, preview: 120 },
    { line: 8, preview: 360 }, { line: 12, preview: 720 },
  ])
  assert.deepEqual(reads, [1, 1, 1, 1])
  assert.equal(queries.length, 2)
  host.scrollTop = 200
  assert.strictEqual(cache.read(host), geometry)
  assert.strictEqual(cache.readHeadings(host), headings)
  assert.deepEqual(reads, [1, 1, 1, 1])
})

test('complete reads share headings while width, host, and explicit invalidation clear both caches', () => {
  const first = paneFixture(), second = paneFixture()
  const cache = createPreviewGeometryCache()
  const original = cache.read(first.host)
  assert.strictEqual(cache.readHeadings(first.host), original.headings)
  assert.deepEqual(first.reads, [1, 1, 1, 1])

  first.host.clientWidth = 500
  first.host.contentOffset = 25
  const resizedHeadings = cache.readHeadings(first.host)
  assert.notStrictEqual(resizedHeadings, original.headings)
  assert.deepEqual(resizedHeadings, [{ index: 0, top: 145 }, { index: 1, top: 745 }])
  assert.deepEqual(first.reads, [1, 2, 1, 2])
  assert.strictEqual(cache.read(first.host).headings, resizedHeadings)
  assert.deepEqual(cache.read(first.host).anchors.map(point => point.preview), [25, 145, 385, 745])
  assert.deepEqual(first.reads, [2, 2, 2, 2])

  second.host.clientWidth = 500
  second.host.contentOffset = 70
  assert.deepEqual(cache.readHeadings(second.host), [{ index: 0, top: 190 }, { index: 1, top: 790 }])
  assert.deepEqual(cache.read(second.host).anchors.map(point => point.preview), [70, 190, 430, 790])
  assert.deepEqual(second.reads, [1, 1, 1, 1])
  cache.read(first.host)
  assert.deepEqual(first.reads, [3, 3, 3, 3])

  first.host.contentOffset = 90
  cache.invalidate()
  assert.deepEqual(cache.readHeadings(first.host), [{ index: 0, top: 210 }, { index: 1, top: 810 }])
  assert.deepEqual(cache.read(first.host).anchors.map(point => point.preview), [90, 210, 450, 810])
  assert.deepEqual(first.reads, [4, 4, 4, 4])
})

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
