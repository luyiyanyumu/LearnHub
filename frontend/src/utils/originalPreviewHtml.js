import MarkdownIt from 'markdown-it'
import katex from 'katex'
import hljs from 'highlight.js/lib/common'
import mdAnchor from './mdAnchor.js'
import mdCallout from './mdCallout.js'

const HTML_TAGS = new Set('p h1 h2 h3 h4 h5 h6 table thead tbody tfoot tr td th caption colgroup col strong b em i u s del sub sup span a img div br hr ul ol li blockquote pre code mark font dl dt dd figure figcaption abbr'.split(' '))
const MATH_TAGS = new Set('math semantics annotation mrow mi mn mo mtext mspace mfrac msqrt mroot msub msup msubsup munder mover munderover mtable mtr mtd menclose mstyle mpadded mphantom mfenced mmultiscripts mprescripts none'.split(' '))
const DROP_TAGS = new Set('script style iframe frame frameset object embed applet link meta base form input textarea button select option video audio source track canvas svg annotation-xml template noscript'.split(' '))
const MATH_NS = 'http://www.w3.org/1998/Math/MathML'
const COLOR = /^(?:#[0-9a-f]{3,8}|[a-z]{1,24}|(?:rgb|rgba|hsl|hsla)\([\d.,%\s+-]+\))$/i
const LENGTH = /^(?:0|\d{1,4}(?:\.\d{1,3})?(?:px|pt|em|rem|%|cm|mm|in))$/i
const CLASSES = /^(?:word-[\w-]+|md-callout(?:-[\w-]+)?|hljs(?:-[\w-]+)?|language-[\w+-]+|katex|original-math(?:-error|-block)?|original-image-blocked)$/

function safeLength(value) {
  if (!LENGTH.test(value)) return false
  const n = parseFloat(value)
  return value.endsWith('%') ? n <= 100 : /(?:em|rem|cm|mm|in)$/i.test(value) ? n <= 100 : n <= 2000
}

function safeStyle(source) {
  const accepted = []
  let rejected = false
  for (const part of String(source || '').split(';')) {
    const colon = part.indexOf(':')
    if (colon < 0) continue
    const name = part.slice(0, colon).trim().toLowerCase()
    const value = part.slice(colon + 1).trim()
    // No URL, CSS escape, variable, expression, or custom property may survive.
    if (!value || /[\\{}<>@]|url\s*\(|expression\s*\(|var\s*\(|!important/i.test(value)) { rejected = true; continue }
    let allowed = false
    if (['color', 'background-color'].includes(name)) allowed = COLOR.test(value)
    else if (name === 'font-family') allowed = /^[\p{L}\p{N}\s,'"-]{1,180}$/u.test(value)
    else if (name === 'font-size') allowed = safeLength(value) || /^(?:small|medium|large|smaller|larger)$/.test(value)
    else if (name === 'font-weight') allowed = /^(?:normal|bold|bolder|[1-9]00)$/.test(value)
    else if (name === 'font-style') allowed = /^(?:normal|italic|oblique)$/.test(value)
    else if (name === 'text-align') allowed = /^(?:left|right|center|justify|start|end)$/.test(value)
    else if (name === 'text-decoration') allowed = /^(?:none|underline|line-through)(?:\s+(?:underline|line-through))?$/.test(value)
    else if (name === 'vertical-align') allowed = /^(?:baseline|top|middle|bottom|super|sub|text-top|text-bottom)$/.test(value)
    else if (name === 'white-space') allowed = /^(?:normal|pre|pre-wrap|pre-line|break-spaces)$/.test(value)
    else if (name === 'line-height') allowed = /^(?:normal|[0-4](?:\.\d{1,3})?)$/.test(value) || safeLength(value)
    else if (/^(?:width|max-width|height|min-height|text-indent|letter-spacing|border-spacing|margin(?:-(?:top|right|bottom|left))?|padding(?:-(?:top|right|bottom|left))?)$/.test(name)) {
      const lengths = value.split(/\s+/)
      allowed = lengths.length <= 4 && lengths.every(safeLength)
    } else if (/^border(?:-(?:top|right|bottom|left))?$/.test(name)) {
      const match = value.match(/^(\d{1,2}(?:\.\d+)?(?:px|pt))\s+(solid|dashed|dotted|double)\s+(.+)$/i)
      allowed = !!match && parseFloat(match[1]) <= 10 && COLOR.test(match[3])
    } else if (name === 'border-collapse') allowed = /^(?:collapse|separate)$/.test(value)
    else if (name === 'table-layout') allowed = /^(?:auto|fixed)$/.test(value)
    if (allowed) accepted.push(`${name}: ${value}`)
    else rejected = true
  }
  return { value: accepted.join('; '), rejected }
}

function safeHref(value) {
  const href = String(value || '').trim()
  if (!href || /[\u0000-\u0020\u007f\\]/.test(href)) return null
  if (href.startsWith('#')) {
    try { return '#original-' + decodeURIComponent(href.slice(1)) } catch { return null }
  }
  if (!/^https?:\/\//i.test(href)) return null
  try {
    const url = new URL(href)
    return url.hostname && !url.username && !url.password ? url.href : null
  } catch { return null }
}

function safeImage(value) {
  // Raster data URIs only: SVG/data HTML and remote/local paths cannot trigger requests.
  const match = String(value || '').match(/^data:image\/(png|jpe?g|gif|bmp|webp);base64,([a-z0-9+/=\r\n]+)$/i)
  if (!match || value.length > 3 * 1024 * 1024) return false
  try {
    const bytes = globalThis.atob(match[2].replace(/[\r\n]/g, '').slice(0, 32))
    const mime = match[1].toLowerCase()
    if (mime === 'png') return bytes.startsWith('\x89PNG\r\n\x1a\n')
    if (mime.startsWith('jp')) return bytes.startsWith('\xff\xd8\xff')
    if (mime === 'gif') return /^GIF8[79]a/.test(bytes)
    if (mime === 'bmp') return bytes.startsWith('BM')
    return bytes.startsWith('RIFF') && bytes.slice(8, 12) === 'WEBP'
  } catch { return false }
}

/** Parse in an inert template, then reconstruct an allowlisted tree before v-html. */
export function sanitizeOriginalHtml(source, document = globalThis.document) {
  if (!document?.createElement) throw new Error('当前环境不支持文档预览')
  const template = document.createElement('template')
  template.innerHTML = String(source || '')
  const output = document.createElement('div')
  let blockedImages = 0
  let filtered = false

  function copy(node, parent, inMath = false) {
    if (node.nodeType === 3) {
      parent.appendChild(document.createTextNode(node.textContent))
      return
    }
    if (node.nodeType !== 1) return
    const tag = node.localName?.toLowerCase() || node.tagName.toLowerCase()
    if (DROP_TAGS.has(tag)) { filtered = true; return }
    const isMath = tag === 'math' || inMath
    if (!(isMath ? MATH_TAGS : HTML_TAGS).has(tag)) {
      filtered = true
      for (const child of Array.from(node.childNodes)) copy(child, parent, inMath)
      return
    }
    if (tag === 'img' && !safeImage(node.getAttribute('src'))) {
      blockedImages++
      const placeholder = document.createElement('span')
      placeholder.className = 'original-image-blocked'
      placeholder.textContent = `[图片未加载${node.getAttribute('alt') ? '：' + node.getAttribute('alt').slice(0, 200) : ''}]`
      parent.appendChild(placeholder)
      return
    }
    const element = isMath ? document.createElementNS(MATH_NS, tag) : document.createElement(tag)
    for (const attribute of Array.from(node.attributes)) {
      const name = attribute.name.toLowerCase()
      const value = attribute.value
      if (name === 'style' && !isMath) {
        const clean = safeStyle(value)
        if (clean.value) element.setAttribute('style', clean.value)
        if (clean.rejected) filtered = true
      } else if (name === 'class' && !isMath) {
        const names = value.split(/\s+/).filter(n => CLASSES.test(n))
        if (names.length) element.setAttribute('class', names.join(' '))
      } else if (name === 'id' && !isMath && value.length <= 512 && /^[\p{L}\p{N}_:.\-]+$/u.test(value)) {
        element.setAttribute('id', 'original-' + value)
      } else if (name === 'href' && tag === 'a') {
        const href = safeHref(value)
        if (href) {
          element.setAttribute('href', href)
          if (!href.startsWith('#')) {
            element.setAttribute('target', '_blank')
            element.setAttribute('rel', 'noopener noreferrer')
          }
        } else filtered = true
      } else if (name === 'src' && tag === 'img') element.setAttribute('src', value)
      else if (['title', 'alt'].includes(name) && !isMath) element.setAttribute(name, value.slice(0, 1000))
      else if (['colspan', 'rowspan', 'span'].includes(name) && ['td', 'th', 'col', 'colgroup'].includes(tag) && /^\d{1,3}$/.test(value) && Number(value) > 0) element.setAttribute(name, value)
      else if (['start', 'value'].includes(name) && ['ol', 'li'].includes(tag) && /^-?\d{1,6}$/.test(value)) element.setAttribute(name, value)
      else if (['width', 'height'].includes(name) && ['img', 'table', 'td', 'th', 'col'].includes(tag) && /^\d{1,4}(?:\.\d+)?%?$/.test(value) && parseFloat(value) <= (value.endsWith('%') ? 100 : 2000)) element.setAttribute(name, value)
      else if (name === 'align' && /^(?:left|right|center|justify)$/.test(value)) element.setAttribute(name, value)
      else if (isMath && name === 'xmlns' && tag === 'math') element.setAttribute('xmlns', MATH_NS)
      else if (isMath && name === 'encoding' && tag === 'annotation' && value === 'application/x-tex') element.setAttribute(name, value)
      else if (isMath && /^(?:display|mathvariant|stretchy|fence|separator|accent|accentunder|symmetric|largeop|movablelimits|columnalign|rowalign|columnlines|rowlines|notation)$/.test(name) && /^[a-z\s-]{1,100}$/i.test(value)) element.setAttribute(name, value)
      else if (isMath && /^(?:width|height|depth|lspace|rspace|voffset|linethickness|columnspacing|rowspacing|minsize|maxsize)$/.test(name) && /^-?[\d.]+(?:em|ex|px|pt|%)?(?:\s+-?[\d.]+(?:em|ex|px|pt|%)?)*$/.test(value) && value.length < 100) element.setAttribute(name, value)
      else if (isMath && name === 'mathcolor' && COLOR.test(value)) element.setAttribute(name, value)
      else filtered = true
    }
    if (tag === 'img') {
      element.setAttribute('loading', 'lazy')
      element.setAttribute('decoding', 'async')
    }
    parent.appendChild(element)
    for (const child of Array.from(node.childNodes)) copy(child, element, isMath)
  }
  for (const node of Array.from(template.content.childNodes)) copy(node, output)
  const warnings = []
  if (blockedImages) warnings.push(`${blockedImages} 张图片引用了外部地址、本地路径或不支持的格式，未自动加载。`)
  if (filtered) warnings.push('已过滤文档中的不安全标签、链接或样式。')
  return {
    html: output.innerHTML,
    warnings,
    hasContent: !!output.textContent.trim() || !!output.querySelector('img, math'),
  }
}

const md = new MarkdownIt({
  html: true, linkify: true, breaks: false,
  highlight(code, language) {
    if (language && hljs.getLanguage(language)) {
      try { return `<pre class="hljs"><code>${hljs.highlight(code, { language, ignoreIllegals: true }).value}</code></pre>` } catch { /* plain code fallback */ }
    }
    return `<pre class="hljs"><code>${md.utils.escapeHtml(code)}</code></pre>`
  },
}).use(mdAnchor).use(mdCallout)

function renderMath(tex, display) {
  if (tex.length > 12000) return `<code class="original-math-error">${md.utils.escapeHtml(tex)}</code>`
  try {
    return `<span class="original-math${display ? '-block' : ''}">${katex.renderToString(tex, {
      output: 'mathml', displayMode: display, throwOnError: true, trust: false,
      strict: 'ignore', maxExpand: 500, maxSize: 10,
    })}</span>`
  } catch {
    return `<code class="original-math-error" title="公式暂不能渲染，保留原始表达式">${md.utils.escapeHtml(tex)}</code>`
  }
}

function escapedAt(source, at) {
  let slashes = 0
  while (at > 0 && source[--at] === '\\') slashes++
  return slashes % 2 === 1
}

// Rules run at the token stage, so dollar signs inside fenced/inline code are untouched.
md.inline.ruler.before('escape', 'original_math', (state, silent) => {
  const pos = state.pos
  const dollar = state.src[pos] === '$' && state.src[pos + 1] !== '$'
  const paren = state.src.slice(pos, pos + 2) === '\\('
  if (!dollar && !paren) return false
  const start = pos + (dollar ? 1 : 2)
  if ((dollar && /\s/.test(state.src[start] || '')) || start >= state.posMax) return false
  const delimiter = dollar ? '$' : '\\)'
  let end = state.src.indexOf(delimiter, start)
  while (end >= 0 && escapedAt(state.src, end)) end = state.src.indexOf(delimiter, end + 1)
  if (end < 0 || end >= state.posMax || end === start || (dollar && (/\s/.test(state.src[end - 1]) || /\d/.test(state.src[end + 1] || '')))) return false
  if (!silent) {
    const token = state.push('original_math', '', 0)
    token.content = state.src.slice(start, end)
  }
  state.pos = end + delimiter.length
  return true
})
md.renderer.rules.original_math = (tokens, index) => renderMath(tokens[index].content, false)
md.block.ruler.before('fence', 'original_math_block', (state, startLine, endLine, silent) => {
  if (state.sCount[startLine] - state.blkIndent >= 4) return false
  const line = state.src.slice(state.bMarks[startLine] + state.tShift[startLine], state.eMarks[startLine])
  const opening = line.startsWith('$$') ? '$$' : line.startsWith('\\[') ? '\\[' : ''
  if (!opening) return false
  const closing = opening === '$$' ? '$$' : '\\]'
  let content = line.slice(2)
  let nextLine = startLine + 1
  const inlineClose = content.indexOf(closing)
  if (inlineClose >= 0) {
    if (content.slice(inlineClose + 2).trim()) return false
    content = content.slice(0, inlineClose)
  } else {
    let close = -1
    for (let n = nextLine; n < endLine; n++) {
      const part = state.src.slice(state.bMarks[n] + state.tShift[n], state.eMarks[n])
      const index = part.indexOf(closing)
      if (index >= 0 && !part.slice(index + 2).trim()) { content += '\n' + part.slice(0, index); close = n; break }
      content += '\n' + part
    }
    if (close < 0) return false
    nextLine = close + 1
  }
  if (silent) return true
  const token = state.push('original_math_block', '', 0)
  token.content = content.trim()
  token.block = true
  token.map = [startLine, nextLine]
  state.line = nextLine
  return true
}, { alt: ['paragraph', 'reference', 'blockquote', 'list'] })
md.renderer.rules.original_math_block = (tokens, index) => `<div class="original-math-block">${renderMath(tokens[index].content, true)}</div>\n`

export function renderOriginalPreview(payload, document = globalThis.document) {
  if (!['word', 'markdown'].includes(payload?.format)) throw new Error('此文件格式暂不支持原文预览，请下载原文件查看。')
  const source = payload.format === 'markdown' ? md.render(String(payload.source || '')) : String(payload.html || '')
  const result = sanitizeOriginalHtml(source, document)
  return {
    ...result, format: payload.format,
    warnings: [...new Set([...(Array.isArray(payload.warnings) ? payload.warnings.filter(w => typeof w === 'string') : []), ...result.warnings])],
  }
}

/** A file switch invalidates both fulfilled and failed older requests. */
export function createOriginalPreviewLoader({ load, render = renderOriginalPreview, onState }) {
  let generation = 0
  return {
    async open(id) {
      const current = ++generation
      onState({ loading: true, error: '', html: '', format: '', warnings: [], hasContent: false })
      try {
        const payload = await load(id)
        if (current !== generation) return
        onState({ ...render(payload), loading: false, error: '' })
      } catch (error) {
        if (current !== generation) return
        onState({ loading: false, error: error?.message || '原文加载失败，请稍后重试。', html: '', format: '', warnings: [], hasContent: false })
      }
    },
    dispose() { generation++ },
  }
}
