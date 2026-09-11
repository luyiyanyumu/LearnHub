/**
 * mdCallout —— 让 `:::名称 … :::` 容器语法渲染成彩色提示块。
 *
 * 背景：语雀 / 部分博客平台的笔记用 :::success、:::info、:::warning 等
 * 容器包住内容，markdown-it 原生不认识，预览里会把 :::success 和收尾 :::
 * 当普通文本显示。本插件把它们转成 <div class="md-callout md-callout-名称">。
 *
 * 支持：success / tip / info / note / warning / attention / danger / error，
 * 其它名称用中性灰色样式（颜色样式见 style.css 的 .md-callout-*）。
 * 嵌套容器不支持：::: 打开后遇到第一个收尾 ::: 就闭合；
 * 找不到收尾的孤立 ::: 不处理，保持原样显示。
 */
const OPEN_RE = /^:::([A-Za-z][\w-]*)?\s*$/
const CLOSE_RE = /^:::\s*$/

export default function mdCallout(md) {
  md.block.ruler.before('fence', 'md_callout', calloutRule, {
    alt: ['paragraph', 'reference', 'blockquote', 'list'],
  })

  function calloutRule(state, startLine, endLine, silent) {
    const pos = state.bMarks[startLine] + state.tShift[startLine]
    const max = state.eMarks[startLine]
    if (state.src.charCodeAt(pos) !== 0x3a /* : */) return false
    const m = state.src.slice(pos, max).trim().match(OPEN_RE)
    if (!m) return false
    if (silent) return true

    // 找配对的收尾 :::（找不到 = 不是完整容器，放回普通段落渲染）
    let close = -1
    for (let i = startLine + 1; i < endLine; i++) {
      const p = state.bMarks[i] + state.tShift[i]
      if (CLOSE_RE.test(state.src.slice(p, state.eMarks[i]).trim())) {
        close = i
        break
      }
    }
    if (close < 0) return false

    const openTk = new state.Token('html_block', '', 0)
    openTk.content = `<div class="md-callout md-callout-${md.utils.escapeHtml((m[1] || 'default').toLowerCase())}">\n`
    state.tokens.push(openTk)

    // 容器内部仍按 markdown 正常解析（标题/列表/代码块都可用）
    const oldParent = state.parentType
    const oldLineMax = state.lineMax
    state.parentType = 'blockquote'
    state.lineMax = close
    state.md.block.tokenize(state, startLine + 1, close)
    state.parentType = oldParent
    state.lineMax = oldLineMax

    const closeTk = new state.Token('html_block', '', 0)
    closeTk.content = '</div>\n'
    state.tokens.push(closeTk)
    state.line = close + 1
    return true
  }
}
