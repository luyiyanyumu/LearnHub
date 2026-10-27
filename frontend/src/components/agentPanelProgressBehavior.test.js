import test from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import { compileScript, parse } from '@vue/compiler-sfc'
import * as vue from 'vue'
import * as profileView from '../utils/modelProfileView.js'
import * as noteMerge from '../utils/agentNoteMerge.js'
import * as noteEdit from '../utils/agentNoteEdit.js'
import * as retrievalDisplay from '../utils/retrievalDisplay.js'
import * as agentProgress from '../utils/agentProgress.js'
import * as replyActions from '../utils/replyActions.js'
import { fixHtmlQuotes } from '../utils/htmlQuotes.js'
import { groundingDisplay } from '../utils/groundingDisplay.js'
import { mermaidFrameHtml } from '../utils/mermaidPreview.js'

const source = readFileSync(new URL('./AgentPanel.vue', import.meta.url), 'utf8')
const compiled = compileScript(parse(source).descriptor, { id: 'agent-panel-progress-behavior' }).content
const factory = new Function('modules', compiled
  .replace(/^import\s+([\s\S]*?)\s+from\s+['"]([^'"]+)['"]\s*;?\r?$/gm,
    (_, names, from) => `const ${names.replace(/\bas\b/g, ':')} = modules[${JSON.stringify(from)}]`)
  .replace('export default', 'return'))
const deferred = () => { let resolve, reject; const promise = new Promise((ok, fail) => { resolve = ok; reject = fail }); return { promise, resolve, reject } }
const flush = async () => { for (let i = 0; i < 7; i++) { await Promise.resolve(); await vue.nextTick() } }
const result = (sessionId, reply, extra = {}) => ({ sessionId, reply, events: [], progress: [], retrieved: [], pendingActions: [], ...extra })
const action = { id: 91, toolName: 'edit_note', summary: '更新合成目录', preview: { noteId: 701 } }

function harness(options = {}) {
  const previousWindow = globalThis.window, previousStorage = globalThis.localStorage
  const values = new Map(options.saved ? [['lh-agent-session', options.saved]] : [])
  globalThis.window = new EventTarget()
  globalThis.localStorage = { getItem: key => values.get(key) ?? null, setItem: (key, value) => values.set(key, value), removeItem: key => values.delete(key) }
  const calls = [], legacyCalls = [], approvals = [], rejections = [], checks = [], updates = [], warnings = [], notices = [], preparations = [], insertions = []
  const history = new Map()
  const reads = []
  window.addEventListener(noteEdit.AGENT_NOTE_UPDATED_EVENT, event => updates.push(event.detail))
  const component = factory({
    vue, 'vue-router': { useRouter: () => ({ push() {} }) },
    'element-plus': { ElMessage: {
      success: text => notices.push({ type: 'success', text }),
      info: text => notices.push({ type: 'info', text }),
      error: text => notices.push({ type: 'error', text }),
      warning: text => { warnings.push(text); notices.push({ type: 'warning', text }) },
    }, ElMessageBox: { async confirm() {} } },
    '../api': {
      aiApi: {
        async status() { return { configured: true } },
        async session(id) { reads.push(id); return options.session ? options.session(id) : history.get(id) || { messages: [], pendingActions: [] } },
        chatStream(request, onEvent, signal) { const done = deferred(); calls.push({ request, onEvent, signal, ...done }); return done.promise },
        async chat(request, signal) { legacyCalls.push({ request, signal }); return result(request.sessionId || 'legacy', '旧版合成回答') },
        async approveAction(id) { approvals.push(id); return options.approve ? options.approve(id) : { ok: true, hint: '合成修改完成' } },
        async rejectAction(id) { rejections.push(id) },
        async actionState(id) { checks.push(id); return options.actionState ? options.actionState(id) : { id, status: 'pending' } },
      },
      modelApi: { async sessions() { return [{ id: 'a' }, { id: 'b' }] }, async profiles() { return { profiles: [{ id: 'profile-a', purpose: 'chat' }] } }, async setSessionModel() {} },
      settingsApi: { async get() { return { webEnabled: false } } }, categoryApi: { async tree() { return [] } }, noteApi: {},
    },
    './AgentRunTrace.vue': {},
    '../utils/htmlQuotes': { fixHtmlQuotes }, '../utils/agentNoteMerge': noteMerge,
    '../utils/mermaidPreview.js': { mermaidFrameHtml }, '../utils/agentProgress.js': agentProgress,
    '../utils/agentNoteEdit': { ...noteEdit,
      async prepareAgentNote(request) {
        preparations.push(request)
        return options.prepare ? options.prepare(request) : { ...request, title: '合成已保存', context: '合成最新正文', dirty: false, isNew: false }
      },
      async insertAgentNote(request) { insertions.push(request); return options.insert ? options.insert(request) : { ok: true } },
    }, '../utils/retrievalDisplay': retrievalDisplay,
    '../utils/replyActions.js': replyActions,
    '../utils/groundingDisplay': { groundingDisplay }, '../utils/modelProfileView': profileView,
    '../composables/useTheme': { isDark: vue.ref(false) },
  })
  component.render = () => null
  const renderer = vue.createRenderer({
    createElement: tag => ({ tag, children: [] }), createText: text => ({ text }), createComment: text => ({ text }),
    insert: (child, parent) => parent.children.push(child), remove() {}, setText() {}, setElementText() {}, patchProp() {}, parentNode: () => null, nextSibling: () => null,
  })
  const app = renderer.createApp(component)
  app.mount({ children: [] })
  return { state: app._instance.setupState, calls, legacyCalls, approvals, rejections, checks, updates, warnings, notices, history, reads, values, preparations, insertions,
    dispose() { app.unmount(); globalThis.window = previousWindow; globalThis.localStorage = previousStorage },
  }
}

