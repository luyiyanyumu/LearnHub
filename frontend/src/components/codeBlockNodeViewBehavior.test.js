import test from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import { parse, compileScript } from '@vue/compiler-sfc'
import * as vue from 'vue'
import { EditorState, Compartment, Transaction, Facet } from '@codemirror/state'
import { EditorView as ActualEditorView, keymap, lineNumbers } from '@codemirror/view'
import { defaultKeymap, history, historyKeymap, indentWithTab, undo } from '@codemirror/commands'
import { bracketMatching, syntaxHighlighting, defaultHighlightStyle } from '@codemirror/language'
import { codeBlockChange, codeBlockDocument } from '../utils/codeBlockViewState.js'
import { highlightCodeBlockLines } from '../utils/codeBlockHighlight.js'

// Run the actual SFC setup/watchers and real CodeMirror state/history. Only the
// DOM view and viewport observer are replaced, so constructions are observable.
const source = readFileSync(new URL('./CodeBlockNodeView.vue', import.meta.url), 'utf8')
const compiled = compileScript(parse(source).descriptor, { id: 'code-block-reading-behavior' }).content
  .replace(/^import .*\r?\n/gm, '').replace('export default', 'return')

function harness(options = {}) {
  const mounted = [], unmounted = [], views = [], attributes = [], loads = []
  let viewportOptions
  class EditorView {
    static lineWrapping = ActualEditorView.lineWrapping
    static editable = ActualEditorView.editable
    static updateListener = ActualEditorView.updateListener
    constructor({ state, parent }) { this.state = state; this.parent = parent; this.focused = 0; this.measured = 0; views.push(this) }
    dispatch(spec) {
      const transaction = spec instanceof Transaction ? spec : this.state.update(spec)
      this.state = transaction.state
      for (const listener of this.state.facet(ActualEditorView.updateListener)) listener({ state: this.state, docChanged: transaction.docChanged })
    }
    requestMeasure() { this.measured++ }
    posAtCoords() { return Math.min(3, this.state.doc.length) }
    focus() { this.focused++ }
    destroy() { this.destroyed = true }
  }
  const props = vue.reactive({
    node: { attrs: { code: 'const sample = 1\n', language: 'javascript', ...options.attrs } },
    selected: Boolean(options.selected),
    editor: { options: { editable: !options.readonly }, isDestroyed: false,
      view: { hasFocus: () => true }, state: { selection: { from: 10 } } },
    getPos: () => 10,
    updateAttributes(patch) { attributes.push(patch); Object.assign(props.node.attrs, patch) },
  })
  const bindings = { computed: vue.computed, nextTick: vue.nextTick, ref: vue.ref, shallowRef: vue.shallowRef, watch: vue.watch,
    EditorState, Compartment, Transaction, EditorView, keymap, lineNumbers,
    defaultKeymap, history, historyKeymap, indentWithTab, bracketMatching, syntaxHighlighting, defaultHighlightStyle,
    nodeViewProps: {}, NodeViewWrapper: {}, MermaidPreview: {}, ArrowDown: {}, ArrowUp: {}, Check: {}, DocumentCopy: {}, Edit: {}, FullScreen: {}, Share: {}, View: {},
    codeBlockChange, codeBlockDocument,
    highlightCodeBlockLines: options.highlight || highlightCodeBlockLines,
    loadCodeBlockLanguage(language) { loads.push(language); return options.load ? options.load(language) : Promise.resolve(null) },
    onMounted: callback => mounted.push(callback), onBeforeUnmount: callback => unmounted.push(callback),
    createCodeBlockViewport(value) {
      viewportOptions = value
      value.onActivity(true)
      return { request() { value.activate(); return true }, refresh() { value.activate() }, dispose() {} }
    },
  }
  const component = new Function(...Object.keys(bindings), compiled)(...Object.values(bindings))
  const scope = vue.effectScope()
  const setup = scope.run(() => component.setup(props, { expose() {}, emit() {} }))
  setup.host.value = { closest: () => ({}) }
  const languageControls = { focused: 0, toggled: 0, focus() { this.focused++ }, toggleMenu() { this.toggled++ } }
  setup.languageSelect.value = languageControls
  mounted.forEach(callback => callback())
  return { props, setup, views, attributes, loads, languageControls,
    near: () => viewportOptions.activate(),
    activity: active => viewportOptions.onActivity(active),
    async flush() { await vue.nextTick(); await Promise.resolve(); await vue.nextTick() },
    dispose() { unmounted.forEach(callback => callback()); scope.stop() },
  }
}

test('scrolling past 120 blocks builds only static reading, with no CodeMirror or language selects', async () => {
  const blocks = Array.from({ length: 120 }, (_, index) => harness({ attrs: { code: `const synthetic${index} = ${index}` } }))
  try {
    blocks.forEach(block => { block.near(); block.near() })
    await Promise.all(blocks.map(block => block.flush()))
    assert.equal(blocks.reduce((count, block) => count + block.views.length, 0), 0)
    assert.ok(blocks.every(block => block.setup.controlsMounted.value === false))
    assert.ok(blocks.every(block => block.setup.highlightedLines.value !== null))
    assert.ok(blocks.every(block => block.attributes.length === 0))
  } finally { blocks.forEach(block => block.dispose()) }
})

