/**
 * mdTitle —— 「标题只存笔记标题字段，正文不再写 `# 标题`」这条约定的前端兜底。
 *
 * 背景：一级标题（`# xxx`）以前既出现在正文首行、又存在笔记标题字段里，打开笔记就看到两个标题；
 * 导出 .md 时还会在前面再拼一次，变成三处。
 * 后端保存时已经会剥掉重复的那一行（NoteService#stripDuplicatedTitle），
 * 这里再兜一层：导出前若正文首行正好是同一个标题，先剥掉，保证导出件里标题只出现一次。
 */

/** 去掉空白与常见标点，便于比较两个标题是不是"同一个" */
function norm(s) {
  return String(s || '').replace(/[\s`*：:、，,。.（）()【】\[\]-]/g, '')
}

/** 两个标题是否算同一个：忽略空白与标点后相等，或互相包含（模型常把标题简写成短版） */
export function sameDocTitle(a, b) {
  const x = norm(a)
  const y = norm(b)
  if (!x || !y) return false
  return x === y || x.includes(y) || y.includes(x)
}

/**
 * 剥掉正文开头那行「与本笔记标题重复」的一级标题。
 * 只处理第一行就是 `# xxx` 且与标题相关的情况，其余内容一字不动。
 *
 * @param {string} content 正文
 * @param {string} title 笔记标题
 * @returns {string} 去掉重复标题行后的正文
 */
export function stripLeadingDocTitle(content, title = '') {
  const s = String(content || '')
  const m = s.match(/^\s*#\s+([^\n]+)\n?/)
  if (!m) return s
  if (!sameDocTitle(m[1], title)) return s
  return s.slice(m[0].length).replace(/^\s*\n/, '')
}
