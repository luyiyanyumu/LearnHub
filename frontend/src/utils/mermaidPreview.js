export const MERMAID_MAX_SOURCE = 30000
export const MERMAID_MAX_EDGES = 300

let runtimePromise
let diagramId = 0

async function loadRuntime() {
  if (!runtimePromise) {
    runtimePromise = Promise.all([import('mermaid'), import('dompurify')])
      .then(([diagram, purifier]) => ({ mermaid: diagram.default, purifier: purifier.default }))
      .catch(error => { runtimePromise = null; throw error })
  }
  return runtimePromise
}

export function mermaidConfig(theme = {}) {
  return {
    startOnLoad: false,
    securityLevel: 'strict',
    suppressErrorRendering: true,
    maxTextSize: MERMAID_MAX_SOURCE,
    maxEdges: MERMAID_MAX_EDGES,
    htmlLabels: false,
    theme: 'base',
    fontFamily: 'system-ui, sans-serif',
    // Diagram directives/frontmatter must not relax the site security or inject CSS.
    secure: ['secure', 'securityLevel', 'startOnLoad', 'suppressErrorRendering', 'maxTextSize', 'maxEdges',
      'htmlLabels', 'theme', 'themeVariables', 'themeCSS', 'dompurifyConfig', 'fontFamily'],
    themeVariables: {
      darkMode: Boolean(theme.dark),
      background: theme.background || '#ffffff',
      primaryColor: theme.primary || '#e2f5f1',
      primaryTextColor: theme.text || '#1f2d2a',
      primaryBorderColor: theme.brand || '#0e9f8e',
      lineColor: theme.muted || '#5a6b67',
      secondaryColor: theme.background || '#ffffff',
      secondaryTextColor: theme.text || '#1f2d2a',
      tertiaryColor: theme.primary || '#e2f5f1',
      tertiaryTextColor: theme.text || '#1f2d2a',
      edgeLabelBackground: theme.background || '#ffffff',
      textColor: theme.text || '#1f2d2a',
    },
    flowchart: { useMaxWidth: true },
    sequence: { useMaxWidth: true },
  }
}

export function readMermaidTheme(dark = false) {
  const style = typeof document !== 'undefined' && typeof getComputedStyle === 'function'
    ? getComputedStyle(document.documentElement) : null
  const value = name => style?.getPropertyValue(name)?.trim() || undefined
  return { dark, background: value('--app-card'), primary: value('--app-brand-soft'), text: value('--app-text-1'),
    brand: value('--app-brand'), muted: value('--app-text-2') }
}

