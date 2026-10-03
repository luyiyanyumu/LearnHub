/**
 * noteHeadings —— 笔记正文里的**标题行**处理（大纲、去重都用这一套规则）。
 *
 * <h3>为什么单独抽出来</h3>
 * "大纲"（`NoteEdit.vue` 右侧栏）与"融入后去重"要做的是同一件事：**认出正文里的标题**。
 * 规则各写一份的后果是两边不一致 —— 大纲里看得见、去重却认不出（或反过来）。
 *
 * <h3>认哪些形式（实测依据）</h3>
 * 库里的笔记正文以 **Markdown 标题行**为主（抽查那条 4.9 万字的 R 笔记，116 个标题全是 `#` 行，
 * HTML 标题 0 个），但编辑器在两种情况下会把标题存成 HTML：块链接（`<h2 id="block-…">`）
 * 与缩进块（`<h2 data-block-indent="1">`）—— 见 `blockMeta.js` 的 `renderHTML`。
 * 所以标题识别要同时覆盖这两种形态。
 */

/** Markdown 标题行：`## 标题`（1~3 级才进大纲，与编辑器一致） */
const MD_HEADING = /^(#{1,3})\s+(.+?)\s*$/

/** HTML 标题（单行）：编辑器为块链接/缩进存下来的形态 */
const HTML_HEADING = /^\s*<h([1-3])\b[^>]*>([\s\S]*?)<\/h\1>\s*$/i

/**
 * 解析一行标题。
 * @returns {{level:number, text:string, kind:'md'|'html'} | null} 不是标题返回 null
 */
export function parseHeadingLine(line) {
  const s = String(line ?? '')
  const md = MD_HEADING.exec(s)
  if (md) {
    return { level: md[1].length, text: stripInline(md[2]), kind: 'md' }
  }
  const html = HTML_HEADING.exec(s)
  if (html) {
    return { level: Number(html[1]), text: stripInline(html[2]), kind: 'html' }
  }
  return null
}

/** 去掉行内标签与强调符号后再比较 —— 否则 `## **小节**` 与 `## 小节` 会被当成两个标题 */
function stripInline(text) {
  return String(text || '')
    .replace(/<[^<>]*>/g, '')
    .replace(/[*`~]/g, '')
    .replace(/\s+/g, ' ')
    .trim()
}

/**
 * 合并**相邻重复**的标题行。
 *
 * <h3>为什么只合并相邻的</h3>
 * 实测库里的重复都是"同一个标题连着出现两三次"（那条 R 笔记里
 * `## R 与 RStudio 起步` 在第 3、5 行，`### R 是什么` 在第 7、85、87 行）。
 * 而**跨小节的同名标题是合法的、不能删**（例如每节都有「小结」「注意事项」、
 * 或者不同层级下的同名子标题），所以这里只处理"只隔空行/只挨着"的重复：
 * 它们一定是同一份内容被写了两遍，不是有意的结构。
 *
 * @param {string} content 笔记正文
 * @returns {{content:string, removed:number[]}} 去重后的正文 + 被删标题的原行号（1 起）
 */
export function collapseAdjacentHeadingDuplicates(content) {
  const text = String(content ?? '')
  if (!text.trim()) {
    return { content: text, removed: [] }
  }
  const hasCrlf = text.includes('\r\n')
  const lines = text.split(/\r?\n/)
  const keep = []
  const removed = []
  // 上一个标题（空行视为连接符：`## X` / 空行 / `## X` 也算相邻重复）
  let lastHeading = null
  // 攒着的空行：遇到"非空行"时才决定留不留
  let blanks = []

  for (let i = 0; i < lines.length; i++) {
    const line = lines[i]
    if (line.trim() === '') {
      blanks.push(line)
      continue
    }
    const heading = parseHeadingLine(line)
    const dup = heading && lastHeading && lastHeading.level === heading.level && lastHeading.text === heading.text
    if (dup) {
      // 相邻重复 → 这一行丢掉，**它前面的空行也一起丢**（那是两份标题之间的空行）
      blanks = []
      removed.push(i + 1)
      continue
    }
    keep.push(...blanks)          // 正文/标题之间原本就有的空行留着
    blanks = []
    keep.push(line)
    lastHeading = heading || null
  }
  keep.push(...blanks)            // 文末空行
  return { content: keep.join(hasCrlf ? '\r\n' : '\n'), removed }
}
