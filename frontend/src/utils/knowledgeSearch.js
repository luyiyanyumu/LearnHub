import { retrievalKey, retrievalRelations, retrievalSnippet } from './retrievalDisplay.js'

/** Publish only the latest query's complete result, including its mode and error state. */
export function createKnowledgeSearchLoader({ loadFusion, loadKeyword, onState, onError }) {
  let generation = 0
  let disposed = false
  return {
    async search({ query = '', mode: requestedMode = 'fusion' } = {}) {
      if (disposed) return
      const input = String(query ?? '')
      const q = input.trim()
      const mode = requestedMode === 'fusion' && q ? 'fusion' : 'keyword'
      const current = ++generation
      onState({ loading: true, searched: true, error: '' })
      try {
        const response = await (mode === 'fusion' ? loadFusion(q, 10) : loadKeyword(input))
        if (current !== generation || disposed) return
        const items = mode === 'fusion' ? (response || []).map(r => ({
          type: r.sourceType ?? r.type,
          id: r.sourceId ?? r.id,
          // Preserve evidence-window keys before truncating their visible snippets.
          passageKey: retrievalKey(r),
          title: r.title,
          snippet: retrievalSnippet(r),
          categoryName: r.category,
          score: Number.isFinite(r.score) ? r.score : null,
          seq: r.seq,
          channels: r.channels,
          graphRelations: retrievalRelations(r),
          topicKey: r.topicKey,
          sectionKey: r.sectionKey,
          heading: r.heading,
          generatedGuide: r.generatedGuide,
          sourceRefs: r.sourceRefs,
        })) : response.items || []
        onState({ loading: false, searched: true, error: '', items,
          keyword: mode === 'fusion' ? q : response.keyword || '', lastMode: mode })
      } catch (error) {
        if (current !== generation || disposed) return
        const message = error?.response?.data?.msg || error?.message || '未知错误'
        onState({ loading: false, searched: true, error: message, items: [], keyword: q, lastMode: mode })
        onError?.(message, mode)
      }
    },
    dispose() { disposed = true; generation++ },
  }
}
