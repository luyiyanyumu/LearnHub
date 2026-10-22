<script setup>
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import { ArrowRight, Cpu, Refresh, Upload } from '@element-plus/icons-vue'
import { ElMessage } from 'element-plus'
import { knowledgeModelApi } from '../api/knowledgeModel.js'
import { createKnowledgeModelRefresh, formatKnowledgeDrop, formatKnowledgeScore,
  knowledgeModelAnswerView, knowledgeModelJobs, knowledgeModelScores } from '../utils/knowledgeModelView.js'

const status = ref(null)
const jobsPayload = ref(null)
const refreshing = ref(false)
const initialLoading = ref(true)
const loadError = ref('')
const actionError = ref('')
const syncedAt = ref('')
const savingSetting = ref(false)
const retrying = ref(null)
const filter = ref('all')
const selectedKey = ref(null)
const drawerOpen = ref(false)
const question = ref('')
const querying = ref(false)
const queryError = ref('')
const answer = ref(null)
const askedQuestion = ref('')
const fileOptions = ref([])
const fileId = ref(null)
const filesLoading = ref(false)
const fileError = ref('')
const enqueueing = ref(false)
let disposed = false
let timer = null
let queryController = null
let filesController = null
let filesGeneration = 0

const service = computed(() => status.value?.service || {})
const online = computed(() => status.value?.online === true && !loadError.value)
const jobs = computed(() => knowledgeModelJobs(jobsPayload.value, service.value.progress))
const visibleJobs = computed(() => jobs.value.filter(job => filter.value === 'all'
  || (filter.value === 'active' && ['pending', 'retry', 'submitted', 'queued', 'running', 'evaluating'].includes(job.view.key))
  || (filter.value === 'attention' && ['failed', 'rejected', 'unvalidated'].includes(job.status))
  || job.status === filter.value))
const selectedJob = computed(() => jobs.value.find(job => job.key === selectedKey.value))
const selectedScores = computed(() => knowledgeModelScores(selectedJob.value?.result))
const latestCompleted = computed(() => jobs.value.find(job => job.result))
const latestScores = computed(() => knowledgeModelScores(latestCompleted.value?.result))
const answerView = computed(() => knowledgeModelAnswerView(answer.value))
const pendingCount = computed(() => jobs.value.filter(job => ['pending', 'retry', 'submitted', 'queued'].includes(job.status)).length)
const activeVersion = computed(() => service.value.active_version === 'base'
  ? '基础模型 · 尚未发布知识版本' : service.value.active_version || '尚未读取')
const progress = computed(() => service.value.progress || {})
const progressLabel = computed(() => ({ idle: '等待任务', loading_training: '正在加载训练模型',
  loading_baseline: '正在加载当前模型以进行训练前评测', loading_candidate: '正在重载已保存的候选模型以进行评测',
  baseline_evaluation: '训练前闭卷评测', training: '正在增量训练', candidate_evaluation: '训练后闭卷评测',
  loading_inference: '正在加载查询模型' }[progress.value.phase] || '正在处理任务'))
const progressPercent = computed(() => {
  const p = progress.value
  const completed = p.phase === 'training' ? p.step : p.completed
  const total = p.phase === 'training' ? p.max_steps : p.total
  return total > 0 ? Math.min(100, Math.round((completed || 0) / total * 100)) : null
})

const refresher = createKnowledgeModelRefresh({
  load: signal => Promise.all([knowledgeModelApi.status(signal), knowledgeModelApi.jobs(signal)]),
  onResult([nextStatus, nextJobs]) {
    if (savingSetting.value && status.value) nextStatus = { ...nextStatus, enabled: status.value.enabled }
    status.value = nextStatus
    jobsPayload.value = nextJobs
    loadError.value = ''
    syncedAt.value = new Date().toLocaleTimeString('zh-CN', { hour12: false })
    initialLoading.value = false
  },
  onError(error) { loadError.value = error.message; initialLoading.value = false },
  onBusy(value) { refreshing.value = value },
})

function refresh() { return refresher.refresh() }
async function poll() {
  if (disposed) return
  if (!document.hidden && !savingSetting.value) await refresh()
  if (!disposed) timer = setTimeout(poll, 5000)
}

