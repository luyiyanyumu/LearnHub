// Large graph geometry is uploaded only when positions/topology change. Camera
// movement and zoom use uniforms, so the browser does not rebuild thousands of
// SVG elements or upload the same geometry for every pointer event.
const CORNERS = [0, -1, 1, -1, 1, 1, 0, -1, 1, 1, 0, 1]
const NODE_CORNERS = [-1, -1, 1, -1, 1, 1, -1, -1, 1, 1, -1, 1]
const DEFAULT_PALETTE = {
  brand: [0.035, 0.62, 0.56, 1], text: [0.12, 0.19, 0.21, 1],
  border: [0.79, 0.83, 0.85, 1], card: [1, 1, 1, 1], semantic: [0.32, 0.57, 0.55, 1],
}
const finite = (value, fallback = 0) => Number.isFinite(Number(value)) ? Number(value) : fallback
const clamp = (value, lo, hi) => Math.max(lo, Math.min(hi, value))
const minimumRadius = (node) => 3.3 + Math.min(2.5, Math.sqrt(Math.max(0, finite(node.degree))) * 0.5)

/** Shared with hit testing: r is the world radius, the returned value is CSS px. */
export function graphNodeScreenRadius(node, scale = 1) {
  return Math.max(Math.max(0, finite(node.r, 8)) * Math.max(0, finite(scale, 1)), minimumRadius(node))
}

