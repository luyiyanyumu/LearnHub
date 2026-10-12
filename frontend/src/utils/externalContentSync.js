/** Coalesce source changes without rebuilding a hidden editor or replaying its own echo. */
export function createExternalContentSync({
  current, apply, onExternal = () => {}, delay = 150, available = false,
  setTimer = setTimeout, clearTimer = clearTimeout,
}) {
  let timer = null
  let latest = ''
  let pending = false
  let enabled = available
  let paused = false
  function clear() {
    if (timer !== null) clearTimer(timer)
    timer = null
  }
  function flush() {
    clear()
    if (!pending) return current()
    // A synchronous reader gets the authoritative source even while its view is hidden.
    if (!enabled || paused) return latest
    const value = latest
    pending = false
    try { apply(value) } catch (error) { pending = true; throw error }
    return value
  }
  function schedule() {
    clear()
    if (pending && enabled && !paused) timer = setTimer(flush, delay)
  }
  return {
    receive(value) {
      latest = String(value ?? '')
      pending = latest !== current()
      if (pending) onExternal()
      schedule()
    },
    flush,
    prepare() {
      flush()
      return enabled && !paused && !pending
    },
    hasPending: () => pending,
    setAvailable(value) {
      enabled = Boolean(value)
      clear()
      if (enabled && !paused) flush()
    },
    pause() { paused = true; clear() },
    resume() { paused = false; schedule() },
    cancel() { clear(); pending = false },
  }
}
