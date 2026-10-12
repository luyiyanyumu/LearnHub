<script setup>
import { computed, onBeforeUnmount, ref, useId, watch } from 'vue'

const props = defineProps({
  entries: { type: Array, default: () => [] },
  running: { type: Boolean, default: false },
  startedAt: { type: Number, default: 0 },
  elapsedMs: { type: Number, default: 0 },
  failed: { type: Boolean, default: false },
})

const traceId = `agent-run-trace-${useId()}`
const expanded = ref(props.running)
const now = ref(Date.now())
const localStartedAt = ref(now.value)
const stoppedElapsed = ref(0)
let clock = null

const entries = computed(() => (props.entries || []).filter(entry => entry && typeof entry === 'object'))
const planEntries = computed(() => entries.value.filter(entry => entry.kind === 'plan'))
const executionEntries = computed(() => entries.value.filter(entry => entry.kind !== 'plan'))
const activeEntry = computed(() => executionEntries.value.findLast(entry => entry.status === 'running'))
const activePlan = computed(() => planEntries.value.findLast(entry => entry.status === 'running'))
const completedPlans = computed(() => planEntries.value.filter(entry => entry.status === 'completed').length)
const adjustedPlans = computed(() => planEntries.value.filter(entry => entry.status === 'cancelled').length)
const plannedCount = computed(() => planEntries.value.length - adjustedPlans.value)
const errorCount = computed(() => entries.value.filter(entry => entry.status === 'error').length)
const waitingCount = computed(() => executionEntries.value.filter(entry => entry.status === 'waiting').length)
const pendingCount = computed(() => entries.value.filter(entry => entry.status === 'pending').length)
const interruptedCount = computed(() => entries.value.filter(entry => statusOf(entry) === 'interrupted').length)
const headerTitle = computed(() => props.running
  ? (activeEntry.value?.title || (activePlan.value ? `计划：${activePlan.value.title}` : entries.value.length ? '正在处理' : '正在连接'))
  : '处理过程')
const headerStatus = computed(() => {
  if (props.running) return 'running'
  if (props.failed || errorCount.value) return 'error'
  if (waitingCount.value) return 'waiting'
  if (pendingCount.value) return 'pending'
  if (interruptedCount.value || !entries.value.length) return 'interrupted'
  return 'completed'
})
const elapsed = computed(() => {
  const recorded = positiveNumber(props.elapsedMs)
  if (!props.running) return Math.max(recorded, stoppedElapsed.value)
  return Math.max(recorded, now.value - (positiveNumber(props.startedAt) || localStartedAt.value), 0)
})
const elapsedLabel = computed(() => duration(elapsed.value))
const statusLabels = {
  pending: '待执行', running: '进行中', completed: '已完成', cancelled: '已调整', error: '未成功', waiting: '待确认', interrupted: '已中断',
}

function positiveNumber(value) {
  return Number.isFinite(value) && value > 0 ? value : 0
}

function duration(value) {
  const seconds = Math.floor(positiveNumber(value) / 1000)
  if (seconds < 60) return `${seconds} 秒`
  const minutes = Math.floor(seconds / 60)
  if (minutes < 60) return `${minutes} 分 ${seconds % 60} 秒`
  return `${Math.floor(minutes / 60)} 小时 ${minutes % 60} 分`
}

function statusOf(entry) {
  if (entry.status === 'running' && !props.running) return 'interrupted'
  return Object.hasOwn(statusLabels, entry.status) ? entry.status : 'interrupted'
}

function stopClock() {
  if (clock !== null) clearInterval(clock)
  clock = null
}

watch(() => props.running, (running, wasRunning) => {
  stopClock()
  now.value = Date.now()
  if (running) {
    localStartedAt.value = now.value
    stoppedElapsed.value = 0
    expanded.value = true
    clock = setInterval(() => { now.value = Date.now() }, 1000)
  } else if (wasRunning) {
    stoppedElapsed.value = Math.max(positiveNumber(props.elapsedMs), now.value - (positiveNumber(props.startedAt) || localStartedAt.value), 0)
    expanded.value = false
  }
}, { immediate: true })

