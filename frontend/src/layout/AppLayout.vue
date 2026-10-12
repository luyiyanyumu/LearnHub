<script setup>
import { computed, defineAsyncComponent, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { useRoute } from 'vue-router'
import { ArrowLeft, ArrowRight, ChatDotRound, Collection, Cpu, Document, FolderOpened, House, Moon, Notebook, Setting, Sunny } from '@element-plus/icons-vue'
import { isDark, toggleTheme } from '../composables/useTheme'
import { focusMode } from '../composables/useViewMode'
import AgentPanel from '../components/AgentPanel.vue'
import { noteTabs, isNoteEditorRoute, openNoteRoute } from '../composables/useNoteTabs.js'
import { backendDown, backendDownReason } from '../utils/connectionState.js'
import { categoryApi } from '../api'

/**
 * 设置面板改成异步组件。
 * <p>注意不能只写 defineAsyncComponent —— 它的加载时机是「组件被实例化」，
 * 所以下面还必须用 v-if 控制它**第一次被打开后才挂载**，否则等于没省。
 * 挂载后不再卸载（settingsOpen 关闭只是隐藏），避免重复下载与丢状态。
 */
const SettingsDialog = defineAsyncComponent(() => import('../components/SettingsDialog.vue'))
const NoteWorkspace = defineAsyncComponent(() => import('../components/NoteWorkspace.vue'))

const route = useRoute()
const isNoteEditor = computed(() => isNoteEditorRoute(route))
const showNoteWorkspace = computed(() => route.path === '/notes' || isNoteEditor.value)
watch(() => route.fullPath, () => openNoteRoute(route), { immediate: true })
const settingsOpen = ref(false)
const settingsMounted = ref(false)

/** 全局降级横幅的重试按钮状态 */
const probing = ref(false)
/**
 * 用户手动关掉了横幅。
 * <p>关闭只在「本次故障内」有效：backendDown 一变化（恢复、或又一次新的失败）就重置，
 * 否则用户会永远看不到后续的降级提示，那比不显示更危险。
 */
const bannerDismissed = ref(false)
watch(backendDown, () => {
  bannerDismissed.value = false
})

/**
 * 重试探测：用一个最轻的既有接口探活（分类树）。
 * <p>成功时不需要在这里手动撤横幅 —— api 拦截器拿到任何成功响应都会调 markBackendUp()，
 * 横幅自动消失，避免"探测成功了但横幅还在"这种两处状态各说各话的情况。
 */
async function retryBackend() {
  probing.value = true
  try {
    await categoryApi.tree()
  } catch {
    // 失败原因已由拦截器写进 backendDownReason，这里不再重复弹提示
  } finally {
    probing.value = false
  }
}

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

/** 侧栏导航项：与 Element Plus 控件共用图标风格。 */
const navItems = [
  { index: '/', name: '总览', icon: House },
  { index: '/notes', name: '笔记', icon: Notebook },
  { index: '/refs', name: '速查卡', icon: Document },
  { index: '/files', name: '资料库', icon: FolderOpened },
  { index: '/knowledge', name: '知识库', icon: Collection },
  // 智能体：会话管理（新开/切换/删除/各自选模型）与对话，和笔记/知识库同级
  { index: '/agent', name: '智能体', icon: ChatDotRound },
  // 代码库：代码片段单独存放（不进知识库的向量索引，避免把笔记挤下去）
  { index: '/code', name: '代码库', icon: Cpu },
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

      <div v-show="!sidebarCollapsed" class="nav-caption">WORKSPACE</div>
      <nav class="workspace-nav" aria-label="主导航">
        <router-link
          v-for="n in navItems"
          :key="n.index"
          :to="n.index"
          class="tn-item"
          :class="{ on: activeMenu === n.index }"
          :title="sidebarCollapsed ? n.name : undefined"
          :aria-label="n.name"
          :aria-current="activeMenu === n.index ? 'page' : undefined"
        >
          <span class="tn-ico" aria-hidden="true"><component :is="n.icon" /></span>
          <span class="tn-name">{{ n.name }}</span>
        </router-link>
      </nav>

      <div class="side-foot">
        <button v-if="!narrowScreen" class="theme-btn collapse-btn" type="button" :title="sidebarCollapsed ? '展开侧栏' : '收起侧栏'" :aria-label="sidebarCollapsed ? '展开侧栏' : '收起侧栏'" @click="toggleCollapse">
          <ArrowRight v-if="sidebarCollapsed" class="foot-icon" aria-hidden="true" />
          <ArrowLeft v-else class="foot-icon" aria-hidden="true" />
          <span v-show="!sidebarCollapsed">收起侧栏</span>
        </button>
        <button class="theme-btn" type="button" title="设置" aria-label="设置" @click="openSettings">
          <Setting class="foot-icon" aria-hidden="true" />
          <span v-show="!sidebarCollapsed">设置</span>
        </button>
        <button class="theme-btn" type="button" :title="isDark ? '切换到浅色模式' : '切换到深色模式'" :aria-label="isDark ? '浅色模式' : '深色模式'" @click="toggleTheme">
          <Sunny v-if="isDark" class="foot-icon" aria-hidden="true" />
          <Moon v-else class="foot-icon" aria-hidden="true" />
          <span v-show="!sidebarCollapsed">{{ isDark ? '浅色模式' : '深色模式' }}</span>
        </button>
        <div class="foot-note" v-show="!sidebarCollapsed">Keep learning, keep growing</div>
      </div>
    </aside>

    <main class="content">
      <!-- 全局降级横幅：整条链路不可达时统一说明，避免各页面的空态被误读成"我的数据没了"。
           页面级失败态说"这一块没加载出来"，这条说"后端没连上"，两者分工不同。 -->
      <div v-if="backendDown && !bannerDismissed" class="offline-bar" role="alert">
        <span class="ob-dot" aria-hidden="true" />
        <span class="ob-text">
          <b>后端服务未连接</b>
          <span class="ob-why">{{ backendDownReason }}；页面上的内容可能不是最新的</span>
        </span>
        <button class="ob-btn" type="button" :disabled="probing" @click="retryBackend">
          {{ probing ? '正在重试…' : '重试连接' }}
        </button>
        <button class="ob-close" type="button" aria-label="关闭提示" @click="bannerDismissed = true">×</button>
      </div>
      <NoteWorkspace v-if="noteTabs.length" v-show="showNoteWorkspace" />
      <router-view v-if="!isNoteEditor" />
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
  width: 224px;
  flex-shrink: 0;
  min-height: 0;
  background: var(--app-side);
  border-right: 1px solid var(--app-border);
  display: flex;
  flex-direction: column;
  transition: background-color var(--dur) ease, border-color var(--dur) ease, width var(--dur) var(--ease);
}

.logo {
  display: flex;
  align-items: center;
  min-height: 92px;
  padding: 24px 24px 20px;
}

.logo-lockup {
  height: 38px;
  max-width: 100%;
  width: auto;
  object-fit: contain;
  display: block;
  user-select: none;
  -webkit-user-drag: none;
}

.logo-mark {
  width: 36px;
  height: 36px;
  display: block;
  border-radius: var(--radius-lg);
  user-select: none;
  -webkit-user-drag: none;
}

.nav-caption {
  padding: 12px 26px 10px;
  color: var(--app-text-2);
  font-size: 10px;
  font-weight: 600;
  letter-spacing: 0.15em;
}

.workspace-nav {
  flex: 1;
  min-height: 0;
  padding: 0 12px 24px;
  display: flex;
  flex-direction: column;
  gap: 5px;
  overflow-y: auto;
  overflow-x: hidden;
}

.tn-item {
  position: relative;
  display: flex;
  align-items: center;
  gap: 12px;
  min-height: 44px;
  padding: 0 14px;
  border: 1px solid transparent;
  border-radius: var(--radius-lg);
  color: var(--app-text-2);
  font-size: 13.5px;
  font-weight: 500;
  text-decoration: none;
  transition: color var(--dur-fast) ease, background-color var(--dur-fast) ease, border-color var(--dur-fast) ease;
}

.tn-ico {
  display: flex;
  align-items: center;
  justify-content: center;
  width: 20px;
  height: 20px;
  flex-shrink: 0;
}

.tn-ico :deep(svg) {
  width: 19px;
  height: 19px;
}

.tn-name {
  white-space: nowrap;
}

.tn-item:hover {
  color: var(--app-text-1);
  background: var(--app-bg);
}

.tn-item.on {
  color: var(--app-brand-deep);
  font-weight: 600;
  background: var(--app-brand-soft);
  border-color: color-mix(in srgb, var(--app-brand) 12%, transparent);
}

.tn-item.on::after {
  content: '';
  width: 5px;
  height: 5px;
  margin-left: auto;
  border-radius: 50%;
  background: var(--app-brand);
}

.tn-item:focus-visible,
.theme-btn:focus-visible {
  outline: 2px solid var(--app-brand);
  outline-offset: -2px;
}

.side-foot {
  flex-shrink: 0;
  margin: 0 16px;
  padding: 14px 0 18px;
  border-top: 1px solid var(--app-border-weak);
  display: flex;
  flex-direction: column;
  gap: 2px;
}

.theme-btn {
  display: flex;
  align-items: center;
  justify-content: flex-start;
  gap: 12px;
  min-height: 36px;
  padding: 0 10px;
  border: 1px solid transparent;
  border-radius: var(--radius);
  background: transparent;
  color: var(--app-text-2);
  font-size: 12px;
  font-family: inherit;
  cursor: pointer;
  transition: color var(--dur-fast) ease, background-color var(--dur-fast) ease;
}

.foot-icon {
  width: 16px;
  height: 16px;
  flex-shrink: 0;
}

.theme-btn:hover {
  color: var(--app-brand-deep);
  background: var(--app-brand-soft);
}

.foot-note {
  margin-top: 14px;
  padding-left: 10px;
  font-size: 10px;
  color: var(--app-text-2);
  letter-spacing: 0.015em;
}

.content {
  flex: 1;
  min-width: 0;
  overflow: auto;
  background: var(--app-bg);
  transition: background-color var(--dur) ease;
}

/* 全局降级横幅：整宽状态条，贴在内容区顶部（滚动时保持可见）。
   用 Element Plus 的 warning 系列，深浅色主题下都由 EP 自己换色。 */
.offline-bar {
  position: sticky;
  top: 0;
  z-index: 5;
  display: flex;
  align-items: center;
  gap: var(--space-sm);
  padding: 8px 20px;
  border-bottom: 1px solid var(--el-color-warning-light-5);
  background: var(--el-color-warning-light-9);
  color: var(--app-text-1);
  font-size: var(--font-caption);
}

.ob-dot {
  flex-shrink: 0;
  width: 8px;
  height: 8px;
  border-radius: 50%;
  background: var(--el-color-warning);
}

.ob-text {
  display: flex;
  flex-direction: column;
  gap: 2px;
  flex: 1;
  min-width: 0;
}

.ob-why {
  color: var(--app-text-2);
  overflow-wrap: anywhere;
}

.ob-btn {
  flex-shrink: 0;
  padding: 4px 12px;
  border: 1px solid var(--app-border);
  border-radius: var(--radius);
  background: var(--app-card);
  color: var(--app-text-1);
  font: inherit;
  cursor: pointer;
  transition: border-color var(--dur) var(--ease), color var(--dur) var(--ease);
}

.ob-btn:hover:not(:disabled) {
  border-color: var(--app-brand);
  color: var(--app-brand);
}

.ob-btn:focus-visible {
  outline: 2px solid var(--app-brand);
  outline-offset: 2px;
}

.ob-btn:disabled {
  cursor: progress;
  opacity: .6;
}

.ob-close {
  flex-shrink: 0;
  width: 24px;
  height: 24px;
  padding: 0;
  border: 0;
  border-radius: var(--radius);
  background: transparent;
  color: var(--app-text-2);
  font-family: inherit;
  font-size: 16px;
  line-height: 1;
  cursor: pointer;
}

.ob-close:hover {
  background: var(--el-color-warning-light-7);
  color: var(--app-text-1);
}

.ob-close:focus-visible {
  outline: 2px solid var(--app-brand);
  outline-offset: 2px;
}

/* 窄屏：横幅内容换行，重试按钮独占一行，避免挤成一条看不清的细条 */
@media (max-width: 680px) {
  .offline-bar {
    flex-wrap: wrap;
    padding: 8px 12px;
  }

  .ob-btn {
    width: 100%;
  }
}

/* 窄屏继续使用 64px 图标栏，导航和底部操作均保持可点击。 */
.side.collapsed {
  width: 64px;
}

.side.collapsed .logo {
  min-height: 84px;
  padding: 24px 12px;
  justify-content: center;
}

.side.collapsed .workspace-nav {
  padding: 0 8px 16px;
  gap: 6px;
}

.side.collapsed .tn-name {
  display: none;
}

.side.collapsed .tn-item {
  justify-content: center;
  min-height: 44px;
  padding: 0;
  gap: 0;
}

.side.collapsed .tn-item.on::after {
  display: none;
}

.side.collapsed .side-foot {
  margin: 0 8px;
  padding: 12px 0 16px;
  gap: 4px;
}

.side.collapsed .theme-btn {
  justify-content: center;
  min-height: 40px;
  padding: 0;
}
</style>
