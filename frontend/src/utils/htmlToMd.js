/**
 * 预览 HTML → Markdown 反向转换。
 *
 * 用途：在「预览即编辑」模式里，用户直接改渲染后的富文本，
 * 保存时把预览区 DOM 反推回 Markdown 源码（语雀的编辑逻辑：改所见，代码自动跟着变）。
 *
 * 关键点（都是对着 md-editor 真实输出调的，见 probe 脚本）：
 * - 代码块真实结构是 `<details class="md-editor-code"><summary class="md-editor-code-head">…</summary><pre><code class="language-x">…</code></pre></details>`，
 *   还夹着装饰用的 `<span rn-wrapper>`，必须先把 `<details>` 还原成干净的 `<pre><code>`，否则反推出来的是一堆噪音。
 * - 提示块是 `<div class="md-callout md-callout-success">`，要还原成 `:::success … :::`。
 * - 行内样式标签（font/span/mark/u/sup/sub）Markdown 没有对应语法，用 keep 原样保留。
 * - 删除线交给 gfm 插件转成 `~~…~~`（比原样保留 `<s>` 更干净，且渲染结果一致）。
 * - md-editor 给每个块加了 `data-line`，反推前统一剥掉。
 */
import TurndownService from 'turndown'
import { gfm } from 'turndown-plugin-gfm'

/** 预览区里的“装饰性”节点：不属于用户内容，反推前必须剔除 */
const DROP_SELECTOR = [
  '.md-editor-code-head',
  '.md-editor-copy-button',
  '.md-editor-collapse-tips',
  '.md-editor-code-flag',
  '.md-editor-code-action',
  '.md-editor-icon',
  '.md-editor-heading-anchor',
  '[rn-wrapper]',
].join(',')

/** 反推时原样保留的内联/块级标签（Markdown 没有等价语法） */
const KEEP_TAGS = [
  'font', 'span', 'mark', 'u', 'sup', 'sub',
  'kbd', 'abbr', 'small', 'big', 'summary',
]

let service = null

