/** Keep expensive serialization off the input transaction, and flush before reads. */
export function createDeferredSync(run, { delay = 250, setTimer = setTimeout, clearTimer = clearTimeout } = {}) {
  let timer = null
  let pending = false
  let paused = false
  function clear() {
    if (timer !== null) clearTimer(timer)
    timer = null
  }
  function flush() {
    clear()
    if (!pending) return
    pending = false
    try { return run() } catch (error) { pending = true; throw error }
  }
  function schedule() {
    pending = true
    clear()
    if (!paused) timer = setTimer(flush, delay)
  }
  return {
    schedule, flush,
    hasPending: () => pending,
    pause() { paused = true; clear() },
    resume() { paused = false; if (pending) schedule() },
    cancel() { clear(); pending = false },
  }
}
