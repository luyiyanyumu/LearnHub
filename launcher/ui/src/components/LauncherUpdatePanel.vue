<script setup>
import { computed } from 'vue'
import { ArrowRight, Check, Download, InfoFilled, Monitor, Open, Refresh, Warning } from '@element-plus/icons-vue'
import { canCancelLauncherUpdate, canInstallLauncherUpdate, formatBytes, launcherPhaseLabel, launcherUpdateSummary } from '../launcherUpdate.js'
import { formatTime } from '../model.js'

const props = defineProps({
  info: { type: Object, default: null }, checking: Boolean, error: { type: String, default: '' },
  currentVersion: { type: String, default: '1.1.0' }, installing: Boolean,
  progress: { type: Object, default: null }, busy: Boolean, disabled: Boolean, demo: Boolean, cancelPending: Boolean,
})
const emit = defineEmits(['check', 'install', 'release', 'cancel'])
const summary = computed(() => launcherUpdateSummary(props.info))
const installAllowed = computed(() => canInstallLauncherUpdate(props.info) && !props.error && !props.checking && !props.busy && !props.disabled)
const versionText = value => value ? `v${String(value).replace(/^v/, '')}` : '尚未提供'
const phases = [{ id: 'downloading', label: '下载更新包' }, { id: 'verifying', label: '校验文件' }, { id: 'restarting', label: '自动重开' }]
const phaseIndex = computed(() => phases.findIndex(phase => phase.id === props.progress?.phase))
</script>

<template>
  <section class="panel self-update-panel" aria-labelledby="launcher-version-title" :aria-busy="checking || installing">
    <div class="panel-heading"><div><h2 id="launcher-version-title"><el-icon><Monitor /></el-icon>启动器 EXE</h2><p>独立更新启动器，业务服务继续运行</p></div><span :class="['version-summary-badge', summary.kind]"><el-icon><Download v-if="summary.kind === 'available'" /><Check v-else-if="summary.kind === 'current'" /><InfoFilled v-else /></el-icon>{{ checking ? '正在检查' : !info ? '尚未检查' : summary.kind === 'available' ? '发现新版' : summary.kind === 'current' ? '无需更新' : '暂不可用' }}</span></div>
    <div v-if="error" class="launcher-inline-error" role="alert"><el-icon><Warning /></el-icon><div><strong>启动器更新未完成</strong><p>{{ error }}</p></div><el-button size="small" :icon="Refresh" :loading="checking" :disabled="busy || disabled" @click="emit('check')">重新检查</el-button></div>
    <div class="self-update-main"><div class="self-version-pair"><div><span>当前启动器</span><strong>{{ versionText(info?.currentVersion || currentVersion) }}</strong></div><el-icon class="self-version-arrow"><ArrowRight /></el-icon><div><span>可用最新版本</span><strong>{{ info?.latestVersion ? versionText(info.latestVersion) : checking ? '检查中' : info ? '暂无安装包' : '等待检查' }}</strong></div></div><div class="self-update-action"><el-button v-if="info?.state === 'available'" type="primary" :icon="Download" :disabled="!installAllowed" @click="emit('install')">一键更新并重启</el-button><el-button v-else :icon="Refresh" :loading="checking" :disabled="busy || disabled" @click="emit('check')">检查启动器更新</el-button><span>只重启启动器，不会停止前后端或容器。</span></div></div>
    <div v-if="installing" class="self-download-status" role="status" aria-live="polite"><div class="self-download-heading"><div><strong>{{ launcherPhaseLabel(progress) }}</strong><span v-if="progress?.phase === 'downloading'">{{ formatBytes(progress.downloadedBytes) }}<template v-if="progress.totalBytes"> / {{ formatBytes(progress.totalBytes) }}</template></span><span v-else>{{ progress?.phase === 'restarting' ? '即将关闭当前窗口，并自动打开新版启动器。' : '正在验证更新包，请稍候。' }}</span></div><el-button v-if="canCancelLauncherUpdate(progress)" size="small" :loading="cancelPending" :disabled="cancelPending" @click="emit('cancel')">取消下载</el-button><strong class="download-percent">{{ Math.round(progress?.percent ?? 0) }}%</strong></div><div class="self-download-track" role="progressbar" :aria-label="launcherPhaseLabel(progress)" :aria-valuenow="Math.round(progress?.percent ?? 0)" aria-valuemin="0" aria-valuemax="100"><span :style="{ width: `${progress?.percent ?? 0}%` }"></span></div><ol class="self-update-phases"><li v-for="(phase, index) in phases" :key="phase.id" :class="{ current: index === phaseIndex, done: index < phaseIndex }"><span>{{ index < phaseIndex ? '✓' : index + 1 }}</span>{{ phase.label }}<small v-if="index === phaseIndex">进行中</small></li></ol><p v-if="demo" class="self-demo-note">演示更新流程，不会关闭浏览器或替换本机 EXE。</p></div>
    <div v-else :class="['self-update-detail', summary.kind]"><el-icon><Check v-if="summary.kind === 'current'" /><InfoFilled v-else /></el-icon><div><strong>{{ summary.title }}</strong><p>{{ summary.detail }}</p></div></div>
    <div class="self-update-footer"><span v-if="info">{{ info.runtime || '当前系统' }}<template v-if="info.size"> · {{ formatBytes(info.size) }}</template><template v-if="info.checkedAt"> · 检查于 {{ formatTime(info.checkedAt) }}</template></span><span v-else>点击检查时联网查询官方更新包</span><el-button v-if="info?.releaseUrl" text size="small" :icon="Open" @click="emit('release')">官方发布页面</el-button></div>
  </section>
</template>
