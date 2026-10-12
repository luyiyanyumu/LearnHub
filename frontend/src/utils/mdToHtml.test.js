import assert from 'node:assert/strict'
import test from 'node:test'
import { readFileSync } from 'node:fs'
import { registerHooks } from 'node:module'
import { fileURLToPath } from 'node:url'
import domino from '@mixmark-io/domino'
import { documentHtmlToMd } from './htmlToMd.js'

// Match the two Vite import conveniences used by this module without starting a
// dev server or loading project configuration. Production rendering is unmodified.
const hooks = registerHooks({
  resolve(specifier, context, nextResolve) {
    if (specifier.endsWith('?raw')) {
      const result = nextResolve(specifier.slice(0, -4), context)
      return { ...result, url: `${result.url}?raw` }
    }
    if (specifier.startsWith('.') && !/\.[a-z]+$/i.test(specifier)) return nextResolve(`${specifier}.js`, context)
    return nextResolve(specifier, context)
  },
  load(url, context, nextLoad) {
    if (!url.endsWith('?raw')) return nextLoad(url, context)
    const css = readFileSync(fileURLToPath(url.slice(0, -4)), 'utf8')
    return { format: 'module', source: `export default ${JSON.stringify(css)}`, shortCircuit: true }
  },
})
const { renderNoteHtml, renderMarkdownFragment } = await import('./mdToHtml.js')
test.after(() => hooks.deregister())

const flowchart = 'flowchart LR\n  A[合成输入] --> B[合成结果]\n'
const fence = source => `\`\`\`mermaid\n${source}\`\`\``
const syntheticSvg = '<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 240 100"><style>text{fill:#123}</style><text x="10" y="30">合成图形</text></svg>'
const renderDiagram = async () => syntheticSvg

function inspect(html) { return domino.createWindow(html).document }

function withImportDocument(operation) {
  const previousDocument = globalThis.document
  const previousParser = globalThis.DOMParser
  const document = inspect('')
  const collection = Object.getPrototypeOf(document.createElement('div').children)
  if (!collection[Symbol.iterator]) Object.defineProperty(collection, Symbol.iterator, { value: Array.prototype[Symbol.iterator] })
  const createElement = document.createElement.bind(document)
  Object.defineProperty(document, 'createElement', { value: tag => {
    const element = createElement(tag)
    const queryAll = element.querySelectorAll.bind(element)
    Object.defineProperty(element, 'querySelectorAll', { value: selector => Array.from(queryAll(selector)) })
    return element
  } })
  globalThis.document = document
  globalThis.DOMParser = class { parseFromString(html) { return inspect(html) } }
  try { return operation() } finally {
    globalThis.document = previousDocument
    globalThis.DOMParser = previousParser
  }
}

test('clipboard fragment remains synchronous source code rather than a rendered diagram', () => {
  const html = renderMarkdownFragment(fence(flowchart))
  assert.equal(typeof html, 'string')
  const document = inspect(html)
  assert.equal(document.querySelector('pre code.language-mermaid').textContent, flowchart)
  assert.ok(!document.querySelector('iframe,svg'))
})

