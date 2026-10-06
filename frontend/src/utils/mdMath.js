/**
 * LaTeX 数学公式：markdown-it 插件 + KaTeX 渲染（笔记 / 智能体回答 / 速查卡共用一套约定）。
 *
 * <h3>支持的定界符</h3>
 * <pre>
 *   行内：$E=mc^2$          或  \(E=mc^2\)
 *   块级：$$ x = \frac{-b}{2a} $$   或  \[ x = … \]
 * </pre>
 *
 * <h3>为什么自己写插件，而不是装 markdown-it-katex</h3>
 * 项目里已经有 katex（0.19）与一套自研的 TeX 校验/渲染（`extractedMath.js`），
 * 但**没有** markdown-it 的数学插件；而这一步的规则其实很小、边界明确，
 * 自己写能顺便把两件本仓库特别在意的事做对：
 *   1. `\$` 转义、`$5 与 $6`（价格）、`$ 空格 ` 这些都不该被当成公式；
 *   2. 代码块/行内代码里的 `$` 必须原样保留（数学插件绝不能进代码）。
 * 而 markdown-it 的 inline/block 规则天然满足第 2 点（代码由更高的优先级消费）。
 *
 * <h3>输出为什么带一层 data-tex 外壳</h3>
 * 编辑器（tiptap）要能把渲染结果**还原成节点**，最稳的做法是把 TeX 源码挂在属性上，
 * 而不是去 katex 的输出里找 `<annotation>`。外壳不影响源码往返：`htmlToMd` 的
 * `normalize()` 会先把 `.katex` / `.katex-display` 换回 `$…$` 文本，未知外壳随后被丢弃。
 * katex 的 `output` 固定为 `htmlAndMathml` 也是为此 —— 有 MathML 才有 annotation。
 */
import katex from 'katex'

/** TeX → katex HTML。渲染失败时返回 `<span class="math-error">` 而不是抛错（笔记不能被一段坏公式带崩） */
export function renderMathToHtml(tex, { display = false } = {}) {
  const source = String(tex == null ? '' : tex)
  try {
    return katex.renderToString(source, {
      displayMode: display,
      throwOnError: false,
      strict: false,
      trust: false,
      output: 'htmlAndMathml',
    })
  } catch (e) {
    const esc = source.replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;')
    return `<span class="math-error" title="${String(e && e.message ? e.message : e)}">${esc}</span>`
  }
}

const CODE_DOLLAR = 0x24 // $
const CODE_BACKSLASH = 0x5c // \
const CODE_OPEN_BRACKET = 0x5b // [
const CODE_OPEN_PAREN = 0x28 // (

const isSpace = (code) => code === 0x20 || code === 0x09 || code === 0x0a || code === 0x0d
const isDigit = (code) => code >= 0x30 && code <= 0x39

/** `$…$` 与 `\(…\)` 的行内规则；不满足条件就返回 false，交给后面的规则当普通文本 */
function mathInline(state, silent) {
  const start = state.pos
  const src = state.src
  const code = src.charCodeAt(start)
  let openLen = 1
  let closer = CODE_DOLLAR
  if (code === CODE_DOLLAR) {
    // 见下
  } else if (code === CODE_BACKSLASH && src.charCodeAt(start + 1) === CODE_OPEN_PAREN) {
    openLen = 2
    closer = CODE_OPEN_PAREN
  } else {
    return false
  }

  const bodyStart = start + openLen
  if (bodyStart >= state.posMax) return false
  if (code === CODE_DOLLAR && isSpace(src.charCodeAt(bodyStart))) return false // "$ 5" 不是公式

  let end = -1
  for (let i = bodyStart; i < state.posMax; i++) {
    const c = src.charCodeAt(i)
    if (c === CODE_BACKSLASH) {
      // `\)` 才是 \(…\) 的收尾；其它转义字符整体跳过，避免把 `\$` 当定界符
      if (closer === CODE_OPEN_PAREN && src.charCodeAt(i + 1) === 0x29 /* ) */) { end = i; break }
      i++
      continue
    }
    if (closer === CODE_DOLLAR && c === CODE_DOLLAR) { end = i; break }
  }
  if (end < 0) return false
  if (end === bodyStart) return false
  if (isSpace(src.charCodeAt(end - 1))) return false // "$ … $" 不是公式
  if (closer === CODE_DOLLAR && isDigit(src.charCodeAt(end + 1))) return false // "$5 … $6" 不是公式

  // `\(…\)` 的正文不包含收尾的 `\)`
  const content = src.slice(bodyStart, closer === CODE_OPEN_PAREN ? end : end)

  if (!silent) {
    const token = state.push('math_inline', 'math', 0)
    token.markup = closer === CODE_DOLLAR ? '$' : '\\)'
    token.content = content
  }
  state.pos = closer === CODE_OPEN_PAREN ? end + 2 : end + 1
  return true
}

