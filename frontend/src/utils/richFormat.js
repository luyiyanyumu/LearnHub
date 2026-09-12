/**
 * 富文本格式工具：颜色换算 + 语雀风格的标签包裹。
 *
 * 设计要点：
 * - 全部走「内联 HTML 标签」，因为 md-editor 的 markdown-it 开了 html:true，
 *   标签会原样渲染，导出 HTML / 前端预览 / 后端存储三处表现一致。
 * - 颜色统一写成 `rgb(r, g, b)`：语雀复制出来的就是这种写法，视觉上也与调色板一致。
 */

/** 常用文字色（对齐语雀调色板） */
export const TEXT_COLORS = [
  '#262626', '#595959', '#8c8c8c', '#bfbfbf',
  '#f5222d', '#fa541c', '#fa8c16', '#faad14',
  '#a0d911', '#52c41a', '#13c2c2', '#1677ff',
  '#2f54eb', '#722ed1', '#eb2f96', '#8c6b4f',
]

/** 常用背景色（浅色系，适合做行内标记） */
export const BG_COLORS = [
  '#fde8e8', '#fdebd0', '#fdf6d8', '#e8f5d8',
  '#d9f2e6', '#d8f0f2', '#dbeafe', '#e6e6fa',
  '#f5e0f0', '#efe6dc', '#f2f2f2', '#ffffff',
]

/** 字号档位（px） */
export const FONT_SIZES = [12, 14, 16, 18, 20, 24, 28, 32]

const clamp = (n, min, max) => Math.min(max, Math.max(min, Math.round(Number(n) || 0)))

export function rgbToCss(r, g, b) {
  return `rgb(${clamp(r, 0, 255)}, ${clamp(g, 0, 255)}, ${clamp(b, 0, 255)})`
}

export function hexToRgb(hex) {
  let h = String(hex || '').trim().replace(/^#/, '')
  if (h.length === 3) h = h.split('').map((c) => c + c).join('')
  if (!/^[0-9a-fA-F]{6}$/.test(h)) return null
  const n = parseInt(h, 16)
  return { r: (n >> 16) & 255, g: (n >> 8) & 255, b: n & 255 }
}

export function rgbToHex(r, g, b) {
  return '#' + [r, g, b].map((v) => clamp(v, 0, 255).toString(16).padStart(2, '0')).join('')
}

/** `rgb(1, 2, 3)` / `#abc` / `#aabbcc` → { r, g, b }，解析失败返回 null */
export function parseColor(str) {
  const s = String(str || '').trim()
  const m = s.match(/^rgba?\(\s*(\d+)\s*,\s*(\d+)\s*,\s*(\d+)/i)
  if (m) return { r: +m[1], g: +m[2], b: +m[3] }
  return hexToRgb(s)
}

/**
 * 构造 md-editor `insert(generate)` 需要的包裹参数。
 * 有选中 → 包住选中内容；无选中 → 插入占位文字并自动选中，方便直接改写。
 *
 * @param {string} prefix 前置标签
 * @param {string} suffix 后置标签
 * @param {string} placeholder 无选中时的占位文字
 */
export function wrapSelection(prefix, suffix, placeholder = '文字') {
  return (selected) => {
    const text = selected || placeholder
    return {
      targetValue: prefix + text + suffix,
      deviationStart: prefix.length,
      deviationEnd: -suffix.length,
    }
  }
}

/** 整段插入（块级格式用）：前后补空行，避免被当成行内内容吞掉 */
export function insertBlock(block, placeholder) {
  return (selected) => {
    const inner = selected || placeholder
    const text = block.replace('$1', inner)
    const targetValue = `\n\n${text}\n\n`
    // 选中块内占位文字，方便直接改写
    const at = targetValue.indexOf(inner)
    return {
      targetValue,
      deviationStart: at >= 0 ? at : targetValue.length,
      deviationEnd: at >= 0 ? -(targetValue.length - at - inner.length) : 0,
    }
  }
}

/** 去掉选中文本上的内联格式标签（清除格式） */
const STRIP_TAGS = /<\/?(?:font|span|mark|u|s|del|ins|sup|sub|strong|em|b|i|small|big)\b[^>]*>/gi

export function stripInline(text) {
  return String(text || '')
    .replace(STRIP_TAGS, '')
    .replace(/&nbsp;/gi, ' ')
}

/** 预设格式定义：kind → 包裹方式 */
export const FORMAT_PRESETS = {
  color: (v) => wrapSelection(`<font style="color: ${v}">`, '</font>'),
  bg: (v) => wrapSelection(`<font style="background-color: ${v}">`, '</font>'),
  size: (v) => wrapSelection(`<font style="font-size: ${v}px">`, '</font>'),
  mark: () => wrapSelection('<mark>', '</mark>', '高亮文字'),
  underline: () => wrapSelection('<u>', '</u>'),
  strike: () => wrapSelection('<s>', '</s>'),
  sup: () => wrapSelection('<sup>', '</sup>', '2'),
  sub: () => wrapSelection('<sub>', '</sub>', '2'),
  center: () => insertBlock('<p style="text-align: center">$1</p>', '居中文字'),
  // 段落对齐（语雀对齐∨下拉）：左对齐 = 纯文本不包裹
  align: (v) =>
    v === 'left'
      ? (selected) => ({ targetValue: selected || '', select: false })
      : insertBlock(`<p style="text-align: ${v}">$1</p>`, v === 'right' ? '右对齐文字' : '居中文字'),
  details: () =>
    insertBlock('<details>\n<summary>点击展开</summary>\n\n$1\n\n</details>', '折叠内容'),
  callout: () => insertBlock(':::tip\n$1\n:::', '提示内容'),
}
