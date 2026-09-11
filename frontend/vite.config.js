import vue from '@vitejs/plugin-vue'
import { defineConfig } from 'vite'
import Components from 'unplugin-vue-components/vite'
import { ElementPlusResolver } from 'unplugin-vue-components/resolvers'

// https://vite.dev/config/
export default defineConfig({
  plugins: [
    vue(),
    // Element Plus 按需引入：模板里出现的 <el-xxx> 由插件在编译期补上
    //   import { ElXxx } from 'element-plus/es/components/xxx/index.mjs'
    //   import 'element-plus/es/components/xxx/style/css'
    // 于是不再需要 main.js 里全量 import + dist/index.css
    //（实测 123 个组件里本项目只用 27 个，全量那份 CSS 有 361 KB）。
    //
    // dirs 故意留空：本项目的自定义组件全部是显式相对路径 import 的，
    // 不想让插件再去猜 src/components 下的组件名。
    Components({
      dts: false,
      dirs: [],
      resolvers: [ElementPlusResolver({ importStyle: 'css' })],
    }),
  ],
  optimizeDeps: {
    // 显式列出全部「按需样式入口」。
    // 为什么需要：Vite 的依赖预扫描只沿着**静态** import 图走，而本项目的页面
    // （views/*.vue）全是路由懒加载的，扫描器不会预先看到它们注入的
    // element-plus/es/components/xxx/style/css。结果就是「第一次进某个页面」时
    // 才发现新依赖 → 触发一次重新预构建 → 浏览器被迫整页刷新一次（开发时表现为
    // 页面闪一下、首次加载变慢）。把它们写死在这里，预构建一次到位。
    //
    // 维护提示：新增 el-xxx 组件后，如果开发时发现「进页面会刷新一次」，
    // 把对应的 xxx/style/css 补进这个数组即可（子组件如 menu-item 已自带）。
    include: [
      // mdEditorSetup.js 里显式传给 md-editor 的 highlighter（懒加载模块，
      // 预扫描看不到它 —— 不列在这里会触发重新预构建 + 整页刷新）
      'highlight.js',
      // element-plus 主入口（main.js 的 ElMessage/ElLoading 直引）+ 其内部依赖 config-provider
      'element-plus/es',
      'element-plus/es/components/base/style/css',
      'element-plus/es/components/config-provider/style/css',
      'element-plus/theme-chalk/base.css',
      'element-plus/theme-chalk/dark/css-vars.css',
      'element-plus/es/components/alert/style/css',
      'element-plus/es/components/button/style/css',
      'element-plus/es/components/card/style/css',
      'element-plus/es/components/dialog/style/css',
      'element-plus/es/components/drawer/style/css',
      'element-plus/es/components/dropdown/style/css',
      'element-plus/es/components/dropdown-item/style/css',
      'element-plus/es/components/dropdown-menu/style/css',
      'element-plus/es/components/empty/style/css',
      'element-plus/es/components/icon/style/css',
      'element-plus/es/components/input/style/css',
      'element-plus/es/components/link/style/css',
      // loading 不在模板里，是 el-table 的 v-loading / ElLoading 指令带来的
      'element-plus/es/components/loading/style/css',
      'element-plus/es/components/menu/style/css',
      'element-plus/es/components/menu-item/style/css',
      'element-plus/es/components/option/style/css',
      'element-plus/es/components/option-group/style/css',
      'element-plus/es/components/pagination/style/css',
      'element-plus/es/components/popover/style/css',
      'element-plus/es/components/radio-button/style/css',
      'element-plus/es/components/radio-group/style/css',
      'element-plus/es/components/select/style/css',
      'element-plus/es/components/slider/style/css',
      'element-plus/es/components/tab-pane/style/css',
      'element-plus/es/components/table/style/css',
      'element-plus/es/components/table-column/style/css',
      'element-plus/es/components/tabs/style/css',
      'element-plus/es/components/tag/style/css',
      // 下面两个不在模板里，是命令式 API（main.js 里显式引的）
      'element-plus/es/components/message/style/css',
      'element-plus/es/components/message-box/style/css',
    ],
  },
  server: {
    // 固定 5174：5173 可能被其他项目（如 ai-portal）占用，strictPort 避免静默改端口导致打开错页面
    port: 5174,
    strictPort: true,
    proxy: {
      // 开发环境将 /api 请求代理到后端 Spring Boot
      '/api': {
        target: 'http://localhost:18080',
        changeOrigin: true,
      },
    },
  },
  preview: {
    // 预览「构建产物」用：npm run build 后 `npm run preview` 起 4174。
    // 同样把 /api 代理到后端 —— 否则预览的是静态文件，接口全 404，看不出真实效果。
    // 注意：preview 默认 4173，这里也固定住避免和别的项目撞车。
    port: 4174,
    strictPort: true,
    proxy: {
      '/api': {
        target: 'http://localhost:18080',
        changeOrigin: true,
      },
    },
  },
})
