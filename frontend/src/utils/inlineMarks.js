import { Mark, mergeAttributes } from '@tiptap/core'

/** 上标 <sup>（markdown-it-sup 把 ^text^ 渲染成 <sup>） */
export const Superscript = Mark.create({
  name: 'superscript',
  parseHTML() { return [{ tag: 'sup' }] },
  renderHTML({ HTMLAttributes }) { return ['sup', mergeAttributes(HTMLAttributes), 0] },
})

/** 下标 <sub>（markdown-it-sub 把 ~text~ 渲染成 <sub>） */
export const Subscript = Mark.create({
  name: 'subscript',
  parseHTML() { return [{ tag: 'sub' }] },
  renderHTML({ HTMLAttributes }) { return ['sub', mergeAttributes(HTMLAttributes), 0] },
})

/** 高亮 <mark>（==text== 语法需 markdown-it-mark，未装；原生 <mark> HTML 可走这里） */
export const Highlight = Mark.create({
  name: 'highlight',
  parseHTML() { return [{ tag: 'mark' }] },
  renderHTML({ HTMLAttributes }) { return ['mark', mergeAttributes(HTMLAttributes), 0] },
})

/** 字体样式 <font style="…">（颜色/字号/背景色）。mark 的 renderHTML 无 node，样式从 HTMLAttributes 取 */
export const FontStyle = Mark.create({
  name: 'fontStyle',
  parseHTML() { return [{ tag: 'font' }] },
  addAttributes() {
    return {
      style: {
        default: '',
        parseHTML: (el) => el.getAttribute('style') || '',
        renderHTML: (attrs) => (attrs.style ? { style: attrs.style } : {}),
      },
    }
  },
  renderHTML({ HTMLAttributes }) { return ['font', mergeAttributes(HTMLAttributes), 0] },
})
