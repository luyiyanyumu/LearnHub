/**
 * Markdown 表格操作：在 CodeMirror 编辑器中，对光标所在的 Markdown 表格做增删行列。
 *
 * 表格判定：连续以「可选空白 + |」开头的行视为一个表格块。
 * 其中由 `-` / `:` / `|` / 空格 组成的分隔行用于识别表头与对齐方式——
 * 它不参与「删除行」，但会随「增减列」一起改写，保证表格始终合法。
 *
 * 所有操作都是纯文本改写（不碰预览 DOM），改完后 md-editor-v3 会通过内部的
 * updateListener 自动把新文档同步回 v-model，预览随之刷新。
 */

/** 一行是否属于表格 */
function isTableLine(text) {
  return /^\s*\|/.test(text)
}

/** 一行拆成单元格：去掉首尾 |，按 | 切分并 trim */
function splitRow(text) {
  let s = text.trim()
  if (s.startsWith('|')) s = s.slice(1)
  if (s.endsWith('|')) s = s.slice(0, -1)
  return s.split('|').map((c) => c.trim())
}

/** 是否为分隔行（内容只含 - : 空格） */
function isSeparatorRow(cells) {
  return cells.length > 0 && cells.every((c) => /^:?-{1,}:?$/.test(c.trim()))
}

/** 定位光标所在的表格块；不在表格内返回 null */
export function findTable(view, pos) {
  const doc = view.state.doc
  const line = doc.lineAt(pos)
  if (!isTableLine(line.text)) return null

  let start = line.number
  let end = line.number
  while (start > 1 && isTableLine(doc.line(start - 1).text)) start--
  while (end < doc.lines && isTableLine(doc.line(end + 1).text)) end++

  const rows = []
  let sepIndex = -1
  for (let n = start; n <= end; n++) {
    const cells = splitRow(doc.line(n).text)
    rows.push(cells)
    if (sepIndex === -1 && isSeparatorRow(cells)) sepIndex = rows.length - 1
  }
  const colCount = Math.max(...rows.map((r) => r.length), 1)

  return {
    from: doc.line(start).from,
    to: doc.line(end).to,
    cursorLine: line.number - start, // 光标行在块内的 0 基序号
    rows,
    sepIndex, // 分隔行在 rows 中的下标；-1 表示无分隔行
    colCount,
  }
}

/** 光标所在列（0 基）：光标前的 | 数 - 1，越界收敛 */
function currentColumn(view, pos, colCount) {
  const line = view.state.doc.lineAt(pos)
  const before = line.text.slice(0, pos - line.from)
  let pipes = 0
  for (const ch of before) if (ch === '|') pipes++
  return Math.max(0, Math.min(pipes - 1, colCount - 1))
}

/** 把行数据格式化成列对齐的 Markdown 表格（返回行数组与列宽） */
function formatRows(rows, colCount) {
  const widths = new Array(colCount).fill(3)
  for (const r of rows) {
    for (let i = 0; i < colCount; i++) {
      widths[i] = Math.max(widths[i], (r[i] || '').length)
    }
  }
  const lines = rows.map((r) => {
    const cells = []
    for (let i = 0; i < colCount; i++) cells.push((r[i] || '').padEnd(widths[i], ' '))
    return '| ' + cells.join(' | ') + ' |'
  })
  return { lines, widths }
}

/** 目标单元格内容起点在重建文本里的偏移（用于把光标放回合适位置） */
function cellOffset(lines, widths, row, col) {
  let off = 0
  for (let i = 0; i < row; i++) off += lines[i].length + 1
  off += 2 // 跳过「| 」
  for (let j = 0; j < col; j++) off += widths[j] + 3 // 「 | 」
  return off
}

function emptyRow(colCount) {
  return new Array(colCount).fill('')
}

/** 统一把一次改写打包成 CM 事务参数（from/to/insert + 光标位置） */
function buildEdit(t, rows, colCount, selectRow, selectCol = 0) {
  const { lines, widths } = formatRows(rows, colCount)
  return {
    from: t.from,
    to: t.to,
    insert: lines.join('\n'),
    select: t.from + cellOffset(lines, widths, selectRow, selectCol),
  }
}

