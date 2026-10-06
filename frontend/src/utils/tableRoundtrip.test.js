import assert from 'node:assert/strict'
import test from 'node:test'
import domino from '@mixmark-io/domino'
import MarkdownIt from 'markdown-it'
import { getSchema } from '@tiptap/core'
import StarterKit from '@tiptap/starter-kit'
import { TableKit } from '@tiptap/extension-table'
import { DOMParser } from '@tiptap/pm/model'
import { previewHtmlToMd } from './htmlToMd.js'

const schema = getSchema([StarterKit, TableKit])
function withDocument(operation) {
  const previous = globalThis.document
  const document = domino.createWindow().document
  // Domino's HTMLCollection predates the browser iterable DOM API.
  const collection = Object.getPrototypeOf(document.createElement('div').children)
  if (!collection[Symbol.iterator]) Object.defineProperty(collection, Symbol.iterator, { value: Array.prototype[Symbol.iterator] })
  const createElement = document.createElement.bind(document)
  Object.defineProperty(document, 'createElement', { value: tag => {
    const element = createElement(tag)
    const queryAll = element.querySelectorAll.bind(element)
    Object.defineProperty(element, 'querySelectorAll', { value: selector => Array.from(queryAll(selector)) })
    return element
  } })
  globalThis.document = document
  try { return operation(document) } finally { globalThis.document = previous }
}
function parse(html, document) {
  const box = document.createElement('div')
  box.innerHTML = html
  return DOMParser.fromSchema(schema).parse(box)
}
const table = content => `<table><tbody><tr><th><p>标题</p></th><th><p>说明</p></th></tr>${content}</tbody></table>`

test('cell line breaks, blank lines, marks and neighboring cells survive repeated saves', () => withDocument(document => {
  const original = table('<tr><td><p><strong>第一行</strong><br><br><a href="https://example.com">第三行</a><br></p></td><td><p>相邻不变</p></td></tr>')
  const before = parse(original, document)
  let html = original
  for (let i = 0; i < 3; i++) {
    const markdown = previewHtmlToMd(html)
    assert.match(markdown, /<table>/)
    html = new MarkdownIt({ html: true, breaks: true }).render(markdown)
    const after = parse(html, document)
    after.check()
    assert.ok(after.eq(before))
    assert.equal((html.match(/<br\s*\/?>/g) || []).length, 3)
  }
}))

test('multiple paragraphs and merged cells preserve their structure on reopen', () => withDocument(document => {
  const original = '<table><tbody><tr><th colspan="2"><p>合并标题<br>第二行</p></th></tr><tr><td rowspan="2"><p>第一段</p><p></p><p>第三段</p></td><td><p>右一</p></td></tr><tr><td><p>右二</p></td></tr></tbody></table>'
  const markdown = previewHtmlToMd(original)
  assert.match(markdown, /colspan="2"/)
  assert.match(markdown, /rowspan="2"/)
  const after = parse(new MarkdownIt({ html: true, breaks: true }).render(markdown), document)
  assert.ok(after.eq(parse(original, document)))
}))

test('ordinary single-line tables still use Markdown pipe syntax', () => withDocument(document => {
  const original = table('<tr><td>甲</td><td>乙</td></tr>')
  const markdown = previewHtmlToMd(original)
  assert.ok(!markdown.includes('<table'))
  assert.match(markdown, /\|.*标题.*\|.*说明.*\|/)
  const after = parse(new MarkdownIt({ html: true, breaks: true }).render(markdown), document)
  assert.ok(after.eq(parse(original, document)))
}))
