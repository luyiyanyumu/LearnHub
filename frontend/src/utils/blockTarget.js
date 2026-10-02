/** Keep the original block identity separate from the currently hovered menu item. */
export function createBlockTarget(doc, pos) {
  if (!Number.isInteger(pos) || pos < 0 || pos >= doc.content.size) return null
  const node = doc.nodeAt(pos)
  if (!node?.isBlock) return null
  return { pos, end: pos + node.nodeSize, node, deleted: false }
}

/**
 * Map both boundaries: a deleted block's start alone can point at its identical
 * following sibling, whereas its full range collapses. Markup-only replacements
 * keep a nonempty range, including atom code blocks that have no inner position.
 */
export function mapBlockTarget(target, transaction) {
  if (!target || target.deleted || !transaction.docChanged) return target
  const start = transaction.mapping.mapResult(target.pos, 1)
  const end = transaction.mapping.mapResult(target.end, -1)
  target.pos = start.pos
  target.end = end.pos
  target.deleted = start.deletedAcross || end.deletedAcross || end.pos <= start.pos
  // target.node deliberately stays the initial snapshot for async content checks.
  return target
}