async function changeEnabled(enabled) {
  savingSetting.value = true
  actionError.value = ''
  try {
    const next = await knowledgeModelApi.settings(enabled)
    if (disposed) return
    status.value = next
    ElMessage.success(enabled ? '后续新增上传将自动入队' : '已关闭新增上传自动入队，已有任务继续执行')
  } catch (error) { if (!disposed) actionError.value = error.message }
  finally { if (!disposed) savingSetting.value = false }
}

function openJob(job) { selectedKey.value = job.key; drawerOpen.value = true }
function canRetry(job) { return job?.outboxId != null && ['retry', 'failed', 'rejected', 'unvalidated'].includes(job.status) }
async function retryJob(job) {
  if (!canRetry(job) || retrying.value !== null) return
  retrying.value = job.outboxId
  actionError.value = ''
  try {
    await knowledgeModelApi.retry(job.outboxId)
    if (!disposed) { ElMessage.success('任务已重新排队'); await refresh() }
  } catch (error) { if (!disposed) actionError.value = error.message }
  finally { if (!disposed) retrying.value = null }
}

async function searchFiles(kw = '') {
  const generation = ++filesGeneration
  filesController?.abort()
  filesController = new AbortController()
  filesLoading.value = true
  fileError.value = ''
  try {
    const result = await knowledgeModelApi.files(kw, filesController.signal)
    if (!disposed && generation === filesGeneration) fileOptions.value = result?.list || []
  } catch (error) {
    if (!disposed && generation === filesGeneration && error.code !== 'ERR_CANCELED') fileError.value = error.message
  } finally { if (!disposed && generation === filesGeneration) filesLoading.value = false }
}

async function enqueueFile() {
  if (!fileId.value || enqueueing.value) return
  enqueueing.value = true
  actionError.value = ''
  try {
    await knowledgeModelApi.enqueue(fileId.value)
    if (!disposed) { ElMessage.success('资料已入队；相同正文会自动去重'); fileId.value = null; await refresh() }
  } catch (error) { if (!disposed) actionError.value = error.message }
  finally { if (!disposed) enqueueing.value = false }
}

async function queryModel() {
  const text = question.value.trim()
  if (!text || querying.value || !online.value) return
  queryController = new AbortController()
  querying.value = true
  queryError.value = ''
  answer.value = null
  askedQuestion.value = text
  try {
    const result = await knowledgeModelApi.query({ question: text, max_tokens: 128 }, queryController.signal)
    if (!disposed) answer.value = result
  } catch (error) {
    if (!disposed && error.code !== 'ERR_CANCELED') queryError.value = error.status === 503
      ? `本地模型暂不可用，可能正在训练或加载模型。请稍后重试。${error.message ? ' ' + error.message : ''}`
      : error.message
  } finally { if (!disposed) querying.value = false }
}

function formatDate(value) {
  if (!value) return '时间未记录'
  const date = new Date(value)
  return Number.isNaN(date.getTime()) ? value : date.toLocaleString('zh-CN', { hour12: false })
}
function shortVersion(value) { return value && value !== 'base' ? value.slice(0, 12) : value || '—' }
function metricSummary(group) { return group?.total ? `${group.correct} / ${group.total} 题` : '没有可核验题目' }
function refusalSummary(metric, mistaken = false) {
  return metric.covered ? `${metric.count} / ${metric.total} 题${mistaken ? '误拒' : '正确拒答'}` : '没有可核验题目'
}

onMounted(poll)
onBeforeUnmount(() => {
  disposed = true
  clearTimeout(timer)
  refresher.dispose()
  queryController?.abort()
  filesController?.abort()
})
</script>

