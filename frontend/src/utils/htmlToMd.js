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
import { hasExplicitSize } from './tableResize.js'
import { isBlockId, parseBlockIndent } from './blockMeta.js'

function hasBlockMetadata(node) {
  return /^(P|H[1-6])$/.test(node.nodeName) && (
    isBlockId(node.getAttribute('id'))
    || parseBlockIndent(node) > 0
    || /text-align|margin-left/i.test(node.getAttribute('style') || '')
  )
}

/** Raw HTML blocks need HTML children: Markdown emphasis inside <p> is not parsed. */
export function metadataBlockHtml(node) {
  const tag = node.nodeName.toLowerCase()
  const id = node.getAttribute('id') || ''
  let style = node.getAttribute('style') || ''
  const indent = parseBlockIndent(node)
  if (indent && !/margin-left\s*:/i.test(style)) style += `${style ? '; ' : ''}margin-left: ${indent * 2}em`
  const escapeAttr = (value) => value.replace(/&/g, '&amp;').replace(/"/g, '&quot;').replace(/</g, '&lt;').replace(/>/g, '&gt;')
  const attrs = [
    isBlockId(id) ? `id="${id}"` : '',
    indent ? `data-block-indent="${indent}"` : '',
    style ? `style="${escapeAttr(style)}"` : '',
  ].filter(Boolean).join(' ')
  return `<${tag}${attrs ? ` ${attrs}` : ''}>${node.innerHTML}</${tag}>`
}

/** 预览区里的“装饰性”节点：不属于用户内容，反推前必须剔除 */
const DROP_SELECTOR = [
  '.md-editor-code-head',
  '.md-editor-copy-button',
  '.md-editor-collapse-tips',
  '.md-editor-code-flag',
  '.md-editor-code-action',
  '.md-editor-icon',
  '.md-editor-heading-anchor',
  // 我们自己画的行号栏：纯装饰，绝不能反推进代码里（否则每行会被写上数字）
  '.code-row-numbers',
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
    blankReplacement: (_content, node) => hasBlockMetadata(node)
      ? `\n\n${metadataBlockHtml(node)}\n\n`
      : node.isBlock ? '\n\n' : '',
  })

  t.use(gfm)
  t.keep(KEEP_TAGS)

  // 管道表无法表示尺寸、合并单元格或单元格内换行/多个块，保留这些表格的 HTML。
  // addRule 内部是 unshift，此规则会排在 gfm 的 table 规则之前，先命中。
  t.addRule('sizedTable', {
    filter: (node) => node.nodeName === 'TABLE' && (
      hasExplicitSize(node)
      || Array.from(node.querySelectorAll('th,td')).some((cell) =>
        Number(cell.getAttribute('colspan')) > 1 || Number(cell.getAttribute('rowspan')) > 1
        || cell.querySelector('br,ul,ol,pre,blockquote,table')
        || Array.from(cell.children).some((child) => /^(H[1-6]|DIV)$/.test(child.nodeName) || hasBlockMetadata(child))
        || Array.from(cell.children).filter((child) => child.nodeName === 'P').length > 1)
    ),
    replacement: (content, node) => `\n\n${node.outerHTML}\n\n`,
  })

  // Tiptap wraps even a single-line cell in <p>; paragraph separators would
  // otherwise split an ordinary GFM table row into several Markdown lines.
  t.addRule('singleTableParagraph', {
    filter: (node) => node.nodeName === 'P' && /^(TH|TD)$/.test(node.parentNode?.nodeName)
      && node.parentNode.children.length === 1 && !hasBlockMetadata(node),
    replacement: (content) => content,
  })

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

  // The normalized task prefix is syntax, rather than escaped literal brackets.
  t.addRule('taskParagraph', {
    filter: (node) => node.nodeName === 'P' && node.hasAttribute('data-task-marker') && !hasBlockMetadata(node),
    replacement: (content) => `\n\n${content.replace(/^\\\[([ xX])\\\]/, '[$1]')}\n\n`,
  })

  // 块链接和缩进没有等价 Markdown 语法，保留 HTML。内联格式也必须保留为 HTML，
  // 否则 <p> 内的 **强调** 会在下次渲染时显示为字面文本。
  t.addRule('metadataBlock', {
    filter: hasBlockMetadata,
    replacement: (_content, node) => `\n\n${metadataBlockHtml(node)}\n\n`,
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

  // Tiptap task items wrap their checkbox in a label and their paragraphs in a
  // div. Write the marker into the first paragraph before Turndown runs; otherwise
  // the checkbox and its text become two separate Markdown paragraphs.
  box.querySelectorAll('li[data-type="taskItem"]').forEach((item) => {
    const children = Array.from(item.children)
    const label = children.find((child) => child.nodeName === 'LABEL')
    const input = children.find((child) => child.nodeName === 'INPUT' && child.type === 'checkbox')
      || label?.querySelector('input[type="checkbox"]')
    const checkedAttr = item.getAttribute('data-checked')
    const checked = checkedAttr == null ? Boolean(input?.checked) : checkedAttr === 'true'
    if (label) label.remove()
    else input?.remove()
    const content = children.find((child) => child.nodeName === 'DIV') || item
    let firstParagraph = Array.from(content.children).find((child) => child.nodeName === 'P')
    if (!firstParagraph) {
      firstParagraph = document.createElement('p')
      content.insertBefore(firstParagraph, content.firstChild)
    }
    firstParagraph.setAttribute('data-task-marker', '')
    firstParagraph.insertBefore(document.createTextNode(checked ? '[x] ' : '[ ] '), firstParagraph.firstChild)
  })

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
      // 勾选框**后面**多余的空格也要收敛：md-editor 渲染任务项时是
      // `<li class="task-list-item"><input type="checkbox" disabled> 任务</li>`（框后带一个空格），
      // 反推回来就成了 `- [ ]  任务`（两个空格）—— 渲染无差别，但源码不整齐。
      .replace(/^(\s*(?:[-*+]|\d+\.)\s+\[[ xX]\])\s{2,}/gm, '$1 ')
      .replace(/^(\s*)([-*+]|\d+\.)\s{2,}/gm, '$1$2 ') // 其它列表项多余缩进
      .replace(/\n{3,}/g, '\n\n') // 连续空行压成一个
  return splitByFence(md)
    .map((p) => (p.code ? p.text : clean(p.text)))
    .join('\n')
    .trim()
}

/**
 * 把**一整份 HTML 文档**转成笔记内容：返回 `{title, content}`（笔记导入用）。
 *
 * <h3>与 previewHtmlToMd 的分工</h3>
 * 那个吃的是编辑器预览区的**片段**（结构已知、专门为反推源码调过规则）；
 * 这个吃的是用户从别处存下来的**完整文档** —— 得自己挑正文、扔掉 head 与脚本样式，
 * 以及**本应用导出时自己加的**那几块：`.doc-head`（h1 + 分类/标签/时间）、
 * `.doc-foot`（"由 learn-hub 笔记工作台导出"）。这样"导出 HTML → 再导入"不会把
 * 元信息行和页脚也吃进正文。转换本身仍走同一套 Turndown 规则，所以代码围栏、
 * 表格、任务列表的写法与反推源码时完全一致。
 *
 * <h3>标题规则</h3>
 * 优先文档里第一个 `<h1>`（多数文档的真标题），其次 `<title>`；取到标题的那个元素
 * 会从正文里移除，避免正文第一行又是同一个标题（与 Markdown 导入"首行 # 标题提升为
 * 笔记标题"是同一条规则）。
 *
 * <h3>刻意不做"正文提取"</h3>
 * readability 那类算法猜错就会把用户的内容整段丢掉，而导入是**只增不减**的操作 ——
 * 网页里的导航文字留着，用户自己删，比丢内容好。只丢掉几乎一定是外壳的 `nav`。
 */
export function documentHtmlToMd(html) {
  const doc = new DOMParser().parseFromString(String(html || ''), 'text/html')
  const titleEl = doc.querySelector('h1') || doc.querySelector('title')
  const title = (titleEl?.textContent || '').replace(/\s+/g, ' ').trim()
  // 标题已经取走：正文里别再来一遍（<title> 在 head 里，删不删都无所谓）
  if (titleEl && titleEl.nodeName === 'H1') titleEl.remove()
  doc.querySelectorAll('script,style,noscript,link,meta,title,iframe,form,button,nav,.doc-meta,.doc-foot,.doc-head')
    .forEach((n) => n.remove())
  // 本应用导出的正文在 article.markdown-body 里；别处的 HTML 就退回 article/main/body
  const root = doc.querySelector('article.markdown-body') || doc.querySelector('.markdown-body')
    || doc.querySelector('article') || doc.querySelector('main') || doc.body
  if (!root) return { title, content: '' }
  return { title, content: previewHtmlToMd(root.innerHTML) }
}
