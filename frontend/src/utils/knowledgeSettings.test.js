import assert from 'node:assert/strict'
import test from 'node:test'
import { diagnosticNumber, diagnosticPercent, knowledgeChanges, knowledgeValues, validateKnowledgeValues } from './knowledgeSettings.js'

const fields = [
  { key: 'chunkTarget', label: '目标长度', type: 'number', min: 200, max: 4000, step: 1, default: 800 },
  { key: 'chunkOverlap', label: '重叠长度', type: 'number', min: 0, max: 1000, step: 1, default: 120 },
  { key: 'chunkMax', label: '长块长度', type: 'number', min: 200, max: 6000, step: 1, default: 1200 },
  { key: 'vectorEnabled', type: 'boolean', default: true },
]
const defaults = Object.fromEntries(fields.map(f => [f.key, f.default]))

test('settings normalize false strings, preserve zero and submit only actual changes', () => {
  const baseline = knowledgeValues({ fields, defaults, values: { vectorEnabled: 'false', chunkOverlap: 0 } })
  assert.equal(baseline.vectorEnabled, false)
  assert.equal(baseline.chunkOverlap, 0)
  assert.deepEqual(knowledgeChanges({ ...baseline, chunkTarget: 900 }, baseline, fields), { chunkTarget: 900 })
})

test('invalid overlapping chunks are rejected before saving while a blank resets the field default', () => {
  assert.equal(validateKnowledgeValues({ ...defaults, chunkOverlap: 800 }, defaults, fields).length, 1)
  assert.equal(validateKnowledgeValues({ ...defaults, chunkTarget: 800.2 }, defaults, fields).length, 1)
  assert.deepEqual(validateKnowledgeValues({ ...defaults, chunkTarget: null }, defaults, fields), [])
})

test('negative/no-ground-truth diagnostics do not turn missing metrics into a successful score', () => {
  assert.equal(diagnosticPercent(null), '—')
  assert.equal(diagnosticPercent(0), '0.0%')
  assert.equal(diagnosticNumber(undefined), '—')
  assert.equal(diagnosticNumber(0), '0.000')
})
