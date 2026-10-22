import { classHighlighter, highlightTree } from '@lezer/highlight'

const LANG_LOADERS = {
  python: () => import('@codemirror/lang-python').then(m => m.python()),
  java: () => import('@codemirror/lang-java').then(m => m.java()),
  javascript: () => import('@codemirror/lang-javascript').then(m => m.javascript()),
  typescript: () => import('@codemirror/lang-javascript').then(m => m.javascript({ typescript: true })),
  vue: () => import('@codemirror/lang-vue').then(m => m.vue()),
  html: () => import('@codemirror/lang-html').then(m => m.html()),
  css: () => import('@codemirror/lang-css').then(m => m.css()),
  json: () => import('@codemirror/lang-json').then(m => m.json()),
  yaml: () => import('@codemirror/lang-yaml').then(m => m.yaml()),
  xml: () => import('@codemirror/lang-xml').then(m => m.xml()),
  sql: () => import('@codemirror/lang-sql').then(m => m.sql()),
  go: () => import('@codemirror/lang-go').then(m => m.go()),
  rust: () => import('@codemirror/lang-rust').then(m => m.rust()),
  cpp: () => import('@codemirror/lang-cpp').then(m => m.cpp()),
  php: () => import('@codemirror/lang-php').then(m => m.php()),
  markdown: () => import('@codemirror/lang-markdown').then(m => m.markdown()),
}
const languages = new Map()

/** Share language support between lightweight reading and the editor opened on demand. */
export async function loadCodeBlockLanguage(language) {
  const key = String(language || '').toLowerCase()
  if (!Object.hasOwn(LANG_LOADERS, key)) return null
  const loader = LANG_LOADERS[key]
  if (!languages.has(key)) languages.set(key, loader().catch(() => { languages.delete(key); return null }))
  return languages.get(key)
}

/** Text tokens only: Vue escapes their content, including HTML inside code. */
export function highlightCodeBlockLines(source, support) {
  const text = String(source ?? '')
  const ranges = []
  const parser = support?.language?.parser
  if (parser) highlightTree(parser.parse(text), classHighlighter, (from, to, className) => ranges.push({ from, to, className }))
  let rangeIndex = 0, offset = 0
  return text.split('\n').map(line => {
    const end = offset + line.length, tokens = []
    let position = offset
    while (rangeIndex < ranges.length && ranges[rangeIndex].to <= position) rangeIndex++
    while (position < end) {
      const range = ranges[rangeIndex]
      if (!range || range.from >= end) { tokens.push({ text: text.slice(position, end), className: '' }); break }
      if (range.from > position) tokens.push({ text: text.slice(position, range.from), className: '' })
      const from = Math.max(position, range.from), to = Math.min(end, range.to)
      tokens.push({ text: text.slice(from, to), className: range.className })
      position = to
      if (range.to <= end) rangeIndex++
    }
    offset = end + 1
    return tokens
  })
}
