/**
 * 粘贴清洗：把"带格式粘贴"（Ctrl+V）进来的 HTML 整理成可安全落进 Markdown 的样子。
 *
 * <h3>为什么需要这一层</h3>
 * 纯文本粘贴（Ctrl+Shift+V）不需要它 —— 那一路只取 `text/plain`。但带格式粘贴会拿到
 * 外部网页 / Word 的完整 HTML，其中有两类东西**不能原样进笔记**：
 *
 * <ol>
 *   <li><b>`data:` 内嵌图片</b>：Word 里贴一张截图，剪贴板 HTML 里就是几百 KB 的 base64。
 *       它会跟着反推写进 Markdown、存进 MySQL 的 LONGTEXT，之后每次打开笔记都要传一遍。
 *       这里按体积阈值直接丢掉（并回报数量，让调用方提示用户，而不是静默消失）。</li>
 *   <li><b>Office 私有命名空间标签</b>（`<o:p>` `<w:*>` `<v:*>` `<st1:*>`）与
 *       **`mso-*` 样式声明**：Word 内部标记，浏览器里没有任何意义。不清理的话，
 *       反推出来的 Markdown 里会塞满看不见的噪音（`<span style="mso-bidi-font-size:10.5pt">`）。</li>
 * </ol>
 *
 * <h3>为什么用正则而不是 DOMParser</h3>
 * 目标只有这三个「形态固定」的清理动作，正则足够且**能在 node 测试里直接跑**
 * （DOMParser 在纯 node 下不存在，为它引入 domino 会把测试依赖带进生产判断）。
 * 真正需要 DOM 的结构性转换仍由 `htmlToMd.js` 的 turndown 负责 —— 这里只做前置净化。
 */

/** 内嵌图片的体积阈值：超过就丢。200KB ≈ 一张普通截图的 base64 长度 */
const DEFAULT_MAX_IMAGE_BYTES = 200_000

/**
 * @param {string} html 剪贴板里的 text/html
 * @param {{maxImageBytes?: number}} [options]
 * @returns {{html: string, droppedImages: number, droppedMso: number}}
 *         清洗后的 HTML 与两个计数（调用方据此提示用户，避免"东西悄悄没了"）
 */
export function sanitizePastedHtml(html, options = {}) {
  const maxImageBytes = options.maxImageBytes ?? DEFAULT_MAX_IMAGE_BYTES
  let out = String(html ?? '')
  let droppedImages = 0
  let droppedMso = 0

  // ① 超大的内嵌图片：整段 <img> 删掉（留着 src 就等于留着那几百 KB）
  out = out.replace(/<img\b[^>]*>/gi, (tag) => {
    const m = /src\s*=\s*["'](data:image\/[^"']*)["']/i.exec(tag)
    if (m && m[1].length > maxImageBytes) {
      droppedImages++
      return ''
    }
    return tag
  })

  // ② Office 私有命名空间标签：成对或自闭的都去掉（它们本身不承载可见内容）
  out = out.replace(/<\/?(?:o|w|v|st1|x|m):[^>]*>/gi, '')

  // ③ style 属性里的 mso-* 声明：逐条过滤，整条 style 空了就连属性一起去掉
  out = out.replace(/(\sstyle\s*=\s*)(["'])([\s\S]*?)\2/gi, (_all, pre, quote, css) => {
    const kept = String(css)
      .split(';')
      .map((s) => s.trim())
      .filter((s) => {
        if (!s) return false
        if (/^mso-/i.test(s)) {
          droppedMso++
          return false
        }
        return true
      })
      .join('; ')
    return kept ? `${pre}${quote}${kept}${quote}` : ''
  })

  return { html: out, droppedImages, droppedMso }
}

/**
 * 剪贴板里有没有"值得按带格式处理"的 HTML。
 *
 * <p>为什么还要判一下：从记事本/终端复制的剪贴板里 `text/html` 要么没有、要么只是把纯文本
 * 包一层 `<meta charset>`，这时走带格式那条路反而绕远（还要过一次 turndown）。
 */
export function hasRichHtml(html) {
  const s = String(html ?? '')
  if (!s.trim()) return false
  // 只有这些标签 = 等价于纯文本，没必要转换
  return /<(?!\/?(?:meta|html|head|body|br|p|div|span|font)\b)[a-z][^>]*>/i.test(s)
    || /<(?:table|ul|ol|li|h[1-6]|pre|code|blockquote|a|img|strong|em|b|i)\b/i.test(s)
}
