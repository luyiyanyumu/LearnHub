/** Return the next table cell in the same column, or null at the edge. */
export function nextTableCell({ row, col, width, height }, direction = 1) {
  if (![row, col, width, height].every(Number.isInteger) || width < 1 || height < 1) return null
  const nextRow = row + (direction < 0 ? -1 : 1)
  if (row < 0 || row >= height || col < 0 || col >= width || nextRow < 0 || nextRow >= height) return null
  return { row: nextRow, col }
}
