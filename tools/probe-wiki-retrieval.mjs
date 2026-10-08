import { mkdir, writeFile } from 'node:fs/promises'
import { createHash } from 'node:crypto'
import { resolve } from 'node:path'

// Read-only integration checks. Flags belong to each request; no setting or source is changed.
const base = process.env.LEARNHUB_BASE_URL || 'http://localhost:18080'
const output = resolve(process.argv[2] || 'output/wiki-retrieval-probe.json')
const queries = process.argv.slice(3).length ? process.argv.slice(3) : [
  'ReAct 和 Planning 有什么关系？', 'AgentRewind 如何回滚状态？', 'MQTT 是什么？',
]
const arms = [
  { name: 'base', wiki: false, kg: false },
  { name: 'wiki', wiki: true, kg: false },
  { name: 'graph', wiki: false, kg: true },
  { name: 'wiki_graph', wiki: true, kg: true },
]
async function get(path, params) {
  const url = new URL(path, base)
  for (const [key, value] of Object.entries(params || {})) url.searchParams.set(key, String(value))
  const response = await fetch(url, { signal: AbortSignal.timeout(120000) })
  const result = await response.json()
  if (!response.ok || result.code !== 200) throw new Error(`${path}: ${result.msg || response.status}`)
  return result.data
}
const results = []
for (const question of queries) {
  for (const arm of arms) {
    const started = performance.now()
    const data = await get('/api/kb/context', { q: question, mode: 'fused', topK: 5, wiki: arm.wiki, kg: arm.kg })
    const passages = data.retrieved.filter(item => ['note', 'quick_ref', 'file'].includes(item.type))
    const guides = data.retrieved.filter(item => item.type === 'wiki')
    if (data.chars !== data.text.length || data.chars > data.budget) throw new Error('Context exceeds its declared budget')
    if (new Set(passages.map(item => item.passageKey)).size !== passages.length) throw new Error('Duplicated original passage')
    if (data.refs.some(ref => ref.startsWith('wiki:'))) throw new Error('Generated guide became an original reference')
    if (!arm.wiki && guides.length) throw new Error('Wiki entered a disabled arm')
    if (guides.some(item => item.generatedGuide !== true || item.factVerified !== false)) throw new Error('Invalid guide evidence label')
    if (data.groundingText.includes('【Wiki 生成导览】')) throw new Error('Wiki guide entered grounding evidence')
    const row = { question, arm: arm.name, options: arm, latencyMs: Math.round(performance.now() - started),
      contextHash: createHash('sha256').update(data.text).digest('hex'), ...data }
    results.push(row)
    console.log(JSON.stringify({ question, arm: arm.name, chars: row.chars, passages: passages.length,
      guides: guides.map(item => `${item.title} / ${item.heading}`), wikiLocated: passages.filter(item => item.channels?.includes('wiki')).length,
      latencyMs: row.latencyMs }))
  }
}
const search = await get('/api/wiki/search', { q: queries[0], limit: 4 })
let read = null
if (search.items?.length) {
  const first = search.items[0]
  read = await get('/api/wiki/read', { topicKey: first.topicKey, sectionKey: first.sectionKey, maxChars: 4000 })
  if (!read.ok || read.generatedGuide !== true || read.factVerified !== false) throw new Error('Wiki read failed evidence isolation')
}
await mkdir(resolve(output, '..'), { recursive: true })
await writeFile(output, JSON.stringify({ generatedAt: new Date().toISOString(),
  note: 'Read-only wiring and provenance checks; no gold answers or accuracy estimate.', search, read, results }, null, 2))
console.log(`Saved ${results.length} contexts to ${output}`)
