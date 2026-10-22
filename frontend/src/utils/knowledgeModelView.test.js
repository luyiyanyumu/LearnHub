import test from 'node:test'
import assert from 'node:assert/strict'
import { createKnowledgeModelRefresh, formatKnowledgeDrop, formatKnowledgeScore,
  knowledgeModelAnswerView, knowledgeModelJobs, knowledgeModelScores } from './knowledgeModelView.js'

test('submission is distinct from training and confirmed jobs are merged once', () => {
  const jobs = knowledgeModelJobs({ outbox: [
    { id: 1, title: '合成课程 A', state: 'submitted', remoteJobId: 'a' },
    { id: 2, title: '合成课程 B', state: 'submitted', remoteJobId: 'missing' },
  ], service: { jobs: [{ id: 'a', status: 'running', title: '合成课程 A' },
    { id: 'b', status: 'unvalidated', title: '合成课程 C' }] } }, { phase: 'candidate_evaluation' })
  assert.equal(jobs.length, 3)
  assert.equal(jobs[0].view.key, 'evaluating')
  assert.equal(jobs[0].outboxId, 1)
  assert.equal(jobs[1].view.key, 'submitted')
  assert.match(jobs[2].view.label, /未覆盖.*未发布/)
})

test('absence of old evaluation cannot become zero forgetting or a perfect score', () => {
  const absent = knowledgeModelScores({ baseline: { old: { accuracy: null } }, candidate: { old: { accuracy: null } } })
  assert.equal(absent.forgetting, null)
  assert.equal(formatKnowledgeScore(absent.oldScore), '未覆盖')
  assert.equal(formatKnowledgeDrop(absent.forgetting), '暂无旧题')
  const scores = knowledgeModelScores({ baseline: { old: { accuracy: 0.9 } }, candidate: { old: { accuracy: 0.7 } } })
  assert.equal(formatKnowledgeDrop(scores.forgetting), '+20.0 个百分点')
  assert.equal(formatKnowledgeDrop(-0.1), '-10.0 个百分点')
})

test('timezone-aware worker timestamps take precedence while local queue fallback is unchanged', () => {
  const fallback = '2026-10-17T09:22:11'
  const jobs = knowledgeModelJobs({ outbox: [
    { id: 1, title: '合成课程时区验证', state: 'submitted', remoteJobId: 'a', createdAt: fallback },
    { id: 2, title: '合成课程待提交', state: 'pending', createdAt: fallback },
    { id: 3, title: '合成课程旧任务', state: 'submitted', remoteJobId: 'b', createdAt: fallback },
  ], service: { jobs: [
    { id: 'a', status: 'published', created_at: '2026-10-17T09:22:11+00:00' },
    { id: 'b', status: 'queued' },
  ] } })
  const timestamp = jobs.find(job => job.outboxId === 1).createdAt
  assert.equal(timestamp, '2026-10-17T09:22:11+00:00')
  const localTime = new Intl.DateTimeFormat('en-GB', { timeZone: 'Asia/Shanghai',
    hour: '2-digit', minute: '2-digit', second: '2-digit', hourCycle: 'h23' }).format(new Date(timestamp))
  assert.equal(localTime, '17:22:11')
  assert.equal(jobs.find(job => job.outboxId === 2).createdAt, fallback)
  assert.equal(jobs.find(job => job.outboxId === 3).createdAt, fallback)
})

test('refusal metrics preserve new and old accuracy while reporting unknown recall and known false refusals separately', () => {
  const scores = knowledgeModelScores({ abstention_policy: 'v1',
    baseline: { old: { accuracy: 0.9 } }, candidate: {
      new: { accuracy: 0.8 }, old: { accuracy: 0.7 },
      unknown: { total: 8, correct: 6, accuracy: 0.75 },
      known_abstention: { total: 10, count: 2, rate: 0.2 },
    } })
  assert.equal(scores.newScore, 0.8)
  assert.equal(scores.oldScore, 0.7)
  assert.equal(formatKnowledgeDrop(scores.forgetting), '+20.0 个百分点')
  assert.deepEqual(scores.unknownRefusal, { covered: true, rate: 0.75, count: 6, total: 8 })
  assert.deepEqual(scores.knownRefusal, { covered: true, rate: 0.2, count: 2, total: 10 })
  assert.equal(formatKnowledgeScore(scores.knownRefusal.rate), '20.0%')
})

test('legacy and empty refusal evaluations stay uncovered instead of claiming zero mistakes', () => {
  const legacy = knowledgeModelScores({ candidate: { new: { accuracy: 1 }, old: { accuracy: 1 } } })
  assert.equal(legacy.unknownRefusal.covered, false)
  assert.equal(legacy.knownRefusal.covered, false)
  assert.equal(formatKnowledgeScore(legacy.knownRefusal.rate), '未覆盖')
  const empty = knowledgeModelScores({ abstention_policy: 'v1', candidate: {
    unknown: { total: 0, correct: 0, accuracy: null },
    known_abstention: { total: 0, count: 0, rate: null },
  } })
  assert.equal(empty.unknownRefusal.rate, null)
  assert.equal(empty.knownRefusal.rate, null)
  const measuredZero = knowledgeModelScores({ abstention_policy: 'v1', candidate: {
    known_abstention: { total: 5, count: 0, rate: 0 },
  } })
  assert.equal(measuredZero.knownRefusal.covered, true)
  assert.equal(formatKnowledgeScore(measuredZero.knownRefusal.rate), '0.0%')
})

test('answer labels use explicit refusal and validation metadata without upgrading legacy responses', () => {
  const legacy = knowledgeModelAnswerView({ answer: '不知道' })
  assert.equal(legacy.label, '参数回答')
  assert.equal(legacy.validated, false)
  assert.match(legacy.notice, /尚未验证/)
  const refusal = knowledgeModelAnswerView({ abstained: true, abstention_validated: true })
  assert.equal(refusal.label, '不知道 · 已拒答')
  assert.equal(refusal.validated, true)
  assert.match(refusal.notice, /有限题验证.*不能保证/)
  const unvalidated = knowledgeModelAnswerView({ abstained: true, abstention_validated: false })
  assert.equal(unvalidated.label, '不知道 · 已拒答')
  assert.equal(unvalidated.validated, false)
  assert.equal(knowledgeModelAnswerView({ abstained: false, abstention_validated: true }).label, '参数回答')
})

test('refresh requests do not overlap, recover after errors, and abort on unmount', async () => {
  const calls = [], results = [], errors = []
  const refresher = createKnowledgeModelRefresh({ load: signal => new Promise((resolve, reject) => calls.push({ signal, resolve, reject })),
    onResult: result => results.push(result), onError: error => errors.push(error) })
  const first = refresher.refresh()
  assert.equal(refresher.refresh(), first)
  await Promise.resolve()
  assert.equal(calls.length, 1)
  calls[0].reject(new Error('offline'))
  await first
  assert.equal(errors.length, 1)
  const next = refresher.refresh()
  await Promise.resolve()
  refresher.dispose()
  assert.equal(calls[1].signal.aborted, true)
  calls[1].resolve({ online: true })
  await next
  assert.equal(results.length, 0)
  await refresher.refresh()
  assert.equal(calls.length, 2)
})
