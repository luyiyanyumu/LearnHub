import { Node, mergeAttributes } from '@tiptap/core'
export const Callout = Node.create({
  name: 'callout',
  group: 'block',
  content: 'block+',
  defining: true,
  addAttributes() {
    return { type: { default: 'tip', parseHTML: (el) => { const cls = [...(el.classList || [])].find((c) => c.startsWith('md-callout-')); return cls ? cls.slice('md-callout-'.length) : 'tip' }, rendered: false } }
  },
  parseHTML() { return [{ tag: 'div', getAttrs: (el) => (el.classList.contains('md-callout') ? {} : false) }] },
  renderHTML({ node, HTMLAttributes }) { return ['div', mergeAttributes(HTMLAttributes, { class: `md-callout md-callout-${node.attrs.type}` }), 0] },
})
