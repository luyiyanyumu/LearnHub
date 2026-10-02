import { DOMSerializer, Fragment } from '@tiptap/pm/model'
import { NodeSelection, Selection, TextSelection } from '@tiptap/pm/state'
import { liftListItem, sinkListItem } from '@tiptap/pm/schema-list'
import { closeHistory } from '@tiptap/pm/history'

const lists = new Set(['bulletList', 'orderedList', 'taskList'])
const wrappers = new Set(['blockquote', 'callout', 'details'])
const itemNames = new Set(['listItem', 'taskItem'])

function contextAt(editor, pos) {
  const { doc } = editor.state
  if (!Number.isInteger(pos) || pos < 0 || pos >= doc.content.size) return null
  const node = doc.nodeAt(pos)
  if (!node?.isBlock) return null
  const $pos = doc.resolve(pos)
  return { pos, node, $pos, parent: $pos.parent, index: $pos.index() }
}

/** Resolve the document block, never a table cell or a code-editor decoration. */
export function findActionBlock(view, domTarget) {
  if (!view?.dom || !domTarget || !view.dom.contains(domTarget)) return null
  let element = domTarget.nodeType === 1 ? domTarget : domTarget.parentElement
  while (element && element !== view.dom) {
    const positions = []
    // Atom node views may map their interior to the following paragraph. Their
    // boundary in the parent DOM still maps to the atom's actual document position.
    try {
      if (element.parentNode?.childNodes) {
        const index = Array.prototype.indexOf.call(element.parentNode.childNodes, element)
        if (index >= 0) positions.push(view.posAtDOM(element.parentNode, index))
      }
      positions.push(view.posAtDOM(element, 0))
    } catch { element = element.parentElement; continue }
    const candidates = []
    for (const pos of positions) {
      const $pos = view.state.doc.resolve(Math.max(0, Math.min(pos, view.state.doc.content.size)))
      const direct = view.state.doc.nodeAt($pos.pos)
      if (direct?.isBlock) candidates.push({ pos: $pos.pos, node: direct })
      for (let depth = $pos.depth; depth > 0; depth--) {
        const node = $pos.node(depth)
        if (node.isBlock) candidates.push({ pos: $pos.before(depth), node })
      }
    }
    const matching = candidates.filter(candidate => {
      const dom = view.nodeDOM(candidate.pos)
      return dom && (dom === domTarget || dom.contains?.(domTarget))
    })
    const target = matching.find(({ node }) => node.type.name === 'table')
      || matching.find(({ node }) => node.type.name === 'codeBlock')
      || (matching.some(({ node }) => node.type.name === 'summary')
        ? matching.find(({ node }) => node.type.name === 'details') : null)
      || matching.find(({ node }) => itemNames.has(node.type.name))
      || matching.find(({ node }) => node.isTextblock)
      || matching.find(({ node }) => !['tableRow', 'tableCell', 'tableHeader', 'summary'].includes(node.type.name))
    if (target) {
      const dom = view.nodeDOM(target.pos)
      if (dom) return { ...target, dom: dom.nodeType === 1 ? dom : dom.parentElement }
    }
    element = element.parentElement
  }
  return null
}

function freshAttrs(node, extra = {}) {
  return { ...node?.attrs, blockId: null, dataLine: null, ...extra }
}

function children(node) {
  const result = []
  node.forEach(child => result.push(child))
  return result
}

function paragraph(schema, content = null, attrs = null) {
  return schema.nodes.paragraph.create(attrs, content)
}

function textWithBreaks(schema, text) {
  const nodes = []
  String(text).split('\n').forEach((line, index) => {
    if (index && schema.nodes.hardBreak) nodes.push(schema.nodes.hardBreak.create())
    if (line) nodes.push(schema.text(line))
  })
  return Fragment.fromArray(nodes)
}

function codeText(node) {
  if (node.type.name === 'codeBlock') return node.attrs.code ?? node.textContent
  if (node.type.name === 'summary') return node.attrs.title || ''
  if (node.isText) return node.text
  if (node.type.name === 'hardBreak') return '\n'
  return children(node).map(codeText).join(node.isTextblock ? '' : '\n')
}

/** Unwrap containers without discarding body blocks or a folding-block title. */
function unwrapped(schema, node) {
  if (node.type.name === 'details') {
    const title = node.firstChild?.attrs.title || ''
    return [paragraph(schema, title ? schema.text(title) : null), ...children(node).slice(1)]
  }
  if (wrappers.has(node.type.name) || itemNames.has(node.type.name)) return children(node)
  if (lists.has(node.type.name)) return children(node).flatMap(item => unwrapped(schema, item))
  return [node]
}

function containsTable(node) {
  let found = node.type.name === 'table'
  node.descendants(child => { if (child.type.name === 'table') found = true; return !found })
  return found
}

