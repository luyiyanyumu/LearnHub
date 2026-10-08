/** Route values are data, never selectors; malformed or repeated query parameters are ignored. */
export function wikiRouteTarget(query) {
  if (query?.tab !== 'wiki' || typeof query.topic !== 'string' || !/^[\w-]{1,200}$/.test(query.topic)) return null
  return {
    topicKey: query.topic,
    sectionKey: typeof query.section === 'string' ? query.section : '',
    heading: typeof query.heading === 'string' ? query.heading : '',
  }
}

function headingText(value) {
  return String(value ?? '').replace(/\s+/g, ' ').trim()
}

/** Preserve the existing Markdown anchor while identifying the parser's one-based heading order. */
export function wikiHeadingId({ index, currentToken, text }) {
  currentToken?.attrSet('data-wiki-section', `section-${index}`)
  return currentToken?.attrGet('id') || text || ''
}

/** Exact heading text first; a breadcrumb path can fall back to its last heading. */
export function findWikiHeading(headings, target) {
  const list = Array.from(headings ?? [])
  const heading = headingText(target?.heading)
  const leaf = headingText(heading.split(/\s+[›>]\s+|\s+\/\s+/).at(-1))
  const section = list.find(item => item.getAttribute?.('data-wiki-section') === target?.sectionKey)
  if (section && (!heading || headingText(section.textContent) === heading || headingText(section.textContent) === leaf)) return section
  if (!heading) return null
  const exact = list.find(item => headingText(item.textContent) === heading)
  if (exact) return exact
  return leaf !== heading ? list.find(item => headingText(item.textContent) === leaf) ?? null : null
}
