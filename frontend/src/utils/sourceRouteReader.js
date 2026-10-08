/** Resolve read=id independently of list filters; route or manual actions invalidate old loads. */
export function createSourceRouteReader({ load, onOpen, onMissing, onError }) {
  let generation = 0
  let disposed = false
  return {
    async open(read) {
      if (disposed) return
      const current = ++generation
      if (typeof read !== 'string' || !/^[1-9]\d*$/.test(read)) return
      try {
        const row = await load(read)
        if (disposed || current !== generation) return
        if (!row || String(row.id) !== read) { onMissing?.(read); return }
        await onOpen(row)
      } catch (error) {
        if (!disposed && current === generation) onError?.(error)
      }
    },
    cancel() { generation++ },
    dispose() { disposed = true; generation++ },
  }
}
