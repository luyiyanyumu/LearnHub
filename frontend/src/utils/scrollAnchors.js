/** Build a monotone map from matching block positions in the two panes. */
export function normalizeScrollAnchors(points) {
  const sorted = points.filter(p => Number.isFinite(p.line)
    && Number.isFinite(p.source) && Number.isFinite(p.preview))
    .sort((a, b) => a.line - b.line)
  const result = []
  for (const point of sorted) {
    const previous = result.at(-1)
    // Nested lists/tables can mark several elements with the same source line.
    // Hidden/collapsed children must not make the map run backwards.
    if (previous && (point.line <= previous.line || point.source <= previous.source
      || point.preview < previous.preview)) continue
    result.push(point)
  }
  return result
}

/** Include both scroll limits so the last pixel does not cause a large jump. */
export function boundScrollAnchors(points, sourceMax, previewMax) {
  return [
    { source: 0, preview: 0 },
    ...points.filter(p => p.source > 0 && p.source < sourceMax
      && p.preview > 0 && p.preview < previewMax),
    { source: sourceMax, preview: previewMax },
  ]
}

/** Interpolate measured pixel positions, rather than logical source line counts. */
export function mapScrollPosition(points, position, from = 'source') {
  if (!points.length) return null
  const to = from === 'source' ? 'preview' : 'source'
  let lo = 0
  let hi = points.length - 1
  while (lo < hi) {
    const mid = (lo + hi + 1) >> 1
    if (points[mid][from] <= position) lo = mid
    else hi = mid - 1
  }
  const a = points[lo]
  const b = points[lo + 1]
  if (position < a[from]) return Math.max(0, a[to] + position - a[from])
  if (!b) return a[to] + position - a[from]
  const distance = b[from] - a[from]
  if (distance <= 0) return a[to]
  return a[to] + (position - a[from]) / distance * (b[to] - a[to])
}
