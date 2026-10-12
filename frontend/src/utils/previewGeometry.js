/** Cache content coordinates; scrolling changes viewport coordinates, not this layout. */
export function createPreviewGeometryCache() {
  let currentHost = null, currentWidth = 0, geometry = null
  return {
    invalidate() { geometry = null },
    read(host) {
      if (!host) return { headings: [], anchors: [] }
      if (geometry && host === currentHost && host.clientWidth === currentWidth) return geometry
      currentHost = host
      currentWidth = host.clientWidth
      const origin = host.getBoundingClientRect().top + host.clientTop - host.scrollTop
      const measured = new Map()
      const rect = element => {
        if (!measured.has(element)) measured.set(element, element.getBoundingClientRect())
        return measured.get(element)
      }
      const headings = [...host.querySelectorAll('.md-editor-preview h1, .md-editor-preview h2, .md-editor-preview h3, .tiptap h1, .tiptap h2, .tiptap h3')]
        .flatMap((element, index) => {
          const bounds = rect(element)
          return bounds.height > 0 ? [{ index, top: bounds.top - origin }] : []
        })
      const candidates = [...host.querySelectorAll('.md-editor-preview [data-line], .tiptap [data-line]')]
        .flatMap(element => {
          const line = Number(element.dataset.line), bounds = rect(element)
          return Number.isInteger(line) && line >= 0 && bounds.height > 0
            ? [{ line, preview: bounds.top - origin }] : []
        }).sort((a, b) => a.line - b.line)
      const anchors = []
      for (const point of candidates) {
        const previous = anchors.at(-1)
        if (previous && (point.line <= previous.line || point.preview < previous.preview)) continue
        anchors.push(point)
      }
      geometry = { headings, anchors }
      return geometry
    },
  }
}

export function activeHeadingIndex(headings, position) {
  let low = 0, high = headings.length
  while (low < high) {
    const mid = (low + high) >> 1
    if (headings[mid].top <= position) low = mid + 1
    else high = mid
  }
  return low ? headings[low - 1].index : -1
}

/** Only measure the binary-search path and its two interpolation neighbors. */
export function mapSparseScroll(anchors, position, from, measureSource, sourceMax, previewMax, maxLine = Infinity) {
  if (sourceMax <= 0 || previewMax <= 0) return null
  // Pending preview updates can retain anchors beyond the current source. Bound
  // the search without filtering or copying every anchor on each scroll frame.
  let first = 0, last = anchors.length
  while (first < last) {
    const mid = (first + last) >> 1
    if (anchors[mid].line < maxLine) first = mid + 1
    else last = mid
  }
  const count = first
  const samples = new Map()
  const point = index => {
    if (!samples.has(index)) samples.set(index, { source: measureSource(anchors[index]), preview: anchors[index].preview })
    return samples.get(index)
  }
  let low = 0, high = count
  while (low < high) {
    const mid = (low + high) >> 1
    const value = from === 'preview' ? anchors[mid].preview : point(mid).source
    if (value <= position) low = mid + 1
    else high = mid
  }
  const start = { source: 0, preview: 0 }, end = { source: sourceMax, preview: previewMax }
  const valid = p => Number.isFinite(p.source) && p.source > 0 && p.source < sourceMax
    && p.preview > 0 && p.preview < previewMax
  let before = low > 0 ? point(low - 1) : null
  const after = low < count ? point(low) : null
  // The two panes can have different final visible blocks. Find the preceding
  // valid anchor rather than jumping to the document start near the bottom.
  if (before && (before.source >= sourceMax || before.preview >= previewMax)) {
    let first = 0, last = low
    while (first < last) {
      const mid = (first + last) >> 1, candidate = point(mid)
      if (candidate.source < sourceMax && candidate.preview < previewMax) first = mid + 1
      else last = mid
    }
    before = first ? point(first - 1) : null
  }
  const a = before && valid(before) ? before : start, b = after && valid(after) ? after : end
  const to = from === 'preview' ? 'source' : 'preview'
  const distance = b[from] - a[from]
  if (distance <= 0) return null
  return a[to] + (position - a[from]) / distance * (b[to] - a[to])
}
