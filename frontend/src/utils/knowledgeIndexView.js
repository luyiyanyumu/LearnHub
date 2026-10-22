/** Reading status never starts work; automatic maintenance is owned by the backend. */
export function knowledgeIndexView(status, loading = false, maintenance = null) {
  const base = { canRebuild: false, keywordOnly: false, warning: false, unavailable: false }
  if (loading) return { ...base, label: '读取索引…', description: '正在读取嵌入配置与索引状态' }
  if (!status) return { ...base, unavailable: true, label: '索引状态不可用', description: '暂时无法读取索引状态，请稍后重试；关键词检索仍可使用。' }
  const configured = status.configured !== false
  const enabled = status.enabled !== false
  const chunks = Number(status.chunks) || 0
  // Older servers do not report space compatibility; retain their previous behavior.
  const compatible = status.compatibleChunks == null ? chunks : Number(status.compatibleChunks) || 0
  const updateHint = maintenance?.enabled === true
    ? (maintenance.message || '后台会自动合并变更并更新索引，可在知识库维护查看进度。')
    : maintenance?.enabled === false ? '自动维护已关闭，可在知识库维护开启或点击立即维护；高级修复中也可单独重建索引。'
      : '请到知识库维护查看后台更新状态。'
  if (!configured) return { ...base, keywordOnly: true, label: '未配置嵌入模型', description: '关键词检索仍可用。请在设置 → 模型档案与分工中选择向量嵌入档案。' + updateHint + (status.reason ? ' ' + status.reason : '') }
  if (!enabled) return { ...base, keywordOnly: true, label: '向量检索已关闭', description: '关键词检索仍可用；开启向量检索后可使用嵌入模型与索引。' }
  const ready = { ...base, canRebuild: !maintenance?.running }
  if (status.embeddingChanged || (chunks > 0 && compatible === 0)) return { ...ready, keywordOnly: compatible === 0, warning: true, label: '嵌入索引待更新', description: '嵌入服务或模型已变化，旧向量不能用于当前模型。' + updateHint + '关键词检索仍可用。' }
  if (status.stale) return { ...ready, warning: true, label: '索引待更新', description: '资料或嵌入空间已变化。' + updateHint + '关键词检索仍可用。' }
  if (!compatible) return { ...ready, keywordOnly: true, label: '等待建立索引', description: '嵌入模型已配置，尚无可用向量索引；目前使用关键词检索。' + updateHint }
  return { ...ready, label: '索引可用', description: '当前嵌入模型有可用索引。重建索引会调用嵌入服务。' }
}

export function maintenanceUpdateHint(status) {
  if (status?.enabled === false) return (status.message || '自动维护已关闭。') + ' 可到知识库维护开启自动维护或立即维护。'
  if (status?.message) return status.message
  if (status?.enabled === true) return '后台将自动更新，可在知识库维护查看待更新状态与进度。'
  return '暂未确认自动维护状态，请到知识库维护查看更新状态。'
}

export function maintenanceTime(value) {
  if (!value) return '—'
  const time = new Date(value)
  return Number.isFinite(time.getTime()) ? time.toLocaleString('zh-CN', { hour12: false }) : '—'
}

/** One read at a time, no automatic writes, and no response may outlive its owner. */
export function createKnowledgeMaintenanceLoader({ load, onState, interval = 5000, setTimer = setTimeout, clearTimer = clearTimeout }) {
  let active = false, disposed = false, revision = 0, timer, controller
  let state = { status: null, loading: false, submitting: false, error: '' }
  const publish = changes => { state = { ...state, ...changes }; if (!disposed) onState(state) }
  function cancelRead() { clearTimer(timer); timer = undefined; controller?.abort(); controller = null }
  function schedule() { if (active && !disposed && !state.submitting) timer = setTimer(() => { timer = undefined; refresh() }, interval) }
  async function refresh() {
    if (disposed || state.submitting) return false
    cancelRead()
    const current = ++revision
    const request = controller = new AbortController()
    publish({ loading: true, error: '' })
    try {
      const status = await load(request.signal)
      if (disposed || current !== revision) return false
      publish({ status, loading: false, error: '' })
      return true
    } catch (error) {
      if (disposed || current !== revision) return false
      publish({ loading: false, error: error?.message || '后台维护状态暂时不可用' })
      return false
    } finally {
      if (!disposed && current === revision) { controller = null; schedule() }
    }
  }
  return {
    refresh,
    start() { if (disposed) return; active = true; return refresh() },
    stop() { active = false; cancelRead(); if (!state.submitting) { revision++; publish({ loading: false }) } },
    async submit(action) {
      if (disposed || state.submitting) return false
      cancelRead(); const current = ++revision
      publish({ submitting: true, loading: false, error: '' })
      try {
        const status = await action()
        if (disposed || current !== revision) return false
        publish({ status, error: '' })
        return true
      } catch (error) {
        if (disposed || current !== revision) return false
        publish({ error: error?.message || '维护请求未确认，请刷新后台状态' })
        return false
      } finally {
        if (!disposed && current === revision) { publish({ submitting: false }); schedule() }
      }
    },
    dispose() { disposed = true; active = false; revision++; cancelRead() },
  }
}

/** A configuration refresh supersedes older in-flight status responses. */
export function createKnowledgeStatusLoader({ load, onState }) {
  let generation = 0
  let disposed = false
  return {
    async refresh() {
      if (disposed) return
      const current = ++generation
      onState({ loading: true, status: null })
      try {
        const status = await load()
        if (!disposed && current === generation) onState({ loading: false, status })
      } catch {
        if (!disposed && current === generation) onState({ loading: false, status: null })
      }
    },
    dispose() { disposed = true; generation++ },
  }
}
