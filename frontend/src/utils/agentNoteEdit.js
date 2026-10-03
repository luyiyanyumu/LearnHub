/** 确认定向编辑后的正文更新通知；只让同一篇且没有本地草稿的编辑器自动同步。 */
export const AGENT_NOTE_UPDATED_EVENT = 'lh-agent-note-updated'

export function matchesOpenNote(currentId, noteId) {
  return currentId != null && noteId != null && String(currentId) === String(noteId)
}

export function parseActionResult(response) {
  try {
    return typeof response?.result === 'string' ? JSON.parse(response.result) : (response?.result || {})
  } catch {
    return {}
  }
}
