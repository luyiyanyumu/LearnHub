import test from 'node:test'
import assert from 'node:assert/strict'
import { isGeneratedGuide, retrievalChannels, retrievalGroups, retrievalKey, retrievalPosition, retrievalRelations, retrievalSnippet, retrievalSourcePath, retrievalTitle, retrievalTooltip } from './retrievalDisplay.js'

test('table snippets remove long layout attributes before truncating and keep cell boundaries', () => {
  const text = `<table style="${'border: 1px solid black; width: 100%; '.repeat(8)}"><thead><tr><th>概念</th><th>说明</th></tr></thead>
    <tbody><tr><td><p>ReAct</p></td><td><span title="a > b">推理&nbsp;与行动</span><br>工具反馈</td></tr></tbody></table>`
  assert.equal(retrievalSnippet({ text }), '概念 说明 ReAct 推理 与行动 工具反馈')
  assert.equal(retrievalSnippet({ text: '<div style="color:red">' + '知识'.repeat(80) + '</div>' }), '知识'.repeat(60) + '…')
})

test('snippets preserve raw Java generics and code operators', () => {
  const text = 'List<T> Map<K, V> Comparator<? super T> a < b && b > c x << 2 y >> 1 <T extends Comparable<T>>'
  assert.equal(retrievalSnippet({ text }), text)
  assert.equal(retrievalSnippet({ text: 'List<B> Predicate<P> Set<U> <U extends Serializable>' }), 'List<B> Predicate<P> Set<U> <U extends Serializable>')
  assert.equal(retrievalSnippet({ text: '<P><B>真实排版</B></P>' }), '真实排版')
  assert.equal(retrievalSnippet({ text: '<pre><code>List<T> value = a < b ? left : right;</code></pre>' }), 'List<T> value = a < b ? left : right;')
})

test('snippets decode common and numeric entities once without treating escaped code as markup', () => {
  assert.equal(retrievalSnippet({ text: 'Map&lt;String, T&gt; &amp;&amp; x &lt;= 3 &quot;引号&quot; &apos;值&apos; &#x4E2D;&#25991; &nbsp; &rarr; &#x1F680;' }),
    'Map<String, T> && x <= 3 "引号" \'值\' 中文 → 🚀')
  assert.equal(retrievalSnippet({ text: '&lt;table&gt; &amp;lt;T&amp;gt; &unknown; &#x110000; &#xD800;' }),
    '<table> &lt;T&gt; &unknown; &#x110000; &#xD800;')
})

test('snippet cleanup uses inert strings, drops hidden markup, and accepts missing content', () => {
  assert.equal(retrievalSnippet({ text: '<style>p { color: red }</style><!-- noise --><p onclick="throw 1">正文</p><script>throw 1</script><img src="x" onerror="throw 1">' }), '正文')
  assert.equal(retrievalSnippet({ snippet: '<b>优先摘要</b>', text: '全文' }), '优先摘要')
  assert.equal(retrievalSnippet(null), '')
})

test('labels report only actual retrieval channels, in a stable order', () => {
  assert.deepEqual(retrievalChannels({ channels: ['graph', 'keyword', 'vector', 'graph'] }), ['关键词', '语义', '图谱'])
  assert.deepEqual(retrievalChannels({ channels: ['graph'] }), ['图谱'])
  assert.deepEqual(retrievalChannels({ channels: ['vector', 'unknown'] }), ['语义'])
  assert.deepEqual(retrievalChannels({ channels: ['tool', 'graph', 'keyword', 'tool'] }), ['关键词', '图谱', '工具读取'])
  assert.deepEqual(retrievalChannels({ channels: ['tool'] }), ['工具读取'])
  assert.deepEqual(retrievalChannels({ type: 'graph', title: '图谱背景' }), [])
  assert.deepEqual(retrievalChannels({ channels: 'vector' }), [])
})

test('passage keys retain separate chunks of a source and work for search and agent responses', () => {
  const hit = { sourceType: 'note', sourceId: 4, seq: 0 }
  assert.equal(retrievalKey(hit), retrievalKey({ type: 'note', id: 4, seq: 0 }))
  assert.notEqual(retrievalKey(hit), retrievalKey({ ...hit, seq: 1 }))
  assert.notEqual(retrievalKey(hit), retrievalKey({ type: 'note', id: 4 }))
  assert.notEqual(retrievalKey(hit), retrievalKey({ ...hit, sourceType: 'file' }))
  assert.notEqual(retrievalKey({ type: 'note', id: 4, seq: -1 }), retrievalKey({ type: 'note', id: 4 }))
})

test('server passage keys take priority for both knowledge hits and agent references', () => {
  const passageKey = 'note:4:window-sha256'
  assert.equal(retrievalKey({ sourceType: 'note', sourceId: 4, seq: -1, passageKey, text: '正文' }), passageKey)
  assert.equal(retrievalKey({ type: 'note', id: 4, seq: -1, passageKey }), passageKey)
  assert.notEqual(retrievalKey({ passageKey }), retrievalKey({ passageKey: 'note:4:other-window-sha256' }))
})