/** 在上方/下方插入一行（where: 'above' | 'below'） */
export function addRow(view, pos, where) {
  const t = findTable(view, pos)
  if (!t) return null
  let insertAt
  if (t.sepIndex === -1) {
    insertAt = where === 'above' ? t.cursorLine : t.cursorLine + 1
  } else if (t.cursorLine <= t.sepIndex) {
    insertAt = t.sepIndex + 1 // 光标在表头/分隔行：统一插到分隔行之后
  } else {
    insertAt = where === 'above' ? t.cursorLine : t.cursorLine + 1
  }
  const rows = t.rows.slice()
  rows.splice(insertAt, 0, emptyRow(t.colCount))
  return buildEdit(t, rows, t.colCount, insertAt)
}

/** 删除光标所在的数据行（表头/分隔行不可删） */
export function deleteRow(view, pos) {
  const t = findTable(view, pos)
  if (!t) return null
  if (t.sepIndex >= 0 && t.cursorLine <= t.sepIndex) return null
  const rows = t.rows.slice()
  rows.splice(t.cursorLine, 1)
  // 至少保留一行数据，避免删空后表格只剩表头
  if (t.sepIndex >= 0 && rows.length === t.sepIndex + 1) rows.push(emptyRow(t.colCount))
  const selectRow = Math.min(t.cursorLine, rows.length - 1)
  return buildEdit(t, rows, t.colCount, selectRow)
}

/** 在左侧/右侧插入一列（where: 'left' | 'right'） */
export function addCol(view, pos, where) {
  const t = findTable(view, pos)
  if (!t) return null
  const col = currentColumn(view, pos, t.colCount)
  const insertAt = where === 'left' ? col : col + 1
  const rows = t.rows.map((r, i) => {
    const cells = r.slice()
    cells.splice(insertAt, 0, i === t.sepIndex ? '---' : '')
    return cells
  })
  return buildEdit(t, rows, t.colCount + 1, t.cursorLine, insertAt)
}

/** 删除光标所在列（至少保留一列） */
export function deleteCol(view, pos) {
  const t = findTable(view, pos)
  if (!t) return null
  if (t.colCount <= 1) return null
  const col = currentColumn(view, pos, t.colCount)
  const rows = t.rows.map((r) => {
    const cells = r.slice()
    cells.splice(col, 1)
    return cells
  })
  return buildEdit(t, rows, t.colCount - 1, t.cursorLine, Math.min(col, t.colCount - 2))
}

/** 把光标所在行设为表头：移到第一行，并确保其后紧跟分隔行 */
export function setHeaderRow(view, pos) {
  const t = findTable(view, pos)
  if (!t) return null
  // 已是表头 / 光标在分隔行：无可操作
  if (t.sepIndex >= 0 && t.cursorLine <= t.sepIndex) return null
  const header = t.rows[t.cursorLine].slice()
  const sep = t.sepIndex >= 0 ? t.rows[t.sepIndex].slice() : header.map(() => '---')
  const others = t.rows.filter((_, i) => i !== t.cursorLine && i !== t.sepIndex)
  const rows = [header, sep, ...others]
  return buildEdit(t, rows, t.colCount, 0)
}

/** 设置光标所在列的对齐（left | center | right），改写分隔行标记 */
export function alignColumn(view, pos, align) {
  const t = findTable(view, pos)
  if (!t) return null
  if (t.sepIndex === -1) return null
  const col = currentColumn(view, pos, t.colCount)
  const marker = align === 'center' ? ':---:' : align === 'right' ? '---:' : '---'
  const rows = t.rows.map((r, i) => {
    if (i !== t.sepIndex) return r.slice()
    const cells = r.slice()
    cells[col] = marker
    return cells
  })
  return buildEdit(t, rows, t.colCount, t.cursorLine, col)
}

/** 删除整个表格块 */
export function deleteTable(view, pos) {
  const t = findTable(view, pos)
  if (!t) return null
  return { from: t.from, to: t.to, insert: '', select: t.from }
}
