const FALLBACK_SIZE = { width: 640, height: 360 }
export const MERMAID_PREVIEW_MAX_HEIGHT = 440

const positive = value => Number.isFinite(value) && value > 0
const intrinsicSize = size => positive(size?.width) && positive(size?.height) ? size : FALLBACK_SIZE
const zoomFactor = zoom => Number.isFinite(zoom) ? Math.min(2, Math.max(0.5, zoom)) : 1

/** Fit the complete SVG before applying zoom; the surrounding viewport owns scrolling. */
export function mermaidViewportSize(size, availableWidth, zoom = 1, options = {}) {
  const intrinsic = intrinsicSize(size)
  const widthBudget = positive(availableWidth) ? availableWidth : FALLBACK_SIZE.width
  const heightBudget = positive(options?.maxHeight) ? options.maxHeight : MERMAID_PREVIEW_MAX_HEIGHT
  const fit = Math.min(1, widthBudget / intrinsic.width, heightBudget / intrinsic.height)
  const magnification = zoomFactor(zoom)
  // Keep both dimensions proportional, including fractional pixels on narrow screens.
  // A minimum size or independent rounding could crop the SVG or distort its aspect ratio.
  return { width: intrinsic.width * fit * magnification, height: intrinsic.height * fit * magnification }
}

/** Keep long or wide diagrams readable; their complete SVG can scroll within the preview. */
export function mermaidReadableSize(size, availableWidth, zoom = 1) {
  const intrinsic = intrinsicSize(size)
  const fitted = mermaidViewportSize(intrinsic, availableWidth)
  const fit = fitted.width / intrinsic.width
  const scale = (fit < 0.6 ? 0.75 : fit) * zoomFactor(zoom)
  return { width: intrinsic.width * scale, height: intrinsic.height * scale }
}