function asTextBlocks(schema, node, level = null) {
  const blocks = unwrapped(schema, node)
  if (!blocks.length) blocks.push(paragraph(schema))
  // Only the first text block changes type; nested lists and other body blocks survive.
  const first = blocks[0]
  if (!first.isTextblock && first.type.name !== 'codeBlock') return null
  const content = first.type.name === 'codeBlock'
    ? textWithBreaks(schema, first.attrs.code ?? first.textContent) : first.content
  blocks[0] = level === null
    ? paragraph(schema, content, first.attrs)
    : schema.nodes.heading?.create({ ...first.attrs, level }, content)
  return blocks[0] ? blocks : null
}

function convertedNodes(schema, node, type) {
  const heading = /^h([1-6])$/.exec(type)
  if ((type === 'paragraph' || heading || lists.has(type) || type === 'codeBlock') && containsTable(node)) return null
  if (type === 'paragraph' || heading) return asTextBlocks(schema, node, heading ? Number(heading[1]) : null)
  if (type === 'codeBlock') {
    if (!schema.nodes.codeBlock) return null
    // This application's code blocks are atoms with their source stored in attrs.
    const attrs = { ...node.attrs, code: codeText(node), language: node.attrs.language || null }
    const codeType = schema.nodes.codeBlock
    return [codeType.isLeaf ? codeType.create(attrs) : codeType.create(attrs, attrs.code ? schema.text(attrs.code) : null)]
  }
  if (lists.has(type)) {
    const listType = schema.nodes[type]
    const itemType = schema.nodes[type === 'taskList' ? 'taskItem' : 'listItem']
    if (!listType || !itemType) return null
    if (lists.has(node.type.name)) {
      const items = children(node).map(item => itemType.create(item.attrs, item.content))
      return [listType.create(freshAttrs(node, type === 'orderedList' ? { start: node.attrs.start || 1 } : {}), items)]
    }
    const body = unwrapped(schema, node)
    if (!body.length) body.push(paragraph(schema))
    if (body[0].isTextblock) body[0] = paragraph(schema, body[0].content, body[0].attrs)
    else body.unshift(paragraph(schema))
    return [listType.create(null, itemType.create(null, body))]
  }
  if (wrappers.has(type) && schema.nodes[type]) {
    const body = unwrapped(schema, node)
    if (!body.length) body.push(paragraph(schema))
    if (type === 'details') {
      if (!schema.nodes.summary) return null
      return [schema.nodes.details.create(null, [schema.nodes.summary.create({ title: '折叠内容' }), ...body])]
    }
    return [schema.nodes[type].create(type === 'callout' ? { type: 'tip' } : null, body)]
  }
  return null
}

/** A list item converted to a block splits its list, keeping both sibling lists. */
function replacementPlan(editor, target, replacement) {
  if (!itemNames.has(target.node.type.name)) return { from: target.pos, to: target.pos + target.node.nodeSize, nodes: replacement, selectionPos: target.pos }
  const list = target.parent
  if (!lists.has(list.type.name)) return null
  const listPos = target.$pos.before(target.$pos.depth)
  const items = children(list)
  const nodes = []
  const before = items.slice(0, target.index)
  if (before.length) nodes.push(list.copy(Fragment.fromArray(before)))
  const selectionPos = listPos + nodes.reduce((sum, node) => sum + node.nodeSize, 0)
  nodes.push(...replacement)
  const after = items.slice(target.index + 1)
  if (after.length) {
    const attrs = freshAttrs(list, list.type.name === 'orderedList' ? { start: (list.attrs.start || 1) + target.index + 1 } : {})
    nodes.push(list.type.create(attrs, after))
  }
  return { from: listPos, to: listPos + list.nodeSize, nodes, selectionPos }
}

function validPlan(editor, plan) {
  if (!plan) return false
  const $from = editor.state.doc.resolve(plan.from)
  const $to = editor.state.doc.resolve(plan.to)
  return $from.sameParent($to) && $from.parent.canReplace($from.index(), $to.index(), Fragment.fromArray(plan.nodes))
}

function conversionPlan(editor, pos, type) {
  const target = contextAt(editor, pos)
  if (!target || target.node.type.name === 'summary') return null
  if (target.node.type.name === type || (itemNames.has(target.node.type.name) && target.parent.type.name === type)) return null
  try {
    const replacement = convertedNodes(editor.schema || editor.state.schema, target.node, type)
    if (!replacement || (replacement.length === 1 && target.node.eq(replacement[0]))) return null
    for (const node of replacement) node.check()
    const plan = replacementPlan(editor, target, replacement)
    return validPlan(editor, plan) ? plan : null
  } catch { return null }
}

export function getBlockActionSupport(editor, pos, type) {
  return Boolean(conversionPlan(editor, pos, type))
}

function caretIn(doc, pos, size = 0) {
  const start = Math.max(0, Math.min(pos, doc.content.size))
  const node = doc.nodeAt(start)
  if (node) {
    if (node.isTextblock) return TextSelection.create(doc, start + 1)
    let textPos = null
    node.descendants((child, offset) => {
      if (textPos !== null) return false
      if (child.isTextblock) { textPos = start + 1 + offset + 1; return false }
      return true
    })
    if (textPos !== null) return TextSelection.create(doc, textPos)
    if (NodeSelection.isSelectable(node)) return NodeSelection.create(doc, start)
  }
  return Selection.near(doc.resolve(Math.min(start + size, doc.content.size)), 1)
}

