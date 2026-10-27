import test from 'node:test'
import assert from 'node:assert/strict'
import { createBridge } from '../src/bridge.js'
import { canCancelLauncherUpdate, canInstallLauncherUpdate, compareVersions, launcherUpdateSummary, normalizeLauncherProgress } from '../src/launcherUpdate.js'

const packageInfo = { currentVersion: '1.0.0', latestVersion: '1.1.0', state: 'available', sha256: 'ab'.repeat(32), downloadUrl: 'https://github.com/example/learnhub-demo/releases/download/v1.1.0/launcher.exe' }

test('launcher version gate permits only valid newer HTTPS packages', () => {
  assert.equal(canInstallLauncherUpdate(packageInfo), true)
  assert.equal(canInstallLauncherUpdate({ ...packageInfo, latestVersion: '0.9.0' }), false)
  assert.equal(canInstallLauncherUpdate({ ...packageInfo, latestVersion: '1.0.0' }), false)
  assert.equal(canInstallLauncherUpdate({ ...packageInfo, latestVersion: '1.0.0-rc.1' }), false)
  assert.equal(canInstallLauncherUpdate({ ...packageInfo, currentVersion: 'unknown' }), false)
  assert.equal(canInstallLauncherUpdate({ ...packageInfo, sha256: 'bad' }), false)
  assert.equal(canInstallLauncherUpdate({ ...packageInfo, downloadUrl: 'http://example.invalid/launcher.exe' }), false)
  assert.equal(canInstallLauncherUpdate({ ...packageInfo, state: 'unavailable' }), false)
  assert.equal(compareVersions('v1.0.0.0', '1.0.0+build.2'), 0)
  assert.equal(compareVersions('1.0.0', '1.0.0-rc.2'), 1)
  assert.equal(compareVersions('1.0.0-rc.10', '1.0.0-rc.2'), 1)
  assert.equal(launcherUpdateSummary({ ...packageInfo, latestVersion: '0.9.0' }).title, '当前版本高于最新发布版')
})

test('launcher progress normalizes data and disables cancellation after download', () => {
  assert.equal(normalizeLauncherProgress({ phase: 'unexpected' }), null)
  const progress = normalizeLauncherProgress({ phase: 'downloading', downloadedBytes: 50, totalBytes: 100, version: '1.1.0' })
  assert.equal(progress.percent, 50)
  assert.equal(canCancelLauncherUpdate(progress), true)
  assert.equal(normalizeLauncherProgress({ phase: 'downloading', percent: 300 }).percent, 100)
  assert.equal(normalizeLauncherProgress({ phase: 'downloading', downloadedBytes: -10 }).downloadedBytes, 0)
  assert.equal(canCancelLauncherUpdate({ phase: 'verifying' }), false)
  assert.equal(canCancelLauncherUpdate({ phase: 'restarting' }), false)
})

test('native update events preserve phases while source and launcher replies stay independent', async () => {
  let listener
  const sent = []
  const view = { addEventListener: (_, fn) => { listener = fn }, removeEventListener() {}, postMessage: value => sent.push(value) }
  const bridge = createBridge(view)
  const phases = []
  bridge.on('launcher-update-progress', event => phases.push(normalizeLauncherProgress(event)))
  const source = bridge.request('check-updates')
  const sourceRejected = assert.rejects(source, /源码检查网络失败/)
  const launcher = bridge.request('check-launcher-update')
  listener({ data: { id: sent[0].id, error: '源码检查网络失败' } })
  listener({ data: { type: 'launcher-update-progress', phase: 'downloading', downloadedBytes: 10, totalBytes: 100, percent: 10, version: '1.1.0' } })
  listener({ data: { id: sent[1].id, result: packageInfo } })
  await sourceRejected
  assert.deepEqual(await launcher, packageInfo)
  assert.equal(phases[0].phase, 'downloading')
  assert.equal(phases[0].version, '1.1.0')
  bridge.dispose()
})

test('synthetic EXE update emits all phases and leaves source and services untouched', async () => {
  const bridge = createBridge(null)
  const info = await bridge.request('check-launcher-update')
  const before = (await bridge.request('snapshot')).services
  const phases = []
  bridge.on('launcher-update-progress', event => phases.push(event.phase))
  await assert.rejects(bridge.request('install-launcher-update', { version: '0.9.0', sha256: info.sha256 }), /目标已变化/)
  await bridge.request('install-launcher-update', { version: info.latestVersion, sha256: info.sha256 })
  assert.equal((await bridge.request('check-launcher-update')).state, 'current')
  assert.equal((await bridge.request('bootstrap')).version, '1.2.0')
  assert.equal((await bridge.request('check-updates')).currentVersion, 'v0.1.4')
  assert.deepEqual((await bridge.request('snapshot')).services, before)
  assert.ok(phases.includes('downloading') && phases.includes('verifying') && phases.includes('restarting'))
  assert.equal(await bridge.request('open-launcher-release'), true)
  bridge.dispose()
})

test('synthetic download cancellation preserves the installed EXE and service state', async () => {
  const bridge = createBridge(null)
  const info = await bridge.request('check-launcher-update')
  const install = bridge.request('install-launcher-update', { version: info.latestVersion, sha256: info.sha256 })
  const cancelled = assert.rejects(install, /下载已取消/)
  await assert.rejects(bridge.request('action', { action: 'stop-all' }), /已有启动器更新任务/)
  await bridge.request('cancel')
  await cancelled
  assert.equal((await bridge.request('check-launcher-update')).currentVersion, '1.1.0')
  assert.ok((await bridge.request('snapshot')).services.every(service => service.state === 'healthy'))
  bridge.dispose()
})
