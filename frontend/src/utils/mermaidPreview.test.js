import assert from 'node:assert/strict'
import test from 'node:test'
import { MERMAID_MAX_SOURCE, MERMAID_MAX_EDGES, mermaidConfig, mermaidSource, createMermaidRenderer,
  createMermaidPreviewState, sanitizeMermaidSvg, mermaidFrameDocument, mermaidFrameHtml, mermaidSvgSize } from './mermaidPreview.js'

const deferred = () => {
  let resolve, reject
  const promise = new Promise((yes, no) => { resolve = yes; reject = no })
  return { promise, resolve, reject }
}
const tick = () => new Promise(resolve => setImmediate(resolve))

test('site config locks safety, resource limits and theme against diagram directives', () => {
  const config = mermaidConfig({ dark: true, background: '#182221', primary: '#12332e', text: '#e7eeec', brand: '#2dd4bf' })
  assert.equal(config.securityLevel, 'strict')
  assert.equal(config.htmlLabels, false)
  assert.equal(config.startOnLoad, false)
  assert.equal(config.suppressErrorRendering, true)
  assert.equal(config.maxTextSize, MERMAID_MAX_SOURCE)
  assert.equal(config.maxEdges, MERMAID_MAX_EDGES)
  assert.equal(config.themeVariables.primaryColor, '#12332e')
  for (const key of ['securityLevel', 'htmlLabels', 'maxTextSize', 'maxEdges', 'themeCSS', 'dompurifyConfig']) {
    assert.ok(config.secure.includes(key))
  }
})

test('source validation leaves original whitespace intact and rejects empty or oversized diagrams', () => {
  const code = 'flowchart TD\n  A[开始] --> B[结束]\n\n'
  assert.equal(mermaidSource(code), code)
  assert.throws(() => mermaidSource(' \n'), /源码为空/)
  assert.throws(() => mermaidSource('x'.repeat(MERMAID_MAX_SOURCE + 1)), /超过/)
  assert.equal(mermaidSource('flowchart TD\n A[https://example.com] --> B'), 'flowchart TD\n A[https://example.com] --> B')
  for (const source of ['flowchart TD\n A@{ img: "https://example.com/image.png" }',
    'flowchart TD\n A["![asset](https://example.com/image.png)"]',
    'flowchart TD\n A["<img src=https://example.com/image.png>"]',
    'flowchart TD\n classDef x fill:url(https://example.com/image.png)',
    'flowchart TD\n classDef x fill:u\\72l(https://example.com/image.png)']) {
    assert.throws(() => mermaidSource(source), /不支持图片或外部样式/)
  }
})

test('multiple previews initialize and render serially; a failure releases the queue and temporary DOM', async () => {
  const first = deferred()
  const events = []
  let removed = 0
  const render = createMermaidRenderer({
    load: async () => ({ mermaid: {
      initialize: config => events.push(`theme:${config.themeVariables.darkMode}`),
      render: async (_id, source) => { events.push(source); if (source === 'first') return first.promise; return { svg: '<svg>second</svg>' } },
    } }),
    sanitize: svg => svg,
    createContainer: () => ({ remove() { removed++ } }),
  })
  const a = render('first', { dark: false })
  const b = render('second', { dark: true })
  await tick()
  assert.deepEqual(events, ['theme:false', 'first'])
  first.reject(new Error('invalid syntax'))
  await assert.rejects(a, /invalid syntax/)
  assert.equal(await b, '<svg>second</svg>')
  assert.deepEqual(events, ['theme:false', 'first', 'theme:true', 'second'])
  assert.equal(removed, 2)
})

test('stale queued or loading previews never initialize the renderer or create temporary DOM', async () => {
  const runtime = deferred()
  let current = true
  let touched = 0
  const render = createMermaidRenderer({
    load: () => runtime.promise,
    createContainer: () => { touched++; return { remove() {} } },
  })
  const pending = render('flowchart TD\nA-->B', {}, { isCurrent: () => current })
  await tick()
  current = false
  runtime.resolve({ mermaid: { initialize() { touched++ } } })
  assert.equal(await pending, null)
  assert.equal(await render('never run', {}, { isCurrent: () => false }), null)
  assert.equal(touched, 0)
})

test('latest source and theme wins, including stale failures and disposal during a render', async () => {
  const pending = []
  const states = []
  const preview = createMermaidPreviewState({ render: (source, theme, context) => {
    const task = deferred(); pending.push({ ...task, source, theme, context }); return task.promise
  }, onState: state => states.push(state) })
  const old = preview.update('old', { dark: false })
  const latest = preview.update('new', { dark: true })
  pending[1].resolve('<svg>new dark</svg>')
  await latest
  pending[0].reject(new Error('outdated syntax error'))
  await old
  assert.equal(states.at(-1).svg, '<svg>new dark</svg>')
  assert.equal(pending[0].context.isCurrent(), false)
  assert.equal(pending[1].theme.dark, true)
  const next = preview.update('unmounted', {})
  const count = states.length
  preview.dispose()
  pending[2].resolve('<svg>discard</svg>')
  await next
  assert.equal(states.length, count)
})

