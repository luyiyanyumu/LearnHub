<script setup>
import { computed, defineAsyncComponent, ref } from 'vue'
import { useRoute } from 'vue-router'
import { isDark, toggleTheme } from '../composables/useTheme'
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

const activeMenu = computed(() => {
  const p = route.path
  if (p.startsWith('/notes')) return '/notes'
  if (p.startsWith('/refs')) return '/refs'
  if (p.startsWith('/files')) return '/files'
  return '/'
})
</script>

<template>
  <div class="layout">
    <aside class="side">
      <div class="logo">
        <div class="logo-mark">
          <span class="logo-l">L</span>
          <span class="logo-dot"></span>
        </div>
        <div class="logo-text">
          <div class="logo-title">学习工作台</div>
          <div class="logo-sub">Learn · Note · Grow</div>
        </div>
      </div>

      <el-menu :default-active="activeMenu" router class="menu">
        <el-menu-item index="/">
          <span class="mi-name">总览</span>
        </el-menu-item>
        <el-menu-item index="/notes">
          <span class="mi-name">笔记</span>
        </el-menu-item>
        <el-menu-item index="/refs">
          <span class="mi-name">速查卡</span>
        </el-menu-item>
        <el-menu-item index="/files">
          <span class="mi-name">资料库</span>
        </el-menu-item>
      </el-menu>

      <div class="side-foot">
        <button class="theme-btn" type="button" @click="openSettings">
          <svg viewBox="0 0 24 24" width="15" height="15" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round">
            <circle cx="12" cy="12" r="3.2"></circle>
            <path d="M19.4 15a1.65 1.65 0 0 0 .33 1.82l.06.06a2 2 0 1 1-2.83 2.83l-.06-.06a1.65 1.65 0 0 0-1.82-.33 1.65 1.65 0 0 0-1 1.51V21a2 2 0 1 1-4 0v-.09a1.65 1.65 0 0 0-1-1.51 1.65 1.65 0 0 0-1.82.33l-.06.06a2 2 0 1 1-2.83-2.83l.06-.06a1.65 1.65 0 0 0 .33-1.82 1.65 1.65 0 0 0-1.51-1H3a2 2 0 1 1 0-4h.09a1.65 1.65 0 0 0 1.51-1 1.65 1.65 0 0 0-.33-1.82l-.06-.06a2 2 0 1 1 2.83-2.83l.06.06a1.65 1.65 0 0 0 1.82.33h.01a1.65 1.65 0 0 0 1-1.51V3a2 2 0 1 1 4 0v.09a1.65 1.65 0 0 0 1 1.51h.01a1.65 1.65 0 0 0 1.82-.33l.06-.06a2 2 0 1 1 2.83 2.83l-.06.06a1.65 1.65 0 0 0-.33 1.82v.01a1.65 1.65 0 0 0 1.51 1H21a2 2 0 1 1 0 4h-.09a1.65 1.65 0 0 0-1.51 1Z"></path>
          </svg>
          <span>设置</span>
        </button>
        <button class="theme-btn" type="button" @click="toggleTheme">
          <svg v-if="isDark" viewBox="0 0 24 24" width="15" height="15" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round">
            <circle cx="12" cy="12" r="4.2"></circle>
            <path d="M12 2.5v2.4M12 19.1v2.4M2.5 12h2.4M19.1 12h2.4M5 5l1.7 1.7M17.3 17.3L19 19M19 5l-1.7 1.7M6.7 17.3L5 19"></path>
          </svg>
          <svg v-else viewBox="0 0 24 24" width="15" height="15" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round">
            <path d="M20.5 14.2A8.5 8.5 0 0 1 9.8 3.5a8.5 8.5 0 1 0 10.7 10.7Z"></path>
          </svg>
          <span>{{ isDark ? '浅色模式' : '深色模式' }}</span>
        </button>
        <div class="foot-note">Keep learning, keep growing</div>
      </div>
    </aside>

    <main class="content">
      <router-view />
    </main>

    <!-- 全局智能体（右下角悬浮 + 抽屉） -->
    <AgentPanel />

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
  width: 216px;
  flex-shrink: 0;
  background: var(--app-side);
  border-right: 1px solid var(--app-border);
  display: flex;
  flex-direction: column;
  transition: background-color var(--dur) ease, border-color var(--dur) ease;
}

.logo {
  display: flex;
  align-items: center;
  gap: 11px;
  padding: 22px 18px 18px;
}

.logo-mark {
  position: relative;
  width: 38px;
  height: 38px;
  border-radius: 12px;
  /* 单色块 → 同 hue 斜向渐变：比平色更有"做工"，又不到花哨的程度 */
  background: linear-gradient(140deg, var(--app-brand) 10%, var(--app-brand-deep) 95%);
  display: flex;
  align-items: center;
  justify-content: center;
  box-shadow: 0 4px 12px var(--app-brand-glow);
  transition: transform var(--dur) var(--ease);
}

.logo-mark:hover {
  transform: rotate(-6deg) scale(1.04);
}

.logo-l {
  font-size: 20px;
  font-weight: 600;
  color: #fff;
}

.logo-dot {
  position: absolute;
  right: 5px;
  top: 5px;
  width: 6px;
  height: 6px;
  border-radius: 50%;
  background: #ffd166;
  border: 1.5px solid var(--app-side);
}

.logo-text {
  min-width: 0;
}

.logo-title {
  font-size: 14.5px;
  font-weight: 600;
  color: var(--app-text-1);
  white-space: nowrap;
}

.logo-sub {
  font-size: 10.5px;
  color: var(--app-text-3);
  margin-top: 2px;
  letter-spacing: 0.06em;
}

.menu {
  border-right: none;
  flex: 1;
  padding: 4px 10px;
  --el-menu-bg-color: transparent;
  --el-menu-item-height: 42px;
  --el-menu-text-color: var(--app-text-2);
  --el-menu-active-color: var(--app-brand-deep);
  --el-menu-hover-bg-color: transparent;
}

.menu :deep(.el-menu-item) {
  position: relative;
  margin-bottom: 3px;
  border-radius: var(--radius-sm);
  font-size: 14px;
  font-weight: 500;
  transition: background-color var(--dur-fast) ease, color var(--dur-fast) ease;
}

/* hover 用中性色而非品牌色：品牌色要留给 active，稀有才有分量 */
.menu :deep(.el-menu-item:hover) {
  background: color-mix(in srgb, var(--app-text-1) 5%, transparent);
}

.menu :deep(.el-menu-item.is-active) {
  background: var(--app-brand-soft);
  color: var(--app-brand-deep);
  font-weight: 600;
}

/* active 指示条：细圆角短棒，比整条 inset 阴影更精致 */
.menu :deep(.el-menu-item.is-active)::before {
  content: '';
  position: absolute;
  left: 0;
  top: 50%;
  transform: translateY(-50%);
  width: 3px;
  height: 16px;
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
  overflow: auto;
  background: var(--app-bg);
  transition: background-color var(--dur) ease;
}
</style>
