import assert from 'node:assert/strict'
import test from 'node:test'
import domino from '@mixmark-io/domino'
import { isPasteInEditor } from './pasteTarget.js'

function surfaces() {
  const document = domino.createWindow(`
    <div class="editor-wrap">
      <section class="pane-editor">
        <div id="source" class="cm-editor">
          <div id="toolbar"><button>Source toolbar</button></div>
          <div id="content" class="cm-content" contenteditable="true">
            <div class="cm-line"><span id="text">Source text</span></div>
            <div id="nested" class="cm-editor"><div class="cm-content" contenteditable="true"><span id="nested-text">Nested source</span></div></div>
            <div class="block-preview"><div class="ProseMirror" contenteditable="true"><p id="nested-preview">Nested preview</p></div></div>
          </div>
        </div>
      </section>
      <section class="pane-preview"><div class="block-preview"><div class="ProseMirror" contenteditable="true"><p id="preview">Preview text</p></div></div></section>
      <div class="cm-editor"><div class="cm-content" contenteditable="true"><span id="other">Other source</span></div></div>
      <textarea id="outside"></textarea>
    </div>
  `).document
  const view = { dom: document.getElementById('source'), contentDOM: document.getElementById('content') }
  return { document, view, get: id => document.getElementById(id) }
}

test('paste belongs to the receiving source editor element and text nodes', () => {
  const { get, view } = surfaces()
  assert.equal(isPasteInEditor(get('content'), view), true)
  assert.equal(isPasteInEditor(get('text'), view), true)
  assert.equal(isPasteInEditor(get('text').firstChild, view), true)
})

test('a preview or another editor cannot paste into the source caret', () => {
  const { get, view } = surfaces()
  for (const id of ['preview', 'other', 'outside', 'nested-text', 'nested-preview', 'toolbar']) {
    assert.equal(isPasteInEditor(get(id), view), false, id)
  }
})

test('missing, removed, or invalid targets leave paste with its receiving surface', () => {
  const { document, get, view } = surfaces()
  assert.equal(isPasteInEditor(null, view), false)
  assert.equal(isPasteInEditor(get('text'), null), false)
  assert.equal(isPasteInEditor(get('text'), {}), false)
  assert.equal(isPasteInEditor(document.createElement('div'), view), false)
  assert.equal(isPasteInEditor({}, view), false)
  assert.equal(isPasteInEditor(get('text'), { dom: { contains() { throw Error('stale') } } }), false)
})

test('DOM ownership works without the host global Node constructor', () => {
  const { get, view } = surfaces()
  const previous = globalThis.Node
  globalThis.Node = class UnrelatedWindowNode {}
  try {
    assert.equal(isPasteInEditor(get('text'), view), true)
    assert.equal(isPasteInEditor(get('text').firstChild, view), true)
  } finally {
    if (previous === undefined) delete globalThis.Node
    else globalThis.Node = previous
  }
})
