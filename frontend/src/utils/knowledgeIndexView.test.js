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

test('changed model spaces require explicit rebuilding and never describe incompatible vectors as available', () => {
  const view = knowledgeIndexView({ configured: true, enabled: true, chunks: 20, compatibleChunks: 0, embeddingChanged: true, stale: true })
  assert.equal(view.label, '嵌入索引需重建')
  assert.equal(view.keywordOnly, true)
  assert.equal(view.canRebuild, true)
  assert.equal(view.warning, true)
  assert.match(view.description, /手动重建/)
  assert.equal(knowledgeIndexView({ chunks: 20, compatibleChunks: 8, stale: true }).keywordOnly, false)
  assert.equal(knowledgeIndexView({ chunks: 20, compatibleChunks: 8, stale: true }).label, '索引待更新')
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
