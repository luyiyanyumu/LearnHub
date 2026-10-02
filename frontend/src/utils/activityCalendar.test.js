import test from 'node:test'
import assert from 'node:assert/strict'
import { buildActivityCalendar } from './activityCalendar.js'

test('month labels align with their first day instead of stacking at January', () => {
  const calendar = buildActivityCalendar(2026)
  assert.equal(calendar.labels[0].week, 0)
  assert.equal(calendar.labels[1].week, 4)
  assert.equal(calendar.labels[8].week, 35)
  assert.equal(calendar.labels[11].week, 48)
  for (const label of calendar.labels) {
    const month = Number.parseInt(label.month, 10)
    const index = calendar.cells.findIndex((cell) => cell.date === `2026-${String(month).padStart(2, '0')}-01`)
    assert.equal(label.week, Math.floor(index / 7))
  }
})

test('leap days and Monday week boundaries retain the correct activity date', () => {
  const calendar = buildActivityCalendar(2024, [{ date: '2024-02-29', count: 7 }], 7)
  assert.equal(calendar.cells.filter((cell) => !cell.outside).length, 366)
  assert.equal(calendar.cells[0].date, '2024-01-01')
  const leapDay = calendar.cells.find((cell) => cell.date === '2024-02-29')
  assert.equal(leapDay.count, 7)
  assert.equal(leapDay.level, 4)
})

test('year boundary placeholders stay empty and do not count adjacent years', () => {
  const calendar = buildActivityCalendar(2026, [{ date: '2025-12-29', count: 9 }], 9)
  assert.equal(calendar.cells[0].date, '2025-12-29')
  assert.equal(calendar.cells[0].outside, true)
  assert.equal(calendar.cells[0].count, 0)
  assert.equal(calendar.cells.filter((cell) => !cell.outside).length, 365)
})
