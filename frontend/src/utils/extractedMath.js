import MarkdownIt from 'markdown-it'
import katex from 'katex'

export const MAX_EXTRACTED_LATEX_CHARS = 12000
const MAX_MATH_PER_BLOCK = 100
const md = new MarkdownIt({ html: false, linkify: false, breaks: true })
const UNSAFE_TEX = /\\(?:href|url|includegraphics|htmlClass|htmlId|htmlStyle|htmlData|class|style)\b/

// Extracted Markdown image references must not trigger requests to remote/local paths.
md.renderer.rules.image = (tokens, index) =>
  `<span class="extracted-image-placeholder">[图片${tokens[index].content ? '：' + md.utils.escapeHtml(tokens[index].content) : ''}，请查看原文]</span>`

export function normalizeExtractedLatex(value) {
  const source = String(value ?? '').trim()
  for (const [open, close] of [['$$', '$$'], ['\\[', '\\]'], ['\\(', '\\)'], ['$', '$']]) {
    if (source.startsWith(open) && source.endsWith(close) && source.length > open.length + close.length) {
      return source.slice(open.length, -close.length).trim()
    }
  }
  return source
}

/** Only generated KaTeX MathML reaches v-html; uploaded HTML is never trusted. */
export function renderExtractedMath(value, { display = true } = {}) {
  const latex = normalizeExtractedLatex(value)
  if (!latex) return { latex, html: '', ok: false, error: '还没有可预览的 LaTeX。' }
  if (latex.length > MAX_EXTRACTED_LATEX_CHARS) return { latex, html: '', ok: false, error: '公式过长，请缩短后预览。' }
  if (UNSAFE_TEX.test(latex)) return { latex, html: '', ok: false, error: '公式含链接或 HTML 指令，已停用这些指令。' }
  try {
    return {
      latex, ok: true, error: '',
      html: katex.renderToString(latex, {
        output: 'mathml', displayMode: display, throwOnError: true, trust: false,
        strict: 'error', maxExpand: 500, maxSize: 10,
      }),
    }
  } catch {
    return { latex, html: '', ok: false, error: 'LaTeX 语法暂不能渲染，请修正源码并核对原文。' }
  }
}

function escapedAt(source, at) {
  let slashes = 0
  while (at > 0 && source[--at] === '\\') slashes++
  return slashes % 2 === 1
}

// Single dollars are deliberately conservative: $5 and $10 are prices, not math.
function plausibleDollarMath(source) {
  if (!source || /^\s|\s$/.test(source) || /\n/.test(source)) return false
  if (/^[,.;:!?\])}]/.test(source)) return false
  if (/^[\d,.\s]+$/.test(source)) return false
  if (/^[\d,.]+\s+\p{L}{2,}\b/u.test(source)) return false
  if (/^[\d,.]+(?:元|美元|人民币|USD|EUR|GBP|JPY|CNY|RMB)$/i.test(source)) return false
  return /[\\^_{}=+*/<>]/.test(source) || /^[\p{L}\p{N}]+$/u.test(source)
    || /^[\p{L}\p{N}]+\s*-\s*[\p{L}\p{N}]+$/u.test(source)
}

function mathHtml(latex, display, env) {
  env.extractedMathCount = (env.extractedMathCount || 0) + 1
  const result = env.extractedMathCount > MAX_MATH_PER_BLOCK
    ? { ok: false, error: '本段公式过多，已保留剩余源码。' }
    : renderExtractedMath(latex, { display })
  if (!result.ok) {
    env.extractedMathErrors ||= []
    env.extractedMathErrors.push(result.error)
    return `<code class="extracted-math-error" title="${md.utils.escapeHtml(result.error)}">${md.utils.escapeHtml(latex)}</code>`
  }
  return `<span class="extracted-math${display ? '-display' : '-inline'}">${result.html}</span>`
}

// Markdown's token rules keep formulas inside backticks/fenced code as literal text.
md.inline.ruler.before('escape', 'extracted_math', (state, silent) => {
  const pos = state.pos
  const source = state.src
  const paren = source.slice(pos, pos + 2) === '\\('
  const bracket = source.slice(pos, pos + 2) === '\\['
  const doubled = source.slice(pos, pos + 2) === '$$'
  const dollar = source[pos] === '$' && !doubled
  if (!paren && !bracket && !doubled && !dollar) return false
  const openingLength = dollar ? 1 : 2
  const start = pos + openingLength
  const closing = paren ? '\\)' : bracket ? '\\]' : doubled ? '$$' : '$'
  let end = source.indexOf(closing, start)
  while (end >= 0 && escapedAt(source, end)) end = source.indexOf(closing, end + closing.length)
  if (end < 0 || end >= state.posMax || end === start) return false
  const content = source.slice(start, end)
  if (dollar && (source[end + 1] === '$' || /\d/.test(source[end + 1] || '') || !plausibleDollarMath(content))) return false
  if (!silent) {
    const token = state.push('extracted_math', '', 0)
    token.content = content
    token.meta = { display: bracket || doubled }
  }
  state.pos = end + closing.length
  return true
})
md.renderer.rules.extracted_math = (tokens, index, options, env) =>
  mathHtml(tokens[index].content, tokens[index].meta.display, env)

