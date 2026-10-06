/**
 * tiptap 的数学公式节点（行内 + 块级）—— 笔记阅读模式与速查卡共用。
 *
 * <h3>为什么要有节点</h3>
 * 笔记的默认渲染器是块编辑器（tiptap），它的 schema 只认认识的节点：markdown-it 渲染出来的
 * KaTeX 结构（`<span class="katex">` 里一堆 span/MathML）没有对应节点，解析时会被**整段丢掉**，
 * 公式就消失了。所以必须有一对节点：TeX 存在属性里，视觉由节点视图用 KaTeX 现渲染。
 *
 * <h3>TeX 为什么放在 data-tex</h3>
 * 序列化（`getHTML()`）走的是 `renderHTML`，而它只能吐 DOM 结构、塞不进 KaTeX 的 HTML 字符串；
 * 视觉交给节点视图。把 TeX 挂在 `data-tex` 上，解析回来时直接读属性即可，
 * 不必去 KaTeX 输出里翻 `<annotation>`；`htmlToMd` 也按这个属性反推出 `$…$` / `$$…$$`
 *（见其中 mathInlineData / mathBlockData 两条规则）。
 *
 * <h3>兜底：直接解析 KaTeX 产物</h3>
 * 从网页/别处粘进来的内容里可能是现成的 KaTeX DOM（没有 data-tex），这时从
 * `<annotation encoding="application/x-tex">` 里把 TeX 取回来 —— 与 htmlToMd 用的是同一招。
 */
import { Node, mergeAttributes } from '@tiptap/core'
import { renderMathToHtml } from './mdMath'

/** 从 KaTeX 产物里取回 TeX 源码（没有就返回空串） */
function texFromKatex(el) {
  const ann = el.querySelector?.('annotation[encoding="application/x-tex"]')
  return ann ? ann.textContent : ''
}

/** 节点视图：用一个只读的 span/div 装 KaTeX 渲染结果 */
function mathNodeView({ node, className }) {
  const dom = document.createElement(node.type.name === 'mathBlock' ? 'div' : 'span')
  dom.className = className
  dom.setAttribute('data-tex', node.attrs.tex || '')
  dom.innerHTML = renderMathToHtml(node.attrs.tex, { display: node.type.name === 'mathBlock' })
  dom.setAttribute('contenteditable', 'false')
  return {
    dom,
    // 属性变了就重渲染（双击编辑改的就是它）
    update: (updated) => {
      if (updated.type.name !== node.type.name) return false
      dom.setAttribute('data-tex', updated.attrs.tex || '')
      dom.innerHTML = renderMathToHtml(updated.attrs.tex, { display: updated.type.name === 'mathBlock' })
      return true
    },
  }
}

const texAttribute = {
  tex: {
    default: '',
    parseHTML: (el) => el.getAttribute('data-tex') || texFromKatex(el),
    renderHTML: (attrs) => ({ 'data-tex': attrs.tex || '' }),
  },
}

/** 行内公式：`$E=mc^2$` */
export const MathInline = Node.create({
  name: 'mathInline',
  group: 'inline',
  inline: true,
  atom: true,
  selectable: true,
  addAttributes: () => texAttribute,
  parseHTML() {
    return [{ tag: 'span.math-inline' }, { tag: 'span.katex' }]
  },
  renderHTML({ HTMLAttributes }) {
    return ['span', mergeAttributes(HTMLAttributes, { class: 'math-inline' })]
  },
  addNodeView() {
    return (props) => mathNodeView({ node: props.node, className: 'math-inline' })
  },
})

/** 块级公式：`$$ … $$`（独占一块） */
export const MathBlock = Node.create({
  name: 'mathBlock',
  group: 'block',
  atom: true,
  selectable: true,
  isolating: true,
  addAttributes: () => texAttribute,
  parseHTML() {
    return [{ tag: 'div.math-block' }, { tag: 'span.katex-display' }, { tag: 'div.katex-display' }]
  },
  renderHTML({ HTMLAttributes }) {
    return ['div', mergeAttributes(HTMLAttributes, { class: 'math-block' })]
  },
  addNodeView() {
    return (props) => mathNodeView({ node: props.node, className: 'math-block' })
  },
})
