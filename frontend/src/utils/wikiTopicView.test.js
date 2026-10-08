import test from 'node:test'
import assert from 'node:assert/strict'
import { wikiTopicCountLabel, wikiTopicState, wikiTopicView } from './wikiTopicView.js'

const topics = [
  { topicKey: 'entity-a', topicType: 'entity', title: 'Agent Rewind', generated: true, stale: true, itemCount: 3 },
  { topicKey: 'cat-2', topicType: 'category', title: '智能体', generated: false, stale: false, itemCount: 8 },
  { topicKey: 'cat-1', topicType: 'category', title: '论文阅读', generated: true, itemCount: 2 },
  { topicKey: 'tag-1', topicType: 'tag', title: 'RAG', aliases: ['检索增强生成'], generated: true, itemCount: 6 },
  { topicKey: 'index', topicType: 'index', title: '知识索引', generated: true, itemCount: 42 },
  { topicKey: 'lint', topicType: 'lint', title: '自检报告', generated: true, itemCount: 7 },
  { topicKey: 'custom', topicType: 'future', title: '特殊主题', generated: false },
]

test('navigation preserves API order inside established groups and includes unknown page types', () => {
  const before = JSON.stringify(topics)
  const view = wikiTopicView(topics)
  assert.deepEqual(view.groups.map(group => group.type), ['category', 'tag', 'entity', 'index', 'lint', 'other'])
  assert.deepEqual(view.groups[0].items.map(topic => topic.topicKey), ['cat-2', 'cat-1'])
  assert.equal(view.groups[0].items[0], topics[1])
  assert.equal(view.totalCount, 7)
  assert.equal(view.visibleCount, 7)
  assert.equal(view.generatedCount, 5)
  assert.deepEqual(view.counts, { stale: 1, missing: 2, generated: 4 })
  assert.equal(view.hasFilter, false)
  assert.equal(JSON.stringify(topics), before)
})

test('query, type and generation status intersect without changing counts for the full library', () => {
  const matched = wikiTopicView(topics, { query: '  AGENT ', type: 'entity', state: 'stale' })
  assert.equal(matched.visibleCount, 1)
  assert.equal(matched.groups[0].items[0].topicKey, 'entity-a')
  assert.equal(matched.totalCount, 7)
  assert.equal(matched.generatedCount, 5)
  assert.equal(matched.hasFilter, true)
  assert.equal(wikiTopicView(topics, { query: 'Agent', state: 'generated' }).visibleCount, 0)
  assert.equal(wikiTopicView(topics, { query: '检索增强' }).groups[0].items[0].topicKey, 'tag-1')
  assert.equal(wikiTopicView(topics, { query: 'cat-2', state: 'missing' }).visibleCount, 1)
  assert.equal(wikiTopicView(topics, { type: 'lint' }).groups[0].label, '自检')
})

test('stale has priority and generated states never imply source verification', () => {
  assert.equal(wikiTopicState({ stale: true, generated: true }), 'stale')
  assert.equal(wikiTopicState({ stale: true, generated: false }), 'stale')
  assert.equal(wikiTopicState({ generated: true }), 'generated')
  assert.equal(wikiTopicState({}), 'missing')
  assert.equal(wikiTopicState({ generated: 'false', stale: 'false' }), 'missing')
})

test('item counts retain their page-type meanings and invalid counts are not fabricated', () => {
  assert.equal(wikiTopicCountLabel(topics[0]), '3 个关联来源')
  assert.equal(wikiTopicCountLabel(topics[1]), '8 条素材')
  assert.equal(wikiTopicCountLabel(topics[4]), '收录 42 个页面')
  assert.equal(wikiTopicCountLabel(topics[5]), '7 条问题')
  assert.equal(wikiTopicCountLabel({ topicType: 'entity', itemCount: 0 }), '跨页编译生成')
  assert.equal(wikiTopicCountLabel({ topicType: 'future', itemCount: 2 }), '2 项关联内容')
  for (const itemCount of [undefined, null, -1, NaN, '3', 1.5]) {
    assert.equal(wikiTopicCountLabel({ itemCount }), '')
  }
})

test('empty or malformed lists are safe and legacy topics without a type remain visible', () => {
  assert.equal(wikiTopicView(null).totalCount, 0)
  assert.equal(wikiTopicView([null, {}, { topicKey: 1 }]).visibleCount, 0)
  const view = wikiTopicView([{ topicKey: 'legacy', title: '旧主题' }], { type: 'category' })
  assert.equal(view.groups[0].type, 'category')
  assert.equal(view.counts.missing, 1)
  assert.equal(wikiTopicView(topics, { query: '不存在的内容' }).visibleCount, 0)
  assert.equal(wikiTopicView(topics, { query: '   ' }).hasFilter, false)
})
