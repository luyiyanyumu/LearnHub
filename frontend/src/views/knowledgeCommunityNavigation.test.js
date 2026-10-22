import test from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import { compileScript, parse } from '@vue/compiler-sfc'
import * as vue from 'vue'

const source = readFileSync(new URL('./Knowledge.vue', import.meta.url), 'utf8')
const compiled = compileScript(parse(source).descriptor, { id: 'knowledge-community-navigation' }).content
const imports = /^import\s+([\s\S]*?)\s+from\s+['"]([^'"]+)['"]\s*;?\r?$/gm
const script = compiled.replace(/^import\s+['"][^'"]+['"]\s*;?\r?$/gm, '')
const factory = new Function('modules', script
  .replace(imports, (_, names, from) => `const ${names.trim().replace(/\bas\b/g, ':')} = modules[${JSON.stringify(from)}]`)
  .replace('export default', 'return'))
const utilities = {}
await Promise.all([...script.matchAll(imports)].map(async match => {
  const from = match[2]
  if (from.startsWith('../utils/')) utilities[from] = await import(new URL(from.endsWith('.js') ? from : `${from}.js`, import.meta.url))
}))
const renderer = vue.createRenderer({
  createElement: tag => ({ tag, children: [] }), createText: text => ({ text }), createComment: text => ({ text }),
  insert: (node, parent) => parent.children.push(node), remove() {}, setText() {}, setElementText() {}, patchProp() {},
  parentNode: () => null, nextSibling: () => null,
})
async function flush() { for (let index = 0; index < 6; index++) { await Promise.resolve(); await vue.nextTick() } }

function fixture() {
  return {
    nodes: [
      { id: 'a', label: '示例甲', aliases: ['alpha'], degree: 3, type: 'concept' },
      { id: 'b', label: '示例乙', degree: 2, type: 'concept' },
      { id: 'c', label: '示例丙', aliases: ['gamma'], degree: 2, type: 'concept' },
      { id: 'd', label: '示例丁', degree: 1, type: 'concept' },
      { id: 'isolated', label: '示例孤立概念', degree: 0, type: 'concept' },
      { id: 'unassigned', label: '示例待归属概念', degree: 1, type: 'concept' },
    ],
    edges: [
      { id: 'ab', source: 'a', target: 'b', relation: 'is_a', origin: 'llm', evidence: '合成证据甲' },
      { id: 'bc', source: 'b', target: 'c', relation: 'uses', origin: 'llm', evidence: '合成跨社区证据' },
      { id: 'cd', source: 'c', target: 'd', relation: 'is_a', origin: 'derived', evidence: '合成推导' },
      { id: 'au', source: 'a', target: 'unassigned', relation: 'uses', origin: 'llm', evidence: '合成证据乙' },
    ],
    ontology: [{ id: 'is_a', label: '属于' }, { id: 'uses', label: '使用' }],
  }
}

