import test from 'node:test'
import assert from 'node:assert/strict'
import { knowledgeWorkspaceQuery, knowledgeWorkspaceTab } from './knowledgeWorkspace.js'

test('retrieval is the initial workspace; all explicit tabs remain linkable', () => {
  assert.equal(knowledgeWorkspaceTab({}), 'search')
  for (const tab of ['search', 'graph', 'wiki']) assert.equal(knowledgeWorkspaceTab({ tab }), tab)
  for (const tab of ['other', ['wiki', 'graph'], null]) assert.equal(knowledgeWorkspaceTab({ tab }), 'search')
})

test('leaving Wiki clears its location without losing unrelated query parameters', () => {
  const query = { tab: 'wiki', topic: 'entity-a', section: 'section-2', heading: '细节', context: 'keep' }
  assert.deepEqual(knowledgeWorkspaceQuery(query, 'graph'), { tab: 'graph', context: 'keep' })
  assert.equal(query.section, 'section-2')
})

test('explicit Wiki selection retains exact key and discards previous heading', () => {
  assert.deepEqual(knowledgeWorkspaceQuery({ heading: 'old' }, 'wiki', 'entity-43e538e6c5'), { tab: 'wiki', topic: 'entity-43e538e6c5' })
  assert.deepEqual(knowledgeWorkspaceQuery({}, 'wiki', '<script>'), { tab: 'wiki' })
})
