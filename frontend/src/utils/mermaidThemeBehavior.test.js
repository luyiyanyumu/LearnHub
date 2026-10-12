import assert from 'node:assert/strict'
import test from 'node:test'
import { readFileSync } from 'node:fs'
import { parse, compileScript } from '@vue/compiler-sfc'
import * as vue from 'vue'
import { isDark, setThemeMode } from '../composables/useTheme.js'
import { createMermaidAdapter, createMermaidPreviewState, mermaidFrameDocument, mermaidFrameHtml, mermaidSvgSize, readMermaidTheme } from './mermaidPreview.js'

// Compile the actual component setup, replacing only DOM lifecycle and renderer.
// Its watchers/generation state and the actual useTheme ordering run unchanged.
function previewHarness(render) {
  const file = new URL('../components/MermaidPreview.vue', import.meta.url)
  const { descriptor } = parse(readFileSync(file, 'utf8'), { filename: file.pathname })
  const { content } = compileScript(descriptor, { id: 'mermaid-theme-test' })
  const script = content.replace(/^import .*\n/gm, '').replace('export default', 'return')
  const mounted = [], unmounted = []
  const bindings = { computed: vue.computed, nextTick: vue.nextTick, reactive: vue.reactive, ref: vue.ref, watch: vue.watch,
    isDark, onMounted: callback => mounted.push(callback), onBeforeUnmount: callback => unmounted.push(callback),
    createMermaidPreviewState: options => createMermaidPreviewState({ ...options, render }),
    mermaidFrameDocument, mermaidSvgSize, readMermaidTheme }
  const component = new Function(...Object.keys(bindings), script)(...Object.values(bindings))
  const scope = vue.effectScope()
  const props = vue.reactive({ source: 'flowchart TD\nA-->B', active: true })
  const setup = scope.run(() => component.setup(props, { expose() {}, emit() {} }))
  mounted.forEach(callback => callback())
  return { setup, props, dispose() { unmounted.forEach(callback => callback()); scope.stop() } }
}

function withThemeDom(operation) {
  const names = ['document', 'getComputedStyle', 'requestAnimationFrame', 'localStorage']
  const original = new Map(names.map(name => [name, Object.getOwnPropertyDescriptor(globalThis, name)]))
  const classes = new Set()
  globalThis.document = { documentElement: { classList: {
    add: name => classes.add(name), remove: name => classes.delete(name),
    toggle(name, enabled) { if (enabled) classes.add(name); else classes.delete(name) },
    contains: name => classes.has(name),
  } } }
  globalThis.getComputedStyle = () => ({ getPropertyValue(name) {
    const dark = classes.has('dark')
    return { '--app-card': dark ? '#182221' : '#ffffff', '--app-brand-soft': dark ? '#12332e' : '#e2f5f1',
      '--app-text-1': dark ? '#e7eeec' : '#1f2d2a', '--app-brand': dark ? '#2dd4bf' : '#0e9f8e',
      '--app-text-2': dark ? '#a2b3af' : '#5a6b67' }[name] || ''
  } })
  globalThis.requestAnimationFrame = () => 0
  globalThis.localStorage = { setItem() {}, removeItem() {} }
  isDark.value = false
  return Promise.resolve().then(operation).finally(() => {
    for (const [name, descriptor] of original) {
      if (descriptor) Object.defineProperty(globalThis, name, descriptor)
      else delete globalThis[name]
    }
    isDark.value = false
  })
}

