import MarkdownIt from 'markdown-it'
import Bold from '@tiptap/extension-bold'
import { markInputRule } from '@tiptap/core'
import mdMath from './mdMath.js'

// Use the same syntax decisions for paste and typing. Delimiter positions are
// recorded before markdown-it's emphasis postprocessor turns them into marks.
export const pasteMarkdown = new MarkdownIt({ html: false }).use(mdMath, { renderKatex: false })
const InlineState = pasteMarkdown.inline.State
pasteMarkdown.inline.State = class extends InlineState {
  push(type, tag, nesting) {
    const start = this.pos
    const token = super.push(type, tag, nesting)
    token.meta = { ...token.meta, pasteStart: start }
    return token
  }
}

export function markdownInlineTokens(source) {
  const tokens = []
  pasteMarkdown.inline.parse(source, pasteMarkdown, {}, tokens)
  return tokens
}

/** Only paired ** delimiters confirmed by the parser; other syntax stays raw. */
export function markdownBoldRanges(source, tokens = markdownInlineTokens(source)) {
  const stack = []
  const ranges = []
  for (const token of tokens) {
    if (token.type === 'strong_open') stack.push(token)
    if (token.type !== 'strong_close') continue
    const opening = stack.pop()
    const start = opening?.meta?.pasteStart
    const close = token.meta?.pasteStart
    if (opening?.markup !== '**' || token.markup !== '**' || !Number.isInteger(start) || !Number.isInteger(close)) continue
    // Triple-star runs also contain emphasis. Do not partially consume them.
    if (source.slice(start, start + 2) !== '**' || source.slice(close, close + 2) !== '**'
      || source[start - 1] === '*' || source[start + 2] === '*'
      || source[close - 1] === '*' || source[close + 2] === '*') continue
    ranges.push({ start, bodyStart: start + 2, bodyEnd: close, end: close + 2 })
  }
  return ranges.sort((a, b) => a.start - b.start)
}

/** A completed, plain-text bold pair at the caret; no regex-only guessing. */
export function markdownBoldInput(source) {
  if (!source.endsWith('**')) return null
  const tokens = markdownInlineTokens(source)
  const range = markdownBoldRanges(source, tokens).find(candidate => candidate.end === source.length)
  if (!range) return null
  const body = source.slice(range.bodyStart, range.bodyEnd)
  if (!body || /[\n`]/.test(body)) return null
  // An unfinished code/formula delimiter means the user is still entering
  // source. Completed code and formulas are already shielded by the parser.
  const before = source.slice(0, range.start)
  const unmatched = value => (before.match(value) || []).length % 2 !== 0
  if (unmatched(/(?<!\\)`+/g) || unmatched(/(?<!\\)\$/g) || unmatched(/(?<!\\)\$\$/g)
    || (before.match(/\\\(/g) || []).length > (before.match(/\\\)/g) || []).length
    || (before.match(/\\\[/g) || []).length > (before.match(/\\\]/g) || []).length) return null
  if (tokens.some(token => token.meta?.pasteStart >= range.bodyStart && token.meta?.pasteStart < range.bodyEnd
    && ['math_inline', 'code_inline', 'strong_open', 'em_open', 'link_open'].includes(token.type))) return null
  return { index: range.start, text: source.slice(range.start), replaceWith: body }
}

export const MarkdownBold = Bold.extend({
  addInputRules() {
    return [markInputRule({
      type: this.type,
      find: source => {
        const state = this.editor.state
        const parent = state.selection.$from.parent
        if (parent.type.spec.code || ['codeBlock', 'mathBlock', 'mathInline'].includes(parent.type.name)
          || state.storedMarks?.some(mark => mark.type.spec.code)) return null
        return markdownBoldInput(source)
      },
    }), ...(this.parent?.() || []).slice(1)]
  },
})
