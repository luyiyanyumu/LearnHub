import test from 'node:test'
import assert from 'node:assert/strict'
import { confidenceLabel, extractionBlocks, extractionStatus, extractionQualityLabel } from './documentExtraction.js'

test('unprocessed and partial reports cannot be presented as a successful extraction', () => {
  assert.equal(extractionStatus(null).type, 'info')
  assert.equal(extractionStatus({ status: 'not_processed', report: null }).label, '尚无抽取报告')
  assert.equal(extractionStatus({ status: 'ok', report: { partial: true } }).label, '部分完成')
  assert.equal(extractionStatus({ status: 'ok', report: { truncated: true } }).type, 'warning')
  assert.equal(extractionStatus({ status: 'failed' }).type, 'danger')
  assert.equal(extractionStatus({ status: 'failed', report: { partial: true } }).type, 'danger')
  assert.equal(extractionStatus({ status: 'empty', report: { partial: true } }).label, '暂无可检索正文，需核对')
  assert.equal(extractionStatus({ status: 'ok', report: { partial: false } }).type, 'success')
})

test('accepted and rejected blocks both retain original evidence, coordinates and confidence', () => {
  const blocks = extractionBlocks({ text: 'not rendered as one document', pages: [{ page: 2, width: 1000, height: 2000, coordinateUnit: 'px', method: 'ocr', blocks: [
    { id: 'accepted', rawText: '合成 原始', cleanText: '合成原始', indexed: true, quality: 'accepted', confidence: .95, rect: [10, 20, 30, 40] },
    { id: 'rejected', rawText: '合成低质量', cleanText: '', indexed: false, quality: 'rejected', confidence: .2 },
    { id: 'unknown', rawText: '合成未知' },
  ] }] })
  assert.deepEqual(blocks.map(b => b.rejected), [false, true, true])
  assert.deepEqual(blocks.map(b => b.text), ['合成原始', '合成低质量', '合成未知'])
  assert.equal(blocks[0].rawText, '合成 原始')
  assert.equal(blocks[0].width, 1000)
  assert.equal(blocks[0].coordinateUnit, 'px')
  assert.deepEqual(blocks[0].rect, [10, 20, 30, 40])
  assert.equal(confidenceLabel(blocks[0].confidence), '95%')
  for (const value of [null, undefined, NaN, -1, 2, '0.9']) assert.equal(confidenceLabel(value), '未提供')
  assert.equal(confidenceLabel(0), '0%')
  assert.equal(extractionQualityLabel('page_furniture'), '重复页眉或页脚（已排除）')
  assert.equal(extractionQualityLabel('ocr_required'), '扫描页需要 OCR 识别')
})
