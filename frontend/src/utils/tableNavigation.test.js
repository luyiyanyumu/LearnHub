import assert from 'node:assert/strict'
import test from 'node:test'
import { nextTableCell } from './tableNavigation.js'

test('Enter moves to the same column in the next row', () => {
  assert.deepEqual(nextTableCell({ row: 0, col: 2, width: 3, height: 4 }, 1), { row: 1, col: 2 })
})

test('upward navigation stops at table edges', () => {
  assert.deepEqual(nextTableCell({ row: 2, col: 1, width: 3, height: 4 }, -1), { row: 1, col: 1 })
  assert.equal(nextTableCell({ row: 0, col: 1, width: 3, height: 4 }, -1), null)
  assert.equal(nextTableCell({ row: 3, col: 1, width: 3, height: 4 }, 1), null)
})
