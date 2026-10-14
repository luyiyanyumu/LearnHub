import assert from 'node:assert/strict'
import test from 'node:test'
import { markdownBoldInput, markdownBoldRanges } from './markdownBold.js'

test('Chinese adjacent and emoji bold use parser-confirmed source positions', () => {
  for (const raw of ['前**中文**', '共用一个**最多 20 项**', '🙂**合成**', '**开头**']) {
    const match = markdownBoldInput(raw)
    assert.ok(match, raw)
    assert.equal(match.text, raw.slice(match.index))
    assert.equal(match.replaceWith, raw.slice(match.index + 2, -2))
  }
  const raw = '前**中文**后**第二段**'
  assert.deepEqual(markdownBoldRanges(raw).map(range => raw.slice(range.bodyStart, range.bodyEnd)), ['中文', '第二段'])
  assert.equal(markdownBoldInput('前**中文**后'), null)
})

test('escaped stars, triple emphasis, code and completed formulas are protected', () => {
  for (const raw of [String.raw`\**转义**`, '***三重***', '****四重****', '`**代码**`', '$**公式**$', String.raw`\(**公式**\)`, '** 空格 **', '**未闭合']) {
    assert.equal(markdownBoldRanges(raw).length, 0, raw)
    assert.equal(markdownBoldInput(raw), null, raw)
  }
})

test('unfinished source code/formulas and complex content do not trigger bold input', () => {
  for (const raw of ['`**代码**', '` **代码**', '$**公式**', '$$**公式**', String.raw`\(**公式**`, String.raw`\[**公式**`, '**$x$**', '**`code`**', '**a **inner** b**']) {
    assert.equal(markdownBoldInput(raw), null, raw)
  }
  assert.ok(markdownBoldInput('`code` 后**加粗**'))
  assert.ok(markdownBoldInput('$x$ 后**加粗**'))
  assert.ok(markdownBoldInput(String.raw`\(x\) 后**加粗**`))
})
