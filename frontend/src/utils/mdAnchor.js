/**
 * mdAnchor —— 给渲染后的标题加 `id`，让手写目录 `[小节](#id)` 真正能跳转。
 *
 * 背景：md-editor-v3 6.5.6 **不自带标题锚点**（包内没有任何 heading-anchor / id 生成逻辑），
 * 导出的自包含 HTML 也没有 id。结果是手写的 `[小节](#小节)` 在预览和导出里都点不动。
 * 这里用一个 markdown-it 插件一次补齐，并且让编辑器预览（mdEditorSetup.js）与
 * 导出（mdToHtml.js）**共用同一套规则** —— 保证「预览里能跳的，导出后也能跳」。
 *
 * slug 规则（写目录时按这个拼，见 skills/markdown-beautify/REFERENCE.md 第 2.2 节）：
 *   1. 去掉行内 HTML 标签与强调符号 * ` ~
 *   2. 整体转小写
 *   3. 只保留 中文 / 英文字母 / 数字 / 空格 / 下划线 / 连字符，其余标点（、，。（）等）一律丢弃
 *   4. 空白与下划线合并为单个 `-`，并去掉首尾 `-`
 *   5. 标题为空或只剩标点时用 `section`；同名标题依次追加 -1、-2
 *
 * 例：`## 一、Java 核心基础` → `#一java-核心基础`
 *     `### 1. Java 简介`    → `#1-java-简介`
 */

/** 把标题文本转成锚点 id（导出便于人手写的规则，改动需同步文档） */
export function headingSlug(text) {
  return String(text || '')
    .replace(/<[^<>]*>/g, '') // 行内 HTML 标签
    .replace(/[*`~]/g, '') // 强调 / 代码 / 删除线符号
    .trim()
    .toLowerCase()
    .replace(/[^\u4e00-\u9fa5a-z0-9\s_-]/g, '') // 只留中文、字母、数字、空白、下划线、连字符
    .replace(/[\s_]+/g, '-')
    .replace(/-{2,}/g, '-')
    .replace(/^-+|-+$/g, '')
}

/**
 * markdown-it 插件：给每个 heading_open 写 id。
 * 用 core 规则而不是 renderer 规则：token 层加属性，预览与导出两条渲染链都生效。
 */
export default function mdAnchor(md) {
  const seen = new Map()

  md.core.ruler.push('lh_heading_anchor', (state) => {
    seen.clear()
    for (let i = 0; i < state.tokens.length; i++) {
      const token = state.tokens[i]
      if (token.type !== 'heading_open') continue
      const inline = state.tokens[i + 1]
      const raw = inline && inline.type === 'inline' ? inline.content : ''
      const base = headingSlug(raw) || 'section'
      const n = seen.get(base) || 0
      seen.set(base, n + 1)
      token.attrSet('id', n === 0 ? base : `${base}-${n}`)
    }
    return true
  })
}
