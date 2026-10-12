import test from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import { compileScript, compileTemplate, parse } from '@vue/compiler-sfc'
import * as vue from 'vue'
import * as folders from '../utils/folders.js'
import * as explorer from '../utils/folderExplorer.js'

const source = readFileSync(new URL('./FolderExplorerToolbar.vue', import.meta.url), 'utf8')
const descriptor = parse(source).descriptor
const compiled = compileScript(descriptor, { id: 'folder-explorer-toolbar-behavior' })
const factory = new Function('modules', compiled.content
  .replace(/^import\s+([\s\S]*?)\s+from\s+['"]([^'"]+)['"]\s*;?\r?$/gm,
    (_, names, from) => `const ${names.replace(/\bas\b/g, ':')} = modules[${JSON.stringify(from)}]`)
  .replace('export default', 'return'))

const syntheticFolders = [
  { id: 1, parentId: 0, name: '示例课程', itemCount: 2 },
  { id: 2, parentId: 1, name: '示例章节', itemCount: 1 },
  { id: 3, parentId: 0, name: '示例研究', itemCount: 0 },
]

function deferred() {
  let resolve, reject
  const promise = new Promise((ok, fail) => { resolve = ok; reject = fail })
  return { promise, resolve, reject }
}

async function flush() {
  for (let i = 0; i < 5; i++) { await Promise.resolve(); await vue.nextTick() }
}

function harness(options = {}) {
  const props = vue.reactive({ kind: 'note', folders: structuredClone(syntheticFolders), modelValue: 0, loading: false, ...options.props })
  const calls = [], events = [], prompts = [], confirmations = [], successes = []
  const component = factory({
    vue,
    '@element-plus/icons-vue': {},
    'element-plus': {
      ElMessage: { success(message) { successes.push(message) } },
      ElMessageBox: {
        prompt(...args) { prompts.push(args); return options.prompt ? options.prompt(...args) : Promise.resolve({ value: '新示例目录' }) },
        confirm(...args) { confirmations.push(args); return options.confirm ? options.confirm(...args) : Promise.resolve() },
      },
    },
    '../api/index.js': {
      folderApi: {
        create(data) { calls.push(['create', data]); return options.create ? options.create(data) : Promise.resolve({ id: 10, ...data }) },
        update(id, data) { calls.push(['update', id, data]); return options.update ? options.update(id, data) : Promise.resolve() },
        remove(id) { calls.push(['remove', id]); return options.remove ? options.remove(id) : Promise.resolve() },
      },
    },
    '../utils/folders.js': folders,
    '../utils/folderExplorer.js': explorer,
  })
  component.render = () => null
  const renderer = vue.createRenderer({
    createElement: tag => ({ tag, children: [] }), createText: text => ({ text }), createComment: text => ({ text }),
    insert: (child, parent) => parent.children.push(child), remove() {}, setText() {}, setElementText() {},
    patchProp() {}, parentNode: () => null, nextSibling: () => null,
  })
  const parent = {
    setup: () => () => vue.h(component, {
      ...props,
      'onUpdate:modelValue': value => { events.push(['scope', value]); props.modelValue = value },
      onChanged: value => events.push(['changed', value]),
    }),
  }
  const app = renderer.createApp(parent)
  app.mount({ children: [] })
  return {
    state: app._instance.subTree.component.setupState, props, calls, events, prompts, confirmations, successes,
    async navigate(scope) { this.state.navigate(scope); await flush() },
    async back() { this.state.traverse(this.state.previousIndex); await flush() },
    async forward() { this.state.traverse(this.state.nextIndex); await flush() },
    dispose() { app.unmount() },
  }
}

test('history follows root, parent and child and preserves forward navigation until a new branch', async () => {
  const h = harness()
  try {
    assert.equal(h.state.previousIndex, -1)
    assert.equal(h.state.nextIndex, -1)
    await h.navigate(1)
    await h.navigate(2)
    assert.deepEqual(h.state.history, [0, 1, 2])
    assert.deepEqual(h.state.breadcrumbs, [{ id: 1, name: '示例课程' }, { id: 2, name: '示例章节' }])
    await h.back()
    assert.equal(h.state.scope, 1)
    await h.back()
    assert.equal(h.state.scope, 0)
    await h.forward()
    await h.forward()
    assert.equal(h.state.scope, 2)
    await h.navigate(h.state.parentFolderScope(h.props.folders, h.state.scope))
    assert.equal(h.state.scope, 1)
    assert.deepEqual(h.state.history, [0, 1, 2, 1])
    await h.back()
    assert.equal(h.state.scope, 2)
    await h.navigate(3)
    assert.deepEqual(h.state.history, [0, 1, 2, 3])
    assert.equal(h.state.nextIndex, -1)
    const eventCount = h.events.length
    await h.navigate(3)
    assert.equal(h.events.length, eventCount)
  } finally { h.dispose() }
})

