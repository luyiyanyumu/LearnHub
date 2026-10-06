/**
 * md-editor-v3 的全局初始化（副作用模块）。
 *
 * 为什么单独抽成一个文件，而不是写在 main.js 里：
 * main.js 是首屏入口，在那里 `import { config } from 'md-editor-v3'` 会把整个
 * 编辑器（含预览渲染器、样式表）拖进首屏包 —— 实测 index chunk 因此多了约 310 KB。
 * 但真正用到编辑器的只有三个地方：笔记编辑页（路由懒加载）、速查卡页（懒加载）、
 * 智能体抽屉（打开时才渲染）。所以把初始化挪到这里，谁用谁 import，
 * 打进的是各自的异步 chunk。
 *
 * 用法：
 * <pre>
 *   import '../utils/mdEditorSetup'          // 静态：页面本身已经是懒加载路由
 *   // 或
 *   const MdPreview = defineAsyncComponent(() =>
 *     import('../utils/mdEditorSetup').then(() => import('md-editor-v3').then((m) => m.MdPreview)))
 * </pre>
 *
 * 注意：必须在**创建编辑器组件实例之前**执行，否则第一批渲染出来的 `:::` 提示块
 * 不会被识别（markdown-it 的插件是在实例化时读 config 的）。
 */
import { config } from 'md-editor-v3'
import 'md-editor-v3/lib/style.css'
import hljs from 'highlight.js'
import katex from 'katex'
import mdCallout from './mdCallout'
import mdAnchor from './mdAnchor'

config({
  // 支持 :::名称 … ::: 彩色提示块（语雀等平台粘贴兼容）
  markdownItConfig(md) {
    md.use(mdCallout)
    // md-editor 自身不给标题加 id（6.5.6 无锚点实现），这里补上，
    // 使笔记里的手写目录 `[小节](#id)` 在预览与导出中行为一致
    md.use(mdAnchor)
  },
  // ★ 注入高亮器：md-editor-v3 不自带 highlight.js，不传 instance 时
  //   代码块走 escapeHtml 分支，渲染出来是纯文本（2026-09-11 实测踩到）。
  //   用完整版 highlight.js（与 mdToHtml.js 同一份依赖）：语言注册表只有一份，
  //   未知语言内部会回落 highlightAuto，不会抛错。
  editorExtensions: {
    highlight: { instance: hljs },
    // ★ 数学公式（LaTeX）：必须给**本地** katex 实例。md-editor 的默认配置指向
    //   https://unpkg.com/katex@0.16.33/…（见其 config.mjs），而本应用是自托管/离线部署
    //   （Docker 内网），线上一拉不到整块公式就不渲染；传了 instance 后它的加载分支
    //   `if (noKatex || instance) return` 会直接跳过，连 <link> 都不插。
    //   样式由 style.css 顶部的 `@import 'katex/dist/katex.min.css'` 统一提供
    //   （笔记的块编辑器节点视图、速查卡、面板回答共用同一份 CSS）。
    katex: { instance: katex },
  },
})

// 标记已初始化，方便调试时确认没有重复执行
export const MD_EDITOR_READY = true
