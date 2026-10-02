import assert from 'node:assert/strict'
import { test } from 'node:test'
import { boundScrollAnchors, mapScrollPosition, normalizeScrollAnchors } from './scrollAnchors.js'

test('wrapped source paragraphs use their measured height before a heading', () => {
  const anchors = [
    { line: 100, source: 2000, preview: 3000 },
    { line: 129, source: 4200, preview: 5500 },
    { line: 133, source: 4360, preview: 5650 },
  ]
  // The preceding paragraph wraps to 100px despite occupying one logical line.
  assert.equal(mapScrollPosition(anchors, 4320), 5612.5)
  assert.equal(mapScrollPosition(anchors, 4360), 5650)
  assert.equal(mapScrollPosition(anchors, 5650, 'preview'), 4360)
  assert.equal(mapScrollPosition(anchors, 5612.5, 'preview'), 4320)
})

test('duplicate and backwards nested anchors cannot break binary search', () => {
  const anchors = normalizeScrollAnchors([
    { line: 8, source: 160, preview: 210 },
    { line: 2, source: 40, preview: 90 },
    { line: 2, source: 40, preview: 100 },
    { line: 4, source: 80, preview: 70 },
    { line: 6, source: 120, preview: 90 },
    { line: NaN, source: 140, preview: 200 },
  ])
  assert.deepEqual(anchors.map(a => a.line), [2, 6, 8])
  assert.equal(mapScrollPosition(anchors, 150, 'preview'), 140)
})

test('positions outside the anchor range remain finite and empty maps fall back', () => {
  const anchors = [{ line: 0, source: 4, preview: 24 }]
  assert.equal(mapScrollPosition(anchors, 0), 20)
  assert.equal(mapScrollPosition(anchors, 44), 64)
  assert.equal(mapScrollPosition([], 0), null)
})

test('the final scroll pixel maps continuously to the other pane bottom', () => {
  const points = boundScrollAnchors([
    { source: 1000, preview: 2000 },
    { source: 1800, preview: 3700 },
  ], 1500, 3500)
  assert.equal(mapScrollPosition(points, 1499), 3497)
  assert.equal(mapScrollPosition(points, 1500), 3500)
  assert.equal(mapScrollPosition(points, 3500, 'preview'), 1500)
  assert.equal(mapScrollPosition(points, 0), 0)
})