test('external scope changes join history and deleted directories are skipped in both directions', async () => {
  const h = harness()
  try {
    await h.navigate(1)
    h.props.modelValue = '2'
    await flush()
    h.props.modelValue = 3
    await flush()
    assert.deepEqual(h.state.history, [0, 1, 2, 3])
    h.props.folders = h.props.folders.filter(folder => folder.id !== 2)
    await flush()
    await h.back()
    assert.equal(h.state.scope, 1)
    assert.equal(h.state.historyIndex, 1)
    await h.forward()
    assert.equal(h.state.scope, 3)
    h.props.modelValue = null
    await flush()
    assert.deepEqual(h.state.history, [0, 1, 2, 3, null])
    await h.back()
    assert.equal(h.state.scope, 3)
    await h.forward()
    assert.equal(h.state.scope, null)
    assert.equal(h.state.parentFolderScope(h.props.folders, h.state.scope), 0)
  } finally { h.dispose() }
})

test('loading and in-flight mutation block navigation and duplicate submissions', async () => {
  const pending = deferred()
  const h = harness({ props: { loading: true }, prompt: () => pending.promise })
  try {
    await h.navigate(1)
    await h.state.manageFolder('create')
    assert.deepEqual(h.events, [])
    assert.deepEqual(h.prompts, [])
    h.props.loading = false
    await flush()
    const creation = h.state.manageFolder('create')
    assert.equal(h.state.busy, true)
    await h.navigate(1)
    await h.state.manageFolder('create')
    assert.equal(h.prompts.length, 1)
    assert.deepEqual(h.events, [])
    pending.resolve({ value: '新示例目录' })
    await creation
    assert.equal(h.state.busy, false)
    assert.equal(h.calls.length, 1)
  } finally { h.dispose() }
})

test('create uses themed prompt validation and snapshots original parent and kind while modal is open', async () => {
  const pending = deferred()
  const h = harness({ props: { modelValue: 1 }, prompt: () => pending.promise })
  try {
    const creation = h.state.manageFolder('create')
    const [message, title, config] = h.prompts[0]
    assert.equal(message, '在「示例课程」内创建文件夹：')
    assert.equal(title, '新建文件夹')
    assert.equal(config.confirmButtonText, '创建')
    assert.equal(config.cancelButtonText, '取消')
    assert.equal(config.inputValidator('  合成名称  '), true)
    assert.equal(config.inputValidator('文'.repeat(100)), true)
    for (const name of ['', '文'.repeat(101), 'a/b', 'a\\b', 'a\n\nb', 'a\u0000b', 'a\u0085b']) {
      assert.equal(typeof config.inputValidator(name), 'string')
    }
    h.props.modelValue = 3
    h.props.kind = 'file'
    await flush()
    pending.resolve({ value: '  新示例目录  ' })
    await creation
    assert.deepEqual(h.calls, [['create', { kind: 'note', name: '新示例目录', parentId: 1 }]])
    assert.deepEqual(h.events, [['changed', { action: 'create', folder: { id: 10, kind: 'note', name: '新示例目录', parentId: 1 } }]])
  } finally { h.dispose() }
})

test('create at root and aggregate snapshots root and explicit row actions use that row as parent', async () => {
  for (const initialScope of [0, null]) {
    const pending = deferred()
    const h = harness({ props: { modelValue: initialScope }, prompt: () => pending.promise })
    try {
      const creation = h.state.manageFolder('create')
      assert.equal(h.prompts[0][0], '文件夹名称：')
      h.props.modelValue = 1
      await flush()
      pending.resolve({ value: '根目录示例' })
      await creation
      assert.deepEqual(h.calls, [['create', { kind: 'note', name: '根目录示例', parentId: 0 }]])
    } finally { h.dispose() }
  }
  const h = harness()
  try {
    await h.state.manageFolder('create', { id: 2, name: '过期名称' })
    assert.equal(h.prompts[0][0], '在「示例章节」内创建文件夹：')
    assert.deepEqual(h.calls, [['create', { kind: 'note', name: '新示例目录', parentId: 2 }]])
    await h.state.manageFolder('create', { id: 999, name: '失效目录' })
    assert.equal(h.prompts.length, 1)
  } finally { h.dispose() }
})

