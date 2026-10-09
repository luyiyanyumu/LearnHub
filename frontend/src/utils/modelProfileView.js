export const MODEL_CONFIGURATION_CHANGED_EVENT = 'lh-model-configuration-changed'

/** Profiles created before purpose was introduced remain chat profiles. */
export function profilePurpose(profile) {
  return (typeof profile === 'string' ? profile : profile?.purpose) === 'embedding' ? 'embedding' : 'chat'
}

export function chatProfiles(profiles) {
  return (Array.isArray(profiles) ? profiles : []).filter(profile => profilePurpose(profile) === 'chat')
}

export function paramProfileSelection(profiles, currentId, activeId) {
  const choices = chatProfiles(profiles)
  return choices.find(profile => profile.id === currentId)?.id
    || choices.find(profile => profile.id === activeId)?.id || choices[0]?.id || ''
}

export function chatProfileSelection(profiles, id) {
  return chatProfiles(profiles).some(profile => profile.id === id) ? id : ''
}

export function isEmbeddingTask(row) {
  return row?.task === 'embed' || row?.field === 'modelForEmbed'
}

export function profileTargetsForTask(row, profiles) {
  const embedding = isEmbeddingTask(row)
  const source = Array.isArray(row?.targets) ? row.targets : [
    ...(embedding ? [
      { id: 'disabled', label: '未配置（仅关键词检索）', model: '', purpose: 'embedding' },
      { id: 'legacy', label: '兼容旧嵌入配置', model: '', purpose: 'embedding' },
    ] : []),
    ...(Array.isArray(profiles) ? profiles : []).filter(profile => profilePurpose(profile) === (embedding ? 'embedding' : 'chat')),
  ]
  return source.filter(target => embedding
    ? ['disabled', 'legacy'].includes(target.id) || profilePurpose(target) === 'embedding'
    : !['disabled', 'legacy'].includes(target.id) && profilePurpose(target) === 'chat')
    .map(target => ({ ...target, label: target.label || target.name || target.id, model: target.model || '' }))
}

export function routingTargetLabel(target) {
  return target.model ? `${target.label}（${target.model}）` : target.label
}

export function canRemoveProfile(profile, profiles) {
  return profilePurpose(profile) === 'embedding' || chatProfiles(profiles).length > 1
}

/** Discovery identifies embedding names heuristically; unmarked names remain usable. */
export function preferredDiscoveredModels(models, purpose) {
  const embedding = profilePurpose(purpose) === 'embedding'
  return (Array.isArray(models) ? models : []).filter(model => typeof model?.id === 'string' && model.id.trim())
    .map((model, index) => ({ model, index }))
    .sort((a, b) => Number((b.model.embedding === true) === embedding) - Number((a.model.embedding === true) === embedding) || a.index - b.index)
    .map(item => item.model)
}

export function discoveredModelSelection(models, purpose, currentModel = '') {
  if (String(currentModel || '').trim()) return String(currentModel).trim()
  const embedding = profilePurpose(purpose) === 'embedding'
  return preferredDiscoveredModels(models, purpose).find(model => (model.embedding === true) === embedding)?.id || ''
}

export function profileSaveBody(editor) {
  return {
    name: editor.name.trim(), provider: editor.provider, purpose: profilePurpose(editor), baseUrl: editor.baseUrl.trim(),
    model: editor.model.trim(), note: String(editor.note || '').trim(),
    apiKey: editor.id ? (editor.apiKey.trim() || '__KEEP__') : editor.apiKey.trim(),
  }
}

export function embeddingProfileSpaceChanged(previous, editor, assignedId) {
  return !!previous?.id && previous.id === assignedId && (
    profilePurpose(previous) !== profilePurpose(editor) || previous.provider !== editor.provider
    || String(previous.baseUrl || '').trim() !== editor.baseUrl.trim()
    || String(previous.model || '').trim() !== editor.model.trim())
}

const emptyDiscovery = () => ({ loading: false, models: [], message: '', hint: '', ok: null })

/** Switching the editor or its endpoint invalidates both old successes and errors. */
export function createModelDiscoveryLoader({ load, onState, onResult }) {
  let generation = 0
  let disposed = false
  return {
    reset() { generation++; if (!disposed) onState(emptyDiscovery()) },
    async request(input) {
      if (disposed) return
      const current = ++generation
      const snapshot = { ...input }
      onState({ ...emptyDiscovery(), loading: true })
      try {
        const result = await load(snapshot)
        if (disposed || current !== generation) return
        onResult?.(result, snapshot)
        if (disposed || current !== generation) return
        onState({ loading: false, ok: !!result.ok, models: result.models || [], message: result.message || '', hint: result.hint || '' })
      } catch (error) {
        if (disposed || current !== generation) return
        onState({ ...emptyDiscovery(), ok: false, message: error?.message || '获取模型失败，请检查地址与密钥' })
      }
    },
    dispose() { disposed = true; generation++ },
  }
}
