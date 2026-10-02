import assert from 'node:assert/strict'
import test from 'node:test'
import { getSchema } from '@tiptap/core'
import StarterKit from '@tiptap/starter-kit'
import { EditorState } from '@tiptap/pm/state'
import { DOMParser as ProseMirrorDOMParser } from '@tiptap/pm/model'
import MarkdownIt from 'markdown-it'
import domino from '@mixmark-io/domino'
import { BlockMeta, createBlockId, duplicateBlockIdUpdates, isBlockId, normalizeBlockIndent, parseBlockIndent } from './blockMeta.js'
import { metadataBlockHtml, previewHtmlToMd } from './htmlToMd.js'

const schema = getSchema([StarterKit, BlockMeta])
const element = (tag, attributes = {}, innerHTML = '') => ({
  nodeName: tag.toUpperCase(),
  id: attributes.id || '',
  innerHTML,
  getAttribute: (name) => attributes[name] ?? null,
})
const parseAttrs = (type, tag, attributes) => schema.nodes[type].spec.parseDOM.find((rule) => rule.tag === tag).getAttrs(element(tag, attributes))

test('paragraph and heading attrs parse and render while normal heading anchors are not persisted', () => {
  for (const [type, tag] of [['paragraph', 'p'], ['heading', 'h3']]) {
    const attrs = parseAttrs(type, tag, { id: 'block-fixed', 'data-block-indent': '2', style: 'margin-left: 4em' })
    assert.equal(attrs.blockId, 'block-fixed')
    assert.equal(attrs.indent, 2)
    const node = schema.node(type, attrs, schema.text('Content'))
    assert.deepEqual(schema.nodes[type].spec.toDOM(node), [tag, { id: 'block-fixed', 'data-block-indent': '2', style: 'margin-left: 4em' }, 0])
    assert.equal(schema.node(type, parseAttrs(type, tag, { id: 'python-list-methods' })).attrs.blockId, null)
  }
})

test('indentation is bounded and accepts existing HTML margin styles', () => {
  assert.equal(normalizeBlockIndent(-2), 0)
  assert.equal(normalizeBlockIndent(99), 6)
  assert.equal(normalizeBlockIndent('2.9'), 2)
  assert.equal(normalizeBlockIndent('NaN'), 0)
  assert.equal(parseBlockIndent(element('p', { style: 'text-align: center; margin-left: 6em' })), 3)
  assert.equal(parseBlockIndent(element('h2', { style: 'margin-left: 64px' })), 2)
  assert.equal(parseBlockIndent(element('p', { 'data-block-indent': '1', style: 'margin-left: 8em' })), 1)
})

test('metadata HTML preserves real bold, code, links, and escaped text without Markdown reformatting', () => {
  const children = '<strong>Bold &amp; text</strong> <code>x &lt; 1</code> <a href="https://example.com">link</a><br>next'
  const html = metadataBlockHtml(element('h3', { id: 'block-fixed', 'data-block-indent': '1' }, children))
  assert.equal(html, `<h3 id="block-fixed" data-block-indent="1" style="margin-left: 2em">${children}</h3>`)
  assert.equal(new MarkdownIt({ html: true }).render(html).trim(), html)
  assert.equal(metadataBlockHtml(element('p', { id: 'generated-heading-slug', style: 'text-align: center' }, '<em>Centered</em>')), '<p style="text-align: center"><em>Centered</em></p>')
  assert.equal(metadataBlockHtml(element('p', { id: 'block-empty' })), '<p id="block-empty"></p>')
})

test('duplicate link targets from splitting or pasting get distinct ids without changing content', () => {
  const first = schema.node('paragraph', { blockId: 'block-original', indent: 1 }, schema.text('First'))
  const second = schema.node('heading', { blockId: 'block-original', indent: 2, level: 3 }, schema.text('Second'))
  const doc = schema.node('doc', null, [first, second])
  const state = EditorState.create({ doc })
  const updates = duplicateBlockIdUpdates(doc, () => 'block-new')
  assert.equal(updates.length, 1)
  const transaction = state.tr
  for (const { pos, node, blockId } of updates) transaction.setNodeMarkup(pos, undefined, { ...node.attrs, blockId })
  const next = state.apply(transaction)
  assert.equal(next.doc.child(0).attrs.blockId, 'block-original')
  assert.equal(next.doc.child(1).attrs.blockId, 'block-new')
  assert.equal(next.doc.child(1).attrs.indent, 2)
  assert.equal(next.doc.child(1).attrs.level, 3)
  assert.equal(next.doc.textContent, doc.textContent)
  assert.deepEqual(duplicateBlockIdUpdates(next.doc), [])
})

