import test from 'node:test'
import assert from 'node:assert/strict'
import { createChatRunGate, interruptProgress, restoredProgress, upsertProgress } from './agentProgress.js'

test('same-id completion replaces a running row in place while preserving parent/tool metadata and earlier input', () => {
  const initial = [
    { id: 'outer', kind: 'tool', title: '运行示例工具', status: 'running' },
    { id: 'inner', kind: 'tool', parentId: 'outer', toolName: 'get_note', title: '读取示例笔记', status: 'running', detail: '示例说明' },
  ]
  const result = upsertProgress(initial, { id: 'inner', title: '已读取示例笔记', status: 'completed', elapsedMs: 245 })
  assert.equal(result.length, 2)
  assert.deepEqual(result[1], { ...initial[1], title: '已读取示例笔记', status: 'completed', elapsedMs: 245 })
  assert.equal(initial[1].status, 'running')
  assert.equal(result[0], initial[0])
  assert.notEqual(result, initial)
})

test('replaying persisted updates deduplicates ids and structured traces take precedence over legacy strings', () => {
  const result = restoredProgress([
    { id: 'one', title: '读取示例资料', kind: 'tool', status: 'running' },
    { id: 'two', title: '等待确认示例编辑', kind: 'tool', status: 'waiting' },
    { id: 'one', title: '已读取示例资料', status: 'completed' },
  ], ['不应混入旧记录'])
  assert.equal(result.length, 2)
  assert.equal(result[0].title, '已读取示例资料')
  assert.equal(result[0].status, 'completed')
  assert.equal(result[1].status, 'waiting')
})

test('plan rows preserve pending, active and adjusted states independently from tool results', () => {
  const pending = upsertProgress([], { id: 'plan-1', kind: 'plan', title: '检查合成资料', status: 'pending' })
  assert.equal(pending[0].status, 'pending')
  const running = upsertProgress(pending, { id: 'plan-1', title: '检查合成资料', status: 'running' })
  const withTool = upsertProgress(running, { id: 'tool-1', kind: 'tool', title: '读取合成资料', status: 'completed' })
  assert.equal(withTool[0].status, 'running')
  const restored = restoredProgress([
    ...withTool,
    { id: 'plan-2', kind: 'plan', title: '整理合成结论', status: 'pending' },
    { id: 'plan-1', title: '检查合成资料', status: 'cancelled', detail: '计划已调整，此步骤不再需要' },
  ])
  assert.deepEqual(restored.map(row => [row.id, row.status]), [['plan-1', 'cancelled'], ['tool-1', 'completed'], ['plan-2', 'pending']])
  assert.equal(restored[0].kind, 'plan')
  const disconnected = interruptProgress(restored)
  assert.equal(disconnected[0].status, 'cancelled')
  assert.equal(disconnected[2].status, 'pending')
})

test('an unknown plan status never becomes a fabricated completion', () => {
  const initial = upsertProgress([], { id: 'plan-1', kind: 'plan', title: '核对合成内容', status: 'unknown' })
  assert.equal(initial[0].status, 'pending')
  const update = upsertProgress(initial, { id: 'plan-1', title: '核对合成内容', status: 'unknown' })
  assert.equal(update[0].kind, 'plan')
  assert.equal(update[0].status, 'pending')
})

test('invalid progress payloads leave the current trace intact and fresh traces accept valid events', () => {
  const initial = [{ id: 'first', title: '示例步骤', status: 'completed' }]
  for (const event of [null, undefined, {}, { id: '', title: '无效事件' }, { id: 1, title: '无效事件' }, { id: 'missing-title' }]) {
    assert.equal(upsertProgress(initial, event), initial)
  }
  assert.deepEqual(upsertProgress(undefined, { id: 'fresh', title: '示例步骤', status: 'running' }), [{ id: 'fresh', title: '示例步骤', status: 'running' }])
  assert.deepEqual(upsertProgress(undefined, null), [])
})

test('bounded traces retain the most recent 180 steps and updates do not move existing rows', () => {
  let entries = []
  for (let i = 0; i < 200; i++) entries = upsertProgress(entries, { id: `step-${i}`, title: `示例步骤 ${i}`, status: 'completed' })
  assert.equal(entries.length, 180)
  assert.equal(entries[0].id, 'step-20')
  assert.equal(entries.at(-1).id, 'step-199')
  entries = upsertProgress(entries, { id: 'step-20', title: '更新示例步骤', status: 'waiting' })
  assert.equal(entries.length, 180)
  assert.equal(entries[0].id, 'step-20')
  assert.equal(entries[0].status, 'waiting')
})

test('legacy history preserves waiting-for-confirmation and failure states instead of claiming completion', () => {
  const result = restoredProgress([], ['已读取示例笔记', '更新示例笔记待确认', '创建笔记尚未执行', '工具执行失败', '处理未完成'])
  assert.deepEqual(result.map(entry => entry.status), ['completed', 'waiting', 'waiting', 'error', 'error'])
  assert.equal(new Set(result.map(entry => entry.id)).size, 5)
  assert.deepEqual(restoredProgress(undefined, []), [])
})

test('disconnect marks only running steps interrupted and preserves staged confirmation and failure details', () => {
  const initial = [
    { id: 'run', title: '生成示例结果', status: 'running', detail: '处理中' },
    { id: 'wait', title: '编辑示例笔记', status: 'waiting', detail: '需要确认' },
    { id: 'error', title: '读取示例资料', status: 'error', detail: '资料不存在' },
    { id: 'done', title: '检索示例笔记', status: 'completed' },
  ]
  const result = interruptProgress(initial)
  assert.equal(result[0].status, 'interrupted')
  assert.match(result[0].detail, /连接已断开.*重新打开会话/)
  assert.equal(initial[0].status, 'running')
  for (let i = 1; i < result.length; i++) assert.equal(result[i], initial[i])
  assert.deepEqual(interruptProgress(undefined), [])
})

test('starting a new request aborts the old run and finishing the old run cannot clear the new owner', () => {
  const gate = createChatRunGate()
  const previous = gate.begin()
  assert.equal(gate.isCurrent(previous), true)
  const current = gate.begin()
  assert.equal(previous.controller.signal.aborted, true)
  assert.equal(gate.isCurrent(previous), false)
  assert.equal(gate.isCurrent(current), true)
  gate.finish(previous)
  assert.equal(gate.isCurrent(current), true)
  assert.equal(current.controller.signal.aborted, false)
  gate.finish(current)
  assert.equal(gate.isCurrent(current), false)
})

test('session navigation invalidates in-flight callbacks and a later request stays independent', () => {
  const gate = createChatRunGate()
  const oldSession = gate.begin()
  let aborts = 0
  oldSession.controller.signal.addEventListener('abort', () => aborts++)
  gate.invalidate()
  gate.invalidate()
  assert.equal(aborts, 1)
  assert.equal(gate.isCurrent(oldSession), false)
  const newSession = gate.begin()
  gate.finish(oldSession)
  assert.equal(gate.isCurrent(newSession), true)
  newSession.controller.abort()
  assert.equal(gate.isCurrent(newSession), false)
  gate.finish(newSession)
})
