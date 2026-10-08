import assert from 'node:assert/strict'
import test from 'node:test'
import { createMemoryHistory, createRouter } from 'vue-router'
import { installRouteLoadRecovery } from './routeLoadRecovery.js'

const STORAGE_KEY = 'lh-route-load-recovery'
const lazyLoadError = () => new TypeError('Failed to fetch dynamically imported module: https://example.test/assets/NoteList.js')
const route = (fullPath, name = 'notes') => ({ fullPath, name })

function memoryStorage(initial = []) {
  const values = new Map(initial)
  return {
    values,
    getItem(key) { return values.get(key) ?? null },
    setItem(key, value) { values.set(key, String(value)) },
    removeItem(key) { values.delete(key) },
  }
}

function harness({ storage = memoryStorage(), from = route('/', 'dashboard') } = {}) {
  const callbacks = {}, assigned = [], notices = []
  const router = {
    currentRoute: { value: from },
    onError(callback) { callbacks.error = callback; return () => { delete callbacks.error } },
    beforeEach(callback) { callbacks.before = callback; return () => { delete callbacks.before } },
    afterEach(callback) { callbacks.after = callback; return () => { delete callbacks.after } },
    resolve(fullPath) { return { href: `/workspace${fullPath}` } },
  }
  installRouteLoadRecovery(router, {
    location: { assign(href) { assigned.push(href) } },
    storage,
    notify(message) { notices.push(message) },
  })
  return { router, callbacks, assigned, notices, storage, from }
}

test('a failed page import recovers the requested path, including query and anchor, exactly once', () => {
  const h = harness(), to = route('/notes?tag=router#heading')
  h.callbacks.before(to, h.from)
  h.callbacks.error(lazyLoadError(), to, h.from)
  h.callbacks.error(lazyLoadError(), to, h.from)
  assert.deepEqual(h.assigned, ['/workspace/notes?tag=router#heading'])
  assert.equal(h.storage.getItem(STORAGE_KEY), h.assigned[0])
})

test('common browser and bundler chunk failures all trigger recovery', () => {
  const errors = [
    lazyLoadError(),
    new TypeError('error loading dynamically imported module: https://example.test/assets/page.js'),
    new TypeError('Importing a module script failed.'),
    new Error('Unable to preload CSS for /assets/page.css'),
    new Error('Loading chunk 45 failed.'),
    Object.assign(new Error('A resource was unavailable'), { name: 'ChunkLoadError' }),
  ]
  for (const error of errors) {
    const h = harness(), to = route('/notes')
    h.callbacks.before(to, h.from)
    h.callbacks.error(error, to, h.from)
    assert.deepEqual(h.assigned, ['/workspace/notes'], error.message)
  }
})

test('an ordinary navigation error is reported without refreshing or recording a retry', () => {
  const h = harness(), to = route('/notes')
  h.callbacks.before(to, h.from)
  h.callbacks.error(new Error('The navigation guard crashed'), to, h.from)
  assert.deepEqual(h.assigned, [])
  assert.equal(h.storage.getItem(STORAGE_KEY), null)
  assert.equal(h.notices.length, 1)
  assert.equal(typeof h.notices[0], 'string')
  assert.ok(h.notices[0].length > 0)
})

test('a previously retried target reports failure without creating a reload loop', () => {
  const storage = memoryStorage([[STORAGE_KEY, '/workspace/notes']])
  const h = harness({ storage }), to = route('/notes')
  h.callbacks.before(to, h.from)
  h.callbacks.error(lazyLoadError(), to, h.from)
  assert.deepEqual(h.assigned, [])
  assert.equal(h.storage.getItem(STORAGE_KEY), '/workspace/notes')
  assert.equal(h.notices.length, 1)
})

test('a retry marker for another target still allows one recovery of the requested page', () => {
  const h = harness({ storage: memoryStorage([[STORAGE_KEY, '/workspace/files']]) })
  const to = route('/notes')
  h.callbacks.before(to, h.from)
  h.callbacks.error(lazyLoadError(), to, h.from)
  assert.deepEqual(h.assigned, ['/workspace/notes'])
  assert.equal(h.storage.getItem(STORAGE_KEY), '/workspace/notes')
})

test('unavailable retry storage does not allow an untracked refresh', () => {
  for (const failingMethod of ['getItem', 'setItem']) {
    const storage = memoryStorage()
    storage[failingMethod] = () => { throw new Error('Storage is unavailable') }
    const h = harness({ storage }), to = route('/notes')
    h.callbacks.before(to, h.from)
    assert.doesNotThrow(() => h.callbacks.error(lazyLoadError(), to, h.from))
    assert.deepEqual(h.assigned, [], failingMethod)
    assert.equal(h.notices.length, 1, failingMethod)
  }
})