async function harness() {
  const previousStorage = Object.getOwnPropertyDescriptor(globalThis, 'localStorage')
  const previousWindow = Object.getOwnPropertyDescriptor(globalThis, 'window')
  Object.defineProperty(globalThis, 'localStorage', { configurable: true, value: { getItem: () => null, setItem() {} } })
  Object.defineProperty(globalThis, 'window', { configurable: true, value: new EventTarget() })
  const route = vue.reactive({ path: '/knowledge', fullPath: '/knowledge?tab=graph', query: { tab: 'graph' } })
  const calls = { reset: 0, focused: [], galaxyReset: 0, galaxyFocused: [] }
  const modules = {
    ...utilities,
    // Exercise the real view state while leaving unrelated loading and background polling out of this navigation test.
    vue: { ...vue, onMounted() {} },
    'vue-router': { useRoute: () => route, useRouter: () => ({ replace() {}, push() {}, resolve: () => ({ fullPath: '' }) }) },
    'element-plus': { ElMessage: { success() {}, warning() {}, error() {} }, ElMessageBox: { confirm: async () => {} } },
    'md-editor-v3': { MdPreview: {} },
    '@element-plus/icons-vue': { Search: {}, Share: {}, Reading: {}, Setting: {}, ArrowRight: {} },
    '../composables/useTheme': { isDark: vue.ref(false) },
    '../api': { fileApi: {}, kbApi: {}, knowledgeApi: {}, kgApi: {}, wikiApi: {}, saveBlob() {} },
    '../api/knowledgeMaintenance.js': { knowledgeMaintenanceApi: {} },
  }
  for (const match of script.matchAll(imports)) if (match[2].endsWith('.vue')) modules[match[2]] = {}
  const component = factory(modules)
  component.render = () => null
  const app = renderer.createApp(component)
  app.mount({ children: [] })
  const state = app._instance.setupState
  state.concept = fixture()
  state.communityOf = { a: 0, b: 0, c: 1, d: 1, isolated: 2 }
  state.communityGroups = [
    { communityId: 0, size: 2, nodeIds: ['a', 'b'] },
    { communityId: 1, size: 2, nodeIds: ['c', 'd'] },
    { communityId: 2, size: 1, nodeIds: ['isolated'] },
  ]
  state.relationFilter = ['is_a', 'uses']
  state.graphRef = { resetView() { calls.reset++ }, focusNode(id) { calls.focused.push(id) } }
  state.galaxyRef = { resetView() { calls.galaxyReset++ }, focusCommunity(id) { calls.galaxyFocused.push(id) } }
  await flush()
  return { state, calls,
    async close() {
      app.unmount()
      await flush()
      if (previousStorage) Object.defineProperty(globalThis, 'localStorage', previousStorage)
      else delete globalThis.localStorage
      if (previousWindow) Object.defineProperty(globalThis, 'window', previousWindow)
      else delete globalThis.window
    },
  }
}
const ids = nodes => nodes.map(node => node.id).sort()

test('concept view starts in the community overview and aggregates the currently visible evidence', async () => {
  const h = await harness()
  try {
    assert.equal(h.state.isCommunityOverview, true)
    assert.equal(h.state.communityFilter, '')
    assert.equal(h.state.focus, null)
    assert.equal(h.state.originFilter, 'direct')
    assert.deepEqual(ids(h.state.overviewConceptGraph.nodes), ['a', 'b', 'c', 'unassigned'])
    assert.deepEqual(ids(h.state.overviewConceptGraph.edges), ['ab', 'au', 'bc'])
    h.state.originFilter = 'derived'
    assert.deepEqual(ids(h.state.overviewConceptGraph.nodes), ['c', 'd'])
    assert.deepEqual(ids(h.state.overviewConceptGraph.edges), ['cd'])
  } finally { await h.close() }
})

test('opening community zero preserves filters and expands only its concepts and boundary neighbors', async () => {
  const h = await harness()
  try {
    h.state.relationFilter = ['uses']
    h.state.originFilter = 'all'
    h.state.showIsolated = true
    h.state.focus = new Set(['d'])
    h.state.focusRootId = 'd'
    h.state.graphQuery = 'old search'
    h.state.conceptSelected = h.state.concept.nodes[3]
    h.state.focusCommunity(0)
    await flush()
    assert.equal(h.state.isCommunityOverview, false)
    assert.equal(h.state.communityFilter, '0')
    assert.equal(h.state.focus, null)
    assert.equal(h.state.focusRootId, '')
    assert.equal(h.state.graphQuery, '')
    assert.equal(h.state.conceptSelected, null)
    assert.deepEqual(h.state.relationFilter, ['uses'])
    assert.equal(h.state.originFilter, 'all')
    assert.equal(h.state.showIsolated, true)
    assert.deepEqual(ids(h.state.canvasNodes), ['a', 'b', 'c', 'unassigned'])
    assert.deepEqual(ids(h.state.canvasEdges), ['au', 'bc'])
    assert.ok(h.calls.reset > 0)
  } finally { await h.close() }
})

