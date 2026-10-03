import { Extension } from '@tiptap/core'
import { isBlockId } from './blockMeta.js'

export function findNoteAnchor(root, href) {
  if (!root || !href?.startsWith('#') || href === '#') return null
  let id
  try { id = decodeURIComponent(href.slice(1)) } catch { return null }
  return [...root.querySelectorAll('[id]')].find(element => element.id === id) || null
}

/** Markdown 标题锚点在 Tiptap 内保留；序列化回 Markdown 时仍由原有 htmlToMd 规则去掉。 */
export const HeadingAnchorAttr = Extension.create({
  name: 'headingAnchor',
  addGlobalAttributes() {
    return [{ types: ['heading'], attributes: {
      headingAnchor: {
        default: null,
        keepOnSplit: false,
        parseHTML: element => element.id && !isBlockId(element.id) ? element.id : null,
        renderHTML: attrs => attrs.headingAnchor && !isBlockId(attrs.blockId) ? { id: attrs.headingAnchor } : {},
      },
    } }]
  },
})
