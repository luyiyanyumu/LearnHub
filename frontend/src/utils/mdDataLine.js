/**
 * mdDataLine —— 给块级元素标注 `data-line`（该块在源码里的起始行号）。
 *
 * md-editor 自带这个标注，源码对照的滚动联动靠它把「预览滚动位置 ↔ 源码行号」对齐。
 * Tiptap 预览（BlockPreview）没有这层标注，这里用 markdown-it 渲染钩子补上，
 * 再由 dataLineAttr 扩展让 Tiptap 解析/回写该属性。
 *
 * 两类都要标：
 * 1) 常规块（p/h1-h6/ul/ol/li/blockquote/table…）—— 开标签 nesting === 1 且有 map；
 * 2) 原生 HTML 块（用户的 `<table style="…">…</table>` 等）—— html_block 是 nesting 0 的
 *    单 token，走专门规则。**这类必须标**：它们往往是很长的源码行，渲染后却是紧凑表格，
 *    两边高度差异极大；没有锚点就只能吸附到上一个锚点，预览会明显滞后。
 */

/** 给一段 HTML 的首个开标签插入 data-line（已有则跳过） */
function injectDataLine(html, line) {
  if (!html || /data-line\s*=/.test(html)) return html
  return html.replace(/^(\s*)<([a-zA-Z][\w-]*)/, (m, sp, tag) => `${sp}<${tag} data-line="${line}"`)
}

export default function mdDataLine(md) {
  // ① 常规块：开标签
  const original = md.renderer.renderToken.bind(md.renderer)
  md.renderer.renderToken = function (tokens, idx, options, env, self) {
    const token = tokens[idx]
    if (token.map && (token.nesting === 1 || token.type === 'hr')) {
      token.attrSet('data-line', String(token.map[0]))
    }
    return original(tokens, idx, options, env, self)
  }

  // ② 原生 HTML 块：给首个开标签插入 data-line
  const origHtml = md.renderer.rules.html_block
  md.renderer.rules.html_block = function (tokens, idx, options, env, self) {
    const token = tokens[idx]
    const html = origHtml
      ? origHtml(tokens, idx, options, env, self)
      : token.content
    if (!token.map) return html
    return injectDataLine(html, token.map[0])
  }

  // ③ 围栏代码块：同样给 <pre> 插入 data-line。
  //    **这条最关键**：代码块在源码里动辄几十行，预览里也是几十行，
  //    没有锚点时大片区域只能靠两侧锚点插值/吸附，预览会整块滞后（用户实测"完全对不上"）。
  const origFence = md.renderer.rules.fence
  md.renderer.rules.fence = function (tokens, idx, options, env, self) {
    const token = tokens[idx]
    const html = origFence
      ? origFence(tokens, idx, options, env, self)
      : self.renderToken(tokens, idx, options)
    if (!token.map) return html
    return injectDataLine(html, token.map[0])
  }
}
