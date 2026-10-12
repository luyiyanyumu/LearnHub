import test from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import { compileScript, parse } from '@vue/compiler-sfc'
import * as vue from 'vue'
import * as folderUtils from './folders.js'

function deferred() {
  let resolve, reject
  const promise = new Promise((ok, fail) => { resolve = ok; reject = fail })
  return { promise, resolve, reject }
}

function mountComponent(name, props, api = {}, messageBox = {}) {
  const source = readFileSync(new URL(`../components/${name}.vue`, import.meta.url), 'utf8')
  const compiled = compileScript(parse(source).descriptor, { id: `folder-${name}-test` }).content
  const factory = new Function('modules', compiled
    .replace(/^import\s+([\s\S]*?)\s+from\s+['"]([^'"]+)['"]\s*;?\r?$/gm,
      (_, names, from) => `const ${names.replace(/\bas\b/g, ':')} = modules[${JSON.stringify(from)}]`)
    .replace('export default', 'return'))
  const events = []
  const component = factory({
    vue, '@element-plus/icons-vue': {},
    'element-plus': { ElMessage: { success() {} }, ElMessageBox: messageBox },
    '../api/index.js': { folderApi: api }, '../utils/folders.js': folderUtils,
  })
  component.render = () => null
  const renderer = vue.createRenderer({
    createElement: tag => ({ tag, children: [] }), createText: text => ({ text }), createComment: text => ({ text }),
    insert: (child, parent) => parent.children.push(child), remove() {}, setText() {}, setElementText() {},
    patchProp() {}, parentNode: () => null, nextSibling: () => null,
  })
  const app = renderer.createApp(component, {
    ...props,
    'onUpdate:modelValue': value => events.push(['visibility-or-scope', value]),
    onMoved: value => events.push(['moved', value]), onChanged: value => events.push(['changed', value]),
  })
  app.mount({ children: [] })
  return { app, state: app._instance.setupState, events }
}

test('a pending bulk move uses its original selection once and cannot be dismissed or resubmitted', async () => {
  const pending = deferred(), writes = []
  const ids = [1, 2]
  const h = mountComponent('FolderMoveDialog', {
    modelValue: true, kind: 'note', ids, folders: [{ id: 12, parentId: 0, name: '研究' }],
  }, { moveItems(data) { writes.push(data); return pending.promise } })
  try {
    h.state.target = 12
    const first = h.state.move()
    assert.equal(h.state.busy, true)
    ids.splice(0, ids.length, 99)
    await h.state.move()
    h.state.close()
    assert.deepEqual(h.events, [])
    assert.deepEqual(writes, [{ kind: 'note', ids: [1, 2], folderId: 12 }])
    pending.resolve(2)
    await first
    assert.deepEqual(h.events, [
      ['moved', { folderId: 12, ids: [1, 2] }], ['visibility-or-scope', false],
    ])
    assert.equal(h.state.busy, false)
  } finally { h.app.unmount() }
})

test('move errors retain the dialog and selection; unfiled is sent as null and a missing target never writes', async () => {
  const writes = []
  const h = mountComponent('FolderMoveDialog', { modelValue: true, kind: 'file', ids: [2], folders: [] }, {
    async moveItems(data) { writes.push(data); throw new Error('目标文件夹已被删除') },
  })
  try {
    h.state.target = 123
    assert.equal(h.state.canMove, false)
    await h.state.move()
    assert.deepEqual(writes, [])
    h.state.target = 0
    await h.state.move()
    assert.deepEqual(writes, [{ kind: 'file', ids: [2], folderId: null }])
    assert.equal(h.state.error, '目标文件夹已被删除')
    assert.equal(h.state.busy, false)
    assert.deepEqual(h.events, [])
  } finally { h.app.unmount() }
})

test('folder deletion cannot remove a branch and only resets the active scope after successful deletion', async () => {
  const pending = deferred(), deletes = [], confirmations = []
  const leaf = { id: 12, parentId: 0, name: '研究', children: [] }
  const h = mountComponent('FolderSidebar', { modelValue: 12, kind: 'note', folders: [leaf] }, {
    remove(id) { deletes.push(id); return pending.promise },
  }, { async confirm(text) { confirmations.push(text) } })
  try {
    await h.state.remove({ id: 13, name: '课程', children: [leaf] })
    assert.deepEqual(deletes, [])
    const removal = h.state.remove(leaf)
    await Promise.resolve()
    assert.equal(h.state.busy, true)
    assert.deepEqual(h.events, [])
    assert.match(confirmations[0], /未收纳.*内容会保留/)
    pending.resolve(2)
    await removal
    assert.deepEqual(deletes, [12])
    assert.deepEqual(h.events, [['visibility-or-scope', 0], ['changed', { action: 'delete', folderId: 12 }]])
  } finally { h.app.unmount() }
})

test('folder names follow server limits and move targets retain their complete path for long names', () => {
  const sidebar = mountComponent('FolderSidebar', { kind: 'note', folders: [] })
  const dialog = mountComponent('FolderMoveDialog', {
    modelValue: true, kind: 'note', ids: [1], folders: [
      { id: 1, parentId: 0, name: '研究' }, { id: 2, parentId: 1, name: '深层笔记'.repeat(20) },
    ],
  })
  try {
    assert.equal(sidebar.state.validateName('  研究资料  '), true)
    assert.equal(sidebar.state.validateName('文'.repeat(100)), true)
    for (const name of ['', '文'.repeat(101), 'a/b', 'a\\b', 'a\n\nb', 'a\u0000b', 'a\u0085b', 'a\u007fb']) {
      assert.equal(typeof sidebar.state.validateName(name), 'string', JSON.stringify(name))
    }
    assert.equal(dialog.state.targetPath, '未收纳')
    dialog.state.target = 2
    assert.equal(dialog.state.targetPath, `研究 / ${'深层笔记'.repeat(20)}`)
  } finally { sidebar.app.unmount(); dialog.app.unmount() }
})
