<script setup>
import { computed, nextTick, onBeforeUnmount, ref, watch } from 'vue'
import { kbApi } from '../api'
import { diagnosticHitKey, diagnosticNumber, diagnosticPercent } from '../utils/knowledgeSettings'
import { MODEL_CONFIGURATION_CHANGED_EVENT } from '../utils/modelProfileView'

const props = defineProps({ active: Boolean, dirty: Boolean, config: { type: Object, default: () => ({}) }, indexStatus: Object })
const cases = ref([])
const casesLoading = ref(false)
const casesLoaded = ref(false)
const loadError = ref('')
const error = ref('')
const dataset = ref('builtin')
const mode = ref('fused')
const topK = ref(8)
const question = ref('')
const selectedCase = ref('')
const busy = ref(false)
const result = ref(null)
const reportElement = ref(null)
const resultTab = ref('hits')
const resultStale = ref(false)
const lastRequest = ref(null)
let sequence = 0
let caseSequence = 0
let controller
let disposed = false
const candidateMax = computed(() => Math.min(100, Number(props.config.candidates) || 24))
const currentCase = computed(() => cases.value.find(item => item.id === selectedCase.value))
const beforeRanks = computed(() => new Map((result.value?.beforeRerank || []).map((hit, index) => [diagnosticHitKey(hit), hit.rank || index + 1])))
const sortedHits = computed(() => (result.value?.afterRerank || []).map((hit, index) => ({
  ...hit, afterRank: hit.rank || index + 1, beforeRank: beforeRanks.value.get(diagnosticHitKey(hit)),
})))
const hasExpected = computed(() => Number(result.value?.metrics?.totalExpected) > 0)
const processIssues = computed(() => (result.value?.stages || []).filter(stage => ['error', 'failed', 'degraded', 'fallback'].includes(stage.status)).length)
const caseKinds = { exact: '术语', semantic: '同义', 'multi-source': '多来源', negative: '未覆盖' }
const sourceNames = { note: '笔记', quick_ref: '速查卡', file: '资料', wiki: 'Wiki' }
const channelNames = { keyword: '关键词', vector: '向量', graph: '图谱原文', wikiSources: 'Wiki 原文', supplemental: '补检索', wiki: 'Wiki 导览', 'section-neighbor': '同小节扩展' }
const statusNames = { ok: '完成', success: '完成', completed: '完成', skipped: '跳过', disabled: '未启用', notApplicable: '不参与', not_applicable: '不参与', degraded: '降级', fallback: '回退', error: '失败', failed: '失败', running: '进行中', empty: '无命中' }

watch(() => props.active, active => { if (active && !casesLoaded.value && !casesLoading.value) loadCases(); if (!active) cancel() }, { immediate: true })
watch(() => props.config, () => { if (result.value) resultStale.value = true; topK.value = Math.min(topK.value, candidateMax.value) }, { deep: true })
watch(dataset, () => { cancel(); result.value = null; error.value = ''; lastRequest.value = null })

async function loadCases() {
  const ticket = ++caseSequence
  casesLoading.value = true; loadError.value = ''
  try {
    const data = await kbApi.diagnosticCases()
    if (disposed || ticket !== caseSequence) return
    cases.value = data.cases || []
    if (!selectedCase.value || !cases.value.some(item => item.id === selectedCase.value)) selectedCase.value = cases.value[0]?.id || ''
    casesLoaded.value = true
  } catch (reason) { if (!disposed && ticket === caseSequence) loadError.value = reason?.message || '内置案例加载失败' }
  finally { if (!disposed && ticket === caseSequence) casesLoading.value = false }
}

function cancel() { sequence++; controller?.abort(); controller = null; busy.value = false }