<template>
  <div class="page model-page">
    <header class="model-header">
      <div>
        <div class="page-kicker"><el-icon><Cpu /></el-icon> 本机训练 · 参数知识</div>
        <h1 class="page-h2">本地知识模型</h1>
        <p class="page-description">上传后增量学习，用闭卷评测观察新知识与旧知识，再发布可调用的版本。</p>
      </div>
      <el-button :icon="Refresh" :loading="refreshing" @click="refresh">刷新状态</el-button>
    </header>

    <el-alert v-if="loadError" class="page-notice" type="error" title="状态读取失败" :description="loadError + '；已显示的内容可能不是最新状态，可手动刷新。'" :closable="false" show-icon />
    <el-alert v-else-if="status && !status.online" class="page-notice" type="warning" title="本地训练服务未连接" :description="(status.error || '请启动本地知识模型服务。') + ' 上传队列保存在 LearnHub，连接恢复后自动继续提交。'" :closable="false" show-icon />
    <el-alert v-else-if="jobsPayload && !jobsPayload.online" class="page-notice" type="warning" title="任务状态暂未同步" :description="jobsPayload.error || '训练服务的任务列表暂时无法读取，稍后会自动重试。'" :closable="false" show-icon />
    <el-alert v-if="actionError || status?.outbox?.enqueueError" class="page-notice" type="error" title="任务操作需要处理" :description="actionError || status.outbox.enqueueError" :closable="false" show-icon />

    <section class="model-card connection-card" aria-labelledby="connection-title">
      <div class="connection-top">
        <div class="service-heading">
          <span class="service-dot" :class="{ online }" aria-hidden="true" />
          <h2 id="connection-title">{{ initialLoading ? '正在读取状态' : loadError ? '连接待确认' : online ? '本地服务在线' : '本地服务离线' }}</h2>
          <el-tag size="small" effect="plain" type="info">验证版</el-tag>
        </div>
        <span class="sync-time">{{ syncedAt ? `最近同步 ${syncedAt}` : '尚未同步' }}</span>
      </div>
      <div class="model-identity">
        <div><span class="field-label">基础模型</span><strong>{{ service.base_model || '尚未读取' }}</strong></div>
        <div><span class="field-label">当前调用版本</span><strong class="version-label">{{ activeVersion }}</strong></div>
      </div>
      <div class="auto-train-row">
        <div><label id="auto-train-label">新增上传自动训练</label><p>正文抽取完成后自动入队；关闭后已有任务继续执行。</p></div>
        <el-switch :model-value="status?.enabled === true" :loading="savingSetting" :disabled="!status || !!loadError || refreshing" aria-labelledby="auto-train-label" @change="changeEnabled" />
      </div>
      <div v-if="online && service.gpu_busy" class="current-progress" role="status">
        <span>{{ progressLabel }}</span>
        <span v-if="progress.phase === 'training' && progress.max_steps">{{ progress.step || 0 }} / {{ progress.max_steps }} 步</span>
        <span v-else-if="progress.total">{{ progress.completed || 0 }} / {{ progress.total }} 题</span>
        <el-progress v-if="progressPercent !== null" :percentage="progressPercent" :stroke-width="6" :show-text="false" />
        <small>训练与查询共用显卡，训练期间查询可能暂不可用。</small>
      </div>
    </section>

    <section class="score-grid" aria-label="最近评测概况">
      <div class="score-card"><span>等待处理</span><strong>{{ initialLoading ? '—' : pendingCount }}</strong><small>本地队列与训练队列</small></div>
      <div class="score-card"><span>新知识闭卷成绩</span><strong>{{ formatKnowledgeScore(latestScores.newScore) }}</strong><small>{{ metricSummary(latestCompleted?.result?.candidate?.new) }}</small></div>
      <div class="score-card"><span>旧知识闭卷成绩</span><strong>{{ formatKnowledgeScore(latestScores.oldScore) }}</strong><small>{{ metricSummary(latestCompleted?.result?.candidate?.old) }}</small></div>
      <div class="score-card"><span>旧知识下降</span><strong class="drop-value" :class="{ regressed: latestScores.forgetting > 0 }">{{ formatKnowledgeDrop(latestScores.forgetting) }}</strong><small>训练前减训练后；正值表示退步</small></div>
      <div class="score-card"><span>未知题拒答率</span><strong>{{ formatKnowledgeScore(latestScores.unknownRefusal.rate) }}</strong><small>{{ refusalSummary(latestScores.unknownRefusal) }}</small></div>
      <div class="score-card"><span>已知题误拒率</span><strong :class="{ regressed: latestScores.knownRefusal.rate > 0 }">{{ formatKnowledgeScore(latestScores.knownRefusal.rate) }}</strong><small>{{ refusalSummary(latestScores.knownRefusal, true) }}</small></div>
    </section>
    <p class="coverage-note">成绩来自最近一次已完成任务，仅覆盖有标准答案的评测题；未发布候选的成绩不代表当前调用版本。训练完成不代表资料已全部掌握，没有新知识评测题的候选版本不会自动发布。</p>
    <p class="coverage-note">拒答表现仅覆盖有限题目，不能保证所有知识库外的问题都不会乱答。旧版本没有拒答评测指标时显示为未覆盖。</p>

    <div class="workspace-grid">
      <section class="model-card jobs-card" aria-labelledby="jobs-title">
        <div class="section-header">
          <div><h2 id="jobs-title">训练与评测任务</h2><p>排队 → 训练 → 闭卷评测 → 通过后发布</p></div>
          <el-select v-model="filter" class="job-filter" aria-label="筛选训练任务">
            <el-option label="全部任务" value="all" /><el-option label="处理中" value="active" />
            <el-option label="需要关注" value="attention" /><el-option label="已发布" value="published" />
          </el-select>
        </div>
        <div v-if="initialLoading" class="empty-state" role="status">正在读取训练任务…</div>
        <div v-else-if="!visibleJobs.length" class="empty-state">
          <el-icon :size="28"><Upload /></el-icon>
          <p>{{ jobs.length ? '当前筛选下没有任务' : loadError ? '任务暂时无法读取' : '还没有训练任务' }}</p>
          <span v-if="!jobs.length && !loadError">开启自动训练后上传资料，或将已有资料加入队列。</span>
        </div>
        <ul v-else class="job-list">
          <li v-for="job in visibleJobs" :key="job.key">
            <button class="job-row" type="button" @click="openJob(job)">
              <span class="job-main"><strong>{{ job.title }}</strong><span>{{ formatDate(job.createdAt) }}</span></span>
              <el-tag :type="job.view.type" size="small" effect="light">{{ job.view.label }}</el-tag>
              <el-icon class="job-arrow"><ArrowRight /></el-icon>
            </button>
          </li>
        </ul>
        <div class="enqueue-panel">
          <label id="existing-file-label">将已有资料加入训练</label>
          <div class="enqueue-controls">
            <el-select v-model="fileId" filterable remote clearable :remote-method="searchFiles" :loading="filesLoading" placeholder="搜索并选择资料" aria-labelledby="existing-file-label" @visible-change="value => value && searchFiles()">
              <el-option v-for="file in fileOptions" :key="file.id" :label="file.title || file.originName || file.originalName || file.name || `资料 ${file.id}`" :value="file.id" />
            </el-select>
            <el-button :disabled="!fileId || enqueueing" :loading="enqueueing" @click="enqueueFile">加入队列</el-button>
          </div>
          <p v-if="fileError" class="inline-error" role="alert">{{ fileError }}</p>
          <p v-else>同一份资料的相同正文会去重；正文尚未抽取时请先在资料库完成处理。</p>
        </div>
      </section>

      <section class="model-card query-card" aria-labelledby="query-title">
        <div class="section-header"><div><h2 id="query-title">向本地模型提问</h2><p>只读取模型参数，观察训练后的真实回答。</p></div></div>
        <form @submit.prevent="queryModel">
          <label class="query-label" for="knowledge-model-question">验证问题</label>
          <el-input id="knowledge-model-question" v-model="question" type="textarea" :rows="4" maxlength="1600" show-word-limit resize="vertical" placeholder="换一种问法，检查刚上传的知识；也可以问以前的资料。" :disabled="querying" @keydown.ctrl.enter.prevent="queryModel" @keydown.meta.enter.prevent="queryModel" />
          <div class="query-actions"><span>{{ querying ? '首次加载可能较慢，正在等待本地模型…' : 'Ctrl / ⌘ + Enter 提问' }}</span><el-button type="primary" native-type="submit" :loading="querying" :disabled="!online || !question.trim() || querying">提问</el-button></div>
        </form>
        <p v-if="!online" class="query-hint">连接本地训练服务后可提问。</p>
        <p v-else-if="service.active_version === 'base'" class="query-hint">当前使用基础模型，尚没有通过评测的知识版本。</p>
        <el-alert v-if="queryError" type="warning" :description="queryError" title="查询暂未完成" :closable="false" show-icon />
        <div v-if="answer" class="model-answer" aria-live="polite">
          <div class="answer-meta"><el-tag size="small" effect="plain" :type="answerView.type">{{ answerView.label }}</el-tag><span>版本 {{ shortVersion(answer.model_version) }}</span><el-tag v-if="answerView.validated" size="small" effect="plain" type="info">拒答经有限题验证</el-tag></div>
          <p class="asked-question">{{ askedQuestion }}</p>
          <div class="answer-text">{{ answer.answer || '模型返回了空答案。' }}</div>
          <p class="answer-scope">{{ answer.retrieval_used === false ? '本次回答未使用资料检索。' : '检索使用情况未确认。' }}请结合闭卷成绩判断可靠程度。</p>
          <p class="answer-scope">{{ answerView.notice }}</p>
        </div>
      </section>
    </div>

    <el-drawer v-model="drawerOpen" title="任务详情" size="min(640px, 100vw)" append-to-body>
      <div v-if="selectedJob" class="job-detail">
        <h2>{{ selectedJob.title }}</h2>
        <el-tag :type="selectedJob.view.type">{{ selectedJob.view.label }}</el-tag>
        <dl class="detail-fields"><dt>提交时间</dt><dd>{{ formatDate(selectedJob.createdAt) }}</dd><dt>任务编号</dt><dd>{{ selectedJob.remoteJobId || `本地队列 ${selectedJob.outboxId}` }}</dd><dt>版本来源</dt><dd>{{ selectedJob.result?.parent_version || '尚未生成候选版本' }}</dd></dl>
        <el-alert v-if="selectedJob.error" type="error" title="执行信息" :description="selectedJob.error" :closable="false" show-icon />
        <el-alert v-if="selectedJob.result?.reasons?.length" type="warning" title="候选版本未发布" :description="selectedJob.result.reasons.join('；')" :closable="false" show-icon />
        <template v-if="selectedJob.result">
          <h3>闭卷评测</h3>
          <div class="evaluation-table-wrap"><table class="evaluation-table"><thead><tr><th scope="col">评测范围</th><th scope="col">训练前</th><th scope="col">训练后</th><th scope="col">题数</th></tr></thead><tbody>
            <tr><th scope="row">新知识</th><td>{{ formatKnowledgeScore(selectedJob.result.baseline?.new?.accuracy) }}</td><td>{{ formatKnowledgeScore(selectedScores.newScore) }}</td><td>{{ selectedJob.result.candidate?.new?.total || 0 }}</td></tr>
            <tr><th scope="row">旧知识</th><td>{{ formatKnowledgeScore(selectedScores.oldBefore) }}</td><td>{{ formatKnowledgeScore(selectedScores.oldScore) }}</td><td>{{ selectedJob.result.candidate?.old?.total || 0 }}</td></tr>
          </tbody></table></div>
          <p class="detail-note">旧知识下降：{{ formatKnowledgeDrop(selectedScores.forgetting) }}。正值表示旧题成绩下降；无旧题时无法判断是否遗忘。</p>
          <h3>拒答表现</h3>
          <div class="evaluation-table-wrap"><table class="evaluation-table"><thead><tr><th scope="col">评测指标</th><th scope="col">训练后</th><th scope="col">计数</th></tr></thead><tbody>
            <tr><th scope="row">未知题拒答率</th><td>{{ formatKnowledgeScore(selectedScores.unknownRefusal.rate) }}</td><td>{{ refusalSummary(selectedScores.unknownRefusal) }}</td></tr>
            <tr><th scope="row">已知题误拒率</th><td>{{ formatKnowledgeScore(selectedScores.knownRefusal.rate) }}</td><td>{{ refusalSummary(selectedScores.knownRefusal, true) }}</td></tr>
          </tbody></table></div>
          <p class="detail-note">{{ selectedJob.result.abstention_policy === 'v1' ? '拒答评测仅覆盖有限题目，不能保证所有知识库外的问题都不会乱答。' : '此版本未提供拒答评测指标，拒答能力未覆盖。' }}误拒率统计已知题回答规范“不知道”的比例；其他拒绝措辞计为答错，但未计入此项。</p>
          <h3>本轮训练范围</h3>
          <dl class="detail-fields"><dt>新资料样本</dt><dd>{{ selectedJob.result.coverage?.selected_samples?.new ?? '—' }} / {{ selectedJob.result.coverage?.available_samples?.new ?? '—' }}</dd><dt>历史回放样本</dt><dd>{{ selectedJob.result.coverage?.selected_samples?.old ?? '—' }} / {{ selectedJob.result.coverage?.available_samples?.old ?? '—' }}</dd><dt>训练步数</dt><dd>{{ selectedJob.result.steps ?? '—' }}</dd><dt>训练耗时</dt><dd>{{ Number.isFinite(selectedJob.result.training_seconds) ? selectedJob.result.training_seconds.toFixed(1) + ' 秒' : '—' }}</dd><dt>峰值分配显存</dt><dd>{{ Number.isFinite(selectedJob.result.peak_allocated_vram_gib) ? selectedJob.result.peak_allocated_vram_gib.toFixed(2) + ' GiB' : '—' }}</dd></dl>
          <p class="detail-note">{{ selectedJob.result.coverage?.limited_coverage ? '本轮只采样了部分训练样本。' : '' }}评测结论仅适用于已测试的事实，不代表整份资料已掌握。</p>
        </template>
        <p v-else class="detail-note">训练和评测结果尚未生成。已提交仅表示进入训练服务，还需要等待实际执行和评测。</p>
        <el-button v-if="canRetry(selectedJob)" type="primary" :loading="retrying === selectedJob.outboxId" :disabled="retrying !== null" @click="retryJob(selectedJob)">重新排队</el-button>
        <p v-else-if="['failed', 'rejected', 'unvalidated'].includes(selectedJob.status) && selectedJob.outboxId == null" class="detail-note">这是直接提交到训练服务的任务，可在本地训练服务重试。</p>
        <p v-if="selectedJob.status === 'unvalidated'" class="detail-note">此资料没有覆盖新知识的可核验评测题。补充标准答案与独立问法后再训练，单纯重复训练不会解决评测覆盖问题。</p>
        <p v-if="actionError" class="inline-error" role="alert">{{ actionError }}</p>
      </div>
      <p v-else class="detail-note">任务已不在当前状态列表中，请刷新后重新打开。</p>
    </el-drawer>
  </div>