function dispatchPlan(editor, plan) {
  if (!validPlan(editor, plan)) return false
  const tr = editor.state.tr.replaceWith(plan.from, plan.to, Fragment.fromArray(plan.nodes))
  tr.setSelection(caretIn(tr.doc, plan.selectionPos ?? plan.from))
  editor.view.dispatch(closeHistory(tr).scrollIntoView())
  return true
}

export function convertActionBlock(editor, pos, type) {
  const plan = conversionPlan(editor, pos, type)
  return plan ? dispatchPlan(editor, plan) : false
}

function emptyBlock(schema, type) {
  if (type === 'horizontalRule') return schema.nodes.horizontalRule?.create()
  if (type === 'table') {
    const { table, tableRow, tableHeader, tableCell } = schema.nodes
    if (!table || !tableRow || !tableHeader || !tableCell) return null
    const row = (cellType, labels) => tableRow.create(null, labels.map(label => cellType.create(null, paragraph(schema, label ? schema.text(label) : null))))
    return table.create(null, [row(tableHeader, ['列 1', '列 2']), row(tableCell, ['', ''])])
  }
  return convertedNodes(schema, paragraph(schema), type)?.[0] || null
}

export function addActionBlockBelow(editor, pos, type = 'paragraph') {
  const target = contextAt(editor, pos)
  if (!target) return false
  const schema = editor.schema || editor.state.schema
  try {
    if (itemNames.has(target.node.type.name) && type === 'paragraph') {
      const item = target.node.type.create(null, paragraph(schema))
      return dispatchPlan(editor, { from: pos + target.node.nodeSize, to: pos + target.node.nodeSize, nodes: [item] })
    }
    const node = emptyBlock(schema, type)
    if (!node) return false
    node.check()
    let insertPos = pos + target.node.nodeSize
    // Heading/quote/etc. below a list item belongs after that list, not inside it.
    if (itemNames.has(target.node.type.name)) insertPos = target.$pos.after(target.$pos.depth)
    return dispatchPlan(editor, { from: insertPos, to: insertPos, nodes: [node] })
  } catch { return false }
}

export function deleteActionBlock(editor, pos) {
  let target = contextAt(editor, pos)
  if (!target) return false
  const schema = editor.schema || editor.state.schema
  const empty = paragraph(schema)
  // A one-item list cannot retain zero items; remove its wrapper in the same transaction.
  while (target) {
    const from = target.pos
    const to = from + target.node.nodeSize
    if (target.parent.canReplace(target.index, target.index + 1)) {
      return dispatchPlan(editor, { from, to, nodes: [], selectionPos: from })
    }
    if (target.parent.canReplace(target.index, target.index + 1, Fragment.from(empty))) {
      return dispatchPlan(editor, { from, to, nodes: [empty], selectionPos: from })
    }
    if (target.$pos.depth === 0) return false
    target = contextAt(editor, target.$pos.before(target.$pos.depth))
  }
  return false
}

function indentPlan(editor, pos, direction, dispatch) {
  const target = contextAt(editor, pos)
  if (!target) return false
  if (itemNames.has(target.node.type.name)) {
    const selection = caretIn(editor.state.doc, pos)
    const commandState = { doc: editor.state.doc, selection, tr: editor.state.tr.setSelection(selection) }
    const command = direction > 0 ? sinkListItem(target.node.type) : liftListItem(target.node.type)
    return command(commandState, dispatch)
  }
  if (!['paragraph', 'heading'].includes(target.node.type.name) || !('indent' in target.node.attrs)) return false
  const indent = Math.max(0, Math.min(6, (Number(target.node.attrs.indent) || 0) + (direction > 0 ? 1 : -1)))
  if (indent === target.node.attrs.indent) return false
  if (dispatch) dispatch(editor.state.tr.setNodeMarkup(pos, undefined, { ...target.node.attrs, indent }).scrollIntoView())
  return true
}

export function canIndentActionBlock(editor, pos, direction = 1) {
  return indentPlan(editor, pos, direction)
}

export function indentActionBlock(editor, pos, direction = 1) {
  return indentPlan(editor, pos, direction, tr => editor.view.dispatch(closeHistory(tr)))
}

/** Serialize model content instead of node-view DOM (code toolbars are decorations). */
export function serializeActionBlock(editor, pos) {
  const target = contextAt(editor, pos)
  if (!target) return null
  const ownerDocument = editor.view.dom.ownerDocument
  const box = ownerDocument.createElement('div')
  const serializer = DOMSerializer.fromSchema(editor.schema || editor.state.schema)
  box.appendChild(serializer.serializeFragment(Fragment.from(target.node), { document: ownerDocument }))
  return { html: box.innerHTML, text: codeText(target.node) }
}
