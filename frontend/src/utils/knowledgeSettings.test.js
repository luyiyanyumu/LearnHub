import assert from 'node:assert/strict'
import test from 'node:test'
import { diagnosticNumber, diagnosticPercent, knowledgeChanges, knowledgeChunkingHint, knowledgeValues, validateKnowledgeValues } from './knowledgeSettings.js'

const fields = [
  { key: 'chunkStrategy', label: '切割方式', type: 'select', default: 'structure', options: [{ value: 'structure', label: '结构切割' }, { value: 'semantic_long', label: '长资料语义切割' }] },
  { key: 'chunkTarget', label: '目标长度', type: 'number', min: 200, max: 4000, step: 1, default: 800 },
  { key: 'chunkOverlap', label: '重叠长度', type: 'number', min: 0, max: 1000, step: 1, default: 120 },
  { key: 'chunkMax', label: '长块长度', type: 'number', min: 200, max: 6000, step: 1, default: 1200 },
  { key: 'vectorEnabled', type: 'boolean', default: true },
  { key: 'semanticMinChars', label: '语义切割起始长度', type: 'number', min: 2000, max: 100000, step: 100, default: 4000 },
  { key: 'semanticBreakpointPercentile', label: '语义边界百分位', type: 'number', min: 50, max: 99, step: 1, default: 85 },
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

test('semantic chunking settings validate enum, range and integer inputs before saving', () => {
  assert.deepEqual(validateKnowledgeValues({ ...defaults, chunkStrategy: 'semantic_long', semanticMinChars: 100000, semanticBreakpointPercentile: 99 }, defaults, fields), [])
  for (const patch of [{ chunkStrategy: 'unknown' }, { semanticMinChars: 1999 }, { semanticMinChars: 100001 }, { semanticBreakpointPercentile: 49 }, { semanticBreakpointPercentile: 100 }, { semanticBreakpointPercentile: 85.5 }]) {
    assert.equal(validateKnowledgeValues({ ...defaults, ...patch }, defaults, fields).length, 1)
  }
  assert.deepEqual(validateKnowledgeValues({ ...defaults, chunkStrategy: '', semanticMinChars: null, semanticBreakpointPercentile: '' }, defaults, fields), [])
})

test('chunking help distinguishes the file-only semantic scope and index-build cost', () => {
  const structural = knowledgeChunkingHint(defaults)
  const semantic = knowledgeChunkingHint({ ...defaults, chunkStrategy: 'semantic_long' })
  assert.match(structural, /完整保留代码、公式与表格/)
  assert.match(semantic, /仅作用于达到起始长度的资料库文件/)
  assert.match(semantic, /笔记、速查卡和短资料保留结构切割/)
  assert.match(semantic, /增加重建耗时/)
  assert.match(semantic, /保存后需重建索引/)
})
