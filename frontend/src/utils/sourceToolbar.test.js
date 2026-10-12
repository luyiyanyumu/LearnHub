import assert from 'node:assert/strict'
import test from 'node:test'
import MarkdownIt from 'markdown-it'
import { EditorState } from '@codemirror/state'
import { history, undo } from '@codemirror/commands'
import { createSourceInsert } from './sourceToolbar.js'
import { DEFAULT_FLOWCHART_SOURCE, DEFAULT_FLOWCHART_MARKDOWN } from './flowchart.js'

const markdown = new MarkdownIt({ html: true })
function apply(source, edit) {
  const { from, to, insert } = edit.changes
  return source.slice(0, from) + insert + source.slice(to)
}
function selectedText(source, edit) {
  const result = apply(source, edit)
  return result.slice(edit.selection.anchor, edit.selection.head)
}

test('block insertion splits at the caret and preserves both paragraph halves', () => {
  const source = '前半段后半段\n\n下一段'
  for (const name of ['hr', 'codeBlock', 'table', 'details', 'callout']) {
    const edit = createSourceInsert(source, { from: 3, to: 3 }, name, 'python')
    const result = apply(source, edit)
    assert.deepEqual({ from: edit.changes.from, to: edit.changes.to }, { from: 3, to: 3 })
    assert.ok(result.startsWith('前半段\n\n'), name)
    assert.ok(result.endsWith('\n\n后半段\n\n下一段'), name)
    assert.equal(result.split('下一段').length, 2, name)
  }
})

test('existing blank lines stay intact without redundant separators', () => {
  const source = '前文\n\n后文'
  const edit = createSourceInsert(source, { from: 4, to: 4 }, 'hr')
  assert.equal(apply(source, edit), '前文\n\n---\n\n后文')
  const sourceAtSingleNewline = '前文\n后文'
  const single = createSourceInsert(sourceAtSingleNewline, { from: 3, to: 3 }, 'hr')
  assert.equal(apply(sourceAtSingleNewline, single), '前文\n\n---\n\n后文')
})

test('empty and document-boundary insertions do not gain unnecessary leading blank lines', () => {
  const empty = createSourceInsert('', { from: 0, to: 0 }, 'codeBlock', 'java')
  assert.equal(apply('', empty), '```java\n\n```')
  assert.equal(empty.selection.anchor, 8)
  assert.equal(empty.selection.head, 8)
  assert.equal(apply('正文', createSourceInsert('正文', { from: 0, to: 0 }, 'hr')), '---\n\n正文')
  assert.equal(apply('正文', createSourceInsert('正文', { from: 2, to: 2 }, 'hr')), '正文\n\n---')
})

test('an inserted code fence renders between the original paragraph halves and focuses its empty body', () => {
  const source = 'BeforeAfter'
  const edit = createSourceInsert(source, { from: 6, to: 6 }, 'codeBlock', 'javascript')
  const result = apply(source, edit)
  assert.equal(markdown.render(result), '<p>Before</p>\n<pre><code class="language-javascript">\n</code></pre>\n<p>After</p>\n')
  assert.equal(result.slice(edit.selection.anchor - 1, edit.selection.anchor + 1), '\n\n')
  assert.equal(edit.selection.anchor, edit.selection.head)
})

test('selected code is moved into a fence that cannot be closed by embedded backticks', () => {
  const source = 'Before```x```After'
  const edit = createSourceInsert(source, { from: 6, to: 13 }, 'codeBlock', 'text')
  const result = apply(source, edit)
  assert.equal(selectedText(source, edit), '```x```')
  assert.ok(result.includes('````text\n```x```\n````'))
  assert.equal(result.split('```x```').length, 2)
  assert.ok(markdown.render(result).includes('```x```\n</code>'))
})

test('flowcharts insert the shared Mermaid fence at the caret or selected range without changing nearby source', () => {
  const source = 'BeforeSelectedAfter\n\nLater'
  for (const [from, to] of [[6, 6], [6, 14]]) {
    const edit = createSourceInsert(source, { from, to }, 'flowchart')
    const result = apply(source, edit)
    assert.equal(result, source.slice(0, from) + '\n\n' + DEFAULT_FLOWCHART_MARKDOWN + '\n\n' + source.slice(to))
    assert.equal(selectedText(source, edit), DEFAULT_FLOWCHART_SOURCE)
    const fence = markdown.parse(result, {}).find(token => token.type === 'fence')
    assert.equal(fence.info, 'mermaid')
    assert.equal(fence.content, DEFAULT_FLOWCHART_SOURCE + '\n')
  }
  const empty = createSourceInsert('', { from: 0, to: 0 }, 'flowchart')
  assert.equal(apply('', empty), DEFAULT_FLOWCHART_MARKDOWN)
  assert.equal(selectedText('', empty), DEFAULT_FLOWCHART_SOURCE)
})

