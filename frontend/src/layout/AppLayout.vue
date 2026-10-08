<script setup>
import { computed, defineAsyncComponent, onBeforeUnmount, onMounted, ref } from 'vue'
import { useRoute } from 'vue-router'
import { isDark, toggleTheme } from '../composables/useTheme'
import { focusMode } from '../composables/useViewMode'
import AgentPanel from '../components/AgentPanel.vue'

/**
 * 设置面板改成异步组件。
 * <p>注意不能只写 defineAsyncComponent —— 它的加载时机是「组件被实例化」，
 * 所以下面还必须用 v-if 控制它**第一次被打开后才挂载**，否则等于没省。
 * 挂载后不再卸载（settingsOpen 关闭只是隐藏），避免重复下载与丢状态。
 */
const SettingsDialog = defineAsyncComponent(() => import('../components/SettingsDialog.vue'))

const route = useRoute()
const settingsOpen = ref(false)
const settingsMounted = ref(false)

/** 首次点「设置」时才把面板拉进来 */
function openSettings() {
  settingsMounted.value = true
  settingsOpen.value = true
}

/**
 * 当前高亮哪个导航项。
 *
 * 原来是一串 if 判断（/notes、/refs、/files、/knowledge…），**漏掉的分支会静默回落到「总览」**——
 * 新增「智能体」页时就踩到了：进入 /agent 后左边高亮的是 01 总览（看起来像"点智能体跳到了总览"）。
 * 现在改成对 navItems 做**最长前缀匹配**：新增页面只要在 navItems 里登记过就自动正确，
 * 不会再因为忘加一个 if 而高亮错项。
 */
const activeMenu = computed(() => {
  const path = route.path
  let best = '/'
  for (const n of navItems) {
    if (n.index === '/') {
      continue // 首页单独兜底，不参与前缀匹配（否则任何路径都会被它"包含"）
    }
    if (path === n.index || path.startsWith(n.index + '/')) {
      if (n.index.length > best.length) {
        best = n.index
      }
    }
  }
  return best
})

/** 侧栏导航项：统一线性图标（stroke 风格，16px 视觉） */
const navItems = [
  { index: '/', name: '总览', icon: 'M3.5 12l8.5-7.5L20.5 12M5.5 10.5V20h13v-9.5M10 20v-5h4v5' },
  { index: '/notes', name: '笔记', icon: 'M7 3.5h7a3 3 0 0 1 3 3V20.5H7a3 3 0 0 1-3-3v-11a3 3 0 0 1 3-3ZM10 8h4M10 11.5h4M10 15h2' },
  { index: '/refs', name: '速查卡', icon: 'M13 2.5 5 10.5V21.5h14V2.5h-6Zm0 0v7h6M8.5 14h7M8.5 17h5' },
  { index: '/files', name: '资料库', icon: 'M3.5 6.5a2 2 0 0 1 2-2h4l2 2.5h7a2 2 0 0 1 2 2v9.5a2 2 0 0 1-2 2h-13a2 2 0 0 1-2-2Z' },
  { index: '/knowledge', name: '知识库', icon: 'M12 6.5c-1.8-1.6-4.3-2-7-2v13c2.7 0 5.2.4 7 2 1.8-1.6 4.3-2 7-2v-13c-2.7 0-5.2.4-7 2Zm0 0v13' },
  // 智能体：会话管理（新开/切换/删除/各自选模型）与对话，和笔记/知识库同级
  { index: '/agent', name: '智能体', icon: 'M4 5.5h16v11H9l-4 3.5v-14.5ZM8 9h8M8 12.5h5' },
  // 代码库：代码片段单独存放（不进知识库的向量索引，避免把笔记挤下去）
  { index: '/code', name: '代码库', icon: 'M9 7.5 5.5 12 9 16.5M15 7.5 18.5 12 15 16.5M13 5.5l-2 13' },
]

