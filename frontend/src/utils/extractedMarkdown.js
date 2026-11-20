import MarkdownIt from 'markdown-it'
import mdMath from './mdMath.js'
import mdAnchor from './mdAnchor.js'
import footnote from 'markdown-it-footnote'
import { renderExtractedMath } from './extractedMath.js'

const SHA = /^[a-f\d]{64}$/i
const ASSET = /^assets\/([a-f\d]{64})\.png$/i
const ANCHOR = /^(?:word-bookmark-[A-Za-z0-9_.:-]{1,100}|source-page-[1-9]\d*)$/

function dimension(value) {
  if (typeof value !== 'number' && (typeof value !== 'string' || !/^(?:\d+(?:\.\d{1,6})?|\.\d{1,6})$/.test(value))) return null
  const number = Number(value)
  return Number.isFinite(number) && number >= .1 && number <= 4096 ? Math.round(number * 1000) / 1000 : null
}

export function extractedImageDimensions(value = {}) {
  return { width: dimension(value.widthPx ?? value.width), height: dimension(value.heightPx ?? value.height) }
}

export function verifiedSourcePage(page, artifact = {}) {
  const number = Number(page)
  if (!Number.isSafeInteger(number) || number < 1 || !SHA.test(String(artifact.sourceSha256))) return false
  const found = Array.isArray(artifact.pages) ? artifact.pages.find(item => Number(item.page) === number) : null
  return !!found && ['pt', 'px'].includes(found.coordinateUnit)
    && (!artifact.report?.totalPages || number <= artifact.report.totalPages)
}

