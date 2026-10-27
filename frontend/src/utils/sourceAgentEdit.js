import { isolateHistory } from '@codemirror/commands'
import MarkdownIt from 'markdown-it'
import mdMath from './mdMath.js'

const headingParser = new MarkdownIt({ html: true }).use(mdMath, { renderKatex: false })

function lineBreaks(value, leading) {
  const edge = leading ? /^(?:[ \t]*\r?\n)*/ : /(?:\r?\n[ \t]*)*$/
  return (value.match(edge)?.[0].match(/\n/g) || []).length
}

function headingInsertion(state, from, content) {
  const source = state.doc.toString()
  const line = state.doc.lineAt(from).number - 1
  // Parse block structure rather than treating a fenced-code or formula line
  // beginning with # as a heading. A Setext heading includes its underline.
  const heading = headingParser.parse(source, {}).find(token => token.type === 'heading_open'
    && token.map && line >= token.map[0] && line < token.map[1])
  if (!heading) return null
  const position = state.doc.line(heading.map[1]).to
  const newline = source.includes('\r\n') ? '\r\n' : '\n'
  const before = newline.repeat(Math.max(0, 2 - lineBreaks(content, true)))
  const suffix = source.slice(position)
  const after = suffix ? newline.repeat(Math.max(0, 2 - lineBreaks(content, false) - lineBreaks(suffix, true))) : ''
  return { position, insert: before + content + after, anchor: position + before.length + content.length }
}

export function replaceSourceContent(view, next) {
  if (!view?.state || typeof view.dispatch !== 'function' || view.composing) return false
  const before = view.state.doc.toString()
  let start = 0, oldEnd = before.length, newEnd = next.length
  while (start < oldEnd && start < newEnd && before[start] === next[start]) start++
  while (oldEnd > start && newEnd > start && before[oldEnd - 1] === next[newEnd - 1]) { oldEnd--; newEnd-- }
  if (start < oldEnd || start < newEnd) view.dispatch({ changes: { from: start, to: oldEnd, insert: next.slice(start, newEnd) }, annotations: isolateHistory.of('full') })
  return true
}

export function insertSourceContent(view, content, { block = false } = {}) {
  if (!view?.state || typeof view.dispatch !== 'function' || view.composing) return false
  const { from, to } = view.state.selection.main
  if (block && from === to) {
    const insertion = headingInsertion(view.state, from, content)
    if (insertion) {
      view.dispatch({ changes: { from: insertion.position, insert: insertion.insert }, selection: { anchor: insertion.anchor }, annotations: isolateHistory.of('full') })
      return true
    }
  }
  view.dispatch({ changes: { from, to, insert: content }, selection: { anchor: from + content.length }, annotations: isolateHistory.of('full') })
  return true
}