async function run(caseItem, retryRequest) {
  if (props.dirty) { error.value = '参数有未保存修改，请先保存知识库设置再检验'; return }
  const item = caseItem || currentCase.value
  const payload = retryRequest || {
    dataset: dataset.value, mode: mode.value, topK: topK.value, includeContext: true,
    ...(dataset.value === 'builtin' ? { caseId: item?.id } : { question: question.value.trim() }),
  }
  if (payload.dataset === 'builtin' && !payload.caseId) { error.value = '请选择内置案例'; return }
  if (payload.dataset === 'current' && !payload.question) { error.value = '请填写要检验的问题'; return }
  if (payload.caseId) selectedCase.value = payload.caseId
  controller?.abort()
  controller = new AbortController()
  const ticket = ++sequence
  busy.value = true; error.value = ''; result.value = null; resultStale.value = false
  lastRequest.value = { ...payload }
  try {
    const data = await kbApi.diagnose(payload, { signal: controller.signal })
    if (disposed || ticket !== sequence) return
    result.value = data
    resultTab.value = 'hits'
    await nextTick()
    if (!disposed && ticket === sequence && props.active && window.innerWidth <= 760) reportElement.value?.scrollIntoView({ block: 'start', behavior: 'auto' })
  } catch (reason) {
    if (!disposed && ticket === sequence && reason?.code !== 'ERR_CANCELED' && reason?.name !== 'CanceledError') error.value = reason?.message || '检验失败，请重试'
  } finally { if (!disposed && ticket === sequence) busy.value = false }
}

function stageCount(stage) { return stage.count ?? (Array.isArray(stage.items) ? stage.items.length : '—') }
function stageStatus(stage) { return statusNames[stage.status] || stage.status || '完成' }
function hitSource(hit) { return `${sourceNames[hit.sourceType || hit.type] || hit.sourceType || hit.type} #${hit.sourceId ?? hit.id}` }
function hitChunk(hit) { return hit.seq == null || Number(hit.seq) < 0 ? '证据窗口' : `块 ${hit.seq}` }
function rankChange(hit) {
  if (!hit.beforeRank) return '新进入'
  const difference = hit.beforeRank - hit.afterRank
  return difference > 0 ? `↑ ${difference}` : difference < 0 ? `↓ ${-difference}` : '不变'
}
function reportContext() { return typeof result.value?.context === 'string' ? result.value.context : result.value?.context?.text || '' }
function modelsChanged() { if (result.value) resultStale.value = true }
window.addEventListener(MODEL_CONFIGURATION_CHANGED_EVENT, modelsChanged)
onBeforeUnmount(() => { disposed = true; caseSequence++; cancel(); window.removeEventListener(MODEL_CONFIGURATION_CHANGED_EVENT, modelsChanged) })
</script>

