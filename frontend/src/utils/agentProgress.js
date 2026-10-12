const STATUSES = new Set(['pending', 'running', 'completed', 'cancelled', 'error', 'waiting', 'interrupted'])

/** Same-id updates replace a running row in place; replay does not duplicate steps. */
export function upsertProgress(entries, event) {
  if (!event || typeof event.id !== 'string' || !event.id || typeof event.title !== 'string') return entries || []
  const previous = Array.isArray(entries) ? entries : []
  const index = previous.findIndex(item => item.id === event.id)
  const row = {
    ...(index < 0 ? {} : previous[index]),
    ...event,
    status: STATUSES.has(event.status) ? event.status : event.kind === 'plan' || previous[index]?.kind === 'plan' ? 'pending' : 'completed',
  }
  const next = [...previous]
  if (index < 0) next.push(row)
  else next[index] = row
  return next.slice(-180)
}

export function restoredProgress(progress, events = []) {
  if (Array.isArray(progress) && progress.length) return progress.reduce(upsertProgress, [])
  return events.map((event, index) => ({
    id: `legacy-${index}`, kind: 'update', status: /待确认|尚未执行/.test(event) ? 'waiting' : /失败|未完成/.test(event) ? 'error' : 'completed',
    title: event,
  }))
}

export function interruptProgress(entries) {
  return (entries || []).map(entry => entry.status === 'running'
    ? { ...entry, status: 'interrupted', detail: '进度连接已断开，可稍后重新打开会话查看处理结果' }
    : entry)
}

/** Responses belong to the request that created them, even after navigation or unmount. */
export function createChatRunGate() {
  let current = null
  return {
    begin() {
      current?.controller.abort()
      current = { controller: new AbortController() }
      return current
    },
    isCurrent(run) { return current === run && !run.controller.signal.aborted },
    finish(run) { if (current === run) current = null },
    invalidate() { current?.controller.abort(); current = null },
  }
}
