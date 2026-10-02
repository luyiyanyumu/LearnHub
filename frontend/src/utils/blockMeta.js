import { Extension } from '@tiptap/core'
import { Plugin, PluginKey } from '@tiptap/pm/state'
import { Mapping } from '@tiptap/pm/transform'

export const MAX_BLOCK_INDENT = 6
const BLOCK_TYPES = ['paragraph', 'heading']
const INDENT_EM = 2

export function normalizeBlockIndent(value) {
  const number = Number(value)
  return Number.isFinite(number) ? Math.max(0, Math.min(MAX_BLOCK_INDENT, Math.floor(number))) : 0
}

/** Only ids created for block links are persisted; generated heading slugs stay Markdown. */
export function isBlockId(value) {
  return typeof value === 'string' && /^block-[a-zA-Z0-9][a-zA-Z0-9_-]{0,100}$/.test(value)
}

export function createBlockId() {
  const uuid = globalThis.crypto?.randomUUID?.()
  return `block-${uuid || `${Date.now().toString(36)}-${Math.random().toString(36).slice(2, 12)}`}`
}

export function parseBlockIndent(element) {
  const level = element.getAttribute('data-block-indent')
  if (level != null && /^\d+$/.test(level)) return normalizeBlockIndent(level)
  const margin = element.style?.marginLeft
    || (element.getAttribute('style') || '').match(/(?:^|;)\s*margin-left\s*:\s*([^;]+)/i)?.[1]
    || ''
  const match = String(margin).trim().match(/^(\d+(?:\.\d+)?)\s*(em|px)$/i)
  if (!match) return 0
  return normalizeBlockIndent(Number(match[1]) / (match[2].toLowerCase() === 'em' ? INDENT_EM : 32))
}

/** Splitting or pasting a linked paragraph must not leave two identical link targets. */
export function duplicateBlockIdUpdates(doc, nextId = createBlockId, retainedPositions = new Map()) {
  const seen = new Set()
  const reserved = new Set()
  const updates = []
  doc.descendants((node) => {
    if (BLOCK_TYPES.includes(node.type.name) && isBlockId(node.attrs.blockId)) reserved.add(node.attrs.blockId)
  })
  doc.descendants((node, pos) => {
    if (!BLOCK_TYPES.includes(node.type.name) || !isBlockId(node.attrs.blockId)) return
    const retained = retainedPositions.get(node.attrs.blockId)
    if (retained === pos || (retained == null && !seen.has(node.attrs.blockId))) {
      seen.add(node.attrs.blockId)
      return
    }
    let blockId
    do { blockId = nextId() } while (!isBlockId(blockId) || reserved.has(blockId))
    reserved.add(blockId)
    updates.push({ pos, node, blockId })
  })
  return updates
}

/** Paragraph indentation and permanent block links survive Markdown/HTML round trips. */
export const BlockMeta = Extension.create({
  name: 'blockMeta',
  addGlobalAttributes() {
    return [{
      types: BLOCK_TYPES,
      attributes: {
        blockId: {
          default: null,
          // A new block created by Enter should not inherit the preceding block's link.
          keepOnSplit: false,
          parseHTML: (element) => isBlockId(element.id) ? element.id : null,
          renderHTML: (attrs) => isBlockId(attrs.blockId) ? { id: attrs.blockId } : {},
        },
        indent: {
          default: 0,
          parseHTML: parseBlockIndent,
          renderHTML: (attrs) => {
            const indent = normalizeBlockIndent(attrs.indent)
            return indent ? { 'data-block-indent': String(indent), style: `margin-left: ${indent * INDENT_EM}em` } : {}
          },
        },
      },
    }]
  },
  addProseMirrorPlugins() {
    return [new Plugin({
      key: new PluginKey('blockMetaUniqueIds'),
      appendTransaction(transactions, oldState, newState) {
        if (!transactions.some((transaction) => transaction.docChanged)) return null
        // Keep links on the existing original even when its pasted copy precedes it.
        const mapping = new Mapping()
        for (const transaction of transactions) mapping.appendMapping(transaction.mapping)
        const retained = new Map()
        oldState.doc.descendants((node, pos) => {
          if (!BLOCK_TYPES.includes(node.type.name) || !isBlockId(node.attrs.blockId) || retained.has(node.attrs.blockId)) return
          const mapped = mapping.mapResult(pos, 1)
          if (!mapped.deleted && newState.doc.nodeAt(mapped.pos)?.attrs.blockId === node.attrs.blockId) {
            retained.set(node.attrs.blockId, mapped.pos)
          }
        })
        const updates = duplicateBlockIdUpdates(newState.doc, createBlockId, retained)
        if (!updates.length) return null
        const transaction = newState.tr
        for (const { pos, node, blockId } of updates) {
          transaction.setNodeMarkup(pos, undefined, { ...node.attrs, blockId })
        }
        return transaction.setMeta('addToHistory', false)
      },
    })]
  },
})