test('same-id streamed progress reactively updates before the final reply without duplicating rows', async () => {
  const h = harness()
  try {
    await flush()
    const sending = h.state.send('合成提问')
    const observed = []
    const stop = vue.watch(() => h.state.messages.at(-1)?.progress?.[0]?.status, value => observed.push(value))
    h.calls[0].onEvent('session', { sessionId: 'a' })
    h.calls[0].onEvent('progress', { id: 'read', title: '读取来源', status: 'running' })
    await flush()
    assert.equal(h.state.busy, true)
    assert.equal(h.state.messages.at(-1).content, '')
    h.calls[0].onEvent('progress', { id: 'read', title: '读取来源', status: 'completed' })
    await flush()
    assert.deepEqual(observed, ['running', 'completed'])
    assert.equal(h.state.messages.at(-1).progress.length, 1)
    h.calls[0].resolve(result('a', '合成结论')); await sending
    assert.equal(h.state.messages.at(-1).content, '合成结论')
    assert.equal(h.state.busy, false)
    stop()
  } finally { h.dispose() }
})

test('tool, website and source details survive streamed updates, final delivery and session restoration', async () => {
  const h = harness()
  const tools = [{ name: 'web_fetch', title: '读取网页', arguments: { url: 'https://example.com/synthetic-guide' } }]
  const websites = [{ title: '合成网站指南', url: 'https://example.com/synthetic-guide', snippet: '合成网页摘要' }]
  const sources = [{ type: 'note', id: 701, title: '合成知识笔记', passageKey: 'note:701:0', seq: 0, snippet: '合成原文片段' }]
  try {
    await flush()
    const sending = h.state.send('核对合成资料')
    h.calls[0].onEvent('session', { sessionId: 'a' })
    h.calls[0].onEvent('progress', { id: 'retrieve', kind: 'phase', title: '检索相关知识', status: 'completed', sources })
    h.calls[0].onEvent('progress', { id: 'fetch', kind: 'tool', title: '读取网页', status: 'running', toolName: 'web_fetch', tools, websites })
    await flush()
    assert.deepEqual(h.state.messages.at(-1).progress[1].tools, tools)
    h.calls[0].onEvent('progress', { id: 'fetch', title: '读取网页', status: 'error', error: '合成页面返回 404' })
    await flush()
    const saved = JSON.parse(JSON.stringify(h.state.messages.at(-1).progress))
    assert.deepEqual(saved[1].websites, websites)
    assert.equal(saved[1].error, '合成页面返回 404')
    h.calls[0].resolve(result('a', '合成结论', { progress: saved })); await sending
    h.history.set('a', { messages: [{ role: 'user', content: '核对合成资料' }, { role: 'assistant', content: '合成结论', progress: saved }], pendingActions: [] })
    await h.state.switchSession('b')
    await h.state.switchSession('a')
    assert.deepEqual(h.state.messages.at(-1).progress, saved)
    assert.deepEqual(h.state.messages.at(-1).progress[0].sources, sources)
  } finally { h.dispose() }
})

test('switching sessions aborts delivery and late callbacks cannot replace the selected session or next run', async () => {
  const h = harness()
  try {
    await flush()
    const oldSend = h.state.send('旧任务')
    h.calls[0].onEvent('session', { sessionId: 'a' })
    await h.state.switchSession('b')
    assert.equal(h.calls[0].signal.aborted, true)
    const newSend = h.state.send('新任务')
    h.calls[0].onEvent('session', { sessionId: 'a' })
    h.calls[0].onEvent('progress', { id: 'stale', title: '不应显示', status: 'completed' })
    h.calls[0].resolve(result('a', '旧回答', { pendingActions: [action] })); await oldSend
    assert.equal(h.state.sessionId, 'b')
    assert.equal(h.state.busy, true)
    assert.equal(h.state.pending.length, 0)
    assert.deepEqual(h.state.messages.map(message => message.content), ['新任务', ''])
    h.calls[1].resolve(result('b', '新回答')); await newSend
    assert.equal(h.state.messages.at(-1).content, '新回答')
  } finally { h.dispose() }
})