</template>

<style scoped>
.model-page { max-width: 1600px; margin: 0 auto; }
.model-header { display: flex; align-items: center; justify-content: space-between; gap: var(--space-lg); margin-bottom: var(--space-lg); }
.page-kicker { display: flex; align-items: center; gap: var(--space-sm); color: var(--app-brand-deep); font-size: var(--font-caption); font-weight: 600; margin-bottom: var(--space-sm); }
.page-description, .section-header p { margin: var(--space-sm) 0 0; color: var(--app-text-2); font-size: var(--font-caption); line-height: var(--lh-caption); }
.page-notice { margin-bottom: var(--space-md); }
.model-card { min-width: 0; padding: var(--space-lg); border: 1px solid var(--app-border); border-radius: var(--radius-xl); background: var(--app-card); box-shadow: var(--shadow-sm); }
.connection-top, .service-heading, .section-header { display: flex; align-items: center; gap: var(--space-sm); }
.connection-top, .section-header { justify-content: space-between; }
.service-heading { flex-wrap: wrap; }
h2 { margin: 0; color: var(--app-text-1); font-size: 16px; line-height: 1.5; font-weight: 600; }
.service-dot { width: 8px; height: 8px; flex-shrink: 0; border-radius: 50%; background: var(--app-text-3); }
.service-dot.online { background: var(--app-brand); box-shadow: 0 0 0 4px var(--app-brand-glow); }
.sync-time { color: var(--app-text-3); font-size: var(--font-caption); white-space: nowrap; }
.model-identity { display: grid; grid-template-columns: 1fr 1fr; gap: var(--space-lg); margin: var(--space-lg) 0; }
.model-identity > div { min-width: 0; }
.field-label { display: block; font-size: var(--font-caption); color: var(--app-text-3); margin-bottom: var(--space-xs); }
.model-identity strong { font-size: var(--font-body); font-weight: 500; overflow-wrap: anywhere; }
.version-label { font-family: ui-monospace, SFMono-Regular, Consolas, monospace; }
.auto-train-row { display: flex; align-items: center; justify-content: space-between; gap: var(--space-lg); padding-top: var(--space-md); border-top: 1px solid var(--app-border-weak); }
.auto-train-row label { font-size: var(--font-body); font-weight: 500; }
.auto-train-row p, .enqueue-panel p { margin: var(--space-xs) 0 0; color: var(--app-text-2); font-size: var(--font-caption); line-height: var(--lh-caption); }
.current-progress { display: flex; flex-wrap: wrap; gap: var(--space-sm); align-items: center; margin-top: var(--space-md); padding: var(--space-md); border-radius: var(--radius); background: var(--app-brand-soft); font-size: var(--font-caption); color: var(--app-brand-deep); }
.current-progress :deep(.el-progress) { flex: 1; min-width: 80px; }
.current-progress small { flex-basis: 100%; color: var(--app-text-2); font-size: var(--font-caption); }
.score-grid { display: grid; grid-template-columns: repeat(3, minmax(0, 1fr)); gap: var(--space-md); margin-top: var(--space-lg); }
.score-card { min-width: 0; display: flex; flex-direction: column; gap: var(--space-sm); padding: var(--space-lg); background: var(--app-card); border: 1px solid var(--app-border); border-radius: var(--radius-lg); }
.score-card > span, .score-card > small { color: var(--app-text-2); font-size: var(--font-caption); line-height: var(--lh-caption); }
.score-card > strong { color: var(--app-text-1); font-size: 24px; line-height: 1.3; font-weight: 650; overflow-wrap: anywhere; }
.score-card > .drop-value { font-size: 20px; }
.score-card > .regressed { color: var(--el-color-warning); }
.coverage-note, .query-hint, .detail-note { color: var(--app-text-2); font-size: var(--font-caption); line-height: 1.8; }
.coverage-note { margin: var(--space-sm) 0 var(--space-lg); }
.workspace-grid { display: grid; grid-template-columns: minmax(0, 1.2fr) minmax(0, 1fr); gap: var(--space-lg); align-items: start; }
.section-header { align-items: flex-start; gap: var(--space-md); margin-bottom: var(--space-md); }
.job-filter { flex: 0 0 124px; width: 124px; }
.empty-state { display: flex; flex-direction: column; align-items: center; justify-content: center; min-height: 200px; padding: var(--space-lg); text-align: center; color: var(--app-text-3); font-size: var(--font-caption); line-height: 1.8; }
.empty-state .el-icon { color: var(--app-brand); }
.empty-state p { font-size: var(--font-body); color: var(--app-text-2); margin: var(--space-md) 0 var(--space-xs); }
.job-list { list-style: none; padding: 0; margin: 0; max-height: 520px; overflow-y: auto; }
.job-list li + li { border-top: 1px solid var(--app-border-weak); }
.job-row { display: flex; align-items: center; gap: var(--space-sm); width: 100%; padding: var(--space-md) var(--space-xs); color: var(--app-text-1); background: transparent; border: 0; border-radius: var(--radius); cursor: pointer; text-align: left; font: inherit; transition: background-color var(--dur-fast); }
.job-row:hover { background: var(--app-brand-soft); }
.job-main { flex: 1; min-width: 0; display: flex; flex-direction: column; gap: var(--space-xs); }
.job-main strong { overflow-wrap: anywhere; font-size: var(--font-body); font-weight: 500; }
.job-main > span { color: var(--app-text-3); font-size: var(--font-caption); }
.job-arrow { flex-shrink: 0; color: var(--app-text-3); }
.enqueue-panel { padding-top: var(--space-md); margin-top: var(--space-md); border-top: 1px solid var(--app-border-weak); }
.enqueue-panel label, .query-label { display: block; font-size: var(--font-caption); font-weight: 500; margin-bottom: var(--space-sm); }
.enqueue-controls { display: flex; gap: var(--space-sm); }
.enqueue-controls :deep(.el-select) { flex: 1; min-width: 0; }
.query-actions { display: flex; align-items: center; justify-content: space-between; gap: var(--space-sm); margin: var(--space-md) 0; }
.query-actions > span { color: var(--app-text-3); font-size: var(--font-caption); }
.query-card :deep(.el-textarea__inner) { font-size: var(--font-body); line-height: 1.8; }
.query-card :deep(.el-alert) { margin-top: var(--space-md); }
.model-answer { border-top: 1px solid var(--app-border); padding-top: var(--space-md); margin-top: var(--space-md); }
.answer-meta { display: flex; gap: var(--space-sm); flex-wrap: wrap; align-items: center; font-size: var(--font-caption); color: var(--app-text-3); }
.asked-question { color: var(--app-text-2); font-size: var(--font-caption); white-space: pre-wrap; overflow-wrap: anywhere; }
.answer-text { font-size: var(--font-body); line-height: 1.9; white-space: pre-wrap; overflow-wrap: anywhere; }
.answer-scope { color: var(--app-text-3); font-size: var(--font-caption); line-height: 1.8; margin-bottom: 0; }
.job-detail { color: var(--app-text-1); }
.job-detail h2 { margin-bottom: var(--space-sm); overflow-wrap: anywhere; }
.job-detail h3 { font-size: var(--font-body); margin: var(--space-lg) 0 var(--space-sm); }
.job-detail :deep(.el-alert) { margin: var(--space-md) 0; }
.detail-fields { display: grid; grid-template-columns: 100px minmax(0, 1fr); gap: var(--space-sm) var(--space-md); font-size: var(--font-caption); line-height: 1.8; margin: var(--space-lg) 0; }
.detail-fields dt { color: var(--app-text-3); }
.detail-fields dd { margin: 0; overflow-wrap: anywhere; }
.evaluation-table-wrap { overflow-x: auto; }
.evaluation-table { width: 100%; border-collapse: collapse; font-size: var(--font-caption); white-space: nowrap; }
.evaluation-table th, .evaluation-table td { text-align: left; padding: var(--space-sm); border-bottom: 1px solid var(--app-border); }
.evaluation-table thead { color: var(--app-text-2); background: var(--app-bg); }
.evaluation-table th { font-weight: 500; }
.inline-error { color: var(--el-color-danger) !important; font-size: var(--font-caption); line-height: 1.8; overflow-wrap: anywhere; }
@media (max-width: 1200px) { .workspace-grid { grid-template-columns: 1fr; } .score-card { padding: var(--space-md); } }
@media (max-width: 780px) { .score-grid { grid-template-columns: repeat(2, minmax(0, 1fr)); } .model-identity { grid-template-columns: 1fr; gap: var(--space-md); } }
@media (max-width: 540px) {
  .model-page { padding: var(--space-md) var(--space-sm); }
  .model-header { align-items: flex-start; flex-direction: column; gap: var(--space-md); }
  .model-card { padding: var(--space-md); }
  .connection-top { align-items: flex-start; flex-direction: column; }
  .score-grid, .workspace-grid { gap: var(--space-sm); }
  .score-card { padding: var(--space-sm); }
  .score-card > strong { font-size: 20px; }
  .score-card > .drop-value { font-size: 16px; }
  .section-header { flex-wrap: wrap; }
  .job-row { flex-wrap: wrap; }
  .job-main { flex-basis: calc(100% - 30px); }
  .job-arrow { margin-left: auto; }
  .enqueue-controls { flex-direction: column; }
  .query-actions { flex-wrap: wrap; }
}
</style>