test('click or keyboard focus initializes only the current block once, from its latest attrs', async () => {
  const current = harness(), other = harness()
  try {
    current.near(); other.near()
    current.props.node.attrs.code = 'const latest = 7'
    current.props.node.attrs.language = 'python'
    await current.flush()
    await current.setup.activateSource({ clientX: 10, clientY: 20, preventDefault() {} })
    await current.flush()
    assert.equal(current.views.length, 1)
    assert.equal(other.views.length, 0)
    assert.equal(current.views[0].state.doc.toString(), 'const latest = 7')
    assert.equal(current.views[0].state.selection.main.anchor, 3)
    assert.ok(current.views[0].focused > 0)
    await current.setup.activateSource()
    assert.equal(current.views.length, 1)
    assert.equal(current.loads.at(-1), 'python')
    assert.equal(current.setup.controlsMounted.value, false)

    await other.setup.activateLanguage(true)
    assert.equal(other.views.length, 0)
    assert.equal(other.setup.controlsMounted.value, true)
    assert.equal(other.languageControls.focused, 1)
    assert.equal(other.languageControls.toggled, 1)
  } finally { current.dispose(); other.dispose() }
})

test('existing code editor survives hidden tabs, language changes, draft updates, and local undo', async () => {
  const marker = Facet.define({ combine: values => values.at(-1) })
  const block = harness({ load: language => Promise.resolve(marker.of(language)) })
  try {
    await block.setup.activateSource()
    await block.flush()
    const view = block.views[0], initial = view.state.doc.toString()
    view.dispatch({ changes: { from: initial.length, insert: '// draft' }, selection: { anchor: initial.length + 3 } })
    assert.equal(block.props.node.attrs.code, initial + '// draft')
    const position = view.state.selection.main.anchor
    block.activity(false)
    await block.setup.activateSource()
    block.props.node.attrs.language = 'typescript'
    await block.flush()
    assert.equal(block.views.length, 1)
    assert.equal(view.destroyed, undefined)
    assert.equal(view.state.selection.main.anchor, position)
    assert.equal(view.state.facet(marker), 'typescript')
    block.activity(true)
    await block.flush()
    assert.strictEqual(block.setup.view.value, view)
    assert.equal(view.state.doc.toString(), initial + '// draft')
    assert.equal(undo({ state: view.state, dispatch: transaction => view.dispatch(transaction) }), true)
    assert.equal(view.state.doc.toString(), initial)
    assert.equal(block.props.node.attrs.code, initial)
    assert.equal(block.views.length, 1)
  } finally { block.dispose() }
})

test('stale async highlights never replace the current code or language and unmounted blocks ignore results', async () => {
  const requests = []
  const block = harness({
    load: language => new Promise(resolve => requests.push({ language, resolve })),
    highlight: (text, support) => [[{ text, className: support.tag }]],
  })
  try {
    block.near()
    block.props.node.attrs.code = 'new synthetic code'
    block.props.node.attrs.language = 'python'
    await block.flush()
    assert.equal(requests.at(-1).language, 'python')
    requests.at(-1).resolve({ tag: 'latest-language' })
    await block.flush()
    requests.slice(0, -1).forEach(request => request.resolve({ tag: 'obsolete-language' }))
    await block.flush()
    assert.deepEqual(block.setup.highlightedLines.value, [[{ text: 'new synthetic code', className: 'latest-language' }]])
    block.props.node.attrs.code = 'after disposal'
    block.dispose()
    requests.at(-1).resolve({ tag: 'disposed' })
    await block.flush()
    assert.equal(block.setup.highlightedLines.value, null)
  } finally { block.dispose() }
})

test('new selected blocks and explicit Mermaid source editing activate immediately, readonly reading stays static', async () => {
  const selected = harness({ selected: true }), mermaid = harness({ attrs: { language: 'mermaid', code: 'flowchart TD\nA-->B' } }), readonly = harness({ readonly: true })
  try {
    assert.equal(selected.views.length, 1)
    mermaid.near(); readonly.near()
    await Promise.all([mermaid.flush(), readonly.flush(), selected.flush()])
    assert.equal(mermaid.views.length, 0)
    await mermaid.setup.setMermaidMode('source')
    assert.equal(mermaid.views.length, 1)
    await mermaid.setup.setMermaidMode('preview')
    await mermaid.setup.setMermaidMode('source')
    assert.equal(mermaid.views.length, 1)
    await readonly.setup.activateSource()
    assert.equal(readonly.views.length, 0)
    assert.ok(readonly.setup.highlightedLines.value)
    assert.equal(readonly.setup.controlsMounted.value, false)
  } finally { selected.dispose(); mermaid.dispose(); readonly.dispose() }
})

test('pending reading work stops when the block collapses, changes mode, or its editor is destroyed', async () => {
  for (const stop of [block => { block.setup.collapsed.value = true },
    block => { block.props.node.attrs.language = 'mermaid' },
    block => { block.props.editor.isDestroyed = true }]) {
    const requests = [], highlights = []
    const block = harness({ load: () => new Promise(resolve => requests.push(resolve)),
      highlight: text => { highlights.push(text); return [] } })
    try {
      block.near()
      stop(block)
      await block.flush()
      requests.forEach(resolve => resolve(null))
      await block.flush()
      assert.equal(highlights.length, 0)
      assert.equal(block.views.length, 0)
    } finally { block.dispose() }
  }
})