test('a session history response started before send cannot erase the live turn', async () => {
  const history = deferred(), h = harness({ saved: 'a', session: () => history.promise })
  try {
    await flush()
    const sending = h.state.send('合成新问题')
    history.resolve({ messages: [{ role: 'assistant', content: '旧历史' }] }); await flush()
    assert.equal(h.state.messages[0].content, '合成新问题')
    h.calls[0].resolve(result('a', '新结论')); await sending
    assert.equal(h.state.messages.at(-1).content, '新结论')
  } finally { h.dispose() }
})

test('late histories after rapid session switching do not restore the earlier session', async () => {
  const first = deferred(), second = deferred(), h = harness({ session: id => id === 'a' ? first.promise : second.promise })
  try {
    await flush()
    const a = h.state.switchSession('a'), b = h.state.switchSession('b')
    second.resolve({ messages: [{ role: 'assistant', content: '乙历史' }], pendingActions: [] }); await b
    first.resolve({ messages: [{ role: 'assistant', content: '甲历史' }], pendingActions: [action] }); await a
    assert.equal(h.state.sessionId, 'b')
    assert.equal(h.state.messages[0].content, '乙历史')
    assert.equal(h.state.pending.length, 0)
  } finally { h.dispose() }
})

test('approval results stay with the action-owning reply while a newer reply exists', async () => {
  const approval = deferred(), h = harness({ saved: 'a', approve: () => approval.promise })
  try {
    await flush()
    h.state.messages = [{ role: 'assistant', content: '目录预览', progress: [{ id: 'edit', actionId: 91, title: '编辑笔记', status: 'waiting' }] }]
    h.state.pending = [action]
    const approving = h.state.resolveAction(action, true)
    h.state.messages.push({ role: 'assistant', content: '后续回答', progress: [] })
    approval.resolve({ ok: true, hint: '目录已更新' }); await approving
    assert.match(h.state.messages[0].content, /目录已更新/)
    assert.equal(h.state.messages[0].progress[0].status, 'completed')
    assert.equal(h.state.messages[1].content, '后续回答')
    assert.equal(h.state.pending.length, 0)
  } finally { h.dispose() }
})

test('approval finishing after switching does not modify another session but still broadcasts the actual note change', async () => {
  const approval = deferred(), h = harness({ saved: 'a', approve: () => approval.promise })
  try {
    await flush()
    h.state.messages = [{ role: 'assistant', content: '甲预览', progress: [] }]
    h.state.pending = [action]
    h.history.set('b', { messages: [{ role: 'assistant', content: '乙回答' }], pendingActions: [{ ...action, id: 92 }] })
    const approving = h.state.resolveAction(action, true)
    await h.state.switchSession('b')
    approval.resolve({ ok: true, hint: '合成完成', noteEdit: { noteId: 701, changed: true } }); await approving
    assert.equal(h.state.messages[0].content, '乙回答')
    assert.deepEqual(h.state.pending.map(item => item.id), [92])
    assert.deepEqual(h.updates, [{ noteId: 701, changed: true }])
  } finally { h.dispose() }
})

test('current note is automatically saved before context is sent and again before approval', async () => {
  const h = harness()
  try {
    await flush()
    h.state.noteContextFromEvent({ detail: { noteId: 701, title: '合成甲', context: '甲内容', dirty: true } })
    const sending = h.state.send('整理这篇笔记')
    await flush()
    assert.equal(h.preparations.length, 1)
    assert.equal(h.calls[0].request.noteId, 701)
    assert.equal(h.calls[0].request.noteDirty, false)
    assert.equal(h.calls[0].request.noteContext, '合成最新正文')
    h.state.noteContextFromEvent({ detail: { noteId: 702, title: '合成乙', context: '乙内容', dirty: false } })
    h.calls[0].resolve(result('a', '合成完成')); await sending
    h.state.noteContextFromEvent({ detail: { noteId: 701, dirty: true } })
    await h.state.resolveAction(action, true)
    assert.deepEqual(h.approvals, [91])
    assert.equal(h.preparations.length, 2)
    assert.equal(h.warnings.length, 0)
  } finally { h.dispose() }
})

test('a delayed save cannot send the previous note after switching editors or sessions', async () => {
  for (const switchSession of [false, true]) {
    const saved = deferred(), h = harness({ prepare: () => saved.promise })
    try {
      await flush()
      h.state.noteContextFromEvent({ detail: { noteId: 701, editorKey: 'draft-a', dirty: true } })
      const sending = h.state.send('整理当前笔记')
      assert.equal(h.preparations.length, 1)
      assert.equal(h.calls.length, 0)
      if (switchSession) h.state.newChat()
      else h.state.noteContextFromEvent({ detail: { noteId: 702, editorKey: 'draft-b' } })
      saved.resolve({ noteId: 701, editorKey: 'draft-a', dirty: false, context: '合成甲正文' }); await sending
      assert.equal(h.calls.length, 0)
      assert.equal(h.state.busy, false)
      if (!switchSession) assert.match(h.state.messages.at(-1).error, /切换/)
    } finally { h.dispose() }
  }
})