test('leaving either note editor asks to save the note instead of automatically refreshing', () => {
  for (const name of ['noteEdit', 'noteNew']) {
    const from = route(name === 'noteNew' ? '/notes/new' : '/notes/42', name)
    const h = harness({ from }), to = route('/files', 'files')
    h.callbacks.before(to, from)
    h.callbacks.error(lazyLoadError(), to, from)
    assert.deepEqual(h.assigned, [], name)
    assert.equal(h.storage.getItem(STORAGE_KEY), null, name)
    assert.equal(h.notices.length, 1, name)
    assert.match(h.notices[0], /保存.*笔记|笔记.*保存/, name)
  }
})

test('a newer navigation makes an older import failure irrelevant', () => {
  const h = harness(), oldTo = route('/notes'), latestTo = route('/files', 'files')
  h.callbacks.before(oldTo, h.from)
  h.callbacks.before(latestTo, h.from)
  h.callbacks.error(lazyLoadError(), oldTo, h.from)
  assert.deepEqual(h.assigned, [])
  assert.deepEqual(h.notices, [])
  assert.equal(h.storage.getItem(STORAGE_KEY), null)
  h.callbacks.error(lazyLoadError(), latestTo, h.from)
  assert.deepEqual(h.assigned, ['/workspace/files'])
})

test('successful navigation clears the retry marker and makes old failures irrelevant', () => {
  const h = harness({ storage: memoryStorage([[STORAGE_KEY, '/workspace/notes']]) })
  const oldTo = route('/notes'), latestTo = route('/files', 'files')
  h.callbacks.before(oldTo, h.from)
  h.callbacks.after(latestTo, h.from)
  assert.equal(h.storage.getItem(STORAGE_KEY), null)
  h.callbacks.error(lazyLoadError(), oldTo, h.from)
  assert.deepEqual(h.assigned, [])
  assert.deepEqual(h.notices, [])
})

test('failed navigation never clears a retry marker', () => {
  const h = harness({ storage: memoryStorage([[STORAGE_KEY, '/workspace/notes']]) })
  const to = route('/notes')
  h.callbacks.before(to, h.from)
  h.callbacks.after(to, h.from, new Error('Navigation aborted'))
  assert.equal(h.storage.getItem(STORAGE_KEY), '/workspace/notes')
  h.callbacks.error(lazyLoadError(), to, h.from)
  assert.deepEqual(h.assigned, [])
  assert.equal(h.notices.length, 1)
})

test('Vue Router invokes recovery on a rejected lazy component and keeps the previous page', async () => {
  const error = lazyLoadError(), assigned = [], notices = [], errors = []
  const router = createRouter({
    history: createMemoryHistory('/workspace/'),
    routes: [
      { path: '/', name: 'dashboard', component: { render() { return null } } },
      { path: '/notes', name: 'notes', component: () => Promise.reject(error) },
    ],
  })
  installRouteLoadRecovery(router, {
    location: { assign(href) { assigned.push(href) } },
    storage: memoryStorage(),
    notify(message) { notices.push(message) },
  })
  router.onError((err, to, from) => errors.push({ err, to, from }))
  await router.push('/')
  await assert.rejects(router.push('/notes?q=test#target'), err => err === error)
  assert.equal(errors.length, 1)
  assert.equal(errors[0].to.fullPath, '/notes?q=test#target')
  assert.equal(errors[0].from.fullPath, '/')
  assert.equal(router.currentRoute.value.fullPath, '/')
  assert.deepEqual(assigned, [router.resolve('/notes?q=test#target').href])
})

test('Vue Router guard cancellation when leaving a note keeps the editor without triggering recovery', async () => {
  const assigned = [], notices = [], errors = []
  let importCalls = 0
  const router = createRouter({
    history: createMemoryHistory(),
    routes: [
      { path: '/notes/42', name: 'noteEdit', component: { render() { return null } } },
      { path: '/notes', name: 'notes', component: () => { importCalls++; return Promise.reject(lazyLoadError()) } },
    ],
  })
  installRouteLoadRecovery(router, {
    location: { assign(href) { assigned.push(href) } },
    storage: memoryStorage(),
    notify(message) { notices.push(message) },
  })
  router.onError(error => errors.push(error))
  await router.push('/notes/42')
  router.beforeEach((_to, from) => from.name === 'noteEdit' ? false : undefined)
  const failure = await router.push('/notes')
  assert.ok(failure)
  assert.equal(importCalls, 0)
  assert.equal(router.currentRoute.value.fullPath, '/notes/42')
  assert.deepEqual(errors, [])
  assert.deepEqual(assigned, [])
  assert.deepEqual(notices, [])
})