onBeforeUnmount(stopClock)
</script>

<template>
  <section class="run-trace" :class="{ 'is-running': running, 'is-expanded': expanded }" aria-label="智能体处理过程">
    <button
      type="button"
      class="trace-toggle"
      :aria-expanded="expanded"
      :aria-controls="traceId"
      @click="expanded = !expanded"
    >
      <span class="trace-mark" :class="`is-${headerStatus}`" aria-hidden="true">
        <svg v-if="headerStatus === 'completed'" viewBox="0 0 16 16"><path d="m3.5 8 3 3 6-6" /></svg>
        <svg v-else-if="headerStatus === 'error'" viewBox="0 0 16 16"><path d="M8 3v6M8 12v.1" /></svg>
        <svg v-else-if="headerStatus === 'waiting' || headerStatus === 'interrupted'" viewBox="0 0 16 16"><path d="M5.5 4v8M10.5 4v8" /></svg>
        <svg v-else-if="headerStatus === 'pending'" viewBox="0 0 16 16"><circle cx="8" cy="8" r="4.5" /></svg>
      </span>
      <span class="trace-title">{{ headerTitle }}</span>
      <span class="trace-meta">
        <span v-if="executionEntries.length" class="trace-count">{{ executionEntries.length }} 步</span>
        <span v-if="plannedCount" class="trace-count">{{ plannedCount }} 项计划</span>
        <span v-if="adjustedPlans" class="trace-outcome">{{ adjustedPlans }} 项已调整</span>
        <span v-if="errorCount" class="trace-outcome is-error">{{ errorCount }} 项未成功</span>
        <span v-else-if="failed" class="trace-outcome is-error">处理未完成</span>
        <span v-if="waitingCount" class="trace-outcome is-waiting">{{ waitingCount }} 项待确认</span>
        <span v-if="pendingCount" class="trace-outcome">{{ pendingCount }} 项待执行</span>
        <span v-if="interruptedCount && !running" class="trace-outcome">{{ interruptedCount }} 项中断</span>
        <span v-if="running || elapsed > 0" class="trace-time" :aria-label="`耗时 ${elapsedLabel}`">{{ elapsedLabel }}</span>
      </span>
      <svg class="trace-chevron" :class="{ 'is-open': expanded }" viewBox="0 0 16 16" aria-hidden="true"><path d="m6 4 4 4-4 4" /></svg>
    </button>

    <div v-show="expanded" :id="traceId" class="trace-body">
      <div v-if="planEntries.length" class="trace-plan">
        <div class="trace-group-heading"><span>工作计划</span><span>{{ completedPlans }} / {{ plannedCount }}</span></div>
        <ol class="trace-list plan-list" aria-label="工作计划" aria-live="polite" aria-relevant="additions text">
          <li v-for="(entry, index) in planEntries" :key="entry.id || index" class="trace-entry is-plan" :class="`is-${statusOf(entry)}`">
            <span class="entry-mark" :class="`is-${statusOf(entry)}`" aria-hidden="true">
              <svg v-if="statusOf(entry) === 'completed'" viewBox="0 0 16 16"><path d="m3.5 8 3 3 6-6" /></svg>
              <svg v-else-if="statusOf(entry) === 'error'" viewBox="0 0 16 16"><path d="m5 5 6 6M11 5l-6 6" /></svg>
              <svg v-else-if="statusOf(entry) === 'interrupted' || statusOf(entry) === 'waiting'" viewBox="0 0 16 16"><path d="M5.5 4v8M10.5 4v8" /></svg>
              <svg v-else-if="statusOf(entry) === 'pending'" viewBox="0 0 16 16"><circle cx="8" cy="8" r="4.5" /></svg>
              <svg v-else-if="statusOf(entry) === 'cancelled'" viewBox="0 0 16 16"><path d="M4 8h8" /></svg>
            </span>
            <div class="entry-content">
              <div class="entry-heading"><span class="entry-title">{{ entry.title || '计划步骤' }}</span><span class="entry-status">{{ statusLabels[statusOf(entry)] }}</span></div>
              <p v-if="entry.detail && entry.detail !== entry.title" class="entry-detail">{{ entry.detail }}</p>
            </div>
          </li>
        </ol>
      </div>
      <div v-if="planEntries.length && executionEntries.length" class="trace-group-heading execution-heading">实际执行</div>
      <ol v-if="executionEntries.length" class="trace-list" aria-label="执行步骤" aria-live="polite" aria-relevant="additions text">
        <li
          v-for="(entry, index) in executionEntries"
          :key="entry.id || index"
          class="trace-entry"
          :class="[`is-${statusOf(entry)}`, { 'is-nested': !!entry.parentId, 'is-update': entry.kind === 'update' }]"
        >
          <span class="entry-mark" :class="`is-${statusOf(entry)}`" aria-hidden="true">
            <svg v-if="statusOf(entry) === 'completed'" viewBox="0 0 16 16"><path d="m3.5 8 3 3 6-6" /></svg>
            <svg v-else-if="statusOf(entry) === 'error'" viewBox="0 0 16 16"><path d="m5 5 6 6M11 5l-6 6" /></svg>
            <svg v-else-if="statusOf(entry) === 'waiting' || statusOf(entry) === 'interrupted'" viewBox="0 0 16 16"><path d="M5.5 4v8M10.5 4v8" /></svg>
            <svg v-else-if="statusOf(entry) === 'pending'" viewBox="0 0 16 16"><circle cx="8" cy="8" r="4.5" /></svg>
          </span>
          <div class="entry-content">
            <div class="entry-heading">
              <span class="entry-title">{{ entry.title || '处理步骤' }}</span>
              <span class="entry-status" :class="{ 'visually-hidden': statusOf(entry) === 'completed' }">{{ statusLabels[statusOf(entry)] }}</span>
              <span v-if="positiveNumber(entry.elapsedMs) >= 1000" class="entry-time">{{ duration(entry.elapsedMs) }}</span>
            </div>
            <p v-if="entry.detail && entry.detail !== entry.title" class="entry-detail">{{ entry.detail }}</p>
          </div>
        </li>
      </ol>
      <p v-else class="trace-empty">{{ planEntries.length ? '尚无实际执行记录。' : running ? '正在连接智能体，处理步骤将在这里实时更新。' : '本次对话没有记录处理步骤。' }}</p>
    </div>
  </section>
