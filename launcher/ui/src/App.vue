<script setup>
import { computed, nextTick, onMounted, onUnmounted, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import { ArrowRight, Box, Check, CircleClose, Close, Connection, Document, Download, FolderOpened, Grid, InfoFilled, Monitor, MoreFilled, Open, Refresh, Search, Setting, Upload, VideoPause, VideoPlay, Warning } from '@element-plus/icons-vue'
import { createBridge } from './bridge.js'
import { actionLabels, canUpdateSource, formatTime, healthSummary, readableError, stateLabels } from './model.js'
import VersionUpdates from './components/VersionUpdates.vue'
import { canCancelLauncherUpdate, canInstallLauncherUpdate, launcherPhaseLabel, normalizeLauncherProgress } from './launcherUpdate.js'

const bridge = createBridge()
const demo = ref(!bridge.native)
const page = ref('overview')
const navItems = [{ id: 'overview', label: '服务总览', icon: Grid }, { id: 'logs', label: '运行日志', icon: Document }, { id: 'diagnostics', label: '环境诊断', icon: Connection }, { id: 'updates', label: '版本更新', icon: Upload }, { id: 'settings', label: '设置', icon: Setting }]
const preferences = ref({ projectRoot: '', mode: 'docker', theme: 'system', autoRefresh: true })
const display = ref({ scale: 1, width: 0, height: 0 })
const version = ref('1.1.0')
const snapshot = ref(null)
const loading = ref(true)
const refreshing = ref(false)
const startupError = ref('')
const refreshError = ref('')
const operationError = ref('')
const localBusy = ref(false)
const job = ref(null)
const cancelPending = ref(false)
const busy = computed(() => localBusy.value || job.value?.running === true)
const health = computed(() => healthSummary(snapshot.value))
const serviceList = computed(() => snapshot.value?.services ?? [])
const currentNav = computed(() => navItems.find(item => item.id === page.value))
const errorsCount = computed(() => (snapshot.value?.diagnostics ?? []).filter(item => ['error', 'danger', 'warning'].includes(item.level)).length)
const activity = ref([])
const selectedLog = ref('__launcher')
const levelFilter = ref('all')
const logSearch = ref('')
const autoScroll = ref(true)
const serviceLogs = ref('')
const logLoading = ref(false)
const logError = ref('')
const logPane = ref(null)
const preferencesBusy = ref(false)
const versionInfo = ref(null)
const versionChecking = ref(false)
const versionError = ref('')
const sourceNeedsBuild = ref(false)
const launcherInfo = ref(null)
const launcherChecking = ref(false)
const launcherError = ref('')
const launcherProgress = ref(null)
const launcherInstalling = ref(false)
const launcherTargetVersion = ref(null)
const canCancelJob = computed(() => job.value?.action !== 'launcher-update' || canCancelLauncherUpdate(launcherProgress.value))
const now = ref(Date.now())
let statusTimer, elapsedTimer, mediaQuery, refreshPromise = null, refreshGeneration = 0, logPromise = null, logGeneration = 0, versionGeneration = 0, launcherGeneration = 0
const cleanup = []
const seenUpdateNotices = new Set()
const jobName = computed(() => actionLabels[job.value?.action] ?? '正在处理任务')
const elapsed = computed(() => {
  if (!job.value?.startedAt) return '0 秒'
  const seconds = Math.max(0, Math.floor((now.value - new Date(job.value.startedAt).getTime()) / 1000))
  return seconds >= 60 ? `${Math.floor(seconds / 60)} 分 ${seconds % 60} 秒` : `${seconds} 秒`
})
const visibleLogs = computed(() => {
  const lines = selectedLog.value === '__launcher'
    ? activity.value.map(entry => ({ level: entry.level?.toLowerCase() ?? 'info', time: formatTime(entry.timestamp), message: entry.message }))
    : serviceLogs.value.split(/\r?\n/).filter(Boolean).map(message => ({ level: /\b(error|fatal|exception)\b/i.test(message) ? 'error' : /\bwarn(ing)?\b/i.test(message) ? 'warning' : 'info', time: '', message }))
  return lines.filter(line => (levelFilter.value === 'all' || (levelFilter.value === 'error' ? ['error', 'fatal'].includes(line.level) : levelFilter.value === 'warning' ? ['warn', 'warning'].includes(line.level) : line.level === levelFilter.value)) && (!logSearch.value || line.message.toLowerCase().includes(logSearch.value.toLowerCase())))
})
const recentActivity = computed(() => activity.value.slice(-4).reverse())

function addLog(entry) { activity.value = [...activity.value.slice(-999), entry] }
function running(service) { return ['healthy', 'running', 'starting', 'unhealthy'].includes(service.state) }
function stateClass(state) { return ['healthy', 'running', 'starting', 'stopped', 'unhealthy', 'conflict', 'unknown', 'missing'].includes(state) ? state : 'unknown' }
function applyTheme() {
  const dark = preferences.value.theme === 'dark' || (preferences.value.theme === 'system' && mediaQuery?.matches)
  document.documentElement.classList.toggle('dark', !!dark)
}
function refresh(quiet = false) {
  if (refreshPromise) return refreshPromise
  refreshing.value = true
  const generation = ++refreshGeneration
  const mode = preferences.value.mode
  const projectRoot = preferences.value.projectRoot
  refreshPromise = (async () => {
    try {
      const result = await bridge.request('snapshot', { mode })
      if (generation === refreshGeneration && mode === preferences.value.mode && projectRoot === preferences.value.projectRoot) { snapshot.value = result; refreshError.value = '' }
    } catch (error) {
      if (generation !== refreshGeneration) return
      refreshError.value = readableError(error)
      if (!quiet) addLog({ timestamp: new Date().toISOString(), level: 'error', message: `状态检查失败：${refreshError.value}` })
    } finally { refreshing.value = false; refreshPromise = null }
  })()
  return refreshPromise
}
async function refreshContext() {
  refreshGeneration++
  snapshot.value = null
  refreshError.value = ''
  if (refreshPromise) await refreshPromise
  await refresh()
}
async function bootstrap() {
  startupError.value = ''
  loading.value = true
  try {
    const result = await bridge.request('bootstrap')
    preferences.value = { ...preferences.value, ...result.preferences }
    display.value = result.display ?? display.value
    version.value = result.version ?? version.value
    demo.value = !bridge.native || result.demo === true
    applyTheme()
    if (typeof result.updateNotice === 'string' && result.updateNotice.trim() && !seenUpdateNotices.has(result.updateNotice)) {
      seenUpdateNotices.add(result.updateNotice)
      ElMessage.warning({ message: result.updateNotice, duration: 10000 })
      addLog({ timestamp: new Date().toISOString(), level: 'warning', message: result.updateNotice })
    }
    addLog({ timestamp: new Date().toISOString(), level: 'info', message: demo.value ? '界面预览已就绪 · 所有服务与日志均为合成数据' : '启动器已连接，正在检查项目服务。' })
    await refresh()
  } catch (error) { startupError.value = readableError(error) }
  finally { loading.value = false }
}
async function runAction(action, service) {
  if (busy.value || loading.value || preferencesBusy.value) return
  localBusy.value = true
  operationError.value = ''
  job.value = { running: true, action, service, startedAt: new Date().toISOString() }
  let timedOut = false
  try {
    await bridge.request('action', { action, mode: preferences.value.mode, ...(service ? { service } : {}) })
    if (action === 'build') sourceNeedsBuild.value = false
    ElMessage.success(demo.value ? '演示操作已更新合成状态' : `${actionLabels[action] ?? '任务'}已完成`)
  } catch (error) {
    timedOut = error?.code === 'timeout'
    operationError.value = readableError(error)
    addLog({ timestamp: new Date().toISOString(), level: 'error', message: operationError.value })
  } finally {
    localBusy.value = false
    if (job.value && !timedOut) job.value = { ...job.value, running: false }
    cancelPending.value = false
    await refresh(true)
    if (page.value === 'logs' && selectedLog.value !== '__launcher') await loadLogs()
  }
}
async function cancelJob() {
  if (cancelPending.value || !canCancelJob.value) return
  cancelPending.value = true
  try { await bridge.request('cancel'); ElMessage.info(job.value?.action === 'launcher-update' ? '已请求取消启动器下载，请等待任务结束。' : job.value?.action === 'update-source' ? '已请求取消更新，请查看任务日志并重新检查源码状态。' : '已请求取消。已启动的服务会保留，请查看最新状态。') }
  catch (error) { operationError.value = readableError(error); cancelPending.value = false }
}
async function savePreferences(patch) {
  if (preferencesBusy.value || busy.value) return
  preferencesBusy.value = true
  try { preferences.value = { ...preferences.value, ...await bridge.request('preferences', patch) }; applyTheme() }
  catch (error) { ElMessage.error(readableError(error)) }
  finally { preferencesBusy.value = false }
}
async function changeMode(mode) {
  if (mode === preferences.value.mode) return
  await savePreferences({ mode })
  selectedLog.value = '__launcher'
  await refreshContext()
}
async function chooseProject() {
  if (busy.value || preferencesBusy.value) return
  preferencesBusy.value = true
  try {
    const result = await bridge.request('select-project')
    if (result) {
      preferences.value = { ...preferences.value, ...result }
      selectedLog.value = '__launcher'
      serviceLogs.value = ''
      await refreshContext()
      if (demo.value) ElMessage.info('演示模式使用合成项目目录；EXE 中会打开系统文件夹选择器。')
    }
  } catch (error) { ElMessage.error(readableError(error)) }
  finally { preferencesBusy.value = false }
}
async function openTarget(target) {
  try {
    await bridge.request('open', { target })
    if (demo.value) ElMessage.info(`界面预览：EXE 中将${{ workspace: '打开 LearnHub 工作台', project: '打开项目文件夹', logs: '打开日志文件夹', docker: '打开 Docker Desktop' }[target]}。`)
  } catch (error) { ElMessage.error(readableError(error)) }
}
async function exportDiagnostics() {
  try {
    const result = await bridge.request('export-diagnostics')
    if (result) ElMessage.success(demo.value ? '界面预览：EXE 中将打开保存对话框并导出诊断报告。' : '诊断报告已导出')
  } catch (error) { ElMessage.error(readableError(error)) }
}
async function checkSourceVersion(allowBusy = false) {
  if (versionChecking.value || (!allowBusy && (busy.value || preferencesBusy.value || loading.value))) return
  versionChecking.value = true
  versionError.value = ''
  const projectRoot = preferences.value.projectRoot
  const generation = ++versionGeneration
  try {
    const result = await bridge.request('check-updates', {}, { timeoutMs: 100000 })
    if (generation === versionGeneration && projectRoot === preferences.value.projectRoot) versionInfo.value = result
  } catch (error) {
    if (generation === versionGeneration) versionError.value = readableError(error)
  } finally { versionChecking.value = false }
}
async function checkLauncherVersion(allowBusy = false) {
  if (launcherChecking.value || (!allowBusy && (busy.value || preferencesBusy.value || loading.value))) return
  launcherChecking.value = true
  launcherError.value = ''
  const generation = ++launcherGeneration
  try {
    const result = await bridge.request('check-launcher-update', {}, { timeoutMs: 100000 })
    if (generation === launcherGeneration) launcherInfo.value = result
  } catch (error) {
    if (generation === launcherGeneration) launcherError.value = readableError(error)
  } finally { launcherChecking.value = false }
}
async function checkVersions() {
  await Promise.allSettled([checkSourceVersion(), checkLauncherVersion()])
}
async function installLauncherUpdate() {
  const target = launcherInfo.value
  if (busy.value || loading.value || preferencesBusy.value || launcherChecking.value || launcherError.value || !canInstallLauncherUpdate(target)) return
  localBusy.value = true
  launcherInstalling.value = true
  launcherError.value = ''
  launcherTargetVersion.value = target.latestVersion
  launcherProgress.value = normalizeLauncherProgress({ phase: 'downloading', downloadedBytes: 0, totalBytes: target.size || 0, percent: 0, version: target.latestVersion })
  job.value = { running: true, action: 'launcher-update', startedAt: new Date().toISOString() }
  let timedOut = false
  let awaitingRestart = false
  try {
    await bridge.request('install-launcher-update', { version: target.latestVersion, sha256: target.sha256 })
    if (demo.value) {
      await checkLauncherVersion(true)
      version.value = launcherInfo.value?.currentVersion || version.value
      ElMessage.success('演示自更新完成：未关闭浏览器，未更改真实服务。')
    } else {
      awaitingRestart = true
      launcherProgress.value = normalizeLauncherProgress({ ...launcherProgress.value, phase: 'restarting', percent: 100 })
      job.value = { ...job.value, running: true, action: 'launcher-update' }
    }
  } catch (error) {
    timedOut = error?.code === 'timeout'
    launcherError.value = readableError(error)
    addLog({ timestamp: new Date().toISOString(), level: 'error', message: `启动器更新：${launcherError.value}` })
    if (page.value !== 'updates') ElMessage.error(launcherError.value)
  } finally {
    if (!awaitingRestart) localBusy.value = false
    if (!awaitingRestart && (!timedOut || !job.value?.running)) {
      launcherInstalling.value = false
      launcherProgress.value = null
      launcherTargetVersion.value = null
      if (job.value?.action === 'launcher-update') job.value = { ...job.value, running: false }
    }
    cancelPending.value = false
  }
}
async function updateSource(target) {
  if (busy.value || loading.value || preferencesBusy.value || versionChecking.value || versionError.value || target !== versionInfo.value || !canUpdateSource(target)) return
  localBusy.value = true
  operationError.value = ''
  job.value = { running: true, action: 'update-source', startedAt: new Date().toISOString() }
  let timedOut = false
  try {
    const result = await bridge.request('update-source', { version: target.latestVersion, commit: target.latestCommit })
    versionInfo.value = result
    sourceNeedsBuild.value = true
    ElMessage.success(demo.value ? '演示源码已更新；运行服务保持不变' : '项目源码已更新，可在准备好后构建并应用')
    await checkSourceVersion(true)
  } catch (error) {
    timedOut = error?.code === 'timeout'
    operationError.value = readableError(error)
    addLog({ timestamp: new Date().toISOString(), level: 'error', message: operationError.value })
    if (!timedOut) await checkSourceVersion(true)
  } finally {
    localBusy.value = false
    if (job.value && !timedOut) job.value = { ...job.value, running: false }
    cancelPending.value = false
    await refresh(true)
  }
}
async function openVersionPage(launcher = false) {
  const url = launcher ? launcherInfo.value?.releaseUrl : versionInfo.value?.releaseUrl
  if (!url) return
  try {
    await bridge.request(launcher ? 'open-launcher-release' : 'open-release', {})
    if (demo.value) ElMessage.info('界面预览：EXE 中将打开官方发布页面。')
  } catch (error) { ElMessage.error(readableError(error)) }
}
function loadLogs() {
  if (selectedLog.value === '__launcher') return
  if (logPromise) return logPromise
  const service = selectedLog.value
  const mode = preferences.value.mode
  const projectRoot = preferences.value.projectRoot
  const generation = ++logGeneration
  logLoading.value = true
  logError.value = ''
  logPromise = (async () => {
    try {
      const result = await bridge.request('logs', { mode, service, tail: 500 })
      if (generation === logGeneration && service === selectedLog.value && mode === preferences.value.mode && projectRoot === preferences.value.projectRoot) serviceLogs.value = result ?? ''
    } catch (error) { if (generation === logGeneration) logError.value = readableError(error) }
    finally { logLoading.value = false; logPromise = null }
  })()
  return logPromise
}
function viewServiceLogs(service) { selectedLog.value = service.id; page.value = 'logs' }
watch(selectedLog, async () => { logGeneration++; serviceLogs.value = ''; logError.value = ''; if (logPromise) await logPromise; loadLogs() })
watch(page, () => { if (page.value === 'logs') loadLogs() })
watch(visibleLogs, async () => { if (autoScroll.value) { await nextTick(); if (logPane.value) logPane.value.scrollTop = logPane.value.scrollHeight } })
watch(() => preferences.value.projectRoot, () => { versionGeneration++; versionInfo.value = null; versionError.value = ''; sourceNeedsBuild.value = false })

onMounted(() => {
  mediaQuery = window.matchMedia('(prefers-color-scheme: dark)')
  mediaQuery.addEventListener('change', applyTheme)
  applyTheme()
  cleanup.push(bridge.on('log', addLog), bridge.on('job', value => {
    job.value = value
    if (value?.action === 'launcher-update' && !value.running && launcherInstalling.value && !localBusy.value) { launcherInstalling.value = false; launcherProgress.value = null; launcherTargetVersion.value = null }
  }), bridge.on('launcher-update-progress', event => {
    if (!launcherInstalling.value && job.value?.action !== 'launcher-update') return
    const progress = normalizeLauncherProgress(event)
    if (progress && (!progress.version || !launcherTargetVersion.value || progress.version === launcherTargetVersion.value)) launcherProgress.value = progress
  }))
  bootstrap()
  statusTimer = setInterval(() => {
    if (!loading.value && !preferencesBusy.value && preferences.value.autoRefresh) {
      refresh(true)
      if (page.value === 'logs' && selectedLog.value !== '__launcher') loadLogs()
    }
  }, 5000)
  elapsedTimer = setInterval(() => { now.value = Date.now() }, 1000)
})
onUnmounted(() => { cleanup.forEach(fn => fn()); clearInterval(statusTimer); clearInterval(elapsedTimer); mediaQuery?.removeEventListener('change', applyTheme); bridge.dispose() })
</script>

<template>
  <div class="launcher-shell">
    <aside class="sidebar" aria-label="主导航">
      <a class="brand" href="#" aria-label="LearnHub 启动器首页" @click.prevent="page = 'overview'">
        <svg class="brand-symbol" viewBox="0 0 36 36" fill="none" aria-hidden="true"><rect width="36" height="36" rx="10" fill="currentColor"/><path d="M10 10.5h6a4 4 0 0 1 4 4V27a5 5 0 0 0-4-2h-6V10.5Z" fill="white" fill-opacity=".96"/><path d="M26 10.5h-3a4 4 0 0 0-4 4V27a5 5 0 0 1 4-2h3V10.5Z" fill="white" fill-opacity=".55"/></svg>
        <span class="brand-copy"><strong>LearnHub</strong><span>本地服务启动器</span></span>
      </a>
      <span class="nav-label">工作空间</span>
      <nav>
        <button v-for="item in navItems" :key="item.id" :class="['nav-item', { active: page === item.id }]" :aria-label="item.label" :aria-current="page === item.id ? 'page' : undefined" @click="page = item.id">
          <el-icon><component :is="item.icon" /></el-icon><span>{{ item.label }}</span><span v-if="item.id === 'diagnostics' && errorsCount" class="nav-count">{{ errorsCount }}</span>
        </button>
      </nav>
      <div class="sidebar-bottom">
        <div class="project-mini"><span class="mini-project-icon"><el-icon><FolderOpened /></el-icon></span><span><strong>LearnHub 项目</strong><small>{{ preferences.mode === 'docker' ? 'Docker 容器模式' : '本地进程模式' }}</small></span></div>
        <div class="sidebar-foot"><span>启动器 v{{ version }}</span><el-icon><Monitor /></el-icon></div>
      </div>
    </aside>

    <div class="main-shell">
      <header class="topbar">
        <div class="breadcrumb"><span>LearnHub</span><el-icon><ArrowRight /></el-icon><strong>{{ currentNav.label }}</strong></div>
        <div class="topbar-actions"><span class="mode-label" id="mode-label">运行模式</span><el-select :model-value="preferences.mode" class="mode-select" aria-label="运行模式" :disabled="busy || loading || preferencesBusy" @change="changeMode"><el-option label="Docker 容器" value="docker" /><el-option label="本地进程" value="local" /></el-select><el-button type="primary" :icon="Open" :disabled="loading || !snapshot || !serviceList.some(s => s.id === 'frontend' && s.address && ['healthy','running'].includes(s.state))" @click="openTarget('workspace')">打开工作台</el-button></div>
      </header>
      <div v-if="demo" class="demo-banner" role="status"><el-icon><InfoFilled /></el-icon><strong>界面预览 · 合成数据</strong><span>此界面不会连接或更改真实服务。</span></div>
      <main id="main-content">
        <div v-if="startupError" class="error-banner" role="alert"><el-icon><CircleClose /></el-icon><span><strong>无法连接启动器</strong>{{ startupError }}</span><el-button @click="bootstrap">重新连接</el-button></div>
        <div v-if="refreshError" class="error-banner" role="alert"><el-icon><Warning /></el-icon><span><strong>状态未能更新</strong>{{ refreshError }}<small v-if="snapshot">下方保留 {{ formatTime(snapshot.checkedAt) }} 的检查结果。</small></span><el-button :loading="refreshing" @click="refresh()">重新检测</el-button></div>
        <div v-if="operationError" class="error-banner" role="alert"><el-icon><CircleClose /></el-icon><span><strong>任务未完成</strong>{{ operationError }}</span><el-button text :icon="Close" aria-label="关闭任务错误提示" @click="operationError = ''" /></div>

        <div v-if="busy" class="job-banner" role="status" aria-live="polite"><div class="job-line"><span class="job-spinner"></span><div><strong>{{ jobName }}<template v-if="job?.service"> · {{ serviceList.find(s => s.id === job.service)?.name ?? job.service }}</template></strong><span>{{ job?.action === 'launcher-update' ? launcherPhaseLabel(launcherProgress) : '正在处理，请稍候' }} · 已用时 {{ elapsed }}</span></div><el-button :loading="cancelPending" :disabled="cancelPending || !canCancelJob" @click="cancelJob">{{ cancelPending ? '等待取消' : job?.action === 'launcher-update' && !canCancelJob ? launcherProgress?.phase === 'restarting' ? '即将重启' : '校验中' : '取消任务' }}</el-button></div><div :class="['job-progress', { determinate: job?.action === 'launcher-update' }]" :style="job?.action === 'launcher-update' ? { width: `${launcherProgress?.percent ?? 0}%` } : {}"></div></div>

        <template v-if="page === 'overview'">
          <div class="page-heading"><div><div class="eyebrow">LOCAL WORKSPACE</div><h1>运行概况<span class="heading-dot"></span></h1><p>让服务就绪，然后专注于你的下一次学习。</p></div><el-button :icon="Refresh" :loading="refreshing" :disabled="loading" @click="refresh()">检测状态</el-button></div>
          <section :class="['health-strip', stateClass(health.state)]" aria-label="总体健康状态" :aria-busy="loading"><span class="health-symbol"><el-icon :size="24"><Check v-if="health.state === 'healthy'" /><VideoPause v-else-if="health.state === 'stopped'" /><Warning v-else-if="health.state === 'unhealthy'" /><Connection v-else /></el-icon></span><div class="health-copy"><h2>{{ loading ? '正在检查运行状态' : health.title }}</h2><p>{{ health.detail }}</p></div><div class="health-ratio"><strong>{{ health.healthy }}<span> / {{ health.total }}</span></strong><small>服务健康</small></div><div class="health-updated"><span class="live-dot" :class="{ paused: !preferences.autoRefresh }"></span><span>{{ preferences.autoRefresh ? '每 5 秒自动检测' : '自动检测已关闭' }}<small>{{ snapshot ? `上次检查 ${formatTime(snapshot.checkedAt)}` : '等待读取状态' }}</small></span></div></section>

          <section class="panel services-panel" aria-labelledby="services-title">
            <div class="panel-heading"><div><h2 id="services-title">项目服务 <span class="count">{{ serviceList.length }}</span></h2><p>{{ preferences.mode === 'docker' ? '容器与应用健康状态统一检查' : '检查本地前端、后端进程与数据库连接' }}</p></div><div class="service-controls"><el-button type="primary" :icon="VideoPlay" :disabled="busy || loading || !snapshot || preferencesBusy" @click="runAction('start-all')">一键启动</el-button><el-button :icon="VideoPause" :disabled="busy || loading || !snapshot || preferencesBusy" @click="runAction('stop-all')">全部停止</el-button><el-dropdown trigger="click" :disabled="busy || loading || !snapshot || preferences.mode !== 'docker'" @command="runAction('build')"><el-button :icon="MoreFilled" aria-label="更多服务操作" :disabled="busy || loading || !snapshot || preferences.mode !== 'docker'" /><template #dropdown><el-dropdown-menu><el-dropdown-item command="build" :icon="Box">构建并更新前后端</el-dropdown-item></el-dropdown-menu></template></el-dropdown></div></div>
            <div v-if="loading" class="loading-area"><el-skeleton :rows="6" animated /></div>
            <table v-else-if="serviceList.length" class="service-table"><caption class="sr-only">项目服务运行状态与控制</caption><thead><tr><th>服务</th><th>状态与端口</th><th>{{ preferences.mode === 'docker' ? '容器 / 响应' : '进程 / 响应' }}</th><th class="actions-heading">操作</th></tr></thead><tbody><tr v-for="service in serviceList" :key="service.id"><td><div class="service-identity"><span class="service-icon"><el-icon :size="21"><Monitor v-if="service.id === 'frontend'" /><Connection v-else-if="service.id === 'backend'" /><Box v-else /></el-icon></span><div><strong>{{ service.name }}</strong><span class="service-description">{{ service.detail || '等待服务信息' }}</span></div></div></td><td><span :class="['status-badge', stateClass(service.state)]"><span></span>{{ stateLabels[service.state] ?? '未确认' }}</span><span class="secondary service-port">{{ service.port ? `端口 ${service.port}` : '端口未提供' }}</span></td><td><div class="runtime-name" :title="service.containerName ?? ''">{{ service.containerName || (service.processId ? `PID ${service.processId}` : service.kind === 'external' ? '外部依赖' : '尚未运行') }}</div><span class="secondary numeric">{{ service.responseMs == null ? '响应未测得' : `${service.responseMs} ms` }}<template v-if="service.uptime"> · {{ service.uptime }}</template></span></td><td><div class="row-actions"><el-button size="small" :icon="running(service) ? VideoPause : VideoPlay" :aria-label="`${running(service) ? '停止' : '启动'}${service.name}`" :disabled="busy || preferencesBusy || (running(service) ? !service.canStop : !service.canStart)" @click="runAction(running(service) ? 'stop' : 'start', service.id)">{{ running(service) ? '停止' : '启动' }}</el-button><el-button size="small" :icon="Refresh" :aria-label="`重启${service.name}`" :disabled="busy || preferencesBusy || !service.canRestart" @click="runAction('restart', service.id)">重启</el-button><el-button size="small" text :icon="Document" :aria-label="`查看${service.name}日志`" @click="viewServiceLogs(service)">日志</el-button></div></td></tr></tbody></table>
            <div v-else class="empty-area"><el-icon :size="32"><Search /></el-icon><h3>尚未发现项目服务</h3><p>选择正确的项目目录，再检测运行状态。</p><el-button :icon="FolderOpened" :disabled="busy || preferencesBusy" @click="chooseProject">选择项目目录</el-button></div>
            <div class="panel-note"><el-icon><InfoFilled /></el-icon><span>停止服务会保留容器和数据卷。更新构建仅影响前端与后端。</span></div>
          </section>

          <div class="overview-bottom"><section class="activity-panel" aria-labelledby="activity-title"><div class="section-heading"><h2 id="activity-title">最近活动</h2><button class="text-link" @click="page = 'logs'">查看全部<el-icon><ArrowRight /></el-icon></button></div><div v-if="!recentActivity.length" class="secondary">暂无运行活动</div><ul v-else class="activity-list"><li v-for="(entry, index) in recentActivity" :key="index"><span :class="['activity-dot', entry.level]"></span><span class="activity-message">{{ entry.message }}</span><time>{{ formatTime(entry.timestamp) }}</time></li></ul></section><section class="environment-panel" aria-labelledby="environment-title"><div class="section-heading"><h2 id="environment-title">运行环境</h2><span :class="['environment-state', snapshot?.docker?.ready ? 'ready' : '']">{{ snapshot ? (preferences.mode === 'docker' ? snapshot.docker?.ready ? '引擎就绪' : '等待就绪' : '本地模式') : '等待检查' }}</span></div><div class="environment-detail"><el-icon :size="28"><Box /></el-icon><div><strong>{{ preferences.mode === 'docker' ? 'Docker Engine' : '本地运行环境' }}</strong><span>{{ preferences.mode === 'docker' ? snapshot?.docker?.version || '版本待检测' : '由项目服务状态确认运行情况' }}</span></div></div><button class="text-link" @click="page = 'diagnostics'">检查环境与依赖<el-icon><ArrowRight /></el-icon></button></section></div>
        </template>

        <template v-else-if="page === 'logs'">
          <div class="page-heading"><div><div class="eyebrow">RUNTIME INSIGHTS</div><h1>运行日志</h1><p>查看启动任务、服务输出与异常线索。</p></div><el-button :icon="FolderOpened" @click="openTarget('logs')">打开日志目录</el-button></div>
          <section class="panel log-panel" aria-label="日志查看器"><div class="log-toolbar"><div class="log-filter"><label for="log-source">日志来源</label><el-select id="log-source" v-model="selectedLog" class="log-source" aria-label="日志来源"><el-option label="启动器活动" value="__launcher" /><el-option v-for="service in serviceList" :key="service.id" :label="service.name" :value="service.id" /></el-select><el-select v-model="levelFilter" aria-label="日志级别" class="log-level"><el-option label="所有级别" value="all" /><el-option label="信息" value="info" /><el-option label="警告" value="warning" /><el-option label="错误" value="error" /></el-select></div><el-input v-model="logSearch" class="log-search" :prefix-icon="Search" placeholder="搜索日志内容" aria-label="搜索日志内容" clearable /></div><div class="log-subtoolbar"><span><span class="live-dot"></span>{{ selectedLog === '__launcher' ? '实时启动器活动 · 最多 1,000 条' : '服务最近 500 行 · 跟随自动检测设置' }}</span><div><el-checkbox v-model="autoScroll">自动滚动</el-checkbox><el-button v-if="selectedLog !== '__launcher'" size="small" :icon="Refresh" :loading="logLoading" @click="loadLogs">刷新</el-button></div></div><div v-if="logError" class="inline-error" role="alert">{{ logError }}</div><div ref="logPane" class="log-content" tabindex="0" aria-label="日志内容" :aria-busy="logLoading"><div v-if="logLoading && !serviceLogs" class="log-empty">正在读取服务日志…</div><div v-else-if="!visibleLogs.length" class="log-empty">{{ logSearch || levelFilter !== 'all' ? '没有符合筛选条件的日志' : '暂无日志，执行服务操作后会显示在这里' }}</div><div v-for="(line, index) in visibleLogs" :key="index" :class="['log-line', line.level]"><span class="log-line-number">{{ index + 1 }}</span><time v-if="line.time">{{ line.time }}</time><span v-if="selectedLog === '__launcher'" class="log-line-level">{{ line.level.toUpperCase() }}</span><span class="log-line-message">{{ line.message }}</span></div></div><div class="log-footer"><span>{{ visibleLogs.length }} 条匹配记录</span><span>输出由启动器脱敏后展示</span></div></section>
        </template>

        <template v-else-if="page === 'diagnostics'">
          <div class="page-heading"><div><div class="eyebrow">ENVIRONMENT CHECK</div><h1>环境诊断</h1><p>确认依赖、端口与服务连接，快速找到启动障碍。</p></div><div class="heading-actions"><el-button :icon="Download" :disabled="!snapshot" @click="exportDiagnostics">导出报告</el-button><el-button type="primary" :icon="Search" :disabled="busy || loading || preferencesBusy" @click="runAction('diagnose')">开始诊断</el-button></div></div>
          <section class="panel"><div class="panel-heading"><div><h2>检查结果</h2><p>{{ snapshot ? `检查于 ${formatTime(snapshot.checkedAt)}` : '等待读取环境状态' }}</p></div><span :class="['diagnostic-summary', { attention: errorsCount }]">{{ snapshot ? errorsCount ? `${errorsCount} 项需要关注` : '未发现警告' : '尚未检查' }}</span></div><ul v-if="snapshot?.diagnostics?.length" class="diagnostic-list"><li v-for="(item, index) in snapshot.diagnostics" :key="index" :class="item.level"><span class="diagnostic-icon"><el-icon :size="20"><Check v-if="item.level === 'success'" /><Warning v-else-if="['error','warning','danger'].includes(item.level)" /><InfoFilled v-else /></el-icon></span><div><h3>{{ item.title }}</h3><p>{{ item.detail }}</p></div><span class="diagnostic-level">{{ { success: '已通过', info: '提示', warning: '需关注', error: '异常', danger: '异常' }[item.level] ?? '提示' }}</span></li></ul><div v-else class="empty-area"><el-icon :size="32"><Connection /></el-icon><h3>暂无诊断结果</h3><p>点击“开始诊断”，检查当前项目的运行环境。</p></div></section>
          <section v-if="preferences.mode === 'docker'" class="panel docker-panel"><div><span class="service-icon"><el-icon :size="24"><Box /></el-icon></span><div><h2>Docker Desktop</h2><p>{{ snapshot?.docker?.error || (snapshot?.docker?.ready ? `当前上下文：${snapshot.docker.context || '未提供'}` : '启动容器服务前，需要 Docker 引擎处于可用状态。') }}</p></div></div><div class="heading-actions"><el-button :icon="Open" @click="openTarget('docker')">打开 Docker</el-button><el-button v-if="!snapshot?.docker?.ready" :icon="VideoPlay" :disabled="busy || loading" @click="runAction('docker-start')">启动引擎</el-button></div></section>
          <div class="quiet-note"><el-icon><InfoFilled /></el-icon><span>诊断报告包含服务状态和启动器活动，不包含环境密钥或笔记内容。</span></div>
        </template>

        <template v-else-if="page === 'updates'">
          <VersionUpdates :info="versionInfo" :checking="versionChecking" :error="versionError" :busy="busy" :disabled="loading || preferencesBusy" :launcher-version="version" :mode="preferences.mode" :needs-build="sourceNeedsBuild" :launcher-info="launcherInfo" :launcher-checking="launcherChecking" :launcher-error="launcherError" :launcher-installing="launcherInstalling" :launcher-progress="launcherProgress" :demo="demo" :cancel-pending="cancelPending" @check="checkVersions()" @check-source="checkSourceVersion()" @update="updateSource" @release="openVersionPage()" @check-launcher="checkLauncherVersion()" @install-launcher="installLauncherUpdate" @launcher-release="openVersionPage(true)" @cancel="cancelJob" @build="runAction('build')" />
        </template>

        <template v-else-if="page === 'settings'">
          <div class="page-heading"><div><div class="eyebrow">YOUR PREFERENCES</div><h1>启动器设置</h1><p>按你的工作环境，调整项目与显示偏好。</p></div><span class="autosave-note"><el-icon><Check /></el-icon>更改即时保存</span></div>
          <section class="panel settings-panel"><div class="panel-heading"><h2>项目与检测</h2></div><div class="setting-row project-setting"><div><label>项目目录</label><p>前端、后端与容器配置所在的 LearnHub 根目录。</p><code class="project-path">{{ preferences.projectRoot || '未选择项目目录' }}</code></div><div class="setting-buttons"><el-button :icon="FolderOpened" :disabled="busy || preferencesBusy" @click="chooseProject">选择目录</el-button><el-button :icon="Open" :disabled="!preferences.projectRoot" @click="openTarget('project')">打开</el-button></div></div><div class="setting-row"><div><label id="refresh-setting-label">自动检测运行状态</label><p>每 5 秒检查服务健康状态，并更新当前服务日志。</p></div><el-switch :model-value="preferences.autoRefresh" aria-label="自动检测运行状态" :disabled="busy || preferencesBusy" @change="value => savePreferences({ autoRefresh: value })" /></div></section>
          <section class="panel settings-panel"><div class="panel-heading"><h2>外观与显示</h2></div><div class="setting-row"><div><label id="theme-setting-label">界面主题</label><p>选择主题，或自动跟随系统的深浅色设置。</p></div><el-select class="theme-select" :model-value="preferences.theme" aria-label="界面主题" :disabled="busy || preferencesBusy" @change="value => savePreferences({ theme: value })"><el-option label="跟随系统" value="system" /><el-option label="浅色" value="light" /><el-option label="深色" value="dark" /></el-select></div><div class="setting-row"><div><label>高分辨率显示</label><p>矢量界面自动跟随窗口尺寸与系统缩放，跨屏移动时同步调整。</p></div><span class="display-info"><el-icon><Monitor /></el-icon>{{ display.scale ? `${Math.round(display.scale * 100)}% 系统缩放` : '自动适配' }}</span></div></section>
          <div class="settings-about"><span class="brand-small">LearnHub <span>启动器</span></span><button class="text-link" @click="page = 'updates'">v{{ version }} · 检查版本更新<el-icon><ArrowRight /></el-icon></button></div>
        </template>
        <footer class="main-footer"><span><span class="live-dot" :class="{ paused: !snapshot || !!refreshError }"></span>{{ demo ? '演示工作空间' : snapshot ? '已连接本地工作空间' : '等待连接本地工作空间' }}</span><span>{{ preferences.mode === 'docker' ? 'Docker 容器' : '本地进程' }}<span class="footer-divider">/</span>LearnHub Launcher</span></footer>
      </main>
    </div>
  </div>
</template>
