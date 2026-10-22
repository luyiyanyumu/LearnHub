import test from 'node:test'
import assert from 'node:assert/strict'
import { createKnowledgeStatusLoader, knowledgeIndexView } from './knowledgeIndexView.js'

test('unconfigured and disabled embeddings leave keyword retrieval available without enabling rebuild', () => {
  const unconfigured = knowledgeIndexView({ configured: false, enabled: true, chunks: 20, model: '', reason: '未选择嵌入档案' })
  assert.equal(unconfigured.label, '未配置嵌入模型')
  assert.equal(unconfigured.keywordOnly, true)
  assert.equal(unconfigured.canRebuild, false)
  assert.match(unconfigured.description, /关键词检索仍可用/)
  assert.equal(knowledgeIndexView({ configured: true, enabled: false }).canRebuild, false)
  assert.doesNotMatch(unconfigured.description, /BGE|Ollama/i)
})

test('changed model spaces wait for backend maintenance and never describe incompatible vectors as available', () => {
  const view = knowledgeIndexView({ configured: true, enabled: true, chunks: 20, compatibleChunks: 0, embeddingChanged: true, stale: true }, false, { enabled: true, pending: true, message: '模型变更已排队，等待后台更新' })
  assert.equal(view.label, '嵌入索引待更新')
  assert.equal(view.keywordOnly, true)
  assert.equal(view.canRebuild, true)
  assert.equal(view.warning, true)
  assert.match(view.description, /模型变更已排队/)
  assert.doesNotMatch(view.description, /手动重建|已更新完成/)
  assert.equal(knowledgeIndexView({ chunks: 20, compatibleChunks: 8, stale: true }).keywordOnly, false)
  assert.equal(knowledgeIndexView({ chunks: 20, compatibleChunks: 8, stale: true }).label, '索引待更新')
})

test('disabled or unread maintenance never promises an automatic rebuild', () => {
  const stale = { configured: true, chunks: 20, compatibleChunks: 0, stale: true }
  assert.match(knowledgeIndexView(stale, false, { enabled: false }).description, /自动维护已关闭/)
  assert.doesNotMatch(knowledgeIndexView(stale, false, { enabled: false }).description, /后台会自动/)
  assert.match(knowledgeIndexView(stale).description, /查看后台更新状态/)
})

test('new and legacy status snapshots distinguish no index, ready, loading and unavailable', () => {
  assert.equal(knowledgeIndexView({ configured: true, chunks: 0, compatibleChunks: 0 }).label, '等待建立索引')
  assert.equal(knowledgeIndexView({ chunks: 20, model: 'legacy-model' }).label, '索引可用')
  assert.equal(knowledgeIndexView({ configured: true, chunks: 20, compatibleChunks: 20 }).label, '索引可用')
  assert.equal(knowledgeIndexView(null, true).canRebuild, false)
  assert.equal(knowledgeIndexView(null).unavailable, true)
  assert.doesNotMatch(knowledgeIndexView(null).description, /Ollama|BGE/i)
})

function deferred() { let resolve, reject; const promise = new Promise((ok, fail) => { resolve = ok; reject = fail }); return { promise, resolve, reject } }

test('saving new embedding configuration refreshes status without accepting an old configured response', async () => {
  const calls = [], states = []
  const loader = createKnowledgeStatusLoader({ load: () => { const d = deferred(); calls.push(d); return d.promise }, onState: value => states.push(value) })
  const before = loader.refresh(), after = loader.refresh()
  calls[1].resolve({ configured: false, chunks: 20 }); await after
  calls[0].resolve({ configured: true, chunks: 20, compatibleChunks: 20 }); await before
  assert.equal(states.at(-1).status.configured, false)
  const pending = loader.refresh(); loader.dispose()
  calls[2].reject(new Error('obsolete')); await pending
  assert.equal(states.at(-1).loading, true)
})
