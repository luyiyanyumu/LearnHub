/**
 * mdDataLine —— 给块级元素的开始标签标注 `data-line`（该块在源码里的起始行号）。
 *
 * md-editor 自带这个标注，源码对照的滚动联动靠它把「预览滚动位置 ↔ 源码行号」对齐。
 * Tiptap 预览（BlockPreview）没有这层标注，这里用 markdown-it 渲染钩子补上，
 * 再由 dataLineAttr 扩展让 Tiptap 解析/回写该属性。
 */
export default function mdDataLine(md) {
  const original = md.renderer.renderToken.bind(md.renderer)
  md.renderer.renderToken = function (tokens, idx, options, env, self) {
    const token = tokens[idx]
    // 只标注块级「开标签」（nesting === 1）且有源码行号映射的
    if (token.map && token.nesting === 1) {
      token.attrSet('data-line', String(token.map[0]))
    }
    return original(tokens, idx, options, env, self)
  }
}
