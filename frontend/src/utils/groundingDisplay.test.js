import test from 'node:test'
import assert from 'node:assert/strict'
import { groundingDisplay } from './groundingDisplay.js'

test('absent checks remain hidden instead of implying successful verification', () => {
  assert.equal(groundingDisplay(null), null)
  assert.equal(groundingDisplay(undefined), null)
})

test('current and legacy complete checks show verified evidence only when no claims are unsupported', () => {
  const expected = { status: 'verified', label: '依据已核对', note: '符合当前材料', unsupported: [] }
  assert.deepEqual(groundingDisplay({ checked: true, grounded: true, unsupported: [], note: ' 符合当前材料 ' }), expected)
  assert.deepEqual(groundingDisplay({ grounded: true, unsupported: [], note: '符合当前材料' }), expected)
})

test('skipped checks cannot appear verified even when the underlying grounded flag is true', () => {
  assert.deepEqual(groundingDisplay({ checked: false, grounded: true, unsupported: [], note: '核验模型暂时不可用' }),
    { status: 'unchecked', label: '未完整核验', note: '核验模型暂时不可用', unsupported: [] })
  assert.equal(groundingDisplay({ checked: false }).status, 'unchecked')
  assert.ok(groundingDisplay({ checked: false }).note)
})

test('unsupported claims override a contradictory grounded=true response and keep readable details', () => {
  const result = groundingDisplay({ checked: true, grounded: true, unsupported: [' 缺少数据的结论 ', '缺少数据的结论'], note: '请核对原文' })
  assert.equal(result.status, 'unsupported')
  assert.equal(result.label, '部分结论缺少依据')
  assert.deepEqual(result.unsupported, ['缺少数据的结论'])
  assert.equal(result.note, '请核对原文')
  assert.equal(groundingDisplay({ grounded: false, unsupported: [], note: '' }).status, 'unsupported')
})

test('unknown result shapes and nonboolean flags never imply a passed check', () => {
  for (const result of [{}, [], true, 'true', 1, { checked: 'true', grounded: true, unsupported: [] },
    { checked: null, grounded: true, unsupported: [] }, { checked: true, grounded: 'true', unsupported: [] },
    { grounded: true }, { grounded: true, unsupported: 'none' }, { grounded: true, unsupported: [null] },
    { grounded: true, unsupported: [''] }, { grounded: true, unsupported: [], note: {} }]) {
    assert.equal(groundingDisplay(result).status, 'unchecked')
    assert.equal(groundingDisplay(result).label, '未完整核验')
  }
})

test('claim and note text stays plain data for escaped Vue interpolation', () => {
  const result = groundingDisplay({ checked: true, grounded: false, unsupported: ['<img src=x onerror=alert(1)>'], note: '<script>text</script>' })
  assert.equal(result.note, '<script>text</script>')
  assert.deepEqual(result.unsupported, ['<img src=x onerror=alert(1)>'])
})

test('a generated Wiki page and its source ids alone cannot imply verified original evidence', () => {
  const check = { checked: true, grounded: true, unsupported: [] }
  const wiki = { type: 'wiki', id: 8, generatedGuide: true, sourceRefs: ['note:1'] }
  assert.equal(groundingDisplay(check, [wiki]).status, 'unchecked')
  assert.equal(groundingDisplay(check, [{ sourceType: 'wiki', sourceId: 8 }, { type: 'graph' }]).status, 'unchecked')
  assert.equal(groundingDisplay(check, [wiki, { type: 'note', id: 1, channels: ['wiki'] }]).status, 'verified')
  assert.equal(groundingDisplay({ ...check, grounded: false, unsupported: ['无原文依据'] }, [wiki]).status, 'unsupported')
})
