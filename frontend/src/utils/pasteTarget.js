/** Only the source editor that received the paste may handle its clipboard. */
export function isPasteInEditor(target, view) {
  const root = view?.dom
  if (!target || typeof root?.contains !== 'function') return false
  try {
    if (!root.contains(target)) return false
    // Event targets may be text nodes or come from another window's DOM.
    const element = target.nodeType === 1 ? target : target.parentElement
    if (!element) return false
    const owner = element.closest?.('.cm-editor')
    if (owner && owner !== root) return false
    const preview = element.closest?.('.pane-preview, .block-preview, .md-editor-preview, .ProseMirror')
    if (preview && root.contains(preview)) return false
    // CodeMirror exposes the actual editable surface separately from its UI.
    if (view.contentDOM && !view.contentDOM.contains(target)) return false
    return true
  } catch {
    // A missing or stale DOM target must never redirect paste to another editor.
    return false
  }
}
