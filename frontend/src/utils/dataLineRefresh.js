/**
 * Reconcile source-line metadata after the block editor serializes its Markdown.
 * Returning markup patches lets the caller preserve the current document and selection.
 */
function blocks(doc) {
  const result = []
  doc?.descendants((node, pos) => {
    if (!Object.prototype.hasOwnProperty.call(node.attrs, 'dataLine')) return
    const text = node.type.name === 'codeBlock'
      ? String(node.attrs.code ?? node.textContent)
      : String(node.attrs.title ?? node.textContent).trim().replace(/\s+/g, ' ')
    result.push({
      node,
      pos,
      key: JSON.stringify([node.type.name, node.attrs.level ?? null, text]),
    })
  })
  return result
}

function countByKey(items) {
  const counts = new Map()
  for (const item of items) counts.set(item.key, (counts.get(item.key) || 0) + 1)
  return counts
}

export function dataLineUpdates(currentDoc, markdownDoc) {
  const current = blocks(currentDoc)
  const rendered = blocks(markdownDoc)
  const sameOrder = current.length === rendered.length
    && current.every((item, i) => item.key === rendered[i].key)
  const currentCounts = countByKey(current)
  const renderedCounts = countByKey(rendered)
  const uniqueRendered = new Map(rendered.filter((item) => renderedCounts.get(item.key) === 1).map((item) => [item.key, item]))

  return current.flatMap((item, i) => {
    // Markdown normalization may merge blocks. Keep only unambiguous matches in
    // that case, and clear unmatched metadata instead of retaining stale anchors.
    const match = sameOrder ? rendered[i]
      : currentCounts.get(item.key) === 1 ? uniqueRendered.get(item.key) : null
    const rawLine = match?.node.attrs.dataLine
    const line = rawLine != null && /^\d+$/.test(String(rawLine)) ? String(rawLine) : null
    return item.node.attrs.dataLine === line ? [] : [{ pos: item.pos, node: item.node, line }]
  })
}
