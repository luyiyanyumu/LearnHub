/** Normalize newlines while preserving every source line, including trailing blanks. */
export function codeBlockDocument(value) {
  return String(value || '').replace(/\r\n|\r/g, '\n')
}

/** Apply only the changed span so external undo/redo maps the current cursor. */
export function codeBlockChange(current, next) {
  if (current === next) return null
  let from = 0
  while (from < current.length && from < next.length && current[from] === next[from]) from++
  let oldEnd = current.length
  let newEnd = next.length
  while (oldEnd > from && newEnd > from && current[oldEnd - 1] === next[newEnd - 1]) { oldEnd--; newEnd-- }
  return { from, to: oldEnd, insert: next.slice(from, newEnd) }
}
