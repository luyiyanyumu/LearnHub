/**
 * 「标题字号由级别决定」这条规则的共用实现。
 *
 * <h3>为什么需要它</h3>
 * 从 Word / 网页粘贴会带进 `<font style="font-size: 10.5pt">`（`htmlToMd` 刻意保留行内样式），
 * 于是**同一个 `##` 标题**里，文字被行内字号压成正文字号 —— 用户看到的是"转成 H2 没反应"
 * （实测：笔记 #94 的 `## <font style="…font-size: 10.5pt">2.重载和重写的区别</font>`）。
 *
 * <h3>为什么在"改级别"时清数据，而不是用 CSS 压制</h3>
 * CSS 压制（`!important`）能修好显示，但代价是**"后面再改字号"永远不生效** ——
 * 用户的口径是"谁是最近的一步操作按谁来"。所以改成**一次性动作**：
 * 改标题级别时把字号代码从内容里清掉（标题随后用自己级别默认字号），
 * 之后再手动改字号就是最新的一步操作，正常生效。
 *
 * <p>这里只处理"样式声明"这一层，同时被两条渲染路径复用：
 * 块编辑器清的是 Tiptap 的 `fontStyle` mark（见 blockActions.js），
 * md-editor 路径清的是 Markdown 行内 HTML（见 NoteEdit.vue 的 mdLinePrefix）。
 */

/** 样式串里去掉 font-size 声明，保留颜色/字体等其他样式；全清空了返回空串 */
export function withoutFontSize(style) {
  return String(style ?? '')
    .split(';')
    .map((s) => s.trim())
    .filter((s) => s && !/^font-size\s*:/i.test(s))
    .join('; ')
}

/**
 * Markdown 文本（可含行内 HTML）里所有 `style="…"` 去掉 font-size 声明。
 *
 * <p>整条 style 只剩 font-size 时**连属性一起去掉**（不留空 `style=""`，否则导出/反推会留噪音）。
 *
 * @param {string} text 一行或多行 Markdown / HTML 片段
 * @returns {string}
 */
export function stripFontSizeInHtml(text) {
  return String(text ?? '').replace(/(\sstyle\s*=\s*)(["'])([\s\S]*?)\2/gi, (_all, pre, quote, css) => {
    const kept = withoutFontSize(css)
    return kept ? `${pre}${quote}${kept}${quote}` : ''
  })
}
