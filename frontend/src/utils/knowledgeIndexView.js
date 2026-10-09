/** Status is a read-only snapshot; configuring a model never builds an index. */
export function knowledgeIndexView(status, loading = false) {
  const base = { canRebuild: false, keywordOnly: false, warning: false, unavailable: false }
  if (loading) return { ...base, label: '读取索引…', description: '正在读取嵌入配置与索引状态' }
  if (!status) return { ...base, unavailable: true, label: '索引状态不可用', description: '暂时无法读取索引状态，请稍后重试；关键词检索仍可使用。' }
  const configured = status.configured !== false
  const enabled = status.enabled !== false
  const chunks = Number(status.chunks) || 0
  // Older servers do not report space compatibility; retain their previous behavior.
  const compatible = status.compatibleChunks == null ? chunks : Number(status.compatibleChunks) || 0
  if (!configured) return { ...base, keywordOnly: true, label: '未配置嵌入模型', description: '关键词检索仍可用。请在设置 → 模型档案与分工中选择向量嵌入档案，再手动重建索引。' + (status.reason ? ' ' + status.reason : '') }
  if (!enabled) return { ...base, keywordOnly: true, label: '向量检索已关闭', description: '关键词检索仍可用；开启向量检索后可使用嵌入模型与索引。' }
  const ready = { ...base, canRebuild: true }
  if (status.embeddingChanged || (chunks > 0 && compatible === 0)) return { ...ready, keywordOnly: compatible === 0, warning: true, label: '嵌入索引需重建', description: '嵌入服务或模型已变化，旧向量不能用于当前模型。请手动重建索引；关键词检索仍可用。' }
  if (status.stale) return { ...ready, warning: true, label: '索引待更新', description: '资料或嵌入空间已变化，请重建索引以更新检索结果。关键词检索仍可用。' }
  if (!compatible) return { ...ready, keywordOnly: true, label: '等待建立索引', description: '嵌入模型已配置，尚无可用向量索引；目前使用关键词检索。点击重建索引会调用嵌入服务。' }
  return { ...ready, label: '索引可用', description: '当前嵌入模型有可用索引。重建索引会调用嵌入服务。' }
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