</template>

<style scoped>
.run-trace {
  min-width: 0;
  margin: 0 0 16px;
  color: var(--app-text-2);
  font-size: var(--font-caption, 12px);
  line-height: 1.6;
}
.trace-toggle {
  display: flex;
  width: 100%;
  min-height: 36px;
  align-items: center;
  gap: 8px;
  padding: 6px 8px;
  border: 1px solid transparent;
  border-radius: var(--radius, 8px);
  background: transparent;
  color: inherit;
  font: inherit;
  text-align: left;
  cursor: pointer;
  transition: background-color var(--dur-fast, 0.15s) ease;
}
.trace-toggle:hover { background: var(--app-brand-glow); }
.trace-toggle:focus-visible {
  outline: 2px solid var(--app-brand);
  outline-offset: 2px;
}
.trace-title {
  min-width: 0;
  color: var(--app-text-2);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.is-running .trace-title { color: var(--app-text-1); }
.trace-meta {
  display: flex;
  flex-wrap: wrap;
  gap: 2px 8px;
  align-items: center;
  margin-left: auto;
  color: var(--app-text-3);
  font-size: 11px;
  white-space: nowrap;
}
.trace-time, .entry-time { font-variant-numeric: tabular-nums; }
.trace-time { min-width: 28px; text-align: right; }
.trace-outcome.is-error { color: var(--el-color-danger, #f56c6c); }
.trace-outcome.is-waiting { color: var(--app-brand-deep); }
.trace-chevron {
  width: 14px;
  height: 14px;
  flex: 0 0 14px;
  transition: transform var(--dur-fast, 0.15s) ease;
}
.trace-chevron.is-open { transform: rotate(90deg); }
.trace-mark, .entry-mark {
  display: inline-flex;
  width: 16px;
  height: 16px;
  flex: 0 0 16px;
  align-items: center;
  justify-content: center;
  color: var(--app-text-3);
}
.trace-mark.is-running::before, .entry-mark.is-running::before {
  content: '';
  box-sizing: border-box;
  width: 11px;
  height: 11px;
  border: 1.5px solid var(--app-border);
  border-top-color: var(--app-brand);
  border-right-color: var(--app-brand);
  border-radius: 50%;
  animation: trace-spin 0.9s linear infinite;
}
.trace-mark.is-error, .entry-mark.is-error { color: var(--el-color-danger, #f56c6c); }
.trace-mark.is-waiting, .entry-mark.is-waiting { color: var(--app-brand-deep); }
.run-trace svg { width: 16px; height: 16px; fill: none; stroke: currentColor; stroke-width: 1.5; stroke-linecap: round; stroke-linejoin: round; }
.trace-body { padding: 2px 8px 4px; }
.trace-list { padding: 0; margin: 0; list-style: none; }
.trace-plan { padding: 8px 10px; margin-bottom: 8px; border: 1px solid var(--app-border); border-radius: var(--radius, 8px); background: var(--app-brand-glow); }
.trace-group-heading { display: flex; align-items: center; justify-content: space-between; gap: 8px; color: var(--app-text-3); font-size: 11px; }
.trace-group-heading > :last-child { font-variant-numeric: tabular-nums; }
.execution-heading { padding: 2px 0 3px 24px; }
.plan-list .trace-entry::after { border-left-style: dashed; }
.trace-entry.is-plan.is-cancelled .entry-title { color: var(--app-text-3); text-decoration: line-through; }
.trace-entry {
  position: relative;
  display: flex;
  gap: 8px;
  padding: 5px 0;
  min-width: 0;
}
.trace-entry:not(:last-child)::after {
  content: '';
  position: absolute;
  left: 7.5px;
  top: 24px;
  bottom: -2px;
  border-left: 1px solid var(--app-border);
}
.trace-entry.is-nested { margin-left: 24px; }
.entry-mark { position: relative; z-index: 1; margin-top: 2px; }
.entry-content { min-width: 0; flex: 1; }
.entry-heading { display: flex; flex-wrap: wrap; align-items: baseline; gap: 2px 8px; }
.entry-title { min-width: 0; overflow-wrap: anywhere; }
.trace-entry.is-running .entry-title { color: var(--app-text-1); }
.entry-status, .entry-time { color: var(--app-text-3); font-size: 11px; white-space: nowrap; }
.trace-entry.is-error .entry-status { color: var(--el-color-danger, #f56c6c); }
.trace-entry.is-waiting .entry-status { color: var(--app-brand-deep); }
.entry-time { margin-left: auto; }
.entry-detail { margin: 3px 0 0; color: var(--app-text-3); white-space: pre-wrap; overflow-wrap: anywhere; }
.trace-empty { padding: 4px 0 4px 24px; margin: 0; color: var(--app-text-3); }
.visually-hidden { position: absolute; width: 1px; height: 1px; padding: 0; margin: -1px; overflow: hidden; clip: rect(0, 0, 0, 0); white-space: nowrap; border: 0; }
@keyframes trace-spin { to { transform: rotate(360deg); } }
@media (max-width: 520px) {
  .trace-toggle { flex-wrap: wrap; gap: 4px 8px; }
  .trace-title { flex: 1 1 calc(100% - 64px); }
  .trace-meta { order: 4; width: 100%; margin-left: 24px; }
  .trace-meta:empty { display: none; }
  .trace-time { margin-left: auto; }
  .trace-entry.is-nested { margin-left: 16px; }
}
@media (prefers-reduced-motion: reduce) {
  .trace-toggle, .trace-chevron { transition: none; }
  .trace-mark.is-running::before, .entry-mark.is-running::before { animation: none; }
}
</style>
