const REVISION = /^[a-f\d]{64}$/i
const PIPELINE = /^[A-Za-z0-9][A-Za-z0-9._:-]{0,99}$/

/** Geometry is measured from the top left of the displayed (already rotated) page. */
export function normalizeSourceLocation(value) {
  if (!value || typeof value !== 'object') return null
  const page = Number(value.page)
  if (!Number.isSafeInteger(page) || page < 1) return null
  const rect = Array.isArray(value.rect) && value.rect.length === 4 && value.rect.every(item => typeof item === 'number' || typeof item === 'string' && item.trim()) ? value.rect.map(Number) : null
  const validRect = rect?.every(Number.isFinite) && rect[0] >= 0 && rect[1] >= 0 && rect[2] > rect[0] && rect[3] > rect[1]
  const revision = typeof value.sourceRevision === 'string' && REVISION.test(value.sourceRevision) ? value.sourceRevision.toLowerCase() : ''
  const width = Number(value.width), height = Number(value.height)
  return { page, rect: validRect ? rect : null,
    width: Number.isFinite(width) && width > 0 ? width : null,
    height: Number.isFinite(height) && height > 0 ? height : null,
    sourceRevision: revision,
    blockId: typeof value.blockId === 'string' ? value.blockId : '',
    method: ['ocr', 'vision'].includes(value.method) ? value.method : 'text',
    coordinateUnit: value.coordinateUnit === 'px' ? 'px' : 'pt',
    ...(typeof value.pipelineVersion === 'string' && PIPELINE.test(value.pipelineVersion) ? { pipelineVersion: value.pipelineVersion } : {}),
  }
}

export function sourceLocatorFromRoute(query = {}) {
  if (typeof query.page !== 'string' || !/^[1-9]\d*$/.test(query.page)) return null
  return normalizeSourceLocation({ page: query.page,
    rect: typeof query.rect === 'string' ? query.rect.split(',') : null,
    sourceRevision: query.sourceRevision, blockId: query.blockId, pipelineVersion: query.sourcePipeline })
}

export function firstSourceLocation(hit) {
  return (Array.isArray(hit?.sourceLocations) ? hit.sourceLocations : []).map(normalizeSourceLocation).find(Boolean) || null
}

export function sourceLocationQuery(hit) {
  const location = firstSourceLocation(hit)
  if (!location) return ''
  const query = new URLSearchParams({ page: String(location.page) })
  if (location.rect && location.sourceRevision) query.set('rect', location.rect.join(','))
  if ((location.rect || location.blockId) && location.sourceRevision) query.set('sourceRevision', location.sourceRevision)
  if (location.blockId) query.set('blockId', location.blockId)
  if (location.pipelineVersion) query.set('sourcePipeline', location.pipelineVersion)
  return `&${query}`
}

/** Never draw old coordinates over a replacement source, even if its page count matches. */
export function resolveSourceLocation(value, extraction) {
  const location = normalizeSourceLocation(value)
  if (!location) return { location: null, message: '' }
  const pageOnly = { ...location, rect: null, blockId: '' }
  if (!location.rect && !location.blockId) return { location: pageOnly, message: '' }
  const revision = extraction?.sourceSha256
  if (!location.sourceRevision || typeof revision !== 'string' || !REVISION.test(revision)) {
    return { location: pageOnly, message: '已定位原文页；缺少可核验的来源版本，未高亮区域。重新抽取后可获得区域定位。' }
  }
  if (location.sourceRevision !== revision.toLowerCase()) {
    return { location: pageOnly, message: '来源版本已变化，已定位原文页并取消旧区域高亮。请重新检索以获取最新位置。' }
  }
  if (!location.rect) {
    if (/(?:^|-)v3-layout$/.test(extraction?.pipelineVersion || '') && location.pipelineVersion !== extraction.pipelineVersion) {
      return { location: pageOnly, message: '抽取结构已更新，请重新检索定位。' }
    }
    return { location, message: '' }
  }
  const page = extraction.pages?.find(item => Number(item.page) === location.page)
  const width = Number(page?.width), height = Number(page?.height)
  if (!(width > 0 && height > 0) || !sourceRegionStyle({ ...location, width, height })) {
    return { location: pageOnly, message: '已定位原文页；当前报告缺少有效页面区域，未显示高亮。' }
  }
  return { location: { ...location, width, height }, message: '' }
}

/** Percentages remain correct at any zoom/DPR and for 90°/270° display dimensions. */
export function sourceRegionStyle(value) {
  const location = normalizeSourceLocation(value)
  if (!location?.rect || !location.width || !location.height) return null
  const [x0, y0, x1, y1] = location.rect
  const left = Math.min(x0, location.width), top = Math.min(y0, location.height)
  const right = Math.min(x1, location.width), bottom = Math.min(y1, location.height)
  if (right <= left || bottom <= top) return null
  return { left: `${left / location.width * 100}%`, top: `${top / location.height * 100}%`,
    width: `${(right - left) / location.width * 100}%`, height: `${(bottom - top) / location.height * 100}%` }
}
