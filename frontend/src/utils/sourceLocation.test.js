import test from 'node:test'
import assert from 'node:assert/strict'
import { firstSourceLocation, normalizeSourceLocation, resolveSourceLocation, sourceLocatorFromRoute, sourceRegionStyle } from './sourceLocation.js'
import { retrievalPosition, retrievalSourcePath } from './retrievalDisplay.js'

const revision = 'a'.repeat(64)
const location = { page: 3, rect: [80, 60, 240, 120], width: 800, height: 600, method: 'ocr', sourceRevision: revision }
const extraction = { sourceSha256: revision, pages: [{ page: 3, width: 800, height: 600 }] }

test('file search links carry a versioned rectangle and round-trip through route parsing', () => {
  const hit = { sourceType: 'file', sourceId: 71, seq: 8, sourceLocations: [location] }
  const url = new URL(retrievalSourcePath(hit), 'http://synthetic.local')
  assert.equal(url.pathname, '/files')
  assert.equal(url.searchParams.get('read'), '71')
  assert.equal(url.searchParams.get('page'), '3')
  const parsed = sourceLocatorFromRoute(Object.fromEntries(url.searchParams))
  assert.deepEqual(parsed.rect, location.rect)
  assert.equal(parsed.sourceRevision, revision)
  assert.equal(retrievalPosition(hit), '第 3 页 · OCR')
  assert.equal(retrievalPosition({ ...hit, sourceLocations: [{ ...location, method: 'vision' }] }), '第 3 页 · 视觉识别')
  assert.equal(normalizeSourceLocation({ ...location, method: 'vision' }).method, 'vision')
  assert.deepEqual(resolveSourceLocation(parsed, extraction).location.rect, location.rect)
})

test('unversioned or malformed coordinates fall back to page navigation without drawing', () => {
  assert.equal(retrievalSourcePath({ type: 'file', id: 71, sourceLocations: [{ ...location, sourceRevision: '' }] }), '/files?read=71&page=3')
  for (const rect of [[1, 2, 1, 4], [0, 0, Infinity, 4], [null, 1, 3, 4], ['', 1, 3, 4], [-1, 1, 3, 4]]) {
    assert.equal(normalizeSourceLocation({ ...location, rect }).rect, null)
  }
  for (const page of [undefined, '-1', '0', ['3'], '3abc']) assert.equal(sourceLocatorFromRoute({ page }), null)
  assert.equal(sourceLocatorFromRoute({ page: '3', rect: '1,,3,4' }).rect, null)
  assert.equal(firstSourceLocation({ sourceLocations: [{ page: -1 }, location] }).page, 3)
  assert.equal(retrievalPosition({ type: 'note', seq: 8, sourceLocations: [location] }), '第 9 段')
})

test('stale, absent and invalid report revisions never display a source rectangle', () => {
  for (const current of [null, { sourceSha256: 'b'.repeat(64), pages: extraction.pages }, { sourceSha256: 'not-a-sha' }]) {
    const result = resolveSourceLocation(location, current)
    assert.equal(result.location.page, 3)
    assert.equal(result.location.rect, null)
    assert.ok(result.message)
  }
  assert.equal(resolveSourceLocation(location, { sourceSha256: revision, pages: [] }).location.rect, null)
  assert.equal(resolveSourceLocation({ page: 3 }, null).message, '')
})

test('region percentages use displayed dimensions once, including landscape rotated PDF and image pixels', () => {
  const expected = { left: '10%', top: '10%', width: '20%', height: '10%' }
  assert.deepEqual(sourceRegionStyle(location), expected)
  assert.deepEqual(sourceRegionStyle({ ...location, coordinateUnit: 'px', width: 1600, height: 1200, rect: location.rect.map(n => n * 2) }), expected)
  // The 800×600 page is already rotated by the extraction pipeline: no second rotation.
  assert.deepEqual(sourceRegionStyle(resolveSourceLocation(location, extraction).location), expected)
  assert.deepEqual(sourceRegionStyle({ page: 1, rect: [90, 90, 110, 130], width: 100, height: 100 }), { left: '90%', top: '90%', width: '10%', height: '10%' })
  assert.equal(sourceRegionStyle({ ...location, rect: [900, 60, 920, 120] }), null)
})

test('block-only locators bind their pipeline through routes while validated rectangles survive layout updates', () => {
  const pipelineVersion = 'document-ingestion-v3-layout'
  const block = { page: 1, blockId: 'p1-b2-vision-formula-1', sourceRevision: revision, pipelineVersion }
  const current = { sourceSha256: revision, pipelineVersion, pages: [{ page: 1 }] }
  const url = new URL(retrievalSourcePath({ sourceType: 'file', sourceId: 71, sourceLocations: [block] }), 'http://synthetic.local')
  assert.equal(url.searchParams.get('sourcePipeline'), pipelineVersion)
  const parsed = sourceLocatorFromRoute(Object.fromEntries(url.searchParams))
  assert.equal(parsed.blockId, block.blockId)
  assert.equal(parsed.sourceRevision, revision)
  assert.equal(resolveSourceLocation(parsed, current).location.blockId, block.blockId)
  for (const pipelineVersion of [undefined, 'document-ingestion-v2', '../invalid', ['document-ingestion-v3-layout'], 'a'.repeat(101)]) {
    const normalized = normalizeSourceLocation({ ...block, pipelineVersion })
    if (pipelineVersion !== 'document-ingestion-v2') assert.equal('pipelineVersion' in normalized, false)
    const result = resolveSourceLocation(normalized, current)
    assert.equal(result.location.blockId, '')
    assert.match(result.message, /抽取结构已更新，请重新检索定位/)
  }
  assert.deepEqual(resolveSourceLocation(location, { ...extraction, pipelineVersion }).location.rect, location.rect)
})
