import assert from 'node:assert/strict'
import test from 'node:test'
import domino from '@mixmark-io/domino'
import MarkdownIt from 'markdown-it'
import { previewHtmlToMd } from './htmlToMd.js'

function withDocument(operation) {
  const previous = globalThis.document
  const document = domino.createWindow().document
  const createElement = document.createElement.bind(document)
  Object.defineProperty(document, 'createElement', { value: (tag) => {
    const element = createElement(tag)
    const queryAll = element.querySelectorAll.bind(element)
    Object.defineProperty(element, 'querySelectorAll', { value: (selector) => Array.from(queryAll(selector)) })
    return element
  } })
  globalThis.document = document
  try { return operation() } finally { globalThis.document = previous }
}

const task = (content, checked = false, label = false) => `<li data-type="taskItem" data-checked="${checked}">${label ? '<label>' : ''}<input type="checkbox"${checked ? ' checked="checked"' : ''}>${label ? '<span>Checkbox decoration</span></label>' : ''}<div>${content}</div></li>`
const list = (items) => `<ul data-type="taskList">${items}</ul>`

test('task model HTML serializes checkbox and paragraph as one standard Markdown task', () => withDocument(() => {
  const markdown = previewHtmlToMd(list(task('<p>任务正文</p>') + task('<p>完成任务</p>', true)))
  assert.equal(markdown, '- [ ] 任务正文\n\n- [x] 完成任务')
  const rendered = new MarkdownIt().render(markdown)
  assert.match(rendered, /<p>\[ \] 任务正文<\/p>/)
  assert.match(rendered, /<p>\[x\] 完成任务<\/p>/)
}))

test('official node-view labels do not duplicate markers or leak checkbox decoration', () => withDocument(() => {
  const markdown = previewHtmlToMd(list(task('<p>只出现一次</p>', true, true)))
  assert.equal(markdown, '- [x] 只出现一次')
  assert.equal((markdown.match(/\[x\]/g) || []).length, 1)
  assert.ok(!markdown.includes('Checkbox decoration'))
}))

test('task serialization preserves inline marks, links, and nested checked tasks', () => withDocument(() => {
  const nested = list(task('<p>子任务 <code>x &lt; 1</code></p>', true))
  const markdown = previewHtmlToMd(list(task(`<p><strong>重点</strong> <a href="https://example.com">链接</a></p>${nested}`)))
  assert.match(markdown, /^- \[ \] \*\*重点\*\* \[链接\]\(https:\/\/example.com\)/)
  assert.match(markdown, /\n\s+- \[x\] 子任务 `x < 1`/)
  assert.equal((markdown.match(/\[x\]/g) || []).length, 1)
}))

test('linked task paragraphs retain ids, indentation, and real HTML formatting', () => withDocument(() => {
  const markdown = previewHtmlToMd(list(task('<p id="block-task" data-block-indent="1" style="margin-left: 2em"><strong>重点</strong></p>')))
  assert.ok(markdown.includes('<p id="block-task" data-block-indent="1" style="margin-left: 2em">[ ] <strong>重点</strong></p>'))
  assert.equal((markdown.match(/\[ \]/g) || []).length, 1)
}))

test('ordinary list checkboxes remain supported and empty tasks retain their marker', () => withDocument(() => {
  assert.equal(previewHtmlToMd('<ul><li><input type="checkbox" checked> 普通任务</li></ul>'), '- [x] 普通任务')
  assert.equal(previewHtmlToMd(list(task('<p></p>'))), '- [ ]')
}))