test('saving a new draft promotes its stable editor identity before sending the saved context', async () => {
  const saved = deferred(), h = harness({ prepare: () => saved.promise })
  try {
    await flush()
    h.state.noteContextFromEvent({ detail: { isNew: true, title: '合成新笔记', editorKey: 'new-draft', dirty: true } })
    const sending = h.state.send('补全这篇笔记')
    assert.equal(h.preparations[0].allowNew, true)
    assert.equal(h.preparations[0].editorKey, 'new-draft')
    h.state.noteContextFromEvent({ detail: { noteId: 703, isNew: false, editorKey: 'new-draft' } })
    saved.resolve({ noteId: 703, isNew: false, editorKey: 'new-draft', dirty: false, context: '合成新正文' }); await flush()
    assert.equal(h.calls[0].request.noteId, 703)
    assert.equal(h.calls[0].request.noteDirty, false)
    h.calls[0].resolve(result('a', '合成回答')); await sending
  } finally { h.dispose() }
})

test('ordinary chat and explicit other-note context never save or leak the active editor', async () => {
  const h = harness()
  try {
    await flush()
    let sending = h.state.send('普通合成聊天')
    assert.equal(h.preparations.length, 0)
    assert.equal(h.calls[0].request.noteId, undefined)
    h.calls[0].resolve(result('a', '普通回答')); await sending
    h.state.noteContextFromEvent({ detail: { noteId: 701, context: '不应泄漏的合成草稿', dirty: true } })
    sending = h.state.send('解读另一篇笔记', { noteId: 702, noteTitle: '合成乙' })
    assert.equal(h.preparations.length, 0)
    assert.equal(h.calls[1].request.noteId, 702)
    assert.equal(h.calls[1].request.noteContext, undefined)
    assert.equal(h.calls[1].request.noteDirty, false)
    h.calls[1].resolve(result('a', '合成乙回答')); await sending
  } finally { h.dispose() }
})

test('failed automatic save stops chat and note approval before any network write or recovery', async () => {
  const h = harness({ prepare: async () => { throw new Error('合成保存失败，草稿已保留') } })
  try {
    await flush()
    h.state.noteContextFromEvent({ detail: { noteId: 701, dirty: true } })
    await h.state.send('合成任务')
    assert.equal(h.calls.length, 0)
    assert.match(h.state.messages.at(-1).error, /保存失败/)
    h.state.pending = [action]
    await h.state.resolveAction(action, true)
    assert.deepEqual(h.approvals, [])
    assert.deepEqual(h.checks, [])
    assert.equal(h.state.pending.length, 1)
    assert.equal(h.state.actionLocked(action), false)
    assert.match(h.notices.at(-1).text, /草稿已保留/)
  } finally { h.dispose() }
})

test('append and update approvals save only their matching current note and never rewrite a stale preview', async () => {
  for (const toolName of ['append_to_note', 'update_note', 'edit_note']) {
    const h = harness({ approve: async () => ({ ok: false, error: '正文版本已过期' }) })
    try {
      await flush()
      h.state.noteContextFromEvent({ detail: { noteId: 701, dirty: true } })
      const pending = { id: 95, toolName, noteId: 701, preview: { noteId: 701, beforeHash: 'old-hash' } }
      h.state.pending = [pending]
      h.state.messages = [{ role: 'assistant', content: '合成预览', progress: [] }]
      await h.state.resolveAction(pending, true)
      assert.equal(h.preparations.length, 1)
      assert.deepEqual(h.approvals, [95])
      assert.equal(pending.preview.beforeHash, 'old-hash')
      assert.match(h.notices.at(-1).text, /重新生成修改预览/)
      assert.deepEqual(h.checks, [])
    } finally { h.dispose() }
  }
  const h = harness()
  try {
    await flush()
    h.state.noteContextFromEvent({ detail: { noteId: 702, dirty: true } })
    await h.state.resolveAction({ ...action, noteId: 701 }, true)
    assert.equal(h.preparations.length, 0)
    assert.deepEqual(h.approvals, [91])
  } finally { h.dispose() }
})

test('approving during save cannot double-submit or proceed after switching notes', async () => {
  const saved = deferred(), h = harness({ prepare: () => saved.promise })
  try {
    await flush()
    h.state.noteContextFromEvent({ detail: { noteId: 701, editorKey: 'draft-a', dirty: true } })
    const approving = h.state.resolveAction(action, true)
    await h.state.resolveAction(action, true)
    assert.equal(h.preparations.length, 1)
    assert.equal(h.state.actionLocked(action), true)
    h.state.noteContextFromEvent({ detail: { noteId: 702, editorKey: 'draft-b' } })
    saved.resolve({ noteId: 701, editorKey: 'draft-a', dirty: false }); await approving
    assert.deepEqual(h.approvals, [])
    assert.deepEqual(h.checks, [])
    assert.equal(h.state.actionLocked(action), false)
  } finally { h.dispose() }
})

