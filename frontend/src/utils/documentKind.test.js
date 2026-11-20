import test from 'node:test'
import assert from 'node:assert/strict'
import { documentKind } from './documentKind.js'

test('uploaded Word and Markdown original files use their native reading paths', () => {
  for (const ext of ['doc', '.DOCX', 'docm']) assert.equal(documentKind({ ext }), 'word')
  for (const ext of ['md', 'MARKDOWN']) assert.equal(documentKind({ ext }), 'markdown')
  assert.equal(documentKind({ originName: 'old-file.MD' }), 'markdown')
  assert.equal(documentKind({ ext: 'pdf', originName: 'renamed.docx' }), 'pdf')
  assert.equal(documentKind({ ext: 'zip' }), 'other')
  assert.equal(documentKind(null), 'other')
})

test('multi-frame TIFF sources use the image reader and preserve filename fallback', () => {
  assert.equal(documentKind({ ext: 'tif' }), 'image')
  assert.equal(documentKind({ ext: '.TIFF' }), 'image')
  assert.equal(documentKind({ originName: '合成扫描件.TIFF' }), 'image')
})