md.block.ruler.before('fence', 'extracted_math_block', (state, startLine, endLine, silent) => {
  if (state.sCount[startLine] - state.blkIndent >= 4) return false
  const line = state.src.slice(state.bMarks[startLine] + state.tShift[startLine], state.eMarks[startLine])
  const opening = line.startsWith('$$') ? '$$' : line.startsWith('\\[') ? '\\[' : ''
  if (!opening) return false
  const closing = opening === '$$' ? '$$' : '\\]'
  let content = line.slice(2)
  let nextLine = startLine + 1
  const sameLineEnd = content.indexOf(closing)
  if (sameLineEnd >= 0) {
    if (content.slice(sameLineEnd + 2).trim()) return false
    content = content.slice(0, sameLineEnd)
  } else {
    let closed = false
    for (let n = nextLine; n < endLine; n++) {
      const part = state.src.slice(state.bMarks[n] + state.tShift[n], state.eMarks[n])
      // An unfinished formula cannot swallow the next paragraph or code fence.
      if (!part.trim() || /^\s*(?:`{3,}|~{3,})/.test(part)) return false
      const at = part.indexOf(closing)
      if (at >= 0 && !escapedAt(part, at) && !part.slice(at + 2).trim()) {
        content += '\n' + part.slice(0, at)
        nextLine = n + 1
        closed = true
        break
      }
      content += '\n' + part
      if (content.length > MAX_EXTRACTED_LATEX_CHARS) return false
    }
    if (!closed) return false
  }
  if (!content.trim()) return false
  if (silent) return true
  const token = state.push('extracted_math_block', '', 0)
  token.content = content.trim()
  token.block = true
  token.map = [startLine, nextLine]
  state.line = nextLine
  return true
}, { alt: ['paragraph', 'reference', 'blockquote', 'list'] })
md.renderer.rules.extracted_math_block = (tokens, index, options, env) =>
  `<div class="extracted-math-block">${mathHtml(tokens[index].content, true, env)}</div>\n`

/** Safe Markdown with bounded math. Code and plain dollars retain their meaning. */
export function renderExtractedText(value, { inline = false } = {}) {
  const env = { extractedMathErrors: [], extractedMathCount: 0 }
  const source = String(value ?? '')
  const html = inline ? md.renderInline(source, env) : md.render(source, env)
  return { html, errors: [...new Set(env.extractedMathErrors)], mathCount: env.extractedMathCount }
}

export function extractedFormulaLatex(block, correction) {
  return normalizeExtractedLatex(typeof correction === 'string' ? correction : block?.latex)
}

/** A renderable geometric candidate is still uncertain; show the PDF crop first. */
export function extractedFormulaState(block, source, { baseline, recognition, hasImage = false } = {}) {
  const rendered = renderExtractedMath(source)
  const original = baseline === undefined ? extractedFormulaLatex(block) : normalizeExtractedLatex(baseline)
  const edited = rendered.latex !== original
  const status = recognition?.status || block?.latexStatus || 'unavailable'
  const visual = !!recognition || status === 'recognized' || /视觉模型/.test(block?.latexMessage || '')
  const mainPreview = rendered.ok && (edited || !hasImage || status === 'restored' || status === 'recognized')
  return {
    rendered, edited, status, visual, mainPreview,
    candidatePreview: rendered.ok && !mainPreview,
    originalOpen: hasImage && !mainPreview,
    needsReview: !rendered.ok || (!edited && status !== 'restored'),
  }
}

/** A model response is adopted only after safe, complete KaTeX parsing succeeds. */
export function validateFormulaRecognition(result) {
  if (!result || !['recognized', 'partial'].includes(result.status) || typeof result.latex !== 'string') {
    return { ok: false, error: '识别结果状态无效，已保留之前的源码与原图。' }
  }
  const rendered = renderExtractedMath(result.latex)
  if (!rendered.ok) {
    return { ok: false, error: `识别结果不能安全预览，已保留之前的源码与原图。${rendered.error}` }
  }
  return { ...rendered, status: result.status }
}

/** Copies the corrected formula source, never the fragmented PDF glyph text. */
export function exportExtractedText(pages, corrections = {}) {
  return (pages || []).map(page => (page.blocks || []).map((block, index) => {
    if (block.type !== 'formula') return block.text || ''
    const key = `${page.page}:${index}`
    const latex = extractedFormulaLatex(block, corrections[key])
    return latex ? `$$\n${latex}\n$$` : block.text || ''
  }).filter(Boolean).join('\n')).join('\n\n')
}
