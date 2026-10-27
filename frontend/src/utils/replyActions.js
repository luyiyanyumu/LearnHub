const KINDS = new Set(['follow_up', 'save_note', 'merge_note', 'insert_at_cursor', 'copy'])

/** Model output is data: only the supported, bounded action shapes reach the UI. */
export function normalizeReplyActions(value) {
  if (!Array.isArray(value)) return []
  const actions = [], ids = new Set()
  for (const item of value.slice(0, 24)) {
    if (!item || typeof item !== 'object' || !KINDS.has(item.kind) || item.kind === 'copy') continue
    if (typeof item.id !== 'string' || !item.id.trim() || item.id.length > 80 || ids.has(item.id)) continue
    if (typeof item.label !== 'string' || !item.label.trim() || item.label.length > 80) continue
    if (item.kind === 'follow_up' && (typeof item.prompt !== 'string' || !item.prompt.trim() || item.prompt.length > 6000)) continue
    const action = { id: item.id, label: item.label.trim(), kind: item.kind }
    if (item.kind === 'follow_up') action.prompt = item.prompt.trim()
    if ((typeof item.noteId === 'number' && Number.isSafeInteger(item.noteId) && item.noteId > 0)
        || (typeof item.noteId === 'string' && /^\d{1,18}$/.test(item.noteId) && Number(item.noteId) > 0)) action.noteId = item.noteId
    ids.add(item.id)
    actions.push(action)
    if (actions.length === 4) break
  }
  return actions
}

export function followUpMessage(action, question, answer) {
  return `${action.prompt}\n\n关联的原问题：\n${question || '（无）'}\n\n关联的回答材料：\n${answer || ''}`
}

/** Pending note mutations carry their target without exposing tool arguments to the UI. */
export function pendingNoteId(action) {
  if (!['edit_note', 'update_note', 'append_to_note'].includes(action?.toolName)) return null
  return action.noteId ?? action.preview?.noteId ?? action.arguments?.note_id ?? action.args?.note_id ?? null
}
