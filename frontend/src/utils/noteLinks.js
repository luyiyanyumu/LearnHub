import MarkdownIt from 'markdown-it'
import mdAnchor from './mdAnchor.js'

const headingParser = new MarkdownIt({ html: true }).use(mdAnchor)

/** Match the text the reader sees, without depending on browser DOM APIs. */
export function normalizeHeadingText(text) {
  return String(text ?? '').replace(/\s+/g, ' ').trim()
}

function htmlText(value) {
  return headingParser.utils.unescapeAll(String(value ?? '')
    .replace(/<!--[\s\S]*?-->/g, '')
    .replace(/<(script|style)\b[^>]*>[\s\S]*?<\/\1\s*>/gi, '')
    .replace(/<br\b[^>]*>/gi, ' ')
    .replace(/<[^>]*>/g, ''))
}

function inlineText(token) {
  if (!token?.children?.length) return htmlText(token?.content)
  return token.children.map(child => {
    if (['text', 'code_inline'].includes(child.type)) return child.content
    if (child.type === 'image') return inlineText(child)
    if (child.type === 'softbreak' || child.type === 'hardbreak') return ' '
    if (child.type === 'html_inline') return /^<br\b/i.test(child.content) ? ' ' : ''
    return child.children ? inlineText(child) : ''
  }).join('')
}

function sectionHash(text, occurrence) {
  return `#${new URLSearchParams({ section: text, occurrence: String(occurrence) })}`
}

/**
 * Include all six heading levels, HTML headings and setext headings; code fences
 * never become destinations. Text + duplicate ordinal survives unrelated edits.
 */
export function extractNoteHeadings(content) {
  const headings = [], occurrences = new Map()
  const append = (text, level, line, anchorId = null) => {
    text = normalizeHeadingText(text)
    if (!text) return
    const occurrence = (occurrences.get(text) || 0) + 1
    occurrences.set(text, occurrence)
    const hash = sectionHash(text, occurrence)
    headings.push({ text, level, occurrence, key: hash.slice(1), hash, line, anchorId })
  }
  const tokens = headingParser.parse(String(content ?? ''), {})
  for (let index = 0; index < tokens.length; index++) {
    const token = tokens[index]
    if (token.type === 'heading_open') {
      append(inlineText(tokens[index + 1]), Number(token.tag.slice(1)), token.map?.[0] ?? null, token.attrGet('id'))
    } else if (token.type === 'html_block') {
      const html = token.content
        .replace(/<!--[\s\S]*?-->/g, match => match.replace(/[^\n]/g, ' '))
        .replace(/<(script|style|pre|textarea|template)\b[^>]*>[\s\S]*?<\/\1\s*>/gi, match => match.replace(/[^\n]/g, ' '))
      // Attribute values can contain ">". Do not truncate the opening tag there.
      const pattern = /<h([1-6])\b((?:"[^"]*"|'[^']*'|[^'">])*)>([\s\S]*?)<\/h\1\s*>/gi
      for (const match of html.matchAll(pattern)) {
        const id = /(?:^|\s)id\s*=\s*(?:"([^"]*)"|'([^']*)'|([^\s"'>]+))/i.exec(match[2])
        const line = (token.map?.[0] ?? 0) + html.slice(0, match.index).split('\n').length - 1
        append(htmlText(match[3]), Number(match[1]), line, id ? headingParser.utils.unescapeAll(id[1] ?? id[2] ?? id[3]) : null)
      }
    }
  }
  return headings
}

function validNoteId(value) {
  const text = String(value ?? '')
  if (!/^[1-9]\d*$/.test(text)) return null
  const id = Number(text)
  return Number.isSafeInteger(id) ? id : null
}