test('returning to the overview clears exploration state while retaining evidence and visibility filters', async () => {
  const h = await harness()
  try {
    h.state.focusCommunity(1)
    h.state.relationFilter = ['is_a']
    h.state.originFilter = 'derived'
    h.state.showIsolated = true
    h.state.focus = new Set(['c', 'd'])
    h.state.focusRootId = 'c'
    h.state.conceptSelected = h.state.concept.nodes[2]
    h.state.graphQuery = 'old search'
    h.state.returnToCommunityOverview()
    await flush()
    assert.equal(h.state.isCommunityOverview, true)
    assert.equal(h.state.communityFilter, '')
    assert.equal(h.state.focus, null)
    assert.equal(h.state.focusRootId, '')
    assert.equal(h.state.conceptSelected, null)
    assert.equal(h.state.graphQuery, '')
    assert.deepEqual(h.state.relationFilter, ['is_a'])
    assert.equal(h.state.originFilter, 'derived')
    assert.equal(h.state.showIsolated, true)
    assert.deepEqual(ids(h.state.overviewConceptGraph.edges), ['cd'])
  } finally { await h.close() }
})

test('returning from an expanded community restores its galaxy focus without resetting the camera', async () => {
  const h = await harness()
  try {
    h.state.focusCommunity(0)
    await flush()
    h.state.returnToCommunityOverview()
    await flush()
    assert.deepEqual(h.calls.galaxyFocused, ['0'], 'community zero is restored rather than treated as an empty selection')
    assert.equal(h.calls.galaxyReset, 0, 'return navigation preserves the single galaxy camera')
    h.state.focusCommunity(1)
    await flush()
    h.state.onCommunityChange('')
    await flush()
    assert.deepEqual(h.calls.galaxyFocused, ['0', '1'])
    assert.equal(h.calls.galaxyReset, 0)
    assert.equal(h.state.isCommunityOverview, true)
  } finally { await h.close() }
})

test('global alias search enters the matching neighborhood without resetting relation or evidence filters', async () => {
  const h = await harness()
  try {
    h.state.focusCommunity(0)
    h.state.relationFilter = ['is_a']
    h.state.originFilter = 'derived'
    h.state.showIsolated = true
    h.state.graphQuery = 'gamma'
    assert.equal(h.state.graphMatches[0].id, 'c')
    h.state.locateNode(h.state.graphMatches[0])
    await flush()
    assert.equal(h.state.isCommunityOverview, false)
    assert.equal(h.state.communityFilter, '')
    assert.deepEqual([...h.state.focus].sort(), ['c', 'd'])
    assert.equal(h.state.focusRootId, 'c')
    assert.equal(h.state.conceptSelected.id, 'c')
    assert.equal(h.state.graphQuery, '')
    assert.deepEqual(h.state.relationFilter, ['is_a'])
    assert.equal(h.state.originFilter, 'derived')
    assert.equal(h.state.showIsolated, true)
    assert.deepEqual(ids(h.state.canvasNodes), ['c', 'd'])
    assert.deepEqual(ids(h.state.canvasEdges), ['cd'])
    assert.deepEqual(h.calls.focused, ['c'])
    assert.equal(h.state.conceptRelList[0].evidence, '合成推导')
    h.state.onSelectNode(h.state.concept.nodes[3])
    assert.equal(h.state.focusRootId, 'c', 'inspecting a neighbor must not move the layout center')
  } finally { await h.close() }
})

test('isolated and unassigned concepts remain reachable without modifying stored membership', async () => {
  const h = await harness()
  try {
    assert.equal(h.state.resolvedCommunityOf.unassigned, '__unassigned__')
    assert.equal(h.state.communityOf.unassigned, undefined)
    h.state.showIsolated = true
    h.state.focusCommunity('__unassigned__')
    assert.ok(ids(h.state.canvasNodes).includes('unassigned'))
    h.state.graphQuery = '示例孤立概念'
    h.state.locateNode(h.state.graphMatches[0])
    await flush()
    assert.deepEqual(ids(h.state.canvasNodes), ['isolated'])
    assert.deepEqual(h.state.canvasEdges, [])
    assert.deepEqual([...h.state.focus], ['isolated'])
    h.state.returnToCommunityOverview()
    assert.equal(h.state.isCommunityOverview, true)
    assert.ok(ids(h.state.overviewConceptGraph.nodes).includes('isolated'))
  } finally { await h.close() }
})

