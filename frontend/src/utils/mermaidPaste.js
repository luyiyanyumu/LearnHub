import MarkdownIt from 'markdown-it'
import { Fragment, Slice } from '@tiptap/pm/model'
import { closeHistory } from '@tiptap/pm/history'
import { codeBlockDocument } from './codeBlockViewState.js'

const markdown = new MarkdownIt({ html: false })
const literalCodeLanguages = new Set(['markdown', 'md', 'plaintext', 'plain', 'text', 'txt'])

/** Keep real, language-bearing rich code blocks on the normal HTML paste path. */
export function hasRichCodeLanguage(html) {
  for (const tag of String(html || '').matchAll(/<(?:pre|code)\b([^>]*)>/gi)) {
    for (const attribute of tag[1].matchAll(/\b(class|data-language|data-lang|lang|language)\s*=\s*(?:"([^"]*)"|'([^']*)'|([^\s"'=<>`]+))/gi)) {
      const value = attribute[2] ?? attribute[3] ?? attribute[4] ?? ''
      const languages = attribute[1].toLowerCase() === 'class'
        ? Array.from(value.matchAll(/(?:^|\s)language-([^\s]+)/gi), match => match[1])
        : [value]
      if (languages.some(language => language && !literalCodeLanguages.has(language.toLowerCase()))) return true
    }
  }
  return false
}

export function isClosedMermaidFence(token, lines) {
  if (token.type !== 'fence' || token.level !== 0 || token.info.trim().toLowerCase() !== 'mermaid' || !token.map) return false
  const [start, end] = token.map
  if (end <= start + 1) return false
  const close = /^ {0,3}(`{3,}|~{3,})[\t ]*$/.exec(lines[end - 1] || '')
  return !!close && close[1][0] === token.markup[0] && close[1].length >= token.markup.length
}

/**
 * Recognize complete top-level Markdown Mermaid fences, leaving every other
 * line as literal paragraph text. Tokenization prevents recognizing Mermaid
 * examples nested inside another code fence. No clipboard HTML is executed.
 */
export function mermaidPasteSlice(text, schema, marks = []) {
  if (!text || !schema?.nodes?.codeBlock || !schema.nodes.paragraph) return null
  const source = codeBlockDocument(text)
  const lines = source.split('\n')
  const fences = markdown.parse(source, {}).filter(token => isClosedMermaidFence(token, lines))
  if (!fences.length) return null

  const nodes = []
  const appendText = (start, end) => {
    for (const line of lines.slice(start, end)) {
      nodes.push(schema.nodes.paragraph.create(null, line ? schema.text(line, marks) : null))
    }
  }
  let cursor = 0
  for (const token of fences) {
    const [start, end] = token.map
    appendText(cursor, start)
    // Preserve spaces, blank lines, and the newline before the closing fence.
    const body = lines.slice(start + 1, end - 1)
    const code = body.length ? `${body.join('\n')}\n` : ''
    nodes.push(schema.nodes.codeBlock.create({ language: 'mermaid', code }))
    cursor = end
  }
  // An ending newline after a closing fence is a delimiter, not extra content.
  const end = source.endsWith('\n') ? lines.length - 1 : lines.length
  appendText(cursor, end)
  return Slice.maxOpen(Fragment.fromArray(nodes), true)
}

/** ProseMirror handlePaste: use the receiving view's selection and history. */
export function handleMermaidPaste(view, event) {
  if (view?.editable === false || !view?.state || typeof view.dispatch !== 'function') return false
  const clipboard = event?.clipboardData
  if (!clipboard || typeof clipboard.getData !== 'function') return false
  if (hasRichCodeLanguage(clipboard.getData('text/html'))) return false
  const { state } = view
  const slice = mermaidPasteSlice(clipboard.getData('text/plain'), state.schema, state.selection.$from.marks())
  if (!slice) return false
  const transaction = closeHistory(state.tr.replaceSelection(slice))
    .setMeta('paste', true).setMeta('uiEvent', 'paste').scrollIntoView()
  view.dispatch(transaction)
  return true
}