test('syntax errors keep the source outside the renderer state and cancelled errors stay silent', async () => {
  const states = []
  const task = deferred()
  const preview = createMermaidPreviewState({ render: () => task.promise, onState: value => states.push(value) })
  const source = 'flowchart TD\n A -->'
  const pending = preview.update(source, {})
  task.reject(new Error('<img src=x onerror=alert(1)>'))
  await pending
  assert.match(states.at(-1).error, /源码已保留/)
  assert.equal(states.at(-1).svg, '')
  assert.equal(source, 'flowchart TD\n A -->')
  const cancelled = deferred()
  const quiet = createMermaidPreviewState({ render: () => cancelled.promise, onState: value => states.push(value) })
  const old = quiet.update(source, {})
  quiet.invalidate()
  const count = states.length
  cancelled.reject(new Error('cancelled'))
  await old
  assert.equal(states.length, count)
})

test('SVG sanitizer forbids active content, links and external images, and fails closed', () => {
  let options
  const purifier = { isSupported: true, sanitize: (_svg, config) => { options = config; return '<svg viewBox="0 0 20 10"></svg>' } }
  assert.match(sanitizeMermaidSvg('<svg></svg>', purifier), /^<svg/)
  for (const tag of ['foreignObject', 'image', 'script', 'a', 'animate', 'set']) assert.ok(options.FORBID_TAGS.includes(tag))
  assert.ok(options.FORBID_ATTR.includes('href'))
  assert.deepEqual(options.USE_PROFILES, { svg: true, svgFilters: true })
  assert.throws(() => sanitizeMermaidSvg('<svg></svg>', { isSupported: false }), /安全/)
  assert.throws(() => sanitizeMermaidSvg('oops', { isSupported: true, sanitize: () => '<p>oops</p>' }), /有效/)
})

test('frame HTML isolates SVG CSS and resources without granting iframe script permissions', () => {
  const svg = '<svg viewBox="0 0 640 320"><text>合成 "图"</text></svg>'
  const document = mermaidFrameDocument(svg)
  assert.match(document, /default-src 'none'/)
  assert.match(document, /img-src 'none'/)
  assert.match(document, /form-action 'none'/)
  const html = mermaidFrameHtml(svg)
  assert.match(html, /sandbox=""/)
  assert.match(html, /role="img"/)
  assert.match(html, /&lt;svg viewBox=&quot;0 0 640 320&quot;/)
  assert.doesNotMatch(html, /allow-scripts|allow-same-origin/)
  assert.deepEqual(mermaidSvgSize(svg), { width: 640, height: 320 })
  assert.deepEqual(mermaidSvgSize('<svg viewBox="0 0 Infinity -1">'), { width: 640, height: 360 })
})

test('MdPreview frame scales to actual narrow and wide containers instead of a fixed 640px height', () => {
  const svg = '<svg style="max-width:1200px" width="100%" viewBox="0 0 1200 900"></svg>'
  const html = mermaidFrameHtml(svg)
  assert.match(html, /height:auto;aspect-ratio:1200\/900;max-width:1200px/)
  assert.match(html, /body\{padding:0px/)
  assert.doesNotMatch(html, /height:\d+px/)
  const ratio = html.match(/aspect-ratio:([\d.]+)\/([\d.]+)/).slice(1).map(Number)
  for (const container of [280, 390, 800, 1440]) {
    const width = Math.min(container, ratio[0])
    const height = width / ratio[0] * ratio[1]
    assert.equal(height, width / 1200 * 900)
  }
})

test('zoomable viewports fit the full SVG without intrinsic width limits or inner scrolling', () => {
  const svg = '<svg style="max-width:200px" viewBox="0 0 200 1200"></svg>'
  const document = mermaidFrameDocument(svg, { padding: 0, fit: true })
  assert.match(document, /html,body\{height:100%;overflow:hidden\}/)
  assert.match(document, /svg\{width:100%;height:100%;max-width:none!important\}/)
  assert.match(document, /default-src 'none'/)
})

test('sandbox document defines an opaque theme canvas and only accepts safe hex colors', () => {
  const svg = '<svg viewBox="0 0 120 90"></svg>'
  const dark = mermaidFrameDocument(svg, { dark: true, background: '#182221', padding: 0 })
  assert.match(dark, /color-scheme:dark;background:#182221/)
  assert.match(dark, /body\{padding:0px/)
  const light = mermaidFrameDocument(svg, { dark: false, background: '#fff' })
  assert.match(light, /color-scheme:light;background:#fff/)
  assert.match(light, /body\{padding:8px/)
  assert.match(mermaidFrameDocument(svg), /color-scheme:light;background:#ffffff/)
  for (const background of ['red', '#fff;position:fixed', '#ffffff00', '</style><script>bad</script>', 'url(https://example.com)']) {
    const document = mermaidFrameDocument(svg, { dark: true, background })
    assert.match(document, /color-scheme:dark;background:#182221/)
    assert.doesNotMatch(document, /position:fixed|<script>|url\(/)
  }
})

test('syntax checks use the same serialized runtime with no render container', async () => {
  let containers = 0
  const order = []
  const render = createMermaidRenderer({ load: async () => ({ mermaid: {
    initialize() { order.push('initialize') },
    parse: async (source, options) => { order.push(source); return options.suppressErrors ? false : { diagramType: 'flowchart' } },
  } }), createContainer: () => { containers++; return { remove() {} } } })
  assert.deepEqual(await render.parse('flowchart TD\nA-->B', {}), { diagramType: 'flowchart' })
  assert.equal(await render.parse('invalid', {}, { suppressErrors: true }), false)
  assert.equal(containers, 0)
  assert.deepEqual(order, ['initialize', 'flowchart TD\nA-->B', 'initialize', 'invalid'])
})
