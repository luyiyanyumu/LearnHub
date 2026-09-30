/**
 * markdown-it 插件：给代码块按行产出 `.code-line[data-line]` 结构。
 *
 * 这是主流做法（Prism 的 line-numbers-rows、highlightjs-line-numbers.js 同思路）：
 * **在渲染那一步一次性生成静态结构**，行号由 CSS `::before { content: attr(data-line) }` 画出。
 * 与"渲染完再改 DOM"相比，这条路没有下列老问题：
 *   · 不需要重排 → 不会丢光标（用户在预览里打字时，浏览器只改行内文本，行结构仍在）；
 *   · 行号是**属性**不是文本 → 复制代码 / 预览反推 Markdown 读的是 textContent，
 *     行号绝不会被当成代码内容吞进去（这是之前 14 行变 28 行的根因）；
 *   · 每次渲染都从 Markdown 源头生成 → 不存在"重复注入/孤儿节点"。
 *
 * 行间的换行放进 `<span class="code-br">`（CSS 里 display:none）：
 * 视觉换行由 `.code-line{display:block}` 提供，而 textContent 与原文**一字不差**。
 * 代码结尾那个换行归属最后一行，因此不会多出一个空行（也就不会多一个号）。
 */
export default function mdCodeLines(md) {
  const fence = md.renderer.rules.fence
  md.renderer.rules.fence = (tokens, idx, options, env, self) => {
    const html = fence(tokens, idx, options, env, self)
    const m = html.match(/^(<pre[^>]*><code[^>]*>)([\s\S]*?)(<\/code><\/pre>\s*)$/)
    if (!m) return html
    return m[1] + wrapLines(m[2]) + m[3]
  }
}

/**
 * 把 `<code>` 内的 HTML 按行包起来。
 * 直接按字符串切 `\n` 是安全的：它是 highlight.js 的输出，标签内不会出现换行。
 */
function wrapLines(inner) {
  const lines = inner.split('\n')
  const tail = lines.pop() // 结尾换行后的空串
  let out = ''
  lines.forEach((line, i) => {
    out += `<span class="code-line" data-line="${i + 1}">${line}<span class="code-br">\n</span></span>`
  })
  // 结尾若还有内容（末行没有换行结尾），补一行；否则不补 —— 避免多出一个空行/空号
  if (tail) out += `<span class="code-line" data-line="${lines.length + 1}">${tail}</span>`
  return out
}