test('model reply actions survive stream completion and session reload while legacy replies have none', async () => {
  const actions = [
    { id: 'practice', label: '出三道巩固题', kind: 'follow_up', prompt: '根据这条回答出三道巩固题。' },
    { id: 'save', label: '保存为专题笔记', kind: 'save_note' },
  ]
  const h = harness()
  try {
    await flush()
    const sending = h.state.send('合成课程问答')
    h.calls[0].resolve(result('a', '合成回答', { replyActions: [...actions, { id: 'bad', label: '运行', kind: 'execute' }] })); await sending
    assert.deepEqual(h.state.messages.at(-1).replyActions, actions)
    h.history.set('a', { messages: [{ role: 'assistant', content: '合成历史', replyActions: actions }, { role: 'assistant', content: '旧版历史' }] })
    await h.state.loadSession()
    assert.deepEqual(h.state.messages[0].replyActions, actions)
    assert.deepEqual(h.state.messages[1].replyActions, [])
  } finally { h.dispose() }
})

test('a follow-up action sends the original answer and question even when newer messages exist', async () => {
  const h = harness()
  try {
    await flush()
    const action = { id: 'drill', label: '生成练习题', kind: 'follow_up', prompt: '给这条回答补充练习题。' }
    h.state.messages = [{ role: 'user', content: '原始合成问题' }, { role: 'assistant', content: '原始合成回答', replyActions: [action] }, { role: 'user', content: '后来的问题' }]
    const holder = h.state.messages[1]
    const acting = h.state.runReplyAction(holder, action, 1)
    assert.match(h.calls[0].request.message, /^给这条回答补充练习题。/)
    assert.match(h.calls[0].request.message, /原始合成问题/)
    assert.match(h.calls[0].request.message, /原始合成回答/)
    assert.doesNotMatch(h.calls[0].request.message, /后来的问题/)
    h.calls[0].resolve(result('a', '合成练习题')); await acting
    assert.equal(holder.actionPending, null)
  } finally { h.dispose() }
})

test('cursor insertion waits for automatic save, locks repeat clicks and honors the answer note target', async () => {
  const saved = deferred(), h = harness({ prepare: () => saved.promise })
  try {
    await flush()
    const action = { id: 'cursor', label: '在当前光标补上说明', kind: 'insert_at_cursor', noteId: 701 }
    h.state.noteContextFromEvent({ detail: { noteId: 701, editorKey: 'draft-a', dirty: true } })
    h.state.messages = [{ role: 'user', content: '合成问题' }, { role: 'assistant', content: '合成补充', replyActions: [action], noteId: 701 }]
    const holder = h.state.messages[1]
    const acting = h.state.runReplyAction(holder, action, 1)
    await h.state.runReplyAction(holder, action, 1)
    assert.equal(h.preparations.length, 1)
    assert.equal(h.insertions.length, 0)
    saved.resolve({ noteId: 701, dirty: false }); await acting
    assert.deepEqual(h.insertions.map(({ signal, ...request }) => request), [{ noteId: 701, editorKey: 'draft-a', answer: '合成补充', question: '合成问题' }])
    h.state.noteContextFromEvent({ detail: { noteId: 702, editorKey: 'draft-b' } })
    assert.match(h.state.replyActionUnavailable(holder, action), /切回/)
    await h.state.runReplyAction(holder, action, 1)
    assert.equal(h.insertions.length, 1)
  } finally { h.dispose() }
})

test('merge results identify their request and cannot complete a different reply or note', async () => {
  const h = harness(), requests = []
  try {
    await flush()
    window.addEventListener(noteMerge.AGENT_NOTE_MERGE_EVENT, event => requests.push(event.detail))
    const action = { id: 'merge', label: '融入相应章节', kind: 'merge_note', noteId: 701 }
    h.state.noteContextFromEvent({ detail: { noteId: 701, editorKey: 'draft-a' } })
    h.state.messages = [{ role: 'assistant', content: '合成补充', replyActions: [action], noteId: 701 }]
    const holder = h.state.messages[0]
    await h.state.runReplyAction(holder, action, 0)
    assert.equal(requests.length, 1)
    assert.equal(requests[0].editorKey, 'draft-a')
    assert.equal(holder.mergePending, true)
    h.state.mergeResultFromEvent({ detail: { requestId: 'another-request', noteId: 701, ok: true } })
    assert.equal(holder.mergePending, true)
    h.state.mergeResultFromEvent({ detail: { ...requests[0], ok: true } })
    assert.equal(holder.mergePending, false)
    assert.equal(holder.merged, true)
  } finally { h.dispose() }
})