/** 侧栏折叠：收起后只剩图标（64px 图标栏），状态持久化到 localStorage */
const collapsed = ref(localStorage.getItem('lh-sidebar-collapsed') === '1')
const narrowViewport = window.matchMedia('(max-width: 680px)')
const narrowScreen = ref(narrowViewport.matches)
const sidebarCollapsed = computed(() => collapsed.value || narrowScreen.value)
const syncNarrowScreen = event => { narrowScreen.value = event.matches }
onMounted(() => narrowViewport.addEventListener('change', syncNarrowScreen))
onBeforeUnmount(() => narrowViewport.removeEventListener('change', syncNarrowScreen))
function toggleCollapse() {
  collapsed.value = !collapsed.value
  localStorage.setItem('lh-sidebar-collapsed', collapsed.value ? '1' : '0')
}
</script>

<template>
  <div class="layout">
    <aside class="side" :class="{ collapsed: sidebarCollapsed }" v-show="!focusMode">
      <div class="logo">
        <!-- 品牌图：展开时用「图标 + 字标」组合标，折叠时只留图标。
             深色模式换一份"把文字提亮"的变体（图标本体两张一样），
             这样位图商标在两种主题下都不会变成一块白底。 -->
        <img
          v-if="!sidebarCollapsed"
          class="logo-lockup"
          :src="isDark ? '/brand-logo-dark.png' : '/brand-logo.png'"
          alt="学习工作台"
          draggable="false"
        />
        <img v-else class="logo-mark" src="/brand-icon.png" alt="学习工作台" draggable="false" />
      </div>

      <nav class="time-nav" aria-label="主导航">
        <router-link
          v-for="(n, i) in navItems"
          :key="n.index"
          :to="n.index"
          class="tn-item"
          :class="{ on: activeMenu === n.index }"
          :title="sidebarCollapsed ? n.name : undefined"
          :aria-label="n.name"
          :aria-current="activeMenu === n.index ? 'page' : undefined"
        >
          <span class="tn-node" aria-hidden="true"></span>
          <span class="tn-seq" aria-hidden="true">{{ String(i + 1).padStart(2, '0') }}</span>
          <svg class="tn-ico" viewBox="0 0 24 24" width="16" height="16" fill="none" stroke="currentColor" stroke-width="1.7" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true">
            <path :d="n.icon" />
          </svg>
          <span class="tn-name">{{ n.name }}</span>
        </router-link>
      </nav>

      <div class="side-foot">
        <button v-if="!narrowScreen" class="theme-btn collapse-btn" type="button" :title="sidebarCollapsed ? '展开侧栏' : '收起侧栏'" :aria-label="sidebarCollapsed ? '展开侧栏' : '收起侧栏'" @click="toggleCollapse">
          <svg v-if="!sidebarCollapsed" viewBox="0 0 24 24" width="15" height="15" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round">
            <path d="M14 6l-6 6 6 6" />
          </svg>
          <svg v-else viewBox="0 0 24 24" width="15" height="15" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round">
            <path d="M10 6l6 6-6 6" />
          </svg>
          <span v-show="!sidebarCollapsed">收起侧栏</span>
        </button>
        <button class="theme-btn" type="button" title="设置" aria-label="设置" @click="openSettings">
          <svg viewBox="0 0 24 24" width="15" height="15" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round">
            <circle cx="12" cy="12" r="3.2"></circle>
            <path d="M19.4 15a1.65 1.65 0 0 0 .33 1.82l.06.06a2 2 0 1 1-2.83 2.83l-.06-.06a1.65 1.65 0 0 0-1.82-.33 1.65 1.65 0 0 0-1 1.51V21a2 2 0 1 1-4 0v-.09a1.65 1.65 0 0 0-1-1.51 1.65 1.65 0 0 0-1.82.33l-.06.06a2 2 0 1 1-2.83-2.83l.06-.06a1.65 1.65 0 0 0 .33-1.82 1.65 1.65 0 0 0-1.51-1H3a2 2 0 1 1 0-4h.09a1.65 1.65 0 0 0 1.51-1 1.65 1.65 0 0 0-.33-1.82l-.06-.06a2 2 0 1 1 2.83-2.83l.06.06a1.65 1.65 0 0 0 1.82.33h.01a1.65 1.65 0 0 0 1-1.51V3a2 2 0 1 1 4 0v.09a1.65 1.65 0 0 0 1 1.51h.01a1.65 1.65 0 0 0 1.82-.33l.06-.06a2 2 0 1 1 2.83 2.83l-.06.06a1.65 1.65 0 0 0-.33 1.82v.01a1.65 1.65 0 0 0 1.51 1H21a2 2 0 1 1 0 4h-.09a1.65 1.65 0 0 0-1.51 1Z"></path>
          </svg>
          <span v-show="!sidebarCollapsed">设置</span>
        </button>
        <button class="theme-btn" type="button" :title="isDark ? '切换到浅色模式' : '切换到深色模式'" :aria-label="isDark ? '浅色模式' : '深色模式'" @click="toggleTheme">
          <svg v-if="isDark" viewBox="0 0 24 24" width="15" height="15" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round">
            <circle cx="12" cy="12" r="4.2"></circle>
            <path d="M12 2.5v2.4M12 19.1v2.4M2.5 12h2.4M19.1 12h2.4M5 5l1.7 1.7M17.3 17.3L19 19M19 5l-1.7 1.7M6.7 17.3L5 19"></path>
          </svg>
          <svg v-else viewBox="0 0 24 24" width="15" height="15" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round">
            <path d="M20.5 14.2A8.5 8.5 0 0 1 9.8 3.5a8.5 8.5 0 1 0 10.7 10.7Z"></path>
          </svg>
          <span v-show="!sidebarCollapsed">{{ isDark ? '浅色模式' : '深色模式' }}</span>
        </button>
        <div class="foot-note" v-show="!sidebarCollapsed">Keep learning, keep growing</div>
      </div>
    </aside>

    <main class="content">
      <router-view />
    </main>

    <!-- 全局智能体（右下角悬浮 + 抽屉）。
         智能体页本身就是面板，那页不再挂这个悬浮入口 —— 否则右下角多一个"智能体"按钮，点了还是同一页 -->
    <AgentPanel v-if="route.path !== '/agent'" />

    <!-- 设置面板：首次打开后才挂载（懒加载，不进首屏包） -->
    <SettingsDialog v-if="settingsMounted" v-model="settingsOpen" />
  </div>
