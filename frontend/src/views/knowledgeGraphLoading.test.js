import test from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import { compileScript, parse } from '@vue/compiler-sfc'
import * as vue from 'vue'

const source = readFileSync(new URL('./Knowledge.vue', import.meta.url), 'utf8')
const compiled = compileScript(parse(source).descriptor, { id: 'knowledge-graph-loading' }).content
const imports = /^import\s+([\s\S]*?)\s+from\s+['"]([^'"]+)['"]\s*;?\r?$/gm
const script = compiled.replace(/^import\s+['"][^'"]+['"]\s*;?\r?$/gm, '')
const factory = new Function('modules', script
  .replace(imports, (_, names, from) => `const ${names.trim().replace(/\bas\b/g, ':')} = modules[${JSON.stringify(from)}]`)
  .replace('export default', 'return'))
const dependencies = Object.fromEntries(await Promise.all([...script.matchAll(imports)]
  .map(match => match[2]).filter(from => from.startsWith('../utils/')).map(async from => [
    from, await import(new URL(from.endsWith('.js') ? from : `${from}.js`, import.meta.url)),
  ])))
const renderer = vue.createRenderer({
  createComment: () => ({}), insert() {}, remove() {}, parentNode: () => null, nextSibling: () => null,
})

function harness(t, kgApi, tab = 'graph') {
  const previous = new Map()
  for (const [key, value] of Object.entries({ localStorage: { getItem: () => null, setItem() {} }, window: new EventTarget() })) {
    previous.set(key, Object.getOwnPropertyDescriptor(globalThis, key))
    Object.defineProperty(globalThis, key, { configurable: true, value })
  }
  const component = factory({
    ...dependencies,
    vue: { ...vue, onMounted() {} },
    'vue-router': { useRoute: () => ({ path: '/knowledge', query: { tab } }), useRouter: () => ({}) },
    'element-plus': {}, 'md-editor-v3': {}, '@element-plus/icons-vue': {},
    '../composables/useTheme': { isDark: vue.ref(false) },
    '../api': { kgApi, fileApi: {}, kbApi: {}, knowledgeApi: {}, wikiApi: {} },
    '../api/knowledgeMaintenance.js': { knowledgeMaintenanceApi: { status: async () => ({ running: false }) } },
  })
  component.render = () => null
  const app = renderer.createApp(component)
  app.mount({})
  // Vue's unmount hook uses the browser globals, so clean it up before restoring them.
  t.after(() => {
    try { app.unmount() }
    finally {
      for (const [key, descriptor] of previous) {
        if (descriptor) Object.defineProperty(globalThis, key, descriptor)
        else delete globalThis[key]
      }
    }
  })
  return app._instance.setupState
}

async function flush() {
  for (let count = 0; count < 8; count++) await vue.nextTick()
}

const fixture = {
  nodes: [{ id: 'sample-a', label: '示例概念' }], edges: [], stat: { nodes: 1 },
  ontology: [{ id: 'uses', label: '使用' }],
}
const groups = [{ communityId: 0, size: 1, modularity: 0.42, nodeIds: ['sample-a'] }]

test('the concept layer uses a single parallel grouped request and defers summary status to maintenance', async t => {
  const concept = Promise.withResolvers()
  const grouped = Promise.withResolvers()
  const summary = Promise.withResolvers()
  const calls = []
  const state = harness(t, {
    concept: () => { calls.push('concept'); return concept.promise },
    groupedCommunities: () => { calls.push('grouped'); return grouped.promise },
    communities: () => { calls.push('flat'); return [] },
    communityStatus: () => { calls.push('summary'); return summary.promise },
    graph: () => { calls.push('document'); return {} },
  })
  state.syncGraphLayer()
  const done = state.loadConcept()
  assert.deepEqual(calls, ['concept', 'grouped'])
  concept.resolve(fixture)
  await flush()
  assert.equal(state.conceptLoading, true)
  grouped.resolve(groups)
  await done
  assert.equal(state.conceptLoading, false)
  assert.deepEqual(calls, ['concept', 'grouped'])
  assert.equal(state.resolvedCommunityOf['sample-a'], 0)
  assert.equal(state.communityModularity, 0.42)
  assert.deepEqual(state.relationFilter, ['uses'])

  state.maintenanceOpen = true
  await flush()
  state.maintenanceOpen = false
  await flush()
  state.maintenanceOpen = true
  await flush()
  assert.deepEqual(calls, ['concept', 'grouped', 'summary'])
  const status = state.loadCommunitySummaryStatus()
  summary.resolve({ fresh: 1 })
  await status
  assert.equal(state.summaryStatus.fresh, 1)
})

test('the legacy community rows are used if grouped data is unavailable', async t => {
  const state = harness(t, {
    concept: async () => fixture,
    groupedCommunities: async () => { throw new Error('fixture grouped failure') },
    communities: async () => [{ nodeId: 'sample-a', communityId: 0, modularity: 0.42 }],
  })
  await state.loadConcept()
  assert.equal(state.concept.stat.nodes, 1)
  assert.equal(state.resolvedCommunityOf['sample-a'], 0)
  assert.equal(state.communityGroups[0].size, 1)
  assert.equal(state.communityModularity, 0.42)
})

test('opening the graph reuses the concept count preload and checks document updates only while visible', async t => {
  const concept = Promise.withResolvers()
  const documentGraph = Promise.withResolvers()
  const version = Promise.withResolvers()
  const calls = []
  const state = harness(t, {
    concept: () => { calls.push('concept'); return concept.promise },
    groupedCommunities: async () => { calls.push('grouped'); return groups },
    graph: () => { calls.push('document'); return documentGraph.promise },
    version: () => { calls.push('version'); return version.promise },
  }, 'search')
  const countReady = state.prefetchConceptCount()
  assert.deepEqual(calls, ['concept'])
  state.tab = 'graph'
  await flush()
  const graphReady = state.loadConcept()
  assert.deepEqual(calls, ['concept', 'grouped'])
  concept.resolve(fixture)
  await Promise.all([countReady, graphReady])
  await state.checkVersion()
  assert.deepEqual(calls, ['concept', 'grouped'])

  state.layer = 'doc'
  await flush()
  const documentReady = state.loadGraph()
  assert.deepEqual(calls, ['concept', 'grouped', 'document'])
  documentGraph.resolve({ nodes: [], edges: [], stat: {}, version: 'initial' })
  await documentReady
  const check = state.checkVersion()
  state.layer = 'concept'
  await flush()
  version.resolve({ version: 'changed' })
  await check
  assert.deepEqual(calls, ['concept', 'grouped', 'document', 'version'])
})
