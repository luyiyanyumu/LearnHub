import test from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import { compileScript, compileTemplate, parse } from '@vue/compiler-sfc'
import * as vue from 'vue'
import * as profileView from '../utils/modelProfileView.js'

const source = readFileSync(new URL('./AgentSessions.vue', import.meta.url), 'utf8')
const descriptor = parse(source).descriptor
const compiled = compileScript(descriptor, { id: 'agent-sessions-behavior' })
const factory = new Function('modules', compiled.content
  .replace(/^import\s+([\s\S]*?)\s+from\s+['"]([^'"]+)['"]\s*;?\r?$/gm,
    (_, names, from) => `const ${names.replace(/\bas\b/g, ':')} = modules[${JSON.stringify(from)}]`)
  .replace('export default', 'return'))
const rows = [{ id: 'first', title: '合成会话甲', modelProfileId: '' }, { id: 'second', title: '合成会话乙', modelProfileId: 'profile-b' }]
const profiles = [{ id: 'profile-a', purpose: 'chat' }, { id: 'profile-b', purpose: 'chat' }]
const deferred = () => { let resolve, reject; const promise = new Promise((ok, fail) => { resolve = ok; reject = fail }); return { promise, resolve, reject } }
const flush = async () => { for (let i = 0; i < 8; i++) { await Promise.resolve(); await vue.nextTick() } }

function harness(options = {}) {
  const previousWindow = globalThis.window, previousStorage = globalThis.localStorage
  const values = new Map(options.saved ? [['lh-agent-session', options.saved]] : [])
  globalThis.window = new EventTarget()
  globalThis.localStorage = { getItem: key => values.get(key) ?? null, setItem: (key, value) => values.set(key, value), removeItem: key => values.delete(key) }
  let data = structuredClone(rows), pendingList = null
  const switches = [], modelEvents = [], removals = [], modelChanges = []
  window.addEventListener('lh-agent-switch', event => switches.push({ ...event.detail, saved: values.get('lh-agent-session') }))
  window.addEventListener('lh-agent-session-model', event => modelEvents.push(event.detail))
  const component = factory({
    vue, '../components/AgentPanel.vue': {}, '../utils/modelProfileView': profileView,
    'element-plus': { ElMessage: { success() {} }, ElMessageBox: { async confirm() {} } },
    '../api': { modelApi: {
      sessions: () => pendingList?.promise ?? Promise.resolve(data),
      profiles: async () => ({ profiles }),
      async newSession(body) { const row = options.create ? await options.create(body) : { id: 'created', title: '合成新会话', modelProfileId: '' }; data = [row, ...data]; return row },
      async removeSession(id) { removals.push(id); if (options.remove) await options.remove(id); data = data.filter(row => row.id !== id) },
      async setSessionModel(id, profileId) { modelChanges.push({ id, profileId }); if (options.setModel) await options.setModel(id, profileId) },
    } },
  })
  component.render = () => null
  const renderer = vue.createRenderer({
    createElement: tag => ({ tag, children: [] }), createText: text => ({ text }), createComment: text => ({ text }),
    insert: (child, parent) => parent.children.push(child), remove() {}, setText() {}, setElementText() {}, patchProp() {}, parentNode: () => null, nextSibling: () => null,
  })
  const app = renderer.createApp(component)
  app.mount({ children: [] })
  return {
    state: app._instance.setupState, switches, modelEvents, removals, modelChanges, values,
    holdList() { pendingList = deferred(); return pendingList },
    releaseList() { pendingList = null },
    dispose() { app.unmount(); globalThis.window = previousWindow; globalThis.localStorage = previousStorage },
  }
}

test('first automatic selection persists before notifying the newly mounted panel', async () => {
  const h = harness({ saved: 'missing' })
  try {
    await flush()
    assert.equal(h.state.activeId, 'first')
    assert.equal(h.values.get('lh-agent-session'), 'first')
    assert.deepEqual(h.switches, [{ id: 'first', saved: 'first' }])
  } finally { h.dispose() }
})

test('saved selection restores its own model and survives a delayed list omitting a newer selection', async () => {
  const h = harness({ saved: 'second' })
  try {
    await flush()
    assert.equal(h.state.activeId, 'second')
    assert.equal(h.state.activeProfile, 'profile-b')
    const pending = h.holdList()
    const loading = h.state.loadAll()
    h.state.select('first')
    pending.resolve([rows[1]])
    await loading; await flush()
    assert.equal(h.state.activeId, 'first')
    assert.equal(h.values.get('lh-agent-session'), 'first')
    assert.equal(h.switches.at(-1).id, 'first')
  } finally { h.dispose() }
})