test('changing sessions aborts a pending insertion and an ongoing merge request', async () => {
  for (const kind of ['insert_at_cursor', 'merge_note']) {
    const h = harness({ insert: request => new Promise((resolve, reject) => request.signal.addEventListener('abort', () => reject(new Error('操作已取消')), { once: true })) })
    try {
      await flush()
      let merge
      window.addEventListener(noteMerge.AGENT_NOTE_MERGE_EVENT, event => { merge = event.detail })
      const action = { id: 'edit', label: '应用合成补充', kind, noteId: 701 }
      h.state.noteContextFromEvent({ detail: { noteId: 701, editorKey: 'draft-a' } })
      h.state.messages = [{ role: 'assistant', content: '合成补充', replyActions: [action] }]
      const acting = h.state.runReplyAction(h.state.messages[0], action, 0)
      await flush()
      const signal = kind === 'merge_note' ? merge.signal : h.insertions[0].signal
      assert.equal(signal.aborted, false)
      h.state.newChat()
      assert.equal(signal.aborted, true)
      await acting
      assert.equal(h.state.messages.length, 0)
      assert.equal(h.state.mergeTarget, null)
    } finally { h.dispose() }
  }
})

test('IME Enter and Shift+Enter retain the composer, while plain Enter sends once', async () => {
  const h = harness()
  try {
    await flush()
    h.state.input = '合成输入法文字'
    let prevented = 0
    for (const detail of [{ isComposing: true }, { keyCode: 229 }, { shiftKey: true }]) {
      h.state.onComposerKeydown({ key: 'Enter', preventDefault: () => prevented++, ...detail })
    }
    assert.equal(h.calls.length, 0)
    assert.equal(prevented, 0)
    h.state.onComposerKeydown({ key: 'Enter', preventDefault: () => prevented++ })
    assert.equal(h.calls.length, 1)
    assert.equal(prevented, 1)
    h.calls[0].resolve(result('a', '合成回答')); await flush()
  } finally { h.dispose() }
})

test('disconnect keeps completed steps, marks unfinished work interrupted and never retries the chat', async () => {
  const h = harness()
  try {
    await flush()
    const sending = h.state.send('合成断流任务')
    h.calls[0].onEvent('progress', { id: 'read', title: '读取', status: 'completed' })
    h.calls[0].onEvent('progress', { id: 'answer', title: '整理', status: 'running' })
    h.calls[0].reject(new Error('合成连接已中断')); await sending
    assert.equal(h.state.messages.at(-1).error, '合成连接已中断')
    assert.deepEqual(h.state.messages.at(-1).progress.map(step => step.status), ['completed', 'interrupted'])
    assert.equal(h.legacyCalls.length, 0)
    assert.equal(h.state.busy, false)
  } finally { h.dispose() }
})

test('a missing streaming endpoint falls back once while other failures never resend', async () => {
  const h = harness()
  try {
    await flush()
    const sending = h.state.send('兼容请求')
    h.calls[0].reject(Object.assign(new Error('missing'), { status: 404 })); await sending
    assert.equal(h.legacyCalls.length, 1)
    assert.equal(h.state.messages.at(-1).content, '旧版合成回答')
    assert.equal(h.state.messages.at(-1).progress.some(step => step.id === 'compatibility'), false)
  } finally { h.dispose() }
})

test('failed approval while another turn streams must not replace that live turn with stored history', async () => {
  const h = harness({ saved: 'a', approve: async () => { throw new Error('合成网络错误') } })
  try {
    await flush()
    h.state.messages = [{ role: 'assistant', content: '之前的预览', progress: [] }]
    h.state.pending = [action]
    h.history.set('a', { messages: [{ role: 'assistant', content: '旧历史' }], pendingActions: [action] })
    const sending = h.state.send('本轮实时任务')
    await h.state.resolveAction(action, true); await flush()
    assert.ok(h.state.messages.some(message => message.content === '本轮实时任务'))
    h.calls[0].resolve(result('a', '本轮最终结果')); await sending
    assert.equal(h.state.messages.at(-1).content, '本轮最终结果')
  } finally { h.dispose() }
})

test('an approval remains locked until its response arrives and double clicks never repeat the write', async () => {
  const approval = deferred(), h = harness({ saved: 'a', approve: () => approval.promise })
  try {
    await flush()
    h.state.messages = [{ role: 'assistant', content: '合成操作预览', progress: [] }]
    h.state.pending = [action]
    const approving = h.state.resolveAction(action, true)
    assert.equal(h.state.actionLocked(action), true)
    await h.state.resolveAction(action, true)
    await h.state.resolveAction(action, false)
    assert.deepEqual(h.approvals, [91])
    assert.deepEqual(h.rejections, [])
    approval.resolve({ ok: true, hint: '合成执行完成' }); await approving
    assert.deepEqual(h.checks, [])
    assert.equal(h.state.pending.length, 0)
  } finally { h.dispose() }
})

