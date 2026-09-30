import { Node, mergeAttributes } from '@tiptap/core'
import { VueNodeViewRenderer } from '@tiptap/vue-3'
import CodeBlockNodeView from '../components/CodeBlockNodeView.vue'

export const CodeBlockCm = Node.create({
  name: 'codeBlock',
  group: 'block',
  atom: true,
  selectable: true,
  isolating: true,

  addAttributes() {
    return {
      language: {
        default: null,
        parseHTML: (el) => {
          const c = el.querySelector('code')
          return (c && c.className && c.className.match(/language-([\w+#.-]+)/)?.[1]) || null
        },
      },
      code: {
        default: '',
        parseHTML: (el) => {
          const c = el.querySelector('code')
          console.log('[cm] parseHTML el=', el.tagName, 'child=', c ? c.tagName : null, 'textLen=', c ? c.textContent.length : -1)
          return (c ? c.textContent : el.textContent) || ''
        },
      },
    }
  },

  parseHTML() {
    return [{ tag: 'pre' }]
  },

  renderHTML({ node, HTMLAttributes }) {
    const { language, code } = node.attrs
    return ['pre', mergeAttributes(HTMLAttributes), ['code', { class: language ? `language-${language}` : null }, code || '']]
  },

  addNodeView() {
    console.log('[cm] addNodeView 被调用')
    return VueNodeViewRenderer(CodeBlockNodeView)
  },
})