test('actual component defers CSS reads until html.dark changes and suppresses the previous render', () => withThemeDom(async () => {
  const renders = []
  const harness = previewHarness((source, theme, context) => new Promise(resolve => renders.push({ source, theme, context, resolve })))
  try {
    assert.equal(renders[0].theme.background, '#ffffff')
    setThemeMode('dark')
    assert.equal(renders.length, 1)
    assert.equal(renders[0].context.isCurrent(), false)
    renders[0].resolve('<svg>old light</svg>')
    await vue.nextTick()
    assert.equal(renders[1].theme.dark, true)
    assert.equal(renders[1].theme.background, '#182221')
    assert.equal(renders[1].theme.primary, '#12332e')
    assert.equal(renders[1].theme.text, '#e7eeec')
    renders[1].resolve('<svg>new dark</svg>')
    await vue.nextTick()
    assert.equal(harness.setup.state.svg, '<svg>new dark</svg>')
    assert.match(harness.setup.frameDocument.value, /color-scheme:dark;background:#182221/)
    setThemeMode('light')
    await vue.nextTick()
    assert.equal(renders[2].theme.dark, false)
    assert.equal(renders[2].theme.background, '#ffffff')
    harness.dispose()
    renders[2].resolve('<svg>late</svg>')
    await vue.nextTick()
    assert.equal(harness.setup.state.svg, '')
  } finally { harness.dispose() }
}))

test('rapid theme switches render only final tokens and inactive previews stay invalidated', () => withThemeDom(async () => {
  const renders = []
  const harness = previewHarness((source, theme, context) => { renders.push({ source, theme, context }); return Promise.resolve('<svg>ready</svg>') })
  try {
    setThemeMode('dark')
    setThemeMode('light')
    setThemeMode('dark')
    harness.props.active = false
    await vue.nextTick()
    assert.equal(renders.length, 1)
    assert.equal(renders[0].context.isCurrent(), false)
    harness.props.active = true
    assert.equal(renders.at(-1).theme.dark, true)
    assert.equal(renders.at(-1).theme.background, '#182221')
    await vue.nextTick()
    assert.equal(harness.setup.state.svg, '<svg>ready</svg>')
  } finally { harness.dispose() }
}))

test('actual block preview computes enough iframe height for wide and narrow diagram widths', () => withThemeDom(async () => {
  const harness = previewHarness(async () => '<svg viewBox="0 0 1200 900"></svg>')
  try {
    await vue.nextTick()
    for (const availableWidth of [280, 390, 800, 1440]) {
      harness.setup.width.value = availableWidth
      const diagramHeight = Math.min(availableWidth - 16, 1200) / 1200 * 900
      assert.ok(harness.setup.frameHeight.value >= diagramHeight + 16)
      assert.ok(harness.setup.frameHeight.value < diagramHeight + 26)
    }
  } finally { harness.dispose() }
}))

test('MdPreview adapter reads the initial live dark theme before initialize and follows future themes', () => withThemeDom(async () => {
  const themes = []
  const render = async (_source, theme) => { themes.push(theme); return '<svg></svg>' }
  render.parse = async (_source, theme) => { themes.push(theme); return { diagramType: 'flowchart' } }
  const adapter = createMermaidAdapter(render)
  setThemeMode('dark')
  assert.deepEqual(await adapter.render('id', 'flowchart TD\nA-->B'), { svg: '<svg></svg>' })
  assert.equal(themes[0].dark, true)
  assert.equal(themes[0].background, '#182221')
  adapter.initialize({ theme: 'base' })
  await adapter.parse('flowchart TD\nA-->B')
  assert.equal(themes[1].dark, true)
  setThemeMode('light')
  adapter.initialize({ theme: 'dark' })
  await adapter.render('id', 'flowchart TD\nA-->B')
  assert.equal(themes[2].dark, false)
  assert.equal(themes[2].background, '#ffffff')
}))

test('MdPreview adapter falls back to initialize when no DOM exists', async () => {
  const themes = []
  const render = async (_source, theme) => { themes.push(theme); return '<svg></svg>' }
  render.parse = async (_source, theme) => { themes.push(theme); return { diagramType: 'flowchart' } }
  const adapter = createMermaidAdapter(render)
  adapter.initialize({ theme: 'dark' })
  await adapter.render('id', 'flowchart TD\nA-->B')
  assert.equal(themes[0].dark, true)
  adapter.initialize({ theme: 'base' })
  await adapter.parse('flowchart TD\nA-->B')
  assert.equal(themes[1].dark, false)
})

test('MdPreview iframe HTML takes its canvas from the live application theme', () => withThemeDom(async () => {
  const svg = '<svg viewBox="0 0 120 90"></svg>'
  setThemeMode('dark')
  assert.match(mermaidFrameHtml(svg), /color-scheme:dark;background:#182221/)
  setThemeMode('light')
  assert.match(mermaidFrameHtml(svg), /color-scheme:light;background:#ffffff/)
}))
