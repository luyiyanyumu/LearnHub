/** Build a CodeMirror edit at the supplied selection without changing the source elsewhere. */
export function createSourceInsert(source, selection, name, arg) {
  const text = String(source ?? '')
  const range = normalizeRange(selection, text.length)
  if (!range) return null
  const { from, to } = range
  const selected = text.slice(from, to)

  function edit(insert, anchor = insert.length, head = anchor) {
    return {
      changes: { from, to, insert },
      selection: { anchor: from + anchor, head: from + head },
    }
  }

  // Leave the original prefix and suffix intact. New blank lines isolate a block even
  // when the caret is in the middle of a paragraph rather than at a line boundary.
  function block(body, anchor = body.length, head = anchor) {
    const before = text.slice(0, from)
    const after = text.slice(to)
    const leading = before ? '\n'.repeat(Math.max(0, 2 - trailingNewlines(before) - leadingNewlines(body))) : ''
    const trailing = after ? '\n'.repeat(Math.max(0, 2 - leadingNewlines(after) - trailingNewlines(body))) : ''
    return edit(leading + body + trailing, leading.length + anchor, leading.length + head)
  }

  switch (name) {
    case 'inlineCode': {
      const inner = selected || '代码'
      const delimiter = '`'.repeat(Math.max(1, longestBackticks(inner) + 1))
      const padding = /^`|`$/.test(inner) || (/^ .* $/s.test(inner) && /\S/.test(inner)) ? ' ' : ''
      const start = delimiter.length + padding.length
      return edit(delimiter + padding + inner + padding + delimiter, start, start + inner.length)
    }
    case 'link': {
      const url = String(arg ?? '').trim()
      if (!url) return null
      const label = selected || url
      const escaped = label.replace(/\\/g, '\\\\').replace(/\[/g, '\\[').replace(/\]/g, '\\]')
      return edit('[' + escaped + '](' + linkDestination(url) + ')', 1, 1 + escaped.length)
    }
    case 'image': {
      const url = String(arg ?? '').trim()
      if (!url) return null
      const alt = '图片描述'
      return edit('![' + alt + '](' + linkDestination(url) + ')', 2, 2 + alt.length)
    }
    case 'hr':
      return block('---', 3)
    case 'codeBlock': {
      const lang = String(arg ?? '').trim().replace(/[\r\n`]/g, '')
      const fence = '`'.repeat(Math.max(3, longestBackticks(selected) + 1))
      const start = fence.length + lang.length + 1
      const body = fence + lang + '\n' + selected + '\n' + fence
      return block(body, start, start + selected.length)
    }
    case 'table': {
      const body = '| 列A | 列B |\n| --- | --- |\n|  |  |'
      const start = body.lastIndexOf('\n') + 3
      return block(body, start)
    }
    case 'details': {
      const inner = selected || '折叠内容'
      const prefix = '<details>\n<summary>点击展开</summary>\n\n'
      return block(prefix + inner + '\n\n</details>', prefix.length, prefix.length + inner.length)
    }
    case 'callout': {
      const inner = selected || '提示内容'
      const prefix = ':::tip\n'
      return block(prefix + inner + '\n:::', prefix.length, prefix.length + inner.length)
    }
    case 'todo': {
      const inner = selected || '任务'
      const prefix = '- [ ] '
      return block(prefix + inner, prefix.length, prefix.length + inner.length)
    }
    case 'markdown': {
      const body = String(arg ?? '')
      if (!body) return null
      return block(body)
    }
    default:
      return null
  }
}

function normalizeRange(selection, length) {
  if (!Number.isFinite(selection?.from) || !Number.isFinite(selection?.to)) return null
  const from = Math.max(0, Math.min(length, Math.trunc(selection.from)))
  const to = Math.max(0, Math.min(length, Math.trunc(selection.to)))
  return { from: Math.min(from, to), to: Math.max(from, to) }
}

function leadingNewlines(text) { return text.match(/^\n*/)[0].length }
function trailingNewlines(text) { return text.match(/\n*$/)[0].length }
function longestBackticks(text) {
  return Math.max(0, ...Array.from(text.matchAll(/`+/g), match => match[0].length))
}
function linkDestination(url) {
  // Angle destinations keep spaces and parentheses from terminating Markdown links.
  return '<' + url.replace(/</g, '%3C').replace(/>/g, '%3E').replace(/[\r\n]/g, '') + '>'
}
