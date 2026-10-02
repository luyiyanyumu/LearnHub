const DAY_MS = 86400000

function levelFor(count, max) {
  if (!count || !max) return 0
  if (max <= 3) return Math.min(4, count)
  if (count >= Math.ceil(max * 0.75)) return 4
  if (count >= Math.ceil(max * 0.5)) return 3
  if (count >= Math.ceil(max * 0.25)) return 2
  return 1
}

/** Dates are calendar keys, so UTC arithmetic keeps the grid stable across timezones and DST. */
export function buildActivityCalendar(year, days = [], max = 0) {
  const first = Date.UTC(year, 0, 1)
  const last = Date.UTC(year, 11, 31)
  const offset = (new Date(first).getUTCDay() + 6) % 7
  const start = first - offset * DAY_MS
  const weekCount = Math.ceil(((last - start) / DAY_MS + 1) / 7)
  const counts = new Map(days.map((item) => [item.date, Number(item.count) || 0]))
  const cells = Array.from({ length: weekCount * 7 }, (_, index) => {
    const date = new Date(start + index * DAY_MS)
    const key = date.toISOString().slice(0, 10)
    const outside = date.getUTCFullYear() !== year
    const count = outside ? 0 : counts.get(key) || 0
    return { key, date: key, count, outside, level: levelFor(count, max) }
  })
  const labels = Array.from({ length: 12 }, (_, month) => ({
    month: `${month + 1}月`,
    week: Math.floor((Date.UTC(year, month, 1) - start) / (DAY_MS * 7)),
  }))
  return { cells, labels, weekCount }
}