/** Only files declared by this artifact's manifest may become browser image requests. */
export function extractedAssetUrl(source, { fileId, sourceSha256, assets = [] } = {}) {
  const match = typeof source === 'string' && source.match(ASSET)
  if (!match || !/^[1-9]\d*$/.test(String(fileId)) || !SHA.test(String(sourceSha256))) return ''
  const hash = match[1].toLowerCase()
  const asset = (Array.isArray(assets) ? assets : []).find(item => {
    const name = String(item?.name || '').replace(/^assets\//, '')
    return name.toLowerCase() === `${hash}.png` && item.mediaType === 'image/png'
      && String(item.sha256).toLowerCase() === hash && Number(item.bytes) > 0
  })
  return asset ? `/api/files/${fileId}/extracted-assets/${hash}.png?sourceRevision=${String(sourceSha256).toLowerCase()}` : ''
}

export function extractedBlockAssetUrl(block, artifact) {
  const direct = extractedAssetUrl(block?.src, artifact)
  if (direct) return direct
  const source = String(block?.markdown || '').match(/!\[[^\]]*\]\((assets\/[a-f\d]{64}\.png)\)/i)?.[1]
  if (source) return extractedAssetUrl(source, artifact)
  const html = String(block?.markdown || '').match(/<img\b[^>]*>/i)?.[0]
  const src = html?.match(/\bsrc\s*=\s*["'](assets\/[a-f\d]{64}\.png)["']/i)?.[1]
  return src ? extractedAssetUrl(src, artifact) : ''
}

function safeLink(value) {
  const source = String(value || '')
  if (!source || /[\u0000-\u0020\u007f\\]/.test(source)) return ''
  if (source.startsWith('#')) return source
  try {
    const url = new URL(source)
    return ['http:', 'https:'].includes(url.protocol) && !url.username && !url.password ? url.href : ''
  } catch { return '' }
}

const md = new MarkdownIt({ html: false, linkify: false, breaks: false }).use(mdMath).use(mdAnchor).use(footnote)
function math(tokens, index, options, env, display, inline = false) {
  env.mathCount = (env.mathCount || 0) + 1
  const source = tokens[index].content
  const result = env.mathCount <= 1000 ? renderExtractedMath(source, { display }) : { ok: false, error: '本篇公式较多，剩余公式保留源码。' }
  if (!result.ok) {
    env.errors.add(result.error)
    return `<code class="structured-math-error">${md.utils.escapeHtml(source)}</code>`
  }
  const tag = display && !inline ? 'div' : 'span'
  return `<${tag} class="structured-math${display ? '-block' : '-inline'}">${result.html}</${tag}>`
}
md.renderer.rules.math_inline = (tokens, index, options, env) => math(tokens, index, options, env, false)
md.renderer.rules.math_block = (tokens, index, options, env) => math(tokens, index, options, env, true)
md.inline.ruler.before('math_inline', 'structured_display_math', (state, silent) => {
  const opening = state.src.slice(state.pos, state.pos + 2)
  if (!['$$', '\\['].includes(opening)) return false
  const closing = opening === '$$' ? '$$' : '\\]'
  const start = state.pos + 2
  const end = state.src.indexOf(closing, start)
  if (end <= start || end >= state.posMax) return false
  if (!silent) {
    const token = state.push('structured_display_math', '', 0)
    token.content = state.src.slice(start, end)
  }
  state.pos = end + 2
  return true
})
md.renderer.rules.structured_display_math = (tokens, index, options, env) => math(tokens, index, options, env, true, true)

const TABLE_TAGS = new Set('table thead tbody tfoot tr td th caption colgroup col'.split(' '))
const INLINE_TAGS = new Set(['img', 'br', 'sup', 'sub', 'a'])

function renderImage(source, alt, sizes, env) {
  const url = extractedAssetUrl(source, env.artifact)
  const label = md.utils.escapeHtml(alt || '原文图片')
  if (!url) { env.blockedImages++; return `<span class="structured-image-blocked">[图片未加载：${label}]</span>` }
  const { width, height } = extractedImageDimensions(sizes)
  return `<img src="${md.utils.escapeHtml(url)}" alt="${label}"${width ? ` width="${width}"` : ''}${height ? ` height="${height}"` : ''} loading="lazy" decoding="async">`
}

function prepareHtml(source, env, allowTable = false) {
  if (!env.document?.createElement) return null
  const template = env.document.createElement('template')
  template.innerHTML = source
  const anchors = []
  function prepare(node) {
    if (node.nodeType === 3) {
      const tokens = []
      const previous = env.disableStructuredHtml
      env.disableStructuredHtml = true
      try { md.inline.parse(node.textContent || '', md, env, tokens) }
      finally { env.disableStructuredHtml = previous }
      return { tokens }
    }
    if (node.nodeType !== 1) return { text: '' }
    const tag = node.tagName.toLowerCase()
    if (!(INLINE_TAGS.has(tag) || allowTable && TABLE_TAGS.has(tag))) return { text: md.utils.escapeHtml(node.outerHTML || node.textContent || '') }
    let attributes = ''
    if (tag === 'td' || tag === 'th') {
      for (const key of ['rowspan', 'colspan']) {
        const value = node.getAttribute(key)
        if (/^[1-9]\d{0,2}$/.test(value || '')) attributes += ` ${key}="${value}"`
      }
    }
    if (tag === 'img') return { image: { src: node.getAttribute('src'), alt: node.getAttribute('alt'), width: node.getAttribute('width'), height: node.getAttribute('height') } }
    if (tag === 'br' || tag === 'col') return { text: `<${tag}>` }
    if (tag === 'a') {
      const id = node.getAttribute('id') || ''
      const validId = ANCHOR.test(id) ? id : ''
      if (validId) anchors.push(validId)
      const href = node.getAttribute('href') || ''
      let fragment = ''
      try { if (href.startsWith('#') && ANCHOR.test(decodeURIComponent(href.slice(1)))) fragment = href } catch { /* keep text */ }
      return { anchor: validId, href: fragment, children: Array.from(node.childNodes).map(prepare) }
    }
    return { tag, attributes, children: Array.from(node.childNodes).map(prepare) }
  }
  return { nodes: Array.from(template.content?.childNodes || template.childNodes).map(prepare), anchors }
}

function renderHtml(tree, env) {
  if (!tree) return ''
  function render(node) {
    if (node.text !== undefined) return node.text
    if (node.tokens) return md.renderer.renderInline(node.tokens, md.options, env)
    if (node.image) return renderImage(node.image.src, node.image.alt, node.image, env)
    const children = node.children.map(render).join('')
    if (node.anchor !== undefined) {
      const anchor = node.anchor ? `<a id="${md.utils.escapeHtml(node.anchor)}" tabindex="-1"></a>` : ''
      return anchor + (node.href ? `<a href="${md.utils.escapeHtml(node.href)}">${children}</a>` : children)
    }
    return `<${node.tag}${node.attributes}>${children}</${node.tag}>`
  }
  return tree.nodes.map(render).join('')
}

// Tokenize only this small HTML vocabulary; quoted attributes cannot terminate a tag early.
function htmlTagAt(source, start) {
  const match = source.slice(start).match(/^<(\/?)\s*(img|br|sup|sub|a)(?=[\s/>])/i)
  if (!match) return null
  let quote = ''
  for (let index = start + match[0].length; index < Math.min(source.length, start + 8192); index++) {
    const char = source[index]
    if (quote) { if (char === quote) quote = '' }
    else if (char === '"' || char === "'") quote = char
    else if (char === '>') return { tag: match[2].toLowerCase(), closing: !!match[1], end: index + 1 }
  }
  return null
}

md.inline.ruler.before('html_inline', 'structured_inline_html', (state, silent) => {
  if (state.env.disableStructuredHtml || state.src[state.pos] !== '<') return false
  const opening = htmlTagAt(state.src, state.pos)
  if (!opening || opening.closing) return false
  let end = opening.end
  if (!['img', 'br'].includes(opening.tag)) {
    let depth = 1, position = end
    while (position < state.posMax && position - state.pos < 50000) {
      const next = state.src.indexOf('<', position)
      if (next < 0) return false
      const tag = htmlTagAt(state.src, next)
      if (!tag) { position = next + 1; continue }
      if (tag.tag === opening.tag) depth += tag.closing ? -1 : 1
      position = tag.end
      if (!depth) { end = tag.end; break }
    }
    if (depth) return false
  }
  if (end > state.posMax) return false
  if (!silent) {
    const token = state.push('structured_inline_html', '', 0)
    token.content = state.src.slice(state.pos, end)
    token.meta = prepareHtml(token.content, state.env)
  }
  state.pos = end
  return true
})
md.renderer.rules.structured_inline_html = (tokens, index, options, env) => tokens[index].meta
  ? renderHtml(tokens[index].meta, env) : md.utils.escapeHtml(tokens[index].content)

md.block.ruler.before('html_block', 'structured_table', (state, startLine, endLine, silent) => {
  if (state.sCount[startLine] - state.blkIndent >= 4) return false
  const start = state.bMarks[startLine] + state.tShift[startLine]
  if (!/^<table(?:\s|>)/i.test(state.src.slice(start, state.eMarks[startLine]))) return false
  const source = state.src.slice(start, Math.min(state.bMarks[endLine] ?? state.src.length, start + 500001))
  let depth = 0
  for (const match of source.matchAll(/<!--[\s\S]*?-->|<(\/?)table\b(?:[^"'<>]|"[^"]*"|'[^']*')*>/gi)) {
    if (match[0].startsWith('<!--')) continue
    depth += match[1] ? -1 : 1
    if (depth < 0 || depth > 20) return false
    if (depth !== 0) continue
    const close = start + match.index + match[0].length
    let line = startLine
    while (line < endLine && state.eMarks[line] < close) line++
    if (line >= endLine || state.src.slice(close, state.eMarks[line]).trim()) return false
    if (silent) return true
    const token = state.push('structured_table', '', 0)
    token.block = true
    token.content = state.src.slice(start, close)
    token.map = [startLine, line + 1]
    state.line = line + 1
    return true
  }
  return false
}, { alt: ['paragraph'] })
// Preserve normal inline ordering so the mature footnote plugin sees table references too.
md.core.ruler.at('inline', state => {
  for (const token of state.tokens) {
    if (token.type === 'inline') {
      token.children = []
      state.md.inline.parse(token.content, state.md, state.env, token.children)
    }
    else if (token.type === 'structured_table') token.meta = prepareHtml(token.content, state.env, true)
  }
})
md.renderer.rules.structured_table = (tokens, index, options, env) => tokens[index].meta
  ? `<div class="structured-table-scroll">${renderHtml(tokens[index].meta, env)}</div>` : md.utils.escapeHtml(tokens[index].content)
md.renderer.rules.image = (tokens, index, options, env) => {
  const token = tokens[index]
  return renderImage(token.attrGet('src'), token.content, {}, env)
}
md.renderer.rules.link_open = (tokens, index, options, env) => {
  const href = safeLink(tokens[index].attrGet('href'))
  env.links.push(!!href)
  return href ? `<a href="${md.utils.escapeHtml(href)}"${href.startsWith('#') ? '' : ' target="_blank" rel="noopener noreferrer"'}>` : '<span class="structured-link-blocked">'
}
md.renderer.rules.link_close = (tokens, index, options, env) => env.links.pop() ? '</a>' : '</span>'

function footnoteKey(tokens, index, env, reference = false) {
  const meta = tokens[index].meta
  return `${env.anchorScope}-${Number(meta.id) + 1}${reference && meta.subId > 0 ? `-${meta.subId}` : ''}`
}
md.renderer.rules.footnote_ref = (tokens, index, options, env) => {
  const number = Number(tokens[index].meta.id) + 1
  return `<sup class="footnote-ref"><a href="#lh-fn-${footnoteKey(tokens, index, env)}" id="lh-fnref-${footnoteKey(tokens, index, env, true)}" aria-label="脚注 ${number}">[${number}]</a></sup>`
}
md.renderer.rules.footnote_open = (tokens, index, options, env) => `<li id="lh-fn-${footnoteKey(tokens, index, env)}" class="footnote-item" tabindex="-1">`
md.renderer.rules.footnote_anchor = (tokens, index, options, env) => ` <a href="#lh-fnref-${footnoteKey(tokens, index, env, true)}" class="footnote-backref" aria-label="返回脚注引用">↩</a>`

md.core.ruler.after('inline', 'structured_source_pages', state => {
  for (let index = 3; index < state.tokens.length - 1; index++) {
    const heading = state.tokens[index], inline = state.tokens[index + 1]
    if (heading.type !== 'heading_open' || heading.tag !== 'h6' || inline.type !== 'inline') continue
    const previous = state.tokens[index - 2]
    const anchor = previous?.type === 'inline' && previous.children?.flatMap(token => token.type === 'structured_inline_html' ? token.meta?.anchors || [] : []).find(id => /^source-page-[1-9]\d*$/.test(id))
    const page = anchor ? anchor.slice('source-page-'.length) : ''
    if (!page || !verifiedSourcePage(page, state.env.artifact)) continue
    heading.attrJoin('class', 'structured-page-marker')
    const open = new state.Token('structured_page_button_open', '', 1)
    open.meta = { page, revision: state.env.artifact.sourceSha256 }
    inline.children.unshift(open)
    inline.children.push(new state.Token('structured_page_button_close', '', -1))
  }
})
md.renderer.rules.structured_page_button_open = (tokens, index) => `<button type="button" class="structured-page-button" data-source-page="${tokens[index].meta.page}" data-source-revision="${md.utils.escapeHtml(tokens[index].meta.revision)}">`
md.renderer.rules.structured_page_button_close = () => '</button>'

function reviewLabel(block) {
  if (block.latexStatus === 'needs_review' || block.quality === 'uncertain_formula'
      || block.type === 'formula' && block.indexed !== true
      && !['empty', 'page_furniture', 'superseded_by_formula'].includes(block.quality)) return '公式转写'
  return ({ low_confidence: '识别置信度较低', garbled: '可能含乱码', rejected: '识别质量未通过',
    vision_uncertain: '图像识别结果', local_scan_review: '图片扫描结果', local_scan_failed: '图片扫描失败',
    vision_unavailable: '图片尚未识别', pending: '尚未处理', ocr_required: '尚未扫描',
    character_limit: '正文未完整处理', failed: '识别失败' })[block.quality]
    || (['partial', 'failed', 'error'].includes(block.latexStatus) ? '识别结果' : '')
    || (block.indexed === false && block.type !== 'page_break'
      && !['accepted', 'legacy', 'recognized', 'empty', 'non_text', 'page_furniture', 'superseded_by_formula'].includes(block.quality) ? '识别结果' : '')
}

// Bind annotations to exact saved UTF-16 ranges, never guessed text matches. Hidden
// Office image annotations can point to the visible image in the same artifact.
function reviewRanges(source, artifact) {
  if (!SHA.test(String(artifact.sourceSha256))) return []
  const blocks = (Array.isArray(artifact.pages) ? artifact.pages : []).flatMap(page =>
    (Array.isArray(page.blocks) ? page.blocks : []).map(block => ({ ...block, page: block.page || page.page })))
  const valid = block => Number.isInteger(block.markdownStart) && Number.isInteger(block.markdownEnd)
    && block.markdownStart >= 0 && block.markdownEnd > block.markdownStart && block.markdownEnd <= source.length
    && typeof block.markdown === 'string' && source.slice(block.markdownStart, block.markdownEnd) === block.markdown
  const visible = blocks.filter(valid)
  const imageRanges = new Map()
  for (const block of visible) {
    for (const match of block.markdown.matchAll(/assets\/[a-f\d]{64}\.png/gi)) {
      const key = `${block.page}:${match[0].toLowerCase()}`
      if (!imageRanges.has(key)) imageRanges.set(key, block)
    }
  }
  return blocks.flatMap(block => {
    const label = reviewLabel(block)
    if (!label || typeof block.id !== 'string' || !block.id || block.id.length > 250) return []
    const target = valid(block) ? block : ASSET.test(block.src || '') ? imageRanges.get(`${block.page}:${block.src.toLowerCase()}`) : null
    return target ? [{ start: target.markdownStart, end: target.markdownEnd, blockId: block.id, page: block.page, label }] : []
  }).sort((a, b) => a.start - b.start)
}

md.core.ruler.after('structured_source_pages', 'structured_review_marks', state => {
  if (state.inlineMode) return
  const ranges = reviewRanges(state.src, state.env.artifact)
  if (!ranges.length) return
  const lines = [0]
  for (let index = 0; index < state.src.length; index++) if (state.src[index] === '\n') lines.push(index + 1)
  const roots = state.tokens.map((token, index) => ({ token, index }))
    .filter(({ token }) => token.level === 0 && token.map && token.nesting !== -1)
  const insertions = new Map()
  let cursor = 0
  for (const range of ranges) {
    while (cursor < roots.length && (lines[roots[cursor].token.map[1]] ?? state.src.length) <= range.start) cursor++
    const root = roots[cursor]
    if (!root || lines[root.token.map[0]] >= range.end) continue
    if (!insertions.has(root.index)) insertions.set(root.index, [])
    insertions.get(root.index).push(range)
    if (root.token.nesting === 1) root.token.attrJoin('class', 'structured-needs-review')
    state.env.reviewItems.push({ blockId: range.blockId, page: range.page, label: range.label })
  }
  state.tokens = state.tokens.flatMap((token, index) => {
    const reviews = insertions.get(index)
    if (!reviews) return [token]
    const marker = new state.Token('structured_review_notice', '', 0)
    marker.meta = reviews
    return [marker, token]
  })
})
md.renderer.rules.structured_review_notice = (tokens, index, options, env) => {
  const escape = md.utils.escapeHtml
  return `<div class="structured-review-notice" data-translation-ignore>${tokens[index].meta.map(item =>
    `<button type="button" class="structured-review-button" data-review-block="${escape(item.blockId)}" data-review-page="${escape(String(item.page))}" data-source-revision="${escape(env.artifact.sourceSha256)}">待核对：${escape(item.label)}</button>`).join('')}</div>\n`
}

export function renderExtractedMarkdown(markdown, artifact = {}, document = globalThis.document, { inline = false } = {}) {
  const scope = /^[A-Za-z0-9_-]{1,100}$/.test(String(artifact.renderScope || '')) ? artifact.renderScope : String(artifact.fileId || 'document').replace(/[^\w-]/g, '') || 'document'
  const env = { artifact, document, errors: new Set(), blockedImages: 0, links: [], mathCount: 0, anchorScope: scope, reviewItems: [] }
  const html = inline ? md.renderInline(String(markdown ?? ''), env) : md.render(String(markdown ?? ''), env)
  return { html, errors: [...env.errors], blockedImages: env.blockedImages, reviewCount: env.reviewItems.length, reviewItems: env.reviewItems }
}
