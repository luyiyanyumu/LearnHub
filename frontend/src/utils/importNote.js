/**
 * 笔记导入：把本地的 Markdown / HTML 文件变成一篇新笔记。
 *
 * <h3>与导出的对称性（这是标题规则的全部理由）</h3>
 * 导出一篇笔记写出来的文件是 `# 标题\n\n正文\n`（见 NoteEdit / NoteList 的 exportNote）。
 * 所以导入时必须把首行的 `# 标题` **提升成笔记标题**、并从正文里拿掉 ——
 * 否则"导出再导入"会多出一条重复的标题行，而且笔记标题栏还空着。
 * 没有一级标题时退回文件名（去掉扩展名），这是最符合直觉的兜底。
 *
 * <h3>为什么 HTML 走动态 import</h3>
 * HTML → Markdown 要 DOMParser + Turndown（`utils/htmlToMd.js`，Turndown 本身不小）。
 * 笔记页首屏用不到它，所以只在真的选中 .html 时才 `await import()` ——
 * 与笔记页导出 HTML 时动态加载 `mdToHtml` 是同一套做法。
 *
 * <h3>这里只做"文件 → {title, content}"</h3>
 * 不碰接口、不碰 UI：这样后缀识别、标题提升、换行归一这些规则都能用纯函数单测锁住
 * （HTML 那段依赖浏览器 DOM，只能在真机上验）。
 */

/** 文件选择框的 accept：Markdown 与 HTML 两类 */
export const IMPORT_ACCEPT = '.md,.markdown,.mdx,.txt,.html,.htm'

const HTML_EXT = ['html', 'htm']
const MARKDOWN_EXT = ['md', 'markdown', 'mdx', 'txt']

/** 单个文件的大小上限：笔记是文本，几 MB 的"笔记"基本都是误选 */
export const IMPORT_MAX_BYTES = 5 * 1024 * 1024

/** 取小写扩展名（没有扩展名返回空串） */
export function extOf(name) {
  const base = String(name || '').split(/[\\/]/).pop() || ''
  const i = base.lastIndexOf('.')
  return i > 0 ? base.slice(i + 1).toLowerCase() : ''
}

export function isHtmlFile(name) {
  return HTML_EXT.includes(extOf(name))
}

export function isMarkdownFile(name) {
  return MARKDOWN_EXT.includes(extOf(name))
}

/** 文件名 → 标题：去掉扩展名，其余原样（只清首尾空白） */
export function titleFromFileName(name) {
  const base = String(name || '').split(/[\\/]/).pop() || ''
  const i = base.lastIndexOf('.')
  const stem = i > 0 ? base.slice(0, i) : base
  return stem.trim() || '未命名笔记'
}

/** 统一换行与 BOM：后面所有规则都按 `\n` 处理 */
function normalizeText(text) {
  return String(text == null ? '' : text).replace(/^\uFEFF/, '').replace(/\r\n?/g, '\n')
}

/**
 * 解析 Markdown / 纯文本导入。
 *
 * 规则：跳过开头的空行后，如果第一行是一级标题（`# xxx`），它就是笔记标题，
 * 并从正文里去掉；否则标题取文件名、正文原样保留。
 *
 * @returns {{title: string, content: string}}
 */
export function parseMarkdownImport(name, text) {
  const t = normalizeText(text)
  const lines = t.split('\n')
  let i = 0
  while (i < lines.length && !lines[i].trim()) i++
  const m = /^#\s+(.+?)\s*#*\s*$/.exec(lines[i] || '')
  if (m) {
    const title = m[1].trim()
    const rest = lines.slice(i + 1).join('\n')
    return { title: title || titleFromFileName(name), content: ensureTrailingNewline(stripOuterBlank(rest)) }
  }
  return { title: titleFromFileName(name), content: ensureTrailingNewline(stripOuterBlank(t)) }
}

/** 去掉整体首尾空行（正文内部的空行保持原样） */
function stripOuterBlank(s) {
  return String(s || '').replace(/^\s*\n/, '').replace(/\s+$/, '')
}

function ensureTrailingNewline(s) {
  const v = String(s || '')
  return v ? v + '\n' : ''
}

/**
 * 读一个 File 对象 → `{title, content}`（笔记页的导入按钮用它）。
 *
 * HTML 分支：动态加载 `htmlToMd` 的整份文档转换；标题取 h1（其次 <title>），
 * 取到的那条标题会从正文里去掉，避免正文第一行又是同一个标题。
 */
export async function readNoteFile(file) {
  const name = file?.name || ''
  const text = await file.text()
  if (isHtmlFile(name)) {
    const { documentHtmlToMd } = await import('./htmlToMd')
    const { title, content } = documentHtmlToMd(text)
    return { title: title || titleFromFileName(name), content: ensureTrailingNewline(stripOuterBlank(content)) }
  }
  return parseMarkdownImport(name, text)
}