test('approval timeout queries status once, keeps an executing card locked and later recovers completion without replay', async () => {
  let status = 'executing'
  const h = harness({ saved: 'a', approve: async () => { throw Object.assign(new Error('合成超时'), { code: 'ECONNABORTED' }) },
    actionState: id => ({ id, status, summary: '下载合成资料' }) })
  try {
    await flush()
    h.state.messages = [{ role: 'assistant', content: '合成预览', progress: [{ id: 'write', actionId: 91, status: 'waiting' }] }]
    h.state.pending = [action]
    const originalReads = h.reads.length
    await h.state.resolveAction(action, true)
    assert.deepEqual(h.approvals, [91])
    assert.deepEqual(h.checks, [91])
    assert.equal(h.reads.length, originalReads)
    assert.equal(h.state.pending[0].status, 'executing')
    assert.equal(h.state.messages[0].progress[0].status, 'running')
    assert.equal(h.state.actionLocked(h.state.pending[0]), true)
    assert.equal(h.state.messages[0].content, '合成预览')
    assert.equal(h.notices.some(notice => notice.type === 'error' || notice.type === 'success'), false)
    await h.state.resolveAction(h.state.pending[0], true)
    await h.state.resolveAction(h.state.pending[0], false)
    assert.deepEqual(h.approvals, [91])
    assert.deepEqual(h.rejections, [])
    status = 'approved'
    await h.state.checkActionState(h.state.pending[0])
    assert.deepEqual(h.checks, [91, 91])
    assert.deepEqual(h.approvals, [91])
    assert.equal(h.state.pending.length, 0)
    assert.ok(h.state.messages[0].progress.every(step => step.status === 'completed'))
    assert.match(h.state.messages[0].content, /已执行：下载合成资料/)
    assert.equal(h.notices.filter(notice => notice.type === 'success').length, 1)
    assert.deepEqual(h.updates, [], 'status recovery must not invent a note-edit payload')
  } finally { h.dispose() }
})

test('unreachable status stays uncertain, prevents a repeated write and unlocks only after a read confirms pending', async () => {
  let readable = false
  const h = harness({ saved: 'a', approve: async () => { throw new Error('合成网络断开') },
    actionState: id => { if (!readable) throw new Error('合成状态连接断开'); return { id, status: 'pending' } } })
  try {
    await flush()
    h.state.messages = [{ role: 'assistant', content: '合成预览', progress: [{ id: 'write', actionId: 91, status: 'waiting' }] }]
    h.state.pending = [action]
    await h.state.resolveAction(action, true)
    assert.equal(h.state.uncertainActions.has(91), true)
    assert.equal(h.state.actionLocked(h.state.pending[0]), true)
    assert.equal(h.state.messages[0].content, '合成预览')
    assert.equal(h.notices.some(notice => notice.type === 'success' || notice.type === 'error'), false)
    await h.state.resolveAction(action, true)
    assert.deepEqual(h.approvals, [91])
    readable = true
    await h.state.checkActionState(h.state.pending[0])
    assert.equal(h.state.uncertainActions.has(91), false)
    assert.equal(h.state.actionLocked(h.state.pending[0]), false)
    assert.equal(h.state.pending[0].status, 'pending')
    assert.equal(h.state.messages[0].progress[0].status, 'waiting')
    assert.deepEqual(h.approvals, [91], 'confirming pending only unlocks the button; it never resubmits')
    assert.deepEqual(h.checks, [91, 91])
  } finally { h.dispose() }
})

test('status after timeout can confirm success or failure but unknown statuses never claim a terminal result', async () => {
  for (const status of ['approved', 'failed', 'rejected', 'unexpected']) {
    const h = harness({ saved: 'a', approve: async () => { throw new Error('合成超时') }, actionState: id => ({ id, status }) })
    try {
      await flush()
      h.state.messages = [{ role: 'assistant', content: '合成预览', progress: [{ id: 'write', actionId: 91, status: 'waiting' }] }]
      h.state.pending = [action]
      await h.state.resolveAction(action, true)
      assert.deepEqual(h.approvals, [91])
      assert.deepEqual(h.checks, [91])
      if (status === 'unexpected') {
        assert.equal(h.state.pending.length, 1)
        assert.equal(h.state.uncertainActions.has(91), true)
        assert.equal(h.state.messages[0].content, '合成预览')
        assert.equal(h.notices.some(notice => notice.type === 'success' || notice.type === 'error'), false)
      } else {
        assert.equal(h.state.pending.length, 0)
        assert.equal(h.state.messages[0].progress[0].status, status === 'failed' ? 'error' : 'completed')
        assert.match(h.state.messages[0].content, status === 'approved' ? /已确认执行/ : status === 'failed' ? /执行失败/ : /已取消/)
      }
    } finally { h.dispose() }
  }
})

test('a restored executing action is locked immediately and concurrent status clicks use one GET', async () => {
  const check = deferred(), running = { ...action, status: 'executing' }
  const h = harness({ saved: 'a', session: () => ({ messages: [{ role: 'assistant', content: '合成任务处理中' }], pendingActions: [running] }), actionState: () => check.promise })
  try {
    await flush()
    assert.equal(h.state.pending[0].status, 'executing')
    assert.equal(h.state.actionLocked(h.state.pending[0]), true)
    await h.state.resolveAction(h.state.pending[0], true)
    await h.state.resolveAction(h.state.pending[0], false)
    const checking = h.state.checkActionState(h.state.pending[0])
    await h.state.checkActionState(h.state.pending[0])
    assert.deepEqual(h.approvals, [])
    assert.deepEqual(h.rejections, [])
    assert.deepEqual(h.checks, [91])
    check.resolve({ id: 91, status: 'executing' }); await checking
    assert.equal(h.state.pending[0].status, 'executing')
  } finally { h.dispose() }
})

