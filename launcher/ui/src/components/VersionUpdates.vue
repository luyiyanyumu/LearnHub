<script setup>
import { computed } from 'vue'
import { ElMessageBox } from 'element-plus'
import { ArrowRight, Box, Check, Document, InfoFilled, Open, Refresh, Upload, Warning } from '@element-plus/icons-vue'
import { canUpdateSource, formatTime, versionSummary } from '../model.js'
import LauncherUpdatePanel from './LauncherUpdatePanel.vue'

const props = defineProps({
  info: { type: Object, default: null }, checking: Boolean, error: { type: String, default: '' },
  busy: Boolean, disabled: Boolean, launcherVersion: { type: String, default: '1.1.0' },
  mode: { type: String, default: 'docker' }, needsBuild: Boolean,
  launcherInfo: { type: Object, default: null }, launcherChecking: Boolean, launcherError: { type: String, default: '' },
  launcherInstalling: Boolean, launcherProgress: { type: Object, default: null }, demo: Boolean, cancelPending: Boolean,
})
const emit = defineEmits(['check', 'check-source', 'update', 'release', 'check-launcher', 'install-launcher', 'launcher-release', 'cancel', 'build'])
const summary = computed(() => versionSummary(props.info))
const updateAllowed = computed(() => canUpdateSource(props.info) && !props.error && !props.checking && !props.busy && !props.disabled)
const commit = value => value ? value.slice(0, 10) : '未提供'
const releaseDate = computed(() => {
  if (!props.info?.publishedAt) return '发布时间未提供'
  const value = new Date(props.info.publishedAt)
  return Number.isNaN(+value) ? props.info.publishedAt : value.toLocaleDateString('zh-CN', { year: 'numeric', month: 'long', day: 'numeric' })
})
async function confirmUpdate() {
  if (!updateAllowed.value) return
  const target = props.info
  try {
    await ElMessageBox.confirm(`将项目源码更新到 ${target.latestVersion}。本次只更新源码，当前运行的服务会继续运行。之后应用新版本前，请先备份数据，再手动构建并应用前后端。`, '更新项目源码', {
      confirmButtonText: '确认更新源码', cancelButtonText: '暂不更新', type: 'info',
      closeOnClickModal: false, confirmButtonClass: 'source-update-confirm', autofocus: false,
    })
    if (updateAllowed.value && target === props.info) emit('update', target)
  } catch { /* Closing the Element Plus confirmation leaves the project unchanged. */ }
}
</script>

