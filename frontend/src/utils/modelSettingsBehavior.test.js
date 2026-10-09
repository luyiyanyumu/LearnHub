import test from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import { compileScript, parse } from '@vue/compiler-sfc'
import * as vue from 'vue'
import * as profileView from './modelProfileView.js'
import { extractPromptFromMd } from './promptFromMd.js'

// Run the real Settings setup with mock APIs. No DOM, user database or model service is used.
const source = readFileSync(new URL('../components/SettingsDialog.vue', import.meta.url), 'utf8')
const compiled = compileScript(parse(source).descriptor, { id: 'settings-behavior-test' }).content
const setupSource = compiled.replace(/^import\s+([\s\S]*?)\s+from\s+['"]([^'"]+)['"]\s*;?\r?$/gm,
  (_, names, from) => `const ${names.replace(/\bas\b/g, ':')} = modules[${JSON.stringify(from)}]`)
  .replace('export default', 'return')
const componentFactory = new Function('modules', setupSource)

function mountSettings(initialProfiles = []) {
  const previousWindow = globalThis.window
  globalThis.window = new EventTarget()
  const calls = [], messages = []
  let profiles = structuredClone(initialProfiles)
  let target = 'disabled'
  const modelApi = {
    async profiles() { return { profiles, presets: [{ name: '自定义', provider: 'custom' }], activeId: profiles.find(p => p.active)?.id,
      routing: [{ task: 'embed', field: 'modelForEmbed', target, configured: target !== 'disabled', targets: profileView.profileTargetsForTask({ task: 'embed' }, profiles) }] } },
    async createProfile(body) { calls.push({ method: 'create', body }); profiles.push({ id: 'created', ...body }); return {} },
    async updateProfile(id, body) { calls.push({ method: 'update', id, body }); profiles = profiles.map(p => p.id === id ? { ...p, ...body } : p); return {} },
    async removeProfile(id) { calls.push({ method: 'remove', id }); profiles = profiles.filter(p => p.id !== id); target = 'disabled'; return {} },
    async activateProfile(id) { calls.push({ method: 'activate', id }) },
    async testProfile(id) { calls.push({ method: 'test', id }); return { ok: true, dimension: 1536, ms: 12, message: '嵌入接口可用' } },
    async discoverModels() { throw new Error('discovery must be replaced by this test') },
  }
  const settingsApi = { async update(body) { calls.push({ method: 'routing', body }); target = body.modelForEmbed } }
  const message = kind => value => messages.push({ kind, value })
  const modules = {
    vue, 'element-plus': { ElMessage: { success: message('success'), warning: message('warning'), error: message('error'), info: message('info') }, ElMessageBox: { confirm: async () => {} } },
    '../api': { modelApi, settingsApi, categoryApi: {}, tagApi: {}, aiApi: {}, wikiApi: {} },
    '../utils/modelProfileView': profileView, '../utils/promptFromMd': { extractPromptFromMd },
  }
  const renderer = vue.createRenderer({
    createElement: tag => ({ tag, children: [] }), createText: text => ({ text }), createComment: text => ({ text }),
    insert: (child, parent) => parent.children.push(child), remove() {}, setText() {}, setElementText() {}, patchProp() {}, parentNode: () => null, nextSibling: () => null,
  })
  const component = componentFactory(modules)
  component.render = () => null
  const app = renderer.createApp(component)
  app.mount({ children: [] })
  return { state: app._instance.setupState, calls, messages, modelApi,
    cleanup() { app.unmount(); globalThis.window = previousWindow } }
}

const chat = { id: 'chat', name: '对话', purpose: 'chat', provider: 'custom', model: 'chat-model', baseUrl: 'https://chat.invalid/v1', active: true }
const embed = { id: 'embed', name: '向量', purpose: 'embedding', provider: 'custom', model: 'vector-model', baseUrl: 'https://vector.invalid/v1', hasKey: true }

test('embedding creation is explicit, blank by default, refreshes configuration and never activates or tests automatically', async () => {
  const h = mountSettings([chat])
  try {
    let events = 0
    window.addEventListener(profileView.MODEL_CONFIGURATION_CHANGED_EVENT, () => events++)
    await h.state.loadRouting()
    h.state.addEmbeddingProfile()
    assert.equal(h.state.editor.purpose, 'embedding')
    assert.equal(h.state.editor.provider, 'custom')
    assert.equal(h.state.editor.model, '')
    h.state.editor.baseUrl = 'https://vector.invalid/v1'
    h.state.editor.model = 'unknown-vector-endpoint'
    await h.state.saveProfile()
    assert.equal(h.calls[0].body.purpose, 'embedding')
    assert.equal(h.calls[0].body.model, 'unknown-vector-endpoint')
    assert.deepEqual(h.calls.map(c => c.method), ['create'])
    assert.equal(events, 1)
    assert.deepEqual(h.state.chatProfileOptions.map(p => p.id), ['chat'])
    assert.equal(h.state.paramProfile, 'chat')
    await h.state.activateProfile({ ...embed, id: 'created' })
    assert.equal(h.calls.length, 1)
  } finally { h.cleanup() }
})

test('embedding assignment, service change and deletion notify status while rebuild remains manual', async () => {
  const h = mountSettings([chat, embed])
  try {
    let events = 0
    window.addEventListener(profileView.MODEL_CONFIGURATION_CHANGED_EVENT, () => events++)
    await h.state.loadRouting()
    await h.state.setTaskTarget(h.state.routing.table[0], 'embed')
    assert.deepEqual(h.calls[0], { method: 'routing', body: { modelForEmbed: 'embed' } })
    h.state.openProfileEditor(embed)
    h.state.editor.model = 'vector-new'
    await h.state.saveProfile()
    assert.equal(h.calls[1].body.apiKey, '__KEEP__')
    assert.match(h.messages.at(-1).value, /重建向量索引/)
    await h.state.removeProfile(embed)
    assert.equal(h.state.routing.table[0].target, 'disabled')
    assert.deepEqual(h.calls.map(c => c.method), ['routing', 'update', 'remove'])
    assert.equal(events, 3)
  } finally { h.cleanup() }
})

test('embedding connection test displays dimension and generation controls stay limited to chat', async () => {
  const h = mountSettings([chat, embed])
  try {
    await h.state.loadRouting()
    await h.state.probeProfile(embed)
    assert.match(h.messages.at(-1).value, /1536 维/)
    h.state.paramProfile = 'embed'
    await h.state.saveParam('temperature', 0.8)
    await h.state.testParamProfile()
    await h.state.activateParamProfile()
    assert.deepEqual(h.calls.map(c => c.method), ['test'])
    h.state.openProfileEditor(chat)
    assert.equal(h.state.editorActiveChat, true)
  } finally { h.cleanup() }
})
