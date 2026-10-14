import { Fragment, Slice } from '@tiptap/pm/model'
import { closeHistory } from '@tiptap/pm/history'
import { codeBlockDocument } from './codeBlockViewState.js'
import { hasRichCodeLanguage, isClosedMermaidFence } from './mermaidPaste.js'
import { pasteMarkdown as markdown, markdownInlineTokens, markdownBoldRanges } from './markdownBold.js'

function hasRichMath(html) {
  for (const tag of String(html || '').matchAll(/<(?:span|div|annotation)\b([^>]*)>/gi)) {
    if (/\bdata-tex\s*=/i.test(tag[1])) return true
    for (const attribute of tag[1].matchAll(/\b(class|encoding)\s*=\s*(?:"([^"]*)"|'([^']*)'|([^\s"'=<>`]+))/gi)) {
      const value = attribute[2] ?? attribute[3] ?? attribute[4] ?? ''
      if (attribute[1].toLowerCase() === 'encoding' && value.toLowerCase() === 'application/x-tex') return true
      if (attribute[1].toLowerCase() === 'class' && /(?:^|\s)(?:katex(?:-display)?|math-inline|math-block|md-editor-katex-(?:inline|block))(?:\s|$)/i.test(value)) return true
    }
  }
  return false
}

function hasRichFormatting(html) {
  const source = String(html || '')
  if (/<(?:strong|b|em|i|u|s|del|mark|sub|sup|table|thead|tbody|tr|th|td|ul|ol|li|h[1-6]|blockquote|a|img|hr)\b/i.test(source)) return true
  // Explicit Markdown/plain code wrappers are a source-text clipboard format.
  // Unlabelled code, and real language-bearing code, keep rich HTML semantics.
  if (/<(?:pre|code)\b/i.test(source)
    && !/\blanguage-(?:markdown|md|plaintext|plain|text|txt)(?:\s|["'])/i.test(source)) return true
  for (const tag of source.matchAll(/<(?:p|div|span|font)\b([^>]*)>/gi)) {
    if (/\b(?:color|face|size)\s*=/i.test(tag[1])) return true
    for (const style of tag[1].matchAll(/\bstyle\s*=\s*(?:"([^"]*)"|'([^']*)')/gi)) {
      for (const declaration of (style[1] ?? style[2]).split(';')) {
        const [property, value = ''] = declaration.split(':')
        const key = property.trim().toLowerCase()
        const val = value.trim().toLowerCase()
        if (!val || /^(?:normal|inherit|initial|unset|400|none)$/.test(val)) continue
        if (/^(?:font-weight|font-style|text-decoration(?:-line)?|color|background(?:-color)?|font-family|font-size|text-align)$/.test(key)) return true
      }
    }
  }
  return false
}

function isCurrency(token) {
  if (token.markup !== '$') return false
  // Numeric dollar pairs and explicit currency units are ambiguous. The
  // unambiguous LaTeX \(…\) delimiter remains available for numeric formulas.
  return /^[\d,.\s]+$/.test(token.content)
    || /^[\d,.]+\s*(?:元|美元|人民币|USD|EUR|GBP|JPY|CNY|RMB)$/i.test(token.content)
    || /^[\d,.]+\s+[A-Za-z]{2,}(?:\s+[A-Za-z]+)*$/.test(token.content)
}

/** Extract a display formula only when its delimiters occupy complete lines. */
function blockTex(token, lines) {
  if (token.level !== 0) return null
  const [start, end] = token.map
  const raw = lines.slice(start, end).join('\n')
  const opening = /^\s*(\$\$|\\\[)/.exec(raw)
  if (!opening) return null
  const closer = opening[1] === '$$' ? '$$' : '\\]'
  const bodyStart = opening[0].length
  const closing = raw.indexOf(closer, bodyStart)
  // mdMath historically accepts trailing same-line text. Preserve that text
  // by leaving the entire range literal rather than dropping the suffix.
  if (closing < 0 || raw.slice(closing + closer.length).trim()) return null
  const tex = raw.slice(bodyStart, closing)
  return tex.trim() ? tex : null
}

/**
 * Clipboard-only recognition for bold, GFM tables, formulas and Mermaid.
 * Other syntax stays literal; no HTML, links, images, headings or lists render.
 */
export function markdownPasteSlice(text, schema, marks = []) {
  if (!text || !schema?.nodes?.paragraph) return null
  const source = codeBlockDocument(text)
  const lines = source.split('\n')
  const tokens = markdown.parse(source, {})
  const ranges = tokens.filter(token => token.map && ['fence', 'code_block', 'math_block', 'table_open'].includes(token.type))
  const nodes = []
  let recognized = false

  const inlineParagraphs = (raw, parseSyntax = true, tableCell = false) => {
    const paragraphs = []
    let inline = []
    let boldDepth = 0
    const currentMarks = () => boldDepth && schema.marks?.bold ? schema.marks.bold.create().addToSet(marks) : marks
    const appendLiteral = (value, textMarks = currentMarks()) => {
      const pieces = value.split('\n')
      pieces.forEach((piece, index) => {
        if (index) { paragraphs.push(schema.nodes.paragraph.create(null, inline)); inline = [] }
        if (piece) inline.push(schema.text(piece, textMarks))
      })
    }
    const inlineTokens = parseSyntax ? markdownInlineTokens(raw) : []
    const replacements = []
    if (schema.marks?.bold) for (const range of markdownBoldRanges(raw, inlineTokens)) {
      replacements.push({ from: range.start, to: range.bodyStart, kind: 'bold_open' })
      replacements.push({ from: range.bodyEnd, to: range.end, kind: 'bold_close' })
    }
    for (const token of inlineTokens) {
      const from = token.meta?.pasteStart
      if (!Number.isInteger(from)) continue
      if (schema.nodes.mathInline && token.type === 'math_inline' && !isCurrency(token)) {
        const size = token.content.length + (token.markup === '$' ? 2 : 4)
        replacements.push({ from, to: from + size, kind: 'math', token })
      }
      if (tableCell && schema.marks?.code && token.type === 'code_inline') {
        const tail = raw.slice(from + token.markup.length)
        const close = Array.from(tail.matchAll(/`+/g)).find(match => match[0].length === token.markup.length)
        if (close) replacements.push({ from, to: from + token.markup.length + close.index + close[0].length, kind: 'code', token })
      }
    }
    replacements.sort((a, b) => a.from - b.from)
    let cursor = 0
    for (const replacement of replacements) {
      const { from, to, kind, token } = replacement
      if (from < cursor) continue
      // Inline $$ examples must not become a single-dollar atom with a stray
      // dollar left behind. Adjacent genuine $a$$b$ formulas remain valid.
      if (kind === 'math' && token.markup === '$' && raw[from - 1] === '$' && (!cursor || from !== cursor)) continue
      appendLiteral(raw.slice(cursor, from))
      if (kind === 'bold_open') boldDepth++
      else if (kind === 'bold_close') boldDepth--
      else if (kind === 'math') inline.push(schema.nodes.mathInline.create({ tex: token.content }, null, currentMarks()))
      else appendLiteral(token.content, schema.marks.code.create().addToSet(currentMarks()))
      recognized = true
      cursor = to
    }
    appendLiteral(raw.slice(cursor))
    paragraphs.push(schema.nodes.paragraph.create(null, inline))
    return paragraphs
  }
  const appendText = (start, end, parseSyntax = true) => {
    if (end > start) nodes.push(...inlineParagraphs(lines.slice(start, end).join('\n'), parseSyntax))
  }
  const tableNode = opening => {
    if (opening.level !== 0 || !['table', 'tableRow', 'tableHeader', 'tableCell'].every(name => schema.nodes[name])) return null
    // GFM silently discards excess cells, including an unescaped pipe inside
    // inline code. Keep such tables literal rather than lose clipboard text.
    const cellCount = line => {
      const parts = ['']
      for (let index = 0; index < line.length; index++) {
        let slashes = 0
        if (line[index] === '|') for (let before = index - 1; before >= 0 && line[before] === '\\'; before--) slashes++
        if (line[index] === '|' && slashes % 2 === 0) parts.push('')
        else parts[parts.length - 1] += line[index]
      }
      if (!parts[0].trim()) parts.shift()
      if (parts.length && !parts[parts.length - 1].trim()) parts.pop()
      return parts.length
    }
    const [start, end] = opening.map
    const columns = cellCount(lines[start])
    if (lines.slice(start + 2, end).some(line => cellCount(line) > columns)) return null
    const rows = []
    let cells = []
    let cellType = null
    let attrs = null
    for (let index = tokens.indexOf(opening) + 1; index < tokens.length; index++) {
      const token = tokens[index]
      if (token.type === 'table_close') break
      if (token.type === 'tr_open') cells = []
      if (token.type === 'th_open' || token.type === 'td_open') {
        cellType = schema.nodes[token.type === 'th_open' ? 'tableHeader' : 'tableCell']
        attrs = { align: /text-align:(left|center|right)/.exec(token.attrGet('style') || '')?.[1] || null }
      }
      if (token.type === 'inline' && cellType) {
        cells.push(cellType.create(attrs, inlineParagraphs(token.content, true, true)))
        cellType = null
      }
      if (token.type === 'tr_close') rows.push(schema.nodes.tableRow.create(null, cells))
    }
    return rows.length ? schema.nodes.table.create(null, rows) : null
  }

  let cursor = 0
  for (const token of ranges) {
    const [start, end] = token.map
    if (start < cursor) continue
    appendText(cursor, start)
    const tex = token.type === 'math_block' && schema.nodes.mathBlock ? blockTex(token, lines) : null
    const table = token.type === 'table_open' ? tableNode(token) : null
    if (table) {
      nodes.push(table)
      recognized = true
    } else if (schema.nodes.codeBlock && isClosedMermaidFence(token, lines)) {
      const body = lines.slice(start + 1, end - 1)
      nodes.push(schema.nodes.codeBlock.create({ language: 'mermaid', code: body.length ? `${body.join('\n')}\n` : '' }))
      recognized = true
    } else if (tex !== null) {
      nodes.push(schema.nodes.mathBlock.create({ tex }))
      recognized = true
    } else {
      // Every code fence/indented code range shields literal syntax examples.
      appendText(start, end, false)
    }
    cursor = end
  }
  const end = source.endsWith('\n') ? lines.length - 1 : lines.length
  appendText(cursor, end)
  // Open paragraph edges for caret insertion, but keep isolating table edges
  // closed so an existing paragraph suffix cannot enter the last cell.
  return recognized ? Slice.maxOpen(Fragment.fromArray(nodes), false) : null
}

/** One receiving editor, one selection replacement, one independent undo. */
export function handleMarkdownPaste(view, event) {
  if (view?.editable === false || !view?.state || typeof view.dispatch !== 'function') return false
  const clipboard = event?.clipboardData
  if (!clipboard || typeof clipboard.getData !== 'function') return false
  const html = clipboard.getData('text/html')
  if (hasRichCodeLanguage(html) || hasRichMath(html) || hasRichFormatting(html)) return false
  const { state } = view
  if (state.selection.$from.parent.type.spec.code
    || ['codeBlock', 'mathBlock', 'mathInline'].includes(state.selection.$from.parent.type.name)
    || (state.storedMarks || state.selection.$from.marks()).some(mark => mark.type.spec.code)) return false
  const slice = markdownPasteSlice(clipboard.getData('text/plain'), state.schema, state.selection.$from.marks())
  if (!slice) return false
  view.dispatch(closeHistory(state.tr.replaceSelection(slice))
    .setMeta('paste', true).setMeta('uiEvent', 'paste').scrollIntoView())
  return true
}