</template>

<style scoped>
.layout {
  display: flex;
  height: 100%;
}

.side {
  width: 200px;
  flex-shrink: 0;
  background: var(--app-side);
  border-right: 1px solid var(--app-border);
  display: flex;
  flex-direction: column;
  transition: background-color var(--dur) ease, border-color var(--dur) ease, width var(--dur) var(--ease);
}

.logo {
  display: flex;
  align-items: center;
  gap: 11px;
  padding: 22px 18px 18px;
}

/* 商标是位图（public/brand-*.png，已抠成透明底）：
   高度固定、宽度按比例；关掉拖拽与选中，免得用起来像"一张图片" */
.logo-lockup {
  height: 38px;
  width: auto;
  display: block;
  user-select: none;
  -webkit-user-drag: none;
}

.logo-mark {
  width: 38px;
  height: 38px;
  display: block;
  border-radius: 12px;
  user-select: none;
  -webkit-user-drag: none;
  transition: transform var(--dur) var(--ease);
}

.logo-mark:hover {
  transform: rotate(-6deg) scale(1.04);
}

/* 侧栏导航：竖排时间轴（签名视觉）
   一根 1px 竖线贯穿全部导航项，节点标记当前位置 ——
   同一根线也用在学习路径上，导航即品牌符号 */
.time-nav {
  position: relative;
  flex: 1;
  padding: 4px 10px;
  display: flex;
  flex-direction: column;
}

