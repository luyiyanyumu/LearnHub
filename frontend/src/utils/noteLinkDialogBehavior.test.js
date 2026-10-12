import test from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import { compileScript, parse } from '@vue/compiler-sfc'
import * as vue from 'vue'
import * as noteLinks from './noteLinks.js'

function deferred() {
  let resolve, reject
  const promise = new Promise((ok, fail) => { resolve = ok; reject = fail })
  return { promise, resolve, reject }
}

function mountDialog(props, api) {
  const source = readFileSync(new URL('../components/NoteLinkDialog.vue', import.meta.url), 'utf8')
  const compiled = compileScript(parse(source).descriptor, { id: 'note-link-dialog-test' }).content
  const factory = new Function('modules', compiled
    .replace(/^import\s+([\s\S]*?)\s+from\s+['"]([^'"]+)['"]\s*;?\r?$/gm,
      (_, names, from) => `const ${names.replace(/\bas\b/g, ':')} = modules[${JSON.stringify(from)}]`)
    .replace('export default', 'return'))
  const component = factory({ vue, '../api/index.js': { noteApi: api }, '../utils/noteLinks.js': noteLinks })
  component.render = () => null
  const events = []
  const renderer = vue.createRenderer({
    createElement: tag => ({ tag, children: [] }), createText: text => ({ text }), createComment: text => ({ text }),
    insert: (child, parent) => parent.children.push(child), remove() {}, setText() {}, setElementText() {},
    patchProp() {}, parentNode: () => null, nextSibling: () => null,
  })
  const app = renderer.createApp(component, {
    ...props, 'onUpdate:modelValue': value => events.push(['visible', value]), onInsert: value => events.push(['insert', value]),
  })
  app.mount({ children: [] })
  return { app, state: app._instance.setupState, events }
}

test('current-note links use unsaved headings and preserve selected text, including duplicate sections', async () => {
  const detailCalls = []
  const h = mountDialog({ modelValue: true, currentNoteId: 4, currentTitle: '正在编辑', currentContent: '## 小结\n\n## 新内容\n\n## 小结', initialText: '查看这里' }, {
    async page() { return { list: [{ id: 4, title: '旧标题' }, { id: 5, title: '别的笔记' }], total: 2 } },
    async detail(id) { detailCalls.push(id); return { id, content: '# 服务端旧内容' } },
  })
  try {
    await vue.nextTick()
    assert.deepEqual(detailCalls, [])
    assert.deepEqual(h.state.headings.map(heading => heading.text), ['小结', '新内容', '小结'])
    assert.equal(h.state.noteOptions[0].title, '正在编辑')
    h.state.headingKey = h.state.headings[2].key
    await vue.nextTick()
    h.state.insert()
    assert.deepEqual(h.events, [
      ['insert', { href: noteLinks.createNoteLink(4, { text: '小结', occurrence: 2 }), text: '查看这里' }], ['visible', false],
    ])
  } finally { h.app.unmount() }
})

test('switching notes ignores stale detail results and cannot insert while the chosen note is loading', async () => {
  const first = deferred(), second = deferred()
  const h = mountDialog({ modelValue: true }, {
    async page() { return { list: [{ id: 5, title: '笔记五' }, { id: 6, title: '笔记六' }] } },
    detail(id) { return id === 5 ? first.promise : second.promise },
  })
  try {
    h.state.selectedNoteId = 5
    await vue.nextTick()
    assert.equal(h.state.canInsert, false)
    h.state.selectedNoteId = 6
    await vue.nextTick()
    first.resolve({ id: 5, title: '笔记五', content: '# 旧请求的章节' })
    await Promise.resolve(); await vue.nextTick()
    assert.equal(h.state.canInsert, false)
    assert.equal(h.state.detail, null)
    second.resolve({ id: 6, title: '笔记六', content: '# 正确的章节' })
    await Promise.resolve(); await vue.nextTick()
    assert.equal(h.state.canInsert, true)
    assert.deepEqual(h.state.headings.map(heading => heading.text), ['正确的章节'])
    h.state.headingKey = h.state.headings[0].key
    await vue.nextTick()
    h.state.insert()
    assert.deepEqual(h.events[0], ['insert', { href: noteLinks.createNoteLink(6, '正确的章节'), text: '正确的章节' }])
  } finally { h.app.unmount() }
})

test('latest search wins, cancelling ignores late responses, and failed details never produce a link', async () => {
  const initial = deferred(), search = deferred(), late = deferred()
  const h = mountDialog({ modelValue: true }, {
    page({ kw }) { return kw === '最新' ? search.promise : kw === '关闭后' ? late.promise : initial.promise },
    async detail() { throw new Error('该笔记已删除') },
  })
  try {
    const loading = h.state.loadNotes('最新')
    search.resolve({ list: [{ id: 8, title: '最新笔记' }], total: 1 })
    await loading
    initial.resolve({ list: [{ id: 7, title: '旧搜索' }], total: 1 })
    await Promise.resolve(); await vue.nextTick()
    assert.deepEqual(h.state.notes.map(note => note.id), [8])
    h.state.selectedNoteId = 8
    await vue.nextTick(); await Promise.resolve(); await vue.nextTick()
    assert.equal(h.state.detailError, '该笔记已删除')
    assert.equal(h.state.canInsert, false)
    h.state.insert()
    assert.deepEqual(h.events, [])
    const afterClose = h.state.loadNotes('关闭后')
    h.state.close()
    late.resolve({ list: [{ id: 9, title: '不应该展示' }] })
    await afterClose
    assert.deepEqual(h.state.notes.map(note => note.id), [8])
    assert.deepEqual(h.events, [['visible', false]])
  } finally { h.app.unmount() }
})

test('external links reject executable schemes and cancellation does not insert anything', async () => {
  const h = mountDialog({ modelValue: true }, { async page() { return { list: [] } } })
  try {
    h.state.type = 'external'
    h.state.address = 'javascript:alert(1)'
    await vue.nextTick()
    assert.equal(h.state.canInsert, false)
    h.state.insert()
    assert.deepEqual(h.events, [])
    h.state.address = 'https://example.com/path'
    await vue.nextTick()
    assert.equal(h.state.canInsert, true)
    assert.equal(h.state.text, 'https://example.com/path')
    h.state.close()
    assert.deepEqual(h.events, [['visible', false]])
  } finally { h.app.unmount() }
})