<template>
  <section class="knowledge-diagnostics">
    <el-alert v-if="dirty" title="检验使用已保存参数，请先保存当前修改。" type="warning" :closable="false" show-icon class="diag-alert" />
    <div class="diag-options">
      <div class="diag-option diag-option-dataset"><label for="diag-dataset">资料范围</label><el-select id="diag-dataset" v-model="dataset" aria-label="检验资料范围" class="diag-dataset"><el-option label="内置合成案例" value="builtin" /><el-option label="当前资料检索" value="current" /></el-select></div>
      <div class="diag-option diag-option-mode"><label for="diag-mode">检索模式</label><el-select id="diag-mode" v-model="mode" aria-label="检验检索模式"><el-option label="融合检索" value="fused" /><el-option label="仅关键词" value="keyword" /><el-option label="仅向量" value="vector" /></el-select></div>
      <div class="diag-option diag-option-top-k"><label for="diag-top-k">返回条数</label><el-input-number id="diag-top-k" v-model="topK" :min="1" :max="candidateMax" :step="1" controls-position="right" aria-label="检验返回条数" /></div>
    </div>
    <template v-if="dataset === 'builtin'">
      <el-alert v-if="loadError" :title="loadError" type="error" :closable="false" class="diag-alert"><el-button text @click="loadCases">重新加载案例</el-button></el-alert>
      <p v-if="casesLoading" role="status" class="diag-hint">正在加载内置案例…</p>
      <div class="diag-cases" role="group" aria-label="内置检验案例">
        <button v-for="item in cases" :key="item.id" type="button" class="diag-case" :class="{ selected: selectedCase === item.id }" :disabled="dirty" :aria-pressed="selectedCase === item.id" :aria-label="`运行案例：${item.name}`" @click="run(item)">
          <b>{{ item.name }}</b><span>{{ busy && selectedCase === item.id ? '检验中' : caseKinds[item.kind] || '案例' }}</span>
        </button>
      </div>
      <div v-if="currentCase" class="diag-case-current"><p>{{ currentCase.question }}</p><span>{{ currentCase.description }}</span></div>
    </template>
    <form v-else class="diag-query" @submit.prevent="run()">
      <label for="diag-question">检验问题</label>
      <el-input id="diag-question" v-model="question" type="textarea" :rows="2" maxlength="500" show-word-limit placeholder="例如：这份资料如何实现检索与重排？" aria-label="当前资料检验问题" />
      <el-button type="primary" :loading="busy" :disabled="dirty || !question.trim()" native-type="submit">运行检验</el-button>
    </form>
    <p class="diag-footnote">{{ dataset === 'builtin' ? '合成资料独立使用，图谱与 Wiki 不参与。' : '检索当前资料，请核对命中原文。' }}仅检索，不生成回答；已启用的模型步骤会调用模型。</p>
    <div v-if="busy" class="diag-running" role="status"><span>正在召回、排序并组装检索上下文…</span><el-button text @click="cancel">取消等待</el-button></div>
    <el-alert v-if="error" :title="error" type="error" :closable="false" show-icon class="diag-alert" role="alert"><el-button v-if="lastRequest" text :disabled="dirty" @click="run(null, lastRequest)">重试检验</el-button></el-alert>

    <div v-if="result" ref="reportElement" class="diag-report" aria-live="polite">
      <div class="diag-report-heading"><b>检验结果</b><span>{{ Math.round(result.elapsedMs || 0) }} ms</span></div>
      <p v-if="result.dataset !== 'builtin'" class="diag-result-question">{{ result.question }}</p>
      <el-alert v-if="resultStale" title="设置已更新，此结果使用此前的参数，请重新检验后比较。" type="info" :closable="false" class="diag-alert" />
      <div class="diag-metrics">
        <div><span>返回片段</span><b>{{ result.hits?.length ?? 0 }}</b></div>
        <div><span>来源覆盖</span><b>{{ diagnosticPercent(result.metrics?.recallAtK) }}</b></div>
        <div><span>MRR</span><b>{{ diagnosticNumber(result.metrics?.mrr) }}</b></div>
        <div><span>上下文字数</span><b>{{ result.context?.chars ?? reportContext().length }}<small> / {{ result.context?.budget ?? config.totalChars ?? 7000 }}</small></b></div>
      </div>
      <details class="diag-notes">
        <summary>指标说明与提示<span v-if="hasExpected">命中 {{ result.metrics.matchedExpected ?? 0 }}/{{ result.metrics.totalExpected }} 个来源</span><span v-else>指标不适用</span></summary>
        <p v-if="hasExpected" class="diag-hint">来源覆盖是预期来源的召回率，MRR 按首个正确来源的排名计算；只反映本次小型合成案例，不代表整个知识库或回答质量。</p>
        <p v-if="hasExpected" class="diag-hint">预期来源：{{ (result.metrics.expectedSourceRefs || result.metrics.expected || []).join('、') }}</p>
        <p v-else class="diag-hint">{{ result.dataset === 'builtin' ? `此案例没有预期来源，误命中 ${result.metrics?.unexpectedHits ?? result.hits?.length ?? 0} 条；不计算成功率。` : '当前资料没有人工标注的预期来源，召回率和 MRR 不适用。' }}</p>
        <ul v-if="result.warnings?.length" class="diag-warnings"><li v-for="(warning, index) in result.warnings" :key="index">{{ typeof warning === 'string' ? warning : warning.message || warning.reason }}</li></ul>
      </details>
      <el-tabs v-model="resultTab" class="diag-results" aria-label="检验结果明细">
        <el-tab-pane label="命中片段" name="hits">
          <p v-if="!result.hits?.length" class="diag-empty" role="status">没有命中片段。请查看阶段状态，区分“没有相关资料”和“某通道没有启用”。</p>
          <article v-for="(hit, index) in result.hits || []" :key="diagnosticHitKey(hit)" class="diag-hit">
            <span class="diag-rank" :aria-label="`排名 ${index + 1}`">{{ index + 1 }}</span>
            <div class="diag-hit-main">
              <b class="diag-hit-title">{{ hit.title }}</b>
              <div class="diag-hit-meta"><span>{{ hitSource(hit) }} · {{ hitChunk(hit) }}</span><span>排序分 {{ diagnosticNumber(hit.score, 5) }}</span><span v-if="hit.channels?.length" class="diag-channels">{{ hit.channels.map(channel => channelNames[channel] || channel).join(' / ') }}</span></div>
              <p class="diag-hit-preview">{{ hit.text }}</p>
              <details class="diag-hit-detail"><summary>查看完整原文</summary><pre class="diag-hit-text" tabindex="0" role="region" :aria-label="`${hit.title} 的完整原文`">{{ hit.text }}</pre><p v-if="hit.graphRelations?.length" class="diag-hint">关联路径：{{ hit.graphRelations.join('；') }}</p></details>
            </div>
          </article>
        </el-tab-pane>
        <el-tab-pane label="排序对比" name="order">
          <p class="diag-hint">对比进入重排池的候选。排序分仍是原通道或融合分，不是正确概率；跳过重排时名次保持原样。</p>
          <el-table :data="sortedHits" size="small" class="diag-order" empty-text="没有可比较的候选">
            <el-table-column label="来源" min-width="180"><template #default="{ row }">{{ row.title }}<div class="diag-hint">{{ hitSource(row) }} · {{ hitChunk(row) }}</div></template></el-table-column>
            <el-table-column label="重排前" width="76"><template #default="{ row }">{{ row.beforeRank ?? '—' }}</template></el-table-column>
            <el-table-column label="重排后" prop="afterRank" width="76" />
            <el-table-column label="变化" width="76"><template #default="{ row }">{{ rankChange(row) }}</template></el-table-column>
          </el-table>
        </el-tab-pane>
        <el-tab-pane label="注入上下文" name="context">
          <p class="diag-hint">以下是本次检验组装的检索材料。自动回答还会加入问题、对话历史及当前笔记背景。</p>
          <pre v-if="reportContext()" class="diag-context" tabindex="0" role="region" aria-label="本次检索上下文">{{ reportContext() }}</pre>
          <p v-else class="diag-empty" role="status">本次没有可注入的上下文。</p>
        </el-tab-pane>
        <el-tab-pane name="process">
          <template #label>检索过程<span v-if="processIssues" class="diag-process-badge">{{ processIssues }}</span></template>
          <ol class="diag-stages" aria-label="检索阶段数据">
            <li v-for="stage in result.stages || []" :key="stage.id" class="diag-stage" :class="{ 'stage-error': ['error', 'failed'].includes(stage.status), 'stage-degraded': ['degraded', 'fallback'].includes(stage.status) }">
              <div class="diag-stage-heading"><b>{{ stage.label || channelNames[stage.id] || stage.id }}</b><span class="diag-stage-status">{{ stageStatus(stage) }}</span><span class="diag-stage-stats">{{ stageCount(stage) }} 项 · {{ Math.round(stage.elapsedMs || 0) }} ms</span></div><p v-if="stage.reason">{{ stage.reason }}</p>
            </li>
          </ol>
          <p v-if="!result.stages?.length" class="diag-empty">没有阶段数据。</p>
        </el-tab-pane>
      </el-tabs>
    </div>
  </section>
