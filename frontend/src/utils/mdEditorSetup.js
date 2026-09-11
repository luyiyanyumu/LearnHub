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
import mdCallout from './mdCallout'

config({
  // 支持 :::名称 … ::: 彩色提示块（语雀等平台粘贴兼容）
  markdownItConfig(md) {
    md.use(mdCallout)
  },
  // ★ 注入高亮器：md-editor-v3 不自带 highlight.js，不传 instance 时
  //   代码块走 escapeHtml 分支，渲染出来是纯文本（2026-09-11 实测踩到）。
  //   用完整版 highlight.js（与 mdToHtml.js 同一份依赖）：语言注册表只有一份，
  //   未知语言内部会回落 highlightAuto，不会抛错。
  editorExtensions: {
    highlight: { instance: hljs },
  },
})

// 标记已初始化，方便调试时确认没有重复执行
export const MD_EDITOR_READY = true
