import test from 'node:test'
import assert from 'node:assert/strict'
import { createBridge } from '../src/bridge.js'
import { canUpdateSource, healthSummary, versionSummary } from '../src/model.js'

function webviewStub() {
  const listeners = new Set(), sent = []
  return {
    sent,
    addEventListener(type, fn) { listeners.add(fn) },
    removeEventListener(type, fn) { listeners.delete(fn) },
    postMessage(value) { sent.push(value) },
    reply(data) { for (const fn of listeners) fn({ data }) },
  }
}

test('native bridge pairs out-of-order replies by request ID', async () => {
  const view = webviewStub(), bridge = createBridge(view)
  const first = bridge.request('snapshot', { mode: 'docker' })
  const second = bridge.request('logs', { service: 'backend' })
  assert.notEqual(view.sent[0].id, view.sent[1].id)
  view.reply({ id: view.sent[1].id, result: 'backend logs' })
  view.reply({ id: view.sent[0].id, result: { services: [] } })
  assert.deepEqual(await first, { services: [] })
  assert.equal(await second, 'backend logs')
  bridge.dispose()
})

test('native bridge exposes action errors and allows independent cancellation', async () => {
  const view = webviewStub(), bridge = createBridge(view)
  const action = bridge.request('action', { action: 'start-all', mode: 'docker' })
  const rejection = assert.rejects(action, /取消/)
  const cancel = bridge.request('cancel')
  assert.equal(view.sent[1].command, 'cancel')
  view.reply({ id: view.sent[1].id, result: true })
  view.reply({ id: view.sent[0].id, error: { message: '任务已取消' } })
  assert.equal(await cancel, true)
  await rejection
  bridge.dispose()
})

test('native bridge times out without replaying commands', async () => {
  const view = webviewStub(), bridge = createBridge(view, { timeoutMs: 10 })
  await assert.rejects(bridge.request('snapshot'), error => error.code === 'timeout' && /响应超时/.test(error.message))
  assert.equal(view.sent.length, 1)
  bridge.dispose()
})

test('native bridge routes unsolicited job and activity events', () => {
  const view = webviewStub(), bridge = createBridge(view)
  const received = []
  const off = bridge.on('job', job => received.push(job))
  view.reply({ type: 'job', job: { running: true, action: 'restart' } })
  off()
  view.reply({ type: 'job', job: { running: false } })
  assert.deepEqual(received, [{ running: true, action: 'restart' }])
  bridge.dispose()
})

test('disposing native bridge rejects pending requests', async () => {
  const view = webviewStub(), bridge = createBridge(view)
  const pending = bridge.request('snapshot')
  const rejected = assert.rejects(pending, /连接已关闭/)
  bridge.dispose()
  await rejected
  await assert.rejects(bridge.request('snapshot'), /连接已关闭/)
})

test('browser preview uses labeled synthetic data and operations update its state', async () => {
  const bridge = createBridge(null)
  assert.equal(bridge.native, false)
  const bootstrap = await bridge.request('bootstrap')
  assert.equal(bootstrap.demo, true)
  assert.equal(bootstrap.preferences.projectRoot, 'C:\\Demo\\LearnHub')
  assert.equal(healthSummary(await bridge.request('snapshot')).state, 'healthy')
  await bridge.request('action', { action: 'stop-all' })
  assert.equal(healthSummary(await bridge.request('snapshot')).state, 'stopped')
  await bridge.request('action', { action: 'start', service: 'frontend' })
  assert.equal(healthSummary(await bridge.request('snapshot')).healthy, 1)
  assert.match(await bridge.request('logs', { service: 'frontend' }), /合成数据/)
  bridge.dispose()
})

test('health summary distinguishes unknown, stopped and failed services', () => {
  assert.equal(healthSummary(null).state, 'unknown')
  assert.equal(healthSummary({ services: [] }).total, 0)
  assert.equal(healthSummary({ services: [{ state: 'conflict' }] }).state, 'unhealthy')
  assert.equal(healthSummary({ services: [{ state: 'running' }] }).state, 'starting')
})

test('source update request uses native job events and remains cancellable on timeout', async () => {
  const view = webviewStub(), bridge = createBridge(view)
  const jobs = []
  bridge.on('job', value => jobs.push(value))
  const update = bridge.request('update-source', { version: 'v0.1.5', commit: 'abc123' }, { timeoutMs: 15 })
  const rejection = assert.rejects(update, error => error.code === 'timeout' && /结果尚未确认/.test(error.message))
  view.reply({ type: 'job', job: { running: true, action: 'update-source' } })
  await rejection
  const cancel = bridge.request('cancel')
  view.reply({ id: view.sent[1].id, result: true })
  assert.equal(await cancel, true)
  assert.deepEqual(jobs, [{ running: true, action: 'update-source' }])
  assert.deepEqual(view.sent[0].payload, { version: 'v0.1.5', commit: 'abc123' })
  bridge.dispose()
})

test('update checks can extend their deadline independently of ordinary status requests', async () => {
  const view = webviewStub(), bridge = createBridge(view, { timeoutMs: 1 })
  const check = bridge.request('check-updates', {}, { timeoutMs: 200 })
  await new Promise(resolve => setTimeout(resolve, 10))
  view.reply({ id: view.sent[0].id, result: { latestVersion: 'v0.1.5' } })
  assert.deepEqual(await check, { latestVersion: 'v0.1.5' })
  bridge.dispose()
})

test('synthetic source update changes only source version and validates checked target', async () => {
  const bridge = createBridge(null)
  const info = await bridge.request('check-updates')
  assert.equal(info.currentVersion, 'v0.1.4')
  assert.equal(info.latestVersion, 'v0.1.5')
  assert.equal(canUpdateSource(info), true)
  const before = (await bridge.request('snapshot')).services
  await assert.rejects(bridge.request('update-source', { version: 'v0.1.5', commit: 'changed' }), /目标已变化/)
  assert.equal((await bridge.request('check-updates')).currentVersion, 'v0.1.4')
  const result = await bridge.request('update-source', { version: info.latestVersion, commit: info.latestCommit })
  assert.equal(result.currentVersion, 'v0.1.5')
  assert.equal(result.updateAvailable, false)
  assert.equal(canUpdateSource(result), false)
  assert.deepEqual((await bridge.request('snapshot')).services, before)
  bridge.dispose()
})

test('version summaries protect local changes and never offer a downgrade for an included release', () => {
  const info = { currentVersion: 'v0.1.5', sourceDescription: 'v0.1.5-1-gabcdef', currentCommit: 'abcdefnext', dirty: true, changeCount: 3, latestVersion: 'v0.1.5', latestCommit: 'abcdef', updateAvailable: false, canUpdate: false, blockedReason: '当前目录有 3 项改动，已暂停源码更新。' }
  assert.equal(versionSummary(info).title, '已包含最新稳定版')
  assert.match(versionSummary(info).detail, /3 项改动/)
  assert.equal(canUpdateSource(info), false)
  assert.equal(canUpdateSource({ ...info, canUpdate: true, updateAvailable: true }), false)
  assert.equal(versionSummary(null).kind, 'unknown')
  assert.equal(versionSummary({ ...info, latestVersion: null }).kind, 'unknown')
  assert.equal(versionSummary({ ...info, currentCommit: null }).kind, 'unknown')
})
