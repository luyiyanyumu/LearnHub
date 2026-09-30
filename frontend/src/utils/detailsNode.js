import { Node, mergeAttributes } from '@tiptap/core'

/** <summary>：折叠块标题。用 atom 节点 + 标题存属性，避免"行内内容吞后面块"的解析边界问题 */
export const Summary = Node.create({
  name: 'summary',
  group: 'block',
  atom: true,
  addAttributes() {
    return { title: { default: '', parseHTML: (el) => el.textContent || '', rendered: false } }
  },
  parseHTML() { return [{ tag: 'summary' }] },
  renderHTML({ node }) { return ['summary', {}, node.attrs.title] },
})

export const Details = Node.create({
  name: 'details',
  group: 'block',
  content: 'summary block*',
  defining: true,
  parseHTML() { return [{ tag: 'details' }] },
  renderHTML({ HTMLAttributes }) { return ['details', mergeAttributes(HTMLAttributes), 0] },
})