test('created link ids are valid and distinct', () => {
  const first = createBlockId()
  const second = createBlockId()
  assert.ok(isBlockId(first))
  assert.ok(isBlockId(second))
  assert.notEqual(first, second)
  assert.equal(isBlockId('block-foo" onclick="alert(1)'), false)
})

test('pasting a linked block before the original keeps the link on the original', () => {
  const original = schema.node('paragraph', { blockId: 'block-original' }, schema.text('Original'))
  const doc = schema.node('doc', null, original)
  const plugins = BlockMeta.config.addProseMirrorPlugins()
  const state = EditorState.create({ doc, plugins })
  const copy = schema.node('paragraph', { blockId: 'block-original' }, schema.text('Pasted'))
  const { state: next } = state.applyTransaction(state.tr.insert(0, copy))
  assert.equal(next.doc.child(1).attrs.blockId, 'block-original')
  assert.ok(isBlockId(next.doc.child(0).attrs.blockId))
  assert.notEqual(next.doc.child(0).attrs.blockId, 'block-original')
  assert.equal(next.doc.child(0).textContent, 'Pasted')
  assert.equal(next.doc.child(1).textContent, 'Original')
})

test('preview serialization and Markdown reparsing preserve linked formatting and empty link targets', () => {
  const previousDocument = globalThis.document
  const window = domino.createWindow()
  const document = window.document
  // Turndown's existing DOM dependency predates modern browser NodeList methods.
  const createElement = document.createElement.bind(document)
  Object.defineProperty(document, 'createElement', { value: (tag) => {
    const element = createElement(tag)
    const queryAll = element.querySelectorAll.bind(element)
    Object.defineProperty(element, 'querySelectorAll', { value: (selector) => Array.from(queryAll(selector)) })
    return element
  } })
  globalThis.document = document
  try {
    const source = '<h3 id="block-fixed" data-block-indent="2" data-line="12" style="margin-left: 4em"><strong>Bold &amp; text</strong> <code>x &lt; 1</code> <a href="https://example.com">link</a></h3><p id="block-empty"></p><h2 id="ordinary-slug">Ordinary</h2>'
    const markdown = previewHtmlToMd(source)
    assert.match(markdown, /<h3 id="block-fixed" data-block-indent="2" style="margin-left: 4em">/)
    assert.ok(markdown.includes('<strong>Bold &amp; text</strong>'))
    assert.ok(markdown.includes('<code>x &lt; 1</code>'))
    assert.ok(markdown.includes('<p id="block-empty"></p>'))
    assert.ok(markdown.includes('## Ordinary'))
    assert.ok(!markdown.includes('data-line'))
    assert.ok(!markdown.includes('ordinary-slug'))
    const container = document.createElement('div')
    container.innerHTML = new MarkdownIt({ html: true }).render(markdown)
    const parsed = ProseMirrorDOMParser.fromSchema(schema).parse(container)
    assert.equal(parsed.child(0).type.name, 'heading')
    assert.equal(parsed.child(0).attrs.level, 3)
    assert.equal(parsed.child(0).attrs.blockId, 'block-fixed')
    assert.equal(parsed.child(0).attrs.indent, 2)
    assert.equal(parsed.child(0).child(0).marks[0].type.name, 'bold')
    assert.equal(parsed.child(1).attrs.blockId, 'block-empty')
    assert.equal(parsed.child(1).textContent, '')
    assert.equal(parsed.child(2).attrs.blockId, null)
    assert.equal(parsed.child(0).textContent, 'Bold & text x < 1 link')
  } finally {
    if (previousDocument === undefined) delete globalThis.document
    else globalThis.document = previousDocument
  }
})