/** `$$…$$` 与 `\[…\]` 的块级规则：可以跨行，独占一块 */
function mathBlock(state, startLine, endLine, silent) {
  const start = state.bMarks[startLine] + state.tShift[startLine]
  const max = state.eMarks[startLine]
  const line = state.src.slice(start, max)
  let closer = '$$'
  let m = /^\s*\$\$/.exec(line)
  if (m) {
    closer = '$$'
  } else if ((m = /^\s*\\\[/.exec(line))) {
    closer = '\\]'
  } else {
    return false
  }
  const bodyFrom = start + m[0].length
  // 同一行就闭合（$$ x $$）
  const sameLine = state.src.slice(bodyFrom, max)
  const inlineEnd = sameLine.indexOf(closer)
  if (inlineEnd >= 0) {
    if (silent) return true
    const content = sameLine.slice(0, inlineEnd)
    push(state, content, startLine, startLine + 1)
    state.line = startLine + 1
    return true
  }

  const buf = [sameLine]
  for (let next = startLine + 1; next < endLine; next++) {
    const s = state.bMarks[next] + state.tShift[next]
    const e = state.eMarks[next]
    const text = state.src.slice(s, e)
    const idx = text.indexOf(closer)
    if (idx >= 0) {
      buf.push(text.slice(0, idx))
      if (silent) return true
      push(state, buf.join('\n'), startLine, next + 1)
      state.line = next + 1
      return true
    }
    buf.push(text)
  }
  return false // 没闭合：当普通文本

  function push(st, content, line0, line1) {
    const token = st.push('math_block', 'math', 0)
    token.block = true
    token.content = content.trim()
    token.markup = closer === '$$' ? '$$' : '\\['
    token.map = [line0, line1]
  }
}

/**
 * 把数学公式接进一个 markdown-it 实例。
 * 用法：`md.use(mdMath)` —— 与 `mdCallout` / `mdAnchor` 是同一类插件。
 */
export function mdMath(md) {
  md.inline.ruler.before('escape', 'math_inline', mathInline)
  md.block.ruler.before('fence', 'math_block', mathBlock, {
    alt: ['paragraph', 'reference', 'blockquote', 'list'],
  })
  md.renderer.rules.math_inline = (tokens, idx) => {
    const tex = tokens[idx].content
    return `<span class="math-inline" data-tex="${escapeAttr(tex)}">${renderMathToHtml(tex)}</span>`
  }
  md.renderer.rules.math_block = (tokens, idx) => {
    const tex = tokens[idx].content
    return `<div class="math-block" data-tex="${escapeAttr(tex)}">${renderMathToHtml(tex, { display: true })}</div>\n`
  }
}

function escapeAttr(s) {
  return String(s).replace(/&/g, '&amp;').replace(/"/g, '&quot;').replace(/</g, '&lt;')
}

// 与 mdCallout / mdAnchor 保持一致：默认导出插件本身，`md.use(mdMath)` 直接可用
export default mdMath