test('a late recovery GET never changes the newly selected session', async () => {
  const check = deferred(), h = harness({ saved: 'a', approve: async () => { throw new Error('合成超时') }, actionState: () => check.promise })
  try {
    await flush()
    h.state.messages = [{ role: 'assistant', content: '甲预览', progress: [{ id: 'write', actionId: 91, status: 'waiting' }] }]
    h.state.pending = [action]
    h.history.set('b', { messages: [{ role: 'assistant', content: '乙回答' }], pendingActions: [{ ...action, id: 92 }] })
    const approving = h.state.resolveAction(action, true)
    await flush()
    assert.deepEqual(h.checks, [91])
    await h.state.switchSession('b')
    check.resolve({ id: 91, status: 'approved' }); await approving
    assert.equal(h.state.messages[0].content, '乙回答')
    assert.deepEqual(h.state.pending.map(item => item.id), [92])
    assert.deepEqual(h.updates, [])
    assert.equal(h.notices.some(notice => notice.type === 'success'), false)
  } finally { h.dispose() }
})

test('recovering an older action while a new turn streams only updates the owning reply', async () => {
  const check = deferred(), h = harness({ saved: 'a', approve: async () => { throw new Error('合成超时') }, actionState: () => check.promise })
  try {
    await flush()
    h.state.messages = [{ role: 'assistant', content: '先前预览', progress: [{ id: 'write', actionId: 91, status: 'waiting' }] }]
    h.state.pending = [action]
    const originalReads = h.reads.length
    const approving = h.state.resolveAction(action, true)
    await flush()
    const sending = h.state.send('合成新任务')
    h.calls[0].onEvent('progress', { id: 'read', status: 'running', title: '读取合成来源' })
    check.resolve({ id: 91, status: 'approved' }); await approving
    assert.match(h.state.messages[0].content, /已确认执行/)
    assert.equal(h.state.messages.at(-1).content, '')
    assert.equal(h.state.messages.at(-1).progress[0].status, 'running')
    assert.equal(h.state.busy, true)
    assert.equal(h.reads.length, originalReads)
    h.calls[0].resolve(result('a', '合成新结论')); await sending
    assert.equal(h.state.messages.at(-1).content, '合成新结论')
    assert.match(h.state.messages[0].content, /已确认执行/)
    assert.deepEqual(h.approvals, [91])
  } finally { h.dispose() }
})

test('an older history response cannot resurrect an approved card or roll back a recovered executing state', async () => {
  for (const recovering of [false, true]) {
    const oldHistory = deferred()
    const h = harness({ session: () => oldHistory.promise, actionState: id => ({ id, status: 'executing' }) })
    try {
      await flush()
      h.state.sessionId = 'a'
      h.state.messages = [{ role: 'assistant', content: '合成预览', progress: [{ id: 'write', actionId: 91, status: 'waiting' }] }]
      h.state.pending = [action]
      const reading = h.state.loadSession()
      if (recovering) await h.state.checkActionState(action)
      else await h.state.resolveAction(action, true)
      oldHistory.resolve({ messages: [{ role: 'assistant', content: '过期预览' }], pendingActions: [action] }); await reading
      assert.notEqual(h.state.messages[0].content, '过期预览')
      if (recovering) {
        assert.equal(h.state.pending[0].status, 'executing')
        assert.equal(h.state.actionLocked(h.state.pending[0]), true)
      } else {
        assert.equal(h.state.pending.length, 0)
        assert.match(h.state.messages[0].content, /已确认执行/)
      }
    } finally { h.dispose() }
  }
})

test('a history read started during approval cannot replace its owning reply before or after completion', async () => {
  for (const historyFirst of [false, true]) {
    const oldHistory = deferred(), approval = deferred()
    const h = harness({ session: () => oldHistory.promise, approve: () => approval.promise })
    try {
      await flush()
      h.state.sessionId = 'a'
      h.state.messages = [{ role: 'assistant', content: '合成预览', progress: [] }]
      h.state.pending = [action]
      const approving = h.state.resolveAction(action, true)
      const reading = h.state.loadSession()
      if (!historyFirst) { approval.resolve({ ok: true, hint: '合成已完成' }); await approving }
      oldHistory.resolve({ messages: [{ role: 'assistant', content: '过期快照' }], pendingActions: [action] }); await reading
      if (historyFirst) { approval.resolve({ ok: true, hint: '合成已完成' }); await approving }
      assert.match(h.state.messages[0].content, /合成已完成/)
      assert.equal(h.state.pending.length, 0)
      assert.deepEqual(h.approvals, [91])
    } finally { h.dispose() }
  }
})