export function createNoteLink(noteId, heading = null) {
  const id = validNoteId(noteId)
  if (!id) return ''
  if (!heading) return `/notes/${id}`
  const text = normalizeHeadingText(typeof heading === 'string' ? heading : heading.text)
  const occurrence = typeof heading === 'string' ? 1 : Number(heading.occurrence ?? 1)
  if (!text || !Number.isSafeInteger(occurrence) || occurrence < 1) return ''
  return `/notes/${id}${sectionHash(text, occurrence)}`
}

/** Existing #id / #block-… links keep their original identity. */
export function parseNoteLink(href, baseOrigin = globalThis.location?.origin) {
  if (typeof href !== 'string') return null
  let value = href.trim()
  if (/^https?:\/\//i.test(value)) {
    if (!baseOrigin) return null
    try {
      const absolute = new URL(value)
      if (absolute.origin !== baseOrigin) return null
      value = `${absolute.pathname}${absolute.search}${absolute.hash}`
    } catch { return null }
  }
  const match = /^\/notes\/([1-9]\d*)\/?(?:\?[^#]*)?(#[\s\S]*)?$/.exec(value)
  if (!match) return null
  const noteId = validNoteId(match[1])
  if (!noteId) return null
  const hash = match[2] || ''
  const parsed = { noteId, heading: null, occurrence: 1, hash, anchorId: null }
  if (!hash || hash === '#') return parsed
  if (hash.startsWith('#section=')) {
    // URLSearchParams tolerates broken %-encoding. Reject it before decoding so
    // a corrupt link cannot quietly match a different title.
    try { decodeURIComponent(hash.slice(1)) } catch { return null }
    const params = new URLSearchParams(hash.slice(1))
    const heading = normalizeHeadingText(params.get('section'))
    const ordinal = params.get('occurrence') ?? '1'
    if (!heading || params.getAll('section').length !== 1 || params.getAll('occurrence').length > 1 || !/^[1-9]\d*$/.test(ordinal)) return null
    const occurrence = Number(ordinal)
    if (!Number.isSafeInteger(occurrence)) return null
    return { ...parsed, heading, occurrence }
  }
  try { return { ...parsed, anchorId: decodeURIComponent(hash.slice(1)) } } catch { return null }
}

/** Works with extracted descriptors and with rendered heading elements. */
export function findLinkedHeadingIndex(headings, parsed) {
  if (!parsed) return -1
  const list = Array.from(headings ?? [])
  if (parsed.anchorId) {
    return list.findIndex(heading => (heading.anchorId || heading.id || heading.getAttribute?.('id')) === parsed.anchorId)
  }
  const wanted = normalizeHeadingText(parsed.heading)
  if (!wanted) return -1
  const occurrence = parsed.occurrence ?? 1
  let count = 0
  return list.findIndex(heading => {
    const text = normalizeHeadingText(heading.text ?? heading.textContent)
    if (text !== wanted) return false
    count++
    return count === occurrence
  })
}

/** Link targets are data. Never allow executable schemes or backslash URLs. */
export function validateLinkHref(href) {
  if (typeof href !== 'string') return false
  const value = href.trim()
  if (!value || /[\u0000-\u0020\u007f-\u009f\\]/.test(value) || value.startsWith('//')) return false
  const scheme = /^([a-z][a-z\d+.-]*):/i.exec(value)
  if (scheme) {
    if (!['http', 'https', 'mailto'].includes(scheme[1].toLowerCase())) return false
    try {
      const url = new URL(value)
      return url.protocol === 'mailto:' ? Boolean(url.pathname) : Boolean(url.hostname)
    } catch { return false }
  }
  return !/^[^/?#]*:/.test(value)
}

/** The caller can insert this into Markdown without syntax or HTML injection. */
export function createMarkdownLink(text, href) {
  if (!validateLinkHref(href)) return ''
  const target = href.trim().replace(/[<>]/g, character => character === '<' ? '%3C' : '%3E')
  const label = String(text || href.trim()).replace(/[\r\n]+/g, ' ').replace(/[\\`*_[\]<>]/g, '\\$&')
  return `[${label}](<${target}>)`
}
