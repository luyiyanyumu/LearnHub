<script setup>
import { computed, defineAsyncComponent, onBeforeUnmount, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import { kbApi } from '../api'
import { MODEL_CONFIGURATION_CHANGED_EVENT, profileTargetsForTask, routingTargetLabel } from '../utils/modelProfileView'
import { KNOWLEDGE_GROUP_LABELS, knowledgeChanges, knowledgeValues, validateKnowledgeValues } from '../utils/knowledgeSettings'

const DiagnosticPanel = defineAsyncComponent(() => import('./KnowledgeDiagnosticPanel.vue'))
const props = defineProps({ active: Boolean, profiles: { type: Array, default: () => [] }, routing: { type: Array, default: () => [] }, routingBusy: Boolean })
const emit = defineEmits(['state', 'set-target', 'open-models'])
const loaded = ref(false)
const loading = ref(false)
const saving = ref(false)
const loadError = ref('')
const saveError = ref('')
const statusError = ref('')
const fields = ref([])
const defaults = ref({})
const values = ref({})
const baseline = ref({})
const status = ref(null)
const statusRefreshing = ref(false)
const needsRebuild = ref(false)
const view = ref('parameters')
const diagnosticMounted = ref(false)
const filter = ref('')
const category = ref('common')
const commonKeys = ['chunkTarget', 'chunkOverlap', 'chunkMax', 'vectorEnabled', 'candidates', 'rerankEnabled', 'maxPassages', 'totalChars']
const rebuilding = ref(false)
const syncing = ref(false)
const needsSync = ref(false)
const job = ref(null)
let disposed = false
let loadSequence = 0
let statusSequence = 0
let jobTimer
let cancelJobWait

const changes = computed(() => knowledgeChanges(values.value, baseline.value, fields.value))
const dirty = computed(() => Object.keys(changes.value).length > 0)
const rebuildChanges = computed(() => fields.value.filter(f => f.requiresRebuild && f.key in changes.value))
const validation = computed(() => validateKnowledgeValues(values.value, defaults.value, fields.value))
const routes = computed(() => props.routing.filter(row => ['embed', 'rerank', 'rewrite', 'grounding'].includes(row.task)))
const groups = computed(() => {
  const groups = new Map()
  for (const field of fields.value) {
    const id = field.group || 'retrieval'
    if (!groups.has(id)) groups.set(id, { id, label: KNOWLEDGE_GROUP_LABELS[id] || id, fields: [] })
    groups.get(id).fields.push(field)
  }
  return [...groups.values()]
})
const visibleFields = computed(() => {
  const keyword = filter.value.trim().toLowerCase()
  if (keyword) return fields.value.filter(field => `${field.label} ${field.help || ''} ${field.key}`.toLowerCase().includes(keyword))
  if (category.value === 'common') return commonKeys.map(key => fields.value.find(field => field.key === key)).filter(Boolean)
  return fields.value.filter(field => field.group === category.value)
})
const categoryLabel = computed(() => filter.value.trim() ? '搜索结果' : category.value === 'common' ? '常用参数' : groups.value.find(group => group.id === category.value)?.label || '参数设置')
const categoryHints = {
  common: '先调整切块、召回和上下文；其他设置可按分类查找。',
  index: '控制内容如何切块与建立索引。标记“重建”的参数需要重建已有向量。',
  retrieval: '控制召回范围、向量筛选与多路结果融合。',
  rerank: '控制候选重排方式与输入长度。',
  context: '控制最终注入回答的原文数量和长度。',
  grounding: '控制回答生成后的依据核对。',
}
const summaryText = computed(() => !status.value ? '索引状态加载中' : !status.value.configured ? '嵌入模型未配置' : !status.value.enabled ? '向量检索已关闭' : showRebuild.value ? '索引待重建' : needsSync.value ? '向量待同步' : '索引已就绪')
const showRebuild = computed(() => needsRebuild.value || status.value?.stale || rebuildChanges.value.length > 0)
const canRebuild = computed(() => !!status.value?.configured && !!status.value?.enabled && !dirty.value && !saving.value && !rebuilding.value && !syncing.value && !props.routingBusy && !statusRefreshing.value)

watch([changes, saving, loaded, rebuilding, syncing, view], () => emit('state', { dirty: dirty.value, count: Object.keys(changes.value).length, saving: saving.value, loaded: loaded.value, rebuilding: rebuilding.value || syncing.value, view: view.value }), { immediate: true })
watch(view, value => { if (value === 'diagnostics') diagnosticMounted.value = true })
watch(() => props.active, active => { if (active && !loaded.value && !loading.value) load() }, { immediate: true })

function applySnapshot(snapshot) {
  fields.value = snapshot.fields || []
  defaults.value = snapshot.defaults || {}
  const fresh = knowledgeValues(snapshot)
  values.value = { ...fresh }
  baseline.value = { ...fresh }
  needsRebuild.value = !!snapshot.needsRebuild
  needsSync.value = !!snapshot.indexImpact?.requiresSync
  loaded.value = true
}

async function load() {
  const ticket = ++loadSequence
  loading.value = true
  loadError.value = ''
  try {
    const snapshot = await kbApi.settings()
    if (disposed || ticket !== loadSequence) return
    applySnapshot(snapshot)
    await refreshStatus()
  } catch (error) {
    if (!disposed && ticket === loadSequence) loadError.value = error?.message || '知识库设置加载失败'
  } finally {
    if (!disposed && ticket === loadSequence) loading.value = false
  }
}

async function refreshStatus() {
  const ticket = ++statusSequence
  statusRefreshing.value = true
  try {
    const fresh = await kbApi.status()
    if (disposed || ticket !== statusSequence) return
    status.value = fresh
    statusError.value = ''
    needsRebuild.value = !!fresh.stale
    if (fresh.vectorBackend === 'milvus') needsSync.value = !fresh.annReady
    else needsSync.value = false
  } catch (error) {
    if (!disposed && ticket === statusSequence) statusError.value = error?.message || '索引状态暂时不可用'
  } finally {
    if (!disposed && ticket === statusSequence) statusRefreshing.value = false
  }
}

async function save() {
  if (!loaded.value || saving.value || rebuilding.value || syncing.value) return false
  saveError.value = ''
  if (validation.value.length) { saveError.value = validation.value.join('；'); return false }
  if (!dirty.value) { ElMessage.info('没有需要保存的知识库修改'); return true }
  saving.value = true
  try {
    const snapshot = await kbApi.saveSettings(changes.value)
    if (disposed) return false
    applySnapshot(snapshot)
    await refreshStatus()
    ElMessage.success(snapshot.needsRebuild ? '知识库参数已保存，重建索引后应用切块与嵌入修改' : '知识库参数已保存，下一次检索生效')
    return true
  } catch (error) {
    if (!disposed) saveError.value = error?.message || '知识库参数保存失败，修改仍保留'
    return false
  } finally { if (!disposed) saving.value = false }
}

function resetDefaults(groupId) {
  if (saving.value || rebuilding.value || syncing.value) return
  for (const field of fields.value) {
    if (!groupId || field.group === groupId) values.value[field.key] = defaults.value[field.key] ?? field.default
  }
  saveError.value = ''
}

function resetField(field) { values.value[field.key] = defaults.value[field.key] ?? field.default }
function defaultLabel(field) {
  const value = defaults.value[field.key] ?? field.default
  if (field.type === 'boolean') return value ? '开启' : '关闭'
  return field.options?.find(option => option.value === value)?.label || String(value ?? '') || '留空'
}

function waitForJob() {
  return new Promise(resolve => {
    cancelJobWait = resolve
    jobTimer = setTimeout(() => { cancelJobWait = null; resolve() }, 1500)
  })
}

async function rebuild() {
  if (!canRebuild.value) return
  rebuilding.value = true
  statusError.value = ''
  try {
    const start = await kbApi.rebuild()
    job.value = start
    const deadline = Date.now() + 15 * 60 * 1000
    while (!disposed && Date.now() < deadline) {
      await waitForJob()
      if (disposed) return
      job.value = await kbApi.job(start.jobId)
      if (job.value.status !== 'running') break
    }
    if (disposed) return
    if (job.value?.status === 'done') { ElMessage.success(`索引重建完成，共 ${job.value.chunks ?? 0} 块`); await refreshStatus() }
    else statusError.value = job.value?.error || '重建尚未完成，可刷新索引状态查看进度'
  } catch (error) { if (!disposed) statusError.value = error?.message || '索引重建失败' }
  finally { if (!disposed) rebuilding.value = false }
}

async function syncVector() {
  if (dirty.value || saving.value || rebuilding.value || syncing.value || props.routingBusy || statusRefreshing.value || !status.value?.configured) return
  syncing.value = true; statusError.value = ''
  try {
    const result = await kbApi.syncVector()
    if (disposed) return
    await refreshStatus()
    if (result.ok === false || result.synced === false || needsSync.value) statusError.value = result.reason || result.error || 'Milvus 尚未就绪，向量检索继续回退到 MySQL'
    else ElMessage.success('已有向量已同步到 Milvus')
  } catch (error) { if (!disposed) statusError.value = error?.message || '向量同步失败，MySQL 索引仍保留' }
  finally { if (!disposed) syncing.value = false }
}

function modelsChanged() { if (loaded.value) refreshStatus() }
window.addEventListener(MODEL_CONFIGURATION_CHANGED_EVENT, modelsChanged)
onBeforeUnmount(() => {
  disposed = true; loadSequence++; statusSequence++
  clearTimeout(jobTimer); cancelJobWait?.()
  window.removeEventListener(MODEL_CONFIGURATION_CHANGED_EVENT, modelsChanged)
})
defineExpose({ save, resetDefaults, dirty, saving })
</script>

<template>
  <div class="knowledge-settings" :aria-busy="loading">
    <header class="kb-heading">
      <div><h3>知识库</h3><p>调整检索参数，查看检索效果。</p></div>
      <el-tag v-if="dirty" type="warning" effect="plain" size="small">{{ Object.keys(changes).length }} 项未保存</el-tag>
    </header>
    <el-alert v-if="loadError" :title="loadError" type="error" :closable="false" show-icon class="kb-alert"><el-button text @click="load">重新加载</el-button></el-alert>
    <div v-else-if="loading && !loaded" role="status" class="kb-loading">正在加载知识库设置…</div>
    <template v-if="loaded">
      <div class="kb-topline">
        <el-radio-group v-model="view" class="kb-view" aria-label="知识库设置与检验">
          <el-radio-button value="parameters">参数设置</el-radio-button>
          <el-radio-button value="diagnostics">检验区</el-radio-button>
          <el-radio-button value="maintenance">模型与索引</el-radio-button>
        </el-radio-group>
        <button type="button" class="kb-status-link" :class="{ 'needs-attention': status && (!status.configured || showRebuild || needsSync) }" @click="view = 'maintenance'">
          <span class="kb-status-dot" aria-hidden="true"></span>{{ summaryText }}<span v-if="status?.chunks != null" class="kb-status-count"> · {{ status.chunks }} 块</span>
        </button>
      </div>
      <el-alert v-if="saveError" :title="saveError" type="error" :closable="false" show-icon class="kb-alert" role="alert" />
      <p v-else-if="dirty && validation.length" class="kb-validation" role="status">{{ validation[0] }}</p>

      <section v-show="view === 'parameters'" class="kb-parameters">
        <div class="kb-parameter-tools">
          <el-select v-model="category" aria-label="知识库参数分类" class="kb-category">
            <el-option label="常用参数" value="common" />
            <el-option v-for="group in groups" :key="group.id" :label="group.label" :value="group.id" />
          </el-select>
          <el-input v-model="filter" clearable placeholder="搜索全部参数" aria-label="查找知识库参数" class="kb-filter" />
        </div>
        <div class="kb-section-title">
          <h4>{{ categoryLabel }} <span>{{ visibleFields.length }} 项</span></h4>
          <el-button v-if="category !== 'common' && !filter.trim()" text size="small" :disabled="saving || rebuilding || syncing" @click="resetDefaults(category)">本组恢复默认</el-button>
        </div>
        <p class="kb-hint kb-category-hint">{{ filter.trim() ? '搜索覆盖全部参数。清除搜索后回到当前分类。' : categoryHints[category] }}</p>
        <div class="kb-fields">
          <div v-for="field in visibleFields" :key="field.key" class="kb-field" :class="{ 'is-modified': field.key in changes, 'is-wide': field.type === 'text' }">
            <div class="kb-field-label">
              <label :for="'kb-' + field.key">{{ field.label }}</label>
              <span v-if="field.requiresRebuild" class="kb-rebuild-mark" title="保存后需要重建已有向量索引">重建</span>
              <el-tooltip :content="field.help" :trigger="['hover', 'focus']" placement="top" :show-after="200" :teleported="true">
                <button type="button" class="kb-help" :aria-label="field.label + '说明'">?</button>
              </el-tooltip>
            </div>
            <div class="kb-field-control">
              <el-switch v-if="field.type === 'boolean'" :id="'kb-' + field.key" v-model="values[field.key]" :aria-label="field.label" :disabled="saving || rebuilding || syncing" />
              <el-input-number v-else-if="field.type === 'number'" :id="'kb-' + field.key" v-model="values[field.key]" :aria-label="field.label" :min="field.min" :max="field.max" :step="field.step || 1" :precision="field.step && field.step < 1 ? String(field.step).split('.')[1]?.length : 0" controls-position="right" :disabled="saving || rebuilding || syncing" />
              <el-select v-else-if="field.type === 'select'" :id="'kb-' + field.key" v-model="values[field.key]" :aria-label="field.label" :disabled="saving || rebuilding || syncing"><el-option v-for="option in field.options || []" :key="option.value" :label="option.label" :value="option.value" /></el-select>
              <el-input v-else :id="'kb-' + field.key" v-model="values[field.key]" :aria-label="field.label" :disabled="saving || rebuilding || syncing" :placeholder="String(defaults[field.key] ?? field.default ?? '')" />
            </div>
            <div class="kb-field-meta"><span>默认 {{ defaultLabel(field) }}</span><el-button v-if="field.key in changes" text size="small" :aria-label="field.label + '恢复默认'" :disabled="saving || rebuilding || syncing" @click="resetField(field)">恢复</el-button></div>
          </div>
        </div>
        <p v-if="!visibleFields.length" class="kb-empty" role="status">没有匹配的参数。</p>
        <p v-if="rebuildChanges.length" class="kb-change-note" role="status">切块或嵌入参数已修改。保存后，在“模型与索引”中重建索引。</p>
      </section>

      <DiagnosticPanel v-if="diagnosticMounted" v-show="view === 'diagnostics'" :active="active && view === 'diagnostics'" :dirty="dirty" :config="baseline" :index-status="status" />

      <section v-show="view === 'maintenance'" class="kb-maintenance">
        <div class="kb-section-title"><h4>检索模型</h4><el-button text type="primary" @click="emit('open-models')">管理模型档案</el-button></div>
        <p class="kb-hint">模型选择立即保存。</p>
        <div class="kb-routes">
          <div v-for="row in routes" :key="row.task" class="kb-route">
            <label :for="'kb-route-' + row.task">{{ row.label }}</label>
            <el-select :id="'kb-route-' + row.task" :model-value="row.target" :aria-label="row.label" :disabled="routingBusy || rebuilding || syncing" :placeholder="row.targetLabel || '选择档案'" @change="emit('set-target', row, $event)">
              <el-option v-for="target in profileTargetsForTask(row, profiles)" :key="target.id" :label="routingTargetLabel(target)" :value="target.id" />
            </el-select>
          </div>
        </div>
        <p v-if="!routes.length" class="kb-hint">在模型档案中配置嵌入、重排等任务的模型。</p>
        <section class="kb-index-section">
          <div class="kb-section-title"><h4>向量索引</h4><el-button text :disabled="rebuilding || syncing || statusRefreshing" @click="refreshStatus">刷新状态</el-button></div>
          <dl class="kb-index-stats" aria-label="当前索引状态">
            <div><dt>索引块数</dt><dd>{{ status?.chunks ?? '—' }}</dd></div>
            <div><dt>可用向量块</dt><dd>{{ status?.compatibleChunks ?? '—' }}</dd></div>
            <div><dt>嵌入模型</dt><dd>{{ status?.model || '未配置' }}</dd></div>
            <div><dt>向量后端</dt><dd>{{ status?.vectorBackend === 'milvus' ? (status?.annReady ? 'Milvus' : 'Milvus（未就绪）') : 'MySQL' }}</dd></div>
          </dl>
          <div class="kb-index-action">
            <div>
              <b>{{ status && !status.configured ? '先选择嵌入模型' : showRebuild ? '索引需要重建' : '索引已与当前配置对齐' }}</b>
              <p class="kb-hint">{{ status && !status.configured ? '关键词检索仍可用。选择嵌入模型后，可重建并检验向量召回。' : dirty ? '先保存参数，再重建已有资料的向量索引。' : '切块或嵌入配置改变时，重建索引以应用到已有资料。' }}</p>
            </div>
            <el-button type="primary" :loading="rebuilding" :disabled="!canRebuild || syncing" @click="rebuild">重建向量索引</el-button>
          </div>
          <div v-if="needsSync" class="kb-index-action"><div><b>Milvus 待同步</b><p class="kb-hint">同步已有向量。未就绪时查询继续使用 MySQL。</p></div><el-button :loading="syncing" :disabled="dirty || saving || rebuilding || routingBusy || statusRefreshing || !status?.configured" @click="syncVector">同步到 Milvus</el-button></div>
          <div v-if="rebuilding && job" class="kb-job" role="status"><el-progress :percentage="job.percent || 0" :stroke-width="6" />{{ job.stage }} · 来源 {{ job.done ?? 0 }}/{{ job.total ?? 0 }}</div>
        </section>
        <el-alert v-if="statusError" :title="statusError" type="warning" :closable="false" class="kb-alert"><el-button text @click="refreshStatus">刷新索引状态</el-button></el-alert>
      </section>
    </template>
  </div>
</template>

<style scoped>
.knowledge-settings { min-width: 0; color: var(--app-text-1); }
.kb-heading { display: flex; justify-content: space-between; align-items: flex-start; gap: 12px; margin-bottom: 20px; }
.kb-heading h3 { margin: 0 0 4px; font-size: var(--font-heading); }
.kb-heading p, .kb-hint { color: var(--app-text-3); font-size: var(--font-caption); line-height: 1.7; margin: 4px 0; overflow-wrap: anywhere; }
.kb-topline { display: flex; align-items: center; justify-content: space-between; flex-wrap: wrap; gap: 12px; padding-bottom: 20px; margin-bottom: 20px; border-bottom: 1px solid var(--app-border-weak); }
.kb-view { flex: none; }
.kb-view :deep(.el-radio-button__inner) { font-size: 13px; padding: 9px 16px; }
.kb-status-link { display: inline-flex; align-items: center; gap: 6px; border: 0; background: transparent; padding: 4px 0; color: var(--app-text-3); font: inherit; font-size: var(--font-caption); cursor: pointer; }
.kb-status-dot { width: 6px; height: 6px; flex: none; border-radius: 50%; background: var(--app-brand); }
.kb-status-link.needs-attention .kb-status-dot { background: var(--el-color-warning); }
.kb-status-link:hover { color: var(--app-brand-deep); }
.kb-parameter-tools { display: flex; align-items: center; gap: 12px; margin-bottom: 20px; }
.kb-category { flex: 0 0 176px; min-width: 0; }
.kb-filter { flex: 1; min-width: 0; }
.kb-section-title { display: flex; align-items: center; justify-content: space-between; gap: 12px; }
.kb-section-title h4 { margin: 0; font-size: var(--font-body); font-weight: 600; }
.kb-section-title h4 > span { margin-left: 8px; font-size: var(--font-caption); color: var(--app-text-3); font-weight: 400; }
.kb-category-hint { margin: 4px 0 12px; }
.kb-fields { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); column-gap: 32px; }
.kb-field { display: grid; grid-template-columns: minmax(0, 1fr) 144px; align-items: center; gap: 4px 12px; min-width: 0; padding: 12px 0; border-bottom: 1px solid var(--app-border-weak); }
.kb-field.is-wide { grid-column: 1 / -1; }
.kb-field.is-wide { grid-template-columns: 180px minmax(0, 1fr); }
.kb-field-label { display: flex; align-items: center; flex-wrap: wrap; gap: 6px; margin: 0; font-size: var(--font-body); }
.kb-field-label > label { min-width: 0; }
.kb-field.is-modified .kb-field-label > label::after { content: ''; display: inline-block; width: 5px; height: 5px; border-radius: 50%; margin-left: 6px; vertical-align: middle; background: var(--app-brand); }
.kb-rebuild-mark { color: var(--app-text-3); font-size: 11px; border-bottom: 1px dotted var(--app-border); }
.kb-help { flex: none; margin-left: auto; width: 20px; height: 20px; padding: 0; border: 1px solid var(--app-border); border-radius: 50%; background: transparent; color: var(--app-text-3); font-size: var(--font-caption); cursor: help; }
.kb-field-control { display: flex; align-items: center; min-height: var(--control-h); }
.kb-field-control :deep(.el-input-number), .kb-field-control :deep(.el-select), .kb-field-control :deep(.el-input) { width: 100%; min-width: 0; }
.kb-field-meta { grid-column: 1 / -1; display: flex; align-items: center; gap: 8px; min-height: 22px; margin: 0; font-size: var(--font-caption); color: var(--app-text-3); overflow-wrap: anywhere; }
.kb-field-meta :deep(.el-button) { height: 22px; padding: 0 4px; margin: 0; }
.kb-change-note { margin: 16px 0 0; color: var(--app-brand-deep); font-size: var(--font-caption); line-height: 1.7; }
.kb-routes { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 16px 24px; margin: 16px 0 24px; }
.kb-route { min-width: 0; font-size: var(--font-body); }
.kb-route label { display: block; margin-bottom: 8px; }
.kb-route :deep(.el-select) { width: 100%; }
.kb-index-section { border-top: 1px solid var(--app-border-weak); padding-top: 20px; margin-top: 24px; }
.kb-index-stats { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 16px 24px; margin: 16px 0 24px; }
.kb-index-stats > div { min-width: 0; }
.kb-index-stats dt { color: var(--app-text-3); font-size: var(--font-caption); }
.kb-index-stats dd { margin: 4px 0 0; font-size: var(--font-body); font-weight: 550; overflow-wrap: anywhere; }
.kb-index-action { display: flex; align-items: center; justify-content: space-between; gap: 16px; padding: 16px 0; border-top: 1px solid var(--app-border-weak); }
.kb-index-action > div { flex: 1; min-width: 0; }
.kb-index-action b { font-size: var(--font-body); font-weight: 550; }
.kb-index-action > .el-button { flex: none; }
.kb-alert, .kb-job { margin: 16px 0; }
.kb-job, .kb-validation, .kb-loading, .kb-empty { font-size: var(--font-caption); color: var(--app-text-2); line-height: 1.7; }
.kb-validation { color: var(--el-color-warning); margin: 12px 0; }
.kb-status-link:focus-visible, .kb-help:focus-visible { outline: 2px solid var(--app-brand); outline-offset: 3px; }
@media (max-width: 760px) {
  .kb-heading { margin-bottom: 16px; }
  .kb-topline { gap: 10px; padding-bottom: 16px; margin-bottom: 16px; }
  .kb-status-count { display: none; }
  .kb-parameter-tools { gap: 8px; margin-bottom: 16px; }
  .kb-category { flex-basis: 132px; }
  .kb-fields { grid-template-columns: 1fr; }
  .kb-field { display: grid; grid-template-columns: minmax(0, 1fr) 132px; align-items: center; gap: 4px 12px; padding: 14px 0; }
  .kb-field-label { margin: 0; gap: 5px; font-size: 13px; }
  .kb-help { margin-left: 0; }
  .kb-field-meta { grid-column: 1 / -1; margin: 0; min-height: 18px; }
  .kb-field.is-wide { grid-template-columns: minmax(0, 1fr) 132px; }
  .kb-field.is-wide .kb-field-control { grid-column: 1 / -1; }
  .kb-field-control { min-height: 32px; }
  .kb-routes { grid-template-columns: 1fr; }
  .kb-view :deep(.el-radio-button__inner) { padding: 9px 12px; }
}
@media (max-width: 400px) {
  .kb-heading h3 { font-size: 18px; }
  .kb-heading { gap: 8px; }
  .kb-topline { gap: 8px; }
  .kb-view :deep(.el-radio-button__inner) { padding: 9px 10px; font-size: var(--font-caption); }
  .kb-category { flex-basis: 120px; }
  .kb-field { grid-template-columns: minmax(0, 1fr) 112px; gap: 4px 8px; }
  .kb-field-label { flex-wrap: wrap; font-size: var(--font-caption); }
  .kb-index-action { flex-wrap: wrap; }
  .kb-index-action > div { flex-basis: 100%; }
}
</style>