/* 竖线：仅覆盖首项与末项节点之间，不外溢到 logo 与底部操作区 */
.time-nav::before {
  content: '';
  position: absolute;
  left: 25px;
  top: 24px;
  bottom: 24px;
  width: 1px;
  background: var(--app-border);
}

.tn-item {
  position: relative;
  display: flex;
  align-items: center;
  gap: 9px;
  height: 44px;
  padding: 0 10px;
  border-radius: var(--radius);
  color: var(--app-text-2);
  font-size: 13.5px;
  font-weight: 500;
  text-decoration: none;
  /* 悬停只动文字与节点，不动背景：品牌色要留给当前态，稀有才有分量 */
  transition: color var(--dur-fast) ease;
}

.tn-node {
  width: 8px;
  height: 8px;
  flex-shrink: 0;
  margin-left: 2px;
  border-radius: 50%;
  background: var(--app-border);
  /* 用侧栏底色断开竖线，节点不悬空 */
  box-shadow: 0 0 0 4px var(--app-side);
  transition: background-color var(--dur-instant) linear;
}

.tn-seq {
  font-size: var(--font-caption);
  color: var(--app-text-3);
  letter-spacing: 0.06em;
  font-variant-numeric: tabular-nums;
}

.tn-ico {
  flex-shrink: 0;
  opacity: 0.78;
  transition: opacity var(--dur-fast) ease;
}

.tn-name {
  white-space: nowrap;
}

.tn-item:hover {
  color: var(--app-text-1);
}

/* 当前态：指示条 + 节点实心，取消整块填充 */
.tn-item.on {
  color: var(--app-brand-deep);
  font-weight: 600;
}

.tn-item.on .tn-ico {
  opacity: 1;
}

.tn-item.on .tn-node {
  background: var(--app-brand);
}

.tn-item.on::before {
  content: '';
  position: absolute;
  left: -10px;
  top: 50%;
  transform: translateY(-50%);
  width: 3px;
  height: 20px;
  border-radius: 2px;
  background: var(--app-brand);
}

.side-foot {
  padding: 12px 16px 14px;
  border-top: 1px solid var(--app-border-weak);
  display: flex;
  flex-direction: column;
  gap: 8px;
}

.theme-btn {
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 7px;
  height: 32px;
  border: 1px solid var(--app-border);
  border-radius: var(--radius-sm);
  background: transparent;
  color: var(--app-text-2);
  font-size: 12.5px;
  font-family: inherit;
  cursor: pointer;
  transition: all var(--dur-fast) ease;
}

.theme-btn:hover {
  border-color: var(--app-brand);
  color: var(--app-brand-deep);
  background: var(--app-brand-soft);
}

.theme-btn:active {
  transform: scale(0.98);
}

.foot-note {
  font-size: 10px;
  color: var(--app-text-3);
  text-align: center;
  letter-spacing: 0.05em;
}

.content {
  flex: 1;
  min-width: 0;
  overflow: auto;
  background: var(--app-bg);
  transition: background-color var(--dur) ease;
}

/* ---- 折叠态：侧栏收成 64px 图标栏，图标带 title 提示 ---- */
.side.collapsed {
  width: 64px;
}

.side.collapsed .logo {
  padding: 22px 12px 18px;
  justify-content: center;
}

.side.collapsed .time-nav {
  padding: 4px 8px;
}

/* 折叠态：竖线与序号隐藏，节点居中。
   原实现靠「指示条 left:-8px」的位移补丁避免悬空，这里随 el-menu 一并删除。 */
.side.collapsed .time-nav::before,
.side.collapsed .tn-seq,
.side.collapsed .tn-name {
  display: none;
}

.side.collapsed .tn-item {
  justify-content: center;
  padding: 0;
  gap: 0;
}

.side.collapsed .tn-node {
  margin-left: 0;
  box-shadow: none;
}

.side.collapsed .tn-item.on::before {
  left: -8px;
}

.side.collapsed .side-foot {
  padding: 12px 10px 14px;
}

.side.collapsed .theme-btn {
  justify-content: center;
  padding: 0;
}
</style>
