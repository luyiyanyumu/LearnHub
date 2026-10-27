import assert from 'node:assert/strict'
import test from 'node:test'
import { readFileSync } from 'node:fs'
import { parse, compileScript } from '@vue/compiler-sfc'
import * as vue from 'vue'
import domino from '@mixmark-io/domino'
import { getSchema } from '@tiptap/core'
import StarterKit from '@tiptap/starter-kit'
import { DOMParser, DOMSerializer } from '@tiptap/pm/model'
import { EditorState, TextSelection } from '@tiptap/pm/state'
import { history, undo, undoDepth } from '@tiptap/pm/history'
import { MathInline, MathBlock } from '../utils/mathNodes.js'

// Exercise the actual exposed SFC methods with real ProseMirror state/history.
// Editor mounting and viewport UI are unnecessary for programmatic edits.
const sourceUrl = new URL('./BlockPreview.vue', import.meta.url)
const compiled = compileScript(parse(readFileSync(sourceUrl, 'utf8')).descriptor, { id: 'block-preview-change-test' }).content
const importPattern = /^import\s+([\s\S]*?)\s+from\s+['"]([^'"]+)['"]\s*;?\r?$/gm
const modules = {}
for (const match of compiled.matchAll(importPattern)) {
  const from = match[2]
  if (from.endsWith('.vue') || from.endsWith('/codeBlockCm')) { modules[from] = {}; continue }
  modules[from] = from.startsWith('.')
    ? await import(new URL(from.endsWith('.js') ? from : `${from}.js`, sourceUrl))
    : await import(from)
}
const factory = new Function('modules', compiled
  .replace(importPattern, (_, names, from) => {
    const target = `modules[${JSON.stringify(from)}]`
    const binding = names.trim()
    if (binding.startsWith('{')) return `const ${binding.replace(/\bas\b/g, ':')} = ${target}`
    const [main, rest] = binding.split(/,\s*(?=\{)/)
    return `const ${main} = ${target}.default\n${rest ? `const ${rest.replace(/\bas\b/g, ':')} = ${target}` : ''}`
  })
  .replace('export default', 'return'))

function harness(options = {}) {
  const previousDocument = globalThis.document
  const previousWindow = globalThis.window
  globalThis.window = domino.createWindow()
  globalThis.document = window.document
  const elementProto = Object.getPrototypeOf(document.createElement('div').children)
  if (!elementProto[Symbol.iterator]) Object.defineProperty(elementProto, Symbol.iterator, { value: Array.prototype[Symbol.iterator] })
  const schema = getSchema([StarterKit, MathInline, MathBlock])
  const initial = schema.node('doc', null, [schema.node('paragraph', null, schema.text('原始笔记'))])
  let state = EditorState.create({ schema, doc: initial, selection: TextSelection.create(initial, 3), plugins: [history()] })
  const events = [], transactions = [], unmounts = []
  const editor = { isDestroyed: false, get schema() { return schema }, get state() { return state },
    getHTML() {
      const box = document.createElement('div')
      box.appendChild(DOMSerializer.fromSchema(schema).serializeFragment(state.doc.content, { document }))
      return box.innerHTML
    },
    view: { composing: false, hasFocus: () => false,
      dispatch(transaction) { transaction.doc.check(); transactions.push(transaction); state = state.apply(transaction) } },
  }
  const props = vue.reactive({ content: '', active: true, visible: false, readonly: false, sourceLinesActive: true, ...options })
  const scopedModules = { ...modules, vue: { ...vue, onMounted() {}, onBeforeUnmount: callback => unmounts.push(callback) } }
  const component = factory(scopedModules)
  const scope = vue.effectScope()
  let exposed
  const setup = scope.run(() => component.setup(props, { expose: value => { exposed = value }, emit: (...args) => events.push(args) }))
  setup.editor.value = editor
  return { props, editor, setup, exposed, events, transactions,
    cleanup() { setup.editor.value = null; unmounts.forEach(callback => callback()); scope.stop(); globalThis.document = previousDocument; globalThis.window = previousWindow },
  }
}

test('the exposed AI batch API works with hidden preview and preserves an independent undo event', () => {
  const h = harness()
  try {
    const original = h.editor.state.doc
    const markdown = '原始笔记\n\n$$\n\\begin{aligned}\nx &= y + 1\\\\\nz &= x^2\n\\end{aligned}\n$$\n\n补充正文'
    assert.equal(h.exposed.applyMarkdownChange(markdown), true)
    assert.equal(undoDepth(h.editor.state), 1)
    assert.equal(h.transactions.filter(transaction => transaction.docChanged).length, 1)
    assert.deepEqual(h.events.filter(([event]) => event === 'update'), [['update', markdown]])
    assert.equal(h.exposed.flushContent(markdown), markdown)
    assert.equal(undo(h.editor.state, h.editor.view.dispatch), true)
    assert.ok(h.editor.state.doc.eq(original))
  } finally { h.cleanup() }
})

test('cursor insertion uses the stored caret, emits the new source and preserves surrounding text', () => {
  const h = harness()
  try {
    assert.equal(h.exposed.insertMarkdownAtCursor('新内容'), true)
    assert.equal(h.editor.state.doc.textContent, '原始新内容笔记')
    assert.ok(h.events.some(([event, value]) => event === 'update' && value.includes('原始新内容笔记')))
    assert.equal(undoDepth(h.editor.state), 1)
  } finally { h.cleanup() }
})

test('inactive, read-only and composing editors reject both mutation APIs without any dispatch', () => {
  for (const options of [{ active: false }, { readonly: true }, { composing: true }]) {
    const h = harness(options)
    h.editor.view.composing = Boolean(options.composing)
    try {
      assert.equal(h.exposed.applyMarkdownChange('改动'), false)
      assert.equal(h.exposed.insertMarkdownAtCursor('插入'), false)
      assert.equal(h.transactions.length, 0)
      assert.equal(h.events.length, 0)
      assert.equal(h.editor.state.doc.textContent, '原始笔记')
    } finally { h.cleanup() }
  }
})

test('ordinary queued source synchronization is excluded from preview undo history', () => {
  const h = harness({ content: '初次同步' })
  let resets = 0
  h.editor.chain = () => {
    let transaction = h.editor.state.tr, html
    const chain = {
      command(operation) { operation({ tr: transaction }); return chain },
      setContent(value) { html = value; return chain },
      run() {
        resets++
        const box = document.createElement('div')
        box.innerHTML = html
        const next = DOMParser.fromSchema(h.editor.schema).parse(box)
        h.editor.view.dispatch(transaction.replaceWith(0, transaction.doc.content.size, next.content))
        return true
      },
    }
    return chain
  }
  try {
    // flushContent(current) handles a parent source update before Vue patches props.
    assert.equal(h.exposed.flushContent('最新源码'), '最新源码')
    h.setup.externalSync.setAvailable(true)
    assert.equal(resets, 1)
    assert.equal(h.editor.state.doc.textContent, '最新源码')
    assert.equal(undoDepth(h.editor.state), 0)
  } finally { h.cleanup() }
})
