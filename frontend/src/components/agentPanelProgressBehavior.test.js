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
  const calls = [], legacyCalls = [], approvals = [], rejections = [], checks = [], updates = [], warnings = [], notices = []
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
    '../utils/agentNoteEdit': noteEdit, '../utils/retrievalDisplay': retrievalDisplay,
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
  return { state: app._instance.setupState, calls, legacyCalls, approvals, rejections, checks, updates, warnings, notices, history, reads, values,
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

test('active note context is captured at send and dirty-note approval is refused before mutation', async () => {
  const h = harness()
  try {
    await flush()
    h.state.noteContextFromEvent({ detail: { noteId: 701, title: '合成甲', context: '甲内容', dirty: true } })
    const sending = h.state.send('整理这篇笔记')
    h.state.noteContextFromEvent({ detail: { noteId: 702, title: '合成乙', context: '乙内容', dirty: false } })
    assert.equal(h.calls[0].request.noteId, 701)
    assert.equal(h.calls[0].request.noteDirty, true)
    assert.equal(h.calls[0].request.noteContext, '甲内容')
    h.calls[0].resolve(result('a', '合成完成')); await sending
    h.state.noteContextFromEvent({ detail: { noteId: 701, dirty: true } })
    await h.state.resolveAction(action, true)
    assert.deepEqual(h.approvals, [])
    assert.equal(h.warnings.length, 1)
  } finally { h.dispose() }
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
