// Editor activity lives outside document attrs: switching tabs must not create
// transactions, dirty a draft, or replace an already-created CodeMirror view.
const editorActivity = new WeakMap()
function activityFor(editor) {
  let activity = editorActivity.get(editor)
  if (!activity) { activity = { active: true, listeners: new Set() }; editorActivity.set(editor, activity) }
  return activity
}

export function setCodeBlockViewportActivity(editor, active) {
  if (!editor) return
  const activity = activityFor(editor)
  const next = Boolean(active)
  if (activity.active === next) return
  activity.active = next
  for (const listener of activity.listeners) listener(next)
}

export function clearCodeBlockViewportActivity(editor) {
  if (!editor) return
  setCodeBlockViewportActivity(editor, false)
  editorActivity.delete(editor)
}

/** Lazily activate a node near its scroll viewport, or immediately on interaction. */
export function createCodeBlockViewport({ editor, element, activate, onActivity = () => {},
  Observer = globalThis.IntersectionObserver,
  schedule = callback => requestAnimationFrame(callback),
  cancel = handle => cancelAnimationFrame(handle),
}) {
  const activity = activityFor(editor)
  let active = activity.active
  let near = !Observer
  let frame = null
  let disposed = false
  const visible = () => element.isConnected !== false
    && (!element.getClientRects || element.getClientRects().length > 0)
  function cancelPending() { if (frame != null) { cancel(frame); frame = null } }
  function run() {
    if (disposed || !active || !visible() || editor?.isDestroyed) return false
    activate()
    return true
  }
  function refresh() {
    if (disposed || !active || !near || frame != null) return
    frame = schedule(() => { frame = null; if (near) run() })
  }
  const observer = Observer ? new Observer(entries => {
    if (disposed || !active) return
    const entry = entries.find(item => item.target === element)
    if (!entry) return
    near = entry.isIntersecting
    if (near) refresh()
    else cancelPending()
  }, { root: element.closest?.('.pv-scroll') || null, rootMargin: '120px 0px', threshold: 0 }) : null
  function setActive(next) {
    active = next
    onActivity(active)
    if (!active) { cancelPending(); observer?.disconnect(); return }
    observer?.observe(element)
    refresh()
  }
  activity.listeners.add(setActive)
  setActive(active)
  return {
    refresh,
    request() { cancelPending(); return run() },
    dispose() {
      disposed = true
      cancelPending()
      observer?.disconnect()
      activity.listeners.delete(setActive)
    },
  }
}
