/**
 * 表格尺寸调节工具（预览编辑模式）。
 *
 * 列宽用 <colgroup><col style="width:…"> 表示，行高用 <tr style="height:…"> 表示。
 * 这些显式尺寸在 Turndown 反推时会被保留为内联 HTML <table>（见 htmlToMd.js 的 sizedTable 规则），
 * 因为 Markdown 管道表（| a | b |）本身无法表达列宽/行高。
 */

/** 找到表格的直接子 <colgroup>，没有则返回 null */
export function findColgroup(table) {
  for (const child of table.children) {
    if (child.tagName === 'COLGROUP') return child
  }
  return null
}

/** 确保表格有 colgroup（每列一个 col），无则创建 */
export function ensureColgroup(table) {
  if (findColgroup(table)) return
  const colCount = table.rows[0]?.cells.length || 0
  if (!colCount) return
  const cg = document.createElement('colgroup')
  for (let i = 0; i < colCount; i++) cg.appendChild(document.createElement('col'))
  table.insertBefore(cg, table.firstChild)
}

/** 表格是否带显式尺寸（列宽 / 行高），用于决定是否保留为内联 HTML */
export function hasExplicitSize(table) {
  const cg = findColgroup(table)
  if (cg) {
    for (const col of cg.children) {
      if (/width/i.test(col.getAttribute('style') || '')) return true
    }
  }
  for (const tr of Array.from(table.rows)) {
    if (/height/i.test(tr.getAttribute('style') || '')) return true
  }
  return false
}