function getService() {
  if (service) return service
  const t = new TurndownService({
    headingStyle: 'atx',
    hr: '---',
    bulletListMarker: '-',
    codeBlockStyle: 'fenced',
    fence: '```',
    emDelimiter: '*',
    strongDelimiter: '**',
    linkStyle: 'inlined',
    // md-editor 的 markdown-it 开了 breaks:true，单个换行就渲染成 <br>。
    // turndown 实际写入的是 `options.br + '\n'`，所以这里给两个空格 → "  \n"（硬换行），
    // 再由下方统一去掉行尾空格变成 "\n"；因为 breaks:true，单个 \n 依然渲染成 <br>，往返一致。
    br: '  ',
  })

  t.use(gfm)
  t.keep(KEEP_TAGS)

  // gfm 插件把 <s> 转成单波浪线 ~x~，但 markdown-it 只认 ~~x~~，这里覆盖成正确的双波浪线
  t.addRule('strikethroughGfm', {
    filter: ['del', 's', 'strike'],
    replacement: (content) => `~~${content}~~`,
  })

  // 代码块：<pre><code class="language-java"> → ```java
  t.addRule('fencedCodeWithLang', {
    filter: (node) => node.nodeName === 'PRE' && node.firstChild && node.firstChild.nodeName === 'CODE',
    replacement: (content, node) => {
      const code = node.firstChild
      const cls = code.getAttribute('class') || ''
      const lang = (cls.match(/language-([\w+#.-]+)/) || [null, code.getAttribute('language') || ''])[1] || ''
      const text = code.textContent.replace(/\n+$/, '')
      // 围栏必须比「内容里最长的一串反引号」还长：代码示例本身含 ``` 时，
      // 固定写三个反引号会让围栏提前闭合，反推出的 Markdown 结构就坏了
      const longest = (text.match(/`{3,}/g) || []).reduce((n, s) => Math.max(n, s.length), 0)
      const fence = '`'.repeat(Math.max(3, longest + 1))
      return `\n\n${fence}${lang}\n${text}\n${fence}\n\n`
    },
  })

  // 提示块：<div class="md-callout md-callout-success"> → :::success
  t.addRule('calloutBlock', {
    filter: (node) => node.nodeType === 1 && node.classList?.contains('md-callout'),
    replacement: (content, node) => {
      const cls = [...(node.classList || [])].find((c) => c.startsWith('md-callout-'))
      const name = cls ? cls.slice('md-callout-'.length) : 'tip'
      return `\n\n:::${name}\n${content.trim()}\n:::\n\n`
    },
  })

  // 折叠块：保留 <details>/<summary> 结构，内部内容照常转 Markdown
  t.addRule('detailsBlock', {
    filter: (node) => node.nodeName === 'DETAILS',
    replacement: (content) => `\n\n<details>\n${content.trim()}\n</details>\n\n`,
  })

  // 居中段落等带对齐样式的块：保留 <p style="…">
  t.addRule('styledParagraph', {
    filter: (node) =>
      node.nodeName === 'P' && /text-align/i.test(node.getAttribute('style') || ''),
    replacement: (content, node) =>
      `\n\n<p style="${node.getAttribute('style')}">${content}</p>\n\n`,
  })

  service = t
  return t
}

/** 把 md-editor 的预览 DOM 结构规整成 Turndown 好处理的干净 HTML */
function normalize(html) {
  const box = document.createElement('div')
  box.innerHTML = String(html || '')

  // 1) 剥掉装饰节点与 data-line 定位属性
  box.querySelectorAll(DROP_SELECTOR).forEach((n) => n.remove())
  box.querySelectorAll('[data-line]').forEach((n) => n.removeAttribute('data-line'))

  // 2) 代码块：<details class="md-editor-code"> → <pre><code class="language-x">
  box.querySelectorAll('details.md-editor-code').forEach((d) => {
    const codeEl = d.querySelector('pre > code')
    if (!codeEl) return
    const cls = codeEl.getAttribute('class') || ''
    const lang =
      (cls.match(/language-([\w+#.-]+)/) || [])[1] || (codeEl.getAttribute('language') || '').trim()
    const src = codeEl.querySelector('.md-editor-code-block') || codeEl
    const pre = document.createElement('pre')
    const code = document.createElement('code')
    if (lang) {
      code.setAttribute('class', `language-${lang}`)
      code.setAttribute('language', lang)
    }
    code.textContent = src.textContent
    pre.appendChild(code)
    d.replaceWith(pre)
  })

  // 3) 公式：md-editor 未装 katex 时保留原始 TeX 源码，直接还原成 $…$
  box.querySelectorAll('.md-editor-katex-inline').forEach((n) => {
    n.replaceWith(document.createTextNode(`$${n.textContent}$`))
  })
  box.querySelectorAll('.md-editor-katex-block').forEach((n) => {
    n.replaceWith(document.createTextNode(`\n\n$$\n${n.textContent.trim()}\n$$\n\n`))
  })
  // 装了 katex 的情况：从 annotation 取回 TeX 源码
  box.querySelectorAll('.katex-display').forEach((n) => {
    const tex = n.querySelector('annotation[encoding="application/x-tex"]')?.textContent
    if (tex) n.replaceWith(document.createTextNode(`\n\n$$\n${tex}\n$$\n\n`))
  })
  box.querySelectorAll('.katex').forEach((n) => {
    const tex = n.querySelector('annotation[encoding="application/x-tex"]')?.textContent
    if (tex) n.replaceWith(document.createTextNode(`$${tex}$`))
  })

  // 4) 表格/列表里 md-editor 可能塞的额外空行节点，清掉纯空白文本节点
  return box.innerHTML
}

/** 预览区里是否存在无法反推的内容（mermaid 图、内嵌 HTML 组件等） */
export function findUnsupported(html) {
  const box = document.createElement('div')
  box.innerHTML = String(html || '')
  const found = []
  if (box.querySelector('.md-editor-mermaid, svg[id^="mermaid"]')) found.push('Mermaid 图表')
  return found
}

/**
 * 把 Markdown 按围栏代码块切成片段：code 片段逐字保留，只有普通片段做收尾清理。
 * <p>
 * 为什么必须切开：收尾那几条正则原本是全局生效的，连代码块内部一起改 ——
 * YAML 里的 `-   key` 会被压成 `- key`、Python 里连续三个换行会被合并成两个，
 * 都属于静默改写用户的代码。
 */
function splitByFence(md) {
  const pieces = []
  let fence = null
  let buf = []
  const flushText = () => {
    if (buf.length) {
      pieces.push({ code: false, text: buf.join('\n') })
      buf = []
    }
  }
  for (const line of md.split('\n')) {
    const m = line.match(/^\s*(`{3,}|~{3,})/)
    if (fence === null) {
      if (m) {
        flushText()
        pieces.push({ code: true, text: line })
        fence = m[1]
      } else {
        buf.push(line)
      }
    } else {
      pieces.push({ code: true, text: line })
      if (m && m[1][0] === fence[0] && m[1].length >= fence.length) {
        fence = null
      }
    }
  }
  flushText()
  return pieces
}

/**
 * 把预览区 HTML 反推成 Markdown 源码。
 * @param {string} html 预览区（.md-editor-preview）的 innerHTML
 * @returns {string} Markdown
 */
export function previewHtmlToMd(html) {
  const md = getService().turndown(normalize(html))
  const clean = (t) =>
    t
      .replace(/\u00a0/g, ' ') // &nbsp; → 普通空格
      .replace(/[ \t]+$/gm, '') // 行尾空格（含硬换行的两个空格，等价于 breaks:true 下的软换行）
      .replace(/^(\s*)([-*+]|\d+\.)\s+(?=\[[ xX]\])/gm, '$1$2 ') // 任务列表项："-   [x]" → "- [x]"
      .replace(/^(\s*)([-*+]|\d+\.)\s{2,}/gm, '$1$2 ') // 其它列表项多余缩进
      .replace(/\n{3,}/g, '\n\n') // 连续空行压成一个
  return splitByFence(md)
    .map((p) => (p.code ? p.text : clean(p.text)))
    .join('\n')
    .trim()
}
