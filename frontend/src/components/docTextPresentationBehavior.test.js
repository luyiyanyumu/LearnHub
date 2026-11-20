import test from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import { compileScript, parse } from '@vue/compiler-sfc'
import * as vue from 'vue'
import domino from '@mixmark-io/domino'
import * as math from '../utils/extractedMath.js'
import * as markdown from '../utils/extractedMarkdown.js'

const source = readFileSync(new URL('./DocTextView.vue', import.meta.url), 'utf8')
const compiled = compileScript(parse(source).descriptor, { id: 'doc-presentation-behavior' }).content
const imports = /^import\s+([\s\S]*?)\s+from\s+['"]([^'"]+)['"]\s*;?\r?$/gm
const factory = new Function('modules', compiled.replace(imports, (_, names, from) => `const ${names.trim().replace(/\bas\b/g, ':')} = modules[${JSON.stringify(from)}]`).replace('export default', 'return'))
const renderer = vue.createRenderer({ createElement: tag => ({ tag, children: [] }), createText: text => ({ text }), createComment: text => ({ text }), insert: (node, parent) => parent.children.push(node), remove() {}, setText() {}, setElementText() {}, patchProp() {}, parentNode: () => null, nextSibling: () => null })
const hash = 'b'.repeat(64), revision = 'a'.repeat(64)
function harness(pages) {
  const previous = Object.getOwnPropertyDescriptor(globalThis, 'document')
  Object.defineProperty(globalThis, 'document', { configurable: true, value: domino.createWindow().document })
  const component = factory({ vue, 'element-plus': { ElMessage: {} }, '../utils/extractedMath': math, '../utils/extractedMarkdown.js': markdown,
    './ExtractedFormula.vue': {}, '../api': { fileApi: { formulaRecognitionCaps: async () => ({ enabled: false }) } } })
  component.render = () => null
  const app = renderer.createApp(component, { fileId: 41, pages, allowRegionRecognition: false,
    artifact: { sourceSha256: revision, assets: [{ name: `${hash}.png`, sha256: hash, mediaType: 'image/png', bytes: 512 }] } })
  app.mount({ children: [] })
  return { state: app._instance.setupState, focusBlock: app._instance.exposed.focusBlock, close() { app.unmount(); if (previous) Object.defineProperty(globalThis, 'document', previous); else delete globalThis.document } }
}

test('verification keeps inline image sizes and distinguishes Word groups from labelled physical pages', () => {
  const block = { id: 'paragraph-1', type: 'para', text: '前后文', markdown: `前文 <img src="assets/${hash}.png" width="16" height="12" alt="小图"> 后文`,
    presentation: { placement: 'inline', groupId: 'word-p1', widthPx: 16, heightPx: 12 } }
  const h = harness([{ page: 1, coordinateUnit: 'none', blocks: [block] }])
  try {
    assert.equal(h.state.pageDescription({ page: 1, coordinateUnit: 'none' }), '内容分组 1')
    assert.equal(h.state.pageDescription({ page: 4, coordinateUnit: 'pt', label: 'iv' }), '原件第 4 页（原印刷页码：iv）')
    const root = document.createElement('div')
    root.innerHTML = h.state.renderedText(1, 0).html
    assert.equal(root.querySelector('img').getAttribute('width'), '16')
    assert.ok(!root.querySelector('p'))
    assert.equal(root.textContent, '前文  后文')
  } finally { h.close() }
})

test('hidden annotations and table resolvers focus their visible source image or owning table', () => {
  const blocks = [
    { id: 'original-image', type: 'figure', src: `assets/${hash}.png`, presentation: { groupId: 'word-p1', placement: 'inline' } },
    { id: 'original-image-vision-formula-1', type: 'formula', presentation: { groupId: 'word-p1', placement: 'annotation' } },
    { id: 'table-owner', type: 'table' },
    { id: 'table-resolver', type: 'figure', presentation: { groupId: 'table-owner', placement: 'resolver_only' } },
    { id: 'table-resolver-vision-formula-1', type: 'formula', presentation: { groupId: 'table-owner', placement: 'annotation' } },
  ]
  const h = harness([{ page: 1, coordinateUnit: 'none', blocks }])
  try {
    const focused = []
    const nodes = ['original-image', 'table-owner'].map(id => ({ dataset: { blockId: id }, scrollIntoView() {}, focus() { focused.push(id) } }))
    h.state.documentRoot = { querySelectorAll: selector => selector === '[data-block-id]' ? nodes : [] }
    assert.deepEqual(h.state.contentBlocks({ blocks }).map(item => item.block.id), ['original-image', 'table-owner'])
    h.focusBlock('original-image-vision-formula-1', 1)
    h.focusBlock('table-resolver-vision-formula-1', 1)
    assert.deepEqual(focused, ['original-image', 'table-owner'])
  } finally { h.close() }
})
