const states = {
  pending: ['等待提交', 'info'], retry: ['提交待重试', 'warning'], submitted: ['已提交 · 待同步', 'info'],
  queued: ['排队中', 'info'], running: ['训练中', 'primary'], evaluating: ['闭卷评测中', 'primary'],
  published: ['评测通过 · 已发布', 'success'], rejected: ['评测未通过', 'warning'],
  unvalidated: ['评测未覆盖 · 未发布', 'warning'], failed: ['执行失败', 'danger'],
}

export function knowledgeModelJobState(status, progress = {}) {
  const phase = progress.phase || ''
  const evaluating = status === 'running' && phase.includes('evaluation')
  const key = evaluating ? 'evaluating' : status
  const [label, type] = states[key] || ['状态待确认', 'info']
  return { key, label, type }
}

/** A submitted upload is never considered trained until the worker confirms it. */
export function knowledgeModelJobs(payload, progress = {}) {
  const remote = Array.isArray(payload?.service) ? payload.service : payload?.service?.jobs || []
  const outbox = Array.isArray(payload?.outbox) ? payload.outbox : []
  const remoteById = new Map(remote.map(job => [String(job.id), job]))
  const linked = new Set()
  const result = outbox.map(upload => {
    const job = remoteById.get(String(upload.remoteJobId))
    if (job) linked.add(String(job.id))
    const status = job?.status || upload.state
    return { ...job, key: `upload-${upload.id}`, outboxId: upload.id, fileId: upload.fileId,
      title: job?.title || upload.title || '未命名资料', createdAt: job?.created_at || upload.createdAt,
      status, error: job?.error || upload.lastError,
      remoteJobId: job?.id || upload.remoteJobId,
      view: knowledgeModelJobState(status, progress), attempts: upload.attempts }
  })
  for (const job of remote) {
    if (!linked.has(String(job.id))) result.push({ ...job, key: `job-${job.id}`,
      title: job.title || '未命名资料', createdAt: job.created_at,
      remoteJobId: job.id, view: knowledgeModelJobState(job.status, progress) })
  }
  return result.sort((a, b) => (Date.parse(b.createdAt) || 0) - (Date.parse(a.createdAt) || 0))
}

export function knowledgeModelScores(result) {
  const before = result?.baseline?.old?.accuracy
  const after = result?.candidate?.old?.accuracy
  const hasAbstentionPolicy = result?.abstention_policy === 'v1'
  return {
    newScore: result?.candidate?.new?.accuracy ?? null,
    oldScore: after ?? null,
    oldBefore: before ?? null,
    forgetting: Number.isFinite(before) && Number.isFinite(after) ? before - after : null,
    unknownRefusal: abstentionMetric(hasAbstentionPolicy && result?.candidate?.unknown, 'accuracy', 'correct'),
    knownRefusal: abstentionMetric(hasAbstentionPolicy && result?.candidate?.known_abstention, 'rate', 'count'),
  }
}

function abstentionMetric(group, rateKey, countKey) {
  const covered = Number.isFinite(group?.total) && group.total > 0
    && Number.isFinite(group?.[rateKey]) && Number.isFinite(group?.[countKey])
  return { covered, rate: covered ? group[rateKey] : null,
    count: covered ? group[countKey] : null, total: covered ? group.total : null }
}

/** Refusal text alone is not evidence that an old model passed refusal checks. */
export function knowledgeModelAnswerView(answer) {
  return {
    label: answer?.abstained === true ? '不知道 · 已拒答' : '参数回答',
    type: answer?.abstained === true ? 'warning' : 'primary',
    validated: answer?.abstention_validated === true,
    notice: answer?.abstention_validated === true
      ? '此版本的拒答能力仅经有限题验证，不能保证对所有知识库外的问题都正确拒答。'
      : '此版本尚未验证拒答能力，不能保证对知识库外的问题正确拒答。',
  }
}

export function formatKnowledgeScore(value) {
  return Number.isFinite(value) ? `${(value * 100).toFixed(1)}%` : '未覆盖'
}

export function formatKnowledgeDrop(value) {
  return Number.isFinite(value) ? `${value > 0 ? '+' : ''}${(value * 100).toFixed(1)} 个百分点` : '暂无旧题'
}

/** One request at a time; disposal aborts it and makes late results inert. */
export function createKnowledgeModelRefresh({ load, onResult, onError, onBusy }) {
  let pending = null
  let disposed = false
  let controller = null
  return {
    refresh() {
      if (disposed) return Promise.resolve()
      if (pending) return pending
      controller = new AbortController()
      onBusy?.(true)
      pending = Promise.resolve().then(() => load(controller.signal)).then(result => {
        if (!disposed) onResult(result)
      }).catch(error => {
        if (!disposed && error?.code !== 'ERR_CANCELED') onError(error)
      }).finally(() => {
        pending = null
        if (!disposed) onBusy?.(false)
      })
      return pending
    },
    dispose() { disposed = true; controller?.abort() },
  }
}
