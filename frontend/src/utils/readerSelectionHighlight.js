import { READER_SELECTION_IGNORE, selectedReaderText } from './readerTranslation.js'

export const READER_HIGHLIGHT_NAME = 'learnhub-reader-selection'
const highlights = new WeakMap()

/** Keep the translation's source marked without wrapping or changing document text. */
export function createReaderSelectionHighlight(view = globalThis.window, onRects = () => {}) {
  let entries = []
  let observer = null
  let highlight = null
  let stopPainting = null
  let refresh = null
  const registry = view?.CSS?.highlights
  const supported = registry && typeof view.Highlight === 'function'

  function clear() {
    observer?.disconnect()
    observer = null
    stopPainting?.()
    stopPainting = null
    refresh = null
    entries.forEach(({ range }) => highlight?.delete(range))
    entries = []
    onRects([])
    if (highlight?.size === 0 && registry?.get(READER_HIGHLIGHT_NAME) === highlight) {
      registry.delete(READER_HIGHLIGHT_NAME)
    }
  }

  function set(selection, root) {
    clear()
    if (!selectedReaderText(selection, root)) return
    const document = root.ownerDocument
    // Use text-only ranges so selections crossing toolbar/page labels never mark them.
    for (let i = 0; i < selection.rangeCount; i++) {
      const selected = selection.getRangeAt(i)
      const walker = document.createTreeWalker(selected.commonAncestorContainer, 4 /* SHOW_TEXT */)
      let node = selected.commonAncestorContainer.nodeType === 3 ? selected.commonAncestorContainer : walker.nextNode()
      while (node) {
        if (!node.parentElement?.closest(READER_SELECTION_IGNORE) && selected.intersectsNode(node)) {
          const start = selected.startContainer === node ? selected.startOffset : 0
          const end = selected.endContainer === node ? selected.endOffset : node.length
          if (end > start) {
            const range = document.createRange()
            range.setStart(node, start)
            range.setEnd(node, end)
            entries.push({ range, node, text: node.textContent })
          }
        }
        node = walker.nextNode()
      }
    }
    if (!entries.length) return
    if (supported) {
      highlight = highlights.get(view)
      if (!highlight) {
        highlight = new view.Highlight()
        highlights.set(view, highlight)
      }
      entries.forEach(({ range }) => highlight.add(range))
      registry.set(READER_HIGHLIGHT_NAME, highlight)
    } else {
      // Older WebViews lack Custom Highlight. Paint a pointer-transparent layer
      // outside the original document instead, following line wrapping and scroll.
      const paint = () => {
        const clips = new Map()
        const rects = entries.flatMap(({ range, node }) => {
          let bounds = root.getBoundingClientRect()
          for (let parent = node.parentElement; parent && parent !== root; parent = parent.parentElement) {
            if (!clips.has(parent)) {
              const style = view.getComputedStyle(parent)
              clips.set(parent, { rect: parent.getBoundingClientRect(), x: /auto|scroll|hidden|clip/.test(style.overflowX), y: /auto|scroll|hidden|clip/.test(style.overflowY) })
            }
            const clip = clips.get(parent)
            bounds = {
              left: clip.x ? Math.max(bounds.left, clip.rect.left) : bounds.left,
              right: clip.x ? Math.min(bounds.right, clip.rect.right) : bounds.right,
              top: clip.y ? Math.max(bounds.top, clip.rect.top) : bounds.top,
              bottom: clip.y ? Math.min(bounds.bottom, clip.rect.bottom) : bounds.bottom,
            }
          }
          return Array.from(range.getClientRects()).map((rect) => {
            const left = Math.max(rect.left, bounds.left)
            const top = Math.max(rect.top, bounds.top)
            return { left, top, width: Math.min(rect.right, bounds.right) - left, height: Math.min(rect.bottom, bounds.bottom) - top }
          }).filter((rect) => rect.width > 0 && rect.height > 0)
        })
        onRects(rects)
      }
      let frame = null
      const schedule = () => {
        if (frame !== null) return
        frame = view.requestAnimationFrame(() => { frame = null; paint() })
      }
      refresh = schedule
      root.ownerDocument.addEventListener('scroll', schedule, true)
      view.addEventListener('resize', schedule)
      const resize = view.ResizeObserver ? new view.ResizeObserver(schedule) : null
      resize?.observe(root)
      stopPainting = () => {
        if (frame !== null) view.cancelAnimationFrame(frame)
        root.ownerDocument.removeEventListener('scroll', schedule, true)
        view.removeEventListener('resize', schedule)
        resize?.disconnect()
      }
      paint()
    }
    // PDF zoom/page changes replace text-layer nodes. A live Range migrates to its
    // parent on removal, so retain node identities to avoid marking unrelated text.
    if (view.MutationObserver) {
      observer = new view.MutationObserver(() => {
        if (entries.some(({ range, node, text }) => range.collapsed || !root.contains(node) || node.textContent !== text)) clear()
        else refresh?.()
      })
      observer.observe(root, { childList: true, subtree: true, characterData: true })
    }
  }

  return { set, clear, get mode() { return supported ? 'native' : 'overlay' }, get size() { return entries.length } }
}