<template>
  <div class="page-heading"><div><div class="eyebrow">VERSION & RELEASES</div><h1>版本更新</h1><p>分别管理启动器与项目源码，按需要应用更新。</p></div><el-button type="primary" :icon="Refresh" :loading="checking || launcherChecking" :disabled="busy || disabled" @click="emit('check')">{{ checking || launcherChecking ? '正在检查' : '检查更新' }}</el-button></div>
  <LauncherUpdatePanel :info="launcherInfo" :checking="launcherChecking" :error="launcherError" :current-version="launcherVersion" :installing="launcherInstalling" :progress="launcherProgress" :busy="busy" :disabled="disabled" :demo="demo" :cancel-pending="cancelPending" @check="emit('check-launcher')" @install="emit('install-launcher')" @release="emit('launcher-release')" @cancel="emit('cancel')" />
  <div v-if="error" class="error-banner" role="alert"><el-icon><Warning /></el-icon><span><strong>项目源码检查未完成</strong>{{ error }}<small v-if="info">下方保留 {{ formatTime(info.checkedAt) }} 的源码结果，启动器更新可独立操作。</small></span><el-button :loading="checking" :disabled="busy || disabled" @click="emit('check-source')">重试源码检查</el-button></div>

  <section class="panel version-panel" aria-labelledby="source-version-title" :aria-busy="checking">
    <div class="panel-heading"><div><h2 id="source-version-title"><el-icon><Box /></el-icon>项目源码</h2><p>源码版本与运行中的服务版本可能不同</p></div><span :class="['version-summary-badge', summary.kind]"><el-icon><Check v-if="summary.kind === 'current'" /><Upload v-else-if="summary.kind === 'available'" /><InfoFilled v-else /></el-icon>{{ info ? summary.title : '尚未检查' }}</span></div>
    <div v-if="!info" class="version-empty"><span class="version-empty-icon"><el-icon :size="27"><Refresh /></el-icon></span><div><h3>{{ checking ? '正在读取版本并查询官方发布' : summary.title }}</h3><p>{{ checking ? '请稍候，检查完成后会展示当前版本与更新说明。' : summary.detail }}</p><span>仅在你点击检查时联网，不会自动检查或更新源码。</span></div></div>
    <template v-else>
      <div class="version-comparison"><div><span class="version-overline">当前项目版本</span><strong>{{ info.currentVersion || '版本未识别' }}</strong><span class="version-caption">{{ info.sourceDescription || '源码描述未提供' }}</span></div><span class="version-arrow"><el-icon :size="20"><ArrowRight /></el-icon></span><div><span class="version-overline">官方最新稳定版</span><strong>{{ info.latestVersion || '待确认' }}</strong><span class="version-caption">{{ releaseDate }}</span></div></div>
      <dl class="source-metadata"><div><dt>源码分支</dt><dd>{{ info.branch || '未提供' }}</dd></div><div><dt>当前提交</dt><dd class="commit-value" :title="info.currentCommit || ''">{{ commit(info.currentCommit) }}</dd></div><div><dt>本地改动</dt><dd :class="{ 'dirty-value': info.dirty }"><span :class="['live-dot', { paused: !info.dirty }]"></span>{{ info.dirty ? `${info.changeCount ?? 0} 项未提交改动` : '工作目录干净' }}</dd></div><div><dt>官方仓库</dt><dd>{{ info.repository || '未提供' }}</dd></div></dl>
      <div :class="['version-outcome', summary.kind]" role="status"><el-icon :size="18"><Warning v-if="summary.kind === 'blocked'" /><Check v-else-if="summary.kind === 'current'" /><InfoFilled v-else /></el-icon><div><strong>{{ summary.title }}</strong><p>{{ summary.detail }}</p></div><el-button v-if="info.updateAvailable" type="primary" :icon="Upload" :disabled="!updateAllowed" @click="confirmUpdate">更新源码到 {{ info.latestVersion }}</el-button></div>
      <div v-if="needsBuild" class="source-build-notice" role="status"><el-icon :size="20"><Check /></el-icon><div><strong>源码已更新，当前服务继续运行</strong><p>{{ mode === 'docker' ? '准备好后，先备份数据，再构建并应用前后端以运行新版本。' : '本地模式请重新打包后端 JAR，并按需重启前后端服务，使新源码生效。' }}</p></div><el-button v-if="mode === 'docker'" type="primary" :icon="Box" :disabled="busy || disabled" @click="emit('build')">构建并应用</el-button></div>
      <div class="version-check-footer"><span>上次检查 {{ formatTime(info.checkedAt) }}</span><span>更新前会再次核验源码和本地改动</span></div>
    </template>
  </section>

  <section v-if="info" class="panel release-panel" aria-labelledby="release-title"><div class="panel-heading"><div><h2 id="release-title"><el-icon><Document /></el-icon>{{ info.releaseName || (info.latestVersion ? `${info.latestVersion} 发布说明` : '发布说明') }}</h2><p>官方发布说明 · 纯文本展示</p></div><el-button :icon="Open" :disabled="!info.releaseUrl" @click="emit('release')">官方发布页面</el-button></div><pre class="release-notes" tabindex="0" aria-label="发布说明内容">{{ info.releaseNotes || '本次发布暂无说明，可前往官方页面查看详细信息。' }}</pre></section>

  <div class="quiet-note"><el-icon><InfoFilled /></el-icon><span>源码更新与服务应用分开执行。工作目录有本地改动时，更新会暂停以保护你的工作。</span></div>
</template>
