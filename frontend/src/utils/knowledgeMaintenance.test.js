import test from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import { compileTemplate, parse } from '@vue/compiler-sfc'
import request from '../api/index.js'
import { knowledgeMaintenanceApi } from '../api/knowledgeMaintenance.js'
import { createKnowledgeMaintenanceLoader, maintenanceTime, maintenanceUpdateHint } from './knowledgeIndexView.js'

const deferred = () => { let resolve, reject; const promise = new Promise((ok, fail) => { resolve = ok; reject = fail }); return { promise, resolve, reject } }
function harness() {
  const reads = [], states = [], timers = new Map()
  let timerId = 0
  const loader = createKnowledgeMaintenanceLoader({
    load(signal) { const read = deferred(); reads.push({ ...read, signal }); return read.promise },
    onState: state => states.push(state),
    setTimer(fn) { timers.set(++timerId, fn); return timerId }, clearTimer: id => timers.delete(id),
  })
  return { reads, states, timers, loader }
}

test('status reads and polling never queue maintenance; close and unmount stop future polling', async () => {
  const h = harness()
  const started = h.loader.start()
  assert.equal(h.reads.length, 1)
  h.reads[0].resolve({ enabled: true, running: false, pending: true, message: '等待后台执行' }); await started
  assert.equal(h.states.at(-1).status.pending, true)
  assert.equal(h.timers.size, 1)
  const poll = h.timers.values().next().value; h.timers.clear(); poll()
  assert.equal(h.reads.length, 2)
  h.loader.stop()
  assert.equal(h.reads[1].signal.aborted, true)
  h.reads[1].resolve({ enabled: false }); await Promise.resolve(); await Promise.resolve()
  assert.equal(h.states.at(-1).status.enabled, true)
  assert.equal(h.timers.size, 0)
  h.loader.dispose()
  await h.loader.refresh()
  assert.equal(h.reads.length, 2)
})

test('a newer status supersedes an older response and failures preserve the last known result with an error', async () => {
  const h = harness()
  const first = h.loader.start(), second = h.loader.refresh()
  assert.equal(h.reads[0].signal.aborted, true)
  h.reads[1].resolve({ enabled: false, phase: 'idle', message: '已关闭' }); await second
  h.reads[0].resolve({ enabled: true, phase: 'done' }); await first
  assert.equal(h.states.at(-1).status.enabled, false)
  const failed = h.loader.refresh()
  h.reads[2].reject(new Error('状态接口暂时不可用')); await failed
  assert.equal(h.states.at(-1).status.enabled, false)
  assert.equal(h.states.at(-1).error, '状态接口暂时不可用')
  h.loader.dispose()
})

test('explicit queue or settings writes supersede stale reads and cannot run twice concurrently', async () => {
  const h = harness(), action = deferred()
  const oldRead = h.loader.start()
  let writes = 0
  const submitted = h.loader.submit(() => { writes++; return action.promise })
  await h.loader.submit(() => { writes++; throw new Error('duplicate') })
  await h.loader.refresh()
  h.reads[0].resolve({ enabled: true, pending: false, message: '旧结果' }); await oldRead
  action.resolve({ enabled: true, pending: true, phase: 'queued', message: '已排队，尚未执行' }); await submitted
  assert.equal(writes, 1)
  assert.equal(h.states.at(-1).status.pending, true)
  assert.equal(h.states.at(-1).status.message, '已排队，尚未执行')
  assert.equal(h.states.at(-1).submitting, false)
  const late = deferred(), count = h.states.length
  const closing = h.loader.submit(() => late.promise)
  h.loader.dispose(); late.resolve({ message: 'late' }); await closing
  assert.equal(h.states.length, count + 1)
})

test('maintenance API preserves signals, uses exact queue/settings endpoints and leaves all rebuild calls to the backend', async () => {
  const previous = request.defaults.adapter, calls = []
  request.defaults.adapter = async config => {
    calls.push(config)
    return { status: 200, statusText: '', headers: {}, config, data: { code: 200, data: { enabled: false, pending: true } } }
  }
  try {
    const controller = new AbortController()
    await knowledgeMaintenanceApi.status(controller.signal)
    await knowledgeMaintenanceApi.settings(false)
    await knowledgeMaintenanceApi.run()
    assert.deepEqual(calls.map(c => [c.method, c.url]), [['get', '/knowledge/maintenance'], ['put', '/knowledge/maintenance'], ['post', '/knowledge/maintenance/run']])
    assert.equal(calls[0].signal, controller.signal)
    assert.deepEqual(JSON.parse(calls[1].data), { enabled: false })
    assert.ok(calls.every(c => c.silentError === true))
  } finally { request.defaults.adapter = previous }
})

test('maintenance descriptions distinguish disabled, unknown, partial and actual backend messages', () => {
  assert.match(maintenanceUpdateHint({ enabled: false }), /自动维护已关闭/)
  assert.match(maintenanceUpdateHint(null), /暂未确认/)
  assert.equal(maintenanceUpdateHint({ enabled: true, pending: true, message: '仅索引完成，图谱尚有剩余' }), '仅索引完成，图谱尚有剩余')
  assert.equal(maintenanceTime(''), '—')
  assert.equal(maintenanceTime('invalid'), '—')
})

test('maintenance drawer compiles with accessible themed controls and honest partial step presentation', () => {
  const source = readFileSync(new URL('../views/Knowledge.vue', import.meta.url), 'utf8')
  const descriptor = parse(source).descriptor
  assert.deepEqual(compileTemplate({ source: descriptor.template.content, filename: 'Knowledge.vue', id: 'maintenance-view' }).errors, [])
  assert.match(source, /aria-label="后台自动维护知识库"/)
  assert.match(source, /aria-live="polite"/)
  assert.match(source, /partial: '尚未完成'/)
  assert.match(source, /maintenance\.phaseLabel/)
  assert.match(source, /var\(--app-card\)/)
  assert.match(source, /summary:focus-visible/)
  assert.doesNotMatch(source, /更换配置不会自动重建/)
})
