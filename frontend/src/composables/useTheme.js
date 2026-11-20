import { ref } from 'vue'

const STORAGE_KEY = 'learnhub-theme'
const SKIN_STORAGE_KEY = 'learnhub-skin'

/** 当前是否深色 */
export const isDark = ref(false)

/** 当前主题模式：light / dark / auto（跟随系统） */
export const themeMode = ref('auto')

/** 主题皮肤与亮度独立，可以搭配浅色、深色或系统模式。 */
export const skin = ref('default')

let switchRevision = 0
function applyWithoutTransition(update) {
  const root = document.documentElement
  // 切换瞬间先禁掉过渡：否则背景/描边等各处元素各自播放 0.25s 过渡，
  // 会出现"半浅半深"的中间帧（快速连点主题按钮时尤其明显）。
  root.classList.add('theme-switching')
  update(root)
  // 双 rAF：等新主题真正完成一次绘制后再恢复过渡
  const revision = ++switchRevision
  requestAnimationFrame(() => requestAnimationFrame(() => {
    if (revision === switchRevision) root.classList.remove('theme-switching')
  }))
}

function apply(dark) {
  isDark.value = dark
  applyWithoutTransition(root => root.classList.toggle('dark', dark))
}

function readPreference(key) {
  try { return localStorage.getItem(key) } catch { return null }
}

function savePreference(key, value) {
  try {
    if (value === null) localStorage.removeItem(key)
    else localStorage.setItem(key, value)
  } catch {
    // 浏览器拒绝持久化时，仍允许在本次页面会话中切换。
  }
}

function applySkin(value) {
  applyWithoutTransition(root => {
    if (value === 'default') root.removeAttribute('data-skin')
    else root.setAttribute('data-skin', value)
  })
  skin.value = value
}

function systemDark() {
  return window.matchMedia('(prefers-color-scheme: dark)').matches
}

/** 应用初始化：localStorage（显式选择）> 系统偏好 */
export function initTheme() {
  const saved = readPreference(STORAGE_KEY)
  themeMode.value = saved === 'dark' || saved === 'light' ? saved : 'auto'
  apply(themeMode.value === 'auto' ? systemDark() : themeMode.value === 'dark')
  applySkin(readPreference(SKIN_STORAGE_KEY) === 'firefly' ? 'firefly' : 'default')
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
    savePreference(STORAGE_KEY, null)
    apply(systemDark())
  } else {
    savePreference(STORAGE_KEY, mode)
    apply(mode === 'dark')
  }
}

export function setSkin(value) {
  if (value !== 'default' && value !== 'firefly') return
  savePreference(SKIN_STORAGE_KEY, value === 'default' ? null : value)
  applySkin(value)
}

/** 切换深浅主题并持久化（侧栏按钮用） */
export function toggleTheme() {
  setThemeMode(isDark.value ? 'light' : 'dark')
}