test('flowchart source insertion and its editable selection undo together without touching another draft', () => {
  const source = 'BeforeSelectedAfter'
  let state = EditorState.create({ doc: source, selection: { anchor: 6, head: 14 }, extensions: [history()] })
  const other = EditorState.create({ doc: 'Independent synthetic draft', extensions: [history()] })
  const edit = createSourceInsert(state.doc.toString(), state.selection.main, 'flowchart')
  state = state.update(edit).state
  assert.equal(state.doc.sliceString(state.selection.main.from, state.selection.main.to), DEFAULT_FLOWCHART_SOURCE)
  assert.equal(other.doc.toString(), 'Independent synthetic draft')
  const view = { get state() { return state }, dispatch(tr) { state = tr.state } }
  assert.equal(undo(view), true)
  assert.equal(state.doc.toString(), source)
  assert.equal(state.selection.main.anchor, 6)
  assert.equal(state.selection.main.head, 14)
  assert.equal(undo(view), false)
})

test('table insertion isolates the table and places the caret in the first empty data cell', () => {
  const source = 'LeftRight'
  const edit = createSourceInsert(source, { from: 4, to: 4 }, 'table')
  const result = apply(source, edit)
  assert.ok(markdown.render(result).includes('<table>'))
  assert.equal(result.slice(edit.selection.anchor - 2, edit.selection.anchor + 2), '|  |')
  assert.equal(edit.selection.anchor, edit.selection.head)
})

test('details and callout move selected text into the inserted block without duplication', () => {
  const source = '前文目标文字后文'
  for (const name of ['details', 'callout']) {
    const edit = createSourceInsert(source, { from: 2, to: 6 }, name)
    const result = apply(source, edit)
    assert.ok(result.startsWith('前文\n\n'), name)
    assert.ok(result.endsWith('\n\n后文'), name)
    assert.equal(result.split('目标文字').length, 2, name)
    assert.equal(selectedText(source, edit), '目标文字', name)
  }
})

test('details and callout select only their placeholder at an empty caret', () => {
  assert.equal(selectedText('', createSourceInsert('', { from: 0, to: 0 }, 'details')), '折叠内容')
  assert.equal(selectedText('', createSourceInsert('', { from: 0, to: 0 }, 'callout')), '提示内容')
})

test('inline code replaces the selection while preserving surrounding text and literal backticks', () => {
  const source = '前文a`b后文'
  const edit = createSourceInsert(source, { from: 2, to: 5 }, 'inlineCode')
  assert.equal(apply(source, edit), '前文``a`b``后文')
  assert.equal(selectedText(source, edit), 'a`b')
  assert.ok(markdown.render(apply(source, edit)).includes('<code>a`b</code>'))
  assert.equal(selectedText('前后', createSourceInsert('前后', { from: 1, to: 1 }, 'inlineCode')), '代码')
})

test('inline links retain selected labels and escaped Markdown punctuation at the original caret', () => {
  const source = '前文[a]后文'
  const edit = createSourceInsert(source, { from: 2, to: 5 }, 'link', 'https://example.com/a(b)')
  assert.equal(apply(source, edit), '前文[\\[a\\]](<https://example.com/a(b)>)后文')
  assert.ok(markdown.render(apply(source, edit)).includes('href="https://example.com/a(b)"'))
  assert.ok(markdown.render(apply(source, edit)).includes('>[a]</a>'))
  const empty = createSourceInsert('前后', { from: 1, to: 1 }, 'link', 'https://example.com')
  assert.equal(selectedText('前后', empty), 'https://example.com')
})

test('note links insert the supplied label at the caret and escape literal Markdown and HTML characters', () => {
  const source = '前文后文'
  const label = '<b>章节</b> [1] *重点* _下划线_ `代码` \\ 中文'
  const href = '/notes/12#section=A%26B&occurrence=2'
  const edit = createSourceInsert(source, { from: 2, to: 2 }, 'noteLink', { href, text: label })
  const result = apply(source, edit)
  const tokens = markdown.parseInline(result, {})[0].children
  const opened = tokens.findIndex(token => token.type === 'link_open')
  assert.notEqual(opened, -1)
  assert.equal(tokens[opened].attrGet('href'), href)
  assert.equal(tokens[opened + 1].type, 'text')
  assert.equal(tokens[opened + 1].content, label)
  assert.equal(tokens[opened + 2].type, 'link_close')
  assert.ok(result.startsWith('前文['))
  assert.ok(result.endsWith('>)后文'))
  assert.equal(edit.selection.anchor, result.length - 2)
  assert.equal(edit.selection.head, edit.selection.anchor)
  assert.equal(markdown.render(result).includes('<b>'), false)
})

