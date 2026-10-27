import { appendNoteSection, applySectionPatch, demoteSectionHeadings, splitNoteSections, SECTION_REWRITE_MAX } from './noteSections.js'

export function mergeSectionTargets(source, location) {
  if (location?.action === 'covered' && !Array.isArray(location?.targets)) return []
  const sections = splitNoteSections(source).sections
  const raw = Array.isArray(location?.targets) ? location.targets : [location]
  if (!raw.length || raw.length > 12) throw new Error('分节目标需为 1～12 项，请缩小本次修改范围')
  const seen = new Set()
  return raw.filter(item => item?.action !== 'covered').map(item => {
    if (!['merge', 'append'].includes(item?.action)) throw new Error('模型没有给出有效的分节动作')
    const index = Number(item.index || 0)
    const section = item.action === 'merge' && Number.isInteger(index) && index > 0 ? sections[index - 1] : null
    if (item.action === 'merge' && !section) throw new Error('模型定位的小节不存在，请重新生成预览')
    const afterSection = item.action === 'append' && Number.isInteger(index) && index > 0 ? sections[index - 1] : null
    if (item.action === 'append' && (!Number.isInteger(index) || index < 0 || (index > 0 && !afterSection))) throw new Error('新增小节的放置位置不存在')
    const heading = section?.heading || String(item.heading || '').trim()
    if (!heading) throw new Error('新增小节缺少标题，请重新生成预览')
    const key = section ? `section:${index}` : `new:${heading}`
    if (seen.has(key)) throw new Error('模型重复分派了同一小节，请重新生成预览')
    seen.add(key)
    return { ...item, section, afterSection, heading, mode: section && section.size > SECTION_REWRITE_MAX ? 'insert' : 'rewrite' }
  })
}

/** Bounded independent generation; a failed part cancels siblings and exposes no partial patch. */
export async function generateSectionBatch(targets, generate, { signal, onProgress = () => {} } = {}) {
  const controller = new AbortController()
  const abort = () => controller.abort()
  if (signal?.aborted) abort()
  else signal?.addEventListener('abort', abort, { once: true })
  const results = new Array(targets.length)
  let next = 0, completed = 0, failure
  try {
    const workers = Array.from({ length: Math.min(3, targets.length) }, async () => {
      while (!controller.signal.aborted && next < targets.length) {
        const index = next++
        try {
          const value = await generate(targets[index], index, controller.signal)
          if (controller.signal.aborted) return
          if (!String(value || '').trim()) throw new Error(`「${targets[index].heading}」未生成有效正文`)
          results[index] = String(value)
          onProgress(++completed, targets.length)
        } catch (error) {
          if (!failure) failure = error
          controller.abort()
        }
      }
    })
    await Promise.all(workers)
    if (failure) throw failure
    if (controller.signal.aborted) throw new Error('已取消处理')
    return results
  } finally { signal?.removeEventListener('abort', abort) }
}

export function composeSectionBatch(source, patches) {
  let next = String(source || '')
  const placed = patches.map((patch, index) => ({ ...patch, index }))
    .filter(patch => patch.section || patch.afterSection)
    .sort((a, b) => (b.afterSection?.end ?? b.section.bodyStart) - (a.afterSection?.end ?? a.section.bodyStart)
      || b.index - a.index)
  for (const patch of placed) {
    if (patch.section) next = applySectionPatch(next, patch.section, patch.after)
    else {
      const anchor = patch.afterSection
      if (next.slice(anchor.start, anchor.end) !== anchor.text) throw new Error('新增小节的位置已变化，请重新生成预览')
      const body = demoteSectionHeadings(String(patch.after || '').trim(), 2)
      next = next.slice(0, anchor.end) + `\n\n## ${patch.heading}\n\n${body}` + next.slice(anchor.end)
    }
  }
  for (const patch of patches.filter(p => !p.section && !p.afterSection)) next = appendNoteSection(next, patch.heading, patch.after)
  return next
}
