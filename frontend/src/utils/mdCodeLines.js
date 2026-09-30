/**
 * markdown-it 插件：给代码块按行产出 `.code-line[data-line]` 结构。
 *
 * 主流做法（Prism 的 line-numbers-rows、highlightjs-line-numbers.js 同思路）：
 * **在渲染那一步一次性生成静态结构**，行号由 CSS `::before { content: attr(data-line) }` 画出。
 * 与"渲染完再改 DOM"相比没有下列老问题：
 *   · 不需要重排 → 不会丢光标；
 *   · 行号是**属性**不是文本 → 复制代码 / 预览反推 Markdown 读 textContent 时绝不会带上行号；
 *   · 每次渲染都从 Markdown 源头生成 → 不会重复注入或留下孤儿节点。
 *
 * ⚠️ 幂等性是硬要求（踩过）：md-editor 的 markdownItConfig 可能对同一个实例多次生效，
 * 而 markdown-it 的 `use()` 不去重 —— 覆盖 fence 时会把上一次的产物**再包一层**，
 * 表现为"两列行号 + 代码块底部多出重复行"（用户截图）。这里用实例标记 +
 * 内容探测双重防护。
 *
 * 已知边界（不是 bug，是这条路线的边界）：行号在**渲染时**确定，
 * 因此用户在预览里直接按回车新增的那一行，要等**重新渲染**（保存/切换）才会拿到号。
 * 要做到"打字立即出行号"，只能由拥有行结构的编辑器负责（Stage 1 的代码块 node view）。
 */
const FLAG = '__mdCodeLinesEnabled'

export default function mdCodeLines(md) {
  // 同一个 markdown-it 实例只生效一次
  if (md[FLAG]) return
  md[FLAG] = true

  const fence = md.renderer.rules.fence
  md.renderer.rules.fence = (tokens, idx, options, env, self) => {
    const html = fence(tokens, idx, options, env, self)
    // 已经被本插件处理过就不再处理（防止链路里被二次调用）
    if (html.indexOf('class="code-line"') >= 0) return html
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
