import test from 'node:test'
import assert from 'node:assert/strict'
import { highlightCodeBlockLines, loadCodeBlockLanguage } from './codeBlockHighlight.js'

test('static reading preserves exact source, HTML text, blank lines, and multiline syntax tokens', async () => {
  const source = 'const markup = "<img src=x onerror=alert(1)>";\n/* first\nsecond */\n\n'
  const support = await loadCodeBlockLanguage('javascript')
  const lines = highlightCodeBlockLines(source, support)
  assert.equal(lines.map(line => line.map(token => token.text).join('')).join('\n'), source)
  assert.equal(lines.length, 5)
  assert.ok(lines[0].some(token => token.className.includes('tok-keyword') && token.text === 'const'))
  assert.ok(lines[0].some(token => token.className.includes('tok-string') && token.text.includes('<img')))
  assert.ok(lines[1].some(token => token.className.includes('tok-comment')))
  assert.ok(lines[2].some(token => token.className.includes('tok-comment')))
  assert.deepEqual(lines[3], [])
  assert.deepEqual(lines[4], [])
})

test('reading and editing share lazy language support and unknown languages retain readable text', async () => {
  const [first, second] = await Promise.all([loadCodeBlockLanguage('PYTHON'), loadCodeBlockLanguage('python')])
  assert.strictEqual(first, second)
  const source = 'def sample():\n    return True'
  const tokens = highlightCodeBlockLines(source, first).flat()
  assert.ok(tokens.some(token => token.className.includes('tok-keyword') && token.text === 'def'))
  const unknown = await loadCodeBlockLanguage('synthetic-language')
  assert.equal(unknown, null)
  assert.deepEqual(highlightCodeBlockLines('<sample>\n\n', unknown), [[{ text: '<sample>', className: '' }], [], []])
  assert.deepEqual(highlightCodeBlockLines('', unknown), [[]])
  for (const language of ['constructor', '__proto__', 'toString']) {
    assert.equal(await loadCodeBlockLanguage(language), null)
  }
})
