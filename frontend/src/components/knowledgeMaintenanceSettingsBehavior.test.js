import test from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import { compileScript, parse } from '@vue/compiler-sfc'
import * as vue from 'vue'
import * as modelProfileView from '../utils/modelProfileView.js'
import * as knowledgeSettings from '../utils/knowledgeSettings.js'
import * as knowledgeIndexView from '../utils/knowledgeIndexView.js'

const source = readFileSync(new URL('./KnowledgeSettingsPanel.vue', import.meta.url), 'utf8')
const compiled = compileScript(parse(source).descriptor, { id: 'kb-maintenance-settings' }).content
const setup = compiled.replace(/^import\s+([\s\S]*?)\s+from\s+['"]([^'"]+)['"]\s*;?\r?$/gm,
  (_, names, from) => `const ${names.replace(/\bas\b/g, ':')} = modules[${JSON.stringify(from)}]`).replace('export default', 'return')

function mountPanel(enabled) {
  const previousWindow = globalThis.window
  globalThis.window = new EventTarget()
  const calls = [], messages = []
  const fields = [{ key: 'chunkTarget', label: '示例目标长度', type: 'number', min: 200, max: 4000, default: 800, requiresRebuild: true }]
  let saved = false
  const snapshot = () => ({ fields, values: { chunkTarget: saved ? 900 : 800 }, defaults: { chunkTarget: 800 }, needsRebuild: saved })
  const modules = {
    vue,
    'element-plus': { ElMessage: { success: value => messages.push(value), info: value => messages.push(value) } },
    '../api': { kbApi: {
      async settings() { return snapshot() }, async status() { return { configured: true, enabled: true, stale: saved } },
      async saveSettings(changes) { calls.push(changes); saved = true; return snapshot() },
      rebuild() { throw new Error('saving must not rebuild in the browser') },
    } },
    '../api/knowledgeMaintenance.js': { knowledgeMaintenanceApi: { async status() { return { enabled, pending: saved, message: enabled ? '配置变更待后台维护，尚未更新完成' : '自动维护已关闭' } } } },
    '../utils/modelProfileView': modelProfileView, '../utils/knowledgeSettings': knowledgeSettings, '../utils/knowledgeIndexView': knowledgeIndexView,
  }
  const component = new Function('modules', setup)(modules)
  component.render = () => null
  const renderer = vue.createRenderer({ createElement: tag => ({ tag, children: [] }), createText: text => ({ text }), createComment: text => ({ text }), insert: (child, parent) => parent.children.push(child), remove() {}, setText() {}, setElementText() {}, patchProp() {}, parentNode: () => null, nextSibling: () => null })
  const app = renderer.createApp(component, { active: false })
  app.mount({ children: [] })
  return { state: app._instance.setupState, calls, messages, cleanup() { app.unmount(); globalThis.window = previousWindow } }
}

test('saving rebuild-sensitive parameters shows real backend pending state and never starts a browser rebuild', async () => {
  const h = mountPanel(true)
  try {
    await h.state.load()
    assert.equal(h.state.summaryText, '等待建立索引')
    h.state.values.chunkTarget = 900
    assert.equal(await h.state.save(), true)
    assert.deepEqual(h.calls, [{ chunkTarget: 900 }])
    assert.equal(h.state.maintenance.pending, true)
    assert.equal(h.state.dirty, false)
    assert.match(h.messages.at(-1), /配置变更待后台维护，尚未更新完成/)
    assert.equal(h.state.needsRebuild, true)
    assert.equal(h.state.summaryText, '索引待更新')
  } finally { h.cleanup() }
})

test('saved settings honor disabled automatic maintenance and retain a visible maintenance entry hint', async () => {
  const h = mountPanel(false)
  try {
    await h.state.load()
    h.state.values.chunkTarget = 900
    await h.state.save()
    assert.match(h.messages.at(-1), /自动维护已关闭/)
    assert.match(h.state.maintenanceHint, /立即维护/)
    assert.doesNotMatch(h.messages.at(-1), /后台将自动/)
  } finally { h.cleanup() }
})
