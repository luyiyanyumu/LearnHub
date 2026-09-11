/**
 * mdToHtml —— 把 Markdown 笔记渲染成「单个自包含 .html 文件」的工具
 *
 * 特点：
 *  1. 离线可打开：语法高亮 CSS、正文排版样式全部内联，无任何外链请求
 *  2. 代码高亮：highlight.js（lib/common 常用语言集，按需异步加载，不拖首屏）
 *  3. 自动深浅色：导出文件跟随浏览器的 prefers-color-scheme
 *  4. 组件用 `await import('./mdToHtml')` 懒加载，首次导出时才下载
 */
import MarkdownIt from 'markdown-it'
import hljs from 'highlight.js/lib/common'
// `?raw` 让 Vite 把 css 以纯文本导入，从而内联进导出文件
import githubCss from 'highlight.js/styles/github.css?raw'
import githubDarkCss from 'highlight.js/styles/github-dark.css?raw'
import { fixHtmlQuotes } from './htmlQuotes'
import mdCallout from './mdCallout'

const md = new MarkdownIt({
  html: true, // 笔记中允许少量原始 HTML（图片/表格微调等），与编辑器预览一致
  linkify: true,
  breaks: true, // 单个换行即 <br>，贴合 md-editor 编辑习惯
  highlight(code, lang) {
    const esc = (s) =>
      s.replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;')
    if (lang && hljs.getLanguage(lang)) {
      try {
        return `<pre class="hljs"><code>${hljs.highlight(code, { language: lang, ignoreIllegals: true }).value}</code></pre>`
      } catch {
        /* 高亮失败则退回纯文本 */
      }
    }
    return `<pre class="hljs"><code>${esc(code)}</code></pre>`
  },
})
md.use(mdCallout) // :::名称 … ::: 彩色提示块，与编辑器预览保持一致

/**
 * 任务列表支持：`- [ ] 待办` / `- [x] 已完成` → 带勾选框的列表项
 * 原理：markdown-it 渲染是深度优先的，list_item_open 回调执行时，
 * 该列表项的 inline token 尚未渲染，此时改写其 children 仍有效。
 */
const TASK_RE = /^\[([ xX])\]\s?/
md.renderer.rules.list_item_open = function (tokens, idx, options, env, slf) {
  const baseLevel = tokens[idx].level
  for (let j = idx + 1; j < tokens.length; j++) {
    const t = tokens[j]
    if (t.type === 'list_item_close') break
    if (t.type === 'inline') {
      const c0 = t.children && t.children[0]
      if (c0 && c0.type === 'text' && TASK_RE.test(c0.content)) {
        const m = c0.content.match(TASK_RE)
        const checked = m[1].toLowerCase() === 'x'
        c0.content = c0.content.slice(m[0].length)
        t.content = t.content.slice(m[0].length)
        t.children.unshift({
          type: 'html_inline',
          content: `<span class="task-check ${checked ? 'on' : ''}">${checked ? '✓' : '○'}</span>`,
          markup: '',
          info: '',
          meta: null,
          block: false,
          hidden: false,
        })
      }
      break
    }
  }
  return slf.renderToken(tokens, idx, options)
}