test('HTML export embeds isolated offline light and dark diagrams and retains exact source for import', async () => {
  const source = 'flowchart TD\n  A["合成 & <输入>"] --> B["结果 `code`"]\n\n\n  B --> C[结束]\n'
  const calls = []
  const html = await renderNoteHtml({ title: '合成流程图', content: fence(source) }, {
    renderDiagram: async (code, theme) => { calls.push({ code, dark: theme.dark }); return syntheticSvg },
  })
  const document = inspect(html)
  const wrapper = document.querySelector('.note-mermaid-export')
  assert.equal(wrapper.getAttribute('data-mermaid-source'), source)
  assert.equal(wrapper.querySelector('pre[hidden] code').textContent, source)
  const frames = Array.from(wrapper.querySelectorAll('iframe'))
  assert.equal(frames.length, 2)
  for (const frame of frames) {
    assert.equal(frame.getAttribute('sandbox'), '')
    assert.ok(frame.getAttribute('srcdoc').includes(syntheticSvg))
    assert.ok(frame.getAttribute('srcdoc').includes("default-src 'none'"))
  }
  const lightDocument = wrapper.querySelector('.note-mermaid-light').getAttribute('srcdoc')
  const darkDocument = wrapper.querySelector('.note-mermaid-dark').getAttribute('srcdoc')
  assert.match(lightDocument, /color-scheme:light/)
  assert.match(lightDocument, /background:#ffffff/)
  assert.match(darkDocument, /color-scheme:dark/)
  assert.match(darkDocument, /background:#0d1117/)
  assert.ok(!lightDocument.includes('background:transparent'))
  assert.ok(!darkDocument.includes('background:transparent'))
  assert.ok(!document.querySelector('script'))
  assert.deepEqual(calls, [{ code: source, dark: false }, { code: source, dark: true }])
  const restored = withImportDocument(() => documentHtmlToMd(html))
  assert.equal(restored.title, '合成流程图')
  assert.equal(restored.content, fence(source.replace(/\n+$/, '') + '\n'))
})

test('a diagram error preserves its original code while the rest of the export completes', async () => {
  const invalid = 'flowchart TD\n  [合成无效语法\n'
  const html = await renderNoteHtml({ title: '合成混合笔记', content: `前文\n\n${fence(invalid)}\n\n${fence(flowchart)}\n\n后文` }, {
    renderDiagram: async source => { if (source.includes('无效')) throw new Error('synthetic parse failure'); return syntheticSvg },
  })
  const document = inspect(html)
  assert.equal(document.querySelectorAll('.note-mermaid-export').length, 1)
  assert.equal(document.querySelector('.markdown-body > pre code.language-mermaid').textContent, invalid)
  assert.ok(document.querySelector('.markdown-body').textContent.includes('前文'))
  assert.ok(document.querySelector('.markdown-body').textContent.includes('后文'))
  assert.ok(!html.includes('synthetic parse failure'))
})

test('an unsuccessful dark variant also falls back to one original readable code block', async () => {
  const html = await renderNoteHtml({ title: '合成深色失败', content: fence(flowchart) }, {
    renderDiagram: async (_source, theme) => { if (theme.dark) return null; return syntheticSvg },
  })
  const document = inspect(html)
  assert.equal(document.querySelectorAll('iframe').length, 0)
  assert.equal(document.querySelectorAll('pre code.language-mermaid').length, 1)
  assert.equal(document.querySelector('pre code.language-mermaid').textContent, flowchart)
})

test('wide exported diagrams retain the SVG ratio without fixed inner padding at narrow widths', async () => {
  const width = 1800.5
  const height = 120.25
  const svg = `<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 ${width} ${height}"><text>合成宽图</text></svg>`
  const html = await renderNoteHtml({ title: '合成宽图', content: fence(flowchart) }, { renderDiagram: async () => svg })
  const frames = Array.from(inspect(html).querySelectorAll('.note-mermaid-export iframe'))
  assert.equal(frames.length, 2)
  for (const frame of frames) {
    const style = frame.getAttribute('style')
    assert.equal(style, `aspect-ratio:${width}/${height};max-width:${width}px`)
    assert.equal(frame.getAttribute('width'), String(width))
    assert.equal(frame.getAttribute('height'), String(height))
    assert.match(frame.getAttribute('srcdoc'), /body\{padding:0px;/)
    const ratio = style.match(/aspect-ratio:([\d.]+)\/([\d.]+)/).slice(1).map(Number)
    const maxWidth = Number(style.match(/max-width:([\d.]+)px/)[1])
    for (const availableWidth of [280, 390, 800, 1440]) {
      const displayedWidth = Math.min(maxWidth, availableWidth)
      const iframeHeight = displayedWidth / (ratio[0] / ratio[1])
      const scaledSvgHeight = height * displayedWidth / width
      assert.ok(Math.abs(iframeHeight - scaledSvgHeight) < 1e-9, `complete SVG fits at ${availableWidth}px`)
    }
  }
})

test('concurrent exports and synchronous clipboard rendering keep their own diagram environment', async () => {
  let release
  const gate = new Promise(resolve => { release = resolve })
  const first = 'flowchart TD\n  A[合成第一份] --> B[结束]\n'
  const second = 'flowchart LR\n  A[合成第二份] --> B[结束]\n'
  const renderer = async source => { if (source.includes('第一份')) await gate; return syntheticSvg }
  const firstExport = renderNoteHtml({ title: '第一份', content: fence(first) }, { renderDiagram: renderer })
  const secondExport = renderNoteHtml({ title: '第二份', content: fence(second) }, { renderDiagram: renderer })
  const clipboard = renderMarkdownFragment(fence(flowchart))
  const secondHtml = await secondExport
  release()
  const firstHtml = await firstExport
  assert.equal(inspect(firstHtml).querySelector('.note-mermaid-export').getAttribute('data-mermaid-source'), first)
  assert.equal(inspect(secondHtml).querySelector('.note-mermaid-export').getAttribute('data-mermaid-source'), second)
  assert.ok(!firstHtml.includes('合成第二份'))
  assert.ok(!secondHtml.includes('合成第一份'))
  assert.equal(inspect(clipboard).querySelector('code.language-mermaid').textContent, flowchart)
  assert.ok(!/LEARNHUBMERMAIDEXPORT/.test(firstHtml + secondHtml + clipboard))
})

test('user text resembling an export placeholder remains literal', async () => {
  const literal = '<!--LEARNHUBMERMAIDEXPORT0-->'
  const html = await renderNoteHtml({ title: '合成占位符', content: `${literal}\n\n${fence(flowchart)}` }, { renderDiagram })
  assert.ok(html.includes(literal))
  assert.equal(inspect(html).querySelectorAll('.note-mermaid-export').length, 1)
})
