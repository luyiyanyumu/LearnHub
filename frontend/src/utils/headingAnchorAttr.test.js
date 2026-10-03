import test from 'node:test'
import assert from 'node:assert/strict'
import { getSchema } from '@tiptap/core'
import StarterKit from '@tiptap/starter-kit'
import { DOMParser, DOMSerializer } from '@tiptap/pm/model'
import domino from '@mixmark-io/domino'
import MarkdownIt from 'markdown-it'
import mdAnchor from './mdAnchor.js'
import { HeadingAnchorAttr, findNoteAnchor } from './headingAnchorAttr.js'
import { BlockMeta } from './blockMeta.js'
import { FontStyle } from './inlineMarks.js'

const schema = getSchema([StarterKit, BlockMeta, HeadingAnchorAttr, FontStyle])

test('generated TOC links retain their heading targets through the editable preview', () => {
  const document = domino.createWindow().document
  const container = document.createElement('div')
  container.innerHTML = new MarkdownIt({ html: true }).use(mdAnchor).render('# Python 学习笔记\n\n## 核心知识\n\n## 核心知识')
  const doc = DOMParser.fromSchema(schema).parse(container)
  assert.deepEqual([...Array(doc.childCount)].map((_, i) => doc.child(i).attrs.headingAnchor), ['python-学习笔记', '核心知识', '核心知识-1'])
  const rendered = document.createElement('div')
  rendered.appendChild(DOMSerializer.fromSchema(schema).serializeFragment(doc.content, { document }))
  assert.equal(rendered.querySelector('h1').id, 'python-学习笔记')
  assert.ok(rendered.querySelector('#核心知识-1'))
  assert.equal(findNoteAnchor(rendered, '#%E6%A0%B8%E5%BF%83%E7%9F%A5%E8%AF%86-1').id, '核心知识-1')
  assert.equal(findNoteAnchor(rendered, '#missing'), null)
  assert.equal(findNoteAnchor(rendered, 'https://example.com/#核心知识-1'), null)
  assert.equal(findNoteAnchor(rendered, '#%invalid'), null)
})

test('permanent block links continue to take priority over generated heading anchors', () => {
  const node = schema.node('heading', { level: 2, blockId: 'block-fixed', headingAnchor: 'generated' }, schema.text('Heading'))
  assert.equal(schema.nodes.heading.spec.toDOM(node)[1].id, 'block-fixed')
})

test('single-character color and combined font styles survive preview parsing and serialization', () => {
  for (const tag of ['font', 'span']) {
    const document = domino.createWindow().document
    const container = document.createElement('div')
    container.innerHTML = `<p>Py<${tag} style="color: #e34e58; font-size: 24px">t</${tag}>hon</p>`
    const doc = DOMParser.fromSchema(schema).parse(container)
    assert.equal(doc.textContent, 'Python')
    const mark = doc.firstChild.child(1).marks.find(m => m.type.name === 'fontStyle')
    assert.ok(mark?.attrs.style.includes('color: #e34e58'))
    assert.ok(mark?.attrs.style.includes('font-size: 24px'))
    const rendered = document.createElement('div')
    rendered.appendChild(DOMSerializer.fromSchema(schema).serializeFragment(doc.content, { document }))
    assert.equal(rendered.querySelector('font').textContent, 't')
  }
})