function escapeHtml(s) {
  return String(s ?? '')
    .replace(/&/g, '&amp;')
    .replace(/</g, '&lt;')
    .replace(/>/g, '&gt;')
    .replace(/"/g, '&quot;')
}

/** 笔记正文排版样式（GitHub 风格精简版，含深色自动适配） */
const BODY_CSS = `
:root { color-scheme: light dark; }
* { box-sizing: border-box; }
body {
  margin: 0;
  font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", "PingFang SC",
    "Hiragino Sans GB", "Microsoft YaHei", "Helvetica Neue", Arial, sans-serif;
  color: #1f2328;
  background: #ffffff;
  line-height: 1.75;
  -webkit-font-smoothing: antialiased;
}
.container {
  max-width: 860px;
  margin: 0 auto;
  padding: 48px 28px 72px;
}
.doc-head { border-bottom: 1px solid #d0d7de; padding-bottom: 20px; margin-bottom: 28px; }
.doc-head h1 { margin: 0 0 10px; font-size: 30px; line-height: 1.35; }
.doc-meta { color: #6e7781; font-size: 13px; }
.markdown-body > *:first-child { margin-top: 0; }
.markdown-body h1, .markdown-body h2 { border-bottom: 1px solid #d8dee4; padding-bottom: .3em; }
.markdown-body h1 { font-size: 1.75em; }
.markdown-body h2 { font-size: 1.4em; }
.markdown-body h3 { font-size: 1.2em; }
.markdown-body h4 { font-size: 1.05em; }
.markdown-body h1, .markdown-body h2, .markdown-body h3, .markdown-body h4 {
  margin: 1.4em 0 .6em; font-weight: 600; line-height: 1.35;
}
.markdown-body p { margin: .8em 0; }
.markdown-body a { color: #0969da; text-decoration: none; }
.markdown-body a:hover { text-decoration: underline; }
.markdown-body img { max-width: 100%; }
.markdown-body blockquote {
  margin: 1em 0; padding: .1em 1em; color: #57606a;
  border-left: 4px solid #d0d7de; background: #f6f8fa;
}
.markdown-body code {
  font-family: ui-monospace, SFMono-Regular, "SF Mono", Consolas, "Liberation Mono", Menlo, monospace;
  font-size: 86%;
  background: rgba(175,184,193,.2);
  padding: .15em .4em; border-radius: 6px;
}
.markdown-body pre {
  background: #f6f8fa; border: 1px solid #d0d7de;
  border-radius: 8px; padding: 14px 16px; overflow-x: auto;
  margin: 1em 0; line-height: 1.6;
}
.markdown-body pre code { background: none; padding: 0; font-size: 88%; }
.hljs { display: block; overflow-x: auto; }
.markdown-body ul, .markdown-body ol { padding-left: 2em; margin: .6em 0; }
.markdown-body li { margin: .25em 0; }
.markdown-body li .task-check { display: inline-block; width: 1.2em; color: #57606a; }
.markdown-body table {
  border-collapse: collapse; margin: 1em 0; width: 100%;
  display: block; overflow-x: auto;
}
.markdown-body th, .markdown-body td {
  border: 1px solid #d0d7de; padding: 6px 13px;
}
.markdown-body th { background: #f6f8fa; font-weight: 600; }
.markdown-body hr { border: 0; border-top: 2px solid #d8dee4; margin: 2em 0; }
.markdown-body del { color: #6e7781; }
/* :::名称 … ::: 彩色提示块（半透明底色，深色下也可读） */
.markdown-body .md-callout {
  margin: 1em 0; padding: 10px 14px; border-radius: 8px;
  border-left: 4px solid #909399; background: rgba(144,147,153,.08);
}
.markdown-body .md-callout p { margin: .3em 0; }
.markdown-body .md-callout-success { border-left-color: #52c41a; background: rgba(82,196,26,.08); }
.markdown-body .md-callout-info, .markdown-body .md-callout-note { border-left-color: #409eff; background: rgba(64,158,255,.08); }
.markdown-body .md-callout-warning, .markdown-body .md-callout-attention { border-left-color: #e6a23c; background: rgba(230,162,60,.1); }
.markdown-body .md-callout-danger, .markdown-body .md-callout-error { border-left-color: #f56c6c; background: rgba(245,108,108,.08); }
.markdown-body .md-callout-tip { border-left-color: #13c2c2; background: rgba(19,194,194,.08); }
.doc-foot {
  margin-top: 48px; padding-top: 16px; border-top: 1px solid #d0d7de;
  color: #8c959f; font-size: 12px; text-align: center;
}
@media (prefers-color-scheme: dark) {
  body { color: #e6edf3; background: #0d1117; }
  .doc-head { border-color: #30363d; }
  .doc-meta { color: #8b949e; }
  .markdown-body h1, .markdown-body h2 { border-color: #21262d; }
  .markdown-body a { color: #4493f8; }
  .markdown-body blockquote { color: #8b949e; border-color: #30363d; background: #161b22; }
  .markdown-body code { background: rgba(110,118,129,.25); }
  .markdown-body pre { background: #161b22; border-color: #30363d; }
  .markdown-body th { background: #161b22; }
  .markdown-body th, .markdown-body td { border-color: #30363d; }
  .markdown-body hr { border-color: #21262d; }
  .markdown-body del { color: #8b949e; }
  .doc-foot { border-color: #30363d; color: #6e7681; }
  .markdown-body li .task-check { color: #8b949e; }
}
`

/**
 * 渲染整份 HTML 文档字符串
 * @param {{ title?:string, content?:string, meta?:string }} param
 * @returns {string} 完整 html 文本
 */
export function renderNoteHtml({ title = '', content = '', meta = '' } = {}) {
  // 与预览一致：先修复标签内弯引号，否则导出的 HTML 里 <font style=”…”> 也会显示成源码
  const body = md.render(fixHtmlQuotes(content || ''))
  const safeTitle = escapeHtml(title.trim() || '无标题笔记')
  const safeMeta = escapeHtml(meta)
  const darkCssBlock = `@media (prefers-color-scheme: dark) {\n${githubDarkCss}\n}`
  return `<!DOCTYPE html>
<html lang="zh-CN">
<head>
<meta charset="UTF-8">
<meta name="viewport" content="width=device-width, initial-scale=1.0">
<title>${safeTitle}</title>
<style>
${githubCss}
${BODY_CSS}
${darkCssBlock}
</style>
</head>
<body>
<div class="container">
  <header class="doc-head">
    <h1>${safeTitle}</h1>
    ${safeMeta ? `<div class="doc-meta">${safeMeta}</div>` : ''}
  </header>
  <article class="markdown-body">
${body}
  </article>
  <footer class="doc-foot">由 learn-hub 笔记工作台导出 · ${escapeHtml(new Date().toLocaleString('zh-CN'))}</footer>
</div>
</body>
</html>
`
}
