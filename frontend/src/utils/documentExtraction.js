export function extractionBlocks(extraction) {
  return (Array.isArray(extraction?.pages) ? extraction.pages : []).flatMap(page =>
    (Array.isArray(page.blocks) ? page.blocks : []).map((block, index) => ({ ...block,
      page: Number(block.page || page.page), width: block.width || page.width, height: block.height || page.height,
      coordinateUnit: page.coordinateUnit, method: block.quality === 'recognized' ? 'vision' : block.method || page.method,
      key: `${page.page}:${block.id || index}`,
      text: String(block.cleanText || block.rawText || block.text || ''),
      rejected: block.quality === 'rejected' || block.indexed !== true,
    })))
}

export function extractionStatus(extraction) {
  if (!extraction || extraction.status === 'not_processed') return { label: '尚无抽取报告', type: 'info' }
  if (['failed', 'error', 'rejected'].includes(extraction.status)) return { label: '抽取未通过', type: 'danger' }
  if (extraction.status === 'empty') return { label: extraction.report?.partial ? '暂无可检索正文，需核对' : '暂无可检索正文', type: 'warning' }
  if (extraction.report?.partial || extraction.report?.truncated) return { label: '部分完成', type: 'warning' }
  if (['ok', 'success', 'completed'].includes(extraction.status)) return { label: '抽取完成', type: 'success' }
  if (extraction.status === 'partial') return { label: '部分完成', type: 'warning' }
  return { label: '暂无可检索正文', type: 'info' }
}

export function confidenceLabel(value) {
  return typeof value === 'number' && Number.isFinite(value) && value >= 0 && value <= 1 ? `${Math.round(value * 100)}%` : '未提供'
}

const COUNT = new Intl.NumberFormat('zh-CN')
const validLimitAmount = amount => Number.isInteger(amount) && amount > 0
function pixelCount(amount) {
  return amount % 10000 === 0 ? `${COUNT.format(amount / 10000)} 万像素` : `${COUNT.format(amount)} 像素`
}

const LIMIT_HINTS = {
  document_capacity: () => '已达到单份资料的累计容量上限',
  page_pixels: amount => validLimitAmount(amount) ? `部分页面超过单页 ${pixelCount(amount)}上限` : '部分页面超过单页像素上限',
  batch_time: amount => validLimitAmount(amount) ? `本批达到 ${COUNT.format(amount)} 秒时限` : '本批达到处理时限',
  batch_pixels: amount => validLimitAmount(amount) ? `本批达到 ${pixelCount(amount)} 预算` : '本批达到像素预算',
  batch_ocr_pages: amount => validLimitAmount(amount) ? `本批达到 ${COUNT.format(amount)} 页 OCR 预算` : '本批达到 OCR 页数预算',
  batch_pages: amount => validLimitAmount(amount) ? `本批达到 ${COUNT.format(amount)} 页处理预算` : '本批达到页数预算',
  interrupted: () => '上次正文抽取中断',
  pixel_budget: () => '部分页面因图像像素超限待核对',
  media_limit: () => '本批公式与图片处理达到预算',
}

export function extractionLimitHints(coverage) {
  const limits = Array.isArray(coverage?.limitReasons) ? coverage.limitReasons : []
  return Object.entries(LIMIT_HINTS).flatMap(([code, describe]) => {
    const reason = limits.find(limit => limit?.code === code)
    return reason ? [{ code, text: describe(reason.amount) }] : []
  })
}

export function hasExtractionLimit(coverage, code) {
  return Array.isArray(coverage?.limitReasons) && coverage.limitReasons.some(limit => limit?.code === code)
}

export function extractionQualityLabel(value) {
  return { accepted: '质量通过', low_confidence: '识别置信度较低', garbled: '含有乱码', empty: '没有可用文字',
    non_text: '图片或非文字内容', uncertain_formula: '公式识别待核对', character_limit: '达到正文处理上限', rejected: '质量未通过',
    ocr_required: '扫描页需要 OCR 识别', page_furniture: '重复页眉或页脚（已排除）',
    vision_unavailable: '图片模型不可用，保留原图', vision_uncertain: '识别结果待核对', pending: '本次仍有内容待处理',
    failed: '处理失败，原件已保留', recognized: '媒体识别结果已保存', superseded_by_formula: '已由可靠公式替代' }[value] || '未建立检索索引'
}

export function extractionBlockTypeLabel(value) {
  return { title: '标题', heading: '小节', para: '段落', paragraph: '段落', text: '文字', formula: '公式', table: '表格',
    figure: '图片', bullet: '列表', authors: '作者', meta: '文档信息' }[value] || '文字块'
}

export function isManuallyReviewedFormula(block) {
  return block?.latexStatus === 'recognized' && block.latexMessage === '已人工核对'
}

export function extractionMediaLabel(block) {
  const state = block?.latexStatus
  if (block?.quality === 'vision_unavailable') return '图片模型不可用，原图保留'
  if (block?.quality === 'vision_uncertain') return '识别结果待核对'
  if (block?.quality === 'pending') return '仍有媒体待处理'
  if (block?.quality === 'superseded_by_formula') return '已由可靠公式替代'
  if (state === 'needs_review') return '公式转写待核对'
  if (isManuallyReviewedFormula(block)) return '已人工核对'
  if (state === 'recognized') return '视觉识别已保存'
  if (state === 'restored') return '公式已还原'
  if (state === 'partial') return '识别结果待核对'
  if (['failed', 'error'].includes(state) || /failed|error/.test(block?.quality || '')) return '处理失败，保留原图'
  if (state === 'described' || /described/.test(block?.quality || '') || block?.quality === 'recognized' && ['figure', 'image'].includes(block.type)) return '图片说明已生成'
  if (['formula', 'figure', 'image'].includes(block?.type)) return '原图已保留，待处理'
  return ''
}
