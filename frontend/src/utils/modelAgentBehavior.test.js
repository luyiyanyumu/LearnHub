import test from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import { compileScript, parse } from '@vue/compiler-sfc'
import * as vue from 'vue'
import * as profileView from './modelProfileView.js'
import * as noteMerge from './agentNoteMerge.js'
import * as noteEdit from './agentNoteEdit.js'
import * as retrievalDisplay from './retrievalDisplay.js'
import { fixHtmlQuotes } from './htmlQuotes.js'
import { groundingDisplay } from './groundingDisplay.js'

const source = readFileSync(new URL('../components/AgentPanel.vue', import.meta.url), 'utf8')
const compiled = compileScript(parse(source).descriptor, { id: 'agent-model-behavior-test' }).content
const setupSource = compiled.replace(/^import\s+([\s\S]*?)\s+from\s+['"]([^'"]+)['"]\s*;?\r?$/gm,
  (_, names, from) => `const ${names.replace(/\bas\b/g, ':')} = modules[${JSON.stringify(from)}]`)
  .replace('export default', 'return')
const componentFactory = new Function('modules', setupSource)

function deferred() { let resolve; const promise = new Promise(ok => { resolve = ok }); return { promise, resolve } }
const flush = async () => { for (let i = 0; i < 5; i++) await Promise.resolve() }

test('restored sessions retain their chat profile when model profiles arrive before the session list', async () => {
  const previousWindow = globalThis.window, previousStorage = globalThis.localStorage
  globalThis.window = new EventTarget()
  globalThis.localStorage = { getItem: () => 'saved-session', setItem() {}, removeItem() {} }
  const sessions = deferred(), profiles = deferred(), events = []
  window.addEventListener('lh-agent-current', event => events.push(event.detail))
  const modules = {
    vue, 'vue-router': { useRouter: () => ({}) },
    'element-plus': { ElMessage: {}, ElMessageBox: {} },
    '../api': { aiApi: { async status() { return { configured: true } }, async session() { return { messages: [] } } },
      modelApi: { sessions: () => sessions.promise, profiles: () => profiles.promise }, settingsApi: { async get() { return {} } }, categoryApi: {}, noteApi: {} },
    '../utils/htmlQuotes': { fixHtmlQuotes }, '../utils/agentNoteMerge': noteMerge,
    '../utils/agentNoteEdit': noteEdit, '../utils/retrievalDisplay': retrievalDisplay,
    '../utils/groundingDisplay': { groundingDisplay }, '../utils/modelProfileView': profileView,
    '../composables/useTheme': { isDark: vue.ref(false) },
  }
  const renderer = vue.createRenderer({
    createElement: tag => ({ tag, children: [] }), createText: text => ({ text }), createComment: text => ({ text }),
    insert: (child, parent) => parent.children.push(child), remove() {}, setText() {}, setElementText() {}, patchProp() {}, parentNode: () => null, nextSibling: () => null,
  })
  const component = componentFactory(modules)
  component.render = () => null
  const app = renderer.createApp(component)
  try {
    app.mount({ children: [] })
    const state = app._instance.setupState
    profiles.resolve({ profiles: [{ id: 'saved-chat', name: '对话', purpose: 'chat', model: 'chat-model' }, { id: 'vector', purpose: 'embedding' }] })
    await flush()
    sessions.resolve([{ id: 'saved-session', modelProfileId: 'saved-chat' }])
    await flush()
    assert.equal(state.chatProfile, 'saved-chat')
    assert.equal(state.currentModelLabel, '代码答疑 · 对话（chat-model）')
    assert.deepEqual(state.modelProfiles.map(profile => profile.id), ['saved-chat'])
    assert.equal(events.at(-1).modelProfileId, 'saved-chat')
  } finally {
    app.unmount()
    globalThis.window = previousWindow
    globalThis.localStorage = previousStorage
  }
})
