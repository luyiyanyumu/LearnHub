import test from 'node:test'
import assert from 'node:assert/strict'
import { canRemoveProfile, chatProfiles, chatProfileSelection, createModelDiscoveryLoader, discoveredModelSelection,
  embeddingProfileSpaceChanged, paramProfileSelection, preferredDiscoveredModels, profilePurpose, profileSaveBody,
  profileTargetsForTask, routingTargetLabel } from './modelProfileView.js'

const profiles = [
  { id: 'old', name: '旧档案', model: 'chat-old' },
  { id: 'chat', purpose: 'chat', name: '对话档案', model: 'chat-new' },
  { id: 'embed', purpose: 'embedding', name: '嵌入档案', model: 'custom-vector' },
]

test('legacy profiles remain chat while parameters and sessions exclude embedding profiles', () => {
  assert.equal(profilePurpose(profiles[0]), 'chat')
  assert.deepEqual(chatProfiles(profiles).map(p => p.id), ['old', 'chat'])
  assert.equal(paramProfileSelection(profiles, 'embed', 'chat'), 'chat')
  assert.equal(paramProfileSelection([profiles[2]], 'embed', 'embed'), '')
  assert.equal(chatProfileSelection(profiles, 'embed'), '')
  assert.equal(chatProfileSelection(profiles, 'old'), 'old')
  assert.equal(canRemoveProfile(profiles[2], [profiles[2]]), true)
  assert.equal(canRemoveProfile(profiles[0], [profiles[0], profiles[2]]), false)
})

test('routing uses per-row choices without leaking chat profiles into embeddings or the reverse', () => {
  const embed = { task: 'embed', targets: [{ id: 'disabled', label: '未配置', purpose: 'embedding' }, { id: 'legacy', label: '旧配置', model: 'old-vector', purpose: 'embedding' }, ...profiles] }
  assert.deepEqual(profileTargetsForTask(embed, profiles).map(p => p.id), ['disabled', 'legacy', 'embed'])
  assert.deepEqual(profileTargetsForTask({ task: 'chat', targets: embed.targets }, profiles).map(p => p.id), ['old', 'chat'])
  assert.deepEqual(profileTargetsForTask({ task: 'embed' }, profiles).map(p => p.id), ['disabled', 'legacy', 'embed'])
  assert.deepEqual(profileTargetsForTask({ task: 'chat', targets: [] }, profiles), [])
  assert.equal(routingTargetLabel({ label: '未配置', model: '' }), '未配置')
})

test('discovery prioritizes the requested purpose without deleting unknown service model names', () => {
  const models = [{ id: 'custom-unknown' }, { id: 'vector', embedding: true }, { id: 'chat', embedding: false }]
  assert.deepEqual(preferredDiscoveredModels(models, 'embedding').map(m => m.id), ['vector', 'custom-unknown', 'chat'])
  assert.deepEqual(preferredDiscoveredModels(models, 'chat').map(m => m.id), ['custom-unknown', 'chat', 'vector'])
  assert.equal(discoveredModelSelection(models, 'embedding'), 'vector')
  assert.equal(discoveredModelSelection([{ id: 'unknown' }], 'embedding'), '')
  assert.equal(discoveredModelSelection(models, 'embedding', ' my-endpoint '), 'my-endpoint')
  assert.deepEqual(models.map(m => m.id), ['custom-unknown', 'vector', 'chat'])
})

test('embedding saves preserve masked keys and only service/model changes invalidate the assigned space', () => {
  const previous = { id: 'embed', purpose: 'embedding', provider: 'custom', baseUrl: 'https://example.invalid/v1', model: 'vector' }
  const editor = { ...previous, name: ' 向量 ', apiKey: '', note: ' 本地备注 ' }
  assert.deepEqual(profileSaveBody(editor), { name: '向量', purpose: 'embedding', provider: 'custom', baseUrl: previous.baseUrl, model: 'vector', note: '本地备注', apiKey: '__KEEP__' })
  assert.equal(profileSaveBody({ ...editor, id: '', apiKey: '' }).apiKey, '')
  assert.equal(embeddingProfileSpaceChanged(previous, editor, 'embed'), false)
  assert.equal(embeddingProfileSpaceChanged(previous, { ...editor, model: 'new-vector' }, 'embed'), true)
  assert.equal(embeddingProfileSpaceChanged(previous, { ...editor, model: 'new-vector' }, 'other'), false)
})

function deferred() { let resolve, reject; const promise = new Promise((ok, fail) => { resolve = ok; reject = fail }); return { promise, resolve, reject } }

test('a response from an old profile cannot overwrite the new endpoint model list or suggest its address', async () => {
  const calls = [], states = [], results = []
  const loader = createModelDiscoveryLoader({ load: input => { const d = deferred(); calls.push({ ...d, input }); return d.promise }, onState: value => states.push(value), onResult: result => results.push(result) })
  const first = loader.request({ profileId: 'A', baseUrl: 'https://a.invalid/v1', purpose: 'chat' })
  loader.reset()
  const second = loader.request({ profileId: 'B', baseUrl: 'https://b.invalid/v1', purpose: 'embedding' })
  calls[1].resolve({ ok: true, models: [{ id: 'B-vector', embedding: true }] }); await second
  const latest = structuredClone(states.at(-1))
  calls[0].resolve({ ok: true, suggestedBaseUrl: 'https://a.invalid/fixed', models: [{ id: 'A-chat' }] }); await first
  assert.deepEqual(states.at(-1), latest)
  assert.equal(results.length, 1)
  assert.equal(results[0].models[0].id, 'B-vector')
})

test('purpose/key/address reset and disposal suppress old failures and late model discovery', async () => {
  const d = deferred(), states = [], results = []
  const loader = createModelDiscoveryLoader({ load: () => d.promise, onState: value => states.push(value), onResult: value => results.push(value) })
  const request = loader.request({ baseUrl: 'https://example.invalid', purpose: 'chat' }); loader.reset()
  d.reject(new Error('old endpoint failed')); await request
  assert.equal(states.at(-1).ok, null)
  assert.equal(states.at(-1).message, '')
  loader.dispose(); await loader.request({ purpose: 'embedding' })
  assert.equal(results.length, 0)
})
