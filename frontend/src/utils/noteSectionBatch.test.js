import test from 'node:test'
import assert from 'node:assert/strict'
import { mergeSectionTargets, generateSectionBatch, composeSectionBatch } from './noteSectionBatch.js'
import { EditorState } from '@codemirror/state'
import { history, undo } from '@codemirror/commands'
import { replaceSourceContent, insertSourceContent } from './sourceAgentEdit.js'

const source = '## 第一节\n\n原文 A\n\n## 第二节\n\n原文 B\n\n## 保留节\n\n原样保留\n'

test('multiple section replacements use one source snapshot without shifting later targets or duplicating an append', () => {
  const targets = mergeSectionTargets(source, { action: 'merge', targets: [{ action: 'merge', index: 1 }, { action: 'merge', index: 2 }, { action: 'append', heading: '新增节' }] })
  const patches = targets.map((target, index) => ({ ...target, after: ['原文 A\n\n新增公式：$$a=b$$', '原文 B\n\n补充内容', '新增独立内容'][index] }))
  const result = composeSectionBatch(source, patches)
  assert.equal((result.match(/原文 A/g) || []).length, 1)
  assert.equal((result.match(/原文 B/g) || []).length, 1)
  assert.ok(result.includes('## 保留节\n\n原样保留\n'))
  assert.ok(result.includes('## 新增节\n\n新增独立内容'))
  assert.throws(() => composeSectionBatch(source.replace('原文 A', '新草稿'), patches), /发生了变化/)
  assert.throws(() => mergeSectionTargets(source, { targets: [{ action: 'merge', index: 1 }, { action: 'merge', index: 1 }] }), /重复/)
})

test('new sibling sections follow the selected original section in planned order while that section is rewritten', () => {
  const targets = mergeSectionTargets(source, { targets: [
    { action: 'merge', index: 1 },
    { action: 'append', index: 1, heading: '插入甲' },
    { action: 'append', index: 1, heading: '插入乙' },
  ] })
  const patches = targets.map((target, index) => ({ ...target, after: ['更长的第一节新正文', '甲正文', '乙正文'][index] }))
  const result = composeSectionBatch(source, patches)
  assert.ok(result.indexOf('更长的第一节新正文') < result.indexOf('## 插入甲'))
  assert.ok(result.indexOf('## 插入甲') < result.indexOf('## 插入乙'))
  assert.ok(result.indexOf('## 插入乙') < result.indexOf('## 第二节'))
  assert.ok(result.includes('原文 B'))
})

test('section generation has at most three concurrent jobs and preserves planned order', async () => {
  let running = 0, maximum = 0
  const progress = []
  const jobs = Array.from({ length: 7 }, (_, index) => ({ heading: `第${index}节`, index }))
  const result = await generateSectionBatch(jobs, async job => {
    maximum = Math.max(maximum, ++running)
    await new Promise(resolve => setTimeout(resolve, (7 - job.index) * 2))
    running--
    return `正文${job.index}`
  }, { onProgress: (done, total) => progress.push([done, total]) })
  assert.equal(maximum, 3)
  assert.deepEqual(result, jobs.map(job => `正文${job.index}`))
  assert.deepEqual(progress.at(-1), [7, 7])
})

test('a failed generation cancels its siblings and exposes no applicable partial batch', async () => {
  let aborted = 0, started = 0
  await assert.rejects(generateSectionBatch(Array.from({ length: 6 }, (_, index) => ({ heading: String(index), index })), async (job, index, signal) => {
    started++
    if (index === 1) throw new Error('分段达到输出上限')
    await new Promise(resolve => { signal.addEventListener('abort', () => { aborted++; resolve() }, { once: true }); if (signal.aborted) resolve() })
    return '完成内容'
  }), /输出上限/)
  assert.ok(started <= 3)
  assert.ok(aborted >= 1)
})

test('a generated source change is a separate undo step and preserves the earlier manual edit', () => {
  const view = { state: EditorState.create({ doc: '原文', selection: { anchor: 2 }, extensions: [history()] }), dispatch(transaction) { this.state = this.state.update(transaction).state } }
  view.dispatch({ changes: { from: 2, insert: '手写' } })
  replaceSourceContent(view, '原文手写\n\nAI 一段\n\nAI 二段')
  assert.equal(undo({ state: view.state, dispatch: value => { view.state = value.state } }), true)
  assert.equal(view.state.doc.toString(), '原文手写')
  insertSourceContent(view, '光标插入')
  assert.ok(view.state.doc.toString().includes('光标插入'))
  assert.equal(undo({ state: view.state, dispatch: value => { view.state = value.state } }), true)
  assert.equal(view.state.doc.toString(), '原文手写')
})