export function mermaidSource(source) {
  const value = typeof source === 'string' ? source : ''
  if (!value.trim()) throw new Error('流程图源码为空，请切换到源码后填写 Mermaid 内容。')
  if (value.length > MERMAID_MAX_SOURCE) throw new Error(`流程图超过 ${MERMAID_MAX_SOURCE} 字符，请精简后预览。`)
  // Mermaid's image shape loads images before producing SVG, even in strict mode.
  // Final sanitization alone cannot prevent that request in its measuring DOM.
  if (/(?:\bimg\b|["']img["'])\s*:/i.test(value) || /!\[[^\]]*\]\s*\(/.test(value)
    || /<\s*(?:img|image|iframe|object|embed)\b/i.test(value)
    || /url\s*\(|@import|\b(?:style|classDef|linkStyle)\b[^\n]*\\/i.test(value)) {
    throw new Error('流程图暂不支持图片或外部样式资源，请使用文字节点。')
  }
  return value
}

export function sanitizeMermaidSvg(svg, purifier) {
  if (typeof svg !== 'string' || !purifier?.isSupported) throw new Error('当前浏览器无法安全显示流程图。')
  const clean = purifier.sanitize(svg, {
    USE_PROFILES: { svg: true, svgFilters: true },
    FORBID_TAGS: ['script', 'foreignObject', 'iframe', 'image', 'a', 'animate', 'animateMotion', 'animateTransform', 'set'],
    FORBID_ATTR: ['href', 'xlink:href'],
    ALLOW_DATA_ATTR: false,
  })
  if (!/^\s*<svg\b/i.test(clean)) throw new Error('流程图未生成有效的 SVG，请检查源码。')
  return clean
}

function makeRenderContainer() {
  const element = document.createElement('div')
  element.setAttribute('aria-hidden', 'true')
  // Mermaid measures labels in a real DOM. Keep its temporary nodes out of the editor.
  Object.assign(element.style, { position: 'fixed', left: '-100000px', top: '0', width: '1200px', visibility: 'hidden', pointerEvents: 'none' })
  document.body.appendChild(element)
  return element
}

/** Mermaid has global configuration; serialize initialize/render across every preview. */
export function createMermaidRenderer({ load = loadRuntime, sanitize = sanitizeMermaidSvg, createContainer = makeRenderContainer } = {}) {
  let queue = Promise.resolve()
  const schedule = (source, theme, { isCurrent = () => true } = {}, parseOptions) => {
    const operation = async () => {
      if (!isCurrent()) return null
      const code = mermaidSource(source)
      const runtime = await load()
      if (!isCurrent()) return null
      runtime.mermaid.initialize(mermaidConfig(theme))
      if (parseOptions) return runtime.mermaid.parse(code, parseOptions)
      let container
      try {
        container = createContainer()
        const { svg } = await runtime.mermaid.render(`learnhub-mermaid-${++diagramId}`, code, container)
        if (!isCurrent()) return null
        return sanitize(svg, runtime.purifier)
      } finally { container?.remove() }
    }
    const result = queue.then(operation, operation)
    queue = result.catch(() => {})
    return result
  }
  const render = (source, theme, options) => schedule(source, theme, options)
  render.parse = (source, theme, options = {}) => schedule(source, theme, {}, options)
  return render
}

export const renderMermaid = createMermaidRenderer()

/** md-editor's synchronous initialize cannot mutate a pending block render. */
export function createMermaidAdapter(render = renderMermaid) {
  let adapterDark = false
  const theme = () => {
    // An injected md-editor instance may render before it calls initialize.
    const root = typeof document !== 'undefined' ? document.documentElement : null
    const dark = root?.classList?.contains ? root.classList.contains('dark') : adapterDark
    return readMermaidTheme(dark)
  }
  return {
    initialize(config = {}) { adapterDark = config.theme === 'dark' || Boolean(config.themeVariables?.darkMode) },
    async render(_id, source, _container) { return { svg: await render(source, theme()) } },
    parse(source, options = {}) { return render.parse(source, theme(), options) },
  }
}
export const mermaidAdapter = createMermaidAdapter()

/** A stale render or parse error must never replace the newest diagram. */
export function createMermaidPreviewState({ render = renderMermaid, onState = () => {} } = {}) {
  let generation = 0
  let disposed = false
  return {
    async update(source, theme) {
      const version = ++generation
      const isCurrent = () => !disposed && version === generation
      if (disposed) return
      onState({ loading: true, svg: '', error: '' })
      try {
        const svg = await render(source, theme, { isCurrent })
        if (isCurrent() && svg) onState({ loading: false, svg, error: '' })
      } catch (error) {
        if (isCurrent()) onState({ loading: false, svg: '', error: mermaidErrorMessage(error) })
      }
    },
    invalidate() { generation++; if (!disposed) onState({ loading: false }) },
    dispose() { disposed = true; generation++ },
  }
}

export function mermaidErrorMessage(error) {
  const detail = String(error?.message || '请检查 Mermaid 语法。').replace(/\u001b\[[0-9;]*m/g, '').slice(0, 700)
  return `流程图暂时无法显示，源码已保留。${detail}`
}

export function mermaidSvgSize(svg) {
  const tag = String(svg || '').match(/<svg\b[^>]*>/i)?.[0] || ''
  const viewBox = tag.match(/\bviewBox\s*=\s*["']([^"']+)["']/i)?.[1]?.trim().split(/[\s,]+/).map(Number)
  if (viewBox?.length === 4 && viewBox.every(Number.isFinite) && viewBox[2] > 0 && viewBox[3] > 0) {
    return { width: viewBox[2], height: viewBox[3] }
  }
  return { width: 640, height: 360 }
}

/** SVG styles and diagram labels stay inside an opaque, script-free document. */
export function mermaidFrameDocument(svg, { padding = 8, dark = false, background } = {}) {
  const safePadding = Number.isFinite(padding) ? Math.max(0, Math.min(24, padding)) : 8
  // An opaque document background avoids the sandbox's default white canvas.
  // Keep only opaque hex colors; never interpolate arbitrary CSS tokens here.
  const color = typeof background === 'string' ? background.trim() : ''
  const safeBackground = /^#(?:[\da-f]{3}|[\da-f]{6})$/i.test(color) ? color : dark ? '#182221' : '#ffffff'
  return `<!doctype html><html><head><meta charset="utf-8"><meta http-equiv="Content-Security-Policy" content="default-src 'none'; style-src 'unsafe-inline'; img-src 'none'; font-src 'none'; base-uri 'none'; form-action 'none'"><style>html,body{margin:0;color-scheme:${dark ? 'dark' : 'light'};background:${safeBackground}}body{padding:${safePadding}px;box-sizing:border-box}svg{display:block;max-width:100%;height:auto;margin:0 auto}</style></head><body>${svg}</body></html>`
}

export function mermaidFrameHtml(svg) {
  const { width, height } = mermaidSvgSize(svg)
  const escapeAttribute = value => value.replace(/&/g, '&amp;').replace(/"/g, '&quot;').replace(/</g, '&lt;').replace(/>/g, '&gt;')
  const dark = typeof document !== 'undefined' && Boolean(document.documentElement?.classList?.contains('dark'))
  const theme = readMermaidTheme(dark)
  // MdPreview can be wider than 640px. Let CSS follow the full diagram ratio;
  // zero inner padding keeps that ratio exact even on narrow screens.
  return `<iframe title="Mermaid 流程图预览" role="img" sandbox="" referrerpolicy="no-referrer" style="display:block;width:100%;height:auto;aspect-ratio:${width}/${height};max-width:${width}px;margin:0 auto;border:0" srcdoc="${escapeAttribute(mermaidFrameDocument(svg, { ...theme, padding: 0 }))}"></iframe>`
}
