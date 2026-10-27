/** 确认定向编辑后的正文更新通知；只让同一篇且没有本地草稿的编辑器自动同步。 */
export const AGENT_NOTE_UPDATED_EVENT = 'lh-agent-note-updated'
export const AGENT_NOTE_PREPARE_EVENT = 'lh-agent-note-prepare'
export const AGENT_NOTE_INSERT_EVENT = 'lh-agent-note-insert'

/** The active editor claims a request synchronously, then completes its own save. */
function requestEditor(type, detail, { signal, timeoutMs = 30000 } = {}) {
  return new Promise((resolve, reject) => {
    if (signal?.aborted) { reject(new Error('操作已取消')); return }
    const controller = new AbortController()
    let claimed = false, settled = false, timer
    const finish = (error, result) => {
      if (settled) return
      settled = true
      clearTimeout(timer)
      signal?.removeEventListener('abort', abort)
      if (error) {
        // A save may finish after the caller has already timed out. Expire the
        // editor's request too, so its post-save check cannot insert late.
        controller.abort(error)
        reject(error instanceof Error ? error : new Error(String(error)))
      }
      else resolve(result)
    }
    const abort = () => finish(new Error('操作已取消'))
    signal?.addEventListener('abort', abort, { once: true })
    timer = setTimeout(() => finish(new Error('笔记自动保存尚未完成，请检查保存状态后重试')), timeoutMs)
    const request = { ...detail, signal: controller.signal, respond(result) {
      if (claimed) return
      claimed = true
      Promise.resolve(result).then(value => finish(null, value), error => finish(error))
    } }
    try { window.dispatchEvent(new CustomEvent(type, { detail: request })) }
    catch (error) { finish(error); return }
    if (!claimed) finish(new Error('当前笔记已切换或编辑器尚未就绪，请回到目标笔记重试'))
  })
}

export function prepareAgentNote({ noteId, editorKey, allowNew = false, signal, timeoutMs } = {}) {
  return requestEditor(AGENT_NOTE_PREPARE_EVENT, { noteId, editorKey, allowNew }, { signal, timeoutMs })
}

export function insertAgentNote({ noteId, editorKey, answer, question, signal, timeoutMs } = {}) {
  return requestEditor(AGENT_NOTE_INSERT_EVENT, { noteId, editorKey, answer, question }, { signal, timeoutMs })
}

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
