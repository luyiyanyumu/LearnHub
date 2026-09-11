import { ref } from 'vue'

const STORAGE_KEY = 'learnhub-theme'

/** 当前是否深色 */
export const isDark = ref(false)

/** 当前主题模式：light / dark / auto（跟随系统） */
export const themeMode = ref('auto')

function apply(dark) {
  isDark.value = dark
  document.documentElement.classList.toggle('dark', dark)
}

function systemDark() {
  return window.matchMedia('(prefers-color-scheme: dark)').matches
}

/** 应用初始化：localStorage（显式选择）> 系统偏好 */
export function initTheme() {
  const saved = localStorage.getItem(STORAGE_KEY)
  themeMode.value = saved === 'dark' || saved === 'light' ? saved : 'auto'
  apply(themeMode.value === 'auto' ? systemDark() : themeMode.value === 'dark')
  // 系统偏好变化时，仅在“跟随系统”模式下跟随
  window.matchMedia('(prefers-color-scheme: dark)').addEventListener('change', (e) => {
    if (themeMode.value === 'auto') apply(e.matches)
  })
  return isDark
}

/** 设置主题模式（设置面板用）：light / dark / auto */
export function setThemeMode(mode) {
  themeMode.value = mode
  if (mode === 'auto') {
    localStorage.removeItem(STORAGE_KEY)
    apply(systemDark())
  } else {
    localStorage.setItem(STORAGE_KEY, mode)
    apply(mode === 'dark')
  }
}

/** 切换深浅主题并持久化（侧栏按钮用） */
export function toggleTheme() {
  setThemeMode(isDark.value ? 'light' : 'dark')
}
