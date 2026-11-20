import test from 'node:test'
import assert from 'node:assert/strict'
import { createExtractionTaskMonitor, extractionPageProgress, extractionTaskLabel, extractionTasksFromRoute, pendingScanCoverage, readExtractionTasks, rememberExtractionTask } from './extractionTasks.js'

function deferred() { let resolve, reject; const promise = new Promise((ok, fail) => { resolve = ok; reject = fail }); return { promise, resolve, reject } }
async function flush() { for (let n = 0; n < 10; n++) await Promise.resolve() }
function harness() {
  const requests = [], states = [], finished = [], errors = [], scheduled = []
  const monitor = createExtractionTaskMonitor({
    load(id) { const request = { id, ...deferred() }; requests.push(request); return request.promise },
    onState: value => states.push(value), onFinished: value => finished.push(value), onError: error => errors.push(error.message),
    schedule: callback => { scheduled.push(callback); return callback },
    unschedule: callback => { const index = scheduled.indexOf(callback); if (index >= 0) scheduled.splice(index, 1) },
  })
  return { monitor, requests, states, finished, errors, scheduled }
}

test('queued jobs poll until partial, retain source identity and never claim complete success', async () => {
  const h = harness()
  const watching = h.monitor.watch({ id: 'media-1', status: 'queued', fileId: '41' })
  h.requests[0].resolve({ id: 'media-1', status: 'running', done: 0, total: 2 })
  await watching
  assert.equal(h.scheduled.length, 1)
  h.scheduled.shift()()
  h.requests[1].resolve({ id: 'media-1', status: 'partial', done: 2, total: 2, failed: 1 })
  await flush()
  assert.equal(h.finished.length, 1)
  assert.equal(h.finished[0].fileId, '41')
  assert.match(extractionTaskLabel(h.finished[0]), /部分完成.*待处理/)
  assert.equal(h.scheduled.length, 0)
  h.monitor.dispose()
})

test('late job responses and disposal cannot replace current progress or cancel a server task', async () => {
  const h = harness()
  const old = h.monitor.watch({ id: 'old', status: 'queued' })
  const current = h.monitor.watch({ id: 'current', status: 'queued' })
  h.requests[1].resolve({ status: 'completed' })
  await current
  h.requests[0].resolve({ status: 'failed' })
  await old
  assert.equal(h.states.at(-1).id, 'current')
  const late = h.monitor.watch({ id: 'background', status: 'queued' })
  h.monitor.dispose()
  h.requests[2].resolve({ status: 'completed' })
  await late
  assert.deepEqual(h.finished.map(job => job.id), ['current'])
})

test('status failures pause polling and can be retried without creating another job', async () => {
  const h = harness()
  const job = { id: 'migration-1', status: 'running' }
  const first = h.monitor.watch(job)
  h.requests[0].reject(new Error('合成暂时离线'))
  await first
  assert.deepEqual(h.errors, ['合成暂时离线'])
  assert.equal(h.scheduled.length, 0)
  const retry = h.monitor.watch(job)
  h.requests[1].resolve({ status: 'completed' })
  await retry
  assert.deepEqual(h.requests.map(request => request.id), ['migration-1', 'migration-1'])
  assert.equal(h.finished.length, 1)
  h.monitor.dispose()
})

test('only unfinished identifiers persist for later route visits; terminal jobs clear their own entry', () => {
  const values = new Map(), storage = { getItem: key => values.get(key), setItem: (key, value) => values.set(key, value) }
  rememberExtractionTask('media', { id: 'media-1', fileId: '41', status: 'running' }, storage)
  rememberExtractionTask('migration', { id: 'migration-1', status: 'queued' }, storage)
  rememberExtractionTask('structure', { id: 'structure-1', fileId: '41', status: 'queued' }, storage)
  assert.equal(readExtractionTasks(storage).media.fileId, '41')
  rememberExtractionTask('media', { id: 'media-1', status: 'partial' }, storage)
  assert.deepEqual(Object.keys(readExtractionTasks(storage)), ['migration', 'structure'])
  assert.equal(readExtractionTasks(storage).structure.fileId, '41')
  rememberExtractionTask('extraction', { id: 'body-1', fileId: '91', status: 'running' }, storage)
  rememberExtractionTask('structure', { id: 'structure-1', status: 'completed' }, storage)
  assert.deepEqual(readExtractionTasks(storage).extraction, { id: 'body-1', fileId: '91', status: 'running' })
  rememberExtractionTask('extraction', { id: 'body-1', status: 'partial' }, storage)
  assert.deepEqual(Object.keys(readExtractionTasks(storage)), ['migration'])
  assert.deepEqual(readExtractionTasks({ getItem() { throw new Error('storage unavailable') } }), {})
})