test('only the newest list request updates rows and clears loading', async () => {
  const h = harness()
  try {
    await flush()
    const older = h.holdList(), olderLoad = h.state.loadAll()
    const newer = h.holdList(), newerLoad = h.state.loadAll()
    older.resolve([{ id: 'stale' }]); await olderLoad
    assert.equal(h.state.loading, true)
    newer.resolve([rows[1]]); await newerLoad
    assert.deepEqual(h.state.sessions.map(row => row.id), ['second'])
    assert.equal(h.state.loading, false)
  } finally { h.dispose() }
})

test('an explicit empty panel selection persists across list refresh without selecting the first row again', async () => {
  const h = harness()
  try {
    await flush()
    window.dispatchEvent(new CustomEvent('lh-agent-current', { detail: { id: '', modelProfileId: '' } }))
    await h.state.loadAll(); await flush()
    assert.equal(h.state.activeId, '')
    assert.equal(h.state.activeProfile, '')
    assert.equal(h.values.has('lh-agent-session'), false)
  } finally { h.dispose() }
})

test('creating selects the returned session before refreshing and sends one matching panel notification', async () => {
  const h = harness()
  try {
    await flush()
    await h.state.createSession(); await flush()
    assert.equal(h.state.activeId, 'created')
    assert.equal(h.values.get('lh-agent-session'), 'created')
    assert.equal(h.switches.filter(event => event.id === 'created').length, 1)
    assert.equal(h.state.sessions[0].id, 'created')
  } finally { h.dispose() }
})

test('a new-session response does not steal a more recent user selection', async () => {
  const creation = deferred(), h = harness({ create: () => creation.promise })
  try {
    await flush()
    const creating = h.state.createSession()
    h.state.select('second')
    creation.resolve({ id: 'created', title: '合成新会话' })
    await creating; await flush()
    assert.equal(h.state.activeId, 'second')
    assert.equal(h.switches.at(-1).id, 'second')
    assert.ok(h.state.sessions.some(row => row.id === 'created'))
  } finally { h.dispose() }
})

test('deleting the current session clears both storage and panel without automatically switching', async () => {
  const h = harness()
  try {
    await flush()
    await h.state.removeSession(rows[0]); await flush()
    assert.deepEqual(h.removals, ['first'])
    assert.equal(h.state.activeId, '')
    assert.equal(h.values.has('lh-agent-session'), false)
    assert.equal(h.switches.at(-1).id, '')
    assert.deepEqual(h.state.sessions.map(row => row.id), ['second'])
  } finally { h.dispose() }
})

test('a late model change belongs to the original session and does not update the newly selected session', async () => {
  const model = deferred(), h = harness({ setModel: () => model.promise })
  try {
    await flush()
    const saving = h.state.setModel('profile-a')
    h.state.select('second')
    model.resolve(); await saving
    assert.equal(h.state.activeProfile, 'profile-b')
    assert.deepEqual(h.modelChanges, [{ id: 'first', profileId: 'profile-a' }])
    assert.deepEqual(h.modelEvents, [{ id: 'first', profileId: 'profile-a' }])
  } finally { h.dispose() }
})

test('failed refresh preserves the list and selected conversation', async () => {
  const h = harness()
  try {
    await flush()
    const pending = h.holdList(), loading = h.state.loadAll()
    pending.reject(new Error('合成网络错误')); await loading
    assert.equal(h.state.activeId, 'first')
    assert.equal(h.state.sessions.length, 2)
    assert.equal(h.state.loading, false)
  } finally { h.dispose() }
})

test('selection uses a native keyboard button separate from the delete button', () => {
  const result = compileTemplate({ source: descriptor.template.content, filename: 'AgentSessions.vue', id: 'agent-sessions-behavior', compilerOptions: { bindingMetadata: compiled.bindings } })
  assert.deepEqual(result.errors, [])
  const nodes = []
  function walk(node) { if (node?.type === 1) nodes.push(node); for (const child of node?.children || []) walk(child) }
  walk(descriptor.template.ast)
  const main = nodes.find(node => node.props?.some(prop => prop.name === 'class' && prop.value?.content === 'row-main'))
  assert.equal(main?.tag, 'button')
  assert.equal(main.children.some(node => node.tag === 'button'), false)
  assert.match(source, /\.side-row:focus-within \.row-del/)
})
