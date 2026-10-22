/** Cache content coordinates; scrolling changes viewport coordinates, not this layout. */
export function createPreviewGeometryCache() {
  let currentHost = null, currentWidth = 0, layout = null
  const currentLayout = host => {
    if (!layout || host !== currentHost || host.clientWidth !== currentWidth) {
      currentHost = host
      currentWidth = host.clientWidth
      layout = { headings: null, geometry: null, measured: new Map() }
    }
    return layout
  }
  const measureContent = (host, current) => {
    let origin
    return element => {
      if (!current.measured.has(element)) {
        origin ??= host.getBoundingClientRect().top + host.clientTop - host.scrollTop
        const bounds = element.getBoundingClientRect()
        // Store content coordinates so a later anchor read can reuse headings
        // even when the pane has scrolled since they were first measured.
        current.measured.set(element, { top: bounds.top - origin, height: bounds.height })
      }
      return current.measured.get(element)
    }
  }
  const readHeadings = (host, current, rect) => {
    if (!current.headings) {
      current.headings = [...host.querySelectorAll('.md-editor-preview h1, .md-editor-preview h2, .md-editor-preview h3, .tiptap h1, .tiptap h2, .tiptap h3')]
        .flatMap((element, index) => {
          const bounds = rect(element)
          return bounds.height > 0 ? [{ index, top: bounds.top }] : []
        })
    }
    return current.headings
  }
  return {
    invalidate() { layout = null },
    readHeadings(host) {
      if (!host) return []
      const current = currentLayout(host)
      return readHeadings(host, current, measureContent(host, current))
    },
    read(host) {
      if (!host) return { headings: [], anchors: [] }
      const current = currentLayout(host)
      if (current.geometry) return current.geometry
      const rect = measureContent(host, current)
      const headings = readHeadings(host, current, rect)
      const candidates = [...host.querySelectorAll('.md-editor-preview [data-line], .tiptap [data-line]')]
        .flatMap(element => {
          const line = Number(element.dataset.line), bounds = rect(element)
          return Number.isInteger(line) && line >= 0 && bounds.height > 0
            ? [{ line, preview: bounds.top }] : []
        }).sort((a, b) => a.line - b.line)
      const anchors = []
      for (const point of candidates) {
        const previous = anchors.at(-1)
        if (previous && (point.line <= previous.line || point.preview < previous.preview)) continue
        anchors.push(point)
      }
      current.geometry = { headings, anchors }
      return current.geometry
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
