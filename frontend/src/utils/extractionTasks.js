const TERMINAL = new Set(['completed', 'partial', 'failed'])
const STORAGE_KEY = 'learnhub-document-tasks-v1'
const UUID = /^[a-f\d]{8}-[a-f\d]{4}-[a-f\d]{4}-[a-f\d]{4}-[a-f\d]{12}$/i

export function extractionTasksFromRoute(query = {}) {
  const tasks = {}
  if (typeof query.mediaJob === 'string' && UUID.test(query.mediaJob)
      && typeof query.read === 'string' && /^[1-9]\d*$/.test(query.read)) {
    tasks.media = { id: query.mediaJob.toLowerCase(), fileId: query.read, status: 'queued' }
  }
  if (typeof query.migrationJob === 'string' && UUID.test(query.migrationJob)) {
    tasks.migration = { id: query.migrationJob.toLowerCase(), status: 'queued' }
  }
  return tasks
}

export const extractionTaskFinished = job => TERMINAL.has(job?.status)
export function extractionTaskLabel(job) {
  return { queued: '排队中', running: '处理中', completed: '已完成', partial: '部分完成，仍有内容待处理', failed: '处理失败' }[job?.status] || '等待状态'
}

const PAGE_STAGE_LABELS = { preparing: '准备读取原件', extracting: '识别正文', media: '处理公式与图片', saving: '保存正文并更新索引' }
const NUMBER = new Intl.NumberFormat('zh-CN')

/** A staged scan is not published coverage: keep these counts separate in the UI. */
export function pendingScanCoverage(source) {
  if (!source?.pendingUpgrade || !source.scanProgress) return null
  const scan = source.scanProgress
  return {
    ...scan.report,
    completedPages: scan.completedPages ?? scan.report?.completedPages,
    limitReasons: scan.limitReasons ?? scan.report?.limitReasons,
  }
}

export function extractionPageProgress(job) {
  const scan = job?.scanMode === true || job?.pendingUpgrade === true
  const candidate = pendingScanCoverage(job)
  const { stage, currentPage, completedPages, totalPages } = job?.pageProgress || candidate || {}
  const validTotal = Number.isInteger(totalPages) && totalPages > 0
  const validCount = validTotal && Number.isInteger(completedPages) && completedPages >= 0 && completedPages <= totalPages
  const pages = validCount ? `${scan ? '扫描通过' : '可用'} ${NUMBER.format(completedPages)} / ${NUMBER.format(totalPages)} 页` : ''
  const current = !extractionTaskFinished(job) && stage === 'extracting' && Number.isInteger(currentPage) && currentPage > 0
    ? `正在处理原文第 ${NUMBER.format(currentPage)} 页` : ''
  const step = job?.stage || (!extractionTaskFinished(job) ? scan && stage === 'extracting' ? '逐页扫描' : PAGE_STAGE_LABELS[stage] : '')
  return {
    pages,
    detail: [step, !job?.stage && current, job?.pendingUpgrade ? '新结果待发布，原有正文仍可用' : ''].filter(Boolean).join(' · '),
    percentage: validCount ? Math.floor(completedPages / totalPages * 100) : null,
  }
}

/** Stops only local polling. It never cancels or re-submits a server-side job. */
export function createExtractionTaskMonitor({ load, onState, onFinished, onError, interval = 2000, schedule = setTimeout, unschedule = clearTimeout }) {
  let generation = 0, timer = null, disposed = false
  function stop() { generation++; if (timer !== null) unschedule(timer); timer = null }
  async function poll(job, current) {
    try {
      const latest = await load(job.id)
      if (disposed || current !== generation) return
      const result = { ...job, ...latest }
      onState(result)
      if (extractionTaskFinished(result)) await onFinished?.(result)
      else timer = schedule(() => { timer = null; poll(result, current) }, interval)
    } catch (error) { if (!disposed && current === generation) onError?.(error, job) }
  }
  return {
    async watch(job) {
      if (disposed || !job?.id) return
      const current = ++generation
      if (timer !== null) unschedule(timer)
      timer = null
      onState(job)
      if (extractionTaskFinished(job)) await onFinished?.(job)
      else await poll(job, current)
    },
    stop,
    dispose() { disposed = true; stop() },
  }
}

export function readExtractionTasks(storage = globalThis.localStorage) {
  try {
    const value = JSON.parse(storage?.getItem(STORAGE_KEY) || '{}')
    return Object.fromEntries(['media', 'migration', 'structure', 'extraction'].flatMap(kind => {
      const item = value?.[kind]
      return item && typeof item.id === 'string' && /^[\w-]{1,200}$/.test(item.id) && !extractionTaskFinished(item)
        ? [[kind, item]] : []
    }))
  } catch { return {} }
}
export function rememberExtractionTask(kind, job, storage = globalThis.localStorage) {
  try {
    const tasks = readExtractionTasks(storage)
    if (job && !extractionTaskFinished(job)) tasks[kind] = { id: String(job.id), fileId: job.fileId, status: job.status }
    else delete tasks[kind]
    storage?.setItem(STORAGE_KEY, JSON.stringify(tasks))
  } catch { /* Polling still works when browser storage is unavailable. */ }
}
