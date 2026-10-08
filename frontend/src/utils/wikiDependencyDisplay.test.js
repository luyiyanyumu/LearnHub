import test from 'node:test'
import assert from 'node:assert/strict'
import { createWikiDependencyLoader, wikiDependencyView } from './wikiDependencyDisplay.js'

test('recorded chunk dependencies display exact source routes, zero-based positions and hashes without fact verification', () => {
  const view = wikiDependencyView({ status: 'current', sourceUnchanged: true, factVerified: true,
    coverage: { sourceCount: 2, chunkCount: 3, totalChunks: 4, selectedChars: 2000, sourceChars: 1800, complete: false },
    dependencies: [
      { sourceType: 'file', sourceId: 6, title: '论文', seq: 0, heading: '方法', status: 'current', chunkText: '生成素材',
        fullContentHash: 'old-source', currentFullContentHash: 'old-source', chunkHash: 'old-chunk', currentChunkHash: 'old-chunk' },
      { sourceType: 'quick_ref', sourceId: 3, seq: 4, status: 'current' },
    ] })
  assert.equal(view.rows[0].path, '/files?read=6')
  assert.equal(view.rows[0].position, '第 1 段')
  assert.equal(view.rows[1].path, '/refs?read=3')
  assert.equal(view.rows[1].position, '第 5 段')
  assert.equal(view.rows[0].heading, '方法')
  assert.equal(view.rows[0].chunkText, '生成素材')
  assert.match(view.rows[0].hashDescription, /来源指纹（生成时）：old-source/)
  assert.match(view.rows[0].hashDescription, /片段指纹（当前）：old-chunk/)
  assert.equal(view.statusLabel, '已记录片段未变化')
  assert.equal(view.material, '2 个来源 · 读取 3 / 4 个片段 · 生成素材 2,000 字')
  assert.equal(view.partialMaterial, true)
  assert.equal(view.factVerified, false)
  assert.doesNotMatch(JSON.stringify(view), /%|已核验/)
})

test('changed and missing dependencies retain independent states and deleted sources cannot be opened', () => {
  const view = wikiDependencyView({ status: 'stale', sourceUnchanged: false, dependencies: [
    { sourceType: 'note', sourceId: 1, seq: 2, status: 'source_changed' },
    { sourceType: 'file', sourceId: 2, seq: 1, status: 'chunk_changed' },
    { sourceType: 'quick_ref', sourceId: 3, seq: 0, status: 'source_missing' },
    { sourceType: 'note', sourceId: 4, seq: 0, status: 'page_changed' },
    { sourceType: 'file', sourceId: 5, seq: 0, status: 'unknown' },
  ] })
  assert.equal(view.status, 'stale')
  assert.equal(view.summary, '5 个原文片段 · 3 处变更 · 1 个缺失 · 1 个待确认')
  assert.equal(view.rows[0].path, '/notes/1')
  assert.equal(view.rows[2].path, null)
  assert.deepEqual(view.rows.map(row => row.status), ['来源内容已变更', '片段已变更', '来源缺失', '知识页已变化', '尚无法确认'])
})

