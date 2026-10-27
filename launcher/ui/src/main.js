import { createApp, h } from 'vue'
import { ElButton, ElCheckbox, ElConfigProvider, ElDropdown, ElDropdownItem, ElDropdownMenu, ElIcon, ElInput, ElOption, ElSelect, ElSkeleton, ElSwitch } from 'element-plus'
import zhCn from 'element-plus/es/locale/lang/zh-cn'
import 'element-plus/dist/index.css'
import 'element-plus/theme-chalk/dark/css-vars.css'
import './style.css'
import App from './App.vue'

const app = createApp({ render: () => h(ElConfigProvider, { locale: zhCn }, { default: () => h(App) }) })
for (const component of [ElButton, ElCheckbox, ElDropdown, ElDropdownItem, ElDropdownMenu, ElIcon, ElInput, ElOption, ElSelect, ElSkeleton, ElSwitch]) app.component(component.name, component)
app.mount('#app')
