import test from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import { compileScript, parse } from '@vue/compiler-sfc'
import * as vue from 'vue'
import * as math from '../utils/extractedMath.js'

const source = readFileSync(new URL('./ExtractedFormula.vue', import.meta.url), 'utf8')
const compiled = compileScript(parse(source).descriptor, { id: 'extracted-formula-behavior' }).content
const imports = /^import\s+([\s\S]*?)\s+from\s+['"]([^'"]+)['"]\s*;?\r?$/gm
const factory = new Function('modules', compiled.replace(imports, (_, names, from) => `const ${names.trim().replace(/\bas\b/g, ':')} = modules[${JSON.stringify(from)}]`).replace('export default', 'return'))
const renderer = vue.createRenderer({ createElement: tag => ({ tag, children: [] }), createText: text => ({ text }), createComment: text => ({ text }), insert: (node, parent) => parent.children.push(node), remove() {}, setText() {}, setElementText() {}, patchProp() {}, parentNode: () => null, nextSibling: () => null })
function deferred() { let resolve, reject; const promise = new Promise((ok, fail) => { resolve = ok; reject = fail }); return { promise, resolve, reject } }
async function flush() { for (let n = 0; n < 12; n++) { await Promise.resolve(); await vue.nextTick() } }
function harness(options = {}) {
  const calls = [], persisted = [], regions = []
  let accept = async () => ({}), recognize = async () => ({ status: 'recognized', latex: 'x_1', cacheKey: 'synthetic-cache' })
  const component = factory({ vue, 'element-plus': { ElMessage: { success() {}, error() {} } }, '../utils/extractedMath': math,
    '../api': { fileApi: { async recognizeFormula(id, payload) { calls.push({ action: 'recognize', id, payload }); return recognize() }, async acceptFormulaRecognition(id, payload) { calls.push({ action: 'accept', id, payload }); return accept() } } } })
  component.render = () => null
  const app = renderer.createApp(component, { block: { id: 'formula-1', page: 1, rect: [10, 20, 80, 60], latex: 'x', latexStatus: 'partial' }, fileId: 41,
    recognitionCapabilities: { enabled: true }, ...options, onPersisted: value => persisted.push(value), onOpenRegion: value => regions.push(value) })
  app.mount({ children: [] })
  return { state: app._instance.setupState, calls, persisted, regions, setAccept: value => { accept = value }, setRecognize: value => { recognize = value }, close: () => app.unmount() }
}

test('a formula emits persisted only after safe recognition and a successful acceptance write', async () => {
  const h = harness(), saved = deferred()
  try {
    h.setAccept(() => saved.promise)
    const pending = h.state.recognize()
    await flush()
    assert.equal(h.state.source, 'x_1')
    assert.equal(h.persisted.length, 0)
    saved.resolve({})
    await pending
    assert.deepEqual(h.persisted, [{ fileId: 41, blockId: 'formula-1', page: 1 }])
  } finally { h.close() }
})

test('failed acceptance keeps the recognized result local and never refreshes it as saved', async () => {
  const h = harness()
  try {
    h.setAccept(() => Promise.reject(new Error('合成保存失败')))
    await h.state.recognize()
    assert.equal(h.persisted.length, 0)
    assert.match(h.state.recognitionError, /仅用于本次阅读/)
    assert.equal(h.state.source, 'x_1')
    h.setRecognize(async () => ({ status: 'recognized', latex: '\\unsafeUnknownCommand', cacheKey: 'bad' }))
    const count = h.calls.filter(call => call.action === 'accept').length
    await h.state.recognize()
    assert.equal(h.calls.filter(call => call.action === 'accept').length, count)
  } finally { h.close() }
})

test('Word and image formulas use supplied artifact images and never call the PDF-only recognizer', async () => {
  const image = `/api/files/41/extracted-assets/${'b'.repeat(64)}.png?sourceRevision=${'a'.repeat(64)}`
  const h = harness({ allowRegionRecognition: false, imageSrc: image })
  try {
    assert.equal(h.state.imageUrl, image)
    await h.state.recognize()
    assert.equal(h.calls.length, 0)
    h.state.viewOriginal()
    assert.equal(h.regions[0].id, 'formula-1')
  } finally { h.close() }
})