test('page progress uses accepted page coverage and waits for a trustworthy total', () => {
  assert.deepEqual(extractionPageProgress({ status: 'queued', total: 1, done: 0 }), { pages: '', detail: '', percentage: null })
  assert.deepEqual(extractionPageProgress({ status: 'running', pageProgress: { stage: 'extracting', totalPages: 431, completedPages: 430, currentPage: 9 } }), {
    pages: '可用 430 / 431 页', detail: '识别正文 · 正在处理原文第 9 页', percentage: 99,
  })
  assert.deepEqual(extractionPageProgress({ status: 'running', stage: '更新分块与索引', pageProgress: { stage: 'saving', totalPages: 5, completedPages: 5 } }), {
    pages: '可用 5 / 5 页', detail: '更新分块与索引', percentage: 100,
  })
})

test('scan progress describes the candidate without claiming it is published coverage', () => {
  const job = {
    status: 'running', scanMode: true, pendingUpgrade: true,
    pageProgress: { stage: 'extracting', currentPage: 21, completedPages: 20, totalPages: 431 },
    extractionCoverage: { completedPages: 431, totalPages: 431 },
  }
  assert.deepEqual(extractionPageProgress(job), {
    pages: '扫描通过 20 / 431 页', detail: '逐页扫描 · 正在处理原文第 21 页 · 新结果待发布，原有正文仍可用', percentage: 4,
  })
  const staged = { pendingUpgrade: true, scanProgress: { completedPages: 20, report: { totalPages: 431, processedPages: 21, partial: true } } }
  assert.equal(pendingScanCoverage(staged).completedPages, 20)
  assert.equal(extractionPageProgress({ ...staged, status: 'partial' }).percentage, 4)
  assert.equal(pendingScanCoverage({ ...staged, pendingUpgrade: false }), null)
  assert.equal(extractionPageProgress({ scanMode: true, status: 'running', pageProgress: { completedPages: 20 } }).percentage, null)
})

test('task links accept UUIDs and bind media only to an exact positive read id', () => {
  const id = '11111111-1111-4111-8111-111111111111'
  assert.deepEqual(extractionTasksFromRoute({ read: '41', mediaJob: id }), { media: { id, fileId: '41', status: 'queued' } })
  assert.deepEqual(extractionTasksFromRoute({ migrationJob: id.toUpperCase() }), { migration: { id, status: 'queued' } })
  for (const read of [undefined, '', '0', '-1', '1?read=2', ['41']]) assert.deepEqual(extractionTasksFromRoute({ read, mediaJob: id }), {})
  for (const mediaJob of [undefined, 'not-a-uuid', '../job', [id]]) assert.deepEqual(extractionTasksFromRoute({ read: '41', mediaJob }), {})
})

test('stopping a route watch ignores its pending result and still permits another existing job', async () => {
  const h = harness()
  const pending = h.monitor.watch({ id: 'old-route', status: 'queued' })
  h.monitor.stop()
  h.requests[0].resolve({ status: 'completed' })
  await pending
  assert.deepEqual(h.finished, [])
  const next = h.monitor.watch({ id: 'new-route', status: 'queued' })
  h.requests[1].resolve({ status: 'completed' })
  await next
  assert.deepEqual(h.finished.map(job => job.id), ['new-route'])
  h.monitor.dispose()
})
