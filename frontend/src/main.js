// ===========================================================================
// 引入顺序很重要！Element Plus 的样式必须全部排在 ./style.css 之前。
//
// 原因：style.css 里覆盖了 --el-color-primary 等 EP 变量，靠的是「后加载的赢」
// —— 同为 :root、特异性相同，谁后注入谁生效。一旦顺序反过来（或者干脆指望
// 组件懒加载时才注入），开发环境里品牌色就会被 EP 默认蓝 #409eff 盖掉，
// 而构建产物又是对的，变成典型的「开发/生产表现不一致」，很难查。
// ===========================================================================
import 'element-plus/theme-chalk/base.css'           // EP 的 :root 变量 + 基础样式(reset)
import 'element-plus/theme-chalk/dark/css-vars.css'  // 暗色变量组（html.dark { … }）
// 脱离模板的「命令式 API」不会被 unplugin 自动识别，需显式引它们的样式。
// 目前用到 ElMessage / ElMessageBox（JS 那半截仍由各文件自己 import，可被 tree-shaking）。
import 'element-plus/es/components/message/style/css'
import 'element-plus/es/components/message-box/style/css'
// -------- 以上是 Element Plus；下面才是本项目自己的样式与代码 --------
import { createApp } from 'vue'
import './style.css'
import App from './App.vue'
import router from './router'
import { initTheme } from './composables/useTheme'

// ---------------------------------------------------------------------------
// Element Plus 采用「按需引入」（vite.config.js 的 unplugin-vue-components
// + ElementPlusResolver）：模板里写 <el-button> 时由编译期自动补上
//   import { ElButton } from 'element-plus/es/components/button/index.mjs'
//   import 'element-plus/es/components/button/style/css'
// 所以这里既没有 `import ElementPlus from 'element-plus'`，也没有
// `import 'element-plus/dist/index.css'` —— 那两句会把 123 个组件、361 KB CSS
// 全量打进首屏，而本项目实际只用到 27 个（实测首屏 CSS 438.9 KB → 131.4 KB）。
// ---------------------------------------------------------------------------

// md-editor-v3 的全局初始化（含样式）已移到 utils/mdEditorSetup.js，
// 由真正用到它的页面/组件按需加载，不再占用首屏。

// 主题需在渲染前就位，避免闪白/闪黑
initTheme()

// 语言包通过 App.vue 里的 <el-config-provider :locale="zhCn"> 下发
// （app.use(ElementPlus, { locale }) 那种写法会重新引入全量组件，已弃用）
createApp(App).use(router).mount('#app')