test('legacy evidence windows keep distinct keys even when their displayed excerpts match', () => {
  const commonStart = '共同内容'.repeat(40)
  const first = { sourceType: 'note', sourceId: 4, seq: -1, text: commonStart + '窗口 A' }
  const second = { ...first, text: commonStart + '窗口 B' }
  assert.notEqual(retrievalKey(first), retrievalKey(second))
  assert.equal(retrievalKey(first), retrievalKey({ ...first, passageKey: '' }))
  assert.equal(retrievalKey(first), retrievalKey({ type: 'note', id: 4, seq: -1, snippet: first.text }))
  const displayed = { type: 'note', id: 4, seq: -1, snippet: commonStart.slice(0, 120), passageKey: retrievalKey(first) }
  assert.equal(retrievalKey(displayed), retrievalKey(first))
})

test('passage positions are one-based and do not invent positions for whole sources', () => {
  assert.equal(retrievalPosition({ seq: 0 }), '第 1 段')
  assert.equal(retrievalPosition({ seq: 6 }), '第 7 段')
  assert.equal(retrievalPosition({ seq: -1 }), '证据片段')
  for (const seq of [null, undefined, 0.5, '2']) assert.equal(retrievalPosition({ seq }), '')
})

test('reference tooltips retain graph support and handle old wiki/graph references', () => {
  const hit = { title: 'ReAct', seq: 2, channels: ['graph', 'vector'],
    graphRelations: [' ReAct —扩展→ Planning ', '', 'ReAct —扩展→ Planning', null], score: 0.032 }
  assert.deepEqual(retrievalRelations(hit), ['ReAct —扩展→ Planning'])
  assert.equal(retrievalTooltip(hit), 'ReAct\n第 3 段\n召回：语义 / 图谱\n图谱关系：\nReAct —扩展→ Planning')
  assert.equal(retrievalTooltip({ title: 'Wiki 背景' }), 'Wiki 背景')
  assert.equal(retrievalTooltip({ type: 'graph', title: '知识图谱', graphRelations: 'legacy' }), '知识图谱')
  assert.equal(retrievalTooltip(null), '')
})

test('retrieval sources open the exact note, quick reference or file with both response shapes', () => {
  assert.equal(retrievalSourcePath({ type: 'note', id: 12 }), '/notes/12')
  assert.equal(retrievalSourcePath({ sourceType: 'note', sourceId: 12, seq: 3 }), '/notes/12')
  assert.equal(retrievalSourcePath({ type: 'quick_ref', id: 8 }), '/refs?read=8')
  assert.equal(retrievalSourcePath({ sourceType: 'quick_ref', sourceId: '8' }), '/refs?read=8')
  assert.equal(retrievalSourcePath({ type: 'ref', id: 8 }), '/refs?read=8')
  assert.equal(retrievalSourcePath({ type: 'file', id: 31 }), '/files?read=31')
  assert.equal(retrievalSourcePath({ sourceType: 'file', sourceId: '9223372036854775807' }), '/files?read=9223372036854775807')
})

test('unsupported references and invalid source ids do not navigate to an unrelated library', () => {
  for (const hit of [null, {}, { type: 'wiki', id: 3 }, { type: 'graph', id: 4 }, { type: 'file', id: 0 },
    { type: 'file', id: -1 }, { type: 'file', id: '../notes/2' }, { type: 'note', id: '2?read=4' }, { type: 'ref', id: NaN }]) {
    assert.equal(retrievalSourcePath(hit), null)
  }
})

test('Wiki routes preserve the exact topic and section without navigating by an unrelated source id', () => {
  const path = retrievalSourcePath({ type: 'wiki', id: 71, topicKey: 'entity-e98baab012', sectionKey: 'section-2', heading: 'ReAct / 循环机制' })
  const url = new URL(path, 'https://learnhub.local')
  assert.equal(url.pathname, '/knowledge')
  assert.equal(url.searchParams.get('tab'), 'wiki')
  assert.equal(url.searchParams.get('topic'), 'entity-e98baab012')
  assert.equal(url.searchParams.get('section'), 'section-2')
  assert.equal(url.searchParams.get('heading'), 'ReAct / 循环机制')
  assert.equal(retrievalSourcePath({ sourceType: 'wiki', topicKey: 'cat-3' }), '/knowledge?tab=wiki&topic=cat-3')
  for (const topicKey of ['', '../notes/2', 'entity-1?tab=graph', ['entity-1'], null]) {
    assert.equal(retrievalSourcePath({ type: 'wiki', id: 71, topicKey }), null)
  }
})

test('generated guides are separated from original evidence even when they list valid source references', () => {
  const wiki = { type: 'wiki', id: 71, title: 'ReAct', heading: '循环机制', topicKey: 'entity-1', sectionKey: 'section-2',
    channels: ['wiki'], generatedGuide: true, sourceRefs: ['note:1', 'file:2'] }
  const original = { type: 'note', id: 1, title: '原文', channels: ['wiki', 'vector'] }
  const context = { type: 'graph', title: '知识图谱' }
  assert.deepEqual(retrievalGroups([wiki, original, context]).map(group => [group.key, group.label, group.items]), [
    ['source', '原文证据', [original]], ['guide', '生成导览', [wiki]], ['context', '关系背景', [context]],
  ])
  assert.equal(isGeneratedGuide(wiki), true)
  assert.equal(isGeneratedGuide(original), false)
  assert.deepEqual(retrievalChannels(original), ['语义', 'Wiki 定位'])
  assert.equal(retrievalTitle(wiki), 'ReAct · 循环机制')
  assert.match(retrievalTooltip(wiki), /模型生成的知识导览，请结合原文核对/)
  assert.notEqual(retrievalKey(wiki), retrievalKey({ ...wiki, sectionKey: 'section-3' }))
  assert.deepEqual(retrievalGroups(null), [])
})
