// 独立演示脚本：复刻 mdToHtml.js 渲染逻辑，生成效果演示 HTML
import { readFileSync, writeFileSync } from 'node:fs'
import { fileURLToPath } from 'node:url'
import { dirname, join } from 'node:path'
import { createRequire } from 'node:module'

const require = createRequire(import.meta.url)
const MarkdownIt = require('markdown-it')
const hljs = require('highlight.js/lib/common')
const __dirname = dirname(fileURLToPath(import.meta.url))

// 从源码提取 BODY_CSS，保证演示样式与真实导出一致
const src = readFileSync(join(__dirname, 'src/utils/mdToHtml.js'), 'utf-8')
const bodyCss = src.match(/const BODY_CSS = `([\s\S]*?)`/)[1]
const githubCss = readFileSync(join(__dirname, 'node_modules/highlight.js/styles/github.css'), 'utf-8')
const githubDarkCss = readFileSync(join(__dirname, 'node_modules/highlight.js/styles/github-dark.css'), 'utf-8')

const md = new MarkdownIt({
  html: true,
  linkify: true,
  breaks: true,
  highlight(code, lang) {
    const esc = (s) => s.replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;')
    if (lang && hljs.getLanguage(lang)) {
      try {
        return `<pre class="hljs"><code>${hljs.highlight(code, { language: lang, ignoreIllegals: true }).value}</code></pre>`
      } catch {}
    }
    return `<pre class="hljs"><code>${esc(code)}</code></pre>`
  },
})

const TASK_RE = /^\[([ xX])\]\s?/
md.renderer.rules.list_item_open = function (tokens, idx, options, env, slf) {
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
          markup: '', info: '', meta: null, block: false, hidden: false,
        })
      }
      break
    }
  }
  return slf.renderToken(tokens, idx, options)
}

const escapeHtml = (s) =>
  String(s ?? '').replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;').replace(/"/g, '&quot;')

const demoMd = `## 学习目标

- [x] 搞懂 \`MyBatis-Plus\` 的条件构造器
- [ ] 把智能体的函数调用循环改成可配置轮数
- [ ] 复盘 Spring 事务传播行为

## 今日代码：DeepSeekClient 高亮示例

\`\`\`java
public String chat(List<Message> history) {
    HttpRequest req = HttpRequest.newBuilder()
        .header("Authorization", "Bearer " + apiKey)
        .POST(BodyPublishers.ofString(body))
        .build();
    return client.send(req, BodyHandlers.ofString()).body();
}
\`\`\`

## 造价类比

| 前端概念 | 造价类比 |
| --- | --- |
| 组件 Component | 预制构件 |
| 状态 State | 现场签证单 |
| Props 传参 | 图纸交底 |

> 提示：导出的 HTML 是**单文件自包含**的，发给别人直接双击就能看，~~不需要装任何东西~~。

详情见 [learn-hub 项目](http://localhost:5173)。
`

const body = md.render(demoMd)
const title = 'md 导出功能演示'
const meta = '后端开发 · #SpringBoot #AI智能体　·　更新于 2026-09-09 12:48'
const darkCssBlock = `@media (prefers-color-scheme: dark) {\n${githubDarkCss}\n}`

const html = `<!DOCTYPE html>
<html lang="zh-CN">
<head>
<meta charset="UTF-8">
<meta name="viewport" content="width=device-width, initial-scale=1.0">
<title>${escapeHtml(title)}</title>
<style>
${githubCss}
${bodyCss}
${darkCssBlock}
</style>
</head>
<body>
<div class="container">
  <header class="doc-head">
    <h1>${escapeHtml(title)}</h1>
    <div class="doc-meta">${escapeHtml(meta)}</div>
  </header>
  <article class="markdown-body">
${body}
  </article>
  <footer class="doc-foot">由 learn-hub 笔记工作台导出 · ${escapeHtml(new Date().toLocaleString('zh-CN'))}</footer>
</div>
</body>
</html>
`

const out = join(__dirname, 'md-export-demo.html')
writeFileSync(out, html, 'utf-8')
console.log('OK ->', out, 'size:', html.length)
