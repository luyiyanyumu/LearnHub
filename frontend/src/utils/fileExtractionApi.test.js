import test from 'node:test'
import assert from 'node:assert/strict'
import request, { fileApi } from '../api/index.js'

test('extraction read APIs preserve their contracts and uploads wait for OCR without retrying', async () => {
  const adapter = request.defaults.adapter
  const calls = []
  request.defaults.adapter = async config => {
    calls.push(config)
    return { data: { code: 200, data: { synthetic: true } }, status: 200, statusText: '', headers: {}, config }
  }
  try {
    await fileApi.extraction(41)
    await fileApi.extractionCaps()
    await fileApi.sourceLocations(41, '合成正文')
    await fileApi.upload(new Blob(['synthetic document']), null, null)
    assert.deepEqual(calls.slice(0, 3).map(call => [call.url, call.method]), [
      ['/files/41/extraction', 'get'], ['/files/document-extraction/capabilities', 'get'], ['/files/41/source-locations', 'post'],
    ])
    assert.deepEqual(JSON.parse(calls[2].data), { text: '合成正文' })
    assert.ok(calls.slice(0, 3).every(call => call.silentError))
    assert.equal(calls[3].url, '/files/upload')
    assert.equal(calls[3].timeout, 0)
    assert.equal(calls.length, 4)
    const pageImage = new URL(fileApi.extractionPageImageUrl(41, 2, 'a'.repeat(64)), 'http://synthetic.local')
    assert.equal(pageImage.searchParams.get('page'), '2')
    assert.equal(pageImage.searchParams.get('sourceRevision'), 'a'.repeat(64))
    await fileApi.markdown(41)
    await fileApi.downloadMarkdown(41)
    await fileApi.downloadMarkdown(41, true)
    await fileApi.processMedia(41, true)
    await fileApi.mediaJob('media-1')
    await fileApi.migrateMarkdown()
    await fileApi.markdownMigration('migration-1')
    assert.deepEqual(calls.slice(4).map(call => call.url), ['/files/41/markdown', '/files/41/markdown/download', '/files/41/markdown/bundle', '/files/41/process-media', '/files/media-jobs/media-1', '/files/markdown-migrations', '/files/markdown-migrations/migration-1'])
    assert.equal(calls[5].responseType, 'blob')
    assert.deepEqual(JSON.parse(calls[7].data), { force: true })
    assert.deepEqual(JSON.parse(calls[9].data), { force: false, processMedia: true })
    await fileApi.processStructure(41, { resume: true })
    await fileApi.structureJob('structure-1')
    await fileApi.confirmStructureFormula(41, { blockId: 'formula-1', sourceRevision: 'a'.repeat(64), expectedLatex: 'x', latex: 'y' })
    assert.deepEqual(calls.slice(11).map(call => [call.url, call.method]), [
      ['/files/41/process-structure', 'post'], ['/files/structure-jobs/structure-1', 'get'], ['/files/41/confirm-structure-formula', 'post'],
    ])
    assert.deepEqual(JSON.parse(calls[13].data), { blockId: 'formula-1', sourceRevision: 'a'.repeat(64), expectedLatex: 'x', latex: 'y' })
    assert.ok(calls.slice(11).every(call => call.silentError))
    assert.deepEqual(JSON.parse(calls[11].data), { resume: true })
    await fileApi.startExtraction(41, { resume: true })
    await fileApi.extractionJob('body-1')
    assert.deepEqual(calls.slice(14).map(call => [call.url, call.method]), [
      ['/files/41/extraction-jobs', 'post'], ['/files/extraction-jobs/body-1', 'get'],
    ])
    assert.deepEqual(JSON.parse(calls[14].data), { resume: true })
  } finally { request.defaults.adapter = adapter }
})

test('Markdown version reads and exports remain pinned while publication is a separate explicit write', async () => {
  const adapter = request.defaults.adapter
  const calls = []
  request.defaults.adapter = async config => {
    calls.push(config)
    return { data: { code: 200, data: {} }, status: 200, statusText: '', headers: {}, config }
  }
  try {
    await fileApi.markdown(41)
    await fileApi.markdown(41, 'published')
    const revision = { version: 'candidate', sourceRevision: 'a'.repeat(64), artifactRevision: 'b'.repeat(64) }
    await fileApi.downloadMarkdown(41, false, revision)
    await fileApi.downloadMarkdown(41, true, revision)
    assert.deepEqual(calls.map(call => call.method), ['get', 'get', 'get', 'get'])
    assert.deepEqual(calls[0].params, { version: 'latest' })
    assert.deepEqual(calls[1].params, { version: 'published' })
    assert.deepEqual(calls[2].params, revision)
    assert.deepEqual(calls[3].params, revision)
    await fileApi.publishScan(41, revision)
    assert.equal(calls[4].url, '/files/41/publish-scan')
    assert.equal(calls[4].method, 'post')
    assert.deepEqual(JSON.parse(calls[4].data), { sourceRevision: revision.sourceRevision, artifactRevision: revision.artifactRevision })
  } finally { request.defaults.adapter = adapter }
})
