import assert from 'node:assert/strict'
import test from 'node:test'
import { MERMAID_PREVIEW_MAX_HEIGHT, mermaidViewportSize, mermaidReadableSize } from './mermaidViewport.js'

const close = (actual, expected) => assert.ok(Math.abs(actual - expected) < 1e-8, `${actual} differs from ${expected}`)

test('a tall flowchart fits the complete SVG within the inline height budget', () => {
  const result = mermaidViewportSize({ width: 800, height: 2400 }, 760)
  close(result.height, MERMAID_PREVIEW_MAX_HEIGHT)
  close(result.width, 800 * MERMAID_PREVIEW_MAX_HEIGHT / 2400)
  close(result.width / result.height, 800 / 2400)
})

test('a wide diagram fits the available width without imposing a minimum height', () => {
  assert.deepEqual(mermaidViewportSize({ width: 2400, height: 300 }, 720), { width: 720, height: 90 })
})

test('narrow screens and subpixel sizes keep the full diagram and its aspect ratio', () => {
  assert.deepEqual(mermaidViewportSize({ width: 640, height: 480 }, 280), { width: 280, height: 210 })
  const result = mermaidViewportSize({ width: 640, height: 480 }, 0.5)
  assert.deepEqual(result, { width: 0.5, height: 0.375 })
})

test('small diagrams retain their intrinsic size at fit zoom', () => {
  assert.deepEqual(mermaidViewportSize({ width: 120, height: 80 }, 900), { width: 120, height: 80 })
})

test('dialog height budgets fit tall diagrams while still respecting their available width', () => {
  assert.deepEqual(mermaidViewportSize({ width: 400, height: 1200 }, 800, 1, { maxHeight: 560 }),
    { width: 400 * (560 / 1200), height: 560 })
  assert.deepEqual(mermaidViewportSize({ width: 400, height: 1200 }, 100, 1, { maxHeight: 560 }),
    { width: 100, height: 300 })
  const shortDialog = mermaidViewportSize({ width: 400, height: 1200 }, 800, 1, { maxHeight: 300 })
  assert.deepEqual(shortDialog, { width: 100, height: 300 })
})

test('zoom enlarges both fitted dimensions together so a scrollable frame never crops the SVG', () => {
  const size = { width: 600, height: 1800 }
  const fitted = mermaidViewportSize(size, 480)
  for (const zoom of [0.5, 0.75, 1, 1.25, 2]) {
    const result = mermaidViewportSize(size, 480, zoom)
    close(result.width, fitted.width * zoom)
    close(result.height, fitted.height * zoom)
    close(result.width / result.height, size.width / size.height)
  }
  assert.ok(mermaidViewportSize(size, 480, 2).height > MERMAID_PREVIEW_MAX_HEIGHT)
})

test('finite zoom values are limited to 50–200 percent and invalid values restore fit', () => {
  const size = { width: 100, height: 50 }
  for (const zoom of [-100, 0, 0.01, 0.5]) assert.deepEqual(mermaidViewportSize(size, 900, zoom), { width: 50, height: 25 })
  for (const zoom of [2, 3, Number.MAX_VALUE]) assert.deepEqual(mermaidViewportSize(size, 900, zoom), { width: 200, height: 100 })
  for (const zoom of [NaN, Infinity, -Infinity, null, '2']) assert.deepEqual(mermaidViewportSize(size, 900, zoom), size)
})

test('invalid or incomplete intrinsic sizes use a consistent 640 by 360 fallback', () => {
  for (const size of [undefined, null, {}, { width: 120 }, { width: 0, height: 100 },
    { width: 100, height: -1 }, { width: Infinity, height: 100 }, { width: 100, height: NaN },
    { width: '120', height: 80 }]) {
    assert.deepEqual(mermaidViewportSize(size, 320), { width: 320, height: 180 })
  }
})

test('invalid width and height budgets fall back without introducing Infinity or NaN', () => {
  const size = { width: 1280, height: 720 }
  for (const availableWidth of [undefined, null, 0, -10, NaN, Infinity, '800']) {
    assert.deepEqual(mermaidViewportSize(size, availableWidth), { width: 640, height: 360 })
  }
  for (const maxHeight of [undefined, null, 0, -10, NaN, Infinity, '560']) {
    const result = mermaidViewportSize({ width: 600, height: 1800 }, 900, 1, { maxHeight })
    close(result.height, MERMAID_PREVIEW_MAX_HEIGHT)
    close(result.width / result.height, 1 / 3)
  }
  assert.deepEqual(mermaidViewportSize(size, 320, 1, null), { width: 320, height: 180 })
})

test('readable preview preserves 75 percent scale for long vertical diagrams', () => {
  const size = { width: 240, height: 3000 }
  const result = mermaidReadableSize(size, 700)
  assert.deepEqual(result, { width: 180, height: 2250 })
  close(result.width / result.height, size.width / size.height)
  assert.ok(result.height > MERMAID_PREVIEW_MAX_HEIGHT, 'the outer viewport scrolls the complete SVG')
  assert.ok(mermaidViewportSize(size, 700).height <= MERMAID_PREVIEW_MAX_HEIGHT, 'fit overview remains available separately')
})

test('readable preview allows wide graphs to scroll instead of shrinking text excessively', () => {
  assert.deepEqual(mermaidReadableSize({ width: 3000, height: 240 }, 700), { width: 2250, height: 180 })
  assert.deepEqual(mermaidReadableSize({ width: 600, height: 400 }, 280), { width: 450, height: 300 })
})

test('readable preview retains fit for regular diagrams and does not enlarge small diagrams', () => {
  assert.deepEqual(mermaidReadableSize({ width: 600, height: 600 }, 700), mermaidViewportSize({ width: 600, height: 600 }, 700))
  assert.deepEqual(mermaidReadableSize({ width: 120, height: 80 }, 700), { width: 120, height: 80 })
  assert.deepEqual(mermaidReadableSize({ width: 100, height: 600 }, 60), { width: 60, height: 360 }, '60 percent fit remains readable')
  assert.deepEqual(mermaidReadableSize({ width: 100, height: 600 }, 59), { width: 75, height: 450 }, 'smaller fit uses scrolling')
})

test('readable preview applies proportional zoom after choosing its readable scale', () => {
  const size = { width: 240, height: 3000 }
  const readable = mermaidReadableSize(size, 700)
  for (const zoom of [0.5, 0.75, 1, 1.25, 2]) {
    const result = mermaidReadableSize(size, 700, zoom)
    close(result.width, readable.width * zoom)
    close(result.height, readable.height * zoom)
    close(result.width / result.height, size.width / size.height)
  }
})

test('readable preview shares the safe intrinsic, width and zoom fallbacks', () => {
  for (const size of [undefined, null, {}, { width: 0, height: 100 }, { width: 100, height: NaN }]) {
    assert.deepEqual(mermaidReadableSize(size, 700), { width: 640, height: 360 })
  }
  for (const width of [undefined, null, 0, -1, NaN, Infinity, '700']) {
    assert.deepEqual(mermaidReadableSize({ width: 640, height: 360 }, width), { width: 640, height: 360 })
  }
  const size = { width: 240, height: 3000 }
  for (const zoom of [NaN, Infinity, null, '2']) assert.deepEqual(mermaidReadableSize(size, 700, zoom), { width: 180, height: 2250 })
  assert.deepEqual(mermaidReadableSize(size, 700, -1), { width: 90, height: 1125 })
  assert.deepEqual(mermaidReadableSize(size, 700, 99), { width: 360, height: 4500 })
})
