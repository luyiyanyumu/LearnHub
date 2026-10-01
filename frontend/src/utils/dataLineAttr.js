/**
 * dataLineAttr —— 让 Tiptap 的各块级节点保留 markdown-it 标注的 `data-line` 属性。
 *
 * 源码对照（滚动联动）靠预览里的 `[data-line]` 锚点做双向对齐；
 * 不给 Tiptap 加这层全局属性，解析时 data-line 会被丢掉，联动就失效了。
 */
import { Extension } from '@tiptap/core'

/** 需要保留 data-line 的块级节点类型（覆盖 markdown-it 会标注的那些块） */
const TYPES = [
  'paragraph',
  'heading',
  'bulletList',
  'orderedList',
  'listItem',
  'blockquote',
  'codeBlock',
  'horizontalRule',
  'table',
  'tableRow',
  'tableHeader',
  'tableCell',
  'callout',
  'details',
  'summary',
]

export const DataLineAttr = Extension.create({
  name: 'dataLineAttr',
  addGlobalAttributes() {
    return [
      {
        types: TYPES,
        attributes: {
          dataLine: {
            default: null,
            parseHTML: (el) => el.getAttribute('data-line'),
            renderHTML: (attrs) => (attrs.dataLine ? { 'data-line': attrs.dataLine } : {}),
          },
        },
      },
    ]
  },
})