</template>

<style scoped>
.knowledge-diagnostics { min-width: 0; }
.diag-hint, .diag-footnote { color: var(--app-text-3); font-size: var(--font-caption); line-height: 1.6; margin: 6px 0 10px; overflow-wrap: anywhere; }
.diag-alert { margin: 10px 0; }
.diag-options { display: flex; flex-wrap: wrap; align-items: end; gap: 10px 12px; margin-bottom: 12px; }
.diag-option { min-width: 0; }
.diag-option-dataset { flex: 1 1 180px; }
.diag-option-mode { flex: 1 1 140px; }
.diag-option-top-k { flex: 0 0 112px; }
.diag-options label, .diag-query > label { display: block; margin-bottom: 5px; font-size: var(--font-caption); color: var(--app-text-2); }
.diag-options :deep(.el-input-number), .diag-options :deep(.el-select) { width: 100%; }
.diag-options :deep(.el-select), .diag-options :deep(.el-input-number), .diag-query > .el-button { --el-component-size: 36px; }
.diag-options :deep(.el-select__wrapper) { min-height: 36px; }
.diag-options :deep(.el-input-number) { height: 36px; }
.diag-cases { display: flex; flex-wrap: wrap; gap: 6px; }
.diag-case { min-width: 0; display: inline-flex; align-items: center; justify-content: center; gap: 7px; min-height: 32px; padding: 5px 10px; border: 1px solid var(--app-border); border-radius: var(--radius); background: var(--app-card); color: var(--app-text-2); font: inherit; text-align: left; cursor: pointer; }
.diag-case:hover:not(:disabled), .diag-case.selected { border-color: var(--app-brand); background: var(--app-brand-soft); color: var(--app-brand-deep); }
.diag-case:disabled { cursor: default; opacity: .6; }
.diag-case b { font-size: var(--font-caption); font-weight: 550; white-space: nowrap; }.diag-case > span { font-size: 11px; color: var(--app-text-3); white-space: nowrap; }
.diag-case-current { margin: 10px 0 0; }
.diag-case-current p, .diag-result-question { margin: 0 0 4px; font-size: var(--font-body); color: var(--app-text-2); line-height: 1.6; overflow-wrap: anywhere; }
.diag-case-current > span { display: block; color: var(--app-text-3); font-size: var(--font-caption); line-height: 1.6; overflow-wrap: anywhere; }
.diag-footnote { margin: 10px 0 0; }
.diag-query > .el-button { margin: 8px 0 0; height: 36px; }
.diag-running { display: flex; justify-content: space-between; align-items: center; gap: 8px; flex-wrap: wrap; font-size: var(--font-caption); color: var(--app-brand-deep); margin: 12px 0; }
.diag-report { margin-top: 16px; padding-top: 12px; border-top: 1px solid var(--app-border); }
.diag-report-heading { display: flex; justify-content: space-between; align-items: center; gap: 12px; margin-bottom: 8px; font-size: var(--font-body); }.diag-report-heading > span { white-space: nowrap; color: var(--app-text-3); font-size: var(--font-caption); }
.diag-result-question { margin-bottom: 8px; }
.diag-metrics { display: grid; grid-template-columns: repeat(4, minmax(0, 1fr)); gap: 10px 16px; margin-bottom: 8px; }
.diag-metrics > div { min-width: 0; }
.diag-metrics span { display: block; font-size: var(--font-caption); color: var(--app-text-3); }.diag-metrics b { display: block; margin-top: 3px; font-size: 18px; font-weight: 600; line-height: 1.4; overflow-wrap: anywhere; }.diag-metrics small { display: inline-block; font-size: 11px; font-weight: 400; color: var(--app-text-3); }
.diag-notes { margin-bottom: 8px; }
.diag-notes > summary, .diag-hit-detail > summary { width: fit-content; max-width: 100%; cursor: pointer; font-size: var(--font-caption); color: var(--app-brand-deep); line-height: 1.6; border-radius: 3px; }
.diag-notes > summary > span { margin-left: 10px; color: var(--app-text-3); }
.diag-notes .diag-hint { margin: 7px 0; }.diag-warnings { padding-left: 18px; margin: 6px 0; color: var(--app-text-3); font-size: var(--font-caption); line-height: 1.6; overflow-wrap: anywhere; }
.diag-results { min-width: 0; margin-top: 8px; }.diag-results :deep(.el-tabs__item) { padding: 0 12px; height: 36px; font-size: var(--font-body); }.diag-results :deep(.el-tabs__header) { margin-bottom: 4px; max-width: 100%; }.diag-results :deep(.el-tabs__content) { min-width: 0; }
.diag-process-badge { display: inline-flex; align-items: center; justify-content: center; min-width: 16px; height: 16px; padding: 0 3px; box-sizing: border-box; margin-left: 5px; border-radius: 8px; background: var(--app-brand-soft); color: var(--app-brand-deep); font-size: 10px; }
.diag-hit { display: flex; align-items: flex-start; gap: 10px; padding: 12px 0; border-bottom: 1px solid var(--app-border-weak); min-width: 0; }.diag-hit:last-child { border-bottom: 0; }
.diag-hit-main { flex: 1; min-width: 0; }.diag-hit-title { display: block; font-size: var(--font-body); line-height: 1.5; font-weight: 550; overflow-wrap: anywhere; }.diag-rank { display: flex; align-items: center; justify-content: center; flex: 0 0 24px; height: 24px; border-radius: 5px; background: var(--app-brand-soft); color: var(--app-brand-deep); font-size: var(--font-caption); }
.diag-hit-meta { display: flex; gap: 3px 10px; flex-wrap: wrap; margin: 4px 0 5px; color: var(--app-text-3); font-size: 11px; line-height: 1.6; overflow-wrap: anywhere; }.diag-channels { color: var(--app-brand-deep); }
.diag-hit-preview { display: -webkit-box; -webkit-box-orient: vertical; -webkit-line-clamp: 3; overflow: hidden; white-space: pre-wrap; overflow-wrap: anywhere; margin: 0 0 4px; font-size: var(--font-caption); line-height: 1.6; color: var(--app-text-2); }
.diag-hit-text, .diag-context { box-sizing: border-box; width: 100%; font-family: inherit; font-size: var(--font-code); line-height: var(--lh-code); white-space: pre-wrap; overflow-wrap: anywhere; margin: 8px 0 0; background: var(--app-bg); border-radius: var(--radius); padding: 10px; color: var(--app-text-2); max-height: 260px; overflow-y: auto; }
.diag-context { max-height: 440px; }.diag-empty { color: var(--app-text-3); font-size: var(--font-body); padding: 12px 0; }
.diag-stages { list-style: none; padding: 0; margin: 0; }.diag-stage { min-width: 0; padding: 10px 0; border-bottom: 1px solid var(--app-border-weak); font-size: var(--font-caption); }.diag-stage:last-child { border-bottom: 0; }
.diag-stage-heading { display: flex; align-items: baseline; gap: 8px; flex-wrap: wrap; }.diag-stage b { font-weight: 550; color: var(--app-text-2); }.diag-stage-status, .diag-stage-stats, .diag-stage p { color: var(--app-text-3); }.diag-stage-stats { margin-left: auto; }.diag-stage p { margin: 4px 0 0; line-height: 1.6; overflow-wrap: anywhere; }.stage-error .diag-stage-status { color: var(--el-color-danger); }.stage-degraded .diag-stage-status { color: var(--el-color-warning); }
.diag-case:focus-visible, .diag-notes > summary:focus-visible, .diag-hit-detail > summary:focus-visible, .diag-context:focus-visible, .diag-hit-text:focus-visible { outline: 2px solid var(--app-brand); outline-offset: 2px; }
.diag-options :deep(.el-select__wrapper.is-focused), .diag-options :deep(.el-input__wrapper.is-focus) { box-shadow: 0 0 0 1px var(--app-brand) inset; }
.diag-results :deep(.el-tabs__item:focus-visible) { outline: 2px solid var(--app-brand); outline-offset: -2px; border-radius: 3px; }
@media (max-width: 560px) { .diag-option-dataset { flex-basis: 100%; }.diag-option-mode { flex-basis: 120px; }.diag-metrics { grid-template-columns: repeat(2, minmax(0, 1fr)); }.diag-results :deep(.el-tabs__item) { padding: 0 9px; font-size: var(--font-caption); } }
@media (max-width: 360px) { .diag-case { padding: 5px 8px; gap: 5px; }.diag-stage-stats { margin-left: 0; width: 100%; }.diag-notes > summary > span { margin-left: 5px; } }
</style>