/** Parse already resolved CSS colors or normalized RGBA; never touches the DOM. */
export function parseGraphColor(input, fallback = DEFAULT_PALETTE.brand) {
  if (Array.isArray(input) || ArrayBuffer.isView(input)) {
    return [0, 1, 2, 3].map((i) => clamp(finite(input[i], i === 3 ? 1 : 0), 0, 1))
  }
  const value = String(input || '').trim().toLowerCase()
  if (value === 'transparent') return [0, 0, 0, 0]
  const hex = value.match(/^#([\da-f]{3,8})$/i)
  if (hex && [3, 4, 6, 8].includes(hex[1].length)) {
    const expanded = hex[1].length < 5 ? [...hex[1]].map((c) => c + c).join('') : hex[1]
    return [0, 2, 4, 6].map((i) => i < expanded.length ? parseInt(expanded.slice(i, i + 2), 16) / 255 : 1)
  }
  const rgb = value.match(/^rgba?\((.*)\)$/)
  if (rgb) {
    const parts = rgb[1].replace(/\//g, ' ').split(/[\s,]+/).filter(Boolean)
    if (parts.length >= 3 && parts.every((part) => /^[-+]?\d*\.?\d+%?$/.test(part))) {
      return [0, 1, 2, 3].map((i) => i >= parts.length ? 1 : clamp(parseFloat(parts[i]) / (parts[i].endsWith('%') ? 100 : i === 3 ? 1 : 255), 0, 1))
    }
  }
  const srgb = value.match(/^color\(srgb\s+(.*)\)$/)
  if (srgb) {
    const parts = srgb[1].replace(/\//g, ' ').split(/\s+/).filter(Boolean)
    if (parts.length >= 3 && parts.every((part) => /^[-+]?\d*\.?\d+%?$/.test(part))) {
      return [0, 1, 2, 3].map((i) => i >= parts.length ? 1 : clamp(parseFloat(parts[i]) / (parts[i].endsWith('%') ? 100 : 1), 0, 1))
    }
  }
  const hsl = value.match(/^hsla?\((.*)\)$/)
  if (hsl) {
    const parts = hsl[1].replace(/\//g, ' ').split(/[\s,]+/).filter(Boolean)
    if (parts.length >= 3 && parts.every((part) => /^[-+]?\d*\.?\d+(?:%|deg|turn|rad)?$/.test(part))) {
      let hue = parseFloat(parts[0])
      if (parts[0].endsWith('turn')) hue *= 360
      if (parts[0].endsWith('rad')) hue *= 180 / Math.PI
      hue = ((hue % 360) + 360) % 360 / 60
      const saturation = clamp(parseFloat(parts[1]) / 100, 0, 1)
      const lightness = clamp(parseFloat(parts[2]) / 100, 0, 1)
      const chroma = (1 - Math.abs(2 * lightness - 1)) * saturation
      const secondary = chroma * (1 - Math.abs(hue % 2 - 1))
      const rgbParts = hue < 1 ? [chroma, secondary, 0] : hue < 2 ? [secondary, chroma, 0]
        : hue < 3 ? [0, chroma, secondary] : hue < 4 ? [0, secondary, chroma]
          : hue < 5 ? [secondary, 0, chroma] : [chroma, 0, secondary]
      const offset = lightness - chroma / 2
      return [...rgbParts.map((part) => part + offset), parts.length < 4 ? 1 : clamp(parseFloat(parts[3]) / (parts[3].endsWith('%') ? 100 : 1), 0, 1)]
    }
  }
  const named = { black: [0, 0, 0, 1], white: [1, 1, 1, 1], red: [1, 0, 0, 1], teal: [0, 128 / 255, 128 / 255, 1] }
  return [...(named[value] || fallback)]
}

function normalizedView(view = {}) {
  return { w: Math.max(1, finite(view.w, 1)), h: Math.max(1, finite(view.h, 1)),
    x: finite(view.x), y: finite(view.y), k: Math.max(0.000001, finite(view.k, 1)),
    dpr: clamp(finite(view.dpr, typeof devicePixelRatio === 'number' ? devicePixelRatio : 1), 1, 2) }
}

/** Snapshot input geometry. Invalid/self edges are ignored, parallel edges remain. */
export function prepareGraphGeometry(inputNodes = [], inputEdges = []) {
  const nodes = [], indexById = new Map(), incidentEdges = new Map()
  for (const input of inputNodes) {
    if (input?.id == null || !Number.isFinite(Number(input.x)) || !Number.isFinite(Number(input.y))) continue
    const id = String(input.id)
    if (indexById.has(id)) continue
    const node = { ...input, id, x: Number(input.x), y: Number(input.y),
      r: Math.max(0, finite(input.r, 8)), degree: Math.max(0, finite(input.degree)),
      color: input.color == null ? null : parseGraphColor(input.color) }
    indexById.set(id, nodes.length)
    incidentEdges.set(id, [])
    nodes.push(node)
  }
  const edges = []
  for (const input of inputEdges) {
    const source = String(input?.source), target = String(input?.target)
    if (source === target || !indexById.has(source) || !indexById.has(target)) continue
    incidentEdges.get(source).push(edges.length); incidentEdges.get(target).push(edges.length)
    edges.push({ ...input, source, target, a: nodes[indexById.get(source)], b: nodes[indexById.get(target)],
      semantic: input.kind === 'semantic' || input.origin === 'llm' || input.origin === 'derived',
      dashed: input.kind === 'semantic' || input.origin === 'derived',
      directed: input.kind === 'semantic' || input.origin === 'llm' || input.origin === 'derived' })
  }
  // Interleaved node: center(2), base/minimum radius/tag(3), corner(2), color(4).
  const nodeData = new Float32Array(nodes.length * 6 * 11)
  nodes.forEach((node, index) => {
    const color = node.color || [0, 0, 0, -1]
    for (let vertex = 0; vertex < 6; vertex++) {
      nodeData.set([node.x, node.y, node.r, minimumRadius(node), node.type === 'tag' ? 1 : 0,
        NODE_CORNERS[vertex * 2], NODE_CORNERS[vertex * 2 + 1], ...color], (index * 6 + vertex) * 11)
    }
  })
  // Edge: source center/base/min radius(4), target(4), corner(2), style(3).
  const edgeData = new Float32Array(edges.length * 6 * 13)
  edges.forEach((edge, index) => {
    for (let vertex = 0; vertex < 6; vertex++) {
      edgeData.set([edge.a.x, edge.a.y, edge.a.r, minimumRadius(edge.a),
        edge.b.x, edge.b.y, edge.b.r, minimumRadius(edge.b), CORNERS[vertex * 2], CORNERS[vertex * 2 + 1],
        Number(edge.semantic), Number(edge.dashed), Number(edge.directed)], (index * 6 + vertex) * 13)
    }
  })
  return { nodes, edges, indexById, incidentEdges, nodeData, edgeData }
}

/** Spatial point index. A camera move never rebuilds it, and tests use the same radii as rendering. */
export function createGraphHitIndex(nodes = [], { cellSize = 128 } = {}) {
  const size = Math.max(1, finite(cellSize, 128)), buckets = new Map(), entries = []
  let maxRadius = 0, maxMinimum = 0, minX = Infinity, maxX = -Infinity, minY = Infinity, maxY = -Infinity
  for (const node of nodes) {
    if (!Number.isFinite(Number(node?.x)) || !Number.isFinite(Number(node?.y))) continue
    const entry = { node, x: Number(node.x), y: Number(node.y), r: Math.max(0, finite(node.r, 8)), min: minimumRadius(node), order: entries.length }
    entries.push(entry)
    const x = Math.floor(entry.x / size), y = Math.floor(entry.y / size), key = `${x},${y}`
    if (!buckets.has(key)) buckets.set(key, [])
    buckets.get(key).push(entry)
    minX = Math.min(minX, x); maxX = Math.max(maxX, x); minY = Math.min(minY, y); maxY = Math.max(maxY, y)
    maxRadius = Math.max(maxRadius, entry.r); maxMinimum = Math.max(maxMinimum, entry.min)
  }
  return { size: entries.length,
    hitTest(screenX, screenY, rawView, { padding = 3 } = {}) {
      if (!entries.length || !Number.isFinite(screenX) || !Number.isFinite(screenY)) return null
      const view = normalizedView(rawView), px = (screenX - view.x) / view.k, py = (screenY - view.y) / view.k
      const extra = Math.max(0, finite(padding, 3)), radius = Math.max(maxRadius, maxMinimum / view.k) + extra / view.k
      const left = Math.max(minX, Math.floor((px - radius) / size)), right = Math.min(maxX, Math.floor((px + radius) / size))
      const top = Math.max(minY, Math.floor((py - radius) / size)), bottom = Math.min(maxY, Math.floor((py + radius) / size))
      if (left > right || top > bottom) return null
      let selected = null, distance = Infinity
      const visit = (entry) => {
        const dx = (entry.x - px) * view.k, dy = (entry.y - py) * view.k, squared = dx * dx + dy * dy
        const hitRadius = Math.max(entry.r * view.k, entry.min) + extra
        if (squared <= hitRadius * hitRadius && (squared < distance || squared === distance && entry.order > selected?.order)) {
          selected = entry; distance = squared
        }
      }
      // At extremely small zoom a large world-space circle can span many empty
      // cells. Scan each real node once rather than iterating an unbounded grid.
      if ((right - left + 1) * (bottom - top + 1) > buckets.size * 2) entries.forEach(visit)
      else for (let x = left; x <= right; x++) for (let y = top; y <= bottom; y++) {
        for (const entry of buckets.get(`${x},${y}`) || []) visit(entry)
      }
      return selected?.node || null
    },
  }
}

const NODE_VERTEX = `
attribute vec2 a_position;
attribute vec3 a_radius;
attribute vec2 a_corner;
attribute vec4 a_color;
attribute vec2 a_focus;
uniform vec2 u_size;
uniform vec2 u_offset;
uniform float u_scale;
varying vec2 v_local;
varying float v_radius;
varying float v_tag;
varying vec4 v_color;
varying vec2 v_focus;
void main() {
  float radius = max(a_radius.x * u_scale, a_radius.y);
  v_local = a_corner * (radius + 3.0);
  v_radius = radius;
  v_tag = a_radius.z;
  v_color = a_color;
  v_focus = a_focus;
  vec2 screen = a_position * u_scale + u_offset + v_local;
  gl_Position = vec4(screen.x / u_size.x * 2.0 - 1.0, 1.0 - screen.y / u_size.y * 2.0, 0.0, 1.0);
}`
const NODE_FRAGMENT = `
#ifdef GL_FRAGMENT_PRECISION_HIGH
precision highp float;
#else
precision mediump float;
#endif
uniform vec4 u_brand;
uniform vec4 u_card;
uniform vec4 u_text;
uniform float u_hasFocus;
varying vec2 v_local;
varying float v_radius;
varying float v_tag;
varying vec4 v_color;
varying vec2 v_focus;
void main() {
  float distance = length(v_local);
  float strokeWidth = mix(1.8, 2.5, v_focus.y);
  float coverage = 1.0 - smoothstep(v_radius + strokeWidth * 0.5 - 0.6, v_radius + strokeWidth * 0.5 + 0.6, distance);
  if (coverage <= 0.0) discard;
  vec4 fill = v_color.a < 0.0 ? u_brand : v_color;
  vec4 outline = mix(u_card, u_brand, v_tag);
  outline = mix(outline, u_text, v_focus.y);
  float border = smoothstep(v_radius - strokeWidth * 0.5 - 0.6, v_radius - strokeWidth * 0.5 + 0.6, distance);
  vec4 color = mix(fill, outline, border);
  color.a *= coverage * mix(1.0, mix(0.16, 1.0, v_focus.x), u_hasFocus);
  gl_FragColor = color;
}`
const EDGE_VERTEX = `
attribute vec4 a_source;
attribute vec4 a_target;
attribute vec2 a_corner;
attribute vec3 a_style;
attribute float a_focus;
uniform vec2 u_size;
uniform vec2 u_offset;
uniform float u_scale;
uniform float u_hasFocus;
varying vec3 v_style;
varying float v_focus;
varying float v_along;
varying float v_side;
varying float v_length;
varying float v_arrow;
void main() {
  vec2 source = a_source.xy * u_scale + u_offset;
  vec2 target = a_target.xy * u_scale + u_offset;
  vec2 delta = target - source;
  float distance = max(0.001, length(delta));
  vec2 direction = delta / distance;
  float sourceRadius = max(a_source.z * u_scale, a_source.w) + 2.0;
  float targetRadius = max(a_target.z * u_scale, a_target.w) + 2.0;
  float visibleLength = max(0.0, distance - sourceRadius - targetRadius);
  float arrow = a_style.z * max(a_focus, step(0.8, u_scale)) * step(8.0, visibleLength);
  float halfWidth = mix(1.2, 2.3, a_focus) * 0.5;
  float extent = max(halfWidth + 1.0, arrow * 4.5);
  vec2 start = source + direction * sourceRadius;
  vec2 screen = start + direction * visibleLength * a_corner.x + vec2(-direction.y, direction.x) * a_corner.y * extent;
  v_style = a_style;
  v_focus = a_focus;
  v_length = visibleLength;
  v_along = a_corner.x * visibleLength;
  v_side = a_corner.y * extent;
  v_arrow = arrow;
  gl_Position = vec4(screen.x / u_size.x * 2.0 - 1.0, 1.0 - screen.y / u_size.y * 2.0, 0.0, 1.0);
}`
const EDGE_FRAGMENT = `
#ifdef GL_FRAGMENT_PRECISION_HIGH
precision highp float;
#else
precision mediump float;
#endif
uniform vec4 u_brand;
uniform vec4 u_border;
uniform vec4 u_semantic;
uniform float u_hasFocus;
varying vec3 v_style;
varying float v_focus;
varying float v_along;
varying float v_side;
varying float v_length;
varying float v_arrow;
void main() {
  if (v_length <= 0.0) discard;
  float halfWidth = mix(1.2, 2.3, v_focus) * 0.5;
  float line = 1.0 - smoothstep(halfWidth - 0.5, halfWidth + 0.5, abs(v_side));
  float arrow = 0.0;
  if (v_arrow > 0.5 && v_along >= v_length - 7.0) {
    float width = max(0.0, (v_length - v_along) * 0.5);
    arrow = 1.0 - smoothstep(width - 0.5, width + 0.5, abs(v_side));
    line *= 1.0 - step(v_length - 5.0, v_along);
  }
  if (v_style.y > 0.5) line *= 1.0 - step(5.0, mod(v_along, 9.0));
  float coverage = max(line, arrow);
  if (coverage <= 0.0) discard;
  vec4 color = mix(u_border, u_semantic, v_style.x);
  color = mix(color, u_brand, v_focus);
  float opacity = mix(mix(0.7, 0.45, v_style.x), 0.95, v_focus);
  opacity = mix(opacity, mix(0.07, 0.95, v_focus), u_hasFocus);
  gl_FragColor = vec4(color.rgb, color.a * opacity * coverage);
}`

function shaderProgram(gl, vertexSource, fragmentSource) {
  const shaders = [], program = gl.createProgram()
  try {
    for (const [type, source] of [[gl.VERTEX_SHADER, vertexSource], [gl.FRAGMENT_SHADER, fragmentSource]]) {
      const shader = gl.createShader(type)
      shaders.push(shader)
      gl.shaderSource(shader, source); gl.compileShader(shader)
      if (!gl.getShaderParameter(shader, gl.COMPILE_STATUS)) throw new Error('Graph shader unavailable')
      gl.attachShader(program, shader)
    }
    gl.linkProgram(program)
    if (!gl.getProgramParameter(program, gl.LINK_STATUS)) throw new Error('Graph program unavailable')
    return program
  } catch (error) { gl.deleteProgram(program); throw error }
  finally { shaders.forEach((shader) => gl.deleteShader(shader)) }
}

function createGpu(gl) {
  const programs = [], buffers = []
  try {
    const make = (vertex, fragment, attributes) => {
      const value = shaderProgram(gl, vertex, fragment); programs.push(value)
      return { program: value,
        attributes: Object.fromEntries(attributes.map((name) => [name, gl.getAttribLocation(value, name)])),
        uniforms: Object.fromEntries(['size', 'offset', 'scale', 'hasFocus', 'brand', 'text', 'card', 'border', 'semantic']
          .map((name) => [name, gl.getUniformLocation(value, `u_${name}`)])) }
    }
    const makeBuffer = () => { const value = gl.createBuffer(); if (!value) throw new Error('Graph buffer unavailable'); buffers.push(value); return value }
    const nodeProgram = make(NODE_VERTEX, NODE_FRAGMENT, ['a_position', 'a_radius', 'a_corner', 'a_color', 'a_focus'])
    const edgeProgram = make(EDGE_VERTEX, EDGE_FRAGMENT, ['a_source', 'a_target', 'a_corner', 'a_style', 'a_focus'])
    return { gl, nodeProgram, edgeProgram, programs, buffers,
      enabledAttributes: new Set(),
      nodeBuffer: makeBuffer(), edgeBuffer: makeBuffer(), nodeFocusBuffer: makeBuffer(), edgeFocusBuffer: makeBuffer() }
  } catch (error) {
    programs.forEach((program) => gl.deleteProgram(program)); buffers.forEach((buffer) => gl.deleteBuffer(buffer)); throw error
  }
}

function cssColor(color) { return `rgba(${color.slice(0, 3).map((part) => Math.round(part * 255)).join(',')},${color[3]})` }
function edgeSegment(edge, view, focused) {
  const ax = edge.a.x * view.k + view.x, ay = edge.a.y * view.k + view.y
  const bx = edge.b.x * view.k + view.x, by = edge.b.y * view.k + view.y
  const distance = Math.hypot(bx - ax, by - ay)
  const sourceRadius = graphNodeScreenRadius(edge.a, view.k) + 2, targetRadius = graphNodeScreenRadius(edge.b, view.k) + 2
  const length = distance - sourceRadius - targetRadius
  if (length <= 0) return null
  const dx = (bx - ax) / distance, dy = (by - ay) / distance
  const x1 = ax + dx * sourceRadius, y1 = ay + dy * sourceRadius
  const x2 = bx - dx * targetRadius, y2 = by - dy * targetRadius
  if (Math.max(x1, x2) < -5 || Math.min(x1, x2) > view.w + 5 || Math.max(y1, y2) < -5 || Math.min(y1, y2) > view.h + 5) return null
  return { x1, y1, x2, y2, dx, dy, arrow: edge.directed && (focused || view.k >= 0.8) && length >= 8 }
}

/** WebGL first, Canvas2D fallback; no animation loop or DOM labels are owned here. */
export function createGraphRenderer(canvas, { onTypeChange } = {}) {
  let geometry = prepareGraphGeometry(), gpu = null, context2d = null, fallbackCanvas = null
  let focusId = '', neighborIds = new Set(), nodeFocus = new Float32Array(), edgeFocus = new Float32Array()
  let type = 'unavailable', destroyed = false, lastView = null, lastPalette = null, lost = false
  let gpuGeometryDirty = true, gpuFocusDirty = true
  const changedNodes = new Set(), changedEdges = new Set()
  const changeType = (next) => { if (type !== next) { type = next; onTypeChange?.(next) } }
  function clearGpu() {
    if (!gpu) return
    if (!lost) {
      gpu.buffers.forEach((buffer) => gpu.gl.deleteBuffer(buffer))
      gpu.programs.forEach((program) => gpu.gl.deleteProgram(program))
    }
    gpu = null
  }
  function clearFallback() {
    fallbackCanvas?.remove(); fallbackCanvas = null; context2d = null
  }
  function useFallback() {
    clearGpu()
    if (!context2d) {
      try { context2d = canvas.getContext('2d') } catch { /* A WebGL canvas is permanently bound to that context type. */ }
    }
    if (!context2d && !fallbackCanvas) {
      fallbackCanvas = canvas.ownerDocument?.createElement('canvas') || null
      if (fallbackCanvas) {
        fallbackCanvas.setAttribute('aria-hidden', 'true')
        Object.assign(fallbackCanvas.style, { position: 'absolute', inset: '0', width: '100%', height: '100%', pointerEvents: 'none', display: 'block' })
        canvas.parentNode?.insertBefore(fallbackCanvas, canvas.nextSibling)
        try { context2d = fallbackCanvas.getContext('2d') } catch { context2d = null }
      }
    }
    changeType(context2d ? 'canvas2d' : 'unavailable')
  }
  function initialize() {
    if (destroyed) return
    let gl = null
    try { gl = canvas.getContext('webgl', { alpha: true, antialias: true, premultipliedAlpha: true, powerPreference: 'high-performance' }) } catch { /* Browser/driver can refuse WebGL. */ }
    if (gl && !gl.isContextLost?.()) {
      try {
        gpu = createGpu(gl); clearFallback(); gpuGeometryDirty = true; gpuFocusDirty = true
        changeType('webgl'); return
      } catch { clearGpu() }
    }
    useFallback()
  }
  function updateFocus() {
    nodeFocus = new Float32Array(geometry.nodes.length * 6 * 2)
    edgeFocus = new Float32Array(geometry.edges.length * 6)
    geometry.nodes.forEach((node, index) => {
      const active = node.id === focusId ? 1 : 0, adjacent = !focusId || active || neighborIds.has(node.id) ? 1 : 0
      for (let vertex = 0; vertex < 6; vertex++) nodeFocus.set([adjacent, active], (index * 6 + vertex) * 2)
    })
    geometry.edges.forEach((edge, index) => edgeFocus.fill(focusId && (edge.source === focusId || edge.target === focusId) ? 1 : 0, index * 6, index * 6 + 6))
    gpuFocusDirty = true
  }
  function upload(buffer, data, usage) { gpu.gl.bindBuffer(gpu.gl.ARRAY_BUFFER, buffer); gpu.gl.bufferData(gpu.gl.ARRAY_BUFFER, data, usage) }
  function attribute(program, name, buffer, size, stride = 0, offset = 0) {
    const gl = gpu.gl, location = program.attributes[name]
    if (location < 0) return
    gl.bindBuffer(gl.ARRAY_BUFFER, buffer); gl.enableVertexAttribArray(location)
    gl.vertexAttribPointer(location, size, gl.FLOAT, false, stride * 4, offset * 4)
    gpu.enabledAttributes.add(location)
  }
  function useProgram(program) {
    const locations = new Set(Object.values(program.attributes).filter((location) => location >= 0))
    for (const location of gpu.enabledAttributes) if (!locations.has(location)) {
      gpu.gl.disableVertexAttribArray(location); gpu.enabledAttributes.delete(location)
    }
    gpu.gl.useProgram(program.program)
  }
  function uniforms(program, view, palette) {
    const gl = gpu.gl, location = program.uniforms
    gl.uniform2f(location.size, view.w, view.h); gl.uniform2f(location.offset, view.x, view.y)
    gl.uniform1f(location.scale, view.k); gl.uniform1f(location.hasFocus, focusId ? 1 : 0)
    for (const key of ['brand', 'text', 'card', 'border', 'semantic']) gl.uniform4fv(location[key], palette[key])
  }
  function drawGpu(view, palette, target) {
    const gl = gpu.gl
    if (gpuGeometryDirty) {
      upload(gpu.nodeBuffer, geometry.nodeData, gl.STATIC_DRAW); upload(gpu.edgeBuffer, geometry.edgeData, gl.STATIC_DRAW)
      gpuGeometryDirty = false
      changedNodes.clear(); changedEdges.clear()
    } else {
      // Dragging one node updates its six vertices and only incident edges.
      // Sets coalesce multiple pointer events before the parent's next frame.
      if (changedNodes.size) {
        gl.bindBuffer(gl.ARRAY_BUFFER, gpu.nodeBuffer)
        for (const index of changedNodes) gl.bufferSubData(gl.ARRAY_BUFFER, index * 66 * 4, geometry.nodeData.subarray(index * 66, (index + 1) * 66))
        changedNodes.clear()
      }
      if (changedEdges.size) {
        gl.bindBuffer(gl.ARRAY_BUFFER, gpu.edgeBuffer)
        for (const index of changedEdges) gl.bufferSubData(gl.ARRAY_BUFFER, index * 78 * 4, geometry.edgeData.subarray(index * 78, (index + 1) * 78))
        changedEdges.clear()
      }
    }
    if (gpuFocusDirty) {
      upload(gpu.nodeFocusBuffer, nodeFocus, gl.DYNAMIC_DRAW); upload(gpu.edgeFocusBuffer, edgeFocus, gl.DYNAMIC_DRAW)
      gpuFocusDirty = false
    }
    gl.viewport(0, 0, target.width, target.height); gl.clearColor(0, 0, 0, 0); gl.clear(gl.COLOR_BUFFER_BIT)
    // Shader colors are straight RGBA, while the canvas compositor consumes a
    // premultiplied backing buffer. RGB is weighted once; alpha accumulates
    // independently instead of being squared for faint/dim graph elements.
    gl.enable(gl.BLEND); gl.blendFuncSeparate(gl.SRC_ALPHA, gl.ONE_MINUS_SRC_ALPHA, gl.ONE, gl.ONE_MINUS_SRC_ALPHA); gl.disable(gl.DEPTH_TEST)
    const edge = gpu.edgeProgram
    useProgram(edge); uniforms(edge, view, palette)
    attribute(edge, 'a_source', gpu.edgeBuffer, 4, 13, 0); attribute(edge, 'a_target', gpu.edgeBuffer, 4, 13, 4)
    attribute(edge, 'a_corner', gpu.edgeBuffer, 2, 13, 8); attribute(edge, 'a_style', gpu.edgeBuffer, 3, 13, 10)
    attribute(edge, 'a_focus', gpu.edgeFocusBuffer, 1)
    gl.drawArrays(gl.TRIANGLES, 0, geometry.edges.length * 6)
    const node = gpu.nodeProgram
    useProgram(node); uniforms(node, view, palette)
    attribute(node, 'a_position', gpu.nodeBuffer, 2, 11, 0); attribute(node, 'a_radius', gpu.nodeBuffer, 3, 11, 2)
    attribute(node, 'a_corner', gpu.nodeBuffer, 2, 11, 5); attribute(node, 'a_color', gpu.nodeBuffer, 4, 11, 7)
    attribute(node, 'a_focus', gpu.nodeFocusBuffer, 2)
    gl.drawArrays(gl.TRIANGLES, 0, geometry.nodes.length * 6)
  }
  function drawCanvas(view, palette, target) {
    const ctx = context2d
    ctx.setTransform(view.dpr, 0, 0, view.dpr, 0, 0); ctx.clearRect(0, 0, view.w, view.h)
    const groups = new Map()
    for (let i = 0; i < geometry.edges.length; i++) {
      const edge = geometry.edges[i], focused = edgeFocus[i * 6] === 1, segment = edgeSegment(edge, view, focused)
      if (!segment) continue
      const key = `${Number(edge.semantic)}${Number(edge.dashed)}${Number(focused)}`
      if (!groups.has(key)) groups.set(key, { edge, focused, segments: [] })
      groups.get(key).segments.push(segment)
    }
    for (const { edge, focused, segments } of groups.values()) {
      const color = focused ? palette.brand : edge.semantic ? palette.semantic : palette.border
      ctx.strokeStyle = cssColor(color); ctx.fillStyle = cssColor(color)
      ctx.globalAlpha = focusId ? focused ? 0.95 : 0.07 : edge.semantic ? 0.45 : 0.7
      ctx.lineWidth = focused ? 2.3 : 1.2; ctx.setLineDash(edge.dashed ? [5, 4] : []); ctx.beginPath()
      for (const s of segments) { ctx.moveTo(s.x1, s.y1); ctx.lineTo(s.x2 - (s.arrow ? s.dx * 5 : 0), s.y2 - (s.arrow ? s.dy * 5 : 0)) }
      ctx.stroke(); ctx.setLineDash([]); ctx.beginPath()
      for (const s of segments) if (s.arrow) {
        ctx.moveTo(s.x2, s.y2); ctx.lineTo(s.x2 - s.dx * 7 - s.dy * 3.5, s.y2 - s.dy * 7 + s.dx * 3.5)
        ctx.lineTo(s.x2 - s.dx * 7 + s.dy * 3.5, s.y2 - s.dy * 7 - s.dx * 3.5); ctx.closePath()
      }
      ctx.fill()
    }
    for (let i = 0; i < geometry.nodes.length; i++) {
      const node = geometry.nodes[i], x = node.x * view.k + view.x, y = node.y * view.k + view.y, radius = graphNodeScreenRadius(node, view.k)
      if (x + radius + 3 < 0 || x - radius - 3 > view.w || y + radius + 3 < 0 || y - radius - 3 > view.h) continue
      const active = nodeFocus[i * 12 + 1] === 1
      ctx.globalAlpha = focusId && nodeFocus[i * 12] === 0 ? 0.16 : 1
      ctx.fillStyle = cssColor(node.color || palette.brand); ctx.strokeStyle = cssColor(active ? palette.text : node.type === 'tag' ? palette.brand : palette.card)
      ctx.lineWidth = active ? 2.5 : 1.8; ctx.beginPath(); ctx.arc(x, y, radius, 0, Math.PI * 2); ctx.fill(); ctx.stroke()
    }
    ctx.globalAlpha = 1
  }
  function render(rawView, rawPalette = {}) {
    if (destroyed) return type
    const view = normalizedView(rawView), palette = {}
    for (const key of Object.keys(DEFAULT_PALETTE)) palette[key] = parseGraphColor(rawPalette[key], DEFAULT_PALETTE[key])
    lastView = view; lastPalette = palette
    const target = fallbackCanvas || canvas, width = Math.ceil(view.w * view.dpr), height = Math.ceil(view.h * view.dpr)
    if (target.width !== width) target.width = width
    if (target.height !== height) target.height = height
    if (gpu) drawGpu(view, palette, target)
    else if (context2d) drawCanvas(view, palette, target)
    return type
  }
  function onLost(event) {
    event.preventDefault(); if (destroyed) return
    lost = true; useFallback(); if (lastView) render(lastView, lastPalette)
  }
  function onRestored() {
    if (destroyed) return
    lost = false; clearGpu(); initialize(); if (lastView) render(lastView, lastPalette)
  }
  canvas.addEventListener?.('webglcontextlost', onLost)
  canvas.addEventListener?.('webglcontextrestored', onRestored)
  initialize()
  return { get type() { return type },
    setGraph(nodes, edges) {
      if (destroyed) return
      geometry = prepareGraphGeometry(nodes, edges); gpuGeometryDirty = true; changedNodes.clear(); changedEdges.clear(); updateFocus()
    },
    updateNodePosition(id, x, y) {
      if (destroyed || !Number.isFinite(x) || !Number.isFinite(y)) return false
      const index = geometry.indexById.get(String(id))
      if (index == null) return false
      const node = geometry.nodes[index]
      if (node.x === x && node.y === y) return false
      node.x = x; node.y = y
      for (let vertex = 0; vertex < 6; vertex++) {
        const offset = (index * 6 + vertex) * 11
        geometry.nodeData[offset] = x; geometry.nodeData[offset + 1] = y
      }
      changedNodes.add(index)
      for (const edgeIndex of geometry.incidentEdges.get(node.id)) {
        const edge = geometry.edges[edgeIndex], offset = node.id === edge.source ? 0 : 4
        for (let vertex = 0; vertex < 6; vertex++) {
          const start = (edgeIndex * 6 + vertex) * 13 + offset
          geometry.edgeData[start] = x; geometry.edgeData[start + 1] = y
        }
        changedEdges.add(edgeIndex)
      }
      return true
    },
    setFocus(id, ids = []) {
      if (destroyed) return
      const nextId = id == null ? '' : String(id), nextIds = new Set(Array.from(ids || [], String))
      if (nextId === focusId && nextIds.size === neighborIds.size && [...nextIds].every((id) => neighborIds.has(id))) return
      focusId = nextId; neighborIds = nextIds; updateFocus()
    },
    render,
    destroy() {
      if (destroyed) return
      destroyed = true; clearGpu(); clearFallback()
      canvas.removeEventListener?.('webglcontextlost', onLost); canvas.removeEventListener?.('webglcontextrestored', onRestored)
      geometry = prepareGraphGeometry(); nodeFocus = new Float32Array(); edgeFocus = new Float32Array(); lastView = null; lastPalette = null
      changedNodes.clear(); changedEdges.clear()
      changeType('unavailable')
    },
  }
}