test('cancelled create, rename and delete never call APIs or leave busy/error state', async () => {
  for (const cancellation of ['cancel', 'close']) {
    const h = harness({
      props: { modelValue: 2 },
      prompt: () => Promise.reject(cancellation), confirm: () => Promise.reject(cancellation),
    })
    try {
      for (const action of ['create', 'rename', 'delete']) {
        await h.state.manageFolder(action)
        assert.equal(h.state.busy, false)
        assert.equal(h.state.error, '')
      }
      assert.deepEqual(h.calls, [])
      assert.deepEqual(h.events, [])
      assert.equal(h.prompts.length, 2)
      assert.equal(h.confirmations.length, 1)
    } finally { h.dispose() }
  }
})

test('rename resolves current row metadata, trims names and avoids unnecessary writes', async () => {
  let nextName = '  示例章节  '
  const h = harness({ prompt: async () => ({ value: nextName }) })
  try {
    await h.state.manageFolder('rename', { id: 2, name: '过期名称' })
    assert.equal(h.prompts[0][1], '重命名文件夹')
    assert.equal(h.prompts[0][2].inputValue, '示例章节')
    assert.equal(h.prompts[0][2].confirmButtonText, '保存')
    assert.deepEqual(h.calls, [])
    nextName = '  更新名称  '
    await h.state.manageFolder('rename', { id: 2 })
    assert.deepEqual(h.calls, [['update', 2, { name: '更新名称' }]])
    assert.deepEqual(h.events, [['changed', { action: 'rename', folderId: 2 }]])
    await h.state.manageFolder('rename', { id: 999 })
    await h.state.manageFolder('unsupported', { id: 2 })
    assert.equal(h.prompts.length, 2)
  } finally { h.dispose() }
})

test('non-leaf deletion is refused and successful active leaf deletion returns to root after API completion', async () => {
  const pending = deferred()
  const h = harness({ props: { modelValue: 2, kind: 'file' }, remove: () => pending.promise })
  try {
    await h.state.manageFolder('delete', { id: 1, children: [] })
    assert.deepEqual(h.confirmations, [])
    assert.deepEqual(h.calls, [])
    const removal = h.state.manageFolder('delete')
    await flush()
    assert.equal(h.state.busy, true)
    assert.match(h.confirmations[0][0], /示例章节.*资料将移到根目录，内容会保留/)
    assert.equal(h.confirmations[0][2].confirmButtonClass, 'el-button--danger')
    assert.deepEqual(h.calls, [['remove', 2]])
    assert.deepEqual(h.events, [])
    pending.resolve()
    await removal
    await flush()
    assert.equal(h.state.scope, 0)
    assert.deepEqual(h.events, [['scope', 0], ['changed', { action: 'delete', folderId: 2 }]])
    assert.equal(h.state.busy, false)
  } finally { h.dispose() }
})

test('deleting a background folder preserves the externally selected location and API failure retains current folder', async () => {
  const pending = deferred()
  const h = harness({ props: { modelValue: 2 }, remove: () => pending.promise })
  try {
    const removal = h.state.manageFolder('delete')
    await flush()
    h.props.modelValue = 3
    await flush()
    pending.resolve()
    await removal
    assert.equal(h.state.scope, 3)
    assert.deepEqual(h.events, [['changed', { action: 'delete', folderId: 2 }]])
  } finally { h.dispose() }
  const failed = harness({ props: { modelValue: 2 }, remove: async () => { throw new Error('合成失败消息') } })
  try {
    await failed.state.manageFolder('delete')
    assert.equal(failed.state.error, '合成失败消息')
    assert.equal(failed.state.scope, 2)
    assert.equal(failed.state.busy, false)
    assert.deepEqual(failed.events, [])
    await failed.navigate(0)
    assert.equal(failed.state.error, '')
  } finally { failed.dispose() }
})

test('toolbar template compiles with accessible navigation and themed prompt controls', () => {
  const result = compileTemplate({
    source: descriptor.template.content, filename: 'FolderExplorerToolbar.vue', id: 'folder-explorer-toolbar-behavior',
    compilerOptions: { bindingMetadata: compiled.bindings },
  })
  assert.deepEqual(result.errors, [])
  assert.match(source, /aria-label="后退"/)
  assert.match(source, /aria-label="前进"/)
  assert.match(source, /aria-label="上一级"/)
  assert.equal(/<select\b/.test(source), false)
  assert.equal(source.includes('window.prompt'), false)
})