test('legacy and malformed records never imply source freshness or manufacture dependencies', () => {
  const legacy = wikiDependencyView({ status: 'unknown', hasDependencies: false, reasons: ['legacy_no_dependencies'], dependencies: [] })
  assert.equal(legacy.status, 'unknown')
  assert.match(legacy.reasons[0], /重新生成后可追踪/)
  assert.equal(legacy.statusLabel, '尚未记录生成片段')
  assert.equal(legacy.material, '')
  assert.deepEqual(legacy.sourceCoverage, [])
  assert.deepEqual(legacy.rows, [])
  const malformed = wikiDependencyView({ status: 'current', sourceUnchanged: false,
    dependencies: [null, { sourceType: 'file', sourceId: '../2', seq: -1, status: 'invented' }],
    coverage: { sourceCount: -1, chunkCount: '2', totalChunks: null, selectedChars: NaN } })
  assert.equal(malformed.status, 'unknown')
  assert.equal(malformed.rows.length, 1)
  assert.equal(malformed.rows[0].path, null)
  assert.equal(malformed.rows[0].position, '')
  assert.equal(malformed.rows[0].status, '尚无法确认')
  assert.equal(malformed.material, '')
  assert.deepEqual(wikiDependencyView(null).rows, [])
  assert.equal(wikiDependencyView({ status: 'current', sourceUnchanged: true, dependencies: [] }).status, 'unknown')
  const invalid = wikiDependencyView({ status: 'unknown', hasDependencies: true, reasons: ['dependency_record_invalid'] })
  assert.equal(invalid.statusLabel, '依赖状态待确认')
  assert.match(invalid.reasons[0], /记录不完整/)
  assert.doesNotMatch(invalid.reasons[0], /旧页/)
  const missing = wikiDependencyView({ status: 'unknown', hasDependencies: true, reasons: ['dependency_record_missing'] })
  assert.equal(missing.reasons[0], '来源依赖记录缺失，重新生成后可恢复。')
  assert.equal(missing.statusLabel, '依赖状态待确认')
})

test('per-source material uses recorded chunk counts and historical source lengths without recomputing a percentage', () => {
  const view = wikiDependencyView({ status: 'stale', sourceUnchanged: false,
    dependencies: [{ sourceType: 'file', sourceId: 6, seq: 5, status: 'source_changed' }],
    coverage: { sources: [{ sourceType: 'file', sourceId: 6, title: '论文', sentChunkCount: 3,
      totalChunkCount: 10, usedChars: 2200, sourceChars: 2000 }] } })
  assert.equal(view.sourceCoverage[0].label, '资料 #6 · 论文')
  assert.equal(view.sourceCoverage[0].material, '读取 3 / 10 个片段 · 生成素材 2,200 字 · 当时原文 2,000 字')
  assert.doesNotMatch(view.sourceCoverage[0].material, /%|当前原文/)
  assert.deepEqual(wikiDependencyView({ status: 'unknown', dependencies: [], coverage: {
    sources: [{ sourceType: 'file', sourceId: 6, sentChunkCount: 3, totalChunkCount: 10 }] } }).sourceCoverage, [])
})

function deferred() {
  let resolve, reject
  const promise = new Promise((ok, fail) => { resolve = ok; reject = fail })
  return { promise, resolve, reject }
}

test('a slow previous page cannot overwrite the newest dependency result or error state', async () => {
  const requests = [], states = []
  const loader = createWikiDependencyLoader({ load: topicKey => {
    const request = { ...deferred(), topicKey }; requests.push(request); return request.promise
  }, onState: state => states.push(state) })
  const first = loader.read('entity-old'), second = loader.read('entity-current')
  requests[1].resolve({ pageId: 2, dependencies: [] })
  await second
  requests[0].reject(new Error('旧页已删除'))
  await first
  assert.equal(states.length, 3)
  assert.equal(states.at(-1).topicKey, 'entity-current')
  assert.equal(states.at(-1).data.pageId, 2)
  assert.equal(states.at(-1).error, '')
})

test('clear and unmount invalidate pending reads; retries clear current failures', async () => {
  const requests = [], states = []
  const loader = createWikiDependencyLoader({ load: topicKey => {
    const request = { ...deferred(), topicKey }; requests.push(request); return request.promise
  }, onState: state => states.push(state) })
  const pending = loader.read('entity-one')
  loader.clear()
  requests[0].resolve({ dependencies: [{ sourceId: 1 }] })
  await pending
  assert.equal(states.at(-1).data, null)
  const failed = loader.read('entity-current')
  requests[1].reject({ response: { data: { msg: '服务暂时不可用' } } })
  await failed
  assert.equal(states.at(-1).error, '服务暂时不可用')
  const retry = loader.read('entity-current')
  assert.equal(states.at(-1).error, '')
  loader.dispose()
  const count = states.length
  requests[2].reject(new Error('late failure'))
  await retry
  await loader.read('entity-after-unmount')
  assert.equal(states.length, count)
  assert.equal(requests.length, 3)
})