test('community selection and clearing use the same navigation state transitions', async () => {
  const h = await harness()
  try {
    h.state.originFilter = 'all'
    h.state.relationFilter = ['uses']
    h.state.onCommunityChange('0')
    assert.equal(h.state.communityFilter, '0')
    assert.equal(h.state.isCommunityOverview, false)
    h.state.focus = new Set(['a'])
    h.state.focusRootId = 'a'
    h.state.conceptSelected = h.state.concept.nodes[0]
    h.state.onCommunityChange('')
    assert.equal(h.state.isCommunityOverview, true)
    assert.equal(h.state.focus, null)
    assert.equal(h.state.focusRootId, '')
    assert.equal(h.state.conceptSelected, null)
    assert.equal(h.state.originFilter, 'all')
    assert.deepEqual(h.state.relationFilter, ['uses'])
  } finally { await h.close() }
})

test('an opened community keeps its name and selection when evidence filters remove every matching concept', async () => {
  const h = await harness()
  try {
    h.state.focusCommunity(0)
    const title = h.state.activeCommunityTitle
    h.state.relationFilter = ['is_a']
    h.state.originFilter = 'derived'
    h.state.showIsolated = false
    await flush()
    assert.equal(h.state.communityFilter, '0')
    assert.equal(h.state.isCommunityOverview, false)
    assert.equal(h.state.activeCommunityTitle, title)
    assert.deepEqual(h.state.canvasNodes, [])
    assert.deepEqual(h.state.canvasEdges, [])
    assert.equal(h.state.communitySelectionOptions.find(option => option.value === '0').label, `${title} · 当前筛选无匹配`)
    assert.deepEqual(h.state.relationFilter, ['is_a'])
    assert.equal(h.state.originFilter, 'derived')
    assert.equal(h.state.showIsolated, false)
    h.state.returnToCommunityOverview()
    await flush()
    assert.equal(h.state.isCommunityOverview, true)
    assert.equal(h.state.communitySelectionOptions.some(option => option.value === '0'), false)
    assert.deepEqual(ids(h.state.overviewConceptGraph.nodes), ['c', 'd'])
    assert.deepEqual(h.state.relationFilter, ['is_a'])
    assert.equal(h.state.originFilter, 'derived')
    assert.equal(h.state.showIsolated, false)
    h.state.focusCommunity('__unassigned__')
    assert.equal(h.state.activeCommunityTitle, '未分组概念')
    assert.equal(h.state.communitySelectionOptions.find(option => option.value === '__unassigned__').label, '未分组概念 · 当前筛选无匹配')
  } finally { await h.close() }
})

test('document view bypasses aggregation and keeps its selection separate from concept exploration', async () => {
  const h = await harness()
  try {
    h.state.focusCommunity(0)
    h.state.conceptSelected = h.state.concept.nodes[0]
    h.state.graph = { nodes: [{ id: 'note-1', type: 'note', label: '合成文档' }, { id: 'file-2', type: 'file', label: '合成资料' }],
      edges: [{ source: 'note-1', target: 'file-2', kind: 'semantic' }], stat: {} }
    h.state.layer = 'doc'
    assert.equal(h.state.isCommunityOverview, false)
    assert.deepEqual(ids(h.state.canvasNodes), ['file-2', 'note-1'])
    assert.equal(h.state.canvasEdges.length, 1)
    h.state.onSelectNode(h.state.graph.nodes[0])
    assert.equal(h.state.selectedNode.id, 'note-1')
    assert.equal(h.state.conceptSelected.id, 'a')
    h.state.layer = 'concept'
    assert.equal(h.state.selectedNode.id, 'a')
    assert.equal(h.state.communityFilter, '0')
  } finally { await h.close() }
})