test('note links replace only the captured range and restore source and selection with one undo', () => {
  const source = '前文旧内容后文'
  for (const [from, to] of [[2, 2], [2, 5]]) {
    let state = EditorState.create({ doc: source, selection: { anchor: from, head: to }, extensions: [history()] })
    const edit = createSourceInsert(state.doc.toString(), state.selection.main, 'noteLink', { href: '/notes/8', text: '新章节' })
    state = state.update(edit).state
    assert.equal(state.doc.toString(), source.slice(0, from) + '[新章节](</notes/8>)' + source.slice(to))
    const view = { get state() { return state }, dispatch(tr) { state = tr.state } }
    assert.equal(undo(view), true)
    assert.equal(state.doc.toString(), source)
    assert.equal(state.selection.main.anchor, from)
    assert.equal(state.selection.main.head, to)
    assert.equal(undo(view), false)
  }
})

test('note links fall back to selected text or href and invalid targets produce no source edit', () => {
  const source = '前目标后'
  const selected = createSourceInsert(source, { from: 1, to: 3 }, 'noteLink', { href: '/notes/8' })
  assert.equal(apply(source, selected), '前[目标](</notes/8>)后')
  const caret = createSourceInsert(source, { from: 1, to: 1 }, 'noteLink', { href: '/notes/8', text: '' })
  assert.equal(apply(source, caret), '前[/notes/8](</notes/8>)目标后')
  for (const href of ['', 'javascript:alert(1)', '//example.com', '/notes/\n12', '/notes\\12']) {
    assert.equal(createSourceInsert(source, { from: 1, to: 3 }, 'noteLink', { href, text: '章节' }), null)
  }
})

test('images remain inline and select the alt text without changing surrounding content', () => {
  const source = '前半后半'
  const edit = createSourceInsert(source, { from: 2, to: 2 }, 'image', 'https://example.com/image.png')
  assert.equal(apply(source, edit), '前半![图片描述](<https://example.com/image.png>)后半')
  assert.equal(selectedText(source, edit), '图片描述')
  assert.ok(markdown.render(apply(source, edit)).startsWith('<p>前半<img'))
})

test('task insertion moves selected text to the task without affecting text on either side', () => {
  const source = '前文待办事项后文'
  const edit = createSourceInsert(source, { from: 2, to: 6 }, 'todo')
  assert.equal(apply(source, edit), '前文\n\n- [ ] 待办事项\n\n后文')
  assert.equal(selectedText(source, edit), '待办事项')
  assert.equal(selectedText('', createSourceInsert('', { from: 0, to: 0 }, 'todo')), '任务')
})

test('Markdown template insertion preserves the supplied text and leaves the caret at its end', () => {
  const source = 'BeforeAfter'
  const template = '## 标题\n\n正文\n'
  const edit = createSourceInsert(source, { from: 6, to: 6 }, 'markdown', template)
  const result = apply(source, edit)
  assert.equal(result, 'Before\n\n## 标题\n\n正文\n\nAfter')
  assert.equal(edit.selection.anchor, result.indexOf('\nAfter'))
  assert.equal(edit.selection.anchor, edit.selection.head)
  assert.equal(createSourceInsert('', { from: 0, to: 0 }, 'markdown', ''), null)
})

test('the edit and its caret form one CodeMirror history step that restores the original selection', () => {
  const source = '前文目标内容后文'
  let state = EditorState.create({ doc: source, selection: { anchor: 2, head: 6 }, extensions: [history()] })
  const edit = createSourceInsert(state.doc.toString(), state.selection.main, 'details')
  state = state.update(edit).state
  assert.equal(state.doc.sliceString(state.selection.main.from, state.selection.main.to), '目标内容')
  assert.equal(undo({ get state() { return state }, dispatch(tr) { state = tr.state } }), true)
  assert.equal(state.doc.toString(), source)
  assert.equal(state.selection.main.anchor, 2)
  assert.equal(state.selection.main.head, 6)
  assert.equal(undo({ get state() { return state }, dispatch(tr) { state = tr.state } }), false)
})

test('invalid operations produce no edit and selection offsets remain inside the resulting document', () => {
  assert.equal(createSourceInsert('abc', { from: 1, to: 1 }, 'unknown'), null)
  assert.equal(createSourceInsert('abc', { from: NaN, to: 1 }, 'hr'), null)
  assert.equal(createSourceInsert('abc', { from: 1, to: 1 }, 'link', ''), null)
  assert.equal(createSourceInsert('abc', { from: 1, to: 1 }, 'image'), null)
  const reversed = createSourceInsert('abc', { from: 99, to: -3 }, 'callout')
  assert.deepEqual({ from: reversed.changes.from, to: reversed.changes.to }, { from: 0, to: 3 })
  assert.equal(selectedText('abc', reversed), 'abc')
})
